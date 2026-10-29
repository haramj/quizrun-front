package com.quizrun.room;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.quizrun.room.Dtos.*;
import com.quizrun.room.Room.ChatMessage;
import com.quizrun.room.Room.Phase;
import com.quizrun.room.Room.Prepare;
import com.quizrun.room.Room.Player;
import com.quizrun.room.Room.Question;

/**
 * 게임 규칙 전부를 담당한다.
 *
 * <p>핵심 설계(수업 Day4 무상태 설계): <b>이 클래스는 상태를 들고 있지 않는다.</b>
 * 방 상태는 {@link RoomStore}(운영: Cloud DB for Cache)에만 있고, 한 방의 변경은 {@link RoomLocks}로 직렬화한다.
 * 타이머 스레드도 없다 — 모든 요청이 방을 읽은 뒤 {@link #advance}로 "마감 시각이 지난 단계"를 따라잡는다.
 * 그래서 어느 서버가 요청을 받아도 결과가 같고, 서버는 언제든 늘리거나 없앨 수 있다.
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    static final long COUNTDOWN_MS = 5000;
    /** 문제 준비에 실패한 방은 이 시간이 지나면 정리한다. */
    static final long FAILED_ROOM_TTL_MS = 10 * 60 * 1000;
    static final long REVEAL_MS = 3500;
    /**
     * 문제 준비가 이 시간을 넘기면 실패로 본다. 준비 작업은 방을 만든 서버의 스레드에서 도는데, 그 서버가 죽거나 교체되면
     * 방이 영원히 preparing에 남는다 — 어느 서버든 이 시간이 지난 방을 발견하면 실패 처리한다(수업 Day4: 서버는 언제든 사라진다).
     * 위키 조회(최대 약 35초) + CLOVA 2회(각 최대 60초)를 감안한 값.
     */
    static final long PREPARE_TIMEOUT_MS = 300_000;
    private static final String[] COLORS = {"#d9ff43", "#b9a7ff", "#ff9b75", "#91d7f3", "#f3a7d6", "#7bddb1"};
    private static final Set<Integer> TIME_LIMITS = Set.of(5, 10, 15, 20, 30);
    private static final Set<Integer> QUESTION_COUNTS = Set.of(4, 6, 8, 10, 12);
    private static final long ONLINE_WINDOW_MS = 5000;
    private static final long CHAT_COOLDOWN_MS = 700;
    private static final int CHAT_HISTORY = 100;

    private final RoomStore store;
    private final RoomLocks locks;
    private final QuizComposer composer;
    private final QuestionReportService reports;
    private final Clock clock;
    private final Executor preparer;
    private final AvatarService avatars;

    /** preparer: 문제 준비(AI 생성)를 요청 스레드 밖에서 돌리는 실행기. 테스트에서는 동기/수동 실행기를 넣는다. */
    public RoomService(RoomStore store, RoomLocks locks, QuizComposer composer, QuestionReportService reports,
                       Clock clock, @Qualifier("quizPreparer") Executor preparer) {
        this(store, locks, composer, reports, clock, preparer, new AvatarService(clock));
    }

    @Autowired
    public RoomService(RoomStore store, RoomLocks locks, QuizComposer composer, QuestionReportService reports,
                       Clock clock, @Qualifier("quizPreparer") Executor preparer, AvatarService avatars) {
        this.store = store;
        this.locks = locks;
        this.composer = composer;
        this.reports = reports;
        this.clock = clock;
        this.preparer = preparer;
        this.avatars = avatars;
    }

    // ---- 조회 -------------------------------------------------------------------------

    public List<RoomSummary> listWaiting() {
        return store.all().stream()
                .filter(r -> r.phase == Phase.WAITING && r.prepare != Prepare.FAILED && !"friends".equals(r.visibility))
                .map(r -> new RoomSummary(r.code, r.title, r.keyword, r.players.size(), r.capacity,
                        r.total(), r.timeLimit, r.source, r.prepare.name().toLowerCase()))
                .toList();
    }

    /**
     * 방 상태 조회(프론트가 700ms마다 부른다). 단계가 바뀔 시각이 아니면 <b>방 잠금 없이</b> 읽는다:
     * 100명이 같은 방을 폴링해도 락에서 줄 서지 않는다. 접속 신호(touch)만 별도 키에 가볍게 기록한다.
     */
    public RoomState state(String code, String token) {
        Room r = load(code);
        Player p = player(r, token); // 토큰이 틀리면 여기서 400
        long now = now();
        if (!needsWrite(r, now)) {
            store.touch(code, p.id, now);
            return view(r, p, store.presence(code), now);
        }
        // 마감이 지났거나 준비 시간이 초과된 경우: 잠그고 따라잡는다. 이미 다른 요청이 처리했다면 저장하지 않는다.
        return locks.run(code, () -> {
            Room room = load(code);
            if (tick(room, now())) store.save(room);
            Player me = player(room, token);
            long t = now();
            store.touch(code, me.id, t);
            return view(room, me, store.presence(code), t);
        });
    }

    // ---- 방 생성 / 입장 / 시작 ---------------------------------------------------------

    public JoinResponse create(CreateRoomRequest b) {
        int capacity = b.capacity() == null ? 4 : integral(b.capacity(), "정원은 2~100명으로 설정해 주세요.");
        if (capacity < 2 || capacity > 100) throw ApiException.badRequest("정원은 2~100명으로 설정해 주세요.");

        int timeLimit = b.timeLimit() == null ? 10 : integral(b.timeLimit(), "제한시간은 5·10·15·20·30초 중 선택해 주세요.");
        if (!TIME_LIMITS.contains(timeLimit)) throw ApiException.badRequest("제한시간은 5·10·15·20·30초 중 선택해 주세요.");

        int count = b.count() != null && QUESTION_COUNTS.contains(b.count().intValue()) ? b.count().intValue() : 6;
        String keyword = cleanKeyword(b.keyword());
        String visibility = b.visibility() == null ? "public" : b.visibility();
        if (!Set.of("public", "friends").contains(visibility)) throw ApiException.badRequest("참가 방식을 확인해 주세요.");
        AvatarService.Photo photo = avatars.parse(b.avatar());
        String title = b.title() == null || b.title().isBlank() ? keyword + " 한 판 달려!" : b.title();
        title = title.substring(0, Math.min(50, title.length()));

        // 빠른 경로(내장 카테고리·AI 꺼짐)는 방 코드를 뽑기 전에 구성한다: 잘못된 입력은 즉시 오류로 돌려준다.
        boolean slow = composer.needsGeneration(keyword);
        QuizComposer.Quiz quick = slow ? null : composer.compose(keyword, count);

        // 방 코드는 서버가 여러 대여도 겹치면 안 된다 → 저장소의 원자적 "없을 때만 저장"으로 확정한다.
        Room room;
        Player host;
        do {
            room = new Room(SecureHexTokens.hex(3).toUpperCase(), title, keyword, capacity, timeLimit, count, now());
            host = addPlayer(room, b.name());
            host.avatar = avatars.save(photo);
            room.visibility = visibility;
            room.host = host.id;
            if (quick != null) room.applyQuiz(quick);
            else room.prepare = Prepare.PREPARING; // 느린 경로(AI 생성 15~30초): 방을 먼저 열고 문제는 백그라운드에서 만든다
        } while (!store.saveIfAbsent(room));

        if (slow) {
            final String code = room.code;
            try {
                preparer.execute(() -> prepare(code, keyword, count));
            } catch (RejectedExecutionException busy) {
                markPrepareFailed(code, "지금 요청이 많아요. 잠시 후 다시 시도해 주세요.");
            }
        }
        return new JoinResponse(room.code, host.token);
    }

    /**
     * 백그라운드에서 문제를 만들어 방에 채운다. 오래 걸리는 compose는 방 잠금 밖에서 돌리고,
     * 결과를 반영할 때만 잠근다. 준비 중에 시작이 요청됐으면 끝나는 즉시 카운트다운을 시작한다.
     */
    void prepare(String code, String keyword, int count) {
        QuizComposer.Quiz quiz;
        try {
            quiz = composer.compose(keyword, count);
        } catch (ApiException e) {
            markPrepareFailed(code, e.getMessage());
            return;
        } catch (Exception e) {
            log.error("문제 준비 중 예기치 못한 오류: room={}", code, e);
            markPrepareFailed(code, "문제를 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
            return;
        }
        try {
            locked(code, r -> {
                if (r.prepare != Prepare.PREPARING) return null;
                r.applyQuiz(quiz);
                if (r.startRequested) {
                    r.startRequested = false;
                    startCountdown(r);
                }
                return null;
            });
            log.info("문제 준비 완료: room={} {}문제 source={}", code, quiz.questions().size(), quiz.source());
        } catch (ApiException gone) {
            log.info("문제 준비 중 방이 사라짐: room={}", code); // 그 사이 만료됨
        }
    }

    private void markPrepareFailed(String code, String message) {
        try {
            locked(code, r -> {
                if (r.prepare == Prepare.PREPARING) {
                    r.prepare = Prepare.FAILED;
                    r.prepareError = message;
                    r.startRequested = false;
                }
                return null;
            });
            log.warn("문제 준비 실패: room={} {}", code, message);
        } catch (ApiException gone) {
            // 방이 이미 사라짐
        }
    }

    public JoinResponse join(String code, String name) {
        return join(code, name, null);
    }

    public JoinResponse join(String code, String name, String avatar) {
        return locked(code, r -> {
            if (r.prepare == Prepare.FAILED) throw ApiException.badRequest("문제 준비에 실패한 방이에요. 새로운 방에 참여해 주세요.");
            if (r.phase != Phase.WAITING) throw ApiException.badRequest("이미 출발한 레이스예요. 새로운 방에 참여해 주세요.");
            if (r.players.size() >= r.capacity) throw ApiException.badRequest("정원이 가득 찼어요.");
            AvatarService.Photo photo = avatars.parse(avatar);
            Player p = addPlayer(r, name);
            p.avatar = avatars.save(photo);
            if (r.players.size() == r.capacity) startCountdown(r);
            return new JoinResponse(r.code, p.token);
        });
    }

    public void start(String code, String token) {
        locked(code, r -> {
            Player p = player(r, token);
            if (!p.id.equals(r.host)) throw ApiException.badRequest("방장만 시작할 수 있어요.");
            startCountdown(r);
            return null;
        });
    }

    public void profile(String code, String token, String name, String avatar) {
        locked(code, r -> {
            Player p = player(r, token);
            String cleaned = name == null ? "" : name.trim();
            if (cleaned.isEmpty() || cleaned.length() > 16) throw ApiException.badRequest("닉네임은 1~16자로 입력해 주세요.");
            AvatarService.Photo photo = avatars.parse(avatar);
            String url = avatars.save(photo);
            p.name = cleaned;
            p.avatar = url;
            return null;
        });
    }

    // ---- 게임 진행 ---------------------------------------------------------------------

    public void answer(String code, String token, Integer choice) {
        locked(code, r -> {
            Player p = player(r, token);
            long now = now();
            if (r.phase != Phase.PLAYING || now >= r.deadline) throw ApiException.badRequest("답변 시간이 끝났어요.");
            if (p.answer != null) throw ApiException.badRequest("이미 답변을 제출했어요.");
            if (choice == null || choice < 0 || choice > 3) throw ApiException.badRequest("답변을 선택해 주세요.");
            p.answer = choice;
            p.answeredAt = now;
            if (r.players.values().stream().allMatch(x -> x.answer != null)) reveal(r, now);
            return null;
        });
    }

    /**
     * 이미 화면에 나온 문제를 신고한다. 한 참가자는 한 문제를 한 번만 신고할 수 있다.
     * 문제은행과 연결되지 않은 문제(내장 폴백)나 중복 신고는 false(기록 안 됨)를 돌려준다.
     */
    public boolean report(String code, String token, Integer index, String reason, String detail) {
        return locked(code, r -> {
            Player p = player(r, token);
            if (index == null || index < 0 || index >= r.total()) {
                throw ApiException.badRequest("신고할 문제를 선택해 주세요.");
            }
            boolean shown = index < r.index
                    || (index == r.index && (r.phase == Phase.PLAYING || r.phase == Phase.REVEAL));
            if (!shown) throw ApiException.badRequest("아직 공개되지 않은 문제는 신고할 수 없어요.");
            Long questionId = r.questionIds.get(index);
            if (questionId == null) return false;
            return reports.report(questionId, r.code + ":" + p.id, reason, detail).recorded();
        });
    }

    public void chat(String code, String token, String text) {
        locked(code, r -> {
            Player p = player(r, token);
            String body = text == null ? "" : text.trim();
            if (body.isEmpty() || body.length() > 200) throw ApiException.badRequest("메시지는 1~200자로 입력해 주세요.");
            long now = now();
            if (now - p.lastChat < CHAT_COOLDOWN_MS) throw ApiException.badRequest("잠깐만요! 조금 천천히 보내 주세요.");
            p.lastChat = now;
            r.messages.add(new ChatMessage(UUID.randomUUID().toString(), p.id, p.name, body, now));
            if (r.messages.size() > CHAT_HISTORY) r.messages.remove(0);
            return null;
        });
    }

    // ---- 상태 전이 (타이머 없이 마감 시각 기준으로 따라잡기) ------------------------------

    private void startCountdown(Room r) {
        if (r.phase != Phase.WAITING) return;
        if (r.prepare == Prepare.FAILED) throw ApiException.badRequest("문제 준비에 실패한 방이에요. 새로운 방을 만들어 주세요.");
        if (r.prepare == Prepare.PREPARING) {
            r.startRequested = true; // 문제가 준비되는 대로 자동으로 출발한다
            return;
        }
        r.phase = Phase.COUNTDOWN;
        r.index = 0;
        r.deadline = now() + COUNTDOWN_MS;
    }

    /** now 시각까지 도래한 모든 전이를 마감 시각 순서대로 적용한다. */
    void advance(Room r, long now) {
        while (r.deadline != null && now >= r.deadline) {
            long at = r.deadline;
            switch (r.phase) {
                case COUNTDOWN -> beginQuestion(r, at);
                case PLAYING -> reveal(r, at);
                case REVEAL -> {
                    r.index++;
                    if (r.index >= r.questions.size()) {
                        r.phase = Phase.FINISHED;
                        r.deadline = null;
                    } else {
                        beginQuestion(r, at);
                    }
                }
                default -> {
                    return;
                }
            }
        }
    }

    private void beginQuestion(Room r, long at) {
        r.phase = Phase.PLAYING;
        r.startedAt = at;
        r.deadline = at + r.timeLimit * 1000L;
        for (Player p : r.players.values()) {
            p.answer = null;
            p.answeredAt = 0;
            p.lastPoints = 0;
        }
    }

    private void reveal(Room r, long at) {
        if (r.phase != Phase.PLAYING) return;
        r.phase = Phase.REVEAL;
        int correct = r.questions.get(r.index).answer();
        for (Player p : r.players.values()) {
            if (p.answer != null && p.answer == correct) {
                p.lastPoints = Scoring.score(p.answeredAt - r.startedAt, r.timeLimit * 1000L);
                p.score += p.lastPoints;
                p.correct++;
            }
        }
        r.deadline = at + REVEAL_MS;
    }

    // ---- 내부 유틸 ---------------------------------------------------------------------

    /**
     * 방 단위 직렬화: <b>락 → 읽기 → 따라잡기 → 수정 → 쓰기 → 락 해제</b>.
     * 서버가 여러 대여도 한 방의 변경은 한 번에 하나씩 일어난다(RoomLocks). action이 예외를 던지면 저장하지 않는다.
     */
    private <T> T locked(String code, Function<Room, T> action) {
        return locks.run(code, () -> {
            Room room = load(code);
            tick(room, now());
            T result = action.apply(room);
            store.save(room);
            return result;
        });
    }

    private Room load(String code) {
        return store.find(code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요. 코드를 확인해 주세요."));
    }

    private boolean needsWrite(Room r, long now) {
        return (r.deadline != null && now >= r.deadline) || preparationTimedOut(r, now);
    }

    private boolean preparationTimedOut(Room r, long now) {
        return r.prepare == Prepare.PREPARING && now - r.created > PREPARE_TIMEOUT_MS;
    }

    /** 마감이 지난 단계를 따라잡고 준비 시간 초과를 처리한다. 무언가 바뀌었으면 true. */
    private boolean tick(Room r, long now) {
        Phase phase = r.phase;
        int index = r.index;
        Long deadline = r.deadline;
        Prepare prepare = r.prepare;
        if (preparationTimedOut(r, now)) {
            r.prepare = Prepare.FAILED;
            r.prepareError = "문제를 준비하는 데 너무 오래 걸렸어요. 새로운 방을 만들어 주세요.";
            r.startRequested = false;
            log.warn("문제 준비 시간 초과로 실패 처리: room={}", r.code);
        }
        advance(r, now);
        return phase != r.phase || index != r.index || prepare != r.prepare
                || !java.util.Objects.equals(deadline, r.deadline);
    }

    private Player addPlayer(Room r, String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.length() > 16) cleaned = cleaned.substring(0, 16);
        if (cleaned.isEmpty()) cleaned = "러너";
        Player p = new Player(UUID.randomUUID().toString(), SecureHexTokens.hex(24), cleaned,
                COLORS[r.players.size() % COLORS.length], now());
        r.players.put(p.id, p);
        return p;
    }

    private Player player(Room r, String token) {
        if (token != null) {
            byte[] given = token.getBytes(StandardCharsets.UTF_8);
            for (Player p : r.players.values()) {
                if (MessageDigest.isEqual(p.token.getBytes(StandardCharsets.UTF_8), given)) return p;
            }
        }
        throw ApiException.badRequest("다시 입장해 주세요.");
    }

    private RoomState view(Room r, Player me, Map<String, Long> presence, long now) {
        boolean showQuestion = r.phase == Phase.PLAYING || r.phase == Phase.REVEAL;
        Question q = showQuestion ? r.questions.get(r.index) : null;
        List<PublicPlayer> players = r.players.values().stream()
                .map(p -> new PublicPlayer(p.id, p.name, p.avatar, p.color, p.score, p.correct, p.lastPoints,
                        now - Math.max(p.lastSeen, presence.getOrDefault(p.id, 0L)) < ONLINE_WINDOW_MS,
                        p.answer != null))
                .toList();
        return new RoomState(now, r.code, r.title, r.keyword, r.source, r.credits, r.prepare.name().toLowerCase(),
                r.prepareError, r.capacity, r.phase.name().toLowerCase(), r.index, r.total(), r.deadline, r.timeLimit,
                List.copyOf(r.messages), r.host, me.id, players,
                q == null ? null : new QuestionView(q.text(), q.options()),
                r.phase == Phase.REVEAL ? q.answer() : null, me.answer,
                r.visibility, r.requestedCount, r.startRequested);
    }

    /** 사용자 키워드를 프롬프트에 넣기 전에 다듬는다: 줄바꿈·제어문자 제거, 공백 정리, 30자 제한. */
    static String cleanKeyword(String raw) {
        String k = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}\"]", " ").replaceAll("\\s+", " ").trim();
        if (k.isEmpty()) throw ApiException.badRequest("키워드를 입력해 주세요.");
        return k.length() > 30 ? k.substring(0, 30) : k;
    }

    private static int integral(Number n, String message) {
        double d = n.doubleValue();
        if (d != Math.rint(d)) throw ApiException.badRequest(message);
        return (int) d;
    }

    private long now() {
        return clock.millis();
    }

    /** 오래된 방 정리 (RoomCleaner가 호출). */
    public int removeExpired(long ttlMs) {
        long cutoff = now() - ttlMs;
        long failedCutoff = now() - FAILED_ROOM_TTL_MS;
        int removed = 0;
        for (Room r : store.all()) {
            if (r.created < cutoff || (r.prepare == Prepare.FAILED && r.created < failedCutoff)) {
                store.delete(r.code);
                removed++;
            }
        }
        return removed;
    }
}
