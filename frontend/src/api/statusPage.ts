import { useMutation, useQuery } from '@tanstack/react-query';
import { api, getJson } from '../lib/api';

/**
 * Public status page (RELEASE3_SPEC §4, backend `StatusPageController` / `StatusPageDtos.PublicStatus`):
 * `GET /api/public/status` (404 unless published) → `{ title, description, overall, generatedAt,
 * components: [{ id, name, group, status, uptime90d, history: [{ date, availability, maintenance }] }],
 * maintenance: [{ id, title, description, startsAt, endsAt, status, components: string[] }],
 * updates: [{ id, title, status, message, postedAt }], subscriptionsAvailable }`;
 * `POST /api/public/status/subscribe {email}` (202) and `POST /api/public/status/{confirm|unsubscribe} {token}`, which
 * always answer with the same generic `{ message }`. No auth.
 *
 * The response is normalised defensively (a few field-name variants are accepted and anything missing degrades to
 * "no data") so a contract change never breaks the public page.
 */

export type ComponentStatus = 'OPERATIONAL' | 'DEGRADED' | 'PARTIAL_OUTAGE' | 'OUTAGE' | 'MAINTENANCE' | 'UNKNOWN';
export type UpdateStatus = 'INVESTIGATING' | 'IDENTIFIED' | 'MONITORING' | 'RESOLVED';
export type MaintenanceStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

export interface UptimeDay {
  date: string;
  /** 0–100, or null when no data was recorded for the day. */
  uptime: number | null;
  status: ComponentStatus | null;
}

export interface StatusComponent {
  id: string;
  name: string;
  group: string | null;
  status: ComponentStatus;
  /** Uptime over the history window (0–100), or null when unknown. */
  uptime: number | null;
  history: UptimeDay[];
}

export interface StatusUpdateEntry {
  id: string;
  status: UpdateStatus;
  message: string;
  postedAt: string | null;
}

export interface StatusIncident {
  id: string;
  title: string;
  status: UpdateStatus;
  components: string[];
  startedAt: string | null;
  updates: StatusUpdateEntry[];
}

export interface StatusMaintenance {
  id: string;
  title: string;
  description: string | null;
  status: MaintenanceStatus;
  startsAt: string | null;
  endsAt: string | null;
  components: string[];
}

export interface PublicStatus {
  title: string;
  description: string | null;
  overall: ComponentStatus;
  components: StatusComponent[];
  incidents: StatusIncident[];
  maintenance: StatusMaintenance[];
  updatedAt: string | null;
  historyDays: number;
  /** False when the deployment cannot send e-mail (SMTP not configured) — the subscribe form is then not offered. */
  subscriptionsAvailable: boolean;
}

// ------------------------------------------------------------------ normalisation
type Raw = Record<string, unknown>;

function asRecord(value: unknown): Raw {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Raw) : {};
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function pick(record: Raw, ...keys: string[]): unknown {
  for (const key of keys) {
    if (record[key] !== undefined && record[key] !== null) return record[key];
  }
  return undefined;
}

function text(value: unknown): string | null {
  if (typeof value === 'string') return value.trim() || null;
  if (typeof value === 'number') return String(value);
  return null;
}

function percent(value: unknown): number | null {
  const number = typeof value === 'string' ? Number.parseFloat(value) : typeof value === 'number' ? value : NaN;
  if (!Number.isFinite(number)) return null;
  // Accept 0–1 ratios as well as 0–100 percentages.
  const scaled = number <= 1 && number >= 0 && !Number.isInteger(number) ? number * 100 : number;
  return Math.min(100, Math.max(0, scaled));
}

const COMPONENT_STATUS_ALIASES: Record<string, ComponentStatus> = {
  OPERATIONAL: 'OPERATIONAL',
  UP: 'OPERATIONAL',
  OK: 'OPERATIONAL',
  DEGRADED: 'DEGRADED',
  DEGRADED_PERFORMANCE: 'DEGRADED',
  PARTIAL_OUTAGE: 'PARTIAL_OUTAGE',
  OUTAGE: 'OUTAGE',
  MAJOR_OUTAGE: 'OUTAGE',
  DOWN: 'OUTAGE',
  MAINTENANCE: 'MAINTENANCE',
  UNDER_MAINTENANCE: 'MAINTENANCE',
};

export function componentStatus(value: unknown): ComponentStatus {
  const key = typeof value === 'string' ? value.trim().toUpperCase().replace(/[\s-]+/g, '_') : '';
  return COMPONENT_STATUS_ALIASES[key] ?? 'UNKNOWN';
}

function updateStatus(value: unknown): UpdateStatus {
  const key = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return (['INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED'] as const).find((status) => status === key) ?? 'INVESTIGATING';
}

