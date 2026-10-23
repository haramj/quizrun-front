package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizrun.room.Dtos.CreateRoomRequest;
import com.quizrun.room.Dtos.JoinResponse;
import com.quizrun.room.Dtos.RoomState;

/**
 * 방 생성 후 문제를 백그라운드에서 준비하는 흐름. 수동 실행기로 "준비가 끝나기 전"의 상태를 잡아낸다.
 */
class AsyncPrepareTest {

    MutableClock clock;
    InMemoryRoomStore store;
    InMemoryQuestionVault vault;
    FakeGenerator generator;
    FakeSources sources;
    QuestionBank bank;
    RoomService service;

    final List<Runnable> queued = new ArrayList<>();
    boolean busy;
    final Executor manual = task -> {
        if (busy) throw new RejectedExecutionException("queue full");
        queued.add(task);
    };

    /** 대기 중인 준비 작업을 실행한다 (= 백그라운드 스레드가 일을 마침). */
    void finishPreparing() {
        List<Runnable> tasks = new ArrayList<>(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }

    RoomService serviceWith(QuizComposer composer) {
        return new RoomService(store, new InMemoryRoomLocks(), composer, new QuestionReportService(vault, 3), clock, manual);
    }

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock();
        store = new InMemoryRoomStore();
        vault = new InMemoryQuestionVault();
        generator = new FakeGenerator();
        generator.enabled = true;
        generator.succeed = true;
        sources = new FakeSources();
        bank = new QuestionBank(new ObjectMapper());
        new QuestionSeeder(vault, bank).run(null);
        service = serviceWith(new QuizComposer(vault, sources, generator, bank, 0.3));
    }

    JoinResponse create(String keyword, int capacity, int count) {
        return service.create(new CreateRoomRequest(keyword, null, "Host", capacity, 10, count));
    }

    @Test
    void createReturnsBeforeAnyQuestionIsGeneratedAndStateShowsPreparing() {
        JoinResponse host = create("손흥민", 4, 4);

        assertThat(generator.calls).as("방 생성 요청은 AI 호출을 기다리지 않는다").isZero();
        RoomState s = service.state(host.code(), host.token());
        assertThat(s.prepare()).isEqualTo("preparing");
        assertThat(s.prepareError()).isNull();
        assertThat(s.phase()).isEqualTo("waiting");
        assertThat(s.total()).as("준비 전에는 요청한 문제 수를 보여 준다").isEqualTo(4);
        assertThat(s.question()).isNull();
        assertThat(s.source()).isNull();
        assertThat(s.credits()).isEmpty();
        assertThat(service.listWaiting()).singleElement().satisfies(r -> {
            assertThat(r.prepare()).isEqualTo("preparing");
            assertThat(r.total()).isEqualTo(4);
        });

        finishPreparing();

        s = service.state(host.code(), host.token());
        assertThat(s.prepare()).isEqualTo("ready");
        assertThat(s.source()).isEqualTo("ai");
        assertThat(s.credits()).containsExactly("위키백과 · 손흥민");
        assertThat(s.total()).isEqualTo(4);
        assertThat(generator.calls).isEqualTo(1);
    }

    @Test
    void fillingTheRoomWhilePreparingStartsTheCountdownAsSoonAsItIsReady() {
        JoinResponse host = create("손흥민", 2, 4);
        service.join(host.code(), "Friend");           // 정원이 찼지만 문제가 아직 없다

        RoomState s = service.state(host.code(), host.token());
        assertThat(s.phase()).as("준비 전에는 출발하지 않는다").isEqualTo("waiting");
        assertThat(s.prepare()).isEqualTo("preparing");

        clock.advance(7000);                            // 준비에 7초가 걸렸다고 치자
        finishPreparing();

        s = service.state(host.code(), host.token());
        assertThat(s.phase()).isEqualTo("countdown");
        assertThat(s.deadline()).as("카운트다운은 준비가 끝난 시점부터 5초").isEqualTo(clock.millis() + 5000);
        clock.advance(5000);
        assertThat(service.state(host.code(), host.token()).phase()).isEqualTo("playing");
    }

