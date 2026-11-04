import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type {
  HistoryDto,
  ProblemActionBody,
  ProblemActionDto,
  ProblemAnalytics,
  ProblemDetail,
  ProblemRequestBody,
  ProblemSummary,
  ProblemTransitionBody,
  RecurringCluster,
  RootCauseSuggestion,
} from '../lib/itsmTypes';
import type { AiResult, PageResponse, Priority } from '../lib/types';
import { STALE } from './common';

/** Problems (docs/API.md "Problems"): readers read; problem editors work them; admins & managers close. */
export interface ProblemListParams {
  search?: string;
  /** CSV of statuses. */
  status?: string;
  priority?: string;
  ownerId?: number;
  teamId?: number;
  serviceId?: number;
  knownError?: boolean;
  scope?: 'all' | 'mine' | 'team';
  page?: number;
  size?: number;
  sort?: string;
}

export const problemKeys = {
  all: ['problems'] as const,
  list: (params: ProblemListParams) => ['problems', 'list', params] as const,
  detail: (id: number) => ['problems', 'detail', id] as const,
  history: (id: number) => ['problems', 'detail', id, 'history'] as const,
  recurring: (days?: number) => ['problems', 'recurring', days ?? null] as const,
  analytics: (days?: number) => ['problems', 'analytics', days ?? null] as const,
};

function invalidateProblems(queryClient: QueryClient, problem?: ProblemDetail) {
  if (problem) queryClient.setQueryData(problemKeys.detail(problem.id), problem);
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: problemKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['my-work'] }),
    queryClient.invalidateQueries({ queryKey: ['incidents'] }),
  ]);
}

export function useProblems(params: ProblemListParams, enabled = true) {
  return useQuery({
    queryKey: problemKeys.list(params),
    queryFn: () => getJson<PageResponse<ProblemSummary>>('/problems', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useProblem(id: number | null) {
  return useQuery({
    queryKey: problemKeys.detail(id ?? 0),
    queryFn: () => getJson<ProblemDetail>(`/problems/${id}`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

export function useProblemHistory(id: number | null) {
  return useQuery({
    queryKey: problemKeys.history(id ?? 0),
    queryFn: () => getJson<HistoryDto[]>(`/problems/${id}/history`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

export function useRecurringClusters(days?: number, enabled = true) {
  return useQuery({
    queryKey: problemKeys.recurring(days),
    queryFn: () => getJson<RecurringCluster[]>('/problems/recurring', { days }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useProblemAnalytics(days?: number, enabled = true) {
  return useQuery({
    queryKey: problemKeys.analytics(days),
    queryFn: () => getJson<ProblemAnalytics>('/problems/analytics', { days }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useSaveProblem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: ProblemRequestBody }) =>
      id ? putJson<ProblemDetail>(`/problems/${id}`, body) : postJson<ProblemDetail>('/problems', body),
    onSuccess: (problem) => invalidateProblems(queryClient, problem),
  });
}

export function useProblemFromCluster() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { incidentIds: number[]; title?: string; priority?: Priority; ownerId?: number | null }) => postJson<ProblemDetail>('/problems/from-cluster', body),
    onSuccess: (problem) => invalidateProblems(queryClient, problem),
  });
}

export function useProblemTransition(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ProblemTransitionBody) => postJson<ProblemDetail>(`/problems/${id}/transition`, body),
    onSuccess: (problem) => Promise.all([invalidateProblems(queryClient, problem), queryClient.invalidateQueries({ queryKey: ['knowledge'] })]),
  });
}

export function useLinkProblemIncidents(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (incidentIds: number[]) => postJson<ProblemDetail>(`/problems/${id}/incidents`, { incidentIds }),
    onSuccess: (problem) => invalidateProblems(queryClient, problem),
  });
}

export function useUnlinkProblemIncident(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (incidentId: number) => deleteJson<ProblemDetail>(`/problems/${id}/incidents/${incidentId}`),
    onSuccess: (problem) => invalidateProblems(queryClient, problem ?? undefined),
  });
}

export function useSaveProblemAction(problemId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ actionId, body }: { actionId?: number; body: ProblemActionBody }) =>
      actionId ? putJson<ProblemActionDto>(`/problems/${problemId}/actions/${actionId}`, body) : postJson<ProblemActionDto>(`/problems/${problemId}/actions`, body),
    onSuccess: () => invalidateProblems(queryClient),
  });
}

/** Creates the KB KNOWN_ERROR article (`publish` → published / in review by role; else draft). */
export function useKnownErrorArticle(problemId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (publish: boolean) => postJson<ProblemDetail>(`/problems/${problemId}/known-error-article`, { publish }),
    onSuccess: (problem) => Promise.all([invalidateProblems(queryClient, problem), queryClient.invalidateQueries({ queryKey: ['knowledge'] })]),
  });
}

/** SA AI root-cause suggestion (LLM or labelled deterministic fallback). */
export function useProblemRootCause(problemId: number) {
  return useMutation({
    mutationFn: () => postJson<AiResult<RootCauseSuggestion>>(`/ai/problems/${problemId}/root-cause`),
  });
}
