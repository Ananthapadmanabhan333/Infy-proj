import type { CiRef } from './opsTypes';
/**
 * Release 3 BE-2 contract (docs/API.md "Release 3 — approvals, forms, problems, changes, deployments"; Java records in
 * `approval/ApprovalService`, `approval/ApprovalModel`, `form/FormService`, `servicedesk/FormField`, `problem/ProblemDtos`,
 * `change/ChangeDtos`, `change/ChangeRisk`, `deployment/DeploymentDtos`). Instants are ISO strings, LocalDates `YYYY-MM-DD`.
 */
import type { ApprovalDto, Category, IncidentSummary, Level, Priority, Role } from './types';

// ------------------------------------------------------------------ approval engine v2

export type ApprovalSubjectType = 'SERVICE_REQUEST' | 'RUNBOOK_EXECUTION' | 'RUNBOOK_STEP' | 'AI_ACTION' | 'CHANGE' | 'PIR' | 'WORKFLOW';
export type StageMode = 'SEQUENTIAL' | 'PARALLEL';
export type Quorum = 'ANY' | 'ALL' | 'N';
export type ApproverKind = 'USER' | 'ROLE' | 'TEAM' | 'MANAGER';
export type StageStatus = 'WAITING' | 'ACTIVE' | 'APPROVED' | 'REJECTED' | 'SKIPPED' | 'CANCELLED';
export type TaskStatus = 'WAITING' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SKIPPED' | 'CANCELLED';

export interface EscalationDef {
  notifyManager: boolean;
  notifyRole: Role | null;
  autoReject: boolean;
}

export interface StageConditionRule {
  field: string;
  operator: string;
  value: string | null;
}

export interface StageCondition {
  match: 'ALL' | 'ANY';
  rules: StageConditionRule[];
}

/** `ApprovalModel.ApproverDef`: USER → userId, ROLE → role, TEAM → teamId, MANAGER → nothing (the requester's manager). */
export interface ApproverDef {
  kind: ApproverKind;
  userId?: number | null;
  role?: Role | null;
  teamId?: number | null;
}

/** `ApprovalModel.StageDef` (approval-flow editor). */
export interface StageDef {
  name: string;
  mode: StageMode;
  quorum: Quorum;
  /** 1–10, required for quorum N (≤ number of approvers). */
  requiredCount?: number | null;
  approvers: ApproverDef[];
  condition?: StageCondition | null;
  /** 1–720 hours; sets the stage due date. */
  deadlineHours?: number | null;
  /** null = default escalation (IT managers + system admins are notified). */
  escalation?: EscalationDef | null;
}

/** `ApprovalFlowService.FlowDto` (`GET /approval-flows`, `GET /approval-flows/{id}`). */
export interface ApprovalFlowDto {
  id: number;
  code: string;
  name: string;
  description: string | null;
  /** null = usable for any subject type. */
  subjectType: ApprovalSubjectType | null;
  stages: StageDef[];
  active: boolean;
  systemFlow: boolean;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
  /** Display names of USER approvers by user id (older API responses may omit it). */
  userNames?: Record<string, string>;
}

/** `ApprovalFlowService.FlowSaveRequest` (`POST /approval-flows`, `PUT /approval-flows/{id}`; code immutable after create). */
export interface ApprovalFlowSaveRequest {
  code: string;
  name: string;
  description?: string | null;
  subjectType?: ApprovalSubjectType | null;
  stages: StageDef[];
  active?: boolean;
}

export interface ApprovalTaskDto {
  id: number;
  seq: number;
  approverKind: ApproverKind;
  approverUserId: number | null;
  approverUserName: string | null;
  approverRole: Role | null;
  approverTeamId: number | null;
  approverTeamName: string | null;
  approverLabel: string;
  status: TaskStatus;
  decidedById: number | null;
  decidedByName: string | null;
  decidedAt: string | null;
  comment: string | null;
  delegatedFromId: number | null;
  delegatedFromName: string | null;
  delegatedAt: string | null;
  canDecide: boolean;
}

