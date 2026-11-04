import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson, putJson } from '../lib/api';
import { ORG_SETTING_KEYS } from '../lib/constants';
import type {
  AiConfigDto,
  IncidentCategoryConfig,
  IncidentCategoryUpdate,
  ScenarioResult,
  SettingDto,
  SystemHealthDto,
} from '../lib/types';
import { asArray, invalidateIncidentData, STALE } from './common';

export function useSettings(enabled = true) {
  return useQuery({
    queryKey: ['admin', 'settings'],
    queryFn: () => getJson<SettingDto[]>('/admin/settings'),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useUpdateSetting() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ key, value }: { key: string; value: string }) =>
      putJson<SettingDto>(`/admin/settings/${encodeURIComponent(key)}`, { value }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'settings'] }),
  });
}

/** §13 organisation settings (`org.*` system settings) as a keyed object; SETTINGS write. */
export function useOrganisationSettings(enabled = true) {
  const settings = useSettings(enabled);
  const byKey = new Map((settings.data ?? []).map((setting) => [setting.key, setting.value]));
  return {
    ...settings,
    organisation: settings.data
      ? {
          name: byKey.get(ORG_SETTING_KEYS.name) ?? '',
          supportEmail: byKey.get(ORG_SETTING_KEYS.supportEmail) ?? '',
          timezone: byKey.get(ORG_SETTING_KEYS.timezone) ?? '',
          businessHoursNote: byKey.get(ORG_SETTING_KEYS.businessHoursNote) ?? '',
        }
      : undefined,
  };
}

/** §13 incident category configuration (READERS read). Codes are the fixed Category set. */
export function useIncidentCategories(enabled = true) {
  return useQuery({
    queryKey: ['admin', 'incident-categories'],
    queryFn: async () => asArray(await getJson<IncidentCategoryConfig[]>('/admin/incident-categories')),
    staleTime: STALE.reference,
    enabled,
  });
}

/** §13 CONFIG_ADMINS: replaces the configuration of the given categories. */
export function useUpdateIncidentCategories() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: IncidentCategoryUpdate[]) => putJson<IncidentCategoryConfig[]>('/admin/incident-categories', body),
    onSuccess: (data) => {
      if (Array.isArray(data)) queryClient.setQueryData(['admin', 'incident-categories'], data);
      return queryClient.invalidateQueries({ queryKey: ['admin', 'incident-categories'] });
    },
  });
}

/** §13 AI configuration (SETTINGS). Keys are never sent or returned. */
export function useAiConfig(enabled = true) {
  return useQuery({
    queryKey: ['admin', 'ai'],
    queryFn: () => getJson<AiConfigDto>('/admin/ai'),
    staleTime: STALE.standard,
    enabled,
  });
}

/** §13 only `llmEnabled` can be changed from the UI. */
export function useUpdateAiConfig() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { llmEnabled: boolean }) => putJson<AiConfigDto>('/admin/ai', body),
    onSuccess: (data) => {
      if (data && typeof data === 'object') queryClient.setQueryData(['admin', 'ai'], data);
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin', 'ai'] }),
        queryClient.invalidateQueries({ queryKey: ['ai', 'status'] }),
      ]);
    },
  });
}

/** §13 system health (HEALTH_READERS). */
export function useSystemHealth(enabled = true) {
  return useQuery({
    queryKey: ['admin', 'system-health'],
    queryFn: () => getJson<SystemHealthDto>('/admin/system-health'),
    staleTime: STALE.live,
    refetchInterval: 30_000,
    enabled,
  });
}

/** Runs the payroll-outage demo through the real engines (SETTINGS). */
export function useRunPayrollScenario() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => postJson<ScenarioResult>('/admin/demo/payroll-outage'),
    onSuccess: () => invalidateIncidentData(queryClient),
  });
}

/** Demo-only SLA clock shift; the backend audits it and labels events SIMULATED. */
export function useSimulateElapsed() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ incidentId, minutes }: { incidentId: number; minutes: number }) =>
      postJson<unknown>(`/admin/demo/incidents/${incidentId}/simulate-elapsed`, { minutes }),
    onSuccess: () => invalidateIncidentData(queryClient),
  });
}
