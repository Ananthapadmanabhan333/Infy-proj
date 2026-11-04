import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson } from '../lib/api';
import type { SessionDto } from '../lib/types';
import { STALE } from './common';

/** R3 session management: own sessions (any user) and a user's sessions (admins). */
export function useMySessions(enabled = true) {
  return useQuery({
    queryKey: ['sessions', 'mine'],
    queryFn: () => getJson<SessionDto[]>('/auth/sessions'),
    staleTime: STALE.live,
    enabled,
  });
}

export function useRevokeSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: SessionDto['id']) => deleteJson(`/auth/sessions/${encodeURIComponent(String(id))}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sessions', 'mine'] }),
  });
}

export function useRevokeOtherSessions() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => postJson<{ revoked: number }>('/auth/sessions/revoke-others'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sessions', 'mine'] }),
  });
}

export function useUserSessions(userId: number, enabled = true) {
  return useQuery({
    queryKey: ['sessions', 'user', userId],
    queryFn: () => getJson<SessionDto[]>(`/users/${userId}/sessions`),
    staleTime: STALE.live,
    enabled,
  });
}

export function useRevokeUserSessions(userId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => postJson<{ revoked: number }>(`/users/${userId}/sessions/revoke`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sessions', 'user', userId] }),
  });
}