    @Test
    void hostStartWhilePreparingIsRememberedAndAppliedWhenReady() {
        JoinResponse host = create("손흥민", 4, 4);
        service.start(host.code(), host.token());      // 거부하지 않고 기억해 둔다
        assertThat(service.state(host.code(), host.token()).phase()).isEqualTo("waiting");

        finishPreparing();
        assertThat(service.state(host.code(), host.token()).phase()).isEqualTo("countdown");
    }

    @Test
    void roomStaysInTheWaitingRoomWhenNobodyAskedToStart() {
        JoinResponse host = create("손흥민", 4, 4);
        finishPreparing();
        RoomState s = service.state(host.code(), host.token());
        assertThat(s.prepare()).isEqualTo("ready");
        assertThat(s.phase()).isEqualTo("waiting");
    }

    @Test
    void failureIsPublishedInStateAndTheRoomCannotBeJoinedOrStarted() {
        sources.available = false;                      // 참고할 자료 없음
        JoinResponse host = create("손흥민", 4, 4);
        finishPreparing();

        RoomState s = service.state(host.code(), host.token());
        assertThat(s.prepare()).isEqualTo("failed");
        assertThat(s.prepareError()).contains("참고할 자료를 찾지 못했어요");
        assertThat(s.phase()).isEqualTo("waiting");
        assertThat(service.listWaiting()).as("실패한 방은 목록에서 빠진다").isEmpty();
        assertThatThrownBy(() -> service.join(host.code(), "Late")).hasMessageContaining("준비에 실패");
        assertThatThrownBy(() -> service.start(host.code(), host.token())).hasMessageContaining("준비에 실패");
    }

    @Test
    void aiFailureIsAlsoPublishedAsFailed() {
        generator.succeed = false;
        JoinResponse host = create("손흥민", 4, 4);
        finishPreparing();
        RoomState s = service.state(host.code(), host.token());
        assertThat(s.prepare()).isEqualTo("failed");
        assertThat(s.prepareError()).contains("AI가 문제를 만들지 못했어요");
    }

    @Test
    void failureWhileStartWasRequestedDoesNotStartTheRace() {
        sources.available = false;
        JoinResponse host = create("손흥민", 2, 4);
        service.join(host.code(), "Friend");           // 정원 충족 → 시작 요청됨
        finishPreparing();
        RoomState s = service.state(host.code(), host.token());
        assertThat(s.prepare()).isEqualTo("failed");
        assertThat(s.phase()).isEqualTo("waiting");
    }

    @Test
    void unexpectedErrorsBecomeAGenericFailureInsteadOfAHangingRoom() {
        SourceFinder exploding = kw -> {
            throw new IllegalStateException("boom");
        };
        service = serviceWith(new QuizComposer(vault, exploding, generator, bank, 0.3));
        JoinResponse host = create("손흥민", 4, 4);
        finishPreparing();
        RoomState s = service.state(host.code(), host.token());
        assertThat(s.prepare()).as("영원히 preparing으로 남지 않는다").isEqualTo("failed");
        assertThat(s.prepareError()).contains("문제를 만들지 못했어요").doesNotContain("boom");
    }

    @Test
    void aBusyServerRejectsPreparationButTheRoomIsStillCreatedAsFailed() {
        busy = true;
        JoinResponse host = create("손흥민", 4, 4);
        RoomState s = service.state(host.code(), host.token());
        assertThat(s.prepare()).isEqualTo("failed");
        assertThat(s.prepareError()).contains("요청이 많아요");
    }

