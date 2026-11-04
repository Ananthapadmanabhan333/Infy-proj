-- SA Release 3 foundations (docs/RELEASE3_SPEC.md §1, §2, §4): product rename, CHANGE_MANAGER / ASSET_MANAGER roles, teams and
-- team assignment, the e-mail outbox and e-mail notification channel, user sessions, the status page (components,
-- maintenance windows, updates, subscribers) and scheduled reports. Demo records for these tables are created by the
-- application's demo seeder (flagged is_demo), never here.

-- ============================================================================ 1. branding
-- Only the default organisation name is renamed; a name an administrator has customised is left alone.
UPDATE system_settings
   SET setting_value = 'SA', updated_at = now()
 WHERE setting_key = 'org.name'
   AND setting_value = 'SA 360';

-- ============================================================================ 2. roles
ALTER TABLE users DROP CONSTRAINT ck_user_role;
ALTER TABLE users ADD CONSTRAINT ck_user_role CHECK (role IN ('SYSTEM_ADMIN', 'IT_ADMIN', 'IT_MANAGER', 'INCIDENT_MANAGER',
    'CHANGE_MANAGER', 'ASSET_MANAGER', 'SUPPORT_AGENT', 'EMPLOYEE', 'AUDITOR'));
ALTER TABLE account_tokens DROP CONSTRAINT ck_account_token_role;
ALTER TABLE account_tokens ADD CONSTRAINT ck_account_token_role CHECK (role IS NULL OR role IN ('SYSTEM_ADMIN', 'IT_ADMIN',
    'IT_MANAGER', 'INCIDENT_MANAGER', 'CHANGE_MANAGER', 'ASSET_MANAGER', 'SUPPORT_AGENT', 'EMPLOYEE', 'AUDITOR'));

-- ============================================================================ 3. teams
CREATE TABLE teams (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(40)  NOT NULL UNIQUE,
    name               VARCHAR(120) NOT NULL UNIQUE,
    department_id      BIGINT       NOT NULL REFERENCES departments (id),
    lead_id            BIGINT REFERENCES users (id) ON DELETE SET NULL,
    email              VARCHAR(160),
    description        VARCHAR(500),
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    -- The department's default team receives the department's work when the service names no team.
    department_default BOOLEAN      NOT NULL DEFAULT FALSE,
    is_demo            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_team_code CHECK (code ~ '^[A-Z0-9_-]{2,40}$'),
    CONSTRAINT ck_team_default_active CHECK (NOT department_default OR active)
);
CREATE UNIQUE INDEX ux_team_department_default ON teams (department_id) WHERE department_default;
CREATE INDEX ix_teams_department ON teams (department_id);

CREATE TABLE team_members (
    team_id     BIGINT      NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    member_role VARCHAR(10) NOT NULL DEFAULT 'MEMBER',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id),
    CONSTRAINT ck_team_member_role CHECK (member_role IN ('MEMBER', 'LEAD'))
);
CREATE INDEX ix_team_members_user ON team_members (user_id);

-- Services may name the team that receives their incidents; incidents and requests record the assigned team.
ALTER TABLE business_services ADD COLUMN default_team_id BIGINT REFERENCES teams (id) ON DELETE SET NULL;
ALTER TABLE incidents ADD COLUMN team_id BIGINT REFERENCES teams (id) ON DELETE SET NULL;
CREATE INDEX ix_incidents_team_active ON incidents (team_id)
    WHERE team_id IS NOT NULL AND status IN ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING', 'REOPENED');
ALTER TABLE service_requests ADD COLUMN team_id BIGINT REFERENCES teams (id) ON DELETE SET NULL;
CREATE INDEX ix_requests_team ON service_requests (team_id) WHERE team_id IS NOT NULL;
CREATE INDEX ix_requests_assignee ON service_requests (assignee_id) WHERE assignee_id IS NOT NULL;

-- ============================================================================ 4. e-mail outbox
-- Messages are written in the business transaction and delivered by a scheduler with retries. Without SMTP
-- configuration (MAIL_HOST) they are recorded as SKIPPED. Bodies of messages that carry a one-time link (sensitive)
-- are replaced by a placeholder once the message reaches a final state, so raw tokens do not stay in the database.
CREATE TABLE email_outbox (
    id                      BIGSERIAL PRIMARY KEY,
    category                VARCHAR(40)  NOT NULL,
    to_address              VARCHAR(320) NOT NULL,
    user_id                 BIGINT REFERENCES users (id) ON DELETE SET NULL,
    subject                 VARCHAR(300) NOT NULL,
    html_body               TEXT         NOT NULL,
    text_body               TEXT         NOT NULL,
    sensitive               BOOLEAN      NOT NULL DEFAULT FALSE,
    attachment_name         VARCHAR(200),
    attachment_content_type VARCHAR(100),
    attachment_data         BYTEA,
    status                  VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    attempts                INT          NOT NULL DEFAULT 0,
    next_attempt_at         TIMESTAMPTZ,
    last_error              VARCHAR(1000),
    sent_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_email_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_email_pending CHECK (status <> 'PENDING' OR next_attempt_at IS NOT NULL),
    CONSTRAINT ck_email_sent CHECK (status <> 'SENT' OR sent_at IS NOT NULL),
    CONSTRAINT ck_email_attachment CHECK (attachment_data IS NULL OR attachment_name IS NOT NULL)
);
CREATE INDEX ix_email_outbox_due ON email_outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_email_outbox_created ON email_outbox (created_at DESC);
CREATE INDEX ix_email_outbox_status ON email_outbox (status, created_at DESC);

