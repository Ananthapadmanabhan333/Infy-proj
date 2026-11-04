import type { Tone } from './constants';
import { titleCase } from './format';
import type {
  ApproverKind,
  ChangeRisk,
  ChangeStatus,
  ChangeType,
  DeploymentEnvironment,
  DeploymentStatus,
  FormFieldType,
  ProblemActionStatus,
  ProblemStatus,
  Quorum,
  ReleaseStatus,
  StageMode,
  StageStatus,
  TaskStatus,
} from './itsmTypes';

/** Labels and badge tones for the Release 3 BE-2 modules (approvals, forms, problems, changes, deployments). */

export const PROBLEM_STATUSES: ProblemStatus[] = ['NEW', 'INVESTIGATING', 'ROOT_CAUSE_IDENTIFIED', 'KNOWN_ERROR', 'RESOLVED', 'CLOSED'];
export const PROBLEM_OPEN_STATUSES: ProblemStatus[] = ['NEW', 'INVESTIGATING', 'ROOT_CAUSE_IDENTIFIED', 'KNOWN_ERROR'];
export const PROBLEM_STATUS_LABELS: Record<ProblemStatus, string> = {
  NEW: 'New',
  INVESTIGATING: 'Investigating',
  ROOT_CAUSE_IDENTIFIED: 'Root cause identified',
  KNOWN_ERROR: 'Known error',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
};
export const PROBLEM_STATUS_TONES: Record<ProblemStatus, Tone> = {
  NEW: 'champagne',
  INVESTIGATING: 'warning',
  ROOT_CAUSE_IDENTIFIED: 'gold',
  KNOWN_ERROR: 'warning',
  RESOLVED: 'success',
  CLOSED: 'muted',
};
/** Transition button label by target (`from` decides whether CLOSED is a cancellation). */
export function problemTransitionLabel(from: ProblemStatus, to: ProblemStatus): string {
  if (to === 'CLOSED') return from === 'NEW' || from === 'INVESTIGATING' ? 'Cancel problem' : 'Close problem';
  if (to === 'INVESTIGATING') return from === 'NEW' ? 'Start investigation' : 'Reopen investigation';
  if (to === 'ROOT_CAUSE_IDENTIFIED') return 'Root cause identified';
  if (to === 'KNOWN_ERROR') return 'Record known error';
  if (to === 'RESOLVED') return 'Resolve';
  return PROBLEM_STATUS_LABELS[to];
}
export const ACTION_STATUSES: ProblemActionStatus[] = ['OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED'];
export const ACTION_STATUS_TONES: Record<ProblemActionStatus, Tone> = { OPEN: 'champagne', IN_PROGRESS: 'warning', DONE: 'success', CANCELLED: 'muted' };

export const CHANGE_TYPES: ChangeType[] = ['STANDARD', 'NORMAL', 'EMERGENCY'];
export const CHANGE_TYPE_LABELS: Record<ChangeType, string> = { STANDARD: 'Standard', NORMAL: 'Normal', EMERGENCY: 'Emergency' };
export const CHANGE_TYPE_TONES: Record<ChangeType, Tone> = { STANDARD: 'neutral', NORMAL: 'champagne', EMERGENCY: 'critical' };
export const CHANGE_RISKS: ChangeRisk[] = ['LOW', 'MEDIUM', 'HIGH'];
export const RISK_TONES: Record<string, Tone> = { LOW: 'success', MEDIUM: 'warning', HIGH: 'critical' };
export const CHANGE_STATUSES: ChangeStatus[] = [
  'DRAFT',
  'SUBMITTED',
  'ASSESSMENT',
  'AWAITING_APPROVAL',
  'APPROVED',
  'SCHEDULED',
  'IMPLEMENTING',
  'COMPLETED',
  'FAILED',
  'ROLLED_BACK',
  'REJECTED',
  'CANCELLED',
];
export const CHANGE_OPEN_STATUSES: ChangeStatus[] = ['DRAFT', 'SUBMITTED', 'ASSESSMENT', 'AWAITING_APPROVAL', 'APPROVED', 'SCHEDULED', 'IMPLEMENTING'];
export const CHANGE_STATUS_LABELS: Record<ChangeStatus, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  ASSESSMENT: 'Assessment',
  AWAITING_APPROVAL: 'Awaiting approval',
  APPROVED: 'Approved',
  SCHEDULED: 'Scheduled',
  IMPLEMENTING: 'Implementing',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  ROLLED_BACK: 'Rolled back',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
};
export const CHANGE_STATUS_TONES: Record<ChangeStatus, Tone> = {
  DRAFT: 'muted',
  SUBMITTED: 'champagne',
  ASSESSMENT: 'champagne',
  AWAITING_APPROVAL: 'warning',
  APPROVED: 'gold',
  SCHEDULED: 'gold',
  IMPLEMENTING: 'warning',
  COMPLETED: 'success',
  FAILED: 'critical',
  ROLLED_BACK: 'critical',
  REJECTED: 'critical',
  CANCELLED: 'muted',
};
export function changeTransitionLabel(from: ChangeStatus, to: ChangeStatus): string {
  switch (to) {
    case 'SUBMITTED':
      return 'Submit';
    case 'ASSESSMENT':
      return 'Assess risk';
    case 'AWAITING_APPROVAL':
      return 'Request CAB approval';
    case 'APPROVED':
      return from === 'SCHEDULED' ? 'Unschedule' : 'Approve';
    case 'SCHEDULED':
      return 'Schedule';
    case 'IMPLEMENTING':
      return 'Start implementation';
    case 'COMPLETED':
      return 'Complete';
    case 'FAILED':
      return 'Mark failed';
    case 'ROLLED_BACK':
      return 'Rolled back';
    case 'DRAFT':
      return 'Back to draft';
    case 'CANCELLED':
      return 'Cancel change';
    default:
      return CHANGE_STATUS_LABELS[to];
  }
}

