-- NEXUS 360 core schema. Every enumerated value is constrained in the database as well as in Java.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------- organisation
CREATE TABLE departments (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(500),
    manager_id  BIGINT,
    email       VARCHAR(160),
    is_demo     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_department_code CHECK (code ~ '^[A-Z0-9_-]{2,40}$')
);

CREATE TABLE users (
    id                    BIGSERIAL PRIMARY KEY,
    email                 VARCHAR(160) NOT NULL,
    full_name             VARCHAR(120) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    role                  VARCHAR(20)  NOT NULL,
    department_id         BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    job_title             VARCHAR(120),
    phone                 VARCHAR(40),
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    is_demo               BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_user_role CHECK (role IN ('ADMIN', 'MANAGER', 'AGENT', 'EMPLOYEE', 'AUDITOR')),
    CONSTRAINT ck_user_failed_logins CHECK (failed_login_attempts >= 0)
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE INDEX ix_users_department ON users (department_id);
CREATE INDEX ix_users_role ON users (role) WHERE active;

ALTER TABLE departments
    ADD CONSTRAINT fk_department_manager FOREIGN KEY (manager_id) REFERENCES users (id) ON DELETE SET NULL;

CREATE TABLE refresh_tokens (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash       VARCHAR(64)    NOT NULL UNIQUE,
    family_id        UUID        NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked_at       TIMESTAMPTZ,
    replaced_by_hash VARCHAR(64),
    user_agent       VARCHAR(255),
    ip_address       VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);

-- ---------------------------------------------------------------- SLA configuration
CREATE TABLE business_hours_calendars (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(80) NOT NULL UNIQUE,
    timezone   VARCHAR(60) NOT NULL,
    work_days  VARCHAR(80) NOT NULL,
    start_time TIME        NOT NULL,
    end_time   TIME        NOT NULL,
    is_default BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_calendar_hours CHECK (end_time > start_time)
);
CREATE UNIQUE INDEX ux_calendar_default ON business_hours_calendars (is_default) WHERE is_default;

CREATE TABLE calendar_holidays (
    id           BIGSERIAL PRIMARY KEY,
    calendar_id  BIGINT       NOT NULL REFERENCES business_hours_calendars (id) ON DELETE CASCADE,
    holiday_date DATE         NOT NULL,
    name         VARCHAR(120) NOT NULL,
    CONSTRAINT ux_calendar_holiday UNIQUE (calendar_id, holiday_date)
);

CREATE TABLE sla_policies (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(120) NOT NULL UNIQUE,
    priority            VARCHAR(10)  NOT NULL,
    response_minutes    INT          NOT NULL,
    resolution_minutes  INT          NOT NULL,
    business_hours_only BOOLEAN      NOT NULL DEFAULT FALSE,
    calendar_id         BIGINT REFERENCES business_hours_calendars (id),
    at_risk_percent     INT          NOT NULL DEFAULT 75,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    description         VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_sla_priority CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_sla_minutes CHECK (response_minutes > 0 AND resolution_minutes >= response_minutes),
    CONSTRAINT ck_sla_at_risk CHECK (at_risk_percent BETWEEN 10 AND 95),
    CONSTRAINT ck_sla_calendar CHECK (NOT business_hours_only OR calendar_id IS NOT NULL)
);
CREATE UNIQUE INDEX ux_sla_policy_active_priority ON sla_policies (priority) WHERE active;

-- ---------------------------------------------------------------- business services (CMDB-lite)
CREATE TABLE business_services (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(40)  NOT NULL UNIQUE,
    name          VARCHAR(120) NOT NULL UNIQUE,
    description   VARCHAR(500),
    department_id BIGINT       NOT NULL REFERENCES departments (id),
    owner_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    tier          INT          NOT NULL DEFAULT 2,
    keywords      VARCHAR(500),
    default_category VARCHAR(20),
    maintenance   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_demo       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_service_tier CHECK (tier BETWEEN 1 AND 3),
    CONSTRAINT ck_service_category CHECK (default_category IS NULL OR default_category IN
        ('APPLICATION', 'NETWORK', 'HARDWARE', 'ACCESS', 'DATABASE', 'EMAIL', 'SECURITY', 'FACILITIES', 'OTHER'))
);
CREATE INDEX ix_services_department ON business_services (department_id);

-- ---------------------------------------------------------------- knowledge base
CREATE SEQUENCE knowledge_number_seq START WITH 1001;

CREATE TABLE knowledge_articles (
    id                BIGSERIAL PRIMARY KEY,
    number            VARCHAR(20)  NOT NULL UNIQUE,
    title             VARCHAR(200) NOT NULL,
    summary           VARCHAR(500) NOT NULL,
    content           TEXT         NOT NULL,
    article_type      VARCHAR(20)  NOT NULL,
    category          VARCHAR(20)  NOT NULL,
    service_id        BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    tags              VARCHAR(400),
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    author_id         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    view_count        INT          NOT NULL DEFAULT 0,
    helpful_count     INT          NOT NULL DEFAULT 0,
    not_helpful_count INT          NOT NULL DEFAULT 0,
    read_minutes      INT          NOT NULL DEFAULT 1,
    published_at      TIMESTAMPTZ,
    is_demo           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    search_vector     TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(summary, '') || ' ' || coalesce(tags, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(content, '')), 'C')) STORED,
    CONSTRAINT ck_kb_type CHECK (article_type IN ('GUIDE', 'SOLUTION', 'KNOWN_ERROR', 'FAQ')),
    CONSTRAINT ck_kb_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_kb_category CHECK (category IN
        ('APPLICATION', 'NETWORK', 'HARDWARE', 'ACCESS', 'DATABASE', 'EMAIL', 'SECURITY', 'FACILITIES', 'OTHER')),
    CONSTRAINT ck_kb_counts CHECK (view_count >= 0 AND helpful_count >= 0 AND not_helpful_count >= 0),
    CONSTRAINT ck_kb_published CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE INDEX ix_kb_search ON knowledge_articles USING GIN (search_vector);
