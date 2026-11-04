import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { getJson, postJson, putJson } from '../lib/api';
import type { Category, PageResponse, RunbookDto, RunbookExecutionDto, RunbookRequest } from '../lib/types';
import { asArray, STALE } from './common';

function invalidateRunbooks(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['runbooks'] }),
    queryClient.invalidateQueries({ queryKey: ['approvals'] }),
    queryClient.invalidateQueries({ queryKey: ['incidents'] }),
  ]);
}

export function useRunbooks(params: { search?: string; serviceId?: number | null; category?: Category | '' } = {}, enabled = true) {
  return useQuery({
    queryKey: ['runbooks', 'list', params],
    queryFn: async () =>
      asArray(
        await getJson<RunbookDto[] | PageResponse<RunbookDto>>('/runbooks', {
          search: params.search,
          serviceId: params.serviceId ?? undefined,
          category: params.category || undefined,
        }),
      ),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useRunbook(id: number | null) {
  return useQuery({
    queryKey: ['runbooks', 'detail', id],
    queryFn: () => getJson<RunbookDto>(`/runbooks/${id}`),
    enabled: id !== null && id > 0,
    staleTime: STALE.standard,
  });
}

export function useRunbookExecutions(params: { incidentId?: number; runbookId?: number; status?: string }, enabled = true) {
  return useQuery({
    queryKey: ['runbooks', 'executions', params],
    queryFn: async () =>
      asArray(await getJson<RunbookExecutionDto[] | PageResponse<RunbookExecutionDto>>('/runbooks/executions', { ...params })),
    staleTime: STALE.live,
    enabled,
  });
}

/** §10 paginated execution history across all runbooks (`GET /runbooks/executions`). */
export function useRunbookExecutionHistory(params: { runbookId?: number; status?: string; page?: number; size?: number }, enabled = true) {
  return useQuery({
    queryKey: ['runbooks', 'executions', 'history', params],
    queryFn: () => getJson<PageResponse<RunbookExecutionDto>>('/runbooks/executions', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useSaveRunbook() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: RunbookRequest }) =>
      id ? putJson<RunbookDto>(`/runbooks/${id}`, body) : postJson<RunbookDto>('/runbooks', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['runbooks'] }),
  });
}

export function useStartRunbook() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ runbookId, incidentId }: { runbookId: number; incidentId: number }) =>
      postJson<RunbookExecutionDto>(`/runbooks/${runbookId}/executions`, { incidentId }),
    onSuccess: () => invalidateRunbooks(queryClient),
  });
}

export function useRunbookExecutionActions() {
  const queryClient = useQueryClient();
  const onSuccess = () => invalidateRunbooks(queryClient);
  /** Completing a sensitive step as a non-approver returns the step with approvalStatus PENDING (§10). */
  const step = useMutation({
    mutationFn: ({ executionId, order, done, notes }: { executionId: number; order: number; done: boolean; notes: string | null }) =>
      postJson<RunbookExecutionDto>(`/runbooks/executions/${executionId}/steps/${order}`, { done, notes }),
    onSuccess,
  });
  const complete = useMutation({
    mutationFn: ({ executionId, outcomeNotes }: { executionId: number; outcomeNotes: string }) =>
      postJson<RunbookExecutionDto>(`/runbooks/executions/${executionId}/complete`, { outcomeNotes }),
    onSuccess,
  });
  const abort = useMutation({
    mutationFn: ({ executionId, reason }: { executionId: number; reason: string }) =>
      postJson<RunbookExecutionDto>(`/runbooks/executions/${executionId}/abort`, { reason }),
    onSuccess,
  });
  return { step, complete, abort };
}
