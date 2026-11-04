/**
 * Pure mapping between a stored `WorkflowDefinition` and the visual builder's canvas model (spec §6, §15.7).
 *
 * Canvas: trigger card → condition diamond → "Yes" branch (`actions`) and "No" branch (`elseActions`).
 * Node positions are kept in `definition.layout` under the keys `trigger`, `condition`, `yes-<i>` and `no-<i>`.
 * Only nodes the user placed explicitly are stored; every other node is laid out relative to its predecessor,
 * so dragging a node carries its unplaced followers with it.
 *
 * No React in here — everything is unit tested in model.test.ts.
 */
import { MIN_SCHEDULE_INTERVAL_MINUTES, PRIORITY_LABELS, STATUS_LABELS, WORKFLOW_TRIGGER_LABELS, roleLabel } from '../../lib/constants';
import { titleCase } from '../../lib/format';
import type {
  AiResult,
  ConditionField,
  ConditionOperator,
  ConditionRule,
  WorkflowAction,
  WorkflowActionType,
  WorkflowDefinition,
  WorkflowDto,
  WorkflowMeta,
  WorkflowRequest,
  WorkflowSuggestion,
  WorkflowTemplate,
  WorkflowTrigger,
} from '../../lib/types';

// ------------------------------------------------------------------ types

export type Branch = 'yes' | 'no';
export type MatchMode = 'ALL' | 'ANY';

export interface Point {
  x: number;
  y: number;
}

export interface ModelAction {
  /** Client-side identity (stable across reorders); never sent to the API. */
  uid: string;
  type: WorkflowActionType;
  params: Record<string, string>;
}

export interface WorkflowModel {
  trigger: WorkflowTrigger;
  /** schedule.intervalMinutes — only meaningful (and only saved) for the SCHEDULED trigger. */
  intervalMinutes: number | null;
  match: MatchMode;
  rules: ConditionRule[];
  /** "Yes" branch — `definition.actions`. */
  yes: ModelAction[];
  /** "No" branch — `definition.elseActions`. */
  no: ModelAction[];
  /** Explicitly placed nodes, keyed by node id (`trigger`, `condition` or an action uid). */
  positions: Record<string, Point>;
}

export interface WorkflowLimits {
  maxExecutionsPerHour: number;
  maxRetries: number;
  retryBackoffSeconds: number;
}

/** Everything the builder edits: workflow settings plus the canvas model. */
export interface WorkflowDraft extends WorkflowLimits {
  name: string;
  description: string;
  active: boolean;
  model: WorkflowModel;
}

export const TRIGGER_NODE = 'trigger';
export const CONDITION_NODE = 'condition';

export const DEFAULT_TRIGGER: WorkflowTrigger = 'INCIDENT_CREATED';
export const DEFAULT_LIMITS: WorkflowLimits = { maxExecutionsPerHour: 60, maxRetries: 3, retryBackoffSeconds: 60 };
export const DEFAULT_SCHEDULE_MINUTES = 60;
export const MAX_SCHEDULE_MINUTES = 10_080;
export const MAX_ACTIONS_PER_BRANCH = 20;
export const MAX_RULES = 20;
export const NAME_MIN = 3;
export const NAME_MAX = 150;
export const DESCRIPTION_MAX = 1000;
export const LIMIT_RANGES: Record<keyof WorkflowLimits, { min: number; max: number; label: string }> = {
  maxExecutionsPerHour: { min: 1, max: 10_000, label: 'Max executions per hour' },
  maxRetries: { min: 0, max: 10, label: 'Max retries' },
  retryBackoffSeconds: { min: 1, max: 3600, label: 'Retry backoff (seconds)' },
};

// ------------------------------------------------------------------ labels

/** Palette / canvas labels (spec §6 wording). Unknown types fall back to the meta label. */
export const ACTION_LABELS: Record<WorkflowActionType, string> = {
  ASSIGN_DEPARTMENT: 'Assign team',
  ASSIGN_USER: 'Assign agent',
  SET_PRIORITY: 'Change priority',
  CHANGE_STATUS: 'Change status',
  NOTIFY_ROLE: 'Send notification to role',
  NOTIFY_DEPARTMENT: 'Send notification to team',
  NOTIFY_ASSIGNEE: 'Send notification to assignee',
  NOTIFY_USER: 'Send notification to user',
  ADD_COMMENT: 'Add comment',
  ESCALATE: 'Create escalation',
  START_RUNBOOK: 'Trigger approved runbook',
  SEND_WEBHOOK: 'Send webhook',
  DECLARE_MAJOR: 'Declare major',
  ASSIGN_TEAM: 'Assign to team',
  REQUEST_APPROVAL: 'Request approval',
  CREATE_INCIDENT: 'Create incident',
};

export const ACTION_DESCRIPTIONS: Record<WorkflowActionType, string> = {
  ASSIGN_DEPARTMENT: 'Route the incident to a support team',
  ASSIGN_USER: 'Assign the incident to a named agent',
  SET_PRIORITY: 'Raise or lower the incident priority',
  CHANGE_STATUS: 'Move the incident to another status (valid transitions only)',
  NOTIFY_ROLE: 'Notify everyone with a role',
  NOTIFY_DEPARTMENT: "Notify a team (default: the incident's team)",
  NOTIFY_ASSIGNEE: 'Notify the current assignee',
  NOTIFY_USER: 'Notify one person by e-mail address',
  ADD_COMMENT: 'Post a public comment or internal work note',
  ESCALATE: 'Escalate to the next support level',
  START_RUNBOOK: 'Start an approved runbook (approval-gated runbooks go to approval)',
  SEND_WEBHOOK: 'Call an outbound integration',
  DECLARE_MAJOR: 'Declare a major incident',
  ASSIGN_TEAM: 'Route to a team and its least-loaded member (else the team queue)',
  REQUEST_APPROVAL: 'Request an approval through an approval flow (changes: CAB approval)',
  CREATE_INCIDENT: "Alert Received only: convert the alert into an incident (intake, duplicates; joins the group's open incident)",
};

