import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useSaveMaintenance, useSaveStatusComponent, useSaveStatusUpdate } from '../../api/statusPageAdmin';
import { useServices } from '../../api/services';
import { CheckboxField, SelectField, TextareaField, TextField, textOrNull } from '../../components/FormField';
import { IncidentPicker } from '../../components/incidents/IncidentPicker';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { isoToLocalInput, localInputToIso } from '../../lib/format';
import type { IncidentSummary, StatusComponentDto, StatusMaintenanceDto, StatusUpdateDto, StatusUpdateStatus } from '../../lib/types';

export const UPDATE_STATUSES: StatusUpdateStatus[] = ['INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED'];
export const UPDATE_STATUS_LABELS: Record<StatusUpdateStatus, string> = {
  INVESTIGATING: 'Investigating',
  IDENTIFIED: 'Identified',
  MONITORING: 'Monitoring',
  RESOLVED: 'Resolved',
};

function GeneralError({ error, hasFieldErrors }: { error: unknown; hasFieldErrors: boolean }) {
  if (!error || hasFieldErrors) return null;
  return (
    <p className="field-error" role="alert">
      {errorMessage(error)}
    </p>
  );
}

function Foot({ onClose, pending, label }: { onClose: () => void; pending: boolean; label: string }) {
  return (
    <div className="modal-foot">
      <button type="button" className="subtle-btn" onClick={onClose} disabled={pending}>
        Cancel
      </button>
      <button type="submit" className="gold-btn" disabled={pending}>
        {pending ? 'Saving…' : label}
      </button>
    </div>
  );
}

// ------------------------------------------------------------------ component
const componentSchema = z.object({
  serviceId: z.string().min(1, 'Choose a business service'),
  displayName: z.string().trim().min(1, 'Display name is required').max(120, 'At most 120 characters'),
  groupName: z.string().trim().max(80, 'At most 80 characters'),
  description: z.string().trim().max(500, 'At most 500 characters'),
  sortOrder: z.string().regex(/^\d{0,5}$/, 'A whole number'),
  publicVisible: z.boolean(),
});
type ComponentValues = z.infer<typeof componentSchema>;

