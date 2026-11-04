import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type { EmailStatusDto, OutboxItem, PageResponse } from '../lib/types';
import { STALE } from './common';

/** R3 e-mail outbox (admins read, SYSTEM_ADMIN sends a test). Credentials are never returned. */
export function useEmailStatus(enabled = true) {
  return useQuery({
    queryKey: ['admin', 'email', 'status'],
    queryFn: () => getJson<EmailStatusDto>('/admin/email/status'),
    staleTime: STALE.live,
    refetchInterval: 30_000,
    enabled,
  });
}

export function useEmailOutbox(params: { status?: string; page?: number; size?: number }, enabled = true) {
  return useQuery({
    queryKey: ['admin', 'email', 'outbox', params],
    queryFn: () => getJson<PageResponse<OutboxItem>>('/admin/email/outbox', { ...params, status: params.status || undefined }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useSendTestEmail() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (to: string) => postJson<{ id: number; status: string }>('/admin/email/test', { to }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'email'] }),
  });
}
