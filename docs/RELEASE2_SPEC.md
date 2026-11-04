# SA — Release 2 change specification

Source of truth for the Release 2 work (reference UI: nine screens; master brief of 3 Nov 2026). Release 1 is
complete and tested (see PROJECT_STATUS.md); Release 2 **extends** it. Backend and frontend both implement exactly
what is written here. Anything not mentioned keeps its Release 1 behaviour and API.

Reference image (nine screens): `C:\Users\Ananthapadmanabhan\.claude\uploads\19c94e11-ffbc-4b07-bf2c-0ca13ff71869\ae35747c-image.png`

Ground rules (unchanged): no fake/hard-coded business data; every number from the database; AI output always
labelled by source (`LLM` vs deterministic `FALLBACK`); data-changing AI suggestions need human confirmation;
server-side authorisation on every endpoint; no secrets in the frontend; seeded data flagged `demo`.

---

## 1. Roles

| New role | Replaces | Summary |
|---|---|---|
| `SYSTEM_ADMIN` | `ADMIN` | Everything, including system/organisation/AI settings and demo tools |
| `IT_ADMIN` | — (new) | IT configuration: users (cannot create, edit or grant `SYSTEM_ADMIN`), departments, services, catalogue, SLA policies, calendars, incident categories, integrations, workflows, runbooks, knowledge publishing and deletion; plus all staff incident powers |
| `IT_MANAGER` | `MANAGER` | Team/department management: approves service requests, workflows, analytics, exports, major incidents |
| `INCIDENT_MANAGER` | — (new) | Incident command: major incidents, escalation, assignment across teams, approves runbook executions, sensitive runbook steps and AI actions, analytics, exports |
| `SUPPORT_AGENT` | `AGENT` | Works incidents and requests |
| `EMPLOYEE` | `EMPLOYEE` | Reports incidents, raises requests, reads published knowledge |
| `AUDITOR` | `AUDITOR` | **Kept** (Release 1 role, read-only compliance): reads operational data, audit logs, exports |

Migration: `ADMIN→SYSTEM_ADMIN`, `MANAGER→IT_MANAGER`, `AGENT→SUPPORT_AGENT` everywhere — `users.role`,
`catalog_items.approver_role`, `approvals.approver_role`, and role names inside workflow definition/version JSON
(`NOTIFY_ROLE` params). Update all CHECK constraints.

Groups (`Roles` constants / frontend `lib/constants.ts`):

- `ADMINS` = SYSTEM_ADMIN, IT_ADMIN
- `MANAGERS` = IT_MANAGER, INCIDENT_MANAGER
- `ADMIN_OR_MANAGER` = ADMINS + MANAGERS
- `STAFF` = ADMIN_OR_MANAGER + SUPPORT_AGENT (work incidents, internal notes, assignment, transitions)
- `READERS` = STAFF + AUDITOR
- `AUDIT_READERS` = SYSTEM_ADMIN, IT_ADMIN, AUDITOR
- `REPORT_EXPORTERS` = ADMIN_OR_MANAGER + AUDITOR
- `USER_ADMINS` = ADMINS; `USER_READERS` = ADMIN_OR_MANAGER + AUDITOR
- `CONFIG_ADMINS` = ADMINS (services, catalogue, SLA, calendars, incident categories, integrations)
- `WORKFLOW_EDITORS` = ADMINS + IT_MANAGER
- `RUNBOOK_EDITORS` = ADMIN_OR_MANAGER
- `KB_PUBLISHERS` = ADMIN_OR_MANAGER; `KB_DELETERS` = ADMINS
- `MAJOR_INCIDENT` (declare/remove major) = ADMIN_OR_MANAGER; escalate = STAFF
- `SETTINGS` = SYSTEM_ADMIN (organisation, notification defaults, AI configuration, system settings, demo tools)
- `HEALTH_READERS` = ADMINS

Approvals: `approver_role ∈ {IT_MANAGER, INCIDENT_MANAGER, SYSTEM_ADMIN}`. Service requests → `IT_MANAGER`;
runbook executions, sensitive runbook steps and AI actions → `INCIDENT_MANAGER`. `SYSTEM_ADMIN` sees and decides all;
a manager sees only items routed to their role. Segregation of duties unchanged.