/** Palette order (spec §6 list). */
export const ACTION_ORDER: WorkflowActionType[] = [
  'ASSIGN_DEPARTMENT',
  'ASSIGN_USER',
  'SET_PRIORITY',
  'CHANGE_STATUS',
  'NOTIFY_ROLE',
  'NOTIFY_DEPARTMENT',
  'NOTIFY_ASSIGNEE',
  'NOTIFY_USER',
  'ADD_COMMENT',
  'ESCALATE',
  'START_RUNBOOK',
  'SEND_WEBHOOK',
  'DECLARE_MAJOR',
];

export const TRIGGER_DESCRIPTIONS: Record<WorkflowTrigger, string> = {
  INCIDENT_CREATED: 'When an incident is created',
  INCIDENT_UPDATED: 'When an incident is updated',
  INCIDENT_STATUS_CHANGED: 'When an incident changes status',
  INCIDENT_ASSIGNED: 'When an incident is assigned',
  PRIORITY_CHANGED: 'When an incident priority changes',
  SLA_AT_RISK: 'When a resolution SLA is at risk',
  SLA_BREACHED: 'When an SLA is breached',
  SERVICE_REQUEST_CREATED: 'When a service request is raised',
  SCHEDULED: 'On a schedule, for every active incident',
  CHANGE_REQUEST_CREATED: 'When a change request is raised',
  ALERT_RECEIVED: 'When a new or reopened alert arrives (suppressed alerts do not trigger)',
};

export function triggerLabel(trigger: string): string {
  return (WORKFLOW_TRIGGER_LABELS as Record<string, string>)[trigger] ?? titleCase(trigger);
}

export function actionLabel(type: string, meta?: WorkflowMeta | null): string {
  return (ACTION_LABELS as Record<string, string>)[type] ?? meta?.actions.find((entry) => entry.type === type)?.label ?? titleCase(type);
}

export const OPERATOR_SYMBOLS: Record<ConditionOperator, string> = {
  EQUALS: '=',
  NOT_EQUALS: '≠',
  IN: 'in',
  CONTAINS: 'contains',
  GREATER_THAN: '>',
  LESS_THAN: '<',
};

export const OPERATOR_LABELS: Record<ConditionOperator, string> = {
  EQUALS: 'equals',
  NOT_EQUALS: 'does not equal',
  IN: 'is one of',
  CONTAINS: 'contains',
  GREATER_THAN: 'is greater than',
  LESS_THAN: 'is less than',
};

/** Condition fields compared as numbers (GREATER_THAN / LESS_THAN only make sense here). */
export const NUMERIC_FIELDS: ConditionField[] = ['slaRemainingMinutes', 'affectedUsers', 'escalationLevel', 'childCount', 'occurrenceCount'];

export function isNumericField(field: string): boolean {
  return (NUMERIC_FIELDS as string[]).includes(field);
}

export function fieldLabel(field: string, meta?: WorkflowMeta | null): string {
  return meta?.fields.find((entry) => entry.field === field)?.label ?? titleCase(field);
}

/** Human label for an enum value used in conditions or parameters (CRITICAL → Critical, IN_PROGRESS → In Progress). */
export function valueLabel(value: string, field?: string): string {
  const trimmed = value.trim();
  if (!trimmed) return '';
  if (field === 'priority' && trimmed in PRIORITY_LABELS) return PRIORITY_LABELS[trimmed as keyof typeof PRIORITY_LABELS];
  if ((field === 'status' || field === undefined) && trimmed in STATUS_LABELS) return STATUS_LABELS[trimmed as keyof typeof STATUS_LABELS];
  if (field === 'role') return roleLabel(trimmed);
  if (field === 'major' || field === 'linkedToParent' || field === 'internal') return trimmed === 'true' ? 'Yes' : trimmed === 'false' ? 'No' : trimmed;
  if (/^[A-Z][A-Z0-9_]*$/.test(trimmed) && fieldHasEnumValues(field)) return titleCase(trimmed);
  return trimmed;
}

const ENUM_FIELDS = new Set(['priority', 'status', 'category', 'impact', 'urgency', 'source', 'role', 'resolutionCode', 'alertSeverity', 'alertSource', 'ciType', 'changeRisk', 'changeType']);
function fieldHasEnumValues(field: string | undefined): boolean {
  return field === undefined || ENUM_FIELDS.has(field);
}

// ------------------------------------------------------------------ ids

let uidSequence = 0;
/** New client id for an action card. */
export function nextUid(): string {
  uidSequence += 1;
  return `a${uidSequence}`;
}

// ------------------------------------------------------------------ definition ↔ model

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function cleanParams(params: unknown): Record<string, string> {
  if (!isRecord(params)) return {};
  const result: Record<string, string> = {};
  Object.entries(params).forEach(([key, value]) => {
    if (value === null || value === undefined) return;
    result[key] = String(value);
  });
  return result;
}

function readActions(list: unknown): ModelAction[] {
  if (!Array.isArray(list)) return [];
  return list
    .filter((entry): entry is WorkflowAction => isRecord(entry) && typeof entry.type === 'string' && entry.type.length > 0)
    .map((entry) => ({ uid: nextUid(), type: entry.type, params: cleanParams(entry.params) }));
}

function readRules(list: unknown): ConditionRule[] {
  if (!Array.isArray(list)) return [];
  return list
    .filter((entry): entry is ConditionRule => isRecord(entry) && typeof entry.field === 'string')
    .map((entry) => ({
      field: entry.field,
      operator: (typeof entry.operator === 'string' ? entry.operator : 'EQUALS') as ConditionOperator,
      value: entry.value === null || entry.value === undefined ? '' : String(entry.value),
    }));
}

function readPoint(value: unknown): Point | null {
  if (!isRecord(value)) return null;
  const x = Number(value.x);
  const y = Number(value.y);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
  return { x: Math.max(0, Math.round(x)), y: Math.max(0, Math.round(y)) };
}