export const ENVIRONMENTS: DeploymentEnvironment[] = ['DEV', 'TEST', 'STAGING', 'PRODUCTION'];
export const ENVIRONMENT_LABELS: Record<DeploymentEnvironment, string> = { DEV: 'Dev', TEST: 'Test', STAGING: 'Staging', PRODUCTION: 'Production' };
export const ENVIRONMENT_TONES: Record<DeploymentEnvironment, Tone> = { DEV: 'neutral', TEST: 'neutral', STAGING: 'champagne', PRODUCTION: 'gold' };
export const DEPLOYMENT_STATUSES: DeploymentStatus[] = ['IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'ROLLED_BACK'];
export const DEPLOYMENT_STATUS_TONES: Record<DeploymentStatus, Tone> = { IN_PROGRESS: 'warning', SUCCEEDED: 'success', FAILED: 'critical', ROLLED_BACK: 'critical' };
export const RELEASE_STATUSES: ReleaseStatus[] = ['PLANNED', 'IN_PROGRESS', 'DEPLOYED', 'FAILED', 'CANCELLED'];
export const RELEASE_STATUS_TONES: Record<ReleaseStatus, Tone> = { PLANNED: 'champagne', IN_PROGRESS: 'warning', DEPLOYED: 'success', FAILED: 'critical', CANCELLED: 'muted' };

export const STAGE_STATUS_TONES: Record<StageStatus, Tone> = {
  WAITING: 'muted',
  ACTIVE: 'warning',
  APPROVED: 'success',
  REJECTED: 'critical',
  SKIPPED: 'neutral',
  CANCELLED: 'muted',
};
export const TASK_STATUS_TONES: Record<TaskStatus, Tone> = {
  WAITING: 'muted',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'critical',
  SKIPPED: 'neutral',
  CANCELLED: 'muted',
};
export const APPROVAL_STATUS_TONES: Record<string, Tone> = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'critical', CANCELLED: 'muted' };
export const STAGE_MODE_LABELS: Record<StageMode, string> = { SEQUENTIAL: 'Sequential', PARALLEL: 'Parallel' };
export const APPROVER_KIND_LABELS: Record<ApproverKind, string> = { USER: 'User', ROLE: 'Role', TEAM: 'Team', MANAGER: 'Manager' };

/** "ANY of 2", "ALL of 3", "2 of 3". */
export function quorumLabel(quorum: Quorum, requiredCount: number, taskCount: number): string {
  if (quorum === 'ANY') return taskCount > 1 ? `Any 1 of ${taskCount}` : 'One approval';
  if (quorum === 'ALL') return taskCount > 1 ? `All ${taskCount}` : 'One approval';
  return `${requiredCount} of ${taskCount}`;
}

export const APPROVAL_EVENT_LABELS: Record<string, string> = {
  REQUESTED: 'Requested',
  STAGE_STARTED: 'Stage started',
  STAGE_SKIPPED: 'Stage skipped',
  TASK_APPROVED: 'Approved',
  TASK_REJECTED: 'Rejected',
  DELEGATED: 'Delegated',
  STAGE_APPROVED: 'Stage approved',
  STAGE_REJECTED: 'Stage rejected',
  ESCALATED: 'Escalated',
  AUTO_REJECTED: 'Automatically rejected',
  APPROVED: 'Approval granted',
  REJECTED: 'Approval rejected',
  CANCELLED: 'Cancelled',
};

export const FORM_FIELD_TYPES: { value: FormFieldType; label: string }[] = [
  { value: 'text', label: 'Short text' },
  { value: 'textarea', label: 'Long text' },
  { value: 'number', label: 'Number' },
  { value: 'email', label: 'E-mail' },
  { value: 'date', label: 'Date' },
  { value: 'select', label: 'Choice list' },
  { value: 'multiselect', label: 'Multi-select' },
  { value: 'checkbox', label: 'Checkbox' },
  { value: 'file', label: 'File upload' },
  { value: 'user', label: 'User picker' },
];
export const FILE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'pdf', 'docx', 'xlsx', 'txt', 'log', 'csv', 'json'];

export function labelOf<T extends string>(labels: Record<T, string>, value: string | null | undefined): string {
  if (!value) return '';
  return (labels as Record<string, string>)[value] ?? titleCase(value);
}

export function toneOf(tones: Record<string, Tone>, value: string | null | undefined): Tone {
  return (value && tones[value]) || 'neutral';
}

/** Hours as "36 h" / "2.5 d"; null → "—". */
export function formatHours(hours: number | null | undefined): string {
  if (hours === null || hours === undefined || Number.isNaN(hours)) return '—';
  if (hours >= 48) return `${(hours / 24).toFixed(1).replace(/\.0$/, '')} d`;
  return `${hours.toFixed(1).replace(/\.0$/, '')} h`;
}

/** Seconds as "4 min 10 s" / "1 h 5 min". */
export function formatDurationSeconds(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '—';
  const s = Math.max(0, Math.round(seconds));
  if (s < 60) return `${s} s`;
  const minutes = Math.floor(s / 60);
  if (minutes < 60) return `${minutes} min${s % 60 ? ` ${s % 60} s` : ''}`;
  const hours = Math.floor(minutes / 60);
  return `${hours} h${minutes % 60 ? ` ${minutes % 60} min` : ''}`;
}

/** ISO instant → `YYYY-MM-DD` in local time. */
export function localDateKey(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