/** Maps a business service to a status component (one per service; the service cannot change afterwards). */
export function ComponentDialog({ component, existing, onClose }: { component: StatusComponentDto | null; existing: StatusComponentDto[]; onClose: () => void }) {
  const editing = component !== null;
  const save = useSaveStatusComponent();
  const services = useServices();
  const toast = useToast();
  const mapped = useMemo(() => new Set(existing.map((entry) => entry.serviceId)), [existing]);
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    getValues,
    formState: { errors },
  } = useForm<ComponentValues>({
    resolver: zodResolver(componentSchema),
    defaultValues: {
      serviceId: component ? String(component.serviceId) : '',
      displayName: component?.displayName ?? '',
      groupName: component?.groupName ?? '',
      description: component?.description ?? '',
      sortOrder: component ? String(component.sortOrder ?? '') : '',
      publicVisible: component?.publicVisible ?? true,
    },
  });
  useEffect(() => {
    if (services.data) setValue('serviceId', getValues('serviceId'));
  }, [services.data, getValues, setValue]);

  const onSubmit = handleSubmit((values) =>
    save.mutate(
      {
        id: component?.id,
        body: {
          serviceId: Number(values.serviceId),
          displayName: values.displayName.trim(),
          groupName: textOrNull(values.groupName),
          description: textOrNull(values.description),
          sortOrder: values.sortOrder ? Number(values.sortOrder) : null,
          publicVisible: values.publicVisible,
        },
      },
      {
        onSuccess: () => {
          toast.success(editing ? 'Component updated' : 'Component added');
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  const options = (services.data ?? []).filter((service) => (component ? service.id === component.serviceId : !mapped.has(service.id)));

  return (
    <Modal open onClose={onClose} title={editing ? `Edit ${component.displayName}` : 'Add a component'} eyebrow="STATUS PAGE" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate aria-label={editing ? 'Edit component' : 'Add component'}>
        <SelectField
          label="Business service"
          required
          disabled={editing || services.isLoading}
          hint={editing ? 'The service cannot change; delete and re-add the component instead.' : 'Status and uptime come from the service health.'}
          error={errors.serviceId?.message}
          {...register('serviceId', {
            onChange: (event) => {
              const service = services.data?.find((entry) => String(entry.id) === event.target.value);
              if (service && !getValues('displayName')) setValue('displayName', service.name);
            },
          })}
        >
          <option value="">{services.isLoading ? 'Loading services…' : options.length ? 'Choose a service' : 'Every service is already on the page'}</option>
          {options.map((service) => (
            <option key={service.id} value={service.id}>
              {service.name} ({service.code})
            </option>
          ))}
        </SelectField>
        <div className="form-row">
          <TextField label="Display name" required error={errors.displayName?.message} {...register('displayName')} />
          <TextField label="Group" hint="e.g. Business applications" error={errors.groupName?.message} {...register('groupName')} />
        </div>
        <TextareaField label="Description" error={errors.description?.message} {...register('description')} />
        <TextField label="Sort order" inputMode="numeric" error={errors.sortOrder?.message} {...register('sortOrder')} />
        <CheckboxField label="Shown on the public status page" {...register('publicVisible')} />
        <GeneralError error={save.error} hasFieldErrors={Object.keys(errors).length > 0} />
        <Foot onClose={onClose} pending={save.isPending} label={editing ? 'Save component' : 'Add component'} />
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------ maintenance
const MAX_WINDOW_MS = 31 * 24 * 3600 * 1000;
const maintenanceSchema = z
  .object({
    title: z.string().trim().min(1, 'Title is required').max(200, 'At most 200 characters'),
    description: z.string().trim().max(2000, 'At most 2000 characters'),
    startsAt: z.string().min(1, 'Start is required'),
    endsAt: z.string().min(1, 'End is required'),
    notifySubscribers: z.boolean(),
  })
  .refine((values) => new Date(values.endsAt).getTime() > new Date(values.startsAt).getTime(), { path: ['endsAt'], message: 'The end must be after the start' })
  .refine((values) => new Date(values.endsAt).getTime() - new Date(values.startsAt).getTime() <= MAX_WINDOW_MS, { path: ['endsAt'], message: 'A window lasts at most 31 days' });
type MaintenanceValues = z.infer<typeof maintenanceSchema>;

export function MaintenanceDialog({ maintenance, components, onClose }: { maintenance: StatusMaintenanceDto | null; components: StatusComponentDto[]; onClose: () => void }) {
  const editing = maintenance !== null;
  const save = useSaveMaintenance();
  const toast = useToast();
  const [componentIds, setComponentIds] = useState<number[]>(() => maintenance?.components.map((component) => component.id) ?? []);
  const [componentError, setComponentError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<MaintenanceValues>({
    resolver: zodResolver(maintenanceSchema),
    defaultValues: {
      title: maintenance?.title ?? '',
      description: maintenance?.description ?? '',
      startsAt: isoToLocalInput(maintenance?.startsAt),
      endsAt: isoToLocalInput(maintenance?.endsAt),
      // POST defaults to notifying subscribers, PUT does not (API).
      notifySubscribers: !editing,
    },
  });

  const toggle = (id: number, checked: boolean) => {
    setComponentError(null);
    setComponentIds((current) => (checked ? [...current, id] : current.filter((entry) => entry !== id)));
  };

  const onSubmit = handleSubmit((values) => {
    if (componentIds.length < 1 || componentIds.length > 50) {
      setComponentError('Choose between 1 and 50 components');
      return;
    }
    save.mutate(
      {
        id: maintenance?.id,
        body: {
          title: values.title.trim(),
          description: textOrNull(values.description),
          startsAt: localInputToIso(values.startsAt) ?? values.startsAt,
          endsAt: localInputToIso(values.endsAt) ?? values.endsAt,
          componentIds,
          notifySubscribers: values.notifySubscribers,
        },
      },
      {
        onSuccess: () => {
          toast.success(editing ? 'Maintenance updated' : 'Maintenance scheduled');
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    );
  });

  return (
    <Modal open onClose={onClose} title={editing ? `Edit ${maintenance.title}` : 'Schedule maintenance'} eyebrow="STATUS PAGE" size="lg" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate aria-label={editing ? 'Edit maintenance' : 'Schedule maintenance'}>
        <TextField label="Title" required error={errors.title?.message} {...register('title')} />
        <TextareaField label="Description" error={errors.description?.message} {...register('description')} />
        <div className="form-row">
          <TextField label="Starts" type="datetime-local" required error={errors.startsAt?.message} {...register('startsAt')} />
          <TextField label="Ends" type="datetime-local" required error={errors.endsAt?.message} {...register('endsAt')} />
        </div>
        <fieldset className="field">
          <legend className="field-label">Affected components</legend>
          {components.length === 0 ? (
            <p className="m-0 text-[11px] text-muted">Add a component first.</p>
          ) : (
            <div className="grid gap-1 sm:grid-cols-2">
              {components.map((component) => (
                <label key={component.id} className="check">
                  <input type="checkbox" checked={componentIds.includes(component.id)} onChange={(event) => toggle(component.id, event.target.checked)} />
                  <span>
                    {component.displayName}
                    {!component.publicVisible && <small className="text-muted"> (internal)</small>}
                  </span>
                </label>
              ))}
            </div>
          )}
          {componentError && (
            <span className="field-error" role="alert">
              {componentError}
            </span>
          )}
        </fieldset>
        <CheckboxField label={editing ? 'E-mail subscribers about this change' : 'E-mail subscribers (public page, SMTP configured)'} {...register('notifySubscribers')} />
        <GeneralError error={save.error} hasFieldErrors={Object.keys(errors).length > 0} />
        <Foot onClose={onClose} pending={save.isPending} label={editing ? 'Save maintenance' : 'Schedule'} />
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------ incident update
const updateSchema = z.object({
  title: z.string().trim().min(1, 'Title is required').max(200, 'At most 200 characters'),
  status: z.enum(['INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED']),
  message: z.string().trim().min(1, 'Message is required').max(5000, 'At most 5000 characters'),
  publicVisible: z.boolean(),
});
type UpdateValues = z.infer<typeof updateSchema>;

/** Minimal incident value for the picker when only the id and number are known. */
function linkedIncident(update: StatusUpdateDto | null): IncidentSummary | null {
  if (!update?.incidentId) return null;
  return { id: update.incidentId, number: update.incidentNumber ?? `#${update.incidentId}`, title: 'Linked incident' } as IncidentSummary;
}

export function UpdateDialog({ update, publicEnabled, onClose }: { update: StatusUpdateDto | null; publicEnabled: boolean; onClose: () => void }) {
  const editing = update !== null;
  const save = useSaveStatusUpdate();
  const toast = useToast();
  const [incident, setIncident] = useState<IncidentSummary | null>(() => linkedIncident(update));
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<UpdateValues>({
    resolver: zodResolver(updateSchema),
    defaultValues: { title: update?.title ?? '', status: update?.status ?? 'INVESTIGATING', message: update?.message ?? '', publicVisible: update?.publicVisible ?? false },
  });

  const onSubmit = handleSubmit((values) =>
    save.mutate(
      { id: update?.id, body: { title: values.title.trim(), status: values.status, message: values.message.trim(), incidentId: incident?.id ?? null, publicVisible: values.publicVisible } },
      {
        onSuccess: () => {
          toast.success(editing ? 'Update edited' : 'Update posted');
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  return (
    <Modal open onClose={onClose} title={editing ? 'Edit status update' : 'Post a status update'} eyebrow="STATUS PAGE" size="lg" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate aria-label={editing ? 'Edit status update' : 'Post status update'}>
        <div className="form-row">
          <TextField label="Title" required error={errors.title?.message} {...register('title')} />
          <SelectField label="Status" required error={errors.status?.message} {...register('status')}>
            {UPDATE_STATUSES.map((status) => (
              <option key={status} value={status}>
                {UPDATE_STATUS_LABELS[status]}
              </option>
            ))}
          </SelectField>
        </div>
        <TextareaField label="Message" required rows={5} error={errors.message?.message} {...register('message')} />
        <IncidentPicker label="Linked incident (optional)" value={incident} onChange={setIncident} />
        <CheckboxField
          label={
            <>
              Public update
              <small className="block text-[10px] text-muted">
                {editing
                  ? 'Shown on the public page. Edits are not e-mailed.'
                  : publicEnabled
                    ? 'Shown on the public page and e-mailed to confirmed subscribers.'
                    : 'Shown once the public page is published.'}
              </small>
            </>
          }
          {...register('publicVisible')}
        />
        <GeneralError error={save.error} hasFieldErrors={Object.keys(errors).length > 0} />
        <Foot onClose={onClose} pending={save.isPending} label={editing ? 'Save update' : 'Post update'} />
      </form>
    </Modal>
  );
}
