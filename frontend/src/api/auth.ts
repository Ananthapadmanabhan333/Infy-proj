import { useMutation, useQuery } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type { DemoAccountsResponse } from '../lib/types';
import { STALE } from './common';

export function useDemoAccounts() {
  return useQuery({
    queryKey: ['auth', 'demo-accounts'],
    queryFn: () => getJson<DemoAccountsResponse>('/auth/demo-accounts'),
    staleTime: STALE.reference,
    retry: false,
  });
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (body: { currentPassword: string; newPassword: string }) => postJson<void>('/auth/change-password', body),
  });
}
