-- SA Release 3 / BE-2 (docs/RELEASE3_SPEC.md §5 "BE-2"): approval engine v2 (flows, stages, tasks, delegation, history),
-- dynamic form builder (versioned form definitions, request-type permissions, request attachments), Problems, Changes
-- (templates, freeze windows, CAB through the approval engine) and Deployments (releases, deployment records, CI/CD
-- webhook sources and replay-safe deliveries). Existing approvals and catalogue forms are migrated without data loss.
-- Demo records are created by the application's demo seeder (flagged is_demo), never here; the two system approval
-- flows for changes are configuration (editable, not demo).

-- ============================================================================ 1. approval engine v2
CREATE TABLE approval_flows (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(40)  NOT NULL UNIQUE,
    name          VARCHAR(120) NOT NULL,
    description   VARCHAR(500),
    -- Subject type the flow is meant for (informational filter); NULL = any subject.
    subject_type  VARCHAR(20),
    -- Ordered stage definitions: [{name, mode, quorum, requiredCount, approvers[{kind, userId, role, teamId}], condition,
    -- deadlineHours, escalation {notifyManager, notifyRole, autoReject}}]
    stages        JSONB        NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    system_flow   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_demo       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_approval_flow_code CHECK (code ~ '^[A-Z0-9_-]{2,40}$'),
    CONSTRAINT ck_approval_flow_stages CHECK (jsonb_typeof(stages) = 'array' AND jsonb_array_length(stages) BETWEEN 1 AND 10)
);

ALTER TABLE approvals DROP CONSTRAINT ck_approval_type, DROP CONSTRAINT ck_approval_role;
ALTER TABLE approvals ALTER COLUMN approver_role DROP NOT NULL;
ALTER TABLE approvals
    ADD CONSTRAINT ck_approval_type CHECK (approval_type IN ('SERVICE_REQUEST', 'RUNBOOK_EXECUTION', 'RUNBOOK_STEP', 'AI_ACTION',
        'CHANGE', 'PIR', 'WORKFLOW')),
    ADD CONSTRAINT ck_approval_role CHECK (approver_role IS NULL OR approver_role IN ('SYSTEM_ADMIN', 'IT_ADMIN', 'IT_MANAGER',
        'INCIDENT_MANAGER', 'CHANGE_MANAGER', 'ASSET_MANAGER', 'SUPPORT_AGENT', 'EMPLOYEE', 'AUDITOR'));
ALTER TABLE approvals ADD COLUMN flow_id       BIGINT REFERENCES approval_flows (id) ON DELETE SET NULL;
ALTER TABLE approvals ADD COLUMN flow_name     VARCHAR(120);
ALTER TABLE approvals ADD COLUMN current_stage INT;
ALTER TABLE approvals ADD COLUMN stage_count   INT NOT NULL DEFAULT 1;
ALTER TABLE approvals ADD COLUMN due_at        TIMESTAMPTZ;
ALTER TABLE approvals ADD COLUMN link          VARCHAR(300);
-- Facts about the subject that conditional stages are evaluated against (e.g. {"changeRisk": "HIGH"}).
ALTER TABLE approvals ADD COLUMN facts         JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX ix_approvals_requested_by ON approvals (requested_by_id, created_at DESC);
CREATE INDEX ix_approvals_created ON approvals (created_at DESC);

