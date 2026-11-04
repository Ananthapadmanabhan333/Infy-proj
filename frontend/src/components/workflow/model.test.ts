import { describe, expect, it } from 'vitest';
import type { WorkflowDefinition, WorkflowDto, WorkflowMeta } from '../../lib/types';
import {
  addAction,
  addRule,
  actionDetail,
  BRANCH_OFFSET,
  branchAt,
  canvasSize,
  CONDITION_NODE,
  conditionLines,
  draftFingerprint,
  draftFromBlueprint,
  draftFromWorkflow,
  emptyDraft,
  emptyModel,
  findAction,
  formatInterval,
  fromDefinition,
  hasErrors,
  issueNodeId,
  layoutEdges,
  layoutNodes,
  moveAction,
  moveActionToBranch,
  NODE_SIZE,
  readSuggestionState,
  removeAction,
  removeRule,
  resetLayout,
  ruleSummary,
  setActionParam,
  setPosition,
  setScheduleInterval,
  setTrigger,
  slotId,
  toDefinition,
  toRequest,
  TRIGGER_NODE,
  triggerDetail,
  updateAction,
  updateRule,
  validateDraft,
  type WorkflowDraft,
} from './model';

const meta: WorkflowMeta = {
  triggers: ['INCIDENT_CREATED', 'INCIDENT_UPDATED', 'PRIORITY_CHANGED', 'SLA_BREACHED', 'SCHEDULED'],
  fields: [
    { field: 'priority', label: 'Priority', values: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] },
    { field: 'status', label: 'Status', values: ['NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING', 'RESOLVED', 'CLOSED', 'REOPENED'] },
    { field: 'serviceCode', label: 'Service code', values: null },
    { field: 'assignedTeam', label: 'Assigned team (department code)', values: null },
    { field: 'assigneeEmail', label: 'Assignee e-mail', values: null },
    { field: 'slaRemainingMinutes', label: 'Resolution SLA remaining (minutes)', values: null },
    { field: 'title', label: 'Title', values: null },
  ],
  operators: ['EQUALS', 'NOT_EQUALS', 'IN', 'CONTAINS', 'GREATER_THAN', 'LESS_THAN'],
  actions: [
    { type: 'ASSIGN_DEPARTMENT', label: 'Assign to department', params: [{ key: 'departmentCode', label: 'Department code', required: true, options: null }] },
    { type: 'ASSIGN_USER', label: 'Assign to user', params: [{ key: 'userEmail', label: 'User e-mail', required: true, options: null }] },
    { type: 'SET_PRIORITY', label: 'Set priority', params: [{ key: 'priority', label: 'Priority', required: true, options: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] }] },
    {
      type: 'CHANGE_STATUS',
      label: 'Change status',
      params: [
        { key: 'status', label: 'New status', required: true, options: ['NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING', 'RESOLVED', 'CLOSED', 'REOPENED'] },
        { key: 'resolutionCode', label: 'Resolution code', required: false, options: ['FIXED', 'WORKAROUND', 'OTHER'] },
      ],
    },
    {
      type: 'NOTIFY_DEPARTMENT',
      label: 'Send notification to department',
      params: [
        { key: 'departmentCode', label: 'Department code', required: false, options: null },
        { key: 'message', label: 'Message', required: false, options: null },
      ],
    },
    {
      type: 'NOTIFY_USER',
      label: 'Send notification to user',
      params: [
        { key: 'userEmail', label: 'User e-mail', required: true, options: null },
        { key: 'message', label: 'Message', required: false, options: null },
      ],
    },
    { type: 'START_RUNBOOK', label: 'Trigger approved runbook', params: [{ key: 'runbookId', label: 'Runbook id', required: true, options: null }] },
    { type: 'DECLARE_MAJOR', label: 'Declare major incident', params: [] },
  ],
};

