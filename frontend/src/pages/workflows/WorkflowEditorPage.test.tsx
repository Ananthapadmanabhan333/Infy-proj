import type { ReactNode } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { ToastProvider } from '../../components/Toast';
import { departments, page, payrollWorkflow, runbooks, templates, workflowMeta } from '../../components/workflow/testFixtures';
import { AuthContext } from '../../hooks/useAuth';
import type { Role, WorkflowRequest } from '../../lib/types';
import { json, mockApi, requests, type MockHandler, type MockReply } from '../../test/mockApi';
import { makeAuth, makeUser } from '../../test/utils';
import WorkflowEditorPage from './WorkflowEditorPage';

const incident = {
  id: 46,
  number: 'INC-7846',
  title: 'Payroll application not accessible',
  priority: 'CRITICAL',
  impact: 'HIGH',
  urgency: 'HIGH',
  status: 'IN_PROGRESS',
  category: 'APPLICATION',
  serviceId: 3,
  serviceName: 'Payroll',
  departmentId: 2,
  departmentName: 'Payroll Team',
  assigneeId: null,
  assigneeName: null,
  requesterId: 7,
  requesterName: 'Ada Lovelace',
  parentId: null,
  parentNumber: null,
  major: false,
  childCount: 0,
  createdAt: '2026-11-03T09:00:00Z',
  updatedAt: '2026-11-03T09:00:00Z',
  resolvedAt: null,
  sla: null,
  demo: false,
};

