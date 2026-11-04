import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type {
  AlertCounts,
  AlertDeliveryDto,
  AlertDetail,
  AlertGroup,
  AlertScope,
  AlertSourceDto,
  AlertSourceRequest,
  AlertSourceSaveResult,
  AlertSummary,
  BulkResult,
  ConvertAlertBody,
  ConvertResult,
  CreateAlertBody,
  RoutingRuleDto,
  RoutingRuleRequest,
  SuppressionRuleDto,
  SuppressionRuleRequest,
} from '../lib/opsTypes';
import type { PageResponse } from '../lib/types';
import { invalidateIncidentData, STALE } from './common';

/** Alerts (docs/API.md "Alerts"): readers read; staff respond; admins configure sources and rules. */
export interface AlertListParams {
  search?: string;
  /** CSV of statuses. */
  status?: string;
  severity?: string;
  source?: string;
  sourceId?: number;
  serviceId?: number;
  ciId?: number;
  teamId?: number;
  incidentId?: number;
  groupKey?: string;
  firing?: boolean;
  hasIncident?: boolean;
  scope?: AlertScope;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const alertKeys = {
  all: ['alerts'] as const,
  list: (params: AlertListParams) => ['alerts', 'list', params] as const,
  counts: (scope?: AlertScope) => ['alerts', 'counts', scope ?? 'all'] as const,
  groups: (params: object) => ['alerts', 'groups', params] as const,
  detail: (id: number) => ['alerts', 'detail', id] as const,
  sources: ['alerts', 'sources'] as const,
  deliveries: (id: number) => ['alerts', 'sources', id, 'deliveries'] as const,
  suppression: ['alerts', 'suppression-rules'] as const,
  routing: ['alerts', 'routing-rules'] as const,
};

/** Alert changes also touch My Work, the CMDB counters and (when converted/linked) incidents. */
function invalidateAlerts(queryClient: QueryClient, detail?: AlertDetail) {
  if (detail) queryClient.setQueryData(alertKeys.detail(detail.alert.id), detail);
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: alertKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['my-work'] }),
    queryClient.invalidateQueries({ queryKey: ['cmdb'] }),
  ]);
}

export function useAlerts(params: AlertListParams, enabled = true) {
  return useQuery({
    queryKey: alertKeys.list(params),
    queryFn: () => getJson<PageResponse<AlertSummary>>('/alerts', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    refetchInterval: 30_000,
    enabled,
  });
}

export function useAlertCounts(scope?: AlertScope, enabled = true) {
  return useQuery({
    queryKey: alertKeys.counts(scope),
    queryFn: () => getJson<AlertCounts>('/alerts/counts', { scope: scope && scope !== 'all' ? scope : undefined }),
    staleTime: STALE.live,
    refetchInterval: 30_000,
    enabled,
  });
}

export function useAlertGroups(params: { status?: string; scope?: AlertScope; limit?: number }, enabled = true) {
  return useQuery({
    queryKey: alertKeys.groups(params),
    queryFn: () => getJson<AlertGroup[]>('/alerts/groups', { ...params, scope: params.scope && params.scope !== 'all' ? params.scope : undefined }),
    staleTime: STALE.live,
    enabled,
  });
}

export function useAlert(id: number | null) {
  return useQuery({
    queryKey: alertKeys.detail(id ?? 0),
    queryFn: () => getJson<AlertDetail>(`/alerts/${id}`),
    staleTime: STALE.live,
    refetchInterval: 30_000,
    enabled: Boolean(id),
  });
}

export function useCreateAlert() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateAlertBody) => postJson<AlertDetail>('/alerts', body),
    onSuccess: (detail) => invalidateAlerts(queryClient, detail),
  });
}

export type AlertCommand =
  | { action: 'acknowledge' | 'resolve'; note?: string | null }
  | { action: 'suppress'; minutes?: number | null; reason: string }
  | { action: 'unsuppress' }
  | { action: 'assign'; teamId: number; note?: string | null };

export function alertCommandBody(command: AlertCommand): unknown {
  switch (command.action) {
    case 'acknowledge':
    case 'resolve':
      return { note: command.note || null };
    case 'suppress':
      return { minutes: command.minutes ?? null, reason: command.reason };
    case 'assign':
      return { teamId: command.teamId, note: command.note || null };
    default:
      return null;
  }
}

export function runAlertCommand(id: number, command: AlertCommand): Promise<AlertDetail> {
  return postJson<AlertDetail>(`/alerts/${id}/${command.action}`, alertCommandBody(command));
}

