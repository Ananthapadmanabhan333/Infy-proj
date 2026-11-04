import type {
  AnomalyDto,
  CorrelationGroupDto,
  DetectorDto,
  SeriesResponse,
  SimulationStatusDto,
  TelemetrySourceDto,
  TwinGraph,
  TwinNode,
  TwinSimulationResult,
} from '../lib/r4Types';

/** Release 4 contract fixtures (shapes of API_DOCUMENTATION.md "Release 4"). */

export function makeSimulationStatus(overrides: Partial<SimulationStatusDto> = {}): SimulationStatusDto {
  return {
    enabled: true,
    allowed: true,
    settingEnabled: true,
    scenarios: [
      { code: 'BASELINE', title: 'Baseline telemetry', description: 'Steady metrics, logs and traces for the demo services.', module: 'OBSERVABILITY', tickIntervalSeconds: 60, running: false, runId: null, startedAt: null, lastTickAt: null, tickCount: 0 },
      {
        code: 'PAYROLL_DB_SATURATION',
        title: 'Payroll DB connection pool saturation',
        description: 'The payroll pool climbs to 50 connections.',
        module: 'OBSERVABILITY',
        tickIntervalSeconds: 60,
        running: true,
        runId: 12,
        startedAt: '2026-11-04T09:00:00Z',
        lastTickAt: '2026-11-04T09:10:00Z',
        tickCount: 10,
      },
    ],
    runningRuns: [
      {
        id: 12,
        scenario: 'PAYROLL_DB_SATURATION',
        status: 'RUNNING',
        startedAt: '2026-11-04T09:00:00Z',
        stoppedAt: null,
        lastTickAt: '2026-11-04T09:10:00Z',
        nextTickAt: '2026-11-04T09:11:00Z',
        tickCount: 10,
        summary: { metricPoints: 1200, logRecords: 80, spans: 40, deployments: 0 },
        error: null,
        startedByName: 'Ada Lovelace',
        stoppedByName: null,
      },
    ],
    simulatedRecords: { telemetry_metric_series: 24, alerts: 3, incidents: 1 },
    ...overrides,
  };
}

export function makeSource(overrides: Partial<TelemetrySourceDto> = {}): TelemetrySourceDto {
  return {
    id: 1,
    name: 'Demo OTLP collector',
    kind: 'OTLP',
    status: 'NOT_CONNECTED',
    prepared: false,
    baseUrl: null,
    authType: 'NONE',
    username: null,
    hasSecret: false,
    tokenHint: 'otsec_…a1b2',
    config: null,
    pullEnabled: false,
    pullIntervalSeconds: 60,
    active: true,
    lastVerifiedAt: null,
    lastTestedAt: null,
    lastError: null,
    lastReceivedAt: null,
    lastPulledAt: null,
    receivedCount: 0,
    ingestPaths: { metrics: '/api/otel/v1/metrics', logs: '/api/otel/v1/logs', traces: '/api/otel/v1/traces' },
    demo: true,
    createdAt: '2026-11-01T08:00:00Z',
    updatedAt: '2026-11-01T08:00:00Z',
    ...overrides,
  };
}

export const KINDS = [
  { kind: 'OTLP', prepared: false, pullSupported: false, testSupported: false, defaultBaseUrl: null, configKeys: [], description: 'OTLP/HTTP JSON push to /api/otel/v1/metrics|logs|traces with Authorization: Bearer <source token>; protobuf is not supported (415).' },
  { kind: 'PROMETHEUS_API', prepared: false, pullSupported: true, testSupported: true, defaultBaseUrl: null, configKeys: ['queries'], description: 'Prometheus HTTP API (PromQL range queries).' },
  { kind: 'CLOUDWATCH', prepared: true, pullSupported: false, testSupported: false, defaultBaseUrl: null, configKeys: ['region', 'queries'], description: 'GetMetricData builder and parser.' },
];

export function makeSeries(overrides: Partial<SeriesResponse> = {}): SeriesResponse {
  return {
    metric: 'db.client.connections.usage',
    aggregation: 'avg',
    from: '2026-11-04T09:00:00Z',
    to: '2026-11-04T10:00:00Z',
    stepSeconds: 60,
    includeSimulated: false,
    simulated: false,
    status: 'OK',
    message: null,
    series: [
      {
        seriesId: 31,
        metric: 'db.client.connections.usage',
        type: 'gauge',
        unit: '{connection}',
        attributes: { state: 'used' },
        resourceId: 4,
        resourceServiceName: 'payroll-db',
        serviceId: 1,
        ciId: 9,
        simulated: false,
        points: [
          { t: '2026-11-04T09:00:00Z', v: 12, samples: 4 },
          { t: '2026-11-04T09:01:00Z', v: 14, samples: 4 },
          { t: '2026-11-04T09:02:00Z', v: 41, samples: 4 },
        ],
      },
    ],
    ...overrides,
  };
}

