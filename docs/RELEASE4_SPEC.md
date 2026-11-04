# SA — Release 4 specification (autonomous enterprise operations)

Source: "SA NEXT-GENERATION PLATFORM EXPANSION" brief (4 Nov 2026). Release 4 **extends** the verified Release 3 code
base (PROJECT_STATUS.md: backend 294, Vitest 648, Playwright 27, Flyway V1–V9). Nothing working is rebuilt, renamed or
removed; earlier specs (RELEASE2/3_SPEC.md, PUBLIC_ACCESS_SPEC.md) stay valid. Stack unchanged: React + TypeScript,
Java 21 Spring Boot, PostgreSQL, Flyway.

## 0. Ground rules (apply to every module)

1. **No fabricated live data.** Telemetry, costs, findings, endpoint health and predictions shown as live come only from
   ingested records or from models trained on stored history. When there is not enough data a feature says so
   ("Insufficient data — N of M samples") instead of inventing numbers.
2. **Simulation mode** (§1) is the only source of sample data for demonstrations. Every simulated record carries
   `simulated = true`, the API returns `simulated: true`, and the UI shows a **SIMULATION** badge on the record and a
   banner on any page whose figures include simulated data. Analytics exclude simulated data by default
   (`includeSimulated=false`); the toggle is explicit. Seeded Release 1–3 demo data keeps its `demo` flag.
3. **AI and automation safety.** Validated inputs (Bean Validation, enums, size limits) and schema-validated outputs;
   role-based permissions on every endpoint (`Roles` groups + URL gates + `@PreAuthorize`); every run/execution is
   logged with actor, inputs, outputs, duration and outcome; approval policies through the existing approval engine;
   timeouts, bounded retries and explicit failure states; **no shell, SQL or free-form command execution** — only
   allow-listed, parameterised action templates; human override (cancel, pause, reject) and rollback where applicable.
   LLM output is labelled `LLM` vs `FALLBACK` exactly as today (`GuardedLlm`, `AiService.runGrounded`).
4. **Integrations are "prepared" until verified.** Adapters for external systems (Prometheus HTTP API, Grafana,
   CloudWatch/Azure Monitor/GCP, security scanners, cloud billing, endpoint agents, execution systems) are configuration
   + parser/client + contract tests with constructed payloads. Status `NOT_CONNECTED → CONNECTED (last verified at)`
   only after a successful real call/delivery. Docs never call an integration operational without that.
5. Outbound calls go through `UrlSafety` (SSRF guard); secrets encrypted with `SecretCipher`, shown once, never
   returned or logged. Inbound endpoints authenticate per source (token or HMAC) and use the inbound rate-limit bucket.
6. Every new table: `id`, `created_at`, `updated_at` where mutable, `demo`/`simulated` flags where applicable,
   indexes for the list filters, FK to services/CIs/teams where relevant. Forward-only migrations; never edit V1–V9.
7. Extension points to reuse: `AlertEscalationPolicy`, `ReportGenerator`, `MyWorkContributor`, `PortalContributor`,
   `NotificationTypes` (+ `ck_notification_pref_type`), `ApprovalHandler` types of the approval engine, workflow triggers
   and actions (`WorkflowModel`), `AuditService`, `TeamRouting`, `ServiceHealthService`, CMDB graph/impact services,
   `AiService`/`GuardedLlm`, advisory-lock schedulers (pick unused lock ids, document them).

## 1. Simulation mode (BE-5)

- Setting `simulation.enabled` (SYSTEM_ADMIN, audited), off by default and **off in production profiles unless set**.
- `POST /api/simulation/scenarios/{code}/start|stop`, `GET /api/simulation/status`. Scenarios generate labelled data
  through the **same ingestion services** real data uses (so pipelines are exercised), with `simulated = true`:
  `BASELINE` (steady metrics/logs/traces for demo services), `PAYROLL_DB_SATURATION` (DB connection pool + latency
  rising, error logs, failing spans → anomalies → correlated alerts → incident), `DEPLOY_REGRESSION` (latency step after a
  simulated deployment), `COST_SPIKE` (FinOps, BE-8), `ENDPOINT_DEGRADATION` (DEX, BE-8), `VULN_WAVE` (SecOps, BE-7).
