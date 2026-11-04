import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { pageOf } from '../../test/itsmFixtures';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import AgentsPage from './AgentsPage';

const last = (method: string, url: string) =>
  [...requests].reverse().find((r) => r.method === method && r.url === url);

const sampleRunSummary = {
  id: 21,
  runType: 'INVESTIGATE_INCIDENT',
  status: 'SUCCEEDED',
  subjectType: 'INCIDENT',
  subjectId: 101,
  subjectNumber: 'INC-101',
  subjectTitle: 'Payroll DB connection pool saturation',
  question: 'Investigate root cause and propose recovery',
  summary: 'Deterministic fallback synthesis: Payroll DB pool saturated following deployment DEP-88.',
  source: 'FALLBACK',
  confidence: 0.78,
  error: null,
  cancelRequested: false,
  simulated: false,
  stepCount: 5,
  actionCount: 1,
  requestedById: 1,
  requestedByName: 'Aarav Mehta',
  requestedByRole: 'SYSTEM_ADMIN',
  startedAt: '2026-11-04T10:00:00Z',
  finishedAt: '2026-11-04T10:00:02Z',
  durationMs: 1420,
  createdAt: '2026-11-04T10:00:00Z',
};

const sampleRunDetail = {
  run: sampleRunSummary,
  output: { summary: sampleRunSummary.summary },
  agentVersions: { INCIDENT: '1.0', RESOLUTION: '1.0' },
  steps: [
    {
      id: 301,
      seq: 1,
      stage: 1,
      agentCode: 'INCIDENT',
      agentVersion: '1.0',
      status: 'SUCCEEDED',
      source: 'FALLBACK',
      fallbackReason: 'LLM disabled in test environment',
      confidence: 0.8,
      input: {},
      output: { summary: 'Triaged critical database incident INC-101 on Payroll.' },
      evidence: [{ type: 'INCIDENT', id: 101, label: 'INC-101' }],
      llmTurns: 0,
      tokensIn: 0,
      tokensOut: 0,
      error: null,
      startedAt: '2026-11-04T10:00:00Z',
      finishedAt: '2026-11-04T10:00:01Z',
      durationMs: 410,
      toolCalls: [
        {
          id: 401,
          seq: 1,
          tool: 'get_incident',
          args: { incidentId: '101' },
          status: 'OK',
          result: { number: 'INC-101' },
          resultSizeBytes: 128,
          truncated: false,
          error: null,
          requestedBy: 'PLAN',
          durationMs: 12,
          createdAt: '2026-11-04T10:00:00Z',
        },
      ],
    },
  ],
  actions: [
    {
      id: 501,
      runId: 21,
      stepId: 301,
      agentCode: 'RESOLUTION',
      kind: 'ADD_INCIDENT_NOTE',
      targetType: 'INCIDENT',
      targetId: 101,
      params: { note: 'Recommended connection pool recycling' },
      title: 'Post investigation summary note to INC-101',
      rationale: 'Share correlated root cause evidence with the incident commander',
      requiresApproval: true,
      status: 'PROPOSED',
      approvalId: null,
      proposedByName: null,
      proposedAt: null,
      decidedByName: null,
      decidedAt: null,
      decisionComment: null,
      result: null,
      link: '/incidents/101',
      createdAt: '2026-11-04T10:00:02Z',
    },
  ],
  feedback: [],
  myFeedback: null,
};

