import type { QueryClient } from '@tanstack/react-query';
import type { PageResponse } from '../lib/types';

/** Some list endpoints may be paginated or plain arrays; accept both. */
export function asArray<T>(data: T[] | PageResponse<T> | null | undefined): T[] {
  if (!data) return [];
  return Array.isArray(data) ? data : data.content ?? [];
}

export function emptyPage<T>(): PageResponse<T> {
  return { content: [], page: 0, size: 0, totalElements: 0, totalPages: 0 };
}

export const STALE = {
  live: 10_000,
  list: 15_000,
  standard: 30_000,
  reference: 5 * 60_000,
} as const;

/** Everything an incident change can affect. */
export function invalidateIncidentData(queryClient: QueryClient): Promise<unknown> {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['incidents'] }),
    queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
    queryClient.invalidateQueries({ queryKey: ['sla'] }),
    queryClient.invalidateQueries({ queryKey: ['services'] }),
    queryClient.invalidateQueries({ queryKey: ['runbooks', 'executions'] }),
    queryClient.invalidateQueries({ queryKey: ['my-work'] }),
    queryClient.invalidateQueries({ queryKey: ['portal'] }),
    queryClient.invalidateQueries({ queryKey: ['teams'] }),
  ]);
}
