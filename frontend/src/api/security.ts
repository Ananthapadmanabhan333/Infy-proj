import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type {
  CreateSecurityIncidentRequest,
  SecurityCorrelationGroupDto,
  SecurityIncidentDetailDto,
  SecurityIncidentDto,
  SecurityOverviewDto,
  SecurityResponseTemplateDto,
  VulnerabilityFindingDto,
  VulnerabilityIngestRequest,
  VulnerabilityIngestResultDto,
  VulnerabilitySourceCreatedDto,
  VulnerabilitySourceDto,
} from '../lib/r4Types';
import { STALE } from './common';

export const securityKeys = {
  all: ['security'] as const,
  overview: (includeSimulated: boolean) => ['security', 'overview', includeSimulated] as const,
  incidents: (includeSimulated: boolean) => ['security', 'incidents', includeSimulated] as const,
  incident: (id: number) => ['security', 'incident', id] as const,
  sources: ['security', 'sources'] as const,
  findings: (includeSimulated: boolean) => ['security', 'findings', includeSimulated] as const,
  correlationGroups: (includeSimulated: boolean) => ['security', 'correlation-groups', includeSimulated] as const,
  templates: ['security', 'templates'] as const,
};

export function useSecurityOverview(includeSimulated = false) {
  return useQuery({
    queryKey: securityKeys.overview(includeSimulated),
    queryFn: () => getJson<SecurityOverviewDto>('/security/overview', { includeSimulated }),
  });
}

export function useSecurityIncidents(includeSimulated = false) {
  return useQuery({
    queryKey: securityKeys.incidents(includeSimulated),
    queryFn: () => getJson<SecurityIncidentDto[]>('/security/incidents', { includeSimulated }),
  });
}

export function useSecurityIncident(id: number | null) {
  return useQuery({
    queryKey: securityKeys.incident(id ?? 0),
    queryFn: () => getJson<SecurityIncidentDetailDto>(`/security/incidents/${id}`),
    enabled: Boolean(id && id > 0),
  });
}

export function useCreateSecurityIncident() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateSecurityIncidentRequest) => postJson<SecurityIncidentDetailDto>('/security/incidents', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: securityKeys.all });
    },
  });
}

export function useTransitionSecurityIncident() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, status, lessonsLearned }: { id: number; status: string; lessonsLearned?: string | null }) =>
      postJson<SecurityIncidentDetailDto>(`/security/incidents/${id}/transition`, { status, lessonsLearned }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: securityKeys.all });
    },
  });
}

export function useAddSecurityIncidentNote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      note,
      indicatorType,
      indicatorValue,
    }: {
      id: number;
      note: string;
      indicatorType?: string | null;
      indicatorValue?: string | null;
    }) => postJson<SecurityIncidentDetailDto>(`/security/incidents/${id}/notes`, { note, indicatorType, indicatorValue }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: securityKeys.all });
    },
  });
}

export function useCompleteSecurityTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ incidentId, taskId }: { incidentId: number; taskId: number }) =>
      postJson<SecurityIncidentDetailDto>(`/security/incidents/${incidentId}/tasks/${taskId}/complete`, {}),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: securityKeys.all });
    },
  });
}

export function useVulnerabilitySources() {
  return useQuery({
    queryKey: securityKeys.sources,
    queryFn: () => getJson<VulnerabilitySourceDto[]>('/security/vulnerabilities/sources'),
  });
}

export function useCreateVulnerabilitySource() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; format: string; scannerName: string }) =>
      postJson<VulnerabilitySourceCreatedDto>('/security/vulnerabilities/sources', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: securityKeys.sources });
    },
  });
}

export function useVulnerabilityFindings(includeSimulated = false) {
  return useQuery({
    queryKey: securityKeys.findings(includeSimulated),
    queryFn: () => getJson<VulnerabilityFindingDto[]>('/security/vulnerabilities/findings', { includeSimulated }),
  });
}

export function useIngestVulnerabilities() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: VulnerabilityIngestRequest) =>
      postJson<VulnerabilityIngestResultDto>('/security/vulnerabilities/ingest', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: securityKeys.all });
    },
  });
}

export function useAcceptVulnerabilityRisk() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason, days }: { id: number; reason: string; days: number }) =>
      postJson<VulnerabilityFindingDto>(`/security/vulnerabilities/findings/${id}/accept-risk`, { reason, days }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: securityKeys.all });
    },
  });
}

export function useSecurityCorrelationGroups(includeSimulated = false) {
  return useQuery({
    queryKey: securityKeys.correlationGroups(includeSimulated),
    queryFn: () => getJson<SecurityCorrelationGroupDto[]>('/security/correlation-groups', { includeSimulated }),
  });
}

export function useRunSecurityCorrelation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => postJson<SecurityCorrelationGroupDto[]>('/security/correlation-groups/correlate'),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: securityKeys.all });
    },
  });
}

export function useRaiseIncidentFromSecurityGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (groupId: number) =>
      postJson<SecurityIncidentDetailDto>(`/security/correlation-groups/${groupId}/raise-incident`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: securityKeys.all });
    },
  });
}

export function useSecurityResponseTemplates() {
  return useQuery({
    queryKey: securityKeys.templates,
    queryFn: () => getJson<SecurityResponseTemplateDto[]>('/security/templates'),
    staleTime: STALE.reference,
  });
}
