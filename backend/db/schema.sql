-- ============================================================================
-- QUIZRUN 목표 스키마 (논리적 설계 → 물리적 설계 결과)
--
--   DBMS      : MySQL 8.0 (NCP Cloud DB for MySQL 대응), InnoDB, utf8mb4
--   설계 문서  : docs/database-design.md
--   검증      : MySQL 8.0.46 컨테이너에서 실행하고 제약조건을 시험했다 (문서 8장)
--
--   주의: 현재 Spring 코드(JPA, ddl-auto=update)가 만드는 스키마와 다르다.
--         현재 구현과의 차이와 이행 계획은 문서 6장을 본다.
-- ============================================================================

SET NAMES utf8mb4;

-- ----------------------------------------------------------------------------
-- 1. 참조(룩업) 릴레이션
-- ----------------------------------------------------------------------------

-- 출처 사이트. 라이선스는 사이트에 종속(site -> license)이므로 문제/문서 행마다 반복하지 않고 분리한다 (3NF).
CREATE TABLE source_site (
    site                 VARCHAR(20)  NOT NULL,
    license              VARCHAR(40)  NOT NULL,
    attribution_required BOOLEAN      NOT NULL DEFAULT TRUE,
    non_commercial_only  BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (site)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO source_site (site, license, attribution_required, non_commercial_only) VALUES
    ('위키백과', 'CC BY-SA 4.0',       TRUE, FALSE),
    ('나무위키', 'CC BY-NC-SA 2.0 KR', TRUE, TRUE);

-- 키워드. topic_key는 공백·기호를 제거하고 소문자로 만든 비교용 값이다.
CREATE TABLE topic (
    topic_id     INT UNSIGNED NOT NULL AUTO_INCREMENT,
    topic_key    VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    is_category  BOOLEAN      NOT NULL DEFAULT FALSE,   -- 내장 카테고리(상식, 개발 ...)인가
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (topic_id),
    UNIQUE KEY uk_topic_key (topic_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 근거 자료(위키 문서).
CREATE TABLE source_document (
    doc_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site       VARCHAR(20)     NOT NULL,
    title      VARCHAR(200)    NOT NULL,
    fetched_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (doc_id),
    UNIQUE KEY uk_document (site, title),
    CONSTRAINT fk_document_site FOREIGN KEY (site) REFERENCES source_site (site)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- 2. 사용자
-- ----------------------------------------------------------------------------

-- 기획서: 로그인(닉네임·프로필 사진), 관리자 로그인. 인증 방식은 미정이므로 관리자만 password_hash를 가진다.
CREATE TABLE app_user (
    user_id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    nickname          VARCHAR(16)     NOT NULL,
    profile_image_key VARCHAR(200)    NULL,              -- Object Storage 객체 키 (이미지 자체는 DB에 두지 않는다)
    role              VARCHAR(10)     NOT NULL DEFAULT 'player',
    password_hash     VARCHAR(100)    NULL,
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    CONSTRAINT ck_user_role CHECK (role IN ('player', 'admin')),
    CONSTRAINT ck_user_admin_pw CHECK (role <> 'admin' OR password_hash IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- 3. 문제은행
-- ----------------------------------------------------------------------------

CREATE TABLE question (
    question_id        BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    topic_id           INT UNSIGNED     NOT NULL,
    text_hash          CHAR(64)         NOT NULL,        -- SHA-256(정규화한 문제 본문): 같은 문제 중복 저장 방지용 (파생)
    question_text      VARCHAR(300)     NOT NULL,
    answer_seq         TINYINT UNSIGNED NOT NULL,        -- 정답 보기 번호 1~4
    evidence           VARCHAR(500)     NULL,            -- 정답 근거가 된 자료 속 원문 문장
    origin             VARCHAR(10)      NOT NULL,        -- 'seed'(직접 검수) | 'ai'(근거 검증을 통과한 생성 문제)
    doc_id             BIGINT UNSIGNED  NULL,
    deactivated_at     DATETIME(3)      NULL,            -- NULL이면 출제 대상(활성)
    deactivated_reason VARCHAR(10)      NULL,            -- 'reports' | 'admin'
    -- 물리 설계에서 추가한 중복(파생) 컬럼: 같은 값을 다른 릴레이션에서 계산할 수 있지만 조회 성능을 위해 둔다. (문서 5.3)
    report_count       SMALLINT UNSIGNED NOT NULL DEFAULT 0,   -- = COUNT(question_report)
    times_used         INT UNSIGNED      NOT NULL DEFAULT 0,   -- = COUNT(room_question)
    created_at         DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (question_id),
    UNIQUE KEY uk_question_topic_hash (topic_id, text_hash),
    KEY idx_question_pool (topic_id, deactivated_at, times_used),
    KEY idx_question_doc (doc_id),
    CONSTRAINT fk_question_topic FOREIGN KEY (topic_id) REFERENCES topic (topic_id),
    CONSTRAINT fk_question_doc   FOREIGN KEY (doc_id)   REFERENCES source_document (doc_id),
    CONSTRAINT ck_question_answer CHECK (answer_seq BETWEEN 1 AND 4),
    CONSTRAINT ck_question_origin CHECK (origin IN ('seed', 'ai')),
    CONSTRAINT ck_question_reason CHECK (deactivated_reason IS NULL OR deactivated_reason IN ('reports', 'admin')),
    -- 비활성 시각과 사유는 함께 있거나 함께 없어야 한다
    CONSTRAINT ck_question_deactivation CHECK ((deactivated_at IS NULL) = (deactivated_reason IS NULL)),
    -- AI 생성 문제는 반드시 근거 문장과 출처 문서를 가진다
    CONSTRAINT ck_question_ai_grounded CHECK (origin = 'seed' OR (doc_id IS NOT NULL AND evidence IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 보기. 다치 애트리뷰트/약한 엔티티: 문제가 삭제되면 함께 삭제된다. 문제당 정확히 4개는 애플리케이션 트랜잭션이 보장한다.
CREATE TABLE question_option (
    question_id BIGINT UNSIGNED  NOT NULL,
    seq         TINYINT UNSIGNED NOT NULL,
    option_text VARCHAR(150)     NOT NULL,
    PRIMARY KEY (question_id, seq),
    UNIQUE KEY uk_option_text (question_id, option_text),
    CONSTRAINT fk_option_question FOREIGN KEY (question_id) REFERENCES question (question_id) ON DELETE CASCADE,
    CONSTRAINT ck_option_seq CHECK (seq BETWEEN 1 AND 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 신고. 사용자와 문제 사이의 M:N 관계 → 기본 키가 (user_id, question_id)이므로 "한 사람이 한 문제를 한 번만"이 키 제약으로 보장된다.
CREATE TABLE question_report (
    user_id     BIGINT UNSIGNED NOT NULL,
    question_id BIGINT UNSIGNED NOT NULL,
    reason      VARCHAR(20)     NOT NULL,
    detail      VARCHAR(200)    NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, question_id),
    KEY idx_report_question (question_id),
    CONSTRAINT fk_report_user     FOREIGN KEY (user_id)     REFERENCES app_user (user_id),
    CONSTRAINT fk_report_question FOREIGN KEY (question_id) REFERENCES question (question_id) ON DELETE CASCADE,
    CONSTRAINT ck_report_reason CHECK (reason IN ('WRONG_ANSWER', 'AMBIGUOUS', 'OFFENSIVE', 'OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- 4. 방과 게임 기록 (진행 중 상태는 Redis(Cloud DB for Cache), 끝난 기록은 여기)
-- ----------------------------------------------------------------------------

-- 6자리 방 코드는 시간이 지나면 재사용될 수 있어 키가 될 수 없다 → 대리 키 room_id를 쓰고 code는 조회용 속성으로 둔다.
CREATE TABLE room (
    room_id        BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    code           CHAR(6)          NOT NULL,
    host_user_id   BIGINT UNSIGNED  NOT NULL,
    topic_id       INT UNSIGNED     NOT NULL,
    title          VARCHAR(50)      NOT NULL,
    capacity       TINYINT UNSIGNED NOT NULL,
    time_limit_sec TINYINT UNSIGNED NOT NULL,
    question_count TINYINT UNSIGNED NOT NULL,            -- 요청한 문제 수 (실제 문제 수는 room_question 행 수)
    status         VARCHAR(10)      NOT NULL DEFAULT 'waiting',
    created_at     DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at     DATETIME(3)      NULL,
    finished_at    DATETIME(3)      NULL,
    PRIMARY KEY (room_id),
    KEY idx_room_code (code, status),
    KEY idx_room_host (host_user_id, created_at),
    KEY idx_room_topic (topic_id),
    CONSTRAINT fk_room_host  FOREIGN KEY (host_user_id) REFERENCES app_user (user_id),
    CONSTRAINT fk_room_topic FOREIGN KEY (topic_id)     REFERENCES topic (topic_id),
    CONSTRAINT ck_room_capacity CHECK (capacity BETWEEN 2 AND 100),
    CONSTRAINT ck_room_time CHECK (time_limit_sec IN (5, 10, 15, 20, 30)),
    CONSTRAINT ck_room_count CHECK (question_count BETWEEN 1 AND 12),
    CONSTRAINT ck_room_status CHECK (status IN ('waiting', 'playing', 'finished', 'closed')),
    CONSTRAINT ck_room_times CHECK (
        (started_at IS NULL OR started_at >= created_at) AND (finished_at IS NULL OR started_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 방에 출제된 문제. 방(소유)에 종속된 약한 엔티티이자 방과 문제 사이의 M:N 관계 (속성: 출제 순서).
CREATE TABLE room_question (
    room_id     BIGINT UNSIGNED  NOT NULL,
    position    TINYINT UNSIGNED NOT NULL,               -- 출제 순서 0부터
    question_id BIGINT UNSIGNED  NOT NULL,
    PRIMARY KEY (room_id, position),
    UNIQUE KEY uk_room_question (room_id, question_id),  -- 한 방에서 같은 문제를 두 번 내지 않는다
    KEY idx_room_question_question (question_id),
    CONSTRAINT fk_rq_room     FOREIGN KEY (room_id)     REFERENCES room (room_id) ON DELETE CASCADE,
    CONSTRAINT fk_rq_question FOREIGN KEY (question_id) REFERENCES question (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 참가. 사용자와 방 사이의 M:N 관계. 점수·정답 수·순위는 answer에서 계산할 수 있는 값이지만 종료 시점에 한 번 확정해 저장한다.
CREATE TABLE participant (
    user_id        BIGINT UNSIGNED NOT NULL,
    room_id        BIGINT UNSIGNED NOT NULL,
    color          VARCHAR(7)      NULL,
    joined_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    left_at        DATETIME(3)     NULL,                 -- 도중 이탈 시각
    final_score    INT UNSIGNED    NULL,                 -- 종료 후 확정
    correct_count  SMALLINT UNSIGNED NULL,
    avg_elapsed_ms INT UNSIGNED    NULL,                 -- 동점 처리: 정답 수 → 평균 응답 속도
    final_rank     SMALLINT UNSIGNED NULL,
    PRIMARY KEY (user_id, room_id),
    KEY idx_participant_room (room_id, final_rank),
    CONSTRAINT fk_participant_user FOREIGN KEY (user_id) REFERENCES app_user (user_id),
    CONSTRAINT fk_participant_room FOREIGN KEY (room_id) REFERENCES room (room_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 응답. 참가자와 방 문제 사이의 M:N 관계 (속성: 선택, 응답 시간, 정오, 점수).
-- 이 기록은 채점 시점의 사실이므로 이후 문제가 비활성화되어도 바뀌지 않는다.
CREATE TABLE answer (
    user_id    BIGINT UNSIGNED  NOT NULL,
    room_id    BIGINT UNSIGNED  NOT NULL,
    position   TINYINT UNSIGNED NOT NULL,
    choice_seq TINYINT UNSIGNED NULL,                    -- NULL = 시간 초과(미제출)
    elapsed_ms INT UNSIGNED     NULL,
    is_correct BOOLEAN          NOT NULL,
    points     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, room_id, position),
    KEY idx_answer_room_position (room_id, position),
    KEY idx_answer_user_correct (user_id, is_correct),   -- 마이페이지 정답률
    CONSTRAINT fk_answer_participant   FOREIGN KEY (user_id, room_id)   REFERENCES participant (user_id, room_id) ON DELETE CASCADE,
    CONSTRAINT fk_answer_room_question FOREIGN KEY (room_id, position)  REFERENCES room_question (room_id, position) ON DELETE CASCADE,
    CONSTRAINT ck_answer_choice CHECK (choice_seq IS NULL OR choice_seq BETWEEN 1 AND 4),
    CONSTRAINT ck_answer_timeout CHECK ((choice_seq IS NULL) = (elapsed_ms IS NULL)),
    CONSTRAINT ck_answer_points CHECK (is_correct OR points = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