function setup({
  path = '/automation/1',
  state,
  role = 'SYSTEM_ADMIN',
  routes = {},
}: { path?: string; state?: unknown; role?: Role; routes?: Record<string, MockHandler | MockReply> } = {}) {
  mockApi({
    'GET /workflows/meta': json(workflowMeta),
    'GET /workflows/1': json(payrollWorkflow),
    'GET /workflows/templates': json(templates),
    'GET /workflows/1/versions': json([
      { versionNumber: 3, definition: payrollWorkflow.definition, changeNote: 'Added No branch', createdByName: 'Priya Raman', createdAt: '2026-11-02T10:00:00Z' },
      { versionNumber: 2, definition: { ...payrollWorkflow.definition, elseActions: [] }, changeNote: null, createdByName: 'Priya Raman', createdAt: '2026-11-01T10:00:00Z' },
    ]),
    'GET /runbooks': json(runbooks),
    'GET /departments': json(departments),
    ...routes,
  });
  const [pathname, search = ''] = path.split('?');
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter initialEntries={[{ pathname, search: search ? `?${search}` : '', state }]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <AuthContext.Provider value={makeAuth(makeUser(role))}>{children}</AuthContext.Provider>
        </ToastProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
  return render(
    <Routes>
      <Route path="/automation/new" element={<WorkflowEditorPage />} />
      <Route path="/automation/:id" element={<WorkflowEditorPage />} />
    </Routes>,
    { wrapper: Wrapper },
  );
}

const lastBody = (method: string, url: string) => requests.filter((request) => request.method === method && request.url === url).at(-1)?.body as WorkflowRequest | undefined;

describe('WorkflowEditorPage (visual builder)', () => {
  it('maps a saved workflow onto the canvas: trigger, condition diamond, Yes and No branches', async () => {
    setup();
    expect(await screen.findByRole('button', { name: /^Trigger: New Incident\. Service: PAYROLL/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Condition: Priority = Critical and Service code = PAYROLL?' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /Yes branch, step 1: Trigger approved runbook — Payroll service restart/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Yes branch, step 2: Send notification to team/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /No branch, step 1: Assign team — to IT Service Desk/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Workflow settings' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Save new version/ })).toBeDisabled();
    // all triggers and actions from /workflows/meta are in the palette, with spec labels
    const palette = screen.getByRole('complementary', { name: 'Workflow building blocks' });
    expect(within(palette).getByRole('button', { name: 'Schedule' })).toBeInTheDocument();
    expect(within(palette).getByRole('button', { name: 'Priority Changed' })).toBeInTheDocument();
    expect(within(palette).getByRole('button', { name: 'Add Assign team to the Yes branch' })).toBeInTheDocument();
    expect(within(palette).getByRole('button', { name: 'Add Declare major to the Yes branch' })).toBeInTheDocument();
  });

  it('edits the canvas and saves a new version with a change note', async () => {
    const user = userEvent.setup();
    setup({
      routes: {
        'PUT /workflows/1': (config) => json({ ...payrollWorkflow, currentVersion: 4, definition: JSON.parse(String(config.data)).definition }),
      },
    });
    const palette = await screen.findByRole('complementary', { name: 'Workflow building blocks' });
    // select the No branch, then add an action from the palette to it
    await user.click(screen.getByRole('button', { name: /No branch, step 1/ }));
    expect(screen.getByRole('heading', { name: 'Assign team' })).toBeInTheDocument();
    await user.click(within(palette).getByRole('button', { name: 'Add Declare major to the No branch' }));
    expect(screen.getByRole('button', { name: /No branch, step 2: Declare major/ })).toBeInTheDocument();
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();

    // edit the condition: switch to ANY
    await user.click(screen.getByRole('button', { name: /^Condition:/ }));
    await user.click(screen.getByRole('radio', { name: 'Any condition (OR)' }));
    expect(screen.getByRole('button', { name: 'Condition: Priority = Critical or Service code = PAYROLL?' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Save new version/ }));
    const dialog = screen.getByRole('dialog', { name: 'Save version v4?' });
    await user.type(within(dialog).getByLabelText('Change note'), 'Declare major when not payroll');
    await user.click(within(dialog).getByRole('button', { name: 'Save version' }));

    await waitFor(() => expect(lastBody('PUT', '/workflows/1')).toBeDefined());
    const body = lastBody('PUT', '/workflows/1')!;
    expect(body).toMatchObject({ name: payrollWorkflow.name, triggerEvent: 'INCIDENT_CREATED', active: true, changeNote: 'Declare major when not payroll' });
    expect(body.definition.conditions.match).toBe('ANY');
    expect(body.definition.actions.map((action) => action.type)).toEqual(['START_RUNBOOK', 'NOTIFY_DEPARTMENT']);
    expect(body.definition.elseActions).toEqual([
      { type: 'ASSIGN_DEPARTMENT', params: { departmentCode: 'IT-SD' } },
      { type: 'DECLARE_MAJOR', params: {} },
    ]);
    expect(await screen.findByText(`Saved ${payrollWorkflow.name} as v4`)).toBeInTheDocument();
  });

  it('removes an action with the Delete key and offers undo', async () => {
    const user = userEvent.setup();
    setup();
    const node = await screen.findByRole('button', { name: /Yes branch, step 2/ });
    node.focus();
    await user.keyboard('{Delete}');
    expect(screen.queryByRole('button', { name: /Yes branch, step 2/ })).not.toBeInTheDocument();
    // the toast offers Undo; so does the canvas toolbar (Ctrl+Z)
    await user.click(within(screen.getByRole('region', { name: 'Notifications' })).getByRole('button', { name: 'Undo' }));
    expect(screen.getByRole('button', { name: /Yes branch, step 2: Send notification to team/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Yes branch, step 2/ }));
    await user.click(screen.getByRole('button', { name: /Remove$/ }));
    expect(screen.queryByRole('button', { name: /Yes branch, step 2/ })).not.toBeInTheDocument();
    await user.click(within(screen.getByRole('region', { name: 'Workflow canvas' })).getByRole('button', { name: 'Undo' }));
    expect(screen.getByRole('button', { name: /Yes branch, step 2/ })).toBeInTheDocument();
  });

  it('validates on save: a new workflow needs a name and a Yes action, and nothing is sent', async () => {
    const user = userEvent.setup();
    setup({ path: '/automation/new' });
    await user.click(await screen.findByRole('button', { name: /Save workflow/ }));
    const panel = screen.getByRole('region', { name: 'Validation' });
    expect(within(panel).getByText('Give the workflow a name')).toBeInTheDocument();
    expect(within(panel).getByText('Add at least one action to the Yes branch')).toBeInTheDocument();
    expect(requests.some((request) => request.method === 'POST' && request.url === '/workflows')).toBe(false);
  });

  it('creates a workflow built on the canvas', async () => {
    const user = userEvent.setup();
    setup({
      path: '/automation/new',
      routes: { 'POST /workflows': (config) => json({ ...payrollWorkflow, id: 9, currentVersion: 1, active: false, ...JSON.parse(String(config.data)) }, 201) },
    });
    await user.type(await screen.findByRole('textbox', { name: 'Workflow name' }), 'P1 bridge');
    const palette = screen.getByRole('complementary', { name: 'Workflow building blocks' });
    await user.click(within(palette).getByRole('button', { name: 'Priority Changed' }));
    expect(screen.getByRole('heading', { name: 'Priority Changed' })).toBeInTheDocument();
    await user.click(within(palette).getByRole('button', { name: 'Add Declare major to the Yes branch' }));
    await user.click(screen.getByRole('button', { name: /Save workflow/ }));
    await user.click(within(screen.getByRole('dialog', { name: 'Save workflow' })).getByRole('button', { name: 'Save workflow' }));
    await waitFor(() => expect(lastBody('POST', '/workflows')).toBeDefined());
    expect(lastBody('POST', '/workflows')).toMatchObject({
      name: 'P1 bridge',
      triggerEvent: 'PRIORITY_CHANGED',
      active: false,
      changeNote: 'Initial version',
      definition: { conditions: { match: 'ALL', rules: [] }, actions: [{ type: 'DECLARE_MAJOR', params: {} }], elseActions: [], schedule: null },
    });
    expect(await screen.findByText('P1 bridge created as v1')).toBeInTheDocument();
  });

  it('pre-fills an unsaved draft from an SA AI suggestion and shows its source label', async () => {
    setup({
      path: '/automation/new',
      state: {
        suggestion: {
          name: 'Auto-route VPN incidents',
          description: 'Routes VPN problems to the network team',
          triggerEvent: 'INCIDENT_CREATED',
          definition: {
            conditions: { match: 'ALL', rules: [{ field: 'serviceCode', operator: 'EQUALS', value: 'VPN' }] },
            actions: [{ type: 'ASSIGN_DEPARTMENT', params: { departmentCode: 'PAY' } }],
            elseActions: [],
          },
          explanation: 'VPN incidents were reassigned 14 times last month.',
        },
        source: { source: 'LLM', provider: 'anthropic', model: 'claude', notice: 'Generated by a language model — review before use' },
      },
    });
    expect(await screen.findByRole('textbox', { name: 'Workflow name' })).toHaveValue('Auto-route VPN incidents');
    const card = screen.getByRole('article', { name: 'Drafted by SA AI — review before saving' });
    expect(within(card).getByText('LLM · anthropic · claude')).toBeInTheDocument();
    expect(within(card).getByText('VPN incidents were reassigned 14 times last month.')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /Yes branch, step 1: Assign team — to Payroll Team/ })).toBeInTheDocument();
    expect(screen.getByText('Not saved yet')).toBeInTheDocument();
    expect(requests.some((request) => request.method !== 'GET')).toBe(false);
  });

  it('labels a suggestion that arrives without a source, and a deterministic fallback as such', async () => {
    const suggestion = {
      name: 'Fallback draft',
      description: '',
      triggerEvent: 'SLA_BREACHED',
      definition: { conditions: { match: 'ALL', rules: [] }, actions: [{ type: 'DECLARE_MAJOR', params: {} }], elseActions: [] },
      explanation: 'Rule-based draft.',
    };
    const first = setup({ path: '/automation/new', state: { suggestion } });
    expect(await screen.findByText('Source label not provided')).toBeInTheDocument();
    first.unmount();
    setup({ path: '/automation/new', state: { suggestion, source: { source: 'FALLBACK', provider: 'fallback', model: null, notice: 'Deterministic' } } });
    expect(await screen.findByText('Deterministic fallback — not AI-generated')).toBeInTheDocument();
  });

  it('opens a template pre-filled (scheduled trigger with its interval)', async () => {
    const user = userEvent.setup();
    setup({ path: '/automation/new?template=stale-pending-reminder' });
    expect(await screen.findByText(/Started from the/)).toHaveTextContent('Stale pending reminder');
    await user.click(screen.getByRole('button', { name: /^Trigger: Schedule\. Every 4 h/ }));
    const interval = screen.getByRole('spinbutton', { name: /Run every \(minutes\)/ });
    expect(interval).toHaveValue(240);
    await user.clear(interval);
    await user.type(interval, '10');
    await user.click(screen.getByRole('button', { name: /^Validate/ }));
    expect(within(screen.getByRole('region', { name: 'Validation' })).getByText(/set an interval of 15 or more/)).toBeInTheDocument();
  });

  it('tests the saved version against an incident and shows rule results and planned actions', async () => {
    const user = userEvent.setup();
    setup({
      routes: {
        'GET /incidents': json(page([incident])),
        'POST /workflows/1/test': json({
          matched: true,
          branch: 'YES',
          ruleResults: [
            { rule: { field: 'priority', operator: 'EQUALS', value: 'CRITICAL' }, matched: true, actual: 'CRITICAL' },
            { rule: { field: 'serviceCode', operator: 'EQUALS', value: 'PAYROLL' }, matched: true, actual: 'PAYROLL' },
          ],
          plannedActions: ['Start runbook "Payroll service restart"', 'Notify department'],
        }),
      },
    });
    await user.click(await screen.findByRole('button', { name: /^Test$/ }));
    const dialog = screen.getByRole('dialog', { name: `Test ${payrollWorkflow.name}` });
    await user.type(within(dialog).getByLabelText('Incident'), 'INC-78');
    await user.click(await within(dialog).findByRole('option', { name: /INC-7846/ }));
    await user.click(within(dialog).getByRole('button', { name: /Run test/ }));
    expect(await within(dialog).findByText('Conditions matched — the Yes branch would run.')).toBeInTheDocument();
    expect(within(dialog).getByText('Priority = Critical')).toBeInTheDocument();
    expect(within(dialog).getByText('Start runbook "Payroll service restart"')).toBeInTheDocument();
    expect(lastBody('POST', '/workflows/1/test')).toEqual({ incidentId: 46 });
  });

  it('lists versions and rolls back after confirmation', async () => {
    const user = userEvent.setup();
    setup({ routes: { 'POST /workflows/1/rollback': json({ ...payrollWorkflow, currentVersion: 4 }) } });
    await user.click(await screen.findByRole('button', { name: /Versions/ }));
    const drawer = screen.getByRole('dialog', { name: payrollWorkflow.name });
    expect(await within(drawer).findByText('Added No branch')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: 'Roll back' }));
    await user.click(within(screen.getByRole('dialog', { name: 'Roll back to v2?' })).getByRole('button', { name: 'Roll back' }));
    await waitFor(() => expect(lastBody('POST', '/workflows/1/rollback')).toEqual({ versionNumber: 2 }));
    expect(await screen.findByText('Rolled back to v2 — now v4')).toBeInTheDocument();
  });

  it('activation of a saved workflow is a confirmed, separate action', async () => {
    const user = userEvent.setup();
    setup({ routes: { 'POST /workflows/1/activate': json({ ...payrollWorkflow, active: false }) } });
    await user.click(await screen.findByRole('switch', { name: `Deactivate ${payrollWorkflow.name}` }));
    await user.click(within(screen.getByRole('dialog', { name: `Deactivate ${payrollWorkflow.name}?` })).getByRole('button', { name: 'Deactivate' }));
    await waitFor(() => expect(lastBody('POST', '/workflows/1/activate')).toEqual({ active: false }));
    expect(screen.getByRole('button', { name: /Save new version/ })).toBeDisabled();
  });

  it('is read-only for auditors: no palette, no save, disabled editors', async () => {
    const user = userEvent.setup();
    setup({ role: 'AUDITOR' });
    await screen.findByRole('button', { name: /^Condition:/ });
    expect(screen.queryByRole('complementary', { name: 'Workflow building blocks' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Save/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Test$/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Read-only/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Condition:/ }));
    expect(screen.getByRole('heading', { name: 'Conditions' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Add condition/ })).toBeDisabled();
  });

  it('shows an error state when the builder metadata cannot be loaded', async () => {
    mockApi({ 'GET /workflows/1': json(payrollWorkflow) });
    setup({ routes: { 'GET /workflows/meta': { status: 500, data: { status: 500, error: 'Error', message: 'Boom', path: '/api/workflows/meta', timestamp: '' } } } });
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the workflow builder');
  });
});
