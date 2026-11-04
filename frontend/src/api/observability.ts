import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type {
  AnomalyDto,
  ConvertGroupRequest,
  CorrelationGroupDto,
  DetectorDto,
  DetectorRequest,
  DetectorRunResult,
  EvaluateRequest,
  EvaluationResult,
  LogDto,
  MappingRuleDto,
  MappingRuleRequest,
  MetricAggregation,
  MetricNameDto,
  ObservabilityOverview,
  PurgeResult,
  ReapplyResult,
  RedSummary,
  ResourceChangeDto,
  RetentionDto,
  SeriesResponse,
  SourcePullResult,
  SourceTestResult,
  TelemetryKindInfo,
  TelemetryResourceDto,
  TelemetrySourceDto,
  TelemetrySourceRequest,
  TelemetrySourceSaved,
  TraceDetail,
  TraceSummary,
} from '../lib/r4Types';
import type { PageResponse } from '../lib/types';
import { STALE } from './common';

/** SA Observability (`/api/observability/**`): readers read, TELEMETRY_CONFIG configures, staff act on correlation groups. */
export const obsKeys = {
  all: ['observability'] as const,
  overview: (includeSimulated: boolean) => ['observability', 'overview', includeSimulated] as const,
  sources: ['observability', 'sources'] as const,
  kinds: ['observability', 'sources', 'kinds'] as const,
  mappings: ['observability', 'mappings'] as const,
  resources: (params: ResourceParams) => ['observability', 'resources', params] as const,
  resourceChanges: (id: number) => ['observability', 'resources', id, 'changes'] as const,
  retention: ['observability', 'retention'] as const,
  metrics: (params: MetricListParams) => ['observability', 'metrics', params] as const,
  series: (params: SeriesParams) => ['observability', 'series', params] as const,
  logs: (params: LogParams) => ['observability', 'logs', params] as const,
  traces: (params: TraceParams) => ['observability', 'traces', params] as const,
  trace: (id: string) => ['observability', 'trace', id] as const,
  red: (params: WindowParams) => ['observability', 'red', params] as const,
  serviceRed: (id: number, params: WindowParams) => ['observability', 'red', id, params] as const,
  detectors: ['observability', 'detectors'] as const,
  anomalies: (params: AnomalyParams) => ['observability', 'anomalies', params] as const,
  groups: (params: GroupParams) => ['observability', 'groups', params] as const,
  group: (id: number) => ['observability', 'group', id] as const,
};

export interface WindowParams {
  from?: string;
  to?: string;
  includeSimulated?: boolean;
}

export interface MetricListParams {
  search?: string;
  serviceId?: number;
  includeSimulated?: boolean;
}

export interface SeriesParams extends WindowParams {
  name: string;
  serviceId?: number;
  ciId?: number;
  resourceId?: number;
  step?: number;
  aggregation?: MetricAggregation;
  /** Attribute filters, sent as `attr.<key>=<value>`. */
  attrs?: Record<string, string>;
}

export interface LogParams extends WindowParams {
  text?: string;
  severity?: string;
  serviceId?: number;
  resourceId?: number;
  traceId?: string;
  page?: number;
  size?: number;
}

export interface TraceParams extends WindowParams {
  serviceId?: number;
  name?: string;
  minDurationMs?: number;
  error?: boolean;
  limit?: number;
}

export interface ResourceParams {
  mapped?: boolean;
  search?: string;
  serviceId?: number;
  includeSimulated?: boolean;
  page?: number;
  size?: number;
}

export interface AnomalyParams extends WindowParams {
  status?: string;
  detectorId?: number;
  serviceId?: number;
  page?: number;
  size?: number;
}

export interface GroupParams {
  status?: string;
  includeSimulated?: boolean;
  page?: number;
  size?: number;
}

function invalidateObservability(queryClient: QueryClient) {
  return queryClient.invalidateQueries({ queryKey: obsKeys.all });
}

// ------------------------------------------------------------------ overview, sources, mappings, resources, retention

export function useObservabilityOverview(includeSimulated: boolean) {
  return useQuery({
    queryKey: obsKeys.overview(includeSimulated),
    queryFn: () => getJson<ObservabilityOverview>('/observability/overview', { includeSimulated }),
    staleTime: STALE.live,
  });
}

export function useTelemetrySources(enabled = true) {
  return useQuery({ queryKey: obsKeys.sources, queryFn: () => getJson<TelemetrySourceDto[]>('/observability/sources'), staleTime: STALE.list, enabled });
}

