import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { IncidentDetail, Role, SlaDetail } from '../../lib/types';
import { apiErrorBody, json, mockApi, requests, type MockHandler, type MockReply } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import { aiResult, makeDetail, makeResolution, makeSla, makeSummary } from './fixtures.test-utils';
import IncidentDetailPage from './IncidentDetailPage';

const slaDetail: SlaDetail = {
  ...makeSla(),
  startedAt: '2026-11-03T08:24:00Z',
  responseTargetMinutes: 15,
  resolutionTargetMinutes: 240,
  atRiskPercent: 75,
  totalPausedSeconds: 0,
  events: [],
};

const lastRequest = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

function routes(incident: IncidentDetail, extra: Record<string, MockHandler | MockReply> = {}) {
  mockApi({
    [`GET /incidents/${incident.id}`]: json(incident),
    [`GET /incidents/${incident.id}/comments`]: json([]),
    [`GET /incidents/${incident.id}/attachments`]: json([]),
    [`GET /incidents/${incident.id}/sla`]: json(slaDetail),
    [`GET /incidents/${incident.id}/activity`]: json([
      { kind: 'ESCALATION', at: '2026-11-03T09:50:00Z', actorName: 'Riya Varma', title: 'Escalated to level 1', detail: 'Payroll run blocked' },
      { kind: 'APPROVAL', at: '2026-11-03T09:40:00Z', actorName: 'Riya Varma', title: 'Approval requested: Payroll service recovery', detail: 'Routed to INCIDENT_MANAGER' },
      { kind: 'COMMENT', at: '2026-11-03T09:00:00Z', actorName: 'Priya Menon', title: 'Comment', detail: 'Still failing for my team' },
      { kind: 'HISTORY', at: '2026-11-03T08:24:00Z', actorName: 'Priya Menon', title: 'Incident created', detail: null },
    ]),
    'GET /runbooks/executions': json([]),
    'GET /runbooks': json([]),
    'GET /knowledge': json({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }),
    ...extra,
  });
}

function renderDetail(role: Role = 'SUPPORT_AGENT', id = 42, query = '') {
  return renderWithProviders(
    <Routes>
      <Route path="/incidents/:id" element={<IncidentDetailPage />} />
    </Routes>,
    { route: `/incidents/${id}${query}`, auth: makeAuth(makeUser(role)) },
  );
}

const incident = makeDetail({
  id: 42,
  relatedSameService: [makeSummary({ id: 50, number: 'INC-7850', title: 'Payroll slow for HR', status: 'ASSIGNED' })],
});

