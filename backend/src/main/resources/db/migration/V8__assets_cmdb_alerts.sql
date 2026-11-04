-- SA Release 3 / BE-3 (docs/RELEASE3_SPEC.md §5 "BE-3"): CMDB (configuration items, typed relationships, history; every
-- business service gets a BUSINESS_SERVICE CI and service dependencies become CI relationships), asset management
-- (hardware/software inventory, licences with seats, assignment history, maintenance, lifecycle events), and alert
-- management (sources for Prometheus Alertmanager / Grafana / AWS CloudWatch via SNS / generic signed JSON, fingerprint
-- deduplication, suppression and routing rules, escalation state, history). Problems and changes gain optional CI links
-- (backfilled where an affected-CI name matches a CI exactly; the free-text field keeps working).
-- Demo records are created by the application's demo seeder (flagged is_demo), never here.

-- ============================================================================ 1. CMDB
CREATE TABLE configuration_items (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120)  NOT NULL,
    ci_type       VARCHAR(20)   NOT NULL,
    status        VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE',
    environment   VARCHAR(12),
    criticality   VARCHAR(10)   NOT NULL DEFAULT 'MEDIUM',
    description   VARCHAR(2000),
    owner_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    team_id       BIGINT REFERENCES teams (id) ON DELETE SET NULL,
    -- BUSINESS_SERVICE CIs mirror one business service (kept in sync by the application).
    service_id    BIGINT UNIQUE REFERENCES business_services (id) ON DELETE SET NULL,
    attributes    JSONB         NOT NULL DEFAULT '{}'::jsonb,
    is_demo       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_ci_type CHECK (ci_type IN ('APPLICATION', 'SERVER', 'DATABASE', 'NETWORK_DEVICE', 'CLOUD_RESOURCE', 'OPERATING_SYSTEM',
        'SOFTWARE', 'BUSINESS_SERVICE', 'INFRASTRUCTURE')),
    CONSTRAINT ck_ci_status CHECK (status IN ('PLANNED', 'ACTIVE', 'MAINTENANCE', 'RETIRED')),
    CONSTRAINT ck_ci_environment CHECK (environment IS NULL OR environment IN ('PRODUCTION', 'STAGING', 'TEST', 'DEV', 'DR')),
    CONSTRAINT ck_ci_criticality CHECK (criticality IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_ci_attributes CHECK (jsonb_typeof(attributes) = 'object'),
    CONSTRAINT ck_ci_service CHECK (service_id IS NULL OR ci_type = 'BUSINESS_SERVICE')
);
-- CI names are unique regardless of case (affected-CI names on problems and changes are matched by name).
CREATE UNIQUE INDEX ux_ci_name ON configuration_items (lower(name));
CREATE INDEX ix_ci_type ON configuration_items (ci_type, status);
CREATE INDEX ix_ci_team ON configuration_items (team_id) WHERE team_id IS NOT NULL;

-- Typed, directed relationships. DEPENDS_ON / RUNS_ON / CONNECTS_TO: the source needs the target; HOSTS: the target runs on the
-- source; PART_OF: the source is a component of the target. Cycles are allowed in the data; traversal is cycle-safe.
CREATE TABLE ci_relationships (
    id            BIGSERIAL PRIMARY KEY,
    source_id     BIGINT       NOT NULL REFERENCES configuration_items (id) ON DELETE CASCADE,
    target_id     BIGINT       NOT NULL REFERENCES configuration_items (id) ON DELETE CASCADE,
    rel_type      VARCHAR(12)  NOT NULL,
    description   VARCHAR(300),
    -- SERVICE_MAP relationships mirror business-service dependencies and are maintained by the application.
    origin        VARCHAR(12)  NOT NULL DEFAULT 'MANUAL',
    is_demo       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_ci_relationship UNIQUE (source_id, target_id, rel_type),
    CONSTRAINT ck_ci_rel_type CHECK (rel_type IN ('DEPENDS_ON', 'RUNS_ON', 'HOSTS', 'CONNECTS_TO', 'PART_OF')),
    CONSTRAINT ck_ci_rel_self CHECK (source_id <> target_id),
    CONSTRAINT ck_ci_rel_origin CHECK (origin IN ('MANUAL', 'SERVICE_MAP'))
);
CREATE INDEX ix_ci_rel_target ON ci_relationships (target_id);