const LAYOUT_ACTION_KEY = /^(yes|no)-(\d+)$/;

/** Builds the canvas model from a stored definition (defensive: tolerates missing or malformed parts). */
export function fromDefinition(definition: Partial<WorkflowDefinition> | null | undefined, trigger: WorkflowTrigger = DEFAULT_TRIGGER): WorkflowModel {
  const def: Record<string, unknown> = isRecord(definition) ? definition : {};
  const conditions: Record<string, unknown> = isRecord(def.conditions) ? def.conditions : {};
  const yes = readActions(def.actions);
  const no = readActions(def.elseActions);
  const interval = isRecord(def.schedule) ? Number(def.schedule.intervalMinutes) : NaN;
  const positions: Record<string, Point> = {};
  if (isRecord(def.layout)) {
    Object.entries(def.layout).forEach(([key, value]) => {
      const point = readPoint(value);
      if (!point) return;
      if (key === TRIGGER_NODE || key === CONDITION_NODE) {
        positions[key] = point;
        return;
      }
      const match = LAYOUT_ACTION_KEY.exec(key);
      if (!match) return;
      const action = (match[1] === 'yes' ? yes : no)[Number(match[2])];
      if (action) positions[action.uid] = point;
    });
  }
  return {
    trigger,
    intervalMinutes: Number.isFinite(interval) && interval > 0 ? Math.round(interval) : trigger === 'SCHEDULED' ? DEFAULT_SCHEDULE_MINUTES : null,
    match: conditions.match === 'ANY' ? 'ANY' : 'ALL',
    rules: readRules(conditions.rules),
    yes,
    no,
    positions,
  };
}

function sortedParams(params: Record<string, string>): Record<string, string> {
  const result: Record<string, string> = {};
  Object.keys(params)
    .sort()
    .forEach((key) => {
      const value = params[key]?.trim();
      if (value) result[key] = value;
    });
  return result;
}

/** Serialises the canvas model into the API definition (empty params dropped, schedule only for SCHEDULED). */
export function toDefinition(model: WorkflowModel): WorkflowDefinition {
  const layout: Record<string, Point> = {};
  const pin = (key: string, id: string) => {
    const point = model.positions[id];
    if (point) layout[key] = { x: Math.round(point.x), y: Math.round(point.y) };
  };
  pin(TRIGGER_NODE, TRIGGER_NODE);
  pin(CONDITION_NODE, CONDITION_NODE);
  model.yes.forEach((action, index) => pin(`yes-${index}`, action.uid));
  model.no.forEach((action, index) => pin(`no-${index}`, action.uid));
  return {
    conditions: {
      match: model.match,
      rules: model.rules.map((rule) => ({ field: rule.field, operator: rule.operator, value: normaliseRuleValue(rule) })),
    },
    actions: model.yes.map((action) => ({ type: action.type, params: sortedParams(action.params) })),
    elseActions: model.no.map((action) => ({ type: action.type, params: sortedParams(action.params) })),
    schedule: model.trigger === 'SCHEDULED' && model.intervalMinutes ? { intervalMinutes: model.intervalMinutes } : null,
    layout: Object.keys(layout).length ? layout : null,
  };
}

function normaliseRuleValue(rule: ConditionRule): string {
  if (rule.operator === 'IN') return splitValues(rule.value).join(',');
  return rule.value.trim();
}

/** "A, B ,,C" → ["A","B","C"]. */
export function splitValues(value: string): string[] {
  return value
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
}

export function emptyModel(trigger: WorkflowTrigger = DEFAULT_TRIGGER): WorkflowModel {
  return {
    trigger,
    intervalMinutes: trigger === 'SCHEDULED' ? DEFAULT_SCHEDULE_MINUTES : null,
    match: 'ALL',
    rules: [],
    yes: [],
    no: [],
    positions: {},
  };
}

// ------------------------------------------------------------------ drafts

export function emptyDraft(): WorkflowDraft {
  return { name: '', description: '', active: false, ...DEFAULT_LIMITS, model: emptyModel() };
}

export function draftFromWorkflow(workflow: WorkflowDto): WorkflowDraft {
  return {
    name: workflow.name,
    description: workflow.description ?? '',
    active: workflow.active,
    maxExecutionsPerHour: workflow.maxExecutionsPerHour,
    maxRetries: workflow.maxRetries,
    retryBackoffSeconds: workflow.retryBackoffSeconds,
    model: fromDefinition(workflow.definition, workflow.triggerEvent),
  };
}

/** Template or SA AI suggestion → unsaved, inactive draft with default limits. */
export function draftFromBlueprint(blueprint: Pick<WorkflowTemplate, 'name' | 'description' | 'triggerEvent' | 'definition'>): WorkflowDraft {
  return {
    name: blueprint.name ?? '',
    description: blueprint.description ?? '',
    active: false,
    ...DEFAULT_LIMITS,
    model: fromDefinition(blueprint.definition, blueprint.triggerEvent ?? DEFAULT_TRIGGER),
  };
}

export function toRequest(draft: WorkflowDraft, changeNote?: string): WorkflowRequest {
  const note = changeNote?.trim();
  const description = draft.description.trim();
  return {
    name: draft.name.trim(),
    description: description || undefined,
    triggerEvent: draft.model.trigger,
    active: draft.active,
    maxExecutionsPerHour: draft.maxExecutionsPerHour,
    maxRetries: draft.maxRetries,
    retryBackoffSeconds: draft.retryBackoffSeconds,
    definition: toDefinition(draft.model),
    ...(note ? { changeNote: note } : {}),
  };
}

/**
 * Stable fingerprint for "unsaved changes" detection. `active` is left out: for saved workflows
 * activation is an immediate action of its own, not part of the next version.
 */
export function draftFingerprint(draft: WorkflowDraft): string {
  const { active: _active, ...rest } = toRequest(draft);
  return JSON.stringify(rest);
}

// ------------------------------------------------------------------ edits (all pure)

