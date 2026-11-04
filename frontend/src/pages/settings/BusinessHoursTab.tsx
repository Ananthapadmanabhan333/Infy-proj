import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { CalendarDays, CalendarPlus, Save, Trash2 } from 'lucide-react';
import { useAddHoliday, useCalendars, useRemoveHoliday, useUpdateCalendar } from '../../api/sla';
import { Badge } from '../../components/Badge';
import { TextField } from '../../components/FormField';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { applyFieldErrors } from '../../lib/api';
import { WORK_DAYS } from '../../lib/constants';
import { formatDate, titleCase } from '../../lib/format';
import { calendarSchema, holidaySchema, type CalendarValues, type HolidayValues } from '../../lib/schemas';
import type { CalendarDto } from '../../lib/types';

function calendarDefaults(calendar: CalendarDto): CalendarValues {
  return {
    name: calendar.name,
    timezone: calendar.timezone,
    workDays: calendar.workDays,
    startTime: calendar.startTime.slice(0, 5),
    endTime: calendar.endTime.slice(0, 5),
  };
}

function CalendarCard({ calendar }: { calendar: CalendarDto }) {
  const update = useUpdateCalendar();
  const addHoliday = useAddHoliday();
  const removeHoliday = useRemoveHoliday();
  const toast = useToast();
  const form = useForm<CalendarValues>({ resolver: zodResolver(calendarSchema), defaultValues: calendarDefaults(calendar) });
  const holidayForm = useForm<HolidayValues>({ resolver: zodResolver(holidaySchema), defaultValues: { date: '', name: '' } });
  const errors = form.formState.errors;

  useEffect(() => {
    form.reset(calendarDefaults(calendar));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [calendar]);

  const onSave = form.handleSubmit((values) =>
    update.mutate(
      { id: calendar.id, body: values },
      {
        onSuccess: () => toast.success(`${values.name} saved`),
        onError: (error) => {
          if (!applyFieldErrors(error, form.setError)) toast.error(error);
        },
      },
    ),
  );

  const onAddHoliday = holidayForm.handleSubmit((values) =>
    addHoliday.mutate(
      { calendarId: calendar.id, ...values, name: values.name.trim() },
      {
        onSuccess: () => {
          holidayForm.reset();
          toast.success('Holiday added');
        },
        onError: (error) => {
          if (!applyFieldErrors(error, holidayForm.setError)) toast.error(error);
        },
      },
    ),
  );

  const holidays = [...calendar.holidays].sort((a, b) => a.date.localeCompare(b.date));

  return (
    <Panel
      aria-label={calendar.name}
      title={
        <span className="inline-flex items-center gap-2">
          <CalendarDays size={15} className="gold-text" /> {calendar.name}
        </span>
      }
      subtitle={`${calendar.timezone} · ${calendar.startTime.slice(0, 5)}–${calendar.endTime.slice(0, 5)}`}
      actions={calendar.isDefault ? <Badge value="active" label="Default" /> : undefined}
    >
      <form onSubmit={onSave} noValidate>
        <div className="form-row">
          <TextField label="Name" error={errors.name?.message} {...form.register('name')} />
          <TextField label="Timezone (IANA)" placeholder="Asia/Kolkata" error={errors.timezone?.message} {...form.register('timezone')} />
        </div>
        <fieldset className="field border-0 p-0">
          <legend className="mb-1">Work days</legend>
          <div className="flex flex-wrap gap-x-4">
            {WORK_DAYS.map((day) => (
              <label key={day} className="check !my-1.5">
                <input type="checkbox" value={day} {...form.register('workDays')} />
                <span>{titleCase(day).slice(0, 3)}</span>
              </label>
            ))}
          </div>
          {errors.workDays?.message && <span className="field-error">{errors.workDays.message}</span>}
        </fieldset>
        <div className="form-row">
          <TextField label="Start time" type="time" error={errors.startTime?.message} {...form.register('startTime')} />
          <TextField label="End time" type="time" error={errors.endTime?.message} {...form.register('endTime')} />
        </div>
        <div className="flex justify-end">
          <button type="submit" className="gold-btn compact" disabled={update.isPending || !form.formState.isDirty}>
            <Save size={13} /> {update.isPending ? 'Saving…' : 'Save calendar'}
          </button>
        </div>
      </form>
      <div className="form-section-title">Holidays</div>
      {holidays.length === 0 && <p className="text-[10px] text-muted">No holidays configured — SLA clocks run on every work day.</p>}
      {holidays.map((holiday) => (
        <div key={holiday.id} className="list-row">
          <div>
            <b>{holiday.name}</b>
            <small>{formatDate(holiday.date)}</small>
          </div>
          <button
            type="button"
            className="icon-btn sm"
            aria-label={`Remove holiday ${holiday.name}`}
            disabled={removeHoliday.isPending}
            onClick={() =>
              removeHoliday.mutate(
                { calendarId: calendar.id, holidayId: holiday.id },
                { onSuccess: () => toast.success('Holiday removed'), onError: (error) => toast.error(error) },
              )
            }
          >
            <Trash2 size={14} />
          </button>
        </div>
      ))}
      <form onSubmit={onAddHoliday} noValidate className="mt-2 flex flex-wrap items-start gap-2">
        <TextField label="Holiday date" type="date" className="field inline" error={holidayForm.formState.errors.date?.message} {...holidayForm.register('date')} />
        <TextField label="Holiday name" className="field inline flex-1" error={holidayForm.formState.errors.name?.message} {...holidayForm.register('name')} />
        <button type="submit" className="subtle-btn compact mt-[22px]" disabled={addHoliday.isPending}>
          <CalendarPlus size={13} /> Add holiday
        </button>
      </form>
    </Panel>
  );
}

/** Settings → Business Hours (CONFIG_ADMINS): working calendars and holidays used by business-hours SLA clocks. */
export function BusinessHoursTab() {
  const calendars = useCalendars();
  if (calendars.isLoading) return <LoadingState variant="panel" label="Loading calendars…" />;
  if (calendars.isError) return <ErrorState error={calendars.error} onRetry={() => void calendars.refetch()} />;
  if (!calendars.data?.length) return <EmptyState title="No calendars configured" icon={<CalendarDays size={20} />} />;
  return (
    <div className="grid gap-[14px] xl:grid-cols-2">
      {calendars.data.map((calendar) => (
        <CalendarCard key={calendar.id} calendar={calendar} />
      ))}
    </div>
  );
}
