import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { getJson, postJson, putJson } from '../lib/api';
import type {
  DeploymentCalendar,
  DeploymentDetail,
  DeploymentDto,
  DeploymentEnvironment,
  DeploymentMetrics,
  DeploymentRequestBody,
  DeploymentSourceDto,
  ReleaseDto,
  ReleaseRequestBody,
  SourceSaveResult,
  WebhookDeliveryDto,
  WebhookProvider,
} from '../lib/itsmTypes';
import type { PageResponse } from '../lib/types';
import { STALE } from './common';

/** Deployments and releases (docs/API.md "Deployments and releases"): readers read; deployment editors write. */
export interface DeploymentListParams {
  search?: string;
  serviceId?: number;
  environment?: string;
  status?: string;
  releaseId?: number;
  changeId?: number;
  source?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const deploymentKeys = {
  all: ['deployments'] as const,
  list: (params: DeploymentListParams) => ['deployments', 'list', params] as const,
  detail: (id: number) => ['deployments', 'detail', id] as const,
  calendar: (from: string, to: string, serviceId?: number, environment?: string) => ['deployments', 'calendar', from, to, serviceId ?? null, environment ?? null] as const,
  metrics: (days?: number) => ['deployments', 'metrics', days ?? null] as const,
  sources: ['deployments', 'sources'] as const,
  deliveries: (id: number) => ['deployments', 'sources', id, 'deliveries'] as const,
  releases: (params: object) => ['releases', 'list', params] as const,
  release: (id: number) => ['releases', 'detail', id] as const,
};

function invalidateDeployments(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: deploymentKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['releases'] }),
    queryClient.invalidateQueries({ queryKey: ['changes'] }),
  ]);
}

export function useDeployments(params: DeploymentListParams, enabled = true) {
  return useQuery({
    queryKey: deploymentKeys.list(params),
    queryFn: () => getJson<PageResponse<DeploymentDto>>('/deployments', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useDeployment(id: number | null) {
  return useQuery({
    queryKey: deploymentKeys.detail(id ?? 0),
    queryFn: () => getJson<DeploymentDetail>(`/deployments/${id}`),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}

export function useDeploymentCalendar(from: string, to: string, serviceId?: number, environment?: string, enabled = true) {
  return useQuery({
    queryKey: deploymentKeys.calendar(from, to, serviceId, environment),
    queryFn: () => getJson<DeploymentCalendar>('/deployments/calendar', { from, to, serviceId, environment }),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
    enabled,
  });
}

export function useDeploymentMetrics(days?: number, enabled = true) {
  return useQuery({
    queryKey: deploymentKeys.metrics(days),
    queryFn: () => getJson<DeploymentMetrics>('/deployments/metrics', { days }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useCreateDeployment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: DeploymentRequestBody) => postJson<DeploymentDto>('/deployments', body),
    onSuccess: () => invalidateDeployments(queryClient),
  });
}

export function useDeploymentStatus(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { status: 'SUCCEEDED' | 'FAILED'; notes?: string }) => postJson<DeploymentDto>(`/deployments/${id}/status`, body),
    onSuccess: () => invalidateDeployments(queryClient),
  });
}

export function useRollbackDeployment(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { reason: string; targetVersion?: string }) => postJson<DeploymentDto>(`/deployments/${id}/rollback`, body),
    onSuccess: () => invalidateDeployments(queryClient),
  });
}

export function useReleases(params: { search?: string; status?: string; serviceId?: number; page?: number; size?: number; sort?: string }, enabled = true) {
  return useQuery({
    queryKey: deploymentKeys.releases(params),
    queryFn: () => getJson<PageResponse<ReleaseDto>>('/releases', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useRelease(id: number | null) {
  return useQuery({
    queryKey: deploymentKeys.release(id ?? 0),
    queryFn: () => getJson<{ release: ReleaseDto; deployments: DeploymentDto[] }>(`/releases/${id}`),
    staleTime: STALE.standard,
    enabled: Boolean(id),
  });
}

export function useSaveRelease() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: ReleaseRequestBody }) => (id ? putJson<ReleaseDto>(`/releases/${id}`, body) : postJson<ReleaseDto>('/releases', body)),
    onSuccess: () => invalidateDeployments(queryClient),
  });
}

/** CI/CD webhook sources (deployment editors). The secret is only returned on create and rotation. */
export function useDeploymentSources(enabled = true) {
  return useQuery({
    queryKey: deploymentKeys.sources,
    queryFn: () => getJson<DeploymentSourceDto[]>('/deployments/sources'),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useSaveDeploymentSource() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      body,
    }: {
      id?: number;
      body: { name: string; provider: WebhookProvider; serviceId: number; defaultEnvironment?: DeploymentEnvironment | null; active?: boolean };
    }) => (id ? putJson<SourceSaveResult>(`/deployments/sources/${id}`, body) : postJson<SourceSaveResult>('/deployments/sources', body)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: deploymentKeys.sources }),
  });
}

export function useRotateSourceSecret() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<SourceSaveResult>(`/deployments/sources/${id}/rotate-secret`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: deploymentKeys.sources }),
  });
}

export function useSourceDeliveries(id: number | null) {
  return useQuery({
    queryKey: deploymentKeys.deliveries(id ?? 0),
    queryFn: () => getJson<WebhookDeliveryDto[]>(`/deployments/sources/${id}/deliveries`, { size: 20 }),
    staleTime: STALE.live,
    enabled: Boolean(id),
  });
}
