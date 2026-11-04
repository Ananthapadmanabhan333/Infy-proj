/**
 * Release 3 BE-4 extensions of existing pages: operations analytics and SLA risk, the Command Center block, the SA AI
 * operations panels (source label and rate limit), the incident-detail major incident card and the notification categories.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeAll, describe, expect, it } from 'vitest';
import { aiResult, makeDetail } from '../pages/incidents/fixtures.test-utils';
import { json, mockApi, requests } from '../test/mockApi';
import { makeMajorDetail, makeNowEntry } from '../test/responseFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../test/utils';
import type { CommandCenterBlock as CommandCenterData, OperationsMetrics } from '../lib/responseTypes';
import { NotificationPreferencesPanel } from './admin/NotificationPreferencesPanel';
import { CmdbImpactPanel, ProblemCorrelationPanel, RunbookRecommendationsPanel, SlaRiskAiPanel, TrendsPanel } from './ai/OpsAiPanels';
import { OpsMetricsTab, SlaRiskTab } from './analytics/OpsMetricsTab';
import type { AnalyticsFilterState } from './analytics/useAnalyticsFilters';
import { CommandCenterBlock } from './dashboard/CommandCenterBlock';
import { MajorCommandCard } from '../pages/incidents/detail/MajorCommandCard';

beforeAll(() => {
  // Recharts' ResponsiveContainer needs ResizeObserver, which jsdom lacks.
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
});

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const METRICS: OperationsMetrics = {
  from: '2026-10-06',
  to: '2026-11-04',
  days: 30,
  departmentId: null,
  generatedAt: '2026-11-04T08:00:00Z',
  mtta: { minutes: 14.5, samples: 40 },
  mttd: { minutes: null, samples: 0, incidentsWithoutAlert: 40 },
  firstContactResolution: { rate: 62.5, resolved: 32, resolvedAtFirstContact: 20 },
  changes: { finished: 10, completed: 8, failed: 1, rolledBack: 1, successRate: 80, failureRate: 20 },
  problemRecurrence: { knownErrors: 4, problemsWithRecurrence: 1, recurringIncidents: 2, rate: 25 },
  alertVolume: { total: 57, occurrences: 140, bySeverity: { CRITICAL: 5, MAJOR: 12 }, bySource: { PROMETHEUS: 40, MANUAL: 17 }, suppressed: 3, withIncident: 9, perDay: [{ date: '2026-11-03', count: 4 }] },
  assetLifecycle: { byStatus: { IN_USE: 120, IN_STOCK: 14 }, acquired: 6, retired: 2, disposed: 1, averageAgeDays: 410.2, warrantyExpiringNext30Days: 3 },
  onCall: {
    alertsPaged: 7,
    pages: 11,
    acknowledged: 6,
    ackRate: 85.7,
    avgAckMinutes: 6.2,
    escalated: 2,
    bySchedule: [{ scheduleId: 3, scheduleName: 'Payroll & HR Apps on-call', teamId: 4, teamName: 'Payroll & HR Apps', alertsPaged: 4, pages: 6, acknowledged: 4, ackRate: 100, avgAckMinutes: 4.1, escalated: 1 }],
  },
  departmentPerformance: [{ departmentId: 2, name: 'IT Operations', created: 30, resolved: 25, open: 5, mttaMinutes: 12, mttrMinutes: 240, slaCompliance: 91.2, firstContactResolution: 60 }],
  serviceAvailability: [{ serviceId: 4, code: 'PAYROLL', name: 'Payroll', tier: 1, availability: 98.75, outageMinutes: 95, degradedMinutes: 0, incidents: 2 }],
};

const FILTERS = { filters: { days: 30, departmentId: null }, days: 30, from: '', to: '', departmentId: null, custom: false, rangeLabel: 'Last 30 days' } as unknown as AnalyticsFilterState;

describe('Analytics — operations metrics', () => {
  it('renders MTTA, MTTD (n/a without alerts), FCR, change outcomes, on-call, departments, availability and the definitions', async () => {
    mockApi({
      'GET /analytics/operations': json(METRICS),
      'GET /analytics/definitions': json([{ key: 'mttd', name: 'Mean time to detect', unit: 'minutes', formula: 'mean (incident created − first linked alert fired)', sources: ['incidents', 'alerts'], filters: ['period', 'departmentId'], notes: null }]),
    });
    renderWithProviders(<OpsMetricsTab state={FILTERS} />, { auth: makeAuth(makeUser('IT_MANAGER')) });
    expect((await screen.findAllByText('MTTA')).length).toBeGreaterThan(0);
    expect(screen.getByText('15 min')).toBeInTheDocument();
    expect(screen.getByText('n/a')).toBeInTheDocument();
    expect(screen.getByText('62.5%')).toBeInTheDocument();
    expect(screen.getByText('80%')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Payroll & HR Apps on-call' })).toHaveAttribute('href', '/on-call/3');
    expect(screen.getByText('IT Operations')).toBeInTheDocument();
    expect(screen.getByText('98.75%')).toBeInTheDocument();
    expect(last('GET', '/analytics/operations')?.params).toEqual({ days: 30 });
    expect(await screen.findByText('How metrics are calculated')).toBeInTheDocument();
    expect(screen.getByText('Mean time to detect')).toBeInTheDocument();
    expect(screen.getByText(/first linked alert fired/)).toBeInTheDocument();
  });

  it('labels the SLA risk ranking as statistical, not AI', async () => {
    mockApi({
      'GET /analytics/sla-risk': json({
        generatedAt: '2026-11-04T08:00:00Z',
        method: 'Share of comparable clocks that breached',
        incidents: [
          { incidentId: 9, number: 'INC-7009', title: 'Payroll 503', priority: 'CRITICAL', policyName: 'P1', clockStatus: 'RUNNING', elapsedPercent: 82, remainingMinutes: 20, resolutionDueAt: null, probability: 0.74, riskLevel: 'HIGH', probabilityLabel: '74 % of 12 comparable clocks breached — a statistic, not an AI estimate', sampleSize: 12, historicalBreaches: 9, historyDays: 180, method: null, factors: [], recommendations: [] },
        ],
      }),
    });
    renderWithProviders(<SlaRiskTab />, { auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByText('Statistical — not AI')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'INC-7009' })).toHaveAttribute('href', '/incidents/9');
    expect(screen.getByText('74%')).toBeInTheDocument();
    expect(screen.getByText('9 / 12 breached')).toBeInTheDocument();
    expect(screen.queryByText(/LLM/)).not.toBeInTheDocument();
  });
});

const COMMAND_CENTER: CommandCenterData = {
  incidents: { open: 17, criticalOpen: 2, majorOpen: 1, createdInPeriod: 40, resolvedInPeriod: 35, slaBreachesInPeriod: 3, slaAtRiskNow: 4, mttaMinutes: 14 },
  alerts: { active: 9, open: 6, acknowledged: 3, critical: 2, escalated: 1, resolvedLast24h: 12 },
  onCallNow: [makeNowEntry()],
  changes: { awaitingApproval: 2, scheduledNext7Days: 5, implementingNow: 1, completedInPeriod: 8, failedInPeriod: 1, emergencyOpen: 0 },
  workflows: { executions24h: 20, succeeded24h: 18, failed24h: 2, running: 1 },
  activeMajorIncidents: [{ id: 2, number: 'MI-002', title: 'Payroll portal returns 503', status: 'ACTIVE', commanderName: 'Sunita Joshi', declaredAt: '2026-11-04T05:00:00Z' }],
  departmentPerformance: [{ departmentId: 2, name: 'IT Operations', created: 30, resolved: 25, open: 5, mttaMinutes: 12, mttrMinutes: 240, slaCompliance: 91.2, firstContactResolution: 60 }],
  recentActivity: [
    { kind: 'ALERT', at: '2026-11-04T07:00:00Z', title: 'ALR-00061 acknowledged', detail: 'Payroll API 5xx', actorName: 'Riya Varma', link: '/alerts/61' },
    { kind: 'CHANGE', at: '2026-11-04T06:00:00Z', title: 'External link ignored', detail: null, actorName: null, link: 'https://evil.example' },
  ],
};

describe('Command Center block', () => {
  it('renders live counts, active major incidents, on call now, departments and recent activity from the API', () => {
    renderWithProviders(<CommandCenterBlock data={COMMAND_CENTER} days={7} />, { auth: makeAuth(makeUser('IT_MANAGER')) });
    expect(screen.getByText('17')).toBeInTheDocument();
    expect(screen.getByText('SLA breaches · 7 d')).toBeInTheDocument();
    expect(screen.getByText('90%')).toBeInTheDocument(); // 18 of 20 workflow executions
    expect(screen.getByRole('link', { name: 'MI-002' })).toHaveAttribute('href', '/major-incidents/2');
    const onCall = screen.getByRole('list', { name: 'On call now' });
    expect(within(onCall).getByText(/Primary Dev Nair/)).toBeInTheDocument();
    expect(within(onCall).getByText('Override')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'ALR-00061 acknowledged' })).toHaveAttribute('href', '/alerts/61');
    expect(screen.queryByRole('link', { name: 'External link ignored' })).not.toBeInTheDocument();
    expect(screen.getByText('External link ignored')).toBeInTheDocument();
  });
});

describe('SA AI operations panels', () => {
  it('shows the problem correlation with the exact source notice and fallback reason; nothing is linked automatically', async () => {
    mockApi({
      'POST /ai/problems/5/correlation': json(
        aiResult(
          {
            problemId: 5,
            number: 'PRB-0005',
            title: 'VPN drops',
            candidates: [{ incidentId: 90, number: 'INC-7090', title: 'VPN disconnects', status: 'NEW', createdAt: '2026-11-02T08:00:00Z', score: 0.71, reasons: ['same service', 'similar text'] }],
            changes: [],
            alertGroups: [],
            summary: 'One unlinked incident looks related.',
            basis: 'Unlinked incidents of the last 90 days',
          },
          { fallbackReason: 'The LLM response failed validation' },
        ),
      ),
    });
    renderWithProviders(<ProblemCorrelationPanel problemId={5} />);
    await userEvent.click(screen.getByRole('button', { name: /Find correlations/ }));
    const card = await screen.findByRole('article', { name: 'Correlated records' });
    expect(within(card).getByText(/Deterministic fallback/)).toBeInTheDocument();
    expect(within(card).getByText(/The LLM response failed validation/)).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'INC-7090' })).toHaveAttribute('href', '/incidents/90');
    expect(within(card).getByText('71%')).toBeInTheDocument();
    expect(requests.filter((request) => request.method !== 'POST' || request.url !== '/ai/problems/5/correlation').filter((request) => request.method !== 'GET')).toHaveLength(0);
  });

  it('labels a model answer with provider and model', async () => {
    mockApi({
      'POST /ai/cmdb/cis/12/impact': json(
        aiResult(
          { ciId: 12, ciName: 'payroll-db', ciType: 'DATABASE', criticality: 'CRITICAL', riskLevel: 'HIGH', impactedServices: [{ serviceId: 4, code: 'PAYROLL', name: 'Payroll', tier: 1, status: 'OPERATIONAL' }], upstreamCount: 3, downstreamCount: 1, criticalUpstream: [], openIncidents: 1, openAlerts: 2, summary: 'Payroll fails with it.', recommendations: ['Plan changes out of hours'], truncated: false, basis: 'CMDB depth 4' },
          { source: 'LLM', provider: 'anthropic', model: 'claude-test', fallbackReason: null, notice: 'Explained by an LLM from computed candidates.' },
        ),
      ),
    });
    renderWithProviders(<CmdbImpactPanel ciId={12} />);
    await userEvent.click(screen.getByRole('button', { name: /Analyse impact/ }));
    const card = await screen.findByRole('article', { name: 'Impact' });
    expect(within(card).getByText(/LLM · anthropic · claude-test/)).toBeInTheDocument();
    expect(within(card).getByText('High risk')).toBeInTheDocument();
    expect(within(card).getByText(/Payroll \(PAYROLL\) · tier 1/)).toBeInTheDocument();
  });

  it('explains the AI rate limit (429) with the retry time', async () => {
    mockApi({ 'POST /ai/incidents/9/runbooks': { status: 429, data: { status: 429, error: 'Too Many Requests', message: 'AI rate limit exceeded', path: '/api/ai', timestamp: '' }, headers: { 'retry-after': '42' } } });
    renderWithProviders(<RunbookRecommendationsPanel incidentId={9} />);
    await userEvent.click(screen.getByRole('button', { name: /Recommend/ }));
    expect(await screen.findByText('SA AI rate limit reached.')).toBeInTheDocument();
    expect(screen.getByText(/Try again in 42 seconds/)).toBeInTheDocument();
  });

  it('shows the computed SLA breach probability with its statistical label', async () => {
    mockApi({
      'POST /ai/incidents/9/sla-risk': json(
        aiResult({ incidentId: 9, number: 'INC-7009', title: 'Payroll 503', priority: 'CRITICAL', policyName: 'P1 resolution', clockStatus: 'RUNNING', elapsedPercent: 60, remainingMinutes: 48, resolutionDueAt: null, probability: 0.42, riskLevel: 'MEDIUM', probabilityLabel: '5 of 12 comparable clocks breached — a statistic, not an AI estimate', sampleSize: 12, historicalBreaches: 5, historyDays: 180, method: 'b / s over 180 days', factors: ['Critical priority'], recommendations: ['Escalate to the database team'] }),
      ),
    });
    renderWithProviders(<SlaRiskAiPanel incidentId={9} />);
    await userEvent.click(screen.getByRole('button', { name: /Assess risk/ }));
    expect(await screen.findByText('42%')).toBeInTheDocument();
    expect(screen.getByText('Medium risk')).toBeInTheDocument();
    expect(screen.getByText(/a statistic, not an AI estimate/)).toBeInTheDocument();
    expect(screen.getByText('Escalate to the database team')).toBeInTheDocument();
  });

  it('runs the trend analysis for the chosen window', async () => {
    mockApi({
      'POST /ai/trends': json(
        aiResult({ from: '2026-10-21', to: '2026-11-04', previousFrom: '2026-10-07', previousTo: '2026-10-21', days: 14, trends: [{ metric: 'INCIDENTS', dimension: 'Payroll', current: 12, previous: 3, changePercent: 300, direction: 'UP', zScore: 2.6, significant: true }], highlights: ['Payroll incidents up 300 %'], narrative: 'Payroll is trending up.', basis: 'z-test' }),
      ),
    });
    renderWithProviders(<TrendsPanel />);
    await userEvent.selectOptions(screen.getByLabelText('Trend window'), '14');
    await userEvent.click(screen.getByRole('button', { name: /Analyse trends/ }));
    expect(await screen.findByText('Payroll is trending up.')).toBeInTheDocument();
    expect(screen.getByText('Significant')).toBeInTheDocument();
    expect(last('POST', '/ai/trends')?.params).toEqual({ days: 14 });
  });
});

describe('Incident detail — major incident command card', () => {
  it('links a major incident to its command view and review', async () => {
    mockApi({ 'GET /major-incidents/by-incident/9': json(makeMajorDetail()) });
    renderWithProviders(<MajorCommandCard incident={makeDetail({ id: 9, major: true })} />, { auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    expect(await screen.findByRole('link', { name: 'Open command view' })).toHaveAttribute('href', '/major-incidents/2');
    expect(screen.getByRole('link', { name: /PIR-006/ })).toHaveAttribute('href', '/reviews/6');
    expect(screen.getByText(/Commander/)).toBeInTheDocument();
  });

  it('renders nothing and makes no request for a normal incident', () => {
    mockApi({});
    renderWithProviders(<MajorCommandCard incident={makeDetail({ id: 9, major: false })} />);
    expect(screen.queryByText('Major incident command')).not.toBeInTheDocument();
    expect(requests.find((request) => request.url.startsWith('/major-incidents'))).toBeUndefined();
  });
});

describe('Notification preferences — Release 3 categories', () => {
  it('labels the on-call, major incident and review categories even when the API sends no label', async () => {
    mockApi({
      'GET /notifications/preferences': json(
        ['ONCALL_PAGE', 'ONCALL_SHIFT', 'MAJOR_INCIDENT_UPDATE', 'PIR_UPDATED'].map((type) => ({ type, label: '', description: null, inApp: true, email: type === 'ONCALL_PAGE', emailDefault: type === 'ONCALL_PAGE' })),
      ),
      'GET /status-page': json({ emailConfigured: true }),
    });
    renderWithProviders(<NotificationPreferencesPanel />, { auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    for (const label of ['On-call pages', 'On-call shifts', 'Major incident command', 'Post-incident reviews']) {
      expect(await screen.findByText(label)).toBeInTheDocument();
    }
    await waitFor(() => expect(screen.getByRole('switch', { name: 'On-call pages e-mail' })).toBeChecked());
  });
});
