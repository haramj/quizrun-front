package com.quizrun.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** DB 없이 문제은행 동작을 검증하기 위한 메모리 구현. JpaQuestionVault와 같은 규칙을 따른다. */
class InMemoryQuestionVault implements QuestionVault {

    final List<StoredQuestion> rows = new ArrayList<>();
    private final Set<String> reportKeys = new HashSet<>();
    private long nextId = 1;

    @Override
    public List<StoredQuestion> findActiveByTopic(String topic) {
        return rows.stream().filter(q -> q.topic.equals(topic) && q.active).toList();
    }

    @Override
    public List<StoredQuestion> findAllByTopic(String topic) {
        return rows.stream().filter(q -> q.topic.equals(topic)).toList();
    }

    @Override
    public Optional<StoredQuestion> findById(Long id) {
        return rows.stream().filter(q -> q.id.equals(id)).findFirst();
    }

    @Override
    public List<StoredQuestion> saveNew(List<StoredQuestion> candidates) {
        List<StoredQuestion> saved = new ArrayList<>();
        for (StoredQuestion c : candidates) {
            boolean exists = rows.stream().anyMatch(q -> q.topic.equals(c.topic) && q.textHash.equals(c.textHash));
            if (exists) continue;
            c.id = nextId++;
            rows.add(c);
            saved.add(c);
        }
        return saved;
    }

    @Override
    public void markUsed(Collection<StoredQuestion> used) {
        for (StoredQuestion q : used) q.timesUsed++;
    }

    @Override
    public boolean addReport(Long questionId, String reporterKey, String reason, String detail) {
        if (!reportKeys.add(questionId + "|" + reporterKey)) return false;
        findById(questionId).orElseThrow().reportCount++;
        return true;
    }

    @Override
    public void deactivate(Long questionId, String reason) {
        StoredQuestion q = findById(questionId).orElseThrow();
        q.active = false;
        q.deactivatedReason = reason;
        q.deactivatedAt = Instant.now();
    }

    @Override
    public void reactivate(Long questionId) {
        StoredQuestion q = findById(questionId).orElseThrow();
        q.active = true;
        q.deactivatedReason = null;
        q.deactivatedAt = null;
        q.reportCount = 0;
        reportKeys.removeIf(k -> k.startsWith(questionId + "|"));
    }

    @Override
    public List<StoredQuestion> search(String topic, String status, int limit) {
        return rows.stream()
                .filter(q -> topic == null || q.topic.equals(topic))
                .filter(q -> switch (status) {
                    case "active" -> q.active;
                    case "inactive" -> !q.active;
                    case "reported" -> q.reportCount > 0;
                    default -> true;
                })
                .sorted((a, b) -> b.reportCount != a.reportCount ? b.reportCount - a.reportCount : Long.compare(b.id, a.id))
                .limit(limit)
                .toList();
    }

    @Override
    public long count() {
        return rows.size();
    }
}
