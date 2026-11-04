import type { Tone } from './constants';
import { titleCase } from './format';
import type {
  AlertProvider,
  AlertSeverity,
  AlertSourceKind,
  AlertStatus,
  AssetStatus,
  AssetType,
  CiEnvironment,
  CiStatus,
  CiType,
  Criticality,
  MaintenanceType,
  RelationshipType,
  WarrantyStatus,
} from './opsTypes';

/** Labels and badge tones for the Release 3 BE-3 modules (CMDB, assets, alerts). */

// ------------------------------------------------------------------ CMDB
export const CI_TYPES: CiType[] = ['BUSINESS_SERVICE', 'APPLICATION', 'SERVER', 'DATABASE', 'NETWORK_DEVICE', 'CLOUD_RESOURCE', 'OPERATING_SYSTEM', 'SOFTWARE', 'INFRASTRUCTURE'];
export const CI_TYPE_LABELS: Record<CiType, string> = {
  APPLICATION: 'Application',
  SERVER: 'Server',
  DATABASE: 'Database',
  NETWORK_DEVICE: 'Network device',
  CLOUD_RESOURCE: 'Cloud resource',
  OPERATING_SYSTEM: 'Operating system',
  SOFTWARE: 'Software',
  BUSINESS_SERVICE: 'Business service',
  INFRASTRUCTURE: 'Infrastructure',
};
/** Two-letter glyph drawn inside graph nodes. */
export const CI_TYPE_GLYPHS: Record<CiType, string> = {
  APPLICATION: 'AP',
  SERVER: 'SV',
  DATABASE: 'DB',
  NETWORK_DEVICE: 'NW',
  CLOUD_RESOURCE: 'CL',
  OPERATING_SYSTEM: 'OS',
  SOFTWARE: 'SW',
  BUSINESS_SERVICE: 'BS',
  INFRASTRUCTURE: 'IN',
};
export const CI_STATUSES: CiStatus[] = ['PLANNED', 'ACTIVE', 'MAINTENANCE', 'RETIRED'];
export const CI_STATUS_TONES: Record<CiStatus, Tone> = { PLANNED: 'champagne', ACTIVE: 'success', MAINTENANCE: 'warning', RETIRED: 'muted' };
export const CI_ENVIRONMENTS: CiEnvironment[] = ['PRODUCTION', 'STAGING', 'TEST', 'DEV', 'DR'];
export const CI_ENVIRONMENT_LABELS: Record<CiEnvironment, string> = { PRODUCTION: 'Production', STAGING: 'Staging', TEST: 'Test', DEV: 'Dev', DR: 'DR' };
export const CRITICALITIES: Criticality[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
export const CRITICALITY_TONES: Record<Criticality, Tone> = { CRITICAL: 'critical', HIGH: 'warning', MEDIUM: 'gold', LOW: 'neutral' };
export const RELATIONSHIP_TYPES: RelationshipType[] = ['DEPENDS_ON', 'RUNS_ON', 'HOSTS', 'CONNECTS_TO', 'PART_OF'];
/** Fallback labels when `/cmdb/meta` is unavailable (the API's own wording). */
export const RELATIONSHIP_LABELS: Record<RelationshipType, { outgoing: string; incoming: string }> = {
  DEPENDS_ON: { outgoing: 'depends on', incoming: 'is needed by' },
  RUNS_ON: { outgoing: 'runs on', incoming: 'runs' },
  HOSTS: { outgoing: 'hosts', incoming: 'runs on' },
  CONNECTS_TO: { outgoing: 'connects to', incoming: 'is connected from' },
  PART_OF: { outgoing: 'is part of', incoming: 'contains' },
};

// ------------------------------------------------------------------ assets
export const ASSET_TYPES: AssetType[] = ['LAPTOP', 'DESKTOP', 'SERVER', 'NETWORK', 'MOBILE', 'MONITOR', 'PERIPHERAL', 'SOFTWARE_LICENSE', 'OTHER'];
export const ASSET_TYPE_LABELS: Record<AssetType, string> = {
  LAPTOP: 'Laptop',
  DESKTOP: 'Desktop',
  SERVER: 'Server',
  NETWORK: 'Network',
  MOBILE: 'Mobile',
  MONITOR: 'Monitor',
  PERIPHERAL: 'Peripheral',
  SOFTWARE_LICENSE: 'Software licence',
  OTHER: 'Other',
};
/** End-user devices need a holder to be IN_USE (server-side rule, mirrored for the UI). */
export const END_USER_DEVICE_TYPES: AssetType[] = ['LAPTOP', 'DESKTOP', 'MOBILE', 'MONITOR', 'PERIPHERAL'];
export const ASSET_STATUSES: AssetStatus[] = ['IN_STOCK', 'IN_USE', 'IN_REPAIR', 'RETIRED', 'DISPOSED'];
export const ASSET_STATUS_LABELS: Record<AssetStatus, string> = { IN_STOCK: 'In stock', IN_USE: 'In use', IN_REPAIR: 'In repair', RETIRED: 'Retired', DISPOSED: 'Disposed' };
export const ASSET_STATUS_TONES: Record<AssetStatus, Tone> = { IN_STOCK: 'champagne', IN_USE: 'success', IN_REPAIR: 'warning', RETIRED: 'muted', DISPOSED: 'muted' };
export const WARRANTY_STATUSES: WarrantyStatus[] = ['ACTIVE', 'EXPIRING', 'EXPIRED', 'NONE'];
export const WARRANTY_LABELS: Record<WarrantyStatus, string> = { ACTIVE: 'Active', EXPIRING: 'Expiring soon', EXPIRED: 'Expired', NONE: 'No warranty' };
export const WARRANTY_TONES: Record<WarrantyStatus, Tone> = { ACTIVE: 'success', EXPIRING: 'warning', EXPIRED: 'critical', NONE: 'muted' };
export const MAINTENANCE_TYPES: MaintenanceType[] = ['REPAIR', 'PREVENTIVE', 'UPGRADE', 'INSPECTION'];

/** Lifecycle button labels by target status. */
export function assetTransitionLabel(to: AssetStatus): string {
  switch (to) {
    case 'IN_STOCK':
      return 'Return to stock';
    case 'IN_USE':
      return 'Put in use';
    case 'IN_REPAIR':
      return 'Send to repair';
    case 'RETIRED':
      return 'Retire';
    case 'DISPOSED':
      return 'Dispose';
    default:
      return titleCase(to);
  }
}

// ------------------------------------------------------------------ alerts
export const ALERT_SEVERITIES: AlertSeverity[] = ['CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO'];
export const ALERT_SEVERITY_TONES: Record<AlertSeverity, Tone> = { CRITICAL: 'critical', MAJOR: 'warning', MINOR: 'gold', WARNING: 'champagne', INFO: 'neutral' };
export const ALERT_STATUSES: AlertStatus[] = ['OPEN', 'ACKNOWLEDGED', 'SUPPRESSED', 'RESOLVED'];
export const ALERT_ACTIVE_STATUSES: AlertStatus[] = ['OPEN', 'ACKNOWLEDGED'];
export const ALERT_STATUS_LABELS: Record<AlertStatus, string> = { OPEN: 'Open', ACKNOWLEDGED: 'Acknowledged', SUPPRESSED: 'Suppressed', RESOLVED: 'Resolved' };
export const ALERT_STATUS_TONES: Record<AlertStatus, Tone> = { OPEN: 'critical', ACKNOWLEDGED: 'warning', SUPPRESSED: 'muted', RESOLVED: 'success' };
export const ALERT_SOURCE_KINDS: AlertSourceKind[] = ['PROMETHEUS', 'GRAFANA', 'CLOUDWATCH', 'GENERIC', 'INTEGRATION', 'MANUAL'];
export const ALERT_SOURCE_LABELS: Record<AlertSourceKind, string> = {
  PROMETHEUS: 'Prometheus',
  GRAFANA: 'Grafana',
  CLOUDWATCH: 'AWS CloudWatch',
  GENERIC: 'Generic JSON',
  INTEGRATION: 'Inbound integration',
  MANUAL: 'Manual',
};
export const ALERT_PROVIDERS: AlertProvider[] = ['PROMETHEUS', 'GRAFANA', 'CLOUDWATCH', 'GENERIC'];
export const ALERT_EVENT_LABELS: Record<string, string> = {
  CREATED: 'Created',
  REPEATED: 'Fired again',
  REOPENED: 'Reopened',
  AUTO_RESOLVED: 'Resolved by the source',
  ACKNOWLEDGED: 'Acknowledged',
  RESOLVED: 'Resolved',
  SUPPRESSED: 'Suppressed',
  UNSUPPRESSED: 'Unsuppressed',
  ROUTED: 'Routed',
  ASSIGNED: 'Assigned',
  ESCALATED: 'Escalated',
  CONVERTED: 'Converted to incident',
  LINKED_INCIDENT: 'Linked to incident',
  COMMENT: 'Note',
};
export const WEEKDAY_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/** "3 d left", "today", "12 d ago". */
export function daysLeftLabel(daysLeft: number, expired: boolean): string {
  if (daysLeft === 0) return expired ? 'ended today' : 'ends today';
  if (expired || daysLeft < 0) return `ended ${Math.abs(daysLeft)} d ago`;
  return `${daysLeft} d left`;
}

/** Amount with an ISO currency code (falls back to plain numbers for unknown codes). */
export function formatMoney(amount: number | null | undefined, currency: string | null | undefined): string {
  if (amount === null || amount === undefined || Number.isNaN(Number(amount))) return '—';
  const code = (currency || 'INR').toUpperCase();
  try {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency: code, maximumFractionDigits: 2 }).format(Number(amount));
  } catch {
    return `${Number(amount).toFixed(2)} ${code}`;
  }
}
