import { QueryClient } from '@tanstack/react-query';
import { toApiError } from './api';

const NO_RETRY_STATUSES = new Set([400, 401, 403, 404, 409, 413, 415, 422, 423, 429]);

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          if (NO_RETRY_STATUSES.has(toApiError(error).status)) return false;
          return failureCount < 2;
        },
      },
      mutations: {
        retry: false,
      },
    },
  });
}
