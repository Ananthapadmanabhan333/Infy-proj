import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useSaveService, useService, useServices } from '../../api/services';
import { useTeams } from '../../api/teams';
import { useAssignableUsers, useDepartments } from '../../api/users';
import { CheckboxField, idOrNull, SelectField, TextareaField, TextField, textOrNull } from '../FormField';
import { Modal } from '../Modal';
import { ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';
import { applyFieldErrors, errorMessage, toApiError } from '../../lib/api';
import { splitList } from '../../lib/format';
import { serviceSchema, type ServiceValues } from '../../lib/schemas';
import type { BusinessServiceRequest, ServiceDto } from '../../lib/types';
import { DependencyPicker } from './DependencyPicker';
import { TIER_LABELS, blockedDependencyIds, type ServiceWithExtras } from './serviceModel';

interface ServiceEditorProps {
  /** null creates a new service. */
  service: ServiceDto | null;
  onClose: () => void;
  onSaved?: (service: ServiceDto) => void;
}

const DEPENDENCY_ERROR = /depend|cycle|itself/i;

/**
 * Create / edit a business service (CONFIG_ADMINS). Always sends `dependsOnIds` (§11): the current dependencies are
 * loaded first when editing, the service itself and its dependents cannot be chosen, and the API's cycle error is
 * shown under the picker.
 */
export function ServiceEditor({ service, onClose, onSaved }: ServiceEditorProps) {
  const editing = service !== null;
  const save = useSaveService();
  const departments = useDepartments();
  const allServices = useServices();
  const detail = useService(service?.id ?? null);
  const toast = useToast();
  const [dependsOnIds, setDependsOnIds] = useState<number[] | null>(editing ? null : []);
  const [dependencyError, setDependencyError] = useState<string | null>(null);
  // R3 default team: '' = none. Sent as null (unchanged) when nothing changed, 0 to clear, else the id.
  const initialTeam = service?.defaultTeamId ? String(service.defaultTeamId) : '';
  const [defaultTeam, setDefaultTeam] = useState(initialTeam);

  // Seed the picker with the current dependencies once the detail has loaded.
  useEffect(() => {
    if (editing && dependsOnIds === null && detail.data) setDependsOnIds(detail.data.dependencies.map((ref) => ref.id));
  }, [editing, dependsOnIds, detail.data]);

  const {
    register,
    handleSubmit,
    control,
    setError,
    setValue,
    getValues,
    formState: { errors },
  } = useForm<ServiceValues>({
    resolver: zodResolver(serviceSchema),
    defaultValues: {
      code: service?.code ?? '',
      name: service?.name ?? '',
      description: service?.description ?? '',
      departmentId: service ? String(service.departmentId) : '',
      ownerId: service?.ownerId ? String(service.ownerId) : '',
      tier: String(service?.tier ?? 2) as ServiceValues['tier'],
      keywords: service?.keywords.join(', ') ?? '',
      maintenance: service?.maintenance ?? false,
    },
  });
  const departmentId = useWatch({ control, name: 'departmentId' });
  const owners = useAssignableUsers(idOrNull(departmentId), Boolean(departmentId));
  const teams = useTeams({ departmentId: departmentId || undefined, active: true }, Boolean(departmentId));
  // The team must belong to the service's department: drop the choice when the department changes.
  useEffect(() => {
    if (!teams.data || !defaultTeam || departmentId === String(service?.departmentId ?? '')) return;
    if (!teams.data.some((team) => String(team.id) === defaultTeam)) setDefaultTeam('');
  }, [teams.data, defaultTeam, departmentId, service?.departmentId]);

  // The selects mount before their options load; re-apply the form values once the options exist so an edit
  // never silently drops the current department or owner.
  useEffect(() => {
    if (departments.data) setValue('departmentId', getValues('departmentId'));
  }, [departments.data, getValues, setValue]);
  useEffect(() => {
    if (owners.data) setValue('ownerId', getValues('ownerId'));
  }, [owners.data, getValues, setValue]);

  const blocked = useMemo(
    () => blockedDependencyIds((allServices.data ?? []) as ServiceWithExtras[], service?.id ?? null, detail.data?.dependents.map((ref) => ref.id) ?? []),
    [allServices.data, service?.id, detail.data],
  );
  const dependenciesReady = dependsOnIds !== null && Boolean(allServices.data);

  const onSubmit = handleSubmit((values) => {
    if (dependsOnIds === null) return;
    setDependencyError(null);
    const extras = (service ?? {}) as Partial<ServiceWithExtras>;
    const body: BusinessServiceRequest & { defaultCategory?: ServiceWithExtras['defaultCategory'] } = {
      code: values.code.trim(),
      name: values.name.trim(),
      description: textOrNull(values.description),
      departmentId: Number(values.departmentId),
      ownerId: idOrNull(values.ownerId),
      tier: Number(values.tier) as 1 | 2 | 3,
      keywords: splitList(values.keywords),
      maintenance: values.maintenance,
      dependsOnIds,
      defaultTeamId: defaultTeam === initialTeam ? null : defaultTeam ? Number(defaultTeam) : 0,
      // Not edited here; passed back unchanged so saving never clears it.
      ...(extras.defaultCategory !== undefined ? { defaultCategory: extras.defaultCategory } : {}),
    };
    save.mutate(
      { id: service?.id, body },
      {
        onSuccess: (saved) => {
          toast.success(editing ? `${saved.name} updated` : `${saved.name} created`);
          onSaved?.(saved);
          onClose();
        },
        onError: (error) => {
          const apiError = toApiError(error);
          const dependencyField = apiError.fieldErrors?.find((fieldError) => fieldError.field.startsWith('dependsOnIds'));
          if (dependencyField) setDependencyError(dependencyField.message);
          else if (!apiError.fieldErrors?.length && DEPENDENCY_ERROR.test(apiError.message)) setDependencyError(apiError.message);
          applyFieldErrors(error, setError);
        },
      },
    );
  });

  const generalError = save.isError && !Object.keys(errors).length && !dependencyError ? errorMessage(save.error) : null;

  return (
    <Modal open onClose={onClose} title={editing ? `Edit ${service.name}` : 'New business service'} eyebrow="SERVICES" size="lg" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate aria-label={editing ? `Edit ${service.name}` : 'New business service'}>
        <div className="form-row">
          <TextField label="Code" required hint="Upper-case letters, digits, - or _" error={errors.code?.message} {...register('code')} />
          <TextField label="Name" required error={errors.name?.message} {...register('name')} />
        </div>
        <TextareaField label="Description" error={errors.description?.message} {...register('description')} />
        <div className="form-row three">
          <SelectField label="Owning department" required error={errors.departmentId?.message} {...register('departmentId')}>
            <option value="">Choose a department</option>
            {departments.data?.map((department) => (
              <option key={department.id} value={department.id}>
                {department.name}
              </option>
            ))}
          </SelectField>
          <SelectField label="Service owner" error={errors.ownerId?.message} {...register('ownerId')}>
            <option value="">No owner</option>
            {service?.ownerId && service.ownerName && !owners.data?.some((owner) => owner.id === service.ownerId) && <option value={service.ownerId}>{service.ownerName}</option>}
            {owners.data?.map((owner) => (
              <option key={owner.id} value={owner.id}>
                {owner.fullName}
              </option>
            ))}
          </SelectField>
          <SelectField label="Tier" error={errors.tier?.message} {...register('tier')}>
            {([1, 2, 3] as const).map((tier) => (
              <option key={tier} value={tier}>
                {TIER_LABELS[tier]}
              </option>
            ))}
          </SelectField>
        </div>
        <SelectField
          label="Default team"
          hint="New incidents of this service are routed to this team (else the department's default team)."
          value={defaultTeam}
          onChange={(event) => setDefaultTeam(event.target.value)}
          disabled={!departmentId || teams.isLoading}
        >
          <option value="">{!departmentId ? 'Choose a department first' : teams.isLoading ? 'Loading teams…' : 'No default team'}</option>
          {service?.defaultTeamId && service.defaultTeamName && !teams.data?.some((team) => team.id === service.defaultTeamId) && (
            <option value={service.defaultTeamId}>{service.defaultTeamName}</option>
          )}
          {teams.data?.map((team) => (
            <option key={team.id} value={team.id}>
              {team.name}
              {team.departmentDefault ? ' (department default)' : ''}
            </option>
          ))}
        </SelectField>
        <TextField
          label="Classification keywords"
          hint="Comma separated. Incident titles containing these words are routed to this service."
          error={errors.keywords?.message}
          {...register('keywords')}
        />
        {(allServices.isLoading || (editing && detail.isLoading)) && <LoadingState rows={2} label="Loading dependencies…" />}
        {allServices.isError && <ErrorState error={allServices.error} onRetry={() => void allServices.refetch()} compact title="Could not load services" />}
        {editing && detail.isError && <ErrorState error={detail.error} onRetry={() => void detail.refetch()} compact title="Could not load the current dependencies" />}
        {dependenciesReady && (
          <DependencyPicker
            services={allServices.data ?? []}
            value={dependsOnIds}
            onChange={(ids) => {
              setDependsOnIds(ids);
              setDependencyError(null);
            }}
            blocked={blocked}
            selfId={service?.id ?? null}
            disabled={save.isPending}
            error={dependencyError}
          />
        )}
        <CheckboxField label="Under planned maintenance" {...register('maintenance')} />
        {generalError && (
          <p className="field-error" role="alert">
            {generalError}
          </p>
        )}
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={save.isPending || !dependenciesReady}>
            {save.isPending ? 'Saving…' : 'Save service'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
