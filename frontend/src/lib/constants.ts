import type {
  AnnouncementDto,
  ApprovalStatus,
  ApprovalType,
  ApproverRole,
  ArticleStatus,
  ArticleType,
  Category,
  ExecutionStatus,
  IncidentSource,
  IncidentStatus,
  IntegrationType,
  Level,
  LinkType,
  NotificationType,
  Priority,
  ResolutionCode,
  Role,
  ServiceDeskCategoryCode,
  ServiceRequestStatus,
  SlaHealth,
  WorkflowActionType,
  WorkflowTrigger,
} from './types';

// ============================================================== roles (§1)
/** Display order for role pickers. */
export const ROLES: Role[] = [
  'SYSTEM_ADMIN',
  'IT_ADMIN',
  'IT_MANAGER',
  'INCIDENT_MANAGER',
  'CHANGE_MANAGER',
  'ASSET_MANAGER',
  'SUPPORT_AGENT',
  'EMPLOYEE',
  'AUDITOR',
  'SECURITY_ANALYST',
  'FINOPS_ANALYST',
];
export const ALL_ROLES: Role[] = ROLES;

export const ROLE_LABELS: Record<Role, string> = {
  SYSTEM_ADMIN: 'System Admin',
  IT_ADMIN: 'IT Admin',
  IT_MANAGER: 'IT Manager',
  INCIDENT_MANAGER: 'Incident Manager',
  CHANGE_MANAGER: 'Change Manager',
  ASSET_MANAGER: 'Asset Manager',
  SUPPORT_AGENT: 'Support Agent',
  EMPLOYEE: 'Employee',
  AUDITOR: 'Auditor',
  SECURITY_ANALYST: 'Security Analyst',
  FINOPS_ANALYST: 'FinOps Analyst',
};

/** Display label for any role value (unknown values are shown as-is). */
export function roleLabel(role: string | null | undefined): string {
  if (!role) return '';
  return (ROLE_LABELS as Record<string, string>)[role] ?? role;
}

export const ROLE_DESCRIPTIONS: Record<Role, string> = {
  SYSTEM_ADMIN: 'Everything, including system, organisation and AI settings and demo tools',
  IT_ADMIN: 'IT configuration: users, departments, services, catalogue, SLA, categories, integrations, workflows, runbooks, knowledge',
  IT_MANAGER: 'Team management: approves service requests and workflows, analytics, exports, major incidents',
  INCIDENT_MANAGER: 'Incident command: major incidents, escalation, cross-team assignment, runbook and AI approvals',
  CHANGE_MANAGER: 'Change enablement: changes, change calendar, CAB approvals, deployments and releases; works tickets',
  ASSET_MANAGER: 'Asset and configuration management: assets, CMDB, software licences, import and export; works tickets',
  SUPPORT_AGENT: 'Works incidents and service requests',
  EMPLOYEE: 'Reports incidents, raises requests, reads published knowledge',
  AUDITOR: 'Read-only compliance access to operational data, audit logs and exports',
  SECURITY_ANALYST: 'Security operations: reads operational data, observability and the digital twin; security incidents and vulnerabilities',
  FINOPS_ANALYST: 'Cloud cost management: reads operational data, observability and the digital twin; cost allocation, budgets and savings',
};

