-- SA Release 4 / BE-7 (docs/RELEASE4_SPEC.md §4.5, §4.6): SA Autonomous Remediation (execution systems, versioned playbooks with
-- approval gates, policy-checked runs with automatic rollback, tamper-evident hash-chained remediation_audit) and SA Security
-- Operations (security incidents with response tasks, vulnerability sources and findings across GENERIC_JSON / SARIF / TRIVY_JSON /
-- CSV with risk scoring, security correlation groups, response templates).

-- ============================================================================ 1. approval types
ALTER TABLE approvals ALTER COLUMN approval_type TYPE VARCHAR(30);
ALTER TABLE approvals DROP CONSTRAINT ck_approval_type;
ALTER TABLE approvals ADD CONSTRAINT ck_approval_type CHECK (approval_type IN ('SERVICE_REQUEST', 'RUNBOOK_EXECUTION', 'RUNBOOK_STEP',
    'AI_ACTION', 'CHANGE', 'PIR', 'WORKFLOW', 'PLAYBOOK', 'VULNERABILITY_ACCEPTANCE'));

-- ============================================================================ 2. autonomous remediation
CREATE TABLE execution_systems (
    id                    BIGSERIAL PRIMARY KEY,
    name                  VARCHAR(140) NOT NULL UNIQUE,
    kind                  VARCHAR(30)  NOT NULL,
    endpoint_url          VARCHAR(500),
    encrypted_credentials TEXT,
    credential_scope      VARCHAR(500),
    action_templates      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status                VARCHAR(30)  NOT NULL DEFAULT 'NOT_CONNECTED',
    last_tested_at        TIMESTAMPTZ,
    last_error            VARCHAR(500),
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    is_demo               BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_exec_system_kind CHECK (kind IN ('HTTP_WEBHOOK', 'ANSIBLE_AWX', 'KUBERNETES', 'AWS_SSM', 'SIMULATED')),
    CONSTRAINT ck_exec_system_status CHECK (status IN ('NOT_CONNECTED', 'CONNECTED', 'SIMULATED', 'ERROR')),
    CONSTRAINT ck_exec_system_templates CHECK (jsonb_typeof(action_templates) = 'array')
);

CREATE TABLE remediation_playbooks (
    id                       BIGSERIAL PRIMARY KEY,
    code                     VARCHAR(60)   NOT NULL UNIQUE,
    name                     VARCHAR(160)  NOT NULL,
    description              VARCHAR(1000),
    target_environment       VARCHAR(30)   NOT NULL DEFAULT 'PRODUCTION',
    pre_approved_alert_types JSONB         NOT NULL DEFAULT '[]'::jsonb,
    max_blast_radius         INT           NOT NULL DEFAULT 5,
    rate_limit_per_hour      INT           NOT NULL DEFAULT 4,
    require_linked_record    BOOLEAN       NOT NULL DEFAULT TRUE,
    active_version           INT,
    enabled                  BOOLEAN       NOT NULL DEFAULT TRUE,
    is_demo                  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_playbook_env CHECK (target_environment IN ('DEVELOPMENT', 'STAGING', 'PRODUCTION', 'ALL')),
    CONSTRAINT ck_playbook_alert_types CHECK (jsonb_typeof(pre_approved_alert_types) = 'array')
);

CREATE TABLE remediation_playbook_versions (
    id                BIGSERIAL PRIMARY KEY,
    playbook_id       BIGINT       NOT NULL REFERENCES remediation_playbooks (id) ON DELETE CASCADE,
    version           INT          NOT NULL,
    status            VARCHAR(30)  NOT NULL,
    trigger_kinds     JSONB        NOT NULL DEFAULT '["MANUAL"]'::jsonb,
    parameters_schema JSONB        NOT NULL DEFAULT '{}'::jsonb,
    steps             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    recovery_criteria JSONB        NOT NULL DEFAULT '{}'::jsonb,
    approval_id       BIGINT REFERENCES approvals (id) ON DELETE SET NULL,
    created_by_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_by_name   VARCHAR(140),
    approved_by_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    approved_by_name  VARCHAR(140),
    approved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_playbook_version UNIQUE (playbook_id, version),
    CONSTRAINT ck_playbook_version_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'RETIRED')),
    CONSTRAINT ck_playbook_version_steps CHECK (jsonb_typeof(steps) = 'array')
);
CREATE INDEX ix_playbook_versions_playbook ON remediation_playbook_versions (playbook_id, version DESC);