Audit: user role changes record `USER_ROLE_CHANGED` `{from,to}`; also `USER_CREATED`, `USER_DEACTIVATED`,
`USER_REACTIVATED`, `SETTINGS_CHANGED`, `WORKFLOW_PUBLISHED`/`WORKFLOW_ACTIVATED`/`WORKFLOW_DEACTIVATED` (keep any that exist).

Demo data: `admin` → SYSTEM_ADMIN; `priya.raman`, `kiran.rao` → IT_MANAGER; `sunita.joshi` → INCIDENT_MANAGER; add
`rajesh.iyer` (Rajesh Iyer, IT_ADMIN, IT-SD, "IT Platform Administrator"); agents → SUPPORT_AGENT.

## 2. Incident statuses

`NEW, ASSIGNED, IN_PROGRESS, PENDING, RESOLVED, CLOSED, REOPENED`. Active = NEW, ASSIGNED, IN_PROGRESS, PENDING, REOPENED.

Migration: `OPEN→NEW`, `ON_HOLD→PENDING`, `CANCELLED→CLOSED` with `resolution_code='CANCELLED'` and
`resolved_at = coalesce(resolved_at, closed_at, updated_at)`. Add `CANCELLED` to `ResolutionCode`. Replace status
literals in workflow definition JSON. Historical `incident_history` text is left as is (UI title-cases unknown values).

Transitions:

| From | To |
|---|---|
| NEW | ASSIGNED, IN_PROGRESS, PENDING, RESOLVED, CLOSED (cancel) |
| ASSIGNED | IN_PROGRESS, PENDING, RESOLVED, CLOSED (cancel) |
| IN_PROGRESS | PENDING, RESOLVED |
| PENDING | IN_PROGRESS, RESOLVED |
| RESOLVED | CLOSED, REOPENED |
| REOPENED | ASSIGNED, IN_PROGRESS, PENDING, RESOLVED |
| CLOSED | — |

- Cancel = `CLOSED` with `resolutionCode=CANCELLED` and a required reason (staff, or the requester on their own NEW/ASSIGNED incident).
- Requester may `REOPENED` their own RESOLVED incident (as before).
- PENDING pauses the SLA clock; REOPENED restarts the resolution clock (Release 1 reopen logic); setting an assignee on a NEW/REOPENED incident moves it to ASSIGNED.
- Frontend labels: New, Assigned, In Progress, Pending, Resolved, Closed, Reopened.

## 3. Incidents API additions

- `GET /incidents/counts` (same filters as `/incidents`, minus paging) → `{ all, open, inProgress, resolved }` where open = active statuses, inProgress = IN_PROGRESS, resolved = RESOLVED+CLOSED (for the list tabs).
- `GET /incidents` adds `assigneeId` filter (exists) and `status` csv with new values.
- `GET /incidents/{id}/activity` → `ActivityItem[]` merged timeline, newest first: `{ kind: 'HISTORY'|'COMMENT'|'SLA'|'ESCALATION'|'APPROVAL'|'RUNBOOK', at, actorName, title, detail }` (comments respect visibility rules).
- `IncidentDetail` adds `escalations: { level, reason, actorName, at }[]` and `relatedSameService: IncidentSummary[]` (up to 5 other active incidents on the same service).
- Bulk operations are done client-side by calling the existing per-incident endpoints.

## 4. Service desk

Categories (seeded codes → names): `HARDWARE` Hardware, `SOFTWARE` Software, `NETWORK` Network, `ACCESS` Access
Management, `ACCOUNT` Account, `FACILITIES` Facilities, `GENERAL` General. Re-map demo catalogue items (e.g. VPN → NETWORK,
password reset → ACCOUNT, general help → GENERAL; keep existing item names so tests keep working).

`service_requests` adds `priority` (LOW|MEDIUM|HIGH|CRITICAL, default MEDIUM; employees may choose LOW–HIGH, staff any).
New tables `service_request_comments` (public/internal, like incident comments) and `service_request_history`.

