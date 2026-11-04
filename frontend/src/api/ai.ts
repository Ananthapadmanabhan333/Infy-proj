import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type {
  AiActionRequest,
  AiInteractionDto,
  AiResult,
  AiStatus,
  ApprovalDto,
  AskResponse,
  ClassificationSuggestion,
  DuplicateSuggestions,
  IncidentSummaryAi,
  KnowledgeRecommendations,
  OperationalInsights,
  ResolutionSuggestion,
  RootCauseAnalysis,
  WorkflowSuggestion,
} from '../lib/types';
import type { CmdbImpact, ProblemCorrelation, RunbookRecommendations, SlaRisk, TrendAnalysis } from '../lib/responseTypes';
import { STALE } from './common';
import { useToast } from '../components/Toast';

export function useAiStatus(enabled = true) {
  return useQuery({
    queryKey: ['ai', 'status'],
    queryFn: () => getJson<AiStatus>('/ai/status'),
    staleTime: STALE.standard,
    enabled,
  });
}

const incidentAi = <T,>(path: string) => (incidentId: number) => postJson<AiResult<T>>(`/ai/incidents/${incidentId}/${path}`);

export const useAiClassify = () => useMutation({ mutationFn: incidentAi<ClassificationSuggestion>('classify') });
export const useAiSummarize = () => useMutation({ mutationFn: incidentAi<IncidentSummaryAi>('summarize') });
export const useAiDuplicates = () => useMutation({ mutationFn: incidentAi<DuplicateSuggestions>('duplicates') });
export const useAiKnowledge = () => useMutation({ mutationFn: incidentAi<KnowledgeRecommendations>('knowledge') });
export const useAiRootCause = () => useMutation({ mutationFn: incidentAi<RootCauseAnalysis>('root-cause') });

/**
 * §7 resolution recommendation (staff) — likely cause, confidence 0–100, steps, runbook, articles.
 * Cached per incident so the Overview card and the AI Recommendation tab share one result;
 * generated on demand: call `refetch()` (the request may hit an LLM and is rate limited → 429).
 */
export function useAiResolution(incidentId: number, enabled = false) {
  return useQuery({
    queryKey: ['ai', 'resolution', incidentId],
    queryFn: () => postJson<AiResult<ResolutionSuggestion>>(`/ai/incidents/${incidentId}/resolution`),
    enabled: enabled && Number.isFinite(incidentId) && incidentId > 0,
    staleTime: 5 * 60_000,
    gcTime: 30 * 60_000,
    retry: false,
  });
}

/** §7 drafts a workflow from a goal (WORKFLOW_EDITORS). Shown for review; never saved automatically. */
export function useAiWorkflowSuggestion() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (goal: string) => postJson<AiResult<WorkflowSuggestion>>('/ai/workflow-suggestion', { goal }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ai', 'interactions'] }),
  });
}

/** §7 the current user's recent AI interactions ("Recent Analyses"). */
export function useAiInteractions(size = 5, enabled = true) {
  return useQuery({
    queryKey: ['ai', 'interactions', size],
    queryFn: () => getJson<AiInteractionDto[]>('/ai/interactions', { size }),
    staleTime: STALE.list,
    enabled,
  });
}

/**
 * Operational insights are organisation-wide. The API shares one result for 10 minutes,
 * so `refetch()` (used for the initial load and retries) does not trigger a new model call;
 * `useRegenerateAiInsights` asks for a fresh analysis.
 */
export function useAiInsights() {
  return useQuery({
    queryKey: ['ai', 'insights'],
    queryFn: () => postJson<AiResult<OperationalInsights>>('/ai/insights'),
    enabled: false,
    staleTime: 5 * 60_000,
    gcTime: 30 * 60_000,
    retry: false,
  });
}

export function useRegenerateAiInsights() {
  const queryClient = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: () => postJson<AiResult<OperationalInsights>>('/ai/insights?refresh=true'),
    onSuccess: (data) => {
      queryClient.setQueryData(['ai', 'insights'], data);
      void queryClient.invalidateQueries({ queryKey: ['ai', 'interactions'] });
    },
    onError: (error) => toast.error(error),
  });
}

export function useAiAsk() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (question: string) => postJson<AiResult<AskResponse>>('/ai/ask', { question }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ai', 'interactions'] }),
  });
}

/** Sensitive AI proposals are queued for INCIDENT_MANAGER approval instead of being applied directly. */
export function useAiAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: AiActionRequest) => postJson<ApprovalDto>('/ai/actions', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['approvals'] }),
  });
}

// ------------------------------------------------------------------ R3 BE-4 operations features (docs/API.md "SA AI extensions")
// Each POST counts toward the per-user AI rate limit (429 + Retry-After) and returns `AiResult<T>` labelled LLM or FALLBACK.
// Results are suggestions only: nothing is applied automatically.

function opsAi<T>(path: string) {
  return () => {
    const queryClient = useQueryClient();
    return useMutation({
      mutationFn: (id: number) => postJson<AiResult<T>>(path.replace('{id}', String(id))),
      onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ai', 'interactions'] }),
    });
  };
}

export const useAiProblemCorrelation = opsAi<ProblemCorrelation>('/ai/problems/{id}/correlation');
export const useAiCmdbImpact = opsAi<CmdbImpact>('/ai/cmdb/cis/{id}/impact');
export const useAiRunbookRecommendations = opsAi<RunbookRecommendations>('/ai/incidents/{id}/runbooks');
export const useAiSlaRisk = opsAi<SlaRisk>('/ai/incidents/{id}/sla-risk');

export function useAiTrends() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (days: number) => postJson<AiResult<TrendAnalysis>>('/ai/trends', null, { params: { days } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ai', 'interactions'] }),
  });
}
