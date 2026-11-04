import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type { AnnouncementDto, AnnouncementRequest } from '../lib/types';
import { STALE } from './common';

export function useAnnouncements(activeOnly: boolean) {
  return useQuery({
    queryKey: ['announcements', { activeOnly }],
    queryFn: () => getJson<AnnouncementDto[]>('/announcements', { activeOnly: activeOnly || undefined }),
    staleTime: STALE.standard,
  });
}

export function useSaveAnnouncement() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: AnnouncementRequest }) =>
      id ? putJson<AnnouncementDto>(`/announcements/${id}`, body) : postJson<AnnouncementDto>('/announcements', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['announcements'] }),
  });
}

export function useDeleteAnnouncement() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => deleteJson(`/announcements/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['announcements'] }),
  });
}
