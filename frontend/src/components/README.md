# SA UI primitives (Release 2, navigation and brand updated in Release 3)

Reference: the nine-screen reference image and `docs/RELEASE2_SPEC.md` §15. Tokens live in `src/styles.css`
(`@theme` for Tailwind utilities, `:root` custom properties for component classes) and, for charts/SVG,
`PALETTE` in `src/lib/constants.ts` / `CHART`, `SERIES_COLORS`, `PRIORITY_COLORS` in `src/lib/chartTheme.ts`.
Never hard-code new colours: gold `#D6B77A`, champagne `#F1DFBA`, bronze `#96764B`, success `#49B889`,
warning `#E5AE62`, critical `#E56D68`; no blue/purple. Real data only — no mock arrays in components.

## Page layout

```tsx
<PageHeader title="Incidents" subtitle="Manage, track and resolve IT incidents" actions={<button className="gold-btn">…</button>} />
<div className="page-stack">           {/* 14 px vertical rhythm */}
  <Tabs … />
  <div className="kpi-grid">…</div>    {/* 4 → 2 → 1 columns */}
  <Panel title="…">…</Panel>
</div>
```

Grids: use Tailwind (`grid gap-[14px] xl:grid-cols-[1.55fr_1fr]`) — collapse to one column below `lg`/`xl`.
Breakpoints: ≥1280 desktop, 1024–1279 laptop, <1024 sidebar drawer, <600 phone.

Buttons: `gold-btn` (primary, one per header), `subtle-btn`, `date-btn` (outlined), `danger-btn`, `link-btn`,
add `compact` for small. Form controls: `control` (+ `sm`), `FormField.tsx` (`TextField`, `SelectField`, …).
Other classes: `icon-tile` (38 px gold icon square), `list-row`, `chip`, `callout` (`red`/`green`), `detail-grid`,
`toolbar` (search + filters row), `bulk-bar`, `gold-text`/`green-text`/`red-text`/`warning-text`/`muted-text`.

## Primitives