const payrollDefinition: WorkflowDefinition = {
  conditions: {
    match: 'ALL',
    rules: [
      { field: 'priority', operator: 'EQUALS', value: 'CRITICAL' },
      { field: 'serviceCode', operator: 'EQUALS', value: 'PAYROLL' },
    ],
  },
  actions: [
    { type: 'START_RUNBOOK', params: { runbookId: '4' } },
    { type: 'NOTIFY_DEPARTMENT', params: { message: '{{priority}} payroll incident {{number}}' } },
  ],
  elseActions: [{ type: 'ASSIGN_DEPARTMENT', params: { departmentCode: 'IT-SD' } }],
  schedule: null,
  layout: null,
};

function draft(overrides: Partial<WorkflowDraft> = {}): WorkflowDraft {
  return { ...emptyDraft(), name: 'Payroll incident – auto resolution', model: fromDefinition(payrollDefinition, 'INCIDENT_CREATED'), ...overrides };
}

describe('definition ↔ model mapping', () => {
  it('round-trips a definition without layout unchanged', () => {
    const model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    expect(model.yes.map((action) => action.type)).toEqual(['START_RUNBOOK', 'NOTIFY_DEPARTMENT']);
    expect(model.no.map((action) => action.type)).toEqual(['ASSIGN_DEPARTMENT']);
    expect(model.positions).toEqual({});
    expect(toDefinition(model)).toEqual(payrollDefinition);
  });

  it('round-trips layout keys (trigger, condition, yes-i, no-i) and keeps them attached to their actions', () => {
    const definition: WorkflowDefinition = {
      ...payrollDefinition,
      layout: { trigger: { x: 300, y: 10 }, condition: { x: 290, y: 140 }, 'yes-1': { x: 40, y: 420 }, 'no-0': { x: 600, y: 330 } },
    };
    const model = fromDefinition(definition, 'INCIDENT_CREATED');
    expect(model.positions[TRIGGER_NODE]).toEqual({ x: 300, y: 10 });
    expect(model.positions[model.yes[1].uid]).toEqual({ x: 40, y: 420 });
    expect(model.positions[model.no[0].uid]).toEqual({ x: 600, y: 330 });
    expect(model.positions[model.yes[0].uid]).toBeUndefined();
    expect(toDefinition(model)).toEqual(definition);
  });

  it('tolerates missing elseActions, conditions and malformed layout entries', () => {
    const model = fromDefinition(
      { actions: [{ type: 'DECLARE_MAJOR', params: {} }], layout: { trigger: { x: 'a', y: 2 }, 'yes-9': { x: 1, y: 1 }, junk: { x: 1, y: 1 } } } as never,
      'INCIDENT_UPDATED',
    );
    expect(model.match).toBe('ALL');
    expect(model.rules).toEqual([]);
    expect(model.no).toEqual([]);
    expect(model.positions).toEqual({});
    expect(toDefinition(model)).toEqual({
      conditions: { match: 'ALL', rules: [] },
      actions: [{ type: 'DECLARE_MAJOR', params: {} }],
      elseActions: [],
      schedule: null,
      layout: null,
    });
    expect(fromDefinition(null).yes).toEqual([]);
  });

  it('only saves a schedule for the SCHEDULED trigger and defaults its interval', () => {
    const scheduled = fromDefinition({ ...payrollDefinition, schedule: { intervalMinutes: 240 } }, 'SCHEDULED');
    expect(toDefinition(scheduled).schedule).toEqual({ intervalMinutes: 240 });
    const switched = setTrigger(scheduled, 'INCIDENT_CREATED');
    expect(toDefinition(switched).schedule).toBeNull();
    const fresh = setTrigger(emptyModel(), 'SCHEDULED');
    expect(fresh.intervalMinutes).toBe(60);
    expect(toDefinition(setScheduleInterval(fresh, 15)).schedule).toEqual({ intervalMinutes: 15 });
  });

  it('drops empty parameters, trims values, normalises IN lists and sorts params', () => {
    let model = emptyModel();
    model = addAction(model, 'yes', 'NOTIFY_USER', { userEmail: ' a@b.co ', message: '' }).model;
    model = addRule(model, { field: 'priority', operator: 'IN', value: ' CRITICAL , HIGH,, ' });
    const definition = toDefinition(model);
    expect(definition.actions[0].params).toEqual({ userEmail: 'a@b.co' });
    expect(definition.conditions.rules[0].value).toBe('CRITICAL,HIGH');
  });

  it('builds drafts and requests from workflows, templates and suggestions', () => {
    const workflow = {
      id: 3,
      name: 'Critical triage',
      description: null,
      triggerEvent: 'PRIORITY_CHANGED',
      active: true,
      currentVersion: 4,
      maxExecutionsPerHour: 30,
      maxRetries: 2,
      retryBackoffSeconds: 120,
      definition: payrollDefinition,
    } as unknown as WorkflowDto;
    const fromWorkflow = draftFromWorkflow(workflow);
    expect(fromWorkflow).toMatchObject({ name: 'Critical triage', description: '', active: true, maxExecutionsPerHour: 30, maxRetries: 2, retryBackoffSeconds: 120 });
    expect(fromWorkflow.model.trigger).toBe('PRIORITY_CHANGED');

    const request = toRequest(fromWorkflow, '  tightened conditions ');
    expect(request).toMatchObject({ name: 'Critical triage', triggerEvent: 'PRIORITY_CHANGED', active: true, changeNote: 'tightened conditions' });
    expect(request.description).toBeUndefined();
    expect(request.definition).toEqual(payrollDefinition);
    expect(toRequest(fromWorkflow, '   ')).not.toHaveProperty('changeNote');

    const blueprint = draftFromBlueprint({
      name: 'Stale pending reminder',
      description: 'Every 4 h',
      triggerEvent: 'SCHEDULED',
      definition: { ...payrollDefinition, schedule: { intervalMinutes: 240 } },
    });
    expect(blueprint.active).toBe(false);
    expect(blueprint.model.intervalMinutes).toBe(240);
    expect(blueprint.maxExecutionsPerHour).toBe(60);
  });

  it('fingerprints drafts so position and parameter edits count as changes', () => {
    const base = draft();
    const same = draft();
    expect(draftFingerprint(base)).toBe(draftFingerprint(same));
    expect(draftFingerprint({ ...base, model: setPosition(base.model, TRIGGER_NODE, { x: 10, y: 10 }) })).not.toBe(draftFingerprint(base));
  });
});

