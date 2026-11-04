// SA API contract — mirrors the Spring Boot DTOs in backend/src/main/java/com/nexus360/**/dto.
// All timestamps are ISO-8601 strings in UTC (e.g. "2026-11-03T10:24:00Z"); dates are "YYYY-MM-DD".
// Release 2 additions follow docs/RELEASE2_SPEC.md (section numbers are noted as §n).

// ---------- Enumerations ----------
/** §1 roles. AUDITOR is the Release 1 read-only compliance role (kept). */
export type Role =
  | 'SYSTEM_ADMIN'
  | 'IT_ADMIN'
  | 'IT_MANAGER'
  | 'INCIDENT_MANAGER'
  | 'CHANGE_MANAGER'
  | 'ASSET_MANAGER'
  | 'SUPPORT_AGENT'
  | 'EMPLOYEE'
  | 'AUDITOR'
  /** Release 4: security operations (reads operational data; not incident STAFF). */
  | 'SECURITY_ANALYST'
  /** Release 4: cloud cost management (reads operational data; not incident STAFF). */
  | 'FINOPS_ANALYST';
/** §1 roles an approval can be routed to. */
export type ApproverRole = 'IT_MANAGER' | 'INCIDENT_MANAGER' | 'SYSTEM_ADMIN';
export type Priority = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
export type Level = 'HIGH' | 'MEDIUM' | 'LOW';
/** §2 statuses. Active = NEW, ASSIGNED, IN_PROGRESS, PENDING, REOPENED. Cancel = CLOSED + resolutionCode CANCELLED. */
export type IncidentStatus = 'NEW' | 'ASSIGNED' | 'IN_PROGRESS' | 'PENDING' | 'RESOLVED' | 'CLOSED' | 'REOPENED';
export type Category = 'APPLICATION' | 'NETWORK' | 'HARDWARE' | 'ACCESS' | 'DATABASE' | 'EMAIL' | 'SECURITY' | 'FACILITIES' | 'OTHER';
export type IncidentSource = 'PORTAL' | 'EMAIL' | 'PHONE' | 'MONITORING' | 'API' | 'SCENARIO';
export type ClassificationSource = 'MANUAL' | 'RULES' | 'AI' | 'MONITORING';
export type ResolutionCode =
  | 'FIXED'
  | 'WORKAROUND'
  | 'DUPLICATE'
  | 'NOT_REPRODUCIBLE'
  | 'USER_ERROR'
  | 'CHANGE_ROLLED_BACK'
  | 'OTHER'
  | 'CANCELLED';
export type LinkType = 'RELATED_TO' | 'CAUSED_BY' | 'BLOCKS';
export type ServiceStatus = 'OPERATIONAL' | 'DEGRADED' | 'OUTAGE' | 'MAINTENANCE';
export type SlaClockStatus = 'RUNNING' | 'PAUSED' | 'STOPPED';
export type SlaHealth = 'ON_TRACK' | 'AT_RISK' | 'BREACHED';
export type SlaOutcome = 'MET' | 'MISSED' | 'CANCELLED';
export type Severity = 'INFO' | 'SUCCESS' | 'WARNING' | 'CRITICAL';
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW';

