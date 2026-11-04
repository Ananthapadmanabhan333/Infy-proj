import { z } from 'zod';
import { ROLES } from './constants';
import { passwordPolicyError } from './passwordPolicy';
import { emailSchema } from './schemas';
import type { Role } from './types';

// Mirrors the Bean Validation constraints of the Release 2.1 account-access endpoints
// (docs/PUBLIC_ACCESS_SPEC.md §4–§5). Kept apart from the shared schemas.ts.

const roleEnum = z.enum(ROLES as [Role, ...Role[]]);
const fullName = z.string().trim().min(2, 'Enter your full name (at least 2 characters)').max(120, 'Full name must be at most 120 characters');
const optionalText = (label: string, max: number) => z.string().trim().max(max, `${label} must be at most ${max} characters`);
const requiredDepartment = z.string().min(1, 'Choose a department');

export const accessRequestSchema = z.object({
  fullName,
  email: emailSchema,
  department: optionalText('Department', 120),
  jobTitle: optionalText('Job title', 120),
  reason: z
    .string()
    .trim()
    .min(10, 'Tell the administrator why you need access (at least 10 characters)')
    .max(1000, 'Reason must be at most 1000 characters'),
});
export type AccessRequestValues = z.infer<typeof accessRequestSchema>;

export const forgotPasswordSchema = z.object({ email: emailSchema });
export type ForgotPasswordValues = z.infer<typeof forgotPasswordSchema>;

/** New password + confirmation, checked against the account e-mail (the policy forbids the e-mail name). */
function passwordWithPolicy(email: string | null) {
  return z.string().superRefine((value, ctx) => {
    if (!value) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Choose a password' });
      return;
    }
    const problem = passwordPolicyError(value, email);
    if (problem) ctx.addIssue({ code: z.ZodIssueCode.custom, message: problem });
  });
}

export function registerSchema(email: string | null) {
  return z
    .object({
      fullName,
      password: passwordWithPolicy(email),
      confirmPassword: z.string().min(1, 'Confirm your password'),
    })
    .refine((values) => values.password === values.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' });
}
export type RegisterValues = z.infer<ReturnType<typeof registerSchema>>;

export function completeResetSchema(email: string | null) {
  return z
    .object({
      newPassword: passwordWithPolicy(email),
      confirmPassword: z.string().min(1, 'Confirm your new password'),
    })
    .refine((values) => values.newPassword === values.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' });
}
export type CompleteResetValues = z.infer<ReturnType<typeof completeResetSchema>>;

// ------------------------------------------------------------ administration
export const approveAccessSchema = z.object({
  role: roleEnum,
  departmentId: requiredDepartment,
  jobTitle: optionalText('Job title', 120),
});
export type ApproveAccessValues = z.infer<typeof approveAccessSchema>;

export const rejectRequestSchema = z.object({
  note: z.string().trim().min(3, 'Add a note explaining the decision (at least 3 characters)').max(500, 'Notes are limited to 500 characters'),
});
export type RejectRequestValues = z.infer<typeof rejectRequestSchema>;

export const inviteUserSchema = z.object({
  email: emailSchema,
  fullName: z
    .string()
    .trim()
    .max(120, 'Full name must be at most 120 characters')
    .refine((value) => value.length === 0 || value.length >= 2, 'Full name must be at least 2 characters'),
  role: roleEnum,
  departmentId: requiredDepartment,
  jobTitle: optionalText('Job title', 120),
});
export type InviteUserValues = z.infer<typeof inviteUserSchema>;