| Component | File | Key props |
|---|---|---|
| `PageHeader` | `PageHeader.tsx` | `title`, `subtitle`, `actions`, `greeting` ("Good morning, Ananta" — use `greetingFor()`), `eyebrow`, `crumbs: {label,to?}[]` (first crumb with `to` gets a back chevron), `goldTitle`, `glow` (CSS radial glow), `children` (badges/meta under the title). Root has class `page-heading` (e2e). `PageHeading` is the Release 1 wrapper (`page`, `description`). |
| `KpiCard` | `KpiCard.tsx` | `icon`, `label`, `value`, `tone` (`gold`·`critical`·`success`·`warning`·`neutral`), `delta` (number → arrow + colour, string → as is, null hides), `deltaUnit` (`'%'`, `'pp'`), `higherIsBetter`, `deltaTone` override, `deltaLabel` (tooltip), `caption`, `to` (card becomes a link), `loading`. `Kpi` = alias. |
| `Panel` / `PanelTitle` | `Panel.tsx` | `title`, `subtitle`, `actions` (right side), `action` + `to`/`onAction` ("View all ›"), `raised`. Labels the section automatically. |
| `Tabs` | `Tabs.tsx` | `tabs: {id,label,count?,hidden?}[]` → "All (1,248)", `value`, `onChange`, `label` (a11y), `inner`, `children` (right side). Arrow keys move. Pair with `useTabParam(allowed, default)` for `?tab=`. |
| `FilterSelect` | `FilterSelect.tsx` | `label` ("Priority", also the empty option), `value`, `onChange(value)`, `options: string[] \| {value,label}[]`, `allLabel`, `formatOption` (e.g. `(v) => STATUS_LABELS[v]`). |
| `SearchInput` | `SearchInput.tsx` | `value`, `onChange(value)`, `placeholder`, `label`, `shortcut`. Clear button + Escape. Debounce/URL-sync with `useSearchInput` (`hooks/useUrlState`). |
| `DataTable` | `DataTable.tsx` | `columns: {key,header,render,sortKey?}[]`, `rows`, `rowKey`, `onRowClick`, `rowLabel`, `sort`/`onSortChange`, `empty`, **selection**: `selected: Set`, `onSelectedChange`, `isSelectable`. Dense `data-table` styles. |
| `StatusBadge` / `PriorityBadge` / `Badge` | `Badge.tsx` | `StatusBadge status` (incident, request, approval, article… labels from constants; unknown values title-cased), `PriorityBadge priority` (solid spec colours), `Badge value label tone`, `RoleBadge`, `DemoBadge`, `BooleanBadge`. |
| `Avatar` / `UserChip` | `Avatar.tsx` | `Avatar name size='xs'\|'sm'\|'md'\|'lg'` (initials, warm tint per name). `UserChip name` = avatar + "Priya R." for table cells (`emptyLabel`). |
| `Pagination` | `Pagination.tsx` | `page` (0-based), `totalPages`, `totalElements`, `size`, `onPageChange`, `label?` → "Showing 1–5 of 1,248" + `1 2 3 4 5 … 250`. `pageWindow()` exported. |
| `Drawer` | `Drawer.tsx` | `open`, `onClose`, `label` (uppercase eyebrow), `title`, `subtitle`, `footer` (sticky actions), `wide`. Focus trap, Escape, scroll lock. |
| `Modal` | `Modal.tsx` | `open`, `onClose`, `title`, `eyebrow`, `description`, `size` (`md`/`lg`/`xl`), `footer`, `dismissible`. |
| `ConfirmDialog` | `ConfirmDialog.tsx` | `open`, `onClose`, `onConfirm(reason)`, `title`, `message`, `confirmLabel`, `tone` (`gold`/`danger`), `reasonLabel`, `reasonRequired`, `pending`, `error`. |
| Toasts | `Toast.tsx` | `const toast = useToast(); toast.success/info/warning/error(message, { action: { label, to \| onClick }, durationMs })`; `error` accepts any thrown API error. |
| `EmptyState` / `ErrorState` / `LoadingState` / `QueryState` | `States.tsx` | `EmptyState title description icon action`; `ErrorState error onRetry title compact` (403/offline aware); `LoadingState variant='table'\|'cards'\|'panel'\|'kpis'\|'inline' rows`; `QueryState query children(data) isEmpty empty`. |
| `Stepper` | `Stepper.tsx` | `steps: (string \| {label,caption})[]`, `current` (index), `complete`. For incidents use `INCIDENT_PROGRESS_STEPS` + `incidentProgressIndex(status)` from constants. |
| `Sparkline` | `Sparkline.tsx` | `data: number[]`, `label` (required a11y text), `width`, `height`, `color`, `fill`, `showLast`. Pure SVG. |
| `StarRating` / `RatingSummary` | `StarRating.tsx` | Display: `value` (avg) + `count` → "★★★★½ 4.8 (212)"; input: add `onChange(1–5)`, `disabled`, `label`. `RatingSummary` = compact single star for lists. |
| `DonutChart` | `DonutChart.tsx` | `data: {label,value,color?,key?}[]`, `centerLabel`, `centerValue`, `size`, `legend`, `ariaLabel`, `empty`. Legend shows count + %. |
| `AiResultCard` / `AiSourceBadge` | `AiResultCard.tsx` | **Every AI output must render inside `AiResultCard result={aiResult}`** (or show `AiSourceBadge`) so the LLM / deterministic-fallback source is always visible. |
| `SlaCountdown` | `SlaCountdown.tsx` | `sla`, `size`, `showIcon` — live ticking countdown coloured by health. |
| `Controls` | `Controls.tsx` | `PeriodSelector` (7/30/90 days), `Toggle` (switch). |

## Shell

`layout/AppShell` (sidebar + top bar + live notification stream; sets the document title `SA | <page>` from the
nav), `layout/Sidebar` (grouped nav from `lib/nav.ts`, open-incident badge, API status, drawer < 1024 px),
`layout/Topbar` (`GlobalSearch` with Ctrl K, `NotificationBell` panel, `ProfileMenu`). "New Incident" is a
page-header action: `const newIncident = useNewIncident(); newIncident.open(prefill?)`.

