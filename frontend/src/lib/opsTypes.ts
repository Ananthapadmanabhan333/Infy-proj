import type { IncidentSummary, Level } from './types';

/**
 * Release 3 BE-3 contract (docs/API.md "Release 3 — assets, CMDB, alerts"; Java records in
 * `com.nexus360.{cmdb,asset,alert}`). Field names are exact; dates are ISO strings.
 */

// ===================================================================== CMDB

export type CiType =
  | 'APPLICATION'
  | 'SERVER'
  | 'DATABASE'
  | 'NETWORK_DEVICE'
  | 'CLOUD_RESOURCE'
  | 'OPERATING_SYSTEM'
  | 'SOFTWARE'
  | 'BUSINESS_SERVICE'
  | 'INFRASTRUCTURE';
export type CiStatus = 'PLANNED' | 'ACTIVE' | 'MAINTENANCE' | 'RETIRED';
export type CiEnvironment = 'PRODUCTION' | 'STAGING' | 'TEST' | 'DEV' | 'DR';
export type Criticality = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type RelationshipType = 'DEPENDS_ON' | 'RUNS_ON' | 'HOSTS' | 'CONNECTS_TO' | 'PART_OF';
export type RelationshipOrigin = 'MANUAL' | 'SERVICE_MAP';
export type ImpactDirection = 'both' | 'upstream' | 'downstream';

export interface RelationshipTypeSpec {
  type: RelationshipType;
  outgoingLabel: string;
  incomingLabel: string;
  sourceDependsOnTarget: boolean;
}

export interface CmdbMeta {
  types: CiType[];
  statuses: CiStatus[];
  environments: CiEnvironment[];
  criticalities: Criticality[];
  relationshipTypes: RelationshipTypeSpec[];
}

export interface CiRef {
  id: number;
  name: string;
  ciType: CiType;
  status: CiStatus;
  environment: CiEnvironment | null;
}

