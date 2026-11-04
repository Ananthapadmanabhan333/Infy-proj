import type { ServiceDetail, ServiceDto } from '../../lib/types';

// Test fixtures shared by the Services tests (imported by *.test.tsx only).

export function makeService(overrides: Partial<ServiceDto> & { dependsOnIds?: number[] } = {}): ServiceDto {
  return {
    id: 1,
    code: 'PAYROLL',
    name: 'Payroll',
    description: 'Monthly salary processing',
    departmentId: 2,
    departmentName: 'Application Support',
    ownerId: 9,
    ownerName: 'Riya Varma',
    tier: 1,
    status: 'OPERATIONAL',
    statusReason: null,
    availability30d: 99.9,
    openIncidents: 3,
    criticalOpen: 1,
    maintenance: false,
    keywords: ['payroll', 'salary'],
    demo: false,
    ...overrides,
  };
}

export const payroll = makeService({ status: 'DEGRADED', statusReason: 'Dependency Payroll DB is in outage', dependsOnIds: [2] });
export const payrollDb = makeService({ id: 2, code: 'PAYROLL-DB', name: 'Payroll DB', status: 'OUTAGE', tier: 1, availability30d: 97.2, departmentId: 4, departmentName: 'Infrastructure', ownerId: null, ownerName: null, criticalOpen: 2 });
export const hrPortal = makeService({ id: 3, code: 'HR-PORTAL', name: 'HR Portal', tier: 2, availability30d: 99.6, criticalOpen: 0, openIncidents: 0, departmentId: 5, departmentName: 'HR Systems', dependsOnIds: [1] });

export function makeDetail(overrides: Partial<ServiceDetail> = {}): ServiceDetail {
  return {
    ...payroll,
    recentIncidents: [],
    slaCompliance30d: 96.4,
    mttrMinutes30d: 28,
    dependencies: [{ id: 2, code: 'PAYROLL-DB', name: 'Payroll DB', status: 'OUTAGE' }],
    dependents: [{ id: 3, code: 'HR-PORTAL', name: 'HR Portal', status: 'OPERATIONAL' }],
    availabilityHistory: [
      { date: '2026-11-01', availability: 100 },
      { date: '2026-11-02', availability: 98.5 },
      { date: '2026-11-03', availability: 99.9 },
    ],
    ...overrides,
  };
}
