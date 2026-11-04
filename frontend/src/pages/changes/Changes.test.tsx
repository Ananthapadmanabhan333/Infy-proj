import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { aiResult } from '../incidents/fixtures.test-utils';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeChangeDetail, makeChangeSummary, makeFlowApproval, pageOf } from '../../test/itsmFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { RiskQuestion } from '../../lib/itsmTypes';
import ChangeDetailPage from './ChangeDetailPage';
import ChangesPage from './ChangesPage';
import { liveRisk, riskLevel } from './changeRisk';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const QUESTIONS: RiskQuestion[] = [
  { key: 'userImpact', text: 'Will users notice the change while it is implemented?', options: [{ value: 'NONE', label: 'No', points: 0 }, { value: 'SOME', label: 'Some users', points: 2 }, { value: 'ALL', label: 'All users of the service', points: 4 }] },
  { key: 'tested', text: 'Was the change tested in a non-production environment?', options: [{ value: 'YES', label: 'Yes, fully', points: 0 }, { value: 'PARTIAL', label: 'Partly', points: 2 }, { value: 'NO', label: 'No', points: 4 }] },
];
const SERVICES = [{ id: 3, code: 'PAYROLL', name: 'Payroll', description: null, departmentId: 2, departmentName: 'IT', ownerId: null, ownerName: null, tier: 1, status: 'OPERATIONAL', statusReason: null }];
const METRICS = {
  from: '2026-08-06', to: '2026-11-04', created: 8, byType: {}, byStatus: {}, byRisk: {}, completed: 3, failed: 1, rolledBack: 1, successRate: 60, failureRate: 20, rollbackRate: 20,
  emergencyRate: 12.5, avgApprovalHours: 20, avgLeadTimeHours: 50, onTimeRate: 100, changesFollowedByIncidents: 1, reviewsOutstanding: 1,
};

function routes(extra: Record<string, unknown> = {}) {
  mockApi({
    'GET /changes': json(pageOf([makeChangeSummary()])),
    'GET /changes/metrics': json(METRICS),
    'GET /changes/risk-questions': json(QUESTIONS),
    'GET /changes/templates': json([]),
    'GET /changes/freeze-windows': json([{ id: 1, name: 'Month-end payroll close', reason: null, startsAt: '2026-11-28T00:00:00Z', endsAt: '2026-12-02T00:00:00Z', serviceIds: [3], active: true, demo: true }]),
    'GET /changes/calendar': json({
      from: '2026-10-26T00:00:00Z',
      to: '2026-12-07T00:00:00Z',
      entries: [{ id: 12, number: 'CHG-1012', title: 'Payroll pool fix', type: 'NORMAL', status: 'AWAITING_APPROVAL', risk: 'HIGH', start: new Date().toISOString(), end: new Date(Date.now() + 3_600_000).toISOString(), actualStart: null, actualEnd: null, services: [{ id: 3, code: 'PAYROLL', name: 'Payroll' }], conflictsWith: ['CHG-1013'], inFreeze: false }],
      freezeWindows: [],
      conflicts: [{ changeA: 'CHG-1012', changeB: 'CHG-1013', services: ['Payroll'], cis: [], overlapStart: '2026-11-08T20:00:00Z', overlapEnd: '2026-11-08T21:00:00Z' }],
    }),
    'GET /services': json(SERVICES),
    'GET /users/assignable': json([]),
    'GET /teams': json([]),
    'POST /changes': (config: { data?: unknown }) => json(makeChangeDetail({ id: 20, number: 'CHG-1020', status: 'DRAFT', ...JSON.parse(String(config.data)) }), 201),
    'GET /changes/12': json(makeChangeDetail()),
    'GET /changes/12/history': json([]),
    'GET /changes/12/incidents': json({ windowStart: null, windowEnd: null, hours: 24, linked: [], correlated: [] }),
    'POST /changes/12/transition': (config: { data?: unknown }) => json(makeChangeDetail({ status: 'AWAITING_APPROVAL', approval: makeFlowApproval(), ...JSON.parse(String(config.data)) })),
    'POST /ai/changes/12/risk': json(aiResult({ riskLevel: 'HIGH', score: 11, factors: [], recommendations: ['Schedule outside business hours'], similarChanges: [], basis: 'Risk model + history' })),
    ...extra,
  } as Parameters<typeof mockApi>[0]);
}

describe('change risk model (client mirror)', () => {
  it('scores answers and computed factors like the server', () => {
    expect(riskLevel(4)).toBe('LOW');
    expect(riskLevel(5)).toBe('MEDIUM');
    expect(riskLevel(10)).toBe('HIGH');
    const result = liveRisk({ type: 'EMERGENCY', answers: { userImpact: 'ALL', tested: 'NO' }, questions: QUESTIONS, serviceTiers: [1], plannedStart: null });
    expect(result.score).toBe(4 + 4 + 3 + 2);
    expect(result.risk).toBe('HIGH');
    // Unanswered questions count as the middle option.
    expect(liveRisk({ type: 'NORMAL', answers: {}, questions: QUESTIONS, serviceTiers: [], plannedStart: null }).score).toBe(4);
    expect(liveRisk({ type: 'STANDARD', answers: { userImpact: 'ALL' }, questions: QUESTIONS, serviceTiers: [1], plannedStart: null }).risk).toBe('LOW');
  });
});

