import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { failedRun, page, payrollWorkflow, reminderWorkflow, succeededRun, templates, workflowMeta } from '../../components/workflow/testFixtures';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { Role } from '../../lib/types';
import WorkflowsPage from './WorkflowsPage';

function Probe() {
  const location = useLocation();
  return <div data-testid="location">{`${location.pathname}${location.search}`}</div>;
}

function setup(route = '/automation', role: Role = 'SYSTEM_ADMIN') {
  mockApi({
    'GET /workflows': json([payrollWorkflow, reminderWorkflow]),
    'GET /workflows/templates': json(templates),
    'GET /workflows/meta': json(workflowMeta),
    'GET /workflows/executions': json(page([failedRun, succeededRun])),
    'POST /workflows/1/activate': (config) => json({ ...payrollWorkflow, active: JSON.parse(String(config.data)).active }),
    'POST /workflows/executions/501/retry': json({ status: 'RETRY_SCHEDULED' }),
  });
  return renderWithProviders(
    <Routes>
      <Route path="/automation" element={<WorkflowsPage />} />
      <Route path="*" element={<Probe />} />
    </Routes>,
    { route, auth: makeAuth(makeUser(role)) },
  );
}

describe('WorkflowsPage (/automation)', () => {
  it('shows the header, tabs with counts and a card per workflow', async () => {
    setup();
    expect(screen.getByRole('heading', { name: 'Workflow Automation' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Create Workflow/ })).toBeInTheDocument();
    const card = await screen.findByRole('article', { name: payrollWorkflow.name });
    expect(within(card).getByText('New Incident')).toBeInTheDocument();
    expect(within(card).getByText('v3')).toBeInTheDocument();
    expect(within(card).getByText('14')).toBeInTheDocument();
    expect(within(card).getByText(/Trigger approved runbook → Send notification to team/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /My Workflows \(2\)/ })).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(screen.getByRole('tab', { name: /Templates \(2\)/ })).toBeInTheDocument());
    expect(screen.getByRole('article', { name: 'Stale pending reminder' })).toBeInTheDocument();
  });

  it('filters the cards by search text and status', async () => {
    const user = userEvent.setup();
    setup();
    await screen.findByRole('article', { name: payrollWorkflow.name });
    await user.type(screen.getByRole('searchbox', { name: 'Search workflows' }), 'stale');
    expect(screen.queryByRole('article', { name: payrollWorkflow.name })).not.toBeInTheDocument();
    await user.clear(screen.getByRole('searchbox', { name: 'Search workflows' }));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'active');
    expect(screen.queryByRole('article', { name: 'Stale pending reminder' })).not.toBeInTheDocument();
    expect(screen.getByRole('article', { name: payrollWorkflow.name })).toBeInTheDocument();
  });

  it('deactivates a workflow only after confirmation', async () => {
    const user = userEvent.setup();
    setup();
    await user.click(await screen.findByRole('switch', { name: `Deactivate ${payrollWorkflow.name}` }));
    const dialog = screen.getByRole('dialog', { name: `Deactivate ${payrollWorkflow.name}?` });
    expect(requests.some((request) => request.url.endsWith('/activate'))).toBe(false);
    await user.click(within(dialog).getByRole('button', { name: 'Deactivate' }));
    await waitFor(() => expect(requests.find((request) => request.url === '/workflows/1/activate')?.body).toEqual({ active: false }));
    expect(await screen.findByText(`${payrollWorkflow.name} deactivated`)).toBeInTheDocument();
  });

  it('opens the builder pre-filled from a template', async () => {
    const user = userEvent.setup();
    setup('/automation?tab=templates');
    await user.click(await screen.findByRole('button', { name: 'Use template Payroll incident – auto resolution' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/automation/new?template=payroll-auto-resolution');
  });

  it('lists run history with branch, incident link, step log drawer and retry', async () => {
    const user = userEvent.setup();
    setup('/automation?tab=history');
    const row = await screen.findByRole('row', { name: /Run 501 of/ });
    expect(within(row).getByRole('link', { name: 'INC-7846' })).toHaveAttribute('href', '/incidents/46');
    expect(within(row).getByText('Yes')).toBeInTheDocument();
    expect(within(row).getByText('Runbook 4 requires approval')).toBeInTheDocument();
    expect(within(screen.getByRole('row', { name: /Run 500 of/ })).queryByRole('button', { name: /Retry/ })).not.toBeInTheDocument();

    await user.click(row);
    const drawer = screen.getByRole('dialog', { name: `${payrollWorkflow.name} · v3` });
    expect(within(drawer).getByText('Step log')).toBeInTheDocument();
    expect(within(drawer).getByText('Trigger approved runbook')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: /Retry run/ }));
    await waitFor(() => expect(requests.some((request) => request.method === 'POST' && request.url === '/workflows/executions/501/retry')).toBe(true));
    expect(await screen.findByText('Retry scheduled for run #501')).toBeInTheDocument();
  });

  it('passes the workflow and status filters to the executions API', async () => {
    const user = userEvent.setup();
    setup('/automation?tab=history&workflowId=1');
    await screen.findByRole('row', { name: /Run 501 of/ });
    expect(requests.find((request) => request.url === '/workflows/executions')?.params).toMatchObject({ workflowId: '1', page: 0 });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'FAILED');
    await waitFor(() => expect(requests.filter((request) => request.url === '/workflows/executions').at(-1)?.params).toMatchObject({ status: 'FAILED', workflowId: '1' }));
  });

  it('is read-only for auditors: no create, toggles disabled, no template use, no retry', async () => {
    setup('/automation', 'AUDITOR');
    await screen.findByRole('article', { name: payrollWorkflow.name });
    expect(screen.queryByRole('button', { name: /Create Workflow/ })).not.toBeInTheDocument();
    expect(screen.getByRole('switch', { name: `Deactivate ${payrollWorkflow.name}` })).toBeDisabled();
  });

  it('hides template and retry actions from non-editors', async () => {
    const user = userEvent.setup();
    setup('/automation?tab=templates', 'SUPPORT_AGENT');
    await screen.findByRole('article', { name: 'Payroll incident – auto resolution' });
    expect(screen.queryByRole('button', { name: /Use template/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Run History' }));
    await screen.findByRole('row', { name: /Run 501 of/ });
    expect(screen.queryByRole('button', { name: /Retry/ })).not.toBeInTheDocument();
  });

  it('shows an error state when workflows cannot be loaded', async () => {
    mockApi({ 'GET /workflows/templates': json(templates), 'GET /workflows/meta': json(workflowMeta) });
    renderWithProviders(<WorkflowsPage />, { route: '/automation' });
    expect(await screen.findByRole('alert')).toHaveTextContent(/API unreachable/);
  });
});