- `DELETE /api/simulation/data` purges simulated rows only (confirmation in UI; audited). Simulated rows never feed
  model training unless the training request sets `includeSimulated: true`, and such models are flagged `simulated`.

## 2. Roles and permission groups

New roles (V10, check constraints on `users.role` and `account_tokens.role`, frontend `ROLES`, demo users flagged demo):
`SECURITY_ANALYST` (security operations), `FINOPS_ANALYST` (cloud cost management). Both are staff for reading
operational data (add to READERS; not to incident STAFF unless stated).

| Group (`Roles`) | Roles |
|---|---|
| OBSERVABILITY_READERS | READERS + SECURITY_ANALYST + FINOPS_ANALYST |
| TELEMETRY_CONFIG | SYSTEM_ADMIN, IT_ADMIN |
| TWIN_EDITORS (simulations are read-only and open to readers) | SYSTEM_ADMIN, IT_ADMIN, ASSET_MANAGER, CHANGE_MANAGER |
| PREDICTION_ADMINS (train/evaluate/activate models) | SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER |
| AGENT_USERS (start agent runs) | STAFF + SECURITY_ANALYST |
| AI_GOVERNANCE (agent policies, evaluations) | SYSTEM_ADMIN, IT_ADMIN |
| PLAYBOOK_EDITORS | SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, CHANGE_MANAGER |
| PLAYBOOK_APPROVERS (approve playbook versions) | SYSTEM_ADMIN, IT_MANAGER, CHANGE_MANAGER |
| REMEDIATION_OPERATORS (request executions) | STAFF |
| EXECUTION_SYSTEM_ADMINS (connectors, credentials) | SYSTEM_ADMIN |
| SECOPS | SYSTEM_ADMIN, IT_ADMIN, SECURITY_ANALYST, INCIDENT_MANAGER |
| SECOPS_READERS | SECOPS + AUDITOR + IT_MANAGER |
| FINOPS | SYSTEM_ADMIN, IT_ADMIN, FINOPS_ANALYST, IT_MANAGER |
| FINOPS_READERS | FINOPS + AUDITOR + CHANGE_MANAGER + ASSET_MANAGER |
| DEX_READERS | READERS |
| SIMULATION_ADMINS | SYSTEM_ADMIN |

The AUDITOR reads everything in Release 4 (no writes). EMPLOYEE sees only their own DEX device summary in the portal.

## 3. Navigation (frontend `lib/nav.ts`)

New group **AUTONOMOUS OPERATIONS** (after OPERATIONS): Observability `/observability`, Digital Twin `/digital-twin`,
Predictions `/predictions`, SA Agents `/agents`, Remediation `/remediation`. New group **ENTERPRISE** (after
AUTONOMOUS OPERATIONS): Security Operations `/security`, FinOps `/finops`, Employee Experience `/dex`. Items are added
with `available: false` by the phase that defines them and flipped to `true` by the frontend phase that ships the page.
Settings gains "Simulation mode" (SYSTEM_ADMIN). A global banner shows while simulation mode is on.

## 4. Modules

### 4.1 SA Observability (BE-5, V10)
- **Telemetry sources** (`/api/observability/sources`): kinds `OTLP` (push), `PROMETHEUS_API` (pull, PromQL over HTTP,
  bearer/basic), `GRAFANA` (prepared: datasource proxy query), `CLOUDWATCH`, `AZURE_MONITOR`, `GCP_MONITORING`
  (prepared: configuration + request builder + response parser tested with fixtures; status NOT_CONNECTED until a real
  test call succeeds). Token shown once (`otsec_…`).
- **OTLP/HTTP JSON ingestion** (OpenTelemetry protocol, JSON encoding): `POST /api/otel/v1/metrics`, `/v1/logs`,
  `/v1/traces` with `Authorization: Bearer <source token>`; protobuf is not supported (`415`, documented). Size limit
  (e.g. 2 MB), batch limits, partial-success response per the OTLP spec (`partialSuccess.rejectedDataPoints` etc.).
