import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson, putJson } from '../lib/api';
import type {
  BusinessServiceTree,
  ChangeTimeline,
  TwinEdge,
  TwinGraph,
  TwinMeta,
  TwinSimulationRequest,
  TwinSimulationResult,
  TwinSimulationSummary,
} from '../lib/r4Types';
import { STALE } from './common';

/** SA Digital Twin (`/api/twin/**`): readers read and simulate (read-only); TWIN_EDITORS toggle relationship redundancy. */
export interface TwinGraphParams {
  root?: number;
  depth?: number;
  layer?: string;
  includeState?: boolean;
  includeSimulated?: boolean;
}

export interface TwinChangesParams {
  ciId?: number;
  serviceId?: number;
  from?: string;
  to?: string;
  includeSimulated?: boolean;
  limit?: number;
}

export const twinKeys = {
  all: ['twin'] as const,
  meta: ['twin', 'meta'] as const,
  graph: (params: TwinGraphParams) => ['twin', 'graph', params] as const,
  tree: (id: number, includeSimulated: boolean) => ['twin', 'tree', id, includeSimulated] as const,
  simulations: (limit: number) => ['twin', 'simulations', limit] as const,
  simulation: (id: number) => ['twin', 'simulation', id] as const,
  changes: (params: TwinChangesParams) => ['twin', 'changes', params] as const,
};

export function useTwinMeta() {
  return useQuery({ queryKey: twinKeys.meta, queryFn: () => getJson<TwinMeta>('/twin/meta'), staleTime: STALE.reference });
}

export function useTwinGraph(params: TwinGraphParams, enabled = true) {
  return useQuery({
    queryKey: twinKeys.graph(params),
    queryFn: () => getJson<TwinGraph>('/twin/graph', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useBusinessServiceTree(serviceId: number | null, includeSimulated: boolean) {
  return useQuery({
    queryKey: twinKeys.tree(serviceId ?? 0, includeSimulated),
    queryFn: () => getJson<BusinessServiceTree>(`/twin/business-services/${serviceId}`, { includeSimulated }),
    staleTime: STALE.live,
    enabled: serviceId !== null,
  });
}

export function useTwinSimulations(limit = 20) {
  return useQuery({ queryKey: twinKeys.simulations(limit), queryFn: () => getJson<TwinSimulationSummary[]>('/twin/simulations', { limit }), staleTime: STALE.list });
}

export function useTwinSimulation(id: number | null) {
  return useQuery({
    queryKey: twinKeys.simulation(id ?? 0),
    queryFn: () => getJson<TwinSimulationResult>(`/twin/simulations/${id}`),
    staleTime: STALE.reference,
    enabled: id !== null,
  });
}

export function useRunTwinSimulation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: TwinSimulationRequest) => postJson<TwinSimulationResult>('/twin/simulations', body),
    onSuccess: (result) => {
      queryClient.setQueryData(twinKeys.simulation(result.id), result);
      return queryClient.invalidateQueries({ queryKey: ['twin', 'simulations'] });
    },
  });
}

export function useTwinChanges(params: TwinChangesParams, enabled = true) {
  return useQuery({
    queryKey: twinKeys.changes(params),
    queryFn: () => getJson<ChangeTimeline>('/twin/changes', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useSetRedundancy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, redundant, note }: { id: number; redundant: boolean; note?: string }) => putJson<TwinEdge>(`/twin/relationships/${id}`, { redundant, note: note || undefined }),
    onSuccess: () => Promise.all([queryClient.invalidateQueries({ queryKey: twinKeys.all }), queryClient.invalidateQueries({ queryKey: ['cmdb'] })]),
  });
}
