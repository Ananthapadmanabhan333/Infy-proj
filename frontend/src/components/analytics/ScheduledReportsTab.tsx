import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { CalendarClock, Download, History, Pencil, Play, Plus, Trash2 } from 'lucide-react';
import { downloadReportRun, useDeleteSchedule, useReportSchedules, useReportTypes, useRunSchedule, useSaveSchedule, useScheduleRuns } from '../../api/reports';
import { useDepartments } from '../../api/users';
import { useAuth } from '../../hooks/useAuth';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { ADMINS, REPORT_EXPORTERS, REPORT_SCHEDULERS } from '../../lib/constants';
import { formatBytes, formatDate, formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import type { ReportFrequency, ReportRunDto, ReportScheduleDto, ReportTypeDto } from '../../lib/types';
import { BooleanBadge, DemoBadge, StatusBadge } from '../Badge';
import { ConfirmDialog } from '../ConfirmDialog';
import { DataTable } from '../DataTable';
import { Drawer } from '../Drawer';
import { CheckboxField, SelectField, TextareaField, TextField } from '../FormField';
import { Modal } from '../Modal';
import { Panel } from '../Panel';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';

const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const FREQUENCY_LABELS: Record<ReportFrequency, string> = { DAILY: 'Daily', WEEKLY: 'Weekly', MONTHLY: 'Monthly' };
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const hour = (value: number) => `${String(value).padStart(2, '0')}:00`;

/** "Weekly · Monday 06:00" (organisation time zone). */
export function describeSchedule(schedule: Pick<ReportScheduleDto, 'frequency' | 'runHour' | 'dayOfWeek' | 'dayOfMonth'>): string {
  if (schedule.frequency === 'WEEKLY') return `Weekly · ${WEEKDAYS[(schedule.dayOfWeek ?? 1) - 1] ?? 'Monday'} ${hour(schedule.runHour)}`;
  if (schedule.frequency === 'MONTHLY') return `Monthly · day ${schedule.dayOfMonth ?? 1} ${hour(schedule.runHour)}`;
  return `Daily · ${hour(schedule.runHour)}`;
}

function splitRecipients(value: string): string[] {
  return value
    .split(/[\s,;]+/)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

const scheduleSchema = z
  .object({
    name: z.string().trim().min(1, 'Name is required').max(150, 'At most 150 characters'),
    reportType: z.string().min(1, 'Choose a report type'),
    format: z.enum(['PDF', 'CSV']),
    frequency: z.enum(['DAILY', 'WEEKLY', 'MONTHLY']),
    runHour: z.string(),
    dayOfWeek: z.string(),
    dayOfMonth: z.string(),
    departmentId: z.string(),
    recipients: z.string(),
    active: z.boolean(),
  })
  .superRefine((values, ctx) => {
    const recipients = splitRecipients(values.recipients);
    if (recipients.length > 20) ctx.addIssue({ code: 'custom', path: ['recipients'], message: 'At most 20 recipients' });
    const invalid = recipients.find((entry) => !EMAIL.test(entry));
    if (invalid) ctx.addIssue({ code: 'custom', path: ['recipients'], message: `"${invalid}" is not an e-mail address` });
  });
type ScheduleValues = z.infer<typeof scheduleSchema>;

function ScheduleEditor({ schedule, types, onClose }: { schedule: ReportScheduleDto | null; types: ReportTypeDto[]; onClose: () => void }) {
  const editing = schedule !== null;
  const save = useSaveSchedule();
  const departments = useDepartments();
  const toast = useToast();
  const firstAvailable = types.find((type) => type.available);
  const {
    register,
    handleSubmit,
    control,
    setError,
    setValue,
    formState: { errors },
  } = useForm<ScheduleValues>({
    resolver: zodResolver(scheduleSchema),
    defaultValues: {
      name: schedule?.name ?? '',
      reportType: schedule?.reportType ?? firstAvailable?.type ?? '',
      format: schedule?.format ?? 'PDF',
      frequency: schedule?.frequency ?? 'WEEKLY',
      runHour: String(schedule?.runHour ?? 6),
      dayOfWeek: String(schedule?.dayOfWeek ?? 1),
      dayOfMonth: String(schedule?.dayOfMonth ?? 1),
      departmentId: schedule?.filters?.departmentId ? String(schedule.filters.departmentId) : '',
      recipients: schedule?.recipients.join(', ') ?? '',
      active: schedule?.active ?? true,
    },
  });
  const reportType = useWatch({ control, name: 'reportType' });
  const frequency = useWatch({ control, name: 'frequency' });
  const format = useWatch({ control, name: 'format' });
  const selectedType = types.find((type) => type.type === reportType);
  const formats = selectedType?.formats?.length ? selectedType.formats : (['PDF', 'CSV'] as const);
  const supportsDepartment = selectedType ? selectedType.filterKeys.includes('departmentId') : false;

  useEffect(() => {
    if (selectedType && !formats.includes(format)) setValue('format', formats[0]);
  }, [selectedType, formats, format, setValue]);
  useEffect(() => {
    if (departments.data) setValue('departmentId', schedule?.filters?.departmentId ? String(schedule.filters.departmentId) : '');
  }, [departments.data, schedule, setValue]);

  const onSubmit = handleSubmit((values) => {
    const departmentId = supportsDepartment && values.departmentId ? Number(values.departmentId) : null;
    save.mutate(
      {
        id: schedule?.id,
        body: {
          name: values.name.trim(),
          reportType: values.reportType,
          format: values.format,
          frequency: values.frequency,
          runHour: Number(values.runHour),
          dayOfWeek: values.frequency === 'WEEKLY' ? Number(values.dayOfWeek) : null,
          dayOfMonth: values.frequency === 'MONTHLY' ? Number(values.dayOfMonth) : null,
          filters: departmentId ? { departmentId } : null,
          recipients: splitRecipients(values.recipients),
          active: values.active,
        },
      },
      {
        onSuccess: (saved) => {
          toast.success(editing ? `${saved.name} updated` : `${saved.name} scheduled`);
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    );
  });

  const generalError = save.isError && !Object.keys(errors).length ? errorMessage(save.error) : null;

  return (
    <Modal open onClose={onClose} title={editing ? `Edit ${schedule.name}` : 'New scheduled report'} eyebrow="SCHEDULED REPORTS" size="lg" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate aria-label={editing ? 'Edit scheduled report' : 'New scheduled report'}>
        <TextField label="Name" required error={errors.name?.message} {...register('name')} />
        <div className="form-row">
          <SelectField label="Report type" required error={errors.reportType?.message} hint={selectedType?.description ?? undefined} {...register('reportType')}>
            <option value="">Choose a report</option>
            {types.map((type) => (
              <option key={type.type} value={type.type} disabled={!type.available}>
                {type.label}
                {type.available ? '' : ' — available once its module is installed'}
              </option>
            ))}
          </SelectField>
          <SelectField label="Format" required error={errors.format?.message} {...register('format')}>
            {formats.map((entry) => (
              <option key={entry} value={entry}>
                {entry}
              </option>
            ))}
          </SelectField>
        </div>
        <div className="form-row three">
          <SelectField label="Frequency" required {...register('frequency')}>
            {(['DAILY', 'WEEKLY', 'MONTHLY'] as const).map((entry) => (
              <option key={entry} value={entry}>
                {FREQUENCY_LABELS[entry]}
              </option>
            ))}
          </SelectField>
          <SelectField label="Run at" hint="Organisation time zone" {...register('runHour')}>
            {Array.from({ length: 24 }, (_, index) => (
              <option key={index} value={index}>
                {hour(index)}
              </option>
            ))}
          </SelectField>
          {frequency === 'WEEKLY' && (
            <SelectField label="Day of week" {...register('dayOfWeek')}>
              {WEEKDAYS.map((day, index) => (
                <option key={day} value={index + 1}>
                  {day}
                </option>
              ))}
            </SelectField>
          )}
          {frequency === 'MONTHLY' && (
            <SelectField label="Day of month" hint="1–28" {...register('dayOfMonth')}>
              {Array.from({ length: 28 }, (_, index) => (
                <option key={index} value={index + 1}>
                  {index + 1}
                </option>
              ))}
            </SelectField>
          )}
        </div>
        <p className="m-0 mb-3 text-[10px] text-muted">
          Period covered: daily = the previous day, weekly = the previous 7 days, monthly = the previous calendar month.
        </p>
        {supportsDepartment && (
          <SelectField label="Department" {...register('departmentId')}>
            <option value="">All departments</option>
            {departments.data?.map((department) => (
              <option key={department.id} value={department.id}>
                {department.name}
              </option>
            ))}
          </SelectField>
        )}
        <TextareaField
          label="Recipients"
          rows={2}
          hint="Up to 20 e-mail addresses of active staff or auditor accounts, separated by commas. Delivered only when SMTP is configured."
          error={errors.recipients?.message}
          {...register('recipients')}
        />
        <CheckboxField label="Active" {...register('active')} />
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
            {save.isPending ? 'Saving…' : editing ? 'Save schedule' : 'Create schedule'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function RunStatus({ run }: { run: ReportRunDto | null }) {
  if (!run) return <span className="muted-text">Never run</span>;
  return (
    <span className="inline-flex items-center gap-1.5" title={run.error ?? undefined}>
      <StatusBadge status={run.status} />
      <span className="muted-text">{formatRelative(run.finishedAt ?? run.startedAt)}</span>
    </span>
  );
}

function DownloadButton({ run }: { run: ReportRunDto }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  if (run.status !== 'SUCCEEDED' || !run.fileName) return null;
  return (
    <button
      type="button"
      className="subtle-btn compact"
      disabled={busy}
      aria-label={`Download ${run.fileName}`}
      onClick={async () => {
        setBusy(true);
        try {
          await downloadReportRun(run);
        } catch (error) {
          toast.error(error);
        } finally {
          setBusy(false);
        }
      }}
    >
      <Download size={12} aria-hidden="true" /> {busy ? 'Downloading…' : 'Download'}
    </button>
  );
}

function RunHistoryDrawer({ schedule, onClose }: { schedule: ReportScheduleDto; onClose: () => void }) {
  const runs = useScheduleRuns(schedule.id, 30);
  return (
    <Drawer open onClose={onClose} label="RUN HISTORY" title={schedule.name} subtitle={`${schedule.reportTypeLabel} · ${schedule.format} · ${describeSchedule(schedule)}`} wide>
      {runs.isLoading && <LoadingState rows={4} label="Loading runs…" />}
      {runs.isError && <ErrorState error={runs.error} onRetry={() => void runs.refetch()} />}
      {runs.data && runs.data.length === 0 && <EmptyState title="No runs yet" description="Use “Run now” or wait for the next scheduled run." />}
      {runs.data && runs.data.length > 0 && (
        <ul className="m-0 list-none p-0" aria-label="Report runs">
          {runs.data.map((run) => (
            <li key={run.id} className="list-row flex-wrap sm:flex-nowrap">
              <div>
                <b className="flex flex-wrap items-center gap-1.5">
                  <StatusBadge status={run.status} />
                  {run.periodFrom && run.periodTo ? `${formatDate(run.periodFrom)} – ${formatDate(run.periodTo)}` : 'Period unknown'}
                  <span className="muted-text text-[10px] font-normal">{run.trigger === 'MANUAL' ? 'Run manually' : 'Scheduled'}</span>
                  <DemoBadge demo={run.demo} />
                </b>
                <small>
                  {formatDateTime(run.finishedAt ?? run.startedAt)}
                  {run.fileName ? ` · ${run.fileName}` : ''}
                  {run.sizeBytes ? ` · ${formatBytes(run.sizeBytes)}` : ''} · e-mailed to {formatNumber(run.emailedTo)}
                </small>
                {run.error && <small className="red-text">{run.error}</small>}
              </div>
              <DownloadButton run={run} />
            </li>
          ))}
        </ul>
      )}
    </Drawer>
  );
}

/** Analytics → Scheduled reports: schedules (exporters see all, others their own), run now, history and downloads. */
export function ScheduledReportsTab() {
  const { user, hasRole } = useAuth();
  const toast = useToast();
  const types = useReportTypes();
  const schedules = useReportSchedules();
  const run = useRunSchedule();
  const remove = useDeleteSchedule();
  const [editing, setEditing] = useState<ReportScheduleDto | 'new' | null>(null);
  const [history, setHistory] = useState<ReportScheduleDto | null>(null);
  const [deleting, setDeleting] = useState<ReportScheduleDto | null>(null);
  const canCreate = hasRole(...REPORT_SCHEDULERS);
  const exporter = hasRole(...REPORT_EXPORTERS);
  const admin = hasRole(...ADMINS);
  const isOwner = (schedule: ReportScheduleDto) => schedule.ownerId === user?.id;
  const canEdit = (schedule: ReportScheduleDto) => canCreate && (isOwner(schedule) || admin);
  const canRun = (schedule: ReportScheduleDto) => isOwner(schedule) || exporter;
  const typeList = useMemo(() => types.data ?? [], [types.data]);
  const noAvailableType = types.data !== undefined && !typeList.some((type) => type.available);

  const runNow = (schedule: ReportScheduleDto) =>
    run.mutate(schedule.id, {
      onSuccess: (result) => {
        if (result?.status === 'FAILED') toast.error(`${schedule.name} failed: ${result.error ?? 'the report could not be generated'}`);
        else
          toast.success(`${schedule.name} generated`, {
            action: { label: 'History', onClick: () => setHistory(schedule) },
          });
      },
      onError: (error) => toast.error(error),
    });

  return (
    <>
      <Panel
        title={
          <span className="inline-flex items-center gap-2">
            <CalendarClock size={15} className="gold-text" /> Scheduled reports
          </span>
        }
        subtitle={exporter ? 'All schedules in the organisation' : 'Your schedules'}
        actions={
          canCreate ? (
            <button type="button" className="gold-btn compact" onClick={() => setEditing('new')} disabled={!types.data || noAvailableType}>
              <Plus size={13} aria-hidden="true" /> New schedule
            </button>
          ) : undefined
        }
      >
        {(schedules.isLoading || types.isLoading) && <LoadingState rows={4} label="Loading scheduled reports…" />}
        {schedules.isError && <ErrorState error={schedules.error} onRetry={() => void schedules.refetch()} />}
        {types.isError && <ErrorState error={types.error} onRetry={() => void types.refetch()} compact title="Could not load report types" />}
        {schedules.data && schedules.data.length === 0 && (
          <EmptyState
            title="No scheduled reports"
            description={canCreate ? 'Schedule a PDF or CSV report to be generated and e-mailed automatically.' : 'Report exporters can schedule reports.'}
            icon={<CalendarClock size={20} />}
          />
        )}
        {schedules.data && schedules.data.length > 0 && (
          <DataTable<ReportScheduleDto>
            caption="Scheduled reports"
            rows={schedules.data}
            rowKey={(schedule) => schedule.id}
            columns={[
              {
                key: 'name',
                header: 'Schedule',
                className: 'summary-cell',
                render: (schedule) => (
                  <span className="flex min-w-0 flex-col">
                    <span className="flex items-center gap-1.5 font-semibold">
                      {schedule.name} <DemoBadge demo={schedule.demo} />
                    </span>
                    <span className="text-muted">
                      {schedule.reportTypeLabel} · {schedule.format}
                      {schedule.ownerName ? ` · ${schedule.ownerName}` : ''}
                    </span>
                  </span>
                ),
              },
              { key: 'when', header: 'Runs', render: (schedule) => describeSchedule(schedule) },
              { key: 'next', header: 'Next run', render: (schedule) => (schedule.active && schedule.nextRunAt ? formatDateTime(schedule.nextRunAt) : '—') },
              { key: 'last', header: 'Last run', render: (schedule) => <RunStatus run={schedule.lastRun} /> },
              { key: 'recipients', header: 'Recipients', render: (schedule) => <span title={schedule.recipients.join(', ') || undefined}>{formatNumber(schedule.recipients.length)}</span> },
              { key: 'active', header: 'Status', render: (schedule) => <BooleanBadge value={schedule.active} on="Active" off="Paused" /> },
              {
                key: 'actions',
                header: <span className="sr-only">Actions</span>,
                render: (schedule) => (
                  <span className="flex flex-wrap gap-1">
                    {canRun(schedule) && (
                      <>
                        <button type="button" className="subtle-btn compact" disabled={run.isPending} onClick={() => runNow(schedule)} aria-label={`Run ${schedule.name} now`}>
                          <Play size={12} aria-hidden="true" /> {run.isPending && run.variables === schedule.id ? 'Running…' : 'Run now'}
                        </button>
                        <button type="button" className="icon-btn sm" onClick={() => setHistory(schedule)} aria-label={`Run history of ${schedule.name}`} title="Run history">
                          <History size={13} />
                        </button>
                        {schedule.lastRun && <DownloadButton run={schedule.lastRun} />}
                      </>
                    )}
                    {canEdit(schedule) && (
                      <>
                        <button type="button" className="icon-btn sm" onClick={() => setEditing(schedule)} aria-label={`Edit ${schedule.name}`} title="Edit">
                          <Pencil size={13} />
                        </button>
                        <button type="button" className="icon-btn sm" onClick={() => setDeleting(schedule)} aria-label={`Delete ${schedule.name}`} title="Delete">
                          <Trash2 size={13} />
                        </button>
                      </>
                    )}
                  </span>
                ),
              },
            ]}
          />
        )}
      </Panel>

      {typeList.length > 0 && (
        <Panel title="Report types" subtitle="Types from modules that are not installed yet cannot be scheduled.">
          <ul className="m-0 list-none p-0">
            {typeList.map((type) => (
              <li key={type.type} className="list-row">
                <div>
                  <b>{type.label}</b>
                  <small>
                    {type.description ?? '—'} · {type.formats.join(' / ')}
                  </small>
                </div>
                <BooleanBadge value={type.available} on="Available" off="Not available yet" />
              </li>
            ))}
          </ul>
        </Panel>
      )}

      {editing !== null && <ScheduleEditor schedule={editing === 'new' ? null : editing} types={typeList} onClose={() => setEditing(null)} />}
      {history && <RunHistoryDrawer schedule={history} onClose={() => setHistory(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => {
          setDeleting(null);
          remove.reset();
        }}
        title={`Delete ${deleting?.name ?? 'schedule'}?`}
        message="The schedule and its stored runs are deleted."
        confirmLabel="Delete"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              toast.success(`${deleting.name} deleted`);
              setDeleting(null);
            },
          })
        }
      />
    </>
  );
}
