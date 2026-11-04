import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type {
  ApprovalDto,
  CreateServiceRequest,
  PageResponse,
  ServiceRequestCommentDto,
  ServiceRequestCounts,
  ServiceRequestDto,
  ServiceRequestHistoryItem,
} from '../lib/types';
import { STALE } from './common';

export type ServiceRequestScope = 'mine' | 'department' | 'all';

export interface ServiceRequestListParams {
  scope?: ServiceRequestScope;
  status?: string;
  search?: string;
  /** §4 catalogue category code, e.g. NETWORK. */
  category?: string;
  /** §4 LOW | MEDIUM | HIGH | CRITICAL (csv allowed). */
  priority?: string;
  page?: number;
  size?: number;
}

function invalidateDesk(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['service-requests'] }),
    queryClient.invalidateQueries({ queryKey: ['approvals'] }),
    queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
    queryClient.invalidateQueries({ queryKey: ['my-work'] }),
    queryClient.invalidateQueries({ queryKey: ['portal'] }),
    queryClient.invalidateQueries({ queryKey: ['teams'] }),
  ]);
}

export function useServiceRequests(params: ServiceRequestListParams, enabled = true) {
  return useQuery({
    queryKey: ['service-requests', 'list', params],
    queryFn: () => getJson<PageResponse<ServiceRequestDto>>('/service-requests', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useServiceRequest(id: number | null) {
  return useQuery({
    queryKey: ['service-requests', 'detail', id],
    queryFn: () => getJson<ServiceRequestDto>(`/service-requests/${id}`),
    enabled: id !== null && id > 0,
    staleTime: STALE.live,
  });
}

/** §4 tab counts { all, mine, pendingApproval, inFulfillment, fulfilled }. */
export function useServiceRequestCounts(scope?: ServiceRequestScope, enabled = true) {
  return useQuery({
    queryKey: ['service-requests', 'counts', scope ?? null],
    queryFn: () => getJson<ServiceRequestCounts>('/service-requests/counts', { scope }),
    staleTime: STALE.list,
    enabled,
  });
}

/** §4 public and (for staff) internal comments on a request. */
export function useServiceRequestComments(id: number | null) {
  return useQuery({
    queryKey: ['service-requests', 'detail', id, 'comments'],
    queryFn: () => getJson<ServiceRequestCommentDto[]>(`/service-requests/${id}/comments`),
    enabled: id !== null && id > 0,
    staleTime: STALE.live,
  });
}

/** §4 `internal` is honoured for staff only. */
export function useAddServiceRequestComment(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { body: string; internal: boolean }) => postJson<ServiceRequestCommentDto>(`/service-requests/${id}/comments`, body),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['service-requests', 'detail', id] }),
        queryClient.invalidateQueries({ queryKey: ['service-requests', 'list'] }),
      ]),
  });
}

/** §4 status / assignment / approval history of a request. */
export function useServiceRequestHistory(id: number | null) {
  return useQuery({
    queryKey: ['service-requests', 'detail', id, 'history'],
    queryFn: () => getJson<ServiceRequestHistoryItem[]>(`/service-requests/${id}/history`),
    enabled: id !== null && id > 0,
    staleTime: STALE.live,
  });
}

export function useCreateServiceRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateServiceRequest) => postJson<ServiceRequestDto>('/service-requests', body),
    onSuccess: () => invalidateDesk(queryClient),
  });
}

type RequestAction =
  | { action: 'assign'; assigneeId?: number | null; teamId?: number | null }
  | { action: 'start' }
  | { action: 'fulfill'; notes: string }
  | { action: 'cancel'; reason: string };

export function useServiceRequestAction(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: RequestAction) => {
      switch (input.action) {
        case 'assign':
          // R3: `{assigneeId?, teamId?}` (at least one); a team alone puts the request in the team queue.
          return postJson<ServiceRequestDto>(`/service-requests/${id}/assign`, {
            ...(input.assigneeId ? { assigneeId: input.assigneeId } : {}),
            ...(input.teamId ? { teamId: input.teamId } : {}),
          });
        case 'start':
          return postJson<ServiceRequestDto>(`/service-requests/${id}/start`);
        case 'fulfill':
          return postJson<ServiceRequestDto>(`/service-requests/${id}/fulfill`, { notes: input.notes });
        case 'cancel':
          return postJson<ServiceRequestDto>(`/service-requests/${id}/cancel`, { reason: input.reason });
        default:
          return Promise.reject(new Error('Unknown action'));
      }
    },
    onSuccess: () => invalidateDesk(queryClient),
  });
}

/** Assign any request to a team and/or a member (My Work team requests: take / assign within the team). */
export function useAssignAnyRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, assigneeId, teamId }: { id: number; assigneeId?: number | null; teamId?: number | null }) =>
      postJson<ServiceRequestDto>(`/service-requests/${id}/assign`, { ...(assigneeId ? { assigneeId } : {}), ...(teamId ? { teamId } : {}) }),
    onSuccess: () => invalidateDesk(queryClient),
  });
}

export function useApprovals(params: { status?: string; page?: number; size?: number }, enabled = true) {
  return useQuery({
    queryKey: ['approvals', params],
    queryFn: () => getJson<PageResponse<ApprovalDto>>('/approvals', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useDecideApproval() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, approved, comment }: { id: number; approved: boolean; comment: string }) =>
      postJson<ApprovalDto>(`/approvals/${id}/decision`, { approved, comment }),
    onSuccess: () =>
      Promise.all([
        invalidateDesk(queryClient),
        queryClient.invalidateQueries({ queryKey: ['runbooks'] }),
        queryClient.invalidateQueries({ queryKey: ['incidents'] }),
      ]),
  });
}
