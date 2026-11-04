/**
 * Release 3 BE-4 contract (docs/API.md "Release 3 — on-call, major incidents, PIR, analytics, AI"): on-call schedules,
 * major incident command, post-incident reviews, operations analytics and the SA AI operations features.
 * Field names mirror the Java records in `com.nexus360.{oncall,major,analytics,ai}`.
 */
import type { IncidentStatus, IncidentSummary, Priority } from './types';

// =================================================================== on-call

export type RotationType = 'DAILY' | 'WEEKLY' | 'CUSTOM';
export type OnCallLayer = 'PRIMARY' | 'SECONDARY';
export type AssignmentSource = 'ROTATION' | 'OVERRIDE' | 'HOLIDAY_COVER' | 'AVAILABILITY';
export type OverrideStatus = 'OFFERED' | 'ACTIVE' | 'DECLINED' | 'CANCELLED';
export type EscalationTarget = 'PRIMARY' | 'SECONDARY' | 'MANAGER' | 'USER';
export type AlertSeverityName = 'CRITICAL' | 'MAJOR' | 'MINOR' | 'WARNING' | 'INFO';

export interface OnCallPerson {
  userId: number;
  name: string;
  email: string | null;
  phone: string | null;
  source: AssignmentSource;
  since: string | null;
  until: string | null;
  scheduledUserId: number | null;
  scheduledUserName: string | null;
  holiday: boolean;
}

export interface NowEntry {
  scheduleId: number;
  scheduleName: string;
  teamId: number;
  teamCode: string | null;
  teamName: string | null;
  timeZone: string;
  at: string;
  primary: OnCallPerson | null;
  secondary: OnCallPerson | null;
  escalationPolicyId: number | null;
  escalationPolicyName: string | null;
}

export interface ScheduleSummary {
  id: number;
  name: string;
  teamId: number;
  teamCode: string | null;
  teamName: string | null;
  timeZone: string;
  rotationType: RotationType;
  handoffTime: string | null;
  handoffDay: number | null;
  shiftHours: number | null;
  rotationStart: string;
  primaryCount: number;
  secondaryCount: number;
  escalationPolicyId: number | null;
  escalationPolicyName: string | null;
  active: boolean;
  demo: boolean;
  primaryNow: OnCallPerson | null;
  secondaryNow: OnCallPerson | null;
}

export interface ParticipantDto {
  position: number;
  userId: number;
  userName: string;
  email: string | null;
  active: boolean;
  availableNow: boolean;
}

export interface OverrideDto {
  id: number;
  scheduleId: number;
  scheduleName: string;
  layer: OnCallLayer;
  userId: number | null;
  userName: string | null;
  originalUserId: number | null;
  originalUserName: string | null;
  startsAt: string;
  endsAt: string;
  reason: string | null;
  status: OverrideStatus;
  createdByName: string | null;
  takenAt: string | null;
  decidedNote: string | null;
  canTake: boolean;
  demo: boolean;
  createdAt: string;
}

export interface HandoverDto {
  id: number;
  scheduleId: number;
  authorId: number | null;
  authorName: string | null;
  toUserId: number | null;
  toUserName: string | null;
  shiftStart: string | null;
  shiftEnd: string | null;
  body: string;
  demo: boolean;
  createdAt: string;
}

export interface EscalationLevelDto {
  level: number;
  delayMinutes: number;
  minutesFromStart: number;
  target: EscalationTarget;
  userId: number | null;
  userName: string | null;
  label: string;
}

