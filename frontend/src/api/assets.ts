import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, cleanParams, getJson, postJson, putJson } from '../lib/api';
import type {
  AssetDetail,
  AssetEventDto,
  AssetRequestBody,
  AssetStats,
  AssetStatus,
  AssetSummary,
  AssignmentDto,
  ExpiringItem,
  ImportResult,
  MaintenanceDto,
  MaintenanceRequestBody,
} from '../lib/opsTypes';
import type { PageResponse } from '../lib/types';
import { STALE } from './common';

/** Assets (docs/API.md "Assets"): readers read; asset editors (admins, asset managers) write. */
export interface AssetListParams {
  search?: string;
  /** CSV of asset types. */
  type?: string;
  /** CSV of statuses. */
  status?: string;
  category?: string;
  assignedToId?: number;
  departmentId?: number;
  ciId?: number;
  warranty?: string;
  location?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const assetKeys = {
  all: ['assets'] as const,
  list: (params: AssetListParams) => ['assets', 'list', params] as const,
  summary: ['assets', 'summary'] as const,
  expiring: (days?: number) => ['assets', 'expiring', days ?? null] as const,
  detail: (id: number) => ['assets', 'detail', id] as const,
  assignments: (id: number) => ['assets', 'detail', id, 'assignments'] as const,
  history: (id: number) => ['assets', 'detail', id, 'history'] as const,
};

function invalidateAssets(queryClient: QueryClient, detail?: AssetDetail) {
  if (detail) queryClient.setQueryData(assetKeys.detail(detail.asset.id), detail);
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: assetKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['cmdb'] }),
    queryClient.invalidateQueries({ queryKey: ['portal'] }),
  ]);
}

export function useAssets(params: AssetListParams, enabled = true) {
  return useQuery({
    queryKey: assetKeys.list(params),
    queryFn: () => getJson<PageResponse<AssetSummary>>('/assets', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useAssetStats(enabled = true) {
  return useQuery({ queryKey: assetKeys.summary, queryFn: () => getJson<AssetStats>('/assets/summary'), staleTime: STALE.standard, enabled });
}

export function useExpiringAssets(days?: number, enabled = true) {
  return useQuery({
    queryKey: assetKeys.expiring(days),
    queryFn: () => getJson<ExpiringItem[]>('/assets/expiring', { days }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useAsset(id: number | null) {
  return useQuery({
    queryKey: assetKeys.detail(id ?? 0),
    queryFn: () => getJson<AssetDetail>(`/assets/${id}`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

export function useAssetAssignments(id: number | null) {
  return useQuery({
    queryKey: assetKeys.assignments(id ?? 0),
    queryFn: () => getJson<AssignmentDto[]>(`/assets/${id}/assignments`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

export function useAssetHistory(id: number | null) {
  return useQuery({
    queryKey: assetKeys.history(id ?? 0),
    queryFn: () => getJson<AssetEventDto[]>(`/assets/${id}/history`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

export function useSaveAsset() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: AssetRequestBody }) => (id ? putJson<AssetDetail>(`/assets/${id}`, body) : postJson<AssetDetail>('/assets', body)),
    onSuccess: (detail) => invalidateAssets(queryClient, detail),
  });
}

export function useAssetTransition(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { status: AssetStatus; note?: string | null; assigneeId?: number | null }) => postJson<AssetDetail>(`/assets/${id}/transition`, body),
    onSuccess: (detail) => invalidateAssets(queryClient, detail),
  });
}

export function useAssignAsset(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { userId: number; note?: string | null }) => postJson<AssetDetail>(`/assets/${id}/assign`, body),
    onSuccess: (detail) => invalidateAssets(queryClient, detail),
  });
}

export function useReturnAsset(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { userId?: number | null; note?: string | null }) => postJson<AssetDetail>(`/assets/${id}/return`, body),
    onSuccess: (detail) => invalidateAssets(queryClient, detail),
  });
}

export function useRecordMaintenance(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: MaintenanceRequestBody) => postJson<MaintenanceDto>(`/assets/${id}/maintenance`, body),
    onSuccess: () => invalidateAssets(queryClient),
  });
}

/** `POST /assets/import?dryRun=` with a multipart `file`. A dry run stores nothing; an apply stores everything or nothing. */
export async function importAssets(file: File, dryRun: boolean): Promise<ImportResult> {
  const form = new FormData();
  form.append('file', file);
  const response = await api.post<ImportResult>('/assets/import', form, { params: cleanParams({ dryRun }) });
  return response.data;
}

export function useImportAssets() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ file, dryRun }: { file: File; dryRun: boolean }) => importAssets(file, dryRun),
    onSuccess: (result) => (result.applied ? invalidateAssets(queryClient) : undefined),
  });
}