function setupRoutes() {
  mockApi({
    'GET /agents': json([
      {
        code: 'INCIDENT',
        name: 'Incident Triage Agent',
        description: 'Classifies incident impact and gathers SLA & service context',
        version: '1.0',
        tools: ['get_incident', 'get_sla_prediction'],
        outputSchema: {},
        enabled: true,
        allowedTools: ['get_incident', 'get_sla_prediction'],
        maxSteps: 6,
        timeoutSeconds: 20,
        telemetryMinutes: 60,
        historyDays: 30,
        allowedRoles: ['SYSTEM_ADMIN', 'SUPPORT_AGENT'],
        approvalActionKinds: ['ADD_INCIDENT_NOTE'],
        canRun: true,
      },
    ]),
    'GET /agents/tools': json([
      {
        name: 'get_incident',
        description: 'Reads an incident by ID as the requesting user',
        arguments: { incidentId: 'number' },
        allowedRoles: ['SYSTEM_ADMIN', 'SUPPORT_AGENT'],
      },
    ]),
    'GET /agents/policies': json([
      {
        agentCode: 'INCIDENT',
        enabled: true,
        allowedTools: ['get_incident', 'get_sla_prediction'],
        maxSteps: 6,
        timeoutSeconds: 20,
        telemetryMinutes: 60,
        historyDays: 30,
        allowedRoles: ['SYSTEM_ADMIN', 'SUPPORT_AGENT'],
        approvalActionKinds: ['ADD_INCIDENT_NOTE'],
        updatedByName: 'System',
        updatedAt: '2026-11-04T09:00:00Z',
      },
    ]),
    'GET /agents/runs': json(pageOf([sampleRunSummary])),
    'GET /agents/runs/21': json(sampleRunDetail),
    'POST /agents/runs/21/actions/501/propose': json({
      ...sampleRunDetail.actions[0],
      status: 'PENDING_APPROVAL',
      approvalId: 88,
    }),
    'GET /agents/governance': json({
      days: 30,
      from: '2026-10-05T00:00:00Z',
      to: '2026-11-04T00:00:00Z',
      totalRuns: 8,
      succeededRuns: 8,
      failedRuns: 0,
      cancelledRuns: 0,
      timedOutRuns: 0,
      llmSteps: 0,
      fallbackSteps: 24,
      totalTokensIn: 0,
      totalTokensOut: 0,
      toolCalls: 42,
      deniedToolCalls: 0,
      proposedActions: 3,
      appliedActions: 2,
      rejectedActions: 0,
      helpfulCount: 5,
      notHelpfulCount: 0,
      lastEvalScore: 1.0,
      lastEvalAt: '2026-11-04T09:30:00Z',
      byRunType: { INVESTIGATE_INCIDENT: 6 },
      byAgent: {},
      agents: [],
      policies: [],
    }),
    'GET /agents/evaluations/cases': json([
      {
        id: 1,
        name: 'Payroll DB saturation investigation',
        description: 'Verifies multi-agent fan-out and resolution synthesis',
        agentCode: 'RESOLUTION',
        runType: 'INVESTIGATE_INCIDENT',
        incidentId: 101,
        changeId: null,
        question: 'Investigate INC-101',
        expected: {},
        enabled: true,
        demo: true,
        createdByName: 'System',
        createdAt: '2026-11-04T08:00:00Z',
        updatedAt: '2026-11-04T08:00:00Z',
      },
    ]),
    'GET /agents/evaluations/runs': json([]),
    'GET /incidents': json(pageOf([{ id: 101, number: 'INC-101', title: 'Payroll DB connection pool saturation', priority: 'CRITICAL', status: 'IN_PROGRESS' }])),
    'GET /changes': json(pageOf([{ id: 201, number: 'CHG-201', title: 'Upgrade Payroll schema', type: 'NORMAL', status: 'ASSESSMENT' }])),
  });
}

describe('AgentsPage', () => {
  it('renders the run timeline with FALLBACK labels, read-only tool calls, and submits proposed actions for approval', async () => {
    setupRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AgentsPage />, {
      route: '/agents',
      auth: makeAuth(makeUser('SUPPORT_AGENT')),
    });

    expect(await screen.findByText('Post investigation summary note to INC-101')).toBeInTheDocument();
    expect(screen.getByText(/get_incident/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Submit for Approval' }));
    await waitFor(() => expect(last('POST', '/agents/runs/21/actions/501/propose')).toBeDefined());
  });

  it('displays governance metrics and policies', async () => {
    setupRoutes();
    renderWithProviders(<AgentsPage />, {
      route: '/agents?tab=governance',
      auth: makeAuth(makeUser('SYSTEM_ADMIN')),
    });

    expect(await screen.findByText('INCIDENT')).toBeInTheDocument();
    expect(screen.getByText('Per-Agent Governance Policies')).toBeInTheDocument();
  });
});
