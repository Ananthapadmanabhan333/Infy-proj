-- SA Release 3 / BE-4 (docs/RELEASE3_SPEC.md §5 "BE-4"): on-call schedules (per team, time zone, DAILY/WEEKLY/CUSTOM rotations
-- with a local handoff time, ordered PRIMARY/SECONDARY participants, overrides that can be offered and taken, personal
-- unavailability, holiday calendars with an optional holiday cover), escalation policies (levels with delays: primary → secondary
-- → manager) used by the on-call alert escalation policy, handover notes and the page log (on-call performance); major incident
-- command records (roles, war-room timeline, stakeholder updates optionally published to the status page, impacted services,
-- action items); post-incident reviews (auto-built timeline, analysis, corrective/preventive actions, approval through the
-- approval engine type PIR). Existing major incidents get a command record (backfill). Demo records are created by the
-- application's demo seeder (flagged is_demo), never here.

-- ============================================================================ 1. on-call
CREATE TABLE oncall_escalation_policies (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    description   VARCHAR(500),
    team_id       BIGINT REFERENCES teams (id) ON DELETE SET NULL,
    -- Alerts below this severity are left to the built-in escalation (team-lead policy).
    min_severity  VARCHAR(10)  NOT NULL DEFAULT 'MAJOR',
    -- Ordered levels: [{delayMinutes, target PRIMARY|SECONDARY|MANAGER|USER, userId?}]; delay counts from the previous level
    -- (level 1: from the moment the alert became active).
    levels        JSONB        NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    is_demo       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_oncall_policy_severity CHECK (min_severity IN ('CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO')),
    CONSTRAINT ck_oncall_policy_levels CHECK (jsonb_typeof(levels) = 'array' AND jsonb_array_length(levels) BETWEEN 1 AND 10)
);
CREATE UNIQUE INDEX ux_oncall_policy_name ON oncall_escalation_policies (lower(name));

CREATE TABLE oncall_schedules (
    id                    BIGSERIAL PRIMARY KEY,
    team_id               BIGINT       NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    name                  VARCHAR(120) NOT NULL,
    description           VARCHAR(1000),
    time_zone             VARCHAR(60)  NOT NULL,
    rotation_type         VARCHAR(10)  NOT NULL,
    -- Local wall-clock time of every handoff (DAILY, WEEKLY; CUSTOM: the first handoff on rotation_start).
    handoff_time          TIME         NOT NULL DEFAULT '09:00',
    -- WEEKLY: ISO day of week of the handoff (1 = Monday).
    handoff_day           INT,
    -- CUSTOM: shift length in hours (exact durations, independent of daylight-saving changes).
    shift_hours           INT,
    rotation_start        DATE         NOT NULL,
    escalation_policy_id  BIGINT REFERENCES oncall_escalation_policies (id) ON DELETE SET NULL,
    holiday_calendar_id   BIGINT REFERENCES business_hours_calendars (id) ON DELETE SET NULL,
    holiday_cover_user_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    is_demo               BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_oncall_rotation CHECK (rotation_type IN ('DAILY', 'WEEKLY', 'CUSTOM')),
    CONSTRAINT ck_oncall_handoff_day CHECK (handoff_day IS NULL OR handoff_day BETWEEN 1 AND 7),
    CONSTRAINT ck_oncall_weekly CHECK (rotation_type <> 'WEEKLY' OR handoff_day IS NOT NULL),
    CONSTRAINT ck_oncall_custom CHECK (rotation_type <> 'CUSTOM' OR shift_hours BETWEEN 1 AND 336)
);
CREATE UNIQUE INDEX ux_oncall_schedule_name ON oncall_schedules (lower(name));
CREATE INDEX ix_oncall_schedule_team ON oncall_schedules (team_id) WHERE active;