describe('IncidentDetailPage', () => {
  it('renders the reference header, stepper, tabs and overview facts', async () => {
    routes(incident);
    renderDetail();

    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading).toHaveTextContent('INC-7846');
    expect(heading).toHaveTextContent('Payroll application not accessible');
    const header = heading.closest('header') as HTMLElement;
    expect(within(header).getByRole('link', { name: /Incidents/ })).toHaveAttribute('href', '/incidents');
    expect(within(header).getByText('Critical', { exact: true })).toBeInTheDocument();
    expect(within(header).getByText('In Progress')).toBeInTheDocument();
    expect(within(header).getByText(/Updated/)).toBeInTheDocument();

    const toolbar = within(header).getByRole('toolbar', { name: 'Incident actions' });
    expect(within(toolbar).getAllByRole('button').map((button) => button.textContent?.trim())).toEqual(['Set Pending', 'Escalate', 'More', 'Resolve']);

    const stepper = screen.getByRole('list', { name: 'Progress of INC-7846' });
    expect(within(stepper).getByText('In Progress').closest('li')).toHaveAttribute('aria-current', 'step');

    const tabs = screen.getByRole('tablist', { name: 'Incident sections' });
    expect(within(tabs).getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Overview', 'Activity', 'Related(1)', 'AI Recommendation']);

    const overview = screen.getByRole('tabpanel', { name: 'Overview' });
    expect(within(overview).getByText('~320 users')).toBeInTheDocument();
    expect(within(overview).getByText('Priya M.')).toBeInTheDocument();
    expect(within(overview).getByText('Riya V.')).toBeInTheDocument();
    expect(within(overview).getByText('(Payroll Team)')).toBeInTheDocument();
    expect(await screen.findByText(/P1 Critical 24x7/)).toBeInTheDocument();
  });

  it('escalates with a required reason', async () => {
    routes(incident, { 'POST /incidents/42/escalate': json({ ...incident, escalationLevel: 1 }) });
    renderDetail();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Escalate' }));
    const dialog = screen.getByRole('dialog', { name: 'Escalate INC-7846' });
    const confirm = within(dialog).getByRole('button', { name: 'Escalate' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Reason'), 'Payroll run for the whole company is blocked');
    await user.click(confirm);
    await waitFor(() => expect(lastRequest('POST', '/incidents/42/escalate')?.body).toEqual({ reason: 'Payroll run for the whole company is blocked' }));
  });

  it('generates the SA AI recommendation, ticks actions and sends the gated runbook for approval', async () => {
    routes(incident, {
      'POST /ai/incidents/42/resolution': json(aiResult(makeResolution())),
      'POST /runbooks/5/executions': json({ id: 77, runbookId: 5, runbookTitle: 'Payroll service recovery', incidentId: 42, status: 'PENDING_APPROVAL', stepStates: [] }),
    });
    renderDetail();
    const user = userEvent.setup();
    const card = await screen.findByRole('region', { name: 'SA AI Recommendation' });
    await user.click(within(card).getByRole('button', { name: /Generate recommendation/ }));

    expect(await within(card).findByText('87% Match')).toBeInTheDocument();
    expect(within(card).getByText('Database connection pool exhaustion')).toBeInTheDocument();
    expect(within(card).getByText('Deterministic fallback — not AI-generated')).toBeInTheDocument();
    const checklist = within(card).getByRole('list', { name: 'Recommended actions checklist' });
    expect(within(checklist).getAllByRole('checkbox')).toHaveLength(3);
    await user.click(within(checklist).getByRole('checkbox', { name: 'Restart payroll application service' }));
    expect(within(card).getByText(/Recommended actions \(1\/3 done\)/)).toBeInTheDocument();
    expect(within(card).getByText(/requires Incident Manager approval/)).toBeInTheDocument();

    await user.click(within(card).getByRole('button', { name: /Apply Resolution Runbook/ }));
    const dialog = screen.getByRole('dialog', { name: 'Apply “Payroll service recovery”?' });
    await user.click(within(dialog).getByRole('button', { name: 'Request approval' }));
    expect(await within(card).findByText('Sent for approval.')).toBeInTheDocument();
    expect(lastRequest('POST', '/runbooks/5/executions')?.body).toEqual({ incidentId: 42 });
  });

  it('disables Apply Resolution Runbook with an explanation when no runbook matches', async () => {
    routes(incident, { 'POST /ai/incidents/42/resolution': json(aiResult(makeResolution({ runbook: null, confidence: 41 }), { source: 'LLM', provider: 'anthropic', model: 'claude' })) });
    renderDetail();
    const user = userEvent.setup();
    const card = await screen.findByRole('region', { name: 'SA AI Recommendation' });
    await user.click(within(card).getByRole('button', { name: /Generate recommendation/ }));
    expect(await within(card).findByText('41% Match')).toHaveClass('low');
    expect(within(card).getByRole('button', { name: /Apply Resolution Runbook/ })).toBeDisabled();
    expect(within(card).getByText(/No runbook matches this incident/)).toBeInTheDocument();
    expect(within(card).getByText(/LLM · anthropic · claude/)).toBeInTheDocument();
  });

  it('shows the AI rate-limit error with a retry', async () => {
    routes(incident, { 'POST /ai/incidents/42/resolution': json(apiErrorBody(429, 'AI rate limit reached'), 429) });
    renderDetail();
    const user = userEvent.setup();
    const card = await screen.findByRole('region', { name: 'SA AI Recommendation' });
    await user.click(within(card).getByRole('button', { name: /Generate recommendation/ }));
    expect(await within(card).findByText(/AI rate limit reached/)).toBeInTheDocument();
    expect(within(card).getByRole('button', { name: /Try again/ })).toBeInTheDocument();
  });

  it('lists the merged activity with escalation history and filters', async () => {
    routes(makeDetail({ id: 42, escalationLevel: 1, escalations: [{ level: 1, reason: 'Payroll run blocked', actorName: 'Riya Varma', at: '2026-11-03T09:50:00Z' }] }));
    renderDetail();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: 'Activity' }));
    const panel = screen.getByRole('tabpanel', { name: 'Activity' });
    expect(within(panel).getByRole('region', { name: 'Escalation history' })).toHaveTextContent('Level 1');
    const feed = await within(panel).findByRole('list', { name: 'Activity timeline' });
    expect(within(feed).getAllByRole('listitem')).toHaveLength(4);
    expect(within(feed).getByText('Routed to Incident Manager')).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: 'Comments (1)' }));
    expect(within(feed).getAllByRole('listitem')).toHaveLength(1);
    expect(within(feed).getByText('Still failing for my team')).toBeInTheDocument();
  });

  it('links an active incident on the same service from the Related tab', async () => {
    routes(incident, { 'POST /incidents/42/links': json({}) });
    renderDetail('SUPPORT_AGENT', 42, '?tab=related');
    const panel = await screen.findByRole('tabpanel', { name: 'Related' });
    const region = within(panel).getByRole('region', { name: 'Other active incidents on Payroll' });
    expect(within(region).getByText(/INC-7850/)).toBeInTheDocument();
    await userEvent.setup().click(within(region).getByRole('button', { name: /Link/ }));
    await waitFor(() => expect(lastRequest('POST', '/incidents/42/links')?.body).toEqual({ targetId: 50, linkType: 'RELATED_TO' }));
  });

  it('keeps the full AI panel on its tab with the classification priority recommendation', async () => {
    routes(incident, {
      'POST /ai/incidents/42/classify': json(
        aiResult({ category: 'APPLICATION', serviceId: 3, serviceName: 'Payroll', impact: 'HIGH', urgency: 'HIGH', priority: 'HIGH', confidence: 0.9, rationale: 'Payroll keywords' }),
      ),
    });
    renderDetail('SUPPORT_AGENT', 42, '?tab=ai');
    const user = userEvent.setup();
    const panel = await screen.findByRole('tabpanel', { name: 'AI Recommendation' });
    const tools = within(panel).getByRole('group', { name: 'SA AI tools' });
    expect(within(tools).getAllByRole('button').map((button) => button.textContent?.trim())).toEqual([
      'Summarize',
      'Classify & prioritise',
      'Root cause',
      'Recommended knowledge',
      'Duplicate suggestions',
    ]);
    await user.click(within(tools).getByRole('button', { name: /Classify/ }));
    const result = await within(panel).findByRole('article', { name: 'Classification & priority recommendation' });
    expect(within(result).getByText('Recommended priority')).toBeInTheDocument();
    expect(within(result).getByText('current')).toBeInTheDocument();
    expect(within(result).getByText('Deterministic fallback — not AI-generated')).toBeInTheDocument();
  });

  it('offers the More menu with assignment, major and edit actions for managers', async () => {
    routes(incident);
    renderDetail('IT_MANAGER');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'More' }));
    const menu = screen.getByRole('menu', { name: 'More incident actions' });
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      'Assign…',
      'Auto-route to least-loaded agent',
      'Edit details…',
      'Declare major incident…',
      'Link to parent…',
      'Add related incident…',
    ]);
    expect(within(menu).getAllByRole('menuitem')[0]).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'More' }));
    await user.click(screen.getByRole('menuitem', { name: 'Declare major incident…' }));
    expect(screen.getByRole('dialog', { name: 'Declare INC-7846 a major incident?' })).toBeInTheDocument();
  });

  it('validates the resolution dialog (code, notes ≥ 10 characters)', async () => {
    routes(incident, { 'POST /incidents/42/transition': json({ ...incident, status: 'RESOLVED' }) });
    renderDetail();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Resolve' }));
    const dialog = screen.getByRole('dialog', { name: 'Resolve INC-7846' });
    await user.click(within(dialog).getByRole('button', { name: 'Confirm resolution' }));
    expect(await within(dialog).findByText('Choose a resolution code', { selector: '.field-error' })).toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText('Resolution code'), 'FIXED');
    await user.type(within(dialog).getByLabelText('Resolution notes'), 'Restarted');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm resolution' }));
    expect(await within(dialog).findByText(/at least 10 characters/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Resolution notes'), ' the payroll nodes');
    await user.type(within(dialog).getByLabelText('Root cause'), 'Connection pool exhausted');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm resolution' }));
    await waitFor(() =>
      expect(lastRequest('POST', '/incidents/42/transition')?.body).toEqual({
        status: 'RESOLVED',
        resolutionCode: 'FIXED',
        resolutionNotes: 'Restarted the payroll nodes',
        rootCause: 'Connection pool exhausted',
        resolutionArticleId: null,
      }),
    );
  });

  it('lets a requester cancel their own new incident without staff controls', async () => {
    const own = makeDetail({ id: 42, status: 'NEW', assigneeId: null, assigneeName: null, allowedTransitions: ['CLOSED'] });
    routes(own, { 'POST /incidents/42/transition': json({ ...own, status: 'CLOSED' }) });
    renderDetail('EMPLOYEE');
    const user = userEvent.setup();
    const toolbar = await screen.findByRole('toolbar', { name: 'Incident actions' });
    expect(within(toolbar).getAllByRole('button').map((button) => button.textContent?.trim())).toEqual(['Cancel Incident']);
    expect(screen.queryByRole('region', { name: 'SA AI Recommendation' })).not.toBeInTheDocument();
    expect(screen.getByText(/Unassigned · Payroll Team queue/)).toBeInTheDocument();

    await user.click(within(toolbar).getByRole('button', { name: 'Cancel Incident' }));
    const dialog = screen.getByRole('dialog', { name: 'Cancel INC-7846?' });
    await user.type(within(dialog).getByLabelText('Reason'), 'Fixed itself after a restart');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm cancellation' }));
    await waitFor(() =>
      expect(lastRequest('POST', '/incidents/42/transition')?.body).toMatchObject({ status: 'CLOSED', resolutionCode: 'CANCELLED', comment: 'Fixed itself after a restart' }),
    );
  });

  it('shows a not-found state for a missing incident', async () => {
    mockApi({ 'GET /incidents/99': json(apiErrorBody(404, 'Incident 99 not found'), 404) });
    renderDetail('SUPPORT_AGENT', 99);
    expect(await screen.findByRole('heading', { level: 1, name: 'Incident not found' })).toBeInTheDocument();
    expect(screen.getByText('This incident does not exist or you cannot access it')).toBeInTheDocument();
  });
});