CREATE TABLE approval_stages (
    id             BIGSERIAL PRIMARY KEY,
    approval_id    BIGINT       NOT NULL REFERENCES approvals (id) ON DELETE CASCADE,
    stage_order    INT          NOT NULL,
    name           VARCHAR(120) NOT NULL,
    mode           VARCHAR(10)  NOT NULL,
    quorum         VARCHAR(5)   NOT NULL,
    required_count INT          NOT NULL DEFAULT 1,
    stage_condition JSONB,
    status         VARCHAR(10)  NOT NULL,
    deadline_hours INT,
    due_at         TIMESTAMPTZ,
    escalation     JSONB,
    escalated_at   TIMESTAMPTZ,
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_approval_stage UNIQUE (approval_id, stage_order),
    CONSTRAINT ck_approval_stage_mode CHECK (mode IN ('SEQUENTIAL', 'PARALLEL')),
    CONSTRAINT ck_approval_stage_quorum CHECK (quorum IN ('ANY', 'ALL', 'N')),
    CONSTRAINT ck_approval_stage_required CHECK (required_count >= 1),
    CONSTRAINT ck_approval_stage_status CHECK (status IN ('WAITING', 'ACTIVE', 'APPROVED', 'REJECTED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT ck_approval_stage_deadline CHECK (deadline_hours IS NULL OR deadline_hours BETWEEN 1 AND 720)
);
CREATE INDEX ix_approval_stages_due ON approval_stages (due_at) WHERE status = 'ACTIVE' AND escalated_at IS NULL;

CREATE TABLE approval_tasks (
    id                BIGSERIAL PRIMARY KEY,
    approval_id       BIGINT      NOT NULL REFERENCES approvals (id) ON DELETE CASCADE,
    stage_id          BIGINT      NOT NULL REFERENCES approval_stages (id) ON DELETE CASCADE,
    seq               INT         NOT NULL,
    approver_kind     VARCHAR(10) NOT NULL,
    approver_user_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    approver_role     VARCHAR(20),
    approver_team_id  BIGINT REFERENCES teams (id) ON DELETE SET NULL,
    status            VARCHAR(10) NOT NULL,
    decided_by_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    decided_at        TIMESTAMPTZ,
    comment           VARCHAR(1000),
    delegated_from_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    delegated_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_approval_task_kind CHECK (approver_kind IN ('USER', 'ROLE', 'TEAM')),
    CONSTRAINT ck_approval_task_status CHECK (status IN ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT ck_approval_task_target CHECK ((approver_kind = 'USER' AND approver_user_id IS NOT NULL)
        OR (approver_kind = 'ROLE' AND approver_role IS NOT NULL) OR (approver_kind = 'TEAM' AND approver_team_id IS NOT NULL)
        OR status IN ('SKIPPED', 'CANCELLED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_approval_task_decided CHECK (status NOT IN ('APPROVED', 'REJECTED') OR decided_at IS NOT NULL)
);
CREATE INDEX ix_approval_tasks_approval ON approval_tasks (approval_id, stage_id, seq);
CREATE INDEX ix_approval_tasks_role ON approval_tasks (approver_role) WHERE status = 'PENDING';
CREATE INDEX ix_approval_tasks_user ON approval_tasks (approver_user_id) WHERE status = 'PENDING';
CREATE INDEX ix_approval_tasks_team ON approval_tasks (approver_team_id) WHERE status = 'PENDING';
CREATE INDEX ix_approval_tasks_decider ON approval_tasks (decided_by_id) WHERE decided_by_id IS NOT NULL;

-- Append-only history of everything that happened to an approval.
CREATE TABLE approval_events (
    id          BIGSERIAL PRIMARY KEY,
    approval_id BIGINT       NOT NULL REFERENCES approvals (id) ON DELETE CASCADE,
    stage_order INT,
    task_id     BIGINT,
    event_type  VARCHAR(30)  NOT NULL,
    actor_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name  VARCHAR(160) NOT NULL,
    comment     VARCHAR(1000),
    details     JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_approval_events_approval ON approval_events (approval_id, created_at, id);

-- Standing (out-of-office) delegations: tasks assigned to the delegator personally go to the delegate during the window.
CREATE TABLE approval_delegations (
    id           BIGSERIAL PRIMARY KEY,
    delegator_id BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    delegate_id  BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    starts_at    TIMESTAMPTZ  NOT NULL,
    ends_at      TIMESTAMPTZ  NOT NULL,
    reason       VARCHAR(500),
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_approval_delegation_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_approval_delegation_self CHECK (delegator_id <> delegate_id)
);
CREATE INDEX ix_approval_delegations_delegator ON approval_delegations (delegator_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_approval_delegations_delegate ON approval_delegations (delegate_id) WHERE revoked_at IS NULL;

-- Existing single-step approvals become one-stage approvals with one role task (same routing, decision and history).
INSERT INTO approval_stages (approval_id, stage_order, name, mode, quorum, required_count, status, started_at, completed_at)
SELECT a.id, 1, 'Approval', 'PARALLEL', 'ANY', 1,
       CASE a.status WHEN 'PENDING' THEN 'ACTIVE' WHEN 'APPROVED' THEN 'APPROVED' WHEN 'REJECTED' THEN 'REJECTED' ELSE 'CANCELLED' END,
       a.created_at, a.decided_at
  FROM approvals a
 WHERE NOT EXISTS (SELECT 1 FROM approval_stages s WHERE s.approval_id = a.id);
INSERT INTO approval_tasks (approval_id, stage_id, seq, approver_kind, approver_role, status, decided_by_id, decided_at, comment, created_at)
SELECT a.id, s.id, 1, 'ROLE', coalesce(a.approver_role, 'IT_MANAGER'), a.status, a.decided_by_id,
       CASE WHEN a.status IN ('APPROVED', 'REJECTED') THEN coalesce(a.decided_at, a.created_at) END, a.decision_comment, a.created_at
  FROM approvals a JOIN approval_stages s ON s.approval_id = a.id AND s.stage_order = 1
 WHERE NOT EXISTS (SELECT 1 FROM approval_tasks t WHERE t.approval_id = a.id);
UPDATE approvals SET current_stage = 1 WHERE current_stage IS NULL;
INSERT INTO approval_events (approval_id, stage_order, event_type, actor_id, actor_name, created_at)
SELECT a.id, NULL, 'REQUESTED', a.requested_by_id, coalesce(u.full_name, 'SA Automation'), a.created_at
  FROM approvals a LEFT JOIN users u ON u.id = a.requested_by_id
 WHERE NOT EXISTS (SELECT 1 FROM approval_events e WHERE e.approval_id = a.id);
INSERT INTO approval_events (approval_id, stage_order, event_type, actor_id, actor_name, comment, created_at)
SELECT a.id, 1, CASE a.status WHEN 'APPROVED' THEN 'TASK_APPROVED' ELSE 'TASK_REJECTED' END, a.decided_by_id,
       coalesce(u.full_name, 'SA Automation'), a.decision_comment, coalesce(a.decided_at, a.created_at)
  FROM approvals a LEFT JOIN users u ON u.id = a.decided_by_id
 WHERE a.status IN ('APPROVED', 'REJECTED');

-- System flows for changes (configuration; editable by approval-flow editors, cannot be deleted).
INSERT INTO approval_flows (code, name, description, subject_type, stages, system_flow) VALUES
    ('CHANGE_NORMAL', 'Normal change (CAB)', 'Change authority sign-off; high-risk changes also need the Change Advisory Board.', 'CHANGE',
     '[{"name": "Change authority", "mode": "PARALLEL", "quorum": "ANY", "requiredCount": 1,
        "approvers": [{"kind": "ROLE", "role": "CHANGE_MANAGER"}, {"kind": "ROLE", "role": "IT_MANAGER"}],
        "deadlineHours": 48, "escalation": {"notifyManager": false, "notifyRole": "SYSTEM_ADMIN", "autoReject": false}},
       {"name": "Change Advisory Board", "mode": "PARALLEL", "quorum": "N", "requiredCount": 2,
        "approvers": [{"kind": "ROLE", "role": "IT_MANAGER"}, {"kind": "ROLE", "role": "IT_MANAGER"}, {"kind": "ROLE", "role": "CHANGE_MANAGER"}],
        "condition": {"match": "ALL", "rules": [{"field": "changeRisk", "operator": "EQUALS", "value": "HIGH"}]},
        "deadlineHours": 72, "escalation": {"notifyManager": false, "notifyRole": "SYSTEM_ADMIN", "autoReject": false}}]'::jsonb, TRUE),
    ('CHANGE_EMERGENCY', 'Emergency change (ECAB)', 'Expedited approval by one change authority; escalates after 2 hours.', 'CHANGE',
     '[{"name": "Emergency CAB", "mode": "PARALLEL", "quorum": "ANY", "requiredCount": 1,
        "approvers": [{"kind": "ROLE", "role": "CHANGE_MANAGER"}, {"kind": "ROLE", "role": "IT_MANAGER"}, {"kind": "ROLE", "role": "INCIDENT_MANAGER"}],
        "deadlineHours": 2, "escalation": {"notifyManager": false, "notifyRole": "SYSTEM_ADMIN", "autoReject": false}}]'::jsonb, TRUE);

-- ============================================================================ 2. dynamic form builder
CREATE TABLE form_definitions (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(40)  NOT NULL UNIQUE,
    name            VARCHAR(150) NOT NULL,
    description     VARCHAR(1000),
    -- Templates are starting points that new forms (and catalogue items) can be copied from.
    is_template     BOOLEAN      NOT NULL DEFAULT FALSE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    current_version INT          NOT NULL DEFAULT 1,
    is_demo         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_form_code CHECK (code ~ '^[A-Z0-9_-]{2,40}$'),
    CONSTRAINT ck_form_version CHECK (current_version >= 1)
);

CREATE TABLE form_definition_versions (
    id             BIGSERIAL PRIMARY KEY,
    form_id        BIGINT      NOT NULL REFERENCES form_definitions (id) ON DELETE CASCADE,
    version_number INT         NOT NULL,
    fields         JSONB       NOT NULL,
    change_note    VARCHAR(500),
    created_by_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_form_version UNIQUE (form_id, version_number),
    CONSTRAINT ck_form_fields CHECK (jsonb_typeof(fields) = 'array')
);

-- Catalogue items (request types) use a form definition and may restrict who can submit them.
ALTER TABLE catalog_items ADD COLUMN form_definition_id BIGINT REFERENCES form_definitions (id) ON DELETE SET NULL;
ALTER TABLE catalog_items ADD COLUMN allowed_roles JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE catalog_items ADD COLUMN allowed_department_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE catalog_items ADD CONSTRAINT ck_catalog_allowed CHECK (jsonb_typeof(allowed_roles) = 'array'
    AND jsonb_typeof(allowed_department_ids) = 'array');
ALTER TABLE service_requests ADD COLUMN form_definition_id BIGINT REFERENCES form_definitions (id) ON DELETE SET NULL;
ALTER TABLE service_requests ADD COLUMN form_version INT;

-- Files submitted through file-upload form fields (uploaded first, then linked when the request is created).
CREATE TABLE service_request_attachments (
    id             BIGSERIAL PRIMARY KEY,
    request_id     BIGINT REFERENCES service_requests (id) ON DELETE CASCADE,
    uploaded_by_id BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    field_key      VARCHAR(40),
    file_name      VARCHAR(200) NOT NULL,
    content_type   VARCHAR(120) NOT NULL,
    size_bytes     INT          NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    data           BYTEA        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_request_attachment_size CHECK (size_bytes > 0 AND size_bytes <= 10485760)
);
CREATE INDEX ix_request_attachments_request ON service_request_attachments (request_id);
CREATE INDEX ix_request_attachments_staged ON service_request_attachments (uploaded_by_id, created_at) WHERE request_id IS NULL;

-- Every existing catalogue item gets its own form definition (version 1 = its current field list, unchanged).
INSERT INTO form_definitions (code, name, description, is_template, active, current_version, is_demo, created_at, updated_at)
SELECT 'ITEM-' || i.id, left(i.name || ' form', 150), 'Migrated from the catalogue item "' || left(i.name, 150) || '".', FALSE, TRUE, 1,
       i.is_demo, i.created_at, now()
  FROM catalog_items i
 WHERE i.form_definition_id IS NULL;
INSERT INTO form_definition_versions (form_id, version_number, fields, change_note, created_at)
SELECT f.id, 1, i.form_fields, 'Migrated from the Release 2 catalogue form', i.created_at
  FROM catalog_items i JOIN form_definitions f ON f.code = 'ITEM-' || i.id
 WHERE i.form_definition_id IS NULL;
UPDATE catalog_items i SET form_definition_id = f.id FROM form_definitions f WHERE f.code = 'ITEM-' || i.id AND i.form_definition_id IS NULL;
UPDATE service_requests r SET form_definition_id = i.form_definition_id, form_version = 1
  FROM catalog_items i WHERE i.id = r.catalog_item_id AND r.form_definition_id IS NULL;

-- ============================================================================ 3. problems
CREATE SEQUENCE problem_number_seq START WITH 1001;

CREATE TABLE problems (
    id                     BIGSERIAL PRIMARY KEY,
    number                 VARCHAR(20)  NOT NULL UNIQUE,
    title                  VARCHAR(200) NOT NULL,
    description            TEXT,
    status                 VARCHAR(25)  NOT NULL DEFAULT 'NEW',
    priority               VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    category               VARCHAR(20),
    owner_id               BIGINT REFERENCES users (id) ON DELETE SET NULL,
    team_id                BIGINT REFERENCES teams (id) ON DELETE SET NULL,
    department_id          BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    root_cause             TEXT,
    workaround             TEXT,
    permanent_fix          TEXT,
    known_error            BOOLEAN      NOT NULL DEFAULT FALSE,
    known_error_at         TIMESTAMPTZ,
    kb_article_id          BIGINT REFERENCES knowledge_articles (id) ON DELETE SET NULL,
    affected_cis           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    closure_note           VARCHAR(1000),
    root_cause_at          TIMESTAMPTZ,
    resolved_at            TIMESTAMPTZ,
    closed_at              TIMESTAMPTZ,
    created_by_id          BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_demo                BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_problem_status CHECK (status IN ('NEW', 'INVESTIGATING', 'ROOT_CAUSE_IDENTIFIED', 'KNOWN_ERROR', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_problem_priority CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_problem_category CHECK (category IS NULL OR category IN
        ('APPLICATION', 'NETWORK', 'HARDWARE', 'ACCESS', 'DATABASE', 'EMAIL', 'SECURITY', 'FACILITIES', 'OTHER')),
    CONSTRAINT ck_problem_cis CHECK (jsonb_typeof(affected_cis) = 'array'),
    CONSTRAINT ck_problem_known_error CHECK (NOT known_error OR known_error_at IS NOT NULL)
);
CREATE INDEX ix_problems_status ON problems (status, created_at DESC);
CREATE INDEX ix_problems_owner ON problems (owner_id) WHERE owner_id IS NOT NULL;
CREATE INDEX ix_problems_team ON problems (team_id) WHERE team_id IS NOT NULL;

CREATE TABLE problem_services (
    problem_id BIGINT NOT NULL REFERENCES problems (id) ON DELETE CASCADE,
    service_id BIGINT NOT NULL REFERENCES business_services (id) ON DELETE CASCADE,
    PRIMARY KEY (problem_id, service_id)
);
CREATE INDEX ix_problem_services_service ON problem_services (service_id);

-- An incident belongs to at most one problem.
CREATE TABLE problem_incidents (
    problem_id   BIGINT      NOT NULL REFERENCES problems (id) ON DELETE CASCADE,
    incident_id  BIGINT      NOT NULL UNIQUE REFERENCES incidents (id) ON DELETE CASCADE,
    linked_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (problem_id, incident_id)
);

CREATE TABLE problem_actions (
    id           BIGSERIAL PRIMARY KEY,
    problem_id   BIGINT       NOT NULL REFERENCES problems (id) ON DELETE CASCADE,
    title        VARCHAR(200) NOT NULL,
    description  VARCHAR(2000),
    action_type  VARCHAR(12)  NOT NULL DEFAULT 'CORRECTIVE',
    owner_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    due_date     DATE,
    status       VARCHAR(12)  NOT NULL DEFAULT 'OPEN',
    completed_at TIMESTAMPTZ,
    is_demo      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_problem_action_type CHECK (action_type IN ('CORRECTIVE', 'PREVENTIVE')),
    CONSTRAINT ck_problem_action_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_problem_action_done CHECK (status <> 'DONE' OR completed_at IS NOT NULL)
);
CREATE INDEX ix_problem_actions_problem ON problem_actions (problem_id);
CREATE INDEX ix_problem_actions_owner ON problem_actions (owner_id) WHERE status IN ('OPEN', 'IN_PROGRESS');

CREATE TABLE problem_history (
    id         BIGSERIAL PRIMARY KEY,
    problem_id BIGINT       NOT NULL REFERENCES problems (id) ON DELETE CASCADE,
    actor_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name VARCHAR(160) NOT NULL,
    action     VARCHAR(40)  NOT NULL,
    from_value VARCHAR(200),
    to_value   VARCHAR(200),
    note       VARCHAR(1000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_problem_history_problem ON problem_history (problem_id, created_at, id);

-- ============================================================================ 4. changes
CREATE SEQUENCE change_number_seq START WITH 5001;

CREATE TABLE change_templates (
    id                       BIGSERIAL PRIMARY KEY,
    code                     VARCHAR(40)  NOT NULL UNIQUE,
    name                     VARCHAR(150) NOT NULL,
    description              VARCHAR(1000),
    category                 VARCHAR(20),
    implementation_plan      TEXT         NOT NULL,
    rollback_plan            TEXT         NOT NULL,
    test_plan                TEXT,
    default_duration_minutes INT          NOT NULL DEFAULT 60,
    service_ids              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    active                   BOOLEAN      NOT NULL DEFAULT TRUE,
    is_demo                  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id            BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_change_template_code CHECK (code ~ '^[A-Z0-9_-]{2,40}$'),
    CONSTRAINT ck_change_template_duration CHECK (default_duration_minutes BETWEEN 5 AND 10080),
    CONSTRAINT ck_change_template_services CHECK (jsonb_typeof(service_ids) = 'array')
);

CREATE TABLE changes (
    id                       BIGSERIAL PRIMARY KEY,
    number                   VARCHAR(20)  NOT NULL UNIQUE,
    title                    VARCHAR(200) NOT NULL,
    description              TEXT,
    change_type              VARCHAR(10)  NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    category                 VARCHAR(20),
    priority                 VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    risk                     VARCHAR(10)  NOT NULL DEFAULT 'LOW',
    risk_score               INT          NOT NULL DEFAULT 0,
    risk_answers             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    risk_factors             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    risk_assessed_at         TIMESTAMPTZ,
    impact                   VARCHAR(10)  NOT NULL DEFAULT 'LOW',
    affected_users           INT          NOT NULL DEFAULT 0,
    affected_cis             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    justification            TEXT,
    implementation_plan      TEXT,
    rollback_plan            TEXT,
    test_plan                TEXT,
    planned_start            TIMESTAMPTZ,
    planned_end              TIMESTAMPTZ,
    actual_start             TIMESTAMPTZ,
    actual_end               TIMESTAMPTZ,
    requester_id             BIGINT REFERENCES users (id) ON DELETE SET NULL,
    assignee_id              BIGINT REFERENCES users (id) ON DELETE SET NULL,
    team_id                  BIGINT REFERENCES teams (id) ON DELETE SET NULL,
    template_id              BIGINT REFERENCES change_templates (id) ON DELETE SET NULL,
    approval_id              BIGINT REFERENCES approvals (id) ON DELETE SET NULL,
    problem_id               BIGINT REFERENCES problems (id) ON DELETE SET NULL,
    outcome_notes            TEXT,
    pir_required             BOOLEAN      NOT NULL DEFAULT FALSE,
    pir_notes                TEXT,
    pir_completed_at         TIMESTAMPTZ,
    pir_by_id                BIGINT REFERENCES users (id) ON DELETE SET NULL,
    conflict_override_reason VARCHAR(1000),
    submitted_at             TIMESTAMPTZ,
    approved_at              TIMESTAMPTZ,
    closed_at                TIMESTAMPTZ,
    is_demo                  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_change_type CHECK (change_type IN ('STANDARD', 'NORMAL', 'EMERGENCY')),
    CONSTRAINT ck_change_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'ASSESSMENT', 'AWAITING_APPROVAL', 'APPROVED', 'SCHEDULED',
        'IMPLEMENTING', 'COMPLETED', 'FAILED', 'ROLLED_BACK', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_change_priority CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_change_risk CHECK (risk IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_change_impact CHECK (impact IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_change_category CHECK (category IS NULL OR category IN
        ('APPLICATION', 'NETWORK', 'HARDWARE', 'ACCESS', 'DATABASE', 'EMAIL', 'SECURITY', 'FACILITIES', 'OTHER')),
    CONSTRAINT ck_change_window CHECK (planned_end IS NULL OR planned_start IS NULL OR planned_end > planned_start),
    CONSTRAINT ck_change_actual CHECK (actual_end IS NULL OR actual_start IS NULL OR actual_end >= actual_start),
    CONSTRAINT ck_change_affected CHECK (affected_users >= 0 AND risk_score >= 0),
    CONSTRAINT ck_change_json CHECK (jsonb_typeof(risk_answers) = 'object' AND jsonb_typeof(risk_factors) = 'array'
        AND jsonb_typeof(affected_cis) = 'array')
);
CREATE INDEX ix_changes_status ON changes (status, created_at DESC);
CREATE INDEX ix_changes_window ON changes (planned_start, planned_end) WHERE planned_start IS NOT NULL;
CREATE INDEX ix_changes_assignee ON changes (assignee_id) WHERE assignee_id IS NOT NULL;
CREATE INDEX ix_changes_approval ON changes (approval_id) WHERE approval_id IS NOT NULL;

CREATE TABLE change_services (
    change_id  BIGINT NOT NULL REFERENCES changes (id) ON DELETE CASCADE,
    service_id BIGINT NOT NULL REFERENCES business_services (id) ON DELETE CASCADE,
    PRIMARY KEY (change_id, service_id)
);
CREATE INDEX ix_change_services_service ON change_services (service_id);

CREATE TABLE change_incidents (
    change_id     BIGINT      NOT NULL REFERENCES changes (id) ON DELETE CASCADE,
    incident_id   BIGINT      NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    relation      VARCHAR(12) NOT NULL,
    created_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (change_id, incident_id),
    CONSTRAINT ck_change_incident_relation CHECK (relation IN ('CAUSED_BY', 'RESOLVES', 'RELATED'))
);
CREATE INDEX ix_change_incidents_incident ON change_incidents (incident_id);

CREATE TABLE change_history (
    id         BIGSERIAL PRIMARY KEY,
    change_id  BIGINT       NOT NULL REFERENCES changes (id) ON DELETE CASCADE,
    actor_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name VARCHAR(160) NOT NULL,
    action     VARCHAR(40)  NOT NULL,
    from_value VARCHAR(200),
    to_value   VARCHAR(200),
    note       VARCHAR(1000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_change_history_change ON change_history (change_id, created_at, id);

-- Change freeze windows: no NORMAL or STANDARD change may be scheduled into them (EMERGENCY changes may, and are flagged).
CREATE TABLE change_freeze_windows (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(150) NOT NULL,
    reason        VARCHAR(1000),
    starts_at     TIMESTAMPTZ  NOT NULL,
    ends_at       TIMESTAMPTZ  NOT NULL,
    -- Business service ids the freeze applies to; empty = every service.
    service_ids   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    is_demo       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_freeze_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_freeze_services CHECK (jsonb_typeof(service_ids) = 'array')
);
CREATE INDEX ix_freeze_windows_range ON change_freeze_windows (starts_at, ends_at) WHERE active;

-- ============================================================================ 5. releases and deployments
CREATE SEQUENCE release_number_seq START WITH 101;
CREATE SEQUENCE deployment_number_seq START WITH 9001;

CREATE TABLE releases (
    id          BIGSERIAL PRIMARY KEY,
    number      VARCHAR(20)  NOT NULL UNIQUE,
    name        VARCHAR(150) NOT NULL,
    version     VARCHAR(80)  NOT NULL,
    description VARCHAR(2000),
    status      VARCHAR(12)  NOT NULL DEFAULT 'PLANNED',
    service_id  BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    change_id   BIGINT REFERENCES changes (id) ON DELETE SET NULL,
    owner_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    target_at   TIMESTAMPTZ,
    is_demo     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    row_version BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_release_status CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'DEPLOYED', 'FAILED', 'CANCELLED'))
);

-- Webhook sources: one per CI/CD pipeline; the shared secret is stored encrypted (AES-256-GCM, SecretCipher).
CREATE TABLE deployment_sources (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(120) NOT NULL UNIQUE,
    provider            VARCHAR(10)  NOT NULL,
    secret_encrypted    VARCHAR(500) NOT NULL,
    service_id          BIGINT       NOT NULL REFERENCES business_services (id),
    default_environment VARCHAR(12)  NOT NULL DEFAULT 'PRODUCTION',
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    last_delivery_at    TIMESTAMPTZ,
    is_demo             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_deployment_source_provider CHECK (provider IN ('GITHUB', 'GITLAB', 'GENERIC')),
    CONSTRAINT ck_deployment_source_env CHECK (default_environment IN ('DEV', 'TEST', 'STAGING', 'PRODUCTION'))
);

CREATE TABLE deployments (
    id              BIGSERIAL PRIMARY KEY,
    number          VARCHAR(20)  NOT NULL UNIQUE,
    release_id      BIGINT REFERENCES releases (id) ON DELETE SET NULL,
    change_id       BIGINT REFERENCES changes (id) ON DELETE SET NULL,
    service_id      BIGINT       NOT NULL REFERENCES business_services (id),
    environment     VARCHAR(12)  NOT NULL,
    version         VARCHAR(80)  NOT NULL,
    commit_sha      VARCHAR(64),
    pipeline_url    VARCHAR(500),
    status          VARCHAR(12)  NOT NULL,
    source          VARCHAR(10)  NOT NULL DEFAULT 'MANUAL',
    source_id       BIGINT REFERENCES deployment_sources (id) ON DELETE SET NULL,
    external_id     VARCHAR(120),
    actor_name      VARCHAR(160),
    actor_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    started_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ,
    rollback_of_id  BIGINT REFERENCES deployments (id) ON DELETE SET NULL,
    rolled_back_at  TIMESTAMPTZ,
    rollback_reason VARCHAR(1000),
    notes           VARCHAR(2000),
    is_demo         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    row_version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_deployment_env CHECK (environment IN ('DEV', 'TEST', 'STAGING', 'PRODUCTION')),
    CONSTRAINT ck_deployment_status CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT ck_deployment_source CHECK (source IN ('MANUAL', 'GITHUB', 'GITLAB', 'GENERIC')),
    CONSTRAINT ck_deployment_finished CHECK (finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT ck_deployment_rolled_back CHECK (status <> 'ROLLED_BACK' OR rolled_back_at IS NOT NULL)
);
CREATE UNIQUE INDEX ux_deployment_external ON deployments (source_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX ix_deployments_service ON deployments (service_id, started_at DESC);
CREATE INDEX ix_deployments_started ON deployments (started_at DESC);

-- Replay protection: a delivery id is processed at most once per source.
CREATE TABLE deployment_webhook_deliveries (
    id            BIGSERIAL PRIMARY KEY,
    source_id     BIGINT       NOT NULL REFERENCES deployment_sources (id) ON DELETE CASCADE,
    delivery_id   VARCHAR(200) NOT NULL,
    event_type    VARCHAR(60),
    outcome       VARCHAR(10)  NOT NULL,
    message       VARCHAR(500),
    deployment_id BIGINT REFERENCES deployments (id) ON DELETE SET NULL,
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_deployment_delivery UNIQUE (source_id, delivery_id),
    CONSTRAINT ck_deployment_delivery_outcome CHECK (outcome IN ('PROCESSED', 'IGNORED', 'FAILED'))
);
CREATE INDEX ix_deployment_deliveries_source ON deployment_webhook_deliveries (source_id, received_at DESC);

-- ============================================================================ 6. workflows
ALTER TABLE workflows DROP CONSTRAINT ck_workflow_trigger;
ALTER TABLE workflows ADD CONSTRAINT ck_workflow_trigger CHECK (trigger_event IN ('INCIDENT_CREATED', 'INCIDENT_UPDATED',
    'INCIDENT_STATUS_CHANGED', 'INCIDENT_ASSIGNED', 'PRIORITY_CHANGED', 'SLA_AT_RISK', 'SLA_BREACHED', 'SERVICE_REQUEST_CREATED', 'SCHEDULED',
    'CHANGE_REQUEST_CREATED'));
ALTER TABLE workflow_executions ADD COLUMN change_id BIGINT REFERENCES changes (id) ON DELETE SET NULL;

-- ============================================================================ 7. notification categories
ALTER TABLE notification_preferences DROP CONSTRAINT ck_notification_pref_type;
ALTER TABLE notification_preferences ADD CONSTRAINT ck_notification_pref_type CHECK (type IN ('INCIDENT_ASSIGNED', 'INCIDENT_COMMENT',
    'INCIDENT_RESOLVED', 'SLA_AT_RISK', 'SLA_BREACHED', 'ESCALATION', 'APPROVAL_REQUESTED', 'APPROVAL_DECIDED', 'WORKFLOW_FAILED',
    'REQUEST_UPDATED', 'ANNOUNCEMENT', 'ACCESS_REQUEST', 'PASSWORD_RESET_REQUEST', 'TEAM_QUEUE', 'REPORT_READY',
    'APPROVAL_ESCALATED', 'PROBLEM_UPDATED', 'CHANGE_UPDATED', 'DEPLOYMENT_STATUS'));

-- ============================================================================ 8. settings
INSERT INTO system_settings (setting_key, setting_value, value_type, description) VALUES
    ('change.correlation.hours', '24', 'NUMBER',
     'Incidents on a changed service created within this many hours after the change started are shown as possibly related.'),
    ('change.freeze.enforce', 'true', 'BOOLEAN',
     'Reject scheduling NORMAL and STANDARD changes into an active change freeze window (EMERGENCY changes are allowed and flagged).'),
    ('deployment.correlation.hours', '12', 'NUMBER',
     'Incidents on a deployed service created within this many hours after a deployment are shown as possibly related.'),
    ('problem.recurring.window_days', '30', 'NUMBER', 'Recurring-incident detection looks at incidents created in this many days.'),
    ('problem.recurring.min_incidents', '3', 'NUMBER', 'Smallest cluster of similar incidents (without a problem) suggested as a problem.'),
    ('problem.recurring.similarity', '0.42', 'NUMBER', 'Text similarity (0-1) at which two incidents on the same service are clustered.')
ON CONFLICT (setting_key) DO NOTHING;