- `CreateServiceRequest` adds optional `priority`.
- `ServiceRequestDto` adds `priority`, `categoryCode`, `commentCount`.
- `GET /service-requests` adds filters `category` (code), `priority`.
- `GET /service-requests/{id}/comments`, `POST /service-requests/{id}/comments` `{body, internal}` (internal: staff only).
- `GET /service-requests/{id}/history` → `{ at, actorName, action, fromValue, toValue, note }[]`.
- `GET /service-requests/counts?scope` → `{ all, mine, pendingApproval, inFulfillment, fulfilled }`.

## 5. SLA

- Escalations are idempotent: each `(incident_sla, escalation level)` fires once (unique constraint or equivalent guard); re-running evaluation never re-escalates or re-notifies.
- `SlaOverview` adds `kpis: { compliance, breachesInPeriod, atRiskNow, avgResolutionMinutes }`, `byDepartment: { name, met, missed, atRisk, compliance }[]`.

## 6. Workflows

- Triggers add `PRIORITY_CHANGED` and `SCHEDULED`.
- `WorkflowDefinition` adds:
  - `elseActions: WorkflowAction[]` — run when the conditions do **not** match (the "No" branch). Empty = do nothing.
  - `schedule?: { intervalMinutes: number }` — required for `SCHEDULED` (min 15). The scheduler evaluates the conditions against each active incident (max 200 per run) and runs actions for matches; per-workflow `maxExecutionsPerHour` applies; a given incident is processed at most once per interval.
  - `layout?: Record<string, { x: number; y: number }>` — node positions for the visual builder, stored verbatim, ignored by the engine.
- Condition fields add `slaRemainingMinutes` (resolution SLA remaining, number; null when none), `assignedTeam` (department code of the assigned team; same value as `departmentCode`), `assigneeEmail`.
- Actions add `CHANGE_STATUS` (`params.status`, must be a valid transition) and `NOTIFY_USER` (`params.userEmail`, `params.message`). Labels: ESCALATE = "Create escalation", START_RUNBOOK = "Trigger approved runbook", NOTIFY_* = "Send notification…".
- `GET /workflows/templates` → `{ key, name, description, triggerEvent, definition }[]` (at least: "Payroll incident – auto resolution" [trigger INCIDENT_CREATED, condition priority = CRITICAL and serviceCode = PAYROLL, yes: START_RUNBOOK payroll runbook + NOTIFY_DEPARTMENT, no: ASSIGN_DEPARTMENT IT-SD], "Critical auto-triage", "SLA breach escalation", "Stale pending reminder" [SCHEDULED]).
- `GET /workflows/executions` without `workflowId` lists all executions (Run History tab).
- A finally failed execution notifies the workflow creator and `SYSTEM_ADMIN`s (`WORKFLOW_FAILED`).

## 7. SA AI

- AI endpoints are rate limited per user: `RATE_LIMIT_AI_PER_MINUTE` (default 30) → `429`.
- `POST /ai/incidents/{id}/resolution` (staff) → `AiResult<ResolutionSuggestion>`:
  `{ likelyCause: string, confidence: number (0–100), steps: string[], runbook: { id, title, requiresApproval } | null, articles: ArticleSummary[] }`. Powers the incident "SA AI Recommendation — 87% Match" panel and its "Apply Resolution Runbook" button (which calls the existing `POST /runbooks/{id}/executions`; approval-gated runbooks go to approval).
- `POST /ai/workflow-suggestion` (WORKFLOW_EDITORS) `{ goal }` → `AiResult<{ name, description, triggerEvent, definition, explanation }>` — shown in the builder for review; never saved automatically.
- `OperationalInsights` adds `hourly: { hour: number; count: number }[]` (last 30 days, created incidents by hour of day in the default calendar timezone) for the "Insight of the Day" sparkline, and each insight gets `confidence: 'HIGH'|'MEDIUM'|'LOW'`.
- `GET /ai/interactions?size=5` → current user's recent AI interactions `{ id, feature, subject, source, createdAt }[]` ("Recent Analyses").
- `ClassificationSuggestion.priority` is the priority recommendation (exists).