CREATE TABLE remediation_runs (
    id                  BIGSERIAL PRIMARY KEY,
    playbook_id         BIGINT REFERENCES remediation_playbooks (id) ON DELETE SET NULL,
    playbook_code       VARCHAR(60),
    playbook_name       VARCHAR(160),
    playbook_version    INT           NOT NULL,
    trigger_kind        VARCHAR(30)   NOT NULL,
    status              VARCHAR(30)   NOT NULL,
    target_key          VARCHAR(160)  NOT NULL,
    environment         VARCHAR(30)   NOT NULL DEFAULT 'PRODUCTION',
    incident_id         BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    change_id           BIGINT REFERENCES changes (id) ON DELETE SET NULL,
    alert_id            BIGINT REFERENCES alerts (id) ON DELETE SET NULL,
    parameters          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    policy_checks       JSONB         NOT NULL DEFAULT '[]'::jsonb,
    override_reason     VARCHAR(500),
    overridden_by_id    BIGINT,
    overridden_by_name  VARCHAR(140),
    approval_id         BIGINT REFERENCES approvals (id) ON DELETE SET NULL,
    verification_result JSONB,
    failure_reason      VARCHAR(1000),
    requested_by_id     BIGINT,
    requested_by_name   VARCHAR(140),
    simulated           BOOLEAN       NOT NULL DEFAULT FALSE,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_remediation_trigger CHECK (trigger_kind IN ('MANUAL', 'AGENT', 'WORKFLOW', 'ALERT_RULE')),
    CONSTRAINT ck_remediation_run_status CHECK (status IN ('BLOCKED', 'PENDING_APPROVAL', 'APPROVED', 'RUNNING', 'PAUSED', 'VERIFYING',
        'SUCCEEDED', 'FAILED', 'ROLLING_BACK', 'ROLLED_BACK', 'ROLLBACK_FAILED', 'CANCELLED'))
);
CREATE INDEX ix_remediation_runs_created ON remediation_runs (created_at DESC);
CREATE INDEX ix_remediation_runs_target ON remediation_runs (target_key, created_at DESC);
CREATE INDEX ix_remediation_runs_incident ON remediation_runs (incident_id) WHERE incident_id IS NOT NULL;

CREATE TABLE remediation_run_steps (
    id               BIGSERIAL PRIMARY KEY,
    run_id           BIGINT        NOT NULL REFERENCES remediation_runs (id) ON DELETE CASCADE,
    step_order       INT           NOT NULL,
    name             VARCHAR(160)  NOT NULL,
    kind             VARCHAR(30)   NOT NULL,
    is_rollback      BOOLEAN       NOT NULL DEFAULT FALSE,
    status           VARCHAR(30)   NOT NULL,
    request_summary  JSONB         NOT NULL DEFAULT '{}'::jsonb,
    response_summary JSONB         NOT NULL DEFAULT '{}'::jsonb,
    attempts         INT           NOT NULL DEFAULT 0,
    duration_ms      BIGINT,
    error_message    VARCHAR(1000),
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    CONSTRAINT ck_remediation_step_status CHECK (status IN ('PENDING', 'RUNNING', 'WAITING_CONFIRM', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'ROLLED_BACK'))
);
CREATE INDEX ix_remediation_run_steps_run ON remediation_run_steps (run_id, step_order, id);

CREATE TABLE remediation_audit (
    id          BIGSERIAL PRIMARY KEY,
    event_type  VARCHAR(60)  NOT NULL,
    run_id      BIGINT,
    playbook_id BIGINT,
    actor_id    BIGINT,
    actor_name  VARCHAR(140),
    payload     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    prev_hash   VARCHAR(64)  NOT NULL,
    hash        VARCHAR(64)  NOT NULL
);
CREATE INDEX ix_remediation_audit_run ON remediation_audit (run_id, id);

CREATE OR REPLACE FUNCTION forbid_remediation_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'remediation_audit is append-only and tamper-evident';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_remediation_audit_immutable
    BEFORE UPDATE OR DELETE ON remediation_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_remediation_audit_mutation();

-- ============================================================================ 3. security operations
CREATE SEQUENCE security_incident_number_seq START WITH 4001;

CREATE TABLE security_incidents (
    id                  BIGSERIAL PRIMARY KEY,
    number              VARCHAR(30)  UNIQUE,
    title               VARCHAR(240) NOT NULL,
    description         TEXT,
    category            VARCHAR(40)  NOT NULL,
    severity            VARCHAR(20)  NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'NEW',
    linked_incident_id  BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    affected_ci_id      BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    affected_service_id BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    affected_user_email VARCHAR(160),
    assigned_to_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    assigned_to_name    VARCHAR(140),
    indicators          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    evidence_notes      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    lessons_learned     TEXT,
    simulated           BOOLEAN      NOT NULL DEFAULT FALSE,
    is_demo             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id       BIGINT,
    created_by_name     VARCHAR(140),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at           TIMESTAMPTZ,
    CONSTRAINT ck_sec_incident_category CHECK (category IN ('MALWARE', 'PHISHING', 'UNAUTHORISED_ACCESS', 'DATA_EXPOSURE',
        'VULNERABILITY_EXPLOITATION', 'DOS', 'POLICY_VIOLATION')),
    CONSTRAINT ck_sec_incident_severity CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_sec_incident_status CHECK (status IN ('NEW', 'TRIAGE', 'CONTAINMENT', 'ERADICATION', 'RECOVERY', 'CLOSED'))
);
CREATE INDEX ix_security_incidents_status ON security_incidents (status, created_at DESC);

