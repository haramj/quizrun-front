-- ============================================================================
-- 현재 구현(v1: Spring JPA가 만든 stored_question / question_report)의 데이터를
-- 목표 스키마(v2: db/schema.sql)로 옮기는 이행 스크립트.
--
--   사용: 같은 MySQL 서버에 v1 DB(예: quizrun)와 v2 DB(예: quizrun_v2)가 있고,
--         v2에 db/schema.sql을 이미 적용했다고 가정한다.
--   설계: docs/database-design.md 6장
--
--   옮기는 것   : 키워드(topic), 근거 문서, 문제, 보기 4개, 정답 번호(0~3 → 1~4), 비활성 상태, 출제 횟수
--   옮기지 않는 것: question_report. v1의 신고자는 "방코드:참가자ID" 문자열이라 v2의 사용자(app_user)와
--                  연결할 수 없다. 대신 report_count는 0으로 초기화한다(이미 비활성이 된 문제는 비활성 그대로 유지).
-- ============================================================================

SET NAMES utf8mb4;
-- 데이터베이스 이름은 아래 문장에 quizrun(v1) / quizrun_v2(v2)로 고정되어 있다. 다르면 일괄 치환한다.

START TRANSACTION;

-- 1) 키워드: v1은 문제 행마다 topic 문자열을 반복해서 가졌다 → 한 번씩만 topic 릴레이션으로 분리
INSERT INTO quizrun_v2.topic (topic_key, display_name, is_category)
-- origin이 NULL인 행만 있는 키워드는 카테고리가 아니라고 본다(MAX가 NULL이 되므로 0으로 바꾼다)
SELECT s.topic, s.topic, COALESCE(MAX(s.origin = 'seed'), 0)
FROM quizrun.stored_question s
GROUP BY s.topic;

-- 2) 근거 문서: v1은 source_site/source_title을 문제마다 반복 저장했다 → 문서 릴레이션으로 분리
INSERT INTO quizrun_v2.source_document (site, title)
SELECT DISTINCT s.source_site, s.source_title
FROM quizrun.stored_question s
WHERE s.source_site IS NOT NULL AND s.source_title IS NOT NULL;

-- 3) 문제
INSERT INTO quizrun_v2.question
    (topic_id, text_hash, question_text, answer_seq, evidence, origin, doc_id,
     deactivated_at, deactivated_reason, report_count, times_used, created_at)
SELECT t.topic_id,
       s.text_hash,
       s.question_text,
       s.answer + 1,                                                   -- 0~3 → 1~4
       s.evidence,
       COALESCE(s.origin, 'seed'),
       d.doc_id,
       IF(s.active, NULL, COALESCE(s.deactivated_at, NOW(3))),
       IF(s.active, NULL, COALESCE(s.deactivated_reason, 'admin')),
       0,
       COALESCE(s.times_used, 0),
       COALESCE(s.created_at, NOW(3))
FROM quizrun.stored_question s
JOIN quizrun_v2.topic t ON t.topic_key = s.topic
LEFT JOIN quizrun_v2.source_document d ON d.site = s.source_site AND d.title = s.source_title;

-- 4) 보기: option1~option4 (반복 열) → 행 4개 (다치 애트리뷰트의 릴레이션 분리)
INSERT INTO quizrun_v2.question_option (question_id, seq, option_text)
SELECT q.question_id,
       n.seq,
       CASE n.seq WHEN 1 THEN s.option1 WHEN 2 THEN s.option2 WHEN 3 THEN s.option3 ELSE s.option4 END
FROM quizrun.stored_question s
JOIN quizrun_v2.topic t    ON t.topic_key = s.topic
JOIN quizrun_v2.question q ON q.topic_id = t.topic_id AND q.text_hash = s.text_hash
CROSS JOIN (SELECT 1 AS seq UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) n;

COMMIT;
