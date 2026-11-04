import { cloneElement, type ReactElement } from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { rankServiceBreaches } from '../components/sla/Breaches';
import { slaKpis } from '../components/sla/kpis';
import { complianceDomain, latestCompliance } from '../components/sla/SlaComplianceTrend';
import { json, mockApi, requests } from '../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../test/utils';
import type { IncidentSummary, Role, SlaOverview } from '../lib/types';
import SlaMonitorPage from './SlaMonitorPage';

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

const overview: SlaOverview = {
  days: 30,
  activeCount: 60,
  onTrackCount: 20,
  atRiskCount: 34,
  breachedActiveCount: 4,
  pausedCount: 2,
  metCount: 241,
  missedCount: 9,
  compliancePercent: 96.4,
  responseCompliancePercent: 98.1,
  kpis: { compliance: 96.4, breachesInPeriod: 12, atRiskNow: 34, avgResolutionMinutes: 28 },
  byPriority: [
    { priority: 'CRITICAL', met: 20, missed: 2, active: 3, compliance: 90.9 },
    { priority: 'HIGH', met: 60, missed: 3, active: 10, compliance: 95.2 },
    { priority: 'MEDIUM', met: 100, missed: 3, active: 30, compliance: 97.1 },
    { priority: 'LOW', met: 61, missed: 1, active: 17, compliance: 98.4 },
  ],
  byService: [
    { serviceId: 1, serviceName: 'Payroll', met: 40, missed: 3, atRisk: 2, breached: 1, compliance: 93 },
    { serviceId: 2, serviceName: 'HR Portal', met: 30, missed: 3, atRisk: 0, breached: 0, compliance: 90.9 },
    { serviceId: 3, serviceName: 'Network', met: 50, missed: 2, atRisk: 1, breached: 0, compliance: 96.2 },
    { serviceId: 4, serviceName: 'Facilities', met: 20, missed: 0, atRisk: 0, breached: 0, compliance: 100 },
  ],
  byDepartment: [
    { name: 'IT Service Desk', met: 150, missed: 4, atRisk: 20, compliance: 97.4 },
    { name: 'Payroll Team', met: 40, missed: 5, atRisk: 3, compliance: 88.9 },
  ],
  trend: [
    { date: '2026-10-29', met: 8, missed: 1, compliance: 88.9 },
    { date: '2026-10-30', met: 9, missed: 0, compliance: 100 },
    { date: '2026-10-31', met: 0, missed: 0, compliance: null },
  ],
};

function incident(id: number, title: string): IncidentSummary {
  return {
    id,
    number: `INC-${7840 + id}`,
    title,
    priority: 'CRITICAL',
    impact: 'HIGH',
    urgency: 'HIGH',
    status: 'IN_PROGRESS',
    category: 'APPLICATION',
    serviceId: 1,
    serviceName: 'Payroll',
    departmentId: 1,
    departmentName: 'Payroll Team',
    assigneeId: 11,
    assigneeName: 'Riya Varma',
    requesterId: 9,
    requesterName: 'Priya Raman',
    parentId: null,
    parentNumber: null,
    major: false,
    childCount: 0,
    createdAt: '2026-11-03T08:00:00Z',
    updatedAt: '2026-11-03T08:30:00Z',
    resolvedAt: null,
    sla: {
      policyName: 'P1 Critical',
      businessHoursOnly: false,
      clockStatus: 'RUNNING',
      health: 'AT_RISK',
      outcome: null,
      responseDueAt: '2026-11-03T08:15:00Z',
      respondedAt: '2026-11-03T08:05:00Z',
      responseBreached: false,
      resolutionDueAt: new Date(Date.now() + 18 * 60_000).toISOString(),
      remainingSeconds: 18 * 60,
      percentElapsed: 85,
      escalationLevel: 1,
    },
    demo: false,
  };
}

function setup(role: Role = 'IT_MANAGER') {
  mockApi({
    'GET /sla/overview': (config) => json({ ...overview, days: Number(config.params?.days) || 30 }),
    'GET /sla/active': (config) => {
      const health = config.params?.health;
      const content = health === 'AT_RISK' ? [incident(6, 'Payroll application not accessible')] : health === 'BREACHED' ? [incident(7, 'HR portal timeout')] : [];
      return json({ content, page: 0, size: 15, totalElements: content.length, totalPages: content.length ? 1 : 0 });
    },
    'POST /sla/evaluate': json({ evaluated: 60, escalated: 1 }),
  });
  const user = userEvent.setup();
  renderWithProviders(<SlaMonitorPage />, { auth: makeAuth(makeUser(role)) });
  return user;
}

