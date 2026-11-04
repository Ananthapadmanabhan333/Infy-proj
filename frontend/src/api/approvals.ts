import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson } from '../lib/api';
import type { ApprovalDetail, DelegationDto, InboxCounts, TaskInboxItem, UserOption } from '../lib/itsmTypes';
import type { ApprovalDto, PageResponse, Role } from '../lib/types';
import { STALE } from './common';

/** Approval engine v2 (docs/API.md "Approval engine v2"): any signed-in user; results are routed to the caller. */
export interface TaskInboxParams {
  status?: 'PENDING' | 'DECIDED';
  type?: string;
  overdue?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}

export interface ApprovalListParams {
  status?: string;
  type?: string;
  /** mine (default) | requested | all (SYSTEM_ADMIN, IT_ADMIN, AUDITOR). */
  scope?: 'mine' | 'requested' | 'all';
  search?: string;
  overdue?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}

export const approvalKeys = {
  all: ['approvals'] as const,
  tasks: (params: TaskInboxParams) => ['approvals', 'tasks', params] as const,
  list: (params: ApprovalListParams) => ['approvals', 'list', params] as const,
  counts: ['approvals', 'counts'] as const,
  detail: (id: number) => ['approvals', 'detail', id] as const,
  delegations: ['approvals', 'delegations'] as const,
};

/** Everything an approval decision can change (subjects re-read their state). */
export function invalidateApprovalData(queryClient: QueryClient) {
  return Promise.all(
    ['approvals', 'my-work', 'portal', 'service-requests', 'changes', 'runbooks', 'incidents', 'dashboard'].map((key) =>
      queryClient.invalidateQueries({ queryKey: [key] }),
    ),
  );
}

export function useApprovalTasks(params: TaskInboxParams, enabled = true) {
  return useQuery({
    queryKey: approvalKeys.tasks(params),
    queryFn: () => getJson<PageResponse<TaskInboxItem>>('/approvals/tasks', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useApprovalList(params: ApprovalListParams, enabled = true) {
  return useQuery({
    queryKey: approvalKeys.list(params),
    queryFn: () => getJson<PageResponse<ApprovalDto>>('/approvals', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useApprovalCounts(enabled = true) {
  return useQuery({
    queryKey: approvalKeys.counts,
    queryFn: () => getJson<InboxCounts>('/approvals/counts'),
    staleTime: STALE.live,
    enabled,
  });
}

export function useApprovalDetail(id: number | null) {
  return useQuery({
    queryKey: approvalKeys.detail(id ?? 0),
    queryFn: () => getJson<ApprovalDetail>(`/approvals/${id}`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

/** Decides one task (`POST /approvals/tasks/{taskId}/decision`); a rejection needs a comment (server-enforced). */
export function useDecideTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ taskId, approved, comment }: { taskId: number; approved: boolean; comment?: string }) =>
      postJson<ApprovalDto>(`/approvals/tasks/${taskId}/decision`, { approved, comment: comment?.trim() || undefined }),
    onSuccess: () => invalidateApprovalData(queryClient),
  });
}

export function useDelegateTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ taskId, userId, comment }: { taskId: number; userId: number; comment?: string }) =>
      postJson<ApprovalDto>(`/approvals/tasks/${taskId}/delegate`, { userId, comment: comment?.trim() || undefined }),
    onSuccess: () => invalidateApprovalData(queryClient),
  });
}

export function useDelegations(enabled = true) {
  return useQuery({
    queryKey: approvalKeys.delegations,
    queryFn: () => getJson<DelegationDto[]>('/approvals/delegations'),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useCreateDelegation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { delegateId: number; startsAt: string; endsAt: string; reason?: string }) => postJson<DelegationDto>('/approvals/delegations', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: approvalKeys.all }),
  });
}

export function useRevokeDelegation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/approvals/delegations/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: approvalKeys.all }),
  });
}

/** User picker search (`GET /service-requests/user-options`, ≥ 2 characters, any user, no e-mail addresses). */
export function useUserOptions(q: string, roles?: Role[] | null, enabled = true) {
  const term = q.trim();
  return useQuery({
    queryKey: ['user-options', term, roles ?? []],
    queryFn: () => getJson<UserOption[]>('/service-requests/user-options', { q: term, roles: roles && roles.length ? roles.join(',') : undefined }),
    staleTime: STALE.standard,
    enabled: enabled && term.length >= 2,
  });
}
