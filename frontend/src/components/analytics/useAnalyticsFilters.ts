import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PERIOD_OPTIONS, type PeriodDays } from '../../lib/constants';
import { formatDate } from '../../lib/format';
import type { AnalyticsFilters } from '../../lib/types';

export const DEFAULT_ANALYTICS_DAYS: PeriodDays = 30;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A valid "YYYY-MM-DD" calendar date, or ''. */
export function validIsoDate(value: string | null | undefined): string {
  if (!value || !ISO_DATE.test(value)) return '';
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime()) ? '' : value;
}

export interface AnalyticsFilterState {
  /** What the §8 endpoints receive: `{ days }` or `{ from, to }`, plus `departmentId`. */
  filters: AnalyticsFilters;
  days: PeriodDays;
  from: string;
  to: string;
  departmentId: number | null;
  /** True when a custom from/to range replaces the 7/30/90-day preset. */
  custom: boolean;
  /** "Last 30 days" or "01 Oct 2026 – 31 Oct 2026". */
  rangeLabel: string;
  setPeriod: (days: PeriodDays) => void;
  /** Sets a custom range (and, when given, the department) in one URL update. */
  setRange: (from: string, to: string, departmentId?: number | null) => void;
  setDepartment: (departmentId: number | null) => void;
  reset: () => void;
}

export function describeRange(custom: boolean, days: number, from: string, to: string): string {
  if (!custom) return `Last ${days} days`;
  if (from && to) return `${formatDate(from)} – ${formatDate(to)}`;
  if (from) return `Since ${formatDate(from)}`;
  return `Until ${formatDate(to)}`;
}

/**
 * Analytics filters shared by every tab and the export, kept in the URL:
 * `?days=7|30|90` or `?from=YYYY-MM-DD&to=YYYY-MM-DD`, plus `?department=<id>`.
 */
export function useAnalyticsFilters(): AnalyticsFilterState {
  const [params, setParams] = useSearchParams();
  const rawDays = Number.parseInt(params.get('days') ?? '', 10);
  const days = (PERIOD_OPTIONS as readonly number[]).includes(rawDays) ? (rawDays as PeriodDays) : DEFAULT_ANALYTICS_DAYS;
  const from = validIsoDate(params.get('from'));
  const to = validIsoDate(params.get('to'));
  const rawDepartment = Number.parseInt(params.get('department') ?? '', 10);
  const departmentId = Number.isFinite(rawDepartment) && rawDepartment > 0 ? rawDepartment : null;
  const custom = Boolean(from || to);

  const filters = useMemo<AnalyticsFilters>(
    () => (custom ? { from: from || undefined, to: to || undefined, departmentId } : { days, departmentId }),
    [custom, from, to, days, departmentId],
  );

  const update = useCallback(
    (patch: Record<string, string | null>) =>
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          Object.entries(patch).forEach(([key, value]) => {
            if (value) next.set(key, value);
            else next.delete(key);
          });
          return next;
        },
        { replace: true },
      ),
    [setParams],
  );

  const setPeriod = useCallback((value: PeriodDays) => update({ days: String(value), from: null, to: null }), [update]);
  // One URL update per action: react-router's functional setter reads the params of the last render.
  const setRange = useCallback(
    (nextFrom: string, nextTo: string, nextDepartment?: number | null) =>
      update({
        from: validIsoDate(nextFrom) || null,
        to: validIsoDate(nextTo) || null,
        days: null,
        ...(nextDepartment !== undefined ? { department: nextDepartment ? String(nextDepartment) : null } : {}),
      }),
    [update],
  );
  const setDepartment = useCallback((id: number | null) => update({ department: id ? String(id) : null }), [update]);
  const reset = useCallback(() => update({ days: null, from: null, to: null, department: null }), [update]);

  return {
    filters,
    days,
    from,
    to,
    departmentId,
    custom,
    rangeLabel: describeRange(custom, days, from, to),
    setPeriod,
    setRange,
    setDepartment,
    reset,
  };
}
