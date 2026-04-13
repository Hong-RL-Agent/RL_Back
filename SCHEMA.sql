CREATE TYPE user_role AS ENUM ('ADMIN', 'USER');

CREATE TABLE user_account (
                              id          BIGSERIAL PRIMARY KEY,
                              email       VARCHAR(100)  NOT NULL UNIQUE,
                              password_hash VARCHAR(255) NOT NULL,
                              user_name   VARCHAR(50)   NOT NULL,
                              role        user_role     NOT NULL DEFAULT 'USER',
                              created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- test_session
CREATE TYPE session_status AS ENUM ('READY', 'RUNNING', 'COMPLETED', 'FAILED');

CREATE TABLE test_session (
                              id           BIGSERIAL PRIMARY KEY,
                              user_id      BIGINT        NOT NULL,
                              session_uuid VARCHAR(50)   NOT NULL UNIQUE,
                              target_url   VARCHAR(512)  NOT NULL,
                              status       session_status NOT NULL DEFAULT 'READY',
                              agent_config JSONB,                          -- JSON → JSONB (인덱스/검색 가능)
                              created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              ended_at     TIMESTAMP,

                              CONSTRAINT fk_test_session_user
                                  FOREIGN KEY (user_id) REFERENCES user_account(id)
);

-- action_log
CREATE TABLE action_log (
                            id               BIGSERIAL PRIMARY KEY,
                            session_id       BIGINT        NOT NULL,
                            action_type      VARCHAR(50),
                            target_selector  TEXT,
                            input_value      TEXT,
                            current_url      VARCHAR(512),
                            reward_score     DOUBLE PRECISION DEFAULT 0.0,
                            screenshot_url   VARCHAR(512),
                            created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            CONSTRAINT fk_action_log_session
                                FOREIGN KEY (session_id) REFERENCES test_session(id)
);

-- detected_bug
-- ENUM('SERVER', 'CLIENT', 'NETWORK', 'SEMANTIC') → TYPE
CREATE TYPE error_scope_type AS ENUM ('SERVER', 'CLIENT', 'NETWORK', 'SEMANTIC');

CREATE TABLE detected_bug (
                              id                       BIGSERIAL PRIMARY KEY,
                              session_id               BIGINT           NOT NULL,
                              action_id                BIGINT,
                              category_code            VARCHAR(50)      NOT NULL,
                              error_scope              error_scope_type,
                              severity                 INT CHECK (severity BETWEEN 1 AND 5),
                              error_message            TEXT             NOT NULL,
                              stack_trace              TEXT,                        -- LONGTEXT → TEXT
                              is_embedded              BOOLEAN          NOT NULL DEFAULT FALSE,
                              embedded_vector_metadata JSONB,                      -- JSON → JSONB

                              CONSTRAINT fk_detected_bug_session
                                  FOREIGN KEY (session_id) REFERENCES test_session(id),
                              CONSTRAINT fk_detected_bug_action
                                  FOREIGN KEY (action_id) REFERENCES action_log(id)
);

-- ai_analysis_log
CREATE TABLE ai_analysis_log (
                                 id                     BIGSERIAL PRIMARY KEY,
                                 bug_id                 BIGINT           NOT NULL,
                                 referenced_context_ids JSONB,                        -- JSON → JSONB
                                 similarity_score       DOUBLE PRECISION,
                                 solution_text          TEXT,                         -- LONGTEXT → TEXT
                                 prompt_used            TEXT,
                                 token_usage            INT,
                                 created_at             TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                 CONSTRAINT fk_ai_analysis_bug
                                     FOREIGN KEY (bug_id) REFERENCES detected_bug(id)
);

-- pdf_report
CREATE TABLE pdf_report (
                            id         BIGSERIAL PRIMARY KEY,
                            session_id BIGINT       NOT NULL,
                            file_path  VARCHAR(512),
                            total_bugs INT,
                            created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            CONSTRAINT fk_pdf_report_session
                                FOREIGN KEY (session_id) REFERENCES test_session(id)
);

-- system_health
-- ON UPDATE CURRENT_TIMESTAMP → PostgreSQL 미지원, 트리거로 처리
CREATE TABLE system_health (
                               id             BIGSERIAL PRIMARY KEY,
                               component_name VARCHAR(50)  NOT NULL UNIQUE,
                               status         VARCHAR(20),
                               ip_address     VARCHAR(50),
                               last_ping      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- last_ping 자동 갱신 트리거 (MySQL의 ON UPDATE CURRENT_TIMESTAMP 대체)
CREATE OR REPLACE FUNCTION update_last_ping()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_ping = CURRENT_TIMESTAMP;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_system_health_last_ping
    BEFORE UPDATE ON system_health
    FOR EACH ROW
    EXECUTE FUNCTION update_last_ping();

-- internal_error_log
CREATE TABLE internal_error_log (
                                    id           BIGSERIAL PRIMARY KEY,
                                    error_source VARCHAR(50),
                                    message      TEXT,
                                    stack_trace  TEXT,                             -- LONGTEXT → TEXT
                                    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);