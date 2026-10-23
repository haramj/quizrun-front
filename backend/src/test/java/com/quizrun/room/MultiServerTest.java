package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizrun.room.Dtos.CreateRoomRequest;
import com.quizrun.room.Dtos.JoinResponse;
import com.quizrun.room.Dtos.RoomState;

/**
 * <b>서버 2대 실험</b>(수업 Day4 무상태 설계)을 자동 테스트로 만든 것.
 *
 * <p>서버 A, B는 서로 다른 RoomService 인스턴스이고 <b>저장소와 락만 공유</b>한다(운영에서는 Redis).
 * 예전(방 상태를 서버 메모리에 둔 구조)에는 "A에서 만든 방을 B가 모른다(404)"로 이 테스트가 전부 실패했다.
 */
class MultiServerTest {

    MutableClock clock;
    CountingRoomStore store;
    RoomLocks locks;
    InMemoryQuestionVault vault;
    FakeGenerator generator;
    FakeSources sources;
    QuizComposer composer;
    RoomService serverA;
    RoomService serverB;
    final List<Runnable> queuedOnA = new ArrayList<>();
    ExecutorService pool;

    final Executor manualOnA = task -> queuedOnA.add(task);

    RoomService newServer(Executor preparer) {
        return new RoomService(store, locks, composer, new QuestionReportService(vault, 3), clock, preparer);
    }

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock();
        store = new CountingRoomStore(new InMemoryRoomStore());
        locks = new InMemoryRoomLocks();
        vault = new InMemoryQuestionVault();
        generator = new FakeGenerator();
        generator.enabled = true;
        generator.succeed = true;
        sources = new FakeSources();
        QuestionBank bank = new QuestionBank(new ObjectMapper());
        new QuestionSeeder(vault, bank).run(null);
        composer = new QuizComposer(vault, sources, generator, bank, 0.3);
        serverA = newServer(manualOnA);
        serverB = newServer(Runnable::run);
        pool = Executors.newFixedThreadPool(16);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    RoomService alternating(int i) {
        return i % 2 == 0 ? serverA : serverB;
    }

    @Test
    void aRoomCreatedOnServerAIsFullyUsableOnServerB() {
        JoinResponse host = serverA.create(new CreateRoomRequest("개발", null, "Host", 3, 10, 4));

        RoomState seenByB = serverB.state(host.code(), host.token());   // 예전에는 404
        assertThat(seenByB.code()).isEqualTo(host.code());
        assertThat(seenByB.players()).hasSize(1);

        JoinResponse guest = serverB.join(host.code(), "Friend");        // B에서 입장
        assertThat(serverA.state(host.code(), host.token()).players()).as("A가 B의 입장을 본다").hasSize(2);
        assertThat(serverA.listWaiting()).extracting(r -> r.code()).contains(host.code());
        assertThat(serverB.listWaiting()).extracting(r -> r.code()).contains(host.code());

        serverA.chat(host.code(), host.token(), "A에서 보냄");
        serverB.chat(host.code(), guest.token(), "B에서 보냄");
        assertThat(serverA.state(host.code(), host.token()).messages()).extracting(m -> m.text())
                .containsExactly("A에서 보냄", "B에서 보냄");
    }

    @Test
    void aFullRaceCanBePlayedWithEveryRequestGoingToAnAlternatingServer() {
        JoinResponse host = serverA.create(new CreateRoomRequest("개발", null, "Host", 2, 10, 4));
        JoinResponse guest = serverB.join(host.code(), "Friend");        // 정원 충족 → 카운트다운
        assertThat(serverA.state(host.code(), host.token()).phase()).isEqualTo("countdown");

        clock.advance(5000);
        int seq = 0;
        for (int q = 0; q < 4; q++) {
            RoomState playing = alternating(seq++).state(host.code(), host.token());
            assertThat(playing.phase()).isEqualTo("playing");
            assertThat(playing.index()).isEqualTo(q);
            int correct = store.find(host.code()).orElseThrow().questions.get(q).answer();
            alternating(seq++).answer(host.code(), host.token(), correct);
            alternating(seq++).answer(host.code(), guest.token(), correct);   // 전원 응답 → 즉시 공개
            assertThat(alternating(seq++).state(host.code(), host.token()).phase()).isEqualTo("reveal");
            clock.advance(RoomService.REVEAL_MS);
        }
        RoomState end = serverB.state(host.code(), guest.token());
        assertThat(end.phase()).isEqualTo("finished");
        assertThat(end.players()).allSatisfy(p -> {
            assertThat(p.correct()).isEqualTo(4);
            assertThat(p.score()).isEqualTo(4 * 1500);                   // 이중 채점 없음
        });
    }

