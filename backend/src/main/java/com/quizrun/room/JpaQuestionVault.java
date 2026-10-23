package com.quizrun.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class JpaQuestionVault implements QuestionVault {

    private static final Logger log = LoggerFactory.getLogger(JpaQuestionVault.class);

    private final StoredQuestionRepository repo;
    private final QuestionReportRepository reports;

    JpaQuestionVault(StoredQuestionRepository repo, QuestionReportRepository reports) {
        this.repo = repo;
        this.reports = reports;
    }

    @Override
    public List<StoredQuestion> findActiveByTopic(String topic) {
        return repo.findByTopicAndActiveTrue(topic);
    }

    @Override
    public List<StoredQuestion> findAllByTopic(String topic) {
        return repo.findByTopic(topic);
    }

    @Override
    public Optional<StoredQuestion> findById(Long id) {
        return repo.findById(id);
    }

    @Override
    public List<StoredQuestion> saveNew(List<StoredQuestion> candidates) {
        if (candidates.isEmpty()) return List.of();
        Set<String> existing = new HashSet<>();
        for (StoredQuestion q : repo.findByTopic(candidates.get(0).topic)) existing.add(q.textHash);

        List<StoredQuestion> saved = new ArrayList<>();
        for (StoredQuestion c : candidates) {
            if (!existing.add(c.textHash)) continue;
            try {
                saved.add(repo.save(c));
            } catch (DataIntegrityViolationException race) {
                // 다른 서버가 같은 문제를 방금 저장함. 유니크 제약이 최종 방어선이다.
                log.debug("중복 문제 저장 건너뜀: {}", c.textHash);
            }
        }
        return saved;
    }

    @Override
    public void markUsed(Collection<StoredQuestion> used) {
        List<Long> ids = used.stream().map(q -> q.id).filter(id -> id != null).toList();
        if (!ids.isEmpty()) repo.incrementUsage(ids);
    }

    @Override
    public boolean addReport(Long questionId, String reporterKey, String reason, String detail) {
        if (reports.existsByQuestionIdAndReporterKey(questionId, reporterKey)) return false;
        try {
            reports.save(new QuestionReport(questionId, reporterKey, reason, detail));
        } catch (DataIntegrityViolationException race) {
            return false; // 동시에 같은 신고가 들어옴: 유니크 제약이 한 번만 인정한다
        }
        repo.incrementReportCount(questionId);
        return true;
    }

    @Override
    public void deactivate(Long questionId, String reason) {
        repo.deactivate(questionId, reason, Instant.now());
    }

    @Override
    public void reactivate(Long questionId) {
        reports.deleteByQuestionId(questionId);
        repo.reactivate(questionId);
    }

    @Override
    public List<StoredQuestion> search(String topic, String status, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        return switch (status) {
            case "active" -> repo.searchActive(topic, page);
            case "inactive" -> repo.searchInactive(topic, page);
            case "reported" -> repo.searchReported(topic, page);
            default -> repo.searchAll(topic, page);
        };
    }

    @Override
    public long count() {
        return repo.count();
    }
}
