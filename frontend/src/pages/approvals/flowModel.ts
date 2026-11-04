import { z } from 'zod';
import { APPROVAL_TYPE_LABELS, ROLES, roleLabel } from '../../lib/constants';
import type { ApprovalFlowDto, ApprovalFlowSaveRequest, ApproverDef, ApprovalSubjectType, StageDef } from '../../lib/itsmTypes';
import type { ConditionOperator, Role } from '../../lib/types';

/**
 * Approval-flow editor model: the form keeps flat, input-friendly drafts (strings for selects, flags for the optional
 * condition / escalation blocks) and converts them to the API's `FlowSaveRequest` (docs/API.md "Approval engine v2").
 */

export const MAX_STAGES = 10;
export const MAX_APPROVERS = 10;
export const MAX_RULES = 20;
export const CODE_PATTERN = /^[A-Z0-9_-]{2,40}$/;
const FACT_PATTERN = /^[a-zA-Z][a-zA-Z0-9_]{0,39}$/;

export const MODE_LABELS = { SEQUENTIAL: 'Sequential (one approver at a time, in order)', PARALLEL: 'Parallel (all approvers at once)' } as const;
export const QUORUM_LABELS = { ANY: 'Any one approver', ALL: 'All approvers', N: 'A number of approvers' } as const;
export const APPROVER_KIND_LABELS = { USER: 'Person', ROLE: 'Role', TEAM: 'Team', MANAGER: "Requester's manager" } as const;
export const CONDITION_OPERATORS: { value: ConditionOperator; label: string }[] = [
  { value: 'EQUALS', label: 'equals' },
  { value: 'NOT_EQUALS', label: 'does not equal' },
  { value: 'IN', label: 'is one of (comma-separated)' },
  { value: 'CONTAINS', label: 'contains' },
  { value: 'GREATER_THAN', label: 'is greater than' },
  { value: 'LESS_THAN', label: 'is less than' },
];
/** Facts the approval engine evaluates for its subjects (free text is allowed too). */
export const CONDITION_FIELD_SUGGESTIONS = ['changeRisk', 'changeType', 'riskScore', 'priority', 'category'];
export const SUBJECT_TYPE_OPTIONS = Object.entries(APPROVAL_TYPE_LABELS).map(([value, label]) => ({ value, label }));

export function subjectTypeLabel(type: string | null | undefined): string {
  if (!type) return 'Any subject';
  return (APPROVAL_TYPE_LABELS as Record<string, string>)[type] ?? type;
}

const optionalInt = (min: number, max: number, label: string) =>
  z
    .number({ invalid_type_error: `${label} must be a number` })
    .int(`${label} must be a whole number`)
    .min(min, `${label}: ${min}–${max}`)
    .max(max, `${label}: ${min}–${max}`)
    .nullable();

export const approverDraftSchema = z
  .object({
    kind: z.enum(['USER', 'ROLE', 'TEAM', 'MANAGER']),
    userId: z.number().nullable(),
    /** Display only (not sent). */
    userName: z.string().nullable(),
    userRole: z.string().nullable(),
    role: z.string(),
    teamId: z.string(),
  })
  .superRefine((approver, ctx) => {
    if (approver.kind === 'USER' && !approver.userId) ctx.addIssue({ code: 'custom', path: ['userId'], message: 'Choose a person' });
    if (approver.kind === 'ROLE' && !approver.role) ctx.addIssue({ code: 'custom', path: ['role'], message: 'Choose a role' });
    if (approver.kind === 'TEAM' && !approver.teamId) ctx.addIssue({ code: 'custom', path: ['teamId'], message: 'Choose a team' });
  });

export const ruleDraftSchema = z.object({
  field: z.string().trim().regex(FACT_PATTERN, 'A fact name: a letter, then letters, digits or _ (max 40)'),
  operator: z.enum(['EQUALS', 'NOT_EQUALS', 'IN', 'CONTAINS', 'GREATER_THAN', 'LESS_THAN']),
  value: z.string().max(500, 'At most 500 characters'),
});

