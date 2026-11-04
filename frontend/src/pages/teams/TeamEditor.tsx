import { useEffect, useMemo } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useSaveTeam } from '../../api/teams';
import { useDepartments, useUsers } from '../../api/users';
import { CheckboxField, idOrNull, SelectField, TextareaField, TextField, textOrNull } from '../../components/FormField';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { emailSchema } from '../../lib/schemas';
import type { TeamDto } from '../../lib/types';
import { isStaffRole, manageableDepartments } from './teamAccess';

export const teamSchema = z
  .object({
    code: z
      .string()
      .trim()
      .regex(/^[A-Z0-9_-]{2,40}$/, '2–40 upper-case letters, digits, - or _'),
    name: z.string().trim().min(2, 'Name must be at least 2 characters').max(120, 'Name must be at most 120 characters'),
    departmentId: z.string().min(1, 'Department is required'),
    leadId: z.string(),
    email: z.union([z.literal(''), emailSchema]),
    description: z.string().trim().max(500, 'At most 500 characters'),
    active: z.boolean(),
    departmentDefault: z.boolean(),
  })
  .refine((values) => values.active || !values.departmentDefault, { path: ['departmentDefault'], message: 'An inactive team cannot be the department default' });
export type TeamValues = z.infer<typeof teamSchema>;

function defaults(team: TeamDto | null): TeamValues {
  return {
    code: team?.code ?? '',
    name: team?.name ?? '',
    departmentId: team ? String(team.departmentId) : '',
    leadId: team?.leadId ? String(team.leadId) : '',
    email: team?.email ?? '',
    description: team?.description ?? '',
    active: team?.active ?? true,
    departmentDefault: team?.departmentDefault ?? false,
  };
}

/** Create / edit a team (team admins). The code cannot change once created. */
export function TeamEditor({ team, onClose, onSaved }: { team: TeamDto | null; onClose: () => void; onSaved?: (team: TeamDto) => void }) {
  const editing = team !== null;
  const { user } = useAuth();
  const toast = useToast();
  const save = useSaveTeam();
  const departments = useDepartments();
  const staff = useUsers({ active: 'true', size: 100 });
  const {
    register,
    handleSubmit,
    control,
    setError,
    setValue,
    getValues,
    formState: { errors },
  } = useForm<TeamValues>({ resolver: zodResolver(teamSchema), defaultValues: defaults(team) });
  const active = useWatch({ control, name: 'active' });

  const departmentOptions = useMemo(() => {
    const allowed = manageableDepartments(user, departments.data);
    // Keep the team's own department selectable when editing (the API checks the change).
    if (team && !allowed.some((department) => department.id === team.departmentId)) {
      const own = departments.data?.find((department) => department.id === team.departmentId);
      if (own) return [own, ...allowed];
    }
    return allowed;
  }, [user, departments.data, team]);

  const leads = useMemo(
    () => (staff.data?.content ?? []).filter((candidate) => candidate.active && isStaffRole(candidate.role)).sort((a, b) => a.fullName.localeCompare(b.fullName)),
    [staff.data],
  );

  // Selects mount before their options; re-apply the values once options exist.
  useEffect(() => {
    if (departments.data) setValue('departmentId', getValues('departmentId'));
  }, [departments.data, getValues, setValue]);
  useEffect(() => {
    if (staff.data) setValue('leadId', getValues('leadId'));
  }, [staff.data, getValues, setValue]);
  useEffect(() => {
    if (!active) setValue('departmentDefault', false);
  }, [active, setValue]);

  const onSubmit = handleSubmit((values) =>
    save.mutate(
      {
        id: team?.id,
        body: {
          code: values.code.trim(),
          name: values.name.trim(),
          departmentId: Number(values.departmentId),
          leadId: idOrNull(values.leadId),
          email: textOrNull(values.email),
          description: textOrNull(values.description),
          active: values.active,
          departmentDefault: values.departmentDefault,
        },
      },
      {
        onSuccess: (saved) => {
          toast.success(editing ? `${saved.name} updated` : `${saved.name} created`);
          onSaved?.(saved);
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  const generalError = save.isError && !Object.keys(errors).length ? errorMessage(save.error) : null;

  return (
    <Modal open onClose={onClose} title={editing ? `Edit ${team.name}` : 'New team'} eyebrow="TEAMS" size="lg" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate aria-label={editing ? `Edit ${team.name}` : 'New team'}>
        <div className="form-row">
          <TextField
            label="Code"
            required
            disabled={editing}
            hint={editing ? 'The code cannot change once the team exists.' : 'Upper-case letters, digits, - or _'}
            error={errors.code?.message}
            {...register('code')}
          />
          <TextField label="Name" required error={errors.name?.message} {...register('name')} />
        </div>
        <div className="form-row">
          <SelectField label="Department" required error={errors.departmentId?.message} disabled={departments.isLoading} {...register('departmentId')}>
            <option value="">{departments.isLoading ? 'Loading departments…' : 'Choose a department'}</option>
            {departmentOptions.map((department) => (
              <option key={department.id} value={department.id}>
                {department.name}
              </option>
            ))}
          </SelectField>
          <SelectField label="Team lead" hint="Active staff; the lead becomes a LEAD member." error={errors.leadId?.message} {...register('leadId')}>
            <option value="">No lead</option>
            {team?.leadId && team.leadName && !leads.some((lead) => lead.id === team.leadId) && <option value={team.leadId}>{team.leadName}</option>}
            {leads.map((lead) => (
              <option key={lead.id} value={lead.id}>
                {lead.fullName}
                {lead.departmentName ? ` · ${lead.departmentName}` : ''}
              </option>
            ))}
          </SelectField>
        </div>
        <TextField label="Team e-mail" type="email" error={errors.email?.message} {...register('email')} />
        <TextareaField label="Description" error={errors.description?.message} {...register('description')} />
        <CheckboxField label="Active" {...register('active')} />
        <CheckboxField
          label={
            <>
              Default team of the department
              <small className="block text-[10px] text-muted">New incidents and requests of the department go to this team; the flag is cleared on its other teams.</small>
            </>
          }
          disabled={!active}
          error={errors.departmentDefault?.message}
          {...register('departmentDefault')}
        />
        {staff.isError && <p className="field-error">Could not load staff: {errorMessage(staff.error)}</p>}
        {generalError && (
          <p className="field-error" role="alert">
            {generalError}
          </p>
        )}
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : editing ? 'Save team' : 'Create team'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
