import { PALETTE } from '../../lib/constants';
import type { Category, ServiceDto, ServiceStatus } from '../../lib/types';

export const SERVICE_STATUSES: ServiceStatus[] = ['OPERATIONAL', 'DEGRADED', 'OUTAGE', 'MAINTENANCE'];

export const SERVICE_STATUS_LABELS: Record<ServiceStatus, string> = {
  OPERATIONAL: 'Operational',
  DEGRADED: 'Degraded',
  OUTAGE: 'Outage',
  MAINTENANCE: 'Maintenance',
};

/** Bar / dot colour per effective service status (§15 palette). */
export const SERVICE_STATUS_COLOR: Record<ServiceStatus, string> = {
  OPERATIONAL: PALETTE.success,
  DEGRADED: PALETTE.warning,
  OUTAGE: PALETTE.critical,
  MAINTENANCE: PALETTE.champagne,
};

export const TIER_LABELS: Record<1 | 2 | 3, string> = {
  1: 'Tier 1 — business critical',
  2: 'Tier 2 — important',
  3: 'Tier 3 — standard',
};

/** "99.9%", "97.25%", "100%" — up to two decimals without trailing zeros. */
export function formatAvailability(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${Number(value.toFixed(2))}%`;
}

/** Availability colour: ≥ 99.5 green, ≥ 98 amber, below red. */
export function availabilityTone(value: number | null | undefined): 'green-text' | 'warning-text' | 'red-text' | 'muted-text' {
  if (value === null || value === undefined || Number.isNaN(value)) return 'muted-text';
  if (value >= 99.5) return 'green-text';
  if (value >= 98) return 'warning-text';
  return 'red-text';
}

/**
 * Fields the API returns on services beyond the shared contract. `defaultCategory` is passed back unchanged on save so
 * an edit never clears it; `dependsOnIds` (when present) seeds the dependency picker.
 */
export type ServiceWithExtras = ServiceDto & { defaultCategory?: Category | null; dependsOnIds?: number[] | null };

export interface ServiceFilterValues {
  q: string;
  status: string;
  tier: string;
  department: string;
}

export function filterServices(services: ServiceDto[], filters: ServiceFilterValues): ServiceDto[] {
  const term = filters.q.trim().toLowerCase();
  return services
    .filter((service) => !filters.status || service.status === filters.status)
    .filter((service) => !filters.tier || String(service.tier) === filters.tier)
    .filter((service) => !filters.department || String(service.departmentId) === filters.department)
    .filter(
      (service) =>
        !term ||
        `${service.name} ${service.code} ${service.departmentName} ${service.ownerName ?? ''} ${service.keywords.join(' ')}`.toLowerCase().includes(term),
    );
}

/**
 * Services that may not be chosen as dependencies of `selfId`: itself, plus every service that already depends on it
 * directly or transitively (choosing one would create a cycle). Uses `dependsOnIds` when the list provides it and the
 * known direct dependents otherwise; the API remains the authority and reports any cycle it finds.
 */
export function blockedDependencyIds(services: ServiceWithExtras[], selfId: number | null, directDependentIds: number[] = []): Set<number> {
  const blocked = new Set<number>();
  if (selfId === null) return blocked;
  blocked.add(selfId);
  const dependentsOf = new Map<number, number[]>();
  services.forEach((service) => {
    (service.dependsOnIds ?? []).forEach((target) => {
      const list = dependentsOf.get(target) ?? [];
      list.push(service.id);
      dependentsOf.set(target, list);
    });
  });
  const queue = [...directDependentIds, ...(dependentsOf.get(selfId) ?? [])];
  while (queue.length) {
    const next = queue.shift() as number;
    if (blocked.has(next)) continue;
    blocked.add(next);
    queue.push(...(dependentsOf.get(next) ?? []));
  }
  return blocked;
}
