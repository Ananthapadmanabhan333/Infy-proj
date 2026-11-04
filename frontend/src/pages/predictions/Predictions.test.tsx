import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { pageOf } from '../../test/itsmFixtures';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import PredictionsPage from './PredictionsPage';

const last = (method: string, url: string) =>
  [...requests].reverse().find((r) => r.method === method && r.url === url);

const sampleModel = {
  id: 10,
  type: 'SLA_BREACH',
  scopeKey: 'GLOBAL',
  version: 1,
  algorithm: 'LOGISTIC_REGRESSION_L2',
  status: 'ACTIVE',
  statusMessage: 'Trained on 180 samples, evaluated on 60 later samples',
  features: ['priority_critical', 'elapsed_share'],
  params: { threshold: 0.5 },
  metrics: {
    test: {
      auc: 0.912,
      brier: 0.084,
      precision: 0.85,
      recall: 0.82,
      f1: 0.835,
      calibration: [
        { bin: 0, lower: 0.0, upper: 0.1, count: 12, meanPredicted: 0.04, observedRate: 0.05 },
      ],
    },
  },
  trainingFrom: '2026-05-01T00:00:00Z',
  trainingTo: '2026-10-31T00:00:00Z',
  cutoffAt: '2026-09-15T00:00:00Z',
  sampleCount: 240,
  positiveCount: 72,
  trainCount: 180,
  testCount: 60,
  minSamples: 200,
  includeSimulated: false,
  simulated: false,
  detectorId: null,
  trainedAt: '2026-11-01T10:00:00Z',
  trainedByName: 'Priya Raman',
  activatedAt: '2026-11-01T10:05:00Z',
  activatedByName: 'Priya Raman',
  retiredAt: null,
  retiredByName: null,
  lastEvaluatedAt: '2026-11-01T10:00:00Z',
  driftStatus: 'DRIFT',
  driftCheckedAt: '2026-11-03T10:00:00Z',
  method: 'L2-regularised logistic regression; probability = sigmoid(intercept + Σ weight × standardised feature)',
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-03T10:00:00Z',
};

function setupRoutes() {
  mockApi({
    'GET /predictions/meta': json({
      types: ['SLA_BREACH', 'INCIDENT_RECURRENCE', 'CAPACITY_RISK', 'SERVICE_HEALTH', 'ANOMALY'],
      statuses: ['TRAINED', 'ACTIVE', 'RETIRED', 'INSUFFICIENT_DATA', 'FAILED'],
      minSamples: 200,
      minPositives: 30,
      minForecastPoints: 100,
      recurrenceWindowDays: 14,
      methods: {
        SLA_BREACH: 'L2-regularised logistic regression',
      },
      slaFeatures: ['priority_critical'],
      recurrenceFeatures: ['past_recurrence_log'],
    }),
    'GET /predictions/models': json([sampleModel]),
    'GET /predictions/models/10/performance': json({
      modelId: 10,
      type: 'SLA_BREACH',
      version: 1,
      status: 'ACTIVE',
      from: '2026-11-01T10:05:00Z',
      to: '2026-11-04T10:00:00Z',
      predictions: 42,
      outcomes: 35,
      pending: 7,
      offlineMetrics: { auc: 0.912, brier: 0.084 },
      liveMetrics: { auc: 0.65, brier: 0.22 },
      overTime: [],
      driftStatus: 'DRIFT',
      driftReasons: ['Live AUC 0.65 is more than 0.1 below the offline AUC 0.912'],
      message: null,
      evaluatedAt: '2026-11-04T10:00:00Z',
    }),
    'POST /predictions/outcomes/reconcile': json({
      checkedAt: '2026-11-04T10:00:00Z',
      evaluated: 40,
      known: 35,
      expired: 0,
      modelsChecked: 1,
      driftWarnings: 1,
    }),
    'GET /incidents': json(pageOf([{ id: 101, number: 'INC-101', title: 'Payroll latency', priority: 'CRITICAL', status: 'IN_PROGRESS' }])),
    'GET /predictions/incidents/101': json({
      incidentId: 101,
      incidentNumber: 'INC-101',
      slaBreach: {
        incidentId: 101,
        incidentNumber: 'INC-101',
        incidentTitle: 'Payroll latency',
        type: 'SLA_BREACH',
        status: 'OK',
        basis: 'MODEL',
        modelId: 10,
        modelVersion: 1,
        algorithm: 'LOGISTIC_REGRESSION_L2',
        predictionId: 501,
        probability: 0.82,
        threshold: 0.5,
        riskLevel: 'HIGH',
        targetAt: '2026-11-04T14:00:00Z',
        features: { priority_critical: 1 },
        contributions: [{ feature: 'priority_critical', value: 1, standardized: 1.5, weight: 0.9, contribution: 1.35 }],
        fallback: null,
        method: 'L2-regularised logistic regression',
        howComputed: 'sigmoid(intercept + Σ weight × standardised feature) = 0.8200',
        message: null,
        simulated: false,
        generatedAt: '2026-11-04T10:00:00Z',
      },
      recurrence: {
        incidentId: 101,
        incidentNumber: 'INC-101',
        incidentTitle: 'Payroll latency',
        type: 'INCIDENT_RECURRENCE',
        status: 'NO_ACTIVE_MODEL',
        basis: 'NONE',
        modelId: null,
        modelVersion: null,
        algorithm: null,
        predictionId: null,
        probability: null,
        threshold: null,
        riskLevel: null,
        targetAt: null,
        features: {},
        contributions: [],
        fallback: null,
        method: 'L2-regularised logistic regression',
        howComputed: null,
        message: 'No ACTIVE INCIDENT_RECURRENCE model',
        simulated: false,
        generatedAt: '2026-11-04T10:00:00Z',
      },
    }),
  });
}

describe('PredictionsPage', () => {
  it('renders models with offline metrics, calibration and drift warnings, and hides admin buttons for auditors', async () => {
    setupRoutes();
    const user = userEvent.setup();
    renderWithProviders(<PredictionsPage />, {
      route: '/predictions',
      auth: makeAuth(makeUser('AUDITOR')),
    });

    expect(await screen.findByText('AUC 0.912')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Train model/i })).not.toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'Inspect' }));
    expect(await screen.findByText(/Live AUC 0.65 is more than 0.1 below the offline AUC 0.912/)).toBeInTheDocument();
  });

  it('shows incident predictions with howComputed and allows prediction admins to reconcile outcomes', async () => {
    setupRoutes();
    const user = userEvent.setup();
    renderWithProviders(<PredictionsPage />, {
      route: '/predictions?tab=incidents',
      auth: makeAuth(makeUser('IT_MANAGER')),
    });

    expect(await screen.findByText(/sigmoid\(intercept \+ Σ weight × standardised feature\)/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Reconcile outcomes/i }));
    await waitFor(() => expect(last('POST', '/predictions/outcomes/reconcile')).toBeDefined());
  });
});
