import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type {
  MaintenanceRequest,
  MaintenanceWindowStatus,
  StatusComponentDto,
  StatusComponentRequest,
  StatusMaintenanceDto,
  StatusPageSettingsDto,
  StatusPageView,
  StatusUpdateDto,
  StatusUpdateRequest,
} from '../lib/types';
import { STALE } from './common';

/**
 * Internal status page (R3, `/api/status-page`): any signed-in user reads (employees see public components and
 * updates only), status page editors write. The public `/status` page lives in `api/statusPage.ts`.
 */
export const statusPageKeys = { view: ['status-page', 'view'] as const };

export function useStatusPageView(enabled = true) {
  return useQuery({
    queryKey: statusPageKeys.view,
    queryFn: () => getJson<StatusPageView>('/status-page'),
    staleTime: STALE.standard,
    refetchInterval: 60_000,
    enabled,
  });
}

function invalidate(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['status-page'] }),
    queryClient.invalidateQueries({ queryKey: ['public-status'] }),
    queryClient.invalidateQueries({ queryKey: ['services'] }),
    queryClient.invalidateQueries({ queryKey: ['portal'] }),
  ]);
}

export function useSaveStatusSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { title: string; description: string | null; publicEnabled: boolean }) => putJson<StatusPageSettingsDto>('/status-page/settings', body),
    onSuccess: () => invalidate(queryClient),
  });
}

export function useSaveStatusComponent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: StatusComponentRequest }) =>
      id ? putJson<StatusComponentDto>(`/status-page/components/${id}`, body) : postJson<StatusComponentDto>('/status-page/components', body),
    onSuccess: () => invalidate(queryClient),
  });
}

export function useDeleteStatusComponent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/status-page/components/${id}`),
    onSuccess: () => invalidate(queryClient),
  });
}

export function useSaveMaintenance() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: MaintenanceRequest }) =>
      id ? putJson<StatusMaintenanceDto>(`/status-page/maintenance/${id}`, body) : postJson<StatusMaintenanceDto>('/status-page/maintenance', body),
    onSuccess: () => invalidate(queryClient),
  });
}

export function useMaintenanceStatus() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status }: { id: number; status: MaintenanceWindowStatus }) => postJson<StatusMaintenanceDto>(`/status-page/maintenance/${id}/status`, { status }),
    onSuccess: () => invalidate(queryClient),
  });
}

export function useSaveStatusUpdate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: StatusUpdateRequest }) =>
      id ? putJson<StatusUpdateDto>(`/status-page/updates/${id}`, body) : postJson<StatusUpdateDto>('/status-page/updates', body),
    onSuccess: () => invalidate(queryClient),
  });
}
