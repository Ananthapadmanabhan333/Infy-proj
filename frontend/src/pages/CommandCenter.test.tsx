import { cloneElement, type ReactElement } from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { greetingFor } from '../components/PageHeader';
import { apiErrorBody, json, mockApi, requests } from '../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../test/utils';
import type { DashboardDto, IncidentSummary, Role, ServiceDto } from '../lib/types';
import CommandCenter from './CommandCenter';

const openNewIncident = vi.fn();
vi.mock('../components/incidents/NewIncidentContext', () => ({ useNewIncident: () => ({ open: openNewIncident }) }));
vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('recharts')>();
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: ReactElement }) => <div style={{ width: 640, height: 260 }}>{cloneElement(children, { width: 640, height: 260 })}</div>,
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

function incident(id: number, overrides: Partial<IncidentSummary> = {}): IncidentSummary {
  return {
    id,
    number: `INC-${7800 + id}`,
    title: `Incident ${id}`,
    priority: 'HIGH',
    impact: 'HIGH',
    urgency: 'HIGH',
    status: 'IN_PROGRESS',
    category: 'APPLICATION',
    serviceId: 1,
    serviceName: 'Payroll',
    departmentId: 1,
    departmentName: 'IT Service Desk',
    assigneeId: 7,
    assigneeName: 'Ada Lovelace',
    requesterId: 9,
    requesterName: 'Priya Raman',
    parentId: null,
    parentNumber: null,
    major: false,
    childCount: 0,
    createdAt: '2026-11-03T08:00:00Z',
    updatedAt: '2026-11-03T08:30:00Z',
    resolvedAt: null,
    sla: null,
    demo: false,
    ...overrides,
  };
}

function service(id: number, name: string, availability30d: number, overrides: Partial<ServiceDto> = {}): ServiceDto {
  return {
    id,
    code: name.toUpperCase().replace(/\s+/g, '_'),
    name,
    description: null,
    departmentId: 1,
    departmentName: 'IT',
    ownerId: null,
    ownerName: null,
    tier: 1,
    status: 'OPERATIONAL',
    statusReason: null,
    availability30d,
    openIncidents: 0,
    criticalOpen: 0,
    maintenance: false,
    keywords: [],
    demo: false,
    ...overrides,
  };
}

function dashboard(days = 7, overrides: Partial<DashboardDto> = {}): DashboardDto {
  return {
    days,
    kpis: {
      totalIncidents: 1248,
      openIncidents: 246,
      criticalOpen: 27,
      createdInPeriod: days === 7 ? 312 : 1104,
      resolvedInPeriod: 290,
      slaCompliance: 96.4,
      mttrMinutes: 28,
      mttaMinutes: 6,
      resolutionRate: 93,
      firstCallResolution: 88,
      deltas: { created: 12, criticalOpen: -3, slaCompliance: 2.1, mttr: 18 },
    },
    trend: [
      { date: '2026-11-01', created: 40, resolved: 35 },
      { date: '2026-11-02', created: 44, resolved: 41 },
    ],
    forecast: [{ date: '2026-11-03', predicted: 46, lower: 38, upper: 54 }],
    forecastMethod: 'Statistical forecast: least-squares linear trend on daily incident counts (95% band)',
    distribution: [
      { priority: 'CRITICAL', count: 27 },
      { priority: 'HIGH', count: 186 },
      { priority: 'MEDIUM', count: 642 },
      { priority: 'LOW', count: 393 },
    ],
    serviceHealth: [service(1, 'Payroll', 99.9), service(2, 'HR Portal', 98.2, { status: 'DEGRADED', openIncidents: 2 }), service(3, 'Network', 99.7), service(4, 'OneDrive', 99.8)],
    activeIncidents: [incident(1, { title: 'Payroll application not accessible' })],
    myQueue: [],
    counts: { activeWorkflows: 4, executionsLast24h: 12, publishedArticles: 30, kbResolutionRate: 41, pendingApprovals: 2, unreadNotifications: 1, openServiceRequests: 5 },
    ...overrides,
  };
}

