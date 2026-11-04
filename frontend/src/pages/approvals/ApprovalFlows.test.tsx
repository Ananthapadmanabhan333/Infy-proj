import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { apiErrorBody, json, mockApi, requests, type MockHandler, type MockReply } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { ApprovalFlowDto } from '../../lib/itsmTypes';
import type { Role } from '../../lib/types';
import ApprovalFlowEditorPage from './ApprovalFlowEditorPage';
import ApprovalFlowsPage from './ApprovalFlowsPage';
import { describeApprover, flowToValues, mapFlowFieldErrors, toFlowRequest } from './flowModel';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);
const all = (method: string, url: string) => requests.filter((request) => request.method === method && request.url === url);

const CHANGE_NORMAL: ApprovalFlowDto = {
  id: 1,
  code: 'CHANGE_NORMAL',
  name: 'Normal change',
  description: 'Change authority, then CAB for high-risk changes',
  subjectType: 'CHANGE',
  stages: [
    { name: 'Change authority', mode: 'PARALLEL', quorum: 'ANY', requiredCount: null, approvers: [{ kind: 'ROLE', role: 'CHANGE_MANAGER' }, { kind: 'ROLE', role: 'IT_MANAGER' }], condition: null, deadlineHours: 48, escalation: null },
    {
      name: 'Change Advisory Board',
      mode: 'PARALLEL',
      quorum: 'N',
      requiredCount: 2,
      approvers: [{ kind: 'ROLE', role: 'IT_MANAGER' }, { kind: 'ROLE', role: 'IT_MANAGER' }, { kind: 'ROLE', role: 'CHANGE_MANAGER' }],
      condition: { match: 'ALL', rules: [{ field: 'changeRisk', operator: 'EQUALS', value: 'HIGH' }] },
      deadlineHours: 72,
      escalation: { notifyManager: true, notifyRole: 'SYSTEM_ADMIN', autoReject: false },
    },
  ],
  active: true,
  systemFlow: true,
  demo: true,
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-11-02T08:00:00Z',
};

const ACCESS_REVIEW: ApprovalFlowDto = {
  id: 7,
  code: 'ACCESS_REVIEW',
  name: 'Access review',
  description: null,
  subjectType: null,
  stages: [
    { name: 'Team lead', mode: 'SEQUENTIAL', quorum: 'ALL', requiredCount: null, approvers: [{ kind: 'TEAM', teamId: 3 }, { kind: 'USER', userId: 55 }], condition: null, deadlineHours: 24, escalation: { notifyManager: false, notifyRole: null, autoReject: true } },
  ],
  active: false,
  systemFlow: false,
  demo: false,
  createdAt: '2026-10-20T08:00:00Z',
  updatedAt: '2026-11-03T08:00:00Z',
};

const TEAMS = [
  { id: 3, code: 'PAY-APPS', name: 'Payroll & HR Apps', description: null, departmentId: 2, departmentCode: 'IT', departmentName: 'IT', leadId: null, leadName: null, email: null, active: true, departmentDefault: false, memberCount: 3, openIncidents: 1, unassignedIncidents: 0, openRequests: 0, demo: true, members: [] },
];

function setup(extra: Record<string, MockHandler | MockReply> = {}) {
  mockApi({
    'GET /approval-flows': json([ACCESS_REVIEW, CHANGE_NORMAL]),
    'GET /approval-flows/1': json(CHANGE_NORMAL),
    'GET /approval-flows/7': json(ACCESS_REVIEW),
    'GET /teams': json(TEAMS),
    'GET /service-requests/user-options': json([{ id: 55, fullName: 'Priya Raman', departmentName: 'Finance', role: 'IT_MANAGER' }]),
    ...extra,
  });
}

function renderFlows(route: string, role: Role) {
  return renderWithProviders(
    <Routes>
      <Route path="/approvals/flows" element={<ApprovalFlowsPage />} />
      <Route path="/approvals/flows/new" element={<ApprovalFlowEditorPage />} />
      <Route path="/approvals/flows/:id" element={<ApprovalFlowEditorPage />} />
    </Routes>,
    { route, auth: makeAuth(makeUser(role)) },
  );
}

