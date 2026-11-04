import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson, putJson } from '../lib/api';
import type {
  PageResponse,
  WorkflowDto,
  WorkflowExecutionDto,
  WorkflowMeta,
  WorkflowRequest,
  WorkflowTemplate,
  WorkflowTestResult,
  WorkflowVersionDto,
} from '../lib/types';
import { asArray, STALE } from './common';

export function useWorkflows() {
  return useQuery({
    queryKey: ['workflows', 'list'],
    queryFn: async () => asArray(await getJson<WorkflowDto[] | PageResponse<WorkflowDto>>('/workflows')),
    staleTime: STALE.list,
  });
}

export function useWorkflow(id: number | null) {
  return useQuery({
    queryKey: ['workflows', 'detail', id],
    queryFn: () => getJson<WorkflowDto>(`/workflows/${id}`),
    enabled: id !== null && id > 0,
    staleTime: STALE.standard,
  });
}

export function useWorkflowVersions(id: number | null) {
  return useQuery({
    queryKey: ['workflows', 'versions', id],
    queryFn: async () => asArray(await getJson<WorkflowVersionDto[] | PageResponse<WorkflowVersionDto>>(`/workflows/${id}/versions`)),
    enabled: id !== null && id > 0,
    staleTime: STALE.standard,
  });
}

export function useWorkflowMeta() {
  return useQuery({
    queryKey: ['workflows', 'meta'],
    queryFn: () => getJson<WorkflowMeta>('/workflows/meta'),
    staleTime: STALE.reference,
  });
}

/** §6 starter templates (e.g. "Payroll incident – auto resolution"). */
export function useWorkflowTemplates(enabled = true) {
  return useQuery({
    queryKey: ['workflows', 'templates'],
    queryFn: async () => asArray(await getJson<WorkflowTemplate[] | PageResponse<WorkflowTemplate>>('/workflows/templates')),
    staleTime: STALE.reference,
    enabled,
  });
}

export function useSaveWorkflow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: WorkflowRequest }) =>
      id ? putJson<WorkflowDto>(`/workflows/${id}`, body) : postJson<WorkflowDto>('/workflows', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useActivateWorkflow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, active }: { id: number; active: boolean }) => postJson<WorkflowDto>(`/workflows/${id}/activate`, { active }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useRollbackWorkflow(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (versionNumber: number) => postJson<WorkflowDto>(`/workflows/${id}/rollback`, { versionNumber }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });
}

export function useTestWorkflow(id: number) {
  return useMutation({
    mutationFn: (incidentId: number) => postJson<WorkflowTestResult>(`/workflows/${id}/test`, { incidentId }),
  });
}

/** Without `workflowId` this lists every workflow's executions (§6 Run History tab). */
export function useWorkflowExecutions(params: { workflowId?: string | number; status?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['workflows', 'executions', params],
    queryFn: () => getJson<PageResponse<WorkflowExecutionDto>>('/workflows/executions', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    refetchInterval: 30_000,
  });
}

export function useRetryExecution() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (executionId: number) => postJson<WorkflowExecutionDto>(`/workflows/executions/${executionId}/retry`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflows'] }),
  });
}