const insights = {
  source: 'FALLBACK',
  provider: 'fallback',
  model: null,
  generatedAt: '2026-11-03T09:00:00Z',
  latencyMs: 12,
  notice: 'Deterministic analysis of live data — no language model was used.',
  fallbackReason: 'LLM disabled',
  data: {
    insights: [
      { title: 'SLA compliance healthy', detail: '96% of incidents met their SLA.', severity: 'INFO', metric: '96%', confidence: 'HIGH' },
      { title: 'Payroll morning peak', detail: 'Payroll Service has a 78% higher incident rate between 10 AM – 12 PM.', severity: 'WARNING', metric: '78%', confidence: 'HIGH' },
    ],
    hourly: [],
  },
};

function render(role: Role, data: DashboardDto = dashboard(), extra: Record<string, ReturnType<typeof json>> = {}) {
  mockApi({
    'GET /analytics/dashboard': (config) =>
      json(Number(config.params?.days) === 30 ? { ...data, days: 30, kpis: { ...data.kpis, createdInPeriod: 1104 } } : data),
    'POST /ai/insights': json(insights),
    ...extra,
  });
  return renderWithProviders(<CommandCenter />, { auth: makeAuth(makeUser(role, { fullName: 'Ananta Padmanabhan' })) });
}

describe('<CommandCenter /> (staff)', () => {
  it('greets the user and shows the gold title, KPIs with coloured deltas, trend, distribution and service health', async () => {
    render('SYSTEM_ADMIN');
    expect(screen.getByText(`${greetingFor()}, Ananta`)).toBeInTheDocument();
    const heading = screen.getByRole('heading', { level: 1, name: 'Operations Command Center' });
    expect(heading).toHaveClass('gold');
    expect(heading.closest('.page-heading')).toHaveClass('glow');

    expect(await screen.findByText('312')).toBeInTheDocument();
    const deltas = screen.getAllByTestId('kpi-delta');
    expect(deltas.map((delta) => delta.textContent)).toEqual(['up 12%', 'down 3', 'up 2.1pp', 'up 18%']);
    expect(deltas.map((delta) => delta.className.split(' ').pop())).toEqual(['bad', 'good', 'good', 'bad']);
    expect(screen.getByText('96.4%')).toBeInTheDocument();
    expect(screen.getByText('28 min')).toBeInTheDocument();

    expect(screen.getByRole('region', { name: 'Incident Trend & Forecast' })).toBeInTheDocument();
    expect(screen.getByTestId('predicted-callout')).toHaveTextContent('46');

    const distribution = screen.getByRole('region', { name: 'Incident Distribution' });
    expect(within(distribution).getByText('1,248')).toBeInTheDocument();
    expect(within(distribution).getByText('Medium')).toBeInTheDocument();
    expect(within(distribution).getByText('642')).toBeInTheDocument();
    expect(within(distribution).getByText('51%')).toBeInTheDocument();

    const health = screen.getByRole('region', { name: 'Service Health' });
    expect(within(health).getAllByRole('link', { name: /availability/ })).toHaveLength(4);
    expect(within(health).getByText('98.2%')).toBeInTheDocument();
  });

  it('generates the AI insight once and shows its confidence and source label', async () => {
    render('IT_MANAGER');
    const card = await screen.findByRole('region', { name: 'AI Insight' });
    expect(await within(card).findByText('Payroll morning peak')).toBeInTheDocument();
    expect(within(card).getByText(/78% higher incident rate/)).toBeInTheDocument();
    expect(within(card).getByText('High Confidence')).toBeInTheDocument();
    expect(within(card).getByText(/Deterministic fallback — not AI-generated/)).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: /1 more insight in SA AI/ })).toHaveAttribute('href', '/sa-ai');
    expect(requests.filter((request) => request.url === '/ai/insights')).toHaveLength(1);
  });

  it('reloads the dashboard for the chosen period', async () => {
    const user = userEvent.setup();
    render('IT_ADMIN');
    await screen.findByText('312');
    await user.selectOptions(screen.getByLabelText('Reporting period'), '30');
    expect(await screen.findByText('1,104')).toBeInTheDocument();
    expect(requests.some((request) => request.url === '/analytics/dashboard' && Number((request.params as Record<string, unknown>).days) === 30)).toBe(true);
  });

  it('offers the payroll scenario to SYSTEM_ADMIN only and New Incident to staff', async () => {
    const { unmount } = render('SYSTEM_ADMIN');
    expect(screen.getByRole('button', { name: 'Run payroll outage demo' })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'New Incident' }));
    expect(openNewIncident).toHaveBeenCalled();
    unmount();

    render('IT_ADMIN');
    expect(screen.queryByRole('button', { name: 'Run payroll outage demo' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New Incident' })).toBeInTheDocument();
  });

  it('hides New Incident for auditors', async () => {
    render('AUDITOR');
    await screen.findByText('312');
    expect(screen.queryByRole('button', { name: 'New Incident' })).not.toBeInTheDocument();
  });

  it('shows My queue for support agents', async () => {
    render('SUPPORT_AGENT', dashboard(7, { myQueue: [incident(2, { title: 'VPN drops every hour' })] }));
    const queue = await screen.findByRole('region', { name: 'My queue' });
    expect(within(queue).getByText('VPN drops every hour')).toBeInTheDocument();
    expect(within(queue).getByRole('link', { name: /Open queue/ })).toHaveAttribute('href', '/incidents?tab=mine');
  });

  it('shows an error state with retry when the dashboard fails', async () => {
    mockApi({ 'GET /analytics/dashboard': { status: 500, data: apiErrorBody(500, 'Dashboard query failed') } });
    renderWithProviders(<CommandCenter />, { auth: makeAuth(makeUser('IT_MANAGER')) });
    expect(await screen.findByText('Dashboard query failed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument();
  });
});

describe('<CommandCenter /> (employee)', () => {
  it('shows the self-service home without staff analytics or AI calls', async () => {
    render('EMPLOYEE', dashboard(7, { myQueue: [incident(3, { title: 'Laptop will not boot', requesterName: 'Ananta Padmanabhan' })] }), {
      'GET /service-requests': json({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 }),
      'GET /announcements': json([]),
    });
    expect(screen.getByRole('heading', { level: 1, name: 'Your IT Service Hub' })).toBeInTheDocument();
    expect(await screen.findByText('Laptop will not boot')).toBeInTheDocument();
    expect(screen.getByRole('search')).toBeInTheDocument();
    expect(screen.queryByLabelText('Reporting period')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'AI Insight' })).not.toBeInTheDocument();
    expect(await screen.findByText('No requests yet')).toBeInTheDocument();
    await waitFor(() => expect(requests.some((request) => request.url === '/ai/insights')).toBe(false));
  });
});

