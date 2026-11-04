-- SA Release 4 / BE-5 (docs/RELEASE4_SPEC.md §1, §2, §4.1, §4.2): the SECURITY_ANALYST and FINOPS_ANALYST roles, simulation
-- mode (runs, labelled fault windows, `simulated` flags on alerts, incidents and deployments), SA Observability (telemetry
-- sources, OTLP/HTTP JSON ingestion into resources / metric series + points / minute roll-ups / logs / spans, attribute mapping
-- rules, anomaly detectors and anomalies, alert correlation groups) and the SA Digital Twin (relationship attributes for
-- redundancy, persisted failure/change impact simulations). Demo records are created by the application's demo seeder (flagged
-- is_demo), simulated records only by simulation scenarios (flagged simulated); never here.

-- ============================================================================ 1. roles
ALTER TABLE users DROP CONSTRAINT ck_user_role;
ALTER TABLE users ADD CONSTRAINT ck_user_role CHECK (role IN ('SYSTEM_ADMIN', 'IT_ADMIN', 'IT_MANAGER', 'INCIDENT_MANAGER',
    'CHANGE_MANAGER', 'ASSET_MANAGER', 'SUPPORT_AGENT', 'EMPLOYEE', 'AUDITOR', 'SECURITY_ANALYST', 'FINOPS_ANALYST'));
ALTER TABLE account_tokens DROP CONSTRAINT ck_account_token_role;
ALTER TABLE account_tokens ADD CONSTRAINT ck_account_token_role CHECK (role IS NULL OR role IN ('SYSTEM_ADMIN', 'IT_ADMIN',
    'IT_MANAGER', 'INCIDENT_MANAGER', 'CHANGE_MANAGER', 'ASSET_MANAGER', 'SUPPORT_AGENT', 'EMPLOYEE', 'AUDITOR', 'SECURITY_ANALYST',
    'FINOPS_ANALYST'));
ALTER TABLE approvals DROP CONSTRAINT ck_approval_role;
ALTER TABLE approvals ADD CONSTRAINT ck_approval_role CHECK (approver_role IS NULL OR approver_role IN ('SYSTEM_ADMIN', 'IT_ADMIN',
    'IT_MANAGER', 'INCIDENT_MANAGER', 'CHANGE_MANAGER', 'ASSET_MANAGER', 'SUPPORT_AGENT', 'EMPLOYEE', 'AUDITOR', 'SECURITY_ANALYST',
    'FINOPS_ANALYST'));

-- ============================================================================ 2. simulated flags, alert source kind, relationship attributes
ALTER TABLE alerts ALTER COLUMN source_kind TYPE VARCHAR(16);
ALTER TABLE alerts DROP CONSTRAINT ck_alert_source_kind;
ALTER TABLE alerts ADD CONSTRAINT ck_alert_source_kind CHECK (source_kind IN ('PROMETHEUS', 'GRAFANA', 'CLOUDWATCH', 'GENERIC', 'INTEGRATION',
    'MANUAL', 'OBSERVABILITY'));
ALTER TABLE alerts ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_alerts_simulated ON alerts (id) WHERE simulated;

ALTER TABLE incidents ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_incidents_simulated ON incidents (id) WHERE simulated;

ALTER TABLE deployments ADD COLUMN simulated BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_deployments_simulated ON deployments (id) WHERE simulated;

-- Free-form relationship facts; the digital twin reads {"redundant": true} (the dependency has a redundant counterpart).
ALTER TABLE ci_relationships ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE ci_relationships ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE ci_relationships ADD CONSTRAINT ck_ci_rel_attributes CHECK (jsonb_typeof(attributes) = 'object');

