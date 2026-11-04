import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type {
  HandoverDto,
  HandoverRequest,
  MyShift,
  NowEntry,
  OverrideDto,
  OverrideRequest,
  PageDto,
  PolicyDto,
  PolicyRequest,
  ScheduleDetail,
  ScheduleRequest,
  ScheduleSummary,
  ShiftsResponse,
  UnavailabilityDto,
  UnavailabilityRequest,
} from '../lib/responseTypes';
import { STALE } from './common';

/** On-call (docs/API.md "Release 3 — On-call"): readers read; staff offer/take/record availability; on-call editors configure. */
export const oncallKeys = {
  all: ['oncall'] as const,
  now: (teamId?: number | null) => ['oncall', 'now', teamId ?? 'all'] as const,
  myShifts: (days: number) => ['oncall', 'my-shifts', days] as const,
  schedules: (params: object) => ['oncall', 'schedules', params] as const,
  schedule: (id: number) => ['oncall', 'schedule', id] as const,
  shifts: (id: number, from: string, to: string) => ['oncall', 'schedule', id, 'shifts', from, to] as const,
  overrides: (id: number, includePast: boolean) => ['oncall', 'schedule', id, 'overrides', includePast] as const,
  handovers: (id: number) => ['oncall', 'schedule', id, 'handovers'] as const,
  offers: ['oncall', 'offers'] as const,
  availability: (params: object) => ['oncall', 'availability', params] as const,
  policies: ['oncall', 'policies'] as const,
  pages: (params: object) => ['oncall', 'pages', params] as const,
};

/** Every on-call change can move who is on call now, the shifts, My Work and the Command Center. */
function invalidateOnCall(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: oncallKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['my-work'] }),
    queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
  ]);
}

export function useOnCallNow(teamId?: number | null, enabled = true) {
  return useQuery({
    queryKey: oncallKeys.now(teamId),
    queryFn: () => getJson<NowEntry[]>('/oncall/now', { teamId: teamId ?? undefined }),
    staleTime: STALE.live,
    refetchInterval: 60_000,
    enabled,
  });
}

export function useMyShifts(days = 14, enabled = true) {
  return useQuery({
    queryKey: oncallKeys.myShifts(days),
    queryFn: () => getJson<MyShift[]>('/oncall/my-shifts', { days }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useSchedules(params: { teamId?: number; active?: boolean } = {}, enabled = true) {
  return useQuery({
    queryKey: oncallKeys.schedules(params),
    queryFn: () => getJson<ScheduleSummary[]>('/oncall/schedules', { ...params }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useSchedule(id: number | null) {
  return useQuery({
    queryKey: oncallKeys.schedule(id ?? 0),
    queryFn: () => getJson<ScheduleDetail>(`/oncall/schedules/${id}`),
    staleTime: STALE.standard,
    enabled: Boolean(id),
  });
}

export function useScheduleShifts(id: number | null, from: string, to: string) {
  return useQuery({
    queryKey: oncallKeys.shifts(id ?? 0, from, to),
    queryFn: () => getJson<ShiftsResponse>(`/oncall/schedules/${id}/shifts`, { from, to }),
    staleTime: STALE.standard,
    enabled: Boolean(id) && Boolean(from) && Boolean(to),
  });
}

export function useSaveSchedule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: ScheduleRequest }) =>
      id ? putJson<ScheduleDetail>(`/oncall/schedules/${id}`, body) : postJson<ScheduleDetail>('/oncall/schedules', body),
    onSuccess: (detail) => {
      queryClient.setQueryData(oncallKeys.schedule(detail.schedule.id), detail);
      return invalidateOnCall(queryClient);
    },
  });
}

export function useScheduleOverrides(id: number | null, includePast = false) {
  return useQuery({
    queryKey: oncallKeys.overrides(id ?? 0, includePast),
    queryFn: () => getJson<OverrideDto[]>(`/oncall/schedules/${id}/overrides`, { includePast: includePast || undefined }),
    staleTime: STALE.standard,
    enabled: Boolean(id),
  });
}

export function useCreateOverride(scheduleId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: OverrideRequest) => postJson<OverrideDto>(`/oncall/schedules/${scheduleId}/overrides`, body),
    onSuccess: () => invalidateOnCall(queryClient),
  });
}

export function useOverrideOffers(enabled = true) {
  return useQuery({
    queryKey: oncallKeys.offers,
    queryFn: () => getJson<OverrideDto[]>('/oncall/overrides/offers'),
    staleTime: STALE.live,
    refetchInterval: 60_000,
    enabled,
  });
}

export function useOverrideDecision() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action, note }: { id: number; action: 'take' | 'decline' | 'cancel'; note?: string | null }) =>
      action === 'cancel'
        ? deleteJson<OverrideDto>(`/oncall/overrides/${id}`)
        : postJson<OverrideDto>(`/oncall/overrides/${id}/${action}`, action === 'decline' ? { note: note || null } : undefined),
    onSuccess: () => invalidateOnCall(queryClient),
  });
}

export function useAvailability(params: { userId?: number; all?: boolean } = {}, enabled = true) {
  return useQuery({
    queryKey: oncallKeys.availability(params),
    queryFn: () => getJson<UnavailabilityDto[]>('/oncall/availability', { ...params }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useAddUnavailability() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UnavailabilityRequest) => postJson<UnavailabilityDto>('/oncall/availability', body),
    onSuccess: () => invalidateOnCall(queryClient),
  });
}

export function useRemoveUnavailability() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/oncall/availability/${id}`),
    onSuccess: () => invalidateOnCall(queryClient),
  });
}

export function useHandovers(scheduleId: number | null, size = 20) {
  return useQuery({
    queryKey: oncallKeys.handovers(scheduleId ?? 0),
    queryFn: () => getJson<HandoverDto[]>(`/oncall/schedules/${scheduleId}/handovers`, { size }),
    staleTime: STALE.standard,
    enabled: Boolean(scheduleId),
  });
}

export function useAddHandover(scheduleId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: HandoverRequest) => postJson<HandoverDto>(`/oncall/schedules/${scheduleId}/handovers`, body),
    onSuccess: () => invalidateOnCall(queryClient),
  });
}

export function useEscalationPolicies(enabled = true) {
  return useQuery({
    queryKey: oncallKeys.policies,
    queryFn: () => getJson<PolicyDto[]>('/oncall/escalation-policies'),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useSavePolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: PolicyRequest }) =>
      id ? putJson<PolicyDto>(`/oncall/escalation-policies/${id}`, body) : postJson<PolicyDto>('/oncall/escalation-policies', body),
    onSuccess: () => invalidateOnCall(queryClient),
  });
}

export function usePages(params: { scheduleId?: number; alertId?: number; size?: number } = {}, enabled = true) {
  return useQuery({
    queryKey: oncallKeys.pages(params),
    queryFn: () => getJson<PageDto[]>('/oncall/pages', { ...params }),
    staleTime: STALE.live,
    refetchInterval: 60_000,
    enabled,
  });
}
