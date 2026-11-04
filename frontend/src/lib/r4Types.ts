import type { AlertSeverity, CiType, RelationshipType } from './opsTypes';
import type { Level } from './types';

/**
 * Release 4 / BE-5 contract: simulation mode, observability, digital twin (API_DOCUMENTATION.md "Release 4 — simulation,
 * observability, digital twin"; Java records in `com.nexus360.{simulation,observability,twin}`). Field names are exact;
 * timestamps are ISO-8601 UTC strings. Every record that can be simulated carries `simulated`.
 */

// ================================================================ simulation mode

export type SimulationRunStatus = 'RUNNING' | 'STOPPED' | 'COMPLETED' | 'FAILED';

export interface SimulationScenarioDto {
  code: string;
  title: string;
  description: string;
  module: string;
  tickIntervalSeconds: number;
  running: boolean;
  runId: number | null;
  startedAt: string | null;
  lastTickAt: string | null;
  tickCount: number;
}

export interface SimulationRunDto {
  id: number;
  scenario: string;
  status: SimulationRunStatus;
  startedAt: string;
  stoppedAt: string | null;
  lastTickAt: string | null;
  nextTickAt: string | null;
  tickCount: number;
  summary: Record<string, unknown> | null;
  error: string | null;
  startedByName: string | null;
  stoppedByName: string | null;
}

export interface SimulationStatusDto {
  /** The setting is on and the deployment allows simulation. */
  enabled: boolean;
  /** `SIMULATION_ALLOWED` (`app.simulation.allowed`) of the deployment. */
  allowed: boolean;
  settingEnabled: boolean;
  scenarios: SimulationScenarioDto[];
  runningRuns: SimulationRunDto[];
  /** Simulated rows currently stored, per table. */
  simulatedRecords: Record<string, number>;
}

/** `DELETE /simulation/data` → deleted rows per table. */
export type SimulationPurgeResult = Record<string, number>;

// ================================================================ observability: sources, mappings, resources

export type TelemetrySourceKind = 'OTLP' | 'PROMETHEUS_API' | 'GRAFANA' | 'CLOUDWATCH' | 'AZURE_MONITOR' | 'GCP_MONITORING';
export type TelemetrySourceStatus = 'NOT_CONNECTED' | 'CONNECTED' | 'ERROR';
export type TelemetryAuthType = 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY';

export interface ObservabilityOverview {
  includeSimulated: boolean;
  sources: number;
  connectedSources: number;
  resources: number;
  unmappedResources: number;
  series: number;
  openAnomalies: number;
  openCorrelationGroups: number;
  detectors: number;
  simulatedDataPresent: boolean;
  lastDataAt: string | null;
}