const stage = (number: number) => screen.getByRole('group', { name: new RegExp(`^Stage ${number}:`) });

describe('Approval flows — list', () => {
  it('lists flows with subject type and active filters and offers "New flow" to editors', async () => {
    setup();
    const user = userEvent.setup();
    renderFlows('/approvals/flows', 'CHANGE_MANAGER');
    const table = await screen.findByRole('table', { name: 'Approval flows' });
    const rows = within(table).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Access review');
    expect(rows[1]).toHaveTextContent('Any subject');
    expect(rows[1]).toHaveTextContent('Inactive');
    expect(rows[2]).toHaveTextContent('CHANGE_NORMAL');
    expect(rows[2]).toHaveTextContent('Change authority → Change Advisory Board');
    expect(rows[2]).toHaveTextContent('System');
    expect(screen.getByRole('link', { name: /New flow/ })).toHaveAttribute('href', '/approvals/flows/new');
    expect(last('GET', '/approval-flows')?.params).toEqual({});

    await user.selectOptions(screen.getByRole('combobox', { name: 'Subject type' }), 'CHANGE');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'true');
    await waitFor(() => expect(last('GET', '/approval-flows')?.params).toEqual({ subjectType: 'CHANGE', active: true }));

    await user.click(screen.getByRole('row', { name: 'Open approval flow Normal change' }));
    expect(await screen.findByRole('heading', { name: 'Normal change' })).toBeInTheDocument();
  });

  it('hides "New flow" for readers who cannot edit', async () => {
    setup();
    renderFlows('/approvals/flows', 'AUDITOR');
    await screen.findByRole('table', { name: 'Approval flows' });
    expect(screen.queryByRole('link', { name: /New flow/ })).not.toBeInTheDocument();
  });
});