describe('branch edits', () => {
  it('adds actions to the Yes and No branches (append or insert)', () => {
    const model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    const first = addAction(model, 'no', 'NOTIFY_USER', { userEmail: 'riya@nexus360.test' });
    expect(first.model.no.map((action) => action.type)).toEqual(['ASSIGN_DEPARTMENT', 'NOTIFY_USER']);
    expect(findAction(first.model, first.uid)).toMatchObject({ branch: 'no', index: 1 });
    const inserted = addAction(first.model, 'yes', 'DECLARE_MAJOR', {}, 0);
    expect(inserted.model.yes.map((action) => action.type)).toEqual(['DECLARE_MAJOR', 'START_RUNBOOK', 'NOTIFY_DEPARTMENT']);
    expect(toDefinition(inserted.model).elseActions).toHaveLength(2);
  });

  it('resets params when the action type changes and edits single params', () => {
    const model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    const uid = model.yes[0].uid;
    const retyped = updateAction(model, uid, { type: 'SET_PRIORITY' });
    expect(findAction(retyped, uid)?.action).toMatchObject({ type: 'SET_PRIORITY', params: {} });
    const withParam = setActionParam(retyped, uid, 'priority', 'HIGH');
    expect(findAction(withParam, uid)?.action.params).toEqual({ priority: 'HIGH' });
    expect(findAction(setActionParam(withParam, uid, 'priority', ''), uid)?.action.params).toEqual({});
    expect(updateAction(model, 'missing', { type: 'SET_PRIORITY' })).toBe(model);
  });

  it('reorders actions within a branch and swaps their placed positions', () => {
    let model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    const [a, b] = model.yes;
    model = setPosition(model, a.uid, { x: 10, y: 400 });
    const moved = moveAction(model, a.uid, 1);
    expect(moved.yes.map((action) => action.uid)).toEqual([b.uid, a.uid]);
    expect(moved.positions[b.uid]).toEqual({ x: 10, y: 400 });
    expect(moved.positions[a.uid]).toBeUndefined();
    expect(moveAction(moved, b.uid, -1)).toBe(moved);
    expect(toDefinition(moved).actions.map((action) => action.type)).toEqual(['NOTIFY_DEPARTMENT', 'START_RUNBOOK']);
    expect(toDefinition(moved).layout).toEqual({ 'yes-0': { x: 10, y: 400 } });
  });

  it('removes actions together with their position and moves actions between branches', () => {
    let model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    const runbook = model.yes[0];
    model = setPosition(model, runbook.uid, { x: 5, y: 5 });
    const removed = removeAction(model, runbook.uid);
    expect(removed.yes).toHaveLength(1);
    expect(removed.positions).toEqual({});
    const switched = moveActionToBranch(model, runbook.uid, 'no');
    expect(switched.no.map((action) => action.type)).toEqual(['ASSIGN_DEPARTMENT', 'START_RUNBOOK']);
    expect(switched.yes.map((action) => action.type)).toEqual(['NOTIFY_DEPARTMENT']);
    expect(switched.positions[runbook.uid]).toBeUndefined();
  });

  it('edits and removes condition rules', () => {
    let model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    model = updateRule(model, 1, { operator: 'NOT_EQUALS' });
    expect(model.rules[1]).toEqual({ field: 'serviceCode', operator: 'NOT_EQUALS', value: 'PAYROLL' });
    model = removeRule(model, 0);
    expect(model.rules).toHaveLength(1);
  });
});

