import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import RemediationPage from './RemediationPage';

const last = (method: string, url: string) =>
  [...requests].reverse().find((r) => r.method === method && r.url === url);

const samplePlaybook = {
  id: 1,
  code: 'PB-POOL-RECYCLE',
  name: 'Recycle saturated Payroll DB connection pool',
  description: 'Drains idle DB connections, rolling restarts app pods and verifies p95 latency recovery',
  targetEnvironment: 'PRODUCTION',
  preApprovedAlertTypes: ['DB_POOL_SATURATED'],
  maxBlastRadius: 5,
  rateLimitPerHour: 4,
  requireLinkedRecord: true,
  activeVersion: 1,
  enabled: true,
  versions: [
    {
      id: 10,
      playbookId: 1,
      version: 1,
      status: 'IN_REVIEW',
      triggerKinds: ['MANUAL', 'AGENT_RECOMMENDATION'],
      parametersSchema: {},
      steps: [
        {
          stepOrder: 1,
          name: 'Drain stale pool connections',
          kind: 'ACTION',
          systemId: 1,
          templateId: 'k8s.rollout_restart',
          parameters: { deployment: 'payroll-api' },
          rollbackTemplateId: 'k8s.rollout_undo',
          rollbackParameters: { deployment: 'payroll-api' },
          verifyConfig: {},
          timeoutSeconds: 30,
          maxRetries: 2,
        },
      ],
      recoveryCriteria: 'p95 < 250ms and error rate < 1%',
      approvalId: 70,
      createdByName: 'Aarav Mehta',
      approvedByName: null,
      approvedAt: null,
      createdAt: '2026-11-04T09:00:00Z',
    },
  ],
};

const sampleRun = {
  id: 55,
  playbookId: 1,
  playbookCode: 'PB-POOL-RECYCLE',
  playbookName: 'Recycle saturated Payroll DB connection pool',
  playbookVersion: 1,
  triggerKind: 'MANUAL',
  status: 'BLOCKED',
  targetKey: 'payroll-db-primary',
  environment: 'PRODUCTION',
  incidentId: 101,
  changeId: null,
  alertId: null,
  policyChecks: [
    { check: 'PLAYBOOK_APPROVED', passed: true, detail: 'Version 1 is APPROVED' },
    { check: 'FREEZE_WINDOW', passed: false, detail: 'Active production change freeze window' },
  ],
  overrideReason: null,
  overriddenByName: null,
  verificationResult: null,
  failureReason: 'Blocked by policy check FREEZE_WINDOW',
  requestedByName: 'Omar Agent',
  simulated: true,
  startedAt: null,
  completedAt: null,
  createdAt: '2026-11-04T10:00:00Z',
};

const sampleRunDetail = {
  run: sampleRun,
  steps: [
    {
      id: 501,
      stepOrder: 1,
      name: 'Drain stale pool connections',
      kind: 'ACTION',
      isRollback: false,
      status: 'ROLLED_BACK',
      requestSummary: { templateId: 'k8s.rollout_restart' },
      responseSummary: { status: 200 },
      attempts: 1,
      durationMs: 120,
      errorMessage: null,
      startedAt: '2026-11-04T10:00:01Z',
      completedAt: '2026-11-04T10:00:02Z',
    },
    {
      id: 502,
      stepOrder: 1,
      name: 'Rollback: Drain stale pool connections',
      kind: 'ACTION',
      isRollback: true,
      status: 'SUCCEEDED',
      requestSummary: { templateId: 'k8s.rollout_undo' },
      responseSummary: { status: 200 },
      attempts: 1,
      durationMs: 95,
      errorMessage: null,
      startedAt: '2026-11-04T10:00:03Z',
      completedAt: '2026-11-04T10:00:04Z',
    },
  ],
  auditEntries: [],
};

