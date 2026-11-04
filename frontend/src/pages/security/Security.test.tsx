import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import SecurityPage from './SecurityPage';

const last = (method: string, url: string) =>
  [...requests].reverse().find((r) => r.method === method && r.url === url);

const sampleIncident = {
  id: 11,
  number: 'SEC-0011',
  title: 'Credential stuffing & anomalous token replay on Payroll API',
  description: 'Detected 42 failed logins followed by anomalous session token usage',
  category: 'UNAUTHORISED_ACCESS',
  severity: 'CRITICAL',
  status: 'TRIAGE',
  linkedIncidentId: 101,
  affectedCiId: 12,
  affectedServiceId: 3,
  affectedUserEmail: 'payroll-svc@nexus360.test',
  assignedToName: 'Riya Security',
  indicators: [{ type: 'IP', value: '198.51.100.42', confidence: 'HIGH' }],
  evidenceNotes: [{ author: 'Riya Security', note: 'Isolated ingress rate limiter logs', createdAt: '2026-11-04T09:00:00Z' }],
  lessonsLearned: null,
  simulated: true,
  createdAt: '2026-11-04T08:30:00Z',
  updatedAt: '2026-11-04T09:00:00Z',
  closedAt: null,
};

const sampleIncidentDetail = {
  incident: sampleIncident,
  tasks: [
    {
      id: 201,
      taskOrder: 1,
      title: 'Revoke active refresh tokens and lock compromised account',
      phase: 'CONTAINMENT',
      status: 'PENDING',
      remediationPlaybookCode: 'PB-DISABLE-ACCOUNT',
      completedByName: null,
      completedAt: null,
    },
  ],
};

function setupRoutes() {
  mockApi({
    'GET /security/overview': json({
      openIncidents: 2,
      criticalFindings: 3,
      highFindings: 5,
      openFindings: 8,
      openCorrelationGroups: 1,
      slaBreachedFindings: 1,
      riskFormula: 'min(100, CVSS_base * 6.5 * criticality_weight * exposure_factor * exploit_factor)',
      simulated: true,
    }),
    'GET /security/incidents': json([sampleIncident]),
    'GET /security/incidents/11': json(sampleIncidentDetail),
    'POST /security/incidents/11/tasks/201/complete': json({
      incident: sampleIncident,
      tasks: [{ ...sampleIncidentDetail.tasks[0], status: 'COMPLETED' }],
    }),
    'GET /security/vulnerabilities/sources': json([
      {
        id: 1,
        name: 'Production Trivy Scanner',
        format: 'TRIVY_JSON',
        scannerName: 'Trivy',
        tokenPrefix: 'secsrc_ab12',
        enabled: true,
        lastIngestedAt: '2026-11-04T08:00:00Z',
      },
    ]),
    'POST /security/vulnerabilities/sources': json({
      source: {
        id: 2,
        name: 'GitHub SARIF',
        format: 'SARIF',
        scannerName: 'CodeQL',
        tokenPrefix: 'secsrc_cd34',
        enabled: true,
        lastIngestedAt: null,
      },
      rawToken: 'secsrc_cd34_one_time_secret_token_999',
    }),
    'GET /security/vulnerabilities/findings': json([
      {
        id: 301,
        sourceId: 1,
        cveId: 'CVE-2026-4101',
        cweId: 'CWE-125',
        title: 'OpenSSL TLS heartbeat buffer over-read',
        scanner: 'Trivy',
        assetName: 'payroll-app-01',
        ciId: 12,
        serviceId: 3,
        packageName: 'openssl',
        installedVersion: '3.0.1',
        fixedVersion: '3.0.15',
        cvssBase: 9.8,
        cvssVector: 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H',
        assetCriticality: 'CRITICAL',
        internetExposed: true,
        exploitKnown: true,
        riskScore: 96.5,
        riskBand: 'CRITICAL',
        slaDays: 7,
        status: 'OPEN',
        acceptedUntil: null,
        acceptanceReason: null,
        firstSeenAt: '2026-11-01T00:00:00Z',
        lastSeenAt: '2026-11-04T08:00:00Z',
        simulated: true,
      },
    ]),
    'POST /security/vulnerabilities/findings/301/accept-risk': json({
      id: 301,
      status: 'ACCEPTED',
    }),
    'GET /security/correlation-groups': json([
      {
        id: 41,
        groupKey: 'ASSET:payroll-app-01',
        title: 'Multi-signal security correlation on payroll-app-01',
        targetAsset: 'payroll-app-01',
        targetUser: 'payroll-svc@nexus360.test',
        severity: 'CRITICAL',
        status: 'OPEN',
        signalSummary: { alerts: 2, criticalFindings: 1, failedLoginBursts: 1 },
        securityIncidentId: null,
        simulated: true,
        createdAt: '2026-11-04T08:15:00Z',
      },
    ]),
    'POST /security/correlation-groups/41/raise-incident': json(sampleIncidentDetail),
    'GET /security/templates': json([
      {
        id: 1,
        code: 'TPL-COMPROMISED-ACCOUNT',
        name: 'Compromised Account Response',
        category: 'UNAUTHORISED_ACCESS',
        description: 'Contain account, rotate credentials, audit session logs and verify MFA recovery',
        tasks: [{ title: 'Disable account sessions' }],
        recommendedPlaybookCode: 'PB-DISABLE-ACCOUNT',
      },
    ]),
  });
}

describe('SecurityPage', () => {
  it('renders the risk prioritisation formula and KPIs, and hides write controls from AUDITOR', async () => {
    setupRoutes();
    renderWithProviders(<SecurityPage />, {
      route: '/security',
      auth: makeAuth(makeUser('AUDITOR')),
    });

    expect(await screen.findByText(/CVSS_base \* 6\.5 \* criticality_weight/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Declare Security Incident/i })).not.toBeInTheDocument();
  });

  it('displays security incidents, IOCs and response tasks and lets SECOPS analysts complete tasks', async () => {
    setupRoutes();
    const user = userEvent.setup();
    renderWithProviders(<SecurityPage />, {
      route: '/security?tab=incidents',
      auth: makeAuth(makeUser('SECURITY_ANALYST')),
    });

    expect(await screen.findByText('Revoke active refresh tokens and lock compromised account')).toBeInTheDocument();
    expect(screen.getByText(/198\.51\.100\.42/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Complete/i }));
    await waitFor(() => expect(last('POST', '/security/incidents/11/tasks/201/complete')).toBeDefined());
  });

  it('shows prioritised vulnerability findings, creates a scanner source showing rawToken once, and raises an incident from a correlation group', async () => {
    setupRoutes();
    const user = userEvent.setup();
    renderWithProviders(<SecurityPage />, {
      route: '/security?tab=findings',
      auth: makeAuth(makeUser('SECURITY_ANALYST')),
    });

    expect(await screen.findByText('96.5')).toBeInTheDocument();
    expect(screen.getByText('OpenSSL TLS heartbeat buffer over-read', { exact: false })).toBeInTheDocument();

    await user.type(screen.getByLabelText('Source name'), 'GitHub SARIF');
    await user.click(screen.getByRole('button', { name: /Create Source/i }));
    expect(await screen.findByText('secsrc_cd34_one_time_secret_token_999')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: /Security Correlation Groups/i }));
    expect(await screen.findByText('Multi-signal security correlation on payroll-app-01')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Raise Security Incident/i }));
    await waitFor(() => expect(last('POST', '/security/correlation-groups/41/raise-incident')).toBeDefined());
  });
});