export function makeAnomaly(overrides: Partial<AnomalyDto> = {}): AnomalyDto {
  return {
    id: 5,
    detectorId: 2,
    detectorName: 'Payroll DB connection pool',
    seriesId: 31,
    metricName: 'db.client.connections.usage',
    seriesAttributes: { state: 'used' },
    serviceId: 1,
    serviceName: 'Payroll',
    ciId: 9,
    ciName: 'payroll-db-primary',
    status: 'OPEN',
    direction: 'UP',
    startedAt: '2026-11-04T09:02:00Z',
    endedAt: null,
    lastPointAt: '2026-11-04T09:05:00Z',
    pointCount: 4,
    peakScore: 6.2,
    observedValue: 41,
    expectedValue: 13,
    expectedLower: 9,
    expectedUpper: 17,
    modelParams: null,
    alertId: 61,
    alertNumber: 'ALR-00061',
    correlationGroupId: 3,
    simulated: false,
    createdAt: '2026-11-04T09:02:00Z',
    ...overrides,
  };
}

export function makeDetector(overrides: Partial<DetectorDto> = {}): DetectorDto {
  return {
    id: 2,
    name: 'Payroll DB connection pool',
    description: null,
    metricName: 'db.client.connections.usage',
    serviceId: 1,
    serviceName: 'Payroll',
    ciId: null,
    ciName: null,
    attributeFilters: { state: 'used' },
    valueAggregation: 'avg',
    algorithm: 'EWMA',
    windowPoints: 60,
    threshold: 3,
    consecutivePoints: 3,
    minSamples: 30,
    direction: 'UP',
    seasonality: 'NONE',
    includeSimulated: false,
    raiseAlert: true,
    alertSeverity: 'CRITICAL',
    createIncident: true,
    enabled: true,
    lastRunAt: '2026-11-04T09:59:00Z',
    lastEvaluation: null,
    lastEvaluatedAt: null,
    openAnomalies: 1,
    demo: true,
    createdAt: '2026-11-01T08:00:00Z',
    updatedAt: '2026-11-01T08:00:00Z',
    ...overrides,
  };
}

export function makeGroup(overrides: Partial<CorrelationGroupDto> = {}): CorrelationGroupDto {
  return {
    id: 3,
    title: 'Payroll DB saturation',
    status: 'OPEN',
    score: 0.82,
    reasons: [
      { code: 'TOPOLOGY', text: 'payroll-app-01 depends on payroll-db-primary (distance 1)', weight: 0.35 },
      { code: 'TIME_WINDOW', text: 'Within 4 minutes', weight: 0.22 },
    ],
    memberCount: 2,
    rootCiId: 9,
    rootCiName: 'payroll-db-primary',
    rootServiceId: 1,
    rootServiceName: 'Payroll',
    rootReason: 'Most other members depend on it',
    incidentId: null,
    incidentNumber: null,
    firstSeenAt: '2026-11-04T09:02:00Z',
    lastActivityAt: '2026-11-04T09:06:00Z',
    highestSeverity: 'CRITICAL',
    simulated: false,
    dismissedReason: null,
    dismissedByName: null,
    dismissedAt: null,
    members: [
      { id: 101, memberType: 'ALERT', alertId: 61, alertNumber: 'ALR-00061', anomalyId: null, title: 'Pool saturation', severity: 'CRITICAL', status: 'OPEN', serviceId: 1, serviceName: 'Payroll', ciId: 9, ciName: 'payroll-db-primary', firstSeenAt: '2026-11-04T09:02:00Z', score: 1, reasons: [], addedBy: 'ENGINE', joinedAt: '2026-11-04T09:02:00Z', simulated: false },
      { id: 102, memberType: 'ALERT', alertId: 62, alertNumber: 'ALR-00062', anomalyId: null, title: 'Payroll API latency', severity: 'MAJOR', status: 'OPEN', serviceId: 1, serviceName: 'Payroll', ciId: 7, ciName: 'payroll-app-01', firstSeenAt: '2026-11-04T09:04:00Z', score: 0.82, reasons: [{ code: 'TOPOLOGY', text: 'depends on root', weight: 0.35 }], addedBy: 'ENGINE', joinedAt: '2026-11-04T09:04:00Z', simulated: false },
    ],
    ...overrides,
  };
}

