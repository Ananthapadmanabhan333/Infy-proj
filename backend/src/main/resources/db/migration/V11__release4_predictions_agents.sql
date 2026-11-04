-- SA Release 4 / BE-6 (docs/RELEASE4_SPEC.md §4.3, §4.4): SA Predictive Intelligence (model registry, training/evaluation jobs,
-- served predictions with features snapshots and recorded outcomes) and SA Agentic AI (agent policies, agent version registry,
-- runs / steps / tool calls, proposed actions routed through the approval engine, evaluation suites and human feedback).
-- Demo evaluation cases are created by the application's demo seeder (flagged is_demo); nothing is seeded here except the default
-- agent policies (configuration, not data) and settings.

-- ============================================================================ 1. prediction model registry
CREATE TABLE prediction_models (
    id                BIGSERIAL PRIMARY KEY,
    model_type        VARCHAR(20)  NOT NULL,
    -- Models of one type compete for the single ACTIVE slot of their scope; ANOMALY registrations are scoped per detector
    -- ('detector:<id>'), every other type has the empty scope.
    scope_key         VARCHAR(60)  NOT NULL DEFAULT '',
    version           INT          NOT NULL,
    algorithm         VARCHAR(40)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    status_message    VARCHAR(1000),
    features          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    params            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    metrics           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    training_from     TIMESTAMPTZ  NOT NULL,
    training_to       TIMESTAMPTZ  NOT NULL,
    cutoff_at         TIMESTAMPTZ,
    sample_count      INT          NOT NULL DEFAULT 0,
    positive_count    INT,
    train_count       INT          NOT NULL DEFAULT 0,
    test_count        INT          NOT NULL DEFAULT 0,
    min_samples       INT          NOT NULL DEFAULT 0,
    include_simulated BOOLEAN      NOT NULL DEFAULT FALSE,
    simulated         BOOLEAN      NOT NULL DEFAULT FALSE,
    detector_id       BIGINT REFERENCES anomaly_detectors (id) ON DELETE SET NULL,
    trained_at        TIMESTAMPTZ  NOT NULL,
    trained_by_id     BIGINT REFERENCES users (id) ON DELETE SET NULL,
    trained_by_name   VARCHAR(160) NOT NULL,
    activated_at      TIMESTAMPTZ,
    activated_by_name VARCHAR(160),
    retired_at        TIMESTAMPTZ,
    retired_by_name   VARCHAR(160),
    last_evaluated_at TIMESTAMPTZ,
    -- Live performance vs. the offline evaluation (outcome reconciliation): OK | DRIFT | INSUFFICIENT_DATA.
    drift_status      VARCHAR(20),
    drift_checked_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_prediction_model_drift CHECK (drift_status IS NULL OR drift_status IN ('OK', 'DRIFT', 'INSUFFICIENT_DATA')),
    CONSTRAINT ck_prediction_model_type CHECK (model_type IN ('SLA_BREACH', 'INCIDENT_RECURRENCE', 'CAPACITY_RISK', 'SERVICE_HEALTH', 'ANOMALY')),
    CONSTRAINT ck_prediction_model_status CHECK (status IN ('TRAINED', 'ACTIVE', 'RETIRED', 'INSUFFICIENT_DATA', 'FAILED')),
    CONSTRAINT ck_prediction_model_window CHECK (training_to > training_from),
    CONSTRAINT ck_prediction_model_features CHECK (jsonb_typeof(features) = 'array'),
    CONSTRAINT ck_prediction_model_params CHECK (jsonb_typeof(params) = 'object'),
    CONSTRAINT ck_prediction_model_metrics CHECK (jsonb_typeof(metrics) = 'object'),
    CONSTRAINT ck_prediction_model_scope CHECK ((model_type = 'ANOMALY') = (scope_key <> '')),
    CONSTRAINT ux_prediction_model_version UNIQUE (model_type, scope_key, version)
);
-- One ACTIVE version per type (per detector for ANOMALY).
CREATE UNIQUE INDEX ux_prediction_model_active ON prediction_models (model_type, scope_key) WHERE status = 'ACTIVE';
CREATE INDEX ix_prediction_models_type ON prediction_models (model_type, trained_at DESC);

