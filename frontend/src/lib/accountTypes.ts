// Contract of the Release 2.1 account-access endpoints (docs/PUBLIC_ACCESS_SPEC.md §4–§5).
// Kept apart from the shared `types.ts` on purpose.
import type { Role } from './types';

// ---------------------------------------------------------------- public (§4)
/** 202 body of POST /auth/access-requests and /auth/forgot-password (deliberately generic). */
export interface PublicMessageResponse {
  message: string;
}

export interface AccessRequestBody {
  fullName: string;
  email: string;
  department: string | null;
  jobTitle: string | null;
  reason: string;
}

export interface ForgotPasswordBody {
  email: string;
}

/** GET /auth/invitations/{token}. */
export interface InvitationDetails {
  email: string;
  fullName: string | null;
  role: Role;
  departmentName: string | null;
  organisationName: string | null;
  expiresAt: string;
}

export interface RegisterBody {
  token: string;
  fullName: string;
  password: string;
}

/** 201 body of POST /auth/register. */
export interface RegisterResponse {
  email: string;
}

/** GET /auth/reset-password/{token}. */
export interface ResetTokenDetails {
  email: string;
  expiresAt: string;
}

export interface CompletePasswordResetBody {
  token: string;
  newPassword: string;
}

/** GET /public/info (no auth). */
export interface PublicInfo {
  organisationName: string;
  supportEmail: string | null;
  version: string;
}

// ------------------------------------------------------------ administration (§5)
export type AccountRequestType = 'ACCESS' | 'PASSWORD_RESET';
export type AccountRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'COMPLETED';

export const ACCOUNT_REQUEST_TYPES: AccountRequestType[] = ['ACCESS', 'PASSWORD_RESET'];
export const ACCOUNT_REQUEST_STATUSES: AccountRequestStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'COMPLETED'];

export const ACCOUNT_REQUEST_TYPE_LABELS: Record<AccountRequestType, string> = {
  ACCESS: 'Access',
  PASSWORD_RESET: 'Password reset',
};

export const ACCOUNT_REQUEST_STATUS_LABELS: Record<AccountRequestStatus, string> = {
  PENDING: 'Pending',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  COMPLETED: 'Completed',
};

export interface AccountRequestDto {
  id: number;
  type: AccountRequestType;
  fullName: string;
  email: string;
  department: string | null;
  jobTitle: string | null;
  reason: string | null;
  status: AccountRequestStatus;
  createdAt: string;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  userId: number | null;
}

export interface AccountRequestCounts {
  pendingAccess: number;
  pendingReset: number;
}

export interface AccountRequestListParams {
  type?: string;
  status?: string;
  page?: number;
  size?: number;
}

/** Body of POST /admin/account-requests/{id}/approve for an ACCESS request (PASSWORD_RESET sends `{}`). */
export interface ApproveAccessBody {
  role: Role;
  departmentId: number;
  jobTitle: string | null;
}

export type ApproveAccountRequestBody = ApproveAccessBody | Record<string, never>;

export interface RejectAccountRequestBody {
  note: string;
}

export interface CreateInvitationBody {
  email: string;
  fullName: string | null;
  role: Role;
  departmentId: number;
  jobTitle: string | null;
}

/**
 * Single-use link returned exactly once by approve / invite. `link` is a path
 * ("/register?invite=…" or "/reset-password?token=…"); the UI prefixes the origin.
 */
export interface IssuedLinkResponse {
  link: string;
  expiresAt: string;
}