## 8. Analytics

- All analytics endpoints accept `from`, `to` (ISO dates, inclusive; default last `days`), `days` (back-compat) and `departmentId`.
- `DashboardKpis` adds `resolutionRate` (% of incidents created in the period that are RESOLVED/CLOSED-not-cancelled) and `firstCallResolution` (% of incidents resolved in the period that had at most one assignee and were never reopened).
- `AnalyticsOverview` adds `serviceVolume: { date: string; counts: Record<string, number> }[]` (top 5 services by volume, daily) for the stacked "Incident Volume by Service" chart, and `serviceNames: string[]` (series order).
- `GET /analytics/export?format=csv|pdf&from&to&departmentId` — PDF (OpenPDF) contains period, filters, KPIs, by-priority, by-service, by-department and agent tables. Both formats audited.

## 9. Knowledge base

- Status: `DRAFT → IN_REVIEW → PUBLISHED → ARCHIVED`. Staff create drafts and `POST /knowledge/{id}/submit`; KB_PUBLISHERS `POST /knowledge/{id}/publish` and `/archive`; KB_DELETERS `DELETE /knowledge/{id}` (incident references set to null, audited).
- Versions: `kb_article_versions`; every content change stores a version. `GET /knowledge/{id}/versions` → `{ version, title, summary, editorName, changeNote, createdAt }[]`; `POST /knowledge/{id}/versions/{version}/restore` (KB_PUBLISHERS).
- Ratings: `POST /knowledge/{id}/feedback` `{ rating: 1–5, comment? }` (one per user per article, updatable; `helpful` derived as rating ≥ 4 for back-compat). `ArticleSummary` adds `averageRating: number | null`, `ratingCount`.
- `GET /knowledge?sort=popular|recent|rating`.
- `KnowledgeStats` adds `countsByType: Record<ArticleType, number>` and `runbookCount` (category tiles: Incidents = SOLUTION + KNOWN_ERROR, Runbooks, Guides, FAQs).

## 10. Runbooks

- `RunbookDto`/`RunbookRequest`: `prerequisites: string[]`, `categories: Category[]` (linked incident categories; replaces single `category`), steps gain `requiresApproval: boolean` (sensitive step).
- Completing a sensitive step by a non-approver creates an approval (`RUNBOOK_STEP`, routed to INCIDENT_MANAGER); the step state shows `approvalStatus: 'NOT_REQUIRED'|'PENDING'|'APPROVED'|'REJECTED'` and becomes done only when approved. INCIDENT_MANAGER/SYSTEM_ADMIN completing it directly counts as approved (still audited).
- `GET /runbooks?category` filter; `GET /runbooks/executions` paginated history across runbooks.

## 11. Services catalogue

- `service_dependencies (service_id, depends_on_id)`; `BusinessServiceRequest` adds `dependsOnIds: number[]` (no cycles, no self).
- `ServiceDetail` adds `dependencies: ServiceRef[]`, `dependents: ServiceRef[]` (`ServiceRef = { id, code, name, status }`), `availabilityHistory: { date, availability }[]` (last 90 days, daily).
- A service whose dependency is in OUTAGE shows `status: 'DEGRADED'` with `statusReason: 'Dependency <name> is in outage'` (`ServiceDto.statusReason: string | null`).

## 12. Notifications

- Preferences: `notification_preferences (user_id, type, in_app)`. `GET /notifications/preferences` → `{ type, label, description, inApp }[]`; `PUT /notifications/preferences` `{ type: boolean }`. Types: `INCIDENT_ASSIGNED, INCIDENT_COMMENT, INCIDENT_RESOLVED, SLA_AT_RISK, SLA_BREACHED, ESCALATION, APPROVAL_REQUESTED, APPROVAL_DECIDED, WORKFLOW_FAILED, REQUEST_UPDATED, ANNOUNCEMENT`. Disabled types are not created for that user.
- Real time: `GET /api/notifications/stream` (Server-Sent Events, `Authorization: Bearer` header; the SPA uses `fetch` streaming because `EventSource` cannot send headers). Events: `notification` (NotificationDto JSON) and `ping` every 25 s. Published after commit. The SPA falls back to 30 s polling if the stream fails.