function node(id: number, name: string, ciType: TwinNode['ciType'], layer: TwinNode['layer'], health = 'OPERATIONAL', extra: Partial<TwinNode> = {}): TwinNode {
  return {
    id,
    name,
    ciType,
    layer,
    status: 'ACTIVE',
    environment: 'PRODUCTION',
    criticality: 'HIGH',
    serviceId: null,
    serviceCode: null,
    depth: 0,
    cluster: null,
    state: { health, healthReason: null, openIncidents: 0, firingAlerts: 0, openAnomalies: 0, lastChangeAt: null, lastChange: null },
    ...extra,
  };
}

export function makeTwinGraph(): TwinGraph {
  return {
    rootId: null,
    depth: 4,
    layer: 'ALL',
    includeState: true,
    includeSimulated: false,
    nodes: [
      node(1, 'Payroll', 'BUSINESS_SERVICE', 'BUSINESS', 'DEGRADED', { serviceId: 1 }),
      node(2, 'Payroll Application', 'APPLICATION', 'APPLICATION'),
      node(3, 'payroll-app-01', 'SERVER', 'INFRASTRUCTURE', 'OPERATIONAL', { cluster: 'payroll-app' }),
      node(4, 'payroll-app-02', 'SERVER', 'INFRASTRUCTURE', 'OPERATIONAL', { cluster: 'payroll-app' }),
      node(9, 'payroll-db-primary', 'DATABASE', 'INFRASTRUCTURE', 'OUTAGE', { state: { health: 'OUTAGE', healthReason: '1 critical alert firing', openIncidents: 1, firingAlerts: 1, openAnomalies: 1, lastChangeAt: null, lastChange: null } }),
    ],
    edges: [
      { id: 11, source: 1, target: 2, type: 'DEPENDS_ON', dependentId: 1, dependencyId: 2, redundant: false, attributes: null },
      { id: 12, source: 2, target: 3, type: 'RUNS_ON', dependentId: 2, dependencyId: 3, redundant: true, attributes: { redundant: true } },
      { id: 13, source: 2, target: 4, type: 'RUNS_ON', dependentId: 2, dependencyId: 4, redundant: true, attributes: { redundant: true } },
      { id: 14, source: 2, target: 9, type: 'DEPENDS_ON', dependentId: 2, dependencyId: 9, redundant: false, attributes: null },
    ],
    layerCounts: { BUSINESS: 1, APPLICATION: 1, INFRASTRUCTURE: 3 },
    truncated: false,
  };
}

export function makeSimulationResult(overrides: Partial<TwinSimulationResult> = {}): TwinSimulationResult {
  return {
    id: 44,
    label: 'Simulation',
    type: 'FAILURE',
    mode: 'HARD',
    name: 'DB outage drill',
    targets: [{ id: 9, name: 'payroll-db-primary', ciType: 'DATABASE', layer: 'INFRASTRUCTURE' }],
    changeId: null,
    changeNumber: null,
    impactedCis: [
      { ciId: 2, name: 'Payroll Application', ciType: 'APPLICATION', layer: 'APPLICATION', impact: 'DOWN', depth: 1, path: ['payroll-db-primary', 'Payroll Application'], pathIds: [9, 2], redundancyApplied: false, reason: 'depends on payroll-db-primary' },
      { ciId: 1, name: 'Payroll', ciType: 'BUSINESS_SERVICE', layer: 'BUSINESS', impact: 'DOWN', depth: 2, path: ['payroll-db-primary', 'Payroll Application', 'Payroll'], pathIds: [9, 2, 1], redundancyApplied: false, reason: 'depends on Payroll Application' },
    ],
    impactedServices: [{ serviceId: 1, code: 'PAY', name: 'Payroll', tier: 1, impact: 'DOWN', ciId: 1, path: ['payroll-db-primary', 'Payroll Application', 'Payroll'], estimatedAffectedUsers: 1800, affectedUsersBasis: 'CI_ATTRIBUTE' }],
    impactByLayer: { BUSINESS: 1, APPLICATION: 1, INFRASTRUCTURE: 0 },
    estimatedAffectedUsers: 1800,
    affectedUsersBasis: 'MAX_OVER_SERVICES',
    slasAtRisk: [{ incidentId: 7846, number: 'INC-7846', title: 'Payroll not accessible', priority: 'CRITICAL', serviceName: 'Payroll', health: 'AT_RISK', resolutionDueAt: '2026-11-04T12:00:00Z' }],
    scheduledChanges: [],
    notes: ['Real data was not changed.'],
    createdByName: 'Ada Lovelace',
    createdAt: '2026-11-04T10:00:00Z',
    ...overrides,
  };
}
