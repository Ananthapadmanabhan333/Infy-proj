import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ExecutionCard } from '../../components/runbooks/ExecutionCard';
import type { Role, RunbookDto, RunbookExecutionDto } from '../../lib/types';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import RunbookDetailPage from './RunbookDetailPage';
import { RunbookEditor } from './RunbookEditor';
import RunbooksPage from './RunbooksPage';

function runbook(overrides: Partial<RunbookDto> = {}): RunbookDto {
  return {
    id: 11,
    title: 'Payroll service restart',
    description: 'Restores payroll when the connection pool is exhausted.',
    serviceId: 1,
    serviceName: 'Payroll',
    categories: ['APPLICATION', 'DATABASE'],
    prerequisites: ['VPN access to the payroll subnet', 'Payroll admin credentials'],
    steps: [
      { order: 2, title: 'Restart payroll service', instruction: 'Run the restart job on both nodes.', requiresApproval: true },
      { order: 1, title: 'Check database pool', instruction: 'Confirm active connections exceed 90%.', requiresApproval: false },
    ],
    requiresApproval: true,
    estimatedMinutes: 25,
    active: true,
    authorName: 'Rajesh Iyer',
    executionCount: 3,
    updatedAt: '2026-11-01T10:00:00Z',
    demo: false,
    ...overrides,
  };
}

function execution(overrides: Partial<RunbookExecutionDto> = {}): RunbookExecutionDto {
  return {
    id: 501,
    runbookId: 11,
    runbookTitle: 'Payroll service restart',
    incidentId: 7846,
    incidentNumber: 'INC-7846',
    status: 'IN_PROGRESS',
    startedByName: 'Riya Varma',
    approval: null,
    stepStates: [
      { order: 1, title: 'Check database pool', done: true, notes: null, completedByName: 'Riya Varma', completedAt: '2026-11-03T09:00:00Z', requiresApproval: false, approvalStatus: 'NOT_REQUIRED' },
      { order: 2, title: 'Restart payroll service', done: false, notes: null, completedByName: null, completedAt: null, requiresApproval: true, approvalStatus: 'PENDING' },
      { order: 3, title: 'Verify deployment', done: false, notes: null, completedByName: null, completedAt: null, requiresApproval: false, approvalStatus: 'NOT_REQUIRED' },
    ],
    outcomeNotes: null,
    startedAt: '2026-11-03T08:55:00Z',
    completedAt: null,
    createdAt: '2026-11-03T08:55:00Z',
    ...overrides,
  };
}

const history = (content: RunbookExecutionDto[]) => ({ content, page: 0, size: 5, totalElements: content.length, totalPages: 1 });

describe('RunbooksPage', () => {
  it('lists runbooks with service, categories, steps, sensitive count and approval, and filters by category', async () => {
    mockApi({
      'GET /services': json([{ id: 1, name: 'Payroll' }]),
      'GET /runbooks': json([runbook(), runbook({ id: 12, title: 'Legacy relay cleanup', active: false, requiresApproval: false, steps: [], categories: [], serviceName: null })]),
      'GET /runbooks/executions': json(history([execution()])),
    });
    renderWithProviders(<RunbooksPage />, { route: '/runbooks', auth: makeAuth(makeUser('IT_MANAGER')) });
    const user = userEvent.setup();
    expect(screen.getByRole('button', { name: /New Runbook/ })).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Runbooks' });
    const row = within(list).getByRole('link', { name: 'Runbook Payroll service restart' });
    expect(row).toHaveAttribute('href', '/runbooks/11');
    expect(row).toHaveTextContent('Application, Database');
    expect(row).toHaveTextContent('2 steps');
    expect(row).toHaveTextContent('1 sensitive');
    expect(row).toHaveTextContent('Approval required');

    // Active / inactive filter works on the loaded list.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'inactive');
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Runbooks' })).queryByRole('link', { name: 'Runbook Payroll service restart' })).not.toBeInTheDocument());

    await user.selectOptions(screen.getByRole('combobox', { name: 'Category' }), 'DATABASE');
    await waitFor(() => expect(requests.some((r) => r.url === '/runbooks' && (r.params as Record<string, string>).category === 'DATABASE')).toBe(true));

    // Cross-runbook execution history (paged endpoint).
    expect(await screen.findByRole('button', { name: 'Open execution of Payroll service restart on INC-7846' })).toBeInTheDocument();
  });

  it('readers cannot create runbooks', async () => {
    mockApi({ 'GET /services': json([]), 'GET /runbooks': json([]), 'GET /runbooks/executions': json(history([])) });
    renderWithProviders(<RunbooksPage />, { route: '/runbooks', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    expect(await screen.findByText('No runbooks yet')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New Runbook/ })).not.toBeInTheDocument();
  });
});