describe('layout', () => {
  it('lays out definitions without layout: trigger above condition, Yes left, No right, actions stacked', () => {
    const model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    const nodes = layoutNodes(model);
    const byId = Object.fromEntries(nodes.map((node) => [node.id, node]));
    const trigger = byId[TRIGGER_NODE];
    const condition = byId[CONDITION_NODE];
    const [yes0, yes1] = model.yes.map((action) => byId[action.uid]);
    const no0 = byId[model.no[0].uid];
    expect(condition.y).toBeGreaterThan(trigger.y + trigger.h);
    expect(trigger.x + trigger.w / 2).toBe(condition.x + condition.w / 2);
    expect(yes0.x + yes0.w / 2).toBe(condition.x + condition.w / 2 - BRANCH_OFFSET);
    expect(no0.x + no0.w / 2).toBe(condition.x + condition.w / 2 + BRANCH_OFFSET);
    expect(yes1.y).toBeGreaterThan(yes0.y + yes0.h);
    expect(yes1.x).toBe(yes0.x);
    expect(nodes.every((node) => !node.placed)).toBe(true);
    // add-action slots sit under the last card of each branch
    expect(byId[slotId('yes')].y).toBeGreaterThan(yes1.y + yes1.h);
    expect(byId[slotId('no')].y).toBeGreaterThan(no0.y + no0.h);
    expect(layoutNodes(model, { slots: false }).some((node) => node.kind === 'slot')).toBe(false);
    // deterministic
    expect(layoutNodes(model)).toEqual(nodes);
  });

  it('moves unplaced followers with a dragged node, but leaves placed nodes where they are', () => {
    let model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    const [first, second] = model.yes;
    model = setPosition(model, first.uid, { x: 20, y: 500 });
    const nodes = layoutNodes(model);
    const node = (id: string) => nodes.find((entry) => entry.id === id)!;
    expect(node(first.uid)).toMatchObject({ x: 20, y: 500, placed: true });
    expect(node(second.uid)).toMatchObject({ x: 20, placed: false });
    expect(node(second.uid).y).toBe(500 + NODE_SIZE.action.h + 30);

    const dragTrigger = layoutNodes(setPosition(emptyModel(), TRIGGER_NODE, { x: 100, y: 100 }));
    const condition = dragTrigger.find((entry) => entry.id === CONDITION_NODE)!;
    expect(condition.x + condition.w / 2).toBe(100 + NODE_SIZE.trigger.w / 2);

    expect(resetLayout(model).positions).toEqual({});
    expect(setPosition(model, 'trigger', { x: -40, y: 3.6 }).positions.trigger).toEqual({ x: 0, y: 4 });
  });

  it('connects trigger → condition → Yes/No branches with labelled edges and dashed slots', () => {
    const model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    const nodes = layoutNodes(model);
    const edges = layoutEdges(nodes);
    expect(edges.map((edge) => [edge.from, edge.to, edge.label, edge.dashed])).toEqual([
      ['trigger', 'condition', null, false],
      ['condition', model.yes[0].uid, 'Yes', false],
      [model.yes[0].uid, model.yes[1].uid, null, false],
      [model.yes[1].uid, slotId('yes'), null, true],
      ['condition', model.no[0].uid, 'No', false],
      [model.no[0].uid, slotId('no'), null, true],
    ]);
    const condition = nodes.find((node) => node.id === CONDITION_NODE)!;
    expect(edges[1].d.startsWith(`M ${condition.x} ${condition.y + condition.h / 2}`)).toBe(true);
    expect(edges[4].d.startsWith(`M ${condition.x + condition.w} ${condition.y + condition.h / 2}`)).toBe(true);

    const empty = layoutEdges(layoutNodes(emptyModel()));
    expect(empty.map((edge) => [edge.to, edge.label, edge.dashed])).toEqual([
      ['condition', null, false],
      [slotId('yes'), 'Yes', true],
      [slotId('no'), 'No', true],
    ]);
  });

  it('sizes the canvas to fit nodes and picks the drop branch by side of the diamond', () => {
    const nodes = layoutNodes(setPosition(emptyModel(), TRIGGER_NODE, { x: 1400, y: 900 }));
    const size = canvasSize(nodes);
    expect(size.width).toBeGreaterThanOrEqual(1400 + NODE_SIZE.trigger.w);
    expect(size.height).toBeGreaterThan(900);
    const base = layoutNodes(emptyModel());
    const condition = base.find((node) => node.id === CONDITION_NODE)!;
    expect(branchAt(base, condition.x)).toBe('yes');
    expect(branchAt(base, condition.x + condition.w)).toBe('no');
  });
});