function maintenanceStatus(value: unknown): MaintenanceStatus {
  const key = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return (['SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const).find((status) => status === key) ?? 'SCHEDULED';
}

function names(value: unknown): string[] {
  return asArray(value)
    .map((entry) => (typeof entry === 'string' ? entry : text(pick(asRecord(entry), 'displayName', 'name', 'title'))))
    .filter((entry): entry is string => Boolean(entry));
}

/** Severity order used to derive the overall status from the components. */
const SEVERITY: ComponentStatus[] = ['OPERATIONAL', 'UNKNOWN', 'MAINTENANCE', 'DEGRADED', 'PARTIAL_OUTAGE', 'OUTAGE'];

export function worstStatus(statuses: ComponentStatus[]): ComponentStatus {
  if (!statuses.length) return 'UNKNOWN';
  return statuses.reduce((worst, status) => (SEVERITY.indexOf(status) > SEVERITY.indexOf(worst) ? status : worst), 'OPERATIONAL');
}

function normaliseDay(entry: unknown): UptimeDay | null {
  const record = asRecord(entry);
  const date = text(pick(record, 'date', 'day'));
  if (!date) return null;
  const status = pick(record, 'status', 'state');
  return {
    date,
    uptime: percent(pick(record, 'uptime', 'uptimePercent', 'availability', 'availabilityPercent')),
    // `maintenance: true` = a maintenance window touched the day.
    status: record.maintenance === true ? 'MAINTENANCE' : status === undefined ? null : componentStatus(status),
  };
}

function normaliseComponent(entry: unknown, index: number): StatusComponent {
  const record = asRecord(entry);
  const history = asArray(pick(record, 'history', 'uptimeHistory', 'availabilityHistory', 'days', 'uptimeDays'))
    .map(normaliseDay)
    .filter((day): day is UptimeDay => day !== null)
    .sort((a, b) => a.date.localeCompare(b.date));
  const known = history.map((day) => day.uptime).filter((value): value is number => value !== null);
  const stated = percent(pick(record, 'uptime', 'uptimePercent', 'uptime90d', 'availability90d', 'availability'));
  return {
    id: text(pick(record, 'id', 'componentId')) ?? `component-${index}`,
    name: text(pick(record, 'displayName', 'name', 'serviceName')) ?? `Component ${index + 1}`,
    group: text(pick(record, 'group', 'groupName')),
    status: componentStatus(pick(record, 'status', 'currentStatus', 'state')),
    uptime: stated ?? (known.length ? known.reduce((sum, value) => sum + value, 0) / known.length : null),
    history,
  };
}

function normaliseUpdate(entry: unknown, index: number): StatusUpdateEntry {
  const record = asRecord(entry);
  return {
    id: text(pick(record, 'id')) ?? `update-${index}`,
    status: updateStatus(pick(record, 'status')),
    message: text(pick(record, 'message', 'body', 'text')) ?? '',
    postedAt: text(pick(record, 'postedAt', 'createdAt', 'publishedAt', 'at')),
  };
}

/** Flat public updates (the backend shape): updates sharing a title form one entry with a timeline, newest first. */
function groupUpdates(entries: unknown[]): StatusIncident[] {
  const byTitle = new Map<string, StatusIncident>();
  entries.forEach((entry, index) => {
    const record = asRecord(entry);
    const title = text(pick(record, 'title', 'name')) ?? 'Service update';
    const update = normaliseUpdate(entry, index);
    const existing = byTitle.get(title.toLowerCase());
    if (existing) existing.updates.push(update);
    else
      byTitle.set(title.toLowerCase(), {
        id: text(pick(record, 'id')) ?? `update-${index}`,
        title,
        status: update.status,
        components: names(pick(record, 'components', 'affectedComponents', 'services')),
        startedAt: update.postedAt,
        updates: [update],
      });
  });
  return [...byTitle.values()].map((incident) => {
    const updates = [...incident.updates].sort((a, b) => (b.postedAt ?? '').localeCompare(a.postedAt ?? ''));
    return { ...incident, updates, status: updates[0].status, startedAt: updates[updates.length - 1].postedAt };
  });
}

/**
 * Incidents shown on the page: either grouped (`incidents: [{ title, status, updates: [...] }]`) or the flat
 * `updates` list of the public API.
 */
function normaliseIncidents(raw: Raw): StatusIncident[] {
  const grouped = asArray(pick(raw, 'incidents', 'activeIncidents'));
  if (!grouped.length) return groupUpdates(asArray(pick(raw, 'updates', 'activeUpdates', 'statusUpdates')));
  return grouped.map((entry, index) => {
    const record = asRecord(entry);
    const nested = asArray(pick(record, 'updates', 'timeline'));
    const updates = (nested.length ? nested : [entry]).map(normaliseUpdate).sort((a, b) => (b.postedAt ?? '').localeCompare(a.postedAt ?? ''));
    return {
      id: text(pick(record, 'id', 'incidentId')) ?? `incident-${index}`,
      title: text(pick(record, 'title', 'name')) ?? 'Service incident',
      status: updateStatus(pick(record, 'status') ?? updates[0]?.status),
      components: names(pick(record, 'components', 'affectedComponents', 'services')),
      startedAt: text(pick(record, 'startedAt', 'createdAt', 'postedAt')),
      updates,
    };
  });
}

function normaliseMaintenance(entry: unknown, index: number): StatusMaintenance {
  const record = asRecord(entry);
  return {
    id: text(pick(record, 'id')) ?? `maintenance-${index}`,
    title: text(pick(record, 'title', 'name')) ?? 'Scheduled maintenance',
    description: text(pick(record, 'description', 'message')),
    status: maintenanceStatus(pick(record, 'status')),
    startsAt: text(pick(record, 'startsAt', 'startAt', 'start', 'scheduledStart')),
    endsAt: text(pick(record, 'endsAt', 'endAt', 'end', 'scheduledEnd')),
    components: names(pick(record, 'components', 'affectedComponents', 'services')),
  };
}

export function normalisePublicStatus(input: unknown): PublicStatus {
  const raw = asRecord(input);
  const settings = asRecord(pick(raw, 'settings', 'page'));
  const components = asArray(pick(raw, 'components', 'services')).map(normaliseComponent);
  const overallRaw = pick(raw, 'overallStatus', 'overall', 'status');
  const overall = overallRaw !== undefined ? componentStatus(typeof overallRaw === 'object' ? pick(asRecord(overallRaw), 'status') : overallRaw) : 'UNKNOWN';
  // The bars always span the published window (90 days by default); days without data are shown as such.
  const statedDays = Number(pick(raw, 'historyDays', 'uptimeDays', 'days'));
  const longest = components.reduce((max, component) => Math.max(max, component.history.length), 0);
  const historyDays = Number.isInteger(statedDays) && statedDays > 0 && statedDays <= 366 ? statedDays : Math.max(90, longest);
  return {
    title: text(pick(raw, 'title')) ?? text(pick(settings, 'title')) ?? 'Service status',
    description: text(pick(raw, 'description')) ?? text(pick(settings, 'description')),
    overall: overall === 'UNKNOWN' ? worstStatus(components.map((component) => component.status)) : overall,
    components,
    incidents: normaliseIncidents(raw),
    maintenance: asArray(pick(raw, 'maintenance', 'maintenanceWindows', 'scheduledMaintenance'))
      .map(normaliseMaintenance)
      .filter((window) => window.status === 'SCHEDULED' || window.status === 'IN_PROGRESS'),
    updatedAt: text(pick(raw, 'updatedAt', 'generatedAt', 'asOf')),
    historyDays,
    subscriptionsAvailable: raw.subscriptionsAvailable !== false,
  };
}

// ------------------------------------------------------------------ hooks
export function usePublicStatus() {
  return useQuery({
    queryKey: ['public-status'],
    queryFn: async () => normalisePublicStatus(await getJson<unknown>('/public/status', undefined, { skipAuthRefresh: true, timeout: 10_000 })),
    retry: false,
    refetchInterval: 60_000,
    staleTime: 30_000,
  });
}

/** The API's generic `{ message }` answer, when it sent one. */
function messageOf(data: unknown): string | null {
  return text(asRecord(data).message);
}

/** Resolves with the API's generic confirmation message (null when none was sent). */
export function useSubscribeToStatus() {
  return useMutation({
    mutationFn: (email: string) => api.post('/public/status/subscribe', { email }, { skipAuthRefresh: true }).then((response) => messageOf(response.data)),
  });
}

export type SubscriptionAction = 'confirm' | 'unsubscribe';

/** Token format of the e-mail links (URL-safe Base64, as the backend validates it). */
export const SUBSCRIPTION_TOKEN = /^[A-Za-z0-9_-]{16,200}$/;

/**
 * Confirms a subscription or unsubscribes with the token from the e-mail link. The API answers the same for valid
 * and unknown tokens, so the resolved message is generic by design.
 */
export function useStatusSubscriptionAction() {
  return useMutation({
    mutationFn: ({ action, token }: { action: SubscriptionAction; token: string }) =>
      api.post(`/public/status/${action}`, { token }, { skipAuthRefresh: true }).then((response) => messageOf(response.data)),
  });
}
