# SA — Database schema

**SA — Enterprise Operations Platform** · PostgreSQL schema reference for Releases 1–4 (migrations V1–V13).

This document is derived from the Flyway migrations in `backend/src/main/resources/db/migration`
(`V1__core_schema.sql` … `V13__release4_finops_dex.sql`). Those files are the source of truth; if this document and a
migration ever disagree, the migration wins. Related: [ARCHITECTURE.md](ARCHITECTURE.md) ·
[API_DOCUMENTATION.md](API_DOCUMENTATION.md) · [SECURITY.md](SECURITY.md).

## 1. Ground rules

- **Flyway owns the schema.** Migrations run automatically when the API starts; Hibernate runs with `ddl-auto: validate`
  and only checks that entities match. Nothing is created by hand.
- **Forward-only.** Every change is a new `V<n>__description.sql`; an applied migration is never edited (Flyway validates
  checksums and the API refuses to start on a mismatch). There are no down-migrations; recovery is by restore
  (see [SECURITY.md §5](SECURITY.md#5-backup-and-recovery)). The next migration number after Release 4 is **V14**.
- **Data-preserving upgrades.** Releases migrate existing rows in place (roles and statuses in V3, single-step approvals and
  catalogue forms in V7, services → CIs in V8, existing major incidents → command records in V9).
- **Conventions.** Surrogate keys are `BIGSERIAL id` (except natural keys noted below). Timestamps are `TIMESTAMPTZ`
  (stored in UTC), `created_at`/`updated_at` default to `now()`. Mutable aggregates carry `version BIGINT` for optimistic
  locking. Every enumerated value is also constrained by a named `CHECK` (`ck_*`); unique constraints/indexes are `ux_*`,
  other indexes `ix_*`, foreign keys default to `NO ACTION` unless `ON DELETE CASCADE` / `SET NULL` is stated.
  User references on history and audit rows use `ON DELETE SET NULL` plus a copied name (`actor_name`) so history
  survives user deletion.
- **Demo data.** Tables that can hold seeded demonstration data have `is_demo BOOLEAN NOT NULL DEFAULT FALSE`. Demo rows are
  created by the application's seeders only when `APP_DEMO_SEED=true`. Migrations V6–V9 create no demo records (their
  headers say so); data-migration steps copy the flag of the source rows (e.g. V8 CIs from services, V9 command records from
  incidents). V3 is the exception: when a Release 1 demo organisation exists it adjusts it (demo user roles, an added demo
  IT administrator, demo catalogue categories and demo service dependencies), always guarded by `is_demo`. The UI badges
  demo rows **DEMO**; demo-only tools refuse non-demo records.
- **Extensions.** `pg_trgm` (V1) for trigram similarity on titles. Full-text search uses a generated `tsvector` column.

Abbreviations below: **PK** primary key · **FK** foreign key (→ target, delete rule) · **UQ** unique · **NN** not null ·
`[demo]` = has `is_demo` · `[v]` = has `version`.

## 2. Human-readable number sequences

| Sequence | Starts at | Used for (prefix added by the application) |
|---|---|---|
| `incident_number_seq` | 7001 | `incidents.number` (`INC-7001`) |
| `service_request_number_seq` | 3001 | `service_requests.number` (`REQ-…`) |
| `knowledge_number_seq` | 1001 | `knowledge_articles.number` (`KB-…`) |
| `problem_number_seq` | 1001 | `problems.number` (`PRB-…`) |
| `change_number_seq` | 5001 | `changes.number` (`CHG-…`) |
| `release_number_seq` | 101 | `releases.number` (`REL-…`) |
| `deployment_number_seq` | 9001 | `deployments.number` (`DEP-…`) |
| `asset_tag_seq` | 10001 | `assets.asset_tag` (`AST-…`) |
| `alert_number_seq` | 20001 | `alerts.number` (`ALR-…`) |
| `major_incident_number_seq` | 101 | `major_incidents.number` (`MI-…`) |
| `pir_number_seq` | 301 | `post_incident_reviews.number` (`PIR-…`) |

## 3. Tables by module

### 3.1 Organisation, identity and sessions

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `departments` [demo] | `code`, `name`, `description`, `manager_id`, `email` | UQ `code`, UQ `name`; FK `manager_id` → users (SET NULL, added after `users`); `ck_department_code` `^[A-Z0-9_-]{2,40}$` | — |
| `users` [demo][v] | `email`, `full_name`, `password_hash` (BCrypt), `role`, `department_id`, `job_title`, `phone`, `active`, `must_change_password`, `failed_login_attempts`, `locked_until`, `last_login_at` | FK `department_id` → departments (SET NULL); `ck_user_role` (V6): `SYSTEM_ADMIN, IT_ADMIN, IT_MANAGER, INCIDENT_MANAGER, CHANGE_MANAGER, ASSET_MANAGER, SUPPORT_AGENT, EMPLOYEE, AUDITOR`; `ck_user_failed_logins` ≥ 0 | `ux_users_email` on `lower(email)`; `ix_users_department`; `ix_users_role` partial `WHERE active` |
| `refresh_tokens` | `user_id`, `token_hash` (SHA-256 hex), `family_id` (UUID), `expires_at`, `revoked_at`, `replaced_by_hash`, `user_agent`, `ip_address` | FK `user_id` → users (CASCADE); UQ `token_hash` | `ix_refresh_tokens_user`, `ix_refresh_tokens_family` |
| `auth_sessions` (V6) | `user_id`, `family_id`, `remember_me`, `user_agent`, `ip_address`, `last_ip_address`, `last_used_at`, `expires_at`, `revoked_at`, `revoked_reason` | FK `user_id` → users (CASCADE); UQ `family_id` (one session per refresh-token family). V6 back-filled one session per existing token family (families without an open token are stored revoked, reason `LEGACY`) | `ix_auth_sessions_user (user_id, last_used_at DESC)`; `ix_auth_sessions_open` partial `WHERE revoked_at IS NULL` |
| `account_requests` (V4) | `type`, `email`, `full_name`, `department_text`, `job_title`, `reason`, `status`, `user_id`, `decided_by_id`, `decided_at`, `decision_note`, `request_ip` | FKs → users (SET NULL); `type` `ACCESS, PASSWORD_RESET`; `status` `PENDING, APPROVED, REJECTED, COMPLETED`; ACCESS needs `full_name` and `reason`; decided rows need `decided_at`; REJECTED needs `decision_note` | `ux_account_requests_pending` on `(lower(email), type) WHERE status = 'PENDING'` (one pending request per address and type); `(type, status, created_at DESC)`; `created_at DESC` |
| `account_tokens` (V4) | `purpose`, `token_hash`, `email`, `full_name`, `role`, `department_id`, `job_title`, `user_id`, `account_request_id`, `expires_at`, `used_at`, `revoked_at`, `created_by_id` | UQ `token_hash`, `ck_account_token_hash` `^[0-9a-f]{64}$` (only SHA-256 hashes are stored); `purpose` `INVITE, PASSWORD_RESET`; INVITE needs `role` (same role list as users, V6), PASSWORD_RESET needs `user_id`; FK `user_id` → users (CASCADE), others SET NULL | `ix_account_tokens_open` on `(lower(email), purpose) WHERE used_at IS NULL AND revoked_at IS NULL`; `ix_account_tokens_request` partial |
| `teams` (V6) [demo][v] | `code`, `name`, `department_id`, `lead_id`, `email`, `description`, `active`, `department_default` | UQ `code`, UQ `name`; FK `department_id` → departments (NN), `lead_id` → users (SET NULL); `ck_team_code`; `ck_team_default_active` (a default team must be active) | `ux_team_department_default` on `department_id WHERE department_default` (at most one default team per department); `ix_teams_department` |
| `team_members` (V6) | `team_id`, `user_id`, `member_role` | PK `(team_id, user_id)`; FKs CASCADE; `member_role` `MEMBER, LEAD` | `ix_team_members_user` |

### 3.2 SLA configuration and clocks

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `business_hours_calendars` | `name`, `timezone`, `work_days`, `start_time`, `end_time`, `is_default` | UQ `name`; `end_time > start_time` | `ux_calendar_default` on `is_default WHERE is_default` (one default calendar) |
| `calendar_holidays` | `calendar_id`, `holiday_date`, `name` | FK → calendars (CASCADE); UQ `(calendar_id, holiday_date)` | — |
| `sla_policies` | `name`, `priority`, `response_minutes`, `resolution_minutes`, `business_hours_only`, `calendar_id`, `at_risk_percent`, `active`, `description` | UQ `name`; `priority` `CRITICAL, HIGH, MEDIUM, LOW`; `response_minutes > 0 AND resolution_minutes ≥ response_minutes`; `at_risk_percent` 10–95; business-hours policies need a calendar | `ux_sla_policy_active_priority` on `priority WHERE active` (one active policy per priority) |
| `incident_slas` [v] | `incident_id`, `policy_id`, snapshot of the policy (`policy_name`, `priority`, `business_hours_only`, `calendar_id`, targets, `at_risk_percent`), `started_at`, `response_due_at`, `responded_at`, `response_breached`, `consumed_seconds`, `running_since`, `resolution_due_at`, `paused_at`, `total_paused_seconds`, `clock_status`, `health`, `outcome`, `escalation_level`, `at_risk_at`, `breached_at`, `completed_at` | UQ `incident_id` (FK CASCADE); `clock_status` `RUNNING, PAUSED, STOPPED`; `health` `ON_TRACK, AT_RISK, BREACHED`; `outcome` `MET, MISSED, CANCELLED`; RUNNING needs `running_since`; STOPPED needs `outcome`; non-negative counters | `ix_isla_running (clock_status, resolution_due_at) WHERE clock_status = 'RUNNING'` (scheduler queue); `ix_isla_completed` partial |
| `sla_events` | `incident_sla_id`, `event_type`, `details` | FK CASCADE; `event_type` `STARTED, PAUSED, RESUMED, RESPONDED, AT_RISK, BREACHED, ESCALATED, MET, MISSED, RECALCULATED, CANCELLED, SIMULATED` (`SIMULATED` = demo time shift) | `(incident_sla_id, created_at)` |

Reference data (V2): the default calendar "Standard Business Hours (IST)" (Asia/Kolkata, Monday–Friday 09:00–18:00) and four
policies — P1 Critical 24×7 (15 min / 4 h), P2 High 24×7 (30 min / 8 h), P3 Medium business hours (2 h / 18 business
hours, at-risk 80 %), P4 Low business hours (8 h / 45 business hours, at-risk 85 %).

### 3.3 Business services and CMDB

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `business_services` [demo] | `code`, `name`, `description`, `department_id`, `owner_id`, `tier`, `keywords`, `default_category`, `maintenance`, `default_team_id` (V6) | UQ `code`, UQ `name`; FK `department_id` → departments (NN), `owner_id` → users (SET NULL), `default_team_id` → teams (SET NULL); `tier` 1–3; `default_category` in the incident category list | `ix_services_department` |
| `service_dependencies` (V3) | `service_id`, `depends_on_id` | PK `(service_id, depends_on_id)`; FKs CASCADE; no self-dependency (cycles are rejected by the application) | `ix_dependencies_depends_on` |
| `configuration_items` (V8) [demo][v] | `name`, `ci_type`, `status`, `environment`, `criticality`, `description`, `owner_id`, `team_id`, `service_id`, `attributes` (JSONB object), `created_by_id` | `ci_type` `APPLICATION, SERVER, DATABASE, NETWORK_DEVICE, CLOUD_RESOURCE, OPERATING_SYSTEM, SOFTWARE, BUSINESS_SERVICE, INFRASTRUCTURE`; `status` `PLANNED, ACTIVE, MAINTENANCE, RETIRED`; `environment` `PRODUCTION, STAGING, TEST, DEV, DR`; `criticality` `LOW, MEDIUM, HIGH, CRITICAL`; UQ `service_id` (FK SET NULL) and `ck_ci_service`: only `BUSINESS_SERVICE` CIs link a service | `ux_ci_name` on `lower(name)`; `(ci_type, status)`; `team_id` partial |
| `ci_relationships` (V8) [demo] | `source_id`, `target_id`, `rel_type`, `description`, `origin` | FKs → CIs (CASCADE); UQ `(source_id, target_id, rel_type)`; `rel_type` `DEPENDS_ON, RUNS_ON, HOSTS, CONNECTS_TO, PART_OF`; no self-relationship; `origin` `MANUAL, SERVICE_MAP`. Cycles are allowed in the data; traversal is cycle-safe | `ix_ci_rel_target` |
| `ci_history` (V8) | `ci_id`, `actor_id`, `actor_name`, `action`, `from_value`, `to_value`, `note` | FK CASCADE | `(ci_id, created_at, id)` |
| `problem_cis`, `change_cis` (V8) | `(problem_id, ci_id)`, `(change_id, ci_id)` | composite PKs; FKs CASCADE. Back-filled where a free-text `affected_cis` name matched a CI exactly | `ix_problem_cis_ci`, `ix_change_cis_ci` |

V8 created a `BUSINESS_SERVICE` CI for every existing business service and turned `service_dependencies` into
`DEPENDS_ON` relationships (`origin = SERVICE_MAP`).

### 3.4 Incidents

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `incidents` [demo][v] | `number`, `title`, `description`, `category`, `classification_source`, `classification_confidence`, `impact`, `urgency`, `priority`, `status`, `service_id`, `department_id`, `team_id` (V6), `assignee_id`, `requester_id`, `created_by_id`, `resolved_by_id`, `parent_id`, `is_major`, `affected_users`, `duplicate_score`, `source`, `escalation_level`, `resolution_code`, `resolution_notes`, `root_cause`, `resolution_article_id`, `first_response_at`, `resolved_at`, `closed_at` | UQ `number`; all FKs SET NULL (services, departments, teams, users, parent incident, KB article); `category` `APPLICATION, NETWORK, HARDWARE, ACCESS, DATABASE, EMAIL, SECURITY, FACILITIES, OTHER`; `classification_source` `MANUAL, RULES, AI, MONITORING`; `impact`/`urgency` `HIGH, MEDIUM, LOW`; `priority` `CRITICAL, HIGH, MEDIUM, LOW`; `status` (V3) `NEW, ASSIGNED, IN_PROGRESS, PENDING, RESOLVED, CLOSED, REOPENED` (default `NEW`); `source` `PORTAL, EMAIL, PHONE, MONITORING, API, SCENARIO`; `resolution_code` (V3) adds `CANCELLED`; `escalation_level` 0–3; confidence 0–1; not its own parent; RESOLVED/CLOSED need `resolved_at` | `(status, priority)`, `created_at DESC`, `resolved_at` partial, service, department, assignee, requester, `parent_id` partial, GIN trigram on `title`; V3 `ix_incidents_active_created` partial on active statuses; V6 `ix_incidents_team_active` partial |
| `incident_comments` | `incident_id`, `author_id`, `body` (1–10 000 chars), `internal`, `system_generated` | FK CASCADE / SET NULL | `(incident_id, created_at)` |
| `incident_attachments` | `incident_id`, `uploaded_by_id`, `file_name`, `content_type`, `size_bytes`, `sha256`, `data` (BYTEA) | FK CASCADE; `size_bytes` 1 – 10 485 760 (the application enforces 5 MB) | `ix_attachments_incident` |
| `incident_history` | `incident_id`, `actor_id`, `actor_name`, `event_type`, `field`, `old_value`, `new_value`, `message` | FK CASCADE | `(incident_id, created_at)` |
| `incident_links` | `source_id`, `target_id`, `link_type`, `created_by_id` | FKs CASCADE; `link_type` `RELATED_TO, CAUSED_BY, BLOCKS`; not self; UQ `(source_id, target_id, link_type)` | `ix_links_target` |
| `incident_escalations` (V3) | `incident_id`, `incident_sla_id`, `level` (1–3), `source`, `reason`, `actor_id`, `actor_name` | FKs CASCADE; `source` `SLA, MANUAL, WORKFLOW, AI` | `(incident_id, created_at)`; `ux_escalation_sla_level` on `(incident_sla_id, level) WHERE incident_sla_id IS NOT NULL` (each SLA level escalates once) |
| `incident_categories` (V3) | PK `code`, `name`, `description`, `default_department_id`, `keywords`, `active`, `sort_order`, `updated_by_id` | natural PK `code` restricted to the nine category codes; UQ `name` | — |

### 3.5 Service catalogue, service desk and forms

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `service_categories` [demo] | `code`, `name`, `description`, `icon`, `sort_order` | UQ `code`, UQ `name`. V3 seeds the standard codes `HARDWARE, SOFTWARE, NETWORK, ACCESS, ACCOUNT, FACILITIES, GENERAL` | — |
| `catalog_items` [demo] | `category_id`, `name`, `description`, `fulfillment_department_id`, `requires_approval`, `approver_role`, `fulfillment_hours`, `form_fields` (JSONB, Release 2 format), `active`, `form_definition_id` (V7), `allowed_roles`, `allowed_department_ids` (V7, JSONB arrays) | FK category (NN), department (NN), form definition (SET NULL); UQ `(category_id, name)`; hours 1–2160; `ck_catalog_approver` (V3): approval ⇔ approver role in `IT_MANAGER, INCIDENT_MANAGER, SYSTEM_ADMIN` | — |
| `service_requests` [demo][v] | `number`, `catalog_item_id`, `requester_id`, `title`, `details`, `form_data` (JSONB), `status`, `priority` (V3), `department_id`, `team_id` (V6), `assignee_id`, `due_at`, `fulfilled_at`, `fulfillment_notes`, `form_definition_id`, `form_version` (V7) | UQ `number`; `status` `PENDING_APPROVAL, APPROVED, REJECTED, IN_FULFILLMENT, FULFILLED, CANCELLED`; FULFILLED needs `fulfilled_at`; `priority` `LOW, MEDIUM, HIGH, CRITICAL` | `(requester_id, created_at DESC)`, status, department, priority, `team_id` partial, `assignee_id` partial |
| `service_request_comments` (V3), `service_request_history` (V3) | comments (`body` 1–10 000, `internal`), history (`action`, `from_value`, `to_value`, `note`, `actor_name`) | FK CASCADE | `(request_id, created_at)` |
| `service_request_attachments` (V7) | `request_id` (NULL while staged), `uploaded_by_id`, `field_key`, `file_name`, `content_type`, `size_bytes`, `sha256`, `data` | FK request CASCADE, uploader CASCADE; size 1 – 10 MB at database level | `ix_request_attachments_request`; `ix_request_attachments_staged` on `(uploaded_by_id, created_at) WHERE request_id IS NULL` |
| `form_definitions` (V7) [demo][v] | `code`, `name`, `description`, `is_template`, `active`, `current_version` | UQ `code` (`^[A-Z0-9_-]{2,40}$`); `current_version ≥ 1` | — |
| `form_definition_versions` (V7) | `form_id`, `version_number`, `fields` (JSONB array), `change_note` | FK CASCADE; UQ `(form_id, version_number)` | — |

V7 gave every existing catalogue item its own form definition (`ITEM-<id>`, version 1 = the unchanged Release 2 field list)
and linked existing requests to it.

### 3.6 Approval engine

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `approvals` [demo][v] | `approval_type`, `reference_id`, `reference_number`, `title`, `details`, `payload`, `requested_by_id`, `approver_role` (nullable since V7), `status`, `decided_by_id`, `decision_comment`, `decided_at`; V7: `flow_id`, `flow_name`, `current_stage`, `stage_count`, `due_at`, `link`, `facts` (JSONB, e.g. `{"changeRisk": "HIGH"}`) | `approval_type` (V7) `SERVICE_REQUEST, RUNBOOK_EXECUTION, RUNBOOK_STEP, AI_ACTION, CHANGE, PIR, WORKFLOW`; `approver_role` any role or NULL; `status` `PENDING, APPROVED, REJECTED, CANCELLED`; decided rows need `decided_at`; FK `flow_id` → approval_flows (SET NULL) | `ix_approvals_pending (approver_role, created_at) WHERE status = 'PENDING'`; `(approval_type, reference_id)`; `(requested_by_id, created_at DESC)`; `created_at DESC` |
| `approval_flows` (V7) [demo][v] | `code`, `name`, `description`, `subject_type`, `stages` (JSONB array, 1–10), `active`, `system_flow`, `created_by_id` | UQ `code`. V7 inserts the system flows `CHANGE_NORMAL` and `CHANGE_EMERGENCY` (configuration, not demo) | — |
| `approval_stages` (V7) [v] | `approval_id`, `stage_order`, `name`, `mode`, `quorum`, `required_count`, `stage_condition`, `status`, `deadline_hours`, `due_at`, `escalation`, `escalated_at`, `started_at`, `completed_at` | FK CASCADE; UQ `(approval_id, stage_order)`; `mode` `SEQUENTIAL, PARALLEL`; `quorum` `ANY, ALL, N`; `required_count ≥ 1`; `status` `WAITING, ACTIVE, APPROVED, REJECTED, SKIPPED, CANCELLED`; deadline 1–720 h | `ix_approval_stages_due (due_at) WHERE status = 'ACTIVE' AND escalated_at IS NULL` (deadline scheduler) |
| `approval_tasks` (V7) [v] | `approval_id`, `stage_id`, `seq`, `approver_kind`, `approver_user_id`, `approver_role`, `approver_team_id`, `status`, `decided_by_id`, `decided_at`, `comment`, `delegated_from_id`, `delegated_at` | FKs CASCADE / SET NULL; `approver_kind` `USER, ROLE, TEAM` with the matching target column required (unless already final); `status` `WAITING, PENDING, APPROVED, REJECTED, SKIPPED, CANCELLED`; decided tasks need `decided_at` | `(approval_id, stage_id, seq)`; pending-task partial indexes by role, user and team; decider partial |
| `approval_events` (V7) | `approval_id`, `stage_order`, `task_id`, `event_type`, `actor_id`, `actor_name`, `comment`, `details` | FK CASCADE; append-only by convention (no update path in the application) | `(approval_id, created_at, id)` |
| `approval_delegations` (V7) | `delegator_id`, `delegate_id`, `starts_at`, `ends_at`, `reason`, `revoked_at` | FKs CASCADE; `ends_at > starts_at`; no self-delegation | open delegations by delegator and by delegate |

V7 converted every Release 2 approval into a one-stage approval with one role task, keeping routing, decision and history.

### 3.7 Problems, changes, releases and deployments

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `problems` (V7) [demo][v] | `number`, `title`, `description`, `status`, `priority`, `category`, `owner_id`, `team_id`, `department_id`, `root_cause`, `workaround`, `permanent_fix`, `known_error`, `known_error_at`, `kb_article_id`, `affected_cis` (JSONB names), `closure_note`, `root_cause_at`, `resolved_at`, `closed_at`, `created_by_id` | UQ `number`; `status` `NEW, INVESTIGATING, ROOT_CAUSE_IDENTIFIED, KNOWN_ERROR, RESOLVED, CLOSED`; priority and category lists; known errors need `known_error_at` | `(status, created_at DESC)`, owner and team partial |
| `problem_services` (V7) | `(problem_id, service_id)` | composite PK, FKs CASCADE | `ix_problem_services_service` |
| `problem_incidents` (V7) | `(problem_id, incident_id)`, `linked_by_id` | composite PK; **UQ `incident_id`** — an incident belongs to at most one problem | — |
| `problem_actions` (V7) [demo][v] | `problem_id`, `title`, `description`, `action_type` (`CORRECTIVE, PREVENTIVE`), `owner_id`, `due_date`, `status` (`OPEN, IN_PROGRESS, DONE, CANCELLED`), `completed_at` | DONE needs `completed_at` | by problem; open actions by owner |
| `problem_history` (V7) | `problem_id`, `actor_name`, `action`, `from_value`, `to_value`, `note` | FK CASCADE | `(problem_id, created_at, id)` |
| `change_templates` (V7) [demo][v] | `code`, `name`, `description`, `category`, `implementation_plan`, `rollback_plan`, `test_plan`, `default_duration_minutes` (5–10 080), `service_ids` (JSONB array), `active` | UQ `code` | — |
| `changes` (V7) [demo][v] | `number`, `title`, `description`, `change_type`, `status`, `category`, `priority`, `risk`, `risk_score`, `risk_answers` (JSONB object), `risk_factors` (JSONB array), `risk_assessed_at`, `impact`, `affected_users`, `affected_cis`, `justification`, plans, `planned_start/end`, `actual_start/end`, `requester_id`, `assignee_id`, `team_id`, `template_id`, `approval_id`, `problem_id`, `outcome_notes`, `pir_required`, `pir_notes`, `pir_completed_at`, `pir_by_id`, `conflict_override_reason`, `submitted_at`, `approved_at`, `closed_at` | UQ `number`; `change_type` `STANDARD, NORMAL, EMERGENCY`; `status` `DRAFT, SUBMITTED, ASSESSMENT, AWAITING_APPROVAL, APPROVED, SCHEDULED, IMPLEMENTING, COMPLETED, FAILED, ROLLED_BACK, REJECTED, CANCELLED`; `risk`/`impact` `LOW, MEDIUM, HIGH`; planned end > start; actual end ≥ start; JSON type checks; FK `approval_id` → approvals (SET NULL) | `(status, created_at DESC)`; `(planned_start, planned_end)` partial (calendar/conflicts); assignee and approval partial |
| `change_services` (V7) | `(change_id, service_id)` | composite PK | `ix_change_services_service` |
| `change_incidents` (V7) | `(change_id, incident_id)`, `relation` (`CAUSED_BY, RESOLVES, RELATED`) | composite PK, FKs CASCADE | `ix_change_incidents_incident` |
| `change_history` (V7) | `change_id`, `actor_name`, `action`, `from_value`, `to_value`, `note` | FK CASCADE | `(change_id, created_at, id)` |
| `change_freeze_windows` (V7) [demo] | `name`, `reason`, `starts_at`, `ends_at`, `service_ids` (JSONB, empty = all), `active` | `ends_at > starts_at` | `(starts_at, ends_at) WHERE active` |
| `releases` (V7) [demo][v] | `number`, `name`, `description`, `status` (`PLANNED, IN_PROGRESS, DEPLOYED, FAILED, CANCELLED`), `service_id`, `change_id`, `owner_id`, `target_at`, `row_version` | UQ `number` | — |
| `deployment_sources` (V7) [demo] | `name`, `provider` (`GITHUB, GITLAB, GENERIC`), `secret_encrypted` (AES-256-GCM), `service_id`, `default_environment`, `active`, `last_delivery_at` | UQ `name`; FK `service_id` NN | — |
| `deployments` (V7) [demo][v] | `number`, `release_id`, `change_id`, `service_id`, `environment` (`DEV, TEST, STAGING, PRODUCTION`), `commit_sha`, `pipeline_url`, `status` (`IN_PROGRESS, SUCCEEDED, FAILED, ROLLED_BACK`), `source` (`MANUAL, GITHUB, GITLAB, GENERIC`), `source_id`, `external_id`, `actor_name`, `actor_id`, `started_at`, `finished_at`, `rollback_of_id`, `rolled_back_at`, `rollback_reason`, `notes`, `row_version` | UQ `number`; finished ≥ started; ROLLED_BACK needs `rolled_back_at` | `ux_deployment_external` on `(source_id, external_id) WHERE external_id IS NOT NULL` (one record per pipeline run); `(service_id, started_at DESC)`; `started_at DESC` |
| `deployment_webhook_deliveries` (V7) | `source_id`, `delivery_id`, `event_type`, `outcome` (`PROCESSED, IGNORED, FAILED`), `message`, `deployment_id`, `received_at` | **UQ `(source_id, delivery_id)`** — replay protection | `(source_id, received_at DESC)` |

### 3.8 Assets

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `assets` (V8) [demo][v] | `asset_tag`, `name`, `asset_type`, `status`, `manufacturer`, `model`, `serial_number`, `assigned_to_id`, `assigned_at`, `department_id`, `location`, `vendor`, `purchase_order`, `purchase_cost` (NUMERIC(12,2)), `currency` (default `INR`), `purchase_date`, `warranty_expires`, `license_seats`, `license_expires`, `ci_id`, `notes`, `retired_at`, `disposed_at` | UQ `asset_tag`; `asset_type` `LAPTOP, DESKTOP, SERVER, NETWORK, MOBILE, MONITOR, PERIPHERAL, SOFTWARE_LICENSE, OTHER`; `status` `IN_STOCK, IN_USE, IN_REPAIR, RETIRED, DISPOSED`; cost ≥ 0; currency `^[A-Z]{3}$`; seats present **iff** `SOFTWARE_LICENSE` (1–100 000); licences are never assigned to one holder (seat holders are in `asset_assignments`) | `ux_asset_serial` on `lower(serial_number)` partial; `(status, asset_type)`; assignee, warranty and CI partial |
| `asset_assignments` (V8) | `asset_id`, `user_id`, `assigned_by_id`, `assigned_at`, `returned_at`, `returned_by_id`, `note` | FKs CASCADE / SET NULL; returned ≥ assigned | `ux_asset_assignment_open` on `(asset_id, user_id) WHERE returned_at IS NULL`; by asset; open by user |
| `asset_maintenance` (V8) [demo] | `asset_id`, `maintenance_type` (`REPAIR, PREVENTIVE, UPGRADE, INSPECTION`), `status` (`SCHEDULED, COMPLETED`), `description`, `vendor`, `cost`, `performed_on`, `next_due`, `created_by_name` | cost ≥ 0 | `(asset_id, performed_on DESC)` |
| `asset_events` (V8) | `asset_id`, `event_type`, `from_value`, `to_value`, `note`, `actor_name` | FK CASCADE | `(asset_id, created_at, id)` |

### 3.9 Alerts

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `alert_sources` (V8) [demo] | `name`, `provider` (`PROMETHEUS, GRAFANA, CLOUDWATCH, GENERIC`), `secret_encrypted`, `sns_topic_arn`, `subscription_status` (`NONE, PENDING, CONFIRMED, UNSUBSCRIBED`), `subscription_arn`, `subscription_confirmed_at`, `default_service_id`, `default_ci_id`, `default_severity`, `active`, `last_received_at` | UQ `name`; every provider except CLOUDWATCH needs a secret; CLOUDWATCH needs a topic ARN | — |
| `alert_webhook_deliveries` (V8) | `source_id`, `delivery_id`, `event_type`, `outcome`, `message`, `alert_count`, `received_at` | **UQ `(source_id, delivery_id)`** (replay protection) | `(source_id, received_at DESC)` |
| `alert_suppression_rules` (V8) [demo][v] | `name`, `description`, `enabled`, `match_criteria` (JSONB object: labels, sources, severities, serviceIds, ciIds, titleContains), `starts_at`, `ends_at`, `daily_start`, `daily_end`, `days_of_week` (JSONB array), `match_count`, `last_matched_at` | window end > start; daily start and end set together | — |
| `alert_routing_rules` (V8) [demo][v] | `name`, `enabled`, `sort_order`, `match_criteria`, `team_id`, `service_id`, `ci_id`, `create_incident`, `incident_min_severity`, `match_count`, `last_matched_at` | severity list | `(sort_order, id) WHERE enabled` (first match wins) |
| `alerts` (V8) [demo][v] | `number`, `source_kind` (`PROMETHEUS, GRAFANA, CLOUDWATCH, GENERIC, INTEGRATION, MANUAL`), `source_id`, `integration_id`, `scope_key`, `fingerprint`, `group_key`, `title`, `description`, `severity` (`CRITICAL, MAJOR, MINOR, WARNING, INFO`), `status` (`OPEN, ACKNOWLEDGED, RESOLVED, SUPPRESSED`), `firing`, `labels`, `annotations`, `external_url`, `service_id`, `ci_id`, `team_id`, `incident_id`, `routing_rule_id`, `suppression_rule_id`, `occurrence_count`, `first_seen_at`, `last_seen_at`, `starts_at`, `ends_at`, ack/resolve/suppress columns, `escalation_level`, `last_escalated_at`, `next_escalation_at` | UQ `number`; all FKs SET NULL; `occurrence_count ≥ 1`; `last_seen_at ≥ first_seen_at`; RESOLVED needs `resolved_at`, ACKNOWLEDGED needs `acknowledged_at` | **`ux_alert_active_fingerprint` on `(scope_key, fingerprint) WHERE status IN ('OPEN','ACKNOWLEDGED','SUPPRESSED')`** — one active alert per source scope and fingerprint (deduplication); `(scope_key, fingerprint, resolved_at DESC)` (reopen within the grouping window); `(status, last_seen_at DESC)`; active by team; group, incident, CI partial; `ix_alerts_escalation (next_escalation_at) WHERE status = 'OPEN' AND next_escalation_at IS NOT NULL` (escalation scheduler) |
| `alert_events` (V8) | `alert_id`, `event_type`, `actor_name`, `note`, `details` | FK CASCADE | `(alert_id, created_at, id)` |

### 3.10 On-call, major incidents and post-incident reviews

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `oncall_escalation_policies` (V9) [demo][v] | `name`, `description`, `team_id`, `min_severity`, `levels` (JSONB array, 1–10 levels of `{delayMinutes, target, userId?}`), `active` | severity list | `ux_oncall_policy_name` on `lower(name)` |
| `oncall_schedules` (V9) [demo][v] | `team_id`, `name`, `description`, `time_zone`, `rotation_type` (`DAILY, WEEKLY, CUSTOM`), `handoff_time` (default 09:00), `handoff_day` (1–7), `shift_hours`, `rotation_start`, `escalation_policy_id`, `holiday_calendar_id`, `holiday_cover_user_id`, `active` | FK team CASCADE, others SET NULL; WEEKLY needs `handoff_day`; CUSTOM needs `shift_hours` 1–336 | `ux_oncall_schedule_name` on `lower(name)`; `team_id WHERE active` |
| `oncall_participants` (V9) | `schedule_id`, `layer` (`PRIMARY, SECONDARY`), `position`, `user_id` | FKs CASCADE; UQ `(schedule_id, layer, position)`, UQ `(schedule_id, layer, user_id)` | by user |
| `oncall_overrides` (V9) [demo][v] | `schedule_id`, `layer`, `user_id`, `original_user_id`, `starts_at`, `ends_at`, `reason`, `status` (`OFFERED, ACTIVE, DECLINED, CANCELLED`), `created_by_id`, `taken_at`, `decided_note` | end > start; ACTIVE needs `user_id` | `(schedule_id, starts_at) WHERE status IN ('OFFERED','ACTIVE')` |
| `oncall_unavailability` (V9) [demo] | `user_id`, `starts_at`, `ends_at`, `reason` | end > start | `(user_id, ends_at)` |
| `oncall_handovers` (V9) [demo] | `schedule_id`, `author_name`, `to_user_id`, `shift_start`, `shift_end`, `body` (1–5000) | FK schedule CASCADE | `(schedule_id, created_at DESC)` |
| `oncall_pages` (V9) [demo] | `alert_id`, `schedule_id`, `policy_id`, `level`, `target` (`PRIMARY, SECONDARY, MANAGER, USER`), `user_id`, `paged_at` | FK alert CASCADE; **UQ `(alert_id, level, user_id)`** — each person is paged once per level | `paged_at`; `(schedule_id, paged_at)` |
| `major_incidents` (V9) [demo][v] | `number`, `incident_id`, `status` (`ACTIVE, MONITORING, RESOLVED, CLOSED`), `commander_id`, `tech_lead_id`, `comms_lead_id`, `summary`, `impact_summary`, `investigation_notes`, `resolution_summary`, `bridge_url`, `declared_at`, `declared_by_id`, `resolved_at`, `closed_at` | UQ `number`; **UQ `incident_id`** (FK CASCADE) — one command record per incident | `(status, declared_at DESC)` |
| `major_incident_services` (V9) | `(major_incident_id, service_id)` | composite PK, FKs CASCADE | — |
| `major_incident_timeline` (V9) [demo] | `major_incident_id`, `entry_type` (`NOTE, DECISION, ACTION, INVESTIGATION, STATUS, ROLE, UPDATE, SYSTEM`), `body` (≤ 4000), `actor_name`, `occurred_at` | FK CASCADE | `(major_incident_id, occurred_at, id)` |
| `stakeholder_updates` (V9) [demo] | `major_incident_id`, `status` (`INVESTIGATING, IDENTIFIED, MONITORING, RESOLVED`), `title`, `message` (1–5000), `status_update_id`, `published_at`, `posted_by_name`, `posted_at` | FK `status_update_id` → status_updates (SET NULL) when published | `(major_incident_id, posted_at DESC)` |
| `post_incident_reviews` (V9) [demo][v] | `number`, `incident_id`, `major_incident_id`, `title`, `status` (`DRAFT, IN_REVIEW, APPROVED, REJECTED`), `owner_id`, `summary`, `root_cause`, `contributing_factors` (JSONB array), `impact`, `detection_gaps`, `response_analysis`, `lessons_learned`, `timeline` (JSONB array), `metrics` (JSONB object), `timeline_built_at`, `approval_id`, `submitted_at`, `approved_at`, `approved_by_id`, `review_comment` | UQ `number`; **UQ `incident_id`** — one review per incident; APPROVED needs `approved_at`; FK `approval_id` → approvals (SET NULL) | `(status, created_at DESC)` |
| `incident_action_items` (V9) [demo][v] | `major_incident_id`, `pir_id`, `title`, `description`, `action_type` (`CORRECTIVE, PREVENTIVE, FOLLOW_UP`), `owner_id`, `due_date`, `status` (`OPEN, IN_PROGRESS, DONE, CANCELLED`), `completed_at` | `ck_action_item_parent`: exactly one of `major_incident_id` / `pir_id` is set; FKs CASCADE | open items by owner; by major incident; by PIR |

V9 created a command record (`CLOSED`, `RESOLVED` or `ACTIVE` from the incident status) with its service and a `SYSTEM`
timeline entry for every top-level incident that was already flagged major.

### 3.11 Knowledge, runbooks and workflows

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `knowledge_articles` [demo][v] | `number`, `title`, `summary`, `content`, `article_type` (`GUIDE, SOLUTION, KNOWN_ERROR, FAQ`), `category`, `service_id`, `tags`, `status` (V3: `DRAFT, IN_REVIEW, PUBLISHED, ARCHIVED`), `author_id`, `view_count`, `helpful_count`, `not_helpful_count`, `read_minutes`, `published_at`, `search_vector` (generated `tsvector`); V3: `rating_count`, `rating_average` (1–5), `legacy_helpful_count`, `legacy_not_helpful_count`, `current_version` | UQ `number`; PUBLISHED needs `published_at`; non-negative counters | GIN on `search_vector`; GIN trigram on `title`; `(status, article_type)`; service |
| `kb_article_versions` (V3) | `article_id`, `version_number`, snapshot of title/summary/content/type/category/service/tags, `editor_name`, `change_note` | FK CASCADE; UQ `(article_id, version_number)` | — |
| `kb_article_ratings` (V3) | `article_id`, `user_id`, `rating` (1–5), `comment` | FKs CASCADE; UQ `(article_id, user_id)` (one rating per user) | — |
| `runbooks` [demo][v] | `title`, `description`, `service_id`, `steps` (non-empty JSONB array), `requires_approval`, `estimated_minutes` (1–1440), `active`, `author_id`, `prerequisites` (V3, JSONB array) | UQ `title`. V3 moved the single `category` column into `runbook_categories` | — |
| `runbook_categories` (V3) | `(runbook_id, category)` | composite PK; category list | by category |
| `runbook_executions` [demo][v] | `runbook_id`, `incident_id`, `status` (`PENDING_APPROVAL, READY, IN_PROGRESS, COMPLETED, REJECTED, ABORTED`), `started_by_id`, `approval_id`, `step_states` (JSONB), `outcome_notes`, `started_at`, `completed_at` | FKs CASCADE / SET NULL | incident; `(runbook_id, created_at DESC)`; `created_at DESC` |
| `workflows` [demo][v] | `name`, `description`, `trigger_event`, `active`, `current_version`, `max_executions_per_hour` (1–10 000), `max_retries` (0–10), `retry_backoff_seconds` (1–3600), `created_by_id`, `last_scheduled_run_at` (V3) | UQ `name`; `trigger_event` (V8) `INCIDENT_CREATED, INCIDENT_UPDATED, INCIDENT_STATUS_CHANGED, INCIDENT_ASSIGNED, PRIORITY_CHANGED, SLA_AT_RISK, SLA_BREACHED, SERVICE_REQUEST_CREATED, SCHEDULED, CHANGE_REQUEST_CREATED, ALERT_RECEIVED` | `trigger_event WHERE active` |
| `workflow_versions` | `workflow_id`, `version_number`, `definition` (JSONB; V3 migrated Release 1 definitions to the new status names), `change_note` | UQ `(workflow_id, version_number)` | — |
| `workflow_executions` | `workflow_id`, `workflow_version`, `trigger_event`, `incident_id`, `service_request_id`, `change_id` (V7), `alert_id` (V8), `status` (`RUNNING, SUCCEEDED, FAILED, RETRY_SCHEDULED, RATE_LIMITED`), `attempt`, `next_attempt_at`, `error_message`, `steps` (JSONB), `depth`, `branch` (V3: `YES, NO`), `started_at`, `finished_at` | RETRY_SCHEDULED needs `next_attempt_at` | by workflow; `next_attempt_at WHERE status = 'RETRY_SCHEDULED'` (retry poller); incident; scheduled-run partial; `started_at DESC` |

### 3.12 Notifications, e-mail, status page and reports

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `notifications` | `user_id`, `type`, `title`, `message`, `link`, `severity` (`INFO, SUCCESS, WARNING, CRITICAL`), `read_at` | FK CASCADE | `(user_id, created_at DESC)`; unread partial |
| `notification_preferences` (V3) | `user_id`, `type`, `in_app`, `email` (V6; NULL = the category default) | PK `(user_id, type)`; `ck_notification_pref_type` (rewritten in V4, V6, V7, V8, V9) — 26 categories after V9: `INCIDENT_ASSIGNED, INCIDENT_COMMENT, INCIDENT_RESOLVED, SLA_AT_RISK, SLA_BREACHED, ESCALATION, APPROVAL_REQUESTED, APPROVAL_DECIDED, WORKFLOW_FAILED, REQUEST_UPDATED, ANNOUNCEMENT, ACCESS_REQUEST, PASSWORD_RESET_REQUEST, TEAM_QUEUE, REPORT_READY, APPROVAL_ESCALATED, PROBLEM_UPDATED, CHANGE_UPDATED, DEPLOYMENT_STATUS, ALERT_ROUTED, ALERT_ESCALATED, ASSET_ASSIGNED, ONCALL_PAGE, ONCALL_SHIFT, MAJOR_INCIDENT_UPDATE, PIR_UPDATED` | — |
| `announcements` [demo] | `title`, `body`, `severity` (`INFO, MAINTENANCE, WARNING, CRITICAL`), `service_id`, `starts_at`, `ends_at`, `published`, `author_id` | end > start when both set | — |
| `email_outbox` (V6) | `category`, `to_address`, `user_id`, `subject`, `html_body`, `text_body`, `sensitive`, `attachment_name`, `attachment_content_type`, `attachment_data`, `status` (`PENDING, SENT, FAILED, SKIPPED`), `attempts`, `next_attempt_at`, `last_error`, `sent_at` | PENDING needs `next_attempt_at`; SENT needs `sent_at`; an attachment needs a name. Bodies of `sensitive` messages (one-time links) are replaced by a placeholder once final | `next_attempt_at WHERE status = 'PENDING'` (delivery queue); `created_at DESC`; `(status, created_at DESC)` |
| `status_page_settings` (V6) | `id` SMALLINT PK, `title`, `description`, `public_enabled` (default false), `updated_by_id` | `ck_status_page_single_row` (`id = 1`) — a single-row table, inserted by V6 | — |
| `status_components` (V6) [demo] | `service_id`, `display_name`, `group_name`, `description`, `sort_order`, `is_public` | UQ `service_id` (FK CASCADE) | — |
| `maintenance_windows` (V6) [demo][v] | `title`, `description`, `starts_at`, `ends_at`, `status` (`SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED`), `created_by_id` | end > start | `(starts_at, ends_at)` |
| `maintenance_window_components` (V6) | `(window_id, component_id)` | composite PK, FKs CASCADE | by component |
| `status_updates` (V6) [demo] | `incident_id`, `title`, `status` (`INVESTIGATING, IDENTIFIED, MONITORING, RESOLVED`), `message` (1–5000), `is_public`, `posted_by_id`, `posted_at` | FKs SET NULL | `posted_at DESC`; incident partial |
| `status_subscribers` (V6) | `email`, `confirm_token_hash`, `confirm_expires_at`, `confirmed_at`, `unsubscribe_token_hash`, `request_ip` | token hashes must be 64 lower-case hex characters (only SHA-256 hashes stored); UQ `unsubscribe_token_hash`; either confirmed or holding a confirmation hash | `ux_status_subscribers_email` on `lower(email)`; UQ confirm hash partial |
| `report_schedules` (V6) [demo][v] | `name`, `report_type` (`ANALYTICS_OVERVIEW, SLA, INCIDENTS, CHANGES, PROBLEMS, ASSETS, ONCALL`), `format` (`PDF, CSV`), `frequency` (`DAILY, WEEKLY, MONTHLY`), `run_hour` (0–23), `day_of_week` (1–7), `day_of_month` (1–28), `filters` (JSONB object), `recipients` (JSONB array), `owner_id`, `next_run_at`, `last_run_at`, `active` | FK owner CASCADE | `next_run_at WHERE active` (scheduler); owner |
| `report_runs` (V6) [demo] | `schedule_id`, `trigger_type` (`SCHEDULE, MANUAL`), `scheduled_for`, `triggered_by_id`, `status` (`RUNNING, SUCCEEDED, FAILED`), `period_from`, `period_to`, `file_name`, `content_type`, `file_data` (BYTEA), `size_bytes`, `error`, `emailed_to`, `started_at`, `finished_at` | SUCCEEDED needs a file; SCHEDULE needs `scheduled_for` | **`ux_report_run_period` on `(schedule_id, scheduled_for) WHERE trigger_type = 'SCHEDULE'`** — a schedule runs at most once per due time; `(schedule_id, started_at DESC)` |

### 3.13 Integrations, AI, settings and audit

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `integrations` [demo] | `name`, `integration_type` (`WEBHOOK, SLACK, TEAMS, MONITORING_INBOUND`), `enabled`, `endpoint_url`, `secret_encrypted` (AES-256-GCM), `inbound_token_hash`, `subscribed_events`, `default_service_id`, `last_delivery_at`, `last_delivery_status`, `failure_count`, `created_by_id` | UQ `name`, UQ `inbound_token_hash`; outbound types need `endpoint_url` | — |
| `integration_deliveries` | `integration_id`, `event_type`, `status` (`SUCCESS, FAILED`), `http_status`, `response_snippet`, `duration_ms` | FK CASCADE | `(integration_id, created_at DESC)` |
| `ai_interactions` | `user_id`, `feature`, `source` (`LLM, FALLBACK`), `provider`, `model`, `incident_id`, `latency_ms`, `success`, `error`, `subject` (V3) | FKs SET NULL | `created_at DESC`; `(user_id, created_at DESC)` |
| `system_settings` | PK `setting_key`, `setting_value`, `value_type` (`STRING, NUMBER, BOOLEAN`), `description`, `updated_by_id` | natural PK | — |
| `audit_logs` | `actor_id`, `actor_email`, `action`, `entity_type`, `entity_id`, `details` (JSONB), `ip_address`, `user_agent`, `outcome` (`SUCCESS, FAILURE, DENIED`), `created_at` | FK `actor_id` → users (SET NULL) | `created_at DESC`, actor, `(entity_type, entity_id)`, action |

### 3.14 Simulation, observability and digital twin (V10)

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `simulation_runs` (V10) | `scenario` (`BASELINE, PAYROLL_DB_SATURATION, DEPLOY_REGRESSION, VULN_WAVE, COST_SPIKE, ENDPOINT_DEGRADATION`), `status` (`RUNNING, STOPPED`), `tick_count`, `summary` (JSONB), `started_at`, `stopped_at`, `next_tick_at` | `ux_simulation_running` on `(scenario) WHERE status = 'RUNNING'` | `started_at DESC` |
| `simulation_fault_windows` (V10) | `run_id`, `detector_name`, `metric_name`, `service_id`, `ci_id`, `starts_at`, `ends_at`, `description` | FK `run_id` CASCADE | `(metric_name, starts_at)` |
| `telemetry_sources` (V10) [demo] | `name`, `kind` (`OTLP, PROMETHEUS_PULL, GRAFANA, CLOUDWATCH, AZURE_MONITOR, GCP_MONITORING`), `auth_type`, `token_hash`, `token_hint`, `secret_encrypted`, `endpoint_url`, `config` (JSONB), `status`, `active` | UQ `name` | `token_hash WHERE token_hash IS NOT NULL` |
| `telemetry_mapping_rules` (V10) [demo] | `name`, `attribute_key`, `match_type` (`EXACT, PREFIX, REGEX`), `match_value`, `service_id`, `ci_id`, `sort_order`, `enabled` | FKs SET NULL | `(sort_order, id) WHERE enabled` |
| `telemetry_resources` (V10) | `fingerprint`, `service_name`, `attributes` (JSONB), `service_id`, `ci_id`, `simulated`, `first_seen_at`, `last_seen_at` | UQ `(fingerprint, simulated)` | `service_id`, `ci_id` |
| `telemetry_metric_series` (V10) | `resource_id`, `name`, `unit`, `kind` (`GAUGE, SUM, HISTOGRAM`), `attributes` (JSONB), `simulated`, `first_seen_at`, `last_seen_at` | FK `resource_id` CASCADE | `(name, resource_id)` |
| `telemetry_metric_points` / `telemetry_metric_rollups` (V10) | `series_id`, `recorded_at` / `bucket_start`, `bucket_size` (`5m, 1h`), `value` / `min_val, max_val, avg_val, sum_val, p50_val, p95_val, p99_val, sample_count`, `simulated` | FK `series_id` CASCADE; UQ `(series_id, bucket_size, bucket_start)` on rollups | `(series_id, recorded_at DESC)` |
| `telemetry_logs` / `telemetry_spans` (V10) | `resource_id`, `trace_id`, `span_id`, `parent_span_id`, `severity` / `name, kind, status_code, duration_ms`, `body` / `attributes`, `simulated`, `recorded_at` / `started_at` | FK `resource_id` CASCADE | `trace_id`, `recorded_at DESC` |
| `anomaly_detectors` / `telemetry_anomalies` / `anomaly_detector_evaluations` (V10) [demo] | detector config (`EWMA, MEDIAN_MAD, ZSCORE, SEASONAL`), detected anomaly spans (`alert_id`, `simulated`), precision/recall/F1 evaluation runs | FKs SET NULL / CASCADE | `(status, detected_at DESC)` |
| `correlation_groups` / `correlation_group_members` (V10) | topological & temporal alert correlation groups (`root_ci_id`, `service_id`, `incident_id`, `score`, `reasons`, `simulated`) | UQ `(group_id, alert_id)` | `(status, last_alert_at DESC)` |

### 3.15 Predictive intelligence and agentic AI (V11)

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `prediction_models` (V11) | `model_type` (`SLA_BREACH, INCIDENT_RECURRENCE, CAPACITY_RISK, SERVICE_HEALTH, ANOMALY`), `scope_key`, `version`, `algorithm`, `status` (`TRAINED, ACTIVE, INSUFFICIENT_DATA, RETIRED, FAILED`), `features`, `params`, `metrics`, `sample_count`, `include_simulated` | UQ `(model_type, scope_key, version)`; `ux_prediction_models_active` on `(model_type, scope_key) WHERE status = 'ACTIVE'` | `(model_type, created_at DESC)` |
| `prediction_jobs` (V11) | `job_type` (`TRAIN, EVALUATE, RETRAIN`), `model_type`, `model_id`, `status`, `summary` | FK `model_id` SET NULL | `started_at DESC` |
| `predictions` (V11) | `model_id`, `model_type`, `model_version`, `subject_type`, `subject_id`, `predicted_value`, `lower_bound`, `upper_bound`, `probability`, `features`, `outcome_value`, `outcome_label`, `simulated` | FK `model_id` CASCADE | `(model_id, created_at DESC)`, `(subject_type, subject_id)` |
| `agent_policies` / `agent_versions` (V11) | PK `agent_code` (`INCIDENT, ROOT_CAUSE, CHANGE_RISK, KNOWLEDGE, INFRA_INVESTIGATION, RESOLUTION`), `enabled`, `allowed_tools`, `max_steps`, `step_timeout_seconds`, `run_timeout_seconds`, `allowed_roles`, `require_approval_actions`; prompt SHA-256 & output schema registry | UQ `(agent_code, version)` | — |
| `agent_runs` / `agent_run_steps` / `agent_tool_calls` (V11) | `run_type` (`INVESTIGATE_INCIDENT, ASSESS_CHANGE, ANSWER_QUESTION`), `status`, `source` (`LLM, FALLBACK`), `input`, `output`, `confidence`, `simulated`; per-step and per-tool-call audit | FKs CASCADE | `(status, created_at DESC)` |
| `agent_proposed_actions` / `agent_eval_cases` / `agent_eval_runs` / `agent_feedback` (V11) [demo] | proposed actions linked to `approvals(id)` (`AI_ACTION`), evaluation test suites and pass/fail runs, user feedback | FKs CASCADE / SET NULL | `run_id` |

### 3.16 Autonomous remediation and security operations (V12)

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `execution_systems` (V12) [demo] | `name`, `kind` (`HTTP_WEBHOOK, ANSIBLE_AWX, KUBERNETES, AWS_SSM, SIMULATED`), `endpoint_url`, `encrypted_credentials`, `credential_scope`, `action_templates` (JSONB allow-list), `status` | UQ `name` | — |
| `remediation_playbooks` / `remediation_playbook_versions` (V12) [demo] | `code`, `name`, `target_environment`, `max_blast_radius`, `rate_limit_per_hour`, `require_linked_record`, `active_version`; versioned `steps`, `recovery_criteria`, `approval_id` (`PLAYBOOK`) | UQ `code`; UQ `(playbook_id, version)` | — |
| `remediation_runs` / `remediation_run_steps` (V12) | `playbook_id`, `playbook_version`, `trigger_kind`, `status` (`BLOCKED, PENDING_APPROVAL, APPROVED, RUNNING, PAUSED, VERIFYING, SUCCEEDED, FAILED, ROLLING_BACK, ROLLED_BACK, ROLLBACK_FAILED, CANCELLED`), `target_key`, `policy_checks`, `override_reason`, `simulated` | FKs SET NULL / CASCADE | `(target_key, status)`, `created_at DESC` |
| `remediation_audit` (V12) | `event_type`, `run_id`, `playbook_id`, `actor_name`, `payload` (JSONB), `prev_hash` (64 hex), `hash` (SHA-256 hex) | Trigger `trg_remediation_audit_immutable` blocks `UPDATE` and `DELETE` | `id ASC`, `run_id` |
| `security_incidents` / `security_incident_tasks` (V12) [demo] | `number` (`SEC-…`), `category`, `severity`, `status` (`NEW, TRIAGE, CONTAINMENT, ERADICATION, RECOVERY, CLOSED`), `indicators`, `evidence_notes`, `lessons_learned`, `simulated`; phase tasks | UQ `number`; FK CASCADE | `(status, severity)` |
| `vulnerability_sources` / `vulnerability_findings` (V12) [demo] | scanner sources (`GENERIC_JSON, SARIF, TRIVY_JSON, CSV`); normalised findings (`cve_id, asset_name, package_name, cvss_base, risk_score, risk_band, sla_days, status, simulated`) | UQ `(source_id, dedupe_key)` | `(risk_score DESC, id DESC)` |
| `security_correlation_groups` / `security_response_templates` (V12) [demo] | correlated security signals (`security_incident_id`, `simulated`); response task templates | UQ `code` on templates | `created_at DESC` |

### 3.17 FinOps and digital employee experience (V13)

| Table | Key columns | Keys and constraints | Indexes |
|---|---|---|---|
| `finops_cost_sources` / `finops_cost_items` (V13) [demo] | `kind` (`FOCUS_CSV, MAPPED_CSV, AWS_CUR, AZURE_COST, GCP_BILLING`); cost items (`charge_date, service_name, resource_id, billed_cost, effective_cost, tags, allocated_service_id, allocated_department_id, allocated_cost_center, simulated`) | UQ `(source_id, dedupe_key)` | `(charge_date, service_name)` |
| `finops_allocation_rules` / `finops_budgets` / `finops_anomalies` / `finops_recommendations` (V13) [demo] | tag/account/pattern allocation rules; monthly budgets with threshold notifications; EWMA/MAD daily cost anomalies; savings recommendations linked to draft `change_requests` | FKs SET NULL | `charge_date DESC` |
| `dex_endpoints` / `dex_app_events` / `dex_login_events` / `dex_proactive_rules` (V13) [demo] | endpoint health (`device_id, user_email, cpu/mem/disk, crash_count_7d, device_health_score, experience_score, score_breakdown, simulated`); app RUM events; login/SSO events; department proactive incident rules | UQ `device_id` | `user_email`, `department_name` |

Settings inserted by migrations (all editable by a system administrator; names are the keys used by the code):

| Migration | Keys (default) |
|---|---|
| V2 | `incident.duplicate.threshold` (0.48), `incident.duplicate.suggestion_threshold` (0.30), `incident.duplicate.window_hours` (72), `incident.duplicate.auto_link` (true), `incident.major.report_threshold` (3), `incident.assignment.auto` (true), `ai.llm.enabled` (true), `sla.escalation.severe_overrun_percent` (50) |
| V3 | `org.name`, `org.support_email`, `org.timezone` (Asia/Kolkata), `org.business_hours_note`, `notifications.sla_at_risk.notify_managers` (false), `notifications.major.notify_all_staff` (false) |
| V5, V6 | rename the default `org.name` (`NEXUS 360` → `SA 360` → `SA`), only while it still has the default value |
| V7 | `change.correlation.hours` (24), `change.freeze.enforce` (true), `deployment.correlation.hours` (12), `problem.recurring.window_days` (30), `problem.recurring.min_incidents` (3), `problem.recurring.similarity` (0.42) |
| V8 | `alert.grouping.window_minutes` (30), `alert.escalation.ack_minutes` (15), `alert.escalation.min_severity` (MAJOR), `alert.notify.min_severity` (MAJOR), `asset.warranty.warning_days` (30) |
| V9 | `ai.sla_risk.history_days` (180), `pir.due_days` (5) |
| V10 | `simulation.enabled` (false), `telemetry.retention.raw_metrics_days` (15), `telemetry.retention.rollup_5m_days` (90), `telemetry.retention.rollup_1h_days` (365), `telemetry.retention.logs_days` (30), `telemetry.retention.traces_days` (14), `telemetry.correlation.window_minutes` (15) |
| V11 | `predictions.min_samples` (200), `predictions.min_positives` (30), `predictions.min_forecast_points` (100), `predictions.drift.auc_tolerance` (0.10), `predictions.drift.brier_tolerance` (0.05), `predictions.drift.mae_ratio` (1.5), `predictions.drift.coverage_tolerance` (0.15), `predictions.drift.f1_tolerance` (0.15), `predictions.drift.min_outcomes` (30) |

## 4. Append-only and tamper-evident audit tables

1. **`audit_logs`** — trigger `trg_audit_append_only` (`BEFORE UPDATE OR DELETE … FOR EACH ROW`) calls `forbid_audit_mutation()`,
   which raises `audit_logs is append-only` for every `DELETE` and every `UPDATE`, with one exception: an `UPDATE` that only
   sets `actor_id` to `NULL` (same `action` and `created_at`) is allowed so that `ON DELETE SET NULL` on user deletion keeps working.
2. **`remediation_audit` (V12)** — each row stores `prev_hash` (`GENESIS` on the first row, or the preceding row's `hash`) and
   `hash = SHA-256(prev_hash ‖ canonicalJson)`. Trigger `trg_remediation_audit_immutable` calls `forbid_remediation_audit_mutation()`,
   which unconditionally raises `remediation_audit is append-only and tamper-evident` on any `UPDATE` or `DELETE`. `GET /api/remediation/audit/verify`
   recomputes every hash in `id ASC` order and reports whether the chain is intact.

## 5. Migration history

| Version | File | What it adds or changes |
|---|---|---|
| V1 | `V1__core_schema.sql` | `pg_trgm`; departments, users, refresh tokens; business-hours calendars, holidays, SLA policies; business services; knowledge articles (generated `tsvector`); incidents with comments, attachments, history, links; incident SLA clocks and SLA events; service categories, catalogue items, service requests; approvals; integrations and deliveries; runbooks and executions; workflows, versions, executions; notifications; announcements; `audit_logs` with the append-only trigger; AI interactions; system settings; sequences for incident, request and article numbers |
| V2 | `V2__reference_data.sql` | Default business-hours calendar (IST), four SLA policies P1–P4, core system settings |
| V3 | `V3__release2.sql` | Release 2 role model (`ADMIN/MANAGER/AGENT` → `SYSTEM_ADMIN/IT_MANAGER/SUPPORT_AGENT`, new `IT_ADMIN`, `INCIDENT_MANAGER`); incident statuses `NEW…REOPENED` and resolution code `CANCELLED`; incident escalations; standard service categories; request priority, comments and history; workflow triggers and Yes/No branches; knowledge review status, versions and ratings; runbook prerequisites and categories; service dependencies; notification preferences; configurable incident categories |
| V4 | `V4__account_access.sql` | Account requests and hashed invitation/reset tokens; notification types `ACCESS_REQUEST`, `PASSWORD_RESET_REQUEST` |
| V5 | `V5__rename_product_to_sa.sql` | Renames the default organisation name `NEXUS 360` → `SA 360` (only if unchanged) |
| V6 | `V6__release3_foundations.sql` | Default organisation name `SA 360` → `SA`; roles `CHANGE_MANAGER`, `ASSET_MANAGER`; teams and members; e-mail outbox; auth sessions; status page; scheduled reports and runs |
| V7 | `V7__approvals_forms_problems_changes_deployments.sql` | Approval engine v2; dynamic forms; problems; changes and freeze windows; releases, deployments, deployment sources and webhook deliveries |
| V8 | `V8__assets_cmdb_alerts.sql` | CMDB (configuration items, relationships, history); assets, assignments, maintenance, events; alert sources, webhook deliveries, suppression and routing rules, alerts, alert events |
| V9 | `V9__oncall_major_incidents_pir.sql` | On-call escalation policies, schedules, participants, overrides, unavailability, handovers, pages; major incidents, impacted services, war-room timeline, stakeholder updates; post-incident reviews and action items |
| V10 | `V10__release4_simulation_observability_twin.sql` | Roles `SECURITY_ANALYST`, `FINOPS_ANALYST`; simulation runs and fault windows; `simulated` flag on incidents, alerts and deployments; telemetry sources, mapping rules, resources, metric series/points/rollups, logs, spans, retention settings; anomaly detectors, anomalies and evaluations; topological alert correlation groups |
| V11 | `V11__release4_predictions_agents.sql` | Prediction model registry, training/evaluation jobs, served predictions with features and outcomes; agent governance policies, version registry, agent runs, steps, tool calls, approval-gated proposed actions (`AI_ACTION`), evaluation suites/runs and feedback |
| V12 | `V12__release4_remediation_secops.sql` | Execution systems with encrypted credentials and allow-listed action templates; versioned remediation playbooks (`PLAYBOOK` approval), runs, steps, and hash-chained `remediation_audit` with `trg_remediation_audit_immutable`; security incidents, response tasks, vulnerability sources/findings (`VULNERABILITY_ACCEPTANCE` approval), security correlation groups and response templates |
| V13 | `V13__release4_finops_dex.sql` | FinOps cost sources, cost items, allocation rules, budgets (`FINOPS_BUDGET_THRESHOLD` notifications), cost anomalies and savings recommendations; DEX endpoint health, application performance events, login/SSO events and proactive incident rules (`DEX_PROACTIVE_INCIDENT`) |

Migration tests in `backend/src/test` (`Release2MigrationTest`, `Release3MigrationTest`, `Release7MigrationTest`,
`Release8MigrationTest`, `AdministrationIT`) verify schema migrations and assert the latest schema version (`"13"`).