describe('summaries', () => {
  it('summarises the condition diamond like the reference ("Priority = Critical?")', () => {
    const single = fromDefinition({ ...payrollDefinition, conditions: { match: 'ALL', rules: [{ field: 'priority', operator: 'EQUALS', value: 'CRITICAL' }] } });
    expect(conditionLines(single, meta)).toMatchObject({ headline: 'Priority = Critical?', more: null });
    const double = fromDefinition(payrollDefinition);
    expect(conditionLines(double, meta)).toEqual({
      headline: 'Priority = Critical',
      more: '+1 more (all must match)',
      full: 'Priority = Critical and Service code = PAYROLL?',
    });
    expect(conditionLines(emptyModel(), meta).headline).toBe('Always (no conditions)');
    expect(ruleSummary({ field: 'priority', operator: 'IN', value: 'CRITICAL,HIGH' }, meta)).toBe('Priority in Critical, High');
    expect(ruleSummary({ field: 'slaRemainingMinutes', operator: 'LESS_THAN', value: '30' }, meta)).toBe('Resolution SLA remaining (minutes) < 30');
    expect(ruleSummary({ field: 'status', operator: 'NOT_EQUALS', value: 'IN_PROGRESS' }, meta)).toBe('Status ≠ In Progress');
  });

  it('describes trigger and action cards', () => {
    const model = fromDefinition(payrollDefinition, 'INCIDENT_CREATED');
    expect(triggerDetail(model)).toBe('Service: PAYROLL');
    expect(triggerDetail(fromDefinition({ ...payrollDefinition, schedule: { intervalMinutes: 240 } }, 'SCHEDULED'))).toBe('Every 4 h');
    expect(triggerDetail(emptyModel('SLA_BREACHED'))).toBe('When an SLA is breached');
    expect(formatInterval(15)).toBe('Every 15 min');
    expect(formatInterval(90)).toBe('Every 1 h 30 min');
    expect(formatInterval(1440)).toBe('Every day');
    const resolve = (kind: string, key: string) => (kind === 'runbook' && key === '4' ? 'Payroll recovery' : undefined);
    expect(actionDetail({ type: 'START_RUNBOOK', params: { runbookId: '4' } }, resolve)).toBe('Payroll recovery');
    expect(actionDetail({ type: 'START_RUNBOOK', params: { runbookId: '9' } }, resolve)).toBe('Runbook #9');
    expect(actionDetail({ type: 'SET_PRIORITY', params: { priority: 'CRITICAL' } })).toBe('to Critical');
    expect(actionDetail({ type: 'NOTIFY_ROLE', params: { role: 'INCIDENT_MANAGER' } })).toBe('Incident Manager');
    expect(actionDetail({ type: 'NOTIFY_DEPARTMENT', params: {} })).toBe("Incident's team");
    expect(actionDetail({ type: 'ADD_COMMENT', params: { message: 'Bridge opened', internal: 'true' } })).toBe('Work note: Bridge opened');
  });
});