export interface CiSummary {
  id: number;
  name: string;
  ciType: CiType;
  status: CiStatus;
  environment: CiEnvironment | null;
  criticality: Criticality | null;
  description: string | null;
  ownerId: number | null;
  ownerName: string | null;
  teamId: number | null;
  teamName: string | null;
  serviceId: number | null;
  serviceCode: string | null;
  relationshipCount: number;
  openAlerts: number;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RelationshipDto {
  id: number;
  type: RelationshipType;
  direction: 'OUTGOING' | 'INCOMING';
  label: string;
  ci: CiRef;
  description: string | null;
  origin: RelationshipOrigin;
  demo: boolean;
  createdAt: string;
}

export interface WorkRef {
  id: number;
  number: string;
  title: string;
  status: string;
}

export interface CiDetail {
  id: number;
  name: string;
  ciType: CiType;
  status: CiStatus;
  environment: CiEnvironment | null;
  criticality: Criticality | null;
  description: string | null;
  ownerId: number | null;
  ownerName: string | null;
  teamId: number | null;
  teamName: string | null;
  serviceId: number | null;
  serviceCode: string | null;
  serviceName: string | null;
  attributes: Record<string, string | number | boolean | null> | null;
  outgoing: RelationshipDto[];
  incoming: RelationshipDto[];
  assets: { id: number; assetTag: string; name: string; assetType: string; status: string }[];
  openAlerts: WorkRef[];
  openAlertCount: number;
  problems: WorkRef[];
  changes: WorkRef[];
  createdByName: string | null;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CiRequestBody {
  name: string;
  ciType: CiType;
  status?: CiStatus | null;
  environment?: CiEnvironment | null;
  criticality?: Criticality | null;
  description?: string | null;
  /** `0` clears; `null` leaves unchanged on update. */
  ownerId?: number | null;
  teamId?: number | null;
  serviceId?: number | null;
  attributes?: Record<string, string | number | boolean | null> | null;
}

export interface CiHistoryDto {
  id: number;
  at: string;
  actorName: string | null;
  action: string;
  fromValue: string | null;
  toValue: string | null;
  note: string | null;
}

export interface ImpactNode {
  ci: CiRef;
  criticality: Criticality | null;
  depth: number;
  via: RelationshipType | null;
  viaCiId: number | null;
}

export interface ImpactAnalysis {
  ci: CiRef;
  direction: string;
  depth: number;
  upstream: ImpactNode[];
  downstream: ImpactNode[];
  impactedServices: { ciId: number; serviceId: number; code: string; name: string; tier: number; status: 'OPERATIONAL' | 'DEGRADED' | 'OUTAGE' | string }[];
  openIncidents: IncidentSummary[];
  openAlerts: { id: number; number: string; title: string; severity: AlertSeverity; status: AlertStatus; ciId: number | null; ciName: string | null }[];
  truncated: boolean;
}

export interface GraphNode {
  id: number;
  name: string;
  ciType: CiType;
  status: CiStatus;
  environment: CiEnvironment | null;
  criticality: Criticality | null;
  serviceId: number | null;
  openAlerts: number;
  depth: number;
}

export interface GraphEdge {
  id: number;
  source: number;
  target: number;
  type: RelationshipType;
  origin: RelationshipOrigin;
}

export interface CmdbGraph {
  rootId: number | null;
  depth: number;
  nodes: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
}

// =================================================================== assets

export type AssetType = 'LAPTOP' | 'DESKTOP' | 'SERVER' | 'NETWORK' | 'MOBILE' | 'MONITOR' | 'PERIPHERAL' | 'SOFTWARE_LICENSE' | 'OTHER';
export type AssetStatus = 'IN_STOCK' | 'IN_USE' | 'IN_REPAIR' | 'RETIRED' | 'DISPOSED';
export type AssetCategory = 'HARDWARE' | 'SOFTWARE';
export type WarrantyStatus = 'NONE' | 'ACTIVE' | 'EXPIRING' | 'EXPIRED';
export type MaintenanceType = 'REPAIR' | 'PREVENTIVE' | 'UPGRADE' | 'INSPECTION';

export interface AssetSummary {
  id: number;
  assetTag: string;
  name: string;
  assetType: AssetType;
  category: AssetCategory;
  status: AssetStatus;
  manufacturer: string | null;
  model: string | null;
  serialNumber: string | null;
  assignedToId: number | null;
  assignedToName: string | null;
  assignedAt: string | null;
  departmentId: number | null;
  departmentName: string | null;
  location: string | null;
  warrantyExpires: string | null;
  warrantyStatus: WarrantyStatus;
  licenseSeats: number | null;
  seatsUsed: number;
  licenseExpires: string | null;
  ciId: number | null;
  ciName: string | null;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SeatDto {
  assignmentId: number;
  userId: number;
  userName: string;
  userEmail: string | null;
  assignedAt: string;
  assignedByName: string | null;
}

export interface MaintenanceDto {
  id: number;
  maintenanceType: MaintenanceType;
  status: 'SCHEDULED' | 'COMPLETED';
  description: string;
  vendor: string | null;
  cost: number | null;
  performedOn: string;
  nextDue: string | null;
  createdByName: string | null;
  createdAt: string;
}

export interface AssetDetail {
  asset: AssetSummary;
  vendor: string | null;
  purchaseOrder: string | null;
  purchaseCost: number | null;
  currency: string | null;
  purchaseDate: string | null;
  notes: string | null;
  retiredAt: string | null;
  disposedAt: string | null;
  seats: SeatDto[];
  maintenance: MaintenanceDto[];
  allowedTransitions: AssetStatus[];
  createdByName: string | null;
}

export interface AssetRequestBody {
  assetTag?: string | null;
  name: string;
  assetType: AssetType;
  manufacturer?: string | null;
  model?: string | null;
  serialNumber?: string | null;
  departmentId?: number | null;
  location?: string | null;
  vendor?: string | null;
  purchaseOrder?: string | null;
  purchaseCost?: number | null;
  currency?: string | null;
  purchaseDate?: string | null;
  warrantyExpires?: string | null;
  licenseSeats?: number | null;
  licenseExpires?: string | null;
  ciId?: number | null;
  notes?: string | null;
  assignedToId?: number | null;
}

export interface MaintenanceRequestBody {
  maintenanceType: MaintenanceType;
  status?: 'SCHEDULED' | 'COMPLETED';
  description: string;
  vendor?: string | null;
  cost?: number | null;
  performedOn: string;
  nextDue?: string | null;
}

export interface AssignmentDto {
  id: number;
  userId: number;
  userName: string;
  assignedAt: string;
  assignedByName: string | null;
  returnedAt: string | null;
  returnedByName: string | null;
  note: string | null;
}

export interface AssetEventDto {
  id: number;
  type: string;
  fromValue: string | null;
  toValue: string | null;
  note: string | null;
  actorName: string | null;
  at: string;
}

export interface ExpiringItem {
  asset: AssetSummary;
  kind: 'WARRANTY' | 'LICENSE';
  expiresOn: string;
  daysLeft: number;
  expired: boolean;
}

export interface AssetStats {
  total: number;
  byStatus: Partial<Record<AssetStatus, number>>;
  byType: Partial<Record<AssetType, number>>;
  hardware: number;
  licences: number;
  seatsTotal: number;
  seatsUsed: number;
  expiringWithinWindow: number;
  expired: number;
  warningDays: number;
  totalCost: number | null;
  mixedCurrencies: boolean;
}

export interface ImportError {
  row: number;
  field: string | null;
  message: string;
}

export interface ImportRow {
  row: number;
  assetTag: string | null;
  name: string | null;
  assetType: string | null;
  status: string | null;
  action: 'CREATE' | 'UPDATE';
}

export interface ImportResult {
  dryRun: boolean;
  applied: boolean;
  totalRows: number;
  validRows: number;
  created: number;
  updated: number;
  errors: ImportError[];
  rows: ImportRow[];
}

// =================================================================== alerts

export type AlertSeverity = 'CRITICAL' | 'MAJOR' | 'MINOR' | 'WARNING' | 'INFO';
export type AlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | 'SUPPRESSED';
export type AlertSourceKind = 'PROMETHEUS' | 'GRAFANA' | 'CLOUDWATCH' | 'GENERIC' | 'INTEGRATION' | 'MANUAL';
export type AlertProvider = 'PROMETHEUS' | 'GRAFANA' | 'CLOUDWATCH' | 'GENERIC';
export type AlertAction = 'ACKNOWLEDGE' | 'RESOLVE' | 'SUPPRESS' | 'UNSUPPRESS' | 'ASSIGN' | 'CONVERT' | 'LINK_INCIDENT';
export type AlertScope = 'all' | 'mine' | 'unassigned';

export interface AlertSummary {
  id: number;
  number: string;
  title: string;
  severity: AlertSeverity;
  status: AlertStatus;
  firing: boolean;
  sourceKind: AlertSourceKind;
  sourceId: number | null;
  sourceName: string | null;
  groupKey: string | null;
  serviceId: number | null;
  serviceName: string | null;
  ciId: number | null;
  ciName: string | null;
  teamId: number | null;
  teamName: string | null;
  incidentId: number | null;
  incidentNumber: string | null;
  occurrenceCount: number;
  firstSeenAt: string;
  lastSeenAt: string;
  acknowledgedAt: string | null;
  acknowledgedByName: string | null;
  resolvedAt: string | null;
  escalationLevel: number;
  nextEscalationAt: string | null;
  demo: boolean;
  /** Release 4: generated by simulation mode. */
  simulated?: boolean;
  correlationGroupId?: number | null;
}

export interface AlertEventDto {
  id: number;
  type: string;
  actorName: string | null;
  note: string | null;
  details: Record<string, unknown> | null;
  at: string;
}

export interface AlertDetail {
  alert: AlertSummary;
  description: string | null;
  fingerprint: string;
  labels: Record<string, string> | null;
  annotations: Record<string, string> | null;
  externalUrl: string | null;
  startsAt: string | null;
  endsAt: string | null;
  resolvedByName: string | null;
  resolutionNote: string | null;
  suppressedAt: string | null;
  suppressedUntil: string | null;
  suppressionRuleId: number | null;
  suppressionRuleName: string | null;
  routingRuleId: number | null;
  routingRuleName: string | null;
  integrationId: number | null;
  integrationName: string | null;
  incident: IncidentSummary | null;
  groupAlerts: AlertSummary[];
  history: AlertEventDto[];
  allowedActions: AlertAction[];
  escalationPolicy: string | null;
}

export interface AlertCounts {
  open: number;
  acknowledged: number;
  suppressed: number;
  active: number;
  resolvedLast24h: number;
  bySeverity: Partial<Record<AlertSeverity, number>>;
  criticalOpen: number;
  escalated: number;
  withIncident: number;
}

export interface AlertGroup {
  groupKey: string;
  title: string;
  alertCount: number;
  firingCount: number;
  maxSeverity: AlertSeverity;
  occurrences: number;
  firstSeenAt: string;
  lastSeenAt: string;
  incidentId: number | null;
  incidentNumber: string | null;
  sourceKind: AlertSourceKind | null;
  byStatus: Partial<Record<AlertStatus, number>>;
}

export interface CreateAlertBody {
  title: string;
  description?: string | null;
  severity?: AlertSeverity | null;
  status?: 'firing' | 'resolved';
  fingerprint?: string | null;
  groupKey?: string | null;
  serviceId?: number | null;
  ciId?: number | null;
  labels?: Record<string, string> | null;
  annotations?: Record<string, string> | null;
  externalUrl?: string | null;
}

export interface ConvertAlertBody {
  title?: string | null;
  description?: string | null;
  impact?: Level | null;
  urgency?: Level | null;
}

export interface ConvertResult {
  alert: AlertDetail;
  incidentId: number;
  incidentNumber: string;
  duplicate: boolean;
  parentNumber: string | null;
  linkedAlerts: number;
}

export interface BulkResult {
  processed: number;
  skipped: number;
  messages: string[];
}

export interface AlertSourceDto {
  id: number;
  name: string;
  provider: AlertProvider;
  defaultServiceId: number | null;
  defaultServiceName: string | null;
  defaultCiId: number | null;
  defaultCiName: string | null;
  defaultSeverity: AlertSeverity | null;
  topicArn: string | null;
  subscriptionStatus: 'NONE' | 'PENDING' | 'CONFIRMED' | 'UNSUBSCRIBED';
  subscriptionArn: string | null;
  subscriptionConfirmedAt: string | null;
  active: boolean;
  webhookPath: string;
  authentication: string;
  lastReceivedAt: string | null;
  demo: boolean;
  createdAt: string;
}

export interface AlertSourceRequest {
  name: string;
  provider: AlertProvider;
  defaultServiceId?: number | null;
  defaultCiId?: number | null;
  defaultSeverity?: AlertSeverity | null;
  topicArn?: string | null;
  active?: boolean;
}

export interface AlertSourceSaveResult {
  source: AlertSourceDto;
  secret: string | null;
}

export interface AlertDeliveryDto {
  id: number;
  deliveryId: string | null;
  eventType: string | null;
  outcome: 'PROCESSED' | 'IGNORED' | 'FAILED' | string;
  message: string | null;
  alertCount: number;
  receivedAt: string;
}

export interface MatchCriteria {
  labels?: Record<string, string> | null;
  sources?: AlertSourceKind[] | null;
  severities?: AlertSeverity[] | null;
  serviceIds?: number[] | null;
  ciIds?: number[] | null;
  sourceIds?: number[] | null;
  titleContains?: string | null;
}

export interface SuppressionRuleDto {
  id: number;
  name: string;
  description: string | null;
  enabled: boolean;
  match: MatchCriteria | null;
  startsAt: string | null;
  endsAt: string | null;
  dailyStart: string | null;
  dailyEnd: string | null;
  daysOfWeek: number[] | null;
  activeNow: boolean;
  expired: boolean;
  matchCount: number;
  lastMatchedAt: string | null;
  createdByName: string | null;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SuppressionRuleRequest {
  name: string;
  description?: string | null;
  enabled?: boolean;
  match?: MatchCriteria | null;
  startsAt?: string | null;
  endsAt?: string | null;
  dailyStart?: string | null;
  dailyEnd?: string | null;
  daysOfWeek?: number[] | null;
}

export interface RoutingRuleDto {
  id: number;
  name: string;
  description: string | null;
  enabled: boolean;
  sortOrder: number;
  match: MatchCriteria | null;
  teamId: number | null;
  teamName: string | null;
  serviceId: number | null;
  serviceName: string | null;
  ciId: number | null;
  ciName: string | null;
  createIncident: boolean;
  incidentMinSeverity: AlertSeverity | null;
  matchCount: number;
  lastMatchedAt: string | null;
  createdByName: string | null;
  demo: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RoutingRuleRequest {
  name: string;
  description?: string | null;
  enabled?: boolean;
  sortOrder?: number | null;
  match?: MatchCriteria | null;
  teamId?: number | null;
  serviceId?: number | null;
  ciId?: number | null;
  createIncident?: boolean;
  incidentMinSeverity?: AlertSeverity | null;
}