export interface ApprovalStageDto {
  id: number;
  order: number;
  name: string;
  mode: StageMode;
  quorum: Quorum;
  requiredCount: number;
  status: StageStatus;
  condition: StageCondition | null;
  deadlineHours: number | null;
  dueAt: string | null;
  escalation: EscalationDef | null;
  escalatedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  approvedCount: number;
  tasks: ApprovalTaskDto[];
}

export interface ApprovalEventDto {
  id: number;
  type: string;
  stageOrder: number | null;
  taskId: number | null;
  actorName: string | null;
  comment: string | null;
  details: Record<string, unknown> | null;
  at: string;
}

export interface ApprovalDetail {
  approval: ApprovalDto;
  stages: ApprovalStageDto[];
  history: ApprovalEventDto[];
}

export interface TaskInboxItem {
  taskId: number;
  taskStatus: TaskStatus;
  approverLabel: string;
  stageOrder: number;
  stageName: string;
  dueAt: string | null;
  overdue: boolean;
  canDecide: boolean;
  approval: ApprovalDto;
}

export interface InboxCounts {
  pendingTasks: number;
  overdueTasks: number;
  requestedPending: number;
}

export interface DelegationDto {
  id: number;
  delegatorId: number;
  delegatorName: string;
  delegateId: number;
  delegateName: string;
  startsAt: string;
  endsAt: string;
  reason: string | null;
  active: boolean;
  createdAt: string;
}

/** `GET /service-requests/user-options` — user picker (no e-mail addresses). */
export interface UserOption {
  id: number;
  fullName: string;
  departmentName: string | null;
  role: Role;
}

// ------------------------------------------------------------------ dynamic forms

export type FormFieldType = 'text' | 'textarea' | 'number' | 'email' | 'date' | 'select' | 'multiselect' | 'checkbox' | 'file' | 'user';
export type ShowIfOperator = 'EQUALS' | 'NOT_EQUALS' | 'IN' | 'CONTAINS' | 'NOT_EMPTY' | 'EMPTY';

export interface ShowIf {
  field: string;
  op: ShowIfOperator;
  value?: string | null;
}

/** One input of a form definition version (null properties are omitted by the API). */
export interface FormFieldDef {
  key: string;
  label: string;
  type: FormFieldType;
  required: boolean;
  options?: string[] | null;
  placeholder?: string | null;
  helpText?: string | null;
  min?: number | null;
  max?: number | null;
  pattern?: string | null;
  minDate?: string | null;
  maxDate?: string | null;
  showIf?: ShowIf | null;
  accept?: string[] | null;
  roles?: Role[] | null;
}

export interface FormSummary {
  id: number;
  code: string;
  name: string;
  description: string | null;
  template: boolean;
  active: boolean;
  currentVersion: number;
  fieldCount: number;
  catalogItemCount: number;
  demo: boolean;
  updatedAt: string;
}

