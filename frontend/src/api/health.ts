import { useQuery } from '@tanstack/react-query';
import { getJson } from '../lib/api';

/** Live API reachability for the sidebar system-status card. */
export function useHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: () => getJson<Record<string, unknown>>('/health', undefined, { skipAuthRefresh: true, timeout: 8000 }),
    refetchInterval: 30_000,
    retry: false,
    staleTime: 15_000,
  });
}
