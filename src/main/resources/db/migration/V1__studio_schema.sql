-- Studio(관리자 서비스) 스키마 — KT MySQL 8.4 · 스키마 studio · utf8mb4.
-- 업무 데이터(공연 · 상품 · 회원)는 여기 없습니다. AWS 서비스의 내부 API 로만 읽고 씁니다.
-- 시각은 전부 UTC DATETIME(3). enum 값은 VARCHAR 이고 값 목록(CHECK)은 V2__studio_values.sql 에 있습니다.
-- 🔴 마이그레이션은 이 파일(V1 구조)과 V2(값) 두 개만 둡니다 — 구조를 바꾸면 이 파일을 고치고 DB 스키마를 비운 뒤 다시 올립니다.
--    적용된 뒤에 파일만 고치면 Flyway 체크섬 검증에 걸려 서비스가 뜨지 않습니다.

-- 관리자 계정 — 아이디 · 비밀번호(bcrypt) · 로그인 인증 코드를 받을 메일. 계정마다 역할 하나.
-- 역할별 공용 계정이면 담당자 메일을 모두 넣습니다 (같은 코드가 모두에게 갑니다).
-- 🔴 행을 지우지 않습니다 (audit_log · stage_job 과 AWS 쪽 기록이 이 id 를 가리킵니다). 퇴사 · 업무 종료는 DISABLED.
CREATE TABLE admin_account (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    login_id        VARCHAR(32)    NOT NULL,
    name            VARCHAR(50)    NOT NULL,
    password_hash   VARCHAR(60)    NOT NULL,              -- bcrypt
    role            VARCHAR(16)    NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    otp_emails      VARCHAR(512)   NOT NULL,              -- 인증 코드를 받을 메일 1~5개 (쉼표 구분)
    failed_count    INT            NOT NULL DEFAULT 0,    -- 비밀번호 · 인증 코드 연속 실패
    locked_until    DATETIME(3)    NULL,
    last_login_at   DATETIME(3)    NULL,
    created_at      DATETIME(3)    NOT NULL,
    updated_at      DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_admin_login_id UNIQUE (login_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 로그인 2단계 대기(메일 인증 코드 입력 전 5분) · 로그인 세션은 MySQL 이 아니라 Redis 에 둡니다 (LoginChallengeStore · SessionStore).

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
    CONSTRAINT fk_stage_job_sender FOREIGN KEY (sent_by) REFERENCES admin_account (id)
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
    CONSTRAINT fk_stage_job_image_job FOREIGN KEY (job_id) REFERENCES stage_job (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 크롤러가 넘긴 공연 후보 (11/17 결정) — 공연 관리자가 확인 · 보완(공연장 · 라인업 id 등)한 뒤 Concert 에 등록합니다.
-- 같은 (source, external_id) 는 한 행. 확인 전(PENDING)에 다시 오면 값만 바뀌고, 확인한 뒤(APPROVED · REJECTED)에는 그대로 둡니다.
-- 아티스트 · 앨범 · 상품은 여기 두지 않고 바로 Catalog 로 넘깁니다 (CrawlIntakeController).
CREATE TABLE crawl_concert (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    source       VARCHAR(32)  NOT NULL,
    external_id  VARCHAR(128) NOT NULL,
    source_hash  VARCHAR(128) NULL,
    payload_json MEDIUMTEXT   NOT NULL,                   -- 크롤러가 준 공연 값 그대로
    previous_json MEDIUMTEXT  NULL,                       -- CHANGED 일 때만 — 등록(또는 마지막 변경 확인) 때의 값 (화면이 비교해 보여 줌 · 11/18)
    status       VARCHAR(16)  NOT NULL,
    received_at  DATETIME(3)  NOT NULL,                   -- 마지막으로 받은 시각
    reviewed_by  BIGINT       NULL,
    reviewed_at  DATETIME(3)  NULL,
    concert_id   BIGINT       NULL,                       -- 등록된 concert.concert.id (AWS · FK 없음)
    PRIMARY KEY (id),
    CONSTRAINT uq_crawl_concert_source UNIQUE (source, external_id),
    CONSTRAINT fk_crawl_concert_reviewer FOREIGN KEY (reviewed_by) REFERENCES admin_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_crawl_concert_status ON crawl_concert (status, id);

-- 크롤러가 넘긴 아티스트(굿즈 포함) · 앨범(트랙 · 피지컬 포함) 후보 — 상품 관리자가 승인하면 Catalog 관리자 적재로 (11/17 결정)
CREATE TABLE crawl_catalog (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    type         VARCHAR(16)  NOT NULL,                   -- ARTIST · ALBUM
    source       VARCHAR(32)  NOT NULL,
    external_id  VARCHAR(128) NOT NULL,
    source_hash  VARCHAR(128) NULL,
    payload_json MEDIUMTEXT   NOT NULL,                   -- Catalog 수집 계약 한 항목 (snake_case) · 앨범은 artist_id 대신 artist_ref
    status       VARCHAR(16)  NOT NULL,
    received_at  DATETIME(3)  NOT NULL,
    reviewed_by  BIGINT       NULL,
    reviewed_at  DATETIME(3)  NULL,
    catalog_id   BIGINT       NULL,                       -- 승인으로 생긴 catalog 아티스트 · 앨범 id (AWS · FK 없음)
    last_error   VARCHAR(500) NULL,                       -- 마지막 승인 실패 사유 (Catalog 가 거절한 이유)
    PRIMARY KEY (id),
    CONSTRAINT uq_crawl_catalog_source UNIQUE (type, source, external_id),
    CONSTRAINT fk_crawl_catalog_reviewer FOREIGN KEY (reviewed_by) REFERENCES admin_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_crawl_catalog_status ON crawl_catalog (status, id);

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
    CONSTRAINT fk_audit_admin FOREIGN KEY (admin_id) REFERENCES admin_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_audit_admin ON audit_log (admin_id, id);
