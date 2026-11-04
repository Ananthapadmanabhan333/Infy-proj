-- SA Release 4 / BE-8 (docs/RELEASE4_SPEC.md §4.7, §4.8): SA FinOps (FOCUS/mapped CSV cost sources and items, allocation rules,
-- budgets with thresholds and forecasts, daily cost anomalies, savings recommendations) and SA Digital Employee Experience
-- (endpoint health, application performance events, login events, proactive incident rules).

-- ============================================================================ 1. FinOps
CREATE TABLE finops_cost_sources (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(140) NOT NULL UNIQUE,
    kind             VARCHAR(30)  NOT NULL,
    provider_name    VARCHAR(60)  NOT NULL,
    column_mapping   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status           VARCHAR(30)  NOT NULL DEFAULT 'READY',
    last_ingested_at TIMESTAMPTZ,
    is_demo          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_finops_source_kind CHECK (kind IN ('FOCUS_CSV', 'MAPPED_CSV', 'AWS_CUR', 'AZURE_COST', 'GCP_BILLING'))
);

CREATE TABLE finops_cost_items (
    id                      BIGSERIAL PRIMARY KEY,
    source_id               BIGINT           NOT NULL REFERENCES finops_cost_sources (id) ON DELETE CASCADE,
    dedupe_key              VARCHAR(240)     NOT NULL,
    charge_date             DATE             NOT NULL,
    provider_name           VARCHAR(60)      NOT NULL,
    sub_account_id          VARCHAR(120),
    service_name            VARCHAR(140)     NOT NULL,
    resource_id             VARCHAR(200)     NOT NULL,
    resource_name           VARCHAR(200),
    region_id               VARCHAR(80),
    billed_cost             NUMERIC(14, 4)   NOT NULL,
    effective_cost          NUMERIC(14, 4)   NOT NULL,
    currency                VARCHAR(10)      NOT NULL DEFAULT 'USD',
    tags                    JSONB            NOT NULL DEFAULT '{}'::jsonb,
    allocated_service_id    BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    allocated_department_id BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    allocated_cost_center   VARCHAR(80),
    cpu_util_avg            DOUBLE PRECISION,
    mem_util_avg            DOUBLE PRECISION,
    simulated               BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ux_finops_cost_item UNIQUE (source_id, dedupe_key)
);
CREATE INDEX ix_finops_cost_items_date ON finops_cost_items (charge_date DESC);
CREATE INDEX ix_finops_cost_items_service ON finops_cost_items (allocated_service_id);