-- Ordered participants per layer. Without SECONDARY participants the secondary is the next PRIMARY participant in the rotation.
CREATE TABLE oncall_participants (
    id          BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT      NOT NULL REFERENCES oncall_schedules (id) ON DELETE CASCADE,
    layer       VARCHAR(10) NOT NULL,
    position    INT         NOT NULL,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_oncall_participant_layer CHECK (layer IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ux_oncall_participant_position UNIQUE (schedule_id, layer, position),
    CONSTRAINT ux_oncall_participant_user UNIQUE (schedule_id, layer, user_id)
);
CREATE INDEX ix_oncall_participant_user ON oncall_participants (user_id);

-- Overrides: ACTIVE ones replace the rotation for their layer and window. OFFERED = waiting for the named user (user_id) or, with
-- user_id NULL, for any participant of the schedule to take it.
CREATE TABLE oncall_overrides (
    id               BIGSERIAL PRIMARY KEY,
    schedule_id      BIGINT       NOT NULL REFERENCES oncall_schedules (id) ON DELETE CASCADE,
    layer            VARCHAR(10)  NOT NULL DEFAULT 'PRIMARY',
    user_id          BIGINT REFERENCES users (id) ON DELETE CASCADE,
    original_user_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    starts_at        TIMESTAMPTZ  NOT NULL,
    ends_at          TIMESTAMPTZ  NOT NULL,
    reason           VARCHAR(500),
    status           VARCHAR(10)  NOT NULL,
    created_by_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    taken_at         TIMESTAMPTZ,
    decided_note     VARCHAR(500),
    is_demo          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_oncall_override_layer CHECK (layer IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ck_oncall_override_status CHECK (status IN ('OFFERED', 'ACTIVE', 'DECLINED', 'CANCELLED')),
    CONSTRAINT ck_oncall_override_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_oncall_override_user CHECK (status <> 'ACTIVE' OR user_id IS NOT NULL)
);
CREATE INDEX ix_oncall_override_schedule ON oncall_overrides (schedule_id, starts_at) WHERE status IN ('OFFERED', 'ACTIVE');

-- Personal unavailability (leave, training): the rotation skips the person to the next available participant.
CREATE TABLE oncall_unavailability (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    starts_at     TIMESTAMPTZ  NOT NULL,
    ends_at       TIMESTAMPTZ  NOT NULL,
    reason        VARCHAR(300),
    created_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_demo       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_oncall_unavailability_window CHECK (ends_at > starts_at)
);
CREATE INDEX ix_oncall_unavailability_user ON oncall_unavailability (user_id, ends_at);

CREATE TABLE oncall_handovers (
    id          BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT       NOT NULL REFERENCES oncall_schedules (id) ON DELETE CASCADE,
    author_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    author_name VARCHAR(160) NOT NULL,
    to_user_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    shift_start TIMESTAMPTZ,
    shift_end   TIMESTAMPTZ,
    body        TEXT         NOT NULL,
    is_demo     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_oncall_handover_body CHECK (char_length(body) BETWEEN 1 AND 5000)
);
CREATE INDEX ix_oncall_handover_schedule ON oncall_handovers (schedule_id, created_at DESC);

-- Every page sent by the on-call escalation policy (one row per alert, level and person; the unique key makes paging idempotent).
CREATE TABLE oncall_pages (
    id          BIGSERIAL PRIMARY KEY,
    alert_id    BIGINT      NOT NULL REFERENCES alerts (id) ON DELETE CASCADE,
    schedule_id BIGINT REFERENCES oncall_schedules (id) ON DELETE SET NULL,
    policy_id   BIGINT REFERENCES oncall_escalation_policies (id) ON DELETE SET NULL,
    level       INT         NOT NULL,
    target      VARCHAR(10) NOT NULL,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    paged_at    TIMESTAMPTZ NOT NULL,
    is_demo     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_oncall_page_target CHECK (target IN ('PRIMARY', 'SECONDARY', 'MANAGER', 'USER')),
    CONSTRAINT ux_oncall_page UNIQUE (alert_id, level, user_id)
);
CREATE INDEX ix_oncall_pages_time ON oncall_pages (paged_at);
CREATE INDEX ix_oncall_pages_schedule ON oncall_pages (schedule_id, paged_at);

-- ============================================================================ 2. major incident command
CREATE SEQUENCE major_incident_number_seq START WITH 101;

CREATE TABLE major_incidents (
    id                  BIGSERIAL PRIMARY KEY,
    number              VARCHAR(20)  NOT NULL UNIQUE,
    incident_id         BIGINT       NOT NULL UNIQUE REFERENCES incidents (id) ON DELETE CASCADE,
    status              VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE',
    commander_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    tech_lead_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    comms_lead_id       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    summary             VARCHAR(2000),
    impact_summary      TEXT,
    investigation_notes TEXT,
    resolution_summary  TEXT,
    bridge_url          VARCHAR(500),
    declared_at         TIMESTAMPTZ  NOT NULL,
    declared_by_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    resolved_at         TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    is_demo             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_major_incident_status CHECK (status IN ('ACTIVE', 'MONITORING', 'RESOLVED', 'CLOSED'))
);
CREATE INDEX ix_major_incidents_status ON major_incidents (status, declared_at DESC);

CREATE TABLE major_incident_services (
    major_incident_id BIGINT NOT NULL REFERENCES major_incidents (id) ON DELETE CASCADE,
    service_id        BIGINT NOT NULL REFERENCES business_services (id) ON DELETE CASCADE,
    PRIMARY KEY (major_incident_id, service_id)
);

-- War-room timeline: notes, decisions and actions written by responders plus system entries (status, roles, updates).
CREATE TABLE major_incident_timeline (
    id                BIGSERIAL PRIMARY KEY,
    major_incident_id BIGINT       NOT NULL REFERENCES major_incidents (id) ON DELETE CASCADE,
    entry_type        VARCHAR(16)  NOT NULL,
    body              VARCHAR(4000) NOT NULL,
    actor_id          BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name        VARCHAR(160) NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    is_demo           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_mi_timeline_type CHECK (entry_type IN ('NOTE', 'DECISION', 'ACTION', 'INVESTIGATION', 'STATUS', 'ROLE', 'UPDATE', 'SYSTEM'))
);
CREATE INDEX ix_mi_timeline ON major_incident_timeline (major_incident_id, occurred_at, id);

-- Stakeholder updates are internal; a published one is also a public status-page update (status_update_id).
CREATE TABLE stakeholder_updates (
    id                BIGSERIAL PRIMARY KEY,
    major_incident_id BIGINT       NOT NULL REFERENCES major_incidents (id) ON DELETE CASCADE,
    status            VARCHAR(20)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    message           TEXT         NOT NULL,
    status_update_id  BIGINT REFERENCES status_updates (id) ON DELETE SET NULL,
    published_at      TIMESTAMPTZ,
    posted_by_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    posted_by_name    VARCHAR(160) NOT NULL,
    posted_at         TIMESTAMPTZ  NOT NULL,
    is_demo           BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_stakeholder_update_status CHECK (status IN ('INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED')),
    CONSTRAINT ck_stakeholder_update_message CHECK (char_length(message) BETWEEN 1 AND 5000)
);
CREATE INDEX ix_stakeholder_updates ON stakeholder_updates (major_incident_id, posted_at DESC);

-- ============================================================================ 3. post-incident reviews
CREATE SEQUENCE pir_number_seq START WITH 301;

CREATE TABLE post_incident_reviews (
    id                   BIGSERIAL PRIMARY KEY,
    number               VARCHAR(20)  NOT NULL UNIQUE,
    incident_id          BIGINT       NOT NULL UNIQUE REFERENCES incidents (id) ON DELETE CASCADE,
    major_incident_id    BIGINT REFERENCES major_incidents (id) ON DELETE SET NULL,
    title                VARCHAR(200) NOT NULL,
    status               VARCHAR(12)  NOT NULL DEFAULT 'DRAFT',
    owner_id             BIGINT REFERENCES users (id) ON DELETE SET NULL,
    summary              TEXT,
    root_cause           TEXT,
    contributing_factors JSONB        NOT NULL DEFAULT '[]'::jsonb,
    impact               TEXT,
    detection_gaps       TEXT,
    response_analysis    TEXT,
    lessons_learned      TEXT,
    -- Auto-built from incident activity, SLA events, alerts and the war-room timeline: [{at, kind, source, title, detail, actorName}].
    timeline             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    -- Computed durations: {detectedAt, declaredAt, firstResponseAt, resolvedAt, minutesToDetect, minutesToAcknowledge, minutesToResolve}.
    metrics              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    timeline_built_at    TIMESTAMPTZ,
    approval_id          BIGINT REFERENCES approvals (id) ON DELETE SET NULL,
    submitted_at         TIMESTAMPTZ,
    approved_at          TIMESTAMPTZ,
    approved_by_id       BIGINT REFERENCES users (id) ON DELETE SET NULL,
    review_comment       VARCHAR(1000),
    created_by_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_demo              BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_pir_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_pir_json CHECK (jsonb_typeof(contributing_factors) = 'array' AND jsonb_typeof(timeline) = 'array'
        AND jsonb_typeof(metrics) = 'object'),
    CONSTRAINT ck_pir_approved CHECK (status <> 'APPROVED' OR approved_at IS NOT NULL)
);
CREATE INDEX ix_pir_status ON post_incident_reviews (status, created_at DESC);

-- Action items of a major incident (follow-ups) or of a review (corrective and preventive actions).
CREATE TABLE incident_action_items (
    id                BIGSERIAL PRIMARY KEY,
    major_incident_id BIGINT REFERENCES major_incidents (id) ON DELETE CASCADE,
    pir_id            BIGINT REFERENCES post_incident_reviews (id) ON DELETE CASCADE,
    title             VARCHAR(200) NOT NULL,
    description       VARCHAR(2000),
    action_type       VARCHAR(12)  NOT NULL DEFAULT 'FOLLOW_UP',
    owner_id          BIGINT REFERENCES users (id) ON DELETE SET NULL,
    due_date          DATE,
    status            VARCHAR(12)  NOT NULL DEFAULT 'OPEN',
    completed_at      TIMESTAMPTZ,
    created_by_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_demo           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_action_item_parent CHECK ((major_incident_id IS NULL) <> (pir_id IS NULL)),
    CONSTRAINT ck_action_item_type CHECK (action_type IN ('CORRECTIVE', 'PREVENTIVE', 'FOLLOW_UP')),
    CONSTRAINT ck_action_item_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED'))
);
CREATE INDEX ix_action_items_owner ON incident_action_items (owner_id) WHERE status IN ('OPEN', 'IN_PROGRESS');
CREATE INDEX ix_action_items_mi ON incident_action_items (major_incident_id) WHERE major_incident_id IS NOT NULL;
CREATE INDEX ix_action_items_pir ON incident_action_items (pir_id) WHERE pir_id IS NOT NULL;

-- ============================================================================ 4. existing major incidents get a command record
INSERT INTO major_incidents (number, incident_id, status, declared_at, resolved_at, closed_at, summary, is_demo, created_at, updated_at)
SELECT 'MI-' || nextval('major_incident_number_seq'), i.id,
       CASE WHEN i.status = 'CLOSED' THEN 'CLOSED' WHEN i.status = 'RESOLVED' THEN 'RESOLVED' ELSE 'ACTIVE' END,
       coalesce((SELECT min(h.created_at) FROM incident_history h WHERE h.incident_id = i.id
                    AND ((h.event_type = 'ESCALATED' AND h.message LIKE 'Declared a major incident%') OR (h.field = 'major' AND h.new_value = 'true'))),
                i.created_at),
       i.resolved_at, i.closed_at, left(i.title, 2000), i.is_demo, now(), now()
  FROM (SELECT * FROM incidents WHERE is_major AND parent_id IS NULL ORDER BY created_at) i;
INSERT INTO major_incident_services (major_incident_id, service_id)
SELECT m.id, i.service_id FROM major_incidents m JOIN incidents i ON i.id = m.incident_id WHERE i.service_id IS NOT NULL;
INSERT INTO major_incident_timeline (major_incident_id, entry_type, body, actor_name, occurred_at, is_demo)
SELECT m.id, 'SYSTEM', 'Major incident command record created for an incident declared major before Release 3', 'SA Automation', m.declared_at, m.is_demo
  FROM major_incidents m;

-- ============================================================================ 5. notification categories
ALTER TABLE notification_preferences DROP CONSTRAINT ck_notification_pref_type;
ALTER TABLE notification_preferences ADD CONSTRAINT ck_notification_pref_type CHECK (type IN ('INCIDENT_ASSIGNED', 'INCIDENT_COMMENT',
    'INCIDENT_RESOLVED', 'SLA_AT_RISK', 'SLA_BREACHED', 'ESCALATION', 'APPROVAL_REQUESTED', 'APPROVAL_DECIDED', 'WORKFLOW_FAILED',
    'REQUEST_UPDATED', 'ANNOUNCEMENT', 'ACCESS_REQUEST', 'PASSWORD_RESET_REQUEST', 'TEAM_QUEUE', 'REPORT_READY',
    'APPROVAL_ESCALATED', 'PROBLEM_UPDATED', 'CHANGE_UPDATED', 'DEPLOYMENT_STATUS',
    'ALERT_ROUTED', 'ALERT_ESCALATED', 'ASSET_ASSIGNED',
    'ONCALL_PAGE', 'ONCALL_SHIFT', 'MAJOR_INCIDENT_UPDATE', 'PIR_UPDATED'));

-- ============================================================================ 6. settings
INSERT INTO system_settings (setting_key, setting_value, value_type, description) VALUES
    ('ai.sla_risk.history_days', '180', 'NUMBER',
     'Predictive SLA risk: completed resolution clocks of the same priority from this many days are the historical sample.'),
    ('pir.due_days', '5', 'NUMBER', 'A post-incident review is due this many days after its major incident was resolved.')
ON CONFLICT (setting_key) DO NOTHING;