export function setTrigger(model: WorkflowModel, trigger: WorkflowTrigger): WorkflowModel {
  return {
    ...model,
    trigger,
    intervalMinutes: trigger === 'SCHEDULED' ? (model.intervalMinutes ?? DEFAULT_SCHEDULE_MINUTES) : model.intervalMinutes,
  };
}

export function setScheduleInterval(model: WorkflowModel, minutes: number | null): WorkflowModel {
  return { ...model, intervalMinutes: minutes === null || Number.isNaN(minutes) ? null : Math.round(minutes) };
}

export function setMatch(model: WorkflowModel, match: MatchMode): WorkflowModel {
  return { ...model, match };
}

export function addRule(model: WorkflowModel, rule: ConditionRule): WorkflowModel {
  return { ...model, rules: [...model.rules, rule] };
}

export function updateRule(model: WorkflowModel, index: number, patch: Partial<ConditionRule>): WorkflowModel {
  return { ...model, rules: model.rules.map((rule, i) => (i === index ? { ...rule, ...patch } : rule)) };
}

export function removeRule(model: WorkflowModel, index: number): WorkflowModel {
  return { ...model, rules: model.rules.filter((_, i) => i !== index) };
}

export function findAction(model: WorkflowModel, uid: string): { branch: Branch; index: number; action: ModelAction } | null {
  const yesIndex = model.yes.findIndex((action) => action.uid === uid);
  if (yesIndex >= 0) return { branch: 'yes', index: yesIndex, action: model.yes[yesIndex] };
  const noIndex = model.no.findIndex((action) => action.uid === uid);
  if (noIndex >= 0) return { branch: 'no', index: noIndex, action: model.no[noIndex] };
  return null;
}

function withBranch(model: WorkflowModel, branch: Branch, list: ModelAction[]): WorkflowModel {
  return branch === 'yes' ? { ...model, yes: list } : { ...model, no: list };
}

/** Appends (or inserts at `index`) a new action; returns the model and the new card's uid. */
export function addAction(
  model: WorkflowModel,
  branch: Branch,
  type: WorkflowActionType,
  params: Record<string, string> = {},
  index?: number,
): { model: WorkflowModel; uid: string } {
  const action: ModelAction = { uid: nextUid(), type, params: { ...params } };
  const list = [...model[branch]];
  const at = index === undefined ? list.length : Math.max(0, Math.min(index, list.length));
  list.splice(at, 0, action);
  return { model: withBranch(model, branch, list), uid: action.uid };
}

/** Changing the type resets the parameters (they belong to the old type). */
export function updateAction(model: WorkflowModel, uid: string, patch: { type?: WorkflowActionType; params?: Record<string, string> }): WorkflowModel {
  const found = findAction(model, uid);
  if (!found) return model;
  const typeChanged = patch.type !== undefined && patch.type !== found.action.type;
  const next: ModelAction = {
    ...found.action,
    type: patch.type ?? found.action.type,
    params: patch.params !== undefined ? { ...patch.params } : typeChanged ? {} : found.action.params,
  };
  const list = model[found.branch].map((action) => (action.uid === uid ? next : action));
  return withBranch(model, found.branch, list);
}

export function setActionParam(model: WorkflowModel, uid: string, key: string, value: string): WorkflowModel {
  const found = findAction(model, uid);
  if (!found) return model;
  const params = { ...found.action.params };
  if (value === '') delete params[key];
  else params[key] = value;
  return updateAction(model, uid, { params });
}

export function removeAction(model: WorkflowModel, uid: string): WorkflowModel {
  const found = findAction(model, uid);
  if (!found) return model;
  const positions = { ...model.positions };
  delete positions[uid];
  return {
    ...withBranch(
      model,
      found.branch,
      model[found.branch].filter((action) => action.uid !== uid),
    ),
    positions,
  };
}

/** Moves an action up (-1) or down (+1) inside its branch; placed positions swap so the slots stay put. */
export function moveAction(model: WorkflowModel, uid: string, delta: -1 | 1): WorkflowModel {
  const found = findAction(model, uid);
  if (!found) return model;
  const target = found.index + delta;
  const list = [...model[found.branch]];
  if (target < 0 || target >= list.length) return model;
  const other = list[target];
  list[target] = found.action;
  list[found.index] = other;
  const positions = { ...model.positions };
  const mine = positions[uid];
  const theirs = positions[other.uid];
  delete positions[uid];
  delete positions[other.uid];
  if (theirs) positions[uid] = theirs;
  if (mine) positions[other.uid] = mine;
  return { ...withBranch(model, found.branch, list), positions };
}

/** Moves an action to the end of the other branch (it then flows with that branch's layout). */
export function moveActionToBranch(model: WorkflowModel, uid: string, branch: Branch): WorkflowModel {
  const found = findAction(model, uid);
  if (!found || found.branch === branch) return model;
  const removed = withBranch(
    model,
    found.branch,
    model[found.branch].filter((action) => action.uid !== uid),
  );
  const positions = { ...model.positions };
  delete positions[uid];
  return { ...withBranch(removed, branch, [...removed[branch], found.action]), positions };
}

export function setPosition(model: WorkflowModel, nodeId: string, point: Point): WorkflowModel {
  return { ...model, positions: { ...model.positions, [nodeId]: { x: Math.max(0, Math.round(point.x)), y: Math.max(0, Math.round(point.y)) } } };
}

/** Forgets every placed position (automatic layout). */
export function resetLayout(model: WorkflowModel): WorkflowModel {
  return { ...model, positions: {} };
}

// ------------------------------------------------------------------ layout

export type CanvasNodeKind = 'trigger' | 'condition' | 'action' | 'slot';

export interface CanvasNode {
  id: string;
  kind: CanvasNodeKind;
  branch: Branch | null;
  /** Position inside the branch (actions) or the branch length (slots). */
  index: number;
  x: number;
  y: number;
  w: number;
  h: number;
  /** True when the position comes from `positions` (placed by the user or loaded from layout). */
  placed: boolean;
}

