import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type { CalendarDto, IncidentSummary, PageResponse, Priority, SlaOverview, SlaPolicyDto } from '../lib/types';
import { STALE } from './common';

export interface SlaPolicyBody {
  name: string;
  priority: Priority;
  responseMinutes: number;
  resolutionMinutes: number;
  businessHoursOnly: boolean;
  calendarId: number | null;
  atRiskPercent: number;
  active: boolean;
  description: string | null;
}

export interface CalendarBody {
  name: string;
  timezone: string;
  workDays: CalendarDto['workDays'];
  startTime: string;
  endTime: string;
}

/** Response of POST /sla/evaluate is not part of the typed contract: render any numeric counters. */
export type SlaEvaluationResult = Record<string, unknown>;

export function useSlaOverview(days: number) {
  return useQuery({
    queryKey: ['sla', 'overview', days],
    queryFn: () => getJson<SlaOverview>('/sla/overview', { days }),
    placeholderData: keepPreviousData,
    staleTime: STALE.standard,
    refetchInterval: 60_000,
  });
}

export function useSlaActive(params: { health?: string; page?: number; size?: number }) {
  return useQuery({
    queryKey: ['sla', 'active', params],
    queryFn: () => getJson<PageResponse<IncidentSummary>>('/sla/active', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    refetchInterval: 60_000,
  });
}

export function useSlaPolicies(enabled = true) {
  return useQuery({
    queryKey: ['sla', 'policies'],
    queryFn: () => getJson<SlaPolicyDto[]>('/sla/policies'),
    staleTime: STALE.reference,
    enabled,
  });
}

export function useCalendars(enabled = true) {
  return useQuery({
    queryKey: ['sla', 'calendars'],
    queryFn: () => getJson<CalendarDto[]>('/sla/calendars'),
    staleTime: STALE.reference,
    enabled,
  });
}

export function useSaveSlaPolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: SlaPolicyBody }) =>
      id ? putJson<SlaPolicyDto>(`/sla/policies/${id}`, body) : postJson<SlaPolicyDto>('/sla/policies', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sla'] }),
  });
}

export function useUpdateCalendar() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: CalendarBody }) => putJson<CalendarDto>(`/sla/calendars/${id}`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sla', 'calendars'] }),
  });
}

export function useAddHoliday() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ calendarId, date, name }: { calendarId: number; date: string; name: string }) =>
      postJson<unknown>(`/sla/calendars/${calendarId}/holidays`, { date, name }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sla', 'calendars'] }),
  });
}

export function useRemoveHoliday() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ calendarId, holidayId }: { calendarId: number; holidayId: number }) =>
      deleteJson(`/sla/calendars/${calendarId}/holidays/${holidayId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sla', 'calendars'] }),
  });
}

export function useEvaluateSla() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => postJson<SlaEvaluationResult | null>('/sla/evaluate'),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['sla'] }),
        queryClient.invalidateQueries({ queryKey: ['incidents'] }),
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      ]),
  });
}