describe('validation', () => {
  const ids = (issues: ReturnType<typeof validateDraft>) => issues.filter((issue) => issue.severity === 'error').map((issue) => issue.id);

  it('accepts a complete workflow (only warnings allowed)', () => {
    const issues = validateDraft(draft(), meta);
    expect(hasErrors(issues)).toBe(false);
  });

  it('requires a name, a Yes action and sensible limits', () => {
    const issues = validateDraft(draft({ name: ' ', maxRetries: 11, retryBackoffSeconds: 0, maxExecutionsPerHour: 1.5, model: emptyModel() }), meta);
    expect(ids(issues)).toEqual(expect.arrayContaining(['name', 'yes-empty', 'limit-maxRetries', 'limit-retryBackoffSeconds', 'limit-maxExecutionsPerHour']));
    expect(issues.find((issue) => issue.id === 'no-conditions')?.severity).toBe('warning');
    expect(ids(validateDraft(draft({ name: 'ab' }), meta))).toContain('name');
  });

  it('requires a schedule interval of at least 15 minutes for SCHEDULED workflows', () => {
    const base = draft();
    const scheduled = { ...base, model: setScheduleInterval(setTrigger(base.model, 'SCHEDULED'), 10) };
    const issues = validateDraft(scheduled, meta);
    expect(ids(issues)).toContain('schedule');
    expect(issueNodeId(issues.find((issue) => issue.id === 'schedule')!)).toBe(TRIGGER_NODE);
    expect(ids(validateDraft({ ...base, model: setScheduleInterval(scheduled.model, 15) }, meta))).not.toContain('schedule');
    expect(ids(validateDraft({ ...base, model: setScheduleInterval(scheduled.model, 20_000) }, meta))).toContain('schedule');
  });

  it('checks condition values against the meta (enum values, numbers, empty values)', () => {
    let model = emptyModel();
    model = addAction(model, 'yes', 'DECLARE_MAJOR').model;
    model = addRule(model, { field: 'priority', operator: 'EQUALS', value: 'URGENT' });
    model = addRule(model, { field: 'slaRemainingMinutes', operator: 'LESS_THAN', value: 'soon' });
    model = addRule(model, { field: 'title', operator: 'GREATER_THAN', value: '3' });
    model = addRule(model, { field: 'assigneeEmail', operator: 'EQUALS', value: '  ' });
    model = addRule(model, { field: 'priority', operator: 'IN', value: 'CRITICAL,high' });
    const issues = validateDraft(draft({ model }), meta);
    expect(ids(issues)).toEqual(['rule-0-value', 'rule-1-value', 'rule-2-op', 'rule-3-value']);
    expect(issueNodeId(issues[0])).toBe(CONDITION_NODE);
  });

  it('checks action parameters from the meta and the CHANGE_STATUS rules', () => {
    let model = emptyModel();
    const runbook = addAction(model, 'yes', 'START_RUNBOOK');
    model = runbook.model;
    const status = addAction(model, 'yes', 'CHANGE_STATUS', { status: 'RESOLVED' });
    model = status.model;
    const email = addAction(model, 'no', 'NOTIFY_USER', { userEmail: 'not-an-email' });
    model = email.model;
    const priority = addAction(model, 'no', 'SET_PRIORITY', { priority: 'P1' });
    model = priority.model;
    const issues = validateDraft(draft({ model }), meta);
    expect(ids(issues)).toEqual([`${runbook.uid}-runbookId`, `${status.uid}-resolution`, `${email.uid}-email`, `${priority.uid}-priority-option`]);
    expect(issueNodeId(issues.find((issue) => issue.id === `${email.uid}-email`)!)).toBe(email.uid);
    const cancelled = setActionParam(model, status.uid, 'resolutionCode', 'CANCELLED');
    expect(ids(validateDraft(draft({ model: cancelled }), meta))).toContain(`${status.uid}-cancelled`);
  });

  it('works without meta (structure checks only)', () => {
    expect(hasErrors(validateDraft(draft(), null))).toBe(false);
  });
});