export interface PolicyDto {
  id: number;
  name: string;
  description: string | null;
  teamId: number | null;
  teamName: string | null;
  minSeverity: AlertSeverityName | null;
  levels: EscalationLevelDto[];
  active: boolean;
  scheduleCount: number;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ScheduleDetail {
  schedule: ScheduleSummary;
  description: string | null;
  primary: ParticipantDto[];
  secondary: ParticipantDto[];
  holidayCalendarId: number | null;
  holidayCalendarName: string | null;
  holidayCoverUserId: number | null;
  holidayCoverUserName: string | null;
  upcomingHolidays: { date: string; name: string }[];
  overrides: OverrideDto[];
  handovers: HandoverDto[];
  escalationPolicy: PolicyDto | null;
  canEdit: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ShiftDto {
  layer: OnCallLayer;
  userId: number | null;
  userName: string | null;
  start: string;
  end: string;
  source: AssignmentSource;
  scheduledUserId: number | null;
  scheduledUserName: string | null;
  holiday: boolean;
  overrideId: number | null;
}

export interface ShiftsResponse {
  scheduleId: number;
  timeZone: string;
  from: string;
  to: string;
  primary: ShiftDto[];
  secondary: ShiftDto[];
}

export interface MyShift {
  scheduleId: number;
  scheduleName: string;
  teamId: number | null;
  teamName: string | null;
  layer: OnCallLayer;
  start: string;
  end: string;
  source: AssignmentSource;
  current: boolean;
}

export interface UnavailabilityDto {
  id: number;
  userId: number;
  userName: string | null;
  startsAt: string;
  endsAt: string;
  reason: string | null;
  demo: boolean;
  createdAt: string;
}

export interface PageDto {
  id: number;
  alertId: number;
  alertNumber: string;
  alertTitle: string;
  severity: string;
  alertStatus: string;
  level: number;
  target: EscalationTarget;
  userId: number | null;
  userName: string | null;
  scheduleId: number | null;
  scheduleName: string | null;
  pagedAt: string;
  acknowledgedAt: string | null;
  ackMinutes: number | null;
  demo: boolean;
}

export interface ScheduleRequest {
  teamId: number;
  name: string;
  description?: string | null;
  timeZone: string;
  rotationType: RotationType;
  handoffTime?: string | null;
  handoffDay?: number | null;
  shiftHours?: number | null;
  rotationStart: string;
  primaryUserIds: number[];
  secondaryUserIds?: number[];
  escalationPolicyId?: number | null;
  holidayCalendarId?: number | null;
  holidayCoverUserId?: number | null;
  active?: boolean;
}

export interface OverrideRequest {
  layer?: OnCallLayer;
  userId?: number | null;
  originalUserId?: number | null;
  startsAt: string;
  endsAt: string;
  reason?: string | null;
  offer?: boolean;
}

export interface UnavailabilityRequest {
  userId?: number | null;
  startsAt: string;
  endsAt: string;
  reason?: string | null;
}

export interface HandoverRequest {
  body: string;
  toUserId?: number | null;
  shiftStart?: string | null;
  shiftEnd?: string | null;
}

export interface PolicyRequest {
  name: string;
  description?: string | null;
  teamId?: number | null;
  minSeverity?: AlertSeverityName | null;
  levels: { delayMinutes: number; target: EscalationTarget; userId?: number | null }[];
  active?: boolean;
}

// =================================================================== major incident command

export type MajorStatus = 'ACTIVE' | 'MONITORING' | 'RESOLVED' | 'CLOSED';
export type TimelineEntryType = 'NOTE' | 'DECISION' | 'ACTION' | 'INVESTIGATION' | 'STATUS' | 'ROLE' | 'UPDATE' | 'SYSTEM';
export type StakeholderStatus = 'INVESTIGATING' | 'IDENTIFIED' | 'MONITORING' | 'RESOLVED';
export type ActionItemType = 'CORRECTIVE' | 'PREVENTIVE' | 'FOLLOW_UP';
export type ActionItemStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';
export type PirStatus = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED';

export interface PersonRef {
  id: number;
  name: string;
  email: string | null;
}

export interface PirRef {
  id: number;
  number: string;
  status: PirStatus;
}

export interface TimelineDto {
  id: number;
  entryType: TimelineEntryType;
  body: string;
  actorId: number | null;
  actorName: string | null;
  occurredAt: string;
  demo: boolean;
}

export interface StakeholderUpdateDto {
  id: number;
  status: StakeholderStatus;
  title: string;
  message: string;
  published: boolean;
  statusUpdateId: number | null;
  publishedAt: string | null;
  postedByName: string | null;
  postedAt: string;
  demo: boolean;
}

export interface ActionItemDto {
  id: number;
  source: 'MAJOR_INCIDENT' | 'PIR';
  majorIncidentId: number | null;
  pirId: number | null;
  title: string;
  description: string | null;
  actionType: ActionItemType;
  ownerId: number | null;
  ownerName: string | null;
  dueDate: string | null;
  status: ActionItemStatus;
  overdue: boolean;
  completedAt: string | null;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ActionRequest {
  title: string;
  description?: string | null;
  actionType?: ActionItemType | null;
  ownerId?: number | null;
  dueDate?: string | null;
  status?: ActionItemStatus | null;
}

export interface MajorSummary {
  id: number;
  number: string;
  status: MajorStatus;
  incidentId: number;
  incidentNumber: string;
  title: string;
  priority: Priority;
  incidentStatus: IncidentStatus;
  commander: PersonRef | null;
  techLead: PersonRef | null;
  commsLead: PersonRef | null;
  declaredAt: string;
  resolvedAt: string | null;
  closedAt: string | null;
  durationMinutes: number;
  openActions: number;
  pir: PirRef | null;
  demo: boolean;
}

export interface MajorDetail {
  id: number;
  number: string;
  status: MajorStatus;
  incident: IncidentSummary;
  commander: PersonRef | null;
  techLead: PersonRef | null;
  commsLead: PersonRef | null;
  summary: string | null;
  impactSummary: string | null;
  investigationNotes: string | null;
  resolutionSummary: string | null;
  bridgeUrl: string | null;
  declaredAt: string;
  declaredByName: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  durationMinutes: number;
  services: { id: number; code: string; name: string }[];
  timeline: TimelineDto[];
  updates: StakeholderUpdateDto[];
  actionItems: ActionItemDto[];
  alerts: { id: number; number: string; title: string; severity: string; status: string }[];
  pir: PirRef | null;
  allowedTransitions: MajorStatus[];
  canCommand: boolean;
  canPublish: boolean;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface DeclareRequest {
  incidentId: number;
  commanderId?: number | null;
  techLeadId?: number | null;
  commsLeadId?: number | null;
  summary?: string | null;
  serviceIds?: number[];
  bridgeUrl?: string | null;
}

export interface MajorUpdateRequest {
  summary?: string | null;
  impactSummary?: string | null;
  investigationNotes?: string | null;
  resolutionSummary?: string | null;
  bridgeUrl?: string | null;
  serviceIds?: number[];
}

export interface StakeholderUpdateRequest {
  status: StakeholderStatus;
  title?: string | null;
  message: string;
  publishToStatusPage?: boolean;
}

// =================================================================== post-incident reviews

export interface PirTimelineItem {
  at: string;
  kind: string;
  source: string | null;
  title: string;
  detail: string | null;
  actorName: string | null;
}

export interface PirMetrics {
  detectedAt?: string | null;
  reportedAt?: string | null;
  firstResponseAt?: string | null;
  declaredAt?: string | null;
  acknowledgedAt?: string | null;
  resolvedAt?: string | null;
  minutesToDetect?: number | null;
  minutesToAcknowledge?: number | null;
  minutesToDeclare?: number | null;
  minutesToResolve?: number | null;
  alerts?: number | null;
  pages?: number | null;
  linkedReports?: number | null;
  slaOutcome?: string | null;
  [key: string]: unknown;
}

export interface PirSummary {
  id: number;
  number: string;
  title: string;
  status: PirStatus;
  incidentId: number;
  incidentNumber: string;
  incidentTitle: string;
  majorIncidentId: number | null;
  majorIncidentNumber: string | null;
  owner: PersonRef | null;
  openActions: number;
  actionCount: number;
  submittedAt: string | null;
  approvedAt: string | null;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PirDetail {
  id: number;
  number: string;
  title: string;
  status: PirStatus;
  incident: IncidentSummary;
  majorIncidentId: number | null;
  majorIncidentNumber: string | null;
  owner: PersonRef | null;
  summary: string | null;
  rootCause: string | null;
  contributingFactors: string[];
  impact: string | null;
  detectionGaps: string | null;
  responseAnalysis: string | null;
  lessonsLearned: string | null;
  timeline: PirTimelineItem[];
  metrics: PirMetrics | null;
  timelineBuiltAt: string | null;
  actions: ActionItemDto[];
  approvalId: number | null;
  approvalStatus: string | null;
  submittedAt: string | null;
  approvedAt: string | null;
  approvedByName: string | null;
  reviewComment: string | null;
  canEdit: boolean;
  canSubmit: boolean;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PirUpdateRequest {
  title?: string | null;
  ownerId?: number | null;
  summary?: string | null;
  rootCause?: string | null;
  contributingFactors?: string[] | null;
  impact?: string | null;
  detectionGaps?: string | null;
  responseAnalysis?: string | null;
  lessonsLearned?: string | null;
}

// =================================================================== operations analytics

export interface DepartmentPerformance {
  departmentId: number;
  name: string;
  created: number;
  resolved: number;
  open: number;
  mttaMinutes: number | null;
  mttrMinutes: number | null;
  slaCompliance: number | null;
  firstContactResolution: number | null;
}

export interface OnCallSchedulePerformance {
  scheduleId: number;
  scheduleName: string;
  teamId: number | null;
  teamName: string | null;
  alertsPaged: number;
  pages: number;
  acknowledged: number;
  ackRate: number | null;
  avgAckMinutes: number | null;
  escalated: number;
}

export interface ServiceAvailabilityRow {
  serviceId: number;
  code: string;
  name: string;
  tier: number;
  availability: number | null;
  outageMinutes: number;
  degradedMinutes: number;
  incidents: number;
}

export interface OperationsMetrics {
  from: string;
  to: string;
  days: number;
  departmentId: number | null;
  generatedAt: string;
  mtta: { minutes: number | null; samples: number };
  mttd: { minutes: number | null; samples: number; incidentsWithoutAlert: number };
  firstContactResolution: { rate: number | null; resolved: number; resolvedAtFirstContact: number };
  changes: { finished: number; completed: number; failed: number; rolledBack: number; successRate: number | null; failureRate: number | null };
  problemRecurrence: { knownErrors: number; problemsWithRecurrence: number; recurringIncidents: number; rate: number | null };
  alertVolume: {
    total: number;
    occurrences: number;
    bySeverity: Record<string, number>;
    bySource: Record<string, number>;
    suppressed: number;
    withIncident: number;
    perDay: { date: string; count: number }[];
  };
  assetLifecycle: {
    byStatus: Record<string, number>;
    acquired: number;
    retired: number;
    disposed: number;
    averageAgeDays: number | null;
    warrantyExpiringNext30Days: number;
  };
  onCall: {
    alertsPaged: number;
    pages: number;
    acknowledged: number;
    ackRate: number | null;
    avgAckMinutes: number | null;
    escalated: number;
    bySchedule: OnCallSchedulePerformance[];
  };
  departmentPerformance: DepartmentPerformance[];
  serviceAvailability: ServiceAvailabilityRow[];
}

export interface MetricDefinition {
  key: string;
  name: string;
  unit: string | null;
  formula: string;
  sources: string[];
  filters: string[];
  notes: string | null;
}

export interface SlaRisk {
  incidentId: number;
  number: string;
  title: string;
  priority: Priority | string;
  policyName: string | null;
  clockStatus: string | null;
  elapsedPercent: number;
  remainingMinutes: number;
  resolutionDueAt: string | null;
  probability: number | null;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'N/A' | string;
  probabilityLabel: string | null;
  sampleSize: number;
  historicalBreaches: number;
  historyDays: number;
  method: string | null;
  factors: string[];
  recommendations: string[];
}

export interface SlaRiskOverview {
  generatedAt: string;
  incidents: SlaRisk[];
  method: string | null;
}

export interface CommandCenterBlock {
  incidents: {
    open: number;
    criticalOpen: number;
    majorOpen: number;
    createdInPeriod: number;
    resolvedInPeriod: number;
    slaBreachesInPeriod: number;
    slaAtRiskNow: number;
    mttaMinutes: number | null;
  };
  alerts: { active: number; open: number; acknowledged: number; critical: number; escalated: number; resolvedLast24h: number };
  onCallNow: NowEntry[];
  changes: { awaitingApproval: number; scheduledNext7Days: number; implementingNow: number; completedInPeriod: number; failedInPeriod: number; emergencyOpen: number };
  workflows: { executions24h: number; succeeded24h: number; failed24h: number; running: number };
  activeMajorIncidents: { id: number; number: string; title: string; status: MajorStatus | string; commanderName: string | null; declaredAt: string }[];
  departmentPerformance: DepartmentPerformance[];
  recentActivity: { kind: 'INCIDENT' | 'ALERT' | 'CHANGE' | 'DEPLOYMENT' | 'MAJOR_INCIDENT' | string; at: string; title: string; detail: string | null; actorName: string | null; link: string | null }[];
}

// =================================================================== SA AI operations features

export interface ProblemCorrelation {
  problemId: number;
  number: string;
  title: string;
  candidates: { incidentId: number; number: string; title: string; status: string; createdAt: string; score: number; reasons: string[] }[];
  changes: { changeId: number; number: string; title: string; status: string; startedAt: string | null; incidentsWithin48h: number }[];
  alertGroups: { groupKey: string; title: string; alerts: number; maxSeverity: string | null }[];
  summary: string | null;
  basis: string | null;
}

export interface CmdbImpact {
  ciId: number;
  ciName: string;
  ciType: string;
  criticality: string | null;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | string;
  impactedServices: { serviceId: number; code: string; name: string; tier: number; status: string }[];
  upstreamCount: number;
  downstreamCount: number;
  criticalUpstream: string[];
  openIncidents: number;
  openAlerts: number;
  summary: string | null;
  recommendations: string[];
  truncated: boolean;
  basis: string | null;
}

export interface RunbookRecommendations {
  incidentId: number;
  incidentNumber: string;
  recommendations: { runbookId: number; title: string; score: number; requiresApproval: boolean; estimatedMinutes: number | null; reasons: string[] }[];
  rationale: string | null;
  basis: string | null;
}

export interface TrendAnalysis {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  days: number;
  trends: { metric: string; dimension: string | null; current: number; previous: number; changePercent: number | null; direction: 'UP' | 'DOWN' | 'FLAT' | string; zScore: number | null; significant: boolean }[];
  highlights: string[];
  narrative: string | null;
  basis: string | null;
}

// =================================================================== My Work sections

export interface MyActionItem {
  id: number;
  title: string;
  actionType: ActionItemType;
  status: ActionItemStatus;
  dueDate: string | null;
  overdue: boolean;
  source: 'MAJOR_INCIDENT' | 'PIR';
  sourceId: number;
  sourceNumber: string | null;
  sourceTitle: string | null;
  link: string | null;
}
