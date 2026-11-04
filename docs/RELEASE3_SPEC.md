# SA — Release 3 specification (enterprise ITSM / ITOM)

Source: the "SA – Intelligent Enterprise Service Management & Operations Platform" master brief (4 Nov 2026).
Release 3 **extends** the verified Release 2.1 codebase (PROJECT_STATUS.md §R2.1); nothing working is rebuilt or
removed. Earlier specs stay valid unless changed here: docs/RELEASE2_SPEC.md, docs/PUBLIC_ACCESS_SPEC.md.
Reference image for the public site: `C:\Users\Ananthapadmanabhan\.claude\uploads\19c94e11-ffbc-4b07-bf2c-0ca13ff71869\832091d7-image.png`.

Ground rules (unchanged): real persisted data only; AI output labelled by source (LLM vs deterministic fallback);
no AI-generated SQL or infrastructure commands; human confirmation for sensitive actions; server-side authorisation
on every endpoint; generic public responses; no secrets in code or the frontend; seeded data flagged `demo`; never
claim an integration, certification or metric that is not implemented and verified.

## 0. Current state (inspection, 4 Nov 2026)

Working and tested: auth (JWT + rotating refresh cookie, lockout, rate limits, access requests, invitations, reset
links), roles SYSTEM_ADMIN…AUDITOR, incidents (intake pipeline, duplicates/parents, state machine, SLA engine,
escalation, attachments, activity), service desk + catalogue + approvals (single step, role-routed), services with
dependencies, knowledge (review, versions, ratings), runbooks (sensitive steps), workflows (visual builder, branches,
scheduled), SA AI (classification, summaries, duplicates, KB, root cause, resolution, workflow suggestion, insights,
ask), analytics (KPIs, FCR, PDF/CSV), notifications (in-app + SSE + preferences), integrations (webhook/Slack/Teams/
inbound monitoring), audit (append-only), settings, landing page + public pages. Flyway V1–V5. Tests: backend 152,
Vitest 411, Playwright 21.

Gaps against this brief: product name "SA 360" → "SA"; no logo set; flat sidebar; no Problems, Changes,
Deployments, Assets, CMDB, Alerts, On-call, Major Incident command, PIR, Status Page, Teams, My Work, Employee Portal
page, form builder (forms are fixed field lists), multi-step approval engine, email delivery, session management,
scheduled reports, CHANGE_MANAGER / ASSET_MANAGER roles, MTTA/MTTD/change/problem/alert/asset/on-call metrics.

## 1. Branding

- Product name **SA**; subtitle **Enterprise Operations Platform**; tagline **Intelligent Service Management. Unified
  Operations.**; AI module **SA AI**. Replace every user-facing "SA 360" with "SA" (UI, titles `SA | <page>`, metadata,
  e-mails, PDFs/CSVs, OpenAPI title, health service name "SA API", docs). Migration renames the default organisation
  name `SA 360` → `SA` when unchanged.
- Logo set in `frontend/public/brand/`: `sa-logo.svg` (primary wordmark, for dark backgrounds), `sa-logo-light.svg`
  (for light backgrounds), `sa-logo-dark.svg` (explicit dark variant), `sa-mark.svg` (square monogram / app icon),
  `favicon.svg`, PNG icons 180/192/512 px rendered from the SVG (e.g. via Playwright screenshot), `site.webmanifest`.
  Minimal, original wordmark in champagne gold (`#D6B77A` → `#F1DFBA`), no imitation of other brands. The in-app
  `BrandMark` and the loading screen (`BrandLoader`) use the same mark.
- Unchanged technical identifiers (backward compatibility): Java package `com.nexus360`, repo folder, artifact
  `nexus360-api`, cookie `nexus_refresh`, header `X-Requested-With: nexus360`, `X-Nexus-*` webhook headers, demo
  domain `demo.nexus360.io`, API paths (`/api/...`), Render service `nexus360-api`, DB tables.

## 2. Roles and permissions

Add **CHANGE_MANAGER** (changes, change calendar, CAB approvals, deployments, releases) and **ASSET_MANAGER** (assets,
CMDB, software licences, import/export). Both are staff (work tickets, see operational data). Existing roles keep
their powers. New groups in `Roles` / `lib/constants.ts`:

