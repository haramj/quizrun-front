package com.quizrun.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.quizrun.room.Room.Question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 검증을 통과해 문제은행에 쌓인 문제. 같은 키워드(topic)에서 같은 문제 본문은 한 번만 저장된다.
 * origin: "ai"(근거 검증을 통과한 생성 문제) 또는 "seed"(직접 검수한 내장 문제).
 */
@Entity
@Table(name = "stored_question",
        uniqueConstraints = @UniqueConstraint(name = "uk_topic_hash", columnNames = {"topic", "text_hash"}),
        indexes = @Index(name = "idx_topic", columnList = "topic"))
public class StoredQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 정규화한 키워드(공백·기호 제거, 소문자). */
    @Column(nullable = false, length = 100)
    public String topic;

    @Column(name = "text_hash", nullable = false, length = 64)
    public String textHash;

    @Column(name = "question_text", nullable = false, length = 300)
    public String text;

    @Column(nullable = false, length = 150)
    public String option1;
    @Column(nullable = false, length = 150)
    public String option2;
    @Column(nullable = false, length = 150)
    public String option3;
    @Column(nullable = false, length = 150)
    public String option4;

    /** 정답 보기 인덱스 0~3 */
    public int answer;

    @Column(length = 500)
    public String evidence;

    @Column(length = 20)
    public String origin;

    @Column(name = "source_site", length = 20)
    public String sourceSite;

    @Column(name = "source_title", length = 200)
    public String sourceTitle;

    @Column(name = "times_used")
    public int timesUsed;

    /** false면 출제에서 제외된다. 신고가 기준 횟수에 도달하거나 관리자가 비활성화하면 false가 된다. */
    @Column(nullable = false, columnDefinition = "boolean default true not null")
    public boolean active = true;

    /** 서로 다른 신고자의 신고 횟수 (question_report 행 수와 같다). */
    @Column(name = "report_count", nullable = false, columnDefinition = "integer default 0 not null")
    public int reportCount;

    /** "reports"(신고 누적) 또는 "admin"(관리자). 활성 문제는 null. */
    @Column(name = "deactivated_reason", length = 30)
    public String deactivatedReason;

    @Column(name = "deactivated_at")
    public Instant deactivatedAt;

    @Column(name = "created_at")
    public Instant createdAt = Instant.now();

    protected StoredQuestion() {}

    public StoredQuestion(String topic, Question q, String evidence, String origin, String sourceSite, String sourceTitle) {
        this.topic = topic;
        this.textHash = Texts.sha256(Texts.norm(q.text()));
        this.text = q.text();
        this.option1 = q.options().get(0);
        this.option2 = q.options().get(1);
        this.option3 = q.options().get(2);
        this.option4 = q.options().get(3);
        this.answer = q.answer();
        this.evidence = evidence;
        this.origin = origin;
        this.sourceSite = sourceSite;
        this.sourceTitle = sourceTitle;
    }

    /** 게임에 낼 때마다 보기 순서를 새로 섞어, 같은 문제도 정답 위치가 외워지지 않게 한다. */
    public Question toShuffledQuestion() {
        List<String> original = List.of(option1, option2, option3, option4);
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3));
        Collections.shuffle(order);
        List<String> options = new ArrayList<>();
        for (int i : order) options.add(original.get(i));
        return new Question(text, List.copyOf(options), order.indexOf(answer));
    }

    public String credit() {
        return sourceSite == null ? null : sourceSite + " · " + sourceTitle;
    }
}
