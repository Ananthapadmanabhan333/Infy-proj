import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { AxiosProgressEvent } from 'axios';
import { api, deleteJson, getJson, postJson, putJson } from '../lib/api';
import { ACTIVE_STATUSES } from '../lib/constants';
import type {
  ActivityItem,
  AssignRequest,
  AttachmentDto,
  CommentDto,
  CreateIncidentRequest,
  CreateIncidentResult,
  DuplicateCandidate,
  HistoryDto,
  IncidentCounts,
  IncidentDetail,
  IncidentStatus,
  IncidentSummary,
  LinkType,
  PageResponse,
  SlaDetail,
  TransitionRequest,
  UpdateIncidentRequest,
} from '../lib/types';
import { invalidateIncidentData, STALE } from './common';

export interface IncidentListParams {
  page?: number;
  size?: number;
  sort?: string;
  search?: string;
  status?: string;
  priority?: string;
  category?: string;
  serviceId?: string | number;
  departmentId?: string | number;
  assigneeId?: string | number;
  /** R3 `team` = incidents of the teams I belong to. */
  scope?: 'all' | 'mine' | 'department' | 'team' | 'reported' | '';
  /** R3 assigned team. */
  teamId?: string | number;
  major?: boolean;
  parentOnly?: boolean;
  slaHealth?: string;
}

/** Filters accepted by GET /incidents/counts (the list filters minus paging and sort). */
export type IncidentCountParams = Omit<IncidentListParams, 'page' | 'size' | 'sort'>;

export const incidentKeys = {
  all: ['incidents'] as const,
  list: (params: IncidentListParams) => ['incidents', 'list', params] as const,
  counts: (params: IncidentCountParams) => ['incidents', 'counts', params] as const,
  detail: (id: number) => ['incidents', 'detail', id] as const,
  activity: (id: number) => ['incidents', 'detail', id, 'activity'] as const,
  comments: (id: number) => ['incidents', 'detail', id, 'comments'] as const,
  attachments: (id: number) => ['incidents', 'detail', id, 'attachments'] as const,
  history: (id: number) => ['incidents', 'detail', id, 'history'] as const,
  sla: (id: number) => ['incidents', 'detail', id, 'sla'] as const,
};

export function useIncidents(params: IncidentListParams, enabled = true) {
  return useQuery({
    queryKey: incidentKeys.list(params),
    queryFn: () => getJson<PageResponse<IncidentSummary>>('/incidents', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

/** §3 tab counts { all, open, inProgress, resolved } for the same filters as the list. */
export function useIncidentCounts(params: IncidentCountParams, enabled = true) {
  return useQuery({
    queryKey: incidentKeys.counts(params),
    queryFn: () => getJson<IncidentCounts>('/incidents/counts', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

/** Live count for the sidebar badge (employees automatically get their own reported incidents). */
export function useOpenIncidentCount(enabled = true) {
  return useQuery({
    queryKey: ['incidents', 'open-count'],
    queryFn: () => getJson<PageResponse<IncidentSummary>>('/incidents', { status: ACTIVE_STATUSES, size: 1 }),
    select: (page) => page.totalElements,
    refetchInterval: 60_000,
    staleTime: STALE.live,
    enabled,
  });
}

export function useIncident(id: number) {
  return useQuery({
    queryKey: incidentKeys.detail(id),
    queryFn: () => getJson<IncidentDetail>(`/incidents/${id}`),
    staleTime: STALE.live,
    refetchInterval: 60_000,
    enabled: Number.isFinite(id) && id > 0,
  });
}

export function useIncidentComments(id: number) {
  return useQuery({
    queryKey: incidentKeys.comments(id),
    queryFn: () => getJson<CommentDto[]>(`/incidents/${id}/comments`),
    staleTime: STALE.live,
  });
}

export function useIncidentAttachments(id: number) {
  return useQuery({
    queryKey: incidentKeys.attachments(id),
    queryFn: () => getJson<AttachmentDto[]>(`/incidents/${id}/attachments`),
    staleTime: STALE.standard,
  });
}

export function useIncidentHistory(id: number) {
  return useQuery({
    queryKey: incidentKeys.history(id),
    queryFn: () => getJson<HistoryDto[]>(`/incidents/${id}/history`),
    staleTime: STALE.live,
  });
}

/** §3 merged timeline (history, comments, SLA, escalations, approvals, runbooks), newest first. */
export function useIncidentActivity(id: number, enabled = true) {
  return useQuery({
    queryKey: incidentKeys.activity(id),
    queryFn: () => getJson<ActivityItem[]>(`/incidents/${id}/activity`),
    staleTime: STALE.live,
    enabled: enabled && Number.isFinite(id) && id > 0,
  });
}

export function useIncidentSla(id: number, enabled = true) {
  return useQuery({
    queryKey: incidentKeys.sla(id),
    queryFn: () => getJson<SlaDetail>(`/incidents/${id}/sla`),
    refetchInterval: 60_000,
    staleTime: STALE.live,
    enabled,
    retry: false,
  });
}

export interface DuplicatePreviewInput {
  title: string;
  description?: string;
  serviceId?: number | null;
}

export function usePreviewDuplicates(input: DuplicatePreviewInput, enabled: boolean) {
  return useQuery({
    queryKey: ['incidents', 'preview-duplicates', input],
    queryFn: () => postJson<DuplicateCandidate[]>('/incidents/preview-duplicates', input),
    enabled,
    staleTime: STALE.standard,
    retry: false,
  });
}

export function useCreateIncident() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateIncidentRequest) => postJson<CreateIncidentResult>('/incidents', body),
    onSuccess: () => invalidateIncidentData(queryClient),
  });
}

/** Mutation on a single incident that refreshes every view an incident change can affect. */
function useIncidentMutation<TVariables = void, TResult = IncidentDetail>(request: (variables: TVariables) => Promise<TResult>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: request,
    onSuccess: () => invalidateIncidentData(queryClient),
  });
}

export const useUpdateIncident = (id: number) =>
  useIncidentMutation((body: UpdateIncidentRequest) => putJson<IncidentDetail>(`/incidents/${id}`, body));

export const useTransitionIncident = (id: number) =>
  useIncidentMutation((body: TransitionRequest) => postJson<IncidentDetail>(`/incidents/${id}/transition`, body));

export const useAssignIncident = (id: number) =>
  useIncidentMutation((body: AssignRequest) => postJson<IncidentDetail>(`/incidents/${id}/assign`, body));

/** Assign any incident (My Work team queue: take / assign within the team). */
export const useAssignAnyIncident = () =>
  useIncidentMutation(({ id, body }: { id: number; body: AssignRequest }) => postJson<IncidentDetail>(`/incidents/${id}/assign`, body));

export const useAutoAssignIncident = (id: number) =>
  useIncidentMutation(() => postJson<IncidentDetail>(`/incidents/${id}/auto-assign`));

export const useEscalateIncident = (id: number) =>
  useIncidentMutation((reason: string) => postJson<IncidentDetail>(`/incidents/${id}/escalate`, { reason }));

export const useSetMajor = (id: number) =>
  useIncidentMutation((major: boolean) => postJson<IncidentDetail>(`/incidents/${id}/major`, { major }));

export const useLinkParent = (id: number) =>
  useIncidentMutation((parentId: number) => postJson<IncidentDetail>(`/incidents/${id}/parent`, { parentId }));

export const useUnlinkParent = (id: number) => useIncidentMutation(() => deleteJson<IncidentDetail>(`/incidents/${id}/parent`));

export const useAddIncidentLink = (id: number) =>
  useIncidentMutation((body: { targetId: number; linkType: LinkType }) => postJson<unknown>(`/incidents/${id}/links`, body));

export const useRemoveIncidentLink = (id: number) =>
  useIncidentMutation((linkId: number) => deleteJson<unknown>(`/incidents/${id}/links/${linkId}`));

export function useAddComment(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { body: string; internal: boolean }) => postJson<CommentDto>(`/incidents/${id}/comments`, body),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: incidentKeys.comments(id) }),
        queryClient.invalidateQueries({ queryKey: incidentKeys.history(id) }),
        queryClient.invalidateQueries({ queryKey: incidentKeys.detail(id) }),
      ]),
  });
}

