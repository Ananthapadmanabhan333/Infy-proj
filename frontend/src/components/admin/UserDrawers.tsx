import { useId, useState, type ReactNode } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, KeyRound, Lock, Pencil, UserCheck, UserX } from 'lucide-react';
import { useCreateUser, useDepartments, useResetPassword, useUpdateUser, type UpdateUserBody } from '../../api/users';
import { useAuth } from '../../hooks/useAuth';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { ADMINS, ROLE_DESCRIPTIONS, roleLabel } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import {
  resetPasswordSchema,
  userCreateSchema,
  userUpdateSchema,
  type ResetPasswordValues,
  type UserCreateValues,
  type UserUpdateValues,
} from '../../lib/schemas';
import type { Role, UserDto } from '../../lib/types';
import { Avatar } from '../Avatar';
import { BooleanBadge, DemoBadge, RoleBadge } from '../Badge';
import { Drawer } from '../Drawer';
import { CheckboxField, idOrNull, SelectField, TextField, textOrNull } from '../FormField';
import { Modal } from '../Modal';
import { UserSessionsSection } from '../profile/SessionsPanel';
import { useToast } from '../Toast';
import { isSelf, readOnlyReason, roleOptions } from './userAccess';

export function DepartmentOptions({ emptyLabel = 'No department' }: { emptyLabel?: string }) {
  const departments = useDepartments();
  return (
    <>
      <option value="">{emptyLabel}</option>
      {departments.data?.map((department) => (
        <option key={department.id} value={department.id}>
          {department.name}
        </option>
      ))}
    </>
  );
}

export function RoleOptions({ roles }: { roles: Role[] }) {
  return (
    <>
      {roles.map((role) => (
        <option key={role} value={role}>
          {roleLabel(role)}
        </option>
      ))}
    </>
  );
}

function ServerError({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div className="callout red mt-2" role="alert">
      <AlertTriangle size={16} />
      <div>{message}</div>
    </div>
  );
}

/** Body for PUT /users/{id} from an existing row, with optional overrides (used for (de)activation). */
export function updateBodyFrom(user: UserDto, patch: Partial<UpdateUserBody> = {}): UpdateUserBody {
  return {
    fullName: user.fullName,
    role: user.role,
    departmentId: user.departmentId,
    jobTitle: user.jobTitle,
    phone: user.phone,
    active: user.active,
    ...patch,
  };
}

export function CreateUserDrawer({ onClose }: { onClose: () => void }) {
  const { user: me } = useAuth();
  const roles = roleOptions(me);
  const create = useCreateUser();
  const toast = useToast();
  const formId = useId();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<UserCreateValues>({
    resolver: zodResolver(userCreateSchema),
    defaultValues: { email: '', fullName: '', role: roles.includes('EMPLOYEE') ? 'EMPLOYEE' : roles[0], departmentId: '', jobTitle: '', phone: '', password: '' },
  });
  const role = useWatch({ control, name: 'role' });

  const onSubmit = handleSubmit((values) => {
    setServerError(null);
    create.mutate(
      {
        email: values.email.trim(),
        fullName: values.fullName.trim(),
        role: values.role,
        departmentId: idOrNull(values.departmentId),
        jobTitle: textOrNull(values.jobTitle),
        phone: textOrNull(values.phone),
        password: values.password,
      },
      {
        onSuccess: (user) => {
          toast.success(`${user.fullName} created — they must change the password at first sign-in`);
          onClose();
        },
        onError: (error) => {
          if (!applyFieldErrors(error, setError)) setServerError(errorMessage(error));
        },
      },
    );
  });

  return (
    <Drawer
      open
      onClose={() => !create.isPending && onClose()}
      label="NEW USER"
      title="Create user"
      subtitle="The user signs in with this e-mail and must change the initial password."
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={create.isPending}>
            Cancel
          </button>
          <button type="submit" form={formId} className="gold-btn" disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Create user'}
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={onSubmit} noValidate>
        <TextField label="Full name" required autoComplete="off" error={errors.fullName?.message} {...register('fullName')} />
        <TextField label="Email" type="email" required autoComplete="off" error={errors.email?.message} {...register('email')} />
        <div className="form-row">
          <SelectField label="Role" required error={errors.role?.message} hint={role ? ROLE_DESCRIPTIONS[role] : undefined} {...register('role')}>
            <RoleOptions roles={roles} />
          </SelectField>
          <SelectField label="Department" error={errors.departmentId?.message} {...register('departmentId')}>
            <DepartmentOptions />
          </SelectField>
        </div>
        <div className="form-row">
          <TextField label="Job title" error={errors.jobTitle?.message} {...register('jobTitle')} />
          <TextField label="Phone" error={errors.phone?.message} {...register('phone')} />
        </div>
        <TextField
          label="Initial password"
          type="password"
          autoComplete="new-password"
          required
          hint="At least 10 characters with letters and digits. Share it securely; the user must change it at first sign-in."
          error={errors.password?.message}
          {...register('password')}
        />
        <ServerError message={serverError} />
      </form>
    </Drawer>
  );
}

