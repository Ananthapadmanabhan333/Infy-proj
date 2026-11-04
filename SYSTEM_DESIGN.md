# SA — System Design, Layman's Architecture, Technical Architecture & Workflows

**SA — Enterprise Operations Platform** · *Intelligent Service Management. Unified Operations.*

This document provides four complementary views of the platform:
1. **[Layman's Architecture (§1)](#1-laymans-architecture-how-sa-works-in-plain-english)** — a non-technical guide using real-world analogies so anyone (executives, new hires, stakeholders, auditors) can understand what SA does and how its pieces work together.
2. **[System Design (§2)](#2-system-design--engineering-principles)** — the engineering trade-offs, capacity & concurrency design, database partitioning, cryptographic integrity, AI guardrails, and simulation isolation rules.
3. **[Technical System Architecture (§3)](#3-technical-system-architecture)** — C4-level container, component, security pipeline, and scheduler diagrams across the React 18 SPA, Spring Boot 3.5 / Java 21 API, and PostgreSQL 17 database.
4. **[End-to-End Workflows (§4)](#4-end-to-end-workflows)** — step-by-step sequence and flowchart diagrams for the platform's 8 primary operational journeys.

---

## 1. Layman's Architecture (How SA Works in Plain English)

### 1.1 The Analogy: A Modern Hospital + Air-Traffic Control Tower for Company Technology

Imagine a large company with thousands of employees. Every day, employees rely on laptops, Wi-Fi, single sign-on, payroll software, customer databases, and cloud servers.

Without a unified platform, when the payroll system slows down or a laptop crashes:
- Employees send emails or chat messages that get lost.
- IT engineers look at five different dashboards that don't talk to each other.
- Security teams track software vulnerabilities in spreadsheets.
- Finance teams only find out about a runaway cloud bill at the end of the month.

**SA (Enterprise Operations Platform)** brings all of those teams into **one shared operations center**:

```mermaid
flowchart TB
    subgraph People["👥 Who Uses SA?"]
        Emp["🧑 Employees\n(Report issues, request laptops/access,\ncheck device health & service status)"]
        Ops["🛠️ IT & Reliability Engineers\n(Fix incidents, monitor telemetry,\nrun Digital Twin & Remediation)"]
        Sec["🛡️ Security & FinOps Analysts\n(Track CVEs, security incidents,\ncloud budgets & cost savings)"]
        Mgr["👔 Managers & Auditors\n(Approve risky actions, review SLAs,\nverify tamper-proof audit logs)"]
    end

    subgraph SA["🏛️ SA — Enterprise Operations Platform"]
        direction TB
        FrontDesk["1️⃣ Front Desk & Triage\n(Employee Portal, Service Desk, Incident Intake & Auto-Grouping)"]
        Radar["2️⃣ Live Radar & Digital Twin\n(Observability Metrics/Logs/Traces, Anomaly Detection & Dependency Map)"]
        Brain["3️⃣ Predictive & Agentic Brain\n(ML Breach/Recurrence Models + 6 Specialist AI Agents with Safe Fallbacks)"]
        Hands["4️⃣ Automated Hands\n(Autonomous Remediation Playbooks with Policy Checks & Auto-Rollback)"]
        Shield["5️⃣ Enterprise Shield & Wallet\n(Security Operations, FinOps Cloud Cost Control, Employee Device Experience)"]
        Vault["6️⃣ Governance Vault\n(Multi-Stage Approvals, SLA Clocks, Tamper-Evident SHA-256 Audit Ledger)"]
    end

    People --> SA
    FrontDesk --> Radar --> Brain --> Hands
    Shield --> Vault
    Hands --> Vault
```

### 1.2 The Six Pillars Explained Simply

| Pillar | Real-World Analogy | What It Actually Does in SA |
|---|---|---|
| **1. Front Desk & Triage** (`Workspace` & `Service Management`) | Hospital Emergency Triage & Concierge Desk | Employees request laptops/software (`/catalog`) or report an issue (`/portal`). If 20 employees report *"Payroll is showing error 503"*, SA automatically recognises they are talking about the same problem, groups them under one master incident, raises the priority to **Critical (P1)**, assigns the right team, and starts a countdown clock (SLA). |
| **2. Live Radar & Digital Twin** (`Operations` & `Observability` / `Digital Twin`) | Air-Traffic Radar & 3D Building Blueprint | Servers send live heartbeat signals (metrics, logs, traces) to SA (`/observability`). SA's **Digital Twin** (`/digital-twin`) keeps a live map of which business service depends on which app and database. Before an engineer upgrades a database, they can run a **What-If Simulation** to see which services and how many users would be affected — without touching the real system. |
| **3. Predictive & Agentic Brain** (`Predictions`, `SA Agents`, `SA AI`) | Weather Forecaster + Team of Specialist Consultants | **Predictions** (`/predictions`) looks at past history to calculate the exact probability that an open ticket will breach its deadline or recur within 14 days. **SA Agents** (`/agents`) is a team of 6 specialist investigators (Incident, Root Cause, Change Risk, Knowledge, Infrastructure, Resolution) that read logs, check recent deployments, and propose a fix. **Crucially:** AI agents can *only read* data — they can never change anything on their own without human approval. |
| **4. Automated Hands** (`Remediation`, `Workflows`, `Runbooks`) | Autopilot with an Emergency Parachute | When a known issue happens (like a database running out of connections), **Autonomous Remediation** (`/remediation`) runs an pre-approved, step-by-step playbook. Before running, it checks 7 safety rules (e.g., *"Is there a holiday change freeze?"*, *"How many services could this affect?"*). If step 2 fails, SA **automatically rolls back** step 1 in reverse order and writes every action into a **tamper-proof cryptographic ledger**. |
| **5. Enterprise Shield & Wallet** (`Security Operations`, `FinOps`, `Employee Experience`) | Building Security, Chief Financial Officer & Employee Wellness | **Security Operations** (`/security`) ingests vulnerability scans and ranks them from `0–100` based on whether the server is exposed to the internet and whether hackers have a known exploit. **FinOps** (`/finops`) imports cloud bills, spots sudden spending spikes, tracks monthly budgets, and suggests downsizing idle servers. **Employee Experience (DEX)** (`/dex`) scores every employee laptop from `0–100` (CPU, memory, boot time, app crashes, login success) so IT can fix slow laptops before employees even complain. |
| **6. Governance Vault** (`Approvals`, `SLA Monitor`, `Audit Logs`) | Notary Public & Black-Box Flight Recorder | Risky actions (running a production playbook, accepting a security vulnerability, deploying an emergency change, applying an AI-proposed action) require sign-off in **Approvals** (`/approvals`) — and the person who requested it is never allowed to approve their own request (**Segregation of Duties**). Every action is permanently recorded in **Audit Logs** (`/audit-logs`). |

---

## 2. System Design & Engineering Principles

### 2.1 Core Design Principles (Ground Rules)

1. **Zero Fabricated Metrics**: Every KPI, trend line, SLA percentage, prediction score, FinOps dollar figure, and DEX score is computed from rows in PostgreSQL. When sample counts are too small to compute a statistically valid figure, the API returns `status: "INSUFFICIENT_DATA"` and the UI explains why rather than inventing numbers.
2. **Strict Simulation Isolation (`simulated = true`)**:
   - Synthetic telemetry, faults, vulnerability waves, cost spikes, and endpoint degradations are generated **only** when a `SYSTEM_ADMIN` enables **Simulation Mode** (`/api/simulation/*`), which is itself gated behind `SIMULATION_ALLOWED=true`.
   - Every synthetic row carries `simulated = true` in the database and a visible **SIMULATION** badge in the UI.
   - All Release 1–3 operational aggregates (Command Center KPIs, SLA compliance, MTTA/MTTR, incident trends, scheduled reports, CSV/PDF exports) exclude `simulated = true` rows by default (`WHERE NOT simulated`).
   - Turning Simulation Mode off or calling `DELETE /api/simulation/data` purges simulated records cleanly without touching real or demo (`is_demo`) records.
3. **Honest AI & Deterministic Fallback**:
   - Every AI feature (`SA AI` and `SA Agents`) works in two modes: live LLM (`AnthropicLlmProvider` wrapped in JSON-schema-validating `GuardedLlm`) and deterministic `FALLBACK`.
   - Every response and persisted step records `source: "LLM"` or `source: "FALLBACK"`, and the UI displays a prominent badge (`LLM` vs `Deterministic fallback — not AI-generated`).
   - Agent tools execute strictly **read-only** queries scoped to the requesting user's role and permissions. Any state-mutating recommendation creates a pending `Approval` (`AI_ACTION`) that must be approved by an authorised human role.
4. **Defense-in-Depth Security**:
   - Short-lived access tokens (`15 min`, held in JavaScript memory only, never `localStorage`) paired with rotating `HttpOnly` refresh cookies (`nexus_refresh`) with token-family replay detection.
   - Dual-layer authorisation: URL-level gates in `SecurityConfig.java` (so unauthenticated or unauthorised callers receive `401`/`403` *before* request body validation) plus method-level `@PreAuthorize` expressions using central role constants in `Roles.java`.
   - SSRF protection (`UrlSafety`) on all outbound webhook, Prometheus pull, and execution-system connectors, blocking loopback, link-local, RFC-1918, and cloud metadata (`169.254.169.254`) addresses in production.

### 2.2 Concurrency, Connection-Pool & Scheduler Design

A critical system design constraint in database-backed operational platforms is preventing background workers and asynchronous event listeners from exhausting the JDBC connection pool and deadlocking HTTP request threads.

```mermaid
flowchart LR
    subgraph HTTP["HTTP Request Threads (Virtual Threads)"]
        Req["Incoming API Requests\n(Controllers & Services)"]
    end

    subgraph Workers["Bounded Background Executors"]
        Async["Spring applicationTaskExecutor\n(Core/Max = 4 threads)\nWorkflows & Webhook Deliveries"]
        Corr["CorrelationService Worker\n(Single-thread bounded queue)\nAlert & Anomaly Correlation"]
        AgentExec["AgentRunner Executor\n(Max = 2 concurrent runs)\nMulti-Agent Orchestration"]
        Sched["@Scheduled Jobs\n(PgLocks 360010–360019)\nSLA, Rollups, Detectors, Predictions"]
    end

    subgraph DB["HikariCP Connection Pool (Max = 8)"]
        Pool[("PostgreSQL 17\nConnections 1..8")]
    end

    Req -->|"Short transactional borrows"| Pool
    Async -->|"Capped at 4 < 8"| Pool
    Corr -->|"1 thread (no AFTER_COMMIT inline tx)"| Pool
    AgentExec -->|"<= 2 threads"| Pool
    Sched -->|"pg_try_advisory_xact_lock"| Pool
```

- **Connection Pool Sizing**: HikariCP `maximum-pool-size` defaults to `8` (`DB_POOL_MAX`), while Spring's `@Async` pool (`ASYNC_POOL_SIZE`) is capped at `4`, `AgentRunner` is capped at `2` concurrent runs, and `CorrelationService` runs on `1` dedicated worker thread. Background tasks can therefore never hold all 8 connections and starve HTTP requests.
- **No `REQUIRES_NEW` Inside `AFTER_COMMIT` on Request Threads**: Event listeners triggered after transaction commit hand work off to bounded background queues rather than opening a second database connection on the caller's thread.
- **Cluster-Safe Scheduled Jobs via PostgreSQL Advisory Locks**: Every periodic background job acquires a non-blocking transaction-level advisory lock (`SELECT pg_try_advisory_xact_lock(key)`) so multiple API replicas never duplicate work:

| Lock ID | Job / Engine | Interval | Purpose |
|---|---|---|---|
| `360010` | `SlaEscalationJob` | 60 s | Evaluates business-hours SLA clocks, marks breaches, triggers escalations |
| `360011` | `WorkflowScheduleJob` | 60 s | Triggers cron/interval scheduled workflows |
| `360012` | `EmailOutboxJob` | 30 s | Delivers queued transactional emails via SMTP (or marks `SKIPPED`) |
| `360013` | `ReportScheduleJob` | 60 s | Generates scheduled PDF/CSV analytics reports |
| `360014` | `ObservabilityJobs` (Rollup/Retention/Pull) | 60 s / 1 h | Computes 5m/1h metric rollups, enforces retention, pulls Prometheus targets |
| `360015` | `AnomalyEvaluationJob` | 60 s | Evaluates enabled EWMA / Median-MAD anomaly detectors |
| `360016` | `SimulationJobs` | 15 s | Advances active synthetic simulation scenarios when Simulation Mode is ON |
| `360017` | `PredictionJobs` (Retrain) | Daily | Retrains predictive models on new historical outcomes |
| `360018` | `PredictionJobs` (Refresh) | 5 m | Refreshes active SLA breach, recurrence, and capacity predictions |
| `360019` | `PredictionJobs` (Outcomes) | 15 m | Reconciles served predictions against actual incident/SLA outcomes |

### 2.3 Cryptographic Audit Integrity (`remediation_audit`)

Autonomous remediation actions carry high operational sensitivity. In addition to the general append-only `audit_logs` table, `remediation_audit` implements a **tamper-evident SHA-256 hash chain**:

$$\text{hash}_n = \text{SHA-256}\left(\text{prev\_hash}_{n-1} \;\|\; \text{event\_type}_n \;\|\; \text{run\_id}_n \;\|\; \text{playbook\_id}_n \;\|\; \text{actor\_id}_n \;\|\; \text{canonical\_json(payload)}_n \;\|\; \text{created\_at\_micros}_n\right)$$

- The first record links to `prev_hash = "GENESIS"`.
- A PostgreSQL `BEFORE UPDATE OR DELETE` trigger (`trg_remediation_audit_immutable`) raises an exception at the database layer if any query attempts to modify or delete a row.
- `GET /api/remediation/audit/verify` recomputes every hash from `GENESIS` in id order and reports `{ valid: true, totalRecords, firstBrokenId: null }`.

---

## 3. Technical System Architecture

### 3.1 High-Level Container Architecture

```mermaid
flowchart LR
    subgraph Client["Browser Client (Desktop & 375px Mobile)"]
        SPA["React 18 + TypeScript + Vite 6 SPA\nTailwind v4 · TanStack Query · RHF + Zod · Recharts"]
    end

    subgraph Edge["Edge / Web Tier"]
        Proxy["Vercel Edge / Nginx Reverse Proxy\nSame-origin /api/* rewrite + Strict CSP"]
    end

    subgraph Backend["Application Tier (Java 21 · Spring Boot 3.5)"]
        SecFilter["Security Filter Chain\nRateLimitFilter → JwtAuthFilter → Role Gates → @PreAuthorize"]
        CoreITSM["Core ITSM & ITOM\nIncidents · Major · PIR · Problems · Changes · Releases\nService Desk · Catalogue · Approvals · CMDB · Assets · Alerts · On-Call"]
        AutoOps["Autonomous Operations (Release 4)\nObservability (OTLP) · Anomaly Detection · Alert Correlation\nDigital Twin · Predictive ML · SA Agents · Autonomous Remediation"]
        EntOps["Enterprise Modules (Release 4)\nSecurity Operations (CVEs/SARIF/Trivy) · FinOps (FOCUS 1.x) · DEX"]
        Engines["Background Engines\nBusiness-Hours SLA · Visual Workflows · GuardedLlm · Simulation Engine"]
    end

    subgraph Data["Data & External Tier"]
        PG[("PostgreSQL 17\nFlyway V1–V13\n(~100 tables, FTS + pg_trgm)")]
        Redis[("Redis 7 (Optional)\nShared Rate-Limit Counters")]
        LLM["Anthropic Claude API\n(Optional — deterministic FALLBACK when unset)"]
    end

    SPA <-->|"HTTPS + SSE (/api/notifications/stream)\nMemory JWT + HttpOnly Refresh Cookie"| Proxy
    Proxy <--> SecFilter
    SecFilter --> CoreITSM & AutoOps & EntOps & Engines
    CoreITSM & AutoOps & EntOps & Engines <-->|"HikariCP (JDBC)"| PG
    SecFilter -.->|"Optional"| Redis
    Engines -.->|"Optional"| LLM
```

### 3.2 Backend Domain Package Architecture (`com.nexus360.*`)

```mermaid
flowchart TB
    subgraph SecurityAndCore["Security, Access & Core Infrastructure"]
        sec["security · access · user · team\nJWT, Refresh Rotation, RBAC (11 Roles), Invitations, Sessions"]
        cfg["config · common · events · audit · email · notification\nSecurityConfig, GlobalExceptionHandler, Append-Only Audit, SSE Hub, Outbox"]
    end

    subgraph ITSM["Service Management (ITSM)"]
        inc["incident · major · problem · change · deployment\nIntake Pipeline, Duplicate Scoring, War-Room, PIR, CAB, Freezes, CI/CD"]
        desk["servicedesk · form · approval · sla · knowledge · runbook · workflow\nDynamic Forms, Multi-Stage Approvals, Business-Time SLA, Visual DAG Workflows"]
    end

    subgraph ITOM_AIOps["Observability, Digital Twin & AIOps"]
        obs["observability · alert · oncall\nOTLP Metrics/Logs/Traces, EWMA/MAD Detectors, Topological Correlation, Paging"]
        twin["twin · cmdb · asset\n3-Layer Operational Graph, Blast-Radius Simulator, Change Timeline, Licences"]
    end

    subgraph AutonomousAndEnterprise["Autonomous & Enterprise Operations"]
        pred["prediction · ai · agent\nLogistic/Ridge/Holt Models, 6 Specialist Agents, GuardedLlm, Eval Suites"]
        rem["remediation\nAllow-Listed Templates, 7 Policy Gates, Auto-Rollback, SHA-256 Audit Chain"]
        ent["secops · finops · dex · simulation\nMulti-Format CVE Ingestion, FOCUS 1.x FinOps, DEX 0–100 Score, 6 Scenarios"]
    end

    SecurityAndCore --> ITSM
    ITSM --> ITOM_AIOps
    ITOM_AIOps --> AutonomousAndEnterprise
```

---

## 4. End-to-End Workflows

### 4.1 Workflow 1: Intelligent Incident Intake → Duplicate Grouping → SLA → Major Incident → Post-Incident Review

When multiple employees report the same outage in their own words, SA automatically classifies, prioritises, groups duplicates under a parent incident, starts the business-hours SLA clock, recommends knowledge articles, and drives major-incident command through to a signed-off Post-Incident Review (PIR).

```mermaid
sequenceDiagram
    autonumber
    actor Emp as Employees (Amit, Divya, Fatima)
    participant API as IncidentService & IntakePipeline
    participant Dup as DuplicateScoringEngine
    participant SLA as SlaEngine (Business Hours)
    participant AI as SA AI (GuardedLlm / Fallback)
    actor Mgr as Incident Manager (Priya)
    participant App as ApprovalEngine (PIR)

    Emp->>API: POST /api/incidents ("Payroll portal 503 error", affectedUsers=25)
    API->>API: Classify category (APPLICATION) & compute Impact × Urgency → P1 CRITICAL
    API->>Dup: Score against open incidents on same service/category (token + symptom match)
    Dup-->>API: Match >= threshold → create/link Parent Major Incident + Child Reports
    API->>SLA: Start P1 24x7 SLA clock (Response: 15m, Resolution: 4h)
    API-->>Emp: 201 Created (INC-xxxx, linked to Parent, SLA ticking)

    Mgr->>API: Open Parent Incident → Request AI Recommendation
    API->>AI: Analyse incident + query published Knowledge Base articles
    AI-->>Mgr: Recommend "Payroll application troubleshooting guide" (labelled FALLBACK or LLM)

    Mgr->>API: Escalate → Start Progress → Resolve citing KB Article
    API->>API: Cascade RESOLVED status + resolution notes to all linked child reports
    API->>SLA: Stop SLA clock & record compliance

    Mgr->>API: Complete Post-Incident Review (Root Cause, Timeline, Action Items) & Submit
    API->>App: Create PIR Approval Request (Segregation of Duties enforced)
    App-->>Mgr: Incident Manager / IT Manager approves PIR → Status = APPROVED
```

### 4.2 Workflow 2: Full-Stack Observability → Anomaly Detection → Alert Correlation → Incident

```mermaid
flowchart LR
    A["📡 OTLP / HTTP JSON Push\nPOST /api/observability/otlp/v1/{metrics,logs,traces}\n(or Prometheus Pull Adapter)"] --> B["🗺️ Telemetry Mapping Rules\nMap resource attributes\n(service.name, host.name)\nto CMDB CIs & Business Services"]
    B --> C["📈 Time-Series & Rollups\nStore raw points + 5m/1h rollups\nCompute Service RED metrics\n(Rate, Errors, p95 Duration)"]
    C --> D["🔍 Anomaly Detectors\nEWMA & Median-MAD Z-Score\nRequire N consecutive points\nabove threshold (minSamples gate)"]
    D --> E["🧩 Alert Correlation Engine\nGroup alerts & anomalies within 15m window\nby Same CI (+0.35), Topology (+0.30),\nService (+0.25), Recent Deploy (+0.25)"]
    E --> F["🚨Root-Cause Candidate & Incident\nIdentify deepest dependency CI as root cause\nand auto-create/link IT Incident"]
```

### 4.3 Workflow 3: Digital Twin Blast-Radius & Change Impact Simulation

```mermaid
sequenceDiagram
    autonumber
    actor Eng as Reliability Engineer / Change Manager
    participant UI as Digital Twin UI (/digital-twin)
    participant Twin as TwinSimulationService
    participant DB as PostgreSQL (CMDB + Incidents + SLAs + Changes)

    Eng->>UI: Select Target CI(s) (e.g. "payroll-db-primary") or Change Request + Mode (HARD / DEGRADED)
    UI->>Twin: POST /api/twin/simulations { type: "FAILURE", mode: "HARD", targets: [ciId] }
    Twin->>DB: Load 3-layer dependency graph (INFRASTRUCTURE → APPLICATION → BUSINESS)
    Twin->>Twin: Propagate impact upstream along dependent edges\n(Redundant edge lowers DOWN → DEGRADED)
    Twin->>DB: Compute impacted Business Services, Estimated Affected Users,\nOpen SLAs at Risk, and Scheduled Changes touching the blast radius
    Twin->>DB: Persist read-only simulation record in twin_simulations
    Twin-->>UI: Return SimulationResult (labelled "Simulation — no real systems were changed")
    UI-->>Eng: Highlight impacted nodes/edges on interactive graph + display KPI impact cards
```

### 4.4 Workflow 4: Multi-Agent AI Investigation (`SA Agents`) → Approval-Gated Action

```mermaid
sequenceDiagram
    autonumber
    actor User as Support Agent / Incident Manager
    participant Orch as AgentService & AgentRunner
    participant Tools as AgentTools (Read-Only, User-Scoped)
    participant Guard as GuardedLlm / Deterministic Fallback
    participant Appr as ApprovalService (AI_ACTION)
    actor Approver as IT Manager / Incident Manager

    User->>Orch: POST /api/agents/runs { runType: "INVESTIGATE_INCIDENT", incidentId }
    Note over Orch: Rate limit check (<= 20/hr/user) & bounded executor (<= 2 concurrent)
    par Fan-out Specialist Investigation Steps
        Orch->>Tools: INCIDENT_AGENT reads incident details, comments & SLA state as requesting user
        Orch->>Tools: INFRA_INVESTIGATION_AGENT queries OTLP metrics, anomalies, alerts & twin graph
        Orch->>Tools: KNOWLEDGE_AGENT searches published KB articles & runbooks
    end
    Orch->>Guard: ROOT_CAUSE_AGENT & RESOLUTION_AGENT synthesise findings + validate JSON schema
    Guard-->>Orch: Validated output + Evidence links + Proposed Action (e.g. "Assign to DB Team / Add Note")
    Orch-->>User: Run SUCCEEDED (every step labelled LLM or FALLBACK with tool call audit trail)

    User->>Orch: POST /api/agents/runs/{runId}/actions/{actionId}/propose
    Orch->>Appr: Create Approval Request (type = AI_ACTION, Segregation of Duties enforced)
    Approver->>Appr: POST /api/approvals/{id}/decide { approve: true }
    Appr->>Orch: Callback executes the proposed action as Approver & records audit log
```

### 4.5 Workflow 5: Autonomous Remediation Playbook → 7 Policy Gates → Auto-Rollback & Hash-Chain Verification

```mermaid
flowchart TB
    Start(["▶️ Trigger Remediation Run\n(Manual, Alert Rule, or Agent Recommendation)"]) --> Gates["🛡️ Evaluate 7 Pre-Execution Policy Gates\n1. Active APPROVED Playbook Version\n2. Authorised Operator Role\n3. Linked Incident or Change Record\n4. Blast Radius <= Playbook Max\n5. Target Rate Limit (runs/hour)\n6. No Concurrent Active Run on Target\n7. No Active Change Freeze Window"]
    Gates -->|"Any gate fails\n(unless Playbook Approver overrides with reason)"| Blocked["⛔ Status = BLOCKED\nAppend to SHA-256 remediation_audit"]
    Gates -->|"All 7 gates pass"| Exec["⚙️ Execute Ordered Playbook Steps\nAllow-listed Action Templates only\n(No free-form shell/SQL commands)"]
    Exec -->|"All steps succeed"| Verify["✅ Post-Execution Recovery Verification\nStatus = SUCCEEDED\nPost summary comment to linked Incident"]
    Exec -->|"Step K fails or times out"| Rollback["⏪ Automatic Reverse-Order Rollback\nExecute rollbackTemplateId for Steps K-1 .. 1\nStatus = ROLLED_BACK\nPost rollback notice to linked Incident"]
    Verify & Rollback & Blocked --> Ledger["🔒 Tamper-Evident SHA-256 Hash Chain\nprev_hash + payload hashed into remediation_audit\nProtected by DB trigger against UPDATE/DELETE\nVerifiable via GET /api/remediation/audit/verify"]
```

### 4.6 Workflow 6: Security Operations — Multi-Format Vulnerability Ingestion → Risk Scoring → Acceptance Workflow

```mermaid
flowchart LR
    Ingest["📥 Multi-Format Scanner Ingestion\nPOST /api/security/vulnerabilities/ingest\nFormats: GENERIC_JSON, SARIF 2.1.0, TRIVY_JSON, CSV\n(Auth via JWT or X-SA-Source-Token)"] --> Dedupe["🔄 Deduplicate & Auto-Close\nMatch on (source, CVE, asset, package)\nMatch assetName to CMDB CI criticality\nFull-scan mode auto-closes absent findings as FIXED"]
    Dedupe --> Score["📐 Documented 0–100 Risk Formula\nscore = clamp(0..100, (CVSS/10 * 100)\n× Criticality × Exposure × Exploit × Age / 1.5)\nBands: CRITICAL (3d SLA), HIGH (14d), MEDIUM (30d), LOW (90d)"]
    Score --> Corr["🔗 Security Alert Correlation\nCorrelate CRITICAL/exploited CVEs with\nactive infrastructure alerts & failed logins\nEscalate group to Security Incident (SEC-xxxx)"]
    Score --> Accept["✍️ Governed Risk Acceptance\nPOST /findings/{id}/accept-risk\nCreates VULNERABILITY_ACCEPTANCE approval\nfor IT_MANAGER with expiry date"]
```

### 4.7 Workflow 7: FinOps — FOCUS 1.x CSV Dry-Run → Allocation → Budgets → Savings Change Request

```mermaid
flowchart LR
    CSV["📄 Cloud Billing CSV Upload\nPOST /api/finops/ingest\n(FOCUS 1.x CSV or Mapped CSV)"] --> DryRun{"Dry Run?"}
    DryRun -->|"dryRun = true"| Validate["🔍 Row-Level Validation\nReport invalid dates, negative/NaN costs,\nmissing columns without writing to DB"]
    DryRun -->|"dryRun = false"| Apply["💾 Idempotent Upsert & Allocation\nEvaluate priority-ordered Allocation Rules\n(Tag, Sub-Account, Resource Pattern, Split %)\nUnmatched rows → UNALLOCATED bucket"]
    Apply --> Monitor["📊 Budgets & Anomaly Detection\nTrack monthly spend + linear EOM forecast\nTrigger 50% / 80% / 100% notifications\nDetect EWMA/MAD daily cost spikes"]
    Apply --> Save["💡 Right-Sizing & Idle Recommendations\nJoin cost records with OTLP CPU/Memory telemetry\nAccepting a recommendation creates a governed\nDraft Change Request (CHG-xxxx)"]
```

### 4.8 Workflow 8: Digital Employee Experience (DEX) — Telemetry → Composite Score → Proactive Incident

```mermaid
flowchart LR
    Tel["💻 Endpoint, App & Login Telemetry\nPOST /api/dex/ingest/{endpoints,app-events,login-events}\n(CPU, Memory, Disk, Boot s, 7d Crashes,\nApp Launch/Load ms, SSO Success/Failure)"] --> Calc["🧮 Composite 0–100 DEX Formula\nexperienceScore = round1(\n0.35 × deviceHealth +\n0.25 × appPerformance +\n0.20 × loginSuccess +\n0.20 × ticketSignals)"]
    Calc --> Views["📈 Fleet & Service Analytics (/dex)\nBreakdowns by Department, Device Model,\nApplication Version, and Service Experience\n(Incidents/100 users, FCR %, Reopen %)"]
    Calc --> Portal["🪪 Employee Portal Card (/portal)\nEmployees see their own laptop health\nand DEX score via GET /api/dex/me"]
    Calc --> Proactive["🚨 Opt-In Proactive Incident Rules\nWhen a department's average DEX score\ndrops below threshold, automatically raise\na proactive IT Incident"]
```