describe('<CommandCenter /> — Release 3 command center block', () => {
  const block = {
    incidents: { open: 17, criticalOpen: 2, majorOpen: 1, createdInPeriod: 40, resolvedInPeriod: 35, slaBreachesInPeriod: 3, slaAtRiskNow: 4, mttaMinutes: 14 },
    alerts: { active: 9, open: 6, acknowledged: 3, critical: 2, escalated: 1, resolvedLast24h: 12 },
    onCallNow: [],
    changes: { awaitingApproval: 2, scheduledNext7Days: 5, implementingNow: 1, completedInPeriod: 8, failedInPeriod: 1, emergencyOpen: 0 },
    workflows: { executions24h: 20, succeeded24h: 18, failed24h: 2, running: 1 },
    activeMajorIncidents: [{ id: 2, number: 'MI-002', title: 'Payroll portal returns 503', status: 'ACTIVE', commanderName: 'Sunita Joshi', declaredAt: '2026-11-04T05:00:00Z' }],
    departmentPerformance: [],
    recentActivity: [],
  };

  it('renders the block when the API sends it', async () => {
    render('IT_MANAGER', dashboard(7, { commandCenter: block }));
    expect(await screen.findByRole('link', { name: 'MI-002' })).toHaveAttribute('href', '/major-incidents/2');
    expect(screen.getByRole('region', { name: 'Active major incidents' })).toBeInTheDocument();
    expect(screen.getByText('No active on-call schedules')).toBeInTheDocument();
  });

  it('renders nothing extra when commandCenter is null', async () => {
    render('IT_MANAGER', dashboard(7, { commandCenter: null }));
    expect(await screen.findByText('312')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Active major incidents' })).not.toBeInTheDocument();
  });
});
