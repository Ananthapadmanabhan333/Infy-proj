import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson } from '../lib/api';
import type {
  AccessRequestBody,
  AccountRequestCounts,
  AccountRequestDto,
  AccountRequestListParams,
  ApproveAccountRequestBody,
  CompletePasswordResetBody,
  CreateInvitationBody,
  ForgotPasswordBody,
  InvitationDetails,
  IssuedLinkResponse,
  PublicInfo,
  PublicMessageResponse,
  RegisterBody,
  RegisterResponse,
  RejectAccountRequestBody,
  ResetTokenDetails,
} from '../lib/accountTypes';
import type { PageResponse } from '../lib/types';
import { STALE } from './common';

// Hooks for the Release 2.1 account-access endpoints (docs/PUBLIC_ACCESS_SPEC.md §4–§5).
// Public calls never attempt a silent token refresh: they run without a session.
const PUBLIC = { skipAuthRefresh: true } as const;

/** Tokens travel as a single path segment. */
const tokenPath = (token: string) => encodeURIComponent(token);

// ------------------------------------------------------------------ public (§4)
export function usePublicInfo() {
  return useQuery({
    queryKey: ['public', 'info'],
    queryFn: () => getJson<PublicInfo>('/public/info', undefined, PUBLIC),
    staleTime: STALE.reference,
    retry: false,
  });
}

export function useRequestAccess() {
  return useMutation({
    mutationFn: (body: AccessRequestBody) => postJson<PublicMessageResponse>('/auth/access-requests', body, PUBLIC),
  });
}

export function useForgotPassword() {
  return useMutation({
    mutationFn: (body: ForgotPasswordBody) => postJson<PublicMessageResponse>('/auth/forgot-password', body, PUBLIC),
  });
}

/** Validates an invitation token. The token is kept only in memory (React Query cache, never persisted). */
export function useInvitation(token: string | null) {
  return useQuery({
    queryKey: ['auth', 'invitation', token],
    queryFn: () => getJson<InvitationDetails>(`/auth/invitations/${tokenPath(token ?? '')}`, undefined, PUBLIC),
    enabled: Boolean(token),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  });
}

export function useRegister() {
  return useMutation({
    mutationFn: (body: RegisterBody) => postJson<RegisterResponse>('/auth/register', body, PUBLIC),
  });
}

export function useResetToken(token: string | null) {
  return useQuery({
    queryKey: ['auth', 'reset-token', token],
    queryFn: () => getJson<ResetTokenDetails>(`/auth/reset-password/${tokenPath(token ?? '')}`, undefined, PUBLIC),
    enabled: Boolean(token),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  });
}

export function useCompletePasswordReset() {
  return useMutation({
    mutationFn: (body: CompletePasswordResetBody) => postJson<void>('/auth/reset-password', body, PUBLIC),
  });
}

// ---------------------------------------------------------- administration (§5)
const REQUESTS_KEY = ['account-requests'] as const;

export function useAccountRequests(params: AccountRequestListParams, enabled = true) {
  return useQuery({
    queryKey: [...REQUESTS_KEY, 'list', params],
    queryFn: () => getJson<PageResponse<AccountRequestDto>>('/admin/account-requests', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useAccountRequestCounts(enabled = true) {
  return useQuery({
    queryKey: [...REQUESTS_KEY, 'counts'],
    queryFn: () => getJson<AccountRequestCounts>('/admin/account-requests/counts'),
    staleTime: STALE.list,
    enabled,
  });
}

/** Returns the single-use link once; callers must not cache or log it. */
export function useApproveAccountRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: ApproveAccountRequestBody }) =>
      postJson<IssuedLinkResponse>(`/admin/account-requests/${id}/approve`, body),
    // The response carries the raw single-use link: drop it from the mutation cache as soon as possible.
    gcTime: 0,
    onSettled: () => queryClient.invalidateQueries({ queryKey: REQUESTS_KEY }),
  });
}

export function useRejectAccountRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: RejectAccountRequestBody }) =>
      postJson<AccountRequestDto>(`/admin/account-requests/${id}/reject`, body),
    onSettled: () => queryClient.invalidateQueries({ queryKey: REQUESTS_KEY }),
  });
}

export function useCreateInvitation() {
  return useMutation({
    mutationFn: (body: CreateInvitationBody) => postJson<IssuedLinkResponse>('/admin/invitations', body),
    gcTime: 0,
  });
}
