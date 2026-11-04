# SA — Architecture

**SA — Enterprise Operations Platform** · *Intelligent Service Management. Unified Operations.*

This document describes how the Release 3 code base is put together: the deployable parts, every backend package, the
extension points later modules plug into, the key end-to-end flows, the background schedulers and how they stay safe with
several API instances, and the frontend structure. It is written from the code; where a statement could not be checked
against the code it says so.

Related documents: [API_DOCUMENTATION.md](API_DOCUMENTATION.md) (endpoint catalogue) ·
[DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) (tables, constraints, migrations) · [SECURITY.md](SECURITY.md) (controls and
permission matrix) · [DEPLOYMENT.md](DEPLOYMENT.md) (hosting and environment variables) ·
[docs/RELEASE3_SPEC.md](docs/RELEASE3_SPEC.md) (Release 3 scope).

Naming: the product is **SA** (formerly "NEXUS 360", then "SA 360"). Technical identifiers were deliberately left
unchanged for backward compatibility: Java package `com.nexus360`, artifact `nexus360-api`, the `nexus_refresh` cookie, the
`X-Requested-With: nexus360` header, `X-Nexus-*` webhook headers, the demo domain `demo.nexus360.io`, the Render service name
and the repository folder.

## 1. System overview

```
┌──────────────────────────── Browser ─────────────────────────────┐
│ React 18 SPA (Vite 6, TypeScript, Tailwind v4)                    │
│  React Router (lazy routes) · TanStack Query · RHF + Zod · Recharts│
│  access token in memory · refresh token = HttpOnly cookie          │
└───────────────┬───────────────────────────────────────────────────┘
                │ same-origin /api  (Vite proxy · nginx · Vercel rewrite)
┌───────────────▼───────────────────────────────────────────────────┐
│ Spring Boot 3.5 API (Java 21, virtual threads)                     │
│  Filters: rate limit → JWT → AI rate limit → URL role gates        │
│  REST controllers → services (@Transactional) → JPA repositories   │
│  Domain events ──AFTER_COMMIT──▶ workflow engine, integrations     │
│  Schedulers: SLA, workflows, approvals, alert escalation, reports, │
│              e-mail outbox (advisory locks / SKIP LOCKED)          │
│  SSE: live notifications after commit                              │
│  SA AI: deterministic engine ⇄ LlmProvider (Anthropic SDK)         │
└───────┬──────────────────────┬──────────────────┬──────────────────┘
        │ JDBC/TLS (Hikari)    │ optional         │ optional HTTPS / SMTP
┌───────▼───────────┐  ┌───────▼────────┐  ┌──────▼───────────────────────────┐
│ PostgreSQL 17     │  │ Redis          │  │ Anthropic API · SMTP server       │
│ Flyway V1 … V9    │  │ shared rate-   │  │ Slack / Teams / webhook targets   │
│ tsvector, pg_trgm │  │ limit counters │  │ inbound: Prometheus, Grafana,     │
└───────────────────┘  └────────────────┘  │ CloudWatch/SNS, GitHub, GitLab    │
                                           └───────────────────────────────────┘
```

One deployable API (`backend/`) and one static frontend (`frontend/`). The database is the single source of truth:
dashboards, SLA state, analytics, on-call assignments and AI grounding are computed from stored records; nothing is
hard-coded in the UI. Seeded demonstration records carry `is_demo = true` and are badged **DEMO**.

| Layer | Technology (from `backend/pom.xml`, `frontend/package.json`) |
|---|---|
| Backend | Java 21, Spring Boot 3.5.x, Spring Security, Spring Data JPA / Hibernate (`ddl-auto: validate`), Flyway, springdoc-openapi, Anthropic Java SDK, OpenPDF |
| Frontend | React 18, TypeScript 5.7, Vite 6, Tailwind CSS v4, React Router 6, TanStack Query 5, React Hook Form + Zod, Recharts, lucide-react |
| Data | PostgreSQL 17 (15+ supported per docs/NEON.md), optional Redis for shared rate-limit counters |
| Tests | JUnit 5, Mockito, Spring Boot Test with embedded PostgreSQL (zonky), GreenMail (SMTP), Vitest + Testing Library, Playwright |

## 2. Backend packages (`backend/src/main/java/com/nexus360`)

Each package owns its entities, repositories, service(s), DTOs and controller. Modules talk to each other through
services, domain events and the extension points in §3 — never through another module's controller.