describe('Approval flows — editor', () => {
  it('creates a flow with the ordered stage editor (approvers by role, team, person and manager; quorum, condition, escalation, reorder)', async () => {
    setup({
      'POST /approval-flows': (config) => json({ ...ACCESS_REVIEW, ...JSON.parse(String(config.data)), id: 31, systemFlow: false, demo: false }, 201),
      'GET /approval-flows/31': () => json({ ...ACCESS_REVIEW, ...(last('POST', '/approval-flows')?.body as object), id: 31 }),
    });
    const user = userEvent.setup();
    renderFlows('/approvals/flows/new', 'CHANGE_MANAGER');

    await user.type(await screen.findByRole('textbox', { name: 'Code' }), 'access-review');
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'Access review');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Subject type' }), 'SERVICE_REQUEST');

    // Stage 1: the requester's manager.
    const first = stage(1);
    await user.clear(within(first).getByRole('textbox', { name: 'Stage 1 name' }));
    await user.type(within(first).getByRole('textbox', { name: 'Stage 1 name' }), 'Manager sign-off');
    await user.selectOptions(within(screen.getByRole('group', { name: 'Stage 1 approver 1' })).getByRole('combobox', { name: 'Approver type' }), 'MANAGER');

    // Stage 2: role + team + person, 2 of 3, condition, deadline with auto-reject.
    await user.click(screen.getByRole('button', { name: 'Add stage' }));
    const second = stage(2);
    await user.clear(within(second).getByRole('textbox', { name: 'Stage 2 name' }));
    await user.type(within(second).getByRole('textbox', { name: 'Stage 2 name' }), 'Security review');
    await user.selectOptions(within(screen.getByRole('group', { name: 'Stage 2 approver 1' })).getByRole('combobox', { name: 'Role' }), 'IT_ADMIN');
    await user.click(within(stage(2)).getByRole('button', { name: 'Add approver to stage 2' }));
    const teamRow = screen.getByRole('group', { name: 'Stage 2 approver 2' });
    await user.selectOptions(within(teamRow).getByRole('combobox', { name: 'Approver type' }), 'TEAM');
    await user.selectOptions(within(teamRow).getByRole('combobox', { name: 'Team' }), '3');
    await user.click(within(stage(2)).getByRole('button', { name: 'Add approver to stage 2' }));
    const personRow = screen.getByRole('group', { name: 'Stage 2 approver 3' });
    await user.selectOptions(within(personRow).getByRole('combobox', { name: 'Approver type' }), 'USER');
    await user.type(within(personRow).getByRole('textbox', { name: 'Person' }), 'Priya');
    await user.click(await within(personRow).findByRole('option', { name: /Priya Raman/ }));
    expect(within(personRow).getByText('Priya Raman')).toBeInTheDocument();

    await user.selectOptions(within(stage(2)).getByRole('combobox', { name: 'Quorum' }), 'N');
    await user.type(within(stage(2)).getByRole('spinbutton', { name: /Required approvals/ }), '2');
    await user.type(within(stage(2)).getByRole('spinbutton', { name: /Deadline/ }), '24');
    await user.click(within(stage(2)).getByRole('checkbox', { name: 'Custom escalation when overdue' }));
    await user.selectOptions(within(stage(2)).getByRole('combobox', { name: 'Also notify role' }), 'SYSTEM_ADMIN');
    await user.click(within(stage(2)).getByRole('checkbox', { name: 'Reject automatically when overdue' }));
    await user.click(within(stage(2)).getByRole('checkbox', { name: /Run this stage only when a condition matches/ }));
    const rule = screen.getByRole('group', { name: 'Stage 2 rule 1' });
    await user.clear(within(rule).getByRole('combobox', { name: 'Fact' }));
    await user.type(within(rule).getByRole('combobox', { name: 'Fact' }), 'priority');
    await user.type(within(rule).getByRole('textbox', { name: 'Value' }), 'P1');

    // Reorder: the security review runs first.
    await user.click(screen.getByRole('button', { name: 'Move stage 2 up' }));
    expect(stage(1)).toHaveAccessibleName('Stage 1: Security review');
    expect(stage(2)).toHaveAccessibleName('Stage 2: Manager sign-off');

    await user.click(screen.getByRole('button', { name: /Create flow/ }));
    await waitFor(() => expect(all('POST', '/approval-flows')).toHaveLength(1));
    expect(last('POST', '/approval-flows')?.body).toEqual({
      code: 'ACCESS-REVIEW',
      name: 'Access review',
      description: null,
      subjectType: 'SERVICE_REQUEST',
      active: true,
      stages: [
        {
          name: 'Security review',
          mode: 'PARALLEL',
          quorum: 'N',
          requiredCount: 2,
          approvers: [{ kind: 'ROLE', role: 'IT_ADMIN' }, { kind: 'TEAM', teamId: 3 }, { kind: 'USER', userId: 55 }],
          condition: { match: 'ALL', rules: [{ field: 'priority', operator: 'EQUALS', value: 'P1' }] },
          deadlineHours: 24,
          escalation: { notifyManager: true, notifyRole: 'SYSTEM_ADMIN', autoReject: true },
        },
        { name: 'Manager sign-off', mode: 'PARALLEL', quorum: 'ANY', requiredCount: null, approvers: [{ kind: 'MANAGER' }], condition: null, deadlineHours: null, escalation: null },
      ],
    });
    expect(await screen.findByText('Access review created')).toBeInTheDocument();
    await waitFor(() => expect(all('GET', '/approval-flows/31').length).toBeGreaterThan(0));
  });

  it('validates the stage model before sending (quorum above the approver count, missing role)', async () => {
    setup({ 'POST /approval-flows': json(ACCESS_REVIEW, 201) });
    const user = userEvent.setup();
    renderFlows('/approvals/flows/new', 'IT_ADMIN');
    await user.type(await screen.findByRole('textbox', { name: 'Code' }), 'X1');
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'Two of one');
    await user.selectOptions(within(stage(1)).getByRole('combobox', { name: 'Quorum' }), 'N');
    await user.type(within(stage(1)).getByRole('spinbutton', { name: /Required approvals/ }), '2');
    await user.click(screen.getByRole('button', { name: /Create flow/ }));
    expect(await within(stage(1)).findByText('Cannot exceed the number of approvers')).toBeInTheDocument();
    const role = within(screen.getByRole('group', { name: 'Stage 1 approver 1' })).getByRole('combobox', { name: 'Role' });
    expect(role).toHaveAttribute('aria-invalid', 'true');
    expect(role).toHaveAccessibleDescription('Choose a role');
    expect(all('POST', '/approval-flows')).toHaveLength(0);
  });

  it('maps API fieldErrors onto the right stage fields and keeps the rest as a stage-prefixed message', async () => {
    setup({
      'PUT /approval-flows/1': {
        status: 400,
        data: apiErrorBody(400, 'requiredCount cannot exceed the number of approvers', [
          { field: 'stages[1].requiredCount', message: 'requiredCount cannot exceed the number of approvers' },
          { field: 'stages[0].approvers', message: 'Approver user 99 is not an active user' },
          { field: 'stages[1].condition.rules[0].field', message: 'Every condition needs a valid field name and an operator' },
          { field: 'stages[0].somethingNew', message: 'Unexpected stage problem' },
        ]),
      },
    });
    const user = userEvent.setup();
    renderFlows('/approvals/flows/1', 'CHANGE_MANAGER');
    const name = await screen.findByRole('textbox', { name: 'Stage 1 name' });
    // System flow: no delete, cannot be deactivated, code read-only.
    expect(screen.queryByRole('button', { name: /Delete flow/ })).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Active' })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: 'Code' })).toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: /Save flow/ })).toBeDisabled();

    await user.type(name, ' (CAB)');
    await user.click(screen.getByRole('button', { name: /Save flow/ }));
    await waitFor(() => expect(all('PUT', '/approval-flows/1')).toHaveLength(1));
    expect(last('PUT', '/approval-flows/1')?.body).toMatchObject({ code: 'CHANGE_NORMAL', active: true, stages: [{ name: 'Change authority (CAB)' }, { name: 'Change Advisory Board', requiredCount: 2 }] });

    expect(await within(stage(2)).findByText('requiredCount cannot exceed the number of approvers')).toBeInTheDocument();
    expect(within(stage(2)).getByRole('spinbutton', { name: /Required approvals/ })).toHaveAttribute('aria-invalid', 'true');
    expect(within(stage(1)).getByText('Approver user 99 is not an active user')).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: 'Stage 2 rule 1' })).getByText('Every condition needs a valid field name and an operator')).toBeInTheDocument();
    expect(within(stage(1)).queryByText('requiredCount cannot exceed the number of approvers')).not.toBeInTheDocument();
    expect(screen.getByText('Stage 1: Unexpected stage problem')).toBeInTheDocument();
  });

  it('shows a duplicate code (409) on the code field', async () => {
    setup({ 'POST /approval-flows': { status: 409, data: apiErrorBody(409, 'Approval flow code DUP is already in use') } });
    const user = userEvent.setup();
    renderFlows('/approvals/flows/new', 'IT_MANAGER');
    await user.type(await screen.findByRole('textbox', { name: 'Code' }), 'DUP');
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'Duplicate');
    await user.selectOptions(within(stage(1)).getByRole('combobox', { name: 'Role' }), 'IT_MANAGER');
    await user.click(screen.getByRole('button', { name: /Create flow/ }));
    expect(await screen.findByText('Approval flow code DUP is already in use')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Code' })).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows USER approvers by name when the API sends userNames', () => {
    const flow = {
      id: 9, code: 'NAMED', name: 'Named', description: null, subjectType: null, active: true, systemFlow: false, demo: false,
      createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', userNames: { '55': 'Priya Raman' },
      stages: [{ name: 'Sign-off', mode: 'PARALLEL', quorum: 'ANY', requiredCount: null, approvers: [{ kind: 'USER', userId: 55, role: null, teamId: null }] }],
    } as unknown as ApprovalFlowDto;
    expect(flowToValues(flow).stages[0].approvers[0].userName).toBe('Priya Raman');
    expect(describeApprover(flow.stages[0].approvers[0], undefined, flow.userNames)).toBe('Priya Raman');
    expect(describeApprover(flow.stages[0].approvers[0])).toBe('User #55');
  });

  it('confirms before deleting a flow and returns to the list', async () => {
    setup({ 'DELETE /approval-flows/7': { status: 204 } });
    const user = userEvent.setup();
    renderFlows('/approvals/flows/7', 'IT_MANAGER');
    await screen.findByRole('textbox', { name: 'Stage 1 name' });
    expect(within(screen.getByRole('group', { name: 'Stage 1 approver 1' })).getByRole('combobox', { name: 'Team' })).toHaveValue('3');
    expect(within(screen.getByRole('group', { name: 'Stage 1 approver 2' })).getByText('User #55')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Delete flow/ }));
    let dialog = await screen.findByRole('dialog', { name: 'Delete Access review?' });
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(all('DELETE', '/approval-flows/7')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: /Delete flow/ }));
    dialog = await screen.findByRole('dialog', { name: 'Delete Access review?' });
    await user.click(within(dialog).getByRole('button', { name: 'Delete flow' }));
    await waitFor(() => expect(all('DELETE', '/approval-flows/7')).toHaveLength(1));
    expect(await screen.findByRole('table', { name: 'Approval flows' })).toBeInTheDocument();
    expect(screen.getByText('Access review deleted')).toBeInTheDocument();
  });

  it('is read-only for readers without editor rights', async () => {
    setup();
    renderFlows('/approvals/flows/1', 'AUDITOR');
    const stages = await screen.findByRole('list', { name: 'Stages' });
    expect(screen.getByText('Read-only')).toBeInTheDocument();
    const cab = within(stages).getByRole('listitem', { name: 'Stage 2: Change Advisory Board' });
    expect(cab).toHaveTextContent('Needs 2 of 3 approvals');
    expect(cab).toHaveTextContent('Only when changeRisk equals HIGH');
    expect(cab).toHaveTextContent("Due in 72 h · overdue: approvers' managers notified, System Admins notified");
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Save flow|Delete flow|Add stage/ })).not.toBeInTheDocument();
  });
});