export function useUploadAttachment(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ file, onProgress }: { file: File; onProgress?: (percent: number) => void }) => {
      const form = new FormData();
      form.append('file', file);
      return api
        .post<AttachmentDto>(`/incidents/${id}/attachments`, form, {
          onUploadProgress: (event: AxiosProgressEvent) => {
            if (onProgress && event.total) onProgress(Math.round((event.loaded / event.total) * 100));
          },
        })
        .then((response) => response.data);
    },
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: incidentKeys.attachments(id) }),
        queryClient.invalidateQueries({ queryKey: incidentKeys.history(id) }),
      ]),
  });
}

export function useDeleteAttachment(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (attachmentId: number) => deleteJson(`/incidents/${id}/attachments/${attachmentId}`),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: incidentKeys.attachments(id) }),
        queryClient.invalidateQueries({ queryKey: incidentKeys.history(id) }),
      ]),
  });
}

// ---------------------------------------------------------------------------
// Bulk operations (§3): done client-side by calling the per-incident endpoints.
// ---------------------------------------------------------------------------
export type BulkIncidentAction =
  | { action: 'assign'; body: AssignRequest }
  | { action: 'transition'; status: IncidentStatus; comment?: string; resolutionCode?: TransitionRequest['resolutionCode']; resolutionNotes?: string };

export interface BulkIncidentResult {
  succeeded: number[];
  failed: { id: number; message: string }[];
}

/** Applies one action to many incidents sequentially; never throws — failures are reported per incident. */
export function useBulkIncidentAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ ids, ...input }: BulkIncidentAction & { ids: number[] }): Promise<BulkIncidentResult> => {
      const result: BulkIncidentResult = { succeeded: [], failed: [] };
      for (const id of ids) {
        try {
          if (input.action === 'assign') await postJson<IncidentDetail>(`/incidents/${id}/assign`, input.body);
          else
            await postJson<IncidentDetail>(`/incidents/${id}/transition`, {
              status: input.status,
              comment: input.comment,
              resolutionCode: input.resolutionCode,
              resolutionNotes: input.resolutionNotes,
            });
          result.succeeded.push(id);
        } catch (error) {
          result.failed.push({ id, message: error instanceof Error ? error.message : 'Request failed' });
        }
      }
      return result;
    },
    onSettled: () => invalidateIncidentData(queryClient),
  });
}
