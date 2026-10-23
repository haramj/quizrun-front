package com.quizrun.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.quizrun.room.QuestionReportService.Result;
import com.quizrun.room.Room.Question;

class QuestionReportServiceTest {

    InMemoryQuestionVault vault;
    QuestionReportService service;
    StoredQuestion q;

    @BeforeEach
    void setUp() {
        vault = new InMemoryQuestionVault();
        service = new QuestionReportService(vault, 3);
        q = new StoredQuestion("손흥민", new Question("손흥민의 생년월일은?", List.of("a", "b", "c", "d"), 0),
                "손흥민은 1992년 7월 8일에 태어났다", "ai", "위키백과", "손흥민");
        vault.saveNew(List.of(q));
    }

    @Test
    void questionStaysActiveBelowTheThreshold() {
        assertThat(service.report(q.id, "r1", "WRONG_ANSWER", null)).isEqualTo(new Result(true, false));
        assertThat(service.report(q.id, "r2", "AMBIGUOUS", null)).isEqualTo(new Result(true, false));
        assertThat(q.active).isTrue();
        assertThat(q.reportCount).isEqualTo(2);
    }

    @Test
    void reachingTheThresholdDeactivatesAutomatically() {
        service.report(q.id, "r1", "WRONG_ANSWER", null);
        service.report(q.id, "r2", "WRONG_ANSWER", null);
        Result third = service.report(q.id, "r3", "OFFENSIVE", null);

        assertThat(third).isEqualTo(new Result(true, true));
        assertThat(q.active).isFalse();
        assertThat(q.deactivatedReason).isEqualTo("reports");
        assertThat(q.deactivatedAt).isNotNull();
        assertThat(vault.findActiveByTopic("손흥민")).isEmpty();
        assertThat(vault.findAllByTopic("손흥민")).as("행은 남는다 — 같은 문제를 AI가 되살리지 못하게").hasSize(1);
    }

    @Test
    void theSameReporterCountsOnlyOnce() {
        assertThat(service.report(q.id, "room1:p1", "WRONG_ANSWER", null).recorded()).isTrue();
        assertThat(service.report(q.id, "room1:p1", "WRONG_ANSWER", null).recorded()).isFalse();
        assertThat(service.report(q.id, "room1:p1", "OTHER", "다시").recorded()).isFalse();
        assertThat(q.reportCount).isEqualTo(1);
        assertThat(q.active).isTrue();
    }

    @Test
    void invalidReasonsAreRejected() {
        assertThatThrownBy(() -> service.report(q.id, "r1", "BOGUS", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.report(q.id, "r1", null, null)).isInstanceOf(ApiException.class);
        assertThat(q.reportCount).isZero();
    }

    @Test
    void unknownQuestionsAreIgnoredWithoutError() {
        assertThat(service.report(9999L, "r1", "WRONG_ANSWER", null)).isEqualTo(new Result(false, false));
    }

    @Test
    void alreadyInactiveQuestionsAreNotDeactivatedTwice() {
        service.deactivate(q.id);
        assertThat(q.deactivatedReason).isEqualTo("admin");
        service.report(q.id, "r1", "WRONG_ANSWER", null);
        service.report(q.id, "r2", "WRONG_ANSWER", null);
        Result third = service.report(q.id, "r3", "WRONG_ANSWER", null);
        assertThat(third.deactivated()).as("이미 비활성이면 사유를 덮어쓰지 않는다").isFalse();
        assertThat(q.deactivatedReason).isEqualTo("admin");
    }

    @Test
    void adminCanDeactivateAndRestore() {
        assertThat(service.deactivate(q.id).active).isFalse();
        assertThat(q.deactivatedReason).isEqualTo("admin");

        service.report(q.id, "r1", "WRONG_ANSWER", null);
        StoredQuestion restored = service.reactivate(q.id);
        assertThat(restored.active).isTrue();
        assertThat(restored.reportCount).as("복구하면 신고 횟수 초기화").isZero();
        assertThat(restored.deactivatedReason).isNull();
        // 복구 후에는 같은 신고자도 다시 신고할 수 있다
        assertThat(service.report(q.id, "r1", "WRONG_ANSWER", null).recorded()).isTrue();
    }

    @Test
    void adminActionsOnUnknownQuestionsReturnNotFound() {
        assertThatThrownBy(() -> service.deactivate(9999L)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(404);
        assertThatThrownBy(() -> service.reactivate(9999L)).isInstanceOf(ApiException.class);
    }

    @Test
    void listFiltersByStatusAndSortsByReportCount() {
        StoredQuestion other = new StoredQuestion("손흥민", new Question("다른 문제", List.of("a", "b", "c", "d"), 0),
                "다른 근거 문장입니다 길게", "ai", "위키백과", "손흥민");
        vault.saveNew(List.of(other));
        service.report(other.id, "r1", "AMBIGUOUS", null);
        service.report(q.id, "r1", "WRONG_ANSWER", null);
        service.report(q.id, "r2", "WRONG_ANSWER", null);

        assertThat(service.list(null, "reported", 10)).extracting(x -> x.id).containsExactly(q.id, other.id);
        assertThat(service.list("손흥민", "all", 10)).hasSize(2);
        service.deactivate(other.id);
        assertThat(service.list(null, "inactive", 10)).extracting(x -> x.id).containsExactly(other.id);
        assertThat(service.list(null, "active", 10)).extracting(x -> x.id).containsExactly(q.id);
        assertThatThrownBy(() -> service.list(null, "bogus", 10)).isInstanceOf(ApiException.class);
    }
}