## 13. Administration

- `GET/PUT /admin/incident-categories` (CONFIG_ADMINS write, READERS read) → `{ code, name, description, defaultDepartmentId, defaultDepartmentName, keywords: string[], active }[]`. Codes are the fixed `Category` set; names, descriptions, default department, extra classifier keywords and active flag are configurable. Inactive categories are hidden from pickers; the classifier uses configured keywords in addition to its built-in signals and routes to the default department when the service has none.
- Organisation settings (system_settings keys, SETTINGS write): `org.name`, `org.support_email`, `org.timezone`, `org.business_hours_note`. Exposed in `GET /auth/me` as `organisation: { name }` for the shell.
- Notification defaults (SETTINGS): `notifications.sla_at_risk.notify_managers` (bool), `notifications.major.notify_all_staff` (bool).
- `GET /admin/ai` (SETTINGS) → `{ provider, model, keyConfigured: boolean, llmEnabled, rateLimitPerMinute, timeoutSeconds }`; `PUT /admin/ai` `{ llmEnabled }` only. API keys are never accepted or returned by the API.
- `GET /admin/system-health` (HEALTH_READERS) → `{ status, version, uptimeSeconds, database: { status, latencyMs, schemaVersion }, redis: { enabled, status }, slaScheduler: { enabled, lastRunAt }, workflowEngine: { pendingRetries, failedLast24h }, ai: { provider, keyConfigured }, counts: { users, incidents, activeIncidents } }`.

## 14. Frontend routes and navigation

Routes: `/dashboard` (and `/` redirects), `/incidents`, `/incidents/:id`, `/service-desk` (tabs: Requests, Service Catalog,
My Requests, Approvals), `/services`, `/services/:id`, `/sa-ai`, `/sla-monitor`, `/automation` (tabs: My Workflows,
Templates, Run History), `/automation/new`, `/automation/:id` (visual builder), `/analytics` (tabs: Operations, Incidents,
Services, Users, Custom; forecast lives here), `/knowledge`, `/knowledge/:id`, `/runbooks`, `/runbooks/:id`,
`/notifications`, `/users` (tabs: Users, Departments), `/audit-logs`, `/integrations`, `/settings` (tabs: Organisation,
SLA Policies, Business Hours, Incident Categories, Service Catalogue, Notifications, AI, System Health, Demo Tools —
each tab role-gated), `/profile`, `/announcements`, `/login`.

Old paths redirect: `/sla→/sla-monitor`, `/ai→/sa-ai`, `/workflows→/automation`, `/workflows/:id→/automation/:id`,
`/admin/users→/users`, `/admin/audit-logs→/audit-logs`, `/admin/configuration→/settings`, `/catalog→/service-desk?tab=catalog`,
`/forecast→/analytics?tab=operations`, `/solutions→/knowledge?type=SOLUTION`.

Sidebar (as in the reference, flat, Lucide icons): Command Center, Incidents (open-count badge), Service Desk, Services,
SLA Monitor, SA AI, Automation, Analytics, Knowledge Base, Administration (expands: Users, Integrations, Audit Logs,
Settings — each filtered by role). Runbooks are reached from Knowledge Base and Automation. Top bar: global search
(Ctrl K), notifications bell with unread badge and panel, profile (avatar initials, name, role label). The
`New Incident` action lives on the Incidents page header and the Command Center.

## 15. Visual specification (from the reference)

Palette: background `#101012`, sidebar `#171618`, secondary `#121213`, cards `#1D1B1A`, elevated `#25211D`, gold
`#D6B77A`, champagne `#F1DFBA`, bronze `#96764B`, text `#F5F1EA`, muted `#A7A19A`, success `#49B889`, warning `#E5AE62`,
critical `#E56D68`. Rounded cards (10–12 px) with 1 px warm borders, restrained gold accents, compact type
(Manrope headings, DM Sans body), dense tables, Lucide icons. No blue/purple, neon, heavy gradients or decorative
images. Responsive: sidebar becomes a drawer below 1024 px; grids collapse to one column on phones.

