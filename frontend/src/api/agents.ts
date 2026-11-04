import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type {
  AgentActionDto,
  AgentCode,
  AgentDto,
  AgentEvalCaseDto,
  AgentEvalCaseRequest,
  AgentEvalRunDto,
  AgentFeedbackDto,
  AgentGovernanceDto,
  AgentPolicyDto,
  AgentPolicyRequest,
  AgentRunDetailDto,
  AgentRunSummaryDto,
  AgentRunType,
  AgentToolDto,
  AgentVersionDto,
  StartAgentRunRequest,
} from '../lib/r4Types';
import type { PageResponse } from '../lib/types';
import { STALE } from './common';

/** SA Agentic AI (`/api/agents/**`): READERS read; AGENT_USERS run/cancel/propose/feedback; AI_GOVERNANCE edits policies & eval suite. */
export const agentKeys = {
  all: ['agents'] as const,
  catalogue: ['agents', 'catalogue'] as const,
  tools: ['agents', 'tools'] as const,
  versions: (code?: AgentCode) => ['agents', 'versions', code ?? 'ALL'] as const,
  policies: ['agents', 'policies'] as const,
  runs: (params: AgentRunListParams) => ['agents', 'runs', params] as const,
  run: (id: number) => ['agents', 'run', id] as const,
  evalCases: ['agents', 'eval-cases'] as const,
  evalRuns: (limit: number) => ['agents', 'eval-runs', limit] as const,
  governance: (days: number) => ['agents', 'governance', days] as const,
};

export interface AgentRunListParams {
  runType?: AgentRunType | '';
  status?: string;
  subjectType?: string;
  subjectId?: number;
  includeSimulated?: boolean;
  page?: number;
  size?: number;
}

export function useAgentCatalogue() {
  return useQuery({
    queryKey: agentKeys.catalogue,
    queryFn: () => getJson<AgentDto[]>('/agents'),
    staleTime: STALE.reference,
  });
}

export function useAgentTools() {
  return useQuery({
    queryKey: agentKeys.tools,
    queryFn: () => getJson<AgentToolDto[]>('/agents/tools'),
    staleTime: STALE.reference,
  });
}

export function useAgentVersions(agentCode?: AgentCode) {
  return useQuery({
    queryKey: agentKeys.versions(agentCode),
    queryFn: () => getJson<AgentVersionDto[]>('/agents/versions', { agentCode }),
  });
}

export function useAgentPolicies() {
  return useQuery({
    queryKey: agentKeys.policies,
    queryFn: () => getJson<AgentPolicyDto[]>('/agents/policies'),
  });
}

export function useAgentRuns(params: AgentRunListParams = {}) {
  return useQuery({
    queryKey: agentKeys.runs(params),
    queryFn: () =>
      getJson<PageResponse<AgentRunSummaryDto>>('/agents/runs', {
        runType: params.runType || undefined,
        status: params.status || undefined,
        subjectType: params.subjectType || undefined,
        subjectId: params.subjectId,
        includeSimulated: params.includeSimulated ?? false,
        page: params.page ?? 0,
        size: params.size ?? 20,
      }),
    placeholderData: keepPreviousData,
  });
}

export function useAgentRun(id: number | null) {
  return useQuery({
    queryKey: agentKeys.run(id ?? 0),
    queryFn: () => getJson<AgentRunDetailDto>(`/agents/runs/${id}`),
    enabled: Boolean(id && id > 0),
  });
}

export function useAgentEvalCases() {
  return useQuery({
    queryKey: agentKeys.evalCases,
    queryFn: () => getJson<AgentEvalCaseDto[]>('/agents/evaluations/cases'),
  });
}

export function useAgentEvalRuns(limit = 20) {
  return useQuery({
    queryKey: agentKeys.evalRuns(limit),
    queryFn: () => getJson<AgentEvalRunDto[]>('/agents/evaluations/runs', { limit }),
  });
}

export function useAgentGovernance(days = 30) {
  return useQuery({
    queryKey: agentKeys.governance(days),
    queryFn: () => getJson<AgentGovernanceDto>('/agents/governance', { days }),
  });
}

export function useStartAgentRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: StartAgentRunRequest) => postJson<AgentRunDetailDto>('/agents/runs', body),
    onSuccess: (detail) => {
      if (detail?.run?.id) {
        qc.setQueryData(agentKeys.run(detail.run.id), detail);
      }
      void qc.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useCancelAgentRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (runId: number) => postJson<AgentRunSummaryDto>(`/agents/runs/${runId}/cancel`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useProposeAgentAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ runId, actionId }: { runId: number; actionId: number }) =>
      postJson<AgentActionDto>(`/agents/runs/${runId}/actions/${actionId}/propose`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: agentKeys.all });
      void qc.invalidateQueries({ queryKey: ['approvals'] });
    },
  });
}

export function useSaveAgentFeedback() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ runId, helpful, comment }: { runId: number; helpful: boolean; comment?: string | null }) =>
      postJson<AgentFeedbackDto>(`/agents/runs/${runId}/feedback`, { helpful, comment }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useUpdateAgentPolicy() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ code, body }: { code: AgentCode; body: AgentPolicyRequest }) =>
      putJson<AgentPolicyDto>(`/agents/policies/${code}`, body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}

export function useCreateAgentEvalCase() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: AgentEvalCaseRequest) => postJson<AgentEvalCaseDto>('/agents/evaluations/cases', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: agentKeys.evalCases });
    },
  });
}

export function useDeleteAgentEvalCase() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson<void>(`/agents/evaluations/cases/${id}`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: agentKeys.evalCases });
    },
  });
}

export function useRunAgentEvaluations() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (caseIds?: number[] | null) =>
      postJson<AgentEvalRunDto>('/agents/evaluations/run', caseIds?.length ? { caseIds } : {}),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: agentKeys.all });
    },
  });
}