CREATE TABLE security_incident_tasks (
    id                        BIGSERIAL PRIMARY KEY,
    security_incident_id      BIGINT       NOT NULL REFERENCES security_incidents (id) ON DELETE CASCADE,
    task_order                INT          NOT NULL,
    title                     VARCHAR(240) NOT NULL,
    phase                     VARCHAR(30)  NOT NULL,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    remediation_playbook_code VARCHAR(60),
    completed_by_name         VARCHAR(140),
    completed_at              TIMESTAMPTZ,
    CONSTRAINT ck_sec_task_phase CHECK (phase IN ('TRIAGE', 'CONTAINMENT', 'ERADICATION', 'RECOVERY')),
    CONSTRAINT ck_sec_task_status CHECK (status IN ('OPEN', 'COMPLETED', 'SKIPPED'))
);
CREATE INDEX ix_security_incident_tasks_incident ON security_incident_tasks (security_incident_id, task_order);

CREATE TABLE vulnerability_sources (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(140) NOT NULL UNIQUE,
    format           VARCHAR(30)  NOT NULL,
    scanner_name     VARCHAR(80)  NOT NULL,
    token_hash       VARCHAR(64)  NOT NULL,
    token_prefix     VARCHAR(16)  NOT NULL,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    last_ingested_at TIMESTAMPTZ,
    is_demo          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_vuln_source_format CHECK (format IN ('GENERIC_JSON', 'SARIF', 'TRIVY_JSON', 'CSV'))
);

CREATE TABLE vulnerability_findings (
    id                BIGSERIAL PRIMARY KEY,
    source_id         BIGINT           NOT NULL REFERENCES vulnerability_sources (id) ON DELETE CASCADE,
    dedupe_key        VARCHAR(240)     NOT NULL,
    cve_id            VARCHAR(60)      NOT NULL,
    cwe_id            VARCHAR(40),
    title             VARCHAR(300)     NOT NULL,
    scanner           VARCHAR(80)      NOT NULL,
    asset_name        VARCHAR(160)     NOT NULL,
    ci_id             BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    service_id        BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    package_name      VARCHAR(160)     NOT NULL,
    installed_version VARCHAR(80),
    fixed_version     VARCHAR(80),
    cvss_base         DOUBLE PRECISION NOT NULL,
    cvss_vector       VARCHAR(160),
    asset_criticality DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    internet_exposed  BOOLEAN          NOT NULL DEFAULT FALSE,
    exploit_known     BOOLEAN          NOT NULL DEFAULT FALSE,
    risk_score        DOUBLE PRECISION NOT NULL,
    risk_band         VARCHAR(20)      NOT NULL,
    sla_days          INT              NOT NULL,
    status            VARCHAR(30)      NOT NULL DEFAULT 'OPEN',
    accepted_until    TIMESTAMPTZ,
    acceptance_reason VARCHAR(500),
    approval_id       BIGINT REFERENCES approvals (id) ON DELETE SET NULL,
    first_seen_at     TIMESTAMPTZ      NOT NULL,
    last_seen_at      TIMESTAMPTZ      NOT NULL,
    simulated         BOOLEAN          NOT NULL DEFAULT FALSE,
    CONSTRAINT ux_vuln_finding_dedupe UNIQUE (source_id, dedupe_key),
    CONSTRAINT ck_vuln_finding_band CHECK (risk_band IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_vuln_finding_status CHECK (status IN ('OPEN', 'FIXED', 'ACCEPTED', 'FALSE_POSITIVE'))
);
CREATE INDEX ix_vuln_findings_score ON vulnerability_findings (risk_score DESC, id DESC);
CREATE INDEX ix_vuln_findings_status ON vulnerability_findings (status, risk_band);

CREATE TABLE security_correlation_groups (
    id                   BIGSERIAL PRIMARY KEY,
    group_key            VARCHAR(160) NOT NULL,
    title                VARCHAR(240) NOT NULL,
    target_asset         VARCHAR(160),
    target_user          VARCHAR(160),
    severity             VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    signal_summary       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    security_incident_id BIGINT REFERENCES security_incidents (id) ON DELETE SET NULL,
    simulated            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_sec_corr_status CHECK (status IN ('OPEN', 'ESCALATED', 'DISMISSED'))
);
CREATE INDEX ix_sec_corr_groups_status ON security_correlation_groups (status, created_at DESC);

CREATE TABLE security_response_templates (
    id                        BIGSERIAL PRIMARY KEY,
    code                      VARCHAR(60)  NOT NULL UNIQUE,
    name                      VARCHAR(160) NOT NULL,
    category                  VARCHAR(40)  NOT NULL,
    description               VARCHAR(500),
    tasks                     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    recommended_playbook_code VARCHAR(60),
    is_demo                   BOOLEAN      NOT NULL DEFAULT FALSE
);