CREATE TABLE finops_allocation_rules (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(140)     NOT NULL,
    priority             INT              NOT NULL DEFAULT 100,
    tag_key              VARCHAR(80),
    tag_value            VARCHAR(120),
    sub_account_id       VARCHAR(120),
    resource_pattern     VARCHAR(160),
    target_service_id    BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    target_department_id BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    target_cost_center   VARCHAR(80)      NOT NULL,
    split_percent        DOUBLE PRECISION NOT NULL DEFAULT 100.0,
    enabled              BOOLEAN          NOT NULL DEFAULT TRUE,
    is_demo              BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX ix_finops_alloc_priority ON finops_allocation_rules (priority ASC, id ASC);

CREATE TABLE finops_budgets (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(140)   NOT NULL,
    target_type             VARCHAR(30)    NOT NULL,
    target_key              VARCHAR(120)   NOT NULL,
    period_month            VARCHAR(7)     NOT NULL,
    amount                  NUMERIC(14, 2) NOT NULL,
    thresholds              JSONB          NOT NULL DEFAULT '[50, 80, 100]'::jsonb,
    last_notified_threshold INT            NOT NULL DEFAULT 0,
    is_demo                 BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_finops_budget_target CHECK (target_type IN ('GLOBAL', 'SERVICE', 'DEPARTMENT', 'COST_CENTER'))
);

CREATE TABLE finops_anomalies (
    id              BIGSERIAL PRIMARY KEY,
    scope_key       VARCHAR(160)     NOT NULL,
    charge_date     DATE             NOT NULL,
    actual_cost     NUMERIC(14, 4)   NOT NULL,
    expected_cost   NUMERIC(14, 4)   NOT NULL,
    upper_bound     NUMERIC(14, 4)   NOT NULL,
    deviation_score DOUBLE PRECISION NOT NULL,
    status          VARCHAR(20)      NOT NULL DEFAULT 'OPEN',
    simulated       BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX ix_finops_anomalies_date ON finops_anomalies (charge_date DESC);

CREATE TABLE finops_recommendations (
    id                        BIGSERIAL PRIMARY KEY,
    category                  VARCHAR(40)    NOT NULL,
    resource_id               VARCHAR(200)   NOT NULL,
    resource_name             VARCHAR(200),
    service_name              VARCHAR(140),
    title                     VARCHAR(240)   NOT NULL,
    evidence                  VARCHAR(1000)  NOT NULL,
    estimated_monthly_savings NUMERIC(14, 2) NOT NULL,
    status                    VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    change_request_id         BIGINT REFERENCES changes (id) ON DELETE SET NULL,
    dismiss_reason            VARCHAR(500),
    simulated                 BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_finops_rec_category CHECK (category IN ('RIGHTSIZING', 'IDLE_SHUTDOWN', 'STORAGE_TIERING', 'COMMITMENT_COVERAGE')),
    CONSTRAINT ck_finops_rec_status CHECK (status IN ('OPEN', 'ACCEPTED', 'DISMISSED'))
);

-- ============================================================================ 2. Digital Employee Experience (DEX)
CREATE TABLE dex_endpoints (
    id                  BIGSERIAL PRIMARY KEY,
    device_id           VARCHAR(120)     NOT NULL UNIQUE,
    user_id             BIGINT REFERENCES users (id) ON DELETE SET NULL,
    user_email          VARCHAR(160)     NOT NULL,
    user_name           VARCHAR(140),
    department_id       BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    department_name     VARCHAR(120),
    os_name             VARCHAR(60)      NOT NULL,
    os_version          VARCHAR(60)      NOT NULL,
    device_model        VARCHAR(120)     NOT NULL,
    cpu_usage_pct       DOUBLE PRECISION NOT NULL,
    memory_usage_pct    DOUBLE PRECISION NOT NULL,
    disk_usage_pct      DOUBLE PRECISION NOT NULL,
    boot_time_seconds   DOUBLE PRECISION NOT NULL,
    crash_count_7d      INT              NOT NULL DEFAULT 0,
    battery_health_pct  DOUBLE PRECISION,
    device_health_score DOUBLE PRECISION NOT NULL,
    experience_score    DOUBLE PRECISION NOT NULL,
    score_breakdown     JSONB            NOT NULL DEFAULT '{}'::jsonb,
    last_seen_at        TIMESTAMPTZ      NOT NULL,
    simulated           BOOLEAN          NOT NULL DEFAULT FALSE,
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX ix_dex_endpoints_user ON dex_endpoints (lower(user_email));
CREATE INDEX ix_dex_endpoints_dept ON dex_endpoints (department_name);

CREATE TABLE dex_app_events (
    id              BIGSERIAL PRIMARY KEY,
    app_name        VARCHAR(120) NOT NULL,
    app_version     VARCHAR(60)  NOT NULL,
    device_id       VARCHAR(120),
    user_email      VARCHAR(160),
    department_name VARCHAR(120),
    launch_time_ms  DOUBLE PRECISION,
    page_load_ms    DOUBLE PRECISION,
    crashed         BOOLEAN      NOT NULL DEFAULT FALSE,
    hang            BOOLEAN      NOT NULL DEFAULT FALSE,
    error_code      VARCHAR(80),
    simulated       BOOLEAN      NOT NULL DEFAULT FALSE,
    recorded_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_dex_app_events_user ON dex_app_events (lower(user_email));

CREATE TABLE dex_login_events (
    id              BIGSERIAL PRIMARY KEY,
    user_email      VARCHAR(160) NOT NULL,
    department_name VARCHAR(120),
    provider        VARCHAR(60)  NOT NULL DEFAULT 'SA_AUTH',
    outcome         VARCHAR(30)  NOT NULL,
    simulated       BOOLEAN      NOT NULL DEFAULT FALSE,
    recorded_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_dex_login_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'LOCKOUT', 'MFA_FAILURE'))
);
CREATE INDEX ix_dex_login_events_user ON dex_login_events (lower(user_email));

CREATE TABLE dex_proactive_rules (
    id                         BIGSERIAL PRIMARY KEY,
    name                       VARCHAR(140)     NOT NULL,
    department_name            VARCHAR(120),
    threshold_score            DOUBLE PRECISION NOT NULL DEFAULT 60.0,
    enabled                    BOOLEAN          NOT NULL DEFAULT TRUE,
    last_triggered_incident_id BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    is_demo                    BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at                 TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- ============================================================================ 3. notification categories
ALTER TABLE notification_preferences DROP CONSTRAINT ck_notification_pref_type;
ALTER TABLE notification_preferences ADD CONSTRAINT ck_notification_pref_type CHECK (type IN ('INCIDENT_ASSIGNED', 'INCIDENT_COMMENT',
    'INCIDENT_RESOLVED', 'SLA_AT_RISK', 'SLA_BREACHED', 'ESCALATION', 'APPROVAL_REQUESTED', 'APPROVAL_DECIDED', 'WORKFLOW_FAILED',
    'REQUEST_UPDATED', 'ANNOUNCEMENT', 'ACCESS_REQUEST', 'PASSWORD_RESET_REQUEST', 'TEAM_QUEUE', 'REPORT_READY',
    'APPROVAL_ESCALATED', 'PROBLEM_UPDATED', 'CHANGE_UPDATED', 'DEPLOYMENT_STATUS',
    'ALERT_ROUTED', 'ALERT_ESCALATED', 'ASSET_ASSIGNED',
    'ONCALL_PAGE', 'ONCALL_SHIFT', 'MAJOR_INCIDENT_UPDATE', 'PIR_UPDATED',
    'PREDICTION_DRIFT', 'AGENT_RUN',
    'FINOPS_BUDGET_THRESHOLD', 'DEX_PROACTIVE_INCIDENT'));
