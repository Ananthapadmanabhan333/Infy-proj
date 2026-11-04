import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { aiResult } from '../incidents/fixtures.test-utils';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeProblemDetail, makeProblemSummary, pageOf } from '../../test/itsmFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import ProblemDetailPage from './ProblemDetailPage';
import ProblemsPage from './ProblemsPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const ANALYTICS = {
  from: '2026-08-06',
  to: '2026-11-04',
  total: 4,
  open: 3,
  byStatus: { NEW: 0, INVESTIGATING: 1, ROOT_CAUSE_IDENTIFIED: 1, KNOWN_ERROR: 1, RESOLVED: 0, CLOSED: 1 },
  byPriority: {},
  knownErrors: 1,
  createdInPeriod: 4,
  resolvedInPeriod: 1,
  avgHoursToRootCause: 30,
  avgHoursToResolve: null,
  linkedIncidents: 9,
  recurrencesAfterKnownError: 0,
  overdueActions: 1,
  recurringClusters: 1,
  topServices: [],
};
const CLUSTER = {
  key: 'svc-4',
  serviceId: 4,
  serviceName: 'VPN',
  category: 'NETWORK',
  size: 3,
  cohesion: 0.61,
  firstSeen: '2026-10-25T08:00:00Z',
  lastSeen: '2026-11-02T08:00:00Z',
  suggestedTitle: 'Recurring: VPN drops',
  incidents: [
    { id: 90, number: 'INC-7090', title: 'VPN drops', status: 'NEW', createdAt: '2026-11-02T08:00:00Z', similarity: 0.7 },
    { id: 91, number: 'INC-7091', title: 'VPN disconnects', status: 'NEW', createdAt: '2026-11-01T08:00:00Z', similarity: 0.6 },
  ],
};

function listRoutes() {
  mockApi({
    'GET /problems': json(pageOf([makeProblemSummary()])),
    'GET /problems/analytics': json(ANALYTICS),
    'GET /problems/recurring': json([CLUSTER]),
    'POST /problems/from-cluster': json(makeProblemDetail({ id: 9, number: 'PRB-0009' }), 201),
    'POST /problems': (config) => json(makeProblemDetail({ id: 10, number: 'PRB-0010', ...JSON.parse(String(config.data)) }), 201),
    'GET /users/assignable': json([{ id: 7, fullName: 'Ada Lovelace', email: 'ada@x.test', role: 'SUPPORT_AGENT', departmentId: 2, departmentName: 'IT', openAssignments: 1 }]),
    'GET /teams': json([]),
    'GET /services': json([]),
  });
}

function detailRoutes(problem = makeProblemDetail()) {
  mockApi({
    'GET /problems/5': json(problem),
    'GET /problems/5/history': json([{ at: '2026-11-03T08:00:00Z', actorName: 'Ada Lovelace', action: 'STATUS_CHANGED', fromValue: 'NEW', toValue: 'INVESTIGATING', note: null }]),
    'POST /problems/5/transition': (config) => json({ ...problem, ...JSON.parse(String(config.data)) }),
    'POST /problems/5/incidents': json(problem),
    'DELETE /problems/5/incidents/71': json({ ...problem, incidents: [] }),
    'POST /problems/5/actions': json(problem.actions[0], 201),
    'PUT /problems/5/actions/1': json({ ...problem.actions[0], status: 'DONE' }),
    'POST /ai/problems/5/root-cause': json(
      aiResult({ hypotheses: [{ cause: 'DB pool exhausted by the batch', likelihood: 'HIGH', evidence: ['4 incidents at 02:00'], suggestedChecks: ['Check pool metrics'] }], commonFactors: ['02:00 batch'], suggestedWorkaround: 'Restart the API', incidentsAnalysed: 4, basis: 'Linked incidents' }),
    ),
    'GET /users/assignable': json([]),
  });
}