- **Normalisation**: resource attributes `service.name`, `service.namespace`, `deployment.environment`, `host.name`,
  `k8s.*`, `cloud.*` → mapped to SA services and CMDB CIs via explicit mapping rules (`/api/observability/mappings`,
  match by attribute value/regex → serviceId/ciId), unmapped telemetry kept with `serviceId null` and listed.
  Metric points (gauge/sum/histogram summary → value, count, sum), log records (severity normalised to
  TRACE…FATAL, body ≤ 8 KB, attributes ≤ 64), spans (traceId, spanId, parent, name, kind, status, duration).
- **Storage & retention**: PostgreSQL tables with time indexes; minute roll-ups for metrics; retention settings
  (`observability.retention.raw_days` 7, `rollup_days` 90, `logs_days` 14, `traces_days` 7) with a nightly purge job.
- **Query API**: metric series (name, filters, from/to, step, aggregation avg|max|min|sum|p95 from roll-ups), log search
  (text, severity, service, time), traces (list, trace detail with span tree), service RED summary (rate, errors,
  duration p95) per service from spans/metrics.
- **Anomaly detection** (statistical, documented): per series rolling baseline (EWMA mean/variance or median/MAD over a
  configurable window, seasonality by hour-of-week when ≥ 2 weeks of data); anomaly when |z| ≥ threshold for k
  consecutive points; stores anomaly with score, expected range, observed, model parameters; **evaluated** on labelled
  history (precision/recall on injected/simulated faults and on past alerts) via `/api/observability/anomaly-detectors/{id}/evaluate`.
  Detectors created by TELEMETRY_CONFIG for chosen series; anomalies can raise alerts (source `OBSERVABILITY`) via the
  existing alert pipeline (dedupe, suppression, routing → incident).
- **Alert correlation**: correlation engine groups alerts and anomalies within a time window using topology (CMDB/
  service dependency distance), shared labels/fingerprints and change/deployment proximity; produces
  `correlation_groups` with members, score, explanation (`reasons[]`), suggested root component; link group ↔ incident
  (convert, link). Re-evaluated on new alerts; human can split/merge/dismiss.

### 4.2 SA Digital Twin (BE-5, V10)
- Builds on CMDB CIs + relationships + services + service dependencies; no second inventory. Twin **layers**: business
  services → applications → infrastructure (CI types), with live state overlays (open incidents, firing alerts,
  anomalies, health, last change).
- `GET /api/twin/graph?root&depth&layer&includeState` — nodes/edges with state; `GET /api/twin/business-services/{id}`
  — dependency tree down to infrastructure.
- **Failure simulation**: `POST /api/twin/simulations` `{type FAILURE|CHANGE, targets: [ciId…], mode HARD|DEGRADED,
  changeId?}` → computes propagation through dependency edges with redundancy (relationship attributes such as
  `redundant: true` / cluster membership reduce impact), returns impacted CIs/services/business services with paths,
  estimated affected users (from service data), open SLAs at risk, scheduled changes touching them. Simulations are
  persisted (history, who ran it), never alter real data, and are labelled "Simulation".
- **Configuration change tracking**: CI attribute/relationship changes already in CMDB history; add a unified
  timeline (`/api/twin/changes?ciId&from&to`) merging CMDB history, change requests, deployments and telemetry
  resource attribute changes (e.g. version attribute changed) with diff views.

### 4.3 SA Predictive Intelligence (BE-6, V11)
- **Model registry**: `prediction_models` (type, version, algorithm, features, training window, trained_at,
  sample count, metrics JSON, status TRAINED|ACTIVE|RETIRED, `simulated`). Only ACTIVE models serve predictions; one
  active version per type. Training/evaluation jobs are explicit (PREDICTION_ADMINS) and scheduled (weekly, advisory lock).
