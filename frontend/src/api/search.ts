import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { getJson } from '../lib/api';
import type { SearchResults } from '../lib/types';
import { STALE } from './common';

export function useGlobalSearch(q: string) {
  const term = q.trim();
  return useQuery({
    queryKey: ['search', term],
    queryFn: () => getJson<SearchResults>('/search', { q: term }),
    enabled: term.length >= 2,
    staleTime: STALE.standard,
    placeholderData: keepPreviousData,
  });
}
