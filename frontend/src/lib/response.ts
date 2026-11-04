/** Labels and tones for on-call, major incident command and post-incident reviews (Release 3 BE-4). */
import type { Tone } from './constants';
import type {
  ActionItemStatus,
  ActionItemType,
  AssignmentSource,
  EscalationTarget,
  MajorStatus,
  OverrideStatus,
  PirStatus,
  RotationType,
  StakeholderStatus,
  TimelineEntryType,
} from './responseTypes';

export const ROTATION_TYPES: RotationType[] = ['DAILY', 'WEEKLY', 'CUSTOM'];
export const ROTATION_LABELS: Record<RotationType, string> = { DAILY: 'Daily', WEEKLY: 'Weekly', CUSTOM: 'Custom (fixed hours)' };

export const SOURCE_LABELS: Record<AssignmentSource, string> = {
  ROTATION: 'Rotation',
  OVERRIDE: 'Override',
  HOLIDAY_COVER: 'Holiday cover',
  AVAILABILITY: 'Availability',
};
export const SOURCE_TONES: Record<AssignmentSource, Tone> = { ROTATION: 'gold', OVERRIDE: 'champagne', HOLIDAY_COVER: 'warning', AVAILABILITY: 'success' };
export const SOURCE_HINTS: Record<AssignmentSource, string> = {
  ROTATION: 'Scheduled by the rotation',
  OVERRIDE: 'An override replaces the rotation participant',
  HOLIDAY_COVER: 'Holiday cover of the schedule’s holiday calendar',
  AVAILABILITY: 'The rotation participant is unavailable; the next available participant covers',
};

export const OVERRIDE_STATUS_TONES: Record<OverrideStatus, Tone> = { OFFERED: 'warning', ACTIVE: 'success', DECLINED: 'muted', CANCELLED: 'muted' };

export const ESCALATION_TARGETS: EscalationTarget[] = ['PRIMARY', 'SECONDARY', 'MANAGER', 'USER'];
export const ESCALATION_TARGET_LABELS: Record<EscalationTarget, string> = {
  PRIMARY: 'Primary on call',
  SECONDARY: 'Secondary on call',
  MANAGER: 'Team lead and manager',
  USER: 'A named person',
};

/** ISO weekday 1 = Monday … 7 = Sunday (the API's `handoffDay`). */
export const WEEKDAY_LABELS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

export const MAJOR_STATUSES: MajorStatus[] = ['ACTIVE', 'MONITORING', 'RESOLVED', 'CLOSED'];
export const MAJOR_STATUS_LABELS: Record<MajorStatus, string> = { ACTIVE: 'Active', MONITORING: 'Monitoring', RESOLVED: 'Resolved', CLOSED: 'Closed' };
export const MAJOR_STATUS_TONES: Record<MajorStatus, Tone> = { ACTIVE: 'critical', MONITORING: 'warning', RESOLVED: 'success', CLOSED: 'muted' };

export function majorTransitionLabel(from: MajorStatus, to: MajorStatus): string {
  if (to === 'MONITORING') return 'Move to monitoring';
  if (to === 'RESOLVED') return 'Mark resolved';
  if (to === 'CLOSED') return 'Close and open review';
  if (to === 'ACTIVE') return from === 'MONITORING' ? 'Back to active' : 'Reopen';
  return MAJOR_STATUS_LABELS[to];
}

export const TIMELINE_ENTRY_TYPES: TimelineEntryType[] = ['NOTE', 'DECISION', 'ACTION', 'INVESTIGATION'];
export const TIMELINE_LABELS: Record<TimelineEntryType, string> = {
  NOTE: 'Note',
  DECISION: 'Decision',
  ACTION: 'Action',
  INVESTIGATION: 'Investigation',
  STATUS: 'Status',
  ROLE: 'Roles',
  UPDATE: 'Stakeholder update',
  SYSTEM: 'System',
};
export const TIMELINE_TONES: Record<TimelineEntryType, Tone> = {
  NOTE: 'neutral',
  DECISION: 'gold',
  ACTION: 'champagne',
  INVESTIGATION: 'warning',
  STATUS: 'success',
  ROLE: 'neutral',
  UPDATE: 'champagne',
  SYSTEM: 'muted',
};

export const STAKEHOLDER_STATUSES: StakeholderStatus[] = ['INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED'];
export const STAKEHOLDER_TONES: Record<StakeholderStatus, Tone> = { INVESTIGATING: 'critical', IDENTIFIED: 'warning', MONITORING: 'champagne', RESOLVED: 'success' };

export const ACTION_ITEM_TYPES: ActionItemType[] = ['CORRECTIVE', 'PREVENTIVE', 'FOLLOW_UP'];
export const ACTION_ITEM_TYPE_LABELS: Record<ActionItemType, string> = { CORRECTIVE: 'Corrective', PREVENTIVE: 'Preventive', FOLLOW_UP: 'Follow-up' };
export const ACTION_ITEM_STATUSES: ActionItemStatus[] = ['OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED'];
export const ACTION_ITEM_STATUS_LABELS: Record<ActionItemStatus, string> = { OPEN: 'Open', IN_PROGRESS: 'In progress', DONE: 'Done', CANCELLED: 'Cancelled' };
export const ACTION_ITEM_STATUS_TONES: Record<ActionItemStatus, Tone> = { OPEN: 'champagne', IN_PROGRESS: 'warning', DONE: 'success', CANCELLED: 'muted' };

export const PIR_STATUSES: PirStatus[] = ['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED'];
export const PIR_STATUS_LABELS: Record<PirStatus, string> = { DRAFT: 'Draft', IN_REVIEW: 'In review', APPROVED: 'Approved', REJECTED: 'Rejected' };
export const PIR_STATUS_TONES: Record<PirStatus, Tone> = { DRAFT: 'champagne', IN_REVIEW: 'warning', APPROVED: 'success', REJECTED: 'critical' };

export const RISK_LEVEL_TONES: Record<string, Tone> = { LOW: 'success', MEDIUM: 'warning', HIGH: 'critical', 'N/A': 'muted' };
