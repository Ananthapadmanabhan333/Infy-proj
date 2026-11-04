import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { downloadFile, getJson } from '../lib/api';
import type { MetricDefinition, OperationsMetrics, SlaRiskOverview } from '../lib/responseTypes';
import type { AnalyticsFilters, AnalyticsOverview, DashboardDto, ForecastDto } from '../lib/types';
import { STALE } from './common';

/** A bare number is the Release 1 `days` period; an object carries the §8 filters. */
export type AnalyticsQuery = number | AnalyticsFilters;

/** Normalises filters into request params (`from`/`to` win over `days`). */
export function analyticsParams(query: AnalyticsQuery): Record<string, string | number | undefined> {
  const filters: AnalyticsFilters = typeof query === 'number' ? { days: query } : query;
  return {
    from: filters.from || undefined,
    to: filters.to || undefined,
    days: filters.from || filters.to ? undefined : filters.days,
    departmentId: filters.departmentId ?? undefined,
  };
}

export function useDashboard(query: AnalyticsQuery) {
  const params = analyticsParams(query);
  return useQuery({
    queryKey: ['dashboard', params],
    queryFn: () => getJson<DashboardDto>('/analytics/dashboard', params),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
    refetchInterval: 60_000,
  });
}

export function useAnalyticsOverview(query: AnalyticsQuery, enabled = true) {
  const params = analyticsParams(query);
  return useQuery({
    queryKey: ['analytics', 'overview', params],
    queryFn: () => getJson<AnalyticsOverview>('/analytics/overview', params),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
    enabled,
  });
}

export function useForecast(query: AnalyticsQuery, horizon: number) {
  const params = analyticsParams(query);
  return useQuery({
    queryKey: ['analytics', 'forecast', params, horizon],
    queryFn: () => getJson<ForecastDto>('/analytics/forecast', { ...params, horizon }),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
  });
}

/** §8 GET /analytics/export?format=csv|pdf — downloaded with the bearer token; audited server side. */
export function useAnalyticsExport() {
  return useMutation({
    mutationFn: ({ format, filters }: { format: 'csv' | 'pdf'; filters: AnalyticsFilters }) =>
      downloadFile('/analytics/export', `nexus360-analytics.${format}`, { ...analyticsParams(filters), format }),
  });
}

/** R3 BE-4 `GET /analytics/operations` — MTTA, MTTD, FCR, change outcomes, problem recurrence, alerts, assets, on-call, departments, availability. */
export function useOperationsMetrics(query: AnalyticsQuery, enabled = true) {
  const params = analyticsParams(query);
  return useQuery({
    queryKey: ['analytics', 'operations', params],
    queryFn: () => getJson<OperationsMetrics>('/analytics/operations', params),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
    enabled,
  });
}

/** `GET /analytics/definitions` — how every metric is computed (any user). */
export function useMetricDefinitions(enabled = true) {
  return useQuery({
    queryKey: ['analytics', 'definitions'],
    queryFn: () => getJson<MetricDefinition[]>('/analytics/definitions'),
    staleTime: STALE.reference,
    enabled,
  });
}

/** `GET /analytics/sla-risk?limit` — active incidents ranked by computed breach probability (statistics, no LLM). */
export function useSlaRiskOverview(limit = 10, enabled = true) {
  return useQuery({
    queryKey: ['analytics', 'sla-risk', limit],
    queryFn: () => getJson<SlaRiskOverview>('/analytics/sla-risk', { limit }),
    staleTime: STALE.live,
    refetchInterval: 60_000,
    enabled,
  });
}