## Navigation (Release 3)

`NAV_GROUPS` in `lib/nav.ts` defines the six sidebar groups of RELEASE3_SPEC §3 — WORKSPACE, SERVICE MANAGEMENT,
OPERATIONS, INTELLIGENCE, GOVERNANCE, ADMINISTRATION — each item with `label`, `to`, Lucide `icon`, `roles` (a role
group from `lib/constants.ts`), optional `badge`/`match`, and **`available`**.

- **Every visible item must lead to a real page.** Items whose page is not built yet are declared with
  `available: false` and are hidden by `navForRole()`. Since BE-4 **every item is available**: My Work, Employee
  Portal, Status Page and Teams shipped with the Release 3 foundations; Approvals (every signed-in user), Problems,
  Changes and Deployments with BE-2; Alerts, CMDB and Assets with BE-3; Major Incidents (`match` covers `/reviews`)
  and On-call with BE-4.
- **Shipping a module page:** add its route in `App.tsx` (guard it with `rolesFor('/your-path')`, which reads the
  item's `roles`), adjust `roles` if the module needs a narrower group, then flip `available` to `true`. Add the
  detail-route prefixes to `match` if they do not start with `to`. Update `Sidebar.test.tsx` (it asserts the visible
  items per role) — nothing else changes: the sidebar, the document title and the landing preview read the config.
- Groups collapse; the state is remembered per user in `localStorage` (`sa.nav.collapsed.v1.<userId>`, wrapped in
  try/catch) and the group holding the current page always opens when the page is entered. A group with no visible
  item for the role is not rendered.

## Brand (Release 3)

Product name **SA**, subtitle **Enterprise Operations Platform**, tagline **Intelligent Service Management. Unified
Operations.**, AI module **SA AI** — constants in `lib/brand.ts` (`BRAND`, `pageTitle(page)` → `SA | <page>`). Static
logo set in `public/brand/`: `sa-logo.svg` (wordmark for dark backgrounds), `sa-logo-light.svg` (light backgrounds,
dark ink), `sa-logo-dark.svg` (wordmark on its own dark plate), `sa-mark.svg` (square monogram / app icon),
`favicon.svg`, `sa-icon-180/192/512.png` (rendered from `sa-mark.svg`) and `site.webmanifest`. In React use
`layout/BrandMark`: `BrandMark` (monogram tile, also in `BrandLoader`), `BrandWordmark` (`tone="gold" | "ink"`) and
`BrandLockup` (wordmark + subtitle — sidebar, public navbar/footer, auth pages, landing preview). The letterforms
live once in `SA_GLYPH` (`lib/brand.ts`); keep the SVG files in sync when changing them. Technical identifiers
(`com.nexus360`, `nexus_refresh`, `X-Requested-With: nexus360`, `demo.nexus360.io`) intentionally keep their names.

## Data and permissions

- Contract: `lib/types.ts`; hooks: `src/api/*` (one file per area). Role checks: `hasRole(...STAFF)` with the
  groups in `lib/constants.ts` (`ADMINS`, `MANAGERS`, `ADMIN_OR_MANAGER`, `STAFF`, `READERS`, `CONFIG_ADMINS`,
  `WORKFLOW_EDITORS`, `RUNBOOK_EDITORS`, `KB_PUBLISHERS`, `KB_DELETERS`, `MAJOR_INCIDENT`, `SETTINGS`, and the
  Release 3 groups `CHANGE_EDITORS`, `CHANGE_APPROVERS`, `APPROVAL_FLOW_EDITORS`, `DEPLOYMENT_EDITORS`, `ASSET_EDITORS`, `CMDB_EDITORS`,
  `PROBLEM_EDITORS`, `PROBLEM_OWNERS_CLOSE`, `ALERT_RESPONDERS`, `ALERT_CONFIG`, `ONCALL_EDITORS`,
  `MAJOR_INCIDENT_COMMAND`, `PIR_APPROVERS`, `STATUS_PAGE_EDITORS`, `REPORT_SCHEDULERS`, `TEAM_ADMINS`) — never
  literal role lists. `STAFF` includes the Release 3 roles `CHANGE_MANAGER` and `ASSET_MANAGER`. Labels: `ROLE_LABELS`/`roleLabel`, `STATUS_LABELS`, `PRIORITY_LABELS`,
  `TRANSITION_LABELS`/`transitionLabel(from,to)`, `isCancelTransition`, `transitionNeedsReason`.
- Live notifications: `useNotificationStreamState()` → `{ status, pollInterval }` (use `pollInterval` as
  `refetchInterval` for notification queries).

## Operations modules (Release 3 BE-3)

Contract types in `lib/opsTypes.ts`, labels/tones in `lib/ops.ts`, hooks in `api/alerts.ts`, `api/cmdb.ts`, `api/assets.ts`.

| Piece | File | Notes |
|---|---|---|
| `SeverityBadge`, `AlertStatusBadge`, `CiStatusBadge`, `CiTypeBadge`, `CriticalityBadge`, `AssetStatusBadge`, `WarrantyBadge`, `EventTimeline` | `ops/OpsParts.tsx` | Tones from `lib/ops.ts`; `EventTimeline events={TimelineEvent[]} query?` for alert/asset/CI history. Extra CSS in `ops/ops.css` (`chip-toggle`, `kv-list`, `code-block`, `row-invalid`). |
| `CiMultiPicker` / `CiSinglePicker` | `cmdb/CiPicker.tsx` | Search `GET /cmdb/cis?search`; multi = chips (`ciIds`), single accepts `{id, name}` records without a type. |
| `AffectedCis` / `unlinkedCiNames` | `cmdb/AffectedCis.tsx` | Problems/changes: linked `cis` open `/cmdb/:id`, legacy `affectedCis` text stays plain. |
| `DependencyGraph` + `layoutGraph` / `highlightFor` | `cmdb/DependencyGraph.tsx`, `cmdb/graphLayout.ts` | Pure-SVG map of `GET /cmdb/graph`: deterministic layered layout (dependents above dependencies, cycle-safe), pan (drag), zoom (buttons, Ctrl/⌘ + wheel), select = highlight upstream/downstream, double-click/Enter or "Open" opens the CI. |
| `MatchCriteriaEditor` + `matchCriteria.ts` | `alerts/` | Rule matcher editor (severities, source kinds, sources, services, CIs, labels `a\|b` / `*`, title) and `describeMatch()` for read-only rule rows. |


## Incident response modules (Release 3 BE-4)

Contract types in `lib/responseTypes.ts`, labels/tones in `lib/response.ts`, hooks in `api/oncall.ts`,
`api/majorIncidents.ts` (major incidents, reviews, action items), `api/analytics.ts` (`useOperationsMetrics`,
`useMetricDefinitions`, `useSlaRiskOverview`) and `api/ai.ts` (`useAiProblemCorrelation`, `useAiCmdbImpact`,
`useAiRunbookRecommendations`, `useAiSlaRisk`, `useAiTrends`). Routes: `/on-call`, `/on-call/:scheduleId` (`/oncall` redirects),
`/major-incidents`, `/major-incidents/:id`, `/reviews`, `/reviews/:id`.

| Piece | File | Notes |
|---|---|---|
| `ShiftTimeline`, `ShiftList`, `OnCallPersonCard`, `SourceBadge`, `TimeZoneLabel` | `oncall/OnCallParts.tsx` | One row per local day of the schedule's IANA zone, primary + secondary lanes, bars styled by `source` (`data-source`), red "now" marker; CSS in `oncall/oncall.css`. |
| `zonedTimeToUtc`, `weekStartKey`, `layoutShifts`, `formatZoned`, … | `oncall/shiftLayout.ts` | Pure Intl-based zone arithmetic (DST-aware 23/25 h days); form inputs for overrides are entered in the schedule zone (`zonedInputToIso`). |
| `ActionItemsPanel` | `major/ActionItemsPanel.tsx` | Add / assign owner / due date / status (`PUT /action-items/{id}`) for major incident follow-ups and review actions. |
| `ProblemCorrelationPanel`, `CmdbImpactPanel`, `RunbookRecommendationsPanel`, `SlaRiskAiPanel`, `TrendsPanel`, `AiErrorNotice` | `ai/OpsAiPanels.tsx` | Run on demand, always inside `AiResultCard` (source + fallback reason as returned), 429 explained with `Retry-After`; nothing is applied automatically. |
| `OpsMetricsTab`, `SlaRiskTab`, `DefinitionsPanel` | `analytics/OpsMetricsTab.tsx` | Analytics tabs "Ops metrics" and "SLA risk" (labelled statistical, not AI). |
| `CommandCenterBlock` | `dashboard/CommandCenterBlock.tsx` | Rendered only when `DashboardDto.commandCenter` is non-null; links only to in-app routes. |

## Approval flows

Routes `/approvals/flows` (list, READERS; header link on `/approvals`), `/approvals/flows/new` (`APPROVAL_FLOW_EDITORS`) and
`/approvals/flows/:id` (readers get a read-only summary, editors the form). Hooks in `api/approvalFlows.ts`, types `ApprovalFlowDto` /
`StageDef` / `ApproverDef` in `lib/itsmTypes.ts`. `pages/approvals/flowModel.ts` holds the zod schema, draft ↔ `FlowSaveRequest`
conversion and `mapFlowFieldErrors()` (`stages[i].requiredCount` → `stages.i.requiredCount`, `stages[i].approvers` → `stages.i.approvers.root`,
condition / escalation paths, leftovers → "Stage n: …"); `StageEditor.tsx` is one stage card (RHF `useFieldArray` for approvers and rules).
System flows cannot be deleted or deactivated.

Publishing a stakeholder update to the public status page always goes through a `ConfirmDialog` (subscribers are e-mailed).

## Release 4 — autonomous operations & enterprise modules (FE-6 – FE-9)

Contract types in `lib/r4Types.ts` (API_DOCUMENTATION.md "Release 4"), hooks in `api/simulation.ts`, `api/observability.ts`,
`api/twin.ts`, `api/predictions.ts`, `api/agents.ts`, `api/remediation.ts`, `api/security.ts`, `api/finops.ts`, `api/dex.ts`.
Roles `SECURITY_ANALYST` / `FINOPS_ANALYST` are READERS (not STAFF); the Release 4 groups (`OBSERVABILITY_READERS`,
`TELEMETRY_CONFIG`, `TWIN_EDITORS`, `PREDICTION_ADMINS`, `AGENT_USERS`, `AI_GOVERNANCE`, `PLAYBOOK_EDITORS`,
`PLAYBOOK_APPROVERS`, `REMEDIATION_OPERATORS`, `EXECUTION_SYSTEM_ADMINS`, `SECOPS(_READERS)`, `FINOPS(_READERS)`,
`DEX_READERS`, `SIMULATION_ADMINS`) live in `lib/constants.ts`.
Nav groups AUTONOMOUS OPERATIONS and ENTERPRISE: all 36 sidebar modules across all 8 groups are `available: true`; the Docs
page MODULES table lists all available leaves (`availableNavLeaves()`).

| Piece | File | Notes |
|---|---|---|
| `SimulatedBadge` | `Badge.tsx` | "SIMULATION" pill for every record with `simulated: true` (alerts, incidents, deployments, telemetry, groups, timeline, predictions, agent runs, remediation runs, findings, costs, endpoints). |
| `SimulationBanner`, `IncludeSimulatedToggle`, `SimulatedDataNotice` | `sim/SimulationParts.tsx` | Banner in `AppShell` (polls `/simulation/status` every 60 s while signed in; shown when enabled and a run is active or simulated rows exist). The toggle maps to `includeSimulated` (default off, `?sim=1` on the Release 4 pages). |
| Settings → Simulation mode | `pages/settings/SimulationTab.tsx` | SYSTEM_ADMIN: mode switch (confirmation), scenarios start/stop (`BASELINE`, `PAYROLL_DB_SATURATION`, `DEPLOY_REGRESSION`, `VULN_WAVE`, `COST_SPIKE`, `ENDPOINT_DEGRADATION`), runs, simulated rows, typed-confirmation purge; explains `SIMULATION_ALLOWED=false`. |
| Observability | `pages/observability/*` | Tabs overview, metrics (`SeriesChart`: anomaly span + expected-range band), logs, traces (waterfall), services (RED), anomalies (+ detectors, run, evaluate — INSUFFICIENT_DATA shown as such), correlation (link/convert/split/merge/dismiss, `?group=`), sources (TELEMETRY_CONFIG; OTLP token shown once with endpoints and header; prepared adapters labelled), mappings, retention. |
| `layoutTwin`, `TwinGraphView` | `twin/twinLayout.ts`, `twin/TwinGraphView.tsx` | Reuses the CMDB layering and stacks it into business → application → infrastructure bands; health overlays, simulation impact (target/DOWN/DEGRADED + path edges), dashed redundant edges. |
| Digital Twin | `pages/twin/*` | Model (+ node detail, redundancy toggle for TWIN_EDITORS, `?root=` from CI detail), business service tree, simulation (every result labelled "Simulation — no real systems were changed"), change timeline. |
| Predictions | `pages/predictions/PredictionsPage.tsx` | Models & Training (AUC, Brier, precision/recall, 10-bin calibration, MAE/MAPE, coverage, live performance & drift warnings, activate/retire), Incident Risk, Service Health Forecast, Capacity Risk Forecast, Prediction Ledger & Jobs; every prediction shows `howComputed` and `INSUFFICIENT_DATA` when applicable. |
| SA Agents | `pages/agents/AgentsPage.tsx` | Runs & Investigation Timeline (`INVESTIGATE_INCIDENT`, `ASSESS_CHANGE`, `ANSWER_QUESTION`, steps, read-only tool calls, `LLM` vs `FALLBACK` badges, proposed actions → `AI_ACTION` approvals, cancel, feedback), Agent Catalog & Tools, Governance Policies (`AI_GOVERNANCE`), Evaluation Suites & Runs, Governance Dashboard. |
| Remediation | `pages/remediation/RemediationPage.tsx` | Playbooks & Versions (`DRAFT → IN_REVIEW → APPROVED`), Execution Runs & Rollback Timeline (policy checks, audited override for `PLAYBOOK_APPROVERS`, reverse rollback on step failure), Execution Systems & Allow-listed Templates (`EXECUTION_SYSTEM_ADMINS`), Tamper-Evident Audit Chain (`GET /remediation/audit/verify`). |
| Security Operations | `pages/security/SecurityPage.tsx` | Overview & Risk Formula (`0–100`), Security Incidents & Response Tasks (`NEW → CLOSED`), Vulnerability Findings & Ingestion (`GENERIC_JSON`, `SARIF`, `TRIVY_JSON`, `CSV`, token shown once, risk acceptance), Security Correlation Groups, Response Templates. |
| FinOps | `pages/finops/FinOpsPage.tsx` | Cost Overview & Allocation (`INSUFFICIENT_DATA` when 0 rows), Cost Ingestion (FOCUS 1.x / mapped CSV dry-run & apply), Budgets & Forecasts, Cost Anomalies & Idle Detection, Savings Recommendations (Accept → Draft Change Request / Dismiss). |
| Employee Experience | `pages/dex/DexPage.tsx`, `pages/workspace/PortalPage.tsx` | Experience Score & Breakdown (`0.35 × DeviceHealth + 0.25 × AppPerformance + 0.20 × LoginSuccess + 0.20 × TicketSignals`), Endpoints & Device Models, Application & Login Telemetry, Service Experience Analytics, Proactive Rules & Ingestion; employee's own device card on `/portal` (`/dex/me`). |