describe('SA AI hand-off', () => {
  const suggestion = { name: 'Auto-route VPN', description: 'Routes VPN incidents', triggerEvent: 'INCIDENT_CREATED', definition: payrollDefinition, explanation: 'Because…' };
  const ai = { source: 'LLM', provider: 'anthropic', model: 'claude', generatedAt: '2026-11-03T10:00:00Z', latencyMs: 900, notice: 'AI generated', fallbackReason: null };

  it('accepts a full AiResult as the suggestion', () => {
    const handoff = readSuggestionState({ suggestion: { ...ai, data: suggestion } });
    expect(handoff?.suggestion.name).toBe('Auto-route VPN');
    expect(handoff?.ai).toMatchObject({ source: 'LLM', provider: 'anthropic' });
  });

  it('accepts the SA AI "Open in builder" shape: { suggestion, source: { source, provider, model, notice } }', () => {
    const handoff = readSuggestionState({ suggestion, source: { source: 'FALLBACK', provider: 'fallback', model: null, notice: 'Deterministic fallback' } });
    expect(handoff?.suggestion.triggerEvent).toBe('INCIDENT_CREATED');
    expect(handoff?.ai).toMatchObject({ source: 'FALLBACK', provider: 'fallback', model: null, notice: 'Deterministic fallback' });
  });

  it('accepts a plain suggestion with the source next to it, or without any source', () => {
    expect(readSuggestionState({ suggestion, result: { ...ai, source: 'FALLBACK' } })?.ai?.source).toBe('FALLBACK');
    expect(readSuggestionState({ suggestion, source: 'LLM', provider: 'openai' })?.ai?.provider).toBe('openai');
    expect(readSuggestionState({ suggestion })?.ai).toBeNull();
  });

  it('ignores unrelated state', () => {
    expect(readSuggestionState(null)).toBeNull();
    expect(readSuggestionState({ from: '/incidents' })).toBeNull();
    expect(readSuggestionState({ suggestion: { name: 'x' } })).toBeNull();
  });
});