    @Test
    void aTimedOutQuestionIsAdvancedByWhicheverServerSeesItFirstExactlyOnce() {
        JoinResponse host = serverA.create(new CreateRoomRequest("개발", null, "Host", 2, 5, 4));
        JoinResponse guest = serverB.join(host.code(), "Friend");
        clock.advance(5000);                                              // 0번 문제 시작
        int correct = store.find(host.code()).orElseThrow().questions.get(0).answer();
        serverA.answer(host.code(), host.token(), correct);
        clock.advance(5000);                                              // 마감: 게스트는 미응답

        // 마감 직후 두 서버가 동시에 폴링해도 채점은 한 번만 반영된다
        List<Callable<RoomState>> polls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            RoomService s = alternating(i);
            polls.add(() -> s.state(host.code(), guest.token()));
        }
        for (Future<RoomState> f : invokeAll(polls)) assertThat(get(f).phase()).isEqualTo("reveal");
        RoomState s = serverA.state(host.code(), host.token());
        assertThat(s.players().get(0).score()).as("호스트 점수는 한 번만").isBetween(1000, 1500);
        assertThat(s.players().get(1).score()).isZero();
    }

    @Test
    void concurrentJoinsOnTwoServersNeverExceedCapacity() {
        JoinResponse host = serverA.create(new CreateRoomRequest("개발", null, "Host", 5, 10, 4));
        List<Callable<Boolean>> joins = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            RoomService s = alternating(i);
            joins.add(() -> {
                try {
                    s.join(host.code(), "러너");
                    return true;
                } catch (ApiException full) {
                    return false;
                }
            });
        }
        long ok = invokeAll(joins).stream().filter(f -> get(f)).count();
        assertThat(ok).as("방장 1명이 이미 있으므로 4명만 더 들어온다").isEqualTo(4);
        RoomState s = serverB.state(host.code(), host.token());
        assertThat(s.players()).hasSize(5);
        assertThat(s.phase()).as("정원이 차면 카운트다운").isEqualTo("countdown");
    }

    @Test
    void simultaneousAnswersFromBothServersAreAllRecordedAndScoredOnce() {
        JoinResponse host = serverA.create(new CreateRoomRequest("개발", null, "Host", 8, 10, 4));
        List<String> tokens = new ArrayList<>(List.of(host.token()));
        for (int i = 1; i < 8; i++) tokens.add(alternating(i).join(host.code(), "러너" + i).token());
        clock.advance(5000);
        assertThat(serverA.state(host.code(), host.token()).phase()).isEqualTo("playing");

        int correct = store.find(host.code()).orElseThrow().questions.get(0).answer();
        int wrong = (correct + 1) % 4;
        List<Callable<Void>> answers = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            RoomService s = alternating(i);
            String token = tokens.get(i);
            int choice = i < 4 ? correct : wrong;                         // 4명 정답, 4명 오답
            answers.add(() -> {
                s.answer(host.code(), token, choice);
                return null;
            });
        }
        invokeAll(answers).forEach(MultiServerTest::get);

        RoomState s = serverB.state(host.code(), host.token());
        assertThat(s.phase()).as("전원 응답 → 공개").isEqualTo("reveal");
        assertThat(s.players()).allMatch(p -> p.answered());
        assertThat(s.players().stream().filter(p -> p.score() == 1500).count()).isEqualTo(4);
        assertThat(s.players().stream().filter(p -> p.score() == 0).count()).isEqualTo(4);
        assertThat(s.players().stream().mapToInt(p -> p.correct()).sum()).isEqualTo(4);
    }

    @Test
    void presenceIsSharedWithoutTakingTheRoomLockOrWritingTheRoom() {
        JoinResponse host = serverA.create(new CreateRoomRequest("개발", null, "Host", 4, 10, 4));
        JoinResponse guest = serverB.join(host.code(), "Friend");
        clock.advance(20_000);                                            // 모두 접속 신호가 끊긴 상태
        RoomState offline = serverA.state(host.code(), host.token());
        assertThat(offline.players().get(1).online()).isFalse();

        int savesBefore = store.saves.get();
        for (int i = 0; i < 50; i++) {
            RoomState s = alternating(i).state(host.code(), guest.token());   // 게스트가 두 서버를 번갈아 폴링
            assertThat(s.phase()).isEqualTo("waiting");
        }
        assertThat(store.saves.get()).as("마감 전 폴링은 방을 다시 쓰지 않는다 (읽기 전용)").isEqualTo(savesBefore);
        assertThat(serverA.state(host.code(), host.token()).players().get(1).online())
                .as("B가 기록한 접속 신호를 A가 본다").isTrue();
    }

    @Test
    void preparationStartedOnServerAIsVisibleOnBAndFinishesThere() {
        JoinResponse host = serverA.create(new CreateRoomRequest("손흥민", null, "Host", 2, 10, 4));
        assertThat(serverB.state(host.code(), host.token()).prepare()).isEqualTo("preparing");
        JoinResponse guest = serverB.join(host.code(), "Friend");        // 정원 충족, 하지만 준비 중 → 대기
        assertThat(serverB.state(host.code(), guest.token()).phase()).isEqualTo("waiting");

        assertThat(queuedOnA).hasSize(1);
        queuedOnA.forEach(Runnable::run);                                // A의 백그라운드 스레드가 일을 마친다

        RoomState s = serverB.state(host.code(), guest.token());
        assertThat(s.prepare()).isEqualTo("ready");
        assertThat(s.phase()).as("준비가 끝나자 자동 출발").isEqualTo("countdown");
    }

    @Test
    void ifTheServerThatWasPreparingDiesAnotherServerFailsTheRoomAfterTheTimeout() {
        JoinResponse host = serverA.create(new CreateRoomRequest("손흥민", null, "Host", 2, 10, 4));
        queuedOnA.clear();                                               // 서버 A가 죽어서 준비 작업이 영영 실행되지 않는다

        clock.advance(RoomService.PREPARE_TIMEOUT_MS - 1000);
        assertThat(serverB.state(host.code(), host.token()).prepare()).isEqualTo("preparing");

        clock.advance(2000);
        RoomState s = serverB.state(host.code(), host.token());
        assertThat(s.prepare()).as("영원히 preparing에 남지 않는다").isEqualTo("failed");
        assertThat(s.prepareError()).contains("너무 오래 걸렸어요");
        assertThat(serverB.listWaiting()).isEmpty();
    }

    @Test
    void aPreparationThatFinishesAfterTheTimeoutDoesNotReviveTheRoom() {
        JoinResponse host = serverA.create(new CreateRoomRequest("손흥민", null, "Host", 2, 10, 4));
        clock.advance(RoomService.PREPARE_TIMEOUT_MS + 1000);
        assertThat(serverB.state(host.code(), host.token()).prepare()).isEqualTo("failed");
        queuedOnA.forEach(Runnable::run);                                // 늦게 끝난 A의 작업
        assertThat(serverB.state(host.code(), host.token()).prepare()).isEqualTo("failed");
    }

    @Test
    void aSavedRoomThatWasDeletedMeanwhileIsNotRecreated() {
        JoinResponse host = serverA.create(new CreateRoomRequest("개발", null, "Host", 2, 10, 4));
        Room stale = store.find(host.code()).orElseThrow();              // 한 서버가 방을 읽어 둔 사이에
        store.delete(host.code());                                       // 다른 서버(청소기)가 만료 삭제
        store.save(stale);                                               // 늦게 저장해도
        assertThat(store.exists(host.code())).as("만료된 방은 되살아나지 않는다").isFalse();
    }

    @Test
    void roomCodesStayUniqueWhenManyRoomsAreCreatedFromTwoServers() {
        List<Callable<String>> creates = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            RoomService s = alternating(i);
            creates.add(() -> s.create(new CreateRoomRequest("개발", null, "H", 2, 10, 4)).code());
        }
        List<String> codes = invokeAll(creates).stream().map(MultiServerTest::get).toList();
        assertThat(codes).doesNotHaveDuplicates().hasSize(60);
        assertThat(store.all()).hasSize(60);
    }

    @Test
    void unknownRoomAndWrongTokenStillFailCleanlyOnEveryServer() {
        JoinResponse host = serverA.create(new CreateRoomRequest("개발", null, "Host", 2, 10, 4));
        assertThatThrownBy(() -> serverB.state("ZZZZZZ", host.token())).isInstanceOf(ApiException.class).hasMessageContaining("방을 찾을 수 없어요");
        assertThatThrownBy(() -> serverB.state(host.code(), "wrong")).isInstanceOf(ApiException.class).hasMessageContaining("다시 입장");
    }

    // ---- 도우미 ----

    <T> List<Future<T>> invokeAll(List<Callable<T>> tasks) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> t : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return t.call();
            }));
        }
        start.countDown();                                                // 동시에 출발
        return futures;
    }

    static <T> T get(Future<T> f) {
        try {
            return f.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("동시 작업 실패: " + e, e);
        }
    }
}