export const stageDraftSchema = z
  .object({
    name: z.string().trim().min(1, 'Give the stage a name').max(120, 'At most 120 characters'),
    mode: z.enum(['SEQUENTIAL', 'PARALLEL']),
    quorum: z.enum(['ANY', 'ALL', 'N']),
    requiredCount: optionalInt(1, 10, 'Required approvals'),
    approvers: z.array(approverDraftSchema).min(1, 'Add at least one approver').max(MAX_APPROVERS, `At most ${MAX_APPROVERS} approvers`),
    deadlineHours: optionalInt(1, 720, 'Deadline'),
    hasCondition: z.boolean(),
    conditionMatch: z.enum(['ALL', 'ANY']),
    conditionRules: z.array(ruleDraftSchema).max(MAX_RULES, `At most ${MAX_RULES} rules`),
    escalate: z.boolean(),
    notifyManager: z.boolean(),
    notifyRole: z.string(),
    autoReject: z.boolean(),
  })
  .superRefine((stage, ctx) => {
    if (stage.quorum === 'N') {
      if (stage.requiredCount == null) ctx.addIssue({ code: 'custom', path: ['requiredCount'], message: 'How many approvals are needed?' });
      else if (stage.requiredCount > stage.approvers.length) ctx.addIssue({ code: 'custom', path: ['requiredCount'], message: 'Cannot exceed the number of approvers' });
    }
    if (stage.escalate && stage.autoReject && stage.deadlineHours == null) ctx.addIssue({ code: 'custom', path: ['deadlineHours'], message: 'Auto-reject needs a deadline' });
    if (stage.hasCondition && stage.conditionRules.length === 0) ctx.addIssue({ code: 'custom', path: ['conditionRules'], message: 'Add at least one rule or remove the condition' });
  });

export const flowFormSchema = z.object({
  code: z.string().trim().regex(CODE_PATTERN, '2–40 capital letters, digits, _ or -'),
  name: z.string().trim().min(1, 'Give the flow a name').max(120, 'At most 120 characters'),
  description: z.string().max(500, 'At most 500 characters'),
  subjectType: z.string(),
  active: z.boolean(),
  stages: z.array(stageDraftSchema).min(1, 'A flow needs at least one stage').max(MAX_STAGES, `At most ${MAX_STAGES} stages`),
});

export type ApproverDraft = z.infer<typeof approverDraftSchema>;
export type RuleDraft = z.infer<typeof ruleDraftSchema>;
export type StageDraft = z.infer<typeof stageDraftSchema>;
export type FlowFormValues = z.infer<typeof flowFormSchema>;

export function emptyApprover(kind: ApproverDraft['kind'] = 'ROLE'): ApproverDraft {
  return { kind, userId: null, userName: null, userRole: null, role: '', teamId: '' };
}

export function emptyRule(): RuleDraft {
  return { field: 'changeRisk', operator: 'EQUALS', value: '' };
}

export function emptyStage(index = 0): StageDraft {
  return {
    name: index === 0 ? 'Approval' : `Stage ${index + 1}`,
    mode: 'PARALLEL',
    quorum: 'ANY',
    requiredCount: null,
    approvers: [emptyApprover()],
    deadlineHours: null,
    hasCondition: false,
    conditionMatch: 'ALL',
    conditionRules: [],
    escalate: false,
    notifyManager: true,
    notifyRole: '',
    autoReject: false,
  };
}

export function emptyFlowValues(): FlowFormValues {
  return { code: '', name: '', description: '', subjectType: '', active: true, stages: [emptyStage()] };
}

type UserNames = Record<string, string> | undefined;

function approverToDraft(approver: ApproverDef, userNames?: UserNames): ApproverDraft {
  return {
    kind: approver.kind,
    userId: approver.userId ?? null,
    userName: (approver.userId != null && userNames?.[String(approver.userId)]) || null,
    userRole: null,
    role: approver.role ?? '',
    teamId: approver.teamId != null ? String(approver.teamId) : '',
  };
}