export interface CanvasEdge {
  id: string;
  from: string;
  to: string;
  /** SVG path data. */
  d: string;
  label: 'Yes' | 'No' | null;
  labelX: number;
  labelY: number;
  /** Dashed connector to an "add action" slot. */
  dashed: boolean;
}

export const NODE_SIZE: Record<CanvasNodeKind, { w: number; h: number }> = {
  trigger: { w: 236, h: 74 },
  condition: { w: 220, h: 116 },
  action: { w: 212, h: 62 },
  slot: { w: 212, h: 44 },
};

export const CANVAS_PADDING = 28;
const CENTER_X = 330;
const TOP = 24;
const GAP_TRIGGER_CONDITION = 46;
const GAP_CONDITION_BRANCH = 58;
const GAP_ACTIONS = 30;
/** Horizontal distance from the diamond's centre to each branch column's centre. */
export const BRANCH_OFFSET = 180;
const MIN_CANVAS = { w: 660, h: 500 };

export function slotId(branch: Branch): string {
  return `slot-${branch}`;
}

/** Positions every node: placed nodes where the user put them, the rest relative to their predecessor. */
export function layoutNodes(model: WorkflowModel, options: { slots?: boolean } = {}): CanvasNode[] {
  const slots = options.slots ?? true;
  const nodes: CanvasNode[] = [];
  const place = (id: string, kind: CanvasNodeKind, branch: Branch | null, index: number, fallback: Point): CanvasNode => {
    const placed = kind === 'slot' ? undefined : model.positions[id];
    const node: CanvasNode = { id, kind, branch, index, ...(placed ?? fallback), ...NODE_SIZE[kind], placed: Boolean(placed) };
    nodes.push(node);
    return node;
  };

  const trigger = place(TRIGGER_NODE, 'trigger', null, 0, { x: CENTER_X - NODE_SIZE.trigger.w / 2, y: TOP });
  const condition = place(CONDITION_NODE, 'condition', null, 0, {
    x: trigger.x + trigger.w / 2 - NODE_SIZE.condition.w / 2,
    y: trigger.y + trigger.h + GAP_TRIGGER_CONDITION,
  });
  const conditionCenter = condition.x + condition.w / 2;

  (['yes', 'no'] as Branch[]).forEach((branch) => {
    const columnCenter = conditionCenter + (branch === 'yes' ? -BRANCH_OFFSET : BRANCH_OFFSET);
    let previous: CanvasNode | null = null;
    model[branch].forEach((action, index) => {
      const fallback: Point = previous
        ? { x: previous.x + previous.w / 2 - NODE_SIZE.action.w / 2, y: previous.y + previous.h + GAP_ACTIONS }
        : { x: columnCenter - NODE_SIZE.action.w / 2, y: condition.y + condition.h + GAP_CONDITION_BRANCH };
      previous = place(action.uid, 'action', branch, index, fallback);
    });
    if (slots) {
      const last = previous as CanvasNode | null;
      place(
        slotId(branch),
        'slot',
        branch,
        model[branch].length,
        last
          ? { x: last.x + last.w / 2 - NODE_SIZE.slot.w / 2, y: last.y + last.h + GAP_ACTIONS }
          : { x: columnCenter - NODE_SIZE.slot.w / 2, y: condition.y + condition.h + GAP_CONDITION_BRANCH },
      );
    }
  });

  // Never let a fallback position go off the top/left edge.
  nodes.forEach((node) => {
    node.x = Math.max(0, Math.round(node.x));
    node.y = Math.max(0, Math.round(node.y));
  });
  return nodes;
}

function center(node: CanvasNode): Point {
  return { x: node.x + node.w / 2, y: node.y + node.h / 2 };
}

/** Elbow connector from the bottom centre of `from` to the top centre of `to`. */
function verticalPath(from: CanvasNode, to: CanvasNode): { d: string; mid: Point } {
  const x1 = from.x + from.w / 2;
  const y1 = from.y + from.h;
  const x2 = to.x + to.w / 2;
  const y2 = to.y;
  if (Math.abs(x1 - x2) < 1) return { d: `M ${x1} ${y1} V ${y2}`, mid: { x: x1, y: (y1 + y2) / 2 } };
  const ym = Math.round((y1 + y2) / 2);
  return { d: `M ${x1} ${y1} V ${ym} H ${x2} V ${y2}`, mid: { x: x2, y: ym } };
}

/** Out of the diamond's left/right vertex, across, then down to the top centre of the target. */
function branchPath(condition: CanvasNode, to: CanvasNode, branch: Branch): { d: string; label: Point } {
  const c = center(condition);
  const x1 = branch === 'yes' ? condition.x : condition.x + condition.w;
  const y1 = c.y;
  const x2 = to.x + to.w / 2;
  const y2 = to.y;
  if (y2 <= y1 + 8) {
    // Target dragged level with / above the diamond: go straight across to its side.
    const side = x2 < x1 ? to.x + to.w : to.x;
    const ty = to.y + to.h / 2;
    return { d: `M ${x1} ${y1} H ${Math.round((x1 + side) / 2)} V ${ty} H ${side}`, label: { x: Math.round((x1 + side) / 2), y: y1 - 8 } };
  }
  return { d: `M ${x1} ${y1} H ${x2} V ${y2}`, label: { x: x2 + (branch === 'yes' ? 16 : -16), y: Math.round(y1 + (y2 - y1) / 2) } };
}

