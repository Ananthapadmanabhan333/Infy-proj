import { useQuery } from '@tanstack/react-query';
import { getJson } from '../lib/api';

/** `GET /api/public/info` (no auth) — spec PUBLIC_ACCESS §4. */
export interface PublicInfo {
  organisationName: string;
  supportEmail: string | null;
  version: string;
}

/** Organisation and support contact for the public pages. Absent (404) on older API versions — callers show a fallback. */
export function usePublicInfo() {
  return useQuery({
    queryKey: ['public-info'],
    queryFn: () => getJson<PublicInfo>('/public/info', undefined, { skipAuthRefresh: true, timeout: 8000 }),
    retry: false,
    staleTime: 5 * 60_000,
  });
}
