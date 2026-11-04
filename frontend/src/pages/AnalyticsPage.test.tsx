import { cloneElement, type ReactElement } from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLocation } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { suggestedQuestions, validateRange } from '../components/analytics/CustomTab';
import { buildServiceVolume } from '../components/analytics/ServiceVolumeChart';
import { sortRows } from '../components/analytics/sortRows';
import { json, mockApi, requests, type MockReply } from '../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../test/utils';
import type { AnalyticsOverview, DepartmentDto, ForecastDto, Role } from '../lib/types';
import AnalyticsPage from './AnalyticsPage';

vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('recharts')>();
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: ReactElement }) => <div style={{ width: 640, height: 280 }}>{cloneElement(children, { width: 640, height: 280 })}</div>,
  };
});

beforeAll(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});

const overview: AnalyticsOverview = {
  days: 30,
  kpis: {
    totalIncidents: 5000,
    openIncidents: 246,
    criticalOpen: 27,
    createdInPeriod: 1248,
    resolvedInPeriod: 1160,
    slaCompliance: 96.4,
    mttrMinutes: 28,
    mttaMinutes: 5,
    resolutionRate: 93,
    firstCallResolution: 88,
    deltas: { created: 12, criticalOpen: 2, slaCompliance: 1.2, mttr: -18, resolutionRate: 4, firstCallResolution: 3 },
  },
  trend: [
    { date: '2026-10-30', created: 40, resolved: 38 },
    { date: '2026-10-31', created: 42, resolved: 40 },
  ],
  byService: [
    { name: 'Payroll', created: 120, resolved: 110, open: 10, mttrMinutes: 35 },
    { name: 'HR Portal', created: 90, resolved: 88, open: 2, mttrMinutes: 22 },
    { name: 'Network', created: 150, resolved: 120, open: 30, mttrMinutes: null },
  ],
  byCategory: [
    { category: 'NETWORK', count: 150 },
    { category: 'APPLICATION', count: 210 },
  ],
  byDepartment: [
    { name: 'IT Service Desk', open: 40, resolved: 400, mttrMinutes: 30, slaCompliance: 97 },
    { name: 'Network Operations', open: 30, resolved: 120, mttrMinutes: 60, slaCompliance: 81 },
  ],
  byPriority: [
    { priority: 'CRITICAL', count: 27, mttrMinutes: 45, slaCompliance: 90 },
    { priority: 'HIGH', count: 186, mttrMinutes: 60, slaCompliance: 94 },
    { priority: 'MEDIUM', count: 642, mttrMinutes: 120, slaCompliance: 97 },
    { priority: 'LOW', count: 393, mttrMinutes: 240, slaCompliance: 99 },
  ],
  backlogAging: [
    { bucket: '< 1 day', count: 80 },
    { bucket: '1–3 days', count: 50 },
    { bucket: '3–7 days', count: 20 },
    { bucket: '> 7 days', count: 5 },
  ],
  topRecurring: [{ pattern: 'Payroll · Application', count: 14, serviceName: 'Payroll' }],
  agentPerformance: [
    { userId: 11, name: 'Riya Varma', departmentName: 'Payroll Team', resolved: 64, avgResolutionMinutes: 31, open: 4 },
    { userId: 12, name: 'Arjun Kumar', departmentName: 'Network Operations', resolved: 80, avgResolutionMinutes: 52, open: 7 },
  ],
  serviceVolume: [
    { date: '2026-10-30', counts: { Payroll: 4, 'HR Portal': 3, Network: 6 } },
    { date: '2026-10-31', counts: { Payroll: 5, 'HR Portal': 2, Network: 7 } },
  ],
  serviceNames: ['Payroll', 'HR Portal', 'Network'],
};

const forecast: ForecastDto = {
  history: [{ date: '2026-10-31', count: 42 }],
  forecast: Array.from({ length: 14 }, (_, index) => ({ date: `2026-11-${String(index + 3).padStart(2, '0')}`, predicted: 10, lower: 8, upper: 12 })),
  method: 'Statistical forecast: least-squares linear trend on daily incident counts (95% band)',
  slope: 0.25,
  rSquared: 0.62,
  byService: [{ serviceName: 'Payroll', last7Days: 20, next7Days: 25 }],
};

const departments: DepartmentDto[] = [
  { id: 2, code: 'IT-SD', name: 'IT Service Desk', description: null, managerId: null, managerName: null, email: null, memberCount: 8, demo: false },
  { id: 3, code: 'NET', name: 'Network Operations', description: null, managerId: null, managerName: null, email: null, memberCount: 5, demo: false },
];

function LocationProbe() {
  return <output data-testid="location">{useLocation().search}</output>;
}

function setup(role: Role = 'IT_MANAGER', route = '/analytics', extra: Record<string, MockReply | ((config: never) => MockReply)> = {}) {
  mockApi({
    'GET /analytics/overview': json(overview),
    'GET /analytics/forecast': json(forecast),
    'GET /departments': json(departments),
    ...extra,
  });
  const user = userEvent.setup();
  const view = renderWithProviders(
    <>
      <AnalyticsPage />
      <LocationProbe />
    </>,
    { route, auth: makeAuth(makeUser(role)) },
  );
  return { user, ...view };
}