| Package | Responsibility |
|---|---|
| `access` | Public account access (Release 2.1): access requests, forgot-password requests, invitation and reset links (`AccountTokenService` stores only SHA-256 hashes, single use, expiry, advisory lock per e-mail), admin review (`AccountRequestAdminService`), link e-mails (`AccountLinkMailer`) |
| `admin` | System settings (`SettingsService`, typed keys), `/api/health`, `/api/admin/system-health` (database, Redis, schedulers, AI, e-mail), incident-category and AI configuration endpoints |
| `ai` | SA AI: `LlmProvider` abstraction with `AnthropicLlmProvider`, `FallbackEngine` (rules and statistics), `AiService` (grounded prompts, `runGrounded`, interaction log), `GuardedLlm` (timeout, retry, output guard), `ResolutionAdvisor`, `WorkflowSuggester`, `AiActionService` (AI proposals become approvals), `OpsAiAdvisor` (Release 3: problem correlation, CMDB impact, runbook recommendations, predictive SLA risk, trends) |
| `alert` | Alert management (Release 3): sources and webhook adapters (`AlertWebhookService`, `AlertPayloads`, `SnsMessageVerifier`, `SnsGateway`), the ingestion pipeline (`AlertIngestService`), suppression and routing rules (`AlertRuleService`, `MatchCriteria`), escalation scheduler (`AlertEscalationService`) and the `AlertEscalationPolicy` extension point with the built-in `TeamLeadEscalationPolicy`, history, My Work contribution |
| `analytics` | KPIs, trends, breakdowns, OLS forecast (`ForecastModel`), PDF/CSV export, Release 3 `OperationsMetricsService` (MTTA, MTTD, FCR, change success, problem recurrence, alert volume, asset lifecycle, on-call, department performance, service availability, Command Center) and metric definitions |
| `announcement` | Announcements |
| `approval` | Approval engine v2: flows (`ApprovalFlow`, stages, quorum `ANY/ALL/N`, conditional stages, deadlines), approvals, stages, tasks (user / role / team approvers), delegations, append-only approval events, `ApprovalDeadlineScheduler`, the `ApprovalHandler` interface implemented per approval type, Release 2 backfill |
| `asset` | Hardware and software inventory, licences with seats, assignments, maintenance, lifecycle events, CSV import (dry run, row errors) and export, portal and report contributions |
| `audit` | Append-only audit log (`AuditService`, `@Immutable` entity, database trigger), queries and CSV export |
| `change` | Changes (`STANDARD/NORMAL/EMERGENCY`), lifecycle, risk model (`ChangeRisk`), templates, freeze windows, conflicts, incident correlation, CAB through the approval engine (`ChangeApprovalHandler`), change maintenance source, `ChangeAiAdvisor`, My Work / portal / report contributions |
| `cmdb` | Business services (`ServiceController`, `ServiceHealthService` — computed health and availability), configuration items and typed relationships, cycle-safe traversal and impact analysis (`CmdbGraphService`), CI links (`CiLinkService`), service ↔ CI sync (`ServiceCiSync`), the `ServiceMaintenanceSource` extension point |
| `common` | `ApiError`/`ApiException`, `GlobalExceptionHandler`, `PageResponse`, base entities, CSV with formula-injection protection, JSON helpers, request info |
| `config` | `AppProperties` (typed `app.*` configuration), `CoreConfig`, `DatabaseUrlEnvironmentPostProcessor` (accepts `postgresql://user:pass@host/db` URLs such as Neon's) |
| `demo` | Demo organisation seeder and the Release 3 seeders (`DemoRelease3Seeder`, `…ChangeSeeder`, `…AssetSeeder`, `…OpsSeeder`), the payroll-outage scenario and simulated SLA time (demo records only) |
| `deployment` | Releases, deployment records, rollbacks, CI/CD webhook sources (GitHub `X-Hub-Signature-256`, GitLab `X-Gitlab-Token`, generic HMAC) with replay-safe delivery log |
| `email` | `EmailService` (queue into the outbox), `EmailOutboxProcessor` (delivery with retries), `EmailTemplates` (escaped branded HTML + text), `MailTransport`, admin status/outbox/test endpoints |
| `events` | `DomainEvents` records: incident, SLA alert, service request, change, alert and major-incident events |
| `form` | Versioned dynamic form definitions, server-side `FormValidator` (types, required/min/max/pattern, `showIf`), catalogue form backfill |
| `incident` | Incident aggregate, `IncidentClassifier`, `PriorityCalculator`, `DuplicateDetector` (`TextSimilarity`), `AssignmentService`, `IncidentStateMachine`, comments, attachments, history, links, escalations, activity timeline, queries/export, category configuration |
| `integration` | Outbound webhooks (HMAC-signed), Slack/Teams, inbound monitoring endpoint (now recorded as alerts), `SecretCipher` (AES-256-GCM), `UrlSafety` (SSRF guard), delivery log |
| `knowledge` | Articles, review workflow, versions, ratings, full-text (`tsvector`) + trigram search, recommendations |
| `major` | Major incident command (`MajorIncidentService`: roles, status, war-room timeline, stakeholder updates, impacted services), post-incident reviews (`PirService`: timeline, metrics, approval), action items (`ActionItemService`), `MajorExtensions` (PIR approval handler, My Work action items) |
| `mywork` | `GET /api/my-work` and the `MyWorkContributor` extension point; core sections (my incidents, team queue, requests, team requests, approvals) |
| `notification` | In-app notifications, per-category preferences with in-app and e-mail channels (`NotificationTypes`), `NotificationStreamHub` (SSE) |
| `oncall` | Schedules, rotations (`Rotation`: DAILY/WEEKLY/CUSTOM, DST-aware), participants, overrides (offer/take), unavailability, handovers, escalation policies, pages, `OnCallEscalation` (an `AlertEscalationPolicy`), My Work and report contributions |
| `portal` | `GET /api/portal/overview` (employee portal) and the `PortalContributor` extension point; core sections |
| `problem` | Problems (`PRB-####`), linked incidents/services/CIs, corrective actions, known errors (optionally published as KB articles), recurring-incident clusters, `ProblemAiAdvisor`, contributions |
| `report` | Scheduled reports: schedules, runs (file stored in the database), `ReportScheduler`, the `ReportGenerator` registry, `ReportPdf` layout |
| `runbook` | Runbooks, executions, sensitive-step approvals (`RunbookStepApprovalHandler`) |
| `search` | Global search scoped by role |
| `security` | JWT (HS256) issue/verify, rotating refresh tokens with reuse detection, sessions (`AuthSession`, "remember me"), BCrypt, password policy, lockout, `RateLimitFilter` (in-memory or Redis), `AiRateLimitFilter`, `SecurityConfig` (CORS allow-list, headers, URL gates), `Roles` (all `@PreAuthorize` groups) |
| `servicedesk` | Service catalogue (categories, items with form definitions and submit permissions), service requests, comments, history, attachments, fulfilment, request approval handler |
| `sla` | Business-hours calendars and holidays, policies, `BusinessTimeCalculator`, `SlaEngine` (start/pause/resume/complete/recalculate/evaluate/escalate), `SlaScheduler` |
| `statuspage` | Status page settings, components, maintenance windows (`StatusMaintenanceSource`), updates, double opt-in subscribers (`StatusSubscriptionService`), public status endpoint |
| `team` | Teams and members, `TeamRouting` (team selection and least-loaded member) |
| `user` | Users, departments, `Role` enum and role sets, bootstrap administrator |
| `workflow` | Versioned workflow definitions, `ConditionEvaluator`, `ActionExecutor`, `WorkflowEngine` (event and scheduled triggers, retries, limits), templates, fact sources, `WorkflowApprovalHandler` |
| `simulation` | Release 4 simulation mode (`SimulationService`, `SimulationScheduler`): scenarios `BASELINE`, `PAYROLL_DB_SATURATION`, `DEPLOY_REGRESSION`, `VULN_WAVE`, `COST_SPIKE`, `ENDPOINT_DEGRADATION`; purge of `simulated = true` rows |
| `observability` | OTLP/HTTP JSON ingestion (`OtlpController`, `TelemetryWriter`), mapping rules (`TelemetryMappingService`), 5m/1h roll-ups and retention (`TelemetryMaintenanceService`), queries, anomaly detectors & evaluation (`AnomalyService`), topological alert correlation (`CorrelationService`), Prometheus pull adapter (`PrometheusPullService`) |
| `twin` | Digital Twin (`TwinService`): live operational graph, business-service dependency trees, failure and change blast-radius simulations, unified change timeline |
| `prediction` | Predictive Intelligence (`PredictionService`, `PredictionTrainer`, `PredictionDatasets`, `PredictionMath`, `PredictionOutcomeService`, `PredictionScheduler`): L2 logistic regression and Holt linear/seasonal forecasting with time-based splits, 10-bin calibration, outcome reconciliation and live drift detection |
| `agent` | SA Agentic AI (`AgentService`, `AgentRunner`, `AgentTools`, `AgentDefinitions`, `AgentPolicyService`, `AgentEvaluationService`, `AgentActionApprovals`, `JsonSchemaValidator`): 6 server-defined agents, read-only user-scoped tools, orchestration (`INVESTIGATE_INCIDENT`, `ASSESS_CHANGE`, `ANSWER_QUESTION`), `AI_ACTION` approvals, governance policies and evaluation suites |
| `remediation` | Autonomous Remediation (`RemediationService`, `RemediationController`): execution systems (`SIMULATED`, `HTTP_WEBHOOK`, `ANSIBLE_AWX`, `KUBERNETES`, `AWS_SSM`) with `SecretCipher` encrypted credentials and allow-listed action templates, versioned playbooks (`PLAYBOOK` approval), pre-execution policy checks, state machine with reverse rollback, and SHA-256 tamper-evident `remediation_audit` |
| `secops` | Security Operations (`SecOpsService`, `SecOpsController`): security incidents (`NEW` → `CLOSED`), multi-format vulnerability ingestion (`GENERIC_JSON`, `SARIF` 2.1.0, `TRIVY_JSON`, `CSV`), documented 0–100 risk score, security correlation groups and response templates |
| `finops` | FinOps (`FinOpsService`, `FinOpsController`): FOCUS 1.x and mapped CSV cost ingestion (dry-run & apply), allocation rules, budgets with threshold notifications and month-end forecasts, EWMA/MAD daily cost anomalies, idle resource detection and savings recommendations |
| `dex` | Digital Employee Experience (`DexService`, `DexController`): endpoint health, application performance and login/SSO telemetry, documented 0–100 experience score, proactive incident rules, service experience analytics and employee portal device card (`/api/dex/me`) |

The Release 3 on-call, major-incident and PIR code lives in the packages `oncall` and `major` (there is no separate
`pir` package; reviews are `major.PostIncidentReview` / `major.PirService`).

## 3. Extension points

Release 3 modules were added without editing the core controllers: each module contributes Spring beans that implement
one of these interfaces (usually in a `*Extensions` configuration class of its own package).

| Extension point | Contract | Implementations today |
|---|---|---|
| `mywork.MyWorkContributor` | `key()` (unique, checked at startup), `order()` (core 10–50, modules ≥ 100), `appliesTo(actor)` (default: staff), `contribute(Context{actor, teamIds, limit, now})` → usually `Section<T>{total, items}`; runs in a read-only transaction | `CoreMyWorkContributors` (incidents, teamQueue, requests, teamRequests, approvals), `ProblemExtensions` (problems, corrective actions), `ChangeExtensions` (changes), `AlertExtensions` (alerts), `OnCallExtensions` (onCallShifts), `MajorExtensions` (actionItems) |
| `portal.PortalContributor` | Same pattern for `GET /api/portal/overview`; sections may only contain the caller's own records or organisation-wide public information | `CorePortalContributors` (open incidents, open requests, awaiting approval, service status, recommended articles), `AssetExtensions` (my assets), `ChangeExtensions` (planned changes) |
| `report.ReportGenerator` | One bean per `ReportType`: `description()`, `formats()`, `filterKeys()`, `generate(Request{format, from, to, zone, filters, title, generatedAt})` → bytes; reads stored records only, must not audit (the run is audited); collected by `ReportGeneratorRegistry` | `CoreReportGenerators` (ANALYTICS_OVERVIEW, SLA, INCIDENTS), `ChangeExtensions` (CHANGES), `ProblemExtensions` (PROBLEMS), `AssetExtensions` (ASSETS), `OnCallExtensions` (ONCALL) |
| `alert.AlertEscalationPolicy` | `name()`, `order()` (lowest applicable wins), `appliesTo(alert)`, `nextEscalationAt(alert, reference)`, `escalate(alert, level, now)` → `Outcome{notifiedUserIds, note}`; runs inside the escalation scheduler's transaction | `oncall.OnCallEscalation` (`on-call`, order 100), `alert.TeamLeadEscalationPolicy` (`team-lead`, order 1000, fallback) |
| `approval.ApprovalHandler` | `type()` + `onDecision(approval, approved, approver)`, called inside the decision transaction when an approval reaches its final outcome | service requests (`ServiceDeskService`), runbook executions (`RunbookService`), runbook steps (`RunbookStepApprovalHandler`), AI actions (`AiActionService`), changes (`ChangeApprovalHandler`), PIRs (`MajorExtensions`), workflow sign-offs (`WorkflowApprovalHandler`) |
| `notification.NotificationTypes` | Static registry of user-configurable categories (`PreferenceType{type, label, description, emailDefault}`) and the mapping from concrete notification types to categories; a new category must also be added to the `ck_notification_pref_type` check constraint in the module's migration. Unmapped types are in-app only, never e-mailed | 26 categories after V9 (see API_DOCUMENTATION.md, "Extension-point contributions") |
| `team.TeamRouting` | Shared component (not an interface): default team of a department, active team by code/id, active members, least-loaded member (support agents first, then other staff) | Used by incident and request assignment, workflows (`ASSIGN_TEAM`), alert routing fallback, problems and changes |
| `cmdb.ServiceMaintenanceSource` | `activeMaintenance(now)` → `Map<serviceId, title>`; a service in an active window shows `MAINTENANCE` | `statuspage.StatusMaintenanceSource` (maintenance windows), `ChangeApprovalHandler.changeMaintenance()` (approved/scheduled changes inside their window and changes being implemented) |
| `email.EmailService` (outbox) | `queue(Request{category, to, userId, subject, content, sensitive, …})` writes an `email_outbox` row in the caller's transaction and returns `Queued{id, status}`; `configured()` tells whether SMTP exists; `link(path)` builds absolute links from `APP_PUBLIC_URL` | Notifications (per-category e-mail preference), invitations and reset links, status-page confirmations and updates, scheduled reports, admin test mail |
| `ai.AiService.runGrounded` | `runGrounded(feature, subject, actor, viaLlm, viaFallback)`: when no provider is configured, LLM use is disabled (`ai.llm.enabled`) or the LLM call throws `LlmException`, the deterministic `viaFallback` result is returned, labelled `source = FALLBACK` with a `fallbackReason`; successful calls are labelled `LLM` with provider and model; every call is logged in `ai_interactions` | `OpsAiAdvisor` (Release 3 AI features, via `GuardedLlm`); the Release 2 features use the same `run` path inside `AiService` |
| Domain events (`events.DomainEvents`) | Records published with Spring's `ApplicationEventPublisher`; workflows and integrations listen after commit; `MajorIncidentEvent` is handled synchronously inside the incident transaction | Incident, SLA, service request, change (`CHANGE_REQUEST_CREATED` trigger), alert (`ALERT_RECEIVED` trigger), major incident |

## 4. Key flows

### 4.1 Incident intake (`IncidentService.create`) — unchanged from Release 2, team-aware since Release 3

1. Validate (Bean Validation). 2. Classify category and service from keyword signals (`IncidentClassifier`, whole-word
matching) unless the reporter chose a service. 3. Prioritise with the impact × urgency matrix (`PriorityCalculator`).
4. Detect duplicates (`DuplicateDetector`: active incidents of the last 72 h, token Jaccard + trigram Dice similarity,
service/category agreement, symptom signatures; threshold setting `incident.duplicate.threshold`, default 0.48). Above the
threshold the report is linked to a parent (created if needed) and its SLA is cancelled; at
`incident.major.report_threshold` (3) reports the parent becomes P1 and a major incident (which opens the command record,
§4.5). 5. Assign: service's default team → department's default team → least-loaded member (`TeamRouting`), else the
department fallback. 6. Start the SLA for the priority's policy. 7. Record history and audit, publish `IncidentEvent`
(`INCIDENT_CREATED` for workflows and integrations after commit).

### 4.2 SLA engine

Response and resolution clocks are stored as `consumed_seconds` + `running_since`, so pause/resume and priority changes
are exact; deadlines are computed in business time (calendar, time zone, holidays) or 24×7. `SlaScheduler` evaluates
every `SLA_EVALUATION_INTERVAL_MS` (60 s) under advisory lock 360001, emits `AT_RISK` and `BREACHED`, raises escalation
levels 1–3 (unique per clock and level) and notifies managers; events feed workflows and the compliance history.

### 4.3 Alert ingestion → deduplication / suppression / routing → incident

```
Prometheus / Grafana / CloudWatch (SNS) / generic HMAC        legacy inbound integration      manual alert
        │  POST /api/alerts/webhooks/{sourceId}                 POST /api/integrations/inbound/{id}   POST /api/alerts
        ▼                                                         │                                  │
AlertWebhookService: verify (Bearer/Basic · HMAC + timestamp + delivery id · SNS signature + topic) ─┘──────────┘
        │  delivery log (unique per source + delivery id); failures → generic 401, audited ALERT_WEBHOOK_REJECTED
        ▼
AlertPayloads → AlertSignal(s) → AlertIngestService.ingest (one transaction per signal)
   1 attach    CI by name / Prometheus instance host / source default; service by code, else the nearest service that depends on the CI
   2 dedupe    advisory lock (360009, hash of scope + fingerprint); one active alert per scope + fingerprint (partial unique index);
               repeat → occurrence_count + 1; resolved notification → RESOLVED; fires again within alert.grouping.window_minutes → reopened
   3 suppress  first enabled suppression rule whose window is open and matcher matches → SUPPRESSED (no routing, paging or incident)
   4 route     first matching routing rule (sort_order) sets team / service / CI; else service default team or CI team (TeamRouting);
               team notified (ALERT_ROUTED) from alert.notify.min_severity
   5 escalate  AlertEscalationPolicy.nextEscalationAt → alerts.next_escalation_at
   6 convert   routing rule createIncident (≥ incident_min_severity) or legacy integration → IncidentService.create (full §4.1 pipeline);
               alerts of the same group reuse the group's open incident
   7 workflows AlertEvent RECEIVED → ALERT_RECEIVED trigger (new or reopened, unsuppressed alerts)
```

Responders acknowledge, resolve, suppress/unsuppress, assign, convert or link alerts (`/api/alerts/{id}/…`); bulk
actions (`POST /api/alerts/bulk`) acknowledge or resolve up to 100 alerts. Suppression is applied per alert
(`/api/alerts/{id}/suppress`), not as a bulk action.

### 4.4 Escalation through on-call

`AlertEscalationService` runs every `ALERT_ESCALATION_POLL_INTERVAL_MS` (60 s) on one instance (advisory lock 360008).
For each due OPEN alert it claims the next level with a conditional update on `escalation_level` (so every level fires
exactly once, even with several instances) and calls the applicable policy in the same transaction:

- `OnCallEscalation` (order 100) applies when the alert's routed team has an active schedule with an active escalation
  policy and the alert reaches the policy's `minSeverity`. Level *n* is due `levels[n-1].delayMinutes` after the previous
  level and pages the schedule's primary or secondary on call *at that moment* (`Rotation` with overrides, holiday cover
  and unavailability applied), the team lead plus the department manager (`MANAGER`), or a named user. An empty layer
  falls back to lead and manager. Pages are stored in `oncall_pages` (unique per alert, level and person) and notified as
  `ONCALL_PAGE` (e-mail channel on by default, so it also goes through the outbox when SMTP is configured).
