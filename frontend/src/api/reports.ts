import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteJson, downloadFile, getJson, postJson, putJson } from '../lib/api';
import type { ReportRunDto, ReportScheduleDto, ReportScheduleRequest, ReportTypeDto } from '../lib/types';
import { STALE } from './common';

/** R3 scheduled reports: any user lists (exporters see all, others their own), report schedulers create. */
export function useReportTypes(enabled = true) {
  return useQuery({
    queryKey: ['reports', 'types'],
    queryFn: () => getJson<ReportTypeDto[]>('/reports/types'),
    staleTime: STALE.reference,
    enabled,
  });
}

export function useReportSchedules(enabled = true) {
  return useQuery({
    queryKey: ['reports', 'schedules'],
    queryFn: () => getJson<ReportScheduleDto[]>('/reports/schedules'),
    staleTime: STALE.list,
    enabled,
  });
}

export function useScheduleRuns(scheduleId: number | null, size = 20) {
  return useQuery({
    queryKey: ['reports', 'runs', scheduleId, size],
    queryFn: () => getJson<ReportRunDto[]>(`/reports/schedules/${scheduleId}/runs`, { size }),
    staleTime: STALE.live,
    enabled: scheduleId !== null,
  });
}

export function useSaveSchedule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: ReportScheduleRequest }) =>
      id ? putJson<ReportScheduleDto>(`/reports/schedules/${id}`, body) : postJson<ReportScheduleDto>('/reports/schedules', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['reports'] }),
  });
}

export function useDeleteSchedule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/reports/schedules/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['reports'] }),
  });
}

/** Runs synchronously for the period ending yesterday; a generator failure resolves with `status: FAILED`. */
export function useRunSchedule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<ReportRunDto>(`/reports/schedules/${id}/run`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['reports'] }),
  });
}

export function downloadReportRun(run: Pick<ReportRunDto, 'id' | 'fileName'>): Promise<void> {
  return downloadFile(`/reports/runs/${run.id}/download`, run.fileName ?? `report-${run.id}`);
}
