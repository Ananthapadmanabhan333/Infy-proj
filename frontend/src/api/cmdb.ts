import { keepPreviousData, useMutation, useQueries, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type {
  CiDetail,
  CiHistoryDto,
  CiRequestBody,
  CiSummary,
  CmdbGraph,
  CmdbMeta,
  ImpactAnalysis,
  ImpactDirection,
  RelationshipDto,
  RelationshipType,
} from '../lib/opsTypes';
import type { PageResponse } from '../lib/types';
import { STALE } from './common';

/** CMDB (docs/API.md "CMDB"): readers read; CMDB editors (admins, asset managers, IT managers) write. */
export interface CiListParams {
  search?: string;
  /** CSV of CI types. */
  type?: string;
  /** CSV of statuses. */
  status?: string;
  environment?: string;
  criticality?: string;
  teamId?: number;
  ownerId?: number;
  serviceId?: number;
  page?: number;
  size?: number;
  sort?: string;
}

export interface GraphParams {
  ciId?: number;
  depth?: number;
  type?: string;
  environment?: string;
  includeRetired?: boolean;
}

export const cmdbKeys = {
  all: ['cmdb'] as const,
  meta: ['cmdb', 'meta'] as const,
  list: (params: CiListParams) => ['cmdb', 'list', params] as const,
  detail: (id: number) => ['cmdb', 'detail', id] as const,
  history: (id: number) => ['cmdb', 'detail', id, 'history'] as const,
  impact: (id: number, direction: ImpactDirection, depth: number) => ['cmdb', 'detail', id, 'impact', direction, depth] as const,
  graph: (params: GraphParams) => ['cmdb', 'graph', params] as const,
};

function invalidateCmdb(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: cmdbKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['assets'] }),
    queryClient.invalidateQueries({ queryKey: ['problems'] }),
    queryClient.invalidateQueries({ queryKey: ['changes'] }),
  ]);
}

export function useCmdbMeta(enabled = true) {
  return useQuery({ queryKey: cmdbKeys.meta, queryFn: () => getJson<CmdbMeta>('/cmdb/meta'), staleTime: STALE.reference, enabled });
}

export function useCis(params: CiListParams, enabled = true) {
  return useQuery({
    queryKey: cmdbKeys.list(params),
    queryFn: () => getJson<PageResponse<CiSummary>>('/cmdb/cis', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useCi(id: number | null) {
  return useQuery({
    queryKey: cmdbKeys.detail(id ?? 0),
    queryFn: () => getJson<CiDetail>(`/cmdb/cis/${id}`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

/** Names of CIs referenced only by id (rule matchers): `id → name` for the ones that loaded. */
export function useCiNames(ids: number[]): Map<number, string> {
  const results = useQueries({
    queries: ids.map((id) => ({ queryKey: cmdbKeys.detail(id), queryFn: () => getJson<CiDetail>(`/cmdb/cis/${id}`), staleTime: STALE.reference })),
  });
  const names = new Map<number, string>();
  results.forEach((result, index) => {
    if (result.data) names.set(ids[index], result.data.name);
  });
  return names;
}

export function useCiHistory(id: number | null) {
  return useQuery({
    queryKey: cmdbKeys.history(id ?? 0),
    queryFn: () => getJson<CiHistoryDto[]>(`/cmdb/cis/${id}/history`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

export function useCiImpact(id: number | null, direction: ImpactDirection, depth: number, enabled = true) {
  return useQuery({
    queryKey: cmdbKeys.impact(id ?? 0, direction, depth),
    queryFn: () => getJson<ImpactAnalysis>(`/cmdb/cis/${id}/impact`, { direction, depth }),
    staleTime: STALE.standard,
    enabled: enabled && Boolean(id),
  });
}

export function useCmdbGraph(params: GraphParams, enabled = true) {
  return useQuery({
    queryKey: cmdbKeys.graph(params),
    queryFn: () => getJson<CmdbGraph>('/cmdb/graph', { ...params, includeRetired: params.includeRetired || undefined }),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
    enabled,
  });
}

export function useSaveCi() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: CiRequestBody }) => (id ? putJson<CiDetail>(`/cmdb/cis/${id}`, body) : postJson<CiDetail>('/cmdb/cis', body)),
    onSuccess: (ci) => {
      queryClient.setQueryData(cmdbKeys.detail(ci.id), ci);
      return invalidateCmdb(queryClient);
    },
  });
}

export function useDeleteCi() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/cmdb/cis/${id}`),
    onSuccess: (_, id) => {
      queryClient.removeQueries({ queryKey: cmdbKeys.detail(id) });
      return invalidateCmdb(queryClient);
    },
  });
}

export function useAddRelationship() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { sourceId: number; targetId: number; type: RelationshipType; description?: string | null }) => postJson<RelationshipDto>('/cmdb/relationships', body),
    onSuccess: () => invalidateCmdb(queryClient),
  });
}

export function useRemoveRelationship() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/cmdb/relationships/${id}`),
    onSuccess: () => invalidateCmdb(queryClient),
  });
}
