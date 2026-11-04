/** Test data for the workflow screens (imported by *.test.tsx only). */
import type { WorkflowDefinition, WorkflowDto, WorkflowExecutionDto, WorkflowMeta, WorkflowTemplate } from '../../lib/types';

export const workflowMeta: WorkflowMeta = {
  triggers: ['INCIDENT_CREATED', 'INCIDENT_UPDATED', 'PRIORITY_CHANGED', 'SLA_BREACHED', 'SCHEDULED'],
  fields: [
    { field: 'priority', label: 'Priority', values: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] },
    { field: 'status', label: 'Status', values: ['NEW', 'ASSIGNED', 'IN_PROGRESS', 'PENDING', 'RESOLVED', 'CLOSED', 'REOPENED'] },
    { field: 'serviceCode', label: 'Service code', values: null },
    { field: 'slaRemainingMinutes', label: 'Resolution SLA remaining (minutes)', values: null },
  ],
  operators: ['EQUALS', 'NOT_EQUALS', 'IN', 'CONTAINS', 'GREATER_THAN', 'LESS_THAN'],
  actions: [
    { type: 'ASSIGN_DEPARTMENT', label: 'Assign to department', params: [{ key: 'departmentCode', label: 'Department code', required: true, options: null }] },
    { type: 'SET_PRIORITY', label: 'Set priority', params: [{ key: 'priority', label: 'Priority', required: true, options: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] }] },
    {
      type: 'NOTIFY_DEPARTMENT',
      label: 'Send notification to department',
      params: [
        { key: 'departmentCode', label: 'Department code', required: false, options: null },
        { key: 'message', label: 'Message', required: false, options: null },
      ],
    },
    { type: 'NOTIFY_ASSIGNEE', label: 'Send notification to assignee', params: [{ key: 'message', label: 'Message', required: false, options: null }] },
    { type: 'DECLARE_MAJOR', label: 'Declare major incident', params: [] },
    { type: 'START_RUNBOOK', label: 'Trigger approved runbook', params: [{ key: 'runbookId', label: 'Runbook id', required: true, options: null }] },
  ],
};

export const payrollDefinition: WorkflowDefinition = {
  conditions: {
    match: 'ALL',
    rules: [
      { field: 'priority', operator: 'EQUALS', value: 'CRITICAL' },
      { field: 'serviceCode', operator: 'EQUALS', value: 'PAYROLL' },
    ],
  },
  actions: [
    { type: 'START_RUNBOOK', params: { runbookId: '4' } },
    { type: 'NOTIFY_DEPARTMENT', params: { message: 'Payroll incident {{number}}' } },
  ],
  elseActions: [{ type: 'ASSIGN_DEPARTMENT', params: { departmentCode: 'IT-SD' } }],
  schedule: null,
  layout: null,
};

export const payrollWorkflow: WorkflowDto = {
  id: 1,
  name: 'Payroll incident – auto resolution',
  description: 'Critical payroll incidents run the payroll runbook.',
  triggerEvent: 'INCIDENT_CREATED',
  active: true,
  currentVersion: 3,
  maxExecutionsPerHour: 60,
  maxRetries: 3,
  retryBackoffSeconds: 60,
  definition: payrollDefinition,
  executionsLast24h: 14,
  failuresLast24h: 1,
  lastExecutedAt: '2026-11-03T09:50:00Z',
  createdByName: 'Priya Raman',
  updatedAt: '2026-11-02T10:00:00Z',
  demo: true,
};

export const reminderWorkflow: WorkflowDto = {
  ...payrollWorkflow,
  id: 2,
  name: 'Stale pending reminder',
  description: null,
  triggerEvent: 'SCHEDULED',
  active: false,
  currentVersion: 1,
  definition: {
    conditions: { match: 'ALL', rules: [{ field: 'status', operator: 'EQUALS', value: 'PENDING' }] },
    actions: [{ type: 'NOTIFY_ASSIGNEE', params: { message: 'Still pending' } }],
    elseActions: [],
    schedule: { intervalMinutes: 240 },
    layout: null,
  },
  executionsLast24h: 0,
  failuresLast24h: 0,
  lastExecutedAt: null,
  demo: false,
};

export const templates: WorkflowTemplate[] = [
  {
    key: 'payroll-auto-resolution',
    name: 'Payroll incident – auto resolution',
    description: 'Runs the payroll recovery runbook.',
    triggerEvent: 'INCIDENT_CREATED',
    definition: payrollDefinition,
  },
  {
    key: 'stale-pending-reminder',
    name: 'Stale pending reminder',
    description: 'Reminds assignees every four hours.',
    triggerEvent: 'SCHEDULED',
    definition: reminderWorkflow.definition,
  },
];

export const failedRun: WorkflowExecutionDto = {
  id: 501,
  workflowId: 1,
  workflowName: payrollWorkflow.name,
  workflowVersion: 3,
  triggerEvent: 'INCIDENT_CREATED',
  incidentId: 46,
  incidentNumber: 'INC-7846',
  status: 'FAILED',
  attempt: 2,
  nextAttemptAt: null,
  errorMessage: 'Runbook 4 requires approval',
  steps: [{ action: 'START_RUNBOOK', status: 'FAILED', message: 'Runbook 4 requires approval', at: '2026-11-03T09:50:01Z' }],
  startedAt: '2026-11-03T09:50:00Z',
  finishedAt: '2026-11-03T09:50:01Z',
  branch: 'YES',
};

export const succeededRun: WorkflowExecutionDto = {
  ...failedRun,
  id: 500,
  incidentId: 45,
  incidentNumber: 'INC-7845',
  status: 'SUCCEEDED',
  attempt: 1,
  errorMessage: null,
  steps: [{ action: 'ASSIGN_DEPARTMENT', status: 'SUCCEEDED', message: 'Assigned to IT Service Desk', at: '2026-11-03T09:00:01Z' }],
  startedAt: '2026-11-03T09:00:00Z',
  finishedAt: '2026-11-03T09:00:00.4Z',
  branch: 'NO',
};

export const runbooks = [
  {
    id: 4,
    title: 'Payroll service restart',
    description: null,
    serviceId: 3,
    serviceName: 'Payroll',
    categories: [],
    prerequisites: [],
    steps: [],
    requiresApproval: true,
    estimatedMinutes: 15,
    active: true,
    authorName: 'Priya',
    executionCount: 2,
  },
];

export const departments = [
  { id: 1, code: 'IT-SD', name: 'IT Service Desk', description: null, managerId: null, managerName: null, email: null, memberCount: 6, demo: true },
  { id: 2, code: 'PAY', name: 'Payroll Team', description: null, managerId: null, managerName: null, email: null, memberCount: 3, demo: true },
];

export function page<T>(content: T[]) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 };
}