export function EditUserDrawer({ user, onClose }: { user: UserDto; onClose: () => void }) {
  const { user: me } = useAuth();
  const self = isSelf(me, user);
  const roles = self ? [user.role] : roleOptions(me, user.role);
  const update = useUpdateUser();
  const toast = useToast();
  const formId = useId();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors, isDirty },
  } = useForm<UserUpdateValues>({
    resolver: zodResolver(userUpdateSchema),
    defaultValues: {
      fullName: user.fullName,
      role: user.role,
      departmentId: user.departmentId ? String(user.departmentId) : '',
      jobTitle: user.jobTitle ?? '',
      phone: user.phone ?? '',
      active: user.active,
    },
  });
  const role = useWatch({ control, name: 'role' });

  const onSubmit = handleSubmit((values) => {
    setServerError(null);
    update.mutate(
      {
        id: user.id,
        body: {
          fullName: values.fullName.trim(),
          // Your own role and status cannot be changed from here (the API rejects it too).
          role: self ? user.role : values.role,
          departmentId: idOrNull(values.departmentId),
          jobTitle: textOrNull(values.jobTitle),
          phone: textOrNull(values.phone),
          active: self ? user.active : values.active,
        },
      },
      {
        onSuccess: () => {
          toast.success(`${values.fullName.trim()} updated`);
          onClose();
        },
        onError: (error) => {
          if (!applyFieldErrors(error, setError)) setServerError(errorMessage(error));
        },
      },
    );
  });

  return (
    <Drawer
      open
      onClose={() => !update.isPending && onClose()}
      label="EDIT USER"
      title={user.fullName}
      subtitle={user.email}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={update.isPending}>
            Cancel
          </button>
          <button type="submit" form={formId} className="gold-btn" disabled={update.isPending || !isDirty}>
            {update.isPending ? 'Saving…' : 'Save changes'}
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={onSubmit} noValidate>
        <TextField label="Full name" required error={errors.fullName?.message} {...register('fullName')} />
        <div className="form-row">
          <SelectField
            label="Role"
            error={errors.role?.message}
            disabled={self}
            hint={self ? 'You cannot change your own role.' : role ? ROLE_DESCRIPTIONS[role] : undefined}
            {...register('role')}
          >
            <RoleOptions roles={roles} />
          </SelectField>
          <SelectField label="Department" error={errors.departmentId?.message} {...register('departmentId')}>
            <DepartmentOptions />
          </SelectField>
        </div>
        <div className="form-row">
          <TextField label="Job title" error={errors.jobTitle?.message} {...register('jobTitle')} />
          <TextField label="Phone" error={errors.phone?.message} {...register('phone')} />
        </div>
        <CheckboxField label={self ? 'Active (you cannot deactivate yourself)' : 'Active (can sign in)'} disabled={self} {...register('active')} />
        <ServerError message={serverError} />
      </form>
    </Drawer>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <small>{label}</small>
      <b>{children}</b>
    </div>
  );
}