- Otherwise `TeamLeadEscalationPolicy` (order 1000): when nobody acknowledges within `alert.escalation.ack_minutes` (15)
  it notifies the routed team's lead and the department manager (level 1; without a team the service department's
  managers, else the IT managers), and after the same time again the incident and IT managers (level 2), as
  `ALERT_ESCALATED`.

Acknowledging, resolving or suppressing clears `next_escalation_at`. History records `ESCALATED` with the policy name.

### 4.5 Change approval through the approval engine

1. A change is raised (`DRAFT`) by change raisers; incident managers may raise `EMERGENCY` changes only.
2. `STANDARD` (from a template): submission approves it. `NORMAL`: `SUBMITTED → ASSESSMENT` (risk questionnaire +
   computed factors → `risk` LOW/MEDIUM/HIGH) `→ AWAITING_APPROVAL`, which calls `ChangeService.requestApproval` with the
   system flow `CHANGE_NORMAL`. `EMERGENCY`: submission goes straight to `CHANGE_EMERGENCY` (expedited, PIR required).
3. `ApprovalService.requestFlow` materialises stages and tasks from the flow. `CHANGE_NORMAL` has a change-authority stage
   and a Change Advisory Board stage that is only activated when the approval's facts contain `changeRisk = HIGH`
   (conditional stage). Tasks are routed to users, roles or teams; standing delegations re-route personal tasks; the
   requester never decides their own approval.
