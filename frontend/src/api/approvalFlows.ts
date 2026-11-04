import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type { ApprovalFlowDto, ApprovalFlowSaveRequest, ApprovalSubjectType } from '../lib/itsmTypes';
import { STALE } from './common';

/** Approval flows (docs/API.md "Approval engine v2"): readers list and read, approval-flow editors maintain. */
export interface ApprovalFlowListParams {
  /** Flows for this subject type (flows without a subject type are always included by the API). */
  subjectType?: ApprovalSubjectType | '';
  active?: boolean;
}

export const approvalFlowKeys = {
  all: ['approval-flows'] as const,
  list: (params: ApprovalFlowListParams) => ['approval-flows', 'list', params] as const,
  detail: (id: number) => ['approval-flows', 'detail', id] as const,
};

export function useApprovalFlows(params: ApprovalFlowListParams = {}, enabled = true) {
  return useQuery({
    queryKey: approvalFlowKeys.list(params),
    queryFn: () => getJson<ApprovalFlowDto[]>('/approval-flows', { subjectType: params.subjectType || undefined, active: params.active }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useApprovalFlow(id: number | null | undefined) {
  return useQuery({
    queryKey: approvalFlowKeys.detail(id ?? 0),
    queryFn: () => getJson<ApprovalFlowDto>(`/approval-flows/${id}`),
    staleTime: STALE.standard,
    enabled: Boolean(id),
  });
}

/** `POST /approval-flows` (201) or `PUT /approval-flows/{id}`; `400 fieldErrors[stages[i]…]`, duplicate code `409`. */
export function useSaveApprovalFlow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number | null; body: ApprovalFlowSaveRequest }) =>
      id ? putJson<ApprovalFlowDto>(`/approval-flows/${id}`, body) : postJson<ApprovalFlowDto>('/approval-flows', body),
    onSuccess: (flow) => {
      queryClient.setQueryData(approvalFlowKeys.detail(flow.id), flow);
      return queryClient.invalidateQueries({ queryKey: approvalFlowKeys.all });
    },
  });
}

/** `DELETE /approval-flows/{id}` → 204; system flows `409`. */
export function useDeleteApprovalFlow() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/approval-flows/${id}`),
    onSuccess: (_, id) => {
      queryClient.removeQueries({ queryKey: approvalFlowKeys.detail(id) });
      return queryClient.invalidateQueries({ queryKey: approvalFlowKeys.all });
    },
  });
}