export function useTelemetryKinds(enabled = true) {
  return useQuery({ queryKey: obsKeys.kinds, queryFn: () => getJson<TelemetryKindInfo[]>('/observability/sources/kinds'), staleTime: STALE.reference, enabled });
}

export function useSaveTelemetrySource() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: TelemetrySourceRequest }) =>
      id ? putJson<TelemetrySourceDto>(`/observability/sources/${id}`, body).then((source): TelemetrySourceSaved => ({ source, token: null })) : postJson<TelemetrySourceSaved>('/observability/sources', body),
    onSuccess: () => invalidateObservability(queryClient),
  });
}

export function useDeleteTelemetrySource() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: (id: number) => deleteJson(`/observability/sources/${id}`), onSuccess: () => invalidateObservability(queryClient) });
}

export function useRotateSourceToken() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<TelemetrySourceSaved>(`/observability/sources/${id}/rotate-token`),
    onSuccess: () => invalidateObservability(queryClient),
  });
}

export function useTestSource() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: (id: number) => postJson<SourceTestResult>(`/observability/sources/${id}/test`), onSettled: () => invalidateObservability(queryClient) });
}

export function usePullSource() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, lookbackMinutes }: { id: number; lookbackMinutes?: number }) =>
      postJson<SourcePullResult>(`/observability/sources/${id}/pull`, null, lookbackMinutes ? { params: { lookbackMinutes } } : undefined),
    onSettled: () => invalidateObservability(queryClient),
  });
}

export function useMappingRules(enabled = true) {
  return useQuery({ queryKey: obsKeys.mappings, queryFn: () => getJson<MappingRuleDto[]>('/observability/mappings'), staleTime: STALE.list, enabled });
}

export function useSaveMappingRule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: MappingRuleRequest }) => (id ? putJson<MappingRuleDto>(`/observability/mappings/${id}`, body) : postJson<MappingRuleDto>('/observability/mappings', body)),
    onSuccess: () => invalidateObservability(queryClient),
  });
}

export function useDeleteMappingRule() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: (id: number) => deleteJson(`/observability/mappings/${id}`), onSuccess: () => invalidateObservability(queryClient) });
}

export function useReapplyMappings() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: () => postJson<ReapplyResult>('/observability/mappings/reapply'), onSuccess: () => invalidateObservability(queryClient) });
}

export function useTelemetryResources(params: ResourceParams, enabled = true) {
  return useQuery({
    queryKey: obsKeys.resources(params),
    queryFn: () => getJson<PageResponse<TelemetryResourceDto>>('/observability/resources', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useResourceChanges(id: number | null) {
  return useQuery({
    queryKey: obsKeys.resourceChanges(id ?? 0),
    queryFn: () => getJson<ResourceChangeDto[]>(`/observability/resources/${id}/changes`),
    staleTime: STALE.list,
    enabled: id !== null,
  });
}

export function useRetention(enabled = true) {
  return useQuery({ queryKey: obsKeys.retention, queryFn: () => getJson<RetentionDto>('/observability/retention'), staleTime: STALE.standard, enabled });
}

export function useSaveRetention() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RetentionDto) => putJson<RetentionDto>('/observability/retention', body),
    onSuccess: (data) => queryClient.setQueryData(obsKeys.retention, data),
  });
}

export function usePurgeRetention() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: () => postJson<PurgeResult>('/observability/retention/purge'), onSuccess: () => invalidateObservability(queryClient) });
}

// ------------------------------------------------------------------ queries