export function stageToDraft(stage: StageDef, userNames?: UserNames): StageDraft {
  const condition = stage.condition ?? null;
  const escalation = stage.escalation ?? null;
  return {
    name: stage.name ?? '',
    mode: stage.mode ?? 'PARALLEL',
    quorum: stage.quorum ?? 'ANY',
    requiredCount: stage.requiredCount ?? null,
    approvers: (stage.approvers ?? []).map((approver) => approverToDraft(approver, userNames)),
    deadlineHours: stage.deadlineHours ?? null,
    hasCondition: Boolean(condition),
    conditionMatch: condition?.match === 'ANY' ? 'ANY' : 'ALL',
    conditionRules: (condition?.rules ?? []).map((rule) => ({
      field: rule.field ?? '',
      operator: (CONDITION_OPERATORS.some((option) => option.value === rule.operator) ? rule.operator : 'EQUALS') as ConditionOperator,
      value: rule.value ?? '',
    })),
    escalate: Boolean(escalation),
    notifyManager: escalation ? Boolean(escalation.notifyManager) : true,
    notifyRole: escalation?.notifyRole ?? '',
    autoReject: Boolean(escalation?.autoReject),
  };
}

export function flowToValues(flow: ApprovalFlowDto): FlowFormValues {
  return {
    code: flow.code,
    name: flow.name,
    description: flow.description ?? '',
    subjectType: flow.subjectType ?? '',
    active: flow.active,
    stages: flow.stages.length ? flow.stages.map((stage) => stageToDraft(stage, flow.userNames)) : [emptyStage()],
  };
}

function approverFromDraft(draft: ApproverDraft): ApproverDef {
  switch (draft.kind) {
    case 'USER':
      return { kind: 'USER', userId: draft.userId };
    case 'ROLE':
      return { kind: 'ROLE', role: draft.role as Role };
    case 'TEAM':
      return { kind: 'TEAM', teamId: Number(draft.teamId) };
    default:
      return { kind: 'MANAGER' };
  }
}

export function stageFromDraft(draft: StageDraft): StageDef {
  return {
    name: draft.name.trim(),
    mode: draft.mode,
    quorum: draft.quorum,
    requiredCount: draft.quorum === 'N' ? draft.requiredCount : null,
    approvers: draft.approvers.map(approverFromDraft),
    condition: draft.hasCondition
      ? { match: draft.conditionMatch, rules: draft.conditionRules.map((rule) => ({ field: rule.field.trim(), operator: rule.operator, value: rule.value.trim() })) }
      : null,
    deadlineHours: draft.deadlineHours ?? null,
    escalation: draft.escalate ? { notifyManager: draft.notifyManager, notifyRole: (draft.notifyRole || null) as Role | null, autoReject: draft.autoReject } : null,
  };
}

export function toFlowRequest(values: FlowFormValues): ApprovalFlowSaveRequest {
  return {
    code: values.code.trim(),
    name: values.name.trim(),
    description: values.description.trim() || null,
    subjectType: (values.subjectType || null) as ApprovalSubjectType | null,
    stages: values.stages.map(stageFromDraft),
    active: values.active,
  };
}

// ------------------------------------------------------------------ server field errors

export interface MappedFieldErrors {
  /** react-hook-form paths (`stages.1.requiredCount`, `stages.0.approvers.root`, …) → message. */
  fields: { name: string; message: string }[];
  /** Errors without a matching input, already prefixed with their stage ("Stage 2: …"). */
  general: string[];
}

const STAGE_SCALARS = ['name', 'mode', 'quorum', 'requiredCount', 'deadlineHours'];
const APPROVER_FIELDS: Record<string, string> = { kind: 'kind', userId: 'userId', role: 'role', teamId: 'teamId' };
const RULE_FIELDS = ['field', 'operator', 'value'];
const FLOW_FIELDS = ['code', 'name', 'description', 'subjectType', 'active'];

/**
 * Maps the API's `fieldErrors` (`stages[1].requiredCount`, `stages[0].approvers`, `stages[0].approvers[2].userId`,
 * `stages[2].condition.rules[0].field`, `stages[0].escalation.notifyRole`, `code`, …) onto the editor's form paths.
 */
