import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type { DexEndpointDto, DexMeDto, DexOverviewDto, DexProactiveRuleDto } from '../lib/r4Types';

export const dexKeys = {
  all: ['dex'] as const,
  overview: (includeSimulated: boolean) => ['dex', 'overview', includeSimulated] as const,
  me: (includeSimulated: boolean) => ['dex', 'me', includeSimulated] as const,
};

export function useDexOverview(includeSimulated = false) {
  return useQuery({
    queryKey: dexKeys.overview(includeSimulated),
    queryFn: () => getJson<DexOverviewDto>('/dex/overview', { includeSimulated }),
  });
}

export function useMyDexDevice(includeSimulated = true) {
  return useQuery({
    queryKey: dexKeys.me(includeSimulated),
    queryFn: () => getJson<DexMeDto>('/dex/me', { includeSimulated }),
    retry: false,
  });
}

export function useIngestDexEndpoints() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { endpoints: Partial<DexEndpointDto>[] }) =>
      postJson<DexEndpointDto[]>('/dex/ingest/endpoints', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: dexKeys.all });
    },
  });
}

export function useCreateDexProactiveRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; departmentName: string; thresholdScore: number; enabled?: boolean }) =>
      postJson<DexProactiveRuleDto>('/dex/proactive-rules', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: dexKeys.all });
    },
  });
}
