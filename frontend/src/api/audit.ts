import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { getJson } from '../lib/api';
import type { AuditLogDto, PageResponse } from '../lib/types';
import { STALE } from './common';

export interface AuditParams {
  actor?: string;
  action?: string;
  entityType?: string;
  entityId?: string;
  outcome?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export function useAuditLogs(params: AuditParams) {
  return useQuery({
    queryKey: ['audit', 'list', params],
    queryFn: () => getJson<PageResponse<AuditLogDto>>('/audit-logs', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
  });
}

export function useAuditActions() {
  return useQuery({
    queryKey: ['audit', 'actions'],
    queryFn: () => getJson<string[]>('/audit-logs/actions'),
    staleTime: STALE.reference,
  });
}