/** Read-only profile of a directory entry (managers, auditors, or accounts the actor cannot change). */
export function UserDetailDrawer({
  user,
  onClose,
  onEdit,
  onResetPassword,
  onToggleActive,
}: {
  user: UserDto;
  onClose: () => void;
  onEdit?: () => void;
  onResetPassword?: () => void;
  onToggleActive?: () => void;
}) {
  const { user: me } = useAuth();
  const reason = readOnlyReason(me, user);
  const self = isSelf(me, user);
  const manageable = !reason;
  return (
    <Drawer
      open
      onClose={onClose}
      label="USER"
      title={
        <span className="flex items-center gap-3">
          <Avatar name={user.fullName} size="lg" />
          <span className="min-w-0">
            {user.fullName}
            <small className="block text-[11px] font-normal text-muted">{user.email}</small>
          </span>
        </span>
      }
      footer={
        manageable ? (
          <>
            {!self && onToggleActive && (
              <button type="button" className={user.active ? 'danger-btn compact' : 'subtle-btn compact'} onClick={onToggleActive}>
                {user.active ? <UserX size={13} /> : <UserCheck size={13} />} {user.active ? 'Deactivate' : 'Activate'}
              </button>
            )}
            {onResetPassword && (
              <button type="button" className="subtle-btn compact" onClick={onResetPassword}>
                <KeyRound size={13} /> Reset password
              </button>
            )}
            {onEdit && (
              <button type="button" className="gold-btn compact" onClick={onEdit}>
                <Pencil size={13} /> Edit user
              </button>
            )}
          </>
        ) : undefined
      }
    >
      <div className="tag-row mt-1">
        <RoleBadge role={user.role} />
        <BooleanBadge value={user.active} />
        <DemoBadge demo={user.demo} />
      </div>
      <div className="detail-grid">
        <Detail label="Department">{user.departmentName ?? '—'}</Detail>
        <Detail label="Job title">{user.jobTitle ?? '—'}</Detail>
        <Detail label="Phone">{user.phone ?? '—'}</Detail>
        <Detail label="Open work">{formatNumber(user.openAssignments)}</Detail>
        <Detail label="Last login">{user.lastLoginAt ? `${formatRelative(user.lastLoginAt)} · ${formatDateTime(user.lastLoginAt)}` : 'Never signed in'}</Detail>
        <Detail label="Created">{formatDateTime(user.createdAt)}</Detail>
      </div>
      <div className="drawer-section">
        <h3>Role</h3>
        <p>
          <b className="text-ivory">{roleLabel(user.role)}</b> — {ROLE_DESCRIPTIONS[user.role] ?? 'Custom role'}
        </p>
      </div>
      {me && ADMINS.includes(me.role) && (
        // Admins see a user's sessions; IT_ADMIN cannot sign out a SYSTEM_ADMIN (API 403).
        <UserSessionsSection userId={user.id} userName={user.fullName} canRevoke={!(me.role === 'IT_ADMIN' && user.role === 'SYSTEM_ADMIN')} />
      )}
      {reason && (
        <p className="chip">
          <Lock size={11} /> {reason}
        </p>
      )}
    </Drawer>
  );
}

export function ResetPasswordModal({ user, onClose }: { user: UserDto; onClose: () => void }) {
  const reset = useResetPassword();
  const toast = useToast();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ResetPasswordValues>({ resolver: zodResolver(resetPasswordSchema), defaultValues: { newPassword: '' } });
  const onSubmit = handleSubmit((values) => {
    setServerError(null);
    reset.mutate(
      { id: user.id, newPassword: values.newPassword },
      {
        onSuccess: () => {
          toast.success(`Password reset for ${user.fullName}. They must change it at next sign-in.`);
          onClose();
        },
        onError: (error) => {
          if (!applyFieldErrors(error, setError)) setServerError(errorMessage(error));
        },
      },
    );
  });
  return (
    <Modal open onClose={onClose} title={`Reset password for ${user.fullName}`} eyebrow="USERS" dismissible={!reset.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <TextField
          label="New temporary password"
          type="password"
          autoComplete="new-password"
          required
          hint="At least 10 characters with letters and digits. The user is forced to change it at next sign-in."
          error={errors.newPassword?.message}
          {...register('newPassword')}
        />
        <ServerError message={serverError} />
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose} disabled={reset.isPending}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={reset.isPending}>
            {reset.isPending ? 'Resetting…' : 'Reset password'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