// Role groups — mirror the backend `Roles` constants (spec §1). Use these, never literal role lists.
export const ADMINS: Role[] = ['SYSTEM_ADMIN', 'IT_ADMIN'];
export const MANAGERS: Role[] = ['IT_MANAGER', 'INCIDENT_MANAGER'];
export const ADMIN_OR_MANAGER: Role[] = [...ADMINS, ...MANAGERS];
/** Work incidents, internal notes, assignment, transitions (Release 3 adds the change and asset managers). */
export const STAFF: Role[] = [...ADMIN_OR_MANAGER, 'CHANGE_MANAGER', 'ASSET_MANAGER', 'SUPPORT_AGENT'];
/** Staff + auditor (+ the Release 4 analysts, who are readers but not staff): read operational data. */
export const READERS: Role[] = [...STAFF, 'AUDITOR', 'SECURITY_ANALYST', 'FINOPS_ANALYST'];
export const AUDIT_READERS: Role[] = ['SYSTEM_ADMIN', 'IT_ADMIN', 'AUDITOR'];
export const REPORT_EXPORTERS: Role[] = [...ADMIN_OR_MANAGER, 'AUDITOR'];
export const USER_ADMINS: Role[] = ADMINS;
export const USER_READERS: Role[] = [...ADMIN_OR_MANAGER, 'AUDITOR'];
/** Services, catalogue, SLA, calendars, incident categories, integrations. */
export const CONFIG_ADMINS: Role[] = ADMINS;
export const WORKFLOW_EDITORS: Role[] = [...ADMINS, 'IT_MANAGER'];
export const RUNBOOK_EDITORS: Role[] = ADMIN_OR_MANAGER;
/** Complete sensitive runbook steps directly (counts as the approval, audited) — §10; approvals route to INCIDENT_MANAGER. */
export const RUNBOOK_APPROVERS: Role[] = ['INCIDENT_MANAGER', 'SYSTEM_ADMIN'];
export const KB_PUBLISHERS: Role[] = ADMIN_OR_MANAGER;
export const KB_DELETERS: Role[] = ADMINS;
/** Declare / remove major incident. */
export const MAJOR_INCIDENT: Role[] = ADMIN_OR_MANAGER;
export const ESCALATORS: Role[] = STAFF;
/** Organisation, notification defaults, AI configuration, system settings, demo tools. */
export const SETTINGS: Role[] = ['SYSTEM_ADMIN'];
export const HEALTH_READERS: Role[] = ADMINS;
/** Roles approvals can be routed to; SYSTEM_ADMIN sees and decides all. */
export const APPROVER_ROLES: ApproverRole[] = ['IT_MANAGER', 'INCIDENT_MANAGER', 'SYSTEM_ADMIN'];
export const APPROVERS: Role[] = APPROVER_ROLES;
/** Who may open the Settings page at all (each tab is gated separately, see SETTINGS_TABS). */
export const SETTINGS_PAGE: Role[] = [...CONFIG_ADMINS];

// Release 3 role groups (RELEASE3_SPEC §2) — mirror the backend `Roles` constants.
/** Create and edit changes (incident managers may also raise emergency changes). */
export const CHANGE_EDITORS: Role[] = [...ADMINS, 'IT_MANAGER', 'CHANGE_MANAGER'];
/** Change Advisory Board. */
export const CHANGE_APPROVERS: Role[] = ['SYSTEM_ADMIN', 'IT_MANAGER', 'CHANGE_MANAGER'];
/** Create, edit and delete approval flows (backend `Roles.APPROVAL_FLOW_EDITORS`); READERS may view them. */
export const APPROVAL_FLOW_EDITORS: Role[] = [...ADMINS, 'IT_MANAGER', 'CHANGE_MANAGER'];
export const DEPLOYMENT_EDITORS: Role[] = [...ADMINS, 'CHANGE_MANAGER'];
export const ASSET_EDITORS: Role[] = [...ADMINS, 'ASSET_MANAGER'];
export const CMDB_EDITORS: Role[] = [...ADMINS, 'ASSET_MANAGER', 'IT_MANAGER'];
/** Agents create and work problems; only admins and managers close them. */
export const PROBLEM_EDITORS: Role[] = [...ADMIN_OR_MANAGER, 'SUPPORT_AGENT'];
export const PROBLEM_OWNERS_CLOSE: Role[] = ADMIN_OR_MANAGER;
export const ALERT_RESPONDERS: Role[] = STAFF;
/** Suppression and routing rules. */
export const ALERT_CONFIG: Role[] = ADMINS;
export const ONCALL_EDITORS: Role[] = [...ADMINS, 'IT_MANAGER', 'INCIDENT_MANAGER'];
/** Declare a major incident and assign its command roles; responders are STAFF. */
export const MAJOR_INCIDENT_COMMAND: Role[] = ADMIN_OR_MANAGER;
export const PIR_APPROVERS: Role[] = ['SYSTEM_ADMIN', 'IT_MANAGER', 'INCIDENT_MANAGER'];
export const STATUS_PAGE_EDITORS: Role[] = [...ADMINS, 'INCIDENT_MANAGER'];
export const REPORT_SCHEDULERS: Role[] = REPORT_EXPORTERS;
/** Maintain teams and their members. */
export const TEAM_ADMINS: Role[] = [...USER_ADMINS, 'IT_MANAGER'];

