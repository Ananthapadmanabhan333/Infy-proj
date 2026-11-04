import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import FinOpsPage from './FinOpsPage';

const last = (method: string, url: string) =>
  [...requests].reverse().find((r) => r.method === method && r.url === url);

function setupRoutes(status: 'OK' | 'INSUFFICIENT_DATA' = 'OK') {
  mockApi({
    'GET /finops/overview': json({
      status,
      totalBilledCost: 18420.5,
      totalEffectiveCost: 16950.0,
      unallocatedCost: 1120.0,
      potentialMonthlySavings: 2450.0,
      openAnomalies: 1,
      idleResourcesCount: 2,
      byService: [
        { key: 'PAYROLL', name: 'Payroll Service', billedCost: 11200.0, effectiveCost: 10400.0, sharePercent: 60.8 },
      ],
      byCostCenter: [
        { key: 'CC-FIN', name: 'CC-FIN', billedCost: 17300.5, effectiveCost: 15830.0, sharePercent: 93.9 },
      ],
      dailyTrend: [{ date: '2026-11-04', billedCost: 620.0, effectiveCost: 580.0 }],
      simulated: true,
    }),
    'GET /finops/sources': json([
      {
        id: 1,
        name: 'AWS FOCUS 1.0 Billing Export',
        kind: 'FOCUS_CSV',
        providerName: 'AWS',
        status: 'PREPARED',
        lastIngestedAt: '2026-11-04T08:00:00Z',
      },
    ]),
    'POST /finops/ingest': json({
      dryRun: true,
      validRows: 1,
      errorRows: 1,
      totalBilledCost: 420.5,
      totalEffectiveCost: 395.0,
      errors: [{ rowNumber: 3, field: 'BilledCost', message: 'BilledCost must be a non-negative number' }],
      appliedCount: 0,
    }),
    'GET /finops/allocation-rules': json([
      {
        id: 1,
        name: 'Payroll Tag Allocation',
        priority: 10,
        tagKey: 'service',
        tagValue: 'payroll',
        subAccountId: null,
        resourcePattern: null,
        targetServiceId: 3,
        targetDepartmentId: 1,
        targetCostCenter: 'CC-FIN',
        splitPercent: 100,
        enabled: true,
      },
    ]),
    'GET /finops/budgets': json([
      {
        id: 1,
        name: 'CC-FIN Monthly Cloud Budget',
        targetType: 'COST_CENTER',
        targetKey: 'CC-FIN',
        periodMonth: '2026-11',
        amount: 15000,
        spentToDate: 13200,
        utilizationPercent: 88.0,
        forecastedEndOfMonth: 19800,
        triggeredThresholds: [50, 80],
        status: 'WARNING',
      },
    ]),
    'GET /finops/anomalies': json([
      {
        id: 1,
        scopeKey: 'SERVICE:Payroll',
        chargeDate: '2026-11-04',
        actualCost: 1450.0,
        expectedCost: 520.0,
        upperBound: 710.0,
        deviationScore: 4.85,
        status: 'OPEN',
        simulated: true,
      },
    ]),
    'GET /finops/recommendations': json([
      {
        id: 91,
        category: 'RIGHTSIZING',
        resourceId: 'rds-payroll-replica-02',
        resourceName: 'Payroll Analytics Read Replica',
        serviceName: 'Payroll Service',
        title: 'Right-size under-utilised db.r6g.4xlarge instance (avg CPU 6.2%)',
        evidence: { avgCpuPct: 6.2, daysObserved: 14 },
        estimatedMonthlySavings: 1240.0,
        status: 'OPEN',
        changeRequestId: null,
        dismissReason: null,
        simulated: true,
      },
    ]),
    'POST /finops/recommendations/91/accept': json({
      id: 91,
      category: 'RIGHTSIZING',
      resourceId: 'rds-payroll-replica-02',
      resourceName: 'Payroll Analytics Read Replica',
      serviceName: 'Payroll Service',
      title: 'Right-size under-utilised db.r6g.4xlarge instance (avg CPU 6.2%)',
      evidence: { avgCpuPct: 6.2, daysObserved: 14 },
      estimatedMonthlySavings: 1240.0,
      status: 'ACCEPTED',
      changeRequestId: 404,
      dismissReason: null,
      simulated: true,
    }),
  });
}

describe('FinOpsPage', () => {
  it('shows insufficient-data callout instead of fabricated numbers when status is INSUFFICIENT_DATA', async () => {
    setupRoutes('INSUFFICIENT_DATA');
    renderWithProviders(<FinOpsPage />, {
      route: '/finops',
      auth: makeAuth(makeUser('AUDITOR')),
    });

    expect(await screen.findByText(/Insufficient data — no cloud cost records ingested yet/i)).toBeInTheDocument();
  });

  it('renders cost overview, dry-run validation errors and accepts savings recommendations into a draft Change Request', async () => {
    setupRoutes('OK');
    const user = userEvent.setup();
    renderWithProviders(<FinOpsPage />, {
      route: '/finops',
      auth: makeAuth(makeUser('FINOPS_ANALYST')),
    });

    expect(await screen.findByText('$18,420.50')).toBeInTheDocument();
    expect(screen.getByText('Payroll Service')).toBeInTheDocument();

    // Switch to Cost Ingestion and run Dry-Run
    await user.click(screen.getByRole('tab', { name: /Cost Ingestion/i }));
    await user.click(await screen.findByRole('button', { name: /Validate \(Dry-Run\)/i }));
    expect(await screen.findByText(/Row #3 \(BilledCost\): BilledCost must be a non-negative number/)).toBeInTheDocument();

    // Switch to Savings Recommendations and accept
    await user.click(screen.getByRole('tab', { name: /Savings Recommendations/i }));
    expect(await screen.findByText(/Right-size under-utilised db\.r6g\.4xlarge instance/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Accept → Draft Change/i }));
    await waitFor(() => expect(last('POST', '/finops/recommendations/91/accept')).toBeDefined());
  });
});