- Types and algorithms (implemented in Java, no external ML service):
  - `SLA_BREACH` — logistic regression (features: priority, category, service tier, team load, time of day, elapsed
    share, reassignments) → probability; supersedes the Release 3 statistic only when an ACTIVE model exists.
  - `INCIDENT_RECURRENCE` — probability that a resolved incident's service/category recurs within 14 days
    (logistic regression on history; features: past recurrence, problem linked, resolution code, change since).
  - `CAPACITY_RISK` — linear/Holt trend on utilisation metrics (cpu, memory, disk, connection pool) → time to threshold
    with prediction interval; risk when the interval crosses the threshold within the horizon.
  - `SERVICE_HEALTH` — Holt-Winters (or seasonal naive when < 2 seasons) forecast of incident/alert counts and error
    rate per service with intervals.
  - `ANOMALY` — the observability detectors (4.1) registered here for evaluation tracking.
- **Measurable validation**: time-based split (train before cutoff, test after); classification metrics AUC, Brier
  score, precision/recall at the operating threshold, calibration table (10 bins); forecasts MAE/MAPE and interval
  coverage. Minimum sample sizes enforced (e.g. ≥ 200 incidents, ≥ 30 positives) → otherwise `INSUFFICIENT_DATA`.
- **Confidence tracking**: every served prediction stored (`predictions`: model version, subject, score, interval,
  features snapshot, created_at); outcomes recorded when known (breached/not, recurred/not, actual value) → live
  accuracy per model over time (`/api/predictions/models/{id}/performance`), drift warning when live metrics degrade
  beyond tolerance vs. offline evaluation.
- UI shows model version, confidence and "how this was computed" for every prediction. Never presented as AI/LLM.

### 4.4 SA Agentic AI (BE-6, V11)
- **Agents** (server-defined classes, not user code): `INCIDENT` (triage summary, classification, priority advice),
  `ROOT_CAUSE` (hypotheses with evidence), `CHANGE_RISK`, `KNOWLEDGE` (retrieval + citations), `INFRA_INVESTIGATION`
  (telemetry, topology, recent changes), `RESOLUTION` (resolution plan, playbook recommendation). Each declares its
  **tools** — read-only, authorised internal queries executed **as the requesting user** (permission checks apply):
  get incident, similar incidents, KB search, CMDB/twin impact, telemetry query (metrics/logs/traces), alerts &
  correlation groups, changes/deployments, problems, predictions. No tool writes data.
- **Orchestration**: run types `INVESTIGATE_INCIDENT` (incident → [root cause ∥ infra ∥ knowledge] → resolution
  synthesis), `ASSESS_CHANGE`, `ANSWER_QUESTION`. Run = steps with status, inputs, tool calls (name, args, result size,
  duration), outputs, tokens, latency; max steps, per-step timeout and total timeout; cancellation by the requester or
  AI_GOVERNANCE. With an LLM configured each agent runs a bounded tool-use loop (`GuardedLlm`, structured output JSON
  schema per agent, validated; invalid → one retry → FALLBACK); without it each agent has a deterministic fallback.
  Every output carries `source LLM|FALLBACK`, evidence references (record type + id), confidence.
- **Proposed actions** (assign team, change priority, link problem, publish KB draft, run remediation playbook,
  declare major incident) are never applied by agents: they create approval requests (`AI_ACTION`, existing handler
  extended) or open the target UI prefilled; the human decision is recorded on the run.
- **Governance**: policies per agent (enabled, allowed tools, max steps, timeout, data scopes, which roles may run it,
  require approval for which action kinds); prompt/version registry (agent version recorded on each run); **evaluation**
  suites (`agent_eval_cases`: input subject + expected labels/keywords, e.g. category, root-cause component, KB article)
  executed on demand → per-case pass/fail and aggregate score per agent version; human feedback (helpful/not, comment)
  on runs; governance dashboard (runs, failure rate, fallback rate, approval outcomes, eval scores).