-- Explicit and scheduled training / evaluation jobs (append-only history).
CREATE TABLE prediction_jobs (
    id                BIGSERIAL PRIMARY KEY,
    job_type          VARCHAR(10)  NOT NULL,
    trigger_type      VARCHAR(10)  NOT NULL,
    model_type        VARCHAR(20)  NOT NULL,
    model_id          BIGINT REFERENCES prediction_models (id) ON DELETE SET NULL,
    status            VARCHAR(20)  NOT NULL,
    message           VARCHAR(1000),
    request           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    result            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    started_at        TIMESTAMPTZ  NOT NULL,
    finished_at       TIMESTAMPTZ  NOT NULL,
    duration_ms       BIGINT       NOT NULL DEFAULT 0,
    requested_by_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    requested_by_name VARCHAR(160) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_prediction_job_type CHECK (job_type IN ('TRAIN', 'EVALUATE')),
    CONSTRAINT ck_prediction_job_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULE')),
    CONSTRAINT ck_prediction_job_model_type CHECK (model_type IN ('SLA_BREACH', 'INCIDENT_RECURRENCE', 'CAPACITY_RISK', 'SERVICE_HEALTH', 'ANOMALY')),
    CONSTRAINT ck_prediction_job_status CHECK (status IN ('SUCCEEDED', 'INSUFFICIENT_DATA', 'FAILED'))
);
CREATE INDEX ix_prediction_jobs_created ON prediction_jobs (created_at DESC);
CREATE INDEX ix_prediction_jobs_model ON prediction_jobs (model_id);