4. Approvers decide through `/api/approvals/tasks/{taskId}/decision`. `ApprovalDeadlineScheduler` (advisory lock 360007)
   escalates stages past their deadline (notify, optional auto-reject).
5. On the final outcome the `CHANGE` handler (`ChangeApprovalHandler` → `ChangeService.onApprovalDecision`) moves the
   change to `APPROVED` or `REJECTED` in the decision transaction; manual transitions to those states are refused (`409`).
6. `APPROVED → SCHEDULED` checks conflicts and freeze windows (`change.freeze.enforce`), then implementation, outcome and
   the post-implementation review follow. Approved/scheduled changes inside their window and implementing changes mark
   their services `MAINTENANCE` via `ServiceMaintenanceSource`.

### 4.6 Major incident → post-incident review → approval

1. A major incident is declared by `POST /api/major-incidents`, `POST /api/incidents/{id}/major`, the automatic duplicate
   threshold, the workflow major-incident action or an approved AI action. Each path publishes `MajorIncidentEvent`
   **inside the incident transaction**, and `MajorIncidentService` opens the command record (`MI-###`) atomically with
   the flag (declaring admin/manager becomes incident commander; impacted service = the incident's service).
2. Command (admins, managers, the commander) assigns commander / technical lead / communications lead and moves the
   status `ACTIVE ⇄ MONITORING → RESOLVED → CLOSED`. Responders (staff) write the war-room timeline, stakeholder updates
   and action items; an update can be published to the public status page by status-page editors, the commander or the
   communications lead.
3. Closing (incident RESOLVED or CLOSED) opens the post-incident review (`PIR-###`, DRAFT, owner = commander) if none
   exists. `PirService` builds the timeline from incident activity, duplicate reports, alerts, on-call pages, war-room
   entries and stakeholder updates, and computes the metrics (time to detect / acknowledge / declare / resolve).
4. Submitting requires the analysis fields and at least one corrective or preventive action, and requests an approval of
   type `PIR` (one stage, roles `INCIDENT_MANAGER` and `IT_MANAGER`, quorum ANY, 72 h deadline escalating to system
   administrators). The PIR approval handler (`MajorExtensions`) sets `APPROVED` or `REJECTED` (with the review comment).

### 4.7 SA AI

- Every feature first computes its evidence, scores and probabilities from the database (deterministic engine:
  `FallbackEngine`, `OpsAiAdvisor` maths, `ChangeRisk`, statistics). With a provider configured and enabled the LLM may
  only select among and explain those candidates.
- Release 3 model calls go through `GuardedLlm.generate`: a hard timeout per attempt (`AI_TIMEOUT_SECONDS`), two
  attempts at most (one retry for provider errors, truncated or invalid output — never after a refusal or when no
  provider is configured), the feature's schema validation, and a content guard that rejects output containing SQL
  statements, shell/PowerShell/cloud-CLI/kubectl/Terraform commands or code fences. The system prompt marks record content
  as untrusted data and forbids inventing incidents, articles, people or metrics.
- Any failure returns the deterministic result labelled `FALLBACK` with its reason; results are never presented as
  model output unless they are. Predictive SLA risk is a statistic computed from historical clocks, labelled as such.
- Proposed data changes (priority escalation, major declaration, runbook execution) become approvals; nothing is applied
  without a human decision. `POST /api/ai/**` is rate-limited per user (`RATE_LIMIT_AI_PER_MINUTE`).

### 4.8 Notifications and e-mail

Notifications are written in the business transaction and pushed after commit over Server-Sent Events
(`/api/notifications/stream`, Bearer-authenticated, 25 s pings); the SPA falls back to 30 s polling. Per-user
preferences decide the in-app and e-mail channels per category. E-mail is never sent inline: `EmailService.queue` writes
an outbox row, `EmailOutboxProcessor` delivers it (§5). Without `MAIL_HOST` every message is stored as `SKIPPED` and
system health reports e-mail as not configured; nothing is reported as sent.

### 4.9 Public site and account access

Landing, `/docs`, `/support`, `/privacy`, `/terms` and the public `/status` page load neither the workspace shell nor
Recharts. Accounts stay administrator-controlled: access and reset requests always get the same `202` body; approval
issues a single-use, hashed, expiring link (invitation 7 days, reset 2 hours) that is shown once to the administrator and
also e-mailed when SMTP is configured.

## 5. Schedulers and cluster safety

All schedulers are Spring `@Scheduled` methods inside the API process (no external job runner). Those that must run on
one instance at a time take a PostgreSQL transaction-scoped advisory lock (`pg_try_advisory_xact_lock`); work queues use
row locks (`FOR UPDATE SKIP LOCKED`) or conditional updates so each item is processed once.

| Job | Interval (property / env) | Mutual exclusion |
|---|---|---|
| SLA evaluation (`SlaScheduler`) | `SLA_EVALUATION_INTERVAL_MS`, 60 s; off with `SLA_SCHEDULER_ENABLED=false` | advisory lock 360001 |
| Scheduled workflows (`WorkflowEngine`) | `WORKFLOW_SCHEDULE_POLL_INTERVAL_MS`, 60 s; off with `WORKFLOW_SCHEDULER_ENABLED=false` | advisory lock 360002 |
| Workflow retries (`WorkflowEngine.processDueRetries`) | `app.workflow.retry-poll-interval-ms`, 15 s | atomic `claimRetry` per execution |
| Scheduled reports (`ReportScheduler`) | `REPORT_POLL_INTERVAL_MS`, 60 s; off with `REPORT_SCHEDULER_ENABLED=false` | advisory lock 360006 + `FOR UPDATE SKIP LOCKED`; unique run per schedule and due time |
| Approval deadlines (`ApprovalDeadlineScheduler`) | `app.approvals.escalation-poll-interval-ms`, 60 s; `app.approvals.scheduler-enabled` | advisory lock 360007 |
| Alert escalation (`AlertEscalationService`) | `ALERT_ESCALATION_POLL_INTERVAL_MS`, 60 s; off with `ALERT_ESCALATION_ENABLED=false` | advisory lock 360008 + conditional claim on `escalation_level` |
| E-mail outbox (`EmailOutboxProcessor`) | `MAIL_POLL_INTERVAL_MS`, 30 s; off with `MAIL_OUTBOX_ENABLED=false`; purge of finished rows older than 30 days daily at 03:40 | `FOR UPDATE SKIP LOCKED` |
| Refresh-token clean-up (`RefreshTokenService.purgeExpired`) | daily at 03:15 (tokens expired more than 30 days ago) | idempotent delete |
| SSE keep-alive (`NotificationStreamHub`) | `app.notifications.ping-interval-ms`, 25 s | per instance (connections are local) |
| Simulation ticks (`SimulationScheduler`) | `app.simulation.tick-interval-ms`, 60 s; `app.simulation.scheduler-enabled` | advisory lock 360010 |
| Telemetry roll-ups & retention (`TelemetryMaintenanceService`) | roll-ups 60 s (lock 360011), retention daily at 02:20 (lock 360012) | advisory locks 360011, 360012 |
| Anomaly detection & Prometheus pull (`ObservabilityScheduler`) | anomalies 60 s (lock 360013), Prometheus pull 60 s (lock 360015) | advisory locks 360013, 360015 |
| Alert correlation (`CorrelationService`) | single worker thread (or inline in tests) | advisory lock 360014 |
| Prediction outcomes & retraining (`PredictionScheduler`) | outcome reconciliation hourly (lock 360017), retraining Sunday 02:35 (lock 360018); model activation uses lock 360016 | advisory locks 360016, 360017, 360018 |

Other advisory locks: alert deduplication (class 360009, per scope + fingerprint) and account links (namespace 3604, per
e-mail) serialise concurrent requests for the same key. Cron times use the server's default time zone (UTC in the
supplied container images is assumed, not verified on a deployment).