### 4.5 SA Autonomous Remediation (BE-7, V12)
- **Execution systems** (EXECUTION_SYSTEM_ADMINS): connector kinds `HTTP_WEBHOOK` (generic signed), `ANSIBLE_AWX`
  (launch job template by id), `KUBERNETES` (scale deployment / rollout restart via API server URL), `AWS_SSM`
  (prepared: start automation document), `SIMULATED` (no external call; labelled). Each connector has **least-privilege
  credentials** (encrypted, write-only, scope description) and an **allow-list of action templates** (id, parameters with
  JSON-schema types/patterns/enums, target scope e.g. namespaces/job template ids). No free-form commands or scripts.
- **Playbooks**: versioned (DRAFT → IN_REVIEW → APPROVED → RETIRED; approval via the approval engine, type
  `PLAYBOOK`); triggers (manual, from agent recommendation, from workflow action `RUN_PLAYBOOK`, from alert rule);
  parameters schema; steps of kinds `ACTION` (template + params), `VERIFY` (telemetry query threshold / HTTP health
  check through UrlSafety / service health), `WAIT`, `MANUAL_CONFIRM`, `NOTIFY`; each ACTION step declares its
  **rollback** step; success criteria for recovery verification.
- **Policy checks before execution** (all recorded): playbook version APPROVED; requester role; environment rules
  (PRODUCTION requires approval unless the playbook is marked pre-approved for that alert type); change freeze windows;
  blast radius from the twin (impacted services ≤ limit); rate limit per target; maintenance window; concurrent run lock
  per target; required linked incident/change. Failing a check → `BLOCKED` with reasons; override only by
  PLAYBOOK_APPROVERS with reason (audited).
- **Execution**: state machine PENDING_APPROVAL → APPROVED → RUNNING → VERIFYING → SUCCEEDED | FAILED → ROLLING_BACK →
  ROLLED_BACK | ROLLBACK_FAILED, CANCELLED; per-step timeout, retries (bounded), pause/cancel by a human; on failure run
  rollbacks of completed steps in reverse; recovery verification after completion; result posted to the linked
  incident's activity.
- **Tamper-evident audit**: `remediation_audit` hash chain (each record stores `prev_hash`, `hash =
  SHA-256(prev_hash ‖ canonical JSON of the record)`), append-only (no update/delete; DB trigger raising on UPDATE/
  DELETE), `GET /api/remediation/audit/verify` recomputes the chain and reports the first broken link. Every policy
  decision, approval, step call (request without secrets, response status/size), rollback and verification is recorded.
  The general audit log also gets the events.

### 4.6 SA Security Operations (BE-7, V12)
- **Security incidents**: separate entity linked optionally to an SA incident; categories (malware, phishing,
  unauthorised access, data exposure, vulnerability exploitation, DoS, policy violation), severity, status NEW → TRIAGE →
  CONTAINMENT → ERADICATION → RECOVERY → CLOSED (lessons), affected assets/CIs/users, indicators (IOC type/value),
  evidence notes, restricted visibility (SECOPS + assigned), response tasks.
- **Vulnerability findings ingestion**: sources (token/HMAC) with formats `GENERIC_JSON`, `SARIF` 2.1.0, `TRIVY_JSON`,
  `CSV`; normalised finding (CVE/CWE ids, title, scanner, asset/CI match by hostname/image/repo, package, installed and
  fixed version, CVSS v3 base score/vector, exploit known flag, first/last seen, status OPEN|FIXED|ACCEPTED|FALSE_POSITIVE);
  dedupe by (source, rule/CVE, asset, package); auto-close when absent from a full scan; risk acceptance with expiry
  and approval.
- **Risk prioritisation** (documented formula): CVSS base × asset criticality (CI/service tier, data classification)
  × exposure (internet-facing attribute) × exploitability (known exploited flag) × age factor → 0–100 score and band;
  remediation SLA per band.
- **Security alert correlation**: alerts with security labels/sources + findings + failed-login bursts (from the auth
  audit) grouped by asset/user/time into security correlation groups → raise security incident.
- **Response workflows**: response playbook templates (phishing, malware containment, compromised account) as task
  checklists + optional remediation playbooks (4.5) such as disable account via an approved connector template; workflow
  triggers `SECURITY_INCIDENT_CREATED`, `VULNERABILITY_CRITICAL`.