- `STAFF` += CHANGE_MANAGER, ASSET_MANAGER; `READERS` follows `STAFF` + AUDITOR
- `CHANGE_EDITORS` = ADMINS + IT_MANAGER + CHANGE_MANAGER (create/edit changes; incidents managers may raise emergency changes)
- `CHANGE_APPROVERS` (CAB) = SYSTEM_ADMIN + IT_MANAGER + CHANGE_MANAGER
- `DEPLOYMENT_EDITORS` = ADMINS + CHANGE_MANAGER
- `ASSET_EDITORS` = ADMINS + ASSET_MANAGER; `CMDB_EDITORS` = ADMINS + ASSET_MANAGER + IT_MANAGER
- `PROBLEM_EDITORS` = ADMIN_OR_MANAGER + SUPPORT_AGENT (agents create/work problems); `PROBLEM_OWNERS_CLOSE` = ADMIN_OR_MANAGER
- `ALERT_RESPONDERS` = STAFF; `ALERT_CONFIG` = ADMINS (suppression/routing rules)
- `ONCALL_EDITORS` = ADMINS + IT_MANAGER + INCIDENT_MANAGER; everyone staff can view and take overrides offered to them
- `MAJOR_INCIDENT_COMMAND` = ADMIN_OR_MANAGER (declare, assign roles); responders = STAFF
- `PIR_APPROVERS` = SYSTEM_ADMIN + IT_MANAGER + INCIDENT_MANAGER
- `STATUS_PAGE_EDITORS` = ADMINS + INCIDENT_MANAGER; `REPORT_SCHEDULERS` = REPORT_EXPORTERS
- `TEAM_ADMINS` = USER_ADMINS + IT_MANAGER (managers maintain their teams)

Document the full matrix in `SECURITY.md` (written at the end from the code).

## 3. Navigation (role-aware, grouped; every item a real page)

| Group | Item → route |
|---|---|
| WORKSPACE | Command Center `/dashboard` · My Work `/my-work` · Employee Portal `/portal` |
| SERVICE MANAGEMENT | Service Desk `/service-desk` · Service Catalogue `/catalog` · Incidents `/incidents` · Major Incidents `/major-incidents` · Problems `/problems` · Changes `/changes` |
| OPERATIONS | Alerts `/alerts` · On-call `/on-call` · Services `/services` · CMDB `/cmdb` · Assets `/assets` · Deployments `/deployments` |
| INTELLIGENCE | SA AI `/sa-ai` · Workflows `/automation` · Runbooks `/runbooks` · Knowledge Base `/knowledge` |
| GOVERNANCE | SLA Monitor `/sla-monitor` · Approvals `/approvals` · Analytics `/analytics` · Audit Logs `/audit-logs` · Status Page `/status-page` |
| ADMINISTRATION | Users `/users` · Teams `/teams` · Integrations `/integrations` · Settings `/settings` |

Detail routes: `/problems/:id`, `/changes/:id`, `/changes/calendar` (tab ok), `/major-incidents/:id`,
`/reviews/:id` (post-incident review), `/alerts/:id`, `/on-call/:scheduleId`, `/cmdb/:id`, `/assets/:id`,
`/deployments/:id`. `/catalog` becomes a real page again (the Service Desk "Service Catalog" tab may link to it).
Employees see WORKSPACE (My Work hidden), Service Desk, Service Catalogue, Incidents, Knowledge Base, Status Page
(read-only). Groups collapse; the active group is expanded; the shell keeps the Release 2 visual style.

Public routes: `/`, `/login`, `/register`, `/forgot-password`, `/reset-password`, `/privacy`, `/terms`, `/status`
(public status page, only when enabled; otherwise a friendly "not published" page), plus existing `/docs`, `/support`.
Landing: nav gains "Documentation"; capabilities become the 12 listed in the brief; preview adds service health and
operational activity; footer adds About SA and Contact (`/support#contact`).

## 4. Cross-cutting backend foundations (Agent BE-1, migration **V6**)