export interface FormDetail {
  id: number;
  code: string;
  name: string;
  description: string | null;
  template: boolean;
  active: boolean;
  currentVersion: number;
  fields: FormFieldDef[];
  catalogItems: { id: number; name: string; active: boolean }[];
  demo: boolean;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface FormVersionDto {
  version: number;
  fieldCount: number;
  changeNote: string | null;
  createdByName: string | null;
  createdAt: string;
}

export interface FormVersionDetail extends Omit<FormVersionDto, 'fieldCount'> {
  fields: FormFieldDef[];
}

export interface FormRequestBody {
  code: string;
  name: string;
  description?: string | null;
  template?: boolean;
  active?: boolean;
  fields?: FormFieldDef[];
  changeNote?: string | null;
  copyFromId?: number | null;
}

/** `GET /catalog/items/{id}/form`. */
export interface CatalogFormDto {
  catalogItemId: number;
  catalogItemName: string;
  formDefinitionId: number | null;
  formVersion: number | null;
  fields: FormFieldDef[];
  canSubmit: boolean;
}

export interface StagedUpload {
  id: number;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
}

export interface RequestAttachmentDto {
  id: number;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  fieldKey: string | null;
  uploadedByName: string | null;
  createdAt: string;
}

// ------------------------------------------------------------------ problems

export type ProblemStatus = 'NEW' | 'INVESTIGATING' | 'ROOT_CAUSE_IDENTIFIED' | 'KNOWN_ERROR' | 'RESOLVED' | 'CLOSED';
export type ProblemActionType = 'CORRECTIVE' | 'PREVENTIVE';
export type ProblemActionStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';

export interface ServiceRef {
  id: number;
  code: string;
  name: string;
}

export interface ProblemSummary {
  id: number;
  number: string;
  title: string;
  status: ProblemStatus;
  priority: Priority;
  category: Category | null;
  ownerId: number | null;
  ownerName: string | null;
  teamId: number | null;
  teamName: string | null;
  knownError: boolean;
  incidentCount: number;
  openActionCount: number;
  services: ServiceRef[];
  createdAt: string;
  updatedAt: string;
  demo: boolean;
}

export interface ProblemActionDto {
  id: number;
  title: string;
  description: string | null;
  actionType: ProblemActionType;
  ownerId: number | null;
  ownerName: string | null;
  dueDate: string | null;
  status: ProblemActionStatus;
  overdue: boolean;
  completedAt: string | null;
  createdAt: string;
}

export interface HistoryDto {
  at: string;
  actorName: string | null;
  action: string;
  fromValue: string | null;
  toValue: string | null;
  note: string | null;
}

export interface ProblemDetail {
  id: number;
  number: string;
  title: string;
  description: string | null;
  status: ProblemStatus;
  priority: Priority;
  category: Category | null;
  ownerId: number | null;
  ownerName: string | null;
  teamId: number | null;
  teamName: string | null;
  departmentId: number | null;
  departmentName: string | null;
  rootCause: string | null;
  workaround: string | null;
  permanentFix: string | null;
  knownError: boolean;
  knownErrorAt: string | null;
  kbArticleId: number | null;
  kbArticleNumber: string | null;
  kbArticleStatus: string | null;
  affectedCis: string[];
  /** R3 BE-3: CMDB configuration items linked to the problem. */
  cis?: CiRef[];
  closureNote: string | null;
  rootCauseAt: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  createdByName: string | null;
  services: ServiceRef[];
  incidents: IncidentSummary[];
  actions: ProblemActionDto[];
  changes: { id: number; number: string; title: string; status: string; type: string }[];
  allowedTransitions: ProblemStatus[];
  createdAt: string;
  updatedAt: string;
  demo: boolean;
}

export interface ProblemRequestBody {
  title: string;
  description?: string | null;
  priority?: Priority;
  category?: Category | null;
  ownerId?: number | null;
  teamId?: number | null;
  serviceIds?: number[] | null;
  affectedCis?: string[] | null;
  /** R3 BE-3: explicit CMDB links (≤ 30); replace the links and add the CI names to `affectedCis`. */
  ciIds?: number[] | null;
  rootCause?: string | null;
  workaround?: string | null;
  permanentFix?: string | null;
  incidentIds?: number[] | null;
}

export interface ProblemTransitionBody {
  status: ProblemStatus;
  note?: string | null;
  rootCause?: string | null;
  workaround?: string | null;
  permanentFix?: string | null;
  publishKnownError?: boolean;
}

export interface ProblemActionBody {
  title: string;
  description?: string | null;
  actionType?: ProblemActionType;
  ownerId?: number | null;
  dueDate?: string | null;
  status?: ProblemActionStatus;
}

export interface RecurringCluster {
  key: string;
  serviceId: number | null;
  serviceName: string | null;
  category: Category | null;
  size: number;
  cohesion: number;
  firstSeen: string;
  lastSeen: string;
  suggestedTitle: string;
  incidents: { id: number; number: string; title: string; status: string; createdAt: string; similarity: number }[];
}

export interface ProblemAnalytics {
  from: string;
  to: string;
  total: number;
  open: number;
  byStatus: Record<string, number>;
  byPriority: Record<string, number>;
  knownErrors: number;
  createdInPeriod: number;
  resolvedInPeriod: number;
  avgHoursToRootCause: number | null;
  avgHoursToResolve: number | null;
  linkedIncidents: number;
  recurrencesAfterKnownError: number;
  overdueActions: number;
  recurringClusters: number;
  topServices: { serviceId: number; serviceName: string; problems: number; incidents: number }[];
}

export interface RootCauseSuggestion {
  hypotheses: { cause: string; likelihood: 'HIGH' | 'MEDIUM' | 'LOW' | string; evidence: string[]; suggestedChecks: string[] }[];
  commonFactors: string[];
  suggestedWorkaround: string | null;
  incidentsAnalysed: number;
  basis: string;
}

// ------------------------------------------------------------------ changes

export type ChangeType = 'STANDARD' | 'NORMAL' | 'EMERGENCY';
export type ChangeRisk = 'LOW' | 'MEDIUM' | 'HIGH';
export type ChangeStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'ASSESSMENT'
  | 'AWAITING_APPROVAL'
  | 'APPROVED'
  | 'SCHEDULED'
  | 'IMPLEMENTING'
  | 'COMPLETED'
  | 'FAILED'
  | 'ROLLED_BACK'
  | 'REJECTED'
  | 'CANCELLED';
export type ChangeIncidentRelation = 'CAUSED_BY' | 'RESOLVES' | 'RELATED';

export interface RiskFactor {
  key: string;
  label: string;
  points: number;
  source: string;
}

export interface RiskQuestion {
  key: string;
  text: string;
  options: { value: string; label: string; points: number }[];
}

export interface ChangeSummary {
  id: number;
  number: string;
  title: string;
  type: ChangeType;
  status: ChangeStatus;
  risk: ChangeRisk;
  riskScore: number;
  priority: Priority;
  plannedStart: string | null;
  plannedEnd: string | null;
  services: ServiceRef[];
  assigneeId: number | null;
  assigneeName: string | null;
  teamId: number | null;
  teamName: string | null;
  requesterId: number | null;
  requesterName: string | null;
  approvalId: number | null;
  approvalStatus: string | null;
  pirRequired: boolean;
  pirDone: boolean;
  createdAt: string;
  updatedAt: string;
  demo: boolean;
}

export interface ChangeConflict {
  changeId: number;
  number: string;
  title: string;
  status: ChangeStatus;
  plannedStart: string | null;
  plannedEnd: string | null;
  services: string[];
  cis: string[];
}

export interface LinkedIncident {
  relation: ChangeIncidentRelation;
  incident: IncidentSummary;
}

export interface DeploymentRef {
  id: number;
  number: string;
  environment: string;
  version: string;
  status: string;
  startedAt: string;
}

export interface ChangeDetail {
  id: number;
  number: string;
  title: string;
  description: string | null;
  type: ChangeType;
  status: ChangeStatus;
  category: Category | null;
  priority: Priority;
  risk: ChangeRisk;
  riskScore: number;
  riskAnswers: Record<string, string>;
  riskFactors: RiskFactor[];
  riskAssessedAt: string | null;
  impact: Level | null;
  affectedUsers: number;
  affectedCis: string[];
  /** R3 BE-3: CMDB configuration items linked to the change. */
  cis?: CiRef[];
  justification: string | null;
  implementationPlan: string | null;
  rollbackPlan: string | null;
  testPlan: string | null;
  plannedStart: string | null;
  plannedEnd: string | null;
  actualStart: string | null;
  actualEnd: string | null;
  services: ServiceRef[];
  requesterId: number | null;
  requesterName: string | null;
  assigneeId: number | null;
  assigneeName: string | null;
  teamId: number | null;
  teamName: string | null;
  templateId: number | null;
  templateName: string | null;
  approval: ApprovalDto | null;
  problemId: number | null;
  problemNumber: string | null;
  outcomeNotes: string | null;
  pirRequired: boolean;
  pirNotes: string | null;
  pirCompletedAt: string | null;
  pirByName: string | null;
  conflictOverrideReason: string | null;
  submittedAt: string | null;
  approvedAt: string | null;
  closedAt: string | null;
  conflicts: ChangeConflict[];
  freezeWindows: { id: number; name: string; startsAt: string; endsAt: string }[];
  linkedIncidents: LinkedIncident[];
  deployments: DeploymentRef[];
  allowedTransitions: ChangeStatus[];
  createdAt: string;
  updatedAt: string;
  demo: boolean;
}

export interface ChangeRequestBody {
  title?: string | null;
  description?: string | null;
  type?: ChangeType;
  category?: Category | null;
  priority?: Priority;
  impact?: Level | null;
  affectedUsers?: number | null;
  affectedCis?: string[] | null;
  /** R3 BE-3: explicit CMDB links (≤ 30). */
  ciIds?: number[] | null;
  justification?: string | null;
  implementationPlan?: string | null;
  rollbackPlan?: string | null;
  testPlan?: string | null;
  plannedStart?: string | null;
  plannedEnd?: string | null;
  serviceIds?: number[] | null;
  assigneeId?: number | null;
  teamId?: number | null;
  templateId?: number | null;
  problemId?: number | null;
  riskAnswers?: Record<string, string> | null;
}

export interface ChangeTransitionBody {
  status: ChangeStatus;
  reason?: string | null;
  plannedStart?: string | null;
  plannedEnd?: string | null;
  outcomeNotes?: string | null;
  riskAnswers?: Record<string, string> | null;
  overrideConflicts?: boolean;
  overrideReason?: string | null;
}

export interface ChangeTemplateDto {
  id: number;
  code: string;
  name: string;
  description: string | null;
  category: Category | null;
  implementationPlan: string;
  rollbackPlan: string;
  testPlan: string | null;
  defaultDurationMinutes: number;
  serviceIds: number[];
  active: boolean;
  demo: boolean;
}

export interface FreezeDto {
  id: number;
  name: string;
  reason: string | null;
  startsAt: string;
  endsAt: string;
  serviceIds: number[];
  active: boolean;
  demo: boolean;
}

export interface CalendarEntry {
  id: number;
  number: string;
  title: string;
  type: ChangeType;
  status: ChangeStatus;
  risk: ChangeRisk;
  start: string | null;
  end: string | null;
  actualStart: string | null;
  actualEnd: string | null;
  services: ServiceRef[];
  conflictsWith: string[];
  inFreeze: boolean;
}

export interface ChangeCalendar {
  from: string;
  to: string;
  entries: CalendarEntry[];
  freezeWindows: FreezeDto[];
  conflicts: { changeA: string; changeB: string; services: string[]; cis: string[]; overlapStart: string; overlapEnd: string }[];
}

export interface ChangeCorrelation {
  windowStart: string | null;
  windowEnd: string | null;
  hours: number;
  linked: LinkedIncident[];
  correlated: { incident: IncidentSummary; minutesAfterStart: number; linked: boolean }[];
}

export interface ChangeMetrics {
  from: string;
  to: string;
  created: number;
  byType: Record<string, number>;
  byStatus: Record<string, number>;
  byRisk: Record<string, number>;
  completed: number;
  failed: number;
  rolledBack: number;
  successRate: number | null;
  failureRate: number | null;
  rollbackRate: number | null;
  emergencyRate: number | null;
  avgApprovalHours: number | null;
  avgLeadTimeHours: number | null;
  onTimeRate: number | null;
  changesFollowedByIncidents: number;
  reviewsOutstanding: number;
}

export interface RiskAnalysis {
  riskLevel: ChangeRisk;
  score: number;
  factors: RiskFactor[];
  recommendations: string[];
  similarChanges: { number: string; title: string; status: ChangeStatus; risk: ChangeRisk; closedAt: string | null }[];
  basis: string;
}

// ------------------------------------------------------------------ deployments and releases

export type DeploymentEnvironment = 'DEV' | 'TEST' | 'STAGING' | 'PRODUCTION';
export type DeploymentStatus = 'IN_PROGRESS' | 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK';
export type DeploymentSourceKind = 'MANUAL' | 'GITHUB' | 'GITLAB' | 'GENERIC';
export type WebhookProvider = 'GITHUB' | 'GITLAB' | 'GENERIC';
export type ReleaseStatus = 'PLANNED' | 'IN_PROGRESS' | 'DEPLOYED' | 'FAILED' | 'CANCELLED';

export interface DeploymentDto {
  id: number;
  number: string;
  releaseId: number | null;
  releaseNumber: string | null;
  releaseName: string | null;
  changeId: number | null;
  changeNumber: string | null;
  serviceId: number;
  serviceName: string;
  environment: DeploymentEnvironment;
  version: string;
  commitSha: string | null;
  pipelineUrl: string | null;
  status: DeploymentStatus;
  source: DeploymentSourceKind;
  sourceId: number | null;
  sourceName: string | null;
  externalId: string | null;
  actorName: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationSeconds: number | null;
  rollbackOfId: number | null;
  rollbackOfNumber: string | null;
  rolledBackAt: string | null;
  rollbackReason: string | null;
  notes: string | null;
  demo: boolean;
  /** Release 4: recorded by a simulation scenario. */
  simulated?: boolean;
  createdAt: string;
}

export interface DeploymentDetail {
  deployment: DeploymentDto;
  risk: { level: string; score: number; factors: { key: string; label: string; points: number }[]; basis: string };
  correlatedIncidents: { incident: IncidentSummary; minutesAfterStart: number }[];
  correlationHours: number;
  rollbacks: DeploymentDto[];
}

export interface DeploymentRequestBody {
  serviceId: number;
  environment: DeploymentEnvironment;
  version: string;
  commitSha?: string | null;
  pipelineUrl?: string | null;
  status?: DeploymentStatus;
  releaseId?: number | null;
  changeId?: number | null;
  notes?: string | null;
  startedAt?: string | null;
}

export interface ReleaseDto {
  id: number;
  number: string;
  name: string;
  version: string;
  description: string | null;
  status: ReleaseStatus;
  serviceId: number | null;
  serviceName: string | null;
  changeId: number | null;
  changeNumber: string | null;
  ownerId: number | null;
  ownerName: string | null;
  targetAt: string | null;
  deploymentCount: number;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ReleaseRequestBody {
  name: string;
  version: string;
  description?: string | null;
  status?: ReleaseStatus;
  serviceId?: number | null;
  changeId?: number | null;
  targetAt?: string | null;
}

export interface DeploymentSourceDto {
  id: number;
  name: string;
  provider: WebhookProvider;
  serviceId: number;
  serviceName: string;
  defaultEnvironment: DeploymentEnvironment | null;
  active: boolean;
  webhookPath: string;
  lastDeliveryAt: string | null;
  demo: boolean;
  createdAt: string;
}

export interface SourceSaveResult {
  source: DeploymentSourceDto;
  secret: string | null;
}

export interface WebhookDeliveryDto {
  id: number;
  deliveryId: string | null;
  eventType: string | null;
  outcome: 'PROCESSED' | 'IGNORED' | 'FAILED';
  message: string | null;
  deploymentId: number | null;
  receivedAt: string;
}

export interface DeploymentCalendar {
  from: string;
  to: string;
  days: { date: string; succeeded: number; failed: number; inProgress: number; rolledBack: number; deployments: DeploymentDto[] }[];
}

export interface DeploymentMetrics {
  from: string;
  to: string;
  total: number;
  byEnvironment: Record<string, number>;
  byStatus: Record<string, number>;
  successRate: number | null;
  failureRate: number | null;
  rollbackRate: number | null;
  avgDurationMinutes: number | null;
  productionWithoutChange: number;
  followedByIncidents: number;
}
