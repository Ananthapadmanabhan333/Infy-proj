import { Phone } from 'lucide-react';
import './oncall.css';
import { Avatar } from '../Avatar';
import { ToneBadge } from '../itsm/ItsmParts';
import { SOURCE_HINTS, SOURCE_LABELS, SOURCE_TONES } from '../../lib/response';
import type { AssignmentSource, OnCallPerson, ShiftDto } from '../../lib/responseTypes';
import { formatDayKey, formatZoned, layoutShifts, offsetLabel, zonedDateKey, type TimelineDay } from './shiftLayout';

export function SourceBadge({ source }: { source: AssignmentSource | null | undefined }) {
  if (!source) return null;
  return (
    <span title={SOURCE_HINTS[source]}>
      <ToneBadge value={source} label={SOURCE_LABELS[source]} tone={SOURCE_TONES[source]} />
    </span>
  );
}

/** "Asia/Kolkata (UTC+05:30)". */
export function TimeZoneLabel({ timeZone }: { timeZone: string }) {
  const offset = offsetLabel(timeZone);
  return (
    <span className="whitespace-nowrap" title="Times on this schedule are shown in its time zone">
      {timeZone}
      {offset && <span className="muted-text"> ({offset})</span>}
    </span>
  );
}

/** Who holds a layer: name, source badge, until when (schedule zone), the rotation participant when covered. */
export function OnCallPersonCard({ person, layer, timeZone }: { person: OnCallPerson | null; layer: 'Primary' | 'Secondary'; timeZone: string }) {
  if (!person) {
    return (
      <div className="oncall-person">
        <div>
          <small>{layer}</small>
          <span className="muted-text text-[11px]">Nobody on call</span>
        </div>
      </div>
    );
  }
  const covered = person.scheduledUserId && person.scheduledUserId !== person.userId && person.scheduledUserName;
  return (
    <div className="oncall-person">
      <Avatar name={person.name} size="sm" />
      <div>
        <small>{layer}</small>
        <b className="flex flex-wrap items-center gap-1.5 text-[12px]">
          <span className="break-words">{person.name}</span>
          <SourceBadge source={person.source} />
        </b>
        <small>
          {person.until ? `until ${formatZoned(person.until, timeZone)}` : 'no handoff scheduled'}
          {covered ? ` · covering ${person.scheduledUserName}` : ''}
          {person.holiday ? ' · holiday' : ''}
        </small>
        {person.phone && (
          <small>
            <a href={`tel:${person.phone}`} className="inline-flex items-center gap-1">
              <Phone size={10} aria-hidden="true" /> {person.phone}
            </a>
          </small>
        )}
      </div>
    </div>
  );
}

const TICKS = ['00:00', '06:00', '12:00', '18:00', '24:00'];

function shiftTitle(shift: ShiftDto, timeZone: string): string {
  const who = shift.userName ?? 'Nobody';
  const covered = shift.scheduledUserName && shift.scheduledUserId !== shift.userId ? ` (scheduled: ${shift.scheduledUserName})` : '';
  return `${shift.layer === 'PRIMARY' ? 'Primary' : 'Secondary'}: ${who}${covered} · ${SOURCE_LABELS[shift.source]} · ${formatZoned(shift.start, timeZone)} – ${formatZoned(shift.end, timeZone)}`;
}

function Lane({ day, layer, timeZone }: { day: TimelineDay; layer: 'primary' | 'secondary'; timeZone: string }) {
  const segments = day[layer];
  return (
    <div className={`shift-lane ${layer}`} aria-label={`${layer === 'primary' ? 'Primary' : 'Secondary'} on ${formatDayKey(day.key)}`}>
      {segments.map((segment) => (
        <span
          key={segment.key}
          className="shift-bar"
          data-source={segment.shift.source}
          data-before={segment.continuesBefore}
          data-after={segment.continuesAfter}
          style={{ left: `${segment.left}%`, width: `${Math.max(segment.width, 0.6)}%` }}
          title={shiftTitle(segment.shift, timeZone)}
          data-testid="shift-bar"
        >
          {segment.width > 7 ? (segment.shift.userName ?? '—').split(' ')[0] : ''}
        </span>
      ))}
    </div>
  );
}

