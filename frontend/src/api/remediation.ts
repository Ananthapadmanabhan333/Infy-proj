import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type {
  AuditVerifyResultDto,
  CreateExecutionSystemRequest,
  CreatePlaybookRequest,
  ExecutionSystemDto,
  PlaybookDto,
  PlaybookVersionDto,
  RemediationAuditDto,
  RemediationMetaDto,
  RemediationRunDetailDto,
  RemediationRunDto,
  StartRemediationRunRequest,
} from '../lib/r4Types';
import { STALE } from './common';

export const remediationKeys = {
  all: ['remediation'] as const,
  meta: ['remediation', 'meta'] as const,
  systems: ['remediation', 'systems'] as const,
  playbooks: ['remediation', 'playbooks'] as const,
  runs: (includeSimulated: boolean) => ['remediation', 'runs', includeSimulated] as const,
  run: (id: number) => ['remediation', 'run', id] as const,
  audit: (runId?: number | null) => ['remediation', 'audit', runId ?? 'ALL'] as const,
  auditVerify: ['remediation', 'audit', 'verify'] as const,
};

export function useRemediationMeta() {
  return useQuery({
    queryKey: remediationKeys.meta,
    queryFn: () => getJson<RemediationMetaDto>('/remediation/meta'),
    staleTime: STALE.reference,
  });
}

export function useExecutionSystems() {
  return useQuery({
    queryKey: remediationKeys.systems,
    queryFn: () => getJson<ExecutionSystemDto[]>('/remediation/systems'),
  });
}

export function useCreateExecutionSystem() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateExecutionSystemRequest) => postJson<ExecutionSystemDto>('/remediation/systems', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: remediationKeys.systems });
    },
  });
}

export function useTestExecutionSystem() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<ExecutionSystemDto>(`/remediation/systems/${id}/test`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: remediationKeys.systems });
    },
  });
}

export function usePlaybooks() {
  return useQuery({
    queryKey: remediationKeys.playbooks,
    queryFn: () => getJson<PlaybookDto[]>('/remediation/playbooks'),
  });
}

export function useCreatePlaybook() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreatePlaybookRequest) => postJson<PlaybookDto>('/remediation/playbooks', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: remediationKeys.playbooks });
    },
  });
}

export function useSubmitPlaybookVersion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: number; version: number }) =>
      postJson<PlaybookVersionDto>(`/remediation/playbooks/${id}/versions/${version}/submit`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: remediationKeys.playbooks });
    },
  });
}

export function useApprovePlaybookVersion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: number; version: number }) =>
      postJson<PlaybookVersionDto>(`/remediation/playbooks/${id}/versions/${version}/approve`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: remediationKeys.playbooks });
    },
  });
}

export function useRemediationRuns(includeSimulated = false) {
  return useQuery({
    queryKey: remediationKeys.runs(includeSimulated),
    queryFn: () => getJson<RemediationRunDto[]>('/remediation/runs', { includeSimulated }),
  });
}

export function useRemediationRun(id: number | null) {
  return useQuery({
    queryKey: remediationKeys.run(id ?? 0),
    queryFn: () => getJson<RemediationRunDetailDto>(`/remediation/runs/${id}`),
    enabled: Boolean(id && id > 0),
  });
}

export function useStartRemediationRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: StartRemediationRunRequest) => postJson<RemediationRunDetailDto>('/remediation/runs', body),
    onSuccess: (detail) => {
      if (detail?.run?.id) {
        qc.setQueryData(remediationKeys.run(detail.run.id), detail);
      }
      void qc.invalidateQueries({ queryKey: remediationKeys.all });
    },
  });
}

export function useOverrideRemediationRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      postJson<RemediationRunDetailDto>(`/remediation/runs/${id}/override`, { reason }),
    onSuccess: (detail) => {
      if (detail?.run?.id) {
        qc.setQueryData(remediationKeys.run(detail.run.id), detail);
      }
      void qc.invalidateQueries({ queryKey: remediationKeys.all });
    },
  });
}

export function useCancelRemediationRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<RemediationRunDetailDto>(`/remediation/runs/${id}/cancel`),
    onSuccess: (detail) => {
      if (detail?.run?.id) {
        qc.setQueryData(remediationKeys.run(detail.run.id), detail);
      }
      void qc.invalidateQueries({ queryKey: remediationKeys.all });
    },
  });
}

export function useConfirmRemediationStep() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<RemediationRunDetailDto>(`/remediation/runs/${id}/confirm-step`),
    onSuccess: (detail) => {
      if (detail?.run?.id) {
        qc.setQueryData(remediationKeys.run(detail.run.id), detail);
      }
      void qc.invalidateQueries({ queryKey: remediationKeys.all });
    },
  });
}

export function useRemediationAudit(runId?: number | null) {
  return useQuery({
    queryKey: remediationKeys.audit(runId),
    queryFn: () => getJson<RemediationAuditDto[]>('/remediation/audit', runId ? { runId } : {}),
  });
}

export function useVerifyRemediationAudit() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => getJson<AuditVerifyResultDto>('/remediation/audit/verify'),
    onSuccess: (result) => {
      qc.setQueryData(remediationKeys.auditVerify, result);
    },
  });
}