export interface TelemetrySourceDto {
  id: number;
  name: string;
  kind: TelemetrySourceKind;
  status: TelemetrySourceStatus;
  prepared: boolean;
  baseUrl: string | null;
  authType: TelemetryAuthType | null;
  username: string | null;
  hasSecret: boolean;
  tokenHint: string | null;
  config: Record<string, unknown> | null;
  pullEnabled: boolean;
  pullIntervalSeconds: number;
  active: boolean;
  lastVerifiedAt: string | null;
  lastTestedAt: string | null;
  lastError: string | null;
  lastReceivedAt: string | null;
  lastPulledAt: string | null;
  receivedCount: number;
  ingestPaths: { metrics?: string; logs?: string; traces?: string } | null;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface TelemetrySourceRequest {
  name: string;
  kind: TelemetrySourceKind;
  baseUrl?: string | null;
  authType?: TelemetryAuthType | null;
  username?: string | null;
  /** Write-only; `null` on update keeps the stored secret. */
  secret?: string | null;
  config?: Record<string, unknown> | null;
  pullEnabled?: boolean;
  pullIntervalSeconds?: number;
  active?: boolean;
}

export interface TelemetrySourceSaved {
  source: TelemetrySourceDto;
  /** OTLP token (`otsec_…`), only in the response that created or rotated it. */
  token: string | null;
}

export interface TelemetryKindInfo {
  kind: TelemetrySourceKind;
  prepared: boolean;
  pullSupported: boolean;
  testSupported: boolean;
  defaultBaseUrl: string | null;
  configKeys: string[];
  description: string;
}

export interface SourceTestResult {
  sourceId: number;
  status: TelemetrySourceStatus;
  success: boolean;
  httpStatus: number | null;
  message: string | null;
  testedAt: string;
  lastVerifiedAt: string | null;
}

export interface SourcePullResult {
  sourceId: number;
  queries: number;
  series: number;
  points: number;
  rejected: number;
  message: string | null;
  from: string;
  to: string;
}

export type MappingMatchType = 'EXACT' | 'REGEX';

export interface MappingRuleDto {
  id: number;
  name: string;
  attributeKey: string;
  matchType: MappingMatchType;
  matchValue: string;
  serviceId: number | null;
  serviceName: string | null;
  ciId: number | null;
  ciName: string | null;
  sortOrder: number;
  enabled: boolean;
  matchCount: number;
  lastMatchedAt: string | null;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface MappingRuleRequest {
  name: string;
  attributeKey: string;
  matchType: MappingMatchType;
  matchValue: string;
  serviceId?: number | null;
  ciId?: number | null;
  sortOrder?: number;
  enabled?: boolean;
}

export interface ReapplyResult {
  resources: number;
  changed: number;
  mapped: number;
  unmapped: number;
}

export interface TelemetryResourceDto {
  id: number;
  serviceName: string | null;
  serviceNamespace: string | null;
  environment: string | null;
  hostName: string | null;
  attributes: Record<string, string> | null;
  serviceId: number | null;
  serviceCode: string | null;
  mappedServiceName: string | null;
  ciId: number | null;
  ciName: string | null;
  mappingRuleId: number | null;
  sourceId: number | null;
  simulated: boolean;
  firstSeenAt: string;
  lastSeenAt: string;
}

export interface ResourceChangeDto {
  id: number;
  resourceId: number;
  attributeKey: string;
  fromValue: string | null;
  toValue: string | null;
  observedAt: string;
  simulated: boolean;
}

export interface RetentionDto {
  rawDays: number;
  rollupDays: number;
  logsDays: number;
  tracesDays: number;
}

export interface PurgeResult {
  ranAt: string;
  points: number;
  rollups: number;
  logs: number;
  spans: number;
  series: number;
  resources: number;
}

// ================================================================ observability: queries

export type MetricAggregation = 'avg' | 'max' | 'min' | 'sum' | 'p95';
export type LogSeverity = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL';

export interface MetricNameDto {
  name: string;
  type: string;
  unit: string | null;
  seriesCount: number;
  lastSeenAt: string | null;
  simulated: boolean;
}

export interface SeriesPoint {
  t: string;
  v: number;
  samples: number;
}

export interface SeriesDto {
  seriesId: number;
  metric: string;
  type: string;
  unit: string | null;
  attributes: Record<string, string> | null;
  resourceId: number | null;
  resourceServiceName: string | null;
  serviceId: number | null;
  ciId: number | null;
  simulated: boolean;
  points: SeriesPoint[];
}

export interface SeriesResponse {
  metric: string;
  aggregation: MetricAggregation;
  from: string;
  to: string;
  stepSeconds: number;
  includeSimulated: boolean;
  simulated: boolean;
  status: 'OK' | 'NO_DATA';
  message: string | null;
  series: SeriesDto[];
}

export interface LogDto {
  id: number;
  timestamp: string;
  severity: LogSeverity;
  severityText: string | null;
  body: string;
  bodyTruncated: boolean;
  attributes: Record<string, string> | null;
  traceId: string | null;
  spanId: string | null;
  resourceId: number | null;
  resourceServiceName: string | null;
  serviceId: number | null;
  simulated: boolean;
}

export interface TraceSummary {
  traceId: string;
  rootName: string | null;
  rootServiceName: string | null;
  serviceId: number | null;
  startTime: string;
  durationMs: number;
  spanCount: number;
  errorCount: number;
  simulated: boolean;
}

export type SpanKind = 'UNSPECIFIED' | 'INTERNAL' | 'SERVER' | 'CLIENT' | 'PRODUCER' | 'CONSUMER';
export type SpanStatusCode = 'UNSET' | 'OK' | 'ERROR';

export interface SpanNode {
  spanId: string;
  parentSpanId: string | null;
  name: string;
  kind: SpanKind;
  serviceName: string | null;
  serviceId: number | null;
  resourceId: number | null;
  startTime: string;
  endTime: string;
  durationMs: number;
  offsetMs: number;
  statusCode: SpanStatusCode;
  statusMessage: string | null;
  attributes: Record<string, string> | null;
  simulated: boolean;
  children: SpanNode[];
}

export interface TraceDetail {
  traceId: string;
  startTime: string;
  endTime: string;
  durationMs: number;
  spanCount: number;
  errorCount: number;
  simulated: boolean;
  truncated: boolean;
  roots: SpanNode[];
}

export interface RedSummary {
  serviceId: number | null;
  serviceCode: string | null;
  serviceName: string | null;
  from: string;
  to: string;
  basis: 'SPANS' | 'METRICS' | 'NONE';
  requests: number;
  ratePerMinute: number | null;
  errors: number;
  errorRate: number | null;
  durationP95Ms: number | null;
  durationAvgMs: number | null;
  simulated: boolean;
  status: 'OK' | 'INSUFFICIENT_DATA';
  message: string | null;
}

// ================================================================ anomaly detection

export type DetectorAlgorithm = 'EWMA' | 'MEDIAN_MAD';
export type DetectorDirection = 'UP' | 'DOWN' | 'BOTH';
export type DetectorSeasonality = 'NONE' | 'HOUR_OF_WEEK' | 'AUTO';

export interface DetectorRequest {
  name: string;
  description?: string | null;
  metricName: string;
  serviceId?: number | null;
  ciId?: number | null;
  attributeFilters?: Record<string, string> | null;
  valueAggregation?: MetricAggregation;
  algorithm?: DetectorAlgorithm;
  windowPoints?: number;
  threshold?: number;
  consecutivePoints?: number;
  minSamples?: number;
  direction?: DetectorDirection;
  seasonality?: DetectorSeasonality;
  includeSimulated?: boolean;
  raiseAlert?: boolean;
  alertSeverity?: AlertSeverity;
  createIncident?: boolean;
  enabled?: boolean;
}

export interface EvaluationDetection {
  seriesId: number;
  from: string;
  to: string;
  peakScore: number;
  observed: number;
  expected: number;
}

export interface EvaluationLabel {
  source: 'FAULT_WINDOW' | 'ALERT' | 'MANUAL';
  reference: string | null;
  from: string;
  to: string;
  detected: boolean;
}

export interface EvaluationResult {
  detectorId: number;
  status: 'EVALUATED' | 'INSUFFICIENT_DATA';
  message: string | null;
  from: string;
  to: string;
  seriesEvaluated: number;
  pointsEvaluated: number;
  detections: EvaluationDetection[];
  labels: EvaluationLabel[];
  truePositives: number;
  falsePositives: number;
  falseNegatives: number;
  precision: number | null;
  recall: number | null;
  f1: number | null;
  params: Record<string, unknown> | null;
  evaluatedAt: string;
}

export interface EvaluateRequest {
  from?: string;
  to?: string;
  labels?: { from: string; to: string; label?: string }[];
  useSimulatedFaults?: boolean;
  useAlerts?: boolean;
  toleranceMinutes?: number;
}

export interface DetectorDto {
  id: number;
  name: string;
  description: string | null;
  metricName: string;
  serviceId: number | null;
  serviceName: string | null;
  ciId: number | null;
  ciName: string | null;
  attributeFilters: Record<string, string> | null;
  valueAggregation: MetricAggregation;
  algorithm: DetectorAlgorithm;
  windowPoints: number;
  threshold: number;
  consecutivePoints: number;
  minSamples: number;
  direction: DetectorDirection;
  seasonality: DetectorSeasonality;
  includeSimulated: boolean;
  raiseAlert: boolean;
  alertSeverity: AlertSeverity | null;
  createIncident: boolean;
  enabled: boolean;
  lastRunAt: string | null;
  lastEvaluation: EvaluationResult | null;
  lastEvaluatedAt: string | null;
  openAnomalies: number;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface DetectorRunResult {
  detectorId: number;
  seriesEvaluated: number;
  pointsEvaluated: number;
  anomaliesOpened: number;
  anomaliesUpdated: number;
  anomaliesResolved: number;
  alertsRaised: number;
  status: 'OK' | 'INSUFFICIENT_DATA' | 'NO_SERIES' | 'DISABLED';
  message: string | null;
}

export interface AnomalyDto {
  id: number;
  detectorId: number;
  detectorName: string | null;
  seriesId: number;
  metricName: string;
  seriesAttributes: Record<string, string> | null;
  serviceId: number | null;
  serviceName: string | null;
  ciId: number | null;
  ciName: string | null;
  status: 'OPEN' | 'RESOLVED';
  direction: 'UP' | 'DOWN';
  startedAt: string;
  endedAt: string | null;
  lastPointAt: string | null;
  pointCount: number;
  peakScore: number;
  observedValue: number;
  expectedValue: number;
  expectedLower: number;
  expectedUpper: number;
  modelParams: Record<string, unknown> | null;
  alertId: number | null;
  alertNumber: string | null;
  correlationGroupId: number | null;
  simulated: boolean;
  createdAt: string;
}

// ================================================================ correlation

export type CorrelationStatus = 'OPEN' | 'CLOSED' | 'DISMISSED';
export type CorrelationReasonCode =
  | 'TIME_WINDOW'
  | 'SAME_CI'
  | 'SAME_SERVICE'
  | 'TOPOLOGY'
  | 'SHARED_LABELS'
  | 'SAME_FINGERPRINT'
  | 'DEPLOYMENT'
  | 'CHANGE'
  | 'INCIDENT'
  | 'MANUAL'
  | 'SEED';

export interface CorrelationReason {
  code: CorrelationReasonCode | string;
  text: string;
  weight: number;
}

export interface CorrelationMemberDto {
  id: number;
  memberType: 'ALERT' | 'ANOMALY';
  alertId: number | null;
  alertNumber: string | null;
  anomalyId: number | null;
  title: string;
  severity: string | null;
  status: string | null;
  serviceId: number | null;
  serviceName: string | null;
  ciId: number | null;
  ciName: string | null;
  firstSeenAt: string;
  score: number;
  reasons: CorrelationReason[] | null;
  addedBy: string | null;
  joinedAt: string;
  simulated: boolean;
}

export interface CorrelationGroupDto {
  id: number;
  title: string;
  status: CorrelationStatus;
  score: number;
  reasons: CorrelationReason[] | null;
  memberCount: number;
  rootCiId: number | null;
  rootCiName: string | null;
  rootServiceId: number | null;
  rootServiceName: string | null;
  rootReason: string | null;
  incidentId: number | null;
  incidentNumber: string | null;
  firstSeenAt: string;
  lastActivityAt: string;
  highestSeverity: string | null;
  simulated: boolean;
  dismissedReason: string | null;
  dismissedByName: string | null;
  dismissedAt: string | null;
  members: CorrelationMemberDto[] | null;
}

export interface ConvertGroupRequest {
  title?: string;
  impact?: Level;
  urgency?: Level;
}

// ================================================================ digital twin

export type TwinLayer = 'BUSINESS' | 'APPLICATION' | 'INFRASTRUCTURE';
export type TwinHealth = 'OPERATIONAL' | 'DEGRADED' | 'OUTAGE' | 'MAINTENANCE';

export interface TwinMeta {
  layers: Record<TwinLayer, CiType[]>;
  simulationTypes: ('FAILURE' | 'CHANGE')[];
  modes: ('HARD' | 'DEGRADED')[];
  impactLevels: ('DOWN' | 'DEGRADED')[];
}

export interface TwinNodeState {
  health: TwinHealth | string;
  healthReason: string | null;
  openIncidents: number;
  firingAlerts: number;
  openAnomalies: number;
  lastChangeAt: string | null;
  lastChange: string | null;
}

export interface TwinNode {
  id: number;
  name: string;
  ciType: CiType;
  layer: TwinLayer;
  status: string;
  environment: string | null;
  criticality: string | null;
  serviceId: number | null;
  serviceCode: string | null;
  depth: number;
  cluster: string | null;
  state: TwinNodeState | null;
}

export interface TwinEdge {
  id: number;
  source: number;
  target: number;
  type: RelationshipType;
  /** `dependentId` needs `dependencyId`. */
  dependentId: number;
  dependencyId: number;
  redundant: boolean;
  attributes: Record<string, unknown> | null;
}

export interface TwinGraph {
  rootId: number | null;
  depth: number;
  layer: string;
  includeState: boolean;
  includeSimulated: boolean;
  nodes: TwinNode[];
  edges: TwinEdge[];
  layerCounts: Record<string, number> | null;
  truncated: boolean;
}

export interface TwinTreeNode {
  ciId: number;
  name: string;
  ciType: CiType;
  layer: TwinLayer;
  via: RelationshipType | null;
  redundant: boolean;
  cluster: string | null;
  state: TwinNodeState | null;
  repeated: boolean;
  children: TwinTreeNode[];
}

export interface BusinessServiceTree {
  service: { id: number; code: string; name: string; tier: number; status: string };
  ciId: number | null;
  depth: number;
  root: TwinTreeNode | null;
  layerCounts: Record<string, number> | null;
  truncated: boolean;
}

export type TwinSimulationType = 'FAILURE' | 'CHANGE';
export type TwinSimulationMode = 'HARD' | 'DEGRADED';

export interface TwinSimulationRequest {
  type: TwinSimulationType;
  targets?: number[];
  mode?: TwinSimulationMode;
  changeId?: number | null;
  name?: string;
}

export interface ImpactedCi {
  ciId: number;
  name: string;
  ciType: CiType;
  layer: TwinLayer;
  impact: 'DOWN' | 'DEGRADED';
  depth: number;
  path: string[];
  pathIds: number[];
  redundancyApplied: boolean;
  reason: string | null;
}

export interface ImpactedService {
  serviceId: number;
  code: string;
  name: string;
  tier: number;
  impact: 'DOWN' | 'DEGRADED';
  ciId: number | null;
  path: string[] | null;
  estimatedAffectedUsers: number | null;
  affectedUsersBasis: 'CI_ATTRIBUTE' | 'MAX_RECORDED_INCIDENT_IMPACT_180D' | 'UNKNOWN' | string;
}

export interface TwinSlaRisk {
  incidentId: number;
  number: string;
  title: string;
  priority: string;
  serviceName: string | null;
  health: string | null;
  resolutionDueAt: string | null;
}

export interface TwinChangeRef {
  changeId: number;
  number: string;
  title: string;
  status: string;
  plannedStart: string | null;
  plannedEnd: string | null;
  touches: 'CI' | 'SERVICE' | string;
}

export interface TwinSimulationResult {
  id: number;
  label: string;
  type: TwinSimulationType;
  mode: TwinSimulationMode;
  name: string | null;
  targets: { id: number; name: string; ciType: CiType; layer: TwinLayer }[];
  changeId: number | null;
  changeNumber: string | null;
  impactedCis: ImpactedCi[];
  impactedServices: ImpactedService[];
  impactByLayer: Record<string, number> | null;
  estimatedAffectedUsers: number | null;
  affectedUsersBasis: string | null;
  slasAtRisk: TwinSlaRisk[];
  scheduledChanges: TwinChangeRef[];
  notes: string[] | null;
  createdByName: string | null;
  createdAt: string;
}

export interface TwinSimulationSummary {
  id: number;
  label: string;
  type: TwinSimulationType;
  mode: TwinSimulationMode;
  name: string | null;
  impactedCiCount: number;
  impactedServiceCount: number;
  createdByName: string | null;
  createdAt: string;
}

export type TimelineKind = 'CMDB' | 'CHANGE' | 'DEPLOYMENT' | 'TELEMETRY';

export interface TimelineEntry {
  at: string;
  kind: TimelineKind;
  title: string;
  actorName: string | null;
  ciId: number | null;
  ciName: string | null;
  serviceId: number | null;
  reference: { type?: string; id?: number; number?: string } | null;
  diff: { field: string; from: string | null; to: string | null }[] | null;
  simulated: boolean;
}

export interface ChangeTimeline {
  ciId: number | null;
  serviceId: number | null;
  from: string;
  to: string;
  includeSimulated: boolean;
  entries: TimelineEntry[];
  truncated: boolean;
}

// ================================================================ predictive intelligence (BE-6 / §4.3)

export type PredictionModelType = 'SLA_BREACH' | 'INCIDENT_RECURRENCE' | 'CAPACITY_RISK' | 'SERVICE_HEALTH' | 'ANOMALY';
export type PredictionModelStatus = 'TRAINED' | 'ACTIVE' | 'RETIRED' | 'INSUFFICIENT_DATA' | 'FAILED';

export interface PredictionMetaDto {
  types: PredictionModelType[];
  statuses: PredictionModelStatus[];
  minSamples: number;
  recurrenceDays: number;
  methods: Record<string, string>;
}

export interface CalibrationBin {
  bin: number;
  lower: number;
  upper: number;
  count: number;
  meanPredicted: number | null;
  fractionPositive: number | null;
}

export interface PredictionModelDto {
  id: number;
  type: PredictionModelType;
  scopeKey: string;
  version: number;
  algorithm: string;
  status: PredictionModelStatus;
  statusMessage: string | null;
  features: string[] | null;
  params: Record<string, unknown> | null;
  metrics: Record<string, unknown> | null;
  trainingFrom: string | null;
  trainingTo: string | null;
  cutoffAt: string | null;
  sampleCount: number;
  positiveCount: number | null;
  trainCount: number;
  testCount: number;
  minSamples: number;
  includeSimulated: boolean;
  simulated: boolean;
  detectorId: number | null;
  trainedAt: string | null;
  trainedByName: string | null;
  activatedAt: string | null;
  activatedByName: string | null;
  retiredAt: string | null;
  retiredByName: string | null;
  lastEvaluatedAt: string | null;
  driftStatus: string | null;
  driftCheckedAt: string | null;
  method: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TrainModelRequest {
  type: PredictionModelType;
  from?: string | null;
  to?: string | null;
  includeSimulated?: boolean;
  testFraction?: number;
  l2?: number;
  detectorId?: number | null;
  useAlerts?: boolean;
  toleranceMinutes?: number;
}

export interface EvaluateModelRequest {
  from?: string | null;
  to?: string | null;
  useAlerts?: boolean;
  toleranceMinutes?: number;
}

export interface PredictionJobDto {
  id: number;
  jobType: string;
  trigger: string;
  modelType: PredictionModelType;
  modelId: number | null;
  status: string;
  message: string | null;
  request: Record<string, unknown> | null;
  result: Record<string, unknown> | null;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number;
  requestedByName: string | null;
}

export interface FeatureContribution {
  feature: string;
  value: number;
  standardized: number;
  weight: number;
  contribution: number;
}

export interface ClassificationPredictionDto {
  type: PredictionModelType;
  status: 'OK' | 'NO_ACTIVE_MODEL' | 'NOT_APPLICABLE' | 'COMPLETE' | 'INSUFFICIENT_DATA' | string;
  basis: 'MODEL' | 'STATISTICAL_FALLBACK' | 'NONE' | string;
  modelId: number | null;
  modelVersion: number | null;
  algorithm: string | null;
  predictionId: number | null;
  subjectType: string;
  subjectId: number;
  subjectLabel: string | null;
  probability: number | null;
  threshold: number | null;
  predictedPositive: boolean | null;
  riskLevel: string | null;
  targetAt: string | null;
  features: Record<string, unknown> | null;
  contributions: FeatureContribution[];
  modelMetrics: Record<string, unknown> | null;
  method: string | null;
  howComputed: string | null;
  message: string | null;
  simulated: boolean;
  generatedAt: string;
  fallback: Record<string, unknown> | null;
}

export interface IncidentPredictionsDto {
  incidentId: number;
  number: string;
  slaBreach: ClassificationPredictionDto;
  recurrence: ClassificationPredictionDto;
}

export interface ForecastPointDto {
  at: string;
  predicted: number;
  lower: number;
  upper: number;
}

export interface MetricForecastDto {
  metric: string;
  status: 'OK' | 'INSUFFICIENT_DATA' | 'NO_ACTIVE_MODEL' | string;
  method: string | null;
  predictionId: number | null;
  historyPoints: number;
  history: ForecastPointDto[];
  forecast: ForecastPointDto[];
  sigma: number | null;
  message: string | null;
}

export interface ServiceHealthForecastDto {
  serviceId: number;
  serviceCode: string;
  serviceName: string;
  status: 'OK' | 'INSUFFICIENT_DATA' | 'NO_ACTIVE_MODEL' | string;
  basis: string;
  modelId: number | null;
  modelVersion: number | null;
  algorithm: string | null;
  horizonDays: number;
  metrics: MetricForecastDto[];
  modelMetrics: Record<string, unknown> | null;
  howComputed: string | null;
  message: string | null;
  generatedAt: string;
}

export interface CapacitySeriesForecastDto {
  seriesId: number;
  metric: string;
  attributes: Record<string, string> | null;
  resourceId: number | null;
  resourceServiceName: string | null;
  serviceId: number | null;
  ciId: number | null;
  status: string;
  method: string | null;
  predictionId: number | null;
  threshold: number;
  thresholdBasis: string;
  current: number | null;
  hoursToThreshold: number | null;
  earliestHours: number | null;
  latestHours: number | null;
  atRisk: boolean;
  riskLevel: string;
  historyPoints: number;
  forecast: ForecastPointDto[];
  message: string | null;
  simulated: boolean;
}

export interface CapacityForecastDto {
  status: 'OK' | 'NO_ACTIVE_MODEL' | string;
  basis: string;
  modelId: number | null;
  modelVersion: number | null;
  algorithm: string | null;
  horizonHours: number;
  series: CapacitySeriesForecastDto[];
  modelMetrics: Record<string, unknown> | null;
  howComputed: string | null;
  message: string | null;
  generatedAt: string;
}

export interface PredictionRecordDto {
  id: number;
  modelId: number;
  modelType: PredictionModelType;
  modelVersion: number;
  subjectType: string;
  subjectId: number;
  subjectLabel: string | null;
  target: string;
  score: number;
  lowerBound: number | null;
  upperBound: number | null;
  threshold: number | null;
  predictedPositive: boolean | null;
  riskLevel: string | null;
  horizonSeconds: number | null;
  targetAt: string | null;
  features: Record<string, unknown> | null;
  explanation: Record<string, unknown> | null;
  outcomeStatus: 'PENDING' | 'KNOWN' | 'EXPIRED' | string;
  outcomeValue: number | null;
  outcomePositive: boolean | null;
  outcomeRecordedAt: string | null;
  simulated: boolean;
  createdAt: string;
}

export interface ModelPerformanceDto {
  modelId: number;
  modelType: PredictionModelType;
  version: number;
  outcomes: number;
  pending: number;
  minOutcomesForLiveMetrics: number;
  minOutcomesForDrift: number;
  offlineMetrics: Record<string, unknown> | null;
  liveMetrics: Record<string, unknown> | null;
  driftStatus: 'OK' | 'INSUFFICIENT_OUTCOMES' | 'DRIFT' | string;
  driftReasons: string[];
  checkedAt: string;
}

export interface ReconcileOutcomesResult {
  known: number;
  expired: number;
  driftWarnings: number;
  checkedAt: string;
}

// ================================================================ agentic AI (BE-6 / §4.4)

export type AgentCode = 'INCIDENT' | 'ROOT_CAUSE' | 'CHANGE_RISK' | 'KNOWLEDGE' | 'INFRA_INVESTIGATION' | 'RESOLUTION';
export type AgentRunType = 'INVESTIGATE_INCIDENT' | 'ASSESS_CHANGE' | 'ANSWER_QUESTION';
export type AgentActionKind = 'ASSIGN_TEAM' | 'CHANGE_PRIORITY' | 'LINK_PROBLEM' | 'RUN_PLAYBOOK' | 'PUBLISH_KB_DRAFT' | 'DECLARE_MAJOR';

export interface AgentDto {
  code: AgentCode;
  name: string;
  description: string;
  version: string;
  tools: string[];
  outputSchema: Record<string, unknown>;
  enabled: boolean;
  allowedTools: string[];
  maxSteps: number;
  timeoutSeconds: number;
  telemetryMinutes: number;
  historyDays: number;
  allowedRoles: string[];
  approvalActionKinds: AgentActionKind[];
  canRun: boolean;
}

export interface AgentToolDto {
  name: string;
  description: string;
  arguments: Record<string, string>;
  allowedRoles: string[];
}

export interface AgentVersionDto {
  id: number;
  agentCode: AgentCode;
  version: string;
  promptHash: string;
  prompt: string;
  outputSchema: Record<string, unknown>;
  tools: string[];
  active: boolean;
  createdAt: string;
}

export interface AgentPolicyDto {
  agentCode: AgentCode;
  enabled: boolean;
  allowedTools: string[];
  maxSteps: number;
  timeoutSeconds: number;
  telemetryMinutes: number;
  historyDays: number;
  allowedRoles: string[];
  approvalActionKinds: AgentActionKind[];
  updatedByName: string | null;
  updatedAt: string;
}

export interface AgentPolicyRequest {
  enabled: boolean;
  allowedTools?: string[];
  maxSteps?: number;
  timeoutSeconds?: number;
  telemetryMinutes?: number;
  historyDays?: number;
  allowedRoles?: string[];
  approvalActionKinds?: AgentActionKind[];
}

export interface StartAgentRunRequest {
  runType: AgentRunType;
  incidentId?: number | null;
  changeId?: number | null;
  question?: string | null;
  includeSimulated?: boolean;
  timeoutSeconds?: number | null;
}

export interface AgentEvidenceRef {
  type: string;
  id: number;
  label: string | null;
}

export interface AgentToolCallDto {
  id: number;
  seq: number;
  tool: string;
  args: Record<string, string>;
  status: 'OK' | 'EMPTY' | 'DENIED' | 'NOT_FOUND' | 'INVALID' | 'DISALLOWED' | 'ERROR' | string;
  result: unknown;
  resultSizeBytes: number;
  truncated: boolean;
  error: string | null;
  requestedBy: 'PLAN' | 'LLM' | string;
  durationMs: number;
  createdAt: string;
}

export interface AgentStepDto {
  id: number;
  seq: number;
  stage: number;
  agentCode: AgentCode;
  agentVersion: string;
  status: string;
  source: 'LLM' | 'FALLBACK' | string | null;
  fallbackReason: string | null;
  confidence: number | null;
  input: Record<string, unknown>;
  output: Record<string, unknown> | null;
  evidence: AgentEvidenceRef[];
  llmTurns: number;
  tokensIn: number;
  tokensOut: number;
  error: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  toolCalls: AgentToolCallDto[];
}

export interface AgentActionDto {
  id: number;
  runId: number;
  stepId: number | null;
  agentCode: AgentCode;
  kind: AgentActionKind;
  targetType: string;
  targetId: number | null;
  params: Record<string, unknown>;
  title: string;
  rationale: string | null;
  requiresApproval: boolean;
  status: 'PROPOSED' | 'PENDING_APPROVAL' | 'APPLIED' | 'REJECTED' | 'FAILED' | string;
  approvalId: number | null;
  proposedByName: string | null;
  proposedAt: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionComment: string | null;
  result: string | null;
  link: string | null;
  createdAt: string;
}

export interface AgentFeedbackDto {
  id: number;
  userId: number | null;
  userName: string | null;
  helpful: boolean;
  comment: string | null;
  createdAt: string;
}

export interface AgentRunSummaryDto {
  id: number;
  runType: AgentRunType;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' | string;
  subjectType: string | null;
  subjectId: number | null;
  subjectNumber: string | null;
  subjectTitle: string | null;
  question: string | null;
  summary: string | null;
  source: 'LLM' | 'FALLBACK' | 'MIXED' | string | null;
  confidence: number | null;
  error: string | null;
  cancelRequested: boolean;
  simulated: boolean;
  stepCount: number;
  actionCount: number;
  requestedById: number | null;
  requestedByName: string | null;
  requestedByRole: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  createdAt: string;
}

export interface AgentRunDetailDto {
  run: AgentRunSummaryDto;
  output: Record<string, unknown> | null;
  agentVersions: Record<string, string>;
  steps: AgentStepDto[];
  actions: AgentActionDto[];
  feedback: AgentFeedbackDto[];
  myFeedback: AgentFeedbackDto | null;
}

export interface AgentEvalCaseDto {
  id: number;
  name: string;
  description: string | null;
  agentCode: AgentCode;
  runType: AgentRunType;
  incidentId: number | null;
  changeId: number | null;
  question: string | null;
  expected: Record<string, unknown>;
  enabled: boolean;
  demo: boolean;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AgentEvalCaseRequest {
  name: string;
  description?: string | null;
  agentCode: AgentCode;
  runType: AgentRunType;
  incidentId?: number | null;
  changeId?: number | null;
  question?: string | null;
  expected: Record<string, unknown>;
  enabled?: boolean;
}

export interface AgentEvalCaseResultDto {
  caseId: number;
  caseName: string;
  agentCode: AgentCode;
  runType: AgentRunType;
  runId: number | null;
  passed: boolean;
  score: number;
  source: string | null;
  checks: string[];
  failures: string[];
  durationMs: number;
}

export interface AgentEvalRunDto {
  id: number;
  status: string;
  agentVersions: Record<string, string>;
  caseCount: number;
  passed: number;
  failed: number;
  score: number | null;
  results: AgentEvalCaseResultDto[];
  startedByName: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
}

export interface AgentGovernanceDto {
  days: number;
  from: string;
  to: string;
  totalRuns: number;
  succeededRuns: number;
  failedRuns: number;
  cancelledRuns: number;
  timedOutRuns: number;
  llmSteps: number;
  fallbackSteps: number;
  totalTokensIn: number;
  totalTokensOut: number;
  toolCalls: number;
  deniedToolCalls: number;
  proposedActions: number;
  appliedActions: number;
  rejectedActions: number;
  helpfulCount: number;
  notHelpfulCount: number;
  lastEvalScore: number | null;
  lastEvalAt: string | null;
  byRunType: Record<string, number>;
  byAgent: Record<string, Record<string, unknown>>;
  agents: AgentDto[];
  policies: AgentPolicyDto[];
}

// ================================================================ autonomous remediation (BE-7 / §4.5)

export interface RemediationMetaDto {
  connectorKinds: string[];
  stepKinds: string[];
  triggerKinds: string[];
  runStatuses: string[];
  versionStatuses: string[];
}

export interface ActionTemplateDto {
  id: string;
  name: string;
  description: string | null;
  parametersSchema: Record<string, unknown>;
  targetScope: string | Record<string, unknown> | null;
}

export type ExecutionSystemStatus = 'NOT_CONNECTED' | 'CONNECTED' | 'SIMULATED' | 'ERROR';

export interface ExecutionSystemDto {
  id: number;
  name: string;
  kind: string;
  endpointUrl: string | null;
  credentialScope: string | null;
  credentialsConfigured: boolean;
  actionTemplates: ActionTemplateDto[];
  status: ExecutionSystemStatus | string;
  lastTestedAt: string | null;
  lastError: string | null;
  enabled: boolean;
}

export interface CreateExecutionSystemRequest {
  name: string;
  kind: string;
  endpointUrl?: string | null;
  credentialScope?: string | null;
  secret?: string | null;
  actionTemplates?: ActionTemplateDto[];
}

export interface PlaybookStepDto {
  stepOrder: number;
  name: string;
  kind: string;
  systemId: number | null;
  templateId: string | null;
  parameters: Record<string, unknown>;
  rollbackTemplateId: string | null;
  rollbackParameters: Record<string, unknown>;
  verifyConfig: Record<string, unknown>;
  timeoutSeconds: number;
  maxRetries: number;
}

export type PlaybookVersionStatus = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'RETIRED';

export interface PlaybookVersionDto {
  id: number;
  playbookId: number;
  version: number;
  status: PlaybookVersionStatus | string;
  triggerKinds: string[];
  parametersSchema: Record<string, unknown>;
  steps: PlaybookStepDto[];
  recoveryCriteria: string | Record<string, unknown> | null;
  approvalId: number | null;
  createdByName: string | null;
  approvedByName: string | null;
  approvedAt: string | null;
  createdAt: string;
}

export interface PlaybookDto {
  id: number;
  code: string;
  name: string;
  description: string | null;
  targetEnvironment: string;
  preApprovedAlertTypes: string[];
  maxBlastRadius: number;
  rateLimitPerHour: number;
  requireLinkedRecord: boolean;
  activeVersion: number | null;
  enabled: boolean;
  versions: PlaybookVersionDto[];
}

export interface CreatePlaybookRequest {
  code: string;
  name: string;
  description?: string | null;
  targetEnvironment?: string;
  preApprovedAlertTypes?: string[];
  maxBlastRadius?: number;
  rateLimitPerHour?: number;
  requireLinkedRecord?: boolean;
  triggerKinds?: string[];
  recoveryCriteria?: string | Record<string, unknown> | null;
  steps?: Partial<PlaybookStepDto>[];
}

export interface PolicyCheckDto {
  check: string;
  passed: boolean;
  detail: string;
}

export interface RemediationRunDto {
  id: number;
  playbookId: number;
  playbookCode: string;
  playbookName: string;
  playbookVersion: number;
  triggerKind: string;
  status: string;
  targetKey: string;
  environment: string;
  incidentId: number | null;
  changeId: number | null;
  alertId: number | null;
  policyChecks: PolicyCheckDto[];
  overrideReason: string | null;
  overriddenByName: string | null;
  verificationResult: string | Record<string, unknown> | null;
  failureReason: string | null;
  requestedByName: string | null;
  simulated: boolean;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

export interface RemediationRunStepDto {
  id: number;
  stepOrder: number;
  name: string;
  kind: string;
  isRollback: boolean;
  status: string;
  requestSummary: Record<string, unknown>;
  responseSummary: Record<string, unknown>;
  attempts: number;
  durationMs: number;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface RemediationAuditDto {
  id: number;
  eventType: string;
  runId: number | null;
  playbookId: number | null;
  actorName: string | null;
  payload: Record<string, unknown>;
  createdAt: string;
  prevHash: string;
  hash: string;
}

export interface RemediationRunDetailDto {
  run: RemediationRunDto;
  steps: RemediationRunStepDto[];
  auditEntries: RemediationAuditDto[];
}

export interface StartRemediationRunRequest {
  playbookId: number;
  targetKey: string;
  environment?: string;
  incidentId?: number | null;
  changeId?: number | null;
  triggerKind?: string;
  parameters?: Record<string, unknown>;
  simulateFailureAtStep?: number | null;
}

export interface AuditVerifyResultDto {
  valid: boolean;
  totalRecords: number;
  firstBrokenId: number | null;
  message: string;
}

// ================================================================ security operations (BE-7 / §4.6)

export interface SecurityOverviewDto {
  openIncidents: number;
  criticalFindings: number;
  highFindings: number;
  openFindings: number;
  openCorrelationGroups: number;
  slaBreachedFindings: number;
  riskFormula: string;
  simulated: boolean;
}

export interface SecurityIndicatorDto {
  type: string;
  value: string;
  confidence: string;
}

export interface SecurityEvidenceNoteDto {
  author: string;
  note: string;
  createdAt: string;
}

export type SecurityIncidentStatus = 'NEW' | 'TRIAGE' | 'CONTAINMENT' | 'ERADICATION' | 'RECOVERY' | 'CLOSED';

export interface SecurityIncidentDto {
  id: number;
  number: string;
  title: string;
  description: string | null;
  category: string;
  severity: string;
  status: SecurityIncidentStatus | string;
  linkedIncidentId: number | null;
  affectedCiId: number | null;
  affectedServiceId: number | null;
  affectedUserEmail: string | null;
  assignedToName: string | null;
  indicators: SecurityIndicatorDto[];
  evidenceNotes: SecurityEvidenceNoteDto[];
  lessonsLearned: string | null;
  simulated: boolean;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
}

export interface SecurityIncidentTaskDto {
  id: number;
  taskOrder: number;
  title: string;
  phase: string;
  status: string;
  remediationPlaybookCode: string | null;
  completedByName: string | null;
  completedAt: string | null;
}

export interface SecurityIncidentDetailDto {
  incident: SecurityIncidentDto;
  tasks: SecurityIncidentTaskDto[];
}

export interface CreateSecurityIncidentRequest {
  title: string;
  description?: string | null;
  category: string;
  severity: string;
  affectedUserEmail?: string | null;
  templateCode?: string | null;
}

export interface VulnerabilitySourceDto {
  id: number;
  name: string;
  format: string;
  scannerName: string;
  tokenPrefix: string;
  enabled: boolean;
  lastIngestedAt: string | null;
}

export interface VulnerabilitySourceCreatedDto {
  source: VulnerabilitySourceDto;
  rawToken: string;
}

export interface VulnerabilityFindingDto {
  id: number;
  sourceId: number | null;
  cveId: string | null;
  cweId: string | null;
  title: string;
  scanner: string;
  assetName: string;
  ciId: number | null;
  serviceId: number | null;
  packageName: string | null;
  installedVersion: string | null;
  fixedVersion: string | null;
  cvssBase: number;
  cvssVector: string | null;
  assetCriticality: string;
  internetExposed: boolean;
  exploitKnown: boolean;
  riskScore: number;
  riskBand: string;
  slaDays: number;
  status: string;
  acceptedUntil: string | null;
  acceptanceReason: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  simulated: boolean;
}

export interface VulnerabilityIngestRequest {
  sourceId?: number | null;
  format: 'GENERIC_JSON' | 'SARIF' | 'TRIVY_JSON' | 'CSV' | string;
  fullScan?: boolean;
  payload: string;
}

export interface VulnerabilityIngestResultDto {
  ingested: number;
  updated: number;
  autoClosed: number;
  findings: VulnerabilityFindingDto[];
}

export interface SecurityCorrelationGroupDto {
  id: number;
  groupKey: string;
  title: string;
  targetAsset: string | null;
  targetUser: string | null;
  severity: string;
  status: string;
  signalSummary: Record<string, unknown>;
  securityIncidentId: number | null;
  simulated: boolean;
  createdAt: string;
}

export interface SecurityResponseTemplateDto {
  id: number;
  code: string;
  name: string;
  category: string;
  description: string | null;
  tasks: Record<string, unknown>[];
  recommendedPlaybookCode: string | null;
}

// ================================================================ finops (BE-8 / §4.7)

export interface CostBreakdownItemDto {
  key: string;
  name?: string;
  label?: string;
  billedCost: number;
  effectiveCost: number;
  sharePercent: number;
}

export interface CostDailyTrendDto {
  date: string;
  billedCost: number;
  effectiveCost: number;
}

export interface FinOpsOverviewDto {
  status: 'OK' | 'INSUFFICIENT_DATA' | string;
  totalBilledCost: number;
  totalEffectiveCost: number;
  unallocatedCost: number;
  potentialMonthlySavings: number;
  openAnomalies: number;
  idleResourcesCount: number;
  byService: CostBreakdownItemDto[];
  byCostCenter: CostBreakdownItemDto[];
  dailyTrend: CostDailyTrendDto[];
  simulated: boolean;
}

export interface CostSourceDto {
  id: number;
  name: string;
  kind: string;
  providerName: string;
  status: string;
  lastIngestedAt: string | null;
}

export interface CostIngestErrorDto {
  rowNumber: number;
  field: string;
  message: string;
}

export interface CostIngestResultDto {
  dryRun: boolean;
  validRows: number;
  errorRows: number;
  totalBilledCost: number;
  totalEffectiveCost: number;
  errors: CostIngestErrorDto[];
  appliedCount: number;
}

export interface AllocationRuleDto {
  id: number;
  name: string;
  priority: number;
  tagKey: string | null;
  tagValue: string | null;
  subAccountId: string | null;
  resourcePattern: string | null;
  targetServiceId: number | null;
  targetDepartmentId: number | null;
  targetCostCenter: string | null;
  splitPercent: number;
  enabled: boolean;
}

export interface BudgetStatusDto {
  id: number;
  name: string;
  targetType: string;
  targetKey: string;
  periodMonth: string;
  amount: number;
  spentToDate: number;
  utilizationPercent: number;
  forecastedEndOfMonth: number;
  triggeredThresholds: number[];
  status: string;
}

export interface CostAnomalyDto {
  id: number;
  scopeKey: string;
  chargeDate: string;
  actualCost: number;
  expectedCost: number;
  upperBound: number;
  deviationScore: number;
  status: string;
  simulated: boolean;
}

export interface CostRecommendationDto {
  id: number;
  category: string;
  resourceId: string;
  resourceName: string;
  serviceName: string | null;
  title: string;
  evidence: Record<string, unknown>;
  estimatedMonthlySavings: number;
  status: string;
  changeRequestId: number | null;
  dismissReason: string | null;
  simulated: boolean;
}

// ================================================================ digital employee experience (BE-8 / §4.8)

export interface DexDepartmentScoreDto {
  key: string;
  name?: string;
  label?: string;
  score?: number;
  avgExperienceScore?: number;
  deviceHealth?: number;
  avgDeviceHealthScore?: number;
  appPerformance?: number;
  loginSuccess?: number;
  ticketSignals?: number;
  count?: number;
  endpointCount?: number;
}

export interface DexModelScoreDto {
  key: string;
  name?: string;
  label?: string;
  score?: number;
  avgExperienceScore?: number;
  count?: number;
  endpointCount?: number;
}

export interface DexAppScoreDto {
  appName: string;
  appVersion: string;
  avgLaunchMs: number;
  avgPageLoadMs: number;
  crashRatePct: number;
  eventCount: number;
}

export interface DexServiceExperienceDto {
  serviceId: number;
  serviceName: string;
  incidentsPer100Users: number;
  avgResolveHours?: number;
  avgResolutionHours?: number;
  reopenRatePct: number;
  fcrRatePct?: number;
  firstContactResolutionPct?: number;
  csatAvg?: number;
}

export interface DexEndpointDto {
  id: number;
  deviceId: string;
  userEmail: string;
  userName: string;
  departmentName: string;
  osName: string;
  osVersion: string;
  deviceModel: string;
  cpuUsagePct: number;
  memoryUsagePct: number;
  diskUsagePct: number;
  bootTimeSeconds: number;
  crashCount7d: number;
  batteryHealthPct: number | null;
  deviceHealthScore: number;
  experienceScore: number;
  scoreBreakdown: Record<string, unknown>;
  lastSeenAt: string;
  simulated: boolean;
}

export interface DexProactiveRuleDto {
  id: number;
  name: string;
  departmentName: string;
  thresholdScore: number;
  enabled: boolean;
  lastTriggeredIncidentId: number | null;
}

export interface DexOverviewDto {
  status: 'OK' | 'INSUFFICIENT_DATA' | string;
  overallExperienceScore: number;
  deviceHealthAvg: number;
  appPerformanceAvg: number;
  loginSuccessRatePct: number;
  endpointCount: number;
  formula: string;
  byDepartment: DexDepartmentScoreDto[];
  byDeviceModel: DexModelScoreDto[];
  byApp: DexAppScoreDto[];
  serviceExperience: DexServiceExperienceDto[];
  endpoints: DexEndpointDto[];
  proactiveRules: DexProactiveRuleDto[];
  simulated: boolean;
}

export interface DexMeDto {
  status: 'OK' | 'NO_DEVICE' | string;
  device: DexEndpointDto | null;
  message: string;
}
