import { useMemo, useState } from 'react';
import { ArrowDown, ArrowUp, Plus, X } from 'lucide-react';
import { useEscalationPolicies, useSaveSchedule } from '../../api/oncall';
import { useCalendars } from '../../api/sla';
import { useTeams } from '../../api/teams';
import { useAssignableUsers } from '../../api/users';
import { CheckboxField, SelectField, TextareaField, TextField } from '../../components/FormField';
import { Modal } from '../../components/Modal';
import { isValidTimeZone } from '../../components/oncall/shiftLayout';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { ROTATION_LABELS, ROTATION_TYPES, WEEKDAY_LABELS } from '../../lib/response';
import type { RotationType, ScheduleDetail, ScheduleRequest } from '../../lib/responseTypes';

const COMMON_ZONES = ['Asia/Kolkata', 'UTC', 'Europe/London', 'Europe/Berlin', 'America/New_York', 'America/Chicago', 'America/Los_Angeles', 'Asia/Singapore', 'Asia/Dubai', 'Asia/Tokyo', 'Australia/Sydney'];

function timeZoneOptions(): string[] {
  const supported = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
  try {
    return supported ? supported('timeZone') : COMMON_ZONES;
  } catch {
    return COMMON_ZONES;
  }
}

function todayKey(): string {
  return new Date().toISOString().slice(0, 10);
}