const paramsOf = (url: string) => requests.filter((request) => request.url === url).map((request) => request.params as Record<string, unknown>);
const lastParams = (url: string) => paramsOf(url).at(-1) ?? {};

describe('<AnalyticsPage />', () => {
  beforeEach(() => {
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    URL.createObjectURL = vi.fn(() => 'blob:report');
    URL.revokeObjectURL = vi.fn();
  });

  it('shows the Operations KPIs with deltas, the stacked service volume and the forecast outlook', async () => {
    setup();
    expect(screen.getByRole('heading', { level: 1, name: 'Analytics & Reports' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('1,248')).toBeInTheDocument();
    expect(screen.getByText('93%')).toBeInTheDocument();
    expect(screen.getByText('88%')).toBeInTheDocument();
    const deltas = screen.getAllByTestId('kpi-delta');
    expect(deltas.map((delta) => delta.textContent)).toEqual(['up 12%', 'up 4pp', 'down 18%', 'up 3pp']);
    expect(deltas.map((delta) => delta.className.split(' ').pop())).toEqual(['bad', 'good', 'good', 'good']);

    const volume = screen.getByRole('region', { name: 'Incident Volume by Service' });
    expect(within(volume).getByRole('list', { name: 'Services' })).toHaveTextContent('PayrollHR PortalNetwork');
    expect(within(volume).getByRole('img', { name: /Payroll, HR Portal, Network/ })).toBeInTheDocument();

    const outlook = screen.getByRole('region', { name: 'Forecast Outlook' });
    expect(await within(outlook).findByText('140')).toBeInTheDocument(); // 14 × 10
    expect(within(outlook).getByText('+25%')).toBeInTheDocument();
    expect(lastParams('/analytics/overview')).toMatchObject({ days: 30 });
    expect(lastParams('/analytics/forecast')).toMatchObject({ days: 30, horizon: 14 });
  });

  it('keeps date range and department in the URL and applies them to every request and tab', async () => {
    const { user } = setup();
    await screen.findByText('1,248');
    await user.selectOptions(screen.getByLabelText('Date range'), '7');
    await waitFor(() => expect(lastParams('/analytics/overview')).toMatchObject({ days: 7 }));
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Department' }), '3');
    await waitFor(() => expect(lastParams('/analytics/overview')).toMatchObject({ days: 7, departmentId: 3 }));
    expect(screen.getByTestId('location')).toHaveTextContent('days=7');
    expect(screen.getByTestId('location')).toHaveTextContent('department=3');

    await user.click(screen.getByRole('tab', { name: 'Users' }));
    expect(screen.getByTestId('location')).toHaveTextContent('tab=users');
    expect(screen.getByTestId('location')).toHaveTextContent('department=3');
    const agents = screen.getByRole('region', { name: 'Agent Performance' });
    const rows = within(agents).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Arjun Kumar'); // most resolved first
    expect(screen.getByRole('region', { name: 'Department Performance' })).toHaveTextContent('Network Operations');
  });

  it('reads a custom range from the URL and sends from/to instead of days', async () => {
    setup('AUDITOR', '/analytics?tab=services&from=2026-10-01&to=2026-10-31&department=2');
    const table = await screen.findByRole('region', { name: 'Service Performance' });
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Network'); // most created first
    const params = lastParams('/analytics/overview');
    expect(params).toMatchObject({ from: '2026-10-01', to: '2026-10-31', departmentId: 2 });
    expect(params.days).toBeUndefined();
    expect(screen.getByLabelText('Date range')).toHaveDisplayValue('01 Oct 2026 – 31 Oct 2026');
  });

  it('shows the incidents breakdowns', async () => {
    setup('IT_MANAGER', '/analytics?tab=incidents');
    const priority = await screen.findByRole('region', { name: 'By Priority' });
    // Donut legend + MTTR/SLA table.
    expect(within(priority).getAllByText('642')).toHaveLength(2);
    expect(within(priority).getByText('1,248')).toBeInTheDocument();
    expect(within(priority).getByRole('table', { name: 'MTTR and SLA compliance by priority' })).toHaveTextContent('Critical2745 min90%');
    expect(screen.getByRole('region', { name: 'By Category' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Backlog Aging' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Top Recurring Issues' })).toHaveTextContent('Payroll · Application');
  });

  it('exports CSV or PDF with the active filters from the gold Export menu', async () => {
    const { user } = setup('IT_MANAGER', '/analytics?days=90', { 'GET /analytics/export': { status: 200, data: new Blob(['pdf']) } });
    await screen.findByText('1,248');
    await user.click(screen.getByRole('button', { name: /Export/ }));
    const menu = screen.getByRole('menu', { name: 'Export format' });
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      'CSVIncident records for spreadsheets',
      'PDF reportKPIs, priorities, services, departments and agents',
    ]);
    await user.click(within(menu).getByRole('menuitem', { name: /PDF report/ }));
    await waitFor(() => expect(paramsOf('/analytics/export')).toHaveLength(1));
    expect(lastParams('/analytics/export')).toMatchObject({ format: 'pdf', days: 90 });
    expect(await screen.findByText(/PDF report downloaded/)).toBeInTheDocument();
    expect(URL.createObjectURL).toHaveBeenCalled();
  });

  it('hides Export from roles that cannot export', async () => {
    setup('SUPPORT_AGENT');
    await screen.findByText('1,248');
    expect(screen.queryByRole('button', { name: /Export/ })).not.toBeInTheDocument();
  });

  it('applies a custom range from the Custom tab and validates it', async () => {
    const { user } = setup('IT_MANAGER', '/analytics?tab=custom');
    const form = await screen.findByRole('form', { name: 'Custom report range' });
    await user.type(within(form).getByLabelText(/From/), '2026-10-10');
    await user.type(within(form).getByLabelText(/^To/), '2026-10-01');
    await user.click(within(form).getByRole('button', { name: 'Apply to all tabs' }));
    expect(await within(form).findByText('The end date must be on or after the start date')).toBeInTheDocument();

    await user.clear(within(form).getByLabelText(/^To/));
    await user.type(within(form).getByLabelText(/^To/), '2026-10-20');
    await user.selectOptions(within(form).getByLabelText('Department'), '2');
    await user.click(within(form).getByRole('button', { name: 'Apply to all tabs' }));
    await waitFor(() => expect(lastParams('/analytics/overview')).toMatchObject({ from: '2026-10-10', to: '2026-10-20', departmentId: 2 }));
    expect(screen.getByTestId('location')).toHaveTextContent('from=2026-10-10&to=2026-10-20');
    expect(screen.getByRole('region', { name: 'Custom Report Range' })).toHaveTextContent('10 Oct 2026 – 20 Oct 2026 · IT Service Desk');
  });

  it('answers natural-language questions with the source label, data and basis', async () => {
    const answer = {
      source: 'LLM',
      provider: 'anthropic',
      model: 'claude',
      generatedAt: '2026-11-03T09:00:00Z',
      latencyMs: 900,
      notice: 'Generated by a language model from SA data.',
      fallbackReason: null,
      data: { answer: 'Network had the most incidents in the last 30 days.', data: [{ label: 'Network', value: 150 }], basis: 'Incidents created in the last 30 days' },
    };
    const { user } = setup('IT_MANAGER', '/analytics?tab=custom', { 'POST /ai/ask': json(answer) });
    const box = await screen.findByLabelText('Question for SA AI');
    await user.type(box, 'Which service had the most incidents in the last 30 days?');
    await user.click(screen.getByRole('button', { name: 'Ask SA AI' }));
    expect(await screen.findByText('Network had the most incidents in the last 30 days.')).toBeInTheDocument();
    expect(screen.getByText(/LLM · anthropic · claude/)).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Answer data' })).toHaveTextContent('Network150');
    expect(screen.getByText(/Based on: Incidents created in the last 30 days/)).toBeInTheDocument();
    expect(requests.find((request) => request.url === '/ai/ask')?.body).toEqual({ question: 'Which service had the most incidents in the last 30 days?' });
  });
});