/**
 * Week (or any span of days) of computed shifts, one row per local day of the schedule's time zone: a primary lane and a
 * thinner secondary lane, bars coloured by assignment source, the current time as a red marker. Bars carry full details
 * in their tooltip; an accessible list of the same shifts is rendered by `ShiftList`.
 */
export function ShiftTimeline({
  primary,
  secondary,
  firstDayKey,
  days = 7,
  timeZone,
  now = Date.now(),
}: {
  primary: ShiftDto[];
  secondary: ShiftDto[];
  firstDayKey: string;
  days?: number;
  timeZone: string;
  now?: number;
}) {
  const rows = layoutShifts(primary, secondary, firstDayKey, days, timeZone);
  const today = zonedDateKey(now, timeZone);
  return (
    <div className="shift-timeline" role="group" aria-label={`Shift timeline in ${timeZone}`}>
      <div className="shift-axis" aria-hidden="true">
        <span />
        <div className="shift-axis-ticks">
          {TICKS.map((tick, index) => (
            <span key={tick} style={{ left: `${index * 25}%` }}>
              {tick}
            </span>
          ))}
        </div>
      </div>
      {rows.map((day) => (
        <div key={day.key} className="shift-day" data-today={day.key === today} data-testid="shift-day">
          <div className="shift-day-label">
            <b>{formatDayKey(day.key)}</b>
            {day.hours !== 24 && <span title="Daylight-saving change">{day.hours} h day</span>}
          </div>
          <div className="shift-lanes">
            <Lane day={day} layer="primary" timeZone={timeZone} />
            <Lane day={day} layer="secondary" timeZone={timeZone} />
            {now >= day.start && now < day.end && <span className="shift-now" style={{ left: `${((now - day.start) / (day.end - day.start)) * 100}%` }} aria-label="Now" />}
          </div>
        </div>
      ))}
      <div className="shift-legend">
        {(['ROTATION', 'OVERRIDE', 'HOLIDAY_COVER', 'AVAILABILITY'] as AssignmentSource[]).map((source) => (
          <span key={source}>
            <i data-source={source} /> {SOURCE_LABELS[source]}
          </span>
        ))}
        <span>Upper lane primary · lower lane secondary</span>
      </div>
    </div>
  );
}

/** Plain list of the same shifts (screen readers, small screens, exact times). */
export function ShiftList({ shifts, timeZone }: { shifts: ShiftDto[]; timeZone: string }) {
  if (!shifts.length) return <p className="m-0 text-[11px] text-muted">No shifts in this period.</p>;
  return (
    <ul className="m-0 grid list-none gap-1 p-0" aria-label="Shifts">
      {shifts.map((shift, index) => (
        <li key={`${shift.layer}-${shift.start}-${index}`} className="list-row flex-wrap sm:flex-nowrap">
          <div className="min-w-0">
            <b className="flex flex-wrap items-center gap-1.5">
              <span className="break-words">{shift.userName ?? 'Nobody on call'}</span>
              <SourceBadge source={shift.source} />
              {shift.holiday && <ToneBadge value="holiday" label="Holiday" tone="warning" />}
            </b>
            <small className="block">
              {shift.layer === 'PRIMARY' ? 'Primary' : 'Secondary'} · {formatZoned(shift.start, timeZone)} – {formatZoned(shift.end, timeZone)}
              {shift.scheduledUserName && shift.scheduledUserId !== shift.userId ? ` · scheduled ${shift.scheduledUserName}` : ''}
            </small>
          </div>
        </li>
      ))}
    </ul>
  );
}
