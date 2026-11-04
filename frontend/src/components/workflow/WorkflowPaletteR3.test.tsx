import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { WorkflowMeta } from '../../lib/types';
import { actionLabel, emptyDraft, emptyModel, fieldLabel, isNumericField, triggerLabel, validateDraft, valueLabel } from './model';
import { WorkflowPalette } from './WorkflowPalette';

/** Shape of `GET /workflows/meta` after BE-2 (trigger CHANGE_REQUEST_CREATED, condition changeRisk, action REQUEST_APPROVAL). */
const META: WorkflowMeta = {
  triggers: ['INCIDENT_CREATED', 'SERVICE_REQUEST_CREATED', 'CHANGE_REQUEST_CREATED'],
  fields: [
    { field: 'priority', label: 'Priority', values: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] },
    { field: 'changeRisk', label: 'Change risk', values: ['LOW', 'MEDIUM', 'HIGH'] },
  ],
  operators: ['EQUALS', 'NOT_EQUALS'],
  actions: [
    { type: 'NOTIFY_ROLE', label: 'Send notification to role', params: [{ key: 'role', label: 'Role', required: true, options: ['CHANGE_MANAGER'] }] },
    { type: 'REQUEST_APPROVAL', label: 'Request approval', params: [{ key: 'flowCode', label: 'Approval flow', required: true, options: ['CHANGE_NORMAL'] }] },
  ],
};

describe('workflow palette — Release 3 change automation', () => {
  it('offers the CHANGE_REQUEST_CREATED trigger and the REQUEST_APPROVAL action from /workflows/meta', () => {
    render(<WorkflowPalette meta={META} trigger="CHANGE_REQUEST_CREATED" targetBranch="yes" onTargetBranch={vi.fn()} onSetTrigger={vi.fn()} onAddAction={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Change Request/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Request approval/ })).toBeInTheDocument();
    expect(triggerLabel('CHANGE_REQUEST_CREATED')).toBe('Change Request');
    expect(actionLabel('REQUEST_APPROVAL', META)).toBe('Request approval');
    expect(fieldLabel('changeRisk', META)).toBe('Change risk');
  });
});

/** `GET /workflows/meta` after BE-3: trigger ALERT_RECEIVED, alert condition fields and the CREATE_INCIDENT action. */
const ALERT_META: WorkflowMeta = {
  triggers: ['INCIDENT_CREATED', 'CHANGE_REQUEST_CREATED', 'ALERT_RECEIVED'],
  fields: [
    { field: 'alertSeverity', label: 'Alert severity', values: ['CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO'] },
    { field: 'alertSource', label: 'Alert source', values: ['PROMETHEUS', 'GRAFANA', 'CLOUDWATCH', 'GENERIC', 'INTEGRATION', 'MANUAL'] },
    { field: 'alertName', label: 'Alert name (alertname / alarm name)', values: null },
    { field: 'ciName', label: 'Configuration item', values: null },
    { field: 'ciType', label: 'Configuration item type', values: ['APPLICATION', 'DATABASE'] },
    { field: 'occurrenceCount', label: 'Alert occurrences', values: null },
  ],
  operators: ['EQUALS', 'NOT_EQUALS', 'GREATER_THAN'],
  actions: [
    { type: 'NOTIFY_ROLE', label: 'Send notification to role', params: [{ key: 'role', label: 'Role', required: true, options: ['INCIDENT_MANAGER'] }] },
    { type: 'CREATE_INCIDENT', label: 'Create an incident from the alert', params: [{ key: 'impact', label: 'Impact', required: false, options: ['HIGH', 'MEDIUM', 'LOW'] }] },
  ],
};

describe('workflow palette — Release 3 alert automation', () => {
  it('offers the ALERT_RECEIVED trigger with its icon label and the CREATE_INCIDENT action', () => {
    render(<WorkflowPalette meta={ALERT_META} trigger="ALERT_RECEIVED" targetBranch="yes" onTargetBranch={vi.fn()} onSetTrigger={vi.fn()} onAddAction={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Alert Received/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Add Create incident to the Yes branch' })).toBeInTheDocument();
    expect(triggerLabel('ALERT_RECEIVED')).toBe('Alert Received');
    expect(actionLabel('CREATE_INCIDENT', ALERT_META)).toBe('Create incident');
    expect(fieldLabel('alertSeverity', ALERT_META)).toBe('Alert severity');
    expect(isNumericField('occurrenceCount')).toBe(true);
    expect(valueLabel('CRITICAL', 'alertSeverity')).toBe('Critical');
  });

  it('rejects CREATE_INCIDENT outside the ALERT_RECEIVED trigger', () => {
    const model = (trigger: 'ALERT_RECEIVED' | 'INCIDENT_CREATED') => ({
      ...emptyModel(trigger),
      rules: [{ field: 'occurrenceCount' as const, operator: 'GREATER_THAN' as const, value: '5' }],
      yes: [{ uid: 'x1', type: 'CREATE_INCIDENT' as const, params: {} }],
    });
    const draft = (trigger: 'ALERT_RECEIVED' | 'INCIDENT_CREATED') => ({ ...emptyDraft(), name: 'Alert to incident', model: model(trigger) });
    expect(validateDraft(draft('ALERT_RECEIVED'), ALERT_META).filter((issue) => issue.severity === 'error')).toEqual([]);
    expect(validateDraft(draft('INCIDENT_CREATED'), { ...ALERT_META }).some((issue) => /only works with the Alert Received trigger/.test(issue.message))).toBe(true);
  });
});