describe('RunbookDetailPage', () => {
  function renderDetail(role: Role) {
    mockApi({
      'GET /runbooks/11': json(runbook()),
      'GET /runbooks/executions': json(history([execution()])),
      'GET /services': json([]),
    });
    return renderWithProviders(
      <Routes>
        <Route path="/runbooks/:id" element={<RunbookDetailPage />} />
      </Routes>,
      { route: '/runbooks/11', auth: makeAuth(makeUser(role)) },
    );
  }

  it('shows prerequisites, ordered steps with sensitive markers, categories and paged execution history', async () => {
    renderDetail('SUPPORT_AGENT');
    expect(await screen.findByRole('heading', { level: 1, name: 'Payroll service restart' })).toBeInTheDocument();
    const prereqs = screen.getByRole('region', { name: 'Prerequisites' });
    expect(prereqs).toHaveTextContent('VPN access to the payroll subnet');
    const steps = within(screen.getByRole('list', { name: 'Runbook steps' })).getAllByRole('listitem');
    expect(steps.map((step) => step.querySelector('b')?.textContent)).toEqual(['Check database pool', 'Restart payroll service']);
    expect(steps[1]).toHaveTextContent('Sensitive — approval required');
    expect(steps[0]).not.toHaveTextContent('Sensitive');
    expect(screen.getByRole('region', { name: 'Overview' })).toHaveTextContent('Database');
    expect(screen.getByRole('button', { name: /Run on incident/ })).toBeInTheDocument();
    // Agents run runbooks but do not edit them.
    expect(screen.queryByRole('button', { name: /Edit runbook/ })).not.toBeInTheDocument();
    await waitFor(() => expect(requests.find((r) => r.url === '/runbooks/executions')?.params).toMatchObject({ runbookId: 11, page: 0, size: 5 }));
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /Open execution of Payroll service restart/ }));
    expect(await screen.findByRole('dialog', { name: 'Payroll service restart' })).toBeInTheDocument();
  });

  it('runbook editors can edit', async () => {
    renderDetail('IT_ADMIN');
    expect(await screen.findByRole('button', { name: /Edit runbook/ })).toBeInTheDocument();
  });
});

describe('RunbookEditor', () => {
  it('edits prerequisites as a list and reorders steps with their sensitive flag', async () => {
    mockApi({ 'GET /services': json([]), 'PUT /runbooks/11': json(runbook()) });
    const onClose = vi.fn();
    renderWithProviders(<RunbookEditor runbook={runbook()} onClose={onClose} />, { auth: makeAuth(makeUser('IT_MANAGER')) });
    const user = userEvent.setup();
    expect(screen.getByLabelText('Prerequisite 1')).toHaveValue('VPN access to the payroll subnet');
    await user.click(screen.getByRole('button', { name: 'Remove prerequisite 2' }));
    await user.click(screen.getByRole('button', { name: /Add prerequisite/ }));
    await user.type(screen.getByLabelText('Prerequisite 2'), 'Change ticket approved');
    // Steps load in order; move the sensitive restart step above the check.
    expect(screen.getAllByLabelText('Step title')[0]).toHaveValue('Check database pool');
    await user.click(screen.getByRole('button', { name: 'Move step 2 up' }));
    await user.click(screen.getByRole('button', { name: 'Save runbook' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const body = requests.find((r) => r.method === 'PUT' && r.url === '/runbooks/11')?.body as Record<string, unknown>;
    expect(body.prerequisites).toEqual(['VPN access to the payroll subnet', 'Change ticket approved']);
    expect(body.categories).toEqual(['APPLICATION', 'DATABASE']);
    expect(body.steps).toEqual([
      { order: 1, title: 'Restart payroll service', instruction: 'Run the restart job on both nodes.', requiresApproval: true },
      { order: 2, title: 'Check database pool', instruction: 'Confirm active connections exceed 90%.', requiresApproval: false },
    ]);
    expect(body).toMatchObject({ requiresApproval: true, active: true, estimatedMinutes: 25, serviceId: 1 });
  });
});

describe('ExecutionCard', () => {
  it('a pending sensitive step is locked for non-approvers and shows its approval status', () => {
    mockApi({ 'GET /runbooks/11': json(runbook()) });
    renderWithProviders(<ExecutionCard execution={execution()} canRun />, { auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    expect(screen.getByText('Awaiting Incident Manager approval')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Restart payroll service' })).toBeDisabled();
    expect(screen.getByText(/Waiting for an Incident Manager to approve/)).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Verify deployment' })).toBeEnabled();
  });

  it('an Incident Manager approves and completes a pending sensitive step directly', async () => {
    mockApi({
      'GET /runbooks/11': json(runbook()),
      'POST /runbooks/executions/501/steps/2': json(execution()),
    });
    renderWithProviders(<ExecutionCard execution={execution()} canRun />, { auth: makeAuth(makeUser('INCIDENT_MANAGER')) });
    const user = userEvent.setup();
    const box = screen.getByRole('checkbox', { name: 'Approve and complete “Restart payroll service”' });
    expect(box).toBeEnabled();
    await user.click(box);
    await waitFor(() => expect(requests.find((r) => r.url === '/runbooks/executions/501/steps/2')?.body).toEqual({ done: true, notes: null }));
  });

  it('completes the execution with outcome notes', async () => {
    mockApi({
      'GET /runbooks/11': json(runbook()),
      'POST /runbooks/executions/501/complete': json(execution({ status: 'COMPLETED' })),
    });
    renderWithProviders(<ExecutionCard execution={execution()} canRun />, { auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Complete runbook' }));
    const dialog = screen.getByRole('dialog', { name: 'Complete runbook' });
    expect(dialog).toHaveTextContent('2 step(s) are not done yet.');
    await user.type(within(dialog).getByLabelText('Outcome notes'), 'Service restored');
    await user.click(within(dialog).getByRole('button', { name: 'Complete' }));
    await waitFor(() => expect(requests.find((r) => r.url === '/runbooks/executions/501/complete')?.body).toEqual({ outcomeNotes: 'Service restored' }));
  });
});