CREATE INDEX ix_kb_title_trgm ON knowledge_articles USING GIN (title gin_trgm_ops);
CREATE INDEX ix_kb_status_type ON knowledge_articles (status, article_type);
CREATE INDEX ix_kb_service ON knowledge_articles (service_id);

-- ---------------------------------------------------------------- incidents
CREATE SEQUENCE incident_number_seq START WITH 7001;

CREATE TABLE incidents (
    id                        BIGSERIAL PRIMARY KEY,
    number                    VARCHAR(20)  NOT NULL UNIQUE,
    title                     VARCHAR(200) NOT NULL,
    description               TEXT,
    category                  VARCHAR(20)  NOT NULL,
    classification_source     VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    classification_confidence NUMERIC(4, 3),
    impact                    VARCHAR(10)  NOT NULL,
    urgency                   VARCHAR(10)  NOT NULL,
    priority                  VARCHAR(10)  NOT NULL,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    service_id                BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    department_id             BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    assignee_id               BIGINT REFERENCES users (id) ON DELETE SET NULL,
    requester_id              BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_by_id             BIGINT REFERENCES users (id) ON DELETE SET NULL,
    resolved_by_id            BIGINT REFERENCES users (id) ON DELETE SET NULL,
    parent_id                 BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    is_major                  BOOLEAN      NOT NULL DEFAULT FALSE,
    affected_users            INT          NOT NULL DEFAULT 1,
    duplicate_score           NUMERIC(4, 3),
    source                    VARCHAR(20)  NOT NULL DEFAULT 'PORTAL',
    escalation_level          INT          NOT NULL DEFAULT 0,
    resolution_code           VARCHAR(30),
    resolution_notes          TEXT,
    root_cause                TEXT,
    resolution_article_id     BIGINT REFERENCES knowledge_articles (id) ON DELETE SET NULL,
    first_response_at         TIMESTAMPTZ,
    resolved_at               TIMESTAMPTZ,
    closed_at                 TIMESTAMPTZ,
    is_demo                   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_incident_category CHECK (category IN
        ('APPLICATION', 'NETWORK', 'HARDWARE', 'ACCESS', 'DATABASE', 'EMAIL', 'SECURITY', 'FACILITIES', 'OTHER')),
    CONSTRAINT ck_incident_class_source CHECK (classification_source IN ('MANUAL', 'RULES', 'AI', 'MONITORING')),
    CONSTRAINT ck_incident_impact CHECK (impact IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_incident_urgency CHECK (urgency IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_incident_priority CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_incident_status CHECK (status IN
        ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_incident_source CHECK (source IN ('PORTAL', 'EMAIL', 'PHONE', 'MONITORING', 'API', 'SCENARIO')),
    CONSTRAINT ck_incident_resolution_code CHECK (resolution_code IS NULL OR resolution_code IN
        ('FIXED', 'WORKAROUND', 'DUPLICATE', 'NOT_REPRODUCIBLE', 'USER_ERROR', 'CHANGE_ROLLED_BACK', 'OTHER')),
    CONSTRAINT ck_incident_affected CHECK (affected_users >= 0),
    CONSTRAINT ck_incident_escalation CHECK (escalation_level BETWEEN 0 AND 3),
    CONSTRAINT ck_incident_confidence CHECK (classification_confidence IS NULL OR classification_confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_incident_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_incident_resolved CHECK (status NOT IN ('RESOLVED', 'CLOSED') OR resolved_at IS NOT NULL)
);
CREATE INDEX ix_incidents_status_priority ON incidents (status, priority);
CREATE INDEX ix_incidents_created ON incidents (created_at DESC);
CREATE INDEX ix_incidents_resolved ON incidents (resolved_at) WHERE resolved_at IS NOT NULL;
CREATE INDEX ix_incidents_service ON incidents (service_id);
CREATE INDEX ix_incidents_department ON incidents (department_id);
CREATE INDEX ix_incidents_assignee ON incidents (assignee_id);
CREATE INDEX ix_incidents_requester ON incidents (requester_id);
CREATE INDEX ix_incidents_parent ON incidents (parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX ix_incidents_title_trgm ON incidents USING GIN (title gin_trgm_ops);

CREATE TABLE incident_comments (
    id               BIGSERIAL PRIMARY KEY,
    incident_id      BIGINT      NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    author_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    body             TEXT        NOT NULL,
    internal         BOOLEAN     NOT NULL DEFAULT FALSE,
    system_generated BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_comment_body CHECK (char_length(body) BETWEEN 1 AND 10000)
);
CREATE INDEX ix_comments_incident ON incident_comments (incident_id, created_at);

CREATE TABLE incident_attachments (
    id             BIGSERIAL PRIMARY KEY,
    incident_id    BIGINT       NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    uploaded_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    file_name      VARCHAR(255) NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    sha256         VARCHAR(64)     NOT NULL,
    data           BYTEA        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_attachment_size CHECK (size_bytes > 0 AND size_bytes <= 10485760)
);
CREATE INDEX ix_attachments_incident ON incident_attachments (incident_id);

CREATE TABLE incident_history (
    id          BIGSERIAL PRIMARY KEY,
    incident_id BIGINT       NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    actor_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name  VARCHAR(120) NOT NULL,
    event_type  VARCHAR(30)  NOT NULL,
    field       VARCHAR(40),
    old_value   VARCHAR(500),
    new_value   VARCHAR(500),
    message     VARCHAR(1000),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_history_incident ON incident_history (incident_id, created_at);

CREATE TABLE incident_links (
    id            BIGSERIAL PRIMARY KEY,
    source_id     BIGINT      NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    target_id     BIGINT      NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    link_type     VARCHAR(20) NOT NULL,
    created_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_link_type CHECK (link_type IN ('RELATED_TO', 'CAUSED_BY', 'BLOCKS')),
    CONSTRAINT ck_link_not_self CHECK (source_id <> target_id),
    CONSTRAINT ux_incident_link UNIQUE (source_id, target_id, link_type)
);
CREATE INDEX ix_links_target ON incident_links (target_id);

-- ---------------------------------------------------------------- SLA runtime
CREATE TABLE incident_slas (
    id                        BIGSERIAL PRIMARY KEY,
    incident_id               BIGINT      NOT NULL UNIQUE REFERENCES incidents (id) ON DELETE CASCADE,
    policy_id                 BIGINT      NOT NULL REFERENCES sla_policies (id),
    policy_name               VARCHAR(120) NOT NULL,
    priority                  VARCHAR(10) NOT NULL,
    business_hours_only       BOOLEAN     NOT NULL,
    calendar_id               BIGINT REFERENCES business_hours_calendars (id),
    response_target_minutes   INT         NOT NULL,
    resolution_target_minutes INT         NOT NULL,
    at_risk_percent           INT         NOT NULL,
    started_at                TIMESTAMPTZ NOT NULL,
    response_due_at           TIMESTAMPTZ NOT NULL,
    responded_at              TIMESTAMPTZ,
    response_breached         BOOLEAN     NOT NULL DEFAULT FALSE,
    consumed_seconds          BIGINT      NOT NULL DEFAULT 0,
    running_since             TIMESTAMPTZ,
    resolution_due_at         TIMESTAMPTZ,
    paused_at                 TIMESTAMPTZ,
    total_paused_seconds      BIGINT      NOT NULL DEFAULT 0,
    clock_status              VARCHAR(10) NOT NULL DEFAULT 'RUNNING',
    health                    VARCHAR(10) NOT NULL DEFAULT 'ON_TRACK',
    outcome                   VARCHAR(10),
    escalation_level          INT         NOT NULL DEFAULT 0,
    at_risk_at                TIMESTAMPTZ,
    breached_at               TIMESTAMPTZ,
    completed_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                   BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_isla_priority CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_isla_clock CHECK (clock_status IN ('RUNNING', 'PAUSED', 'STOPPED')),
    CONSTRAINT ck_isla_health CHECK (health IN ('ON_TRACK', 'AT_RISK', 'BREACHED')),
    CONSTRAINT ck_isla_outcome CHECK (outcome IS NULL OR outcome IN ('MET', 'MISSED', 'CANCELLED')),
    CONSTRAINT ck_isla_consumed CHECK (consumed_seconds >= 0 AND total_paused_seconds >= 0),
    CONSTRAINT ck_isla_running CHECK (clock_status <> 'RUNNING' OR running_since IS NOT NULL),
    CONSTRAINT ck_isla_stopped CHECK (clock_status <> 'STOPPED' OR outcome IS NOT NULL)
);
CREATE INDEX ix_isla_running ON incident_slas (clock_status, resolution_due_at) WHERE clock_status = 'RUNNING';
CREATE INDEX ix_isla_completed ON incident_slas (completed_at) WHERE completed_at IS NOT NULL;

CREATE TABLE sla_events (
    id              BIGSERIAL PRIMARY KEY,
    incident_sla_id BIGINT      NOT NULL REFERENCES incident_slas (id) ON DELETE CASCADE,
    event_type      VARCHAR(20) NOT NULL,
    details         VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sla_event_type CHECK (event_type IN ('STARTED', 'PAUSED', 'RESUMED', 'RESPONDED', 'AT_RISK',
        'BREACHED', 'ESCALATED', 'MET', 'MISSED', 'RECALCULATED', 'CANCELLED', 'SIMULATED'))
);
CREATE INDEX ix_sla_events_sla ON sla_events (incident_sla_id, created_at);

-- ---------------------------------------------------------------- service catalogue & desk
CREATE TABLE service_categories (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(500),
    icon        VARCHAR(40),
    sort_order  INT          NOT NULL DEFAULT 0,
    is_demo     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE catalog_items (
    id                        BIGSERIAL PRIMARY KEY,
    category_id               BIGINT       NOT NULL REFERENCES service_categories (id),
    name                      VARCHAR(150) NOT NULL,
    description               VARCHAR(1000),
    fulfillment_department_id BIGINT       NOT NULL REFERENCES departments (id),
    requires_approval         BOOLEAN      NOT NULL DEFAULT FALSE,
    approver_role             VARCHAR(20),
    fulfillment_hours         INT          NOT NULL DEFAULT 24,
    form_fields               JSONB        NOT NULL DEFAULT '[]'::jsonb,
    active                    BOOLEAN      NOT NULL DEFAULT TRUE,
    is_demo                   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_catalog_item UNIQUE (category_id, name),
    CONSTRAINT ck_catalog_hours CHECK (fulfillment_hours BETWEEN 1 AND 2160),
    CONSTRAINT ck_catalog_approver CHECK (
        (requires_approval AND approver_role IN ('MANAGER', 'ADMIN')) OR (NOT requires_approval AND approver_role IS NULL))
);

CREATE SEQUENCE service_request_number_seq START WITH 3001;

CREATE TABLE service_requests (
    id                BIGSERIAL PRIMARY KEY,
    number            VARCHAR(20)  NOT NULL UNIQUE,
    catalog_item_id   BIGINT       NOT NULL REFERENCES catalog_items (id),
    requester_id      BIGINT       NOT NULL REFERENCES users (id),
    title             VARCHAR(200) NOT NULL,
    details           TEXT,
    form_data         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status            VARCHAR(20)  NOT NULL,
    department_id     BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    assignee_id       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    due_at            TIMESTAMPTZ,
    fulfilled_at      TIMESTAMPTZ,
    fulfillment_notes TEXT,
    is_demo           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_request_status CHECK (status IN
        ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'IN_FULFILLMENT', 'FULFILLED', 'CANCELLED')),
    CONSTRAINT ck_request_fulfilled CHECK (status <> 'FULFILLED' OR fulfilled_at IS NOT NULL)
);
CREATE INDEX ix_requests_requester ON service_requests (requester_id, created_at DESC);
CREATE INDEX ix_requests_status ON service_requests (status);
CREATE INDEX ix_requests_department ON service_requests (department_id);

CREATE TABLE approvals (
    id               BIGSERIAL PRIMARY KEY,
    approval_type    VARCHAR(20)  NOT NULL,
    reference_id     BIGINT       NOT NULL,
    reference_number VARCHAR(30),
    title            VARCHAR(250) NOT NULL,
    details          TEXT,
    payload          JSONB,
    requested_by_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    approver_role    VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decided_by_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    decision_comment VARCHAR(1000),
    decided_at       TIMESTAMPTZ,
    is_demo          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_approval_type CHECK (approval_type IN ('SERVICE_REQUEST', 'RUNBOOK_EXECUTION', 'AI_ACTION')),
    CONSTRAINT ck_approval_role CHECK (approver_role IN ('MANAGER', 'ADMIN')),
    CONSTRAINT ck_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_approval_decided CHECK (status IN ('PENDING', 'CANCELLED') OR decided_at IS NOT NULL)
);
CREATE INDEX ix_approvals_pending ON approvals (approver_role, created_at) WHERE status = 'PENDING';
CREATE INDEX ix_approvals_reference ON approvals (approval_type, reference_id);

-- ---------------------------------------------------------------- integrations (referenced by workflows)
CREATE TABLE integrations (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(120) NOT NULL UNIQUE,
    integration_type     VARCHAR(30)  NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    endpoint_url         VARCHAR(500),
    secret_encrypted     VARCHAR(1000),
    inbound_token_hash   VARCHAR(64) UNIQUE,
    subscribed_events    VARCHAR(500) NOT NULL DEFAULT '',
    default_service_id   BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    last_delivery_at     TIMESTAMPTZ,
    last_delivery_status VARCHAR(10),
    failure_count        INT          NOT NULL DEFAULT 0,
    created_by_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_demo              BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_integration_type CHECK (integration_type IN ('WEBHOOK', 'SLACK', 'TEAMS', 'MONITORING_INBOUND')),
    CONSTRAINT ck_integration_status CHECK (last_delivery_status IS NULL OR last_delivery_status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT ck_integration_endpoint CHECK (integration_type = 'MONITORING_INBOUND' OR endpoint_url IS NOT NULL)
);

CREATE TABLE integration_deliveries (
    id               BIGSERIAL PRIMARY KEY,
    integration_id   BIGINT      NOT NULL REFERENCES integrations (id) ON DELETE CASCADE,
    event_type       VARCHAR(40) NOT NULL,
    status           VARCHAR(10) NOT NULL,
    http_status      INT,
    response_snippet VARCHAR(500),
    duration_ms      INT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_delivery_status CHECK (status IN ('SUCCESS', 'FAILED'))
);
CREATE INDEX ix_deliveries_integration ON integration_deliveries (integration_id, created_at DESC);

-- ---------------------------------------------------------------- runbooks
CREATE TABLE runbooks (
    id                BIGSERIAL PRIMARY KEY,
    title             VARCHAR(200) NOT NULL UNIQUE,
    description       VARCHAR(1000),
    service_id        BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    category          VARCHAR(20),
    steps             JSONB        NOT NULL,
    requires_approval BOOLEAN      NOT NULL DEFAULT FALSE,
    estimated_minutes INT,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    author_id         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_demo           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_runbook_minutes CHECK (estimated_minutes IS NULL OR estimated_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_runbook_steps CHECK (jsonb_typeof(steps) = 'array' AND jsonb_array_length(steps) > 0)
);

CREATE TABLE runbook_executions (
    id            BIGSERIAL PRIMARY KEY,
    runbook_id    BIGINT      NOT NULL REFERENCES runbooks (id) ON DELETE CASCADE,
    incident_id   BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    status        VARCHAR(20) NOT NULL,
    started_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    approval_id   BIGINT REFERENCES approvals (id) ON DELETE SET NULL,
    step_states   JSONB       NOT NULL,
    outcome_notes TEXT,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    is_demo       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_runbook_exec_status CHECK (status IN
        ('PENDING_APPROVAL', 'READY', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'ABORTED'))
);
CREATE INDEX ix_runbook_exec_incident ON runbook_executions (incident_id);
CREATE INDEX ix_runbook_exec_runbook ON runbook_executions (runbook_id, created_at DESC);

-- ---------------------------------------------------------------- workflows
CREATE TABLE workflows (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(150) NOT NULL UNIQUE,
    description             VARCHAR(1000),
    trigger_event           VARCHAR(30)  NOT NULL,
    active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    current_version         INT          NOT NULL DEFAULT 1,
    max_executions_per_hour INT          NOT NULL DEFAULT 100,
    max_retries             INT          NOT NULL DEFAULT 3,
    retry_backoff_seconds   INT          NOT NULL DEFAULT 30,
    created_by_id           BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_demo                 BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_workflow_trigger CHECK (trigger_event IN ('INCIDENT_CREATED', 'INCIDENT_UPDATED',
        'INCIDENT_STATUS_CHANGED', 'INCIDENT_ASSIGNED', 'SLA_AT_RISK', 'SLA_BREACHED', 'SERVICE_REQUEST_CREATED')),
    CONSTRAINT ck_workflow_limits CHECK (max_executions_per_hour BETWEEN 1 AND 10000
        AND max_retries BETWEEN 0 AND 10 AND retry_backoff_seconds BETWEEN 1 AND 3600)
);
CREATE INDEX ix_workflows_trigger ON workflows (trigger_event) WHERE active;

CREATE TABLE workflow_versions (
    id             BIGSERIAL PRIMARY KEY,
    workflow_id    BIGINT      NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    version_number INT         NOT NULL,
    definition     JSONB       NOT NULL,
    change_note    VARCHAR(500),
    created_by_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_workflow_version UNIQUE (workflow_id, version_number)
);

CREATE TABLE workflow_executions (
    id                 BIGSERIAL PRIMARY KEY,
    workflow_id        BIGINT      NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    workflow_version   INT         NOT NULL,
    trigger_event      VARCHAR(30) NOT NULL,
    incident_id        BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    service_request_id BIGINT REFERENCES service_requests (id) ON DELETE SET NULL,
    status             VARCHAR(20) NOT NULL,
    attempt            INT         NOT NULL DEFAULT 1,
    next_attempt_at    TIMESTAMPTZ,
    error_message      VARCHAR(2000),
    steps              JSONB       NOT NULL DEFAULT '[]'::jsonb,
    depth              INT         NOT NULL DEFAULT 0,
    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ,
    CONSTRAINT ck_execution_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'RETRY_SCHEDULED', 'RATE_LIMITED')),
    CONSTRAINT ck_execution_retry CHECK (status <> 'RETRY_SCHEDULED' OR next_attempt_at IS NOT NULL)
);
CREATE INDEX ix_executions_workflow ON workflow_executions (workflow_id, started_at DESC);
CREATE INDEX ix_executions_retry ON workflow_executions (next_attempt_at) WHERE status = 'RETRY_SCHEDULED';
CREATE INDEX ix_executions_incident ON workflow_executions (incident_id);

-- ---------------------------------------------------------------- notifications, announcements
CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(40)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    message    VARCHAR(1000) NOT NULL,
    link       VARCHAR(300),
    severity   VARCHAR(10)  NOT NULL DEFAULT 'INFO',
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_severity CHECK (severity IN ('INFO', 'SUCCESS', 'WARNING', 'CRITICAL'))
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

CREATE TABLE announcements (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(200) NOT NULL,
    body       TEXT         NOT NULL,
    severity   VARCHAR(20)  NOT NULL DEFAULT 'INFO',
    service_id BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    starts_at  TIMESTAMPTZ,
    ends_at    TIMESTAMPTZ,
    published  BOOLEAN      NOT NULL DEFAULT FALSE,
    author_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_demo    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_announcement_severity CHECK (severity IN ('INFO', 'MAINTENANCE', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_announcement_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

-- ---------------------------------------------------------------- audit (append-only)
CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_email VARCHAR(160),
    action      VARCHAR(60) NOT NULL,
    entity_type VARCHAR(40),
    entity_id   VARCHAR(40),
    details     JSONB,
    ip_address  VARCHAR(64),
    user_agent  VARCHAR(255),
    outcome     VARCHAR(10) NOT NULL DEFAULT 'SUCCESS',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
);
CREATE INDEX ix_audit_created ON audit_logs (created_at DESC);
CREATE INDEX ix_audit_actor ON audit_logs (actor_id);
CREATE INDEX ix_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_action ON audit_logs (action);

CREATE FUNCTION forbid_audit_mutation() RETURNS trigger AS $$
BEGIN
    -- actor_id may be nulled by ON DELETE SET NULL when a user row is removed; nothing else may change.
    IF TG_OP = 'UPDATE' AND NEW.actor_id IS NULL AND OLD.actor_id IS NOT NULL
       AND NEW.action = OLD.action AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'audit_logs is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

-- ---------------------------------------------------------------- AI usage log, settings
CREATE TABLE ai_interactions (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    feature     VARCHAR(40) NOT NULL,
    source      VARCHAR(10) NOT NULL,
    provider    VARCHAR(40) NOT NULL,
    model       VARCHAR(80),
    incident_id BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    latency_ms  INT,
    success     BOOLEAN     NOT NULL,
    error       VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_source CHECK (source IN ('LLM', 'FALLBACK'))
);
CREATE INDEX ix_ai_interactions_created ON ai_interactions (created_at DESC);

CREATE TABLE system_settings (
    setting_key   VARCHAR(80) PRIMARY KEY,
    setting_value VARCHAR(1000) NOT NULL,
    value_type    VARCHAR(10)   NOT NULL,
    description   VARCHAR(300),
    updated_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_setting_type CHECK (value_type IN ('STRING', 'NUMBER', 'BOOLEAN'))
);
