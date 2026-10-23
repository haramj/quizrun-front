package com.quizrun.room;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface StoredQuestionRepository extends JpaRepository<StoredQuestion, Long> {

    List<StoredQuestion> findByTopic(String topic);

    List<StoredQuestion> findByTopicAndActiveTrue(String topic);

    @Modifying
    @Transactional
    @Query("update StoredQuestion q set q.timesUsed = q.timesUsed + 1 where q.id in :ids")
    void incrementUsage(@Param("ids") Collection<Long> ids);

    @Modifying
    @Transactional
    @Query("update StoredQuestion q set q.reportCount = q.reportCount + 1 where q.id = :id")
    void incrementReportCount(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update StoredQuestion q set q.active = false, q.deactivatedReason = :reason, q.deactivatedAt = :at where q.id = :id")
    void deactivate(@Param("id") Long id, @Param("reason") String reason, @Param("at") Instant at);

    /** 복구: 다시 출제 대상이 되고 신고 횟수는 0으로 되돌린다. */
    @Modifying
    @Transactional
    @Query("update StoredQuestion q set q.active = true, q.deactivatedReason = null, q.deactivatedAt = null, q.reportCount = 0 where q.id = :id")
    void reactivate(@Param("id") Long id);

    // 관리자 목록 (topic이 null이면 전체). 신고가 많은 순.
    String ORDER = " order by q.reportCount desc, q.id desc";

    @Query("select q from StoredQuestion q where (:topic is null or q.topic = :topic)" + ORDER)
    List<StoredQuestion> searchAll(@Param("topic") String topic, Pageable page);

    @Query("select q from StoredQuestion q where (:topic is null or q.topic = :topic) and q.active = true" + ORDER)
    List<StoredQuestion> searchActive(@Param("topic") String topic, Pageable page);

    @Query("select q from StoredQuestion q where (:topic is null or q.topic = :topic) and q.active = false" + ORDER)
    List<StoredQuestion> searchInactive(@Param("topic") String topic, Pageable page);

    @Query("select q from StoredQuestion q where (:topic is null or q.topic = :topic) and q.reportCount > 0" + ORDER)
    List<StoredQuestion> searchReported(@Param("topic") String topic, Pageable page);
}
