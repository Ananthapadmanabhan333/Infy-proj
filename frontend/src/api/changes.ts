import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type {
  ChangeCalendar,
  ChangeCorrelation,
  ChangeDetail,
  ChangeIncidentRelation,
  ChangeMetrics,
  ChangeRequestBody,
  ChangeSummary,
  ChangeTemplateDto,
  ChangeTransitionBody,
  FreezeDto,
  HistoryDto,
  RiskAnalysis,
  RiskQuestion,
} from '../lib/itsmTypes';
import type { AiResult, PageResponse } from '../lib/types';
import { STALE } from './common';

/** Changes (docs/API.md "Changes"): readers read; change raisers create; editors manage; state rules are server-side. */
export interface ChangeListParams {
  search?: string;
  /** CSV of statuses. */
  status?: string;
  type?: string;
  risk?: string;
  serviceId?: number;
  assigneeId?: number;
  teamId?: number;
  scope?: 'all' | 'mine' | 'requested' | 'team';
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const changeKeys = {
  all: ['changes'] as const,
  list: (params: ChangeListParams) => ['changes', 'list', params] as const,
  detail: (id: number) => ['changes', 'detail', id] as const,
  history: (id: number) => ['changes', 'detail', id, 'history'] as const,
  correlation: (id: number) => ['changes', 'detail', id, 'correlation'] as const,
  calendar: (from: string, to: string, serviceId?: number) => ['changes', 'calendar', from, to, serviceId ?? null] as const,
  metrics: (days?: number) => ['changes', 'metrics', days ?? null] as const,
  questions: ['changes', 'risk-questions'] as const,
  templates: ['changes', 'templates'] as const,
  freezes: (includePast: boolean) => ['changes', 'freeze-windows', includePast] as const,
};

function invalidateChanges(queryClient: QueryClient, change?: ChangeDetail | null) {
  if (change) queryClient.setQueryData(changeKeys.detail(change.id), change);
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: changeKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['approvals'] }),
    queryClient.invalidateQueries({ queryKey: ['my-work'] }),
    queryClient.invalidateQueries({ queryKey: ['services'] }),
  ]);
}

export function useChanges(params: ChangeListParams, enabled = true) {
  return useQuery({
    queryKey: changeKeys.list(params),
    queryFn: () => getJson<PageResponse<ChangeSummary>>('/changes', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useChange(id: number | null) {
  return useQuery({
    queryKey: changeKeys.detail(id ?? 0),
    queryFn: () => getJson<ChangeDetail>(`/changes/${id}`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

export function useChangeHistory(id: number | null) {
  return useQuery({
    queryKey: changeKeys.history(id ?? 0),
    queryFn: () => getJson<HistoryDto[]>(`/changes/${id}/history`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

export function useChangeCorrelation(id: number | null, enabled = true) {
  return useQuery({
    queryKey: changeKeys.correlation(id ?? 0),
    queryFn: () => getJson<ChangeCorrelation>(`/changes/${id}/incidents`),
    staleTime: STALE.standard,
    enabled: enabled && Boolean(id),
  });
}

export function useChangeCalendar(from: string, to: string, serviceId?: number, enabled = true) {
  return useQuery({
    queryKey: changeKeys.calendar(from, to, serviceId),
    queryFn: () => getJson<ChangeCalendar>('/changes/calendar', { from, to, serviceId }),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
    enabled,
  });
}

export function useChangeMetrics(days?: number, enabled = true) {
  return useQuery({
    queryKey: changeKeys.metrics(days),
    queryFn: () => getJson<ChangeMetrics>('/changes/metrics', { days }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useRiskQuestions(enabled = true) {
  return useQuery({
    queryKey: changeKeys.questions,
    queryFn: () => getJson<RiskQuestion[]>('/changes/risk-questions'),
    staleTime: STALE.reference,
    enabled,
  });
}

export function useChangeTemplates(enabled = true) {
  return useQuery({
    queryKey: changeKeys.templates,
    queryFn: () => getJson<ChangeTemplateDto[]>('/changes/templates', { active: true }),
    staleTime: STALE.reference,
    enabled,
  });
}

export function useFreezeWindows(includePast = false, enabled = true) {
  return useQuery({
    queryKey: changeKeys.freezes(includePast),
    queryFn: () => getJson<FreezeDto[]>('/changes/freeze-windows', { includePast: includePast || undefined }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useSaveFreezeWindow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: { name: string; reason?: string | null; startsAt: string; endsAt: string; serviceIds?: number[]; active?: boolean } }) =>
      id ? putJson<FreezeDto>(`/changes/freeze-windows/${id}`, body) : postJson<FreezeDto>('/changes/freeze-windows', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: changeKeys.all }),
  });
}

export function useDeleteFreezeWindow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/changes/freeze-windows/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: changeKeys.all }),
  });
}

export function useSaveChange() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: ChangeRequestBody }) => (id ? putJson<ChangeDetail>(`/changes/${id}`, body) : postJson<ChangeDetail>('/changes', body)),
    onSuccess: (change) => invalidateChanges(queryClient, change),
  });
}

export function useChangeTransition(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ChangeTransitionBody) => postJson<ChangeDetail>(`/changes/${id}/transition`, body),
    onSuccess: (change) => invalidateChanges(queryClient, change),
  });
}

/** Transition of any change (id in the variables) — e.g. submit right after creating it. */
export function useAnyChangeTransition() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: ChangeTransitionBody }) => postJson<ChangeDetail>(`/changes/${id}/transition`, body),
    onSuccess: (change) => invalidateChanges(queryClient, change),
  });
}

export function useChangeReview(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (notes: string) => postJson<ChangeDetail>(`/changes/${id}/review`, { notes }),
    onSuccess: (change) => invalidateChanges(queryClient, change),
  });
}

export function useLinkChangeIncident(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { incidentId: number; relation: ChangeIncidentRelation }) => postJson<ChangeDetail>(`/changes/${id}/incidents`, body),
    onSuccess: (change) => invalidateChanges(queryClient, change),
  });
}

export function useUnlinkChangeIncident(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (incidentId: number) => deleteJson<ChangeDetail>(`/changes/${id}/incidents/${incidentId}`),
    onSuccess: (change) => invalidateChanges(queryClient, change ?? null),
  });
}

/** SA AI change-risk analysis (score/factors from the risk model; LLM or labelled deterministic fallback). */
export function useChangeRiskAnalysis(id: number) {
  return useMutation({
    mutationFn: () => postJson<AiResult<RiskAnalysis>>(`/ai/changes/${id}/risk`),
  });
}