export function layoutEdges(nodes: CanvasNode[]): CanvasEdge[] {
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const trigger = byId.get(TRIGGER_NODE);
  const condition = byId.get(CONDITION_NODE);
  const edges: CanvasEdge[] = [];
  if (trigger && condition) {
    const path = verticalPath(trigger, condition);
    edges.push({ id: 'trigger->condition', from: trigger.id, to: condition.id, d: path.d, label: null, labelX: path.mid.x, labelY: path.mid.y, dashed: false });
  }
  if (!condition) return edges;
  (['yes', 'no'] as Branch[]).forEach((branch) => {
    const chain = nodes.filter((node) => node.branch === branch).sort((a, b) => a.index - b.index || (a.kind === 'slot' ? 1 : -1));
    chain.forEach((node, position) => {
      if (position === 0) {
        const path = branchPath(condition, node, branch);
        edges.push({
          id: `condition->${node.id}`,
          from: condition.id,
          to: node.id,
          d: path.d,
          label: branch === 'yes' ? 'Yes' : 'No',
          labelX: path.label.x,
          labelY: path.label.y,
          dashed: node.kind === 'slot',
        });
        return;
      }
      const previous = chain[position - 1];
      const path = verticalPath(previous, node);
      edges.push({
        id: `${previous.id}->${node.id}`,
        from: previous.id,
        to: node.id,
        d: path.d,
        label: null,
        labelX: path.mid.x,
        labelY: path.mid.y,
        dashed: node.kind === 'slot',
      });
    });
  });
  return edges;
}

export function canvasSize(nodes: CanvasNode[]): { width: number; height: number } {
  const width = nodes.reduce((max, node) => Math.max(max, node.x + node.w), 0) + CANVAS_PADDING;
  const height = nodes.reduce((max, node) => Math.max(max, node.y + node.h), 0) + CANVAS_PADDING;
  return { width: Math.max(MIN_CANVAS.w, width), height: Math.max(MIN_CANVAS.h, height) };
}

/** Which branch a drop at canvas x-coordinate `x` belongs to: left of the diamond's centre = Yes. */
export function branchAt(nodes: CanvasNode[], x: number): Branch {
  const condition = nodes.find((node) => node.id === CONDITION_NODE);
  if (!condition) return 'yes';
  return x < condition.x + condition.w / 2 ? 'yes' : 'no';
}

// ------------------------------------------------------------------ summaries

export function ruleSummary(rule: ConditionRule, meta?: WorkflowMeta | null): string {
  const label = fieldLabel(rule.field, meta);
  const symbol = OPERATOR_SYMBOLS[rule.operator] ?? rule.operator;
  const value =
    rule.operator === 'IN'
      ? splitValues(rule.value)
          .map((part) => valueLabel(part, rule.field))
          .join(', ')
      : valueLabel(rule.value, rule.field);
  return `${label} ${symbol} ${value || '…'}`;
}

/** Text for the condition diamond, e.g. ["Priority = Critical?"] or ["Priority = Critical", "+1 more (all)"]. */
export function conditionLines(model: WorkflowModel, meta?: WorkflowMeta | null): { headline: string; more: string | null; full: string } {
  if (model.rules.length === 0) return { headline: 'Always (no conditions)', more: null, full: 'No conditions: every event takes the Yes branch' };
  const joiner = model.match === 'ALL' ? ' and ' : ' or ';
  const summaries = model.rules.map((rule) => ruleSummary(rule, meta));
  const full = `${summaries.join(joiner)}?`;
  if (summaries.length === 1) return { headline: `${summaries[0]}?`, more: null, full };
  return { headline: summaries[0], more: `+${summaries.length - 1} more (${model.match === 'ALL' ? 'all' : 'any'} must match)`, full };
}

export function formatInterval(minutes: number | null | undefined): string {
  if (!minutes || minutes <= 0) return 'No interval set';
  if (minutes % 1440 === 0) return minutes === 1440 ? 'Every day' : `Every ${minutes / 1440} days`;
  if (minutes % 60 === 0) return minutes === 60 ? 'Every hour' : `Every ${minutes / 60} h`;
  if (minutes > 60) return `Every ${Math.floor(minutes / 60)} h ${minutes % 60} min`;
  return `Every ${minutes} min`;
}

/** Second line of the trigger card ("Every 4 h", "Service: PAYROLL" or the trigger description). */
export function triggerDetail(model: WorkflowModel): string {
  if (model.trigger === 'SCHEDULED') return formatInterval(model.intervalMinutes);
  const service = model.rules.find((rule) => rule.field === 'serviceCode' && rule.operator === 'EQUALS' && rule.value.trim());
  if (service && model.match === 'ALL') return `Service: ${service.value.trim()}`;
  return TRIGGER_DESCRIPTIONS[model.trigger] ?? '';
}

/** Resolves ids/codes to names for card details (runbook titles, integration names…). */
export type NameResolver = (kind: 'runbook' | 'integration' | 'department', key: string) => string | undefined;

function truncate(text: string, max = 48): string {
  const clean = text.replace(/\s+/g, ' ').trim();
  return clean.length > max ? `${clean.slice(0, max - 1)}…` : clean;
}

/** Second line of an action card. */
export function actionDetail(action: Pick<ModelAction, 'type' | 'params'>, resolve?: NameResolver): string {
  const p = action.params;
  const named = (kind: 'runbook' | 'integration' | 'department', key: string | undefined, fallback: string) => (key ? (resolve?.(kind, key) ?? fallback) : '');
  switch (action.type) {
    case 'ASSIGN_DEPARTMENT':
      return p.departmentCode ? `to ${named('department', p.departmentCode, p.departmentCode)}` : 'Choose a team';
    case 'ASSIGN_USER':
      return p.userEmail ? `to ${p.userEmail}` : 'Choose an agent';
    case 'SET_PRIORITY':
      return p.priority ? `to ${valueLabel(p.priority, 'priority')}` : 'Choose a priority';
    case 'CHANGE_STATUS':
      return p.status ? `to ${valueLabel(p.status, 'status')}` : 'Choose a status';
    case 'NOTIFY_ROLE':
      return p.role ? roleLabel(p.role) : 'Choose a role';
    case 'NOTIFY_DEPARTMENT':
      return p.departmentCode ? named('department', p.departmentCode, p.departmentCode) : "Incident's team";
    case 'NOTIFY_ASSIGNEE':
      return p.message ? truncate(p.message) : 'Current assignee';
    case 'NOTIFY_USER':
      return p.userEmail || 'Choose a recipient';
    case 'ADD_COMMENT':
      return p.message ? `${p.internal === 'true' ? 'Work note: ' : ''}${truncate(p.message, 40)}` : 'Write the comment';
    case 'ESCALATE':
      return p.reason ? truncate(p.reason) : 'Next support level';
    case 'START_RUNBOOK':
      return p.runbookId ? named('runbook', p.runbookId, `Runbook #${p.runbookId}`) : 'Choose a runbook';
    case 'SEND_WEBHOOK':
      return p.integrationId ? named('integration', p.integrationId, `Integration #${p.integrationId}`) : 'Choose an integration';
    case 'DECLARE_MAJOR':
      return 'Mark as major incident';
    default:
      return Object.entries(p)
        .map(([key, value]) => `${key}: ${value}`)
        .join(', ');
  }
}