export function useAlertCommand(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (command: AlertCommand) => runAlertCommand(id, command),
    onSuccess: (detail) => invalidateAlerts(queryClient, detail),
  });
}

export function useConvertAlert(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ConvertAlertBody) => postJson<ConvertResult>(`/alerts/${id}/incident`, body),
    onSuccess: (result) => Promise.all([invalidateAlerts(queryClient, result.alert), invalidateIncidentData(queryClient)]),
  });
}

export function useLinkAlertIncident(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (incidentId: number) => postJson<ConvertResult>(`/alerts/${id}/link-incident`, { incidentId }),
    onSuccess: (result) => Promise.all([invalidateAlerts(queryClient, result.alert), invalidateIncidentData(queryClient)]),
  });
}

export interface BulkOutcome extends BulkResult {
  action: 'ACKNOWLEDGE' | 'RESOLVE' | 'SUPPRESS';
}

export type BulkAlertInput =
  | { ids: number[]; action: 'ACKNOWLEDGE' | 'RESOLVE'; note?: string | null }
  | { ids: number[]; action: 'SUPPRESS'; reason: string; minutes?: number | null };

/** Request body of `POST /alerts/bulk` (ids 1–100; SUPPRESS needs `reason` 3–1000 and optional `minutes` 5–10080, null = until resolved). */
export function bulkAlertBody(input: BulkAlertInput): unknown {
  if (input.action === 'SUPPRESS') return { ids: input.ids, action: 'SUPPRESS', minutes: input.minutes ?? null, reason: input.reason };
  return { ids: input.ids, action: input.action, note: input.note || null };
}

/** Bulk acknowledge, resolve and suppress: one `POST /alerts/bulk` call → `{processed, skipped, messages}`. */
export function useBulkAlerts() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: BulkAlertInput): Promise<BulkOutcome> => {
      const result = await postJson<BulkResult>('/alerts/bulk', bulkAlertBody(input));
      return { ...result, action: input.action };
    },
    onSuccess: () => invalidateAlerts(queryClient),
  });
}

// ------------------------------------------------------------------ sources (alert config)

export function useAlertSources(enabled = true) {
  return useQuery({ queryKey: alertKeys.sources, queryFn: () => getJson<AlertSourceDto[]>('/alerts/sources'), staleTime: STALE.standard, enabled });
}

export function useAlertSourceDeliveries(id: number | null, enabled = true) {
  return useQuery({
    queryKey: alertKeys.deliveries(id ?? 0),
    queryFn: () => getJson<AlertDeliveryDto[]>(`/alerts/sources/${id}/deliveries`, { size: 20 }),
    staleTime: STALE.live,
    enabled: enabled && Boolean(id),
  });
}

export function useSaveAlertSource() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: AlertSourceRequest }) =>
      id ? putJson<AlertSourceSaveResult>(`/alerts/sources/${id}`, body) : postJson<AlertSourceSaveResult>('/alerts/sources', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: alertKeys.sources }),
  });
}

export function useRotateAlertSourceSecret() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<AlertSourceSaveResult>(`/alerts/sources/${id}/rotate-secret`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: alertKeys.sources }),
  });
}

// ------------------------------------------------------------------ rules (read: readers; write: alert config)

export function useSuppressionRules(enabled = true) {
  return useQuery({ queryKey: alertKeys.suppression, queryFn: () => getJson<SuppressionRuleDto[]>('/alerts/suppression-rules'), staleTime: STALE.standard, enabled });
}

export function useSaveSuppressionRule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: SuppressionRuleRequest }) =>
      id ? putJson<SuppressionRuleDto>(`/alerts/suppression-rules/${id}`, body) : postJson<SuppressionRuleDto>('/alerts/suppression-rules', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: alertKeys.suppression }),
  });
}

export function useDeleteSuppressionRule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/alerts/suppression-rules/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: alertKeys.suppression }),
  });
}

export function useRoutingRules(enabled = true) {
  return useQuery({ queryKey: alertKeys.routing, queryFn: () => getJson<RoutingRuleDto[]>('/alerts/routing-rules'), staleTime: STALE.standard, enabled });
}

export function useSaveRoutingRule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: RoutingRuleRequest }) =>
      id ? putJson<RoutingRuleDto>(`/alerts/routing-rules/${id}`, body) : postJson<RoutingRuleDto>('/alerts/routing-rules', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: alertKeys.routing }),
  });
}

export function useDeleteRoutingRule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/alerts/routing-rules/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: alertKeys.routing }),
  });
}