- **Teams**: `teams` (code, name, department, lead, e-mail, description, active), `team_members` (team, user,
  `MEMBER|LEAD`). Incidents, service requests, problems, changes, alerts, on-call schedules reference `team_id`.
  Assignment picks a team (service → default team, else department's default team) then the least-loaded member.
  Workflow condition `teamCode`, action `ASSIGN_TEAM` (params.teamCode) next to existing department actions.
- **E-mail**: optional SMTP (`spring-boot-starter-mail`; `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`,
  `MAIL_FROM`, `MAIL_STARTTLS`, `APP_PUBLIC_URL` for links). `email_outbox` (to, subject, html, text, status
  `PENDING|SENT|FAILED|SKIPPED`, attempts, next_attempt_at, last_error) drained by a scheduler with retries and
  backoff; when SMTP is not configured messages are recorded as `SKIPPED` and health says "not configured" — never
  reported as sent. Branded HTML templates (escaped content, inline CSS, SA wordmark as text). Notification
  preferences gain an `email` channel per type (default off except approvals and assignments). Invitation and reset
  links are also e-mailed to the recipient when SMTP is configured (admins still see the link once).
- **Sessions**: login `rememberMe` (true → persistent cookie for `REFRESH_TOKEN_DAYS`; false → browser-session cookie
  and 12 h server expiry). `GET /api/auth/sessions` (own: device/user agent, IP, created, last used, current),
  `DELETE /api/auth/sessions/{id}`, `POST /api/auth/sessions/revoke-others`; admins `POST /api/users/{id}/sessions/revoke`.
- **My Work**: `GET /api/my-work` → `{ incidents, teamQueue, requests, approvals, ...}` with extension points; later
  agents add `problems`, `changes`, `alerts`, `onCallShifts`, `actionItems`.
- **Employee portal**: `GET /api/portal/overview` (my open incidents/requests, approvals I'm waiting for, service
  status summary, recommended articles); employee data isolation re-verified by tests.
- **Status page**: `status_page_settings` (title, public enabled, description), `status_components` (business service,
  display name, group, public), `maintenance_windows` (title, description, components, start/end, status
  `SCHEDULED|IN_PROGRESS|COMPLETED|CANCELLED`), `status_updates` (optional incident link, title, status
  `INVESTIGATING|IDENTIFIED|MONITORING|RESOLVED`, message, public flag, posted by/at), `status_subscribers` (e-mail,
  confirm token hash, confirmed_at, unsubscribe token hash). Public `GET /api/public/status` (404 when not public),
  `POST /api/public/status/subscribe`, confirm/unsubscribe endpoints (generic responses); internal
  `GET /api/status-page`; editors manage components, maintenance and updates; daily uptime history (90 days) from
  service availability; subscribers notified via the outbox.
- **Scheduled reports**: `report_schedules` (name, report type `ANALYTICS_OVERVIEW|SLA|INCIDENTS|CHANGES|PROBLEMS|
  ASSETS|ONCALL`, format `PDF|CSV`, frequency `DAILY|WEEKLY|MONTHLY`, filters JSON, recipients, owner, next_run_at,
  last_run_at, active) + `report_runs` (file bytea, status, error). Runs are downloadable and e-mailed when SMTP is
  configured. Report types the later modules add plug into a `ReportGenerator` registry.
- **Branding** in backend strings (§1) and roles (§2); `V6` renames `org.name`.

## 5. Module agents

Each backend agent owns one migration number, adds its demo data (flagged), tests, `docs/API.md` sections,
notification types, audit events, workflow trigger/condition/action extensions, My Work / Command Center / analytics
contributions, and AI extensions for its domain. Frontend agents build the pages against `docs/API.md`.

### BE-2 (migration **V7**): approval engine v2, form builder v2, Problems, Changes, Deployments
- **Approval engine v2** (backward compatible with existing approvals, handlers and endpoints): approval *flows* with
  ordered stages; stage mode `SEQUENTIAL|PARALLEL`; approver kinds user / role / team; quorum `ANY|ALL|N`;
  conditional stages (rules on the subject, e.g. change risk HIGH adds a CAB stage); deadlines with escalation
  (notify approver's manager / role, optional auto-reject), rejection reasons, delegation, full history. `/approvals`
  inbox shows tasks for me across subject types (service requests, runbooks, runbook steps, AI actions, changes, PIRs).
  Workflow action `REQUEST_APPROVAL`.
- **Dynamic form builder**: versioned form definitions: text, textarea, number, email, date, select, multi-select,
  checkbox, file upload (stored as request attachments via the existing secure upload rules), user picker;
  required/min/max/pattern validation; conditional visibility (`showIf` field/op/value); templates; custom request
  types with permissions (roles/departments allowed to submit). Server-side validation is authoritative. Catalogue
  items use form definitions; existing items are migrated.
- **Problems** (`PRB-####`): status `NEW→INVESTIGATING→ROOT_CAUSE_IDENTIFIED→KNOWN_ERROR→RESOLVED→CLOSED`, priority,
  owner, team, services/CIs, linked incidents, root cause, workaround, permanent fix, known-error record (optionally
  published as a KB KNOWN_ERROR article), corrective actions (owner, due, status), recurring-incident detection
  (clusters of similar incidents without a problem → suggestions), problem analytics, AI root-cause suggestions.
- **Changes** (`CHG-####`): types `STANDARD` (from pre-approved templates), `NORMAL` (CAB), `EMERGENCY` (expedited
  approval, post-implementation review); status `DRAFT→SUBMITTED→ASSESSMENT→AWAITING_APPROVAL→APPROVED→SCHEDULED→
  IMPLEMENTING→COMPLETED|FAILED|ROLLED_BACK`, plus `REJECTED|CANCELLED`; risk assessment questionnaire + computed
  score (+ AI change-risk analysis, labelled), impact assessment (affected services/CIs, users), implementation /
  rollback / test plans, planned and actual windows, change calendar with conflicts and freeze windows, history,
  success metrics, incident correlation (incidents on affected services within N hours after implementation).
  Workflow trigger `CHANGE_REQUEST_CREATED`, condition `changeRisk`.
- **Deployments**: releases and deployment records (environment `DEV|TEST|STAGING|PRODUCTION`, service, version,
  commit, pipeline URL, status `IN_PROGRESS|SUCCEEDED|FAILED|ROLLED_BACK`, linked change, actor), deployment calendar,
  incident correlation, risk analysis, rollback tracking; CI/CD webhook ingestion for GitHub (`workflow_run`,
  `deployment_status`, `X-Hub-Signature-256` HMAC), GitLab (`pipeline`/`deployment`, `X-Gitlab-Token`), and a generic
  signed JSON format — each verified by tests with real sample payloads; unknown/unsigned requests rejected.

### BE-3 (migration **V8**): Assets, CMDB, Alert management
- **Assets**: hardware/software inventory (laptops, desktops, servers, network, mobile, licences with seats),
  assignment/ownership, location, purchase (vendor, PO, cost, date), warranty, maintenance history, lifecycle events
  (`IN_STOCK→IN_USE→IN_REPAIR→RETIRED→DISPOSED`), CSV import (validated, row-level errors, dry run) and export.
- **CMDB**: configuration items (types APPLICATION, SERVER, DATABASE, NETWORK_DEVICE, CLOUD_RESOURCE,
  OPERATING_SYSTEM, SOFTWARE, BUSINESS_SERVICE, INFRASTRUCTURE), attributes JSON, owner/team, environment, status,
  history; relationships (`DEPENDS_ON|RUNS_ON|HOSTS|CONNECTS_TO|PART_OF`) with cycle-safe traversal; impact analysis
  (upstream/downstream, impacted business services and open incidents); graph endpoint for a visual dependency map;
  existing business services get a BUSINESS_SERVICE CI each (migration); assets can link to CIs.
- **Alerts**: ingestion API, inbox, severity, acknowledge/resolve, fingerprint deduplication with counts, grouping
  window, suppression rules, routing rules (service/CI/labels → team), alert→incident conversion (manual and by rule),
  escalation hook for on-call (BE-4 completes it), history. Adapters (unit-tested with real sample payloads):
  Prometheus Alertmanager webhook, Grafana alerting webhook, AWS CloudWatch via SNS (subscription confirmation +
  signature verification with a host allow-list), plus the existing generic inbound format. Workflow trigger
  `ALERT_RECEIVED`.

### BE-4 (migration **V9**): On-call, Major incident command, Post-incident reviews, analytics & AI extensions
- **On-call**: schedules per team (time zone, rotation `DAILY|WEEKLY|CUSTOM`, handoff time, ordered participants,
  primary/secondary layers), overrides, holiday calendars, availability, "who is on call now/when", escalation
  policies (levels with delays: primary → secondary → manager), handover notes, notifications; alert escalation uses
  the policy.
- **Major incident command**: declare on an incident (existing major flag) → command record: incident commander,
  technical lead, communications lead, war-room timeline (notes, decisions), stakeholder updates (internal; optionally
  published to the status page), impacted services, action items (owner, due, status), investigation notes,
  resolution tracking, close → PIR.
- **Post-incident reviews**: auto-built timeline from activity/SLA/alerts, root cause, contributing factors, impact,
  detection gaps, response analysis, corrective and preventive actions (owners, due dates, status), approval via the
  approval engine, PDF export.
- **Analytics**: MTTA (created → first response), MTTD (first related alert → incident created, else n/a),
  first-contact resolution, change success/failure rate, problem recurrence, alert volume, asset lifecycle, on-call
  performance (ack time), department performance, service availability, custom date filters, metric definitions
  endpoint; Command Center adds open/resolved/breaches, MTTA, active alerts, on-call now, change activity, workflow
  executions, department performance, recent activity.
- **SA AI**: problem correlation, change risk, CMDB impact analysis, runbook recommendations, predictive SLA risk
  (computed probability from elapsed share and historical durations, labelled), trend analysis — all with structured
  output validation, timeouts, retries, rate limits and the honest fallback.

## 6. Migration numbers, ownership and order

| Agent | Migration | Starts after |
|---|---|---|
| BE-1 foundations | V6 | now |
| BE-2 approvals/forms/problems/changes/deployments | V7 | BE-1 |
| BE-3 assets/CMDB/alerts | V8 | BE-2 |
| BE-4 on-call/major/PIR/analytics/AI | V9 | BE-3 |

Backend agents run one at a time (shared Maven build). Frontend agents run in parallel with the next backend agent,
building against the finished backend's `docs/API.md` section. Never edit an applied migration.