export function useMetricNames(params: MetricListParams, enabled = true) {
  return useQuery({
    queryKey: obsKeys.metrics(params),
    queryFn: () => getJson<MetricNameDto[]>('/observability/metrics', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function seriesQueryParams({ attrs, ...rest }: SeriesParams): Record<string, string | number | boolean | undefined> {
  const params: Record<string, string | number | boolean | undefined> = { ...rest };
  Object.entries(attrs ?? {}).forEach(([key, value]) => {
    if (key.trim() && value !== '') params[`attr.${key.trim()}`] = value;
  });
  return params;
}

export function useMetricSeries(params: SeriesParams | null) {
  return useQuery({
    queryKey: obsKeys.series(params ?? { name: '' }),
    queryFn: () => getJson<SeriesResponse>('/observability/metrics/series', seriesQueryParams(params!)),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled: Boolean(params?.name),
  });
}

export function useLogs(params: LogParams, enabled = true) {
  return useQuery({
    queryKey: obsKeys.logs(params),
    queryFn: () => getJson<PageResponse<LogDto>>('/observability/logs', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useTraces(params: TraceParams, enabled = true) {
  return useQuery({
    queryKey: obsKeys.traces(params),
    queryFn: () => getJson<TraceSummary[]>('/observability/traces', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useTrace(traceId: string | null) {
  return useQuery({
    queryKey: obsKeys.trace(traceId ?? ''),
    queryFn: () => getJson<TraceDetail>(`/observability/traces/${encodeURIComponent(traceId!)}`),
    staleTime: STALE.standard,
    enabled: Boolean(traceId),
  });
}

export function useRedSummaries(params: WindowParams, enabled = true) {
  return useQuery({
    queryKey: obsKeys.red(params),
    queryFn: () => getJson<RedSummary[]>('/observability/services/red', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useServiceRed(serviceId: number | null, params: WindowParams) {
  return useQuery({
    queryKey: obsKeys.serviceRed(serviceId ?? 0, params),
    queryFn: () => getJson<RedSummary>(`/observability/services/${serviceId}/red`, { ...params }),
    staleTime: STALE.live,
    enabled: serviceId !== null,
  });
}

// ------------------------------------------------------------------ anomaly detection

export function useDetectors(enabled = true) {
  return useQuery({ queryKey: obsKeys.detectors, queryFn: () => getJson<DetectorDto[]>('/observability/anomaly-detectors'), staleTime: STALE.list, enabled });
}

export function useSaveDetector() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: DetectorRequest }) => (id ? putJson<DetectorDto>(`/observability/anomaly-detectors/${id}`, body) : postJson<DetectorDto>('/observability/anomaly-detectors', body)),
    onSuccess: () => invalidateObservability(queryClient),
  });
}

export function useDeleteDetector() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: (id: number) => deleteJson(`/observability/anomaly-detectors/${id}`), onSuccess: () => invalidateObservability(queryClient) });
}

export function useRunDetector() {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: (id: number) => postJson<DetectorRunResult>(`/observability/anomaly-detectors/${id}/run`), onSettled: () => invalidateObservability(queryClient) });
}

export function useEvaluateDetector() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: EvaluateRequest }) => postJson<EvaluationResult>(`/observability/anomaly-detectors/${id}/evaluate`, body),
    onSettled: () => queryClient.invalidateQueries({ queryKey: obsKeys.detectors }),
  });
}

export function useAnomalies(params: AnomalyParams, enabled = true) {
  return useQuery({
    queryKey: obsKeys.anomalies(params),
    queryFn: () => getJson<PageResponse<AnomalyDto>>('/observability/anomalies', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

// ------------------------------------------------------------------ correlation

export function useCorrelationGroups(params: GroupParams, enabled = true) {
  return useQuery({
    queryKey: obsKeys.groups(params),
    queryFn: () => getJson<PageResponse<CorrelationGroupDto>>('/observability/correlation-groups', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useCorrelationGroup(id: number | null) {
  return useQuery({
    queryKey: obsKeys.group(id ?? 0),
    queryFn: () => getJson<CorrelationGroupDto>(`/observability/correlation-groups/${id}`),
    staleTime: STALE.live,
    enabled: id !== null,
  });
}

export type GroupAction =
  | { kind: 'link'; incidentId: number }
  | { kind: 'convert'; body: ConvertGroupRequest }
  | { kind: 'split'; memberIds: number[]; title?: string }
  | { kind: 'merge'; groupIds: number[] }
  | { kind: 'dismiss'; reason: string };

export function useCorrelationAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action }: { id: number; action: GroupAction }) => {
      const base = `/observability/correlation-groups/${id}`;
      switch (action.kind) {
        case 'link':
          return postJson<CorrelationGroupDto>(`${base}/link`, { incidentId: action.incidentId });
        case 'convert':
          return postJson<CorrelationGroupDto>(`${base}/convert`, action.body);
        case 'split':
          return postJson<CorrelationGroupDto>(`${base}/split`, { memberIds: action.memberIds, title: action.title || undefined });
        case 'merge':
          return postJson<CorrelationGroupDto>(`${base}/merge`, { groupIds: action.groupIds });
        case 'dismiss':
          return postJson<CorrelationGroupDto>(`${base}/dismiss`, { reason: action.reason });
      }
    },
    onSuccess: () =>
      Promise.all([invalidateObservability(queryClient), queryClient.invalidateQueries({ queryKey: ['alerts'] }), queryClient.invalidateQueries({ queryKey: ['incidents'] })]),
  });
}
