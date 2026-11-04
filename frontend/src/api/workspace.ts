import { useQuery } from '@tanstack/react-query';
import { getJson } from '../lib/api';
import type { MyWorkDto, PortalOverview } from '../lib/types';
import { STALE } from './common';

/** R3 `GET /my-work?limit` (staff): sections of work for the caller and their teams. */
export function useMyWork(limit = 10, enabled = true) {
  return useQuery({
    queryKey: ['my-work', limit],
    queryFn: () => getJson<MyWorkDto>('/my-work', { limit }),
    staleTime: STALE.live,
    refetchInterval: 60_000,
    enabled,
  });
}

/** R3 `GET /portal/overview` (any user): only the caller's own records plus organisation-wide public information. */
export function usePortalOverview(enabled = true) {
  return useQuery({
    queryKey: ['portal', 'overview'],
    queryFn: () => getJson<PortalOverview>('/portal/overview'),
    staleTime: STALE.standard,
    refetchInterval: 60_000,
    enabled,
  });
}