- Provider integrations are prepared (formats above); none claimed operational until configured and verified.

### 4.7 SA FinOps (BE-8, V13)
- **Cost ingestion**: FOCUS 1.x CSV (FinOps Open Cost and Usage Specification columns: BilledCost, EffectiveCost,
  ChargePeriodStart/End, ServiceName, ResourceId, ResourceName, RegionId, Tags, ProviderName, SubAccountId…), generic
  CSV with column mapping, and prepared API adapters (AWS CUR/Cost Explorer, Azure Cost Management, GCP billing export)
  — dry run with row errors then apply; idempotent per (source, period, resource, charge).
- **Allocation**: rules (tag key/value, account, resource pattern → service/team/department/cost centre, with
  percentage splits); unallocated bucket shown; daily aggregates per allocation target.
- **Utilisation**: from observability metrics mapped to resources (cpu, memory, storage) → utilisation per resource.
- **Idle detection**: rule-based (avg cpu < x % and network < y over N days, unattached volumes, zero-request services)
  with cost of the idle period; **cost anomaly detection**: EWMA/MAD per service/account daily cost with expected range.
- **Budgets**: per target and period with thresholds (e.g. 50/80/100 %) → notifications (new types) and forecast to
  period end (linear on month-to-date).
- **Recommendations**: rightsizing, idle shutdown, storage tiering, commitment coverage (only from data present), with
  computed estimated monthly savings and evidence; accept → create change request draft; dismiss with reason.

### 4.8 SA Digital Employee Experience (BE-8, V13)
- **Endpoint health ingestion** (agent-agnostic JSON via token source; prepared mappings for Intune/Jamf exports):
  device id, user (email), OS + version, model, cpu/memory/disk usage, boot time, crashes, battery health, last seen.
- **Application performance**: app launch/page load times, crashes/hangs, errors per app and version (ingested events;
  web RUM-style JSON accepted).
- **Login and access issues**: SA's own sign-in failures/lockouts (audit) + ingested SSO/IdP events (generic JSON) →
  login success rate, lockouts, MFA failures per department.
- **Experience score** (documented formula, 0–100): weighted device health, app performance, login success and
  ticket signals (incidents per user, reopen rate, resolution time, CSAT if present) per user, department, device model,
  app; trends and drivers; proactive incident rule (opt-in) when a score drops below a threshold for a department.
- **Service experience analytics**: per service — incident volume per 100 users, time to resolve, reopen rate,
  first-contact resolution, satisfaction where captured. Employee portal shows the employee's own device summary.

## 5. Phases, ownership and checks

| Phase | Backend (sequential, shared Maven build) | Migration | Frontend (after its backend) |
|---|---|---|---|
| P1 | BE-5: roles, simulation mode, Observability, Digital Twin | V10 | FE-6: nav groups, simulation settings + banner, Observability, Digital Twin |
| P2 | BE-6: Predictive Intelligence, Agentic AI | V11 | FE-7: Predictions, SA Agents (runs, governance, evaluations) |
| P3 | BE-7: Remediation, Security Operations, hash-chained audit | V12 | FE-8: Remediation, Security Operations |
| P4 | BE-8: FinOps, Digital Employee Experience | V13 | FE-9: FinOps, Employee Experience, portal device card |

Each phase ends with: full backend suite, Vitest, type check, production build, and security checks (dependency audit
`npm audit --omit=dev`, secret scan of the tree, authorisation tests for every new endpoint incl. employee/auditor
denial, input-validation tests, SSRF and signature tests for new inbound/outbound paths). `AdministrationIT` asserts the
latest schema version. Integration at the end: fresh database, Playwright (new journeys per module), visual check,
docs (PROJECT_STATUS.md, ARCHITECTURE.md, API_DOCUMENTATION.md, DATABASE_SCHEMA.md, SECURITY.md), package.

Advisory lock ids already in use: see ARCHITECTURE.md (schedulers). New schedulers pick ids 360010–360029 and record them.