describe('flowModel', () => {
  it('round-trips a flow through the editor values', () => {
    expect(toFlowRequest(flowToValues(CHANGE_NORMAL))).toEqual({
      code: 'CHANGE_NORMAL',
      name: 'Normal change',
      description: 'Change authority, then CAB for high-risk changes',
      subjectType: 'CHANGE',
      active: true,
      stages: CHANGE_NORMAL.stages,
    });
    expect(toFlowRequest(flowToValues(ACCESS_REVIEW)).stages).toEqual(ACCESS_REVIEW.stages);
  });

  it('maps server field paths onto form paths', () => {
    expect(
      mapFlowFieldErrors([
        { field: 'code', message: 'a' },
        { field: 'stages', message: 'b' },
        { field: 'stages[2].name', message: 'c' },
        { field: 'stages[0].approvers[1].userId', message: 'd' },
        { field: 'stages[0].approvers[1]', message: 'e' },
        { field: 'stages[1].deadlineHours', message: 'f' },
        { field: 'stages[1].condition.match', message: 'g' },
        { field: 'stages[1].condition', message: 'h' },
        { field: 'stages[1].escalation.notifyRole', message: 'i' },
        { field: 'stages[1].escalation', message: 'j' },
        { field: 'other', message: 'k' },
      ]),
    ).toEqual({
      fields: [
        { name: 'code', message: 'a' },
        { name: 'stages.root', message: 'b' },
        { name: 'stages.2.name', message: 'c' },
        { name: 'stages.0.approvers.1.userId', message: 'd' },
        { name: 'stages.0.approvers.root', message: 'Approver 2: e' },
        { name: 'stages.1.deadlineHours', message: 'f' },
        { name: 'stages.1.conditionMatch', message: 'g' },
        { name: 'stages.1.conditionRules.root', message: 'h' },
        { name: 'stages.1.notifyRole', message: 'i' },
        { name: 'stages.1.escalate', message: 'j' },
      ],
      general: ['k'],
    });
  });
});
