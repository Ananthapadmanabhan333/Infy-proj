# SA — Development Roadmap

**SA — Enterprise Operations Platform** · *Intelligent Service Management. Unified Operations.*

Principle: **extend the supplied package, never replace it.** The design system in `frontend/src/styles.css`, the navigation model, page names and the `INC-xxxx` identity are carried forward; the single-file prototype is decomposed into routed pages backed by real APIs.

## Target architecture

```
 Browser (Vercel)                 API (Render, Docker, Java 21)                Data
 ┌──────────────────────┐  HTTPS  ┌──────────────────────────────────┐  JDBC  ┌──────────────┐
 │ React + TS + Vite    │ ──────► │ Spring Boot 3.5                  │ ─────► │ Neon Postgres│
 │ Tailwind, Router     │  JWT    │  security  (JWT, roles, limits)  │  SSL   │ Flyway V1–13 │
 │ TanStack Query       │ ◄────── │  incidents / service desk / ITSM │        └──────────────┘
 │ RHF + Zod, Recharts  │         │  SLA & workflow engines          │        ┌──────────────┐
 └──────────────────────┘         │  Observability / Twin / ML / AI  │ ─────► │ Redis (opt.) │
                                  │  Remediation / SecOps / FinOps   │        └──────────────┘
                                  │  DEX, knowledge, analytics, audit│        ┌──────────────┐
                                  └──────────────────────────────────┘ ─────► │ LLM provider │
                                                                               └──────────────┘
```

## Phases

| # | Phase | Deliverables |
|---|---|---|
| 1 | Inspect | `PROJECT_STATUS.md` baseline, this roadmap. |
| 2 | Architecture & database | Package layout, Flyway `V1` schema (FKs, indexes, constraints, sequences), API contract. |
| 3 | Security | JWT access + rotating refresh tokens (httpOnly cookie), BCrypt, roles `ADMIN / MANAGER / AGENT / EMPLOYEE / AUDITOR`, method security, rate limiting, CORS allow-list, security headers, audit logging. |
| 4 | Incidents & service desk | Priority matrix, rule-based classification, auto-assignment, state machine, duplicate detection + parent incidents, relations, comments, attachments, history; service catalogue, requests, approvals. |
| 5 | SLA & workflows | Business-hours calendar, response/resolution deadlines, pause/resume, at-risk + breach detection, escalation levels, compliance history; event-triggered workflows with conditions, actions, versions, executions, retries, execution limits. |
| 6 | AI & knowledge | LLM provider abstraction (Anthropic implementation; further providers plug in behind `LlmProvider`) with a labelled deterministic fallback; classification, summaries, duplicates, KB recommendations, root cause, NL reporting; human approval for state-changing suggestions; full-text knowledge search. |
| 7 | Frontend | Router, auth, protected/role-aware navigation, every page on live data with loading / error / empty states, forms with RHF + Zod. |
| 8 | Analytics & admin | KPIs, trends, statistical forecast, CSV export; users, departments, SLA policies, business hours, settings, integrations, audit viewer. |
| 9 | Security review & tests | JUnit 5 + Mockito, Spring integration tests on PostgreSQL, Vitest, Playwright (incl. payroll outage scenario). |
| 10 | Deployment config | Multi-stage Dockerfiles, Compose, `render.yaml`, `vercel.json`, GitHub Actions, env templates, Neon guide. |
| 11–14 | Deploy & validate | Neon, Render and Vercel by the account owner ([DEPLOYMENT.md](DEPLOYMENT.md)), then the live verification checklist. |

## Demo data policy

Demo records are created only when `APP_DEMO_SEED=true`. Every seeded row carries `is_demo = true`, demo users use the `@demo.nexus360.io` domain, and the UI marks demo records with a **DEMO** badge. Demo passwords come from `APP_DEMO_PASSWORD`; the production profile refuses to seed without it. Sample telemetry, anomalies, cost spikes, vulnerability waves and endpoint degradation come exclusively from **Simulation Mode** (`simulated = true`), are badged **SIMULATION**, and are excluded from operational analytics by default.

## Status and next steps

Releases 1–4 are complete and verified locally (see [PROJECT_STATUS.md](PROJECT_STATUS.md)). Deployment to Neon, Render and Vercel waits on the account owner ([DEPLOYMENT_STATUS.md](DEPLOYMENT_STATUS.md)).

## Release 2 — reference UI and extended requirements (started 3 Nov 2026)

Specification: [docs/RELEASE2_SPEC.md](docs/RELEASE2_SPEC.md) (nine-screen reference UI, six-role model, new incident
status set, new routes, and module extensions). Release 1 (above) is the base; nothing is rebuilt from scratch.

