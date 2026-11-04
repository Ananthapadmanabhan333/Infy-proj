import { assignableRoles, USER_ADMINS } from '../../lib/constants';
import type { CurrentUser, Role, UserDto } from '../../lib/types';

type Actor = Pick<CurrentUser, 'id' | 'role'> | null | undefined;

/**
 * Whether the signed-in user may edit, reset or (de)activate `target`.
 * USER_ADMINS manage users, but IT_ADMIN cannot edit SYSTEM_ADMIN accounts (spec §1). The API enforces the same rule.
 */
export function canManageUser(actor: Actor, target: Pick<UserDto, 'role'>): boolean {
  if (!actor || !USER_ADMINS.includes(actor.role)) return false;
  return actor.role === 'SYSTEM_ADMIN' || target.role !== 'SYSTEM_ADMIN';
}

/** Why a row is read-only for the current user (null when it can be managed). */
export function readOnlyReason(actor: Actor, target: Pick<UserDto, 'role'>): string | null {
  if (!actor || !USER_ADMINS.includes(actor.role)) return 'Your role has read-only access to the user directory.';
  if (!canManageUser(actor, target)) return 'Only a System Admin can change System Admin accounts.';
  return null;
}

/** Roles the actor may grant; the target's current role is kept so an edit never silently changes it. */
export function roleOptions(actor: Actor, currentRole?: Role): Role[] {
  const roles = assignableRoles(actor?.role);
  if (currentRole && !roles.includes(currentRole)) return [currentRole, ...roles];
  return roles;
}

export function isSelf(actor: Actor, target: Pick<UserDto, 'id'>): boolean {
  return Boolean(actor && actor.id === target.id);
}
