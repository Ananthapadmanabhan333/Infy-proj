import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import PortalPage from '../workspace/PortalPage';
import DexPage from './DexPage';

vi.mock('../../components/incidents/NewIncidentContext', () => ({ useNewIncident: () => ({ open: vi.fn() }) }));

const last = (method: string, url: string) =>
  [...requests].reverse().find((r) => r.method === method && r.url === url);

const sampleEndpoint = {
  id: 1,
  deviceId: 'DEV-ENG-104',
  userEmail: 'ananya@nexus360.test',
  userName: 'Ananya Sharma',
  departmentName: 'Engineering',
  osName: 'Windows 11',
  osVersion: '23H2',
  deviceModel: 'ThinkPad T14 Gen 4',
  cpuUsagePct: 42,
  memoryUsagePct: 68,
  diskUsagePct: 55,
  bootTimeSeconds: 26,
  crashCount7d: 1,
  batteryHealthPct: 92,
  deviceHealthScore: 84.5,
  experienceScore: 82.0,
  scoreBreakdown: { deviceHealth: 84.5, appPerformance: 80.0 },
  lastSeenAt: '2026-11-04T09:30:00Z',
  simulated: true,
};

function setupDexRoutes(status: 'OK' | 'INSUFFICIENT_DATA' = 'OK') {
  mockApi({
    'GET /dex/overview': json({
      status,
      overallExperienceScore: 78.4,
      deviceHealthAvg: 81.2,
      appPerformanceAvg: 76.5,
      loginSuccessRatePct: 97.8,
      endpointCount: 12,
      formula: '0.35 * deviceHealth + 0.25 * appPerformance + 0.20 * loginSuccess + 0.20 * ticketSignals',
      byDepartment: [
        {
          key: 'Engineering',
          name: 'Engineering',
          score: 82.0,
          deviceHealth: 84.5,
          appPerformance: 80.0,
          loginSuccess: 98.5,
          ticketSignals: 78.0,
          count: 8,
        },
      ],
      byDeviceModel: [{ key: 'ThinkPad T14 Gen 4', name: 'ThinkPad T14 Gen 4', score: 82.0, count: 8 }],
      byApp: [
        {
          appName: 'Payroll Portal',
          appVersion: '4.2.0',
          avgLaunchMs: 620,
          avgPageLoadMs: 410,
          crashRatePct: 0.4,
          eventCount: 250,
        },
      ],
      serviceExperience: [
        {
          serviceId: 3,
          serviceName: 'Payroll Service',
          incidentsPer100Users: 4.2,
          avgResolveHours: 2.8,
          reopenRatePct: 3.1,
          fcrRatePct: 81.5,
          csatAvg: 4.6,
        },
      ],
      endpoints: [sampleEndpoint],
      proactiveRules: [
        {
          id: 1,
          name: 'Engineering DEX Degradation',
          departmentName: 'Engineering',
          thresholdScore: 65,
          enabled: true,
          lastTriggeredIncidentId: null,
        },
      ],
      simulated: true,
    }),
    'POST /dex/proactive-rules': json({
      id: 2,
      name: 'Finance DEX Alert',
      departmentName: 'Finance',
      thresholdScore: 60,
      enabled: true,
      lastTriggeredIncidentId: null,
    }),
  });
}

describe('DexPage & Employee Portal DEX Card', () => {
  it('shows insufficient-data callout on DexPage when status is INSUFFICIENT_DATA', async () => {
    setupDexRoutes('INSUFFICIENT_DATA');
    renderWithProviders(<DexPage />, {
      route: '/dex',
      auth: makeAuth(makeUser('AUDITOR')),
    });

    expect(await screen.findByText(/Insufficient data — 0 endpoint telemetry samples/i)).toBeInTheDocument();
  });

  it('renders the composite DEX formula, department scores, endpoints and lets ADMINS create proactive rules', async () => {
    setupDexRoutes('OK');
    const user = userEvent.setup();
    renderWithProviders(<DexPage />, {
      route: '/dex',
      auth: makeAuth(makeUser('SYSTEM_ADMIN')),
    });

    expect(await screen.findByText('78.4 / 100')).toBeInTheDocument();
    expect(screen.getByText(/0\.35 \* deviceHealth \+ 0\.25 \* appPerformance/)).toBeInTheDocument();
    expect(screen.getByText('Engineering')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: /Proactive Rules & Ingestion/i }));
    await user.type(screen.getByLabelText('Proactive rule name'), 'Finance DEX Alert');
    await user.click(screen.getByRole('button', { name: /Add/i }));
    await waitFor(() => expect(last('POST', '/dex/proactive-rules')).toBeDefined());
  });

  it('renders the employee own device summary card on PortalPage and shows NO_DEVICE cleanly when absent', async () => {
    const emptySection = { total: 0, items: [] };
    const portalOverview = {
      generatedAt: '2026-11-04T08:00:00Z',
      openIncidents: emptySection,
      openRequests: emptySection,
      awaitingApproval: emptySection,
      serviceStatus: { overall: 'OPERATIONAL', counts: { OPERATIONAL: 3 }, services: [], maintenance: [] },
      recommendedArticles: [],
    };

    mockApi({
      'GET /portal/overview': json(portalOverview),
      'GET /catalog/categories': json([]),
      'GET /dex/me': json({ status: 'OK', device: sampleEndpoint, message: 'Device health active' }),
    });

    const { unmount } = renderWithProviders(<PortalPage />, {
      route: '/portal',
      auth: makeAuth(makeUser('EMPLOYEE')),
    });

    expect(await screen.findByText('My Device Health (DEX)')).toBeInTheDocument();
    expect(await screen.findByText(/ThinkPad T14 Gen 4 \(DEV-ENG-104\)/)).toBeInTheDocument();
    expect(screen.getByText('82.0 / 100')).toBeInTheDocument();
    unmount();

    mockApi({
      'GET /portal/overview': json(portalOverview),
      'GET /catalog/categories': json([]),
      'GET /dex/me': json({
        status: 'NO_DEVICE',
        device: null,
        message: 'No endpoint telemetry reported for your account yet',
      }),
    });

    renderWithProviders(<PortalPage />, {
      route: '/portal',
      auth: makeAuth(makeUser('EMPLOYEE')),
    });

    expect(await screen.findByText('No endpoint telemetry reported for your account yet')).toBeInTheDocument();
  });
});