describe('ChangesPage', () => {
  it('lists open changes with type, status and risk filters', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes', auth: makeAuth(makeUser('AUDITOR')) });
    const table = await screen.findByRole('table', { name: 'Changes' });
    expect(within(table).getByText('CHG-1012')).toBeInTheDocument();
    expect(within(table).getByText('High · 11')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New change/ })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Risk' }), 'HIGH');
    await waitFor(() => expect(last('GET', '/changes')?.params).toMatchObject({ risk: 'HIGH', status: 'DRAFT,SUBMITTED,ASSESSMENT,AWAITING_APPROVAL,APPROVED,SCHEDULED,IMPLEMENTING' }));
  });

  it('creates a normal change through the wizard with a live risk score', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<ChangesPage />, { route: '/changes', auth: makeAuth(makeUser('CHANGE_MANAGER')) });
    await user.click(await screen.findByRole('button', { name: /New change/ }));
    const wizard = await screen.findByRole('dialog', { name: 'New change request' });
    expect(within(wizard).getByRole('radio', { name: /Normal/ })).toHaveAttribute('aria-checked', 'true');
    await user.click(within(wizard).getByRole('button', { name: /Next/ }));

    await user.type(within(wizard).getByLabelText(/^Title/), 'Raise payroll pool');
    await user.selectOptions(await within(wizard).findByLabelText('Affected services'), '3');
    await user.click(within(wizard).getByRole('button', { name: /Next/ }));

    await user.selectOptions(await within(wizard).findByLabelText(/Will users notice/), 'ALL');
    await user.selectOptions(within(wizard).getByLabelText(/Was the change tested/), 'NO');
    const computed = within(wizard).getByRole('complementary', { name: 'Computed risk' });
    expect(within(computed).getByText('High · score 11')).toBeInTheDocument();
    expect(within(computed).getByText(/rules, not AI/)).toBeInTheDocument();
    await user.click(within(wizard).getByRole('button', { name: /Next/ }));

    await user.type(within(wizard).getByLabelText(/Implementation plan/), 'Bump pool to 80');
    await user.type(within(wizard).getByLabelText(/Rollback plan/), 'Revert');
    await user.click(within(wizard).getByRole('button', { name: /Next/ }));
    await user.click(within(wizard).getByRole('button', { name: 'Save as draft' }));

    await waitFor(() =>
      expect(last('POST', '/changes')?.body).toMatchObject({
        title: 'Raise payroll pool',
        type: 'NORMAL',
        serviceIds: [3],
        riskAnswers: { userImpact: 'ALL', tested: 'NO' },
        implementationPlan: 'Bump pool to 80',
        rollbackPlan: 'Revert',
      }),
    );
  });

  it('shows the month calendar with conflicts', async () => {
    routes();
    renderWithProviders(<ChangesPage view="calendar" />, { route: '/changes/calendar', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    expect((await screen.findAllByRole('link', { name: /CHG-1012/ })).length).toBeGreaterThan(0);
    expect(screen.getByText('CHG-1012 ↔ CHG-1013')).toBeInTheDocument();
    expect(last('GET', '/changes/calendar')?.params).toHaveProperty('from');
  });
});

describe('ChangeDetailPage', () => {
  function renderDetail(role: Parameters<typeof makeUser>[0], id = 7) {
    return renderWithProviders(
      <Routes>
        <Route path="/changes/:id" element={<ChangeDetailPage />} />
      </Routes>,
      { route: '/changes/12', auth: makeAuth(makeUser(role, { id })) },
    );
  }

  it('offers the API-allowed state actions to editors and requests CAB approval', async () => {
    routes();
    const user = userEvent.setup();
    renderDetail('CHANGE_MANAGER');
    await user.click(await screen.findByRole('button', { name: 'Request CAB approval' }));
    const dialog = await screen.findByRole('dialog', { name: /Request CAB approval — CHG-1012/ });
    await user.click(within(dialog).getByRole('button', { name: 'Request CAB approval' }));
    await waitFor(() => expect(last('POST', '/changes/12/transition')?.body).toMatchObject({ status: 'AWAITING_APPROVAL' }));
  });

  it('hides management actions from staff who are not editors or the implementer', async () => {
    routes();
    renderDetail('SUPPORT_AGENT', 99);
    expect(await screen.findByRole('heading', { level: 1, name: 'Payroll pool fix' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request CAB approval' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel change' })).not.toBeInTheDocument();
  });

  it('labels the SA AI risk analysis with its source and requires a reason to cancel', async () => {
    routes();
    const user = userEvent.setup();
    renderDetail('IT_MANAGER');
    await user.click(await screen.findByRole('button', { name: /SA AI risk analysis/ }));
    const card = await screen.findByRole('article', { name: 'Change-risk analysis' });
    expect(within(card).getByText('Schedule outside business hours')).toBeInTheDocument();
    expect(within(card).getByText(/Deterministic fallback — not AI-generated/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Cancel change' }));
    const dialog = await screen.findByRole('dialog', { name: /Cancel change — CHG-1012/ });
    const confirm = within(dialog).getByRole('button', { name: 'Cancel change' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/Reason for cancelling/), 'Superseded');
    await user.click(confirm);
    await waitFor(() => expect(last('POST', '/changes/12/transition')?.body).toMatchObject({ status: 'CANCELLED', reason: 'Superseded' }));
  });
});
