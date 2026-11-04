import { describe, expect, it } from 'vitest';
import { blockedDependencyIds, formatAvailability, type ServiceWithExtras } from '../services/serviceModel';
import { hrPortal, payroll, payrollDb } from '../services/fixtures.test-utils';
import { makeApproval, makeRequest, powerBi } from './fixtures.test-utils';
import { buildFormData, fieldKeyFromServerMessage, requestPermissions, requestProgressIndex } from './requestModel';

describe('requestPermissions', () => {
  const agent = { id: 9, role: 'SUPPORT_AGENT' as const };
  const requester = { id: 21, role: 'EMPLOYEE' as const };

  it('gives staff assign/start/fulfil on an approved request and internal notes', () => {
    expect(requestPermissions(makeRequest({ status: 'APPROVED' }), agent, true)).toMatchObject({
      assign: true,
      start: true,
      fulfil: true,
      cancel: true,
      comment: true,
      internalNotes: true,
      decide: false,
    });
  });

  it('lets the requester cancel and comment, nothing else', () => {
    expect(requestPermissions(makeRequest({ status: 'IN_FULFILLMENT' }), requester, false)).toEqual({
      assign: false,
      start: false,
      fulfil: false,
      cancel: true,
      comment: true,
      internalNotes: false,
      decide: false,
    });
  });

  it('allows no actions on final requests and keeps auditors read-only', () => {
    const fulfilled = requestPermissions(makeRequest({ status: 'FULFILLED' }), agent, true);
    expect(fulfilled).toMatchObject({ assign: false, start: false, fulfil: false, cancel: false });
    expect(requestPermissions(makeRequest(), { id: 5, role: 'AUDITOR' }, false)).toMatchObject({ comment: false, cancel: false });
  });

  it('routes the approval decision to the approver role (system admin decides all)', () => {
    const pending = makeRequest({ status: 'PENDING_APPROVAL', approval: makeApproval({ approverRole: 'IT_MANAGER' }) });
    expect(requestPermissions(pending, { id: 3, role: 'IT_MANAGER' }, true).decide).toBe(true);
    expect(requestPermissions(pending, { id: 4, role: 'INCIDENT_MANAGER' }, true).decide).toBe(false);
    expect(requestPermissions(pending, { id: 1, role: 'SYSTEM_ADMIN' }, true).decide).toBe(true);
  });

  it('maps statuses onto the progress stepper', () => {
    expect(['PENDING_APPROVAL', 'APPROVED', 'IN_FULFILLMENT', 'FULFILLED'].map((status) => requestProgressIndex(status as never))).toEqual([0, 1, 2, 3]);
  });
});

describe('request form helpers', () => {
  it('finds the field named in an API validation message', () => {
    expect(fieldKeyFromServerMessage(powerBi.formFields, "'Cost centre' is required")).toBe('costCentre');
    expect(fieldKeyFromServerMessage(powerBi.formFields, "'Workspace' must be one of [Finance, HR]")).toBe('workspace');
    expect(fieldKeyFromServerMessage(powerBi.formFields, 'Catalogue item is not available')).toBeNull();
  });

  it('keeps only provided values, trimmed', () => {
    expect(buildFormData(powerBi.formFields, { costCentre: '  FIN-01 ', workspace: '' })).toEqual({ costCentre: 'FIN-01' });
  });
});

describe('service helpers', () => {
  it('blocks the service itself and everything that depends on it, transitively', () => {
    const services = [payroll, payrollDb, hrPortal] as ServiceWithExtras[];
    // HR Portal depends on Payroll, so neither may become a dependency of Payroll.
    expect([...blockedDependencyIds(services, 1)].sort()).toEqual([1, 3]);
    // Payroll and (through it) HR Portal depend on Payroll DB.
    expect([...blockedDependencyIds(services, 2)].sort()).toEqual([1, 2, 3]);
    expect(blockedDependencyIds(services, null).size).toBe(0);
  });

  it('formats availability without trailing zeros', () => {
    expect(formatAvailability(99.9)).toBe('99.9%');
    expect(formatAvailability(97.254)).toBe('97.25%');
    expect(formatAvailability(100)).toBe('100%');
    expect(formatAvailability(null)).toBe('—');
  });
});
