package com.quizrun.room;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 문제 신고와 문제은행 관리.
 *
 * <p>서로 다른 신고자의 신고가 기준 횟수(quizrun.report.threshold, 기본 3)에 도달하면 그 문제를 자동으로
 * 출제에서 제외(비활성화)한다. 행은 지우지 않는다: 같은 문제·같은 사실을 AI가 다시 만들어 되살리는 것을 막아야 하기 때문이다.
 * 이미 진행 중인 판의 문제는 바뀌지 않고, 다음 판부터 빠진다.
 */
@Service
public class QuestionReportService {

    private static final Logger log = LoggerFactory.getLogger(QuestionReportService.class);

    static final Set<String> REASONS = Set.of("WRONG_ANSWER", "AMBIGUOUS", "OFFENSIVE", "OTHER");
    private static final Set<String> STATUSES = Set.of("all", "active", "inactive", "reported");

    public record Result(boolean recorded, boolean deactivated) {}

    private final QuestionVault vault;
    private final int threshold;

    public QuestionReportService(QuestionVault vault, @Value("${quizrun.report.threshold:3}") int threshold) {
        this.vault = vault;
        this.threshold = Math.max(1, threshold);
    }

    /**
     * 문제 하나를 신고한다. 같은 신고자의 중복 신고는 recorded=false로 조용히 무시한다.
     * 이 신고로 기준에 도달하면 deactivated=true.
     */
    public Result report(Long questionId, String reporterKey, String reason, String detail) {
        if (reason == null || !REASONS.contains(reason)) {
            throw ApiException.badRequest("신고 사유를 선택해 주세요: 정답 오류 · 모호함 · 부적절 · 기타");
        }
        String cleaned = detail == null ? null : detail.replaceAll("\\p{Cntrl}", " ").trim();
        if (cleaned != null && cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        if (cleaned != null && cleaned.isEmpty()) cleaned = null;

        StoredQuestion q = vault.findById(questionId).orElse(null);
        if (q == null) return new Result(false, false);

        if (!vault.addReport(questionId, reporterKey, reason, cleaned)) return new Result(false, false);

        StoredQuestion after = vault.findById(questionId).orElse(q);
        boolean deactivate = after.active && after.reportCount >= threshold;
        if (deactivate) {
            vault.deactivate(questionId, "reports");
            log.warn("신고 {}회 누적으로 문제 제외: id={} \"{}\"", after.reportCount, questionId, after.text);
        }
        return new Result(true, deactivate);
    }

    // ---- 관리자 ------------------------------------------------------------------------

    public List<StoredQuestion> list(String topic, String status, int limit) {
        String s = status == null ? "reported" : status;
        if (!STATUSES.contains(s)) throw ApiException.badRequest("status는 all · active · inactive · reported 중 하나여야 해요.");
        String t = topic == null || topic.isBlank() ? null : Texts.norm(topic);
        return vault.search(t, s, limit);
    }

    public StoredQuestion deactivate(Long id) {
        StoredQuestion q = find(id);
        if (q.active) vault.deactivate(id, "admin");
        return find(id);
    }

    public StoredQuestion reactivate(Long id) {
        find(id);
        vault.reactivate(id);
        return find(id);
    }

    private StoredQuestion find(Long id) {
        return vault.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "문제를 찾을 수 없어요."));
    }
}
