import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson, putJson } from '../lib/api';
import type { BusinessServiceRequest, ServiceDetail, ServiceDto } from '../lib/types';
import { STALE } from './common';

/** Body of POST/PUT /services (§11 adds `dependsOnIds`). */
export type ServiceRequestBody = BusinessServiceRequest;

export function useServices(enabled = true) {
  return useQuery({
    queryKey: ['services', 'list'],
    queryFn: () => getJson<ServiceDto[]>('/services'),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useService(id: number | null) {
  return useQuery({
    queryKey: ['services', 'detail', id],
    queryFn: () => getJson<ServiceDetail>(`/services/${id}`),
    enabled: id !== null && id > 0,
    staleTime: STALE.standard,
  });
}

export function useSaveService() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: ServiceRequestBody }) =>
      id ? putJson<ServiceDto>(`/services/${id}`, body) : postJson<ServiceDto>('/services', body),
    // Dependencies change other services' effective status, so refresh every service view.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['services'] }),
  });
}