// ------------------------------------------------------------------ validation

export type IssueTarget =
  | { kind: 'workflow'; field: 'name' | 'description' | keyof WorkflowLimits }
  | { kind: 'trigger' }
  | { kind: 'condition'; ruleIndex?: number }
  | { kind: 'action'; uid: string; branch: Branch; param?: string }
  | { kind: 'server' };

export interface ValidationIssue {
  id: string;
  severity: 'error' | 'warning';
  message: string;
  target: IssueTarget;
}

/** Canvas node an issue belongs to (null for workflow settings / server messages). */
export function issueNodeId(issue: ValidationIssue): string | null {
  switch (issue.target.kind) {
    case 'trigger':
      return TRIGGER_NODE;
    case 'condition':
      return CONDITION_NODE;
    case 'action':
      return issue.target.uid;
    default:
      return null;
  }
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function isInteger(value: number): boolean {
  return Number.isFinite(value) && Math.floor(value) === value;
}

/**
 * Client-side checks mirroring the API's workflow validation (§6). The server re-validates on save
 * (and checks referenced records exist); its messages are shown alongside these.
 */
export function validateDraft(draft: WorkflowDraft, meta?: WorkflowMeta | null): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  const error = (id: string, message: string, target: IssueTarget) => issues.push({ id, severity: 'error', message, target });
  const warning = (id: string, message: string, target: IssueTarget) => issues.push({ id, severity: 'warning', message, target });
  const model = draft.model;

  const name = draft.name.trim();
  if (!name) error('name', 'Give the workflow a name', { kind: 'workflow', field: 'name' });
  else if (name.length < NAME_MIN) error('name', `The name needs at least ${NAME_MIN} characters`, { kind: 'workflow', field: 'name' });
  else if (name.length > NAME_MAX) error('name', `The name can be at most ${NAME_MAX} characters`, { kind: 'workflow', field: 'name' });
  if (draft.description.trim().length > DESCRIPTION_MAX) {
    error('description', `The description can be at most ${DESCRIPTION_MAX} characters`, { kind: 'workflow', field: 'description' });
  }
  (Object.keys(LIMIT_RANGES) as (keyof WorkflowLimits)[]).forEach((key) => {
    const range = LIMIT_RANGES[key];
    const value = draft[key];
    if (!isInteger(value) || value < range.min || value > range.max) {
      error(`limit-${key}`, `${range.label} must be a whole number from ${range.min} to ${range.max}`, { kind: 'workflow', field: key });
    }
  });

  if (meta && !meta.triggers.includes(model.trigger)) error('trigger', `Unknown trigger ${model.trigger}`, { kind: 'trigger' });
  if (model.trigger === 'SCHEDULED') {
    const minutes = model.intervalMinutes;
    if (minutes === null || !isInteger(minutes) || minutes < MIN_SCHEDULE_INTERVAL_MINUTES) {
      error('schedule', `Scheduled workflows run at most every ${MIN_SCHEDULE_INTERVAL_MINUTES} minutes — set an interval of ${MIN_SCHEDULE_INTERVAL_MINUTES} or more`, {
        kind: 'trigger',
      });
    } else if (minutes > MAX_SCHEDULE_MINUTES) {
      error('schedule', 'The schedule interval can be at most one week (10,080 minutes)', { kind: 'trigger' });
    }
    if (model.rules.length === 0) warning('schedule-all', 'Without conditions the schedule acts on every active incident (up to 200 per run)', { kind: 'condition' });
  } else if (model.rules.length === 0) {
    warning('no-conditions', `No conditions: every "${triggerLabel(model.trigger)}" event runs the Yes branch`, { kind: 'condition' });
  }

  if (model.rules.length > MAX_RULES) error('rules-max', `At most ${MAX_RULES} conditions`, { kind: 'condition' });
  model.rules.forEach((rule, index) => {
    const target: IssueTarget = { kind: 'condition', ruleIndex: index };
    const n = index + 1;
    const fieldMeta = meta?.fields.find((entry) => entry.field === rule.field);
    if (!rule.field) {
      error(`rule-${index}-field`, `Condition ${n}: choose a field`, target);
      return;
    }
    if (meta && !fieldMeta) error(`rule-${index}-field`, `Condition ${n}: unknown field "${rule.field}"`, target);
    if (meta && !meta.operators.includes(rule.operator)) error(`rule-${index}-op`, `Condition ${n}: unknown operator`, target);
    const value = rule.value.trim();
    if (!value) {
      error(`rule-${index}-value`, `Condition ${n} (${fieldLabel(rule.field, meta)}): enter a value`, target);
      return;
    }
    if (rule.operator === 'GREATER_THAN' || rule.operator === 'LESS_THAN') {
      if (!isNumericField(rule.field)) error(`rule-${index}-op`, `Condition ${n}: > and < only work with numeric fields`, target);
      else if (!Number.isFinite(Number(value))) error(`rule-${index}-value`, `Condition ${n}: enter a number`, target);
    }
    const allowed = fieldMeta?.values;
    if (allowed && allowed.length && (rule.operator === 'EQUALS' || rule.operator === 'NOT_EQUALS' || rule.operator === 'IN')) {
      const values = rule.operator === 'IN' ? splitValues(value) : [value];
      const unknown = values.filter((entry) => !allowed.some((option) => option.toLowerCase() === entry.toLowerCase()));
      if (unknown.length) error(`rule-${index}-value`, `Condition ${n}: "${unknown.join(', ')}" is not a valid ${fieldLabel(rule.field, meta).toLowerCase()}`, target);
    }
  });

  if (model.yes.length === 0) error('yes-empty', 'Add at least one action to the Yes branch', { kind: 'condition' });
  (['yes', 'no'] as Branch[]).forEach((branch) => {
    if (model[branch].length > MAX_ACTIONS_PER_BRANCH) {
      error(`${branch}-max`, `At most ${MAX_ACTIONS_PER_BRANCH} actions per branch`, { kind: 'condition' });
    }
    model[branch].forEach((action, index) => {
      const where = `${branch === 'yes' ? 'Yes' : 'No'} branch, step ${index + 1} (${actionLabel(action.type, meta)})`;
      const target = (param?: string): IssueTarget => ({ kind: 'action', uid: action.uid, branch, param });
      const actionMeta = meta?.actions.find((entry) => entry.type === action.type);
      if (meta && !actionMeta) {
        error(`${action.uid}-type`, `${where}: unknown action type`, target());
        return;
      }
      const p = action.params;
      actionMeta?.params
        .filter((param) => param.required && !p[param.key]?.trim())
        .forEach((param) => error(`${action.uid}-${param.key}`, `${where}: ${param.label} is required`, target(param.key)));
      actionMeta?.params
        .filter((param) => param.options && p[param.key]?.trim() && !param.options.includes(p[param.key].trim()))
        .forEach((param) => error(`${action.uid}-${param.key}-option`, `${where}: "${p[param.key]}" is not a valid ${param.label.toLowerCase()}`, target(param.key)));
      if ((action.type === 'ASSIGN_USER' || action.type === 'NOTIFY_USER') && p.userEmail?.trim() && !EMAIL.test(p.userEmail.trim())) {
        error(`${action.uid}-email`, `${where}: enter a valid e-mail address`, target('userEmail'));
      }
      if (action.type === 'START_RUNBOOK' && p.runbookId?.trim() && !/^\d+$/.test(p.runbookId.trim())) {
        error(`${action.uid}-runbook`, `${where}: choose a runbook`, target('runbookId'));
      }
      if (action.type === 'SEND_WEBHOOK' && p.integrationId?.trim() && !/^\d+$/.test(p.integrationId.trim())) {
        error(`${action.uid}-integration`, `${where}: choose an integration`, target('integrationId'));
      }
      if (action.type === 'CREATE_INCIDENT' && model.trigger !== 'ALERT_RECEIVED') {
        error(`${action.uid}-alert-only`, `${where}: only works with the Alert Received trigger`, target());
      }
      if (action.type === 'CHANGE_STATUS') {
        const status = p.status?.trim().toUpperCase();
        if (status === 'RESOLVED' && !p.resolutionCode?.trim()) {
          error(`${action.uid}-resolution`, `${where}: a resolution code is required when resolving`, target('resolutionCode'));
        }
        if (p.resolutionCode?.trim().toUpperCase() === 'CANCELLED') {
          error(`${action.uid}-cancelled`, `${where}: use status Closed to cancel; Cancelled is not a resolution code`, target('resolutionCode'));
        }
      }
    });
  });
  return issues;
}