describe('analytics helpers', () => {
  it('builds safe stacked series keys in the API order', () => {
    const { rows, series, total } = buildServiceVolume(overview.serviceVolume, overview.serviceNames);
    expect(series.map((entry) => [entry.key, entry.name, entry.total])).toEqual([
      ['s0', 'Payroll', 9],
      ['s1', 'HR Portal', 5],
      ['s2', 'Network', 13],
    ]);
    expect(rows[0]).toEqual({ date: '2026-10-30', s0: 4, s1: 3, s2: 6 });
    expect(total).toBe(27);
    expect(buildServiceVolume(undefined, undefined).series).toEqual([]);
  });

  it('validates custom ranges', () => {
    expect(validateRange('', '', '2026-11-03')).toEqual({ from: 'Choose a start date', to: 'Choose an end date' });
    expect(validateRange('2026-11-01', '2026-11-05', '2026-11-03').to).toBe('The end date cannot be in the future');
    expect(validateRange('2024-01-01', '2026-11-01', '2026-11-03').to).toMatch(/at most 366 days/);
    expect(validateRange('2026-10-01', '2026-10-31', '2026-11-03')).toEqual({});
  });

  it('phrases suggestions for the active period', () => {
    const base = { days: 30, custom: false, from: '', to: '' } as Parameters<typeof suggestedQuestions>[0];
    expect(suggestedQuestions(base)[0]).toBe('How many critical incidents in the last 30 days?');
    expect(suggestedQuestions({ ...base, custom: true, from: '2026-10-25', to: '2026-11-03' }, '2026-11-03')[0]).toBe('How many critical incidents in the last 10 days?');
  });

  it('sorts table rows with nulls last', () => {
    const sorted = sortRows(overview.byService, { key: 'mttr', dir: 'asc' }, { mttr: (row) => row.mttrMinutes });
    expect(sorted.map((row) => row.name)).toEqual(['HR Portal', 'Payroll', 'Network']);
  });
});