CREATE TABLE ci_history (
    id         BIGSERIAL PRIMARY KEY,
    ci_id      BIGINT       NOT NULL REFERENCES configuration_items (id) ON DELETE CASCADE,
    actor_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name VARCHAR(160) NOT NULL,
    action     VARCHAR(40)  NOT NULL,
    from_value VARCHAR(500),
    to_value   VARCHAR(500),
    note       VARCHAR(1000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_ci_history_ci ON ci_history (ci_id, created_at, id);

-- Every existing business service gets its BUSINESS_SERVICE CI; service dependencies become DEPENDS_ON relationships.
INSERT INTO configuration_items (name, ci_type, status, environment, criticality, description, owner_id, team_id, service_id, attributes,
                                 is_demo, created_at, updated_at)
SELECT s.name, 'BUSINESS_SERVICE', 'ACTIVE', 'PRODUCTION', CASE s.tier WHEN 1 THEN 'CRITICAL' WHEN 2 THEN 'HIGH' ELSE 'MEDIUM' END,
       left(s.description, 2000), s.owner_id, s.default_team_id, s.id, jsonb_build_object('serviceCode', s.code, 'tier', s.tier), s.is_demo,
       now(), now()
  FROM business_services s
 WHERE NOT EXISTS (SELECT 1 FROM configuration_items c WHERE c.service_id = s.id)
ON CONFLICT DO NOTHING;
INSERT INTO ci_history (ci_id, actor_name, action, note)
SELECT c.id, 'SA Automation', 'CREATED', 'Created from business service ' || (c.attributes ->> 'serviceCode') || ' by migration V8'
  FROM configuration_items c WHERE c.service_id IS NOT NULL;
INSERT INTO ci_relationships (source_id, target_id, rel_type, description, origin, is_demo, created_at)
SELECT a.id, b.id, 'DEPENDS_ON', 'Business service dependency', 'SERVICE_MAP', a.is_demo AND b.is_demo, d.created_at
  FROM service_dependencies d
  JOIN configuration_items a ON a.service_id = d.service_id
  JOIN configuration_items b ON b.service_id = d.depends_on_id
ON CONFLICT DO NOTHING;

-- Problems and changes: optional links to CIs next to the free-text affected_cis names.
CREATE TABLE problem_cis (
    problem_id BIGINT NOT NULL REFERENCES problems (id) ON DELETE CASCADE,
    ci_id      BIGINT NOT NULL REFERENCES configuration_items (id) ON DELETE CASCADE,
    PRIMARY KEY (problem_id, ci_id)
);
CREATE INDEX ix_problem_cis_ci ON problem_cis (ci_id);
CREATE TABLE change_cis (
    change_id BIGINT NOT NULL REFERENCES changes (id) ON DELETE CASCADE,
    ci_id     BIGINT NOT NULL REFERENCES configuration_items (id) ON DELETE CASCADE,
    PRIMARY KEY (change_id, ci_id)
);
CREATE INDEX ix_change_cis_ci ON change_cis (ci_id);
INSERT INTO problem_cis (problem_id, ci_id)
SELECT DISTINCT p.id, c.id FROM problems p CROSS JOIN LATERAL jsonb_array_elements_text(p.affected_cis) AS n(name)
  JOIN configuration_items c ON lower(c.name) = lower(trim(n.name))
ON CONFLICT DO NOTHING;
INSERT INTO change_cis (change_id, ci_id)
SELECT DISTINCT x.id, c.id FROM changes x CROSS JOIN LATERAL jsonb_array_elements_text(x.affected_cis) AS n(name)
  JOIN configuration_items c ON lower(c.name) = lower(trim(n.name))
ON CONFLICT DO NOTHING;

-- ============================================================================ 2. assets
CREATE SEQUENCE asset_tag_seq START WITH 10001;

CREATE TABLE assets (
    id               BIGSERIAL PRIMARY KEY,
    asset_tag        VARCHAR(40)   NOT NULL UNIQUE,
    name             VARCHAR(150)  NOT NULL,
    asset_type       VARCHAR(20)   NOT NULL,
    status           VARCHAR(12)   NOT NULL DEFAULT 'IN_STOCK',
    manufacturer     VARCHAR(80),
    model            VARCHAR(120),
    serial_number    VARCHAR(120),
    -- Hardware: the current holder (history in asset_assignments). Licences use seat assignments instead.
    assigned_to_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    assigned_at      TIMESTAMPTZ,
    department_id    BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    location         VARCHAR(160),
    vendor           VARCHAR(120),
    purchase_order   VARCHAR(60),
    purchase_cost    NUMERIC(12, 2),
    currency         VARCHAR(3)    NOT NULL DEFAULT 'INR',
    purchase_date    DATE,
    warranty_expires DATE,
    license_seats    INT,
    license_expires  DATE,
    ci_id            BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    notes            VARCHAR(2000),
    retired_at       TIMESTAMPTZ,
    disposed_at      TIMESTAMPTZ,
    is_demo          BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_asset_type CHECK (asset_type IN ('LAPTOP', 'DESKTOP', 'SERVER', 'NETWORK', 'MOBILE', 'MONITOR', 'PERIPHERAL',
        'SOFTWARE_LICENSE', 'OTHER')),
    CONSTRAINT ck_asset_status CHECK (status IN ('IN_STOCK', 'IN_USE', 'IN_REPAIR', 'RETIRED', 'DISPOSED')),
    CONSTRAINT ck_asset_cost CHECK (purchase_cost IS NULL OR purchase_cost >= 0),
    CONSTRAINT ck_asset_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_asset_license CHECK ((asset_type = 'SOFTWARE_LICENSE') = (license_seats IS NOT NULL)),
    CONSTRAINT ck_asset_seats CHECK (license_seats IS NULL OR license_seats BETWEEN 1 AND 100000),
    CONSTRAINT ck_asset_holder CHECK (assigned_to_id IS NULL OR asset_type <> 'SOFTWARE_LICENSE')
);
CREATE UNIQUE INDEX ux_asset_serial ON assets (lower(serial_number)) WHERE serial_number IS NOT NULL;
CREATE INDEX ix_assets_status ON assets (status, asset_type);
CREATE INDEX ix_assets_assignee ON assets (assigned_to_id) WHERE assigned_to_id IS NOT NULL;
CREATE INDEX ix_assets_warranty ON assets (warranty_expires) WHERE warranty_expires IS NOT NULL;
CREATE INDEX ix_assets_ci ON assets (ci_id) WHERE ci_id IS NOT NULL;

-- Assignment history (hardware: one open row; licences: one open row per seat holder).
CREATE TABLE asset_assignments (
    id             BIGSERIAL PRIMARY KEY,
    asset_id       BIGINT       NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    user_id        BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    assigned_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    assigned_at    TIMESTAMPTZ  NOT NULL,
    returned_at    TIMESTAMPTZ,
    returned_by_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    note           VARCHAR(500),
    CONSTRAINT ck_asset_assignment_window CHECK (returned_at IS NULL OR returned_at >= assigned_at)
);
CREATE UNIQUE INDEX ux_asset_assignment_open ON asset_assignments (asset_id, user_id) WHERE returned_at IS NULL;
CREATE INDEX ix_asset_assignments_asset ON asset_assignments (asset_id, assigned_at DESC);
CREATE INDEX ix_asset_assignments_user ON asset_assignments (user_id) WHERE returned_at IS NULL;

CREATE TABLE asset_maintenance (
    id               BIGSERIAL PRIMARY KEY,
    asset_id         BIGINT        NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    maintenance_type VARCHAR(12)   NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'COMPLETED',
    description      VARCHAR(2000) NOT NULL,
    vendor           VARCHAR(120),
    cost             NUMERIC(12, 2),
    performed_on     DATE          NOT NULL,
    next_due         DATE,
    created_by_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_by_name  VARCHAR(160)  NOT NULL,
    is_demo          BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_asset_maintenance_type CHECK (maintenance_type IN ('REPAIR', 'PREVENTIVE', 'UPGRADE', 'INSPECTION')),
    CONSTRAINT ck_asset_maintenance_status CHECK (status IN ('SCHEDULED', 'COMPLETED')),
    CONSTRAINT ck_asset_maintenance_cost CHECK (cost IS NULL OR cost >= 0)
);
CREATE INDEX ix_asset_maintenance_asset ON asset_maintenance (asset_id, performed_on DESC);

-- Lifecycle and change history (created, status changes, assignments, returns, updates, maintenance, imports).
CREATE TABLE asset_events (
    id         BIGSERIAL PRIMARY KEY,
    asset_id   BIGINT       NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    event_type VARCHAR(30)  NOT NULL,
    from_value VARCHAR(200),
    to_value   VARCHAR(200),
    note       VARCHAR(1000),
    actor_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_asset_events_asset ON asset_events (asset_id, created_at, id);

-- ============================================================================ 3. alerts
CREATE SEQUENCE alert_number_seq START WITH 20001;

-- Webhook sources. PROMETHEUS / GRAFANA: Authorization: Bearer <secret>; GENERIC: HMAC-signed JSON; CLOUDWATCH: AWS SNS
-- message signatures (no shared secret) restricted to one topic ARN. Secrets are stored AES-256-GCM encrypted (SecretCipher).
CREATE TABLE alert_sources (
    id                        BIGSERIAL PRIMARY KEY,
    name                      VARCHAR(120) NOT NULL UNIQUE,
    provider                  VARCHAR(12)  NOT NULL,
    secret_encrypted          VARCHAR(500),
    sns_topic_arn             VARCHAR(300),
    subscription_status       VARCHAR(14)  NOT NULL DEFAULT 'NONE',
    subscription_arn          VARCHAR(300),
    subscription_confirmed_at TIMESTAMPTZ,
    default_service_id        BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    default_ci_id             BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    default_severity          VARCHAR(10)  NOT NULL DEFAULT 'WARNING',
    active                    BOOLEAN      NOT NULL DEFAULT TRUE,
    last_received_at          TIMESTAMPTZ,
    is_demo                   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id             BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_alert_source_provider CHECK (provider IN ('PROMETHEUS', 'GRAFANA', 'CLOUDWATCH', 'GENERIC')),
    CONSTRAINT ck_alert_source_severity CHECK (default_severity IN ('CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO')),
    CONSTRAINT ck_alert_source_subscription CHECK (subscription_status IN ('NONE', 'PENDING', 'CONFIRMED', 'UNSUBSCRIBED')),
    CONSTRAINT ck_alert_source_secret CHECK (provider = 'CLOUDWATCH' OR secret_encrypted IS NOT NULL),
    CONSTRAINT ck_alert_source_topic CHECK (provider <> 'CLOUDWATCH' OR sns_topic_arn IS NOT NULL)
);

CREATE TABLE alert_webhook_deliveries (
    id          BIGSERIAL PRIMARY KEY,
    source_id   BIGINT       NOT NULL REFERENCES alert_sources (id) ON DELETE CASCADE,
    delivery_id VARCHAR(200) NOT NULL,
    event_type  VARCHAR(60),
    outcome     VARCHAR(10)  NOT NULL,
    message     VARCHAR(500),
    alert_count INT          NOT NULL DEFAULT 0,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_alert_delivery UNIQUE (source_id, delivery_id),
    CONSTRAINT ck_alert_delivery_outcome CHECK (outcome IN ('PROCESSED', 'IGNORED', 'FAILED'))
);
CREATE INDEX ix_alert_deliveries_source ON alert_webhook_deliveries (source_id, received_at DESC);

-- Suppression: matching alerts are recorded (and counted) but SUPPRESSED: no routing, notification, escalation or incident.
-- match_criteria = {labels: {name: value | "*"}, sources: [...], severities: [...], serviceIds: [...], ciIds: [...], titleContains}.
-- Time window: optional absolute starts_at/ends_at plus an optional daily window (organisation time zone, days 1 = Monday).
CREATE TABLE alert_suppression_rules (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    description     VARCHAR(500),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    match_criteria  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    starts_at       TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    daily_start     TIME,
    daily_end       TIME,
    days_of_week    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    match_count     BIGINT       NOT NULL DEFAULT 0,
    last_matched_at TIMESTAMPTZ,
    is_demo         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_suppression_match CHECK (jsonb_typeof(match_criteria) = 'object' AND jsonb_typeof(days_of_week) = 'array'),
    CONSTRAINT ck_suppression_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_suppression_daily CHECK ((daily_start IS NULL) = (daily_end IS NULL))
);

-- Routing: the first enabled matching rule (lowest sort_order) sets the team (and fills service / CI) and may convert the alert
-- into an incident when its severity reaches incident_min_severity.
CREATE TABLE alert_routing_rules (
    id                    BIGSERIAL PRIMARY KEY,
    name                  VARCHAR(120) NOT NULL,
    description           VARCHAR(500),
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order            INT          NOT NULL DEFAULT 100,
    match_criteria        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    team_id               BIGINT REFERENCES teams (id) ON DELETE SET NULL,
    service_id            BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    ci_id                 BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    create_incident       BOOLEAN      NOT NULL DEFAULT FALSE,
    incident_min_severity VARCHAR(10)  NOT NULL DEFAULT 'MAJOR',
    match_count           BIGINT       NOT NULL DEFAULT 0,
    last_matched_at       TIMESTAMPTZ,
    is_demo               BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_routing_match CHECK (jsonb_typeof(match_criteria) = 'object'),
    CONSTRAINT ck_routing_severity CHECK (incident_min_severity IN ('CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO'))
);
CREATE INDEX ix_routing_rules_order ON alert_routing_rules (sort_order, id) WHERE enabled;

-- One row per alert (deduplicated by scope + fingerprint while active; a fingerprint that fires again within the grouping
-- window after resolving reopens the same alert). occurrence_count counts every notification received.
CREATE TABLE alerts (
    id                  BIGSERIAL PRIMARY KEY,
    number              VARCHAR(20)  NOT NULL UNIQUE,
    source_kind         VARCHAR(12)  NOT NULL,
    source_id           BIGINT REFERENCES alert_sources (id) ON DELETE SET NULL,
    integration_id      BIGINT REFERENCES integrations (id) ON DELETE SET NULL,
    -- Deduplication scope, stable even if the source row is deleted: "S<sourceId>", "I<integrationId>" or "MANUAL".
    scope_key           VARCHAR(40)  NOT NULL,
    fingerprint         VARCHAR(128) NOT NULL,
    group_key           VARCHAR(300),
    title               VARCHAR(300) NOT NULL,
    description         TEXT,
    severity            VARCHAR(10)  NOT NULL,
    status              VARCHAR(12)  NOT NULL DEFAULT 'OPEN',
    firing              BOOLEAN      NOT NULL DEFAULT TRUE,
    labels              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    annotations         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    external_url        VARCHAR(500),
    service_id          BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    ci_id               BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    team_id             BIGINT REFERENCES teams (id) ON DELETE SET NULL,
    incident_id         BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    routing_rule_id     BIGINT REFERENCES alert_routing_rules (id) ON DELETE SET NULL,
    suppression_rule_id BIGINT REFERENCES alert_suppression_rules (id) ON DELETE SET NULL,
    occurrence_count    INT          NOT NULL DEFAULT 1,
    first_seen_at       TIMESTAMPTZ  NOT NULL,
    last_seen_at        TIMESTAMPTZ  NOT NULL,
    starts_at           TIMESTAMPTZ,
    ends_at             TIMESTAMPTZ,
    acknowledged_at     TIMESTAMPTZ,
    acknowledged_by_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    resolved_at         TIMESTAMPTZ,
    resolved_by_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    resolution_note     VARCHAR(1000),
    suppressed_at       TIMESTAMPTZ,
    suppressed_until    TIMESTAMPTZ,
    suppressed_by_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    escalation_level    INT          NOT NULL DEFAULT 0,
    last_escalated_at   TIMESTAMPTZ,
    next_escalation_at  TIMESTAMPTZ,
    is_demo             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_alert_source_kind CHECK (source_kind IN ('PROMETHEUS', 'GRAFANA', 'CLOUDWATCH', 'GENERIC', 'INTEGRATION', 'MANUAL')),
    CONSTRAINT ck_alert_severity CHECK (severity IN ('CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO')),
    CONSTRAINT ck_alert_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'SUPPRESSED')),
    CONSTRAINT ck_alert_json CHECK (jsonb_typeof(labels) = 'object' AND jsonb_typeof(annotations) = 'object'),
    CONSTRAINT ck_alert_count CHECK (occurrence_count >= 1 AND escalation_level >= 0),
    CONSTRAINT ck_alert_seen CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT ck_alert_resolved CHECK (status <> 'RESOLVED' OR resolved_at IS NOT NULL),
    CONSTRAINT ck_alert_acknowledged CHECK (status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL)
);
CREATE UNIQUE INDEX ux_alert_active_fingerprint ON alerts (scope_key, fingerprint) WHERE status IN ('OPEN', 'ACKNOWLEDGED', 'SUPPRESSED');
CREATE INDEX ix_alerts_fingerprint ON alerts (scope_key, fingerprint, resolved_at DESC);
CREATE INDEX ix_alerts_status ON alerts (status, last_seen_at DESC);
CREATE INDEX ix_alerts_team ON alerts (team_id) WHERE status IN ('OPEN', 'ACKNOWLEDGED');
CREATE INDEX ix_alerts_group ON alerts (group_key) WHERE group_key IS NOT NULL;
CREATE INDEX ix_alerts_incident ON alerts (incident_id) WHERE incident_id IS NOT NULL;
CREATE INDEX ix_alerts_ci ON alerts (ci_id) WHERE ci_id IS NOT NULL;
CREATE INDEX ix_alerts_escalation ON alerts (next_escalation_at) WHERE status = 'OPEN' AND next_escalation_at IS NOT NULL;

CREATE TABLE alert_events (
    id         BIGSERIAL PRIMARY KEY,
    alert_id   BIGINT       NOT NULL REFERENCES alerts (id) ON DELETE CASCADE,
    event_type VARCHAR(30)  NOT NULL,
    actor_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name VARCHAR(160) NOT NULL,
    note       VARCHAR(1000),
    details    JSONB,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_alert_events_alert ON alert_events (alert_id, created_at, id);

-- ============================================================================ 4. workflows
ALTER TABLE workflows DROP CONSTRAINT ck_workflow_trigger;
ALTER TABLE workflows ADD CONSTRAINT ck_workflow_trigger CHECK (trigger_event IN ('INCIDENT_CREATED', 'INCIDENT_UPDATED',
    'INCIDENT_STATUS_CHANGED', 'INCIDENT_ASSIGNED', 'PRIORITY_CHANGED', 'SLA_AT_RISK', 'SLA_BREACHED', 'SERVICE_REQUEST_CREATED', 'SCHEDULED',
    'CHANGE_REQUEST_CREATED', 'ALERT_RECEIVED'));
ALTER TABLE workflow_executions ADD COLUMN alert_id BIGINT REFERENCES alerts (id) ON DELETE SET NULL;

-- ============================================================================ 5. notification categories
ALTER TABLE notification_preferences DROP CONSTRAINT ck_notification_pref_type;
ALTER TABLE notification_preferences ADD CONSTRAINT ck_notification_pref_type CHECK (type IN ('INCIDENT_ASSIGNED', 'INCIDENT_COMMENT',
    'INCIDENT_RESOLVED', 'SLA_AT_RISK', 'SLA_BREACHED', 'ESCALATION', 'APPROVAL_REQUESTED', 'APPROVAL_DECIDED', 'WORKFLOW_FAILED',
    'REQUEST_UPDATED', 'ANNOUNCEMENT', 'ACCESS_REQUEST', 'PASSWORD_RESET_REQUEST', 'TEAM_QUEUE', 'REPORT_READY',
    'APPROVAL_ESCALATED', 'PROBLEM_UPDATED', 'CHANGE_UPDATED', 'DEPLOYMENT_STATUS',
    'ALERT_ROUTED', 'ALERT_ESCALATED', 'ASSET_ASSIGNED'));

-- ============================================================================ 6. settings
INSERT INTO system_settings (setting_key, setting_value, value_type, description) VALUES
    ('alert.grouping.window_minutes', '30', 'NUMBER',
     'An alert that fires again within this many minutes after it resolved is reopened instead of creating a new alert.'),
    ('alert.escalation.ack_minutes', '15', 'NUMBER',
     'Unacknowledged alerts are escalated to the team lead and department manager after this many minutes (then to managers).'),
    ('alert.escalation.min_severity', 'MAJOR', 'STRING', 'Lowest alert severity that is escalated when nobody acknowledges it.'),
    ('alert.notify.min_severity', 'MAJOR', 'STRING', 'Lowest alert severity whose routing notifies the members of the routed team.'),
    ('asset.warranty.warning_days', '30', 'NUMBER', 'Warranties and licences ending within this many days are listed as expiring.')
ON CONFLICT (setting_key) DO NOTHING;
