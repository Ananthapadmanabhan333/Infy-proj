# SA — Project Status

**SA — Enterprise Operations Platform** · *Intelligent Service Management. Unified Operations.*

_This file is updated as work progresses. The "Inspection baseline" section records the state of the supplied ZIP (`NEXUS360-Deployment-Package.zip`, git commit `cbedb49`) and is kept unchanged for traceability. Nothing in this file claims a test pass, metric, certification or deployment that was not observed; unverified items are marked as such._

## Release 4 — Autonomous Enterprise Operations (status 5 Nov 2026)

Specification: [docs/RELEASE4_SPEC.md](docs/RELEASE4_SPEC.md) · phases: [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md)
· documentation: [ARCHITECTURE.md](ARCHITECTURE.md), [API_DOCUMENTATION.md](API_DOCUMENTATION.md),
[DATABASE_SCHEMA.md](DATABASE_SCHEMA.md), [DEPLOYMENT.md](DEPLOYMENT.md), [SECURITY.md](SECURITY.md).

Integrated and verified locally on 5 Nov 2026 (embedded PostgreSQL, fresh demo database migrated V1–V13, production
frontend build). Not deployed — deployment is left to the owner; see [DEPLOYMENT.md](DEPLOYMENT.md).

### Modules delivered

Legend: ✅ done in code · ⏳ in progress · ⬜ not started.

| # | Module (RELEASE4_SPEC section) | Backend | Frontend | Notes |
|---|---|---|---|---|
| 1 | Simulation Mode & Synthetic Scenarios (§1) | ✅ V10–V13, 6 scenarios (`PAYROLL_DB_SATURATION`, `IDENTITY_OUTAGE`, `BAD_DEPLOYMENT`, `VULN_WAVE`, `COST_SPIKE`, `ENDPOINT_DEGRADATION`), `DELETE /api/simulation/data` | ✅ Topbar simulation banner, Settings → Simulation Mode, `SIMULATION` badges, `IncludeSimulatedToggle` | Simulated rows excluded from Release 1–3 aggregates by default |
| 2 | Roles `SECURITY_ANALYST`, `FINOPS_ANALYST` & role groups (§2) | ✅ `Roles.java`, V10, URL gates + `@PreAuthorize` | ✅ `lib/constants.ts`, `lib/nav.ts` | Matrix in SECURITY.md |
| 3 | Grouped navigation (`AUTONOMOUS OPERATIONS`, `ENTERPRISE`) (§3) | — | ✅ `lib/nav.ts` | All 36 sidebar items live (`available: true`); 12 capability cards on landing page |
| 4 | Full-Stack Observability & AIOps (§4.1–§4.2) | ✅ V10, OTLP/HTTP JSON metrics/logs/traces, mapping rules, rollups, retention, EWMA/MAD anomaly detectors + evaluation, alert correlation, Prometheus pull adapter | ✅ `/observability` (Services/RED, Metrics, Logs, Traces, Anomalies, Correlation, Ingestion), additive `correlationGroupId` on alerts/incidents | Prepared adapters remain `NOT_CONNECTED` until verified |
| 5 | Digital Twin of IT Operations (§4.2) | ✅ V10, 3-layer graph (`BUSINESS`, `APPLICATION`, `INFRASTRUCTURE`), business-service tree, read-only failure/change simulation, change timeline | ✅ `/digital-twin` (Model, Services, Simulate, Changes) | Persisted simulations labelled `Simulation — no real systems were changed` |
| 6 | Predictive Intelligence (§4.3) | ✅ V11, logistic regression + ridge regression + EWMA/Holt-Winters, time-based split, AUC/Brier/10-bin calibration, MAE/MAPE/coverage, drift warning, outcome recording | ✅ `/predictions` (Overview, Models, Live Performance, Serving & Features), SLA risk fallback preserved | Returns `INSUFFICIENT_DATA` below sample minimums |
| 7 | Agentic AI Architecture (§4.4) | ✅ V11, 6 specialist agents (`INCIDENT`, `ROOT_CAUSE`, `CHANGE_RISK`, `KNOWLEDGE`, `INFRA_INVESTIGATION`, `RESOLUTION`), read-only tools as requesting user, bounded LLM loop + deterministic fallbacks, `AI_ACTION` approvals, evaluation suite, governance | ✅ `/agents` (Run, Timeline, Proposed Actions, Policies, Evaluation, Governance) | Runs without LLM key via labelled `FALLBACK` engine |
| 8 | Autonomous Remediation Engine (§4.5) | ✅ V12, scoped execution systems + allow-listed action templates, `SIMULATED` connector, versioned playbooks + approval engine (`PLAYBOOK`), 7 policy checks, reverse-order rollback, SHA-256 hash-chained `remediation_audit` with DB trigger + `/audit/verify` | ✅ `/remediation` (Playbooks, Runs, Systems, Tamper-Evident Audit) | Shell/free-form commands rejected |
| 9 | Security Operations Integration (§4.6) | ✅ V12, security incidents + tasks, multi-format vulnerability ingestion (`GENERIC_JSON`, `SARIF`, `TRIVY_JSON`, `CSV`) with dedupe & full-scan auto-close, documented risk score (`0–100`), risk acceptance approval (`VULNERABILITY_ACCEPTANCE`), correlation | ✅ `/security` (Overview, Security Incidents, Vulnerability Findings & Ingestion, Correlation Groups, Templates) | |
| 10 | FinOps & Cloud Cost Intelligence (§4.7) | ✅ V13, FOCUS 1.x CSV & mapped CSV ingestion with dry-run validation, priority-ordered allocation rules, utilisation & idle detection, EWMA/MAD cost anomalies, monthly budgets + linear EOM forecast, savings recommendations → draft Change Request | ✅ `/finops` (Overview & Allocation, CSV Ingestion, Budgets, Anomalies, Recommendations) | Prepared cloud billing adapters (`AWS_CUR`, `AZURE_COST`, `GCP_BILLING`) |
| 11 | Digital Employee Experience (DEX) (§4.8) | ✅ V13, endpoint health, app RUM events, login/SSO telemetry, documented composite formula (`0.35 * deviceHealth + 0.25 * appPerformance + 0.20 * loginSuccess + 0.20 * ticketSignals`), service experience analytics, opt-in proactive rules, `/api/dex/me` | ✅ `/dex` (Score, Endpoints, Apps, Services, Proactive Rules) + Employee Portal `My Device Health (DEX)` card | |

