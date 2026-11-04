import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { downloadFile, getJson, postJson, putJson } from '../lib/api';
import type {
  ActionItemDto,
  ActionRequest,
  DeclareRequest,
  MajorDetail,
  MajorStatus,
  MajorSummary,
  MajorUpdateRequest,
  PirDetail,
  PirSummary,
  PirUpdateRequest,
  StakeholderUpdateDto,
  StakeholderUpdateRequest,
  TimelineDto,
  TimelineEntryType,
} from '../lib/responseTypes';
import type { PageResponse } from '../lib/types';
import { invalidateIncidentData, STALE } from './common';

/** Major incident command and post-incident reviews (docs/API.md "Major incident command", "Post-incident reviews"). */
export const majorKeys = {
  all: ['major-incidents'] as const,
  list: (params: object) => ['major-incidents', 'list', params] as const,
  detail: (id: number) => ['major-incidents', 'detail', id] as const,
  byIncident: (incidentId: number) => ['major-incidents', 'by-incident', incidentId] as const,
};

export const reviewKeys = {
  all: ['reviews'] as const,
  list: (params: object) => ['reviews', 'list', params] as const,
  detail: (id: number) => ['reviews', 'detail', id] as const,
  byIncident: (incidentId: number) => ['reviews', 'by-incident', incidentId] as const,
};

function invalidateMajor(queryClient: QueryClient, detail?: MajorDetail) {
  if (detail) queryClient.setQueryData(majorKeys.detail(detail.id), detail);
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: majorKeys.all }),
    queryClient.invalidateQueries({ queryKey: reviewKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['my-work'] }),
    queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
  ]);
}

function invalidateReview(queryClient: QueryClient, detail?: PirDetail) {
  if (detail) queryClient.setQueryData(reviewKeys.detail(detail.id), detail);
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: reviewKeys.all }),
    queryClient.invalidateQueries({ queryKey: majorKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['my-work'] }),
    queryClient.invalidateQueries({ queryKey: ['approvals'] }),
  ]);
}

export interface MajorListParams {
  status?: string;
  search?: string;
  page?: number;
  size?: number;
}

export function useMajorIncidents(params: MajorListParams, enabled = true) {
  return useQuery({
    queryKey: majorKeys.list(params),
    queryFn: () => getJson<PageResponse<MajorSummary>>('/major-incidents', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    refetchInterval: 30_000,
    enabled,
  });
}

export function useMajorIncident(id: number | null) {
  return useQuery({
    queryKey: majorKeys.detail(id ?? 0),
    queryFn: () => getJson<MajorDetail>(`/major-incidents/${id}`),
    staleTime: STALE.live,
    refetchInterval: 30_000,
    enabled: Boolean(id),
  });
}

/** The command record of a major incident (404 when the incident has none). */
export function useMajorByIncident(incidentId: number | null, enabled = true) {
  return useQuery({
    queryKey: majorKeys.byIncident(incidentId ?? 0),
    queryFn: () => getJson<MajorDetail>(`/major-incidents/by-incident/${incidentId}`),
    staleTime: STALE.standard,
    retry: false,
    enabled: enabled && Boolean(incidentId),
  });
}

export function useDeclareMajor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: DeclareRequest) => postJson<MajorDetail>('/major-incidents', body),
    onSuccess: (detail) => Promise.all([invalidateMajor(queryClient, detail), invalidateIncidentData(queryClient)]),
  });
}

export function useUpdateMajor(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: MajorUpdateRequest) => putJson<MajorDetail>(`/major-incidents/${id}`, body),
    onSuccess: (detail) => invalidateMajor(queryClient, detail),
  });
}

export function useMajorRoles(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { commanderId?: number | null; techLeadId?: number | null; commsLeadId?: number | null }) => putJson<MajorDetail>(`/major-incidents/${id}/roles`, body),
    onSuccess: (detail) => invalidateMajor(queryClient, detail),
  });
}

export function useMajorStatus(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { status: MajorStatus; note?: string | null }) => postJson<MajorDetail>(`/major-incidents/${id}/status`, body),
    onSuccess: (detail) => Promise.all([invalidateMajor(queryClient, detail), invalidateIncidentData(queryClient)]),
  });
}

export function useAddTimelineEntry(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { entryType: TimelineEntryType; body: string; occurredAt?: string | null }) => postJson<TimelineDto>(`/major-incidents/${id}/timeline`, body),
    onSuccess: () => invalidateMajor(queryClient),
  });
}

export function usePostStakeholderUpdate(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: StakeholderUpdateRequest) => postJson<StakeholderUpdateDto>(`/major-incidents/${id}/updates`, body),
    onSuccess: () => Promise.all([invalidateMajor(queryClient), queryClient.invalidateQueries({ queryKey: ['status-page'] })]),
  });
}

export function usePublishStakeholderUpdate(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (updateId: number) => postJson<StakeholderUpdateDto>(`/major-incidents/${id}/updates/${updateId}/publish`),
    onSuccess: () => Promise.all([invalidateMajor(queryClient), queryClient.invalidateQueries({ queryKey: ['status-page'] })]),
  });
}

export function useAddMajorAction(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ActionRequest) => postJson<ActionItemDto>(`/major-incidents/${id}/actions`, body),
    onSuccess: () => invalidateMajor(queryClient),
  });
}

/** `PUT /action-items/{id}` for either kind (major incident follow-ups and review actions). */
export function useUpdateActionItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: ActionRequest }) => putJson<ActionItemDto>(`/action-items/${id}`, body),
    onSuccess: () => Promise.all([invalidateMajor(queryClient), invalidateReview(queryClient)]),
  });
}

// ------------------------------------------------------------------ post-incident reviews

export function useReviews(params: MajorListParams, enabled = true) {
  return useQuery({
    queryKey: reviewKeys.list(params),
    queryFn: () => getJson<PageResponse<PirSummary>>('/reviews', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useReview(id: number | null) {
  return useQuery({
    queryKey: reviewKeys.detail(id ?? 0),
    queryFn: () => getJson<PirDetail>(`/reviews/${id}`),
    staleTime: STALE.standard,
    enabled: Boolean(id),
  });
}

export function useCreateReview() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { incidentId: number; ownerId?: number | null; title?: string | null }) => postJson<PirDetail>('/reviews', body),
    onSuccess: (detail) => invalidateReview(queryClient, detail),
  });
}

export function useUpdateReview(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: PirUpdateRequest) => putJson<PirDetail>(`/reviews/${id}`, body),
    onSuccess: (detail) => invalidateReview(queryClient, detail),
  });
}

export function useReviewCommand(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (command: 'timeline/rebuild' | 'submit' | 'withdraw') => postJson<PirDetail>(`/reviews/${id}/${command}`),
    onSuccess: (detail) => invalidateReview(queryClient, detail),
  });
}

export function useAddReviewAction(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ActionRequest) => postJson<ActionItemDto>(`/reviews/${id}/actions`, body),
    onSuccess: () => invalidateReview(queryClient),
  });
}

export function useReviewPdf() {
  return useMutation({
    mutationFn: ({ id, number }: { id: number; number: string }) => downloadFile(`/reviews/${id}/pdf`, `${number}.pdf`),
  });
}
