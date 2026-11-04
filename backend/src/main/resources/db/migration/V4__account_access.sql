-- NEXUS 360 Release 2.1: access requests, single-use invitation and password-reset links (docs/PUBLIC_ACCESS_SPEC.md §3)
-- and the two notification categories that announce new requests to user administrators.

-- ============================================================================ 1. account requests
CREATE TABLE account_requests (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(20)   NOT NULL,
    email           VARCHAR(160)  NOT NULL,
    full_name       VARCHAR(120),
    department_text VARCHAR(120),
    job_title       VARCHAR(120),
    reason          VARCHAR(1000),
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    user_id         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    decided_by_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    decided_at      TIMESTAMPTZ,
    decision_note   VARCHAR(500),
    request_ip      VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_account_request_type CHECK (type IN ('ACCESS', 'PASSWORD_RESET')),
    CONSTRAINT ck_account_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'COMPLETED')),
    CONSTRAINT ck_account_request_access CHECK (type <> 'ACCESS' OR (full_name IS NOT NULL AND reason IS NOT NULL)),
    CONSTRAINT ck_account_request_decided CHECK (status = 'PENDING' OR decided_at IS NOT NULL),
    CONSTRAINT ck_account_request_rejected CHECK (status <> 'REJECTED' OR decision_note IS NOT NULL)
);
-- At most one request awaiting review per e-mail address and type.
CREATE UNIQUE INDEX ux_account_requests_pending ON account_requests (lower(email), type) WHERE status = 'PENDING';
CREATE INDEX ix_account_requests_type_status ON account_requests (type, status, created_at DESC);
CREATE INDEX ix_account_requests_created ON account_requests (created_at DESC);

-- ============================================================================ 2. single-use links
-- Only the SHA-256 hash of a link token is stored (enforced below: 64 lower-case hex characters).
CREATE TABLE account_tokens (
    id                 BIGSERIAL PRIMARY KEY,
    purpose            VARCHAR(20)  NOT NULL,
    token_hash         VARCHAR(64)  NOT NULL UNIQUE,
    email              VARCHAR(160) NOT NULL,
    full_name          VARCHAR(120),
    role               VARCHAR(20),
    department_id      BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    job_title          VARCHAR(120),
    user_id            BIGINT REFERENCES users (id) ON DELETE CASCADE,
    account_request_id BIGINT REFERENCES account_requests (id) ON DELETE SET NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    used_at            TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    created_by_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_account_token_purpose CHECK (purpose IN ('INVITE', 'PASSWORD_RESET')),
    CONSTRAINT ck_account_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_account_token_role CHECK (role IS NULL OR role IN
        ('SYSTEM_ADMIN', 'IT_ADMIN', 'IT_MANAGER', 'INCIDENT_MANAGER', 'SUPPORT_AGENT', 'EMPLOYEE', 'AUDITOR')),
    CONSTRAINT ck_account_token_invite CHECK (purpose <> 'INVITE' OR role IS NOT NULL),
    CONSTRAINT ck_account_token_reset CHECK (purpose <> 'PASSWORD_RESET' OR user_id IS NOT NULL)
);
CREATE INDEX ix_account_tokens_open ON account_tokens (lower(email), purpose) WHERE used_at IS NULL AND revoked_at IS NULL;
CREATE INDEX ix_account_tokens_request ON account_tokens (account_request_id) WHERE account_request_id IS NOT NULL;

-- ============================================================================ 3. notification categories
ALTER TABLE notification_preferences DROP CONSTRAINT ck_notification_pref_type;
ALTER TABLE notification_preferences ADD CONSTRAINT ck_notification_pref_type CHECK (type IN ('INCIDENT_ASSIGNED', 'INCIDENT_COMMENT',
    'INCIDENT_RESOLVED', 'SLA_AT_RISK', 'SLA_BREACHED', 'ESCALATION', 'APPROVAL_REQUESTED', 'APPROVAL_DECIDED', 'WORKFLOW_FAILED',
    'REQUEST_UPDATED', 'ANNOUNCEMENT', 'ACCESS_REQUEST', 'PASSWORD_RESET_REQUEST'));
