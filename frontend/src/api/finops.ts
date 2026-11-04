import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type {
  AllocationRuleDto,
  BudgetStatusDto,
  CostAnomalyDto,
  CostIngestResultDto,
  CostRecommendationDto,
  CostSourceDto,
  FinOpsOverviewDto,
} from '../lib/r4Types';

export const finopsKeys = {
  all: ['finops'] as const,
  overview: (includeSimulated: boolean) => ['finops', 'overview', includeSimulated] as const,
  sources: ['finops', 'sources'] as const,
  allocationRules: ['finops', 'allocation-rules'] as const,
  budgets: (includeSimulated: boolean) => ['finops', 'budgets', includeSimulated] as const,
  anomalies: (includeSimulated: boolean) => ['finops', 'anomalies', includeSimulated] as const,
  recommendations: (includeSimulated: boolean) => ['finops', 'recommendations', includeSimulated] as const,
};

export function useFinOpsOverview(includeSimulated = false) {
  return useQuery({
    queryKey: finopsKeys.overview(includeSimulated),
    queryFn: () => getJson<FinOpsOverviewDto>('/finops/overview', { includeSimulated }),
  });
}

export function useCostSources() {
  return useQuery({
    queryKey: finopsKeys.sources,
    queryFn: () => getJson<CostSourceDto[]>('/finops/sources'),
  });
}

export function useCreateCostSource() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; kind: string; providerName: string; columnMapping?: Record<string, string> }) =>
      postJson<CostSourceDto>('/finops/sources', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: finopsKeys.sources });
    },
  });
}

export function useIngestCosts() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      sourceId?: number | null;
      format: 'FOCUS_CSV' | 'MAPPED_CSV' | string;
      dryRun: boolean;
      csvContent: string;
    }) => postJson<CostIngestResultDto>('/finops/ingest', body),
    onSuccess: (res) => {
      if (!res.dryRun) {
        void qc.invalidateQueries({ queryKey: finopsKeys.all });
      }
    },
  });
}

export function useAllocationRules() {
  return useQuery({
    queryKey: finopsKeys.allocationRules,
    queryFn: () => getJson<AllocationRuleDto[]>('/finops/allocation-rules'),
  });
}

export function useCreateAllocationRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Partial<AllocationRuleDto>) => postJson<AllocationRuleDto>('/finops/allocation-rules', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: finopsKeys.all });
    },
  });
}

export function useFinOpsBudgets(includeSimulated = false) {
  return useQuery({
    queryKey: finopsKeys.budgets(includeSimulated),
    queryFn: () => getJson<BudgetStatusDto[]>('/finops/budgets', { includeSimulated }),
  });
}

export function useCreateBudget() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      name: string;
      targetType: string;
      targetKey: string;
      periodMonth: string;
      amount: number;
      thresholds?: number[];
    }) => postJson<BudgetStatusDto>('/finops/budgets', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: finopsKeys.all });
    },
  });
}

export function useCostAnomalies(includeSimulated = false) {
  return useQuery({
    queryKey: finopsKeys.anomalies(includeSimulated),
    queryFn: () => getJson<CostAnomalyDto[]>('/finops/anomalies', { includeSimulated }),
  });
}

export function useCostRecommendations(includeSimulated = false) {
  return useQuery({
    queryKey: finopsKeys.recommendations(includeSimulated),
    queryFn: () => getJson<CostRecommendationDto[]>('/finops/recommendations', { includeSimulated }),
  });
}

export function useAcceptCostRecommendation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<CostRecommendationDto>(`/finops/recommendations/${id}/accept`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: finopsKeys.all });
    },
  });
}

export function useDismissCostRecommendation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      postJson<CostRecommendationDto>(`/finops/recommendations/${id}/dismiss`, { reason }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: finopsKeys.all });
    },
  });
}