### Test results (Release 4)

| Suite | Result | Status |
|---|---|---|
| Backend (`mvn -B test`, embedded PostgreSQL 17, V1–V13) | **346 tests, 0 failures, 0 errors** | Executed 5 Nov 2026: `mvn -B test` — BUILD SUCCESS |
| Frontend unit (Vitest) | **700 tests in 88 files, all passing** | Executed 5 Nov 2026: `npx vitest run` |
| Frontend type check + production build | `npx tsc -p tsconfig.app.json --noEmit` clean; `npm run build` succeeds | Executed 5 Nov 2026 |
| End-to-end (Playwright, fresh database V1–V13) | **30 passed, 0 failed** | Executed 5 Nov 2026 against `LocalDevApplication` on a fresh database: `npx playwright test` (includes `navigation.spec.ts` covering all 36 routes + `SECURITY_ANALYST` / `FINOPS_ANALYST` role boundaries, and `release4.spec.ts` covering simulation → observability → twin → agents → remediation rollback & hash-chain verify → security vulnerability ingestion → FinOps dry-run & apply → DEX score + 375 px mobile viewport + Employee Portal DEX card) |

### Known limitations (Release 4)

- External cloud/monitoring/billing/execution connectors (Grafana, CloudWatch, Azure Monitor, GCP Monitoring, AWS CUR, Azure Cost Management, GCP Billing Export, Kubernetes, Ansible AWX, AWS SSM, HTTP Webhook) are prepared and remain `NOT_CONNECTED` until configured with real credentials and verified with a live call; `SIMULATED` connectors, OTLP/HTTP JSON push, multi-format vulnerability push, FOCUS/mapped CSV cost ingestion, and JSON endpoint/app/login DEX ingestion work out of the box.
- Predictive models require minimum historical sample counts (50 for classification, 30 for regression, 14 daily points for time-series) and return `INSUFFICIENT_DATA` on a fresh database until enough non-simulated records exist (or when trained explicitly with `includeSimulated = true`, which flags the model as simulated).
- Without an `LLM_API_KEY`, SA Agents execute using the deterministic `FALLBACK` engine and label every run and step `FALLBACK`.
- Still not deployed — see [DEPLOYMENT_STATUS.md](DEPLOYMENT_STATUS.md).

---

## Release 3 — SA enterprise ITSM / ITOM (status 4 Nov 2026)