describe('<SlaMonitorPage />', () => {
  it('shows the four SLA KPIs, the compliance trend and breaches by service and department', async () => {
    setup();
    expect(screen.getByRole('heading', { level: 1, name: 'SLA Monitoring' })).toBeInTheDocument();
    expect(await screen.findByText('96.4%')).toBeInTheDocument();
    expect(screen.getByText('Breaches', { selector: '.kpi small' }).closest('.kpi')).toHaveTextContent('12');
    expect(screen.getByText('At Risk', { selector: '.kpi small' }).closest('.kpi')).toHaveTextContent('34');
    expect(screen.getByText('Avg. Resolution', { selector: '.kpi small' }).closest('.kpi')).toHaveTextContent('28 min');

    const trend = screen.getByRole('region', { name: 'SLA Compliance Trend' });
    expect(within(trend).getByTestId('sla-latest')).toHaveTextContent('100%');

    const services = within(screen.getByRole('region', { name: 'Breaches by Service' })).getAllByRole('link');
    expect(services.map((link) => link.textContent)).toEqual([
      'Payroll93% compliance · 2 at risk4',
      'HR Portal90.9% compliance3',
      'Network96.2% compliance · 1 at risk2',
    ]);
    expect(services[0]).toHaveAttribute('href', '/services/1');

    const departments = within(screen.getByRole('region', { name: 'Breaches by Department' })).getAllByRole('row');
    expect(departments[1]).toHaveTextContent('Payroll Team'); // most missed first
    expect(screen.getByRole('region', { name: 'Compliance by Priority' })).toHaveTextContent('90.9%');
  });

  it('keeps the live SLA queue with health tabs, counts and countdowns', async () => {
    const user = setup();
    const queue = await screen.findByRole('region', { name: 'Live SLA Queue' });
    expect(await within(queue).findByText('Payroll application not accessible')).toBeInTheDocument();
    expect(within(queue).getByRole('tab', { name: /Breached \(4\)/ })).toBeInTheDocument();
    await user.click(within(queue).getByRole('tab', { name: /Breached/ }));
    expect(await within(queue).findByText('HR portal timeout')).toBeInTheDocument();
    expect(requests.some((request) => request.url === '/sla/active' && (request.params as Record<string, unknown>).health === 'BREACHED')).toBe(true);
  });

  it('reloads for the chosen period', async () => {
    const user = setup();
    await screen.findByText('96.4%');
    await user.selectOptions(screen.getByLabelText('Reporting period'), '7');
    await waitFor(() => expect(requests.some((request) => request.url === '/sla/overview' && (request.params as Record<string, unknown>).days === 7)).toBe(true));
    expect(await screen.findByText(/In the last 7 days/)).toBeInTheDocument();
  });

  it('lets configuration admins run an evaluation', async () => {
    const user = setup('IT_ADMIN');
    await screen.findByText('96.4%');
    await user.click(screen.getByRole('button', { name: /Evaluate now/ }));
    expect(await screen.findByText(/SLA evaluation complete — Evaluated: 60 · Escalated: 1/)).toBeInTheDocument();
  });

  it('hides evaluation from managers', async () => {
    setup('IT_MANAGER');
    await screen.findByText('96.4%');
    expect(screen.queryByRole('button', { name: /Evaluate now/ })).not.toBeInTheDocument();
  });
});

describe('SLA helpers', () => {
  it('ranks services by breaches and drops services without breaches or risk', () => {
    expect(rankServiceBreaches(overview.byService).map((row) => row.serviceName)).toEqual(['Payroll', 'HR Portal', 'Network']);
  });

  it('derives the chart floor and the latest compliance value', () => {
    expect(complianceDomain(overview.trend)).toEqual([85, 100]);
    expect(complianceDomain([])).toEqual([0, 100]);
    expect(latestCompliance(overview.trend)?.date).toBe('2026-10-30');
  });

  it('falls back to the Release 1 counters when kpis are missing', () => {
    const legacy = { ...overview, kpis: undefined } as unknown as SlaOverview;
    expect(slaKpis(legacy)).toEqual({ compliance: 96.4, breachesInPeriod: 13, atRiskNow: 34, avgResolutionMinutes: null });
  });
});
