import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type { SimulationPurgeResult, SimulationRunDto, SimulationStatusDto } from '../lib/r4Types';
import { STALE } from './common';

/** Simulation mode (API "Release 4 — Simulation mode"): status for every signed-in user, runs for readers, writes for SIMULATION_ADMINS. */
export const simulationKeys = {
  all: ['simulation'] as const,
  status: ['simulation', 'status'] as const,
  runs: (limit: number) => ['simulation', 'runs', limit] as const,
};

/** Polled lightly for the global banner (60 s); pages that act on it refetch after every mutation. */
export function useSimulationStatus(enabled = true, refetchInterval: number | false = 60_000) {
  return useQuery({
    queryKey: simulationKeys.status,
    queryFn: () => getJson<SimulationStatusDto>('/simulation/status'),
    staleTime: STALE.standard,
    refetchInterval,
    enabled,
  });
}

export function useSimulationRuns(limit = 20, enabled = true) {
  return useQuery({
    queryKey: simulationKeys.runs(limit),
    queryFn: () => getJson<SimulationRunDto[]>('/simulation/runs', { limit }),
    staleTime: STALE.live,
    enabled,
  });
}

/** Simulated data changes what almost every Release 4 view (and alerts/incidents/deployments) shows. */
function invalidateSimulated(queryClient: QueryClient) {
  return Promise.all(
    ['simulation', 'observability', 'twin', 'alerts', 'incidents', 'deployments', 'dashboard'].map((key) => queryClient.invalidateQueries({ queryKey: [key] })),
  );
}

export function useSetSimulationMode() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (enabled: boolean) => putJson<SimulationStatusDto>('/simulation/mode', { enabled }),
    onSuccess: (status) => {
      queryClient.setQueryData(simulationKeys.status, status);
      return invalidateSimulated(queryClient);
    },
  });
}

export function useStartScenario() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => postJson<SimulationRunDto>(`/simulation/scenarios/${encodeURIComponent(code)}/start`),
    onSettled: () => invalidateSimulated(queryClient),
  });
}

export function useStopScenario() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => postJson<SimulationRunDto>(`/simulation/scenarios/${encodeURIComponent(code)}/stop`),
    onSettled: () => invalidateSimulated(queryClient),
  });
}

export function usePurgeSimulatedData() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => deleteJson<SimulationPurgeResult>('/simulation/data'),
    onSettled: () => invalidateSimulated(queryClient),
  });
}

/** Whether the banner should show: mode on and a scenario running or simulated rows stored. */
export function simulationActive(status: SimulationStatusDto | undefined): boolean {
  if (!status?.enabled) return false;
  if (status.runningRuns?.length) return true;
  return Object.values(status.simulatedRecords ?? {}).some((count) => count > 0);
}

export function simulatedRecordTotal(status: SimulationStatusDto | undefined): number {
  return Object.values(status?.simulatedRecords ?? {}).reduce((sum, count) => sum + (count || 0), 0);
}