function renderDetail(role: Parameters<typeof makeUser>[0]) {
  return renderWithProviders(
    <Routes>
      <Route path="/problems/:id" element={<ProblemDetailPage />} />
    </Routes>,
    { route: '/problems/5', auth: makeAuth(makeUser(role)) },
  );
}

describe('ProblemsPage', () => {
  it('lists open problems with analytics and creates a problem from a recurring cluster', async () => {
    listRoutes();
    const user = userEvent.setup();
    renderWithProviders(<ProblemsPage />, { route: '/problems', auth: makeAuth(makeUser('SUPPORT_AGENT')) });

    const table = await screen.findByRole('table', { name: 'Problems' });
    expect(within(table).getByText('PRB-0005')).toBeInTheDocument();
    expect(within(table).getAllByText('Known error', { selector: '.badge' })).toHaveLength(2);
    expect(last('GET', '/problems')?.params).toMatchObject({ status: 'NEW,INVESTIGATING,ROOT_CAUSE_IDENTIFIED,KNOWN_ERROR' });
    expect(screen.getByRole('tab', { name: /Open \(3\)/ })).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'Create problem from Recurring: VPN drops' }));
    await waitFor(() => expect(last('POST', '/problems/from-cluster')?.body).toEqual({ incidentIds: [90, 91], title: 'Recurring: VPN drops' }));
  });

  it('creates a problem with the editor', async () => {
    listRoutes();
    const user = userEvent.setup();
    renderWithProviders(<ProblemsPage />, { route: '/problems', auth: makeAuth(makeUser('INCIDENT_MANAGER')) });
    await user.click(await screen.findByRole('button', { name: /New problem/ }));
    const dialog = await screen.findByRole('dialog', { name: 'New problem' });
    await user.type(within(dialog).getByLabelText(/Title/), 'Mail delays every Monday');
    await user.type(within(dialog).getByLabelText(/Other affected items/), 'mx-01, mx-02');
    await user.click(within(dialog).getByRole('button', { name: 'Create problem' }));
    await waitFor(() => expect(last('POST', '/problems')?.body).toMatchObject({ title: 'Mail delays every Monday', priority: 'MEDIUM', affectedCis: ['mx-01', 'mx-02'], ciIds: [], incidentIds: [] }));
  });

  it('links CMDB configuration items with the CI picker (ciIds) and keeps legacy names as text', async () => {
    mockApi({
      'GET /problems': json(pageOf([])),
      'GET /problems/analytics': json(ANALYTICS),
      'GET /problems/recurring': json([]),
      'POST /problems': (config) => json(makeProblemDetail({ id: 10, number: 'PRB-0010', ...JSON.parse(String(config.data)) }), 201),
      'GET /users/assignable': json([]),
      'GET /teams': json([]),
      'GET /services': json([]),
      'GET /cmdb/cis': json(pageOf([{ id: 31, name: 'payroll-db-primary', ciType: 'DATABASE', status: 'ACTIVE', environment: 'PRODUCTION', criticality: 'CRITICAL', description: null, ownerId: null, ownerName: null, teamId: null, teamName: null, serviceId: null, serviceCode: null, relationshipCount: 3, openAlerts: 0, demo: true, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }])),
    });
    const user = userEvent.setup();
    renderWithProviders(<ProblemsPage />, { route: '/problems', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    await user.click(await screen.findByRole('button', { name: /New problem/ }));
    const dialog = await screen.findByRole('dialog', { name: 'New problem' });
    await user.type(within(dialog).getByLabelText(/Title/), 'Payroll DB pool exhaustion');
    await user.type(within(dialog).getByLabelText('Configuration items'), 'payroll');
    await user.click(await within(dialog).findByRole('option', { name: /payroll-db-primary/ }));
    expect(within(dialog).getByRole('button', { name: 'Remove payroll-db-primary' })).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Other affected items/), 'vendor gateway');
    await user.click(within(dialog).getByRole('button', { name: 'Create problem' }));
    await waitFor(() => expect(last('POST', '/problems')?.body).toMatchObject({ ciIds: [31], affectedCis: ['vendor gateway'] }));
  });

  it('is read-only for auditors', async () => {
    listRoutes();
    renderWithProviders(<ProblemsPage />, { route: '/problems', auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByRole('table', { name: 'Problems' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New problem/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create problem from/ })).not.toBeInTheDocument();
  });
});