-- E-mail channel per notification category: NULL = the category's default (on for assignments and approvals only).
ALTER TABLE notification_preferences ADD COLUMN email BOOLEAN;
ALTER TABLE notification_preferences DROP CONSTRAINT ck_notification_pref_type;
ALTER TABLE notification_preferences ADD CONSTRAINT ck_notification_pref_type CHECK (type IN ('INCIDENT_ASSIGNED', 'INCIDENT_COMMENT',
    'INCIDENT_RESOLVED', 'SLA_AT_RISK', 'SLA_BREACHED', 'ESCALATION', 'APPROVAL_REQUESTED', 'APPROVAL_DECIDED', 'WORKFLOW_FAILED',
    'REQUEST_UPDATED', 'ANNOUNCEMENT', 'ACCESS_REQUEST', 'PASSWORD_RESET_REQUEST', 'TEAM_QUEUE', 'REPORT_READY'));

-- ============================================================================ 5. sessions
-- One row per sign-in; the refresh-token family rotates underneath it. remember_me = false sessions end 12 hours after
-- sign-in (browser-session cookie); remember-me sessions slide with every refresh (REFRESH_TOKEN_DAYS).
CREATE TABLE auth_sessions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id       UUID        NOT NULL UNIQUE,
    remember_me     BOOLEAN     NOT NULL,
    user_agent      VARCHAR(255),
    ip_address      VARCHAR(64),
    last_ip_address VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(40)
);
CREATE INDEX ix_auth_sessions_user ON auth_sessions (user_id, last_used_at DESC);
CREATE INDEX ix_auth_sessions_open ON auth_sessions (user_id) WHERE revoked_at IS NULL;

-- Sign-ins made before this release become sessions (persistent cookies, as issued then).
INSERT INTO auth_sessions (user_id, family_id, remember_me, user_agent, ip_address, last_ip_address, created_at, last_used_at,
                           expires_at, revoked_at, revoked_reason)
SELECT f.user_id, f.family_id, TRUE, firstt.user_agent, firstt.ip_address, lastt.ip_address, f.created_at, f.last_used_at, f.expires_at,
       CASE WHEN f.open_tokens = 0 THEN coalesce(f.last_revoked, now()) END,
       CASE WHEN f.open_tokens = 0 THEN 'LEGACY' END
  FROM (SELECT family_id, min(user_id) AS user_id, min(created_at) AS created_at, max(created_at) AS last_used_at,
               max(expires_at) AS expires_at, count(*) FILTER (WHERE revoked_at IS NULL) AS open_tokens, max(revoked_at) AS last_revoked
          FROM refresh_tokens GROUP BY family_id) f
 CROSS JOIN LATERAL (SELECT t.user_agent, t.ip_address FROM refresh_tokens t WHERE t.family_id = f.family_id
                      ORDER BY t.created_at ASC, t.id ASC LIMIT 1) firstt
 CROSS JOIN LATERAL (SELECT t.ip_address FROM refresh_tokens t WHERE t.family_id = f.family_id
                      ORDER BY t.created_at DESC, t.id DESC LIMIT 1) lastt;

-- ============================================================================ 6. status page
CREATE TABLE status_page_settings (
    id             SMALLINT     PRIMARY KEY DEFAULT 1,
    title          VARCHAR(120) NOT NULL,
    description    VARCHAR(1000),
    public_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_status_page_single_row CHECK (id = 1)
);
INSERT INTO status_page_settings (id, title, description, public_enabled)
VALUES (1, 'Service status', 'Current availability of IT services, planned maintenance and incident updates.', FALSE);

