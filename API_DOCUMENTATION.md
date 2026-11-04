# SA — API documentation

**SA — Enterprise Operations Platform** · REST API reference for Release 3 and Release 4 (BE-5).

This file is the single API reference (it replaces `docs/API.md`, which now only points here). It is written from the
controllers in `backend/src/main/java/com/nexus360/**` (`@RequestMapping` / `@GetMapping` …), the method-level
`@PreAuthorize` rules (`com.nexus360.security.Roles`) and the URL gates in `SecurityConfig`. The live OpenAPI document at
`/v3/api-docs` (Swagger UI `/swagger-ui.html`) is generated from the same code and is authoritative for request and
response schemas. Related: [ARCHITECTURE.md](ARCHITECTURE.md) · [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) ·
[SECURITY.md](SECURITY.md) · [DEPLOYMENT.md](DEPLOYMENT.md).

At a glance (details in [Conventions](#conventions)):

| Topic | Rule |
|---|---|
| Authentication | `Authorization: Bearer <accessToken>` (JWT, 15 min). Refresh token = HttpOnly cookie `nexus_refresh` scoped to `/api/auth` |
| CSRF | `POST /api/auth/refresh` and `POST /api/auth/logout` require `X-Requested-With: nexus360` (else `403`) |
| Errors | `ApiError {status, error, message, path, timestamp, fieldErrors?}`; 400 / 401 / 403 / 404 / 409 / 413 / 415 / 429 |
| Pagination | `?page=0&size=20&sort=field,desc` → `PageResponse<T> {content, page, size, totalElements, totalPages}`, `size` ≤ 100 |
| Rate limits | sign-in bucket 10/min/IP, general API 600/min/IP, inbound webhooks 120/min per endpoint, SA AI 30/min/user → `429` + `Retry-After` |
| Authorisation | Role groups below; employees get `403` on staff modules; record-level checks apply on top of role checks |
| Webhooks (no session) | `/integrations/inbound/{id}` (`X-Nexus-Token`), `/alerts/webhooks/{sourceId}`, `/deployments/webhooks/{sourceId}` (per-source signature or token), `/otel/v1/{metrics,logs,traces}` (telemetry source bearer token, Release 4) |

Size at the time of writing: 403 handler mappings in 41 controllers (counted from the mapping annotations); each of
them is listed in the catalogue below.

## Module index

- **[Conventions](#conventions)**
  - [Roles and groups](#roles-and-groups)
  - [Incident statuses](#incident-statuses)
- **[Endpoints](#endpoints)**
  - [Auth](#auth--public-unless-noted)
  - [Account access](#account-access--public-release-21-public_access_spec-25)
  - [Account requests & invitations](#account-requests--invitations--user-administrators-system_admin-it_admin)
  - [Users & departments](#users--departments)
  - [Business services](#business-services)
  - [Incidents](#incidents)
  - [Service catalogue, service desk, approvals](#service-catalogue-service-desk-approvals)
  - [SLA](#sla)
  - [Workflows](#workflows)
  - [Runbooks](#runbooks)
  - [Knowledge base](#knowledge-base)
  - [SA AI](#sa-ai--every-response-is-airesultt-with-source-llm--fallback)
  - [Notifications, announcements, search](#notifications-announcements-search)
  - [Integrations](#integrations)
  - [Audit, analytics, administration](#audit-analytics-administration)
- **[Release 3 foundations](#release-3-foundations-release3_spec-1-2-4--migration-v6__release3_foundationssql)**
  - [Teams](#teams)
  - [My Work](#my-work--staff-only)
  - [Employee portal](#employee-portal)
  - [E-mail (outbox)](#e-mail-outbox)
  - [Status page](#status-page)
  - [Scheduled reports](#scheduled-reports)
- **[Release 3 — approvals, forms, problems, changes, deployments](#release-3--approvals-forms-problems-changes-deployments-release3_spec-5-be-2--migration-v7__approvals_forms_problems_changes_deploymentssql)**
  - [Approval engine v2](#approval-engine-v2)
  - [Dynamic form builder](#dynamic-form-builder)
  - [Problems (PRB-####)](#problems-prb-)
  - [Changes (CHG-####)](#changes-chg-)
  - [Deployments and releases](#deployments-and-releases)
  - [Extension-point contributions](#extension-point-contributions)
  - [Demo data (all demo: true, seeded once by DemoRelease3ChangeSeeder)](#demo-data-all-demo-true-seeded-once-by-demorelease3changeseeder)
- **[Release 3 — assets, CMDB, alerts](#release-3--assets-cmdb-alerts-release3_spec-5-be-3--migration-v8__assets_cmdb_alertssql)**
  - [CMDB](#cmdb)
  - [Assets](#assets)
  - [Alerts](#alerts)
  - [Extension points and contributions](#extension-points-and-contributions)
  - [Demo data (all demo: true, seeded once per part by DemoRelease3AssetSeeder, also on upgraded databases)](#demo-data-all-demo-true-seeded-once-per-part-by-demorelease3assetseeder-also-on-upgraded-databases)
- **[Release 3 — on-call, major incidents, PIR, analytics, AI](#release-3--on-call-major-incidents-pir-analytics-ai-release3_spec-5-be-4--migration-v9__oncall_major_incidents_pirsql)**
  - [On-call](#on-call)
  - [Major incident command (MI-###)](#major-incident-command-mi-)
  - [Post-incident reviews (PIR-###)](#post-incident-reviews-pir-)
  - [Analytics extensions](#analytics-extensions)
  - [SA AI extensions](#sa-ai-extensions--every-response-is-airesultt-source-llm--fallback)
  - [Extension-point contributions](#extension-point-contributions-1)
  - [Demo data (all demo: true, seeded once per part by DemoRelease3OpsSeeder, also on upgraded databases)](#demo-data-all-demo-true-seeded-once-per-part-by-demorelease3opsseeder-also-on-upgraded-databases)
- **[Release 4 — simulation, observability, digital twin](#release-4--simulation-observability-digital-twin-release4_spec-1-2-41-42--migration-v10__release4_simulation_observability_twinsql)**
  - [Roles and groups (Release 4)](#roles-and-groups-release-4)
  - [Simulation mode](#simulation-mode)
  - [OTLP/HTTP JSON ingestion](#otlphttp-json-ingestion--no-session-source-token)
  - [Telemetry sources, mapping rules, resources, retention](#telemetry-sources-mapping-rules-resources-retention)
  - [Telemetry queries](#telemetry-queries)
  - [Anomaly detection](#anomaly-detection)
  - [Correlation groups](#correlation-groups)
  - [Digital twin](#digital-twin)
  - [Schedulers and advisory locks (Release 4)](#schedulers-and-advisory-locks-release-4)
  - [Frontend navigation contract](#frontend-navigation-contract)

Base path: `/api` — every path in the tables below is relative to it (e.g. `/incidents` = `/api/incidents`), except the Spring Boot
actuator endpoints (`/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`), which are served at
the server root. Interactive documentation: `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`; both disabled with `SWAGGER_ENABLED=false`).
DTO shapes are defined once in [`frontend/src/lib/types.ts`](frontend/src/lib/types.ts) and mirrored by the Java DTOs.

## Conventions

- **Auth**: `Authorization: Bearer <accessToken>` (JWT, 15 min). The refresh token is an `HttpOnly` cookie `nexus_refresh` scoped to `/api/auth`; the two cookie-authenticated endpoints `POST /api/auth/refresh` and `POST /api/auth/logout` must send `X-Requested-With: nexus360` (CSRF guard; missing header → `403`) and `credentials: include`.
- **Pagination**: `?page=0&size=20&sort=createdAt,desc` → `PageResponse<T> {content, page, size, totalElements, totalPages}`; `size` is capped at 100.
- **Errors**: `ApiError` JSON `{status, error, message, path, timestamp, fieldErrors?}`. `400` validation (with `fieldErrors: [{field, message}]`; also used for password-policy failures on register/reset), `401` unauthenticated, `403` forbidden, `404`, `409` conflict / invalid state transition / optimistic lock, `413` upload too large, `415` file type rejected, `429` rate limited (with `Retry-After`).
- **Timestamps**: ISO-8601 UTC. Analytics dates (`from`, `to`) are ISO dates in the default calendar time zone, inclusive.
- **Rate limits**: sign-in bucket 10/min/IP (`RATE_LIMIT_LOGIN_PER_MINUTE`, when `RATE_LIMIT_ENABLED`), shared by `POST /auth/login`, `/auth/change-password` and the four public account-access POSTs (`/auth/access-requests`, `/auth/forgot-password`, `/auth/register`, `/auth/reset-password`) and the three public status-page POSTs (`/public/status/subscribe`, `/public/status/confirm`, `/public/status/unsubscribe`); every `POST /api/ai/**` call is limited per user to `RATE_LIMIT_AI_PER_MINUTE` (default 30) regardless of `RATE_LIMIT_ENABLED` → `429` + `Retry-After`. Other buckets (when `RATE_LIMIT_ENABLED`): `POST /auth/refresh` max(30, 3 × login limit)/min/IP; inbound webhooks (`/integrations/inbound/*`, `/alerts/webhooks/*`, `/deployments/webhooks/*`) `RATE_LIMIT_INBOUND_PER_MINUTE` (120) per endpoint path; everything else under `/api` `RATE_LIMIT_API_PER_MINUTE` (600)/min/IP. Responses carry `X-RateLimit-Remaining`. Counters are per API instance unless Redis is enabled (`REDIS_ENABLED`).

### Roles and groups

Roles: `SYSTEM_ADMIN`, `IT_ADMIN`, `IT_MANAGER`, `INCIDENT_MANAGER`, `CHANGE_MANAGER` (Release 3: changes, CAB, deployments, releases), `ASSET_MANAGER` (Release 3: assets, CMDB, licences, import/export), `SUPPORT_AGENT`, `EMPLOYEE`, `AUDITOR`.
Labels (e-mails, reports): System Administrator, IT Administrator, IT Manager, Incident Manager, Change Manager, Asset Manager, Support Agent, Employee, Auditor.
Release 1 names are migrated by V3 (`ADMIN` → `SYSTEM_ADMIN`, `MANAGER` → `IT_MANAGER`, `AGENT` → `SUPPORT_AGENT`) and access tokens issued before the upgrade that still carry the old names are mapped the same way until they expire.

| Group | Roles |
|---|---|
| admins | `SYSTEM_ADMIN`, `IT_ADMIN` |
| managers | `IT_MANAGER`, `INCIDENT_MANAGER` |
| admins & managers | admins + managers |
| staff | admins + managers + `CHANGE_MANAGER` + `ASSET_MANAGER` + `SUPPORT_AGENT` (Release 3 adds the two new roles) |
| readers | staff + `AUDITOR` |
| approvers | `SYSTEM_ADMIN`, `IT_MANAGER`, `INCIDENT_MANAGER` |
| report exporters | admins, managers, `AUDITOR` |
| workflow editors | `SYSTEM_ADMIN`, `IT_ADMIN`, `IT_MANAGER` |
| change editors (R3) | admins + `IT_MANAGER` + `CHANGE_MANAGER` (incident managers may raise emergency changes — enforced by the change module) |
| change approvers / CAB (R3) | `SYSTEM_ADMIN`, `IT_MANAGER`, `CHANGE_MANAGER` |
| deployment editors (R3) | admins + `CHANGE_MANAGER` |
| asset editors (R3) | admins + `ASSET_MANAGER` |
| CMDB editors (R3) | admins + `ASSET_MANAGER` + `IT_MANAGER` |
| problem editors (R3) | admins & managers + `SUPPORT_AGENT`; closing problems: admins & managers |
| alert responders / alert config (R3) | staff / admins |
| on-call editors (R3) | admins + `IT_MANAGER` + `INCIDENT_MANAGER` (staff view and take overrides offered to them) |
| major incident command (R3) | admins & managers (responders are staff) |
| PIR approvers (R3) | `SYSTEM_ADMIN`, `IT_MANAGER`, `INCIDENT_MANAGER` |
| status page editors (R3) | admins + `INCIDENT_MANAGER` |
| report schedulers (R3) | = report exporters |
| team admins (R3) | user administrators + `IT_MANAGER` (IT managers only for departments they manage or belong to) |

The groups are constants in `com.nexus360.security.Roles` (`@PreAuthorize` expressions plus `*_ROLES` arrays for URL gates) and `Role.STAFF` / `Role.READERS` / `Role.REPORT_EXPORTERS` sets.

Approval routing: service requests → the catalogue item's approver role (`IT_MANAGER` by default); runbook executions, sensitive runbook steps and SA AI actions → `INCIDENT_MANAGER`. A `SYSTEM_ADMIN` sees and may decide every approval; requesters never approve their own. Release 3: multi-stage approvals (approval engine v2, flows with user/role/team/manager approvers) — see "Release 3 — approvals, forms, problems, changes, deployments"; CAB approvals of changes go to change and IT managers.
`IT_ADMIN` cannot create, edit or grant `SYSTEM_ADMIN` accounts; the last active `SYSTEM_ADMIN` cannot be demoted or deactivated.

### Incident statuses

`NEW → ASSIGNED → IN_PROGRESS ⇄ PENDING → RESOLVED → CLOSED`, with `RESOLVED → REOPENED → ASSIGNED | IN_PROGRESS | PENDING | RESOLVED`.
`CLOSED` from `NEW`/`ASSIGNED` is a **cancellation**: it requires a `comment` (reason) and stores `resolutionCode: CANCELLED`; cancellations are excluded from analytics and SLA compliance. `PENDING` requires a reason and pauses the SLA clock. Employees may cancel their own untouched incidents, and close or reopen their resolved ones. List filters still accept the Release 1 names `OPEN`, `ON_HOLD`, `CANCELLED`.

## Endpoints

### Auth — public unless noted
| Method | Path | Access | Notes |
|---|---|---|---|
| POST | `/auth/login` | public, 10/min/IP | `{email, password, rememberMe?}` → `LoginResponse` + refresh cookie. `rememberMe: true` (default when omitted) → persistent cookie (`Max-Age` = `REFRESH_TOKEN_DAYS`, sliding); `false` → browser-session cookie without `Max-Age`/`Expires` and the session ends `BROWSER_SESSION_HOURS` (12) after sign-in. The access token carries the session id (`sid` claim). Locks account for 15 min after 5 failures. |
| GET | `/auth/sessions` | any user | Own active sessions `SessionDto[] {id, device, browser, os, userAgent, ipAddress, lastIpAddress, createdAt, lastUsedAt, expiresAt, rememberMe, current}` (most recently used first; `device` e.g. "Chrome on Windows", derived from the user agent; `current` from the token's `sid`, else the refresh cookie). |
| DELETE | `/auth/sessions/{id}` | any user (own) | `204`; ends the session and revokes its refresh-token family; ending the current session also clears the cookie. Another user's or an ended session → `404`. Audit `SESSION_REVOKED`. |
| POST | `/auth/sessions/revoke-others` | any user | → `{revoked: n}`; ends every own session except the current one (`400` if the current session cannot be identified). Audit `SESSIONS_REVOKED_OTHERS`. |
| POST | `/auth/refresh` | cookie | Rotates refresh token (reuse of a rotated token revokes the whole family). |
| POST | `/auth/logout` | cookie | Revokes refresh token, clears cookie. `204`. |
| GET | `/auth/me` | any user | `CurrentUser` — now includes `organisation: {name}` (setting `org.name`). |
| POST | `/auth/change-password` | any user | `{currentPassword,newPassword}` → `204` |
| GET | `/auth/demo-accounts` | public | `DemoAccountsResponse` (empty when demo seeding disabled) |

### Account access — public (Release 2.1, [PUBLIC_ACCESS_SPEC](docs/PUBLIC_ACCESS_SPEC.md) §2–§5)
No session needed. Every POST below is in the sign-in rate-limit bucket. Links are shown once to an administrator; since Release 3 they are **also e-mailed to the recipient when SMTP is configured** (`MAIL_HOST`, see "E-mail"), otherwise the administrator hands them over through a trusted channel.

| Method | Path | Body → Response | Notes |
|---|---|---|---|
| POST | `/auth/access-requests` | `AccessRequestBody {fullName 2–120, email ≤160, department? ≤120, jobTitle? ≤120, reason 10–1000}` → `202 MessageResponse {message}` | Always the same `202` body. A row is created (status `PENDING`) only when the e-mail has no account and no pending access request; then user administrators are notified (`ACCESS_REQUEST`). |
| POST | `/auth/forgot-password` | `ForgotPasswordBody {email}` → `202 MessageResponse {message}` | Always the same `202` body. Active account without a pending reset request → `PASSWORD_RESET` request + `PASSWORD_RESET_REQUEST` notification to user administrators; unknown, inactive or already pending → nothing. |
| GET | `/auth/invitations/{token}` | → `200 InvitationInfo {email, fullName, role, departmentName, organisationName, expiresAt}` | `fullName` may be `null` (direct invitation without a name). Unknown, used, revoked or expired → `404` with one generic message. |
| POST | `/auth/register` | `RegisterBody {token, fullName 2–120, password 10–128}` → `201 RegisterResponse {email}` | Creates an active account with the invited e-mail, role, department and job title (`mustChangePassword: false`); the link becomes used and its access request `COMPLETED`. Unusable link → `404`; password policy → `400` with `fieldErrors: [{field: "password", message}]`; e-mail taken meanwhile → `409`. |
| GET | `/auth/reset-password/{token}` | → `200 ResetLinkInfo {email, expiresAt}` | Unusable link, or the account is no longer active → `404` (same generic message). |
| POST | `/auth/reset-password` | `ResetPasswordBody {token, newPassword 10–128}` → `204` | Sets the password, clears lockout and failed attempts, `mustChangePassword: false`, revokes every refresh token (all sessions sign out), marks the link used and the request `COMPLETED`. Unusable link → `404`; policy → `400` with `fieldErrors: [{field: "newPassword", message}]`. |
| GET | `/public/info` | → `PublicInfo {organisationName, supportEmail, version}` | `organisationName` = setting `org.name`; `supportEmail` = `org.support_email` or `null` when empty; `version` as `/health`. |

Generic messages (the frontend may show them verbatim):
- `MessageResponse.message` for access requests without SMTP: *"Thank you. If your request can be accepted, an administrator will review it and give you a one-time registration link through a trusted channel. This installation of SA does not send e-mail."*; with SMTP: *"Thank you. If your request can be accepted, an administrator will review it and a one-time registration link will be sent to this e-mail address."*
- for forgot-password without SMTP: *"If an active account uses this e-mail address, an administrator has been asked for a password reset link. You will receive it through a trusted channel; this installation of SA does not send e-mail."*; with SMTP: *"If an active account uses this e-mail address, an administrator has been asked for a password reset link. Once issued, the link is sent to this e-mail address."* (The wording depends only on the installation, never on the address.)
- `404` message for every unusable link: *"This link is invalid or has expired. Ask an administrator for a new one."*

### Account requests & invitations — user administrators (`SYSTEM_ADMIN`, `IT_ADMIN`)
| Method | Path | Body → Response | Notes |
|---|---|---|---|
| GET | `/admin/account-requests?type&status&page&size` | → `PageResponse<AccountRequestDto>` | Newest first. `type`: `ACCESS` \| `PASSWORD_RESET`; `status`: `PENDING` \| `APPROVED` \| `REJECTED` \| `COMPLETED` (unknown values → `400`). |
| GET | `/admin/account-requests/counts` | → `RequestCounts {pendingAccess, pendingReset}` | |
| POST | `/admin/account-requests/{id}/approve` | ACCESS: `ApproveBody {role, departmentId, jobTitle?}`; PASSWORD_RESET: `{}` (or no body) → `LinkResponse {link, expiresAt}` | Request becomes `APPROVED`. ACCESS: missing `role`/`departmentId` → `400` with `fieldErrors`; unknown department → `400` (`fieldErrors.departmentId`); `jobTitle` defaults to the requester's. `409` when the request is not `PENDING`, (ACCESS) the e-mail now has an account, or (PASSWORD_RESET) the account is gone or inactive. `403` when an `IT_ADMIN` approves with role `SYSTEM_ADMIN`, or approves a reset for a `SYSTEM_ADMIN` account. |
| POST | `/admin/account-requests/{id}/reject` | `RejectBody {note 1–500, required}` → `AccountRequestDto` (`REJECTED`) | `409` when not `PENDING`. The address may submit a new request afterwards. |
| POST | `/admin/invitations` | `InvitationBody {email, fullName? ≤120, role, departmentId, jobTitle? ≤120}` → `LinkResponse {link, expiresAt}` | `409` if the e-mail has an account; `403` when an `IT_ADMIN` invites as `SYSTEM_ADMIN`; unknown department → `400`. |

- `AccountRequestDto = {id, type, fullName, email, department, jobTitle, reason, status, createdAt, decidedByName, decidedAt, decisionNote, userId}` — `department` is the requester's free text (reset requests: the account's department name, and `jobTitle` the account's); `userId` is the reset target, or the account created from an access request (`null` until registration); `reason` is `null` for reset requests; `decidedByName`, `decidedAt`, `decisionNote` are `null` while `PENDING`.
- `LinkResponse = {link, expiresAt, emailQueued}` — `emailQueued` (Release 3) is `true` when the link was also queued for e-mail delivery to the recipient (absolute link `APP_PUBLIC_URL` + path); the stored e-mail body is replaced by a placeholder once delivered (or immediately when SMTP is not configured), so the raw token does not stay in the outbox. Audit `INVITATION_CREATED` / `PASSWORD_RESET_LINK_CREATED` add `emailQueued`.
- `LinkResponse.link` is an app-relative path — `/register?invite=<token>` or `/reset-password?token=<token>` — to prefix with `window.location.origin`. The raw token appears in this one response only: 32 random bytes (`SecureRandom`), URL-safe base64 without padding (43 characters); only its SHA-256 hash is stored.
- Validity: invitation 7 days, reset link 2 hours (`expiresAt` in whole seconds). Links are single use. Issuing a new link for the same e-mail and purpose revokes the older unused ones (approval and direct invitation alike).
- Notifications (types `ACCESS_REQUEST`, `PASSWORD_RESET_REQUEST`, both user-switchable) link to `/users?tab=access-requests`.
- Audit actions: `ACCESS_REQUESTED`, `PASSWORD_RESET_REQUESTED` (one row per public submission, `details {recorded, outcome: CREATED | EXISTING_ACCOUNT | DUPLICATE_PENDING | UNKNOWN_OR_INACTIVE}`, `actorEmail` = submitted address), `ACCESS_REQUEST_APPROVED`, `ACCESS_REQUEST_REJECTED` (`details.note`), `INVITATION_CREATED`, `PASSWORD_RESET_LINK_CREATED` (`details {email, role?, departmentId?, userId?, accountRequestId, expiresAt, revokedPrevious}`), `USER_REGISTERED`, `PASSWORD_RESET_COMPLETED`. Details never contain tokens, links or passwords.

### Users & departments
| Method | Path | Access |
|---|---|---|
| GET | `/users?search&role&departmentId&active&page&size` | report exporters |
| GET | `/users/{id}` | report exporters |
| GET | `/users/assignable?departmentId` | staff → `UserSummary[]` (agents and managers) |
| POST | `/users` | admins — `{email,fullName,role,departmentId,jobTitle,phone,password}` |
| PUT | `/users/{id}` | admins — `{fullName,role,departmentId,jobTitle,phone,active}`; audits `USER_ROLE_CHANGED {from,to}`, `USER_DEACTIVATED`, `USER_REACTIVATED` |
| POST | `/users/{id}/reset-password` | admins — `{newPassword}`; forces change on next login |
| GET | `/users/{id}/sessions` | admins → `SessionDto[]` (active sessions; `current` always false) |
| POST | `/users/{id}/sessions/revoke` | admins → `{revoked: n}`; signs the user out everywhere (`IT_ADMIN` cannot for a `SYSTEM_ADMIN` → `403`). Audit `USER_SESSIONS_REVOKED`. |
| GET | `/departments` | any user |
| POST / PUT | `/departments`, `/departments/{id}` | admins — `{code,name,description,managerId,email}` |

### Business services
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/services` | any user | `ServiceDto[]` — adds `statusReason`, `defaultCategory`, `dependsOnIds`. A service whose own status is operational is `DEGRADED` when a dependency is in `OUTAGE` (`statusReason: "Dependency X is in outage"`). |
| GET | `/services/{id}` | any user | `ServiceDetail` — adds `dependencies: ServiceRef[]`, `dependents: ServiceRef[]`, `availabilityHistory: {date, availability}[]` (90 days). `ServiceRef = {id, code, name, status}`. |
| POST / PUT | `/services`, `/services/{id}` | admins | `{code,name,description,departmentId,ownerId,tier,keywords[],defaultCategory,maintenance,dependsOnIds[],defaultTeamId}`. **On PUT a missing or `null` `dependsOnIds` leaves the dependencies unchanged; `[]` clears them.** Self-dependencies, unknown ids and cycles → `400`. `defaultTeamId` (R3): `null`/missing leaves it unchanged, `0` clears it; the team must belong to the service's department. |

Release 3: `ServiceDto` and `ServiceDetail` add `defaultTeamId`, `defaultTeamName`. A service in an active maintenance window (status page) shows `status: MAINTENANCE` with `statusReason: "Planned maintenance: <title>"`.

### Incidents
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/incidents` | any user | Filters: `search,status(csv),priority(csv),category,serviceId,departmentId,assigneeId,teamId,scope(all\|mine\|department\|team\|reported),major,parentOnly,slaHealth`. `scope=team` (R3) = incidents of the teams I belong to. Employees always get `scope=reported`. `IncidentSummary`/`IncidentDetail` add `teamId`, `teamName`; `CreateIncidentResult.assignment` adds `teamName`. |
| GET | `/incidents/counts` | any user | Same filters (status ignored) → `IncidentCounts {all, open, inProgress, resolved}` for list tabs (`open` = active statuses NEW, ASSIGNED, IN_PROGRESS, PENDING, REOPENED; `inProgress` = IN_PROGRESS; `resolved` = RESOLVED, CLOSED). |
| GET | `/incidents/{id}` | any user (employees: own only) | `IncidentDetail` — adds `escalations: {level, reason, actorName, at, source}[]` (`source`: SLA, MANUAL, WORKFLOW, AI) and `relatedSameService: IncidentSummary[]` (up to 5 active incidents on the same service; staff and auditors only). |
| GET | `/incidents/{id}/activity` | any user with access | `ActivityItem[] {kind, at, actorName, title, detail}` (`kind`: HISTORY, COMMENT, SLA, ESCALATION, APPROVAL, RUNBOOK) — merged timeline, comments follow the visibility rules, newest first. |
| POST | `/incidents` | any user | `CreateIncidentRequest` → `201 CreateIncidentResult`. Inactive categories → `400`. New incidents start `NEW` and move to `ASSIGNED` when auto-assigned. |
| POST | `/incidents/preview-duplicates` | any user | `{title,description,serviceId}` → `DuplicateCandidate[]` |
| PUT | `/incidents/{id}` | staff | `UpdateIncidentRequest`; recalculates priority and SLA. |
| POST | `/incidents/{id}/transition` | staff (employees: see statuses) | `TransitionRequest {status, resolutionCode, resolutionNotes, rootCause, resolutionArticleId, comment}`; `409` if not allowed. `comment` is the reason for PENDING and cancellation. |
| POST | `/incidents/{id}/assign` | staff | `AssignRequest {departmentId?, assigneeId?, teamId?}`; a NEW or REOPENED incident moves to ASSIGNED. With `teamId` (R3) the department defaults to the team's (another `departmentId` → `400`) and an `assigneeId` must be a team member (`400`); a team without an assignee puts the incident in that team's queue (members get `TEAM_QUEUE`). Without `teamId` the team follows the assignee (their team in the department) or stays/falls back to the department's default team. |
| POST | `/incidents/{id}/auto-assign` | staff | Least-loaded agent in the owning department. |
| POST | `/incidents/{id}/escalate` | staff | `{reason}`; recorded as an escalation (`source: MANUAL`). |
| POST | `/incidents/{id}/major` | admins & managers | `{major:boolean}` |
| POST / DELETE | `/incidents/{id}/parent` | staff | `{parentId}` link as child / unlink |
| POST | `/incidents/{id}/links` | staff | `{targetId,linkType}` |
| DELETE | `/incidents/{id}/links/{linkId}` | staff | |
| GET | `/incidents/{id}/duplicates` | staff | Rule-based similarity candidates |
| GET / POST | `/incidents/{id}/comments` | any user with access | `{body,internal}`; internal notes hidden from employees |
| GET / POST | `/incidents/{id}/attachments` | any user with access | multipart `file`, ≤ 5 MB, allow-listed types verified by signature |
| GET | `/incidents/{id}/attachments/{aid}/download` | any user with access | |
| DELETE | `/incidents/{id}/attachments/{aid}` | uploader, admins & managers | |
| GET | `/incidents/{id}/history` | any user with access | `HistoryDto[]` |
| GET | `/incidents/{id}/sla` | any user with access | `SlaDetail` |
| GET | `/incidents/export` | report exporters | CSV |
| DELETE | `/incidents/{id}` | `SYSTEM_ADMIN` | Prefer cancellation; audited. |

SLA escalations are idempotent: each (SLA clock, level) escalates and notifies once (`incident_escalations` unique index), at-risk and breach alerts fire once per clock.

### Service catalogue, service desk, approvals
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/catalog/categories` | any user | Standard codes `HARDWARE, SOFTWARE, NETWORK, ACCESS, ACCOUNT, FACILITIES, GENERAL` (with `icon`). |
| GET | `/catalog/items?categoryId&search&includeInactive` | any user | |
| POST / PUT | `/catalog/categories`, `/catalog/categories/{id}`, `/catalog/items`, `/catalog/items/{id}` (POST creates, PUT `/{id}` updates) | admins | An item's `approverRole` must be an approver role. |
| GET | `/service-requests?scope(mine\|department\|all)&status&search&category&priority&page&size` | any user (employees: mine) | `category` is a category code. `ServiceRequestDto` adds `priority`, `categoryCode`, `commentCount` (internal comments counted for staff only). |
| GET | `/service-requests/counts?scope&search&category&priority` | any user | `RequestCounts {all, mine, pendingApproval, inFulfillment, fulfilled}` for tabs (`inFulfillment` = APPROVED, IN_FULFILLMENT). |
| GET | `/service-requests/{id}` | requester or staff | |
| POST | `/service-requests` | any user | `CreateServiceRequest` adds `priority` (default MEDIUM; employees LOW–HIGH, staff any). |
| GET / POST | `/service-requests/{id}/comments` | requester or staff (auditors read) | `{body, internal}` → `RequestCommentDto {id, authorName, authorRole, body, internal, createdAt}`; only staff may write internal comments, which requesters never see. Notifies the other party (`REQUEST_COMMENT`). |
| GET | `/service-requests/{id}/history` | requester or staff | `RequestHistoryDto[] {at, actorName, action, fromValue, toValue, note}` (CREATED, STATUS_CHANGED, ASSIGNED, COMMENTED, APPROVAL_*), oldest first. |
| POST | `/service-requests/{id}/assign` | staff — `{assigneeId?, teamId?}` (at least one) | R3: with `teamId` the request moves to the team (and its department); an assignee must be a member; a team alone puts it in the team queue. `ServiceRequestDto` adds `teamId`, `teamName` (new requests get the fulfilment department's default team; fulfilment auto-assignment picks the team's least-loaded member). |
| POST | `/service-requests/{id}/start` | staff | |
| POST | `/service-requests/{id}/fulfill` | staff — `{notes}` | |
| POST | `/service-requests/{id}/cancel` | requester or staff — `{reason}` | |
| GET | `/approvals?status&type&scope&search&overdue&page&size&sort` | any signed-in user (Release 3; lists only approvals routed to the caller — see "Release 3 — approvals, …") | Types: `SERVICE_REQUEST`, `RUNBOOK_EXECUTION`, `RUNBOOK_STEP`, `AI_ACTION`, R3 `CHANGE`, `PIR`, `WORKFLOW`. |
| POST | `/approvals/{id}/decision` | an approver of an open task of the active stage — `{approved,comment}` | `403` when not routed to the caller or for the requester; `409` when decided or the caller already decided this stage. |

### SLA
| Method | Path | Access |
|---|---|---|
| GET | `/sla/overview?days` | readers → `SlaOverview`, adds `kpis {compliance, breachesInPeriod, atRiskNow, avgResolutionMinutes}` and `byDepartment {name, met, missed, atRisk, compliance}[]` |
| GET | `/sla/active?health&page&size` | readers → `PageResponse<IncidentSummary>` ordered by due time |
| GET / POST / PUT | `/sla/policies` (GET list, POST create), `/sla/policies/{id}` (PUT) | GET readers; write admins |
| GET | `/sla/calendars` | readers |
| PUT | `/sla/calendars/{id}` | admins — `{name,timezone,workDays,startTime,endTime}` |
| POST / DELETE | `/sla/calendars/{id}/holidays`, `/sla/calendars/{id}/holidays/{hid}` | admins |
| POST | `/sla/evaluate` | admins — runs the SLA scheduler tick now |

### Workflows
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/workflows`, `/workflows/{id}`, `/workflows/{id}/versions`, `/workflows/meta` | readers | Triggers add `PRIORITY_CHANGED` and `SCHEDULED`; actions add `CHANGE_STATUS {status}` (must be an allowed transition) and `NOTIFY_USER {userEmail, message}`; new condition fields `assignedTeam`, `assigneeEmail`, `slaRemainingMinutes`. Release 3: condition field `teamCode` (code of the assigned team; incidents and service requests) and action `ASSIGN_TEAM {teamCode}` (the team's least-loaded member, else the team queue; incidents and service requests). |
| GET | `/workflows/templates` | readers | `WorkflowTemplate[] {key, name, description, triggerEvent, definition}` |
| POST / PUT | `/workflows`, `/workflows/{id}` | workflow editors | `WorkflowRequest`; `definition` adds `elseActions` (run when conditions do not match), `schedule {intervalMinutes 15–10080}` (required for `SCHEDULED`) and `layout {nodeId: {x, y}}` (builder positions; a layout-only change does not create a version). Audits `WORKFLOW_PUBLISHED`. |
| POST | `/workflows/{id}/activate` | workflow editors — `{active}` | |
| POST | `/workflows/{id}/rollback` | workflow editors — `{versionNumber}` | |
| POST | `/workflows/{id}/test` | staff — `{incidentId}` | `WorkflowTestResult` adds `branch` (`YES`/`NO`); dry run. |
| GET | `/workflows/executions?workflowId&status&page&size` | readers | `ExecutionDto` adds `branch`. |
| POST | `/workflows/executions/{id}/retry` | workflow editors | |

Scheduled workflows are polled every `WORKFLOW_SCHEDULE_POLL_INTERVAL_MS` (default 60 s, cluster-safe advisory lock); each run evaluates active incidents (at most `app.workflow.scheduled-max-incidents`, round robin) and runs at most once per incident per interval. A workflow that fails after its retries notifies its creator and the system administrators (`WORKFLOW_FAILED`).

### Runbooks
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/runbooks?search&serviceId&category`, `/runbooks/{id}` | readers | `RunbookDto` adds `categories[]`, `prerequisites[]`; steps add `requiresApproval`. |
| POST / PUT | `/runbooks`, `/runbooks/{id}` | admins & managers | `{title,description,serviceId,categories[],prerequisites[],steps[{order,title,instruction,requiresApproval}],requiresApproval,estimatedMinutes,active}` |
| POST | `/runbooks/{id}/executions` | staff — `{incidentId}` | `PENDING_APPROVAL` when the runbook requires approval (routed to `INCIDENT_MANAGER`). |
| GET | `/runbooks/executions?incidentId&runbookId&status&page&size` | readers | **Now `PageResponse<ExecutionDto>`**, newest first. Step states add `requiresApproval`, `approvalStatus` (`NOT_REQUIRED`, `PENDING`, `APPROVED`, `REJECTED`), `approvalId`. |
| POST | `/runbooks/executions/{id}/steps/{order}` | staff — `{done,notes}` | Completing a sensitive step as anyone but an `INCIDENT_MANAGER`/`SYSTEM_ADMIN` creates a `RUNBOOK_STEP` approval and leaves the step `PENDING`; approval completes it, rejection leaves it open. |
| POST | `/runbooks/executions/{id}/complete` | staff — `{outcomeNotes}` | `409` while steps are open or awaiting approval. |
| POST | `/runbooks/executions/{id}/abort` | staff — `{reason}` | |

### Knowledge base
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/knowledge?q&type&category&serviceId&status&sort&page&size` | any user (employees: published only) | `sort`: `popular`, `recent`, `rating`; relevance when `q` is given. Summaries add `averageRating` (null until rated), `ratingCount`, `currentVersion`. |
| GET | `/knowledge/{id}` | any user | `ArticleDetail` adds `linkedIncidentCount`, `myRating`. Increments view count. |
| GET | `/knowledge/stats` | any user | adds `inReview`, `countsByType {GUIDE, SOLUTION, KNOWN_ERROR, FAQ}` (published), `runbookCount` (active). |
| POST / PUT | `/knowledge`, `/knowledge/{id}` | staff | `ArticleRequest` adds `changeNote`. Status `IN_REVIEW` added. Only admins & managers publish/archive or edit published/archived articles; content changes create a new version. |
| POST | `/knowledge/{id}/submit` | staff | DRAFT → IN_REVIEW |
| POST | `/knowledge/{id}/publish`, `/knowledge/{id}/archive` | admins & managers | |
| DELETE | `/knowledge/{id}` | admins | `204`; removes versions and ratings, unlinks citing incidents, audited `KB_ARTICLE_DELETED`. |
| GET | `/knowledge/{id}/versions` | any user with access | `VersionDto[] {version, title, summary, editorName, changeNote, createdAt}`, newest first |
| POST | `/knowledge/{id}/versions/{version}/restore` | admins & managers | Copies the old content into a new version. |
| POST | `/knowledge/{id}/feedback` | any user | `{rating 1–5, comment}` (one rating per user, re-rating replaces it; Release 1 `{helpful}` still accepted as 5/2) → `ArticleDetail`. `helpfulCount`/`notHelpfulCount` = legacy votes + ratings 4–5 / 1–3. |

### SA AI — every response is `AiResult<T>` with `source: "LLM" \| "FALLBACK"`
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/ai/status` | any user | |
| POST | `/ai/incidents/{id}/classify` | staff | |
| POST | `/ai/incidents/{id}/summarize` | readers | |
| POST | `/ai/incidents/{id}/duplicates` | staff | |
| POST | `/ai/incidents/{id}/knowledge` | any user with access | |
| POST | `/ai/incidents/{id}/root-cause` | staff | |
| POST | `/ai/incidents/{id}/resolution` | staff | `ResolutionSuggestion {likelyCause, confidence 0–100, steps[], runbook {id, title, requiresApproval}, articles[], matchedIncidents[{id, number, title, similarity, resolutionNotes}], basis}`; the fallback confidence is computed from the matched evidence. |
| POST | `/ai/workflow-suggestion` | workflow editors | `{goal 5–1000 chars}` → `WorkflowSuggestion {name, description, triggerEvent, definition, explanation}`, validated like a saved workflow and never saved. |
| GET | `/ai/interactions?size` | any user | Own recent successful calls `InteractionDto {id, feature, subject, source, createdAt}` (size ≤ 50, default 10). |
| POST | `/ai/insights?refresh` | readers | Organisation-wide result shared for 10 minutes; `refresh=true` regenerates it. `OperationalInsights` adds `hourly {hour, count}[24]`; each insight adds `confidence` (`HIGH`, `MEDIUM`, `LOW`). |
| POST | `/ai/ask` | readers — `{question}` | |
| POST | `/ai/actions` | staff — `AiActionRequest` → `ApprovalDto` | Applied only after an `INCIDENT_MANAGER` (or `SYSTEM_ADMIN`) approves. |

### Notifications, announcements, search
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/notifications?unreadOnly&page&size` | own | |
| GET | `/notifications/unread-count` | own → `{count}` | |
| POST | `/notifications/{id}/read`, `/notifications/read-all` | own | |
| GET | `/notifications/preferences` | own | `PreferenceDto[] {type, label, description, inApp, email, emailDefault}` for the 19 categories (`INCIDENT_ASSIGNED, INCIDENT_COMMENT, INCIDENT_RESOLVED, SLA_AT_RISK, SLA_BREACHED, ESCALATION, APPROVAL_REQUESTED, APPROVAL_DECIDED, WORKFLOW_FAILED, REQUEST_UPDATED, ANNOUNCEMENT`, since Release 2.1 `ACCESS_REQUEST, PASSWORD_RESET_REQUEST` (user administrators), since Release 3 `TEAM_QUEUE` (work waiting unassigned in my team) , `REPORT_READY` (my scheduled report ran), since Release 3 BE-2 `APPROVAL_ESCALATED`, `PROBLEM_UPDATED`, `CHANGE_UPDATED`, `DEPLOYMENT_STATUS`, since Release 3 BE-3 `ALERT_ROUTED`, `ALERT_ESCALATED`, `ASSET_ASSIGNED`, since Release 3 BE-4 `ONCALL_PAGE`, `ONCALL_SHIFT`, `MAJOR_INCIDENT_UPDATE`, `PIR_UPDATED` — 26 categories). `email` (R3) is the effective e-mail channel; `emailDefault` is `true` only for `INCIDENT_ASSIGNED`, `APPROVAL_REQUESTED`, `APPROVAL_DECIDED`, `APPROVAL_ESCALATED`, `ALERT_ESCALATED`, `ONCALL_PAGE`. E-mails go through the outbox (SKIPPED without SMTP). |
| PUT | `/notifications/preferences` | own | Partial map; each value is a boolean (in-app channel, Release 2 format) or `{inApp?: boolean, email?: boolean}` (R3) → updated list; unknown types or other shapes → `400`. Disabled channels are not delivered. |
| GET | `/notifications/stream` | own (Bearer header) | Server-Sent Events: `notification` (NotificationDto JSON, pushed after commit) and `ping` (on connect and every 25 s). |
| GET | `/announcements?activeOnly` | any user | |
| POST / PUT / DELETE | `/announcements`, `/announcements/{id}` | admins & managers | |
| GET | `/search?q` | any user → `SearchResults` (scoped by role) | |

### Integrations
| Method | Path | Access |
|---|---|---|
| GET | `/integrations`, `/integrations/{id}/deliveries` | admins & managers |
| POST / PUT / DELETE | `/integrations`, `/integrations/{id}` | admins → `IntegrationSaveResult` |
| POST | `/integrations/{id}/test` | admins |
| POST | `/integrations/{id}/rotate-token` | admins → `{inboundToken}` |
| POST | `/integrations/inbound/{id}` | `X-Nexus-Token` header — `{title,description,severity,service,source}` → `202 {incidentNumber,duplicate,parentNumber,alertId,alertNumber,alertStatus,occurrences}`. Release 3: recorded as an alert (source `INTEGRATION`, deduplicated by title + service + source) and converted into an incident as before unless a suppression rule matches (`incidentNumber: null`); a repeat of an alert that already has an incident answers with that incident and `duplicate: true` — see "Release 3 — assets, CMDB, alerts" |

### Audit, analytics, administration
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/audit-logs?actor&action&entityType&entityId&outcome&from&to&page&size` | `SYSTEM_ADMIN`, `IT_ADMIN`, `AUDITOR` | |
| GET | `/audit-logs/actions`, `/audit-logs/export` | `SYSTEM_ADMIN`, `IT_ADMIN`, `AUDITOR` | export is CSV |
| GET | `/analytics/dashboard?days&from&to&departmentId` | any user (content scoped by role) | `DashboardDto` adds `from`, `to`, `departmentId`; KPIs add `resolutionRate` (% of incidents created in the period that are RESOLVED/CLOSED and not cancelled) and `firstCallResolution` (% of incidents resolved in the period with at most one assignee and never reopened). |
| GET | `/analytics/overview?days&from&to&departmentId` | readers | `AnalyticsOverview` adds `serviceVolume {date, counts{serviceName: n}}[]`, `serviceNames[]`, `from`, `to`, `departmentId`. |
| GET | `/analytics/forecast?days&horizon&from&to&departmentId` | readers | |
| GET | `/analytics/export?format(csv\|pdf)&days&from&to&departmentId` | report exporters | CSV incident rows or a PDF report (KPIs and breakdowns); audited `ANALYTICS_EXPORTED`. |
| GET | `/admin/settings` | `SYSTEM_ADMIN` | |
| PUT | `/admin/settings/{key}` | `SYSTEM_ADMIN` — `{value}` | Validated keys include `org.name` (1–120 chars), `org.support_email`, `org.timezone` (IANA), `org.business_hours_note`, `notifications.sla_at_risk.notify_managers`, `notifications.major.notify_all_staff`. Audits `SETTINGS_CHANGED`. |
| GET | `/admin/incident-categories` | readers | `CategoryConfigDto[] {code, name, description, defaultDepartmentId, defaultDepartmentName, keywords[], active}` |
| PUT | `/admin/incident-categories` | admins | **Full list**: JSON array of `{code, name, description, defaultDepartmentId, keywords[], active}` → the updated list. Unknown or duplicate codes → `400`; `OTHER` cannot be deactivated; codes not in the array are left unchanged. Keywords extend the classifier, the default department routes incidents without a service, inactive categories are rejected on create. Audits `INCIDENT_CATEGORIES_UPDATED`. |
| GET | `/admin/ai` | `SYSTEM_ADMIN` | `{provider, model, keyConfigured, llmEnabled, rateLimitPerMinute, timeoutSeconds}` — API keys are never returned. |
| PUT | `/admin/ai` | `SYSTEM_ADMIN` | Exactly `{llmEnabled: boolean}`; any other field (e.g. an API key) → `400`. Keys are configured only in the server environment. |
| GET | `/admin/system-health` | admins | `{status UP\|DEGRADED\|DOWN, version, uptimeSeconds, database {status, latencyMs, schemaVersion}, redis {enabled, status}, slaScheduler {enabled, lastRunAt}, workflowEngine {pendingRetries, failedLast24h}, ai {provider, keyConfigured}, counts {users, incidents, activeIncidents}, email {configured, status CONFIGURED\|NOT_CONFIGURED, pending, failedLast24h, lastSentAt}, reportScheduler {enabled, lastRunAt}}` (R3 adds `email`, `reportScheduler`) |
| POST | `/admin/demo/payroll-outage` | `SYSTEM_ADMIN` | Runs the demo scenario through the real engines → `ScenarioResult` |
| POST | `/admin/demo/incidents/{id}/simulate-elapsed` | `SYSTEM_ADMIN` | `{minutes}`; demo incidents only, shifts SLA clock, audited and labelled `SIMULATED` |
| GET | `/health` | public | `{service: "SA API", version: "2.0.0", status, database}` |
| GET | `/actuator/health` (server root, not under `/api`; also `/actuator/health/liveness`, `/actuator/health/readiness`) | public | `{status}` only (`show-details: never`) |

## Release 3 foundations ([RELEASE3_SPEC](docs/RELEASE3_SPEC.md) §1, §2, §4 — migration `V6__release3_foundations.sql`)

Product name **SA** in every user-facing backend string (health `service: "SA API"`, OpenAPI title "SA API", PDF/CSV
headers, e-mails, notification texts, AI prompts). V6 renames the setting `org.name` from `SA 360` to `SA` only when it
was never changed. Technical identifiers are unchanged (package `com.nexus360`, artifact `nexus360-api`, cookie
`nexus_refresh`, header `X-Requested-With: nexus360`, `X-Nexus-*` webhook headers, demo domain `demo.nexus360.io`).

### Teams
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/teams?departmentId&active&search` | readers | `TeamDto[]` ordered by name |
| GET | `/teams/mine` | any user | `TeamRef[] {id, code, name, departmentId, departmentName, memberRole}` (active teams I belong to) |
| GET | `/teams/{id}` | readers | `TeamDto` |
| POST | `/teams` | team admins | `TeamRequest` → `201 TeamDto`; duplicate code or name → `409` |
| PUT | `/teams/{id}` | team admins | `TeamRequest`; `code` cannot change (`400`) |
| POST | `/teams/{id}/members` | team admins | `{userId, memberRole?: MEMBER\|LEAD}` → `TeamDto`; members must be active staff (`400`); already a member → `409` |
| PUT | `/teams/{id}/members/{userId}` | team admins | `{memberRole}` → `TeamDto` |
| DELETE | `/teams/{id}/members/{userId}` | team admins | `204` (removing the lead clears `leadId`) |

- `TeamDto = {id, code, name, description, departmentId, departmentCode, departmentName, leadId, leadName, email, active, departmentDefault, memberCount, openIncidents, unassignedIncidents, openRequests, demo, members: TeamMemberDto[]}`; `TeamMemberDto = {userId, fullName, email, role, jobTitle, memberRole, active, openAssignments, joinedAt}` (leads first). `unassignedIncidents` = the team queue.
- `TeamRequest = {code (^[A-Z0-9_-]{2,40}$), name 2–120, departmentId, leadId?, email?, description? ≤500, active? (default true), departmentDefault?}`. The lead must be active staff and becomes a `LEAD` member. One active default team per department: setting `departmentDefault: true` clears it on the department's other teams; inactive teams cannot be the default.
- IT managers maintain only teams of departments they manage or belong to (`403` otherwise). Audit `TEAM_CREATED`, `TEAM_UPDATED`, `TEAM_MEMBER_ADDED`, `TEAM_MEMBER_UPDATED`, `TEAM_MEMBER_REMOVED`.
- **Routing**: a new incident goes to the affected service's `defaultTeam` (else the default team of the owning / category department), then to the team's least-loaded active member (support agents first, then other staff members). Without a team, or when the team has nobody available, the department's least-loaded agent is used as before. `auto-assign` keeps the incident's current team. Timeline/history records `team` changes as `ASSIGNED` events; `INCIDENT_ASSIGNED` workflow events carry `changes.team`.
- Demo teams (all `demo: true`): Payroll & HR Apps (`PAYROLL-HR`, IT-APPS default), Collaboration Apps (`COLLAB-APPS`), Network Operations (`NET-OPS`), Service Desk L1 (`SD-L1`), Database Operations (`DB-OPS`), Identity & Security (`ID-SEC`), Desktop Support (`DESKTOP`), Facilities (`FACILITIES`). Demo users added: `anil.kumar` (CHANGE_MANAGER, IT-APPS), `deepa.nair` (ASSET_MANAGER, IT-EUC).

### My Work — staff only
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/my-work?limit` | staff | `limit` per section (default 10, max 50) |

Response (JSON object; sections in this order, more are added by later modules):
`{generatedAt, teams: TeamRef[], incidents: Section<IncidentSummary>, teamQueue: Section<IncidentSummary>, requests: Section<ServiceRequestDto>, teamRequests: Section<ServiceRequestDto>, approvals: Section<ApprovalDto>}` with `Section<T> = {total, items: T[]}`.
- `incidents`: active incidents assigned to me (most urgent first); `teamQueue`: active, unassigned top-level incidents of my teams; `requests`: open service requests assigned to me; `teamRequests`: unassigned APPROVED/IN_FULFILLMENT requests of my teams; `approvals`: pending approvals routed to my role that I did not request (segregation of duties).
- Extension point: any bean implementing `com.nexus360.mywork.MyWorkContributor` (`key()`, `order()` — use ≥ 100, `appliesTo(actor)`, `contribute(context)` returning a `Section`) adds a section; planned keys `problems`, `changes`, `alerts`, `onCallShifts`, `actionItems`.

### Employee portal
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/portal/overview` | any user | Only the caller's own records plus organisation-wide public information |

`{generatedAt, openIncidents: Section<IncidentSummary> (active incidents I reported or created, newest first, 5), openRequests: Section<ServiceRequestDto> (my open requests), awaitingApproval: Section<{id, type, referenceNumber, title, approverRole, createdAt}> (pending approvals of my requests), serviceStatus: {overall, counts: {OPERATIONAL, DEGRADED, OUTAGE, MAINTENANCE}, services: [{id, code, name, status, statusReason}], maintenance: [{id, title, startsAt, endsAt, status, components: string[]}] (next 14 days, public components)}, recommendedArticles: ArticleSummary[] (≤5, published only: matches for my latest open incident, then the most viewed)}`.
Extension point: `com.nexus360.portal.PortalContributor` beans (same pattern as My Work).

### E-mail (outbox)
Optional SMTP (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `MAIL_STARTTLS`; links use `APP_PUBLIC_URL`, default the first `FRONTEND_ORIGIN`). Every message is written to `email_outbox` in the business transaction (`status PENDING|SENT|FAILED|SKIPPED`, `attempts`, `next_attempt_at`, `last_error`) and delivered by a scheduler (`MAIL_POLL_INTERVAL_MS`; rows locked `FOR UPDATE SKIP LOCKED`, so several instances never send twice) with exponential backoff (30 s, 1, 2, 4 … min, ≤ 1 h) up to `MAIL_MAX_ATTEMPTS` (5), then `FAILED`. **Without `MAIL_HOST` messages are stored as `SKIPPED` and never reported as sent.** Templates are branded HTML with inline CSS and a plain-text part; all user content is HTML-escaped and only absolute http(s) links are rendered. Messages carrying one-time links (invitations, resets, status confirmations/updates) are *sensitive*: their bodies are replaced by a placeholder once final. Finished rows are purged after 30 days.

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/admin/email/status` | admins | `{configured, fromAddress (null when not configured), pending, sentLast24h, failedLast24h, skippedLast24h, lastSentAt, lastRunAt}` — never credentials |
| GET | `/admin/email/outbox?status&page&size` | admins | `PageResponse<OutboxItem {id, category, to, subject, status, attempts, nextAttemptAt, lastError, sentAt, createdAt, sensitive, attachmentName}>` (no bodies) |
| POST | `/admin/email/test` | `SYSTEM_ADMIN` | `{to}` → `202 {id, status}`; `409` when SMTP is not configured. Audit `EMAIL_TEST_QUEUED` |

Categories: `NOTIFICATION`, `INVITATION`, `PASSWORD_RESET`, `STATUS_SUBSCRIPTION`, `STATUS_UPDATE`, `REPORT`, `TEST`. Notification e-mails follow the per-category `email` preference (see Notifications).

### Status page
Internal (`/api/status-page`, any signed-in user reads; status page editors write):

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/status-page` | any user | `StatusPageView {settings, overall, components: ComponentDto[], maintenance: MaintenanceDto[], updates: UpdateDto[], subscribers: {confirmed, pending} \| null, emailConfigured, generatedAt}` — employees see public components, public updates and maintenance on public components only; `subscribers` for editors only |
| PUT | `/status-page/settings` | status page editors | `{title 1–120, description? ≤1000, publicEnabled}` → `SettingsDto {title, description, publicEnabled, updatedAt}` |
| GET | `/status-page/components` | any user | `ComponentDto[]` (same filtering) |
| POST / PUT / DELETE | `/status-page/components`, `/status-page/components/{id}` | status page editors | `ComponentRequest {serviceId, displayName ≤120, groupName? ≤80, description? ≤500, sortOrder?, publicVisible? (default true)}`; one component per service (`409`); the service cannot change on PUT |
| POST | `/status-page/maintenance` | status page editors | `MaintenanceRequest {title ≤200, description? ≤2000, startsAt, endsAt (> startsAt, ≤ 31 days), componentIds[] (1–50), notifySubscribers? (default true)}` → `201 MaintenanceDto` |
| PUT | `/status-page/maintenance/{id}` | status page editors | same body (`notifySubscribers` default false); completed/cancelled windows → `409` |
| POST | `/status-page/maintenance/{id}/status` | status page editors | `{status}`: SCHEDULED → IN_PROGRESS \| COMPLETED \| CANCELLED; IN_PROGRESS → COMPLETED \| CANCELLED; else `409`. Completing early sets `endsAt` to now |
| POST | `/status-page/updates` | status page editors | `UpdateRequest {title ≤200, status INVESTIGATING\|IDENTIFIED\|MONITORING\|RESOLVED, message ≤5000, incidentId?, publicVisible? (default false)}` → `201 UpdateDto`; public updates are e-mailed to confirmed subscribers while the page is public |
| PUT | `/status-page/updates/{id}` | status page editors | same body (no e-mail) |

- `ComponentDto = {id, serviceId, serviceCode, serviceName, displayName, groupName, description, sortOrder, publicVisible, status (OPERATIONAL\|DEGRADED\|OUTAGE\|MAINTENANCE, from service health), statusReason, uptime90d (mean of the daily values), history: UptimeDay[90] {date, availability, maintenance}, demo}` — `availability` is the service's incident-based daily availability (same numbers as `GET /services/{id}`), `maintenance` = a non-cancelled window touched the day.
- `MaintenanceDto = {id, title, description, startsAt, endsAt, status (effective: a SCHEDULED window is IN_PROGRESS between start and end and COMPLETED afterwards), storedStatus, components: [{id, displayName, serviceId, publicVisible}], demo}`; listed when ending within the last 7 days or later. Services of an in-progress window are `MAINTENANCE` everywhere (services API, Command Center, portal).
- `UpdateDto = {id, title, status, message, publicVisible, incidentId, incidentNumber, postedAt, demo}` (last 30 days, newest first).
- Audit: `STATUS_PAGE_SETTINGS_UPDATED`, `STATUS_COMPONENT_CREATED/UPDATED/DELETED`, `MAINTENANCE_SCHEDULED`, `MAINTENANCE_UPDATED`, `MAINTENANCE_STATUS_CHANGED`, `STATUS_UPDATE_POSTED`, `STATUS_UPDATE_EDITED`.

Public (no session; POSTs share the sign-in rate limit; everything `404 {"message": "The status page is not published"}` while `publicEnabled` is false):

| Method | Path | Body → Response |
|---|---|---|
| GET | `/public/status` | `PublicStatus {title, description, overall, generatedAt, components: [{id, name, group, status, uptime90d, history: UptimeDay[90]}], maintenance: [{id, title, description, startsAt, endsAt, status, components: string[]}], updates: [{id, title, status, message, postedAt}], subscriptionsAvailable}` — public components/updates only, no incident data, no status reasons |
| POST | `/public/status/subscribe` | `{email}` → `202 {message}` always *"Thank you. If this address can receive status updates, a confirmation e-mail is on its way; the subscription starts once you open the link in it."* Double opt-in: a confirmation e-mail with `APP_PUBLIC_URL/status?confirm=<token>` (valid 48 h). Without SMTP nothing is stored. Already confirmed → nothing happens |
| POST | `/public/status/confirm` | `{token}` → `200 {message}` always *"If the link was valid, your subscription to status updates is now confirmed."* |
| POST | `/public/status/unsubscribe` | `{token}` → `200 {message}` always *"If the link was valid, you will no longer receive status updates."*; the subscriber row is deleted. Every update/maintenance e-mail contains `APP_PUBLIC_URL/status?unsubscribe=<token>` |

The SPA's `/status` page reads `confirm` / `unsubscribe` query parameters and POSTs them (state never changes on GET). Only SHA-256 hashes of both tokens are stored. Audit `STATUS_SUBSCRIBE_REQUESTED` (`details {recorded, outcome: CREATED|CONFIRMATION_RESENT|ALREADY_CONFIRMED|MAIL_NOT_CONFIGURED}`), `STATUS_SUBSCRIPTION_CONFIRMED`, `STATUS_UNSUBSCRIBED`.

### Scheduled reports
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/reports/types` | any user | `ReportTypeDto[] {type, label, description, available, formats, filterKeys}` for `ANALYTICS_OVERVIEW, SLA, INCIDENTS` (available) and `CHANGES, PROBLEMS, ASSETS, ONCALL` (available once their module registers a generator; Release 3 BE-2/BE-3/BE-4: CHANGES, PROBLEMS, ASSETS and ONCALL are available — every type) |
| GET | `/reports/schedules` | any user | `ScheduleDto[]`: all for report exporters, otherwise own |
| GET | `/reports/schedules/{id}` | owner or report exporters | `ScheduleDto` (others `404`) |
| POST | `/reports/schedules` | report schedulers | `ScheduleRequest` → `201 ScheduleDto`; owner = caller |
| PUT / DELETE | `/reports/schedules/{id}` | owner or admins (report schedulers URL gate) | other exporters `403`, others `404`; DELETE removes the runs |
| POST | `/reports/schedules/{id}/run` | owner or report exporters | Runs now (synchronously) for the period ending yesterday → `ReportRunDto` (a generator failure gives `status: FAILED` + `error`, not an HTTP error) |
| GET | `/reports/schedules/{id}/runs?size` | owner or report exporters | `ReportRunDto[]` newest first (max 100) |
| GET | `/reports/runs/{runId}/download` | owner or report exporters | The file (`Content-Disposition: attachment`; `application/pdf` or `text/csv;charset=UTF-8`); `409` when the run has no file. Audit `REPORT_DOWNLOADED` |

- `ScheduleRequest = {name ≤150, reportType, format PDF|CSV, frequency DAILY|WEEKLY|MONTHLY, runHour? 0–23 (default 6, organisation time zone), dayOfWeek? 1–7 (WEEKLY, 1 = Monday, default 1), dayOfMonth? 1–28 (MONTHLY, default 1), filters? {departmentId}, recipients? (≤20 addresses of active staff or auditor accounts), active? (default true)}`; unavailable type → `400 fieldErrors[reportType]`; unknown filter keys or recipients → `400`.
- `ScheduleDto = {id, name, reportType, reportTypeLabel, format, frequency, runHour, dayOfWeek, dayOfMonth, filters, recipients, ownerId, ownerName, nextRunAt, lastRunAt, active, demo, lastRun: ReportRunDto|null}`.
- `ReportRunDto = {id, scheduleId, trigger SCHEDULE|MANUAL, scheduledFor, status RUNNING|SUCCEEDED|FAILED, periodFrom, periodTo, fileName, contentType, sizeBytes, error, emailedTo, startedAt, finishedAt, demo}`. Periods: DAILY = the previous day, WEEKLY = the previous 7 days, MONTHLY = the previous calendar month.
- Execution: every `REPORT_POLL_INTERVAL_MS` one instance (PostgreSQL advisory lock 360006) claims due schedules (`FOR UPDATE SKIP LOCKED`), advances `nextRunAt` and inserts the run (unique per schedule and due time), generates the file in its own transaction (PDF via OpenPDF, CSV via the formula-safe writer), stores it, queues it as an e-mail attachment to every recipient (`emailedTo` counts deliverable ones; 0 without SMTP) and notifies the owner (`REPORT_READY`). The last 30 runs per schedule are kept. Audit `REPORT_SCHEDULE_CREATED/UPDATED/DELETED`, `REPORT_RUN_COMPLETED`, `REPORT_RUN_FAILED`.
- Extension point: a bean implementing `com.nexus360.report.ReportGenerator` (`type()`, `description()`, `formats()`, `filterKeys()`, `byte[] generate(Request {format, from, to, zone, filters, title, generatedAt})`) makes its `ReportType` available; use `ReportPdf.render(...)` and `Csv.bytes(...)`.

## Release 3 — approvals, forms, problems, changes, deployments ([RELEASE3_SPEC](docs/RELEASE3_SPEC.md) §5 BE-2 — migration `V7__approvals_forms_problems_changes_deployments.sql`)

All lists are `PageResponse<T>` (`page`, `size` ≤ 100, `sort=field,asc|desc` from an allow-list; unknown sort fields or enum values → `400`).
Every mutation is audited. New role groups (`Roles` / `*_ROLES`): **change raisers** = change editors + `INCIDENT_MANAGER`
(incident managers raise EMERGENCY changes only), **approval-flow editors** = `SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, CHANGE_MANAGER`,
**form editors** = `SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER`. Readers = staff + `AUDITOR`. Employees get `403` on `/problems`,
`/changes`, `/deployments`, `/releases`, `/forms`, `/approval-flows`.

### Approval engine v2
An approval has ordered **stages**; each stage has one **task** per approver entry. `mode`: `PARALLEL` opens all tasks at once,
`SEQUENTIAL` one at a time in order. `quorum`: `ANY` (the first decision — approve or reject — decides the stage), `ALL` (every task;
one rejection rejects), `N` (`requiredCount` approvals; rejected once unreachable). Approver `kind`: `USER {userId}`, `ROLE {role}`
(anyone with the role), `TEAM {teamId}` (any active member), `MANAGER` (the requester's department manager, resolved when the
approval is created; IT managers when there is none). A stage with a `condition` (workflow condition model `{match: ALL|ANY,
rules: [{field, operator, value}]}`, evaluated against the subject's facts, e.g. `changeRisk`) is `SKIPPED` when it does not match;
when no stage applies the approval is approved at once. Rules: the requester never decides (segregation of duties); a person decides
at most one task per stage (list a role twice to need two people); a rejection needs a `comment`; `SYSTEM_ADMIN` sees every approval
and may decide any open task (history flags `administratorDecision`). Release 2 approvals (`ApprovalService.request`, existing rows
via V7 and the start-up backfill) are one stage with one `ROLE` task — same routing, handlers and endpoints as before.

**Deadlines**: `deadlineHours` sets the stage `dueAt`; an overdue stage is escalated **once** (scheduler every
`APPROVAL_ESCALATION_POLL_INTERVAL_MS` / `app.approvals.escalation-poll-interval-ms` (60 s), PostgreSQL advisory lock 360007,
`escalated_at` claimed atomically; `app.approvals.scheduler-enabled`): open approvers get `APPROVAL_ESCALATED` reminders,
`escalation.notifyManager` notifies their managers (user: department manager; team: lead + department manager; role: system admins),
`escalation.notifyRole` notifies a role (default without `escalation`: managers + `SYSTEM_ADMIN`), `escalation.autoReject` rejects
the approval ("Automatically rejected: the approval deadline passed"). **Delegation**: per task (`POST /approvals/tasks/{id}/delegate`)
or out-of-office (`/approvals/delegations`: tasks addressed to the delegator personally go to the delegate during the window).

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/approvals?status&type&scope&search&overdue&page&size&sort` | any signed-in user | `scope`: `mine` (default — approvals with a task routed to me by user/role/team/delegation or decided by me; with `status=PENDING` only those with a task open now; includes my own requests with `canDecide:false`), `requested`, `all` (`SYSTEM_ADMIN`, `IT_ADMIN`, `AUDITOR`; others `403`). Sort: `createdAt` (default desc), `dueAt`, `title`, `status`, `type`, `decidedAt`. → `PageResponse<ApprovalDto>` |
| GET | `/approvals/counts` | any user | `{pendingTasks, overdueTasks, requestedPending}` |
| GET | `/approvals/tasks?status=PENDING\|DECIDED&type&overdue&page&size&sort` | any user | **Task inbox across subject types**: `PENDING` = tasks I can decide now (never my own requests); `DECIDED` = tasks I decided. → `PageResponse<TaskInboxItem>` |
| GET | `/approvals/{id}` | routed approvers, requester, `SYSTEM_ADMIN`, `IT_ADMIN`, `AUDITOR` (else `404`) | `ApprovalDetail {approval: ApprovalDto, stages: StageDto[], history: EventDto[]}` |
| POST | `/approvals/{id}/decision` | approver | Release 2 endpoint: decides the caller's open task of the active stage. `403` not routed / requester (`"Segregation of duties: you cannot approve your own request"`), `409` already decided or caller already decided this stage, `400` rejection without comment |
| POST | `/approvals/tasks/{taskId}/decision` | the task's approver | `{approved, comment ≤1000}` → `ApprovalDto` (same rules) |
| POST | `/approvals/tasks/{taskId}/delegate` | the task's approver | `{userId, comment?}` → `ApprovalDto`; target must be active, not the requester, not yourself, without a task in the stage (`400 fieldErrors[userId]`) |
| GET | `/approvals/delegations` | any user | `DelegationDto[]` (mine and to me, current and upcoming) |
| POST | `/approvals/delegations` | any user | `{delegateId, startsAt, endsAt, reason? ≤500}` → `201 DelegationDto`; ≤ 90 days, must end in the future, overlapping → `409` |
| DELETE | `/approvals/delegations/{id}` | delegator or `SYSTEM_ADMIN` | `204` (revoked) |
| POST | `/approvals/escalations/run` | `SYSTEM_ADMIN` | `{escalated: n}` (runs the deadline scheduler now) |
| GET | `/approval-flows?subjectType&active`, `/approval-flows/{id}` | readers | `FlowDto[]` / `FlowDto {id, code, name, description, subjectType, stages, active, systemFlow, demo, createdAt, updatedAt, userNames {userId: name}}` — `userNames` = display names of USER approvers |
| POST / PUT | `/approval-flows`, `/approval-flows/{id}` | approval-flow editors | `FlowSaveRequest {code ^[A-Z0-9_-]{2,40}$ (immutable), name ≤120, description? ≤500, subjectType?, stages: StageDef[1..10], active?}`; `requiredCount > approvers`, autoReject without deadline, unknown users/teams → `400 fieldErrors[stages[i]…]`; duplicate code `409`. System flows cannot be deactivated |
| DELETE | `/approval-flows/{id}` | approval-flow editors | `204`; system flows `409` |

- `ApprovalDto = {id, type, referenceId, referenceNumber, title, details, requestedByName, approverRole, status (PENDING|APPROVED|REJECTED|CANCELLED), decidedByName, decisionComment, decidedAt, createdAt, payload, requestedById, flowName, currentStage, stageCount, currentStageName, dueAt, overdue, link, canDecide, myTaskId, pendingWith: string[], demo}` (Release 2 fields first, unchanged; `approverRole` is null for flows without a role approver).
- `StageDef = {name ≤120, mode SEQUENTIAL|PARALLEL, quorum ANY|ALL|N, requiredCount? 1–10 (N), approvers: [{kind USER|ROLE|TEAM|MANAGER, userId?, role?, teamId?}] (1–10), condition?, deadlineHours? 1–720, escalation? {notifyManager, notifyRole?, autoReject}}`; `FlowDto = {id, code, name, description, subjectType, stages: StageDef[], active, systemFlow, demo, createdAt, updatedAt}`.
- `StageDto = {id, order, name, mode, quorum, requiredCount, status (WAITING|ACTIVE|APPROVED|REJECTED|SKIPPED|CANCELLED), condition, deadlineHours, dueAt, escalation, escalatedAt, startedAt, completedAt, approvedCount, tasks: TaskDto[]}`; `TaskDto = {id, seq, approverKind (USER|ROLE|TEAM), approverUserId, approverUserName, approverRole, approverTeamId, approverTeamName, approverLabel, status (WAITING|PENDING|APPROVED|REJECTED|SKIPPED|CANCELLED), decidedById, decidedByName, decidedAt, comment, delegatedFromId, delegatedFromName, delegatedAt, canDecide}`; `EventDto = {id, type (REQUESTED, STAGE_STARTED, STAGE_SKIPPED, TASK_APPROVED, TASK_REJECTED, DELEGATED, STAGE_APPROVED, STAGE_REJECTED, ESCALATED, AUTO_REJECTED, APPROVED, REJECTED, CANCELLED), stageOrder, taskId, actorName, comment, details, at}`.
- `TaskInboxItem = {taskId, taskStatus, approverLabel, stageOrder, stageName, dueAt, overdue, canDecide, approval: ApprovalDto}`; `DelegationDto = {id, delegatorId, delegatorName, delegateId, delegateName, startsAt, endsAt, reason, active, createdAt}`.
- System flows (V7): `CHANGE_NORMAL` — stage 1 "Change authority" (`CHANGE_MANAGER` or `IT_MANAGER`, ANY, 48 h), stage 2 "Change Advisory Board" only when `changeRisk = HIGH` (N = 2 of IT manager, IT manager, change manager; 72 h); `CHANGE_EMERGENCY` — "Emergency CAB" (change, IT or incident manager, ANY, 2 h). Audit: `APPROVAL_REQUESTED`, `APPROVAL_APPROVED`, `APPROVAL_REJECTED`, `APPROVAL_CANCELLED`, `APPROVAL_DELEGATED`, `APPROVAL_ESCALATED`, `APPROVAL_DELEGATION_CREATED/REVOKED`, `APPROVAL_FLOW_CREATED/UPDATED/DELETED`.
- Workflow action **`REQUEST_APPROVAL`** `{flowCode, title?}`: on a change (trigger `CHANGE_REQUEST_CREATED`) it requests the CAB approval when the change is SUBMITTED or ASSESSMENT (otherwise SKIPPED); on an incident or service request it requests a `WORKFLOW` sign-off whose decision is recorded as an internal work note (`payload.subjectKind`).

### Dynamic form builder
Form definitions are versioned: saving changed `fields` creates version n+1 (`changeNote`); a name-only change does not. Catalogue items
(request types) use a form's current version, mirrored into `CatalogItemDto.formFields`; every request stores `formDefinitionId`
+ `formVersion`. V7 gave each existing item its own form `ITEM-<id>` (version 1 = its fields, unchanged); items created by Release 2
code paths get one at start-up. Server-side validation is authoritative.

`FormField = {key ^[a-zA-Z][a-zA-Z0-9_]{0,39}$, label ≤80, type text|textarea|number|email|date|select|multiselect|checkbox|file|user,
required, options? (select, multiselect; ≤50, unique), placeholder?, helpText? ≤300, min?, max? (number: value; text/textarea/email:
length; multiselect: selections; file: files, 1–10, default 5), pattern? (Java regex on the whole value, time-limited), minDate?, maxDate?
(YYYY-MM-DD, today, today+N, today-N), showIf? {field (an earlier key), op EQUALS|NOT_EQUALS|IN|CONTAINS|NOT_EMPTY|EMPTY, value?},
accept? (file extensions ⊂ png, jpg, jpeg, gif, pdf, docx, xlsx, txt, log, csv, json), roles? (user picker restriction)}` — the Release 2
shape `{key,label,type,required,options,placeholder}` is still valid; null properties are omitted in JSON.

Validation: hidden fields (`showIf` not met, also when the referenced field is hidden) are neither validated nor stored; unknown keys,
type, required, ranges, pattern, options, dates; `user` values are a user id → stored `{id, name}` (active, role-restricted); `file` values
are ids of the submitter's own, unused uploads → stored `[{id, fileName, contentType, sizeBytes}]` and linked to the request. Errors:
`400 {message: <first problem, Release 2 wording e.g. "'Needed from' is required">, fieldErrors: [{field: "formData.<key>", message}]}`.

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/forms?search&template&active&page&size&sort` | readers | `PageResponse<FormSummary {id, code, name, description, template, active, currentVersion, fieldCount, catalogItemCount, demo, updatedAt}>`; sort `name` (default asc), `code`, `updatedAt`, `createdAt` |
| GET | `/forms/{id}` | readers | `FormDetail {id, code, name, description, template, active, currentVersion, fields: FormField[], catalogItems: [{id, name, active}], demo, createdByName, createdAt, updatedAt}` |
| GET | `/forms/{id}/versions`, `/forms/{id}/versions/{n}` | readers | `[{version, fieldCount, changeNote, createdByName, createdAt}]`, `{version, fields, changeNote, createdByName, createdAt}` |
| POST | `/forms` | form editors | `FormRequest {code ^[A-Z0-9_-]{2,40}$, name ≤150, description? ≤1000, template?, active?, fields? (≤40), changeNote? ≤500, copyFromId?}` → `201 FormDetail` (`copyFromId` without `fields` copies another form/template); duplicate code `409`; definition errors `400 fieldErrors[fields[i]…]` |
| PUT | `/forms/{id}` | form editors | same body (`code` immutable; `fields` omitted = unchanged); deactivating a form used by active items `409` |
| POST | `/forms/{id}/validate` | readers | `{formData}` → `{valid: true, formData}` or `400` (preview; user/file fields checked for shape only) |
| GET | `/catalog/items/{id}/form` | any user allowed to submit the item (else `404`) | `CatalogFormDto {catalogItemId, catalogItemName, formDefinitionId, formVersion, fields, canSubmit}` |
| POST | `/service-requests/validate` | any user | `{catalogItemId, formData}` → `{valid, formData}` — dry run of the submission rules (incl. permissions `403`) |
| POST | `/service-requests/uploads` | any user except auditors | multipart `file` (≤ 5 MB, extension + signature allow-list as incident attachments, `413`/`415`) → `201 StagedUpload {id, fileName, contentType, sizeBytes, createdAt}`; submit the `id` as the file field value; unused uploads expire after 24 h; ≤ 20 open uploads per user (`409`) |
| GET | `/service-requests/{id}/attachments` | requester or staff/auditors | `RequestAttachmentDto[] {id, fileName, contentType, sizeBytes, fieldKey, uploadedByName, createdAt}` |
| GET | `/service-requests/{id}/attachments/{aid}/download` | requester or staff/auditors | file, `Content-Disposition: attachment`, `nosniff`; audit `REQUEST_FILE_DOWNLOADED` |
| GET | `/service-requests/user-options?q&roles` | any user | user picker: `UserOption[] {id, fullName, departmentName, role}` (≥ 2 characters, ≤ 20, no e-mail addresses) |

- Catalogue (Release 2 endpoints): `CatalogItemDto` adds `formDefinitionId, formCode, formVersion, allowedRoles: Role[], allowedDepartmentIds: number[], canSubmit`; `CatalogItemRequest` adds `formDefinitionId?` (link an active form; wins over `formFields`), `allowedRoles?`, `allowedDepartmentIds?` (empty = everyone). Without `formDefinitionId`, `formFields` keep working: they update the item's own form (new version on change) or fork a shared form when changed. **Request-type permissions**: items an employee/staff member may not submit are hidden from `GET /catalog/items` (admins see all with `canSubmit`) and `POST /service-requests` → `403`. `ServiceRequestDto` adds `formDefinitionId, formVersion`.
- Audit: `FORM_CREATED`, `FORM_UPDATED`, `REQUEST_FILE_UPLOADED`, `REQUEST_FILE_DOWNLOADED`; `CATALOG_ITEM_*` add `form`, `restricted`.

### Problems (`PRB-####`)
Lifecycle (server-side): `NEW → INVESTIGATING | CLOSED`, `INVESTIGATING → ROOT_CAUSE_IDENTIFIED | CLOSED`, `ROOT_CAUSE_IDENTIFIED →
KNOWN_ERROR | RESOLVED | INVESTIGATING`, `KNOWN_ERROR → RESOLVED | INVESTIGATING`, `RESOLVED → CLOSED | INVESTIGATING`; other moves `409`.
ROOT_CAUSE_IDENTIFIED needs `rootCause`, KNOWN_ERROR `rootCause` + `workaround` (sets `knownError`, `knownErrorAt`), RESOLVED
`permanentFix` (`400 fieldErrors[...]`); CLOSED only by admins & managers (`403`), from NEW/INVESTIGATING it is a cancellation with a
required `note` (`closureNote "Cancelled: …"`), from RESOLVED all corrective actions must be DONE/CANCELLED (`409`). An incident belongs
to at most one problem (`409`).

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/problems?search&status(csv)&priority&ownerId&teamId&serviceId&knownError&scope(all\|mine\|team)&page&size&sort` | readers | `PageResponse<ProblemSummary>`; sort `createdAt` (default desc), `updatedAt`, `priority`, `status`, `number`, `title` |
| GET | `/problems/{id}`, `/problems/{id}/history` | readers | `ProblemDetail`, `HistoryDto[] {at, actorName, action, fromValue, toValue, note}` |
| POST | `/problems` | problem editors | `ProblemRequest` → `201 ProblemDetail` (status NEW; `incidentIds` linked; their services are added) |
| PUT | `/problems/{id}` | problem editors | `ProblemRequest` (null lists unchanged; closed `409`) |
| POST | `/problems/{id}/transition` | problem editors (CLOSED: admins & managers) | `{status, note?, rootCause?, workaround?, permanentFix?, publishKnownError?}` → `ProblemDetail` |
| POST | `/problems/{id}/incidents`, DELETE `/problems/{id}/incidents/{incidentId}` | problem editors | `{incidentIds: number[1..200]}` → `ProblemDetail` |
| POST | `/problems/{id}/actions`, PUT `/problems/{id}/actions/{actionId}` | problem editors | `{title ≤200, description? ≤2000, actionType? CORRECTIVE\|PREVENTIVE, ownerId? (active staff), dueDate?, status? OPEN\|IN_PROGRESS\|DONE\|CANCELLED}` → `201`/`200 ActionDto`; owner notified (`PROBLEM_ACTION_ASSIGNED`) |
| POST | `/problems/{id}/known-error-article` | problem editors | `{publish?}` → `201 ProblemDetail`: creates the KB `KNOWN_ERROR` article (symptoms, root cause, workaround, permanent fix; tags `known-error`, number) — `PUBLISHED` when a knowledge publisher asks to publish, `IN_REVIEW` for other staff, else `DRAFT`. Not a known error / already linked → `409`. Also via `publishKnownError: true` on the KNOWN_ERROR transition |
| GET | `/problems/recurring?days&minSize` | readers | `RecurringCluster[]`: top-level, non-cancelled incidents of the last `days` (setting `problem.recurring.window_days`, 30) without a problem, per service (or category), greedily clustered by text similarity ≥ `problem.recurring.similarity` (0.42); clusters ≥ `minSize` (`problem.recurring.min_incidents`, 3), largest first |
| POST | `/problems/from-cluster` | problem editors | `{incidentIds, title?, priority?, ownerId?}` → `201 ProblemDetail` (title "Recurring: …", priority = most urgent incident) |
| GET | `/problems/analytics?days` (default 90) | readers | `ProblemAnalytics` |
| POST | `/ai/problems/{id}/root-cause` | problem editors | `AiResult<RootCauseSuggestion>` — LLM (validated) or the labelled deterministic fallback (recorded root causes of the linked incidents ranked by frequency, timing and symptom factors) |

- `ProblemSummary = {id, number, title, status, priority, category, ownerId, ownerName, teamId, teamName, knownError, incidentCount, openActionCount, services: [{id, code, name}], createdAt, updatedAt, demo}`.
- `ProblemDetail = {id, number, title, description, status, priority, category, ownerId, ownerName, teamId, teamName, departmentId, departmentName, rootCause, workaround, permanentFix, knownError, knownErrorAt, kbArticleId, kbArticleNumber, kbArticleStatus, affectedCis: string[], closureNote, rootCauseAt, resolvedAt, closedAt, createdByName, services, incidents: IncidentSummary[], actions: ActionDto[], changes: [{id, number, title, status, type}], allowedTransitions, createdAt, updatedAt, demo}`; `ActionDto = {id, title, description, actionType, ownerId, ownerName, dueDate, status, overdue, completedAt, createdAt}`.
- `ProblemRequest = {title 5–200, description? ≤10000, priority? (MEDIUM), category?, ownerId? (active staff), teamId?, serviceIds?, affectedCis? (≤30 names; the CMDB module may link them to CIs), rootCause?, workaround?, permanentFix?, incidentIds? (create only)}`.
- `RecurringCluster = {key, serviceId, serviceName, category, size, cohesion, firstSeen, lastSeen, suggestedTitle, incidents: [{id, number, title, status, createdAt, similarity}]}`; `ProblemAnalytics = {from, to, total, open, byStatus, byPriority (open), knownErrors, createdInPeriod, resolvedInPeriod, avgHoursToRootCause, avgHoursToResolve, linkedIncidents, recurrencesAfterKnownError, overdueActions, recurringClusters, topServices: [{serviceId, serviceName, problems, incidents}]}`; `RootCauseSuggestion = {hypotheses: [{cause, likelihood HIGH|MEDIUM|LOW, evidence[], suggestedChecks[]}], commonFactors[], suggestedWorkaround, incidentsAnalysed, basis}`.
- Audit `PROBLEM_CREATED`, `PROBLEM_UPDATED`, `PROBLEM_STATUS_CHANGED`, `PROBLEM_INCIDENTS_LINKED`, `PROBLEM_INCIDENT_UNLINKED`, `PROBLEM_ACTION_ADDED/UPDATED`, `PROBLEM_KNOWN_ERROR_PUBLISHED`. Notifications `PROBLEM_UPDATED` (owner), `PROBLEM_ACTION_ASSIGNED` (category `PROBLEM_UPDATED`).

### Changes (`CHG-####`)
Types: `STANDARD` (from a pre-approved template; submission approves it, no CAB), `NORMAL` (assessment + CAB through flow `CHANGE_NORMAL`),
`EMERGENCY` (submission goes straight to the emergency CAB `CHANGE_EMERGENCY`; `pirRequired: true`). Lifecycle (server-side):
`DRAFT → SUBMITTED | CANCELLED`, `SUBMITTED → ASSESSMENT | CANCELLED`, `ASSESSMENT → AWAITING_APPROVAL | DRAFT | CANCELLED`,
`AWAITING_APPROVAL → APPROVED | REJECTED` (**approval engine only** — manual requests `409`) `| CANCELLED`, `APPROVED → SCHEDULED | CANCELLED`,
`SCHEDULED → IMPLEMENTING | APPROVED (unschedule) | CANCELLED`, `IMPLEMENTING → COMPLETED | FAILED | ROLLED_BACK`, `FAILED → ROLLED_BACK`,
`REJECTED → DRAFT`. Data rules: SUBMITTED needs ≥ 1 service and (non-standard) implementation + rollback plans; ASSESSMENT needs every
risk answer; AWAITING_APPROVAL needs the planned window; SCHEDULED needs a window ending in the future, no conflict (or an override)
and no freeze (NORMAL/STANDARD); FAILED/ROLLED_BACK need `outcomeNotes`; CANCELLED needs `reason` (cancels the pending approval).
Editors (`SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, CHANGE_MANAGER`, and the incident manager who raised an emergency change) manage;
IMPLEMENTING/COMPLETED/FAILED/ROLLED_BACK may also be done by the assigned implementer; others `403`.

**Risk**: questionnaire (`GET /changes/risk-questions`: `userImpact`, `tested`, `rollbackTested`, `downtime`, `complexity`, `experience`; unanswered
= middle option) + computed factors (tier-1 service +3 / tier-2 +1, > 2 services +2, window in business hours (org time zone) +1, failed/rolled-back
changes on the services in 90 days +2 each (≤ 4), EMERGENCY +2). Score 0–4 LOW, 5–9 MEDIUM, ≥ 10 HIGH; STANDARD = LOW. HIGH adds the CAB stage.
**Conflicts**: other AWAITING_APPROVAL/APPROVED/SCHEDULED/IMPLEMENTING changes whose planned window overlaps and that share a service or a CI
name. **Freeze windows**: active windows overlapping the change window on its services (empty `serviceIds` = all); setting
`change.freeze.enforce` (true). **Maintenance**: APPROVED/SCHEDULED changes inside their window and IMPLEMENTING changes show their services
as `MAINTENANCE` (`statusReason "Change CHG-…: title"`) via `ServiceMaintenanceSource`.

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/changes?search&status(csv)&type&risk&serviceId&assigneeId&teamId&scope(all\|mine\|requested\|team)&from&to&page&size&sort` | readers | `PageResponse<ChangeSummary>`; `from`/`to` filter the planned window; sort `createdAt` (default desc), `updatedAt`, `plannedStart`, `riskScore`, `number`, `status`, `priority` |
| GET | `/changes/{id}`, `/changes/{id}/history` | readers | `ChangeDetail`, `HistoryDto[]` |
| POST | `/changes` | change raisers | `ChangeRequest` → `201 ChangeDetail` (DRAFT); publishes `CHANGE_REQUEST_CREATED` |
| PUT | `/changes/{id}` | change editors | `ChangeRequest` (DRAFT, SUBMITTED, ASSESSMENT, REJECTED only, else `409`; type immutable) |
| POST | `/changes/{id}/transition` | see above | `TransitionRequest` → `ChangeDetail` |
| POST | `/changes/{id}/review` | change editors | post-implementation review `{notes 10–10000}` (after COMPLETED/FAILED/ROLLED_BACK) → `pirNotes`, `pirCompletedAt` |
| GET | `/changes/{id}/incidents?hours` | readers | `Correlation {windowStart, windowEnd, hours, linked: [{relation, incident}], correlated: [{incident, minutesAfterStart, linked}]}` — incidents on the changed services created within `hours` (setting `change.correlation.hours`, 24) after the actual (else planned) start |
| POST | `/changes/{id}/incidents`, DELETE `/changes/{id}/incidents/{incidentId}` | staff | `{incidentId, relation CAUSED_BY\|RESOLVES\|RELATED}` → `ChangeDetail` |
| GET | `/changes/calendar?from&to&serviceId` | readers | `ChangeCalendar {from, to, entries: [{id, number, title, type, status, risk, start, end, actualStart, actualEnd, services, conflictsWith: string[], inFreeze}], freezeWindows: FreezeDto[], conflicts: [{changeA, changeB, services, cis, overlapStart, overlapEnd}]}`; default today −7 d … +30 d, ≤ 92 days |
| GET | `/changes/metrics?days` (default 90) | readers | `ChangeMetrics {from, to, created, byType, byStatus, byRisk, completed, failed, rolledBack, successRate, failureRate, rollbackRate, emergencyRate, avgApprovalHours, avgLeadTimeHours, onTimeRate, changesFollowedByIncidents, reviewsOutstanding}` (rates in %, null without data) |
| GET | `/changes/risk-questions` | readers | `[{key, text, options: [{value, label, points}]}]` |
| GET / POST / PUT | `/changes/templates` (GET list, POST create), `/changes/templates/{id}` (PUT) | GET readers; write change editors | `TemplateRequest {code (immutable), name ≤150, description?, category?, implementationPlan, rollbackPlan, testPlan?, defaultDurationMinutes? 5–10080 (60), serviceIds?, active?}` → `TemplateDto {id, code, name, description, category, implementationPlan, rollbackPlan, testPlan, defaultDurationMinutes, serviceIds, active, demo}` |
| GET / POST / PUT / DELETE | `/changes/freeze-windows[?includePast]` (GET list, POST create), `/changes/freeze-windows/{id}` (PUT, DELETE) | GET readers; write change editors | `FreezeRequest {name ≤150, reason?, startsAt, endsAt (> start, ≤ 62 days), serviceIds? (empty = all), active?}` → `FreezeDto {id, name, reason, startsAt, endsAt, serviceIds, active, demo}` |
| POST | `/ai/changes/{id}/risk` | readers | `AiResult<RiskAnalysis {riskLevel, score, factors, recommendations[], similarChanges: [{number, title, status, risk, closedAt}], basis}>` — score/factors always from the risk model; the LLM (validated: level LOW/MEDIUM/HIGH, ≥ 1 recommendation) may set the level and adds `source: "AI"` factors; otherwise the labelled deterministic fallback |

- `ChangeRequest = {title? 5–200 (required unless from a template), description?, type? (NORMAL; STANDARD with templateId), category?, priority?, impact? LOW|MEDIUM|HIGH, affectedUsers?, affectedCis? (≤30 names), justification?, implementationPlan?, rollbackPlan?, testPlan?, plannedStart?, plannedEnd? (> start, ≤ 14 days; a template fills the end from its duration), serviceIds?, assigneeId? (implementer, active staff), teamId? (default: the first service's team), templateId?, problemId?, riskAnswers? {key: value}}`.
- `TransitionRequest = {status, reason?, plannedStart?, plannedEnd?, outcomeNotes?, riskAnswers?, overrideConflicts?, overrideReason?}` — scheduling over a conflict needs `overrideConflicts: true` + `overrideReason` by a change approver (`SYSTEM_ADMIN, IT_MANAGER, CHANGE_MANAGER`); emergency changes may be scheduled into a freeze (history note "Inside change freeze").
- `ChangeSummary = {id, number, title, type, status, risk, riskScore, priority, plannedStart, plannedEnd, services, assigneeId, assigneeName, teamId, teamName, requesterId, requesterName, approvalId, approvalStatus, pirRequired, pirDone, createdAt, updatedAt, demo}`.
- `ChangeDetail = {id, number, title, description, type, status, category, priority, risk, riskScore, riskAnswers, riskFactors: [{key, label, points, source ANSWER|COMPUTED}], riskAssessedAt, impact, affectedUsers, affectedCis, justification, implementationPlan, rollbackPlan, testPlan, plannedStart, plannedEnd, actualStart, actualEnd, services, requesterId, requesterName, assigneeId, assigneeName, teamId, teamName, templateId, templateName, approval: ApprovalDto|null, problemId, problemNumber, outcomeNotes, pirRequired, pirNotes, pirCompletedAt, pirByName, conflictOverrideReason, submittedAt, approvedAt, closedAt, conflicts: [{changeId, number, title, status, plannedStart, plannedEnd, services, cis}], freezeWindows: [{id, name, startsAt, endsAt}], linkedIncidents, deployments: [{id, number, environment, version, status, startedAt}], allowedTransitions (manual ones), createdAt, updatedAt, demo}`.
- Workflow: trigger **`CHANGE_REQUEST_CREATED`**; condition fields **`changeRisk`** (LOW|MEDIUM|HIGH), `changeType`, `riskScore` (also `status`, `priority`, `impact`, `category`, `serviceCode`, `teamCode`, `assigneeEmail`, `title`, `affectedUsers` for changes); templates `{{number}}`, `{{title}}`, `{{status}}`, `{{risk}}`; actions that apply to changes: notify (role/user/department/assignee), `SEND_WEBHOOK`, `REQUEST_APPROVAL`; others are SKIPPED. `ExecutionDto` adds `serviceRequestId`, `changeId`.
- Notifications `CHANGE_UPDATED` (requester and implementer on every state change), `CHANGE_ASSIGNED` (category `CHANGE_UPDATED`); approved/scheduled changes are e-mailed to the team mailbox (`teams.email`) via the outbox. Audit `CHANGE_CREATED`, `CHANGE_UPDATED`, `CHANGE_STATUS_CHANGED`, `CHANGE_REVIEWED`, `CHANGE_INCIDENT_LINKED/UNLINKED`, `CHANGE_TEMPLATE_CREATED/UPDATED`, `CHANGE_FREEZE_CREATED/UPDATED/DELETED`.

### Deployments and releases
Deployment records (`DEP-####`): `environment DEV|TEST|STAGING|PRODUCTION`, `status IN_PROGRESS → SUCCEEDED | FAILED`, `ROLLED_BACK` via the
rollback endpoint (the rollback itself is a new deployment with `rollbackOfId`). Releases (`REL-###`): `PLANNED|IN_PROGRESS|DEPLOYED|FAILED|CANCELLED`,
following their deployments (first deployment → IN_PROGRESS; production success → DEPLOYED, failure/rollback → FAILED). Failed or rolled-back
deployments notify change managers and the linked change's implementer (`DEPLOYMENT_FAILED`, category `DEPLOYMENT_STATUS`).

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/deployments?search&serviceId&environment&status&releaseId&changeId&source&from&to&page&size&sort` | readers | `PageResponse<DeploymentDto>`; sort `startedAt` (default desc), `finishedAt`, `number`, `status`, `environment`, `version` |
| GET | `/deployments/{id}` | readers | `DeploymentDetail {deployment, risk: {level, score, factors: [{key, label, points}], basis}, correlatedIncidents: [{incident, minutesAfterStart}], correlationHours (setting deployment.correlation.hours, 12), rollbacks: DeploymentDto[]}` — risk is deterministic (production +3, tier-1 +3/tier-2 +1, production without change +3, change HIGH +2/MEDIUM +1, change not approved +2, ≥ 30 % recent failures +2, during a freeze +3) |
| POST | `/deployments` | deployment editors | `{serviceId, environment, version ≤80, commitSha? (7–64 hex), pipelineUrl? (http/https), status? IN_PROGRESS\|SUCCEEDED\|FAILED, releaseId?, changeId?, notes?, startedAt? (not in the future)}` → `201 DeploymentDto` |
| POST | `/deployments/{id}/status` | deployment editors | `{status SUCCEEDED\|FAILED, notes?}`; only from IN_PROGRESS (`409`) |
| POST | `/deployments/{id}/rollback` | deployment editors | `{reason 5–1000, targetVersion?}` → `201 DeploymentDto` (the rollback deployment, IN_PROGRESS); the original becomes ROLLED_BACK; target defaults to the previous successful version on the same service and environment (`400` when none) |
| GET | `/deployments/calendar?from&to&serviceId&environment` | readers | `{from, to, days: [{date, succeeded, failed, inProgress, rolledBack, deployments}]}` (default last 30 days, ≤ 92) |
| GET | `/deployments/metrics?days` (30) | readers | `{from, to, total, byEnvironment, byStatus, successRate, failureRate, rollbackRate, avgDurationMinutes, productionWithoutChange, followedByIncidents}` |
| GET / POST / PUT | `/releases?search&status&serviceId&page&size&sort`, `/releases/{id}` | GET readers; write deployment editors | `ReleaseRequest {name ≤150, version ≤80, description?, status?, serviceId?, changeId?, targetAt?}` → `ReleaseDto {id, number, name, version, description, status, serviceId, serviceName, changeId, changeNumber, ownerId, ownerName, targetAt, deploymentCount, demo, createdAt, updatedAt}`; `GET /releases/{id}` → `{release, deployments}` |
| GET / POST / PUT | `/deployments/sources` (GET list, POST create), `/deployments/sources/{id}` (PUT) | deployment editors | `SourceRequest {name ≤120 (unique), provider GITHUB\|GITLAB\|GENERIC (immutable), serviceId, defaultEnvironment? (PRODUCTION), active?}` → `SourceSaveResult {source: SourceDto, secret}` — `secret` (`whsec_…`) **only on create**, stored AES-256-GCM encrypted (`SecretCipher`) |
| POST | `/deployments/sources/{id}/rotate-secret` | deployment editors | → `SourceSaveResult` with the new secret (the old one stops working) |
| GET | `/deployments/sources/{id}/deliveries?size` | deployment editors | `[{id, deliveryId, eventType, outcome PROCESSED\|IGNORED\|FAILED, message, deploymentId, receivedAt}]` |
| POST | `/deployments/webhooks/{sourceId}` | **public**, authenticated by the provider signature; inbound rate-limit bucket | → `200 WebhookResult {outcome PROCESSED\|IGNORED\|DUPLICATE, deliveryId, deploymentId, deploymentNumber, message}`; unknown/inactive source, missing or wrong signature/token, stale timestamp → `401 {"message": "Unauthorized"}` (no detail; audited `DEPLOYMENT_WEBHOOK_REJECTED`); malformed payload → `400` |

- `DeploymentDto = {id, number, releaseId, releaseNumber, releaseName, changeId, changeNumber, serviceId, serviceName, environment, version, commitSha, pipelineUrl, status, source MANUAL|GITHUB|GITLAB|GENERIC, sourceId, sourceName, externalId, actorName, startedAt, finishedAt, durationSeconds, rollbackOfId, rollbackOfNumber, rolledBackAt, rollbackReason, notes, demo, createdAt}`; `SourceDto = {id, name, provider, serviceId, serviceName, defaultEnvironment, active, webhookPath, lastDeliveryAt, demo, createdAt}`.
- **GitHub**: `X-Hub-Signature-256: sha256=<hex HMAC-SHA256(secret, raw body)>`, `X-GitHub-Event`, `X-GitHub-Delivery`. `workflow_run` (`requested`/`in_progress` → IN_PROGRESS; `completed` + `success` → SUCCEEDED, other conclusions → FAILED, `skipped`/`neutral` ignored; environment = the source's default; external id `workflow_run:<id>`), `deployment_status` (`pending|queued|in_progress` → IN_PROGRESS, `success` → SUCCEEDED, `failure|error` → FAILED, `inactive` ignored; environment from the payload; external id `deployment:<deployment.id>`), `ping` → IGNORED.
- **GitLab**: `X-Gitlab-Token: <secret>`, `X-Gitlab-Event: Pipeline Hook | Deployment Hook`, `X-Gitlab-Event-UUID` (or `Idempotency-Key`). Pipeline `created|waiting_for_resource|preparing|pending|running` → IN_PROGRESS, `success` → SUCCEEDED, `failed|canceled` → FAILED (`pipeline:<id>`); Deployment `created|running`, `success`, `failed|canceled` (`deployment:<deployment_id>`).
- **Generic signed JSON**: headers `X-Nexus-Timestamp: <unix seconds>` (± 5 min), `X-Nexus-Delivery: <unique id>` (required), `X-Nexus-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + raw body)>`, optional `X-Nexus-Event`; body `{externalId, version, status IN_PROGRESS|SUCCEEDED|FAILED|ROLLED_BACK (reported as FAILED), environment?, service? (code; default the source's service), commit?, pipelineUrl?, actor?, startedAt?, finishedAt?, changeNumber?, notes?}`.
- Common: replays are detected by delivery id (else the SHA-256 of the body) per source and answered `DUPLICATE` without processing; one deployment per source and external id is created then updated (finished states are kept); the version comes from a tag/ref/title/commit message (semantic version) else the short SHA; a `CHG-####` in the title, commit message, ref or `changeNumber` links the change. Audit `DEPLOYMENT_RECORDED`, `DEPLOYMENT_STATUS_CHANGED`, `DEPLOYMENT_ROLLED_BACK`, `DEPLOYMENT_WEBHOOK_RECEIVED`, `DEPLOYMENT_SOURCE_CREATED/UPDATED/SECRET_ROTATED`, `RELEASE_CREATED/UPDATED`.

### Extension-point contributions
- **My Work** (staff): `problems` (110; `Section<ProblemSummary>` — active problems I own or unowned in my teams), `correctiveActions` (115; `Section<{id, title, actionType, dueDate, overdue, status, problemId, problemNumber, problemTitle}>`), `changes` (120; `Section<ChangeSummary>` — changes I implement or raised that need work, plus finished emergency changes without review). `approvals` (core) now lists the engine's decidable tasks (user/role/team/delegation).
- **Employee portal**: `plannedChanges` (110; `Section<{id, title, startsAt, endsAt, services: string[]}>` — approved changes in the next 14 days).
- **Reports**: `CHANGES` and `PROBLEMS` generators (PDF and CSV, filter `departmentId`) — `GET /reports/types` lists them `available: true`.
- **Notification categories** (V7 `ck_notification_pref_type`): `APPROVAL_ESCALATED` (e-mail on by default), `PROBLEM_UPDATED`, `CHANGE_UPDATED`, `DEPLOYMENT_STATUS`.
- **Settings** (V7): `change.correlation.hours` 24, `change.freeze.enforce` true, `deployment.correlation.hours` 12, `problem.recurring.window_days` 30, `problem.recurring.min_incidents` 3, `problem.recurring.similarity` 0.42.
- **AI**: `AiService.runGrounded(feature, subject, actor, viaLlm, viaFallback)` and `AiService.systemPrompt(task)` for module features (same provider selection, validation fallback, labelling and interaction log).

### Demo data (all `demo: true`, seeded once by `DemoRelease3ChangeSeeder`)
Form templates `TPL-ACCESS`, `TPL-HARDWARE`; Finance-only request type "Payroll data extract" (form `PAYROLL-EXTRACT`: conditional file field, multiselect, user picker);
flow `ACCESS-2STEP` (line manager → IT manager) and an upcoming out-of-office delegation (Kiran → Priya); workflow "High-risk change review";
problems: payroll 503s during the batch (KNOWN_ERROR, linked to the payroll incidents and the KB known-error article, three corrective actions),
VPN drops (INVESTIGATING), SSO failures (ROOT_CAUSE_IDENTIFIED), e-mail delays (CLOSED); templates `STD-WIN-PATCH`, `STD-CERT-RENEW`, `STD-DB-INDEX`;
freeze "Month-end payroll close"; changes: payroll pool fix (HIGH risk, permanent fix of the payroll problem, waiting for the CAB), a conflicting
payroll patch (APPROVED), certificate renewal (STANDARD, SCHEDULED), emergency payroll restart (COMPLETED, review outstanding), VPN firmware
(FAILED → ROLLED_BACK), ERP update (COMPLETED), core switch (REJECTED), HR portal SSO (DRAFT); releases Payroll 2.4, VPN firmware 9.18, ERP 7.2
with six deployments incl. the failed and rolled-back VPN 9.18; webhook sources `payroll-github`, `erp-gitlab` (random secrets — rotate to connect a pipeline).

## Release 3 — assets, CMDB, alerts ([RELEASE3_SPEC](docs/RELEASE3_SPEC.md) §5 BE-3 — migration `V8__assets_cmdb_alerts.sql`)

All lists are `PageResponse<T>` unless noted (`page`, `size` ≤ 100, `sort=field,asc|desc` from an allow-list; unknown sort fields or enum
values → `400`). Every mutation is audited. Role groups: **asset editors** = `SYSTEM_ADMIN, IT_ADMIN, ASSET_MANAGER`; **CMDB editors** = asset
editors + `IT_MANAGER`; **alert responders** = staff; **alert config** (sources, suppression and routing rules) = `SYSTEM_ADMIN, IT_ADMIN`.
Readers (staff + `AUDITOR`) read assets, CIs and alerts; employees get `403` on `/assets`, `/cmdb`, `/alerts` (they see their own assets in the
portal). URL gates: `GET /assets/**`, `/cmdb/**`, `/alerts/**` readers; other `/assets/**` asset editors; other `/cmdb/**` CMDB editors;
`/alerts/sources/**` (also GET), non-GET `/alerts/suppression-rules/**` and `/alerts/routing-rules/**` admins; other `/alerts/**` staff;
`POST /alerts/webhooks/*` public (provider authentication, inbound rate-limit bucket).

### CMDB
CI types `APPLICATION, SERVER, DATABASE, NETWORK_DEVICE, CLOUD_RESOURCE, OPERATING_SYSTEM, SOFTWARE, BUSINESS_SERVICE, INFRASTRUCTURE`; status
`PLANNED, ACTIVE, MAINTENANCE, RETIRED`; environment `PRODUCTION, STAGING, TEST, DEV, DR` (optional); criticality `LOW, MEDIUM, HIGH, CRITICAL`.
CI names are unique ignoring case. Relationships (directed, typed): `DEPENDS_ON`, `RUNS_ON`, `CONNECTS_TO` — the source needs the target; `HOSTS`
— the target runs on the source; `PART_OF` — the source is a component of the target (the whole depends on its parts). Cycles are allowed in the
data; every traversal is breadth-first with a visited set, a depth limit (1–10) and a node limit (500; graph 300), `truncated: true` when cut.
**Upstream** = what a CI depends on (possible causes); **downstream** = what depends on it (impacted when it fails).

**Business services**: every business service has exactly one `BUSINESS_SERVICE` CI (`serviceId`, attributes `serviceCode`, `tier`; criticality
from the tier 1/2/3 → CRITICAL/HIGH/MEDIUM). V8 created them for existing services; new services, renames and dependency changes
(`POST/PUT /services`) are mirrored at once (and at start-up). Service dependencies are `DEPENDS_ON` relationships with `origin: SERVICE_MAP`
(change them through the service; deleting one directly → `409`). A service CI cannot be renamed, change type or be deleted (`409`) — rename or
delete the service.

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/cmdb/meta` | readers | `{types, statuses, environments, criticalities, relationshipTypes: [{type, outgoingLabel, incomingLabel, sourceDependsOnTarget}]}` |
| GET | `/cmdb/cis?search&type(csv)&status(csv)&environment&criticality&teamId&ownerId&serviceId&page&size&sort` | readers | `PageResponse<CiSummary>`; `search` matches name and description; sort `name` (default asc), `ciType`, `status`, `criticality`, `environment`, `createdAt`, `updatedAt` |
| GET | `/cmdb/cis/{id}` | readers | `CiDetail` |
| POST | `/cmdb/cis` | CMDB editors | `CiRequest` → `201 CiDetail`; duplicate name `409`; a service already represented `409` |
| PUT | `/cmdb/cis/{id}` | CMDB editors | `CiRequest` (`null` owner/team/attributes = unchanged, `0` clears owner/team) → `CiDetail` |
| DELETE | `/cmdb/cis/{id}` | CMDB editors | `204` (relationships go with it; assets, alerts and problem/change links are unlinked); service CIs `409` |
| GET | `/cmdb/cis/{id}/relationships` | readers | `RelationshipDto[]` (outgoing, then incoming) |
| POST | `/cmdb/relationships` | CMDB editors | `{sourceId, targetId, type, description? ≤300}` → `201 RelationshipDto` (from the source's point of view); self `400`, unknown CI `400 fieldErrors[sourceId|targetId]`, duplicate (same source, target, type) `409` |
| DELETE | `/cmdb/relationships/{id}` | CMDB editors | `204`; `SERVICE_MAP` relationships `409` |
| GET | `/cmdb/cis/{id}/history` | readers | `HistoryDto[] {id, at, actorName, action, fromValue, toValue, note}` newest first; actions `CREATED`, `UPDATED` (per field, e.g. `fromValue: "owner: -"`), `STATUS_CHANGED`, `ATTRIBUTES_CHANGED` (`note: "Changed: k1, k2"`), `RELATIONSHIP_ADDED`, `RELATIONSHIP_REMOVED`, `ASSET_LINKED`, `ASSET_UNLINKED` |
| GET | `/cmdb/cis/{id}/impact?direction=both\|upstream\|downstream&depth` (default both, 5) | readers | `ImpactAnalysis` |
| GET | `/cmdb/graph?ciId&depth&type(csv)&environment&includeRetired` | readers | `Graph`. With `ciId`: the neighbourhood (both directions) up to `depth` (default 2); without: all CIs (business services first, ≤ 300). Filters apply to every node except the root; edges only between returned nodes |

- `CiSummary = {id, name, ciType, status, environment, criticality, description, ownerId, ownerName, teamId, teamName, serviceId, serviceCode, relationshipCount, openAlerts, demo, createdAt, updatedAt}`.
- `CiDetail = {id, name, ciType, status, environment, criticality, description, ownerId, ownerName, teamId, teamName, serviceId, serviceCode, serviceName, attributes, outgoing: RelationshipDto[], incoming: RelationshipDto[], assets: [{id, assetTag, name, assetType, status}], openAlerts: [{id, number, title, status}] (≤ 10), openAlertCount, problems: [{id, number, title, status}], changes: [{id, number, title, status}], createdByName, demo, createdAt, updatedAt}`.
- `CiRequest = {name 2–120, ciType, status?, environment?, criticality?, description? ≤2000, ownerId? (active user; 0 clears), teamId? (active team; 0 clears), serviceId? (BUSINESS_SERVICE only, one CI per service, immutable), attributes? (≤ 50; names ^[A-Za-z][A-Za-z0-9_.-]{0,59}$; values text ≤500, number, boolean or null — nested objects `400 fieldErrors[attributes…]`)}`.
- `RelationshipDto = {id, type, direction OUTGOING|INCOMING, label (e.g. "depends on" / "is needed by", "runs on" / "runs", "hosts" / "runs on", "connects to" / "is connected from", "is part of" / "contains"), ci: CiRef, description, origin MANUAL|SERVICE_MAP, demo, createdAt}`; `CiRef = {id, name, ciType, status, environment}`.
- `ImpactAnalysis = {ci: CiRef, direction, depth, upstream: ImpactNode[], downstream: ImpactNode[], impactedServices: [{ciId, serviceId, code, name, tier, status OPERATIONAL|DEGRADED|OUTAGE}], openIncidents: IncidentSummary[] (≤ 50, active incidents on the impacted services or linked to active alerts on the impacted CIs), openAlerts: [{id, number, title, severity, status, ciId, ciName}] (open/acknowledged alerts on the CI and the CIs it impacts), truncated}`; `ImpactNode = {ci: CiRef, criticality, depth, via (relationship type), viaCiId}`. `impactedServices` = business-service CIs among the CI itself and its downstream CIs (none for `direction=upstream`).
- `Graph = {rootId, depth, nodes: [{id, name, ciType, status, environment, criticality, serviceId, openAlerts, depth}], edges: [{id, source, target, type, origin}], truncated}` (edges point from `source` to `target` with the stored type).
- **Problems and changes**: `ProblemRequest` and `ChangeRequest` add `ciIds?: number[] (≤ 30)`; `ProblemDetail` and `ChangeDetail` add `cis: CiRef[]`. Explicit `ciIds` replace the links and add the CI names to `affectedCis`; without `ciIds` the links follow `affectedCis` (every name equal to a CI name ignoring case is linked). V8 backfilled the links by name; a CI created or renamed later is linked to the problems and changes that already name it. Unknown ids → `400 fieldErrors[ciIds]`. The text field keeps working as before (also for change conflicts).
- Audit `CI_CREATED`, `CI_UPDATED`, `CI_DELETED`, `CI_RELATIONSHIP_ADDED`, `CI_RELATIONSHIP_REMOVED`.

### Assets
Types `LAPTOP, DESKTOP, SERVER, NETWORK, MOBILE, MONITOR, PERIPHERAL, SOFTWARE_LICENSE, OTHER` (`category` HARDWARE / SOFTWARE). Lifecycle
(server-side): `IN_STOCK → IN_USE | IN_REPAIR | RETIRED`, `IN_USE → IN_STOCK | IN_REPAIR | RETIRED`, `IN_REPAIR → IN_STOCK | IN_USE | RETIRED`,
`RETIRED → IN_STOCK | DISPOSED`, `DISPOSED` final; other moves `409`. End-user devices (laptop, desktop, mobile, monitor, peripheral) need a
holder to be `IN_USE` (`400 fieldErrors[assigneeId]`, or give `assigneeId`); servers, network gear and other equipment do not. `IN_STOCK`, `RETIRED`
and `DISPOSED` return the device (and release every licence seat); `IN_REPAIR` keeps the holder; `DISPOSED` needs a `note`; disposed assets cannot
be edited (`409`). Hardware has one holder (`assignedTo`); a licence has `licenseSeats` seats assigned to users. Every assignment and return is
kept (`/assignments`); lifecycle and changes are events (`/history`). Assigning notifies the user (`ASSET_ASSIGNED`, link `/portal`).

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/assets?search&type(csv)&status(csv)&category&assignedToId&departmentId&ciId&warranty&location&page&size&sort` | readers | `PageResponse<AssetSummary>`; `search` matches tag, name, serial, model and holder; `category` HARDWARE\|SOFTWARE; `warranty` ACTIVE\|EXPIRING\|EXPIRED\|NONE (hardware warranty); sort `assetTag` (default asc), `name`, `assetType`, `status`, `createdAt`, `updatedAt`, `warrantyExpires`, `purchaseDate` |
| GET | `/assets/summary` | readers | `{total, byStatus, byType, hardware, licences, seatsTotal, seatsUsed, expiringWithinWindow, expired, warningDays, totalCost, mixedCurrencies}` |
| GET | `/assets/expiring?days` (default setting `asset.warranty.warning_days` 30, 1–365) | readers | `ExpiringItem[] {asset: AssetSummary, kind WARRANTY\|LICENSE, expiresOn, daysLeft, expired}` — active (not retired/disposed) assets whose warranty or licence ends within `days` or already ended, soonest first (the warranty expiry alert list) |
| GET | `/assets/{id}` | readers | `AssetDetail` |
| POST | `/assets` | asset editors | `AssetRequest` → `201 AssetDetail` (IN_STOCK, or IN_USE with `assignedToId`) |
| PUT | `/assets/{id}` | asset editors | `AssetRequest` (`assetTag` immutable; licence ⇄ hardware `400`; fewer seats than assigned `409`) → `AssetDetail` |
| POST | `/assets/{id}/transition` | asset editors | `{status, note? ≤1000, assigneeId?}` → `AssetDetail` |
| POST | `/assets/{id}/assign` | asset editors | `{userId, note? ≤500}` → `AssetDetail`. Hardware: reassigning returns it from the previous holder; same holder `409`. Licence: one seat per user (`409`), all seats used `409 "All n seats of … are in use"`. Only IN_STOCK/IN_USE assets (`409`); an IN_STOCK asset becomes IN_USE |
| POST | `/assets/{id}/return` | asset editors | `{userId?, note?}` (licences: `userId` required) → `AssetDetail`; not assigned `409`; hardware (and a licence without seats left) goes back to IN_STOCK |
| GET | `/assets/{id}/assignments` | readers | `AssignmentDto[] {id, userId, userName, assignedAt, assignedByName, returnedAt, returnedByName, note}` newest first |
| GET | `/assets/{id}/history` | readers | `EventDto[] {id, type, fromValue, toValue, note, actorName, at}`; types `CREATED`, `IMPORTED`, `UPDATED` (`note: "Changed: …"`), `STATUS_CHANGED`, `ASSIGNED`, `RETURNED`, `MAINTENANCE`, `CI_LINKED` |
| GET / POST | `/assets/{id}/maintenance` | GET readers; POST asset editors | `MaintenanceRequest {maintenanceType REPAIR\|PREVENTIVE\|UPGRADE\|INSPECTION, status? SCHEDULED\|COMPLETED (default COMPLETED; completed work not in the future), description 5–2000, vendor? ≤120, cost? ≥0, performedOn, nextDue? (≥ performedOn)}` → `201 MaintenanceDto {id, maintenanceType, status, description, vendor, cost, performedOn, nextDue, createdByName, createdAt}` |
| GET | `/assets/export?…list filters` | readers | CSV (`assets.csv`, formula-safe: cells starting with `= + - @` are prefixed with `'`), columns `assetTag, name, assetType, status, manufacturer, model, serialNumber, assignedToEmail, department (code), location, vendor, purchaseOrder, purchaseCost, currency, purchaseDate, warrantyExpires, licenseSeats, seatsUsed, licenseExpires, ciName, notes`; audit `ASSETS_EXPORTED` |
| GET | `/assets/import-template` | asset editors | CSV with the header and one example row |
| POST | `/assets/import?dryRun=true\|false` (default true) | asset editors | multipart `file` (UTF-8 CSV ≤ 2 MB, ≤ 1000 rows, RFC 4180 quoting, header required) → `ImportResult` |

- `AssetSummary = {id, assetTag, name, assetType, category, status, manufacturer, model, serialNumber, assignedToId, assignedToName, assignedAt, departmentId, departmentName, location, warrantyExpires, warrantyStatus NONE|ACTIVE|EXPIRING|EXPIRED (licences: from licenseExpires), licenseSeats, seatsUsed, licenseExpires, ciId, ciName, demo, createdAt, updatedAt}`.
- `AssetDetail = {asset: AssetSummary, vendor, purchaseOrder, purchaseCost, currency, purchaseDate, notes, retiredAt, disposedAt, seats: [{assignmentId, userId, userName, userEmail, assignedAt, assignedByName}] (licences), maintenance: MaintenanceDto[], allowedTransitions, createdByName}`.
- `AssetRequest = {assetTag? (create only, ^[A-Za-z0-9][A-Za-z0-9-]{1,39}$, stored upper-case; default AST-<n>; duplicate 409), name 2–150, assetType, manufacturer? ≤80, model? ≤120, serialNumber? ≤120 (unique ignoring case, 409), departmentId? (0 clears), location? ≤160, vendor? ≤120, purchaseOrder? ≤60, purchaseCost? (≥ 0, 2 decimals), currency? (3 letters, default INR), purchaseDate? (not in the future), warrantyExpires? (not before purchase), licenseSeats? (1–100000; required for and only for SOFTWARE_LICENSE), licenseExpires? (licences), ciId? (0 clears; CI history records ASSET_LINKED/UNLINKED), notes? ≤2000, assignedToId? (create only, hardware)}`.
- `ImportResult = {dryRun, applied, totalRows, validRows, created, updated, errors: [{row, field, message}], rows: [{row, assetTag, name, assetType, status, action CREATE|UPDATE}]}` — `row` counts the header as row 1. Every row is validated first (types/statuses case-insensitive, ISO dates, amounts, lengths, unknown columns on row 1, duplicate tags and serial numbers within the file and against the inventory, `assignedToEmail` = active user (not for licences), `department` = code or name, `ciName` = existing CI, lifecycle transitions of existing assets, licence seats). With `dryRun` nothing is stored; otherwise everything is stored in one transaction only when no row has an error (`applied: false` + `errors` otherwise). A row whose `assetTag` exists updates that asset (columns missing from the file keep their values, empty cells clear them; a different `assignedToEmail` reassigns; `status` is applied as a transition); other rows create assets. Values exported with the `'` formula guard are restored.
- Audit `ASSET_CREATED`, `ASSET_UPDATED`, `ASSET_STATUS_CHANGED`, `ASSET_ASSIGNED`, `ASSET_RETURNED`, `ASSET_MAINTENANCE_RECORDED`, `ASSETS_IMPORTED` (`{dryRun, applied, rows, created, updated, errors}`), `ASSETS_EXPORTED`.

### Alerts
An alert is deduplicated by **scope** (its webhook source, legacy integration, or manual) and **fingerprint** while active (`OPEN`, `ACKNOWLEDGED`,
`SUPPRESSED`): repeats increment `occurrenceCount` and update labels/annotations/severity; a resolved notification resolves it (`firing: false`,
`resolutionNote: "Resolved by the source"`, internal note on a linked incident); an alert that fires again within `alert.grouping.window_minutes`
(30) after resolving is **reopened** (same number), later a new alert is created. Alerts of one notification group (Alertmanager/Grafana
`groupKey`, CloudWatch alarm) share `groupKey`. Severity `CRITICAL > MAJOR > MINOR > WARNING > INFO` (source words such as critical/error/warn/
page/P1… are mapped; otherwise the source's `defaultSeverity`). Status `OPEN, ACKNOWLEDGED, RESOLVED, SUPPRESSED`; source kinds
`PROMETHEUS, GRAFANA, CLOUDWATCH, GENERIC, INTEGRATION, MANUAL`.

**Pipeline** for a new or reopened alert: attach the CI (`ci` label / hint, the host of Prometheus `instance`, else the source's default CI) and the
business service (`service` label / code, else the nearest business service downstream of the CI in the CMDB, else the source default) →
**suppression** (first enabled rule whose window is open and matcher matches → `SUPPRESSED`, nothing else happens) → **routing** (first enabled rule
by `sortOrder` whose matcher matches sets the team, fills service/CI; else the service's default team, else the department's default team, else the
CI's team) → team members notified (`ALERT_ROUTED`) from `alert.notify.min_severity` (MAJOR) → **escalation** planned → **conversion** to an
incident when the routing rule has `createIncident` and the severity reaches `incidentMinSeverity` (or for legacy integrations; an alert whose group
already has an active incident joins it) → workflow trigger **`ALERT_RECEIVED`**. Converted alerts go through the incident intake pipeline
(classification, priority — impact/urgency from severity: CRITICAL HIGH/HIGH, MAJOR HIGH/MEDIUM, MINOR and WARNING MEDIUM/MEDIUM, INFO LOW/LOW —
duplicate detection and parent linking, assignment, SLA), title `"[SEVERITY] <alert title>"`, description with the alert number, CI, labels and source
link; unlinked active alerts of the same group are linked to the new incident.

**Escalation** of unacknowledged alerts: `AlertEscalationService` (scheduler every `ALERT_ESCALATION_POLL_INTERVAL_MS` / `app.alerts.escalation-poll-interval-ms`
60 s, `app.alerts.escalation-enabled`, PostgreSQL advisory lock 360008, one transaction per alert with optimistic locking → each level exactly once)
uses the `AlertEscalationPolicy` beans. Built-in policy `team-lead` (order 1000) for alerts of at least `alert.escalation.min_severity` (MAJOR):
level 1 after `alert.escalation.ack_minutes` (15) → the routed team's lead and the manager of the team's department (without a team: the service's
department manager, else the IT managers); level 2 after the same time again → incident and IT managers (`ALERT_ESCALATED`, e-mail on by default).
Acknowledging, resolving or suppressing clears the schedule. The same scheduler ends time-limited manual suppressions (still firing → `OPEN`).

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/alerts?search&status(csv)&severity(csv)&source(csv)&sourceId&serviceId&ciId&teamId&incidentId&groupKey&firing&hasIncident&scope(all\|mine\|unassigned)&from&to&page&size&sort` | readers | `PageResponse<AlertSummary>`; `search` matches number, title and fingerprint; `scope=mine` = alerts of my teams; `from`/`to` filter last/first seen; sort `lastSeenAt` (default desc), `firstSeenAt`, `severity` (by rank), `status`, `number`, `occurrenceCount` |
| GET | `/alerts/counts?scope` | readers | `{open, acknowledged, suppressed, active (open + acknowledged), resolvedLast24h, bySeverity {CRITICAL…INFO} (open + acknowledged), criticalOpen, escalated (open, level > 0), withIncident}` |
| GET | `/alerts/groups?status(csv, default active)&scope&limit (≤100, 50)` | readers | `AlertGroup[] {groupKey, title (alertname or first title), alertCount, firingCount, maxSeverity, occurrences, firstSeenAt, lastSeenAt, incidentId, incidentNumber, sourceKind, byStatus}` most severe first |
| GET | `/alerts/{id}` | readers | `AlertDetail` |
| GET | `/alerts/{id}/history` | readers | `EventDto[]` |
| POST | `/alerts` | alert responders | Manual alert `CreateAlertRequest` → `201 AlertDetail` (goes through the pipeline; same title + service + CI again = repeat) |
| POST | `/alerts/{id}/acknowledge` | alert responders | `{note?}` → `AlertDetail`; only OPEN (`409`) |
| POST | `/alerts/{id}/resolve` | alert responders | `{note?}` → `AlertDetail`; already resolved `409` |
| POST | `/alerts/{id}/suppress` | alert responders | `{minutes? 5–10080 (null = until resolved/unsuppressed), reason 3–1000}` → `AlertDetail`; only OPEN/ACKNOWLEDGED (`409`) |
| POST | `/alerts/{id}/unsuppress` | alert responders | → `AlertDetail` (OPEN, routed if it had no team, escalation planned); not suppressed `409` |
| POST | `/alerts/{id}/assign` | alert responders | `{teamId, note?}` → `AlertDetail`; team members notified (`ALERT_ROUTED`) |
| POST | `/alerts/{id}/incident` | alert responders | Convert: `{title? 5–200, description? ≤5000, impact?, urgency?}` (all optional) → `201 ConvertResult`; an OPEN alert is acknowledged; already linked `409` |
| POST | `/alerts/{id}/link-incident` | alert responders | `{incidentId}` → `ConvertResult` (internal note on the incident); already linked `409`, unknown incident `400` |
| POST | `/alerts/bulk` | alert responders | `{ids[1..100], action ACKNOWLEDGE\|RESOLVE\|SUPPRESS, note? ≤1000, minutes? 5–10080 (SUPPRESS; null = until resolved/unsuppressed), reason? 3–1000 (**required** for SUPPRESS, else `400 fieldErrors[{field: reason}]`)}` → `{processed, skipped, messages[]}`. Per alert the same rules as the single actions: ACKNOWLEDGE only OPEN, RESOLVE only active, SUPPRESS only OPEN/ACKNOWLEDGED (same history event `SUPPRESSED` with the reason as note and audit `ALERT_SUPPRESSED` per alert); other, unknown or repeated ids are counted in `skipped` (`skipped` = ids sent − processed) with a message each. `note` is used by ACKNOWLEDGE/RESOLVE, `minutes`/`reason` by SUPPRESS. One audit `ALERTS_BULK_<action>` per call |
| POST | `/alerts/escalations/run` | `SYSTEM_ADMIN` | Runs the escalation scheduler now → `{escalated: n}` |
| GET | `/alerts/sources`, `/alerts/sources/{id}` | alert config | `SourceDto[]`, `SourceDto` |
| POST | `/alerts/sources` | alert config | `SourceRequest` → `201 SourceSaveResult` (`secret` only now; CLOUDWATCH: `null`) |
| PUT | `/alerts/sources/{id}` | alert config | `SourceRequest` (provider immutable `400`; a new topic ARN resets the subscription) → `SourceSaveResult` (`secret: null`) |
| POST | `/alerts/sources/{id}/rotate-secret` | alert config | → `SourceSaveResult` with the new secret (the old one stops working); CLOUDWATCH `409` |
| GET | `/alerts/sources/{id}/deliveries?size` (≤200, 50) | alert config | `[{id, deliveryId, eventType, outcome PROCESSED\|IGNORED\|FAILED, message, alertCount, receivedAt}]` |
| GET | `/alerts/suppression-rules` | readers | `SuppressionRuleDto[]` by name |
| POST / PUT / DELETE | `/alerts/suppression-rules`, `/alerts/suppression-rules/{id}` | alert config | `SuppressionRuleRequest` → `201`/`200 SuppressionRuleDto`; DELETE `204` |
| GET | `/alerts/routing-rules` | readers | `RoutingRuleDto[]` by `sortOrder`, id |
| POST / PUT / DELETE | `/alerts/routing-rules`, `/alerts/routing-rules/{id}` | alert config | `RoutingRuleRequest` → `201`/`200 RoutingRuleDto`; DELETE `204` |
| POST | `/alerts/webhooks/{sourceId}` | **public**, provider authentication | → `200 WebhookResult`; see below |

- `AlertSummary = {id, number (ALR-#####), title, severity, status, firing, sourceKind, sourceId, sourceName (source or integration name), groupKey, serviceId, serviceName, ciId, ciName, teamId, teamName, incidentId, incidentNumber, occurrenceCount, firstSeenAt, lastSeenAt, acknowledgedAt, acknowledgedByName, resolvedAt, escalationLevel, nextEscalationAt, demo}`.
- `AlertDetail = {alert: AlertSummary, description, fingerprint, labels {}, annotations {}, externalUrl, startsAt, endsAt, resolvedByName, resolutionNote, suppressedAt, suppressedUntil, suppressionRuleId, suppressionRuleName, routingRuleId, routingRuleName, integrationId, integrationName, incident: IncidentSummary|null, groupAlerts: AlertSummary[] (other alerts of the group, ≤ 50), history: EventDto[] (newest first, ≤ 300), allowedActions (staff; [] for auditors): ACKNOWLEDGE, RESOLVE, SUPPRESS, UNSUPPRESS, ASSIGN, CONVERT, LINK_INCIDENT, escalationPolicy (name of the policy that would escalate it while OPEN, else null)}`.
- `EventDto = {id, type, actorName, note, details {}, at}`; types `CREATED, REPEATED, REOPENED, AUTO_RESOLVED, ACKNOWLEDGED, RESOLVED, SUPPRESSED, UNSUPPRESSED, ROUTED, ASSIGNED, ESCALATED, CONVERTED, LINKED_INCIDENT, COMMENT`.
- `CreateAlertRequest = {title 3–300, description? ≤10000, severity?, status? firing|resolved, fingerprint? ≤128 (default: SHA-256 of title, service, CI), groupKey? ≤300, serviceId?, ciId?, labels? {string: string} ≤50, annotations? ≤50, externalUrl? (http/https)}`.
- `ConvertResult = {alert: AlertDetail, incidentId, incidentNumber, duplicate, parentNumber, linkedAlerts}` (`duplicate`/`parentNumber` from the intake pipeline's duplicate detection; `linkedAlerts` = alerts now linked to the incident).
- `SourceDto = {id, name, provider PROMETHEUS|GRAFANA|CLOUDWATCH|GENERIC, defaultServiceId, defaultServiceName, defaultCiId, defaultCiName, defaultSeverity, topicArn, subscriptionStatus NONE|PENDING|CONFIRMED|UNSUBSCRIBED, subscriptionArn, subscriptionConfirmedAt, active, webhookPath, authentication (how to call it), lastReceivedAt, demo, createdAt}`; `SourceRequest = {name ≤120 (unique, 409), provider, defaultServiceId?, defaultCiId?, defaultSeverity?, topicArn? (CLOUDWATCH only and required: arn:aws:sns:<region>:<12 digits>:<topic>), active?}`; `SourceSaveResult = {source, secret}` (`alsec_…`, stored AES-256-GCM encrypted).
- `MatchCriteria` (rules' `match`, all set criteria must match; empty = every alert) `= {labels? {name: value | "a|b" alternatives | "*" present} (≤ 20, values ignore case), sources? SourceKind[], severities? [], serviceIds? [], ciIds? [], sourceIds? [], titleContains?}`.
- `SuppressionRuleRequest = {name 2–120, description? ≤500, enabled? (true), match?, startsAt?, endsAt? (> startsAt), dailyStart?, dailyEnd? ("HH:mm", both or neither, organisation time zone, may cross midnight — after midnight the window belongs to the day it started), daysOfWeek? [1–7] (1 = Monday)}` — a rule needs a matcher or a time window (`400`); `SuppressionRuleDto = {id, name, description, enabled, match, startsAt, endsAt, dailyStart, dailyEnd, daysOfWeek, activeNow, expired, matchCount, lastMatchedAt, createdByName, demo, createdAt, updatedAt}`. When a suppressed alert fires again after its rule stopped applying (disabled, window closed, no longer matching) it is reopened (`UNSUPPRESSED`) and goes through routing.
- `RoutingRuleRequest = {name 2–120, description?, enabled?, sortOrder? 0–10000 (100), match?, teamId?, serviceId?, ciId?, createIncident? (false), incidentMinSeverity? (MAJOR)}` — needs a team, service, CI or `createIncident` (`400`); `RoutingRuleDto = {id, name, description, enabled, sortOrder, match, teamId, teamName, serviceId, serviceName, ciId, ciName, createIncident, incidentMinSeverity, matchCount, lastMatchedAt, createdByName, demo, createdAt, updatedAt}`.
- Audit `ALERT_CREATED`, `ALERT_ACKNOWLEDGED`, `ALERT_RESOLVED`, `ALERT_SUPPRESSED`, `ALERT_UNSUPPRESSED`, `ALERT_ASSIGNED`, `ALERT_CONVERTED`, `ALERT_LINKED`, `ALERTS_BULK_ACKNOWLEDGE|RESOLVE|SUPPRESS`, `ALERT_SOURCE_CREATED/UPDATED/SECRET_ROTATED`, `ALERT_SOURCE_SUBSCRIPTION`, `ALERT_SUPPRESSION_RULE_CREATED/UPDATED/DELETED`, `ALERT_ROUTING_RULE_CREATED/UPDATED/DELETED`, `ALERT_WEBHOOK_RECEIVED`, `ALERT_WEBHOOK_REJECTED` (`DENIED`).

**Webhooks** `POST /alerts/webhooks/{sourceId}` → `WebhookResult {outcome PROCESSED|IGNORED|DUPLICATE, deliveryId, message, alerts: [{id, number, action CREATED|REOPENED|UPDATED|RESOLVED|IGNORED, status, incidentId, incidentNumber}]}`. Unknown or inactive sources, missing or wrong credentials, bad or stale signatures, foreign certificates and foreign topics all get `401 {"message": "Unauthorized"}` (no detail); a malformed body after authentication `400` (kept in the delivery log as FAILED).
- **Prometheus Alertmanager** (`webhook_configs: [{url: https://<api>/api/alerts/webhooks/<id>, http_config: {authorization: {credentials: <secret>}}}]`): `Authorization: Bearer <secret>` (or `basic_auth` with the secret as password). Body: the Alertmanager webhook (version 4) — one signal per `alerts[]` entry: fingerprint = `fingerprint` (else the label set), title = `annotations.summary` else `alertname on instance`, description = `annotations.description`, severity = `labels.severity`, service = `labels.service`, CI = `labels.ci` or the host of `labels.instance`, `generatorURL`, `startsAt`/`endsAt`, `status firing|resolved`, `groupKey`.
- **Grafana** (webhook contact point): either the Authorization header credential — `Authorization: Bearer <secret>` (or HTTP Basic with the secret as password) — **or** the contact point's **HMAC signature** option with the source secret as HMAC secret: header `X-Grafana-Alerting-Signature` = lowercase hex HMAC-SHA256(secret, raw body); when the optional timestamp header `X-Grafana-Alerting-Timestamp` (unix seconds, ± 5 min) is sent, the signed message is `timestamp + ":" + raw body`. Keep Grafana's default header names. A request is accepted if either the credential or a valid signature is present; anything else (missing/wrong signature, signature over a modified body, wrong secret, stale or malformed timestamp, a timestamp that was not signed) gets the generic `401` and `ALERT_WEBHOOK_REJECTED`. The signature is checked over the exact bytes received (constant-time comparison). This follows Grafana's documented webhook HMAC option; it was tested with constructed payloads and signatures only, not against a live Grafana instance. Unified-alerting payload (same fields plus `panelURL`/`dashboardURL` as link, `valueString` kept as annotation) or the legacy rule payload (`ruleId`, `ruleName`, `state alerting|ok`; `no_data`/`paused`/`pending` ignored).
- **AWS CloudWatch via SNS** (alarm → SNS topic → HTTPS subscription to the webhook URL): the SNS message signature (SignatureVersion 1 SHA1withRSA / 2 SHA256withRSA over the canonical fields) is verified with the certificate from `SigningCertURL`, which must be `https://sns.<region of the topic>.amazonaws.com/….pem` (no other host, port or user info; fetched through the SSRF guard, cached 24 h); `TopicArn` must equal the source's topic; `MessageId` is processed once (`DUPLICATE`). `SubscriptionConfirmation` is confirmed by visiting `SubscribeURL` (same host rule; `app.alerts.sns.auto-confirm`, default true) → `subscriptionStatus: CONFIRMED`; `UnsubscribeConfirmation` → `UNSUBSCRIBED`. A `Notification`'s `Message` is the CloudWatch alarm JSON: `ALARM` fires, `OK` resolves, `INSUFFICIENT_DATA` is ignored; fingerprint = `AlarmArn`; labels `alarmName, region, account, namespace, metricName, dimension.<name>`; `severity:`/`service:`/`ci:` tags in `AlarmDescription` are honoured; link to the alarm in the CloudWatch console.
- **Generic signed JSON**: headers `X-Nexus-Timestamp` (unix seconds, ± 5 min), `X-Nexus-Delivery` (required, replays → `DUPLICATE`), `X-Nexus-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + raw body)>`, optional `X-Nexus-Event`; body one alert or `{"alerts": [...]}` (≤ 200) with the `CreateAlertRequest` fields (`service` = service code, `ci` = CI name instead of ids).
- **Legacy inbound integration** `POST /integrations/inbound/{id}` (`X-Nexus-Token`) keeps working: it records an `INTEGRATION` alert and converts it as before (see Integrations).

**Workflows**: trigger **`ALERT_RECEIVED`** (new or reopened, unsuppressed alerts); condition fields `alertSeverity` (CRITICAL…INFO), `alertSource`
(source kind), `alertName` (`alertname` / CloudWatch alarm name, else the title), `ciName`, `ciType`, `occurrenceCount`, plus `serviceCode`, `teamCode`,
`title`, `status` (alert status), `source`; templates `{{number}}`, `{{title}}`, `{{status}}`, `{{severity}}`, `{{service}}`, `{{ci}}`. New action
**`CREATE_INCIDENT`** `{impact?, urgency?}` (ALERT_RECEIVED only, else `400`; an alert that already has an incident → SKIPPED; joins the group's open
incident when there is one). Actions that apply to alerts: `CREATE_INCIDENT`, `ASSIGN_TEAM` (sets the alert's team), `ADD_COMMENT` (alert history
note), `NOTIFY_ROLE`, `NOTIFY_USER`, `NOTIFY_DEPARTMENT` (default: the team's or service's department), `SEND_WEBHOOK` (payload `alert {number,
title, severity, status, source, service, ci, labels}`); others are SKIPPED. `ExecutionDto` adds `alertId`.

### Extension points and contributions
- **Alert escalation hook** (`com.nexus360.alert.AlertEscalationPolicy`): `name()`, `order()` (lower first; built-in `team-lead` = 1000),
  `appliesTo(Alert)`, `nextEscalationAt(Alert, Instant reference)` (null = no further level), `escalate(Alert, int level, Instant now)` →
  `Outcome {notifiedUserIds, note}`. A bean with a lower order that accepts an alert (e.g. its team has an on-call schedule) replaces the built-in
  policy for it; `AlertEscalationService.policyFor(alert)` / `schedule(alert, reference)` / `runEscalations()` are public for the on-call module.
- **My Work** (staff): `alerts` (130; `Section<AlertSummary>` — open and acknowledged alerts of my teams, most severe first).
- **Employee portal**: `myAssets` (120; `Section<{id, assetTag, name, assetType, status, manufacturer, model, serialNumber, expiresOn, assignedAt}>` — hardware I hold and licence seats assigned to me, never other people's assets).
- **Reports**: `ASSETS` generator (CSV inventory incl. holder, warranty, seats and CI; PDF with status counts, warranties/licences ending in the period and the inventory; filter `departmentId` = owning department) — `GET /reports/types` lists it `available: true`.
- **Notification categories** (V8 `ck_notification_pref_type`): `ALERT_ROUTED`, `ALERT_ESCALATED` (e-mail on by default), `ASSET_ASSIGNED`.
- **Settings** (V8): `alert.grouping.window_minutes` 30, `alert.escalation.ack_minutes` 15, `alert.escalation.min_severity` MAJOR,
  `alert.notify.min_severity` MAJOR (the two severities are validated), `asset.warranty.warning_days` 30.
- **Configuration**: `ALERT_ESCALATION_ENABLED` (true), `ALERT_ESCALATION_POLL_INTERVAL_MS` (60000), `ALERT_SNS_AUTO_CONFIRM` (true).

### Demo data (all `demo: true`, seeded once per part by `DemoRelease3AssetSeeder`, also on upgraded databases)
- **CMDB**: 21 CIs and 35 relationships forming the payroll chain — `Payroll` (business service) → `Payroll Application` (runs on `payroll-app-01`,
  `payroll-app-02`; depends on `payroll-db-primary` and `Identity Provider (ADFS)`) and `payroll-alb` (AWS ALB → app servers); `payroll-db-primary` /
  `payroll-db-replica` → `san-array-01` and part of `Database Platform`; `Identity Provider (ADFS)` on `adfs-01`, `adfs-02`, needed by the SSO
  service, the HR portal and the VPN gateways `vpn-gw-01`, `vpn-gw-02` (→ `core-sw-01`); `Corporate Network` → `core-sw-01`, `fw-edge-01`; ERP and
  HR portal applications, servers and databases; `Microsoft 365`. Problems and changes that already named these CIs are linked.
- **Assets**: 13 laptops assigned to demo employees and staff, spares, a laptop in repair (scheduled warranty repair), a retired and a disposed laptop,
  a desktop, a monitor, two phones, data-centre servers, storage and network gear linked to their CIs (VPN gateway warranties end in 12 days, the
  core switch's ended 20 days ago), licences Microsoft 365 E3 (50 seats, 12 used), Adobe Acrobat Pro (10/3), JetBrains IntelliJ IDEA (5/4, ends in
  20 days), maintenance records.
- **Alerts**: sources `prometheus-prod`, `grafana-cloud`, `aws-cloudwatch-payroll` (topic `arn:aws:sns:ap-south-1:123456789012:payroll-alarms`,
  CONFIRMED), `uptime-checks` (random secrets — rotate to connect a real system); suppression rules "Nightly backup window" (01:00–03:00, `job=backup`)
  and "VPN firmware 9.18 maintenance" (expired); routing rules "Payroll alerts to Payroll & HR Apps" (critical payroll alerts → incident), "Database
  alerts to Database Operations", "SNMP and network device alerts to Network Operations"; workflow "Critical database alert to incident"
  (ALERT_RECEIVED). Alerts: the Prometheus payroll group `{}:{service="payroll"}` (HTTP 503 on payroll-app-01/02, database pool saturation;
  acknowledged) and the CloudWatch `payroll-alb-5xx` alarm, all linked to the payroll incident; VPN tunnel flapping (Grafana, open); SSO latency (generic,
  escalated to level 1); disk space on erp-app-01 (resolved); a backup I/O warning suppressed by the backup window; a manual certificate warning.

## Release 3 — on-call, major incidents, PIR, analytics, AI ([RELEASE3_SPEC](docs/RELEASE3_SPEC.md) §5 BE-4 — migration `V9__oncall_major_incidents_pir.sql`)

Role groups (`Roles` / `*_ROLES`): **on-call editors** = `SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, INCIDENT_MANAGER` (schedules, escalation policies,
direct overrides, other people's unavailability); every staff member reads schedules, offers their own shifts, takes offers addressed to them
and records their own unavailability. **Major incident command** = admins & managers (declare, assign roles, move the status) plus the
assigned incident commander; responders (staff) write the war-room timeline, stakeholder updates and action items. **PIR approvers** =
`SYSTEM_ADMIN, IT_MANAGER, INCIDENT_MANAGER`. URL gates: `GET /oncall/**`, `/major-incidents/**`, `/reviews/**`, `/action-items/**` readers
(staff + `AUDITOR`), other methods staff; employees get `403`. Every mutation is audited.

### On-call
**Rotation maths** (`com.nexus360.oncall.Rotation`): a schedule belongs to one team and has a `timeZone` (IANA), `rotationType`
`DAILY | WEEKLY | CUSTOM`, a local `handoffTime` ("HH:mm"), `handoffDay` (WEEKLY, 1 = Monday), `shiftHours` (CUSTOM, 1–336) and
`rotationStart` (date). DAILY/WEEKLY slots hand off at the local wall-clock time — across a daylight-saving change the shift containing it is
one hour shorter or longer; WEEKLY slots start on the first `handoffDay` on or after `rotationStart`; CUSTOM slots are exact durations from
`rotationStart` at `handoffTime`. Slot *k* belongs to participant *k mod n* of the layer (positions in order). Before the first handoff nobody
is on call. **Precedence** per layer at an instant: an ACTIVE override covering it (the latest created wins) → on a holiday of the schedule's
holiday calendar (`holidayCalendarId` = an SLA business-hours calendar; the whole local day) with a `holidayCoverUserId`: the cover is primary
and the rotation's primary becomes secondary → the rotation participant; when unavailable (personal unavailability) the next available
participant in order. **Secondary**: the SECONDARY participants when the layer has any, else the next available PRIMARY participant after the
primary's slot — never the person who is primary at that instant. Assignment `source`: `ROTATION | OVERRIDE | HOLIDAY_COVER | AVAILABILITY`.

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/oncall/now?teamId&at` | readers | `NowEntry[]` — who is on call now (or at `at`, ISO instant) for every active schedule, optionally of one team |
| GET | `/oncall/my-shifts?days` (14, ≤ 62) | staff | `MyShift[]` — my current and upcoming shifts (both layers), soonest first |
| GET | `/oncall/schedules?teamId&active` | readers | `ScheduleSummary[]` by name |
| GET | `/oncall/schedules/{id}` | readers | `ScheduleDetail` |
| POST | `/oncall/schedules` | on-call editors | `ScheduleRequest` → `201 ScheduleDetail`; duplicate name `409`; unknown time zone `400 fieldErrors[timeZone]`; CUSTOM without `shiftHours` `400 fieldErrors[shiftHours]`; participants must be active staff, once per layer (`400`) |
| PUT | `/oncall/schedules/{id}` | on-call editors | same body; participants are replaced |
| GET | `/oncall/schedules/{id}/shifts?from&to` | readers | `ShiftsResponse {scheduleId, timeZone, from, to, primary: ShiftDto[], secondary: ShiftDto[]}` — computed, split at every handoff, override, unavailability and holiday boundary and merged where the same person holds the layer for the same reason; default the next 14 days, ≤ 62 days (`400`) |
| GET | `/oncall/schedules/{id}/overrides?includePast` | readers | `OverrideDto[]` (OFFERED and ACTIVE not yet ended; `includePast` = all of the last 90 days) |
| POST | `/oncall/schedules/{id}/overrides` | staff | `OverrideRequest`. `offer: false` (default): on-call editors only (`403`), `userId` required → `ACTIVE`, the user is notified. `offer: true`: editors, or participants offering their own shift (`originalUserId` = caller) → `OFFERED` to `userId` or, without it, to every participant (notified `ONCALL_OVERRIDE`). Window: end after start, in the future, ≤ 31 days (`400 fieldErrors[endsAt]`) |
| GET | `/oncall/overrides/offers` | staff | offers I can take (addressed to me, or open offers of schedules I take part in) |
| POST | `/oncall/overrides/{id}/take` | staff | the addressed user, or for open offers a participant or team member (not the person whose shift it is) → `ACTIVE`; others `403`; not offered or ended `409` |
| POST | `/oncall/overrides/{id}/decline` | the addressed user | `{note?}` → `DECLINED` |
| DELETE | `/oncall/overrides/{id}` | creator, original user or on-call editors | → `200 OverrideDto` (`CANCELLED`); already final `409` |
| GET | `/oncall/availability?userId&all` | readers | `UnavailabilityDto[]` (mine by default; `all=true` everybody's current and future) |
| POST | `/oncall/availability` | staff | `{userId?, startsAt, endsAt, reason? ≤300}` → `201 UnavailabilityDto` (others' only for editors, `403`; ≤ 90 days) |
| DELETE | `/oncall/availability/{id}` | the person or editors | `204` |
| GET / POST | `/oncall/schedules/{id}/handovers?size` | GET readers; POST participants, team members, editors | `HandoverRequest {body 1–5000, toUserId?, shiftStart?, shiftEnd?}` → `201 HandoverDto`; `toUserId` defaults to the primary of the next shift (notified `ONCALL_HANDOVER`) |
| GET | `/oncall/escalation-policies`, `/oncall/escalation-policies/{id}` | readers | `PolicyDto[]`, `PolicyDto` |
| POST / PUT | `/oncall/escalation-policies`, `/oncall/escalation-policies/{id}` | on-call editors | `PolicyRequest` → `201`/`200 PolicyDto`; duplicate name `409`; a USER level needs `userId` (`400 fieldErrors[levels[i].userId]`) |
| GET | `/oncall/pages?scheduleId&alertId&size` (50, ≤ 200) | readers | `PageDto[]` newest first |

- `ScheduleRequest = {teamId, name 2–120, description? ≤1000, timeZone, rotationType, handoffTime? "HH:mm" (09:00), handoffDay? 1–7 (WEEKLY, default 1), shiftHours? 1–336 (CUSTOM), rotationStart (date), primaryUserIds [1..50], secondaryUserIds? [0..50], escalationPolicyId?, holidayCalendarId?, holidayCoverUserId?, active? (true)}`.
- `ScheduleSummary = {id, name, teamId, teamCode, teamName, timeZone, rotationType, handoffTime, handoffDay, shiftHours, rotationStart, primaryCount, secondaryCount, escalationPolicyId, escalationPolicyName, active, demo, primaryNow: OnCallPerson|null, secondaryNow: OnCallPerson|null}`.
- `ScheduleDetail = {schedule: ScheduleSummary, description, primary: ParticipantDto[], secondary: ParticipantDto[], holidayCalendarId, holidayCalendarName, holidayCoverUserId, holidayCoverUserName, upcomingHolidays: [{date, name}] (≤ 10), overrides: OverrideDto[], handovers: HandoverDto[] (last 10), escalationPolicy: PolicyDto|null, canEdit, createdAt, updatedAt}`; `ParticipantDto = {position, userId, userName, email, active, availableNow}`.
- `NowEntry = {scheduleId, scheduleName, teamId, teamCode, teamName, timeZone, at, primary: OnCallPerson|null, secondary: OnCallPerson|null, escalationPolicyId, escalationPolicyName}`; `OnCallPerson = {userId, name, email, phone, source, since, until, scheduledUserId, scheduledUserName, holiday}` (`since`/`until` = the continuous assignment; `scheduledUser*` = the rotation participant of the slot).
- `ShiftDto = {layer PRIMARY|SECONDARY, userId, userName, start, end, source, scheduledUserId, scheduledUserName, holiday, overrideId}`; `MyShift = {scheduleId, scheduleName, teamId, teamName, layer, start, end, source, current}`.
- `OverrideRequest = {layer? (PRIMARY), userId?, originalUserId? (editors), startsAt, endsAt, reason? ≤500, offer?}`; `OverrideDto = {id, scheduleId, scheduleName, layer, userId, userName, originalUserId, originalUserName, startsAt, endsAt, reason, status OFFERED|ACTIVE|DECLINED|CANCELLED, createdByName, takenAt, decidedNote, canTake, demo, createdAt}`.
- `UnavailabilityDto = {id, userId, userName, startsAt, endsAt, reason, demo, createdAt}`; `HandoverDto = {id, scheduleId, authorId, authorName, toUserId, toUserName, shiftStart, shiftEnd, body, demo, createdAt}`.
- `PolicyRequest = {name 2–120, description? ≤500, teamId?, minSeverity? (MAJOR), levels: [{delayMinutes 0–1440, target PRIMARY|SECONDARY|MANAGER|USER, userId?}] (1–10), active?}`; `PolicyDto = {id, name, description, teamId, teamName, minSeverity, levels: [{level, delayMinutes, minutesFromStart, target, userId, userName, label}], active, scheduleCount, demo, createdAt, updatedAt}`.
- `PageDto = {id, alertId, alertNumber, alertTitle, severity, alertStatus, level, target, userId, userName, scheduleId, scheduleName, pagedAt, acknowledgedAt, ackMinutes, demo}`.
- Audit `ONCALL_SCHEDULE_CREATED/UPDATED`, `ONCALL_OVERRIDE_CREATED`, `ONCALL_OVERRIDE_OFFERED/TAKEN/DECLINED/CANCELLED`, `ONCALL_UNAVAILABILITY_ADDED/REMOVED`, `ONCALL_HANDOVER_ADDED`, `ONCALL_POLICY_CREATED/UPDATED`.

**Alert escalation through on-call** (`OnCallEscalation`, `AlertEscalationPolicy` name `on-call`, order 100): applies to an alert whose routed
team has an active schedule with an active escalation policy and whose severity reaches the policy's `minSeverity`; every other alert keeps
the built-in `team-lead` policy (order 1000, the fallback). Level *n* is due `levels[n-1].delayMinutes` after the previous level (level 1:
after the alert became active) and pages the schedule's primary or secondary on call at that moment (overrides, holidays and unavailability
applied), the team lead plus the department manager (`MANAGER`; the IT managers when neither exists), or a named user; an empty layer falls
back to the team lead and manager so an alert is never silent. Pages are stored in `oncall_pages` (unique per alert, level and person) and
notified as `ONCALL_PAGE` (in-app and e-mail by default; critical alerts as CRITICAL). Together with the escalation service's claim on
`escalation_level` every level pages exactly once. Acknowledging, resolving or suppressing stops the escalation (as before). Alert history:
`ESCALATED` with `details.policy = "on-call"`; `AlertDetail.escalationPolicy` shows `on-call`.

### Major incident command (`MI-###`)
Declaring a major incident — `POST /major-incidents`, the existing `POST /incidents/{id}/major {major: true}`, the automatic declaration at the
duplicate-report threshold, the workflow major-incident action or an approved AI action — publishes `MajorIncidentEvent` inside the incident
transaction, and the command record is opened in the same transaction (the declaring admin or manager becomes incident commander; impacted
service = the incident's service). Clearing the flag adds a timeline entry; declaring a closed one again reopens it. V9 created a record
(`CLOSED`, `RESOLVED` or `ACTIVE` from the incident status) for every incident that was already major. Status `ACTIVE → MONITORING | RESOLVED`,
`MONITORING → ACTIVE | RESOLVED`, `RESOLVED → ACTIVE | CLOSED`, `CLOSED` final (`409` otherwise). RESOLVED needs a `note` or an existing
`resolutionSummary` (`400 fieldErrors[note]`; the note becomes the summary). CLOSED needs the incident RESOLVED or CLOSED (`409`) and opens the
post-incident review (DRAFT, owner = commander) when there is none.

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/major-incidents?status(csv)&search&page&size` | readers | `PageResponse<MajorSummary>`, newest declaration first; unknown status `400` |
| POST | `/major-incidents` | major incident command | `DeclareRequest` → `201 MajorDetail`; linked report `400`; closed incident `409`; already an open major incident `409` |
| GET | `/major-incidents/{id}`, `/major-incidents/by-incident/{incidentId}` | readers | `MajorDetail` |
| PUT | `/major-incidents/{id}` | staff | `UpdateRequest` (null = unchanged; `serviceIds` replaces the impacted services); closed `409` |
| PUT | `/major-incidents/{id}/roles` | command (admins, managers, the commander) | `{commanderId?, techLeadId?, commsLeadId?}` (`0` clears; active staff, `400 fieldErrors`) → `MajorDetail`; new role holders are notified (`MAJOR_INCIDENT_ROLE`) |
| POST | `/major-incidents/{id}/status` | command | `{status, note? ≤2000}` → `MajorDetail` |
| POST | `/major-incidents/{id}/timeline` | staff | `{entryType NOTE|DECISION|ACTION|INVESTIGATION, body ≤4000, occurredAt?}` → `201 TimelineDto`; other types `400` |
| POST | `/major-incidents/{id}/updates` | staff | `StakeholderUpdateRequest` → `201 StakeholderUpdateDto`; notifies leadership, the role holders and the assignee (`STAKEHOLDER_UPDATE`). `publishToStatusPage: true` needs a status page editor, the commander or the communications lead (`403`) and posts a **public status-page update** linked to the incident (confirmed subscribers are e-mailed as for any public update) |
| POST | `/major-incidents/{id}/updates/{updateId}/publish` | status page editors, commander, comms lead | publishes an internal update later; already published `409` |
| POST | `/major-incidents/{id}/actions` | staff | `ActionRequest` → `201 ActionItemDto` (follow-up; the owner is notified `MAJOR_ACTION_ASSIGNED`) |

- `DeclareRequest = {incidentId, commanderId?, techLeadId?, commsLeadId?, summary? ≤2000, serviceIds? (≤ 20), bridgeUrl? (http/https)}`; `UpdateRequest = {summary?, impactSummary?, investigationNotes?, resolutionSummary?, bridgeUrl?, serviceIds?}`.
- `StakeholderUpdateRequest = {status INVESTIGATING|IDENTIFIED|MONITORING|RESOLVED, title? ≤200 (default "Service disruption: <service>"), message 1–5000, publishToStatusPage?}`; `StakeholderUpdateDto = {id, status, title, message, published, statusUpdateId, publishedAt, postedByName, postedAt, demo}`.
- `MajorSummary = {id, number, status, incidentId, incidentNumber, title, priority, incidentStatus, commander, techLead, commsLead (PersonRef|null), declaredAt, resolvedAt, closedAt, durationMinutes, openActions, pir: PirRef|null, demo}`; `PersonRef = {id, name, email}`; `PirRef = {id, number, status}`.
- `MajorDetail = {id, number, status, incident: IncidentSummary, commander, techLead, commsLead, summary, impactSummary, investigationNotes, resolutionSummary, bridgeUrl, declaredAt, declaredByName, resolvedAt, closedAt, durationMinutes (declared → resolved, closed or now), services: [{id, code, name}], timeline: TimelineDto[] (oldest first), updates: StakeholderUpdateDto[] (newest first), actionItems: ActionItemDto[], alerts: [{id, number, title, severity, status}] (linked to the incident), pir, allowedTransitions (commanders; else []), canCommand, canPublish, demo, createdAt, updatedAt}`; `TimelineDto = {id, entryType NOTE|DECISION|ACTION|INVESTIGATION|STATUS|ROLE|UPDATE|SYSTEM, body, actorId, actorName, occurredAt, demo}`.
- Audit `MAJOR_INCIDENT_COMMAND_OPENED`, `MAJOR_INCIDENT_ROLES_ASSIGNED`, `MAJOR_INCIDENT_UPDATED`, `MAJOR_INCIDENT_STATUS_CHANGED`, `MAJOR_INCIDENT_TIMELINE_ADDED`, `MAJOR_INCIDENT_UPDATE_POSTED`, `MAJOR_INCIDENT_UPDATE_PUBLISHED`, `MAJOR_INCIDENT_ACTION_ADDED/UPDATED` (plus the existing `MAJOR_INCIDENT_DECLARED/CLEARED` and `STATUS_UPDATE_POSTED`).

### Post-incident reviews (`PIR-###`)
Status `DRAFT → IN_REVIEW` (submit) `→ APPROVED | REJECTED`, `REJECTED → IN_REVIEW`, `IN_REVIEW → DRAFT` (withdraw). One review per top-level
incident, created with the incident's root cause and resolution notes (or the command record's resolution and impact) pre-filled.
**Timeline** (built at creation and on `timeline/rebuild`; ≤ 400 entries, oldest first): the incident activity (history, comments and work
notes, SLA events, escalations, approvals — as `/incidents/{id}/activity`), duplicate reports (`REPORT`), linked alerts fired, acknowledged and
resolved (`ALERT`), on-call pages (`PAGE`), the war-room timeline (`WAR_ROOM`) and stakeholder updates (`UPDATE`). **Metrics** (computed with
it): `detectedAt` (first linked alert, else the incident), `reportedAt`, `firstResponseAt`, `declaredAt`, `acknowledgedAt` (first alert
acknowledgement), `resolvedAt`, `minutesToDetect` (first alert → incident; null without an earlier alert), `minutesToAcknowledge` (incident →
first response), `minutesToDeclare`, `minutesToResolve`, `alerts`, `pages`, `linkedReports`, `slaOutcome`.
**Approval**: submitting (owner, major incident roles, admins & managers) needs the incident RESOLVED or CLOSED (`409`), `summary`, `rootCause`,
`impact` and at least one non-cancelled CORRECTIVE or PREVENTIVE action (`400 fieldErrors[summary|rootCause|impact|actions]`), and requests an
approval of type **`PIR`** through the approval engine: one stage "Post-incident review approval", approvers ROLE `INCIDENT_MANAGER` and ROLE
`IT_MANAGER`, quorum ANY, 72 h deadline escalating to system administrators; `SYSTEM_ADMIN` may decide any approval; the submitter never
decides (segregation of duties). It appears in the `/approvals` inboxes (link `/reviews/{id}`); the decision (`POST /approvals/{id}/decision`)
is applied by the PIR approval handler: approved → `APPROVED` (`approvedAt`, `approvedByName`), rejected → `REJECTED` with `reviewComment`
(editable, resubmit). The owner is notified (`PIR_UPDATED`). PIR-type approvals not requested by a review submission are left untouched.

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/reviews?status(csv)&search&page&size` | readers | `PageResponse<PirSummary>` newest first |
| POST | `/reviews` | admins & managers, or the incident's commander | `{incidentId, ownerId?, title? ≤200}` → `201 PirDetail`; linked report `400`; existing review `409` |
| GET | `/reviews/{id}`, `/reviews/by-incident/{incidentId}` | readers | `PirDetail` |
| PUT | `/reviews/{id}` | owner, major incident roles, admins & managers | `PirUpdateRequest` (null = unchanged) — DRAFT/REJECTED only (`409`) |
| POST | `/reviews/{id}/timeline/rebuild` | same | `PirDetail` |
| POST | `/reviews/{id}/submit`, `/reviews/{id}/withdraw` | same | `PirDetail` |
| POST | `/reviews/{id}/actions` | same (not while IN_REVIEW, `409`) | `ActionRequest` (default type CORRECTIVE) → `201 ActionItemDto`; owner notified (`PIR_ACTION_ASSIGNED`) |
| GET | `/reviews/{id}/pdf` | readers | `application/pdf`, `Content-Disposition: attachment; filename="PIR-###.pdf"` (analysis, key times, actions, timeline via `ReportPdf`); audit `PIR_EXPORTED` |
| GET | `/action-items/mine?size` | staff | my open action items (both kinds), soonest due first |
| PUT | `/action-items/{id}` | staff (major incident items); review items: editors of the review or the item's owner | `ActionRequest` → `ActionItemDto` |

- `PirUpdateRequest = {title? 5–200, ownerId?, summary?, rootCause?, contributingFactors? string[≤ 20], impact?, detectionGaps?, responseAnalysis?, lessonsLearned?}`.
- `PirSummary = {id, number, title, status, incidentId, incidentNumber, incidentTitle, majorIncidentId, majorIncidentNumber, owner, openActions, actionCount, submittedAt, approvedAt, demo, createdAt, updatedAt}`.
- `PirDetail = {id, number, title, status, incident: IncidentSummary, majorIncidentId, majorIncidentNumber, owner, summary, rootCause, contributingFactors, impact, detectionGaps, responseAnalysis, lessonsLearned, timeline: [{at, kind, source, title, detail, actorName}], metrics {…}, timelineBuiltAt, actions: ActionItemDto[], approvalId, approvalStatus, submittedAt, approvedAt, approvedByName, reviewComment, canEdit, canSubmit, demo, createdAt, updatedAt}`.
- `ActionRequest = {title ≤200, description? ≤2000, actionType? CORRECTIVE|PREVENTIVE|FOLLOW_UP, ownerId? (active staff), dueDate?, status? OPEN|IN_PROGRESS|DONE|CANCELLED}`; `ActionItemDto = {id, source MAJOR_INCIDENT|PIR, majorIncidentId, pirId, title, description, actionType, ownerId, ownerName, dueDate, status, overdue, completedAt, demo, createdAt, updatedAt}`.
- Audit `PIR_CREATED`, `PIR_UPDATED`, `PIR_TIMELINE_REBUILT`, `PIR_SUBMITTED`, `PIR_WITHDRAWN`, `PIR_APPROVED`, `PIR_REJECTED`, `PIR_EXPORTED`, `PIR_ACTION_ADDED/UPDATED`.

### Analytics extensions
| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/analytics/operations?days&from&to&departmentId` | readers | `OperationsMetrics` (same period rules as the other analytics endpoints) |
| GET | `/analytics/definitions` | any user | `MetricDefinition[] {key, name, unit, formula, sources[], filters[], notes}` — how every metric is computed |
| GET | `/analytics/sla-risk?limit` (10, ≤ 50) | readers | `SlaRiskOverview {generatedAt, incidents: SlaRisk[], method}` — active incidents with a running clock ranked by computed breach probability (statistics, no LLM) |
| GET | `/analytics/dashboard` | any user | `Dashboard` adds `commandCenter: CommandCenter` (staff and auditors; `null` for employees); every existing field is unchanged |

`OperationsMetrics = {from, to, days, departmentId, generatedAt, mtta: {minutes, samples}, mttd: {minutes, samples, incidentsWithoutAlert},
firstContactResolution: {rate, resolved, resolvedAtFirstContact}, changes: {finished, completed, failed, rolledBack, successRate, failureRate},
problemRecurrence: {knownErrors, problemsWithRecurrence, recurringIncidents, rate}, alertVolume: {total, occurrences, bySeverity {CRITICAL…INFO},
bySource {PROMETHEUS…MANUAL}, suppressed, withIncident, perDay: [{date, count}]}, assetLifecycle: {byStatus {IN_STOCK…DISPOSED}, acquired, retired,
disposed, averageAgeDays, warrantyExpiringNext30Days}, onCall: {alertsPaged, pages, acknowledged, ackRate, avgAckMinutes, escalated, bySchedule:
[{scheduleId, scheduleName, teamId, teamName, alertsPaged, pages, acknowledged, ackRate, avgAckMinutes, escalated}]}, departmentPerformance:
[{departmentId, name, created, resolved, open, mttaMinutes, mttrMinutes, slaCompliance, firstContactResolution}], serviceAvailability:
[{serviceId, code, name, tier, availability, outageMinutes, degradedMinutes, incidents}] (least available first)}`. Rates are % with one decimal
and `null` without data; durations are minutes with one decimal and `null` without samples.

**Metric definitions** (also served by `/analytics/definitions`). Incident metrics count top-level incidents and never count cancellations as
resolved. `departmentId` = the incident's assignment department, a change's team's department, a problem's department, an alert's routed team's
department (else its service's), an asset's owning department, a schedule team's department, a service's department.

| Key | Definition |
|---|---|
| `mtta` | mean (first response − created) over incidents created in the period that have a first response (same as `kpis.mttaMinutes`) |
| `mttd` | mean (incident created − first linked alert fired) over non-cancelled incidents created in the period that have an alert (on the incident or one of its duplicate reports) that fired at or before the incident was recorded; others are counted in `incidentsWithoutAlert`; `minutes: null` = n/a |
| `mttr` | mean (resolved − created) over incidents resolved in the period (existing `kpis.mttrMinutes`) |
| `firstContactResolution` | incidents resolved in the period with ≤ 1 distinct assignee in their history and never reopened ÷ incidents resolved in the period × 100 |
| `slaCompliance` | resolution clocks completed in the period MET ÷ (MET + MISSED) × 100 (existing) |
| `changes.successRate` / `changes.failureRate` | COMPLETED ÷ (COMPLETED + FAILED + ROLLED_BACK) × 100 / (FAILED + ROLLED_BACK) ÷ finished × 100 over changes finished in the period (`closed_at`, else actual end) |
| `problemRecurrence` | known-error problems with ≥ 1 linked incident created after the known error was recorded (and in the period) ÷ known-error problems existing in the period (known error before its end, not closed before its start) × 100; `recurringIncidents` counts those incidents |
| `alertVolume` | alerts first seen in the period by severity, source and day (organisation time zone); `occurrences` = sum of deduplicated repeats; `suppressed` = currently SUPPRESSED; `withIncident` = linked to an incident |
| `assetLifecycle` | `byStatus` now; `acquired` = purchase date in the period (else created in it); `retired`/`disposed` = lifecycle date in the period; `averageAgeDays` = mean (period end date − purchase date) of in-stock, in-use and in-repair hardware; `warrantyExpiringNext30Days` = active hardware whose warranty ends within 30 days from today |
| `onCall` | per alert whose first page is in the period: `acknowledged` if acknowledged; `avgAckMinutes` = mean (acknowledged − first page); `ackRate` = acknowledged ÷ alerts paged × 100; `escalated` = alerts that paged level ≥ 2; `pages` = individual pages |
| `departmentPerformance` | per department: created and resolved in the period, open now, MTTA, MTTR, SLA compliance and FCR as above |
| `serviceAvailability` | 100 − (merged P1 downtime + 25 % of merged P2 time) ÷ window × 100 per service, window = the period up to now — the same rule as the service and status-page availability |
| `commandCenter.slaBreachesInPeriod` | resolution SLA clocks of top-level incidents whose breach time is in the period |
| `commandCenter.workflows` | executions started in the last 24 h (total, succeeded, failed) and those running or waiting for a retry now (not period-filtered) |

`CommandCenter = {incidents: {open, criticalOpen, majorOpen, createdInPeriod, resolvedInPeriod, slaBreachesInPeriod, slaAtRiskNow, mttaMinutes},
alerts: {active, open, acknowledged, critical, escalated, resolvedLast24h}, onCallNow: NowEntry[], changes: {awaitingApproval, scheduledNext7Days,
implementingNow, completedInPeriod, failedInPeriod, emergencyOpen}, workflows: {executions24h, succeeded24h, failed24h, running},
activeMajorIncidents: [{id, number, title, status, commanderName, declaredAt}] (≤ 5), departmentPerformance: DepartmentPerformance[] (≤ 8),
recentActivity: [{kind INCIDENT|ALERT|CHANGE|DEPLOYMENT|MAJOR_INCIDENT, at, title, detail, actorName, link}] (15 newest)}` — live counts are the
current state, "InPeriod" counts follow the dashboard period, and everything except `onCallNow` and `workflows` honours `departmentId`.

### SA AI extensions — every response is `AiResult<T>` (`source` LLM | FALLBACK)
Each feature first computes its evidence, scores and probabilities from the database (the deterministic engine); with an LLM configured and
enabled the model may only **select among and explain** the computed candidates. Model calls go through `GuardedLlm`: a hard timeout per
attempt (`AI_TIMEOUT_SECONDS`), one retry for transient failures (provider error, truncated or invalid output — never for refusals), the
feature's schema validation (e.g. chosen ids must be candidates; at least one recommendation), and a content guard that rejects output with
SQL statements, shell/PowerShell/cloud-CLI/kubectl/Terraform commands or code blocks — SA AI never generates SQL or infrastructure commands.
Any failure gives the deterministic result labelled `FALLBACK` with its `fallbackReason` (e.g. "The LLM response failed validation"; "No AI
provider configured"). Every POST counts toward the per-user AI rate limit (`RATE_LIMIT_AI_PER_MINUTE`, `429` + `Retry-After`); interactions
are logged in `ai_interactions` (features `problem_correlation`, `cmdb_impact`, `runbook_recommendation`, `sla_risk`, `trend_analysis`).

| Method | Path | Access | Data |
|---|---|---|---|
| POST | `/ai/problems/{id}/correlation` | readers | `ProblemCorrelation {problemId, number, title, candidates: [{incidentId, number, title, status, createdAt, score 0–1, reasons[]}] (≤ 10), changes: [{changeId, number, title, status, startedAt, incidentsWithin48h}], alertGroups: [{groupKey, title, alerts, maxSeverity}], summary, basis}` — unlinked incidents of the last 90 days scored 0.7 × text similarity (to the problem and its linked incidents) + 0.2 same service + 0.1 same category (≥ 0.3); changes on the problem's services in the 14 days before its incidents; alert groups on those services (30 days) |
| POST | `/ai/changes/{id}/risk` | readers | the existing change-risk analysis (BE-2), reused |
| POST | `/ai/cmdb/cis/{id}/impact` | readers | `CmdbImpact {ciId, ciName, ciType, criticality, riskLevel LOW|MEDIUM|HIGH, impactedServices: [{serviceId, code, name, tier, status}], upstreamCount, downstreamCount, criticalUpstream[], openIncidents, openAlerts, summary, recommendations[], truncated, basis}` — CMDB traversal in both directions, depth 4; HIGH with a tier-1 service, a CRITICAL CI or an outage, MEDIUM with any dependent service or ≥ 5 dependent CIs |
| POST | `/ai/incidents/{id}/runbooks` | staff with access to the incident | `RunbookRecommendations {incidentId, incidentNumber, recommendations: [{runbookId, title, score 0–100, requiresApproval, estimatedMinutes, reasons[]}] (≤ 5), rationale, basis}` — same service 40, matching category 20, text similarity ≤ 30, completed share of earlier runs on the service ≤ 10; candidates ≥ 15 |
| POST | `/ai/incidents/{id}/sla-risk` | readers with access to the incident | `SlaRisk {incidentId, number, title, priority, policyName, clockStatus, elapsedPercent, remainingMinutes, resolutionDueAt, probability 0–1|null, riskLevel LOW|MEDIUM|HIGH|N/A, probabilityLabel, sampleSize, historicalBreaches, historyDays, method, factors[], recommendations[]}` |
| POST | `/ai/trends?days` (28, 7–90) | readers | `TrendAnalysis {from, to, previousFrom, previousTo, days, trends: [{metric, dimension, current, previous, changePercent, direction UP|DOWN|FLAT, zScore, significant}], highlights[], narrative, basis}` — incidents (total, by service, by category), alerts by severity, failed or rolled-back changes and MTTR in the last *days* against the *days* before; significant when z = (current − previous) / √(current + previous) has \|z\| ≥ 2 with ≥ 6 events (MTTR: ≥ 25 % change with ≥ 5 resolutions in each window) |

**Predictive SLA risk** — `probability` is computed, never generated. Let *e* = the elapsed share of the resolution target (from the SLA
engine; business time when the policy uses business hours). The sample = resolution clocks of the same priority completed MET or MISSED in
the last `ai.sla_risk.history_days` (180) days whose consumed share exceeded *e* (still running at the same point); *s* = sample size, *b* =
MISSED among them. probability = *b* / *s* when *s* ≥ 5, else (*b* + 2*e*) / (*s* + 2) (smoothed toward the elapsed share); 1.00 once the target
is exceeded or the clock breached; completed clocks report 0 or 1 with "nothing to predict". riskLevel HIGH ≥ 0.70, MEDIUM ≥ 0.40, else LOW.
`probabilityLabel` states the sample and that it is "a statistic, not an AI estimate"; the LLM may only add recommendations.

### Extension-point contributions
- **My Work**: `onCallShifts` (140; `Section<MyShift>` — the next 14 days), `actionItems` (150; `Section<{id, title, actionType, status, dueDate, overdue, source MAJOR_INCIDENT|PIR, sourceId, sourceNumber, sourceTitle, link}>` — my open action items).
- **Reports**: `ONCALL` generator — CSV: every computed shift in the period (`schedule, team, layer, person, email, start, end, hours, source, holiday, demo`); PDF: schedules with who is on call, hours on call per person, pages and acknowledgement times; filter `departmentId`; shifts for at most the last 62 days of the period. Every report type is now available.
- **Approval engine**: handler for type `PIR`.
- **Notification categories** (V9 `ck_notification_pref_type`): `ONCALL_PAGE` (e-mail on by default), `ONCALL_SHIFT` (`ONCALL_OVERRIDE`, `ONCALL_HANDOVER`), `MAJOR_INCIDENT_UPDATE` (`MAJOR_INCIDENT_ROLE`, `STAKEHOLDER_UPDATE`, `MAJOR_ACTION_ASSIGNED`), `PIR_UPDATED` (`PIR_ACTION_ASSIGNED`) — 26 categories.
- **Settings** (V9): `ai.sla_risk.history_days` 180, `pir.due_days` 5.
- **Domain event** `MajorIncidentEvent(incidentId, major, actorId, actorName, automatic)` — synchronous, inside the incident transaction.

### Demo data (all `demo: true`, seeded once per part by `DemoRelease3OpsSeeder`, also on upgraded databases)
Escalation policies "Standard IT on-call" (primary after 5 min → secondary +10 → team lead and manager +15) and "Payroll critical path" (primary
at once → secondary +5 → Priya Raman +10). An on-call schedule for each IT team, all in Asia/Kolkata: Payroll & HR Apps weekly (Monday 09:00,
holiday calendar = the default SLA calendar, holiday cover Priya Raman), Collaboration Apps weekly (Wednesday 10:00), Network Operations daily
08:00 with Kiran Rao as standing secondary, Service Desk L1 12-hour CUSTOM shifts, Database Operations, Identity & Security weekly, Desktop
Support daily. An ACTIVE override (Dev Nair covers Riya Varma tomorrow 09:00–21:00 IST), an open shift offer on Network Operations, Meera
Sharma's annual leave, handover notes, and on-call pages for the acknowledged payroll alerts. The payroll major incident (the demo
payroll incident; declared major if it was not — the command status follows the incident: ACTIVE while it is active, RESOLVED or CLOSED
otherwise; on a freshly seeded database it is a CLOSED record with the DRAFT review below, and there is no live payroll outage until the
payroll-outage scenario is run) has its command record: commander Sunita Joshi, technical lead Riya Varma, communications lead
Priya Raman, a war-room timeline (investigation, decision, action, notes), two stakeholder updates — "Payroll portal unavailable" also
published on the status page — and three action items, plus a DRAFT review "Payroll 503s during the morning batch". The earlier HR portal
sign-in outage has a CLOSED command record and an APPROVED review (analysis, three corrective and preventive actions, approved through the
approval engine by Sunita Joshi).

## Release 4 — simulation, observability, digital twin ([RELEASE4_SPEC](docs/RELEASE4_SPEC.md) §1, §2, §4.1, §4.2 — migration `V10__release4_simulation_observability_twin.sql`)

Release 4 / BE-5. Every list endpoint of this section excludes simulated records unless `includeSimulated=true`; every record that can be
simulated carries `simulated: boolean`. Times are ISO-8601 UTC; time ranges default to the last hour unless stated. "Readers" =
`OBSERVABILITY_READERS` (= `READERS`, which now include `SECURITY_ANALYST` and `FINOPS_ANALYST`); "telemetry config" = `TELEMETRY_CONFIG`
(`SYSTEM_ADMIN`, `IT_ADMIN`). Configuration changes are audited (audit action in brackets).

### Roles and groups (Release 4)
New roles `SECURITY_ANALYST`, `FINOPS_ANALYST` (check constraints on `users.role`, `account_tokens.role`, `approvals.approver_role`); both
are in `READERS`, not in `STAFF`. Demo users (existing demo password mechanism): `vikram.sec@demo.nexus360.io` (Vikram Sen,
SECURITY_ANALYST, IT-SEC) and `neha.finops@demo.nexus360.io` (Neha Kapoor, FINOPS_ANALYST, FIN).

| `Roles` group (+ `*_ROLES` array) | Roles |
|---|---|
| `OBSERVABILITY_READERS` | READERS (SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, INCIDENT_MANAGER, CHANGE_MANAGER, ASSET_MANAGER, SUPPORT_AGENT, AUDITOR, SECURITY_ANALYST, FINOPS_ANALYST) |
| `TELEMETRY_CONFIG` | SYSTEM_ADMIN, IT_ADMIN |
| `TWIN_EDITORS` | SYSTEM_ADMIN, IT_ADMIN, ASSET_MANAGER, CHANGE_MANAGER |
| `PREDICTION_ADMINS` | SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER |
| `AGENT_USERS` | STAFF + SECURITY_ANALYST |
| `AI_GOVERNANCE` | SYSTEM_ADMIN, IT_ADMIN |
| `PLAYBOOK_EDITORS` | SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, CHANGE_MANAGER |
| `PLAYBOOK_APPROVERS` | SYSTEM_ADMIN, IT_MANAGER, CHANGE_MANAGER |
| `REMEDIATION_OPERATORS` | STAFF |
| `EXECUTION_SYSTEM_ADMINS` | SYSTEM_ADMIN |
| `SECOPS` / `SECOPS_READERS` | SYSTEM_ADMIN, IT_ADMIN, SECURITY_ANALYST, INCIDENT_MANAGER / + AUDITOR, IT_MANAGER |
| `FINOPS` / `FINOPS_READERS` | SYSTEM_ADMIN, IT_ADMIN, FINOPS_ANALYST, IT_MANAGER / + AUDITOR, CHANGE_MANAGER, ASSET_MANAGER |
| `DEX_READERS` | READERS |
| `SIMULATION_ADMINS` | SYSTEM_ADMIN |

Employees get `403` on every endpoint of this section except `GET /simulation/status`. Auditors and the two analysts read everything here
and write nothing (twin simulations are read-only and therefore open to them).

### Simulation mode
Setting `simulation.enabled` (BOOLEAN, default false), effective only where the deployment allows it: `SIMULATION_ALLOWED`
(`app.simulation.allowed`) is **false** by default and true in the `local` profile, so production stays off unless set. Scenarios generate
data only through the real ingestion services (OTLP ingestion, deployment service) with `simulated = true`.

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/simulation/status` | any signed-in user (global SIMULATION banner) | `StatusDto` |
| GET | `/simulation/runs?limit` | READERS | `RunDto[]`, newest first, `limit` ≤ 100 (20) |
| PUT | `/simulation/mode` | SIMULATION_ADMINS | `{enabled}` → `StatusDto`; `409` when not allowed; off stops running scenarios [`SETTING_UPDATED`, `SIMULATION_MODE_ENABLED` / `_DISABLED`] |
| POST | `/simulation/scenarios/{code}/start` | SIMULATION_ADMINS | → `RunDto`; `404` unknown code; `409` mode off, already running or start failed (run recorded FAILED) [`SIMULATION_SCENARIO_STARTED`] |
| POST | `/simulation/scenarios/{code}/stop` | SIMULATION_ADMINS | → `RunDto` (STOPPED); `409` not running [`SIMULATION_SCENARIO_STOPPED`] |
| DELETE | `/simulation/data` | SIMULATION_ADMINS | stops running scenarios, deletes rows with `simulated = true` only plus the fault windows → counts `{correlation_groups, telemetry_anomalies, alerts, incidents, deployments, telemetry_metric_series, telemetry_logs, telemetry_spans, telemetry_resource_changes, telemetry_resources, telemetry_rollup_queue, simulation_fault_windows}` [`SIMULATION_DATA_PURGED`] |

- `StatusDto {enabled, allowed, settingEnabled, scenarios: ScenarioDto[], runningRuns: RunDto[], simulatedRecords: {<table>: count}}`
- `ScenarioDto {code, title, description, module, tickIntervalSeconds, running, runId, startedAt, lastTickAt, tickCount}`
- `RunDto {id, scenario, status (RUNNING|STOPPED|COMPLETED|FAILED), startedAt, stoppedAt, lastTickAt, nextTickAt, tickCount, summary {metricPoints, logRecords, spans, deployments}, error, startedByName, stoppedByName}`

Scenarios are `SimulationScenario` beans (`code`, `title`, `description`, `module`, `tickInterval`, `maxDuration`, `ownedServiceNames`,
`start(ctx)`, `tick(ctx)`, `stop(ctx)`; `SimulationContext` offers `otlp(signal, json)`, `recordDeployment(request)`, `openFaultWindow(...)`,
`state()`, `random()`). BE-7/BE-8 add `VULN_WAVE`, `COST_SPIKE`, `ENDPOINT_DEGRADATION` as further beans (no migration change needed).

| Code | Data |
|---|---|
| `BASELINE` | OTLP `service.name`s `payroll-api`, `payroll-db`, `hr-portal`, `erp-finance`: `http.server.request.duration` histograms by status code, `process.cpu.utilization`, pool gauges `db.client.connections.usage{state=used\|idle}`, `.max`, `.pending_requests`, `db.client.operation.duration`, access logs, two traces per minute (server + DB client spans). Back-fills 60 min. Skips services owned by another running scenario |
| `PAYROLL_DB_SATURATION` | back-fills 90 normal minutes; from the 2nd tick the payroll pool climbs about 6 connections/min to 50, pending requests queue, API latency and error ratio rise, FATAL/ERROR logs, failing spans. Fault window "Payroll DB connection pool saturation" (metric `db.client.connections.usage`). With the demo detectors: anomalies → OBSERVABILITY alerts → correlation group (root `payroll-db-primary`) → incident, all simulated |
| `DEPLOY_REGRESSION` | back-fills 90 min of `hr-portal` 5.2.0, records a simulated deployment HR-PORTAL 5.3.0 through the deployment service; from the 1st tick `service.version` 5.3.0 (tracked resource change) and latency ×1.8. `409` without the HR-PORTAL service |

`IncidentSummary`/`IncidentDetail`, `AlertSummary` and `DeploymentDto` gained `simulated` (incidents raised from simulated alerts are simulated).
`AlertSummary`, `AlertDetail` and `IncidentDetail` also expose additive `correlationGroupId` (`Long | null`, the latest open correlation group containing the alert or linked to the incident). Every Release 1–3 aggregate endpoint (`/analytics/dashboard`, `/analytics/overview`, `/analytics/operations`, `/analytics/export`, `/sla/overview`) excludes `simulated = true` rows by default and accepts `?includeSimulated=true` when simulated rows are explicitly requested.

### OTLP/HTTP JSON ingestion — no session, source token
`POST /otel/v1/metrics`, `/otel/v1/logs`, `/otel/v1/traces` — OpenTelemetry protocol, **JSON encoding only**: `Authorization: Bearer otsec_…`
(an active `OTLP` telemetry source), `Content-Type: application/json`, optional `Content-Encoding: gzip`. Inbound rate-limit bucket
(`inbound:<path>`, `RATE_LIMIT_INBOUND_PER_MINUTE`).

| Response | When |
|---|---|
| `200 {}` | everything accepted |
| `200 {"partialSuccess": {"rejectedDataPoints" \| "rejectedLogRecords" \| "rejectedSpans": n, "errorMessage": "..."}}` | some items invalid: missing/zero time, non-finite value, histogram without sum, invalid trace/span id, end before start, timestamp older than the signal's retention or > 10 min in the future, more than `OTLP_MAX_ITEMS_PER_REQUEST` (10 000) items |
| `401 {"code": 16, "message": "Unauthorized"}` | missing, unknown or inactive token (audited `OTLP_REJECTED`) |
| `415 {"code": 3, "message": ...}` | binary protobuf (`application/x-protobuf`) or any non-JSON content type; unsupported `Content-Encoding` |
| `413 {"code": 8, ...}` | body or decompressed body > `OTLP_MAX_REQUEST_BYTES` (2 097 152) |
| `400 {"code": 3, ...}` | invalid JSON, not an OTLP object, invalid gzip |
| `429` | inbound bucket exhausted |

Normalisation: gauge/sum → `value`; histogram, exponential histogram and summary → `value` = sum/count (mean) plus `count`, `sum`, `min`,
`max` (points with count 0 are accepted, not stored). Log severity → `TRACE|DEBUG|INFO|WARN|ERROR|FATAL` (OTLP numbers 1-4 … 21-24, else the
text, else INFO); body ≤ 8 KB (`bodyTruncated`); ≤ 64 attributes per log record/span, 32 per point, 64 per resource (excess counted as
`droppedAttributes`). Spans: `kind` UNSPECIFIED|INTERNAL|SERVER|CLIENT|PRODUCER|CONSUMER, `statusCode` UNSET|OK|ERROR, `durationMs`; stored
once per (traceId, spanId), so exporter retries are harmless. A resource is identified by `service.name`, `service.namespace`,
`deployment.environment(.name)`, `host.name`, `k8s.cluster|namespace|deployment|statefulset|daemonset.name`, `cloud.provider|account.id|region`,
`faas.name`; changes of `service.version`, `container.image.*`, `host.image.*`, `os.version`, `process.runtime.version`, `telemetry.sdk.version`,
`faas.version`, `k8s.deployment.revision` are recorded as resource changes. The first authenticated delivery makes the source CONNECTED.

### Telemetry sources, mapping rules, resources, retention

| Method | Path | Roles | Body / response |
|---|---|---|---|
| GET | `/observability/overview?includeSimulated` | readers | `{includeSimulated, sources, connectedSources, resources, unmappedResources, series, openAnomalies, openCorrelationGroups, detectors, simulatedDataPresent, lastDataAt}` |
| GET | `/observability/sources` · `/observability/sources/{id}` | readers | `SourceDto[]` · `SourceDto` |
| GET | `/observability/sources/kinds` | readers | `KindInfo[] {kind, prepared, pullSupported, testSupported, defaultBaseUrl, configKeys[], description}` |
| POST | `/observability/sources` | telemetry config | `SourceRequest` → `201 SourceSaved {source, token}` (`token` only for OTLP, shown once) [`TELEMETRY_SOURCE_CREATED`] |
| PUT | `/observability/sources/{id}` | telemetry config | `SourceRequest` (kind fixed; `secret: null` keeps the secret) → `SourceDto`; endpoint/credential changes reset pull kinds to NOT_CONNECTED [`TELEMETRY_SOURCE_UPDATED`] |
| DELETE | `/observability/sources/{id}` | telemetry config | `204` [`TELEMETRY_SOURCE_DELETED`] |
| POST | `/observability/sources/{id}/rotate-token` | telemetry config | OTLP only → `SourceSaved` with the new token; the old one stops working [`TELEMETRY_SOURCE_TOKEN_ROTATED`] |
| POST | `/observability/sources/{id}/test` | telemetry config | → `TestResult {sourceId, status, success, httpStatus, message, testedAt, lastVerifiedAt}` [`TELEMETRY_SOURCE_TESTED`] |
| POST | `/observability/sources/{id}/pull?lookbackMinutes` | telemetry config | Prometheus only → `PullResult {sourceId, queries, series, points, rejected, message, from, to}`; `409` for prepared kinds or inactive sources |
| GET | `/observability/mappings` · `/observability/mappings/{id}` | readers | `MappingRuleDto {id, name, attributeKey, matchType, matchValue, serviceId, serviceName, ciId, ciName, sortOrder, enabled, matchCount, lastMatchedAt, demo, createdAt, updatedAt}` |
| POST · PUT · DELETE | `/observability/mappings` · `/{id}` · `/{id}` | telemetry config | `MappingRuleRequest {name, attributeKey, matchType (EXACT\|REGEX, full match), matchValue, serviceId?, ciId?, sortOrder?, enabled?}` (service or CI required) [`TELEMETRY_MAPPING_CREATED` / `_UPDATED` / `_DELETED`] |
| POST | `/observability/mappings/reapply?limit` | telemetry config | → `ReapplyResult {resources, changed, mapped, unmapped}` [`TELEMETRY_MAPPINGS_REAPPLIED`] |
| GET | `/observability/resources?mapped&search&serviceId&includeSimulated&page&size` | readers | `PageResponse<ResourceDto {id, serviceName, serviceNamespace, environment, hostName, attributes, serviceId, serviceCode, mappedServiceName, ciId, ciName, mappingRuleId, sourceId, simulated, firstSeenAt, lastSeenAt}>`; `mapped=false` lists unmapped telemetry (`serviceId` null) |
| GET | `/observability/resources/{id}/changes` | readers | `ResourceChangeDto[] {id, resourceId, attributeKey, fromValue, toValue, observedAt, simulated}` |
| GET · PUT | `/observability/retention` | readers · telemetry config | `RetentionDto {rawDays (7), rollupDays (90), logsDays (14), tracesDays (7)}`; PUT bounds 1-90 / 1-3650 / 1-365 / 1-90, `rollupDays ≥ rawDays` [`SETTING_UPDATED`] |
| POST | `/observability/retention/purge` | telemetry config | → `PurgeResult {ranAt, points, rollups, logs, spans, series, resources}` [`TELEMETRY_RETENTION_PURGED`] |

- `SourceDto {id, name, kind, status (NOT_CONNECTED|CONNECTED|ERROR), prepared, baseUrl, authType (NONE|BEARER|BASIC|API_KEY), username, hasSecret, tokenHint, config, pullEnabled, pullIntervalSeconds, active, lastVerifiedAt, lastTestedAt, lastError, lastReceivedAt, lastPulledAt, receivedCount, ingestPaths {metrics, logs, traces}, demo, createdAt, updatedAt}`
- `SourceRequest {name, kind, baseUrl?, authType?, username?, secret? (write-only), config?, pullEnabled?, pullIntervalSeconds? (30-3600), active?}`

| `kind` | Status | `config` |
|---|---|---|
| `OTLP` | push; CONNECTED after the first authenticated delivery | none (no base URL, authType NONE) |
| `PROMETHEUS_API` | real client: test `GET /api/v1/status/buildinfo`, pull `GET /api/v1/query_range` (60 s step), scheduled when `pullEnabled` | `queries: [{name, query}]` (≤ 20); labels `service`/`job` → `service.name`, `instance` host → `host.name`, `namespace` → `k8s.namespace.name`, others are point attributes |
| `GRAFANA` | prepared: `POST /api/ds/query` builder + data-frame parser; test `GET /api/datasources/uid/{uid}` | `datasourceUid`, `queries: [{name, expr}]` |
| `CLOUDWATCH` | prepared: GetMetricData builder + parser; no test call (AWS SigV4 not implemented), stays NOT_CONNECTED | `region`, `queries: [{name, namespace, metricName, dimensions?, stat?, period?}]` |
| `AZURE_MONITOR` | prepared: metrics REST builder + parser; test lists metric definitions with an Entra ID access token as secret | `resourceId`, `queries: [{name, metricName, aggregation?}]` |
| `GCP_MONITORING` | prepared: timeSeries.list builder + parser; test lists metric descriptors with an OAuth access token as secret | `projectId`, `queries: [{name, filter, aligner?}]` |

Prepared kinds cannot pull (`pullEnabled: true` → `400`, pull → `409`). Outbound calls go through `UrlSafety` before every call, never follow
redirects, time out after `app.integrations.timeout-seconds` (10 s) and cap responses at 5 MB; credentials are AES-256-GCM (`SecretCipher`)
and never returned; OTLP tokens are stored as SHA-256 hashes only.

### Telemetry queries

| Method | Path | Roles | Response |
|---|---|---|---|
| GET | `/observability/metrics?search&serviceId&includeSimulated` | readers | `MetricNameDto[] {name, type, unit, seriesCount, lastSeenAt, simulated}` |
| GET | `/observability/metrics/series?name&serviceId&ciId&resourceId&attr.<key>=<value>&from&to&step&aggregation&includeSimulated` | readers | `SeriesResponse {metric, aggregation, from, to, stepSeconds, includeSimulated, simulated, status (OK\|NO_DATA), message, series: SeriesDto[]}`, `SeriesDto {seriesId, metric, type, unit, attributes, resourceId, resourceServiceName, serviceId, ciId, simulated, points: [{t, v, samples}]}`; `aggregation` avg (default) \| max \| min \| sum \| p95 over the minute roll-ups (p95 = 95th percentile of the minutes' p95 values); `step` a multiple of 60 s, ≤ 1500 points, ≤ 50 series |
| GET | `/observability/logs?text&severity&serviceId&resourceId&traceId&from&to&includeSimulated&page&size` | readers | `PageResponse<LogDto {id, timestamp, severity, severityText, body, bodyTruncated, attributes, traceId, spanId, resourceId, resourceServiceName, serviceId, simulated}>`; `severity` = minimum severity, `text` = substring |
| GET | `/observability/traces?serviceId&name&minDurationMs&error&from&to&limit&includeSimulated` | readers | `TraceSummary[] {traceId, rootName, rootServiceName, serviceId, startTime, durationMs, spanCount, errorCount, simulated}` (≤ 200) |
| GET | `/observability/traces/{traceId}` | readers | `TraceDetail {traceId, startTime, endTime, durationMs, spanCount, errorCount, simulated, truncated, roots: SpanNode[]}`, `SpanNode {spanId, parentSpanId, name, kind, serviceName, serviceId, resourceId, startTime, endTime, durationMs, offsetMs, statusCode, statusMessage, attributes, simulated, children[]}` (≤ 5000 spans) |
| GET | `/observability/services/red?from&to&includeSimulated` · `/observability/services/{serviceId}/red` | readers | `RedSummary {serviceId, serviceCode, serviceName, from, to, basis (SPANS\|METRICS\|NONE), requests, ratePerMinute, errors, errorRate, durationP95Ms, durationAvgMs, simulated, status (OK\|INSUFFICIENT_DATA), message}`; SPANS = SERVER spans (else root spans); METRICS fallback = `http.server.request.duration` histograms (delta temporality assumed, mean only, `durationP95Ms` null) |

### Anomaly detection
Model (`AnomalyEngine`): per series of minute values (the roll-up's `valueAggregation`), an EWMA baseline (α = 2/(window+1); exceeding points
do not update it unless the excursion lasts longer than `window` points) or median/MAD over the previous `window` points; hour-of-week
seasonality (median/MAD of the same hour in earlier weeks) with `seasonality` HOUR_OF_WEEK, or AUTO when the series has ≥ 14 days of
history; σ floor max(1e-6, 1 % of the baseline); a point exceeds when z ≥ threshold (UP), z ≤ −threshold (DOWN) or |z| ≥ threshold (BOTH);
an anomaly is a run of ≥ `consecutivePoints` exceeding points; the expected range is baseline ± threshold·σ at the peak; nothing is scored
before `minSamples` baseline points. Detectors run every minute over the last max(180, 2·window + minSamples + consecutive + 10) minutes (15
days with seasonality). A newly **open** anomaly raises an alert when `raiseAlert`: source kind `OBSERVABILITY`, fingerprint
`anomaly:<detectorId>:<seriesId>`, group key `observability:detector:<id>`, severity `alertSeverity`, converted to an incident when
`createIncident` (regular pipeline: dedupe, suppression, routing); the alert is resolved by the source when the anomaly ends.

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/observability/anomaly-detectors` · `/{id}` | readers | `DetectorDto[]` · `DetectorDto` |
| POST · PUT · DELETE | `/observability/anomaly-detectors` · `/{id}` · `/{id}` | telemetry config | `DetectorRequest` [`ANOMALY_DETECTOR_CREATED` / `_UPDATED` / `_DELETED`] |
| POST | `/observability/anomaly-detectors/{id}/run` | telemetry config | → `RunResult {detectorId, seriesEvaluated, pointsEvaluated, anomaliesOpened, anomaliesUpdated, anomaliesResolved, alertsRaised, status (OK\|INSUFFICIENT_DATA\|NO_SERIES\|DISABLED), message}` ("Insufficient data — N of M samples") |
| POST | `/observability/anomaly-detectors/{id}/evaluate` | telemetry config | `EvaluateRequest {from?, to? (default last 7 days, ≤ 31 days), labels?: [{from, to, label?}], useSimulatedFaults? (default = includeSimulated), useAlerts? (true), toleranceMinutes? (5)}` → `EvaluationResult`, stored as `lastEvaluation` [`ANOMALY_DETECTOR_EVALUATED`] |
| GET | `/observability/anomalies?status (OPEN\|RESOLVED)&detectorId&serviceId&from&to&includeSimulated&page&size` · `/observability/anomalies/{id}` | readers | `PageResponse<AnomalyDto>` · `AnomalyDto` |

- `DetectorRequest {name, description?, metricName, serviceId?, ciId?, attributeFilters? ({key: value}, ≤ 10), valueAggregation? (avg|max|min|sum|p95), algorithm? (EWMA|MEDIAN_MAD), windowPoints? (5-10080, 60), threshold? (1-20, 3.0), consecutivePoints? (1-60, 3), minSamples? (5-100000, 30), direction? (UP|DOWN|BOTH), seasonality? (NONE|HOUR_OF_WEEK|AUTO), includeSimulated? (false), raiseAlert? (true), alertSeverity? (MAJOR), createIncident? (false), enabled? (true)}`
- `DetectorDto {id, name, description, metricName, serviceId, serviceName, ciId, ciName, attributeFilters, valueAggregation, algorithm, windowPoints, threshold, consecutivePoints, minSamples, direction, seasonality, includeSimulated, raiseAlert, alertSeverity, createIncident, enabled, lastRunAt, lastEvaluation, lastEvaluatedAt, openAnomalies, demo, createdAt, updatedAt}`
- `EvaluationResult {detectorId, status (EVALUATED|INSUFFICIENT_DATA), message, from, to, seriesEvaluated, pointsEvaluated, detections: [{seriesId, from, to, peakScore, observed, expected}], labels: [{source (FAULT_WINDOW|ALERT|MANUAL), reference, from, to, detected}], truePositives, falsePositives, falseNegatives, precision, recall, f1, params, evaluatedAt}`. Labels: simulated fault windows (by metric name or the series' service), past alerts on the series' services not raised by this detector, explicit labels. precision = detections matching a label / detections; recall = labels matched / labels; `null` when undefined (no labels → INSUFFICIENT_DATA)
- `AnomalyDto {id, detectorId, detectorName, seriesId, metricName, seriesAttributes, serviceId, serviceName, ciId, ciName, status, direction (UP|DOWN), startedAt, endedAt, lastPointAt, pointCount, peakScore, observedValue, expectedValue, expectedLower, expectedUpper, modelParams, alertId, alertNumber, correlationGroupId, simulated, createdAt}`

### Correlation groups
Evaluated after every new or reopened unsuppressed alert (`AlertEvent RECEIVED`, after commit) and for anomalies that raised no alert.
Candidates: alerts and alert-less anomalies within `observability.correlation.window_minutes` (30), with the same `simulated` flag, not in a
CLOSED or DISMISSED group. Score = time 0.25·(1 − Δt/window) + topology (same CI 0.35; CMDB distance d ≤ `observability.correlation.max_distance`
(3): 0.35·(1 − (d−1)/max); otherwise same service 0.30) + labels (same fingerprint 0.20, else 0.20 × Jaccard of shared key=value labels);
an item joins the best match's group (or opens one with it) when the score ≥ `observability.correlation.min_score` (0.5). Group reasons also
list deployments (`DEPLOYMENT`) and changes (`CHANGE`) on the members' services within the window and an incident a member raised
(`INCIDENT`, which links the group). Root suggestion: the member CI that most other members depend on, else the earliest member's CI.

| Method | Path | Roles | Body → response |
|---|---|---|---|
| GET | `/observability/correlation-groups?status (OPEN\|CLOSED\|DISMISSED)&includeSimulated&page&size` | readers | `PageResponse<GroupDto>` (`members` null) |
| GET | `/observability/correlation-groups/{id}` | readers | `GroupDto` with `members` |
| POST | `/observability/correlation-groups/{id}/link` | ALERT_RESPONDERS (staff) | `{incidentId}` → member alerts without incident are linked [`CORRELATION_GROUP_LINKED`] |
| POST | `/observability/correlation-groups/{id}/convert` | staff | `{title?, impact?, urgency?}` → incident from the root alert (intake pipeline), the other members linked; `409` if linked already or anomalies only [`CORRELATION_GROUP_CONVERTED`] |
| POST | `/observability/correlation-groups/{id}/split` | staff | `{memberIds: [], title?}` → the new group [`CORRELATION_GROUP_SPLIT`] |
| POST | `/observability/correlation-groups/{id}/merge` | staff | `{groupIds: []}` → this group; the merged groups become CLOSED [`CORRELATION_GROUP_MERGED`] |
| POST | `/observability/correlation-groups/{id}/dismiss` | staff | `{reason}` (3-1000) → DISMISSED [`CORRELATION_GROUP_DISMISSED`] |

- `GroupDto {id, title, status, score, reasons: Reason[], memberCount, rootCiId, rootCiName, rootServiceId, rootServiceName, rootReason, incidentId, incidentNumber, firstSeenAt, lastActivityAt, highestSeverity, simulated, dismissedReason, dismissedByName, dismissedAt, members: MemberDto[]}`
- `Reason {code (TIME_WINDOW|SAME_CI|SAME_SERVICE|TOPOLOGY|SHARED_LABELS|SAME_FINGERPRINT|DEPLOYMENT|CHANGE|INCIDENT|MANUAL|SEED), text, weight}`
- `MemberDto {id, memberType (ALERT|ANOMALY), alertId, alertNumber, anomalyId, title, severity, status, serviceId, serviceName, ciId, ciName, firstSeenAt, score, reasons, addedBy, joinedAt, simulated}`
- Actions on a group that is not OPEN → `409`.

### Digital twin
Built on CMDB CIs, relationships and business services (no second inventory). Layers: `BUSINESS` (BUSINESS_SERVICE CIs), `APPLICATION`
(APPLICATION, SOFTWARE), `INFRASTRUCTURE` (all other CI types). Relationships gained `attributes` (JSONB; the twin reads `{"redundant": true}`);
the CI attribute `cluster` groups interchangeable CIs.

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/twin/meta` | readers | `{layers: {BUSINESS: [ciTypes], ...}, simulationTypes, modes, impactLevels}` |
| GET | `/twin/graph?root&depth (1-10, 2)&layer (BUSINESS\|APPLICATION\|INFRASTRUCTURE\|ALL)&includeState (true)&includeSimulated` | readers | `TwinGraph {rootId, depth, layer, includeState, includeSimulated, nodes: TwinNode[], edges: TwinEdge[], layerCounts, truncated}` (≤ 300 nodes) |
| GET | `/twin/business-services/{serviceId}?depth (6)&includeState&includeSimulated` | readers | `BusinessServiceTree {service {id, code, name, tier, status}, ciId, depth, root: TreeNode, layerCounts, truncated}` |
| POST | `/twin/simulations` | readers | `SimulationRequest` → `201 SimulationResult` (persisted with who/when, never changes real data) [`TWIN_SIMULATION_RUN`] |
| GET | `/twin/simulations?limit` · `/twin/simulations/{id}` | readers | `SimulationSummary[] {id, label, type, mode, name, impactedCiCount, impactedServiceCount, createdByName, createdAt}` · `SimulationResult` |
| GET | `/twin/changes?ciId&serviceId&from&to (default 30 days)&includeSimulated&limit (≤ 500, 200)` | readers | `ChangeTimeline {ciId, serviceId, from, to, includeSimulated, entries: TimelineEntry[], truncated}` |
| PUT | `/twin/relationships/{id}` | TWIN_EDITORS | `{redundant, note?}` → `TwinEdge`; CI history `RELATIONSHIP_UPDATED` [`TWIN_RELATIONSHIP_REDUNDANCY_CHANGED`] |

- `TwinNode {id, name, ciType, layer, status, environment, criticality, serviceId, serviceCode, depth, cluster, state: NodeState}`
- `NodeState {health (OPERATIONAL|DEGRADED|OUTAGE|MAINTENANCE), healthReason, openIncidents, firingAlerts, openAnomalies, lastChangeAt, lastChange}` — business services use `ServiceHealthService`; other CIs: CI status, firing alerts (CRITICAL → OUTAGE), open anomalies
- `TwinEdge {id, source, target, type, dependentId, dependencyId, redundant, attributes}`
- `TreeNode {ciId, name, ciType, layer, via, redundant, cluster, state, repeated, children[]}` (cycle-safe; `repeated` = already on the path)
- `SimulationRequest {type (FAILURE|CHANGE), targets?: [ciId] (≤ 50; CHANGE defaults to the change's CIs and service CIs), mode? (HARD, default for FAILURE | DEGRADED, default for CHANGE), changeId? (required for CHANGE only), name?}`
- `SimulationResult {id, label ("Simulation"), type, mode, name, targets: [{id, name, ciType, layer}], changeId, changeNumber, impactedCis: ImpactedCi[], impactedServices: ImpactedService[], impactByLayer, estimatedAffectedUsers, affectedUsersBasis, slasAtRisk: [{incidentId, number, title, priority, serviceName, health, resolutionDueAt}], scheduledChanges: [{changeId, number, title, status, plannedStart, plannedEnd, touches (CI|SERVICE)}], notes[], createdByName, createdAt}`
- `ImpactedCi {ciId, name, ciType, layer, impact (DOWN|DEGRADED), depth, path[], pathIds[], redundancyApplied, reason}`; `ImpactedService {serviceId, code, name, tier, impact, ciId, path, estimatedAffectedUsers, affectedUsersBasis (CI_ATTRIBUTE|MAX_RECORDED_INCIDENT_IMPACT_180D|UNKNOWN)}`; the overall `affectedUsersBasis` is NO_SERVICES_IMPACTED, UNKNOWN, MAX_OVER_SERVICES or PARTIAL_MAX_OVER_SERVICES
- Propagation: a dependent takes the highest level of its impacted dependencies, one level lower when that dependency is redundant (a healthy CI with the same `cluster`, or a `redundant: true` relationship whose redundant peer of the same type is healthy or that has no modelled peer). HARD targets start at DOWN, DEGRADED at DEGRADED. SLAs at risk = active non-simulated incidents with an open SLA on impacted services; scheduled changes = AWAITING_APPROVAL / APPROVED / SCHEDULED / IMPLEMENTING changes touching impacted CIs or services
- `TimelineEntry {at, kind (CMDB|CHANGE|DEPLOYMENT|TELEMETRY), title, actorName, ciId, ciName, serviceId, reference {type, id, number?}, diff: [{field, from, to}], simulated}` — CMDB history of the CI(s), transitions of changes linked by CI or service, deployments (diff = previous successful version → version), telemetry resource attribute changes; a CI's timeline includes its nearest business service; newest first

## Release 4 — predictive intelligence, agentic AI

### Predictive intelligence (`/api/predictions`)
Model registry (`prediction_models`), training/evaluation job history (`prediction_evaluation_jobs`) and served predictions (`predictions`).
Model types (`ModelType`): `SLA_BREACH`, `INCIDENT_RECURRENCE` (L2-regularised logistic regression with standardised features, fitted by Newton–Raphson on the training window; evaluated on a chronological hold-out split with ROC AUC, Brier score, precision/recall/F1 at threshold, and 10-bin calibration), `CAPACITY_RISK` (Holt linear trend with grid-searched $\alpha,\beta$ and 95 % normal intervals), `SERVICE_HEALTH` (additive Holt–Winters with 7-day seasonality and seasonal-naive fallback below two seasons; evaluated with MAE, MAPE and 95 % interval coverage), and `ANOMALY` (registration/evaluation of an observability anomaly detector).
Minimum sample gates (`predictions.min_samples` = 200, `predictions.min_positives` = 30, `predictions.min_forecast_points` = 100): when the window has fewer samples, training records a model version in status `INSUFFICIENT_DATA` (`statusMessage: "Insufficient data — ..."`), which cannot be activated (`409`). At most one model version is `ACTIVE` per `(model_type, scope_key)`; activating a new version automatically retires the previous `ACTIVE` version. Without an `ACTIVE` `SLA_BREACH` model, incident SLA breach queries return the labelled Release 3 statistical fallback (`status: "NO_ACTIVE_MODEL"`, `basis: "STATISTICAL_FALLBACK"`, `fallback`). Simulated rows are excluded from training unless `includeSimulated: true` is passed (`model.simulated = true`).

| Method | Path | Roles | Body / response |
|---|---|---|---|
| GET | `/predictions/meta` | `OBSERVABILITY_READERS` | `{types: {SLA_BREACH: method, ...}, statuses[], minSamples, minPositives, minForecastPoints, slaFeatures[], recurrenceFeatures[], recurrenceDays, capacityMetricPattern}` |
| GET | `/predictions/models?type&status` · `/predictions/models/{id}` | readers | `ModelDto[]` · `ModelDto` |
| POST | `/predictions/models/train` | `PREDICTION_ADMINS` | `TrainRequest` → `201 ModelDto` (`TRAINED` or `INSUFFICIENT_DATA`; never auto-activated) [`PREDICTION_MODEL_TRAINED`] |
| POST | `/predictions/models/{id}/evaluate` | `PREDICTION_ADMINS` | `EvaluateRequest?` → `JobDto` (out-of-time evaluation of stored parameters, no refit) [`PREDICTION_MODEL_EVALUATED`] |
| POST | `/predictions/models/{id}/activate` · `/{id}/retire` | `PREDICTION_ADMINS` | → `ModelDto` (`409` if `INSUFFICIENT_DATA`) [`PREDICTION_MODEL_ACTIVATED` / `_RETIRED`] |
| GET | `/predictions/models/{id}/performance?days` | readers | `Performance {modelId, modelType, version, status, windowDays, outcomes, minOutcomesForDrift, offlineMetrics, liveMetrics, driftStatus (OK\|INSUFFICIENT_OUTCOMES\|DRIFT), driftReasons[], evaluatedAt}` |
| GET | `/predictions/jobs?modelId&limit` | readers | `JobDto[] {id, modelId, modelType, version, jobType (TRAIN\|EVALUATE), trigger, status, windowFrom, windowTo, splitCutoff, sampleCount, positiveCount, trainCount, testCount, includeSimulated, metrics, error, startedByName, startedAt, finishedAt}` |
| GET | `/predictions/incidents/{id}` | readers (+ incident access) | `IncidentPredictions {incidentId, number, slaBreach: ClassificationPrediction, recurrence: ClassificationPrediction}` |
| GET | `/predictions/incidents/{id}/sla-breach` · `/recurrence` | readers (+ incident access) | `ClassificationPrediction` |
| GET | `/predictions/services/{id}/health?horizonDays (1-30, 7)` | readers | `ServiceHealthForecast` |
| GET | `/predictions/capacity?seriesId&ciId&serviceId&metric&threshold&horizonHours (1-720, 72)` | readers | `CapacityForecast` |
| GET | `/predictions/records?modelId&subjectType&subjectId&outcome&page&size` | readers | `PageResponse<PredictionRecord>` |
| POST | `/predictions/outcomes/reconcile` | `PREDICTION_ADMINS` | → `ReconcileResult {checkedAt, slaOutcomes, recurrenceOutcomes, healthOutcomes, capacityOutcomes, expired, known, driftWarnings}` [`PREDICTION_OUTCOMES_RECONCILED`] |

- `TrainRequest {type, detectorId? (required for ANOMALY), from?, to?, testFraction? (0.1–0.5, 0.25), threshold? (0.05–0.95, 0.5), includeSimulated? (false), labels?: [{from, to, label?}], useAlerts?, toleranceMinutes?}`
- `EvaluateRequest {from?, to?, includeSimulated?, labels?, useAlerts?, toleranceMinutes?}`
- `ModelDto {id, type, scopeKey, version, name, status (TRAINED|ACTIVE|RETIRED|INSUFFICIENT_DATA), statusMessage, algorithm, featureNames[], params, metrics, trainWindowFrom, trainWindowTo, splitCutoff, sampleCount, positiveCount, trainCount, testCount, minRequiredSamples, detectorId, includeSimulated, simulated, trainedByName, trainedAt, activatedByName, activatedAt, retiredAt}`
- `ClassificationPrediction {incidentId, incidentNumber, type, status (OK|NO_ACTIVE_MODEL|NOT_APPLICABLE), basis (MODEL|STATISTICAL_FALLBACK|NONE), modelId, modelVersion, algorithm, predictionId, probability, threshold, riskLevel (HIGH|MEDIUM|LOW), target, targetAt, features, contributions: [{feature, value, standardized, weight, contribution}], modelMetrics, method, howComputed, message, fallback, simulated, computedAt}`
- `ServiceHealthForecast {serviceId, serviceCode, serviceName, status (OK|NO_ACTIVE_MODEL|INSUFFICIENT_DATA), basis (MODEL|NONE), modelId, modelVersion, algorithm, horizonDays, metrics: [{metric, status, method, predictionId, historyDays, history: ForecastPoint[], forecast: ForecastPoint[], sigma, message}], modelMetrics, howComputed, message, computedAt}` where `ForecastPoint {at, predicted, lower, upper}`
- `CapacityForecast {status (OK|NO_ACTIVE_MODEL|NO_DATA|INSUFFICIENT_DATA), basis (MODEL|NONE), modelId, modelVersion, algorithm, horizonHours, series: [{seriesId, metric, attributes, resourceId, resourceServiceName, serviceId, ciId, status, method, predictionId, threshold, thresholdBasis, currentValue, hoursToThreshold, earliestHoursToThreshold, latestHoursToThreshold, atRisk, riskLevel, historyPoints, forecast: ForecastPoint[], message, simulated}], modelMetrics, howComputed, message, computedAt}`
- `PredictionRecord {id, modelId, modelType, modelVersion, subjectType (INCIDENT|SERVICE|SERIES|DETECTOR), subjectId, subjectLabel, target, score, lowerBound, upperBound, threshold, riskLevel, targetAt, features, explanation, outcomeStatus (PENDING|KNOWN|EXPIRED), outcomeLabel, outcomeValue, outcomeAt, outcomeNote, simulated, createdAt}`

### Agentic AI (`/api/agents`)
Six domain agents (`AgentCode`: `INCIDENT`, `ROOT_CAUSE`, `CHANGE_RISK`, `KNOWLEDGE`, `INFRA_INVESTIGATION`, `RESOLUTION`), 13 read-only tools (`get_incident`, `similar_incidents`, `sla_prediction`, `alerts`, `correlation_groups`, `anomalies`, `recent_changes`, `recent_deployments`, `incidents_after_changes`, `cmdb_impact`, `service_red`, `telemetry_metrics`, `telemetry_logs`, `twin_changes`, `problems`, `kb_search`, `runbooks` — 13 registered in `AgentTools`), and 3 orchestrated run types (`RunType`):
- `INVESTIGATE_INCIDENT`: stage 0 `INCIDENT` → stage 1 `[ROOT_CAUSE, INFRA_INVESTIGATION, KNOWLEDGE]` in parallel → stage 2 `RESOLUTION`
- `ASSESS_CHANGE`: stage 0 `CHANGE_RISK`
- `ANSWER_QUESTION`: stage 0 `KNOWLEDGE`

Every tool runs on a thread bearing the requesting user's `JwtAuthenticationToken` so Spring Security `@PreAuthorize` and `IncidentQueryService` visibility rules apply identically (`ToolStatus.DENIED` when the user lacks permission for a tool). When an LLM is configured and enabled, each step runs a bounded tool-use loop (`AgentTurn {action: TOOL|FINAL, tool?, args?, outputJson?}`) through `GuardedLlm`, validated against the agent's JSON schema (`JsonSchemaLite`) and grounding checks; invalid output is retried once and then falls back to the deterministic engine (`source: "LLM"` vs `"FALLBACK"`). Runs execute on a bounded in-service `ThreadPoolExecutor` (`app.agents.max-concurrent-runs`, default 2; queue capacity 8; `429` when full). Agents never mutate state directly: proposed actions (`ActionKind`: `ASSIGN_TEAM`, `CHANGE_PRIORITY`, `LINK_PROBLEM`, `PUBLISH_KB_DRAFT`, `RUN_PLAYBOOK`, `DECLARE_MAJOR`) are stored in `agent_run_actions` (`PROPOSED`), submitted to the approval engine (`Approval.Type.AI_ACTION`, `PENDING_APPROVAL`) when requested by an operator, and applied as the approver only when approved (`APPLIED`).

| Method | Path | Roles | Body / response |
|---|---|---|---|
| GET | `/agents` | `AGENT_READERS` | `AgentDto[] {code, name, description, version, declaredTools[], runTypes[], outputSchema, policy: PolicyDto}` |
| GET | `/agents/tools` | `AGENT_READERS` | `ToolDto[] {name, description, args, roles[]}` |
| GET | `/agents/versions?agentCode` | `AGENT_READERS` | `VersionDto[] {id, agentCode, version, promptSha256, prompt, outputSchema, tools[], firstSeenAt}` |
| GET · PUT | `/agents/policies` · `/agents/policies/{code}` | `AGENT_READERS` · `AI_GOVERNANCE` | `PolicyDto[]` · `PolicyRequest` → `PolicyDto` [`AGENT_POLICY_UPDATED`] |
| GET | `/agents/runs?runType&status&subjectType&subjectId&requestedById&includeSimulated&page&size` | `AGENT_READERS` | `PageResponse<RunDto>` |
| POST | `/agents/runs?async` | `AGENT_USERS` | `StartRunRequest` → `201 RunDetail` (`409` if primary agent disabled, `429` if runner at capacity) [`AGENT_RUN_STARTED`] |
| GET | `/agents/runs/{id}` | `AGENT_READERS` | `RunDetail {run: RunDto, input, output, steps: StepDto[], actions: ActionDto[], feedback: FeedbackDto[]}` |
| POST | `/agents/runs/{id}/cancel` | `AGENT_USERS` | → `RunDetail` (`CANCELLED`; requester or AI governance) [`AGENT_RUN_CANCELLED`] |
| POST | `/agents/runs/{id}/feedback` | `AGENT_USERS` | `FeedbackRequest {helpful, comment?}` → `FeedbackDto` [`AGENT_FEEDBACK_RECORDED`] |
| POST | `/agents/runs/{runId}/actions/{actionId}/propose` | `AGENT_USERS` | → `ActionDto` (`PENDING_APPROVAL`, creates `AI_ACTION` approval) [`AGENT_ACTION_APPROVAL_REQUESTED`] |
| POST | `/agents/runs/{runId}/actions/{actionId}/dismiss` | `AGENT_USERS` | `DismissRequest {reason}` → `ActionDto` (`DISMISSED`) [`AGENT_ACTION_DISMISSED`] |
| GET | `/agents/evaluations/cases?agentCode` | `AGENT_READERS` | `EvalCaseDto[]` |
| POST · PUT · DELETE | `/agents/evaluations/cases` · `/{id}` · `/{id}` | `AI_GOVERNANCE` | `EvalCaseRequest` → `201 EvalCaseDto` / `200 EvalCaseDto` / `204` [`AGENT_EVAL_CASE_CREATED` / `_UPDATED` / `_DELETED`] |
| GET | `/agents/evaluations/runs?agentCode` · `/agents/evaluations/runs/{id}` | `AGENT_READERS` | `EvalRunDto[]` · `EvalRunDto` |
| POST | `/agents/evaluations/run` | `AI_GOVERNANCE` | `EvalRunRequest {agentCode?, caseIds?}` → `EvalRunDto` [`AGENT_EVAL_RUN_COMPLETED`] |
| GET | `/agents/governance?days (30)` | `AI_GOVERNANCE_READERS` | `{windowDays, from, to, totalRuns, succeededRuns, failedRuns, timedOutRuns, cancelledRuns, runningRuns, llmRuns, fallbackRuns, mixedRuns, failureRate, fallbackRate, avgDurationMs, totalTokensIn, totalTokensOut, latestEvalScore, byStatus, bySource, byRunType, actionCounts, feedback {helpful, notHelpful, helpfulRate}, agents[], versions[], recentEvalRuns[]}` |

- `StartRunRequest {runType (INVESTIGATE_INCIDENT|ASSESS_CHANGE|ANSWER_QUESTION), incidentId?, changeId?, question?, timeoutSeconds? (5–600)}`
- `PolicyRequest {enabled, allowedTools[], maxSteps (1–20), timeoutSeconds (1–300), allowedRoles[], approvalActionKinds[], dataScopes?: {telemetryMinutes (5–1440), historyDays (1–365)}}`
- `PolicyDto {agentCode, enabled, allowedTools[], maxSteps, timeoutSeconds, allowedRoles[], approvalActionKinds[], dataScopes, updatedByName, updatedAt}`
- `RunDto {id, runType, status (QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED|TIMED_OUT), subjectType, subjectId, subjectNumber, question, source (LLM|FALLBACK|MIXED), confidence, summary, agentVersions, timeoutSeconds, stepCount, toolCallCount, tokensIn, tokensOut, error, cancelRequested, cancelledByName, requestedById, requestedByName, evalRunId, simulated, demo, queuedAt, startedAt, finishedAt, durationMs, createdAt, feedback {helpful, notHelpful, mine}}`
- `StepDto {id, seq, stage, agentCode, agentVersion, status, input, output, source, fallbackReason, confidence, evidence: EvidenceRef[], llmTurns, tokensIn, tokensOut, error, startedAt, finishedAt, durationMs, toolCalls: ToolCallDto[]}`
- `ToolCallDto {id, seq, tool, args, status (OK|EMPTY|DENIED|NOT_FOUND|DISALLOWED|INVALID|ERROR|TIMEOUT), result, resultSize, truncated, message, requestedBy (PLAN|LLM), durationMs, createdAt}`
- `ActionDto {id, runId, stepId, agentCode, kind, targetType, targetId, params, title, rationale, requiresApproval, link, status (PROPOSED|PENDING_APPROVAL|APPLIED|REJECTED|FAILED|DISMISSED), approvalId, decidedByName, decidedAt, decisionComment, result, createdAt}`
- `EvalCaseRequest {name, agentCode, runType, incidentId?, changeId?, question?, expected {category?, priority?, rootCauseComponent?, kbArticleIds?, keywords?}, enabled?}`
- `EvalCaseDto {id, name, agentCode, runType, subjectType, subjectId, question, expected, enabled, demo, createdByName, createdAt, updatedAt}`
- `EvalRunDto {id, agentCode, status (QUEUED|RUNNING|COMPLETED|FAILED), agentVersions, caseCount, passed, failed, score, results, error, startedByName, startedAt, finishedAt, createdAt}`

### Schedulers and advisory locks (Release 4)

| Lock id | Job | Interval / switch |
|---|---|---|
| 360010 | simulation scenario ticks (`SimulationService.tickDue`) | `SIMULATION_TICK_INTERVAL_MS` (60 s); `SIMULATION_SCHEDULER_ENABLED` |
| 360011 | metric minute roll-ups (queued minutes) | `app.observability.rollup-interval-ms` (60 s) |
| 360012 | retention purge | `app.observability.retention-cron` (`0 50 3 * * *`) |
| 360013 | anomaly detection over all enabled detectors | `app.observability.anomaly-interval-ms` (60 s) |
| 360014 | due Prometheus pulls | `app.observability.pull-poll-interval-ms` (30 s) |
| 360015 | correlation decisions (transaction lock that serialises group assignment) | on each alert / anomaly |
| 360016 | prediction model activation serialisation (`PredictionModelService.activate`) | per model activation transaction |
| 360017 | prediction outcome reconciliation & live drift check (`PredictionJobs.reconcileOutcomes`) | `app.predictions.outcome-interval-ms` (900 s) |
| 360018 | scheduled weekly retraining of prediction models (`PredictionJobs.retrain`) | `app.predictions.retrain-cron` (`0 20 4 * * SUN`) |

### Autonomous Remediation (`/api/remediation` — `RemediationController`, migration V12, [RELEASE4_SPEC §4.5](docs/RELEASE4_SPEC.md#45-sa-autonomous-remediation-be-7-v12))
Execution systems (`SIMULATED`, `HTTP_WEBHOOK`, `ANSIBLE_AWX`, `KUBERNETES`, `AWS_SSM`) store `SecretCipher`-encrypted credentials (write-only) and allow-listed parameterised action templates (no shell, SQL or free-form commands). Playbook versions (`DRAFT → IN_REVIEW → APPROVED → RETIRED`) are approved via the approval engine (`PLAYBOOK`). Every execution evaluates and records pre-execution policy checks; on step failure, completed `ACTION` steps are rolled back in reverse order. All policy decisions, step calls, rollbacks and verifications are appended to the SHA-256 hash-chained `remediation_audit` table (`trg_remediation_audit_immutable`).

| Method | Path | Access | Request / Response |
|---|---|---|---|
| GET | `/remediation/meta` | `OBSERVABILITY_READERS` | `{connectorKinds[], stepKinds[], triggerKinds[], runStatuses[], versionStatuses[]}` |
| GET · POST · PUT | `/remediation/systems` · `/{id}` | `OBSERVABILITY_READERS` · `EXECUTION_SYSTEM_ADMINS` | `ExecutionSystemDto[]` · `ExecutionSystemRequest` → `ExecutionSystemDto {id, name, kind, endpointUrl, credentialScope, credentialsConfigured, actionTemplates[], status, lastTestedAt, lastError, enabled}` [`EXECUTION_SYSTEM_CREATED` / `_UPDATED`] |
| POST | `/remediation/systems/{id}/test` | `EXECUTION_SYSTEM_ADMINS` | → `ExecutionSystemDto` (`SIMULATED` → `SIMULATED`; external connectors stay `NOT_CONNECTED` unless reachable) [`EXECUTION_SYSTEM_TESTED`] |
| GET · POST | `/remediation/playbooks` | `OBSERVABILITY_READERS` · `PLAYBOOK_EDITORS` | `PlaybookDto[]` · `PlaybookRequest` → `201 PlaybookDto {id, code, name, description, targetEnvironment, preApprovedAlertTypes[], maxBlastRadius, rateLimitPerHour, requireLinkedRecord, activeVersion, enabled, versions: PlaybookVersionDto[]}` [`PLAYBOOK_CREATED`] |
| POST | `/remediation/playbooks/{id}/versions` | `PLAYBOOK_EDITORS` | `PlaybookVersionRequest` → `201 PlaybookVersionDto` (`DRAFT`) [`PLAYBOOK_VERSION_CREATED`] |
| POST | `/remediation/playbooks/{id}/versions/{version}/submit` | `PLAYBOOK_EDITORS` | → `PlaybookVersionDto` (`IN_REVIEW`, creates `PLAYBOOK` approval) [`PLAYBOOK_VERSION_SUBMITTED`] |
| POST | `/remediation/playbooks/{id}/versions/{version}/approve` | `PLAYBOOK_APPROVERS` | → `PlaybookVersionDto` (`APPROVED`, sets `activeVersion`, retires previous active version) [`PLAYBOOK_VERSION_APPROVED`] |
| GET · POST | `/remediation/runs?status&playbookId&incidentId&includeSimulated` | `OBSERVABILITY_READERS` · `REMEDIATION_OPERATORS` | `RemediationRunDto[]` · `StartRemediationRunRequest` → `201 RemediationRunDetailDto {run, steps[], auditEntries[]}` [`REMEDIATION_RUN_STARTED` / `_BLOCKED` / `_ROLLED_BACK`] |
| GET | `/remediation/runs/{id}` | `OBSERVABILITY_READERS` | `RemediationRunDetailDto {run, steps: RemediationRunStepDto[], auditEntries: RemediationAuditDto[]}` |
| POST | `/remediation/runs/{id}/override` | `PLAYBOOK_APPROVERS` | `{reason}` → `RemediationRunDetailDto` (overrides `BLOCKED`/`PENDING_APPROVAL` and executes) [`REMEDIATION_RUN_OVERRIDDEN`] |
| POST | `/remediation/runs/{id}/cancel` · `/confirm-step` | `REMEDIATION_OPERATORS` | → `RemediationRunDetailDto` (`CANCELLED` / confirms `MANUAL_CONFIRM` step) [`REMEDIATION_RUN_CANCELLED` / `_STEP_CONFIRMED`] |
| GET | `/remediation/audit?runId` | `OBSERVABILITY_READERS` | `RemediationAuditDto[] {id, eventType, runId, playbookId, actorName, payload, createdAt, prevHash, hash}` |
| GET | `/remediation/audit/verify` | `OBSERVABILITY_READERS` | `AuditVerifyDto {valid, totalRecords, firstBrokenId, message}` |

### Security Operations (`/api/security` — `SecOpsController`, migration V12, [RELEASE4_SPEC §4.6](docs/RELEASE4_SPEC.md#46-sa-security-operations-be-7-v12))
Security incidents (`NEW → TRIAGE → CONTAINMENT → ERADICATION → RECOVERY → CLOSED`), vulnerability ingestion (`GENERIC_JSON`, `SARIF` 2.1.0, `TRIVY_JSON`, `CSV`) with deduplication and full-scan auto-close, documented 0–100 risk score (`CVSS × criticality × exposure × exploitability × age`), security alert correlation, response templates, and simulation scenario `VULN_WAVE`.

| Method | Path | Access | Request / Response |
|---|---|---|---|
| GET | `/security/overview?includeSimulated` | `SECOPS_READERS` | `{openIncidents, criticalFindings, highFindings, openFindings, openCorrelationGroups, slaBreachedFindings, riskFormula, simulated}` |
| GET · POST | `/security/incidents?status&severity&includeSimulated` | `SECOPS_READERS` · `SECOPS` | `SecurityIncidentDto[]` · `CreateSecurityIncidentRequest` → `201 SecurityIncidentDetailDto {incident, tasks[]}` [`SECURITY_INCIDENT_CREATED`] |
| GET | `/security/incidents/{id}` | `SECOPS_READERS` | `SecurityIncidentDetailDto {incident: SecurityIncidentDto, tasks: SecurityIncidentTaskDto[]}` |
| POST | `/security/incidents/{id}/transition` · `/notes` · `/tasks/{taskId}/complete` | `SECOPS` | → `SecurityIncidentDetailDto` (`CLOSED` requires `lessonsLearned`) [`SECURITY_INCIDENT_TRANSITIONED` / `_NOTE_ADDED` / `_TASK_COMPLETED`] |
| GET · POST | `/security/vulnerabilities/sources` | `SECOPS_READERS` · `SECOPS` | `VulnerabilitySourceDto[]` · `CreateVulnerabilitySourceRequest` → `201 {source: VulnerabilitySourceDto, rawToken}` (token shown once) [`VULNERABILITY_SOURCE_CREATED`] |
| POST | `/security/vulnerabilities/ingest` | `SECOPS` or `X-SA-Source-Token` | `VulnerabilityIngestRequest {sourceId, format, fullScan, payload}` → `VulnerabilityIngestResultDto {ingested, updated, autoClosed, findings[]}` [`VULNERABILITIES_INGESTED`] |
| GET | `/security/vulnerabilities/findings?status&riskBand&includeSimulated` | `SECOPS_READERS` | `VulnerabilityFindingDto[]` (ordered by `riskScore DESC`) |
| POST | `/security/vulnerabilities/findings/{id}/accept-risk` | `SECOPS` | `{reason, days}` → `VulnerabilityFindingDto` (`ACCEPTED`, creates `VULNERABILITY_ACCEPTANCE` approval) [`VULNERABILITY_RISK_ACCEPTED`] |
| GET · POST | `/security/correlation-groups` · `/correlate` · `/{id}/raise-incident` | `SECOPS_READERS` · `SECOPS` | `SecurityCorrelationGroupDto[]` · → `SecurityIncidentDetailDto` [`SECURITY_CORRELATION_RUN` / `_INCIDENT_RAISED`] |
| GET | `/security/templates` | `SECOPS_READERS` | `SecurityResponseTemplateDto[]` |

### FinOps (`/api/finops` — `FinOpsController`, migration V13, [RELEASE4_SPEC §4.7](docs/RELEASE4_SPEC.md#47-sa-finops-be-8-v13))
FOCUS 1.x CSV and mapped CSV cost ingestion (dry-run validation then idempotent apply), allocation rules, utilisation from observability metrics, idle detection, EWMA/MAD daily cost anomalies, budgets with threshold notifications (`FINOPS_BUDGET_THRESHOLD`) and linear month-end forecast, savings recommendations (`ACCEPT` → draft `ChangeRequest`), and simulation scenario `COST_SPIKE`.

| Method | Path | Access | Request / Response |
|---|---|---|---|
| GET | `/finops/overview?includeSimulated` | `FINOPS_READERS` | `{status OK\|INSUFFICIENT_DATA, totalBilledCost, totalEffectiveCost, unallocatedCost, potentialMonthlySavings, openAnomalies, idleResourcesCount, byService[], byCostCenter[], dailyTrend[], simulated}` |
| GET · POST | `/finops/sources` | `FINOPS_READERS` · `FINOPS` | `CostSourceDto[]` · `CreateCostSourceRequest` → `201 CostSourceDto` [`FINOPS_SOURCE_CREATED`] |
| POST | `/finops/ingest` | `FINOPS` | `{sourceId, format FOCUS_CSV\|MAPPED_CSV, dryRun, csvContent, columnMapping?}` → `CostIngestResultDto {dryRun, validRows, errorRows, totalBilledCost, totalEffectiveCost, errors[], appliedCount}` [`FINOPS_COSTS_INGESTED`] |
| GET · POST | `/finops/allocation-rules` | `FINOPS_READERS` · `FINOPS` | `AllocationRuleDto[]` · `CreateAllocationRuleRequest` → `201 AllocationRuleDto` [`FINOPS_ALLOCATION_RULE_CREATED`] |
| GET · POST | `/finops/budgets?includeSimulated` | `FINOPS_READERS` · `FINOPS` | `BudgetStatusDto[]` · `CreateBudgetRequest` → `201 BudgetStatusDto` [`FINOPS_BUDGET_CREATED`] |
| GET | `/finops/anomalies?includeSimulated` · `/recommendations?includeSimulated` | `FINOPS_READERS` | `CostAnomalyDto[]` · `CostRecommendationDto[]` |
| POST | `/finops/recommendations/{id}/accept` · `/dismiss` | `FINOPS` | → `CostRecommendationDto` (`ACCEPTED` with `changeRequestId` / `DISMISSED` with `dismissReason`) [`FINOPS_RECOMMENDATION_ACCEPTED` / `_DISMISSED`] |

### Digital Employee Experience (`/api/dex` — `DexController`, migration V13, [RELEASE4_SPEC §4.8](docs/RELEASE4_SPEC.md#48-sa-digital-employee-experience-be-8-v13))
Endpoint health ingestion, application RUM events, login/SSO events + SA auth audit, documented 0–100 experience score (`0.35 × DeviceHealth + 0.25 × AppPerformance + 0.20 × LoginSuccess + 0.20 × TicketSignals`), proactive department incident rules (`DEX_PROACTIVE_INCIDENT`), service experience analytics, employee portal device summary (`/api/dex/me`), and simulation scenario `ENDPOINT_DEGRADATION`.

| Method | Path | Access | Request / Response |
|---|---|---|---|
| GET | `/dex/overview?includeSimulated` | `DEX_READERS` | `{status OK\|INSUFFICIENT_DATA, overallExperienceScore, deviceHealthAvg, appPerformanceAvg, loginSuccessRatePct, endpointCount, formula, byDepartment[], byDeviceModel[], byApp[], serviceExperience[], endpoints[], proactiveRules[], simulated}` |
| GET | `/dex/me?includeSimulated` | authenticated (incl. `EMPLOYEE`) | `{status OK\|NO_DEVICE, device: DexEndpointDto \| null, message}` |
| POST | `/dex/ingest/endpoints` · `/app-events` · `/login-events` | `ADMINS` | ingests endpoint health / app performance / login telemetry and evaluates proactive rules [`DEX_ENDPOINTS_INGESTED` / `_APP_EVENTS_INGESTED` / `_LOGIN_EVENTS_INGESTED`] |
| POST · PUT | `/dex/proactive-rules` · `/{id}` | `ADMINS` | `DexProactiveRuleDto` [`DEX_PROACTIVE_RULE_CREATED` / `_UPDATED`] |

`OBSERVABILITY_JOBS_ENABLED=false` switches off 360011–360014; `PREDICTIONS_JOBS_ENABLED=false` (`app.predictions.jobs-enabled=false`) switches off 360017–360018. Settings added by V10–V11: `simulation.enabled`,
`observability.retention.raw_days|rollup_days|logs_days|traces_days`, `observability.correlation.window_minutes|max_distance|min_score`,
`predictions.min_samples|min_positives|min_forecast_points`, `predictions.drift.auc_tolerance|brier_tolerance|mae_ratio|coverage_tolerance|f1_tolerance|min_outcomes`.

### Frontend navigation contract
New group **AUTONOMOUS OPERATIONS** (after OPERATIONS):
- `/observability` — Observability (readers). Data: `/observability/overview`, `/metrics`, `/metrics/series`, `/logs`, `/traces`,
  `/traces/{traceId}`, `/services/red`, `/anomalies`, `/correlation-groups` (+ actions for staff); configuration tabs for TELEMETRY_CONFIG:
  `/sources` (+ `/kinds`, test, pull, rotate-token), `/mappings`, `/resources`, `/retention`, `/anomaly-detectors` (+ run, evaluate).
- `/digital-twin` — Digital Twin (readers). Data: `/twin/meta`, `/twin/graph`, `/twin/business-services/{id}`, `/twin/simulations`,
  `/twin/changes`; redundancy editing for TWIN_EDITORS (`PUT /twin/relationships/{id}`).
- `/predictions` — Predictive Intelligence (readers; training/activation/retirement/reconciliation for `PREDICTION_ADMINS`). Data:
  `/predictions/meta`, `/predictions/models`, `/predictions/models/{id}/performance`, `/predictions/jobs`, `/predictions/incidents/{id}`,
  `/predictions/services/{id}/health`, `/predictions/capacity`, `/predictions/records`.
- `/agents` — SA Agents (`AGENT_READERS`; run/cancel/feedback/propose/dismiss for `AGENT_USERS`; policies/evaluations for `AI_GOVERNANCE`).
  Data: `/agents`, `/agents/tools`, `/agents/versions`, `/agents/policies`, `/agents/runs`, `/agents/runs/{id}`, `/agents/evaluations/cases`,
  `/agents/evaluations/runs`, `/agents/governance`.
- `/remediation` — Autonomous Remediation (`OBSERVABILITY_READERS`; playbooks for `PLAYBOOK_EDITORS` / `PLAYBOOK_APPROVERS`; execution for `REMEDIATION_OPERATORS`; connectors for `EXECUTION_SYSTEM_ADMINS`). Data: `/remediation/meta`, `/remediation/systems`, `/remediation/playbooks`, `/remediation/runs`, `/remediation/audit`, `/remediation/audit/verify`.

New group **ENTERPRISE** (after AUTONOMOUS OPERATIONS):
- `/security` — Security Operations (`SECOPS_READERS`; writes for `SECOPS`). Data: `/security/overview`, `/security/incidents`, `/security/vulnerabilities/sources`, `/security/vulnerabilities/findings`, `/security/vulnerabilities/ingest`, `/security/correlation-groups`, `/security/templates`.
- `/finops` — FinOps (`FINOPS_READERS`; writes for `FINOPS`). Data: `/finops/overview`, `/finops/sources`, `/finops/ingest`, `/finops/allocation-rules`, `/finops/budgets`, `/finops/anomalies`, `/finops/recommendations`.
- `/dex` — Employee Experience (`DEX_READERS`; writes for `ADMINS`; `/dex/me` for all authenticated users on `/portal`). Data: `/dex/overview`, `/dex/me`, `/dex/ingest/endpoints`, `/dex/proactive-rules`.

Settings gains "Simulation mode" (SYSTEM_ADMIN): `/simulation/status`, `PUT /simulation/mode`, `/simulation/scenarios/{code}/start|stop`,
`DELETE /simulation/data`, `/simulation/runs`. The global SIMULATION banner reads `GET /simulation/status` (`enabled`) for every signed-in
user; records with `simulated: true` get a SIMULATION badge.

### Demo data (all `demo: true`, seeded once per part by `DemoRelease4Seeder`, also on upgraded databases)
Mapping rules on `service.name`: `payroll-api` → Payroll + "Payroll Application", `payroll-db` → CI `payroll-db-primary`, `hr-portal` → HR
Portal + "HR Portal Application", `erp-finance` → ERP Finance + "ERP Finance Application". One OTLP source "Demo OTLP collector" (random
token, never shown: rotate it to obtain one). Detectors on the simulated series: "Payroll DB connection pool (simulation)" (CRITICAL,
creates incidents), "Payroll API latency (simulation)", "HR portal latency (simulation)". `redundant: true` on payroll-alb and Payroll
Application → payroll-app-01/02, Identity Provider (ADFS) → adfs-01/02, VPN & Remote Access → vpn-gw-01/02. No telemetry is seeded:
telemetry comes only from real ingestion or simulation scenarios.