-- ============================================================================ 3. simulation mode
CREATE TABLE simulation_runs (
    id              BIGSERIAL PRIMARY KEY,
    -- Open code list (scenarios are registered by the application; later phases add VULN_WAVE, COST_SPIKE, ENDPOINT_DEGRADATION).
    scenario        VARCHAR(30)   NOT NULL,
    status          VARCHAR(10)   NOT NULL DEFAULT 'RUNNING',
    started_at      TIMESTAMPTZ   NOT NULL,
    stopped_at      TIMESTAMPTZ,
    last_tick_at    TIMESTAMPTZ,
    next_tick_at    TIMESTAMPTZ,
    tick_count      INT           NOT NULL DEFAULT 0,
    state           JSONB         NOT NULL DEFAULT '{}'::jsonb,
    summary         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    error           VARCHAR(1000),
    started_by_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    started_by_name VARCHAR(160)  NOT NULL,
    stopped_by_name VARCHAR(160),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_simulation_scenario CHECK (scenario ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_simulation_status CHECK (status IN ('RUNNING', 'STOPPED', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_simulation_json CHECK (jsonb_typeof(state) = 'object' AND jsonb_typeof(summary) = 'object'),
    CONSTRAINT ck_simulation_ticks CHECK (tick_count >= 0)
);
CREATE UNIQUE INDEX ux_simulation_running ON simulation_runs (scenario) WHERE status = 'RUNNING';
CREATE INDEX ix_simulation_runs_started ON simulation_runs (started_at DESC);
CREATE INDEX ix_simulation_runs_due ON simulation_runs (next_tick_at) WHERE status = 'RUNNING';

-- Labelled fault windows injected by scenarios: ground truth for anomaly-detector evaluation (precision / recall).
CREATE TABLE simulation_fault_windows (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT       NOT NULL REFERENCES simulation_runs (id) ON DELETE CASCADE,
    scenario    VARCHAR(30)  NOT NULL,
    label       VARCHAR(200) NOT NULL,
    service_id  BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    ci_id       BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    metric_name VARCHAR(200),
    started_at  TIMESTAMPTZ  NOT NULL,
    ended_at    TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_fault_window CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE INDEX ix_fault_windows_service ON simulation_fault_windows (service_id, started_at);
CREATE INDEX ix_fault_windows_metric ON simulation_fault_windows (metric_name, started_at);

-- ============================================================================ 4. telemetry sources
CREATE TABLE telemetry_sources (
    id                    BIGSERIAL PRIMARY KEY,
    name                  VARCHAR(120)  NOT NULL UNIQUE,
    kind                  VARCHAR(16)   NOT NULL,
    status                VARCHAR(14)   NOT NULL DEFAULT 'NOT_CONNECTED',
    base_url              VARCHAR(500),
    auth_type             VARCHAR(10)   NOT NULL DEFAULT 'NONE',
    username              VARCHAR(200),
    -- Outbound credential (bearer token, basic password, API key), AES-256-GCM (SecretCipher); never returned.
    secret_encrypted      VARCHAR(4000),
    -- Inbound OTLP token: only its SHA-256 hash is stored; the token is shown once.
    token_hash            CHAR(64) UNIQUE,
    token_hint            VARCHAR(20),
    config                JSONB         NOT NULL DEFAULT '{}'::jsonb,
    pull_enabled          BOOLEAN       NOT NULL DEFAULT FALSE,
    pull_interval_seconds INT           NOT NULL DEFAULT 60,
    active                BOOLEAN       NOT NULL DEFAULT TRUE,
    last_verified_at      TIMESTAMPTZ,
    last_tested_at        TIMESTAMPTZ,
    last_error            VARCHAR(1000),
    last_received_at      TIMESTAMPTZ,
    last_pulled_at        TIMESTAMPTZ,
    received_count        BIGINT        NOT NULL DEFAULT 0,
    is_demo               BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by_id         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_telemetry_source_kind CHECK (kind IN ('OTLP', 'PROMETHEUS_API', 'GRAFANA', 'CLOUDWATCH', 'AZURE_MONITOR', 'GCP_MONITORING')),
    CONSTRAINT ck_telemetry_source_status CHECK (status IN ('NOT_CONNECTED', 'CONNECTED', 'ERROR')),
    CONSTRAINT ck_telemetry_source_auth CHECK (auth_type IN ('NONE', 'BEARER', 'BASIC', 'API_KEY')),
    CONSTRAINT ck_telemetry_source_config CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT ck_telemetry_source_token CHECK (kind <> 'OTLP' OR token_hash IS NOT NULL),
    CONSTRAINT ck_telemetry_source_connected CHECK (status <> 'CONNECTED' OR last_verified_at IS NOT NULL),
    CONSTRAINT ck_telemetry_source_interval CHECK (pull_interval_seconds BETWEEN 30 AND 3600)
);
CREATE INDEX ix_telemetry_sources_pull ON telemetry_sources (kind) WHERE pull_enabled AND active;

-- ============================================================================ 5. attribute → service / CI mapping rules
CREATE TABLE telemetry_mapping_rules (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    attribute_key   VARCHAR(120) NOT NULL,
    match_type      VARCHAR(6)   NOT NULL DEFAULT 'EXACT',
    match_value     VARCHAR(300) NOT NULL,
    service_id      BIGINT REFERENCES business_services (id) ON DELETE CASCADE,
    ci_id           BIGINT REFERENCES configuration_items (id) ON DELETE CASCADE,
    sort_order      INT          NOT NULL DEFAULT 100,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    match_count     BIGINT       NOT NULL DEFAULT 0,
    last_matched_at TIMESTAMPTZ,
    is_demo         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_mapping_match_type CHECK (match_type IN ('EXACT', 'REGEX')),
    CONSTRAINT ck_mapping_target CHECK (service_id IS NOT NULL OR ci_id IS NOT NULL)
);
CREATE INDEX ix_mapping_rules_order ON telemetry_mapping_rules (sort_order, id) WHERE enabled;

-- ============================================================================ 6. telemetry storage
-- A resource = one emitting entity (service.name, namespace, environment, host, k8s workload, cloud region); simulated resources are
-- separate rows so a purge never touches real telemetry.
CREATE TABLE telemetry_resources (
    id                BIGSERIAL PRIMARY KEY,
    resource_key      CHAR(64)     NOT NULL UNIQUE,
    service_name      VARCHAR(200),
    service_namespace VARCHAR(200),
    environment       VARCHAR(100),
    host_name         VARCHAR(255),
    attributes        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    service_id        BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    ci_id             BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    mapping_rule_id   BIGINT REFERENCES telemetry_mapping_rules (id) ON DELETE SET NULL,
    source_id         BIGINT REFERENCES telemetry_sources (id) ON DELETE SET NULL,
    simulated         BOOLEAN      NOT NULL DEFAULT FALSE,
    first_seen_at     TIMESTAMPTZ  NOT NULL,
    last_seen_at      TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_telemetry_resource_attributes CHECK (jsonb_typeof(attributes) = 'object')
);
CREATE INDEX ix_telemetry_resources_service ON telemetry_resources (service_id) WHERE service_id IS NOT NULL;
CREATE INDEX ix_telemetry_resources_ci ON telemetry_resources (ci_id) WHERE ci_id IS NOT NULL;
CREATE INDEX ix_telemetry_resources_unmapped ON telemetry_resources (last_seen_at DESC) WHERE service_id IS NULL AND ci_id IS NULL;

-- Tracked resource attribute changes (service.version, container image, ...): part of the twin change timeline.
CREATE TABLE telemetry_resource_changes (
    id            BIGSERIAL PRIMARY KEY,
    resource_id   BIGINT       NOT NULL REFERENCES telemetry_resources (id) ON DELETE CASCADE,
    attribute_key VARCHAR(120) NOT NULL,
    from_value    VARCHAR(500),
    to_value      VARCHAR(500),
    observed_at   TIMESTAMPTZ  NOT NULL,
    simulated     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_resource_changes_resource ON telemetry_resource_changes (resource_id, observed_at);

CREATE TABLE telemetry_metric_series (
    id            BIGSERIAL PRIMARY KEY,
    series_key    CHAR(64)     NOT NULL UNIQUE,
    resource_id   BIGINT       NOT NULL REFERENCES telemetry_resources (id) ON DELETE CASCADE,
    name          VARCHAR(200) NOT NULL,
    metric_type   VARCHAR(10)  NOT NULL,
    unit          VARCHAR(40),
    attributes    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    simulated     BOOLEAN      NOT NULL DEFAULT FALSE,
    first_seen_at TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_metric_series_type CHECK (metric_type IN ('GAUGE', 'SUM', 'HISTOGRAM', 'SUMMARY')),
    CONSTRAINT ck_metric_series_attributes CHECK (jsonb_typeof(attributes) = 'object')
);
CREATE INDEX ix_metric_series_name ON telemetry_metric_series (name);
CREATE INDEX ix_metric_series_resource ON telemetry_metric_series (resource_id);

-- Raw points (retention observability.retention.raw_days). Histograms/summaries keep count and sum; value = mean.
CREATE TABLE telemetry_metric_points (
    id          BIGSERIAL PRIMARY KEY,
    series_id   BIGINT           NOT NULL REFERENCES telemetry_metric_series (id) ON DELETE CASCADE,
    ts          TIMESTAMPTZ      NOT NULL,
    value       DOUBLE PRECISION NOT NULL,
    count       BIGINT,
    sum         DOUBLE PRECISION,
    min_value   DOUBLE PRECISION,
    max_value   DOUBLE PRECISION,
    simulated   BOOLEAN          NOT NULL DEFAULT FALSE,
    received_at TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX ix_metric_points_series ON telemetry_metric_points (series_id, ts);
CREATE INDEX ix_metric_points_ts ON telemetry_metric_points (ts);

-- Minute roll-ups (retention observability.retention.rollup_days): queries and anomaly detection read these.
CREATE TABLE telemetry_metric_rollups (
    series_id    BIGINT           NOT NULL REFERENCES telemetry_metric_series (id) ON DELETE CASCADE,
    bucket_start TIMESTAMPTZ      NOT NULL,
    sample_count INT              NOT NULL,
    sum_value    DOUBLE PRECISION NOT NULL,
    min_value    DOUBLE PRECISION NOT NULL,
    max_value    DOUBLE PRECISION NOT NULL,
    avg_value    DOUBLE PRECISION NOT NULL,
    p95_value    DOUBLE PRECISION NOT NULL,
    simulated    BOOLEAN          NOT NULL DEFAULT FALSE,
    updated_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (series_id, bucket_start),
    CONSTRAINT ck_rollup_count CHECK (sample_count > 0)
);
CREATE INDEX ix_metric_rollups_bucket ON telemetry_metric_rollups (bucket_start);

-- Minutes that received raw points since their last roll-up (late and back-filled data included).
CREATE TABLE telemetry_rollup_queue (
    bucket_start TIMESTAMPTZ PRIMARY KEY,
    queued_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE telemetry_logs (
    id                 BIGSERIAL PRIMARY KEY,
    resource_id        BIGINT       NOT NULL REFERENCES telemetry_resources (id) ON DELETE CASCADE,
    ts                 TIMESTAMPTZ  NOT NULL,
    observed_at        TIMESTAMPTZ,
    severity           VARCHAR(5)   NOT NULL,
    severity_number    SMALLINT     NOT NULL DEFAULT 0,
    severity_text      VARCHAR(40),
    body               TEXT         NOT NULL,
    body_truncated     BOOLEAN      NOT NULL DEFAULT FALSE,
    attributes         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    dropped_attributes INT          NOT NULL DEFAULT 0,
    trace_id           CHAR(32),
    span_id            CHAR(16),
    simulated          BOOLEAN      NOT NULL DEFAULT FALSE,
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_log_severity CHECK (severity IN ('TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL')),
    CONSTRAINT ck_log_body CHECK (length(body) <= 8192),
    CONSTRAINT ck_log_attributes CHECK (jsonb_typeof(attributes) = 'object')
);
CREATE INDEX ix_logs_ts ON telemetry_logs (ts DESC);
CREATE INDEX ix_logs_resource ON telemetry_logs (resource_id, ts DESC);
CREATE INDEX ix_logs_severity ON telemetry_logs (severity, ts DESC);
CREATE INDEX ix_logs_trace ON telemetry_logs (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX ix_logs_body_trgm ON telemetry_logs USING gin (body gin_trgm_ops);

CREATE TABLE telemetry_spans (
    id                 BIGSERIAL PRIMARY KEY,
    resource_id        BIGINT           NOT NULL REFERENCES telemetry_resources (id) ON DELETE CASCADE,
    trace_id           CHAR(32)         NOT NULL,
    span_id            CHAR(16)         NOT NULL,
    parent_span_id     CHAR(16),
    name               VARCHAR(300)     NOT NULL,
    kind               VARCHAR(11)      NOT NULL DEFAULT 'UNSPECIFIED',
    start_time         TIMESTAMPTZ      NOT NULL,
    end_time           TIMESTAMPTZ      NOT NULL,
    duration_ms        DOUBLE PRECISION NOT NULL,
    status_code        VARCHAR(5)       NOT NULL DEFAULT 'UNSET',
    status_message     VARCHAR(500),
    attributes         JSONB            NOT NULL DEFAULT '{}'::jsonb,
    dropped_attributes INT              NOT NULL DEFAULT 0,
    simulated          BOOLEAN          NOT NULL DEFAULT FALSE,
    received_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- OTLP exporters retry; a span is stored once.
    CONSTRAINT ux_span UNIQUE (trace_id, span_id),
    CONSTRAINT ck_span_kind CHECK (kind IN ('UNSPECIFIED', 'INTERNAL', 'SERVER', 'CLIENT', 'PRODUCER', 'CONSUMER')),
    CONSTRAINT ck_span_status CHECK (status_code IN ('UNSET', 'OK', 'ERROR')),
    CONSTRAINT ck_span_duration CHECK (duration_ms >= 0 AND end_time >= start_time),
    CONSTRAINT ck_span_attributes CHECK (jsonb_typeof(attributes) = 'object')
);
CREATE INDEX ix_spans_start ON telemetry_spans (start_time DESC);
CREATE INDEX ix_spans_resource ON telemetry_spans (resource_id, start_time DESC);

-- ============================================================================ 7. anomaly detection
CREATE TABLE anomaly_detectors (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(120)     NOT NULL UNIQUE,
    description          VARCHAR(1000),
    metric_name          VARCHAR(200)     NOT NULL,
    service_id           BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    ci_id                BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    attribute_filters    JSONB            NOT NULL DEFAULT '{}'::jsonb,
    value_aggregation    VARCHAR(4)       NOT NULL DEFAULT 'avg',
    algorithm            VARCHAR(10)      NOT NULL DEFAULT 'EWMA',
    window_points        INT              NOT NULL DEFAULT 60,
    threshold            DOUBLE PRECISION NOT NULL DEFAULT 3.0,
    consecutive_points   INT              NOT NULL DEFAULT 3,
    min_samples          INT              NOT NULL DEFAULT 30,
    direction            VARCHAR(4)       NOT NULL DEFAULT 'BOTH',
    seasonality          VARCHAR(12)      NOT NULL DEFAULT 'AUTO',
    include_simulated    BOOLEAN          NOT NULL DEFAULT FALSE,
    raise_alert          BOOLEAN          NOT NULL DEFAULT TRUE,
    alert_severity       VARCHAR(10)      NOT NULL DEFAULT 'MAJOR',
    create_incident      BOOLEAN          NOT NULL DEFAULT FALSE,
    enabled              BOOLEAN          NOT NULL DEFAULT TRUE,
    last_run_at          TIMESTAMPTZ,
    last_evaluation      JSONB,
    last_evaluated_at    TIMESTAMPTZ,
    is_demo              BOOLEAN          NOT NULL DEFAULT FALSE,
    created_by_id        BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    version              BIGINT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_detector_aggregation CHECK (value_aggregation IN ('avg', 'max', 'min', 'sum', 'p95')),
    CONSTRAINT ck_detector_algorithm CHECK (algorithm IN ('EWMA', 'MEDIAN_MAD')),
    CONSTRAINT ck_detector_window CHECK (window_points BETWEEN 5 AND 10080),
    CONSTRAINT ck_detector_threshold CHECK (threshold >= 1 AND threshold <= 20),
    CONSTRAINT ck_detector_consecutive CHECK (consecutive_points BETWEEN 1 AND 60),
    CONSTRAINT ck_detector_min_samples CHECK (min_samples BETWEEN 5 AND 100000),
    CONSTRAINT ck_detector_direction CHECK (direction IN ('UP', 'DOWN', 'BOTH')),
    CONSTRAINT ck_detector_seasonality CHECK (seasonality IN ('NONE', 'HOUR_OF_WEEK', 'AUTO')),
    CONSTRAINT ck_detector_severity CHECK (alert_severity IN ('CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO')),
    CONSTRAINT ck_detector_filters CHECK (jsonb_typeof(attribute_filters) = 'object')
);

CREATE TABLE telemetry_anomalies (
    id             BIGSERIAL PRIMARY KEY,
    detector_id    BIGINT           NOT NULL REFERENCES anomaly_detectors (id) ON DELETE CASCADE,
    series_id      BIGINT           NOT NULL REFERENCES telemetry_metric_series (id) ON DELETE CASCADE,
    service_id     BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    ci_id          BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    metric_name    VARCHAR(200)     NOT NULL,
    status         VARCHAR(8)       NOT NULL DEFAULT 'OPEN',
    direction      VARCHAR(4)       NOT NULL,
    started_at     TIMESTAMPTZ      NOT NULL,
    ended_at       TIMESTAMPTZ,
    last_point_at  TIMESTAMPTZ      NOT NULL,
    point_count    INT              NOT NULL,
    peak_score     DOUBLE PRECISION NOT NULL,
    observed_value DOUBLE PRECISION NOT NULL,
    expected_value DOUBLE PRECISION NOT NULL,
    expected_lower DOUBLE PRECISION NOT NULL,
    expected_upper DOUBLE PRECISION NOT NULL,
    model_params   JSONB            NOT NULL DEFAULT '{}'::jsonb,
    alert_id       BIGINT REFERENCES alerts (id) ON DELETE SET NULL,
    simulated      BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_anomaly_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_anomaly_direction CHECK (direction IN ('UP', 'DOWN')),
    CONSTRAINT ck_anomaly_window CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_anomaly_resolved CHECK (status <> 'RESOLVED' OR ended_at IS NOT NULL)
);
CREATE INDEX ix_anomalies_detector ON telemetry_anomalies (detector_id, series_id, started_at);
CREATE INDEX ix_anomalies_status ON telemetry_anomalies (status, started_at DESC);
CREATE INDEX ix_anomalies_service ON telemetry_anomalies (service_id, started_at DESC);
CREATE INDEX ix_anomalies_alert ON telemetry_anomalies (alert_id) WHERE alert_id IS NOT NULL;

-- ============================================================================ 8. alert / anomaly correlation
CREATE TABLE correlation_groups (
    id                BIGSERIAL PRIMARY KEY,
    title             VARCHAR(300)     NOT NULL,
    status            VARCHAR(10)      NOT NULL DEFAULT 'OPEN',
    score             DOUBLE PRECISION NOT NULL DEFAULT 0,
    reasons           JSONB            NOT NULL DEFAULT '[]'::jsonb,
    root_ci_id        BIGINT REFERENCES configuration_items (id) ON DELETE SET NULL,
    root_service_id   BIGINT REFERENCES business_services (id) ON DELETE SET NULL,
    root_reason       VARCHAR(1000),
    incident_id       BIGINT REFERENCES incidents (id) ON DELETE SET NULL,
    first_seen_at     TIMESTAMPTZ      NOT NULL,
    last_activity_at  TIMESTAMPTZ      NOT NULL,
    member_count      INT              NOT NULL DEFAULT 0,
    simulated         BOOLEAN          NOT NULL DEFAULT FALSE,
    dismissed_reason  VARCHAR(1000),
    dismissed_by_name VARCHAR(160),
    dismissed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_correlation_status CHECK (status IN ('OPEN', 'CLOSED', 'DISMISSED')),
    CONSTRAINT ck_correlation_reasons CHECK (jsonb_typeof(reasons) = 'array'),
    CONSTRAINT ck_correlation_dismissed CHECK (status <> 'DISMISSED' OR dismissed_at IS NOT NULL)
);
CREATE INDEX ix_correlation_groups_status ON correlation_groups (status, last_activity_at DESC);
CREATE INDEX ix_correlation_groups_incident ON correlation_groups (incident_id) WHERE incident_id IS NOT NULL;

CREATE TABLE correlation_group_members (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT           NOT NULL REFERENCES correlation_groups (id) ON DELETE CASCADE,
    member_type VARCHAR(8)       NOT NULL,
    alert_id    BIGINT UNIQUE REFERENCES alerts (id) ON DELETE CASCADE,
    anomaly_id  BIGINT UNIQUE REFERENCES telemetry_anomalies (id) ON DELETE CASCADE,
    score       DOUBLE PRECISION NOT NULL DEFAULT 0,
    reasons     JSONB            NOT NULL DEFAULT '[]'::jsonb,
    added_by    VARCHAR(160)     NOT NULL DEFAULT 'SA Correlation',
    joined_at   TIMESTAMPTZ      NOT NULL,
    simulated   BOOLEAN          NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_correlation_member_type CHECK (member_type IN ('ALERT', 'ANOMALY')),
    CONSTRAINT ck_correlation_member_ref CHECK ((member_type = 'ALERT' AND alert_id IS NOT NULL AND anomaly_id IS NULL)
        OR (member_type = 'ANOMALY' AND anomaly_id IS NOT NULL AND alert_id IS NULL)),
    CONSTRAINT ck_correlation_member_reasons CHECK (jsonb_typeof(reasons) = 'array')
);
CREATE INDEX ix_correlation_members_group ON correlation_group_members (group_id);

-- ============================================================================ 9. digital twin simulations
CREATE TABLE twin_simulations (
    id                     BIGSERIAL PRIMARY KEY,
    sim_type               VARCHAR(8)   NOT NULL,
    mode                   VARCHAR(8)   NOT NULL,
    name                   VARCHAR(200),
    targets                JSONB        NOT NULL DEFAULT '[]'::jsonb,
    change_id              BIGINT REFERENCES changes (id) ON DELETE SET NULL,
    result                 JSONB        NOT NULL,
    impacted_ci_count      INT          NOT NULL DEFAULT 0,
    impacted_service_count INT          NOT NULL DEFAULT 0,
    created_by_id          BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_by_name        VARCHAR(160) NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_twin_sim_type CHECK (sim_type IN ('FAILURE', 'CHANGE')),
    CONSTRAINT ck_twin_sim_mode CHECK (mode IN ('HARD', 'DEGRADED')),
    CONSTRAINT ck_twin_sim_targets CHECK (jsonb_typeof(targets) = 'array'),
    CONSTRAINT ck_twin_sim_result CHECK (jsonb_typeof(result) = 'object')
);
CREATE INDEX ix_twin_simulations_created ON twin_simulations (created_at DESC);

-- ============================================================================ 10. settings
INSERT INTO system_settings (setting_key, setting_value, value_type, description) VALUES
    ('simulation.enabled', 'false', 'BOOLEAN', 'Simulation mode: scenarios may generate labelled sample data (simulated = true). Off by default.'),
    ('observability.retention.raw_days', '7', 'NUMBER', 'Days raw metric points are kept (minute roll-ups are kept longer).'),
    ('observability.retention.rollup_days', '90', 'NUMBER', 'Days minute roll-ups of metrics are kept.'),
    ('observability.retention.logs_days', '14', 'NUMBER', 'Days log records are kept.'),
    ('observability.retention.traces_days', '7', 'NUMBER', 'Days spans (traces) are kept.'),
    ('observability.correlation.window_minutes', '30', 'NUMBER', 'Alerts and anomalies further apart than this are never correlated.'),
    ('observability.correlation.max_distance', '3', 'NUMBER', 'Maximum CMDB topology distance (hops) that still counts as related.'),
    ('observability.correlation.min_score', '0.5', 'NUMBER', 'Minimum correlation score (0-1) for an alert to join a group.')
ON CONFLICT (setting_key) DO NOTHING;