function setupRoutes() {
  mockApi({
    'GET /remediation/meta': json({
      connectorKinds: ['HTTP_WEBHOOK', 'ANSIBLE_AWX', 'KUBERNETES', 'AWS_SSM', 'SIMULATED'],
      stepKinds: ['ACTION', 'VERIFY', 'WAIT', 'MANUAL_CONFIRM', 'NOTIFY'],
      triggerKinds: ['MANUAL', 'AGENT_RECOMMENDATION', 'WORKFLOW', 'ALERT_RULE'],
      runStatuses: ['SUCCEEDED', 'FAILED', 'ROLLED_BACK', 'BLOCKED'],
      versionStatuses: ['DRAFT', 'IN_REVIEW', 'APPROVED', 'RETIRED'],
    }),
    'GET /remediation/playbooks': json([samplePlaybook]),
    'POST /remediation/playbooks/1/versions/1/approve': json({
      ...samplePlaybook.versions[0],
      status: 'APPROVED',
    }),
    'GET /remediation/runs': json([sampleRun]),
    'GET /remediation/runs/55': json(sampleRunDetail),
    'POST /remediation/runs/55/override': json({
      ...sampleRunDetail,
      run: { ...sampleRun, status: 'SUCCEEDED', overrideReason: 'Emergency payroll outage override' },
    }),
    'GET /remediation/systems': json([
      {
        id: 1,
        name: 'Simulated Kubernetes Executor',
        kind: 'SIMULATED',
        endpointUrl: null,
        credentialScope: 'Namespace: payroll-prod',
        credentialsConfigured: true,
        actionTemplates: [
          {
            id: 'k8s.rollout_restart',
            name: 'Rollout restart deployment',
            description: 'Restarts pods in a scoped deployment',
            parametersSchema: {},
            targetScope: 'payroll-prod',
          },
        ],
        status: 'SIMULATED',
        lastTestedAt: '2026-11-04T09:00:00Z',
        lastError: null,
        enabled: true,
      },
    ]),
    'GET /remediation/audit': json([
      {
        id: 1,
        eventType: 'PLAYBOOK_APPROVED',
        runId: 55,
        playbookId: 1,
        actorName: 'Priya Raman',
        payload: { version: 1 },
        createdAt: '2026-11-04T09:30:00Z',
        prevHash: '0000000000000000000000000000000000000000000000000000000000000000',
        hash: 'a94a8fe5ccb19ba61c4c0873d391e987982fbbd3a94a8fe5ccb19ba61c4c0873',
      },
    ]),
    'GET /remediation/audit/verify': json({
      valid: true,
      totalRecords: 1,
      firstBrokenId: null,
      message: 'All 1 hash-chained audit records verified intact.',
    }),
  });
}

describe('RemediationPage', () => {
  it('renders playbooks with allow-listed action templates and lets PLAYBOOK_APPROVERS approve versions', async () => {
    setupRoutes();
    const user = userEvent.setup();
    renderWithProviders(<RemediationPage />, {
      route: '/remediation',
      auth: makeAuth(makeUser('CHANGE_MANAGER')),
    });

    expect(await screen.findByText('Recycle saturated Payroll DB connection pool')).toBeInTheDocument();
    expect(screen.getByText('k8s.rollout_restart')).toBeInTheDocument();
    expect(screen.getByText('k8s.rollout_undo')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Approve Version/i }));
    await waitFor(() => expect(last('POST', '/remediation/playbooks/1/versions/1/approve')).toBeDefined());
  });

  it('shows policy checks, rollback steps and allows PLAYBOOK_APPROVERS to override blocked runs', async () => {
    setupRoutes();
    const user = userEvent.setup();
    renderWithProviders(<RemediationPage />, {
      route: '/remediation?tab=runs',
      auth: makeAuth(makeUser('IT_MANAGER')),
    });

    expect(await screen.findByText('FREEZE_WINDOW')).toBeInTheDocument();
    expect(screen.getByText('Rollback: Drain stale pool connections')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Override reason'), 'Emergency payroll outage override');
    await user.click(screen.getByRole('button', { name: /Override & Execute/i }));
    await waitFor(() =>
      expect(last('POST', '/remediation/runs/55/override')?.body).toEqual({
        reason: 'Emergency payroll outage override',
      }),
    );
  });

  it('verifies the SHA-256 tamper-evident audit hash chain', async () => {
    setupRoutes();
    const user = userEvent.setup();
    renderWithProviders(<RemediationPage />, {
      route: '/remediation?tab=audit',
      auth: makeAuth(makeUser('AUDITOR')),
    });

    expect(await screen.findByText('PLAYBOOK_APPROVED')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Verify hash chain/i }));
    expect(await screen.findByText('Hash Chain Verified Intact')).toBeInTheDocument();
  });
});