## 6. Data model (summary)

Flyway migrations `V1`–`V13` in `backend/src/main/resources/db/migration` own the schema; Hibernate only validates it.
Migrations are forward-only and applied ones are never edited. Highlights: foreign keys everywhere, `CHECK` constraints
for every enumerated value, optimistic locking (`version`) on mutable aggregates, partial indexes for queue paths,
`tsvector` + `pg_trgm` for search, sequences for human-readable numbers (`INC-`, `REQ-`, `PRB-`, `CHG-`, `MI-`, `PIR-`,
alert and asset numbers), JSONB for definitions/forms/criteria, `is_demo` on seeded data, `simulated` on simulation-mode
records, the append-only `audit_logs` table protected by `trg_audit_append_only`, and the SHA-256 hash-chained
`remediation_audit` table protected by `trg_remediation_audit_immutable`. Full reference: [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md).

## 7. Security model (summary)

JWT access tokens (15 min, in memory) plus rotating HttpOnly refresh cookies with reuse detection and per-sign-in
sessions; CSRF header on the two cookie endpoints; per-IP and per-user rate limits; server-side RBAC with URL gates in
`SecurityConfig` and `@PreAuthorize` groups from `Roles`, plus record-level checks in the services; validated input,
allow-listed uploads, SSRF guard and signed webhooks, AES-256-GCM secret storage, append-only audit and hash-chained
remediation audit. Details and the 11-role × module permission matrix: [SECURITY.md](SECURITY.md).