describe('ProblemDetailPage', () => {
  it('records the root cause through the lifecycle and hides closing from agents', async () => {
    detailRoutes();
    const user = userEvent.setup();
    renderDetail('SUPPORT_AGENT');
    expect(await screen.findByRole('heading', { level: 1, name: 'Payroll 503s during the batch' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel problem' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Root cause identified' }));
    const dialog = await screen.findByRole('dialog', { name: /Root cause identified — PRB-0005/ });
    const confirm = within(dialog).getByRole('button', { name: 'Root cause identified' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/Root cause/), 'Connection pool too small');
    await user.click(confirm);
    await waitFor(() => expect(last('POST', '/problems/5/transition')?.body).toEqual({ status: 'ROOT_CAUSE_IDENTIFIED', note: null, rootCause: 'Connection pool too small' }));
  });

  it('records a known error with a workaround and the KB article', async () => {
    detailRoutes(makeProblemDetail({ status: 'ROOT_CAUSE_IDENTIFIED', rootCause: 'Pool too small', allowedTransitions: ['KNOWN_ERROR', 'RESOLVED', 'INVESTIGATING'] }));
    const user = userEvent.setup();
    renderDetail('IT_MANAGER');
    await user.click(await screen.findByRole('button', { name: 'Record known error' }));
    const dialog = await screen.findByRole('dialog', { name: /Record known error/ });
    await user.type(within(dialog).getByLabelText(/Workaround/), 'Restart the API after the batch');
    await user.click(within(dialog).getByLabelText(/knowledge-base KNOWN_ERROR article/));
    await user.click(within(dialog).getByRole('button', { name: 'Record known error' }));
    await waitFor(() =>
      expect(last('POST', '/problems/5/transition')?.body).toMatchObject({ status: 'KNOWN_ERROR', rootCause: 'Pool too small', workaround: 'Restart the API after the batch', publishKnownError: true }),
    );
  });

  it('shows linked CIs as CMDB links next to legacy free-text names', async () => {
    detailRoutes(makeProblemDetail({ affectedCis: ['payroll-db-primary', 'vendor gateway'], cis: [{ id: 31, name: 'payroll-db-primary', ciType: 'DATABASE', status: 'ACTIVE', environment: 'PRODUCTION' }] }));
    renderDetail('AUDITOR');
    expect(await screen.findByRole('link', { name: 'payroll-db-primary' })).toHaveAttribute('href', '/cmdb/31');
    expect(screen.getByText('vendor gateway')).toBeInTheDocument();
  });

  it('unlinks incidents, updates corrective actions and shows the AI suggestion with its source', async () => {
    detailRoutes();
    const user = userEvent.setup();
    renderDetail('IT_MANAGER');
    const incidents = await screen.findByRole('list', { name: 'Linked incidents' });
    await user.click(within(incidents).getByRole('button', { name: 'Unlink INC-7071' }));
    await waitFor(() => expect(last('DELETE', '/problems/5/incidents/71')).toBeTruthy());

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status of Raise DB pool size' }), 'DONE');
    await waitFor(() => expect(last('PUT', '/problems/5/actions/1')?.body).toMatchObject({ title: 'Raise DB pool size', status: 'DONE' }));

    await user.click(screen.getByRole('button', { name: /Suggest root cause/ }));
    const card = await screen.findByRole('article', { name: 'Root-cause hypotheses' });
    expect(within(card).getByText('DB pool exhausted by the batch')).toBeInTheDocument();
    expect(within(card).getByText(/Deterministic fallback — not AI-generated/)).toBeInTheDocument();
  });
});
