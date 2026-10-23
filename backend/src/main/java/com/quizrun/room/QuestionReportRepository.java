package com.quizrun.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

interface QuestionReportRepository extends JpaRepository<QuestionReport, Long> {

    boolean existsByQuestionIdAndReporterKey(Long questionId, String reporterKey);

    @Transactional
    void deleteByQuestionId(Long questionId);
}
