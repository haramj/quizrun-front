package com.quizrun.room;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 문제 신고 한 건. 같은 신고자는 같은 문제를 한 번만 신고할 수 있다(유니크 제약이 최종 방어선).
 * reporterKey는 "방코드:참가자ID"라서 로그인 없이도 한 방의 한 참가자를 구분한다.
 */
@Entity
@Table(name = "question_report",
        uniqueConstraints = @UniqueConstraint(name = "uk_report_once", columnNames = {"question_id", "reporter_key"}),
        indexes = @Index(name = "idx_report_question", columnList = "question_id"))
public class QuestionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "question_id", nullable = false)
    public Long questionId;

    @Column(name = "reporter_key", nullable = false, length = 100)
    public String reporterKey;

    /** WRONG_ANSWER · AMBIGUOUS · OFFENSIVE · OTHER */
    @Column(nullable = false, length = 20)
    public String reason;

    @Column(length = 200)
    public String detail;

    @Column(name = "created_at")
    public Instant createdAt = Instant.now();

    protected QuestionReport() {}

    public QuestionReport(Long questionId, String reporterKey, String reason, String detail) {
        this.questionId = questionId;
        this.reporterKey = reporterKey;
        this.reason = reason;
        this.detail = detail;
    }
}
