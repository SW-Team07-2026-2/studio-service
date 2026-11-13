-- Studio 값 목록 — 컬럼에 들어갈 수 있는 값(Java enum 과 같은 목록)을 CHECK 로 고정합니다. 구조는 V1__studio_schema.sql.
-- 값을 넣거나 빼면 이 파일과 Java enum 을 함께 고칩니다 — StudioSchemaTest 가 둘을 대조합니다.

ALTER TABLE admin_account   ADD CONSTRAINT ck_admin_role           CHECK (role IN ('MASTER', 'STAGE', 'CONCERT', 'GOODS', 'CS', 'CHECKER'));   -- Role
ALTER TABLE admin_account   ADD CONSTRAINT ck_admin_status         CHECK (status IN ('ACTIVE', 'DISABLED'));   -- AdminAccountEntity.Status
ALTER TABLE stage_job       ADD CONSTRAINT ck_stage_job_status     CHECK (status IN ('PENDING', 'ANALYZING', 'REVIEW', 'SENT', 'FAILED'));   -- StageJobEntity.Status
ALTER TABLE stage_job_image ADD CONSTRAINT ck_stage_job_image_kind CHECK (kind IN ('ORIGINAL', 'RESULT'));   -- StageJobImageEntity.Kind
ALTER TABLE audit_log       ADD CONSTRAINT ck_audit_result         CHECK (result IN ('OK', 'FAILED', 'DENIED'));   -- AuditLogEntity.Result
