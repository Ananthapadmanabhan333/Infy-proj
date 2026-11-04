import { useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle } from 'lucide-react';
import { useSaveDepartment, useUsers } from '../../api/users';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { ADMIN_OR_MANAGER, roleLabel } from '../../lib/constants';
import { departmentSchema, type DepartmentValues } from '../../lib/schemas';
import type { DepartmentDto } from '../../lib/types';
import { Drawer } from '../Drawer';
import { idOrNull, SelectField, TextareaField, TextField, textOrNull } from '../FormField';
import { useToast } from '../Toast';

/** Create / edit a department (USER_ADMINS). Managers are chosen from active admins and managers. */
export function DepartmentDrawer({ department, onClose }: { department: DepartmentDto | null; onClose: () => void }) {
  const save = useSaveDepartment();
  const candidates = useUsers({ size: 100, active: 'true' });
  const toast = useToast();
  const formId = useId();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<DepartmentValues>({
    resolver: zodResolver(departmentSchema),
    defaultValues: {
      code: department?.code ?? '',
      name: department?.name ?? '',
      description: department?.description ?? '',
      managerId: department?.managerId ? String(department.managerId) : '',
      email: department?.email ?? '',
    },
  });

  const managers = (candidates.data?.content ?? []).filter((user) => ADMIN_OR_MANAGER.includes(user.role));
  const currentMissing = department?.managerId && !managers.some((user) => user.id === department.managerId);

  const onSubmit = handleSubmit((values) => {
    setServerError(null);
    save.mutate(
      {
        id: department?.id,
        body: {
          code: values.code.trim(),
          name: values.name.trim(),
          description: textOrNull(values.description),
          managerId: idOrNull(values.managerId),
          email: textOrNull(values.email),
        },
      },
      {
        onSuccess: () => {
          toast.success(department ? `${values.name.trim()} updated` : `${values.name.trim()} created`);
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
      onClose={() => !save.isPending && onClose()}
      label={department ? 'EDIT DEPARTMENT' : 'NEW DEPARTMENT'}
      title={department ? department.name : 'Create department'}
      subtitle="Departments own services and support queues; the manager receives escalations."
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="submit" form={formId} className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save department'}
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={onSubmit} noValidate>
        <div className="form-row">
          <TextField label="Code" required placeholder="IT-SD" error={errors.code?.message} {...register('code')} />
          <TextField label="Name" required error={errors.name?.message} {...register('name')} />
        </div>
        <TextareaField label="Description" rows={3} error={errors.description?.message} {...register('description')} />
        <SelectField
          label="Manager"
          error={errors.managerId?.message}
          hint={candidates.isError ? 'Could not load users — the current manager is kept.' : 'Active admins and managers.'}
          {...register('managerId')}
        >
          <option value="">No manager</option>
          {currentMissing && department?.managerId && <option value={department.managerId}>{department.managerName ?? `User #${department.managerId}`}</option>}
          {managers.map((user) => (
            <option key={user.id} value={user.id}>
              {user.fullName} · {roleLabel(user.role)}
            </option>
          ))}
        </SelectField>
        <TextField label="Team email" type="email" placeholder="servicedesk@example.com" error={errors.email?.message} {...register('email')} />
        {serverError && (
          <div className="callout red mt-2" role="alert">
            <AlertTriangle size={16} />
            <div>{serverError}</div>
          </div>
        )}
      </form>
    </Drawer>
  );
}
