-- Studio(관리자 서비스) 스키마 — KT MySQL 8.4 · 스키마 studio · utf8mb4.
-- 업무 데이터(공연 · 상품 · 회원)는 여기 없습니다. AWS 서비스의 내부 API 로만 읽고 씁니다.
-- 시각은 전부 UTC DATETIME(3). enum 값은 VARCHAR + CHECK (Java enum 과 함께 고칩니다 — StudioSchemaTest 가 대조).

-- 관리자 계정 — 아이디 · 비밀번호(bcrypt) · OTP 비밀키(AES-GCM 암호문). 계정마다 역할 하나.
-- 🔴 행을 지우지 않습니다 (audit_log · stage_job 과 AWS 쪽 기록이 이 id 를 가리킵니다). 퇴사 · 업무 종료는 DISABLED.
CREATE TABLE admin_account (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    login_id        VARCHAR(32)    NOT NULL,
    name            VARCHAR(50)    NOT NULL,
    password_hash   VARCHAR(60)    NOT NULL,              -- bcrypt
    role            VARCHAR(16)    NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    totp_secret_enc VARBINARY(128) NULL,                  -- NULL = OTP 미등록 → 다음 로그인 때 등록
    totp_last_step  BIGINT         NULL,                  -- 마지막으로 통과한 30초 단계 (같은 코드 재사용 거부)
    failed_count    INT            NOT NULL DEFAULT 0,    -- 비밀번호 · OTP 연속 실패
    locked_until    DATETIME(3)    NULL,
    last_login_at   DATETIME(3)    NULL,
    created_at      DATETIME(3)    NOT NULL,
    updated_at      DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_admin_login_id UNIQUE (login_id),
    CONSTRAINT ck_admin_role CHECK (role IN ('MASTER', 'STAGE', 'CONCERT', 'GOODS', 'CS')),
    CONSTRAINT ck_admin_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 로그인 2단계 대기 — 1차(아이디 · 비밀번호) 통과 뒤 OTP 를 넣기까지. 5분 · 1회용. 쿠키 값의 해시만 둡니다.
CREATE TABLE admin_login_challenge (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    token_hash        VARCHAR(64)    NOT NULL,            -- SHA-256(쿠키 값)
    admin_id          BIGINT         NOT NULL,
    enroll_secret_enc VARBINARY(128) NULL,                -- OTP 등록 중일 때만 — 코드가 맞으면 admin_account 로 옮깁니다
    client_ip         VARCHAR(45)    NOT NULL,
    attempts          INT            NOT NULL DEFAULT 0,
    expires_at        DATETIME(3)    NOT NULL,
    used_at           DATETIME(3)    NULL,
    created_at        DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_challenge_token UNIQUE (token_hash),
    CONSTRAINT fk_challenge_admin FOREIGN KEY (admin_id) REFERENCES admin_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 세션 — 불투명 토큰(쿠키)의 해시. 요청마다 조회하므로 역할 변경 · 비활성화가 즉시 반영됩니다.
CREATE TABLE admin_session (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    token_hash   VARCHAR(64) NOT NULL,                    -- SHA-256(쿠키 값)
    admin_id     BIGINT      NOT NULL,
    csrf_token   VARCHAR(64) NOT NULL,                    -- 상태 변경 요청의 X-CSRF-Token 과 비교
    client_ip    VARCHAR(45) NOT NULL,
    created_at   DATETIME(3) NOT NULL,
    expires_at   DATETIME(3) NOT NULL,                    -- 로그인 + 8시간
    last_seen_at DATETIME(3) NOT NULL,                    -- 30분 동안 요청이 없으면 만료
    revoked_at   DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_session_token UNIQUE (token_hash),
    CONSTRAINT fk_session_admin FOREIGN KEY (admin_id) REFERENCES admin_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_session_admin ON admin_session (admin_id, revoked_at);

-- 무대 그리기 작업 — 관리자가 그린 도면을 GPU 서버가 검증하고, 관리자가 결과를 확인해 Concert 로 보냅니다.
CREATE TABLE stage_job (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    venue_id      BIGINT       NOT NULL,                  -- concert.venue.id (AWS · FK 없음)
    venue_name    VARCHAR(100) NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    drawing_json  MEDIUMTEXT   NOT NULL,                  -- 관리자가 그린 값 (GPU 로 보낸 그대로)
    result_json   MEDIUMTEXT   NULL,                      -- GPU 가 돌려준 값
    sent_json     MEDIUMTEXT   NULL,                      -- Concert 로 실제 보낸 값 (관리자가 고친 뒤)
    error_message VARCHAR(255) NULL,
    requested_by  BIGINT       NOT NULL,
    sent_by       BIGINT       NULL,
    created_at    DATETIME(3)  NOT NULL,
    analyzed_at   DATETIME(3)  NULL,
    sent_at       DATETIME(3)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_stage_job_requester FOREIGN KEY (requested_by) REFERENCES admin_account (id),
    CONSTRAINT fk_stage_job_sender FOREIGN KEY (sent_by) REFERENCES admin_account (id),
    CONSTRAINT ck_stage_job_status CHECK (status IN ('PENDING', 'ANALYZING', 'REVIEW', 'SENT', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_stage_job_venue ON stage_job (venue_id, id);

-- 도면 사진 — S3 를 쓰지 않고 DB 에 둡니다 (11/13 결정). 목록 조회에 딸려 오지 않게 표를 나눴습니다.
CREATE TABLE stage_job_image (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    job_id       BIGINT      NOT NULL,
    kind         VARCHAR(16) NOT NULL,                    -- ORIGINAL = 관리자가 올린 사진 · RESULT = GPU 가 돌려준 사진
    content_type VARCHAR(32) NOT NULL,
    data         LONGBLOB    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_stage_job_image UNIQUE (job_id, kind),
    CONSTRAINT fk_stage_job_image_job FOREIGN KEY (job_id) REFERENCES stage_job (id) ON DELETE CASCADE,
    CONSTRAINT ck_stage_job_image_kind CHECK (kind IN ('ORIGINAL', 'RESULT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 작업 기록 — 로그인한 관리자의 요청 1건 = 1행 (조회 포함 · 권한 밖 시도 포함). 개인정보 값 · 검색어 · 본문은 남기지 않습니다.
CREATE TABLE audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(3)  NOT NULL,
    admin_id    BIGINT       NOT NULL,
    role        VARCHAR(16)  NOT NULL,                    -- 그 시점의 역할
    method      VARCHAR(7)   NOT NULL,
    path        VARCHAR(255) NOT NULL,                    -- 쿼리스트링 제외
    status      INT          NOT NULL,                    -- HTTP 상태
    result      VARCHAR(8)   NOT NULL,                    -- OK · FAILED · DENIED(권한 밖 — 응답은 404)
    client_ip   VARCHAR(45)  NOT NULL,
    duration_ms INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_admin FOREIGN KEY (admin_id) REFERENCES admin_account (id),
    CONSTRAINT ck_audit_result CHECK (result IN ('OK', 'FAILED', 'DENIED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_audit_admin ON audit_log (admin_id, id);