Specification: [docs/RELEASE3_SPEC.md](docs/RELEASE3_SPEC.md) · phases: [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md#release-3--sa-enterprise-itsm--itom-started-4-nov-2026)
· documentation: [ARCHITECTURE.md](ARCHITECTURE.md), [API_DOCUMENTATION.md](API_DOCUMENTATION.md),
[DATABASE_SCHEMA.md](DATABASE_SCHEMA.md), [DEPLOYMENT.md](DEPLOYMENT.md), [SECURITY.md](SECURITY.md).

Integrated and verified locally on 4 Nov 2026 (embedded PostgreSQL, fresh demo database migrated V1–V9, production
frontend build). Not deployed — deployment is left to the owner; see [DEPLOYMENT.md](DEPLOYMENT.md).

### Modules delivered

Legend: ✅ done in code · ⏳ in progress · ⬜ not started. "Backend" = migration, API and tests written by the backend
agent; "Frontend" = routed page(s) on live data. Neither column means "verified end to end" — see the test table.

| # | Module (RELEASE3_SPEC section) | Backend | Frontend | Notes |
|---|---|---|---|---|
| 1 | Branding "SA" and logo set (§1) | ✅ V6, user-facing strings, `SA API` | ✅ `public/brand/*`, brand mark, `SA \| <page>` titles | Technical identifiers unchanged on purpose |
| 2 | Roles `CHANGE_MANAGER`, `ASSET_MANAGER` and new role groups (§2) | ✅ `Roles.java`, V6 | ✅ `lib/constants.ts` | Matrix in SECURITY.md |
| 3 | Grouped, role-aware navigation (§3) | — | ✅ `lib/nav.ts` | All 28 sidebar items are live pages; the public docs/landing module count is tied to `nav.ts` by tests |
| 4 | Public site updates and public `/status` page (§3) | ✅ public status API | ✅ `pages/public/StatusPage` | Covered by the Playwright landing and Release 3 specs |
| 5 | Teams and team routing (§4) | ✅ V6 | ✅ `/teams` | |
| 6 | E-mail outbox and SMTP delivery (§4) | ✅ V6 | ✅ Settings → E-mail | `SKIPPED` without SMTP; no real SMTP server was used here |
| 7 | Session management / "remember me" (§4) | ✅ V6 | ✅ Profile → sessions | |
| 8 | My Work (§4) | ✅ + module contributions | ✅ `/my-work` | |
| 9 | Employee portal (§4) | ✅ | ✅ `/portal` | |
| 10 | Status page administration (§4) | ✅ V6 | ✅ `/status-page` | |
| 11 | Scheduled reports (§4) | ✅ V6, all 7 report types | ✅ Analytics → scheduled reports | |
| 12 | Approval engine v2 (§5 BE-2) | ✅ V7 | ✅ `/approvals` inbox | No editor page for approval **flows** yet (API only) |
| 13 | Dynamic form builder (§5 BE-2) | ✅ V7 | ✅ `FormBuilder` (catalogue) | |
| 14 | Problems (§5 BE-2) | ✅ V7 | ✅ `/problems`, `/problems/:id` | |
| 15 | Changes, change calendar, freeze windows, CAB (§5 BE-2) | ✅ V7 | ✅ `/changes`, calendar, freeze windows, detail | |
| 16 | Releases and deployments (§5 BE-2) | ✅ V7 | ✅ `/deployments`, `/deployments/:id` | |
| 17 | CI/CD webhooks — GitHub, GitLab, generic HMAC (§5 BE-2) | ✅ V7, tested with sample payloads | ✅ webhook sources panel | Not exercised against a real GitHub/GitLab project |
| 18 | Assets and licences, CSV import/export (§5 BE-3) | ✅ V8 | ✅ `/assets`, `/assets/:id` | |
| 19 | CMDB and impact analysis (§5 BE-3) | ✅ V8 | ✅ `/cmdb`, `/cmdb/:id` | |
| 20 | Alert management — dedupe, suppression, routing, conversion (§5 BE-3) | ✅ V8 | ✅ `/alerts`, `/alerts/:id` | |
| 21 | Monitoring adapters — Prometheus, Grafana, CloudWatch via SNS (§5 BE-3) | ✅ V8, tested with sample payloads | ✅ alert source configuration | Not exercised against real monitoring systems |
| 22 | On-call schedules, overrides, escalation policies (§5 BE-4) | ✅ V9 | ✅ `/on-call`, `/on-call/:scheduleId` | |
| 23 | Major incident command (§5 BE-4) | ✅ V9 | ✅ `/major-incidents`, `/major-incidents/:id`, command card on the incident | |
| 24 | Post-incident reviews (§5 BE-4) | ✅ V9 | ✅ `/reviews`, `/reviews/:id`, PDF export | |
| 25 | Analytics extensions — MTTA, MTTD, FCR, change, problem, alert, asset, on-call, department and availability metrics; metric definitions (§5 BE-4) | ✅ | ✅ Analytics → Ops metrics and SLA risk (labelled statistical) | |
| 26 | Command Center extensions (§5 BE-4) | ✅ `commandCenter` in the dashboard response | ✅ Command Center operations block | |
| 27 | SA AI extensions — problem correlation, change risk, CMDB impact, runbook recommendations, predictive SLA risk, trends (§5 BE-4) | ✅ | ✅ AI panels on the SA AI, incident, problem and CI pages | |

The 27 rows are the deliverables of RELEASE3_SPEC §1–§5, one row per module or cross-cutting capability.

### Test results

| Suite | Result | Status |
|---|---|---|
| Backend (`mvn verify`, embedded PostgreSQL) | **294 tests, 0 failures, 0 errors** | Re-run 4 Nov 2026 after the follow-ups below: `mvn -B test` |
| Frontend unit (Vitest) | **648 tests in 79 files, all passing** | Re-run 4 Nov 2026: `npx vitest run` |
| Frontend type check + production build | `tsc --noEmit` clean; `npm run build` succeeds | Run at integration (the Playwright run builds and serves the production bundle) |
| End-to-end (Playwright) | **27 passed, 0 failed** | Re-run 4 Nov 2026. New `release3-journeys.spec.ts`: normal change → assessment → approval in the inbox by the change authority (requester not offered their own approval); post-incident review refused while incomplete, then completed, submitted and approved by an incident manager; on-call shift offered by one participant and taken by a colleague from My on-call. Earlier run: `npx playwright test` against the local API on a fresh demo database. New `release3.spec.ts`: signed Prometheus webhook (wrong secret → 401, repeat de-duplicated) → alert → acknowledge → convert to incident; public `/status`; on-call, major incidents, CMDB, changes, analytics. Navigation spec covers all Release 3 routes, redirects and employee restrictions |

### Known limitations (Release 3)

- The Grafana adapter accepts the Authorization credential or Grafana's webhook HMAC signature
  (`X-Grafana-Alerting-Signature`, optional `X-Grafana-Alerting-Timestamp`); either one is enough. Tested with constructed
  payloads only, not a live Grafana. Prometheus uses bearer/basic.
- Approval-flow editor: person approvers are shown by name from the flow's `userNames`; condition facts are free text
  with suggestions (there is no per-subject catalogue of facts).
- Predictive SLA risk is statistical — an estimate from historical SLA clocks of the same priority (smoothed when there
  are fewer than 5 samples), not a machine-learning model and not LLM output; it is labelled as a statistic.
- Integrations, alert sources and CI/CD sources are **not operational until they are configured and verified** with a real
  delivery (their delivery log shows `PROCESSED`); none has been connected to a real external system in this environment.
- A freshly seeded demo database has no live payroll outage: the payroll major incident on a fresh database is a CLOSED
  command record with a DRAFT post-incident review. Run the payroll-outage scenario (System Admin) to play a live outage.
- E-mail is recorded as `SKIPPED` when no SMTP server is configured (`MAIL_HOST` unset); nothing is reported as sent.
- Approval flows can be created and edited through the API only (no editor page yet).
- The live LLM path is covered by tests with mocked clients only; no real model call was made.
- Still not deployed — see [DEPLOYMENT_STATUS.md](DEPLOYMENT_STATUS.md).

---

> **Product renamed NEXUS → SA (4 Nov 2026, owner's request).** Every user-facing name now reads "SA" /
> "SA AI" (UI text, page titles, metadata, docs, API messages, PDF exports); the logo mark is a slanted "S"; the AI
> route is `/sa-ai` (`/nexus-ai` redirects); migration `V5__rename_product_to_sa.sql` updated the default organisation
> name on existing databases, and Release 3 (`V6__release3_foundations.sql`) dropped the interim "360" suffix. Technical
> identifiers are unchanged on purpose: Java package `com.nexus360`, folder and artifact names, the `nexus_refresh` cookie,
> the `X-Requested-With: nexus360` header, `X-Nexus-*` webhook headers, demo e-mail domain `demo.nexus360.io`, Render
> service `nexus360-api`. Verified after the original rename (Release 2.1 code): backend 152/152, Vitest 411/411,
> Playwright 21/21 (fresh database).

> **Release 2 complete and verified locally (3 Nov 2026).** A second brief added a nine-screen reference UI and
> extended requirements (six-role model, incident statuses NEW…REOPENED, new routes, visual workflow builder, PDF
> export, live notifications, knowledge versions/ratings, service dependencies, admin settings). Decisions:
> [docs/RELEASE2_SPEC.md](docs/RELEASE2_SPEC.md); phases: [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md#release-2--reference-ui-and-extended-requirements-started-3-nov-2026).
> Results are in **§R2** directly below; sections 2–7 further down record the Release 1 state and remain valid
> except where §R2 supersedes them. Still not deployed — see [DEPLOYMENT_STATUS.md](DEPLOYMENT_STATUS.md).

## R2.1 Public landing page and account access (4 Nov 2026)

Specification and decisions: [docs/PUBLIC_ACCESS_SPEC.md](docs/PUBLIC_ACCESS_SPEC.md). Reference: the approved
landing-page image.

| Area | Status |
|---|---|
| Landing page `/` (navbar with dropdowns and live status, hero, angled dashboard preview with SVG globe, 7 capability cards, How It Works, reliability strip, platform explainers, footer) | ✅ Built from React/CSS/SVG; compared in the browser with the reference at 1672 × 941; phone 375 px without horizontal scroll |
| Public pages `/docs`, `/support`, `/privacy`, `/terms` | ✅ Terms clearly marked as a template |
| Sign in `/login` (show/hide password, forgot/request links, expired and signed-out notices, safe `next`) | ✅ |
| Request access, invitation registration, forgot / reset password | ✅ Administrator-mediated single-use links (no e-mail delivery) |
| Admin review (Users → Access requests, Invite user, show-once link panel) | ✅ |
| Backend (`V4__account_access.sql`, public + admin endpoints, hashed tokens, generic responses, rate limits, audit) | ✅ |

Truthfulness: the reference's "500+ Enterprise Users", "99.9% Platform Uptime", "< 30 min Avg. Resolution",
"SOC-2 Ready" and "SOC-2 · ISO 27001" are not verified, so they were replaced with implemented capabilities (an E2E
test asserts none of these claims appear). The dashboard preview is labelled "Demo preview · illustrative data".

Performance: the landing route loads its own 18 kB chunk plus a 46 kB entry; the workspace shell, sign-in page,
forms library and Recharts are no longer part of the first load (`AppShell` and `LoginPage` are now lazy).

Fixed during integration: sign-in brand panel content was pushed below the fold by the long demo-accounts list
(now sticky); dropped live-notification streams produced hundreds of ERROR stack traces (an ApiError was being
written to a closed `text/event-stream`) — client disconnects now end quietly (new `ClientAbortDetectionTest`).

Observed once, not reproduced: during the first full E2E run the local API's JVM exited with Windows
`0xC0000005` (native access violation, portable Temurin 21.0.13) at the moment ~10 notification streams failed at
once. After the disconnect fix, three further full E2E runs (63 tests) completed with the API healthy and zero ERROR
log lines.

Root-caused and fixed: an intermittent backend test failure (a VPN incident recommended the *database* runbook) came
from the incident classifier matching keywords as raw substrings — the random test suffix `02eedbb5` contains "db",
so the incident was routed to Database Platform. The same flaw affected real text ("feedback" → db, "typing" → ping,
"want" → wan, "1503" → 503). Keywords and category terms now match whole words (short terms must end at a word
boundary, plural "s" allowed); covered by a new test in `PriorityAndClassificationTest`.

Verified (4 Nov 2026, final code): Vitest **410 / 410** (50 files), type check + production build passed, Playwright
**21 / 21** (fresh database, again on the reused database, and again after the classifier fix), backend **152 / 152**
(two consecutive full runs on the final code).

## R2. Release 2 results

**Verified test runs (3 Nov 2026, all executed in this environment on the final code):**

| Suite | Command | Result |
|---|---|---|
| Backend unit, integration, migration (V2→V3 on Release 1 data) and `@DataJpaTest` repository tests on embedded PostgreSQL 17 | `mvn -B test` (JDK 21) | **127 / 127 passed**, BUILD SUCCESS |
| Frontend type check + production build | `npm run build` | passed |
| Frontend component and unit tests (Vitest + Testing Library) | `npx vitest run` | **321 / 321 passed** (41 files) |
| End-to-end (Playwright, Chrome, production build under the CSP, fresh demo database) | `npx playwright test` | **14 / 14 passed** |

The E2E suite covers sign-in, every Release 2 route with live data and no page errors/CSP violations, redirects from
all Release 1 paths, sidebar navigation, employee / auditor / IT-admin permission boundaries, incident form and
comments, the full payroll outage through the UI (three employees report → duplicates grouped → Critical major parent →
Enterprise Applications → P1 SLA → AI-recommended article with fallback label → escalation → resolution citing the
article → all reports resolved → analytics updated), and the demo scenario runner with an automatic SLA breach escalation.

**Visual check:** all nine reference screens were compared in the browser at 1440 px against the reference image
(Command Center, Incidents, Incident Detail with a live 73 %-match AI recommendation, Service Desk, SA AI, SLA
Monitoring, Workflow builder from the payroll template, Analytics & Reports, Knowledge Base) and the shell at 375 px
(no horizontal scroll).

**Fixed during integration:** reopening a resolved incident failed (SLA flush hit the `resolved_at` check — fixed in
`IncidentService.applyTransition`); the sidebar showed the product name twice when no organisation name was set; every
dashboard/AI page view re-ran organisation-wide insights (a paid model call per page view once an LLM is configured) —
now shared for 10 minutes with explicit refresh, covered by a new test.

**Decisions taken with defaults** (see RELEASE2_SPEC §1–§14): the Release 1 read-only `AUDITOR` role is kept beside the
six requested roles; cancellation is `CLOSED` with resolution code `CANCELLED` (the requested status set has no
CANCELLED); incident categories are configurable (names, default team, keywords, active) but the category codes are
fixed; Release 1 URLs redirect to the new routes; the dashboard header glow is CSS, not an image.

**Known limitations (Release 2):**
- The live LLM path is unit-tested with a mocked client only; no real model call was made (no API key here).
- SLA Monitoring offers preset periods only (its endpoint takes `days`); Analytics supports any date range.
- Demo seed data records a single assignee per incident, so first-call resolution reads 100 % on demo data (the calculation itself is tested).
- Large period-over-period deltas (e.g. +13 300 % volume) appear on demo data because the seed concentrates incidents in the last 30 days.
- SSE through the Vercel `/api` rewrite is unverified until deployed; the SPA falls back to 30 s polling automatically.
- Docker images, GitHub Actions and DAST (StackHawk key) still not run here; not deployed.

### Release 2 gap analysis (current app vs. reference and new brief)

| Area | Release 1 state | Release 2 target |
|---|---|---|
| Visual design | Same palette; breadcrumb eyebrows, grouped sidebar, form-style pages | Reference layouts for all nine screens, flat sidebar + Administration group, KPI/tab/filter patterns |
| Roles | ADMIN, MANAGER, AGENT, EMPLOYEE, AUDITOR | SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, INCIDENT_MANAGER, SUPPORT_AGENT, EMPLOYEE (AUDITOR kept, read-only) |
| Incident statuses | OPEN, ASSIGNED, IN_PROGRESS, ON_HOLD, RESOLVED, CLOSED, CANCELLED | NEW, ASSIGNED, IN_PROGRESS, PENDING, RESOLVED, CLOSED, REOPENED |
| Routes | `/`, `/sla`, `/ai`, `/workflows`, `/admin/*`, `/catalog`, `/forecast` | `/dashboard`, `/sla-monitor`, `/sa-ai`, `/automation`, `/users`, `/audit-logs`, `/settings`, `/services/:id`, `/runbooks/:id` (+ redirects) |
| Service desk | Catalogue requests, approvals | + request priority, comments, history, seven reference categories, tabbed desk |
| Workflows | Form editor, linear actions | Visual canvas with Yes/No branches, PRIORITY_CHANGED + SCHEDULED triggers, templates, run history |
| AI | Classify, summarise, duplicates, KB, root cause, insights, ask | + resolution suggestion with runbook apply, workflow suggestion, insight-of-the-day series, recent analyses, AI rate limit |
| Analytics | KPIs, breakdowns, forecast, CSV | + resolution rate, first-call resolution, volume by service, date/department filters, PDF |
| Knowledge / runbooks | Draft/published, helpful votes | + review workflow, versions, 1–5 ratings, delete; runbook prerequisites, categories, sensitive-step approval |
| Services | Health, availability | + dependencies, 90-day history, dependency degradation, detail page |
| Notifications | In-app, polling | + preferences, Server-Sent Events |
| Administration | Users, SLA, calendars, settings, audit | + incident categories, organisation, notification defaults, AI configuration, system health |

---

## 1. Inspection baseline (supplied package)

### 1.1 Inventory

| Area | Files | Observations |
|---|---|---|
| Backend | 9 Java files, `pom.xml`, `application.yml`, `Dockerfile` | Spring Boot 3.4.1 / Java 21. One JPA entity (`Incident`) with free-text `priority`, `status`, `service`, `assignee`, `sla` strings. |
| Frontend | `App.tsx` (26 KB, single file), `styles.css` (21 KB), `lib/api.ts` | React 18 + Vite + Recharts + lucide-react. All 17 navigation pages rendered from one component via `useState`, no router. |
| Database | none | `ddl-auto: update`; no migrations, no foreign keys, one table. |
| Infra | `docker-compose.yml`, two Dockerfiles, `nginx.conf`, `vercel.json` | Compose runs Postgres 16, Redis 7 (unused), API, web. |
| Docs | `README.md`, `DEPLOYMENT_STATUS.md`, `deployment/DEPLOY_TO_VERCEL_AND_RENDER.md` | Docs openly state the package is a demo MVP. |
| Tests | none | No unit, integration or E2E tests. |

### 1.2 Working functionality worth preserving

- The **visual identity**: obsidian/champagne-gold design system in `styles.css` (sidebar, topbar, KPI cards, panels, badges, drawer, modal, toast, workflow canvas, AI workspace), Manrope + DM Sans typography.
- **Navigation structure** (5 groups, 15 items) and page titles/descriptions.
- Incident CRUD endpoints, `/api/health`, Swagger UI, CORS driven by `FRONTEND_ORIGIN`.
- `INC-xxxx` incident numbering, Docker Compose layout, Vercel SPA rewrite.

### 1.3 Broken, fake or placeholder behaviour found

| # | Location | Problem |
|---|---|---|
| 1 | `SecurityConfig` | Every endpoint is `permitAll()`; CSRF disabled; no authentication at all. |
| 2 | `DashboardController` | `slaCompliance: 96.4` and `meanTimeToResolveMinutes: 28` are hardcoded constants. Controller also sets `@CrossOrigin("*")`, bypassing the CORS allow-list. |
| 3 | `App.tsx` Command Center | KPI values `1,248`, `96.4%`, `28 min` hardcoded; critical count is `real + 26`; trend chart, donut, service health, AI insight and quick-card counts are static arrays. |
| 4 | `App.tsx` | Falls back to an in-memory seed list when the API fails, and fabricates IDs (`INC-${random}`) on create, so the UI can show records that do not exist. API errors are swallowed (`catch{}`). |
| 5 | `App.tsx` | Profile ("Anantha P., System Admin"), tenant switcher, date-range button, notification bell, `⌘K`, Service Desk tabs, catalogue tiles, knowledge search, "New article", workflow "Save", AI chat send, "Create resolution task" are toast-only or no-ops. |
| 6 | `App.tsx` | AI recommendation in the incident drawer is the same hardcoded sentence for every incident. AI chat replies with a fixed "Demo response". |
| 7 | `App.tsx` | Services, Forecast & Trends, Integrations, Solutions, Announcements, Users & Roles, Configuration, Audit Logs render a generic placeholder card. |
| 8 | `Incident` model | No impact/urgency, no description, no requester, no relations, comments, attachments or history. `sla` is a display string, not a deadline. |
| 9 | `IncidentController` | No pagination, no validation of priority/status values, no state machine, `DELETE` is open to anyone. |
| 10 | `application.yml` | Default DB password baked in as fallback; `ddl-auto: update` unsuitable for production. |
| 11 | `frontend/package.json` | `@vitejs/plugin-react: "latest"` (non-reproducible) and listed as a runtime dependency. |
| 12 | `api.ts` | Reads a bearer token from `localStorage` that nothing ever writes. |
| 13 | Missing modules | SLA engine, workflow engine, runbooks, knowledge base, notifications, users, integrations, audit logs, AI provider — none exist in the backend. |

### 1.4 Toolchain on the build machine

| Tool | Found | Note |
|---|---|---|
| JDK | 17.0.20 | Project targets Java 21 → authoritative builds run in the `maven:3.9-eclipse-temurin-21` container. |
| Maven | 3.9.6 | |
| Node / npm | 24.19 / 11.17 | |
| Docker Desktop | installed | Was not running at inspection time; started for builds and PostgreSQL/Redis. |
| GitHub CLI | authenticated | |
| Vercel CLI | authenticated | |
| Render / Neon CLI | not installed | Render and Neon steps need the account owner (see deployment guide). |

---

> **Toolchain actually used for verification.** Docker Desktop was stopped at the owner's request, so builds and
> tests ran on a portable Temurin JDK 21.0.13 (session scratch directory, no system install) with an embedded
> PostgreSQL 17.11 instead of containers. Playwright used the installed Google Chrome.

---

## 2. Current status — 3 November 2026

| Phase | Status |
|---|---|
| 1. Inspect ZIP and existing code | ✅ Complete |
| 2. Architecture and database | ✅ Complete — Flyway V1 (schema) + V2 (reference data), `ddl-auto: validate` |
| 3. Secure backend and authentication | ✅ Complete |
| 4. Incident and service desk modules | ✅ Complete |
| 5. SLA and workflow engines | ✅ Complete |
| 6. SA AI and knowledge base | ✅ Complete (LLM path unit-tested with a mocked client; no live LLM call made — no key) |
| 7. Frontend completion and integration | ✅ Complete — every page on live data |
| 8. Analytics and administration | ✅ Complete |
| 9. Security review and automated testing | ✅ Complete — 157 automated tests pass (see §4); DAST not run (no StackHawk key) |
| 10. Production deployment configuration | ✅ Complete — Dockerfiles, Compose, `render.yaml`, `vercel.json`, CI, env templates, guides |
| 11–14. Deploy and validate | ⏸ Waiting for the account owner — nothing is deployed ([DEPLOYMENT_STATUS.md](DEPLOYMENT_STATUS.md)) |

Every baseline problem in §1.3 is resolved: authentication and RBAC on every endpoint (1, 9), all metrics computed
from the database (2, 3), no client-side fabrication or swallowed errors (4), every control wired to the API (5),
grounded AI with honest source labels (6), all 16 modules implemented (7, 13), full incident model (8), Flyway and
no credential fallbacks (10), pinned dependencies (11), in-memory tokens with an HttpOnly refresh cookie (12).

## 3. Modules

| Module | Frontend | Backend | Automated coverage |
|---|---|---|---|
| Command Center | `/` staff + employee dashboards | `GET /analytics/dashboard` | E2E routes, admin KPIs |
| Incident Management | `/incidents` filters, scopes, export | intake pipeline, search, export | `IncidentLifecycleIT`, `PriorityAndClassificationTest`, `DuplicateScoringTest`, E2E form + payroll |
| Incident Details | `/incidents/:id` | transitions, assign, escalate, major, links, comments, attachments, history | `IncidentLifecycleIT`, `AttachmentValidationTest`, E2E payroll |
| Service Desk | `/service-desk` queue + approvals | requests, approvals (segregation of duties) | `ServiceDeskApprovalIT` (form validation, routing, segregation of duties), E2E routes |
| Service Catalogue | `/catalog` dynamic forms | categories, items, form validation | E2E routes |
| SA AI | `/ai` + incident AI panel | provider + fallback, approvals for actions | `AiServiceFallbackTest`, `AnthropicLlmProviderTest`, `AiAndKnowledgeIT`, E2E payroll |
| SLA Monitoring | `/sla` + incident SLA panel | engine, scheduler, calendars | `BusinessTimeCalculatorTest`, `SlaEngineTest`, `PayrollOutageScenarioIT`, E2E escalation |
| Workflow Automation | `/workflows` builder, versions, executions | engine, retries, limits | `ConditionEvaluatorTest`, `WorkflowExecutionIT` |
| Runbooks | `/runbooks` | steps, approval-gated execution | E2E routes |
| Analytics / Reports | `/analytics`, `/forecast` | KPIs, breakdowns, OLS forecast, CSV | `AuthAndAuthorizationIT` (export), E2E routes + payroll |
| Knowledge Base | `/knowledge`, `/solutions`, articles | FTS + trigram search, feedback, recommendations | `AiAndKnowledgeIT`, E2E payroll |
| Notifications | `/notifications`, bell | in-app notifications, announcements | `PayrollOutageScenarioIT`, E2E routes |
| User Management | `/admin/users` | users, roles, departments, lockout | `AuthAndAuthorizationIT`, E2E permissions |
| Integrations | `/integrations` | webhooks (HMAC), Slack/Teams, inbound alerts, SSRF guard | `UrlSafetyTest` (SSRF guard), E2E routes |
| Audit Logs | `/admin/audit-logs` | append-only log, filters, CSV | `AuthAndAuthorizationIT` (append-only, export audit), E2E auditor |
| Administration | `/admin/configuration` | SLA policies, calendars, settings, demo tools | E2E routes + simulated SLA time |

## 4. Test results

All runs below were executed on 3 November 2026 in this environment; nothing is reported that was not run.

| Suite | Command | Result |
|---|---|---|
| Backend unit + integration (JUnit 5, Mockito, Spring Boot Test, embedded PostgreSQL 17) | `mvn -B test` (JDK 21) | **82 / 82 passed** — 16 test classes, BUILD SUCCESS |
| Frontend unit (Vitest + Testing Library) | `npx vitest run` | **63 / 63 passed** — 9 files |
| Frontend type-check + production build | `npm run build` | passed |
| End-to-end (Playwright, Chrome, production build under the CSP via `vite preview`) | `npx playwright test` | **12 / 12 passed** |
| Production jar smoke test (no profile, Neon-style URL) | `java -jar` + curl | readiness UP, 401/403 enforced, headers present; refuses to start without `JWT_SECRET` |

Backend classes: `AuthAndAuthorizationIT` (9), `IncidentLifecycleIT` (6), `PayrollOutageScenarioIT` (1), `ServiceDeskApprovalIT` (3),
`WorkflowExecutionIT` (3), `AiAndKnowledgeIT` (4), `AiServiceFallbackTest` (5), `AnthropicLlmProviderTest` (8),
`PriorityAndClassificationTest` (12), `DuplicateScoringTest` (5), `BusinessTimeCalculatorTest` (5), `SlaEngineTest` (4),
`ConditionEvaluatorTest` (3), `UtilitiesTest` (6), `AttachmentValidationTest` (4), `UrlSafetyTest` (4).

E2E specs: sign-in and demo-account listing, wrong password, client-side validation without an API call,
protected-route redirect and return, session survives reload, all 20 primary routes render live data with no page
errors or CSP violations, sidebar navigation, employee and auditor restrictions, incident form validation and
comments, **payroll outage through the UI** (three employees report → duplicates grouped under a parent → Critical
major incident → Enterprise Applications → P1 SLA → payroll KB recommended with fallback label → escalation → resolution
citing the article → every child resolved → analytics resolved count increases), and the **demo scenario runner** with
simulated SLA time producing an automatic breach and escalation.

Not run: GitHub Actions (no repository yet), Docker image builds (Docker stopped), StackHawk DAST (no API key), any
test against a live deployment.

## 5. Security review

Fixed during the review (beyond the baseline items):

| Finding | Fix |
|---|---|
| Method-security denials were not audited | `GlobalExceptionHandler` records `ACCESS_DENIED` |
| Validation ran before role checks, leaking `400` vs `403` | URL-level role gates in `SecurityConfig` |
| Enum parse errors echoed internal class names | Messages sanitised |
| `X-Forwarded-For` was taken from the left-most (client-controlled) entry | Tomcat RemoteIpValve trusts only internal proxies |
| Incident and analytics CSV exports were not audited | `INCIDENTS_EXPORTED` / `ANALYTICS_EXPORTED` records + test |
| nginx security headers were silently dropped on `/` and `/assets/` | Headers repeated per location |
| No Content-Security-Policy on the SPA | Strict CSP on Vercel, nginx and preview; E2E fails on violations |
| Vercel's CDN may cache proxied API responses | `x-vercel-enable-rewrite-caching: 0` on `/api/*` |
| JSON dirty-checking attempted UPDATEs on the append-only audit table | Normalised JSON, `@Immutable` entity |

Residual risks (accepted for this release, documented for the owner):

- An access token stays valid for up to 15 minutes after a user is deactivated or signs out elsewhere (refresh tokens are revoked immediately).
- Rate limits are per instance unless Redis is enabled; behind the Vercel proxy they apply per Vercel edge address (account lockout still applies per account).
- Uploads are allow-listed by type and magic bytes but not malware-scanned; files are stored in PostgreSQL.
- Swagger UI and the OpenAPI document are public by default (no data; set `SWAGGER_ENABLED=false` to hide).
- The free Render plan sleeps, pausing SLA escalation until the next request.
- No DAST or third-party penetration test has been performed yet.

## 6. Known limitations

- Not deployed: no public URL exists (see [DEPLOYMENT_STATUS.md](DEPLOYMENT_STATUS.md)).
- One LLM provider implementation (Anthropic); the live LLM path has not been exercised against the real API here.
- Notifications are in-app plus Slack/Teams/webhooks; there is no e-mail delivery.
- Single organisation (no multi-tenancy); no SSO.
- Business-hours calendars apply per SLA policy; per-department calendars are not modelled.
- The JS bundle is ~1.2 MB before compression (Recharts ~450 KB); acceptable for an internal tool, not optimised further.

## 7. Deliverables

Source (`backend/`, `frontend/`), migrations (`backend/src/main/resources/db/migration`), API docs (`API_DOCUMENTATION.md`,
live Swagger), architecture (`ARCHITECTURE.md`), database schema (`DATABASE_SCHEMA.md`), security (`SECURITY.md`), tests (`backend/src/test`, `frontend/src/**/*.test.*`,
`frontend/e2e`), Docker (`backend/Dockerfile`, `frontend/Dockerfile`, `docker-compose.yml`), Vercel
(`frontend/vercel.json`), Render (`render.yaml`), CI (`.github/workflows/ci.yml`), Neon guide (`docs/NEON.md`),
env templates (`.env.example`, `backend/.env.example`, `frontend/.env.example`), deployment guide
(`DEPLOYMENT.md`), this file, `README.md`, `DEVELOPMENT_ROADMAP.md`, `DEPLOYMENT_STATUS.md`.
