import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson, putJson } from '../lib/api';
import type { NotificationDto, NotificationPreference, NotificationPreferencesUpdate, PageResponse } from '../lib/types';
import { STALE } from './common';

export const notificationKeys = {
  all: ['notifications'] as const,
  unread: ['notifications', 'unread-count'] as const,
  list: (params: object) => ['notifications', 'list', params] as const,
  preferences: ['notifications', 'preferences'] as const,
};

/**
 * Polling interval for the unread badge. `useNotificationStream` passes `false`
 * while the SSE stream is connected and 30 s when it falls back to polling.
 */
export function useUnreadCount(refetchInterval: number | false = 30_000) {
  return useQuery({
    queryKey: notificationKeys.unread,
    queryFn: () => getJson<{ count: number }>('/notifications/unread-count'),
    select: (data) => data.count,
    refetchInterval,
    staleTime: STALE.live,
  });
}

export function useNotifications(params: { unreadOnly?: boolean; page?: number; size?: number }, enabled = true) {
  return useQuery({
    queryKey: notificationKeys.list(params),
    queryFn: () =>
      getJson<PageResponse<NotificationDto>>('/notifications', {
        unreadOnly: params.unreadOnly || undefined,
        page: params.page,
        size: params.size,
      }),
    placeholderData: keepPreviousData,
    staleTime: STALE.live,
    enabled,
  });
}

export function useMarkNotificationRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => postJson<void>(`/notifications/${id}/read`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  });
}

export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => postJson<void>('/notifications/read-all'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  });
}

/** §12 per-type in-app preferences of the current user. */
export function useNotificationPreferences(enabled = true) {
  return useQuery({
    queryKey: notificationKeys.preferences,
    queryFn: () => getJson<NotificationPreference[]>('/notifications/preferences'),
    staleTime: STALE.standard,
    enabled,
  });
}

/** §12 PUT `{ [type]: enabled }`; disabled types are no longer created for the user. */
export function useUpdateNotificationPreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: NotificationPreferencesUpdate) => putJson<NotificationPreference[] | null>('/notifications/preferences', body),
    onSuccess: (data) => {
      if (Array.isArray(data)) queryClient.setQueryData(notificationKeys.preferences, data);
      return queryClient.invalidateQueries({ queryKey: notificationKeys.preferences });
    },
  });
}