    @Test
    void categoriesAndAiOffKeywordsStayOnTheFastSynchronousPath() {
        JoinResponse category = create("개발", 4, 4);
        assertThat(queued).as("내장 카테고리는 백그라운드를 쓰지 않는다").isEmpty();
        RoomState s = service.state(category.code(), category.token());
        assertThat(s.prepare()).isEqualTo("ready");
        assertThat(s.source()).isEqualTo("bank");
        assertThat(generator.calls).isZero();

        generator.enabled = false;                      // AI 꺼짐
        JoinResponse bankOnly = create("과학", 4, 4);
        assertThat(service.state(bankOnly.code(), bankOnly.token()).prepare()).isEqualTo("ready");
        assertThatThrownBy(() -> create("손흥민", 4, 4)).as("잘못된 키워드는 예전처럼 즉시 오류")
                .isInstanceOf(ApiException.class);
        assertThat(queued).isEmpty();
    }

    @Test
    void inputValidationStillFailsFastBeforeAnyBackgroundWork() {
        assertThatThrownBy(() -> service.create(new CreateRoomRequest("손흥민", null, "H", 1, 10, 4))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(new CreateRoomRequest("   ", null, "H", 2, 10, 4))).hasMessageContaining("키워드");
        assertThat(queued).isEmpty();
        assertThat(store.all()).isEmpty();
    }

    @Test
    void partialResultsUpdateTheTotalWhenReady() {
        generator.maxPerCall = 4;                       // 6문제를 요청했지만 검증 통과는 4개
        JoinResponse host = create("손흥민", 4, 6);
        assertThat(service.state(host.code(), host.token()).total()).isEqualTo(6);
        finishPreparing();
        RoomState s = service.state(host.code(), host.token());
        assertThat(s.prepare()).isEqualTo("ready");
        assertThat(s.total()).as("준비가 끝나면 실제 문제 수").isEqualTo(4);
    }

    @Test
    void aRoomThatExpiresBeforePreparationFinishesIsIgnoredQuietly() {
        JoinResponse host = create("손흥민", 4, 4);
        clock.advance(2000);
        assertThat(service.removeExpired(1000)).isEqualTo(1);
        finishPreparing();                              // 예외 없이 끝나야 한다
        assertThat(store.all()).isEmpty();
        assertThatThrownBy(() -> service.state(host.code(), host.token())).isInstanceOf(ApiException.class);
    }

    @Test
    void failedRoomsAreCleanedUpAfterTenMinutesButPreparingOnesAreNot() {
        sources.available = false;
        JoinResponse failed = create("손흥민", 4, 4);
        finishPreparing();
        sources.available = true;
        JoinResponse preparing = create("조선 시대", 4, 4);   // 아직 준비 중

        clock.advance(10 * 60 * 1000 + 1);
        assertThat(service.removeExpired(6 * 60 * 60 * 1000L)).isEqualTo(1);
        assertThat(store.find(failed.code())).isEmpty();
        assertThat(store.find(preparing.code())).isPresent();
    }

    @Test
    void chatAndReportsBehaveSensiblyWhilePreparing() {
        JoinResponse host = create("손흥민", 4, 4);
        service.chat(host.code(), host.token(), "문제 만드는 중이래");
        assertThat(service.state(host.code(), host.token()).messages()).hasSize(1);
        assertThatThrownBy(() -> service.report(host.code(), host.token(), 0, "OTHER", null))
                .hasMessageContaining("공개되지 않은");
        assertThatThrownBy(() -> service.report(host.code(), host.token(), 9, "OTHER", null))
                .hasMessageContaining("신고할 문제를 선택");
    }

    @Test
    void theRaceRunsNormallyOnceQuestionsArrive() {
        JoinResponse host = create("손흥민", 2, 4);
        service.join(host.code(), "Friend");
        finishPreparing();
        clock.advance(5000);
        RoomState s = service.state(host.code(), host.token());
        assertThat(s.phase()).isEqualTo("playing");
        assertThat(s.question()).isNotNull();
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.answer()).isNull();
    }
}