| # | Phase | Scope | Status |
|---|---|---|---|
| R2.1 | Inspection & spec | Gap analysis against the reference and the master brief → RELEASE2_SPEC.md | ✅ |
| R2.2 | Backend | V3 migration (roles, statuses, categories), RBAC groups, incidents/service desk/SLA/workflow/AI/analytics/KB/runbook/services/notifications/admin extensions, tests | ✅ |
| R2.3 | Frontend foundation | Contract types, hooks, routes + redirects, shell and design system restyled to the reference, live notifications | ✅ |
| R2.4 | Screens | Nine reference screens + services detail, runbooks, users, audit logs, integrations, settings, notifications | ✅ |
| R2.5 | Integration & QA | Full backend, Vitest and Playwright runs against the integrated stack; fixes | ✅ |
| R2.6 | Docs & packaging | API, architecture, deployment, status, ZIP | ✅ |

## Release 3 — SA enterprise ITSM / ITOM (started 4 Nov 2026)

Specification: [docs/RELEASE3_SPEC.md](docs/RELEASE3_SPEC.md). Product renamed to **SA** ("Intelligent Service
Management. Unified Operations."); new modules Problems, Changes, Deployments, Assets, CMDB, Alerts, On-call, Major
Incident command, Post-incident reviews, Status Page, My Work, Employee Portal, Teams, dynamic forms and a multi-stage
approval engine; e-mail delivery, session management, scheduled reports, roles CHANGE_MANAGER and ASSET_MANAGER,
grouped navigation.

| # | Phase | Scope | Status |
|---|---|---|---|
| R3.1 | Inspection & spec | Gap analysis → RELEASE3_SPEC.md | ✅ |
| R3.2 | Foundations (BE-1, V6) + brand/navigation/login (FE-1) | Branding, roles, teams, e-mail outbox, sessions, My Work, portal, status page, scheduled reports; logo set, grouped nav, split-screen login, landing updates, public `/status` | ✅ |
| R3.3 | BE-2 (V7) + FE for foundations | Approval engine v2, form builder, Problems, Changes, Deployments + CI/CD webhooks | ✅ |
| R3.4 | BE-3 (V8) + FE for R3.3 | Assets, CMDB, Alerts + Prometheus/Grafana/CloudWatch adapters | ✅ |
| R3.5 | BE-4 (V9) + FE for R3.4 | On-call, Major incidents, PIR, analytics and AI extensions | ✅ |
| R3.6 | FE for R3.5, integration & QA | Remaining screens, E2E extension, full suite runs | ✅ backend 294/294, Vitest 648/648, Playwright 27/27 (fresh demo DB, 4 Nov 2026) |
| R3.7 | Documentation & packaging | ARCHITECTURE, API_DOCUMENTATION, DATABASE_SCHEMA, DEPLOYMENT, SECURITY, status, ZIP | ✅ |

## Release 4 — Autonomous Enterprise Operations (4 Nov 2026)

Specification: [docs/RELEASE4_SPEC.md](docs/RELEASE4_SPEC.md). Roles `SECURITY_ANALYST` and `FINOPS_ANALYST`, Simulation Mode, SA Observability (OTLP/HTTP JSON metrics/logs/traces, mapping rules, roll-ups, retention, anomaly detectors with evaluation, alert correlation), SA Digital Twin (live dependency graph, business-service tree, failure/change blast-radius simulation, change timeline), SA Predictive Intelligence (L2 logistic regression & Holt linear/seasonal forecasting with time-based splits, calibration, outcome reconciliation and drift detection), SA Agentic AI (6 server-defined agents with read-only user-scoped tools, orchestration, schema-validated `GuardedLlm` + deterministic fallbacks, approval-gated actions, governance & evaluations), SA Autonomous Remediation (allow-listed action templates, policy checks, state machine with reverse rollback, SHA-256 tamper-evident `remediation_audit`), SA Security Operations (security incidents, multi-format vulnerability ingestion, 0–100 risk prioritisation, security correlation), SA FinOps (FOCUS 1.x CSV dry-run/apply, allocation rules, budgets, EWMA/MAD cost anomalies, savings recommendations), and SA Digital Employee Experience (endpoint health, app & login telemetry, 0–100 experience score, proactive incident rules, employee portal device card).

| # | Phase | Scope | Status |
|---|---|---|---|
| R4.P1 | BE-5 (V10) + FE-6 | Roles (`SECURITY_ANALYST`, `FINOPS_ANALYST`), Simulation Mode, SA Observability (`/observability`), SA Digital Twin (`/digital-twin`) | ✅ |
| R4.P2 | BE-6 (V11) + FE-7 | Exclude simulated rows from R1–R3 aggregates, additive `correlationGroupId`, SA Predictive Intelligence (`/predictions`), SA Agentic AI (`/agents`) | ✅ |
| R4.P3 | BE-7 (V12) + FE-8 | SA Autonomous Remediation (`/remediation`, hash-chained `remediation_audit`), SA Security Operations (`/security`, scenario `VULN_WAVE`) | ✅ |
| R4.P4 | BE-8 (V13) + FE-9 | SA FinOps (`/finops`, scenario `COST_SPIKE`), SA Digital Employee Experience (`/dex`, portal device card, scenario `ENDPOINT_DEGRADATION`) | ✅ |
| R4.QA | Integration, Docs & Packaging | Full backend JUnit suite, Vitest, Playwright E2E journeys, updated docs, `SA-Release4.zip` | ✅ |