-- Every served prediction (model version, subject, score, interval, features snapshot) and its outcome once known.
CREATE TABLE predictions (
    id                BIGSERIAL PRIMARY KEY,
    model_id          BIGINT           NOT NULL REFERENCES prediction_models (id) ON DELETE CASCADE,
    model_type        VARCHAR(20)      NOT NULL,
    model_version     INT              NOT NULL,
    subject_type      VARCHAR(10)      NOT NULL,
    subject_id        BIGINT           NOT NULL,
    subject_label     VARCHAR(250),
    target            VARCHAR(60)      NOT NULL DEFAULT '',
    score             DOUBLE PRECISION NOT NULL,
    lower_bound       DOUBLE PRECISION,
    upper_bound       DOUBLE PRECISION,
    threshold         DOUBLE PRECISION,
    risk_level        VARCHAR(10),
    target_at         TIMESTAMPTZ,
    features          JSONB            NOT NULL DEFAULT '{}'::jsonb,
    explanation       JSONB            NOT NULL DEFAULT '{}'::jsonb,
    outcome_status    VARCHAR(10)      NOT NULL DEFAULT 'PENDING',
    outcome_label     BOOLEAN,
    outcome_value     DOUBLE PRECISION,
    outcome_at        TIMESTAMPTZ,
    outcome_note      VARCHAR(500),
    simulated         BOOLEAN          NOT NULL DEFAULT FALSE,
    requested_by_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_prediction_subject_type CHECK (subject_type IN ('INCIDENT', 'SERVICE', 'SERIES', 'DETECTOR')),
    CONSTRAINT ck_prediction_outcome_status CHECK (outcome_status IN ('PENDING', 'KNOWN', 'EXPIRED')),
    CONSTRAINT ck_prediction_risk CHECK (risk_level IS NULL OR risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_prediction_features CHECK (jsonb_typeof(features) = 'object'),
    CONSTRAINT ck_prediction_explanation CHECK (jsonb_typeof(explanation) = 'object')
);
CREATE INDEX ix_predictions_model ON predictions (model_id, created_at DESC);
CREATE INDEX ix_predictions_subject ON predictions (subject_type, subject_id, created_at DESC);
CREATE INDEX ix_predictions_pending ON predictions (model_type, created_at) WHERE outcome_status = 'PENDING';

-- ============================================================================ 2. agent governance
CREATE TABLE agent_policies (
    agent_code            VARCHAR(30)  PRIMARY KEY,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Subset of the agent's declared (read-only) tools it may call; an empty list means "no tools".
    allowed_tools         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    max_steps             INT          NOT NULL DEFAULT 6,
    timeout_seconds       INT          NOT NULL DEFAULT 30,
    allowed_roles         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    approval_action_kinds JSONB        NOT NULL DEFAULT '[]'::jsonb,
    data_scopes           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_by_name       VARCHAR(160),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_agent_policy_code CHECK (agent_code IN ('INCIDENT', 'ROOT_CAUSE', 'CHANGE_RISK', 'KNOWLEDGE', 'INFRA_INVESTIGATION', 'RESOLUTION')),
    CONSTRAINT ck_agent_policy_steps CHECK (max_steps BETWEEN 1 AND 20),
    CONSTRAINT ck_agent_policy_timeout CHECK (timeout_seconds BETWEEN 1 AND 300),
    CONSTRAINT ck_agent_policy_tools CHECK (jsonb_typeof(allowed_tools) = 'array'),
    CONSTRAINT ck_agent_policy_roles CHECK (jsonb_typeof(allowed_roles) = 'array'),
    CONSTRAINT ck_agent_policy_kinds CHECK (jsonb_typeof(approval_action_kinds) = 'array'),
    CONSTRAINT ck_agent_policy_scopes CHECK (jsonb_typeof(data_scopes) = 'object')
);

-- Prompt / version registry: one row per agent version the application has run (recorded at startup).
CREATE TABLE agent_versions (
    id            BIGSERIAL PRIMARY KEY,
    agent_code    VARCHAR(30)  NOT NULL,
    version       VARCHAR(20)  NOT NULL,
    prompt_sha256 CHAR(64)     NOT NULL,
    prompt        TEXT         NOT NULL,
    output_schema JSONB        NOT NULL DEFAULT '{}'::jsonb,
    tools         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    first_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_agent_version UNIQUE (agent_code, version),
    CONSTRAINT ck_agent_version_code CHECK (agent_code IN ('INCIDENT', 'ROOT_CAUSE', 'CHANGE_RISK', 'KNOWLEDGE', 'INFRA_INVESTIGATION', 'RESOLUTION'))
);

-- ============================================================================ 3. agent runs
CREATE TABLE agent_eval_runs (
    id              BIGSERIAL PRIMARY KEY,
    agent_code      VARCHAR(30),
    status          VARCHAR(10)      NOT NULL DEFAULT 'QUEUED',
    agent_versions  JSONB            NOT NULL DEFAULT '{}'::jsonb,
    case_count      INT              NOT NULL DEFAULT 0,
    passed          INT              NOT NULL DEFAULT 0,
    failed          INT              NOT NULL DEFAULT 0,
    score           DOUBLE PRECISION,
    results         JSONB            NOT NULL DEFAULT '[]'::jsonb,
    error           VARCHAR(1000),
    started_by_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    started_by_name VARCHAR(160)     NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_eval_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_agent_eval_run_results CHECK (jsonb_typeof(results) = 'array')
);
CREATE INDEX ix_agent_eval_runs_created ON agent_eval_runs (created_at DESC);

CREATE TABLE agent_runs (
    id                 BIGSERIAL PRIMARY KEY,
    run_type           VARCHAR(30)      NOT NULL,
    status             VARCHAR(10)      NOT NULL DEFAULT 'QUEUED',
    subject_type       VARCHAR(10),
    subject_id         BIGINT,
    subject_number     VARCHAR(30),
    question           VARCHAR(1000),
    input              JSONB            NOT NULL DEFAULT '{}'::jsonb,
    output             JSONB,
    summary            TEXT,
    source             VARCHAR(10),
    confidence         DOUBLE PRECISION,
    agent_versions     JSONB            NOT NULL DEFAULT '{}'::jsonb,
    timeout_seconds    INT              NOT NULL,
    step_count         INT              NOT NULL DEFAULT 0,
    tool_call_count    INT              NOT NULL DEFAULT 0,
    tokens_in          INT              NOT NULL DEFAULT 0,
    tokens_out         INT              NOT NULL DEFAULT 0,
    error              VARCHAR(1000),
    cancel_requested   BOOLEAN          NOT NULL DEFAULT FALSE,
    cancelled_by_name  VARCHAR(160),
    requested_by_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    requested_by_name  VARCHAR(160)     NOT NULL,
    requested_by_role  VARCHAR(20)      NOT NULL,
    eval_run_id        BIGINT REFERENCES agent_eval_runs (id) ON DELETE SET NULL,
    simulated          BOOLEAN          NOT NULL DEFAULT FALSE,
    is_demo            BOOLEAN          NOT NULL DEFAULT FALSE,
    queued_at          TIMESTAMPTZ      NOT NULL,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    duration_ms        BIGINT,
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_run_type CHECK (run_type IN ('INVESTIGATE_INCIDENT', 'ASSESS_CHANGE', 'ANSWER_QUESTION')),
    CONSTRAINT ck_agent_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT')),
    CONSTRAINT ck_agent_run_subject CHECK (subject_type IS NULL OR subject_type IN ('INCIDENT', 'CHANGE')),
    CONSTRAINT ck_agent_run_source CHECK (source IS NULL OR source IN ('LLM', 'FALLBACK', 'MIXED')),
    CONSTRAINT ck_agent_run_input CHECK (jsonb_typeof(input) = 'object')
);
CREATE INDEX ix_agent_runs_created ON agent_runs (created_at DESC);
CREATE INDEX ix_agent_runs_requester ON agent_runs (requested_by_id, created_at DESC);
CREATE INDEX ix_agent_runs_subject ON agent_runs (subject_type, subject_id);
CREATE INDEX ix_agent_runs_active ON agent_runs (status) WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE agent_run_steps (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT           NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    seq             INT              NOT NULL,
    stage           INT              NOT NULL,
    agent_code      VARCHAR(30)      NOT NULL,
    agent_version   VARCHAR(20)      NOT NULL,
    status          VARCHAR(10)      NOT NULL DEFAULT 'PENDING',
    input           JSONB            NOT NULL DEFAULT '{}'::jsonb,
    output          JSONB,
    source          VARCHAR(10),
    fallback_reason VARCHAR(500),
    confidence      DOUBLE PRECISION,
    evidence        JSONB            NOT NULL DEFAULT '[]'::jsonb,
    llm_turns       INT              NOT NULL DEFAULT 0,
    tokens_in       INT              NOT NULL DEFAULT 0,
    tokens_out      INT              NOT NULL DEFAULT 0,
    error           VARCHAR(1000),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,
    CONSTRAINT ux_agent_run_step UNIQUE (run_id, seq),
    CONSTRAINT ck_agent_step_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED', 'TIMED_OUT')),
    CONSTRAINT ck_agent_step_source CHECK (source IS NULL OR source IN ('LLM', 'FALLBACK')),
    CONSTRAINT ck_agent_step_evidence CHECK (jsonb_typeof(evidence) = 'array')
);

CREATE TABLE agent_tool_calls (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT       NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    step_id     BIGINT       NOT NULL REFERENCES agent_run_steps (id) ON DELETE CASCADE,
    seq         INT          NOT NULL,
    tool        VARCHAR(40)  NOT NULL,
    args        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status      VARCHAR(12)  NOT NULL,
    result      JSONB,
    result_size INT          NOT NULL DEFAULT 0,
    truncated   BOOLEAN      NOT NULL DEFAULT FALSE,
    message     VARCHAR(500),
    requested_by VARCHAR(10) NOT NULL DEFAULT 'PLAN',
    duration_ms BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_tool_status CHECK (status IN ('OK', 'EMPTY', 'DENIED', 'NOT_FOUND', 'DISALLOWED', 'INVALID', 'ERROR', 'TIMEOUT')),
    CONSTRAINT ck_agent_tool_requested_by CHECK (requested_by IN ('PLAN', 'LLM')),
    CONSTRAINT ck_agent_tool_args CHECK (jsonb_typeof(args) = 'object')
);
CREATE INDEX ix_agent_tool_calls_step ON agent_tool_calls (step_id, seq);
CREATE INDEX ix_agent_tool_calls_run ON agent_tool_calls (run_id);

CREATE TABLE agent_run_actions (
    id               BIGSERIAL PRIMARY KEY,
    run_id           BIGINT       NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    step_id          BIGINT REFERENCES agent_run_steps (id) ON DELETE SET NULL,
    agent_code       VARCHAR(30)  NOT NULL,
    action_kind      VARCHAR(30)  NOT NULL,
    target_type      VARCHAR(10)  NOT NULL,
    target_id        BIGINT       NOT NULL,
    params           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    title            VARCHAR(250) NOT NULL,
    rationale        VARCHAR(1000) NOT NULL,
    requires_approval BOOLEAN     NOT NULL,
    link             VARCHAR(300),
    status           VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    approval_id      BIGINT REFERENCES approvals (id) ON DELETE SET NULL,
    decided_by_name  VARCHAR(160),
    decided_at       TIMESTAMPTZ,
    decision_comment VARCHAR(1000),
    result           VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_action_kind CHECK (action_kind IN ('ASSIGN_TEAM', 'CHANGE_PRIORITY', 'LINK_PROBLEM', 'PUBLISH_KB_DRAFT', 'RUN_PLAYBOOK',
        'DECLARE_MAJOR')),
    CONSTRAINT ck_agent_action_target CHECK (target_type IN ('INCIDENT', 'CHANGE')),
    CONSTRAINT ck_agent_action_status CHECK (status IN ('PROPOSED', 'PENDING_APPROVAL', 'APPLIED', 'REJECTED', 'FAILED', 'DISMISSED')),
    CONSTRAINT ck_agent_action_params CHECK (jsonb_typeof(params) = 'object')
);
CREATE INDEX ix_agent_actions_run ON agent_run_actions (run_id);
CREATE INDEX ix_agent_actions_approval ON agent_run_actions (approval_id) WHERE approval_id IS NOT NULL;

CREATE TABLE agent_feedback (
    id         BIGSERIAL PRIMARY KEY,
    run_id     BIGINT        NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    user_id    BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_name  VARCHAR(160)  NOT NULL,
    helpful    BOOLEAN       NOT NULL,
    comment    VARCHAR(1000),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_agent_feedback UNIQUE (run_id, user_id)
);

-- ============================================================================ 4. evaluation suites
CREATE TABLE agent_eval_cases (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(160)  NOT NULL UNIQUE,
    agent_code    VARCHAR(30)   NOT NULL,
    run_type      VARCHAR(30)   NOT NULL,
    subject_type  VARCHAR(10),
    subject_id    BIGINT,
    question      VARCHAR(1000),
    -- {category?, priority?, rootCauseComponent?, kbArticleIds?: [], keywords?: []}
    expected      JSONB         NOT NULL DEFAULT '{}'::jsonb,
    enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    is_demo       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by_name VARCHAR(160) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_eval_code CHECK (agent_code IN ('INCIDENT', 'ROOT_CAUSE', 'CHANGE_RISK', 'KNOWLEDGE', 'INFRA_INVESTIGATION', 'RESOLUTION')),
    CONSTRAINT ck_agent_eval_run_type CHECK (run_type IN ('INVESTIGATE_INCIDENT', 'ASSESS_CHANGE', 'ANSWER_QUESTION')),
    CONSTRAINT ck_agent_eval_subject CHECK (subject_type IS NULL OR subject_type IN ('INCIDENT', 'CHANGE')),
    CONSTRAINT ck_agent_eval_expected CHECK (jsonb_typeof(expected) = 'object')
);

-- ============================================================================ 5. default agent policies (every declared tool, staff + security analysts)
INSERT INTO agent_policies (agent_code, enabled, allowed_tools, max_steps, timeout_seconds, allowed_roles, approval_action_kinds) VALUES
    ('INCIDENT', TRUE, '["get_incident","similar_incidents","sla_prediction","alerts"]', 6, 30,
     '["SYSTEM_ADMIN","IT_ADMIN","IT_MANAGER","INCIDENT_MANAGER","CHANGE_MANAGER","ASSET_MANAGER","SUPPORT_AGENT","SECURITY_ANALYST"]',
     '["ASSIGN_TEAM","CHANGE_PRIORITY","DECLARE_MAJOR"]'),
    ('ROOT_CAUSE', TRUE, '["get_incident","similar_incidents","recent_changes","recent_deployments","correlation_groups","anomalies","problems"]', 8, 30,
     '["SYSTEM_ADMIN","IT_ADMIN","IT_MANAGER","INCIDENT_MANAGER","CHANGE_MANAGER","ASSET_MANAGER","SUPPORT_AGENT","SECURITY_ANALYST"]',
     '["LINK_PROBLEM"]'),
    ('CHANGE_RISK', TRUE, '["get_change","cmdb_impact","recent_deployments","incidents_after_changes"]', 6, 30,
     '["SYSTEM_ADMIN","IT_ADMIN","IT_MANAGER","INCIDENT_MANAGER","CHANGE_MANAGER","ASSET_MANAGER","SUPPORT_AGENT","SECURITY_ANALYST"]',
     '[]'),
    ('KNOWLEDGE', TRUE, '["kb_search","similar_incidents","get_incident"]', 6, 30,
     '["SYSTEM_ADMIN","IT_ADMIN","IT_MANAGER","INCIDENT_MANAGER","CHANGE_MANAGER","ASSET_MANAGER","SUPPORT_AGENT","SECURITY_ANALYST"]',
     '["PUBLISH_KB_DRAFT"]'),
    ('INFRA_INVESTIGATION', TRUE, '["cmdb_impact","service_red","telemetry_metrics","telemetry_logs","anomalies","twin_changes"]', 8, 30,
     '["SYSTEM_ADMIN","IT_ADMIN","IT_MANAGER","INCIDENT_MANAGER","CHANGE_MANAGER","ASSET_MANAGER","SUPPORT_AGENT","SECURITY_ANALYST"]',
     '[]'),
    ('RESOLUTION', TRUE, '["runbooks","kb_search","problems","similar_incidents"]', 6, 30,
     '["SYSTEM_ADMIN","IT_ADMIN","IT_MANAGER","INCIDENT_MANAGER","CHANGE_MANAGER","ASSET_MANAGER","SUPPORT_AGENT","SECURITY_ANALYST"]',
     '["RUN_PLAYBOOK","LINK_PROBLEM","PUBLISH_KB_DRAFT","ASSIGN_TEAM","CHANGE_PRIORITY","DECLARE_MAJOR"]');

-- ============================================================================ 6. notification categories
ALTER TABLE notification_preferences DROP CONSTRAINT ck_notification_pref_type;
ALTER TABLE notification_preferences ADD CONSTRAINT ck_notification_pref_type CHECK (type IN ('INCIDENT_ASSIGNED', 'INCIDENT_COMMENT',
    'INCIDENT_RESOLVED', 'SLA_AT_RISK', 'SLA_BREACHED', 'ESCALATION', 'APPROVAL_REQUESTED', 'APPROVAL_DECIDED', 'WORKFLOW_FAILED',
    'REQUEST_UPDATED', 'ANNOUNCEMENT', 'ACCESS_REQUEST', 'PASSWORD_RESET_REQUEST', 'TEAM_QUEUE', 'REPORT_READY',
    'APPROVAL_ESCALATED', 'PROBLEM_UPDATED', 'CHANGE_UPDATED', 'DEPLOYMENT_STATUS',
    'ALERT_ROUTED', 'ALERT_ESCALATED', 'ASSET_ASSIGNED',
    'ONCALL_PAGE', 'ONCALL_SHIFT', 'MAJOR_INCIDENT_UPDATE', 'PIR_UPDATED',
    'PREDICTION_DRIFT', 'AGENT_RUN'));

-- ============================================================================ 7. settings
INSERT INTO system_settings (setting_key, setting_value, value_type, description) VALUES
    ('predictions.min_samples', '200', 'NUMBER', 'Minimum labelled samples (incidents) before a classification model (SLA_BREACH, INCIDENT_RECURRENCE) is trained.'),
    ('predictions.min_positives', '30', 'NUMBER', 'Minimum positive and negative examples before a classification model is trained.'),
    ('predictions.min_forecast_points', '100', 'NUMBER', 'Minimum historical points (all series) before a forecasting model (CAPACITY_RISK, SERVICE_HEALTH) is trained.'),
    ('predictions.drift.auc_tolerance', '0.10', 'NUMBER', 'Drift warning when the live AUC falls this far below the offline AUC.'),
    ('predictions.drift.brier_tolerance', '0.05', 'NUMBER', 'Drift warning when the live Brier score rises this far above the offline Brier score.'),
    ('predictions.drift.mae_ratio', '1.5', 'NUMBER', 'Drift warning when the live MAE exceeds the offline MAE by this factor.'),
    ('predictions.drift.coverage_tolerance', '0.15', 'NUMBER', 'Drift warning when the live interval coverage falls this far below the offline coverage.'),
    ('predictions.drift.f1_tolerance', '0.15', 'NUMBER', 'ANOMALY registrations: drift warning when a later evaluation F1 falls this far below the offline F1.'),
    ('predictions.drift.min_outcomes', '30', 'NUMBER', 'Recorded outcomes needed before live performance is compared with the offline evaluation.')
ON CONFLICT (setting_key) DO NOTHING;
