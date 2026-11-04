import { STAFF, TEAM_ADMINS, USER_ADMINS } from '../../lib/constants';
import type { CurrentUser, DepartmentDto, Role } from '../../lib/types';

type Actor = Pick<CurrentUser, 'id' | 'role' | 'departmentId'> | null | undefined;

/**
 * Team admins (docs/API.md "Teams"): user administrators maintain every team; IT managers only teams of departments
 * they manage or belong to. The backend enforces the same rule (403); the UI only hides what would fail.
 */
export function canManageDepartmentTeams(actor: Actor, departmentId: number | null | undefined, departments: DepartmentDto[] | undefined): boolean {
  if (!actor || !TEAM_ADMINS.includes(actor.role)) return false;
  if (USER_ADMINS.includes(actor.role)) return true;
  if (departmentId === null || departmentId === undefined) return false;
  if (actor.departmentId === departmentId) return true;
  return Boolean(departments?.some((department) => department.id === departmentId && department.managerId === actor.id));
}

/** Departments in which the actor may create teams. */
export function manageableDepartments(actor: Actor, departments: DepartmentDto[] | undefined): DepartmentDto[] {
  return (departments ?? []).filter((department) => canManageDepartmentTeams(actor, department.id, departments));
}

/** Team members must be active staff. */
export function isStaffRole(role: Role): boolean {
  return STAFF.includes(role);
}
