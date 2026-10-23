package com.quizrun.room;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 한 판의 레이스 전체 상태. 접근은 RoomService가 방 단위로 직렬화한다. */
public class Room {

    public enum Phase { WAITING, COUNTDOWN, PLAYING, REVEAL, FINISHED }

    /** 문제 준비 상태. 문제를 AI로 만들어야 하는 방은 PREPARING으로 시작해 백그라운드에서 READY/FAILED가 된다. */
    public enum Prepare { PREPARING, READY, FAILED }

    public record Question(String text, List<String> options, int answer) {}

    public record ChatMessage(String id, String playerId, String name, String text, long at) {}

    public static class Player {
        public final String id;
        public final String token;
        public String name;
        public String avatar = "";
        public final String color;
        public int score;
        public int correct;
        public int lastPoints;
        public Integer answer;
        public long answeredAt;
        public long lastSeen;
        public long lastChat;

        /** JSON 복원용(RoomCodec). 최종 필드는 리플렉션으로 채워진다. */
        private Player() {
            this(null, null, null, null, 0);
        }

        public Player(String id, String token, String name, String color, long now) {
            this.id = id;
            this.token = token;
            this.name = name;
            this.color = color;
            this.lastSeen = now;
        }
    }

    public final String code;
    public final String title;
    public final int capacity;
    public final int timeLimit;
    /** 요청한 문제 수. 준비 전에는 이 값을 total로 보여 주고, 준비 후에는 실제 문제 수가 total이 된다. */
    public final int requestedCount;
    public final long created;
    public final List<ChatMessage> messages = new ArrayList<>();
    public final Map<String, Player> players = new LinkedHashMap<>();

    /** 키워드. 준비가 끝나면 실제 출제 주제(별칭 해석 결과)로 바뀔 수 있다. */
    public String keyword;
    /** Public lobby visibility. Code/link access remains available for invitation rooms. */
    public String visibility = "public";
    /** 문제 출처: "ai"(전부 새로 생성) · "mixed"(문제은행 + 새로 생성) · "bank"(문제은행/내장). 준비 전에는 null. */
    public String source;
    /** 문제에 쓰인 근거 자료 출처(예: "위키백과 · 손흥민"). 화면에 출처를 표시하기 위한 값. */
    public List<String> credits = List.of();
    /** 준비가 끝나기 전에는 비어 있다. */
    public List<Question> questions = List.of();
    /** questions와 같은 순서의 문제은행 ID (신고용). 문제은행에 없는 문제는 null. */
    public List<Long> questionIds = List.of();

    public Prepare prepare = Prepare.READY;
    /** prepare == FAILED일 때 사용자에게 보여줄 이유. */
    public String prepareError;
    /** 준비 중에 시작이 요청됨(정원 충족 또는 방장이 시작). 준비가 끝나면 바로 카운트다운을 시작한다. */
    public boolean startRequested;

    public Phase phase = Phase.WAITING;
    public int index;
    /** 현재 단계가 끝나는 시각(epoch ms). 대기/종료 단계에서는 null. */
    public Long deadline;
    public long startedAt;
    public String host;

    /** JSON 복원용(RoomCodec). */
    private Room() {
        this(null, null, null, 0, 0, 0, 0);
    }

    public Room(String code, String title, String keyword, int capacity, int timeLimit, int requestedCount, long created) {
        this.code = code;
        this.title = title;
        this.keyword = keyword;
        this.capacity = capacity;
        this.timeLimit = timeLimit;
        this.requestedCount = requestedCount;
        this.created = created;
    }

    /** 문제 준비가 끝났을 때 한 번 호출한다. */
    void applyQuiz(QuizComposer.Quiz quiz) {
        this.keyword = quiz.topic();
        this.source = quiz.source();
        this.credits = quiz.credits();
        this.questions = quiz.questions();
        this.questionIds = quiz.questionIds();
        this.prepare = Prepare.READY;
        this.prepareError = null;
    }

    /** 화면에 보여줄 문제 수: 준비 전에는 요청한 수, 준비 후에는 실제 수. */
    int total() {
        return prepare == Prepare.READY ? questions.size() : requestedCount;
    }
}