CREATE TABLE status_components (
    id           BIGSERIAL PRIMARY KEY,
    service_id   BIGINT       NOT NULL UNIQUE REFERENCES business_services (id) ON DELETE CASCADE,
    display_name VARCHAR(120) NOT NULL,
    group_name   VARCHAR(80),
    description  VARCHAR(500),
    sort_order   INT          NOT NULL DEFAULT 0,
    is_public    BOOLEAN      NOT NULL DEFAULT TRUE,
    is_demo      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE maintenance_windows (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(200)  NOT NULL,
    description   VARCHAR(2000),
    starts_at     TIMESTAMPTZ   NOT NULL,
    ends_at       TIMESTAMPTZ   NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'SCHEDULED',
    created_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_demo       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_maintenance_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_maintenance_status CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);
CREATE INDEX ix_maintenance_windows_time ON maintenance_windows (starts_at, ends_at);

CREATE TABLE maintenance_window_components (
    window_id    BIGINT NOT NULL REFERENCES maintenance_windows (id) ON DELETE CASCADE,
    component_id BIGINT NOT NULL REFERENCES status_components (id) ON DELETE CASCADE,
    PRIMARY KEY (window_id, component_id)
);
CREATE INDEX ix_maintenance_components_component ON maintenance_window_components (component_id);

CREATE TABLE status_updates (
    id           BIGSERIAL PRIMARY KEY,
    incident_id  BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    title        VARCHAR(200) NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    message      TEXT         NOT NULL,
    is_public    BOOLEAN      NOT NULL DEFAULT FALSE,
    posted_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    posted_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    is_demo      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_status_update_status CHECK (status IN ('INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED')),
    CONSTRAINT ck_status_update_message CHECK (char_length(message) BETWEEN 1 AND 5000)
);
CREATE INDEX ix_status_updates_posted ON status_updates (posted_at DESC);
CREATE INDEX ix_status_updates_incident ON status_updates (incident_id) WHERE incident_id IS NOT NULL;

-- Only SHA-256 hashes of the confirmation and unsubscribe tokens are stored (64 lower-case hex characters).
CREATE TABLE status_subscribers (
    id                     BIGSERIAL PRIMARY KEY,
    email                  VARCHAR(160) NOT NULL,
    confirm_token_hash     VARCHAR(64),
    confirm_expires_at     TIMESTAMPTZ,
    confirmed_at           TIMESTAMPTZ,
    unsubscribe_token_hash VARCHAR(64)  NOT NULL UNIQUE,
    request_ip             VARCHAR(64),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_subscriber_confirm_hash CHECK (confirm_token_hash IS NULL OR confirm_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_subscriber_unsubscribe_hash CHECK (unsubscribe_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_subscriber_state CHECK (confirmed_at IS NOT NULL OR confirm_token_hash IS NOT NULL)
);
CREATE UNIQUE INDEX ux_status_subscribers_email ON status_subscribers (lower(email));
CREATE UNIQUE INDEX ux_status_subscribers_confirm ON status_subscribers (confirm_token_hash) WHERE confirm_token_hash IS NOT NULL;

-- ============================================================================ 7. scheduled reports
CREATE TABLE report_schedules (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(150) NOT NULL,
    report_type  VARCHAR(30)  NOT NULL,
    format       VARCHAR(5)   NOT NULL,
    frequency    VARCHAR(10)  NOT NULL,
    run_hour     SMALLINT     NOT NULL DEFAULT 6,
    day_of_week  SMALLINT,
    day_of_month SMALLINT,
    filters      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    recipients   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    owner_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    next_run_at  TIMESTAMPTZ  NOT NULL,
    last_run_at  TIMESTAMPTZ,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_demo      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_report_type CHECK (report_type IN ('ANALYTICS_OVERVIEW', 'SLA', 'INCIDENTS', 'CHANGES', 'PROBLEMS', 'ASSETS', 'ONCALL')),
    CONSTRAINT ck_report_format CHECK (format IN ('PDF', 'CSV')),
    CONSTRAINT ck_report_frequency CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_report_hour CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_report_day_of_week CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_report_day_of_month CHECK (day_of_month IS NULL OR day_of_month BETWEEN 1 AND 28),
    CONSTRAINT ck_report_filters CHECK (jsonb_typeof(filters) = 'object'),
    CONSTRAINT ck_report_recipients CHECK (jsonb_typeof(recipients) = 'array')
);
CREATE INDEX ix_report_schedules_due ON report_schedules (next_run_at) WHERE active;
CREATE INDEX ix_report_schedules_owner ON report_schedules (owner_id);

CREATE TABLE report_runs (
    id              BIGSERIAL PRIMARY KEY,
    schedule_id     BIGINT       NOT NULL REFERENCES report_schedules (id) ON DELETE CASCADE,
    trigger_type    VARCHAR(10)  NOT NULL,
    scheduled_for   TIMESTAMPTZ,
    triggered_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    status          VARCHAR(10)  NOT NULL,
    period_from     DATE         NOT NULL,
    period_to       DATE         NOT NULL,
    file_name       VARCHAR(200),
    content_type    VARCHAR(100),
    file_data       BYTEA,
    size_bytes      BIGINT,
    error           VARCHAR(2000),
    emailed_to      INT          NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ,
    is_demo         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_report_run_trigger CHECK (trigger_type IN ('SCHEDULE', 'MANUAL')),
    CONSTRAINT ck_report_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_report_run_file CHECK (status <> 'SUCCEEDED' OR file_data IS NOT NULL),
    CONSTRAINT ck_report_run_scheduled CHECK (trigger_type <> 'SCHEDULE' OR scheduled_for IS NOT NULL)
);
-- A schedule runs at most once per due time, even if two instances ever raced past the advisory lock.
CREATE UNIQUE INDEX ux_report_run_period ON report_runs (schedule_id, scheduled_for) WHERE trigger_type = 'SCHEDULE';
CREATE INDEX ix_report_runs_schedule ON report_runs (schedule_id, started_at DESC);