// Release 4 role groups (RELEASE4_SPEC §2) — mirror the backend `Roles` constants.
export const OBSERVABILITY_READERS: Role[] = READERS;
/** Telemetry sources, mapping rules, retention and anomaly detectors. */
export const TELEMETRY_CONFIG: Role[] = ADMINS;
/** Edit twin relationship redundancy (simulations are read-only and open to readers). */
export const TWIN_EDITORS: Role[] = [...ADMINS, 'ASSET_MANAGER', 'CHANGE_MANAGER'];
export const PREDICTION_ADMINS: Role[] = [...ADMINS, 'IT_MANAGER'];
export const AGENT_USERS: Role[] = [...STAFF, 'SECURITY_ANALYST'];
export const AI_GOVERNANCE: Role[] = ADMINS;
export const PLAYBOOK_EDITORS: Role[] = [...ADMINS, 'IT_MANAGER', 'CHANGE_MANAGER'];
export const PLAYBOOK_APPROVERS: Role[] = ['SYSTEM_ADMIN', 'IT_MANAGER', 'CHANGE_MANAGER'];
export const REMEDIATION_OPERATORS: Role[] = STAFF;
export const EXECUTION_SYSTEM_ADMINS: Role[] = ['SYSTEM_ADMIN'];
export const SECOPS: Role[] = [...ADMINS, 'SECURITY_ANALYST', 'INCIDENT_MANAGER'];
export const SECOPS_READERS: Role[] = [...SECOPS, 'AUDITOR', 'IT_MANAGER'];
export const FINOPS: Role[] = [...ADMINS, 'FINOPS_ANALYST', 'IT_MANAGER'];
export const FINOPS_READERS: Role[] = [...FINOPS, 'AUDITOR', 'CHANGE_MANAGER', 'ASSET_MANAGER'];
export const DEX_READERS: Role[] = READERS;
/** Simulation mode switch, scenarios and simulated-data purge. */
export const SIMULATION_ADMINS: Role[] = ['SYSTEM_ADMIN'];

export function hasAnyRole(role: Role | null | undefined, allowed: readonly Role[]): boolean {
  return Boolean(role && allowed.includes(role));
}

/** IT_ADMIN cannot create, edit or grant SYSTEM_ADMIN (§1). */
export function assignableRoles(actor: Role | null | undefined): Role[] {
  if (actor === 'SYSTEM_ADMIN') return ROLES;
  if (actor === 'IT_ADMIN') return ROLES.filter((role) => role !== 'SYSTEM_ADMIN');
  return [];
}

