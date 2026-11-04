import { describe, expect, it } from 'vitest';
import { toFormData } from '../pages/catalog/RequestItemModal';
import { buildCatalogFormSchema, changePasswordSchema, incidentCreateSchema, loginSchema, passwordPolicy, resolveSchema } from './schemas';
import type { FormField } from './types';

const validIncident = { title: 'Payroll portal is down', description: '', serviceId: '', impact: '' as const, urgency: '' as const, affectedUsers: 1 };

describe('incident create schema', () => {
  it('accepts a minimal valid incident with Auto impact/urgency', () => {
    expect(incidentCreateSchema.safeParse(validIncident).success).toBe(true);
  });

  it('requires a title of 5–200 characters', () => {
    const short = incidentCreateSchema.safeParse({ ...validIncident, title: 'VPN' });
    expect(short.success).toBe(false);
    expect(short.error?.issues[0].message).toBe('Title must be at least 5 characters');
    expect(incidentCreateSchema.safeParse({ ...validIncident, title: 'x'.repeat(201) }).success).toBe(false);
    expect(incidentCreateSchema.safeParse({ ...validIncident, title: '   abcd   ' }).success).toBe(false);
  });

  it('limits the description to 5000 characters', () => {
    expect(incidentCreateSchema.safeParse({ ...validIncident, description: 'd'.repeat(5000) }).success).toBe(true);
    expect(incidentCreateSchema.safeParse({ ...validIncident, description: 'd'.repeat(5001) }).success).toBe(false);
  });

  it('validates impact/urgency enums and affected users', () => {
    expect(incidentCreateSchema.safeParse({ ...validIncident, impact: 'HIGH', urgency: 'LOW' }).success).toBe(true);
    expect(incidentCreateSchema.safeParse({ ...validIncident, impact: 'SEVERE' }).success).toBe(false);
    expect(incidentCreateSchema.safeParse({ ...validIncident, affectedUsers: 0 }).success).toBe(false);
    expect(incidentCreateSchema.safeParse({ ...validIncident, affectedUsers: Number.NaN }).success).toBe(false);
  });
});

describe('login schema', () => {
  it('requires a valid email and a password', () => {
    expect(loginSchema.safeParse({ email: 'ada@nexus360.test', password: 'x' }).success).toBe(true);
    const result = loginSchema.safeParse({ email: 'not-an-email', password: '' });
    expect(result.success).toBe(false);
    const messages = result.error?.issues.map((issue) => issue.message);
    expect(messages).toContain('Enter a valid email address');
    expect(messages).toContain('Password is required');
  });
});

describe('password policy', () => {
  it('requires at least 10 characters with letters and digits', () => {
    expect(passwordPolicy.safeParse('Sup3rSecret').success).toBe(true);
    expect(passwordPolicy.safeParse('short1').error?.issues[0].message).toBe('Password must be at least 10 characters');
    expect(passwordPolicy.safeParse('onlyletterspassword').error?.issues[0].message).toBe('Password must contain at least one digit');
    expect(passwordPolicy.safeParse('1234567890123').error?.issues[0].message).toBe('Password must contain at least one letter');
  });

  it('checks confirmation and reuse in the change-password form', () => {
    const mismatch = changePasswordSchema.safeParse({ currentPassword: 'OldPassw0rd', newPassword: 'NewPassw0rd1', confirmPassword: 'NewPassw0rd2' });
    expect(mismatch.error?.issues[0]).toMatchObject({ path: ['confirmPassword'], message: 'Passwords do not match' });
    const reuse = changePasswordSchema.safeParse({ currentPassword: 'SamePassw0rd', newPassword: 'SamePassw0rd', confirmPassword: 'SamePassw0rd' });
    expect(reuse.error?.issues[0].path).toEqual(['newPassword']);
  });
});

describe('resolve schema', () => {
  it('requires a resolution code and notes', () => {
    const result = resolveSchema.safeParse({ resolutionCode: '', resolutionNotes: '', rootCause: '', resolutionArticleId: '' });
    expect(result.success).toBe(false);
    expect(resolveSchema.safeParse({ resolutionCode: 'FIXED', resolutionNotes: 'Restarted the pool', rootCause: '', resolutionArticleId: '12' }).success).toBe(true);
  });
});

describe('dynamic catalogue form schema', () => {
  const fields: FormField[] = [
    { key: 'system', label: 'System', type: 'select', required: true, options: ['SAP', 'Workday'] },
    { key: 'justification', label: 'Justification', type: 'textarea', required: true },
    { key: 'seats', label: 'Seats', type: 'number', required: false },
    { key: 'startDate', label: 'Start date', type: 'date', required: true },
    { key: 'policyAccepted', label: 'Policy accepted', type: 'checkbox', required: true },
    { key: 'notes', label: 'Notes', type: 'text', required: false },
  ];
  const schema = buildCatalogFormSchema(fields);
  const valid = { system: 'SAP', justification: 'Need payroll access', seats: '', startDate: '2026-11-10', policyAccepted: true, notes: '' };

  it('accepts a complete submission and treats empty optional numbers as missing', () => {
    const result = schema.safeParse(valid);
    expect(result.success).toBe(true);
    expect(result.data?.seats).toBeUndefined();
  });

  it('reports each required field with its label', () => {
    const result = schema.safeParse({ system: '', justification: ' ', seats: '', startDate: '', policyAccepted: false, notes: '' });
    expect(result.success).toBe(false);
    const messages = result.error?.issues.map((issue) => issue.message) ?? [];
    expect(messages).toEqual(
      expect.arrayContaining(['System is required', 'Justification is required', 'Start date is required', 'Policy accepted must be confirmed']),
    );
  });

  it('rejects options outside the select list, invalid numbers and dates', () => {
    expect(schema.safeParse({ ...valid, system: 'Oracle' }).success).toBe(false);
    expect(schema.safeParse({ ...valid, seats: 'many' }).success).toBe(false);
    expect(schema.safeParse({ ...valid, startDate: '10/11/2026' }).success).toBe(false);
    expect(schema.safeParse({ ...valid, seats: 3 }).data?.seats).toBe(3);
  });

  it('builds the request formData with only provided values', () => {
    expect(toFormData(fields, { ...valid, seats: Number.NaN, notes: '  ' })).toEqual({
      system: 'SAP',
      justification: 'Need payroll access',
      startDate: '2026-11-10',
      policyAccepted: true,
    });
  });
});