## 8. Frontend (`frontend/src`)

| Path | Contents |
|---|---|
| `main.tsx`, `App.tsx` | Bootstrap; routes. Every page is a `React.lazy` import (including `AppShell` and the sign-in page), so the public landing page does not download the workspace, forms library or Recharts. Workspace routes sit under the shell and are wrapped in `Guard` with `rolesFor(path)`; Release 1 paths redirect through `LegacyRedirect` |
| `lib/nav.ts` | The grouped, role-aware sidebar (RELEASE3_SPEC §3, RELEASE4_SPEC §3): `NAV_GROUPS` with eight groups — WORKSPACE, SERVICE MANAGEMENT, OPERATIONS, AUTONOMOUS OPERATIONS, ENTERPRISE, INTELLIGENCE, GOVERNANCE, ADMINISTRATION. All 36 sidebar leaves have `available: true`. `navForRole`, `rolesFor` (route guards derive roles from the nav so sidebar and guards cannot drift), `activeLeaf`, `pageLabelFor` (`SA | <page>` titles) and page descriptions |
| `lib/constants.ts` | Role groups mirrored from `Roles.java` (display only; the API enforces) |
| `lib/api.ts` | Axios client, in-memory access token, single-flight refresh on `401` with `X-Requested-With: nexus360`, typed `ApiError` |
| `lib/types.ts`, `itsmTypes.ts`, `opsTypes.ts`, `r4Types.ts`, `accountTypes.ts`, `responseTypes.ts` | API contract types; `schemas.ts`, `accountSchemas.ts`, `dynamicForm.ts` mirror server validation |
| `api/*.ts` | TanStack Query hooks per module (cache keys, invalidation after mutations) — one file per backend area (incidents, alerts, assets, cmdb, changes, problems, deployments, approvals, forms, teams, reports, sessions, statusPage, workspace, observability, twin, predictions, agents, remediation, security, finops, dex …) |
| `components/` | Design-system primitives (badges incl. `SimulatedBadge`, panels, modals, drawers, tables, form fields, states, KPI cards, charts), `layout/` (shell with `SimulationBanner`, sidebar from `navForRole`, topbar with global search and notifications), and feature folders (`alerts`, `cmdb`, `forms` incl. `FormBuilder`, `itsm`, `ops`, `oncall`, `profile` incl. sessions, `analytics` incl. scheduled reports, `sim`, `twin`, …) |
| `pages/` | One page per route, grouped by module (`incidents/`, `problems/`, `changes/`, `deployments/`, `alerts/`, `cmdb/`, `assets/`, `approvals/`, `teams/`, `status/`, `workspace/` (My Work, Portal with DEX card), `observability/`, `twin/`, `predictions/`, `agents/`, `remediation/`, `security/`, `finops/`, `dex/`, `public/`, `auth/`, `settings/` tabs incl. `SimulationTab`, …) |
| `hooks/` | `useAuth`, `useNotificationStream` (SSE with polling fallback), `usePageMeta`, `useUrlState`, … |
| `public/brand/` | Logo set: `sa-logo.svg`, `sa-logo-light.svg`, `sa-logo-dark.svg`, `sa-mark.svg`, `favicon.svg`, PNG icons 180/192/512, `site.webmanifest` |