// ======================================================= incidents (§2)
export const PRIORITIES: Priority[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
export const PRIORITY_LABELS: Record<Priority, string> = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' };
/** Employees may choose LOW–HIGH for service requests; staff any (§4). */
export const EMPLOYEE_REQUEST_PRIORITIES: Priority[] = ['HIGH', 'MEDIUM', 'LOW'];
export const LEVELS: Level[] = ['HIGH', 'MEDIUM', 'LOW'];

export const INCIDENT_STATUSES: IncidentStatus[] = ['NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING', 'RESOLVED', 'CLOSED', 'REOPENED'];
export const ACTIVE_STATUSES: IncidentStatus[] = ['NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING', 'REOPENED'];
export const RESOLVED_STATUSES: IncidentStatus[] = ['RESOLVED', 'CLOSED'];

export const STATUS_LABELS: Record<IncidentStatus, string> = {
  NEW: 'New',
  ASSIGNED: 'Assigned',
  IN_PROGRESS: 'In Progress',
  PENDING: 'Pending',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
  REOPENED: 'Reopened',
};

/** Allowed transitions (§2). The API's `allowedTransitions` is authoritative per incident and user. */
export const INCIDENT_TRANSITIONS: Record<IncidentStatus, IncidentStatus[]> = {
  NEW: ['ASSIGNED', 'IN_PROGRESS', 'PENDING', 'RESOLVED', 'CLOSED'],
  ASSIGNED: ['IN_PROGRESS', 'PENDING', 'RESOLVED', 'CLOSED'],
  IN_PROGRESS: ['PENDING', 'RESOLVED'],
  PENDING: ['IN_PROGRESS', 'RESOLVED'],
  RESOLVED: ['CLOSED', 'REOPENED'],
  REOPENED: ['ASSIGNED', 'IN_PROGRESS', 'PENDING', 'RESOLVED'],
  CLOSED: [],
};

/** CLOSED from NEW/ASSIGNED is a cancellation: send resolutionCode CANCELLED plus a reason. */
export function isCancelTransition(from: IncidentStatus, to: IncidentStatus): boolean {
  return to === 'CLOSED' && (from === 'NEW' || from === 'ASSIGNED');
}

/** Transitions that need a free-text reason (sent as `comment`). */
export function transitionNeedsReason(from: IncidentStatus, to: IncidentStatus): boolean {
  return to === 'PENDING' || isCancelTransition(from, to);
}

/** Button labels, named by the target status. */
export const TRANSITION_LABELS: Record<IncidentStatus, string> = {
  NEW: 'Mark New',
  ASSIGNED: 'Mark Assigned',
  IN_PROGRESS: 'Start Progress',
  PENDING: 'Set Pending',
  RESOLVED: 'Resolve',
  CLOSED: 'Close Incident',
  REOPENED: 'Reopen',
};

export function transitionLabel(from: IncidentStatus, to: IncidentStatus): string {
  return isCancelTransition(from, to) ? 'Cancel Incident' : TRANSITION_LABELS[to];
}

/** Display order for transition buttons. */
export const TRANSITION_ORDER: IncidentStatus[] = ['IN_PROGRESS', 'ASSIGNED', 'PENDING', 'RESOLVED', 'REOPENED', 'CLOSED'];

/** Progress stepper on the incident detail page (spec screen 3). */
export const INCIDENT_PROGRESS_STEPS = ['Created', 'Assigned', 'In Progress', 'Resolved'] as const;
/** Index of the reached stepper step for a status (0 = Created). */
export function incidentProgressIndex(status: IncidentStatus): number {
  switch (status) {
    case 'NEW':
      return 0;
    case 'ASSIGNED':
    case 'REOPENED':
      return 1;
    case 'IN_PROGRESS':
    case 'PENDING':
      return 2;
    case 'RESOLVED':
    case 'CLOSED':
      return 3;
    default:
      return 0;
  }
}

export const CATEGORIES: Category[] = ['APPLICATION', 'NETWORK', 'HARDWARE', 'ACCESS', 'DATABASE', 'EMAIL', 'SECURITY', 'FACILITIES', 'OTHER'];
export const INCIDENT_SOURCES: IncidentSource[] = ['PORTAL', 'EMAIL', 'PHONE', 'MONITORING', 'API', 'SCENARIO'];
/** Codes offered in the Resolve dialog; CANCELLED is set by the cancel action only. */
export const RESOLUTION_CODES: ResolutionCode[] = ['FIXED', 'WORKAROUND', 'DUPLICATE', 'NOT_REPRODUCIBLE', 'USER_ERROR', 'CHANGE_ROLLED_BACK', 'OTHER'];
export const ALL_RESOLUTION_CODES: ResolutionCode[] = [...RESOLUTION_CODES, 'CANCELLED'];
export const LINK_TYPES: LinkType[] = ['RELATED_TO', 'CAUSED_BY', 'BLOCKS'];
export const SLA_HEALTH: SlaHealth[] = ['ON_TRACK', 'AT_RISK', 'BREACHED'];

// ===================================================== service desk (§4)
export const REQUEST_STATUSES: ServiceRequestStatus[] = ['PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'IN_FULFILLMENT', 'FULFILLED', 'CANCELLED'];
export const REQUEST_STATUS_LABELS: Record<ServiceRequestStatus, string> = {
  PENDING_APPROVAL: 'Pending Approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  IN_FULFILLMENT: 'In Fulfilment',
  FULFILLED: 'Fulfilled',
  CANCELLED: 'Cancelled',
};
export const SERVICE_DESK_CATEGORIES: { code: ServiceDeskCategoryCode; name: string }[] = [
  { code: 'HARDWARE', name: 'Hardware' },
  { code: 'SOFTWARE', name: 'Software' },
  { code: 'NETWORK', name: 'Network' },
  { code: 'ACCESS', name: 'Access Management' },
  { code: 'ACCOUNT', name: 'Account' },
  { code: 'FACILITIES', name: 'Facilities' },
  { code: 'GENERAL', name: 'General' },
];
export const APPROVAL_STATUSES: ApprovalStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'];
export const APPROVAL_TYPE_LABELS: Record<ApprovalType, string> = {
  SERVICE_REQUEST: 'Service request',
  RUNBOOK_EXECUTION: 'Runbook execution',
  RUNBOOK_STEP: 'Sensitive runbook step',
  AI_ACTION: 'AI action',
  CHANGE: 'Change',
  PIR: 'Post-incident review',
  WORKFLOW: 'Workflow sign-off',
};

// ======================================================== workflows (§6)
export const EXECUTION_STATUSES: ExecutionStatus[] = ['RUNNING', 'SUCCEEDED', 'FAILED', 'RETRY_SCHEDULED', 'RATE_LIMITED'];
export const WORKFLOW_TRIGGERS: WorkflowTrigger[] = [
  'INCIDENT_CREATED',
  'INCIDENT_UPDATED',
  'INCIDENT_STATUS_CHANGED',
  'INCIDENT_ASSIGNED',
  'PRIORITY_CHANGED',
  'SLA_AT_RISK',
  'SLA_BREACHED',
  'SERVICE_REQUEST_CREATED',
  'SCHEDULED',
  'CHANGE_REQUEST_CREATED',
  'ALERT_RECEIVED',
];
export const WORKFLOW_TRIGGER_LABELS: Record<WorkflowTrigger, string> = {
  INCIDENT_CREATED: 'New Incident',
  INCIDENT_UPDATED: 'Incident Updated',
  INCIDENT_STATUS_CHANGED: 'Status Changed',
  INCIDENT_ASSIGNED: 'Incident Assigned',
  PRIORITY_CHANGED: 'Priority Changed',
  SLA_AT_RISK: 'SLA At Risk',
  SLA_BREACHED: 'SLA Breach',
  SERVICE_REQUEST_CREATED: 'Service Request',
  SCHEDULED: 'Schedule',
  CHANGE_REQUEST_CREATED: 'Change Request',
  ALERT_RECEIVED: 'Alert Received',
};
/** Fallback labels when /workflows/meta is unavailable (§6 wording). */
export const WORKFLOW_ACTION_LABELS: Record<WorkflowActionType, string> = {
  ASSIGN_DEPARTMENT: 'Assign to team',
  ASSIGN_USER: 'Assign to user',
  SET_PRIORITY: 'Set priority',
  CHANGE_STATUS: 'Change status',
  ADD_COMMENT: 'Add comment',
  NOTIFY_ROLE: 'Send notification to role',
  NOTIFY_DEPARTMENT: 'Send notification to team',
  NOTIFY_ASSIGNEE: 'Send notification to assignee',
  NOTIFY_USER: 'Send notification to user',
  ESCALATE: 'Create escalation',
  DECLARE_MAJOR: 'Declare major incident',
  SEND_WEBHOOK: 'Send webhook',
  START_RUNBOOK: 'Trigger approved runbook',
  ASSIGN_TEAM: 'Assign to team (by team code)',
  REQUEST_APPROVAL: 'Request approval',
  CREATE_INCIDENT: 'Create incident from the alert',
};
export const MIN_SCHEDULE_INTERVAL_MINUTES = 15;

// ======================================================== knowledge (§9)
export const ARTICLE_TYPES: ArticleType[] = ['GUIDE', 'SOLUTION', 'KNOWN_ERROR', 'FAQ'];
export const ARTICLE_STATUSES: ArticleStatus[] = ['DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED'];
export const ARTICLE_STATUS_LABELS: Record<ArticleStatus, string> = {
  DRAFT: 'Draft',
  IN_REVIEW: 'In Review',
  PUBLISHED: 'Published',
  ARCHIVED: 'Archived',
};

// ====================================================== notifications (§12)
export const NOTIFICATION_TYPES: NotificationType[] = [
  'INCIDENT_ASSIGNED',
  'INCIDENT_COMMENT',
  'INCIDENT_RESOLVED',
  'SLA_AT_RISK',
  'SLA_BREACHED',
  'ESCALATION',
  'APPROVAL_REQUESTED',
  'APPROVAL_DECIDED',
  'WORKFLOW_FAILED',
  'REQUEST_UPDATED',
  'ANNOUNCEMENT',
];
/** Preference category labels (mirror the backend `NotificationTypes`; the API's own `label` wins when present). */
export const NOTIFICATION_CATEGORY_LABELS: Partial<Record<NotificationType, string>> = {
  INCIDENT_ASSIGNED: 'Incident assigned',
  INCIDENT_COMMENT: 'Incident comments',
  INCIDENT_RESOLVED: 'Incident resolved or reopened',
  SLA_AT_RISK: 'SLA at risk',
  SLA_BREACHED: 'SLA breached',
  ESCALATION: 'Escalations and major incidents',
  APPROVAL_REQUESTED: 'Approval requested',
  APPROVAL_DECIDED: 'Approval decided',
  WORKFLOW_FAILED: 'Workflow failed',
  REQUEST_UPDATED: 'Service request updates',
  ANNOUNCEMENT: 'Announcements',
  ACCESS_REQUEST: 'Access requests',
  PASSWORD_RESET_REQUEST: 'Password reset requests',
  TEAM_QUEUE: 'Team queue',
  REPORT_READY: 'Scheduled reports',
  APPROVAL_ESCALATED: 'Overdue approvals',
  PROBLEM_UPDATED: 'Problems',
  CHANGE_UPDATED: 'Changes',
  DEPLOYMENT_STATUS: 'Deployment failures',
  ALERT_ROUTED: 'Alerts for my teams',
  ALERT_ESCALATED: 'Escalated alerts',
  ASSET_ASSIGNED: 'Assets assigned to me',
  ONCALL_PAGE: 'On-call pages',
  ONCALL_SHIFT: 'On-call shifts',
  MAJOR_INCIDENT_UPDATE: 'Major incident command',
  PIR_UPDATED: 'Post-incident reviews',
};

/** Stream notifications with these severities raise a toast. */
export const TOAST_SEVERITIES = ['CRITICAL', 'WARNING'] as const;

// ================================================== administration (§13)
/** Organisation settings stored as system_settings keys (SETTINGS write). */
export const ORG_SETTING_KEYS = {
  name: 'org.name',
  supportEmail: 'org.support_email',
  timezone: 'org.timezone',
  businessHoursNote: 'org.business_hours_note',
} as const;
export const NOTIFICATION_DEFAULT_KEYS = {
  slaAtRiskNotifyManagers: 'notifications.sla_at_risk.notify_managers',
  majorNotifyAllStaff: 'notifications.major.notify_all_staff',
} as const;

export type SettingsTabId =
  | 'organisation'
  | 'sla'
  | 'business-hours'
  | 'categories'
  | 'catalogue'
  | 'notifications'
  | 'ai'
  | 'email'
  | 'health'
  | 'demo'
  | 'simulation';

/** /settings tabs (§14), each role-gated. */
export const SETTINGS_TABS: { id: SettingsTabId; label: string; roles: Role[] }[] = [
  { id: 'organisation', label: 'Organisation', roles: SETTINGS },
  { id: 'sla', label: 'SLA Policies', roles: CONFIG_ADMINS },
  { id: 'business-hours', label: 'Business Hours', roles: CONFIG_ADMINS },
  { id: 'categories', label: 'Incident Categories', roles: CONFIG_ADMINS },
  { id: 'catalogue', label: 'Service Catalogue', roles: CONFIG_ADMINS },
  { id: 'notifications', label: 'Notifications', roles: SETTINGS },
  { id: 'ai', label: 'AI', roles: SETTINGS },
  { id: 'email', label: 'E-mail', roles: ADMINS },
  { id: 'health', label: 'System Health', roles: HEALTH_READERS },
  { id: 'demo', label: 'Demo Tools', roles: SETTINGS },
  { id: 'simulation', label: 'Simulation mode', roles: SIMULATION_ADMINS },
];

// ============================================================ misc
export const INTEGRATION_TYPES: IntegrationType[] = ['WEBHOOK', 'SLACK', 'TEAMS', 'MONITORING_INBOUND'];
export const ANNOUNCEMENT_SEVERITIES: AnnouncementDto['severity'][] = ['INFO', 'MAINTENANCE', 'WARNING', 'CRITICAL'];
/** Outbound integration events (IntegrationEventListener.EVENTS in the API). */
export const INTEGRATION_EVENTS = [
  'incident.created',
  'incident.status_changed',
  'incident.resolved',
  'incident.escalated',
  'sla.at_risk',
  'sla.breached',
] as const;
export const WORK_DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;

export const PERIOD_OPTIONS = [7, 30, 90] as const;
export type PeriodDays = (typeof PERIOD_OPTIONS)[number];

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
/** Client-side hint only; the API verifies file signatures against its own allow-list. */
export const ALLOWED_UPLOAD_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'pdf', 'txt', 'log', 'csv', 'json', 'docx', 'xlsx'];

// ============================================================ colours (§15)
/** Spec palette, for charts and inline SVG (CSS uses the tokens in styles.css). */
export const PALETTE = {
  bg: '#101012',
  sidebar: '#171618',
  secondary: '#121213',
  card: '#1D1B1A',
  elevated: '#25211D',
  gold: '#D6B77A',
  champagne: '#F1DFBA',
  bronze: '#96764B',
  text: '#F5F1EA',
  muted: '#A7A19A',
  success: '#49B889',
  warning: '#E5AE62',
  critical: '#E56D68',
} as const;

export type Tone = 'critical' | 'warning' | 'success' | 'gold' | 'champagne' | 'neutral' | 'muted';

export const PRIORITY_TONES: Record<Priority, Tone> = { CRITICAL: 'critical', HIGH: 'warning', MEDIUM: 'champagne', LOW: 'neutral' };
export const STATUS_TONES: Record<IncidentStatus, Tone> = {
  NEW: 'champagne',
  ASSIGNED: 'gold',
  IN_PROGRESS: 'gold',
  PENDING: 'warning',
  RESOLVED: 'success',
  CLOSED: 'muted',
  REOPENED: 'critical',
};
export const TONE_COLORS: Record<Tone, string> = {
  critical: PALETTE.critical,
  warning: PALETTE.warning,
  success: PALETTE.success,
  gold: PALETTE.gold,
  champagne: PALETTE.champagne,
  neutral: '#C9BFAF',
  muted: PALETTE.muted,
};
