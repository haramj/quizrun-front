package com.quizrun.room;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 검증된 문제를 쌓아 두는 문제은행 저장소.
 * 구현체: JpaQuestionVault (H2 또는 Cloud DB for MySQL). 테스트에서는 메모리 구현을 쓴다.
 *
 * <p>비활성(active=false) 문제는 출제 대상(findActiveByTopic)에서 빠지지만 행은 남는다.
 * 그래야 같은 문제나 같은 사실을 AI가 다시 만들어 되살리는 것을 막을 수 있다(findAllByTopic).
 */
public interface QuestionVault {

    /** 출제 후보: 활성 문제만. */
    List<StoredQuestion> findActiveByTopic(String topic);

    /** 비활성 포함 전체. 중복·재생성 방지 판단에 쓴다. */
    List<StoredQuestion> findAllByTopic(String topic);

    Optional<StoredQuestion> findById(Long id);

    /** 같은 (topic, 문제 본문)이 이미 있으면(비활성 포함) 건너뛰고, 실제로 새로 저장된 것만 돌려준다. */
    List<StoredQuestion> saveNew(List<StoredQuestion> candidates);

    /** 출제 횟수를 올린다. 덜 쓰인 문제를 먼저 내기 위한 기록이다. */
    void markUsed(Collection<StoredQuestion> used);

    /** 신고를 기록하고 신고 횟수를 올린다. 같은 신고자가 이미 신고했다면 false(아무것도 바뀌지 않음). */
    boolean addReport(Long questionId, String reporterKey, String reason, String detail);

    /** 출제에서 제외한다. reason: "reports" 또는 "admin". */
    void deactivate(Long questionId, String reason);

    /** 복구한다: 다시 출제 대상이 되고 신고 기록과 횟수가 초기화된다. */
    void reactivate(Long questionId);

    /** 관리자 목록. status: all | active | inactive | reported. topic이 null이면 전체. */
    List<StoredQuestion> search(String topic, String status, int limit);

    long count();
}