export interface PageResponse<T> {
  content: T[];
  page: number; // zero-based
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ApiError {
  status: number;
  error: string;
  message: string;
  path: string;
  timestamp: string;
  fieldErrors?: { field: string; message: string }[];
}

// ---------- Auth & users ----------
export interface CurrentUser {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  departmentId: number | null;
  departmentName: string | null;
  jobTitle: string | null;
  mustChangePassword: boolean;
  demo: boolean;
  /** §13 organisation settings exposed for the shell (`org.name`). Absent on older sessions. */
  organisation?: { name: string } | null;
}

export interface LoginResponse {
  accessToken: string;
  expiresIn: number; // seconds
  user: CurrentUser;
}

export interface DemoAccount {
  email: string;
  fullName: string;
  role: Role;
  departmentName: string | null;
}

export interface DemoAccountsResponse {
  enabled: boolean;
  accounts: DemoAccount[];
  password: string | null; // only returned when APP_DEMO_SHOW_PASSWORD=true
}

export interface UserDto {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  departmentId: number | null;
  departmentName: string | null;
  jobTitle: string | null;
  phone: string | null;
  active: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  openAssignments: number;
  demo: boolean;
}

export interface UserSummary {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  departmentId: number | null;
  departmentName: string | null;
  openAssignments: number;
}

export interface DepartmentDto {
  id: number;
  code: string;
  name: string;
  description: string | null;
  managerId: number | null;
  managerName: string | null;
  email: string | null;
  memberCount: number;
  demo: boolean;
}

// ---------- Business services (§11) ----------
export interface ServiceRef {
  id: number;
  code: string;
  name: string;
  status: ServiceStatus;
}

export interface ServiceDto {
  id: number;
  code: string;
  name: string;
  description: string | null;
  departmentId: number;
  departmentName: string;
  ownerId: number | null;
  ownerName: string | null;
  tier: 1 | 2 | 3;
  /** Effective status: DEGRADED when a dependency is in OUTAGE (see statusReason). */
  status: ServiceStatus;
  /** e.g. "Dependency Payroll DB is in outage"; null when the status is the service's own. */
  statusReason: string | null;
  availability30d: number; // percent, 0-100, computed from incident downtime
  openIncidents: number;
  criticalOpen: number;
  maintenance: boolean;
  keywords: string[];
  /** R3: team new incidents of the service are routed to. */
  defaultTeamId?: number | null;
  defaultTeamName?: string | null;
  demo: boolean;
}

export interface ServiceDetail extends ServiceDto {
  recentIncidents: IncidentSummary[];
  slaCompliance30d: number | null;
  mttrMinutes30d: number | null;
  dependencies: ServiceRef[];
  dependents: ServiceRef[];
  /** Last 90 days, daily, oldest first. */
  availabilityHistory: { date: string; availability: number }[];
}

export interface BusinessServiceRequest {
  code: string;
  name: string;
  description: string | null;
  departmentId: number;
  ownerId: number | null;
  tier: 1 | 2 | 3;
  keywords: string[];
  maintenance: boolean;
  /** No cycles, no self-reference (validated server side). */
  dependsOnIds?: number[];
  /** R3: null/missing leaves the default team unchanged, 0 clears it. */
  defaultTeamId?: number | null;
}

// ---------- Incidents (§2, §3) ----------
export interface SlaSnapshot {
  policyName: string;
  businessHoursOnly: boolean;
  clockStatus: SlaClockStatus;
  health: SlaHealth;
  outcome: SlaOutcome | null;
  responseDueAt: string;
  respondedAt: string | null;
  responseBreached: boolean;
  resolutionDueAt: string | null; // null while paused
  remainingSeconds: number; // business seconds left on resolution clock; negative when breached
  percentElapsed: number; // 0..100+ of resolution target consumed
  escalationLevel: number; // 0 none, 1 at-risk, 2 breached, 3 severely breached
}

export interface IncidentSummary {
  id: number;
  number: string; // INC-7001
  title: string;
  priority: Priority;
  impact: Level;
  urgency: Level;
  status: IncidentStatus;
  category: Category;
  serviceId: number | null;
  serviceName: string | null;
  departmentId: number | null;
  departmentName: string | null;
  assigneeId: number | null;
  assigneeName: string | null;
  /** R3 assigned team. */
  teamId?: number | null;
  teamName?: string | null;
  requesterId: number | null;
  requesterName: string | null;
  parentId: number | null;
  parentNumber: string | null;
  major: boolean;
  childCount: number;
  createdAt: string;
  updatedAt: string;
  resolvedAt: string | null;
  sla: SlaSnapshot | null;
  demo: boolean;
  /** Release 4: raised from simulated alerts (simulation mode). */
  simulated?: boolean;
}

export interface IncidentLinkDto {
  id: number;
  linkType: LinkType;
  direction: 'OUTGOING' | 'INCOMING';
  incident: Pick<IncidentSummary, 'id' | 'number' | 'title' | 'status' | 'priority'>;
}

export interface EscalationDto {
  level: number;
  reason: string | null;
  actorName: string | null;
  at: string;
}

export interface IncidentDetail extends IncidentSummary {
  description: string | null;
  classificationSource: ClassificationSource;
  classificationConfidence: number | null; // 0..1
  affectedUsers: number;
  source: IncidentSource;
  escalationLevel: number;
  resolutionCode: ResolutionCode | null;
  resolutionNotes: string | null;
  rootCause: string | null;
  resolutionArticle: { id: number; number: string; title: string } | null;
  firstResponseAt: string | null;
  closedAt: string | null;
  resolvedByName: string | null;
  createdByName: string | null;
  duplicateScore: number | null;
  children: IncidentSummary[];
  links: IncidentLinkDto[];
  allowedTransitions: IncidentStatus[];
  /** §3 escalation trail, oldest first. */
  escalations: EscalationDto[];
  /** §3 up to 5 other active incidents on the same service. */
  relatedSameService: IncidentSummary[];
  /** Release 4: latest correlation group linked to this incident. */
  correlationGroupId?: number | null;
}

/** §3 GET /incidents/counts — same filters as /incidents (minus paging). */
export interface IncidentCounts {
  all: number;
  /** Active statuses (NEW, ASSIGNED, IN_PROGRESS, PENDING, REOPENED). */
  open: number;
  inProgress: number;
  /** RESOLVED + CLOSED. */
  resolved: number;
}

export type ActivityKind = 'HISTORY' | 'COMMENT' | 'SLA' | 'ESCALATION' | 'APPROVAL' | 'RUNBOOK';

/** §3 GET /incidents/{id}/activity — merged timeline, newest first. */
export interface ActivityItem {
  kind: ActivityKind;
  at: string;
  actorName: string | null;
  title: string;
  detail: string | null;
}

export interface CreateIncidentRequest {
  title: string; // 5..200
  description?: string; // <= 5000
  serviceId?: number | null;
  category?: Category | null; // omitted => rule-based classification
  impact?: Level | null; // omitted => derived from affectedUsers + service tier
  urgency?: Level | null; // omitted => MEDIUM (or derived from keywords)
  affectedUsers?: number;
  requesterId?: number | null; // agents may log on behalf of an employee
  source?: IncidentSource;
}

export interface CreateIncidentResult {
  incident: IncidentDetail;
  classification: { category: Category; serviceId: number | null; serviceName: string | null; source: ClassificationSource; confidence: number | null };
  duplicate: {
    detected: boolean;
    matchedIncidentNumber: string;
    parentId: number;
    parentNumber: string;
    parentCreated: boolean;
    score: number;
  } | null;
  assignment: { departmentName: string | null; assigneeName: string | null; teamName?: string | null } | null;
}

export interface UpdateIncidentRequest {
  title: string;
  description?: string | null;
  category: Category;
  impact: Level;
  urgency: Level;
  serviceId?: number | null;
  affectedUsers: number;
}

export interface TransitionRequest {
  status: IncidentStatus;
  /** CANCELLED together with status CLOSED cancels a NEW/ASSIGNED incident (reason required). */
  resolutionCode?: ResolutionCode;
  resolutionNotes?: string; // required for RESOLVED
  rootCause?: string;
  resolutionArticleId?: number | null;
  comment?: string; // reason: required for PENDING and for a cancel
}

export interface AssignRequest {
  departmentId?: number | null;
  assigneeId?: number | null;
  /** R3: the department defaults to the team's; an assignee must be a member; a team alone = team queue. */
  teamId?: number | null;
}

export interface CommentDto {
  id: number;
  body: string;
  internal: boolean;
  systemGenerated: boolean;
  authorId: number | null;
  authorName: string;
  createdAt: string;
}

export interface AttachmentDto {
  id: number;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedByName: string;
  createdAt: string;
}

export interface HistoryDto {
  id: number;
  eventType: string; // CREATED, FIELD_CHANGED, STATUS_CHANGED, ASSIGNED, COMMENTED, LINKED, UNLINKED, SLA, WORKFLOW, AI, ESCALATED, ATTACHMENT
  field: string | null;
  oldValue: string | null; // historical rows may hold Release 1 values (OPEN, ON_HOLD, CANCELLED) — title-case unknowns
  newValue: string | null;
  message: string | null;
  actorName: string;
  createdAt: string;
}

export interface DuplicateCandidate {
  incidentId: number;
  number: string;
  title: string;
  status: IncidentStatus;
  priority: Priority;
  serviceName: string | null;
  createdAt: string;
  score: number; // 0..1 similarity
  parentId: number | null;
}

export interface SlaEventDto {
  id: number;
  eventType: 'STARTED' | 'PAUSED' | 'RESUMED' | 'RESPONDED' | 'AT_RISK' | 'BREACHED' | 'ESCALATED' | 'MET' | 'MISSED' | 'RECALCULATED' | 'CANCELLED' | 'SIMULATED';
  details: string | null;
  createdAt: string;
}

export interface SlaDetail extends SlaSnapshot {
  startedAt: string;
  responseTargetMinutes: number;
  resolutionTargetMinutes: number;
  atRiskPercent: number;
  totalPausedSeconds: number;
  events: SlaEventDto[];
}

// ---------- Service desk & catalogue (§4) ----------
/** Seeded catalogue category codes (§4); categories remain configurable, so treat as open-ended. */
export type ServiceDeskCategoryCode = 'HARDWARE' | 'SOFTWARE' | 'NETWORK' | 'ACCESS' | 'ACCOUNT' | 'FACILITIES' | 'GENERAL';

export interface CatalogCategoryDto {
  id: number;
  code: string;
  name: string;
  description: string | null;
  icon: string | null; // lucide icon name, e.g. "Users", "Server"
  itemCount: number;
}

/**
 * A request form field. Release 2 shape `{key,label,type,required,options,placeholder}`; Release 3 (form builder,
 * `lib/itsmTypes.ts` FormFieldDef) adds the types email, multiselect, file, user and optional validation/visibility.
 */
export interface FormField {
  key: string;
  label: string;
  type: 'text' | 'textarea' | 'select' | 'number' | 'date' | 'checkbox' | 'email' | 'multiselect' | 'file' | 'user';
  required: boolean;
  options?: string[] | null;
  placeholder?: string | null;
  helpText?: string | null;
  min?: number | null;
  max?: number | null;
  pattern?: string | null;
  minDate?: string | null;
  maxDate?: string | null;
  showIf?: { field: string; op: 'EQUALS' | 'NOT_EQUALS' | 'IN' | 'CONTAINS' | 'NOT_EMPTY' | 'EMPTY'; value?: string | null } | null;
  accept?: string[] | null;
  roles?: Role[] | null;
}

export interface CatalogItemDto {
  id: number;
  categoryId: number;
  categoryName: string;
  name: string;
  description: string | null;
  requiresApproval: boolean;
  approverRole: ApproverRole | null;
  fulfillmentHours: number;
  fulfillmentDepartmentId: number;
  fulfillmentDepartmentName: string;
  formFields: FormField[];
  active: boolean;
  /** R3 form builder: the form definition (current version) the item uses. */
  formDefinitionId?: number | null;
  formCode?: string | null;
  formVersion?: number | null;
  /** R3 request-type permissions (empty = everyone). */
  allowedRoles?: Role[];
  allowedDepartmentIds?: number[];
  canSubmit?: boolean;
}

/** A stored form value: R3 adds multiselect lists, user `{id, name}` and files `[{id, fileName, …}]`. */
export type FormDataValue = string | number | boolean | string[] | Record<string, unknown> | Record<string, unknown>[] | null;

export type ServiceRequestStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'IN_FULFILLMENT' | 'FULFILLED' | 'CANCELLED';

export interface ServiceRequestDto {
  id: number;
  number: string; // REQ-3001
  catalogItemId: number;
  catalogItemName: string;
  categoryName: string;
  /** §4 catalogue category code (e.g. NETWORK). */
  categoryCode: string;
  title: string;
  details: string | null;
  formData: Record<string, FormDataValue>;
  status: ServiceRequestStatus;
  /** §4 default MEDIUM. Employees may choose LOW–HIGH, staff any. */
  priority: Priority;
  requesterId: number;
  requesterName: string;
  departmentId: number | null;
  departmentName: string | null;
  assigneeId: number | null;
  assigneeName: string | null;
  /** R3 fulfilment team. */
  teamId?: number | null;
  teamName?: string | null;
  dueAt: string | null;
  fulfilledAt: string | null;
  fulfillmentNotes: string | null;
  createdAt: string;
  updatedAt: string;
  approval: ApprovalDto | null;
  commentCount: number;
  demo: boolean;
}

export interface CreateServiceRequest {
  catalogItemId: number;
  title: string;
  details?: string;
  formData: Record<string, unknown>;
  priority?: Priority;
}

/** §4 GET/POST /service-requests/{id}/comments — same shape as incident comments. */
export type ServiceRequestCommentDto = CommentDto;

/** §4 GET /service-requests/{id}/history. */
export interface ServiceRequestHistoryItem {
  at: string;
  actorName: string | null;
  action: string;
  fromValue: string | null;
  toValue: string | null;
  note: string | null;
}

/** §4 GET /service-requests/counts?scope. */
export interface ServiceRequestCounts {
  all: number;
  mine: number;
  pendingApproval: number;
  inFulfillment: number;
  fulfilled: number;
}

/** R3 approval engine v2 adds CHANGE, PIR (post-incident reviews) and WORKFLOW (REQUEST_APPROVAL sign-offs). */
export type ApprovalType = 'SERVICE_REQUEST' | 'RUNBOOK_EXECUTION' | 'RUNBOOK_STEP' | 'AI_ACTION' | 'CHANGE' | 'PIR' | 'WORKFLOW';
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface ApprovalDto {
  id: number;
  type: ApprovalType;
  referenceId: number;
  referenceNumber: string | null;
  title: string;
  details: string | null;
  requestedByName: string | null;
  /** Null for R3 flows without a role approver. */
  approverRole: ApproverRole;
  status: ApprovalStatus;
  decidedByName: string | null;
  decisionComment: string | null;
  decidedAt: string | null;
  createdAt: string;
  payload: Record<string, unknown> | null;
  // Release 3 approval engine v2 (docs/API.md "Approval engine v2"); optional for Release 2 fixtures.
  requestedById?: number | null;
  flowName?: string | null;
  currentStage?: number | null;
  stageCount?: number;
  currentStageName?: string | null;
  dueAt?: string | null;
  overdue?: boolean;
  /** In-app route of the subject (e.g. `/changes/12`). */
  link?: string | null;
  /** The caller may decide an open task of the active stage; `myTaskId` is that task. */
  canDecide?: boolean;
  myTaskId?: number | null;
  pendingWith?: string[];
  demo?: boolean;
}

// ---------- SLA (§5) ----------
export interface SlaPolicyDto {
  id: number;
  name: string;
  priority: Priority;
  responseMinutes: number;
  resolutionMinutes: number;
  businessHoursOnly: boolean;
  calendarId: number | null;
  calendarName: string | null;
  atRiskPercent: number;
  active: boolean;
  description: string | null;
}

export interface CalendarDto {
  id: number;
  name: string;
  timezone: string; // IANA, e.g. "Asia/Kolkata"
  workDays: ('MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY')[];
  startTime: string; // "09:00"
  endTime: string; // "18:00"
  isDefault: boolean;
  holidays: { id: number; date: string; name: string }[];
}

export interface SlaOverviewKpis {
  compliance: number | null; // percent
  breachesInPeriod: number;
  atRiskNow: number;
  avgResolutionMinutes: number | null;
}

export interface SlaOverview {
  days: number;
  activeCount: number;
  onTrackCount: number;
  atRiskCount: number;
  breachedActiveCount: number;
  pausedCount: number;
  metCount: number;
  missedCount: number;
  compliancePercent: number | null; // resolution SLA met / completed in period
  responseCompliancePercent: number | null;
  kpis: SlaOverviewKpis;
  byPriority: { priority: Priority; met: number; missed: number; active: number; compliance: number | null }[];
  byService: { serviceId: number; serviceName: string; met: number; missed: number; atRisk: number; breached: number; compliance: number | null }[];
  byDepartment: { name: string; met: number; missed: number; atRisk: number; compliance: number | null }[];
  trend: { date: string; met: number; missed: number; compliance: number | null }[];
}

// ---------- Workflows (§6) ----------
export type WorkflowTrigger =
  | 'INCIDENT_CREATED'
  | 'INCIDENT_UPDATED'
  | 'INCIDENT_STATUS_CHANGED'
  | 'INCIDENT_ASSIGNED'
  | 'PRIORITY_CHANGED'
  | 'SLA_AT_RISK'
  | 'SLA_BREACHED'
  | 'SERVICE_REQUEST_CREATED'
  | 'SCHEDULED'
  /** R3: a change request was raised (facts changeRisk, changeType, riskScore, …). */
  | 'CHANGE_REQUEST_CREATED'
  /** R3 BE-3: a new or reopened, unsuppressed alert (facts alertSeverity, alertSource, alertName, ciName, ciType, occurrenceCount, …). */
  | 'ALERT_RECEIVED';

export type ConditionField =
  | 'priority'
  | 'status'
  | 'category'
  | 'serviceCode'
  | 'departmentCode'
  | 'assignedTeam'
  | 'assigneeEmail'
  | 'impact'
  | 'urgency'
  | 'major'
  | 'title'
  | 'affectedUsers'
  | 'escalationLevel'
  | 'slaRemainingMinutes'
  | 'source'
  | 'childCount'
  | 'linkedToParent'
  // Release 3 (teams, changes)
  | 'teamCode'
  | 'changeRisk'
  | 'changeType'
  | 'riskScore'
  // Release 3 alerts (ALERT_RECEIVED)
  | 'alertSeverity'
  | 'alertSource'
  | 'alertName'
  | 'ciName'
  | 'ciType'
  | 'occurrenceCount';
export type ConditionOperator = 'EQUALS' | 'NOT_EQUALS' | 'IN' | 'CONTAINS' | 'GREATER_THAN' | 'LESS_THAN';

export interface ConditionRule {
  field: ConditionField;
  operator: ConditionOperator;
  value: string; // IN => comma separated
}

export type WorkflowActionType =
  | 'ASSIGN_DEPARTMENT' // params.departmentCode
  | 'ASSIGN_USER' // params.userEmail
  | 'SET_PRIORITY' // params.priority
  | 'CHANGE_STATUS' // params.status — must be a valid transition
  | 'ADD_COMMENT' // params.message, params.internal ("true"/"false")
  | 'NOTIFY_ROLE' // params.role, params.message
  | 'NOTIFY_DEPARTMENT' // params.departmentCode (optional, defaults to incident department), params.message
  | 'NOTIFY_ASSIGNEE' // params.message
  | 'NOTIFY_USER' // params.userEmail, params.message
  | 'ESCALATE' // params.reason — "Create escalation"
  | 'DECLARE_MAJOR' // no params
  | 'SEND_WEBHOOK' // params.integrationId
  | 'START_RUNBOOK' // params.runbookId — "Trigger approved runbook" (approval gated if the runbook requires it)
  | 'ASSIGN_TEAM' // R3 params.teamCode — the team and its least-loaded member, else the team queue
  | 'REQUEST_APPROVAL' // R3 params.flowCode, title? — approval engine (changes: CAB approval)
  | 'CREATE_INCIDENT'; // R3 BE-3 params.impact?, params.urgency? — ALERT_RECEIVED only: converts the alert into an incident

export interface WorkflowAction {
  type: WorkflowActionType;
  params: Record<string, string>;
}

export interface WorkflowDefinition {
  conditions: { match: 'ALL' | 'ANY'; rules: ConditionRule[] };
  /** "Yes" branch: run when the conditions match. */
  actions: WorkflowAction[];
  /** "No" branch: run when the conditions do not match. Empty = do nothing. */
  elseActions: WorkflowAction[];
  /** Required for the SCHEDULED trigger (intervalMinutes ≥ 15). */
  schedule?: { intervalMinutes: number } | null;
  /** Node positions for the visual builder; stored verbatim, ignored by the engine. */
  layout?: Record<string, { x: number; y: number }> | null;
}

export interface WorkflowDto {
  id: number;
  name: string;
  description: string | null;
  triggerEvent: WorkflowTrigger;
  active: boolean;
  currentVersion: number;
  maxExecutionsPerHour: number;
  maxRetries: number;
  retryBackoffSeconds: number;
  definition: WorkflowDefinition;
  executionsLast24h: number;
  failuresLast24h: number;
  lastExecutedAt: string | null;
  createdByName: string | null;
  updatedAt: string;
  demo: boolean;
}

export interface WorkflowRequest {
  name: string;
  description?: string;
  triggerEvent: WorkflowTrigger;
  active: boolean;
  maxExecutionsPerHour: number;
  maxRetries: number;
  retryBackoffSeconds: number;
  definition: WorkflowDefinition;
  changeNote?: string;
}

export interface WorkflowVersionDto {
  versionNumber: number;
  definition: WorkflowDefinition;
  changeNote: string | null;
  createdByName: string | null;
  createdAt: string;
}

export type ExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'RETRY_SCHEDULED' | 'RATE_LIMITED';

export interface WorkflowExecutionDto {
  id: number;
  workflowId: number;
  workflowName: string;
  workflowVersion: number;
  triggerEvent: WorkflowTrigger;
  incidentId: number | null;
  incidentNumber: string | null;
  /** R3 BE-3: the alert of an ALERT_RECEIVED execution. */
  alertId?: number | null;
  status: ExecutionStatus;
  attempt: number;
  nextAttemptAt: string | null;
  errorMessage: string | null;
  steps: { action: WorkflowActionType; status: 'SUCCEEDED' | 'FAILED' | 'SKIPPED'; message: string; at: string }[];
  startedAt: string;
  finishedAt: string | null;
  /** Which branch ran: YES (conditions matched → actions) or NO (elseActions). */
  branch?: 'YES' | 'NO' | null;
}

export interface WorkflowTestResult {
  matched: boolean;
  ruleResults: { rule: ConditionRule; matched: boolean; actual: string | null }[];
  plannedActions: string[];
  /** Branch whose actions are planned (YES when matched, NO otherwise). */
  branch?: 'YES' | 'NO' | null;
}

export interface WorkflowMeta {
  triggers: WorkflowTrigger[];
  fields: { field: ConditionField; label: string; values: string[] | null }[];
  operators: ConditionOperator[];
  actions: { type: WorkflowActionType; label: string; params: { key: string; label: string; required: boolean; options: string[] | null }[] }[];
}

/** §6 GET /workflows/templates. */
export interface WorkflowTemplate {
  key: string;
  name: string;
  description: string;
  triggerEvent: WorkflowTrigger;
  definition: WorkflowDefinition;
}

/** §7 POST /ai/workflow-suggestion — reviewed in the builder, never saved automatically. */
export interface WorkflowSuggestion {
  name: string;
  description: string;
  triggerEvent: WorkflowTrigger;
  definition: WorkflowDefinition;
  explanation: string;
}

// ---------- Runbooks (§10) ----------
export interface RunbookStep {
  order: number;
  title: string;
  instruction: string;
  /** Sensitive step: completion by a non-approver creates a RUNBOOK_STEP approval. */
  requiresApproval: boolean;
}

export interface RunbookDto {
  id: number;
  title: string;
  description: string | null;
  serviceId: number | null;
  serviceName: string | null;
  /** Linked incident categories (replaces the Release 1 single `category`). */
  categories: Category[];
  prerequisites: string[];
  steps: RunbookStep[];
  requiresApproval: boolean;
  estimatedMinutes: number | null;
  active: boolean;
  authorName: string | null;
  executionCount: number;
  updatedAt: string;
  demo: boolean;
}

export interface RunbookRequest {
  title: string;
  description?: string;
  serviceId?: number | null;
  categories: Category[];
  prerequisites: string[];
  steps: RunbookStep[];
  requiresApproval: boolean;
  estimatedMinutes?: number | null;
  active: boolean;
}

export type RunbookExecutionStatus = 'PENDING_APPROVAL' | 'READY' | 'IN_PROGRESS' | 'COMPLETED' | 'REJECTED' | 'ABORTED';
export type StepApprovalStatus = 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'REJECTED';

export interface RunbookStepState {
  order: number;
  title: string;
  /** True only once a sensitive step's approval is APPROVED. */
  done: boolean;
  notes: string | null;
  completedByName: string | null;
  completedAt: string | null;
  requiresApproval: boolean;
  approvalStatus: StepApprovalStatus;
}

export interface RunbookExecutionDto {
  id: number;
  runbookId: number;
  runbookTitle: string;
  incidentId: number | null;
  incidentNumber: string | null;
  status: RunbookExecutionStatus;
  startedByName: string | null;
  approval: ApprovalDto | null;
  stepStates: RunbookStepState[];
  outcomeNotes: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

// ---------- Knowledge (§9) ----------
export type ArticleType = 'GUIDE' | 'SOLUTION' | 'KNOWN_ERROR' | 'FAQ';
/** DRAFT → IN_REVIEW → PUBLISHED → ARCHIVED. */
export type ArticleStatus = 'DRAFT' | 'IN_REVIEW' | 'PUBLISHED' | 'ARCHIVED';
export type ArticleSort = 'popular' | 'recent' | 'rating';

export interface ArticleSummary {
  id: number;
  number: string; // KB-1001
  title: string;
  summary: string;
  articleType: ArticleType;
  category: Category;
  serviceId: number | null;
  serviceName: string | null;
  tags: string[];
  status: ArticleStatus;
  authorName: string | null;
  viewCount: number;
  /** Back-compat: ratings ≥ 4. */
  helpfulCount: number;
  notHelpfulCount: number;
  /** 1–5, null while unrated. */
  averageRating: number | null;
  ratingCount: number;
  readMinutes: number;
  publishedAt: string | null;
  updatedAt: string;
  score: number | null; // search relevance when searching
  demo: boolean;
}

export interface ArticleDetail extends ArticleSummary {
  content: string; // markdown
  linkedIncidentCount: number;
}

export interface ArticleRequest {
  title: string;
  summary: string;
  content: string;
  articleType: ArticleType;
  category: Category;
  serviceId?: number | null;
  tags: string[];
  /** Publishing goes through submit/publish (§9); staff may only save drafts. */
  status?: ArticleStatus;
  changeNote?: string;
}

/** §9 POST /knowledge/{id}/feedback — one per user per article, updatable. */
export interface ArticleFeedbackRequest {
  rating: 1 | 2 | 3 | 4 | 5;
  comment?: string;
}

/** §9 GET /knowledge/{id}/versions. */
export interface ArticleVersionDto {
  version: number;
  title: string;
  summary: string;
  editorName: string | null;
  changeNote: string | null;
  createdAt: string;
}

export interface KnowledgeStats {
  published: number;
  drafts: number;
  totalViews: number;
  resolutionRate: number | null; // % of incidents resolved in last 30d that cite a KB article
  /** Category tiles: Incidents = SOLUTION + KNOWN_ERROR, Guides = GUIDE, FAQs = FAQ. */
  countsByType: Record<ArticleType, number>;
  /** Category tile "Runbooks". */
  runbookCount: number;
}

// ---------- SA AI (§7) ----------
export type AiSource = 'LLM' | 'FALLBACK';

export interface AiResult<T> {
  source: AiSource;
  provider: string; // "anthropic", "openai", "fallback"
  model: string | null;
  generatedAt: string;
  latencyMs: number;
  notice: string; // human-readable label, always shown in the UI
  fallbackReason: string | null;
  data: T;
}

export interface AiStatus {
  provider: string;
  model: string | null;
  llmConfigured: boolean;
  llmEnabled: boolean;
  fallbackAvailable: boolean;
  lastError: string | null;
}

export interface ClassificationSuggestion {
  category: Category;
  serviceId: number | null;
  serviceName: string | null;
  impact: Level;
  urgency: Level;
  /** The priority recommendation. */
  priority: Priority;
  confidence: number;
  rationale: string;
}

export interface IncidentSummaryAi {
  summary: string;
  keyFacts: string[];
  nextSteps: string[];
}

export interface DuplicateSuggestions {
  candidates: DuplicateCandidate[];
  explanation: string;
}

export interface KnowledgeRecommendations {
  articles: ArticleSummary[];
  explanation: string;
}

export interface RootCauseAnalysis {
  hypotheses: { cause: string; likelihood: 'HIGH' | 'MEDIUM' | 'LOW'; evidence: string[]; suggestedChecks: string[] }[];
}

/** §7 POST /ai/incidents/{id}/resolution — powers the "SA AI Recommendation" panel. */
export interface ResolutionSuggestion {
  likelyCause: string;
  /** 0–100 ("87% Match"). */
  confidence: number;
  steps: string[];
  /** "Apply Resolution Runbook" calls POST /runbooks/{id}/executions; approval-gated runbooks go to approval. */
  runbook: { id: number; title: string; requiresApproval: boolean } | null;
  articles: ArticleSummary[];
}

export interface OperationalInsight {
  title: string;
  detail: string;
  severity: Severity;
  metric: string | null;
  confidence: Confidence;
}

export interface OperationalInsights {
  insights: OperationalInsight[];
  /** Last 30 days, created incidents by hour of day (0–23) in the default calendar timezone. */
  hourly: { hour: number; count: number }[];
}

export interface AskResponse {
  answer: string;
  data: { label: string; value: number }[];
  basis: string; // which records/queries the answer is grounded in
}

export interface AiActionRequest {
  incidentId: number;
  actionType: 'SET_PRIORITY' | 'LINK_DUPLICATE' | 'START_RUNBOOK' | 'ESCALATE';
  params: Record<string, string>;
  rationale: string;
}

/** §7 GET /ai/interactions?size=5 — the current user's recent AI interactions ("Recent Analyses"). */
export interface AiInteractionDto {
  id: number;
  feature: string; // e.g. ROOT_CAUSE, RESOLUTION, ASK, INSIGHTS, WORKFLOW_SUGGESTION
  subject: string | null; // e.g. "INC-7846"
  source: AiSource;
  createdAt: string;
}

// ---------- Notifications (§12) ----------
export type NotificationType =
  | 'INCIDENT_ASSIGNED'
  | 'INCIDENT_COMMENT'
  | 'INCIDENT_RESOLVED'
  | 'SLA_AT_RISK'
  | 'SLA_BREACHED'
  | 'ESCALATION'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_DECIDED'
  | 'WORKFLOW_FAILED'
  | 'REQUEST_UPDATED'
  | 'ANNOUNCEMENT'
  | 'ACCESS_REQUEST'
  | 'PASSWORD_RESET_REQUEST'
  | 'TEAM_QUEUE'
  | 'REPORT_READY'
  // Release 3 module categories (BE-2, BE-3, BE-4)
  | 'APPROVAL_ESCALATED'
  | 'PROBLEM_UPDATED'
  | 'CHANGE_UPDATED'
  | 'DEPLOYMENT_STATUS'
  | 'ALERT_ROUTED'
  | 'ALERT_ESCALATED'
  | 'ASSET_ASSIGNED'
  | 'ONCALL_PAGE'
  | 'ONCALL_SHIFT'
  | 'MAJOR_INCIDENT_UPDATE'
  | 'PIR_UPDATED';

export interface NotificationDto {
  id: number;
  /** Usually a NotificationType; older rows may carry other values. */
  type: NotificationType | string;
  title: string;
  message: string;
  link: string | null; // frontend route, e.g. "/incidents/12"
  severity: Severity;
  read: boolean;
  createdAt: string;
}

export interface NotificationPreference {
  type: NotificationType;
  label: string;
  description: string;
  inApp: boolean;
  /** R3 effective e-mail channel (absent on Release 2 backends). */
  email?: boolean;
  /** R3: true only for INCIDENT_ASSIGNED, APPROVAL_REQUESTED, APPROVAL_DECIDED. */
  emailDefault?: boolean;
}

/** PUT /notifications/preferences body: `{ [type]: enabled }` (in-app, Release 2) or `{ [type]: { inApp?, email? } }` (R3). */
export type NotificationPreferencesUpdate = Partial<Record<NotificationType, boolean | { inApp?: boolean; email?: boolean }>>;

/** Events on GET /api/notifications/stream (SSE). */
export type NotificationStreamEvent = { event: 'notification'; data: NotificationDto } | { event: 'ping'; data: unknown };

// ---------- Integrations ----------
export type IntegrationType = 'WEBHOOK' | 'SLACK' | 'TEAMS' | 'MONITORING_INBOUND';

export interface IntegrationDto {
  id: number;
  name: string;
  type: IntegrationType;
  enabled: boolean;
  endpointUrl: string | null;
  hasSecret: boolean;
  subscribedEvents: string[];
  defaultServiceId: number | null;
  defaultServiceName: string | null;
  lastDeliveryAt: string | null;
  lastDeliveryStatus: 'SUCCESS' | 'FAILED' | null;
  failureCount: number;
  inboundPath: string | null; // e.g. "/api/integrations/inbound/4"
  createdAt: string;
  demo: boolean;
}

export interface IntegrationRequest {
  name: string;
  type: IntegrationType;
  enabled: boolean;
  endpointUrl?: string | null;
  secret?: string | null; // write-only; omitted/empty keeps the existing secret
  subscribedEvents: string[];
  defaultServiceId?: number | null;
}

export interface IntegrationSaveResult {
  integration: IntegrationDto;
  inboundToken: string | null; // only returned once, on create / rotate for MONITORING_INBOUND
}

export interface DeliveryDto {
  id: number;
  eventType: string;
  status: 'SUCCESS' | 'FAILED';
  httpStatus: number | null;
  responseSnippet: string | null;
  durationMs: number | null;
  createdAt: string;
}

// ---------- Audit ----------
export interface AuditLogDto {
  id: number;
  actorEmail: string | null;
  action: string;
  entityType: string | null;
  entityId: string | null;
  details: Record<string, unknown> | null;
  ipAddress: string | null;
  outcome: 'SUCCESS' | 'FAILURE' | 'DENIED';
  createdAt: string;
}

// ---------- Analytics (§8) ----------
/** Every analytics endpoint accepts these (`from`/`to` inclusive ISO dates; `days` for back-compat). */
export interface AnalyticsFilters {
  from?: string;
  to?: string;
  days?: number;
  departmentId?: number | null;
}

export interface TrendPoint {
  date: string;
  created: number;
  resolved: number;
}

export interface ForecastPoint {
  date: string;
  predicted: number;
  lower: number;
  upper: number;
}

export interface DashboardKpis {
  totalIncidents: number;
  openIncidents: number;
  criticalOpen: number;
  createdInPeriod: number;
  resolvedInPeriod: number;
  slaCompliance: number | null;
  mttrMinutes: number | null;
  mttaMinutes: number | null;
  /** % of incidents created in the period that are RESOLVED/CLOSED (not cancelled). */
  resolutionRate: number | null;
  /** % of incidents resolved in the period with at most one assignee and never reopened. */
  firstCallResolution: number | null;
  deltas: {
    created: number | null; // % change vs previous period
    criticalOpen: number | null; // absolute change in critical incidents created
    slaCompliance: number | null; // percentage points
    mttr: number | null; // % change
    /** Percentage points vs previous period, when the API provides it. */
    resolutionRate?: number | null;
    /** Percentage points vs previous period, when the API provides it. */
    firstCallResolution?: number | null;
  };
}

export interface DashboardDto {
  days: number;
  kpis: DashboardKpis;
  trend: TrendPoint[];
  forecast: ForecastPoint[];
  forecastMethod: string;
  distribution: { priority: Priority; count: number }[]; // incidents created in period
  serviceHealth: ServiceDto[];
  activeIncidents: IncidentSummary[];
  myQueue: IncidentSummary[]; // assigned to the current user (agents) or reported by them (employees)
  counts: {
    activeWorkflows: number;
    executionsLast24h: number;
    publishedArticles: number;
    kbResolutionRate: number | null;
    pendingApprovals: number;
    unreadNotifications: number;
    openServiceRequests: number;
  };
  /** R3 BE-4 Command Center block (staff and auditors; `null` for employees and older servers). */
  commandCenter?: import('./responseTypes').CommandCenterBlock | null;
}

export interface ServiceVolumePoint {
  date: string;
  /** Keyed by service name (see `serviceNames` for series order). */
  counts: Record<string, number>;
}

export interface AnalyticsOverview {
  days: number;
  kpis: DashboardKpis;
  trend: TrendPoint[];
  byService: { name: string; created: number; resolved: number; open: number; mttrMinutes: number | null }[];
  byCategory: { category: Category; count: number }[];
  byDepartment: { name: string; open: number; resolved: number; mttrMinutes: number | null; slaCompliance: number | null }[];
  byPriority: { priority: Priority; count: number; mttrMinutes: number | null; slaCompliance: number | null }[];
  backlogAging: { bucket: string; count: number }[];
  topRecurring: { pattern: string; count: number; serviceName: string | null }[];
  agentPerformance: { userId: number; name: string; departmentName: string | null; resolved: number; avgResolutionMinutes: number | null; open: number }[];
  /** Top 5 services by volume, daily — the stacked "Incident Volume by Service" chart. */
  serviceVolume: ServiceVolumePoint[];
  serviceNames: string[];
}

export interface ForecastDto {
  history: { date: string; count: number }[];
  forecast: ForecastPoint[];
  method: string;
  slope: number;
  rSquared: number | null;
  byService: { serviceName: string; last7Days: number; next7Days: number }[];
}

// ---------- Admin (§13) ----------
export interface SettingDto {
  key: string;
  value: string;
  type: 'STRING' | 'NUMBER' | 'BOOLEAN';
  description: string | null;
  updatedAt: string;
}

/** §13 GET/PUT /admin/incident-categories. Codes are the fixed `Category` set. */
export interface IncidentCategoryConfig {
  code: Category;
  name: string;
  description: string | null;
  defaultDepartmentId: number | null;
  defaultDepartmentName: string | null;
  keywords: string[];
  active: boolean;
}

export type IncidentCategoryUpdate = Omit<IncidentCategoryConfig, 'defaultDepartmentName'>;

/** §13 GET /admin/ai. API keys are never accepted or returned. */
export interface AiConfigDto {
  provider: string;
  model: string | null;
  keyConfigured: boolean;
  llmEnabled: boolean;
  rateLimitPerMinute: number;
  timeoutSeconds: number;
}

export type HealthState = 'UP' | 'DOWN' | 'DEGRADED' | 'UNKNOWN' | string;

/** §13 GET /admin/system-health. */
export interface SystemHealthDto {
  status: HealthState;
  version: string;
  uptimeSeconds: number;
  database: { status: HealthState; latencyMs: number | null; schemaVersion: string | null };
  redis: { enabled: boolean; status: HealthState };
  slaScheduler: { enabled: boolean; lastRunAt: string | null };
  workflowEngine: { pendingRetries: number; failedLast24h: number };
  ai: { provider: string; keyConfigured: boolean };
  counts: { users: number; incidents: number; activeIncidents: number };
  /** R3 e-mail outbox. */
  email?: { configured: boolean; status: 'CONFIGURED' | 'NOT_CONFIGURED' | string; pending: number; failedLast24h: number; lastSentAt: string | null };
  /** R3 scheduled reports. */
  reportScheduler?: { enabled: boolean; lastRunAt: string | null };
}

export interface ScenarioResult {
  parentIncidentId: number | null;
  parentNumber: string | null;
  childNumbers: string[];
  steps: { step: number; title: string; description: string; reference: string | null; at: string }[];
}

export interface AnnouncementDto {
  id: number;
  title: string;
  body: string;
  severity: 'INFO' | 'MAINTENANCE' | 'WARNING' | 'CRITICAL';
  serviceId: number | null;
  serviceName: string | null;
  startsAt: string | null;
  endsAt: string | null;
  published: boolean;
  authorName: string | null;
  createdAt: string;
  demo: boolean;
}

export interface AnnouncementRequest {
  title: string;
  body: string;
  severity: AnnouncementDto['severity'];
  serviceId?: number | null;
  startsAt?: string | null;
  endsAt?: string | null;
  published: boolean;
}

export interface SearchResults {
  incidents: IncidentSummary[];
  articles: ArticleSummary[];
  services: ServiceDto[];
  users: UserSummary[];
}

// ---------- Release 3 foundations (docs/API.md "Release 3 foundations") ----------
export type TeamMemberRole = 'LEAD' | 'MEMBER';

export interface TeamMemberDto {
  userId: number;
  fullName: string;
  email: string;
  role: Role;
  jobTitle: string | null;
  memberRole: TeamMemberRole;
  active: boolean;
  openAssignments: number;
  joinedAt: string;
}

export interface TeamDto {
  id: number;
  code: string;
  name: string;
  description: string | null;
  departmentId: number;
  departmentCode: string | null;
  departmentName: string | null;
  leadId: number | null;
  leadName: string | null;
  email: string | null;
  active: boolean;
  departmentDefault: boolean;
  memberCount: number;
  openIncidents: number;
  /** The team queue (active, unassigned incidents). */
  unassignedIncidents: number;
  openRequests: number;
  demo: boolean;
  /** Leads first. */
  members: TeamMemberDto[];
}

export interface TeamRef {
  id: number;
  code: string;
  name: string;
  departmentId: number | null;
  departmentName: string | null;
  memberRole: TeamMemberRole;
}

export interface TeamRequest {
  code: string;
  name: string;
  departmentId: number;
  leadId?: number | null;
  email?: string | null;
  description?: string | null;
  active?: boolean;
  departmentDefault?: boolean;
}

/** A My Work / portal section. */
export interface WorkSection<T> {
  total: number;
  items: T[];
}

export interface MyWorkDto {
  generatedAt: string;
  teams: TeamRef[];
  incidents: WorkSection<IncidentSummary>;
  teamQueue: WorkSection<IncidentSummary>;
  requests: WorkSection<ServiceRequestDto>;
  teamRequests: WorkSection<ServiceRequestDto>;
  approvals: WorkSection<ApprovalDto>;
  /** Sections added by later modules (problems, changes, alerts, onCallShifts, actionItems). */
  [key: string]: unknown;
}

export interface PortalApprovalRef {
  id: number;
  type: ApprovalType | string;
  referenceNumber: string | null;
  title: string;
  approverRole: ApproverRole | string;
  createdAt: string;
}

export type MaintenanceWindowStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export type StatusUpdateStatus = 'INVESTIGATING' | 'IDENTIFIED' | 'MONITORING' | 'RESOLVED';

export interface PortalMaintenance {
  id: number;
  title: string;
  startsAt: string;
  endsAt: string;
  status: MaintenanceWindowStatus;
  components: string[];
}

export interface PortalOverview {
  generatedAt: string;
  openIncidents: WorkSection<IncidentSummary>;
  openRequests: WorkSection<ServiceRequestDto>;
  awaitingApproval: WorkSection<PortalApprovalRef>;
  serviceStatus: {
    overall: ServiceStatus;
    counts: Partial<Record<ServiceStatus, number>>;
    services: { id: number; code: string; name: string; status: ServiceStatus; statusReason: string | null }[];
    maintenance: PortalMaintenance[];
  };
  recommendedArticles: ArticleSummary[];
  [key: string]: unknown;
}

// Status page (internal view and editor)
export interface StatusPageSettingsDto {
  title: string;
  description: string | null;
  publicEnabled: boolean;
  updatedAt: string | null;
}

export interface StatusUptimeDay {
  date: string;
  availability: number | null;
  maintenance: boolean;
}

export interface StatusComponentDto {
  id: number;
  serviceId: number;
  serviceCode: string;
  serviceName: string;
  displayName: string;
  groupName: string | null;
  description: string | null;
  sortOrder: number;
  publicVisible: boolean;
  status: ServiceStatus;
  statusReason: string | null;
  uptime90d: number | null;
  history: StatusUptimeDay[];
  demo: boolean;
}

export interface StatusMaintenanceDto {
  id: number;
  title: string;
  description: string | null;
  startsAt: string;
  endsAt: string;
  /** Effective status (a SCHEDULED window is IN_PROGRESS between start and end). */
  status: MaintenanceWindowStatus;
  storedStatus: MaintenanceWindowStatus;
  components: { id: number; displayName: string; serviceId: number; publicVisible: boolean }[];
  demo: boolean;
}

export interface StatusUpdateDto {
  id: number;
  title: string;
  status: StatusUpdateStatus;
  message: string;
  publicVisible: boolean;
  incidentId: number | null;
  incidentNumber: string | null;
  postedAt: string;
  demo: boolean;
}

export interface StatusPageView {
  settings: StatusPageSettingsDto;
  overall: ServiceStatus;
  components: StatusComponentDto[];
  maintenance: StatusMaintenanceDto[];
  updates: StatusUpdateDto[];
  /** Editors only. */
  subscribers: { confirmed: number; pending: number } | null;
  emailConfigured: boolean;
  generatedAt: string;
}

export interface StatusComponentRequest {
  serviceId: number;
  displayName: string;
  groupName?: string | null;
  description?: string | null;
  sortOrder?: number | null;
  publicVisible?: boolean;
}

export interface MaintenanceRequest {
  title: string;
  description?: string | null;
  startsAt: string;
  endsAt: string;
  componentIds: number[];
  notifySubscribers?: boolean;
}

export interface StatusUpdateRequest {
  title: string;
  status: StatusUpdateStatus;
  message: string;
  incidentId?: number | null;
  publicVisible?: boolean;
}

// Sessions
export interface SessionDto {
  id: number | string;
  device: string | null;
  browser: string | null;
  os: string | null;
  userAgent: string | null;
  ipAddress: string | null;
  lastIpAddress: string | null;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
  rememberMe: boolean;
  current: boolean;
}

// E-mail outbox
export type OutboxStatus = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';

export interface EmailStatusDto {
  configured: boolean;
  fromAddress: string | null;
  pending: number;
  sentLast24h: number;
  failedLast24h: number;
  skippedLast24h: number;
  lastSentAt: string | null;
  lastRunAt: string | null;
}

export interface OutboxItem {
  id: number;
  category: string;
  to: string;
  subject: string;
  status: OutboxStatus;
  attempts: number;
  nextAttemptAt: string | null;
  lastError: string | null;
  sentAt: string | null;
  createdAt: string;
  sensitive: boolean;
  attachmentName: string | null;
}

// Scheduled reports
export type ReportFormat = 'PDF' | 'CSV';
export type ReportFrequency = 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type ReportRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface ReportTypeDto {
  type: string;
  label: string;
  description: string | null;
  available: boolean;
  formats: ReportFormat[];
  filterKeys: string[];
}

export interface ReportRunDto {
  id: number;
  scheduleId: number;
  trigger: 'SCHEDULE' | 'MANUAL';
  scheduledFor: string | null;
  status: ReportRunStatus;
  periodFrom: string | null;
  periodTo: string | null;
  fileName: string | null;
  contentType: string | null;
  sizeBytes: number | null;
  error: string | null;
  emailedTo: number;
  startedAt: string | null;
  finishedAt: string | null;
  demo: boolean;
}

export interface ReportScheduleDto {
  id: number;
  name: string;
  reportType: string;
  reportTypeLabel: string;
  format: ReportFormat;
  frequency: ReportFrequency;
  runHour: number;
  dayOfWeek: number | null;
  dayOfMonth: number | null;
  filters: { departmentId?: number | null } | null;
  recipients: string[];
  ownerId: number;
  ownerName: string | null;
  nextRunAt: string | null;
  lastRunAt: string | null;
  active: boolean;
  demo: boolean;
  lastRun: ReportRunDto | null;
}

export interface ReportScheduleRequest {
  name: string;
  reportType: string;
  format: ReportFormat;
  frequency: ReportFrequency;
  runHour?: number;
  dayOfWeek?: number | null;
  dayOfMonth?: number | null;
  filters?: { departmentId?: number } | null;
  recipients?: string[];
  active?: boolean;
}
