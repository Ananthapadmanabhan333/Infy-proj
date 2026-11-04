-- NEXUS 360 Release 2: new roles and incident statuses (data migrated in place), service-desk categories, priorities,
-- comments and history, idempotent SLA escalations, workflow triggers and branches, knowledge review/versions/ratings,
-- runbook prerequisites/categories, service dependencies, notification preferences and administration settings.

-- ============================================================================ helpers (dropped at the end)
CREATE FUNCTION r2_map_role(v TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE upper(trim(v)) WHEN 'ADMIN' THEN 'SYSTEM_ADMIN' WHEN 'MANAGER' THEN 'IT_MANAGER' WHEN 'AGENT' THEN 'SUPPORT_AGENT' ELSE v END
$$;

CREATE FUNCTION r2_map_status(v TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT coalesce((SELECT string_agg(CASE upper(trim(s)) WHEN 'OPEN' THEN 'NEW' WHEN 'ON_HOLD' THEN 'PENDING'
                                                           WHEN 'CANCELLED' THEN 'CLOSED' ELSE trim(s) END, ',' ORDER BY ord)
                     FROM unnest(string_to_array(v, ',')) WITH ORDINALITY AS t(s, ord)), v)
$$;

-- Rewrites role names in NOTIFY_ROLE parameters and status literals in "status" condition rules of a workflow definition.
CREATE FUNCTION r2_migrate_definition(def JSONB) RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    rules   JSONB;
    actions JSONB;
BEGIN
    IF def IS NULL THEN
        RETURN def;
    END IF;
    IF jsonb_typeof(def #> '{conditions,rules}') = 'array' THEN
        SELECT coalesce(jsonb_agg(CASE WHEN r ->> 'field' = 'status' AND jsonb_typeof(r -> 'value') = 'string'
                                       THEN jsonb_set(r, '{value}', to_jsonb(r2_map_status(r ->> 'value'))) ELSE r END ORDER BY ord), '[]'::jsonb)
          INTO rules
          FROM jsonb_array_elements(def #> '{conditions,rules}') WITH ORDINALITY AS t(r, ord);
        def := jsonb_set(def, '{conditions,rules}', rules);
    END IF;
    IF jsonb_typeof(def -> 'actions') = 'array' THEN
        SELECT coalesce(jsonb_agg(CASE WHEN jsonb_typeof(a #> '{params,role}') = 'string'
                                       THEN jsonb_set(a, '{params,role}', to_jsonb(r2_map_role(a #>> '{params,role}'))) ELSE a END ORDER BY ord), '[]'::jsonb)
          INTO actions
          FROM jsonb_array_elements(def -> 'actions') WITH ORDINALITY AS t(a, ord);
        def := jsonb_set(def, '{actions}', actions);
    END IF;
    RETURN def;
END
$$;

-- ============================================================================ 1. roles
ALTER TABLE users DROP CONSTRAINT ck_user_role;
UPDATE users SET role = r2_map_role(role) WHERE role IN ('ADMIN', 'MANAGER', 'AGENT');
ALTER TABLE users ADD CONSTRAINT ck_user_role
    CHECK (role IN ('SYSTEM_ADMIN', 'IT_ADMIN', 'IT_MANAGER', 'INCIDENT_MANAGER', 'SUPPORT_AGENT', 'EMPLOYEE', 'AUDITOR'));

ALTER TABLE catalog_items DROP CONSTRAINT ck_catalog_approver;
UPDATE catalog_items SET approver_role = r2_map_role(approver_role) WHERE approver_role IS NOT NULL;
ALTER TABLE catalog_items ADD CONSTRAINT ck_catalog_approver CHECK (
    (requires_approval AND approver_role IN ('IT_MANAGER', 'INCIDENT_MANAGER', 'SYSTEM_ADMIN'))
    OR (NOT requires_approval AND approver_role IS NULL));

-- Pending runbook and AI approvals follow the Release 2 routing (incident managers); decided ones keep their history.
ALTER TABLE approvals DROP CONSTRAINT ck_approval_role, DROP CONSTRAINT ck_approval_type;
UPDATE approvals SET approver_role = CASE
        WHEN approver_role = 'MANAGER' AND status = 'PENDING' AND approval_type IN ('RUNBOOK_EXECUTION', 'AI_ACTION') THEN 'INCIDENT_MANAGER'
        ELSE r2_map_role(approver_role) END
 WHERE approver_role IN ('ADMIN', 'MANAGER');
ALTER TABLE approvals
    ADD CONSTRAINT ck_approval_role CHECK (approver_role IN ('IT_MANAGER', 'INCIDENT_MANAGER', 'SYSTEM_ADMIN')),
    ADD CONSTRAINT ck_approval_type CHECK (approval_type IN ('SERVICE_REQUEST', 'RUNBOOK_EXECUTION', 'RUNBOOK_STEP', 'AI_ACTION'));

-- Existing demo organisations (Release 1 seed) get the Release 2 demo roles; no effect on other installations.
UPDATE users SET role = 'INCIDENT_MANAGER' WHERE lower(email) = 'sunita.joshi@demo.nexus360.io' AND is_demo;
INSERT INTO users (email, full_name, password_hash, role, department_id, job_title, is_demo, created_at, updated_at)
SELECT 'rajesh.iyer@demo.nexus360.io', 'Rajesh Iyer', a.password_hash, 'IT_ADMIN',
       (SELECT d.id FROM departments d WHERE d.code = 'IT-SD'), 'IT Platform Administrator', TRUE, now(), now()
  FROM users a
 WHERE lower(a.email) = 'admin@demo.nexus360.io' AND a.is_demo
   AND NOT EXISTS (SELECT 1 FROM users u WHERE lower(u.email) = 'rajesh.iyer@demo.nexus360.io');

-- ============================================================================ 2. incident statuses
ALTER TABLE incidents DROP CONSTRAINT ck_incident_status, DROP CONSTRAINT ck_incident_resolution_code;
UPDATE incidents SET status = 'NEW' WHERE status = 'OPEN';
UPDATE incidents SET status = 'PENDING' WHERE status = 'ON_HOLD';
UPDATE incidents
   SET status = 'CLOSED', resolution_code = 'CANCELLED',
       resolved_at = coalesce(resolved_at, closed_at, updated_at), closed_at = coalesce(closed_at, updated_at)
 WHERE status = 'CANCELLED';
ALTER TABLE incidents ALTER COLUMN status SET DEFAULT 'NEW';
ALTER TABLE incidents
    ADD CONSTRAINT ck_incident_status CHECK (status IN ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING', 'RESOLVED', 'CLOSED', 'REOPENED')),
    ADD CONSTRAINT ck_incident_resolution_code CHECK (resolution_code IS NULL OR resolution_code IN
        ('FIXED', 'WORKAROUND', 'DUPLICATE', 'NOT_REPRODUCIBLE', 'USER_ERROR', 'CHANGE_ROLLED_BACK', 'CANCELLED', 'OTHER'));
CREATE INDEX ix_incidents_active_created ON incidents (created_at)
    WHERE status IN ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING', 'REOPENED');

-- ============================================================================ 3. escalations (manual and SLA)
CREATE TABLE incident_escalations (
    id              BIGSERIAL PRIMARY KEY,
    incident_id     BIGINT       NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    incident_sla_id BIGINT REFERENCES incident_slas (id) ON DELETE CASCADE,
    level           INT          NOT NULL,
    source          VARCHAR(20)  NOT NULL,
    reason          VARCHAR(1000) NOT NULL,
    actor_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name      VARCHAR(120) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_escalation_level CHECK (level BETWEEN 1 AND 3),
    CONSTRAINT ck_escalation_source CHECK (source IN ('SLA', 'MANUAL', 'WORKFLOW', 'AI'))
);
CREATE INDEX ix_escalations_incident ON incident_escalations (incident_id, created_at);
-- Idempotency guard: every (SLA clock, escalation level) escalates at most once, even under concurrent evaluation.
CREATE UNIQUE INDEX ux_escalation_sla_level ON incident_escalations (incident_sla_id, level) WHERE incident_sla_id IS NOT NULL;

-- Escalations recorded in Release 1 history become visible in the incident detail (not linked to an SLA clock).
INSERT INTO incident_escalations (incident_id, level, source, reason, actor_id, actor_name, created_at)
SELECT h.incident_id, (substring(h.message FROM 'level ([1-3])'))::int,
       CASE WHEN h.actor_id IS NOT NULL THEN 'MANUAL' WHEN h.actor_name LIKE 'Workflow:%' THEN 'WORKFLOW' ELSE 'SLA' END,
       left(h.message, 1000), h.actor_id, h.actor_name, h.created_at
  FROM incident_history h
 WHERE h.event_type = 'ESCALATED' AND h.message ~ 'level [1-3]';

-- ============================================================================ 4. service desk
INSERT INTO service_categories (code, name, description, icon, sort_order, is_demo) VALUES
    ('HARDWARE', 'Hardware', 'Laptops, monitors and peripherals', 'Laptop', 0, FALSE),
    ('SOFTWARE', 'Software', 'Licences and application installs', 'AppWindow', 1, FALSE),
    ('NETWORK', 'Network', 'VPN, Wi-Fi and connectivity', 'Wifi', 2, FALSE),
    ('ACCESS', 'Access Management', 'System and application access', 'KeyRound', 3, FALSE),
    ('ACCOUNT', 'Account', 'Passwords, mailboxes and user accounts', 'UserCog', 4, FALSE),
    ('FACILITIES', 'Facilities', 'Workspace and building services', 'Building2', 5, FALSE),
    ('GENERAL', 'General', 'General support and assistance', 'LifeBuoy', 6, FALSE)
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, icon = EXCLUDED.icon,
                                 sort_order = EXCLUDED.sort_order, is_demo = FALSE;

UPDATE catalog_items SET category_id = (SELECT id FROM service_categories WHERE code = 'NETWORK') WHERE is_demo AND name = 'VPN access';
UPDATE catalog_items SET category_id = (SELECT id FROM service_categories WHERE code = 'ACCOUNT')
 WHERE is_demo AND name IN ('Password reset assistance', 'Shared mailbox');
UPDATE catalog_items SET category_id = (SELECT id FROM service_categories WHERE code = 'GENERAL') WHERE is_demo AND name = 'General IT help';
DELETE FROM service_categories c
 WHERE c.code IN ('IT-SUPPORT', 'OTHER') AND c.is_demo
   AND NOT EXISTS (SELECT 1 FROM catalog_items i WHERE i.category_id = c.id);

ALTER TABLE service_requests ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM';
ALTER TABLE service_requests ADD CONSTRAINT ck_request_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));
CREATE INDEX ix_requests_priority ON service_requests (priority);

CREATE TABLE service_request_comments (
    id               BIGSERIAL PRIMARY KEY,
    request_id       BIGINT      NOT NULL REFERENCES service_requests (id) ON DELETE CASCADE,
    author_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    body             TEXT        NOT NULL,
    internal         BOOLEAN     NOT NULL DEFAULT FALSE,
    system_generated BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_request_comment_body CHECK (char_length(body) BETWEEN 1 AND 10000)
);
CREATE INDEX ix_request_comments_request ON service_request_comments (request_id, created_at);

CREATE TABLE service_request_history (
    id         BIGSERIAL PRIMARY KEY,
    request_id BIGINT       NOT NULL REFERENCES service_requests (id) ON DELETE CASCADE,
    actor_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name VARCHAR(120) NOT NULL,
    action     VARCHAR(40)  NOT NULL,
    from_value VARCHAR(200),
    to_value   VARCHAR(200),
    note       VARCHAR(1000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_request_history_request ON service_request_history (request_id, created_at);

-- Requests created in Release 1 get a creation entry so their history is never empty.
INSERT INTO service_request_history (request_id, actor_id, actor_name, action, to_value, note, created_at)
SELECT r.id, r.requester_id, coalesce(u.full_name, 'Unknown'), 'CREATED', r.status, 'Request raised (migrated record)', r.created_at
  FROM service_requests r LEFT JOIN users u ON u.id = r.requester_id;

-- ============================================================================ 5. workflows
ALTER TABLE workflows DROP CONSTRAINT ck_workflow_trigger;
ALTER TABLE workflows ADD CONSTRAINT ck_workflow_trigger CHECK (trigger_event IN ('INCIDENT_CREATED', 'INCIDENT_UPDATED',
    'INCIDENT_STATUS_CHANGED', 'INCIDENT_ASSIGNED', 'PRIORITY_CHANGED', 'SLA_AT_RISK', 'SLA_BREACHED', 'SERVICE_REQUEST_CREATED', 'SCHEDULED'));
ALTER TABLE workflows ADD COLUMN last_scheduled_run_at TIMESTAMPTZ;
ALTER TABLE workflow_executions ADD COLUMN branch VARCHAR(3) NOT NULL DEFAULT 'YES';
ALTER TABLE workflow_executions ADD CONSTRAINT ck_execution_branch CHECK (branch IN ('YES', 'NO'));
CREATE INDEX ix_executions_scheduled ON workflow_executions (workflow_id, incident_id, started_at DESC) WHERE trigger_event = 'SCHEDULED';
CREATE INDEX ix_executions_started ON workflow_executions (started_at DESC);
UPDATE workflow_versions SET definition = r2_migrate_definition(definition);

-- ============================================================================ 6. NEXUS AI
ALTER TABLE ai_interactions ADD COLUMN subject VARCHAR(200);
CREATE INDEX ix_ai_interactions_user ON ai_interactions (user_id, created_at DESC);

-- ============================================================================ 7. knowledge base
ALTER TABLE knowledge_articles DROP CONSTRAINT ck_kb_status;
ALTER TABLE knowledge_articles ADD CONSTRAINT ck_kb_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED'));
ALTER TABLE knowledge_articles
    ADD COLUMN rating_count INT NOT NULL DEFAULT 0,
    ADD COLUMN rating_average NUMERIC(3, 2),
    ADD COLUMN legacy_helpful_count INT NOT NULL DEFAULT 0,
    ADD COLUMN legacy_not_helpful_count INT NOT NULL DEFAULT 0,
    ADD COLUMN current_version INT NOT NULL DEFAULT 1;
-- Release 1 "helpful" votes had no voter identity; they are kept as a legacy baseline of the derived counters.
UPDATE knowledge_articles SET legacy_helpful_count = helpful_count, legacy_not_helpful_count = not_helpful_count;
ALTER TABLE knowledge_articles ADD CONSTRAINT ck_kb_rating CHECK (rating_count >= 0 AND (rating_average IS NULL OR rating_average BETWEEN 1 AND 5));

CREATE TABLE kb_article_versions (
    id             BIGSERIAL PRIMARY KEY,
    article_id     BIGINT       NOT NULL REFERENCES knowledge_articles (id) ON DELETE CASCADE,
    version_number INT          NOT NULL,
    title          VARCHAR(200) NOT NULL,
    summary        VARCHAR(500) NOT NULL,
    content        TEXT         NOT NULL,
    article_type   VARCHAR(20)  NOT NULL,
    category       VARCHAR(20)  NOT NULL,
    service_id     BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    tags           VARCHAR(400),
    editor_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    editor_name    VARCHAR(120) NOT NULL,
    change_note    VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_kb_version UNIQUE (article_id, version_number)
);
INSERT INTO kb_article_versions (article_id, version_number, title, summary, content, article_type, category, service_id, tags,
                                 editor_id, editor_name, change_note, created_at)
SELECT a.id, 1, a.title, a.summary, a.content, a.article_type, a.category, a.service_id, a.tags, a.author_id,
       coalesce(u.full_name, 'Unknown'), 'Initial version', a.created_at
  FROM knowledge_articles a LEFT JOIN users u ON u.id = a.author_id;

CREATE TABLE kb_article_ratings (
    id         BIGSERIAL PRIMARY KEY,
    article_id BIGINT      NOT NULL REFERENCES knowledge_articles (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    rating     INT         NOT NULL,
    comment    VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_kb_rating_value CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ux_kb_rating_user UNIQUE (article_id, user_id)
);

-- ============================================================================ 8. runbooks
ALTER TABLE runbooks ADD COLUMN prerequisites JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE runbooks ADD CONSTRAINT ck_runbook_prerequisites CHECK (jsonb_typeof(prerequisites) = 'array');
CREATE TABLE runbook_categories (
    runbook_id BIGINT      NOT NULL REFERENCES runbooks (id) ON DELETE CASCADE,
    category   VARCHAR(20) NOT NULL,
    PRIMARY KEY (runbook_id, category),
    CONSTRAINT ck_runbook_category CHECK (category IN
        ('APPLICATION', 'NETWORK', 'HARDWARE', 'ACCESS', 'DATABASE', 'EMAIL', 'SECURITY', 'FACILITIES', 'OTHER'))
);
CREATE INDEX ix_runbook_categories_category ON runbook_categories (category);
INSERT INTO runbook_categories (runbook_id, category) SELECT id, category FROM runbooks WHERE category IS NOT NULL;
ALTER TABLE runbooks DROP COLUMN category;
CREATE INDEX ix_runbook_exec_created ON runbook_executions (created_at DESC);

-- ============================================================================ 9. business service dependencies
CREATE TABLE service_dependencies (
    service_id    BIGINT      NOT NULL REFERENCES business_services (id) ON DELETE CASCADE,
    depends_on_id BIGINT      NOT NULL REFERENCES business_services (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (service_id, depends_on_id),
    CONSTRAINT ck_dependency_not_self CHECK (service_id <> depends_on_id)
);
CREATE INDEX ix_dependencies_depends_on ON service_dependencies (depends_on_id);
-- Dependencies between Release 1 demo services (no effect when the demo organisation is absent).
INSERT INTO service_dependencies (service_id, depends_on_id)
SELECT s.id, d.id
  FROM (VALUES ('PAYROLL', 'DATABASE'), ('PAYROLL', 'IAM'), ('HR-PORTAL', 'IAM'), ('ERP', 'DATABASE'), ('ERP', 'IAM'),
               ('EMAIL', 'NETWORK'), ('VPN', 'NETWORK'), ('IAM', 'NETWORK')) AS v(svc, dep)
  JOIN business_services s ON s.code = v.svc AND s.is_demo
  JOIN business_services d ON d.code = v.dep AND d.is_demo
ON CONFLICT DO NOTHING;

-- ============================================================================ 10. notifications
CREATE TABLE notification_preferences (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(40) NOT NULL,
    in_app     BOOLEAN     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, type),
    CONSTRAINT ck_notification_pref_type CHECK (type IN ('INCIDENT_ASSIGNED', 'INCIDENT_COMMENT', 'INCIDENT_RESOLVED', 'SLA_AT_RISK',
        'SLA_BREACHED', 'ESCALATION', 'APPROVAL_REQUESTED', 'APPROVAL_DECIDED', 'WORKFLOW_FAILED', 'REQUEST_UPDATED', 'ANNOUNCEMENT'))
);
UPDATE notifications SET type = 'APPROVAL_REQUESTED' WHERE type = 'APPROVAL_REQUIRED';

-- ============================================================================ 11. administration
CREATE TABLE incident_categories (
    code                  VARCHAR(20) PRIMARY KEY,
    name                  VARCHAR(80)  NOT NULL UNIQUE,
    description           VARCHAR(500),
    default_department_id BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    keywords              VARCHAR(1000),
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order            INT          NOT NULL DEFAULT 0,
    updated_by_id         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_incident_category_code CHECK (code IN
        ('APPLICATION', 'NETWORK', 'HARDWARE', 'ACCESS', 'DATABASE', 'EMAIL', 'SECURITY', 'FACILITIES', 'OTHER'))
);
INSERT INTO incident_categories (code, name, description, sort_order) VALUES
    ('APPLICATION', 'Application', 'Business applications, portals and software errors', 0),
    ('NETWORK', 'Network', 'Connectivity, VPN, Wi-Fi and internet', 1),
    ('HARDWARE', 'Hardware', 'Laptops, desktops, printers and peripherals', 2),
    ('ACCESS', 'Access', 'Sign-in, passwords, permissions and MFA', 3),
    ('DATABASE', 'Database', 'Database availability and performance', 4),
    ('EMAIL', 'Email', 'Mailboxes, calendars and mail delivery', 5),
    ('SECURITY', 'Security', 'Phishing, malware and suspicious activity', 6),
    ('FACILITIES', 'Facilities', 'Buildings, meeting rooms and workplace services', 7),
    ('OTHER', 'Other', 'Anything that does not fit another category', 8);

INSERT INTO system_settings (setting_key, setting_value, value_type, description) VALUES
    ('org.name', 'NEXUS 360', 'STRING', 'Organisation name shown in the application shell.'),
    ('org.support_email', '', 'STRING', 'Support e-mail address shown to employees (empty = not shown).'),
    ('org.timezone', 'Asia/Kolkata', 'STRING', 'Organisation time zone (IANA name) for display.'),
    ('org.business_hours_note', '', 'STRING', 'Short note about support hours shown to employees.'),
    ('notifications.sla_at_risk.notify_managers', 'false', 'BOOLEAN',
     'Also notify the department manager when an incident SLA becomes at risk.'),
    ('notifications.major.notify_all_staff', 'false', 'BOOLEAN',
     'Notify all support staff (not only managers and administrators) when a major incident is declared.')
ON CONFLICT (setting_key) DO NOTHING;

DROP FUNCTION r2_migrate_definition(JSONB);
DROP FUNCTION r2_map_status(TEXT);
DROP FUNCTION r2_map_role(TEXT);