All 36 sidebar modules across Releases 1–4 are routed and `available: true`.

Visual identity: obsidian `#101012/#171618/#1D1B1A/#25211D`, champagne gold `#D6B77A/#F1DFBA`, bronze `#96764B`, ivory
`#F5F1EA`, muted `#A7A19A` (from `styles.css`, carried over from the original package).

## 9. Testing

| Layer | Tooling | Scope |
|---|---|---|
| Backend unit | JUnit 5, Mockito, AssertJ | priority/classification, duplicate scoring, business-time maths, SLA engine, conditions, rotations, risk and statistics, AI fallback, provider and guard, SSRF guard, webhook signature verification with sample payloads, utilities |
| Backend integration | Spring Boot Test + MockMvc + embedded PostgreSQL 17 (zonky), GreenMail | auth, sessions and RBAC, migrations on earlier data, incident lifecycle, service desk and approval engine, problems, changes, deployments, assets, CMDB, alerts, on-call, major incidents and PIRs, workflows, AI, e-mail outbox, reports, payroll outage end-to-end |
| Frontend unit | Vitest + Testing Library | API client, guards and navigation, forms, pages and components |
| End-to-end | Playwright (Chromium) | landing, sign-in, access requests and resets, navigation and role boundaries, incident form, payroll-outage scenario (`frontend/e2e/*.spec.ts`) |

Current counts and what has been verified are recorded in [PROJECT_STATUS.md](PROJECT_STATUS.md) only.

## 10. Deployment topology

Vercel serves the static build and rewrites `/api/*` to the Render web service (Docker), which talks to Neon PostgreSQL
over TLS; Redis, an LLM provider and SMTP are optional. Locally the same topology runs with `docker compose up` (nginx
proxies `/api`) or without Docker using the embedded-PostgreSQL launcher. See [DEPLOYMENT.md](DEPLOYMENT.md); nothing is
deployed yet ([DEPLOYMENT_STATUS.md](DEPLOYMENT_STATUS.md)).
