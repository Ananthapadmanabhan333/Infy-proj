import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type { DeliveryDto, IntegrationDto, IntegrationRequest, IntegrationSaveResult, PageResponse } from '../lib/types';
import { asArray, STALE } from './common';

export function useIntegrations(enabled = true) {
  return useQuery({
    queryKey: ['integrations', 'list'],
    queryFn: () => getJson<IntegrationDto[]>('/integrations'),
    staleTime: STALE.list,
    enabled,
  });
}

export function useDeliveries(id: number | null) {
  return useQuery({
    queryKey: ['integrations', 'deliveries', id],
    queryFn: async () => asArray(await getJson<DeliveryDto[] | PageResponse<DeliveryDto>>(`/integrations/${id}/deliveries`)),
    enabled: id !== null && id > 0,
    staleTime: STALE.live,
  });
}

export function useSaveIntegration() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: IntegrationRequest }) =>
      id ? putJson<IntegrationSaveResult>(`/integrations/${id}`, body) : postJson<IntegrationSaveResult>('/integrations', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['integrations'] }),
  });
}

export function useDeleteIntegration() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/integrations/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['integrations'] }),
  });
}

/** POST /integrations/{id}/test — response shape is not in the contract; a delivery record is expected. */
export function useTestIntegration() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<Partial<DeliveryDto> | null>(`/integrations/${id}/test`),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['integrations'] }),
  });
}

export function useRotateInboundToken() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<{ inboundToken: string }>(`/integrations/${id}/rotate-token`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['integrations'] }),
  });
}
