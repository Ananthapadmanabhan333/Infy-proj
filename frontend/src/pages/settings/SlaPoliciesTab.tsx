import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Pencil, Plus, Timer } from 'lucide-react';
import { useCalendars, useSaveSlaPolicy, useSlaPolicies } from '../../api/sla';
import { BooleanBadge, PriorityBadge } from '../../components/Badge';
import { DataTable } from '../../components/DataTable';
import { CheckboxField, idOrNull, SelectField, TextareaField, TextField, textOrNull } from '../../components/FormField';
import { Modal } from '../../components/Modal';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { PRIORITIES, PRIORITY_LABELS } from '../../lib/constants';
import { formatMinutes } from '../../lib/format';
import { slaPolicySchema, type SlaPolicyValues } from '../../lib/schemas';
import type { CalendarDto, SlaPolicyDto } from '../../lib/types';

function PolicyModal({ policy, calendars, onClose }: { policy: SlaPolicyDto | null; calendars: CalendarDto[]; onClose: () => void }) {
  const save = useSaveSlaPolicy();
  const toast = useToast();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<SlaPolicyValues>({
    resolver: zodResolver(slaPolicySchema),
    defaultValues: {
      name: policy?.name ?? '',
      priority: policy?.priority ?? 'MEDIUM',
      responseMinutes: policy?.responseMinutes ?? 60,
      resolutionMinutes: policy?.resolutionMinutes ?? 480,
      businessHoursOnly: policy?.businessHoursOnly ?? true,
      calendarId: policy?.calendarId ? String(policy.calendarId) : '',
      atRiskPercent: policy?.atRiskPercent ?? 75,
      active: policy?.active ?? true,
      description: policy?.description ?? '',
    },
  });
  const onSubmit = handleSubmit((values) =>
    save.mutate(
      {
        id: policy?.id,
        body: {
          name: values.name.trim(),
          priority: values.priority,
          responseMinutes: values.responseMinutes,
          resolutionMinutes: values.resolutionMinutes,
          businessHoursOnly: values.businessHoursOnly,
          calendarId: idOrNull(values.calendarId),
          atRiskPercent: values.atRiskPercent,
          active: values.active,
          description: textOrNull(values.description),
        },
      },
      {
        onSuccess: () => {
          toast.success(policy ? 'SLA policy updated — active incidents are recalculated by the SLA engine' : 'SLA policy created');
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );
  return (
    <Modal open onClose={onClose} title={policy ? `Edit ${policy.name}` : 'New SLA policy'} eyebrow="SLA POLICIES" size="lg" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <div className="form-row">
          <TextField label="Name" required error={errors.name?.message} {...register('name')} />
          <SelectField label="Priority" error={errors.priority?.message} {...register('priority')}>
            {PRIORITIES.map((priority) => (
              <option key={priority} value={priority}>
                {PRIORITY_LABELS[priority]}
              </option>
            ))}
          </SelectField>
        </div>
        <div className="form-row three">
          <TextField label="Response target (minutes)" type="number" min={1} error={errors.responseMinutes?.message} {...register('responseMinutes', { valueAsNumber: true })} />
          <TextField label="Resolution target (minutes)" type="number" min={1} error={errors.resolutionMinutes?.message} {...register('resolutionMinutes', { valueAsNumber: true })} />
          <TextField label="At-risk threshold (%)" type="number" min={1} max={99} error={errors.atRiskPercent?.message} {...register('atRiskPercent', { valueAsNumber: true })} />
        </div>
        <SelectField label="Business-hours calendar" error={errors.calendarId?.message} {...register('calendarId')}>
          <option value="">Default calendar</option>
          {calendars.map((calendar) => (
            <option key={calendar.id} value={calendar.id}>
              {calendar.name} ({calendar.timezone})
            </option>
          ))}
        </SelectField>
        <TextareaField label="Description" rows={2} error={errors.description?.message} {...register('description')} />
        <div className="flex flex-wrap gap-6">
          <CheckboxField label="Count business hours only" {...register('businessHoursOnly')} />
          <CheckboxField label="Active" {...register('active')} />
        </div>
        {save.isError && !Object.keys(errors).length && <p className="field-error">{errorMessage(save.error)}</p>}
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save policy'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** Settings → SLA Policies (CONFIG_ADMINS): response and resolution targets per priority. */
export function SlaPoliciesTab() {
  const policies = useSlaPolicies();
  const calendars = useCalendars();
  const [editing, setEditing] = useState<SlaPolicyDto | null | 'new'>(null);
  return (
    <Panel
      title="SLA policies"
      subtitle="Response and resolution targets per priority. Saving a policy recalculates active incidents."
      actions={
        <button type="button" className="gold-btn compact" onClick={() => setEditing('new')}>
          <Plus size={13} /> New policy
        </button>
      }
    >
      {policies.isLoading && <LoadingState rows={4} label="Loading SLA policies…" />}
      {policies.isError && <ErrorState error={policies.error} onRetry={() => void policies.refetch()} />}
      {policies.data && (
        <DataTable<SlaPolicyDto>
          caption="SLA policies"
          rows={[...policies.data].sort((a, b) => PRIORITIES.indexOf(a.priority) - PRIORITIES.indexOf(b.priority))}
          rowKey={(row) => row.id}
          onRowClick={(row) => setEditing(row)}
          rowLabel={(row) => `Edit ${row.name}`}
          rowClassName={(row) => (row.active ? '' : 'opacity-70')}
          empty={<EmptyState title="No SLA policies defined" icon={<Timer size={20} />} description="Add a policy per priority so incidents get response and resolution targets." />}
          columns={[
            {
              key: 'name',
              header: 'Policy',
              className: 'wrap',
              render: (row) => (
                <span className="block">
                  <b className="text-ivory">{row.name}</b>
                  {row.description && <small className="block text-[9px] text-muted">{row.description}</small>}
                </span>
              ),
            },
            { key: 'priority', header: 'Priority', render: (row) => <PriorityBadge priority={row.priority} /> },
            { key: 'response', header: 'Response', render: (row) => formatMinutes(row.responseMinutes) },
            { key: 'resolution', header: 'Resolution', render: (row) => formatMinutes(row.resolutionMinutes) },
            { key: 'hours', header: 'Clock', render: (row) => (row.businessHoursOnly ? `Business hours${row.calendarName ? ` · ${row.calendarName}` : ''}` : '24×7') },
            { key: 'risk', header: 'At risk at', render: (row) => `${row.atRiskPercent}%` },
            { key: 'active', header: 'Status', render: (row) => <BooleanBadge value={row.active} /> },
            {
              key: 'edit',
              header: <span className="sr-only">Edit</span>,
              render: (row) => (
                <button
                  type="button"
                  className="icon-btn sm"
                  aria-label={`Edit ${row.name}`}
                  onClick={(event) => {
                    event.stopPropagation();
                    setEditing(row);
                  }}
                >
                  <Pencil size={14} />
                </button>
              ),
            },
          ]}
        />
      )}
      {editing && <PolicyModal policy={editing === 'new' ? null : editing} calendars={calendars.data ?? []} onClose={() => setEditing(null)} />}
    </Panel>
  );
}