export function mapFlowFieldErrors(fieldErrors: { field: string; message: string }[] | undefined): MappedFieldErrors {
  const result: MappedFieldErrors = { fields: [], general: [] };
  (fieldErrors ?? []).forEach(({ field, message }) => {
    const add = (name: string, text = message) => {
      result.fields.push({ name, message: text });
    };
    if (FLOW_FIELDS.includes(field)) return add(field);
    const stage = /^stages\[(\d+)\](?:\.(.*))?$/.exec(field);
    if (!stage) {
      if (field.startsWith('stages')) return add('stages.root');
      result.general.push(message);
      return;
    }
    const index = Number(stage[1]);
    const rest = stage[2] ?? '';
    const base = `stages.${index}`;
    if (STAGE_SCALARS.includes(rest)) return add(`${base}.${rest}`);
    const approver = /^approvers(?:\[(\d+)\](?:\.(\w+))?)?$/.exec(rest);
    if (approver) {
      const position = approver[1];
      const target = approver[2] ? APPROVER_FIELDS[approver[2]] : undefined;
      if (position !== undefined && target) return add(`${base}.approvers.${position}.${target}`);
      if (position !== undefined) return add(`${base}.approvers.root`, `Approver ${Number(position) + 1}: ${message}`);
      return add(`${base}.approvers.root`);
    }
    if (rest === 'condition.match') return add(`${base}.conditionMatch`);
    const rule = /^condition\.rules\[(\d+)\]\.(\w+)$/.exec(rest);
    if (rule && RULE_FIELDS.includes(rule[2])) return add(`${base}.conditionRules.${rule[1]}.${rule[2]}`);
    if (rest.startsWith('condition')) return add(`${base}.conditionRules.root`);
    if (rest === 'escalation.notifyRole') return add(`${base}.notifyRole`);
    if (rest.startsWith('escalation')) return add(`${base}.escalate`);
    result.general.push(`Stage ${index + 1}: ${message}`);
  });
  return result;
}

// ------------------------------------------------------------------ read-only descriptions

export function describeApprover(approver: ApproverDef, teamName?: (id: number) => string | undefined, userNames?: UserNames): string {
  switch (approver.kind) {
    case 'USER':
      return approver.userId ? (userNames?.[String(approver.userId)] ?? `User #${approver.userId}`) : 'A person';
    case 'ROLE':
      return approver.role ? `Any ${roleLabel(approver.role)}` : 'A role';
    case 'TEAM':
      return approver.teamId ? `Team ${teamName?.(approver.teamId) ?? `#${approver.teamId}`}` : 'A team';
    default:
      return "The requester's manager";
  }
}

export function describeQuorum(stage: Pick<StageDef, 'quorum' | 'requiredCount' | 'approvers'>): string {
  const count = stage.approvers?.length ?? 0;
  if (stage.quorum === 'ALL') return count === 1 ? 'Needs the approver' : `Needs all ${count} approvers`;
  if (stage.quorum === 'N') return `Needs ${stage.requiredCount ?? '?'} of ${count} approvals`;
  return count === 1 ? 'Needs the approver' : 'Needs any one approver';
}

export function describeCondition(stage: Pick<StageDef, 'condition'>): string | null {
  const condition = stage.condition;
  if (!condition || !condition.rules?.length) return null;
  const operator = (value: string) => CONDITION_OPERATORS.find((option) => option.value === value)?.label.replace(' (comma-separated)', '') ?? value;
  return `Only when ${condition.rules.map((rule) => `${rule.field} ${operator(rule.operator)} ${rule.value ?? ''}`.trim()).join(condition.match === 'ANY' ? ' or ' : ' and ')}`;
}

export function describeEscalation(stage: Pick<StageDef, 'deadlineHours' | 'escalation'>): string | null {
  if (!stage.deadlineHours) return null;
  const escalation = stage.escalation;
  if (!escalation) return `Due in ${stage.deadlineHours} h · overdue: IT managers and system admins are notified`;
  const parts: string[] = [];
  if (escalation.notifyManager) parts.push("approvers' managers notified");
  if (escalation.notifyRole) parts.push(`${roleLabel(escalation.notifyRole)}s notified`);
  if (escalation.autoReject) parts.push('automatically rejected');
  return `Due in ${stage.deadlineHours} h${parts.length ? ` · overdue: ${parts.join(', ')}` : ''}`;
}

export const ROLE_OPTIONS = ROLES.map((role) => ({ value: role, label: roleLabel(role) }));