export function hasErrors(issues: ValidationIssue[]): boolean {
  return issues.some((issue) => issue.severity === 'error');
}

// ------------------------------------------------------------------ SA AI hand-off (§7)

export type AiSourceMeta = Pick<AiResult<unknown>, 'source' | 'provider' | 'model' | 'generatedAt' | 'latencyMs' | 'notice' | 'fallbackReason'>;

export interface SuggestionHandoff {
  suggestion: WorkflowSuggestion;
  /** Source label of the AI result; null when the sender did not pass it. */
  ai: AiSourceMeta | null;
}

function isSuggestion(value: unknown): value is WorkflowSuggestion {
  return isRecord(value) && typeof value.triggerEvent === 'string' && isRecord(value.definition);
}

function readAiMeta(value: unknown): AiSourceMeta | null {
  if (!isRecord(value) || (value.source !== 'LLM' && value.source !== 'FALLBACK')) return null;
  return {
    source: value.source,
    provider: typeof value.provider === 'string' ? value.provider : '',
    model: typeof value.model === 'string' ? value.model : null,
    generatedAt: typeof value.generatedAt === 'string' ? value.generatedAt : new Date().toISOString(),
    latencyMs: typeof value.latencyMs === 'number' ? value.latencyMs : 0,
    notice: typeof value.notice === 'string' ? value.notice : '',
    fallbackReason: typeof value.fallbackReason === 'string' ? value.fallbackReason : null,
  };
}

/**
 * Reads `location.state` from SA AI. Accepted shapes:
 * `{ suggestion: AiResult<WorkflowSuggestion> }`, `{ suggestion: WorkflowSuggestion, result|aiResult|ai: AiResult }`
 * `{ suggestion: WorkflowSuggestion, source: { source, provider, model, notice } }` (what SA AI's "Open in builder" sends)
 * or `{ suggestion: WorkflowSuggestion, source, provider, model, notice }`.
 */
export function readSuggestionState(state: unknown): SuggestionHandoff | null {
  if (!isRecord(state)) return null;
  const raw = state.suggestion;
  if (isRecord(raw) && isSuggestion(raw.data)) {
    return { suggestion: raw.data, ai: readAiMeta(raw) };
  }
  if (!isSuggestion(raw)) return null;
  const ai = readAiMeta(state.source) ?? readAiMeta(state.result) ?? readAiMeta(state.aiResult) ?? readAiMeta(state.ai) ?? readAiMeta(state);
  return { suggestion: raw, ai };
}