/** Ordered participant list: add from active staff, move up/down, remove. Position = rotation order. */
function ParticipantList({
  label,
  value,
  onChange,
  people,
  error,
  hint,
}: {
  label: string;
  value: number[];
  onChange: (ids: number[]) => void;
  people: { id: number; fullName: string }[];
  error?: string;
  hint?: string;
}) {
  const [pick, setPick] = useState('');
  const name = (id: number) => people.find((person) => person.id === id)?.fullName ?? `User #${id}`;
  const move = (index: number, delta: number) => {
    const next = [...value];
    const [item] = next.splice(index, 1);
    next.splice(index + delta, 0, item);
    onChange(next);
  };
  return (
    <div className="field" data-invalid={error ? 'true' : undefined}>
      <div className="field-label">
        <span>{label}</span>
      </div>
      {value.length > 0 && (
        <ol className="m-0 mt-[7px] grid list-none gap-1 p-0" aria-label={`${label} in rotation order`}>
          {value.map((id, index) => (
            <li key={id} className="list-row !py-1.5">
              <span className="id-cell w-6 shrink-0 text-center">{index + 1}</span>
              <span className="min-w-0 flex-1 truncate text-[12px]">{name(id)}</span>
              <span className="flex shrink-0 gap-1">
                <button type="button" className="icon-btn sm" aria-label={`Move ${name(id)} up`} disabled={index === 0} onClick={() => move(index, -1)}>
                  <ArrowUp size={12} />
                </button>
                <button type="button" className="icon-btn sm" aria-label={`Move ${name(id)} down`} disabled={index === value.length - 1} onClick={() => move(index, 1)}>
                  <ArrowDown size={12} />
                </button>
                <button type="button" className="icon-btn sm" aria-label={`Remove ${name(id)}`} onClick={() => onChange(value.filter((other) => other !== id))}>
                  <X size={12} />
                </button>
              </span>
            </li>
          ))}
        </ol>
      )}
      <div className="mt-2 flex gap-2">
        <select className="control sm min-w-0 flex-1" aria-label={`Add to ${label.toLowerCase()}`} value={pick} onChange={(event) => setPick(event.target.value)}>
          <option value="">Add a person…</option>
          {people
            .filter((person) => !value.includes(person.id))
            .map((person) => (
              <option key={person.id} value={person.id}>
                {person.fullName}
              </option>
            ))}
        </select>
        <button
          type="button"
          className="subtle-btn compact shrink-0"
          disabled={!pick}
          onClick={() => {
            onChange([...value, Number(pick)]);
            setPick('');
          }}
        >
          <Plus size={12} aria-hidden="true" /> Add
        </button>
      </div>
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && (
        <span className="field-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}

/** Create or edit an on-call schedule (on-call editors). Participants are replaced on save. */
export function ScheduleEditor({ detail, onClose, onSaved }: { detail: ScheduleDetail | null; onClose: () => void; onSaved?: (detail: ScheduleDetail) => void }) {
  const schedule = detail?.schedule;
  const save = useSaveSchedule();
  const toast = useToast();
  const teams = useTeams({ active: true });
  const staff = useAssignableUsers(null);
  const policies = useEscalationPolicies();
  const calendars = useCalendars();
  const zones = useMemo(timeZoneOptions, []);

  const [teamId, setTeamId] = useState(schedule ? String(schedule.teamId) : '');
  const [name, setName] = useState(schedule?.name ?? '');
  const [description, setDescription] = useState(detail?.description ?? '');
  const [timeZone, setTimeZone] = useState(schedule?.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC');
  const [rotationType, setRotationType] = useState<RotationType>(schedule?.rotationType ?? 'WEEKLY');
  const [handoffTime, setHandoffTime] = useState(schedule?.handoffTime ?? '09:00');
  const [handoffDay, setHandoffDay] = useState(String(schedule?.handoffDay ?? 1));
  const [shiftHours, setShiftHours] = useState(schedule?.shiftHours ? String(schedule.shiftHours) : '12');
  const [rotationStart, setRotationStart] = useState(schedule?.rotationStart ?? todayKey());
  const [primary, setPrimary] = useState<number[]>(detail?.primary.map((participant) => participant.userId) ?? []);
  const [secondary, setSecondary] = useState<number[]>(detail?.secondary.map((participant) => participant.userId) ?? []);
  const [policyId, setPolicyId] = useState(schedule?.escalationPolicyId ? String(schedule.escalationPolicyId) : '');
  const [calendarId, setCalendarId] = useState(detail?.holidayCalendarId ? String(detail.holidayCalendarId) : '');
  const [coverId, setCoverId] = useState(detail?.holidayCoverUserId ? String(detail.holidayCoverUserId) : '');
  const [active, setActive] = useState(schedule?.active ?? true);

  const people = (staff.data ?? []).map((person) => ({ id: person.id, fullName: person.fullName }));
  // Keep current participants that are not in the assignable list (e.g. another department) nameable.
  detail?.primary.concat(detail.secondary).forEach((participant) => {
    if (!people.some((person) => person.id === participant.userId)) people.push({ id: participant.userId, fullName: participant.userName });
  });
  const errors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field || error.field.startsWith(`${field}[`))?.message;
  const zoneValid = isValidTimeZone(timeZone);
  const blocked = save.isPending || !teamId || name.trim().length < 2 || !zoneValid || !rotationStart || primary.length === 0 || (rotationType === 'CUSTOM' && !shiftHours);

  const submit = () => {
    const body: ScheduleRequest = {
      teamId: Number(teamId),
      name: name.trim(),
      description: description.trim() || null,
      timeZone: timeZone.trim(),
      rotationType,
      handoffTime: handoffTime || null,
      handoffDay: rotationType === 'WEEKLY' ? Number(handoffDay) : null,
      shiftHours: rotationType === 'CUSTOM' ? Number(shiftHours) : null,
      rotationStart,
      primaryUserIds: primary,
      secondaryUserIds: secondary,
      escalationPolicyId: policyId ? Number(policyId) : null,
      holidayCalendarId: calendarId ? Number(calendarId) : null,
      holidayCoverUserId: calendarId && coverId ? Number(coverId) : null,
      active,
    };
    save.mutate(
      { id: schedule?.id, body },
      {
        onSuccess: (saved) => {
          toast.success(schedule ? `${saved.schedule.name} saved` : `${saved.schedule.name} created`);
          onSaved?.(saved);
          onClose();
        },
      },
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={schedule ? `Edit ${schedule.name}` : 'New on-call schedule'}
      eyebrow="ON-CALL"
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={blocked}>
            {save.isPending ? 'Saving…' : schedule ? 'Save schedule' : 'Create schedule'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <SelectField label="Team" required value={teamId} onChange={(event) => setTeamId(event.target.value)} error={fieldError('teamId')}>
          <option value="">{teams.isLoading ? 'Loading teams…' : 'Choose a team…'}</option>
          {(teams.data ?? []).map((team) => (
            <option key={team.id} value={team.id}>
              {team.name}
            </option>
          ))}
        </SelectField>
        <TextField label="Name" required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} error={fieldError('name')} />
      </div>
      <TextareaField label="Description" rows={2} maxLength={1000} value={description} onChange={(event) => setDescription(event.target.value)} />
      <div className="form-row">
        <TextField
          label="Time zone"
          required
          list="oncall-time-zones"
          value={timeZone}
          onChange={(event) => setTimeZone(event.target.value)}
          error={fieldError('timeZone') ?? (zoneValid ? undefined : 'Unknown IANA time zone (e.g. Asia/Kolkata)')}
          hint="Handoffs happen at this zone’s wall-clock time"
        />
        <datalist id="oncall-time-zones">
          {zones.map((zone) => (
            <option key={zone} value={zone} />
          ))}
        </datalist>
        <SelectField label="Rotation" value={rotationType} onChange={(event) => setRotationType(event.target.value as RotationType)}>
          {ROTATION_TYPES.map((type) => (
            <option key={type} value={type}>
              {ROTATION_LABELS[type]}
            </option>
          ))}
        </SelectField>
      </div>
      <div className="form-row three">
        <TextField label="Handoff time" type="time" value={handoffTime} onChange={(event) => setHandoffTime(event.target.value)} error={fieldError('handoffTime')} />
        {rotationType === 'WEEKLY' && (
          <SelectField label="Handoff day" value={handoffDay} onChange={(event) => setHandoffDay(event.target.value)} error={fieldError('handoffDay')}>
            {WEEKDAY_LABELS.map((label, index) => (
              <option key={label} value={index + 1}>
                {label}
              </option>
            ))}
          </SelectField>
        )}
        {rotationType === 'CUSTOM' && (
          <TextField label="Shift length (hours)" required type="number" min={1} max={336} value={shiftHours} onChange={(event) => setShiftHours(event.target.value)} error={fieldError('shiftHours')} />
        )}
        <TextField label="Rotation start" required type="date" value={rotationStart} onChange={(event) => setRotationStart(event.target.value)} error={fieldError('rotationStart')} />
      </div>
      <div className="form-row">
        <ParticipantList label="Primary participants" value={primary} onChange={setPrimary} people={people} error={fieldError('primaryUserIds')} hint="Slot k belongs to participant k mod n, in this order" />
        <ParticipantList
          label="Secondary participants"
          value={secondary}
          onChange={setSecondary}
          people={people}
          error={fieldError('secondaryUserIds')}
          hint="Optional — without secondaries the next available primary is secondary"
        />
      </div>
      <div className="form-row three">
        <SelectField label="Escalation policy" value={policyId} onChange={(event) => setPolicyId(event.target.value)} error={fieldError('escalationPolicyId')}>
          <option value="">None (team-lead fallback)</option>
          {(policies.data ?? []).map((policy) => (
            <option key={policy.id} value={policy.id}>
              {policy.name}
              {policy.active ? '' : ' (inactive)'}
            </option>
          ))}
        </SelectField>
        <SelectField label="Holiday calendar" value={calendarId} onChange={(event) => setCalendarId(event.target.value)} error={fieldError('holidayCalendarId')}>
          <option value="">No holiday calendar</option>
          {(calendars.data ?? []).map((calendar) => (
            <option key={calendar.id} value={calendar.id}>
              {calendar.name} · {calendar.holidays.length} holidays
            </option>
          ))}
        </SelectField>
        <SelectField label="Holiday cover" value={coverId} disabled={!calendarId} onChange={(event) => setCoverId(event.target.value)} error={fieldError('holidayCoverUserId')}>
          <option value="">Rotation continues on holidays</option>
          {people.map((person) => (
            <option key={person.id} value={person.id}>
              {person.fullName}
            </option>
          ))}
        </SelectField>
      </div>
      <CheckboxField label="Active (used for paging and “on call now”)" checked={active} onChange={(event) => setActive(event.target.checked)} />
      {save.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}
