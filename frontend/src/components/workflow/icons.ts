import {
  ArrowRightLeft,
  Bell,
  BellRing,
  CalendarClock,
  ChevronsUp,
  ClipboardList,
  Cog,
  FilePlus2,
  Flag,
  GitPullRequestArrow,
  MessageSquare,
  PencilLine,
  Radar,
  ShieldAlert,
  Stamp,
  Siren,
  Timer,
  TimerOff,
  TriangleAlert,
  UserCheck,
  UserPlus,
  Users,
  UsersRound,
  Webhook,
  Zap,
  type LucideIcon,
} from 'lucide-react';
import type { WorkflowActionType, WorkflowTrigger } from '../../lib/types';

export const TRIGGER_ICONS: Record<WorkflowTrigger, LucideIcon> = {
  INCIDENT_CREATED: TriangleAlert,
  INCIDENT_UPDATED: PencilLine,
  INCIDENT_STATUS_CHANGED: ArrowRightLeft,
  INCIDENT_ASSIGNED: UserCheck,
  PRIORITY_CHANGED: ChevronsUp,
  SLA_AT_RISK: Timer,
  SLA_BREACHED: TimerOff,
  SERVICE_REQUEST_CREATED: ClipboardList,
  SCHEDULED: CalendarClock,
  CHANGE_REQUEST_CREATED: GitPullRequestArrow,
  ALERT_RECEIVED: Radar,
};

export const ACTION_ICONS: Record<WorkflowActionType, LucideIcon> = {
  ASSIGN_DEPARTMENT: Users,
  ASSIGN_USER: UserPlus,
  SET_PRIORITY: Flag,
  CHANGE_STATUS: ArrowRightLeft,
  NOTIFY_ROLE: BellRing,
  NOTIFY_DEPARTMENT: Bell,
  NOTIFY_ASSIGNEE: Bell,
  NOTIFY_USER: Bell,
  ADD_COMMENT: MessageSquare,
  ESCALATE: ShieldAlert,
  START_RUNBOOK: Cog,
  SEND_WEBHOOK: Webhook,
  DECLARE_MAJOR: Siren,
  ASSIGN_TEAM: UsersRound,
  REQUEST_APPROVAL: Stamp,
  CREATE_INCIDENT: FilePlus2,
};

export function triggerIcon(trigger: string): LucideIcon {
  return (TRIGGER_ICONS as Record<string, LucideIcon>)[trigger] ?? Zap;
}

export function actionIcon(type: string): LucideIcon {
  return (ACTION_ICONS as Record<string, LucideIcon>)[type] ?? Zap;
}