Per screen (match layout and components):

1. **Command Center** — greeting ("Good morning, {first name}"), gold title, subtitle, a subtle CSS radial glow (no image) in the header; 4 KPI cards (Total Incidents, Critical, SLA Compliance, MTTR) with icon tiles and coloured deltas; "Incident Trend & Forecast" area chart with predicted callout; "Incident Distribution" donut with total in the centre and legend with counts/percent; "Service Health" tiles (availability %); "AI Insight" card with confidence badge and source label.
2. **Incidents** — title, subtitle, gold `+ New Incident`; status tabs with counts (All, Open, In Progress, Resolved); search "Search by ID, title or user…"; filter dropdowns Priority, Service, Status, Assignee; table with select checkboxes (bulk bar: Assign, Change status, Export selected), ID, Title, Priority badge, Service, Assignee (avatar initials + short name), SLA (countdown with clock icon, coloured by health), Status badge; pagination "Showing 1–5 of N" with numbered pages.
3. **Incident Detail** — back link + breadcrumb, number pill + priority badge, Escalate and gold Resolve buttons, title, meta line (service · created · updated relative); progress stepper Created → Assigned → In Progress → Resolved; tabs Overview, Activity, Related (count), AI Recommendation; Overview = Description, Impact (level + affected users), Reported by, Assigned to (avatars) + right "SA AI Recommendation" card (match %, likely cause, recommended-action checklist, gold "Apply Resolution Runbook"); SLA panel, comments, attachments keep working.
4. **Service Desk** — title, `+ New Request`; tabs Requests, Service Catalog, My Requests, Approvals (count); search + Category/Priority/Status dropdowns; request rows as cards: icon tile, title, `REQ-… • category`, priority badge, status badge, relative time; row opens a drawer with details, form data, comments, history, actions.
5. **SA AI** — title with Beta badge, "Your IT operations co-pilot"; 4 capability tiles (Analyze — investigate incidents, Predict — forecast issues, Recommend — resolution steps, Automate — create workflows) that open the respective flows; "Ask SA AI anything…" input with gold send button and a real conversation thread; "Insight of the Day" card with sparkline; "Recent Analyses" list; engine status/fallback label visible.
6. **SLA Monitor** — 4 KPI cards (SLA Compliance, Breaches, At Risk, Avg. Resolution); "SLA Compliance Trend" line chart; "Breaches by Service" horizontal bars; plus live SLA queue and breaches by department below.
7. **Automation** — title, `+ Create Workflow`; tabs My Workflows, Templates, Run History; builder: left palette (Triggers, Actions) and a canvas with connected nodes (trigger card → condition diamond with Yes/No branches → action cards), drag to position, click a node to edit it in a side panel; validate, save, activate, test, versions.
8. **Analytics** — title, gold Export (CSV / PDF); tabs Operations, Incidents, Services, Users, Custom; date range + department filters; KPI cards (Incident Volume, Resolution Rate, MTTR, First Call Resolution) with deltas; "Incident Volume by Service" stacked area chart.
9. **Knowledge Base** — title, `Create Article`; search "Search articles, guides, runbooks…"; 4 category tiles (Incidents, Runbooks, Guides, FAQs with counts); "Popular Articles" list with icon, title, star rating (average + count) and read time.

## 16. Tests to add

Backend: role/permission matrix tests per group (security), status migration + transitions, service request
comments/history/priority, SLA escalation idempotency, workflow else-branch, CHANGE_STATUS, SCHEDULED trigger,
PRIORITY_CHANGED, resolution suggestion fallback, AI rate limit, analytics FCR/resolution rate and PDF export,
knowledge publishing/versions/ratings/delete permissions, runbook sensitive-step approval, service dependencies/cycle
rejection, notification preferences and SSE stream, system health, at least two `@DataJpaTest`-style repository tests
on embedded PostgreSQL. Frontend: component tests for the shell/navigation (role filtering), incident table and
filters, workflow canvas model, notification panel. E2E: update to new routes/labels/roles; keep the payroll scenario.
