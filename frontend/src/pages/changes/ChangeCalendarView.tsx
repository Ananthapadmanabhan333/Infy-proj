import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, CalendarDays, ChevronLeft, ChevronRight, Snowflake } from 'lucide-react';
import { useChangeCalendar } from '../../api/changes';
import { useServices } from '../../api/services';
import { FilterSelect } from '../../components/FilterSelect';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { formatDateTime, formatTime } from '../../lib/format';
import { CHANGE_STATUS_LABELS, localDateKey, RISK_TONES } from '../../lib/itsm';
import type { CalendarEntry, FreezeDto } from '../../lib/itsmTypes';

type Mode = 'month' | 'week';
const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const DAY = 86_400_000;

function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

/** Monday of the week containing `date`. */
function startOfWeek(date: Date): Date {
  const day = startOfDay(date);
  const offset = (day.getDay() + 6) % 7;
  return new Date(day.getFullYear(), day.getMonth(), day.getDate() - offset);
}

export function calendarRange(mode: Mode, anchor: Date): { from: Date; to: Date; days: Date[] } {
  const from = mode === 'week' ? startOfWeek(anchor) : startOfWeek(new Date(anchor.getFullYear(), anchor.getMonth(), 1));
  let count = 7;
  if (mode === 'month') {
    const last = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0);
    const end = new Date(startOfWeek(last).getTime() + 6 * DAY);
    count = Math.round((startOfDay(end).getTime() - from.getTime()) / DAY) + 1;
  }
  const days = Array.from({ length: count }, (_, index) => new Date(from.getFullYear(), from.getMonth(), from.getDate() + index));
  const to = new Date(days[days.length - 1].getFullYear(), days[days.length - 1].getMonth(), days[days.length - 1].getDate() + 1);
  return { from, to, days };
}

function onDay(entry: CalendarEntry, day: Date): boolean {
  if (!entry.start) return false;
  const start = new Date(entry.start).getTime();
  const end = entry.end ? new Date(entry.end).getTime() : start;
  return start < day.getTime() + DAY && end >= day.getTime();
}

function frozenOn(freezes: FreezeDto[], day: Date): FreezeDto[] {
  return freezes.filter((freeze) => freeze.active && new Date(freeze.startsAt).getTime() < day.getTime() + DAY && new Date(freeze.endsAt).getTime() > day.getTime());
}

function EntryChip({ entry }: { entry: CalendarEntry }) {
  const tone = RISK_TONES[entry.risk] ?? 'neutral';
  return (
    <Link
      to={`/changes/${entry.id}`}
      className={`block truncate rounded-[6px] border px-1.5 py-0.5 text-[10px] leading-tight no-underline ${tone === 'critical' ? 'border-[#7a3b38] bg-[#2a1716]' : tone === 'warning' ? 'border-[#7a5d33] bg-[#2a2115]' : 'border-[#3b4a3f] bg-[#162019]'}`}
      title={`${entry.number} ${entry.title} · ${CHANGE_STATUS_LABELS[entry.status]} · ${entry.risk} risk · ${formatDateTime(entry.start)} – ${formatDateTime(entry.end)}${entry.conflictsWith.length ? ` · conflicts with ${entry.conflictsWith.join(', ')}` : ''}${entry.inFreeze ? ' · in a freeze window' : ''}`}
    >
      {entry.conflictsWith.length > 0 && <AlertTriangle size={9} className="mr-0.5 inline text-[#E56D68]" aria-label="Conflict" />}
      {entry.inFreeze && <Snowflake size={9} className="mr-0.5 inline" aria-label="In freeze" />}
      <span className="font-semibold">{entry.number}</span> {entry.title}
    </Link>
  );
}

/** Month/week change calendar (`GET /changes/calendar`): windows, risk colours, conflicts and freeze windows. */
export function ChangeCalendarView() {
  const [mode, setMode] = useState<Mode>('month');
  const [anchor, setAnchor] = useState(() => startOfDay(new Date()));
  const [serviceId, setServiceId] = useState('');
  const services = useServices();
  const range = useMemo(() => calendarRange(mode, anchor), [mode, anchor]);
  const calendar = useChangeCalendar(range.from.toISOString(), range.to.toISOString(), serviceId ? Number(serviceId) : undefined);
  const entries = (calendar.data?.entries ?? []).filter((entry) => entry.start);
  const freezes = calendar.data?.freezeWindows ?? [];
  const todayKey = localDateKey(new Date());

  const move = (direction: number) =>
    setAnchor((current) => (mode === 'week' ? new Date(current.getFullYear(), current.getMonth(), current.getDate() + direction * 7) : new Date(current.getFullYear(), current.getMonth() + direction, 1)));
  const heading =
    mode === 'month'
      ? anchor.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
      : `${range.days[0].toLocaleDateString(undefined, { day: 'numeric', month: 'short' })} – ${range.days[6].toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}`;

  const agenda = range.days
    .map((day) => ({ day, items: entries.filter((entry) => onDay(entry, day)), frozen: frozenOn(freezes, day) }))
    .filter((row) => row.items.length > 0 || row.frozen.length > 0);

  return (
    <Panel aria-label="Change calendar">
      <div className="toolbar">
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className="icon-btn" onClick={() => move(-1)} aria-label={mode === 'month' ? 'Previous month' : 'Previous week'}>
            <ChevronLeft size={16} />
          </button>
          <b className="min-w-[150px] text-center text-[13px]" aria-live="polite">
            {heading}
          </b>
          <button type="button" className="icon-btn" onClick={() => move(1)} aria-label={mode === 'month' ? 'Next month' : 'Next week'}>
            <ChevronRight size={16} />
          </button>
          <button type="button" className="subtle-btn compact" onClick={() => setAnchor(startOfDay(new Date()))}>
            Today
          </button>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="seg-toggle inline-flex rounded-[8px] border border-line p-0.5" role="group" aria-label="Calendar view">
            {(['month', 'week'] as Mode[]).map((value) => (
              <button key={value} type="button" className={`rounded-[6px] px-2.5 py-1 text-[11px] ${mode === value ? 'bg-[#2a241c] text-gold' : ''}`} aria-pressed={mode === value} onClick={() => setMode(value)}>
                {value === 'month' ? 'Month' : 'Week'}
              </button>
            ))}
          </div>
          <FilterSelect label="Service" allLabel="All services" value={serviceId} onChange={setServiceId} options={(services.data ?? []).map((service) => ({ value: String(service.id), label: service.name }))} />
        </div>
      </div>

      {calendar.isLoading && <LoadingState variant="panel" label="Loading the change calendar…" />}
      {calendar.isError && <ErrorState error={calendar.error} onRetry={() => void calendar.refetch()} />}
      {calendar.data && (
        <>
          <div className="mb-2 flex flex-wrap gap-2 text-[10px] text-muted" aria-label="Legend">
            <ToneBadge value="LOW" label="Low risk" tone="success" />
            <ToneBadge value="MEDIUM" label="Medium risk" tone="warning" />
            <ToneBadge value="HIGH" label="High risk" tone="critical" />
            <span className="inline-flex items-center gap-1">
              <Snowflake size={10} aria-hidden="true" /> Freeze window
            </span>
            <span className="inline-flex items-center gap-1">
              <AlertTriangle size={10} className="text-[#E56D68]" aria-hidden="true" /> Conflict
            </span>
          </div>

          <div className="hidden md:block" aria-label="Calendar grid">
            <div className="grid grid-cols-7 gap-1 text-center text-[10px] uppercase text-muted">
              {WEEKDAYS.map((day) => (
                <div key={day}>{day}</div>
              ))}
            </div>
            <div className="mt-1 grid grid-cols-7 gap-1">
              {range.days.map((day) => {
                const key = localDateKey(day);
                const items = entries.filter((entry) => onDay(entry, day));
                const frozen = frozenOn(freezes, day);
                const outside = mode === 'month' && day.getMonth() !== anchor.getMonth();
                return (
                  <div
                    key={key}
                    className={`min-h-[92px] min-w-0 rounded-[8px] border p-1 ${key === todayKey ? 'border-[#b8955e]' : 'border-line'} ${frozen.length ? 'bg-[#1a1d24]' : ''} ${outside ? 'opacity-50' : ''}`}
                    aria-label={`${day.toDateString()}: ${items.length} changes${frozen.length ? `, freeze ${frozen.map((freeze) => freeze.name).join(', ')}` : ''}`}
                  >
                    <div className="mb-1 flex items-center justify-between text-[10px]">
                      <span className={key === todayKey ? 'font-bold text-gold' : 'text-muted'}>{day.getDate()}</span>
                      {frozen.length > 0 && <Snowflake size={10} aria-label={`Freeze: ${frozen.map((freeze) => freeze.name).join(', ')}`} />}
                    </div>
                    <div className="grid gap-0.5">
                      {items.slice(0, mode === 'week' ? 12 : 4).map((entry) => (
                        <EntryChip key={entry.id} entry={entry} />
                      ))}
                      {items.length > (mode === 'week' ? 12 : 4) && <span className="text-[9px] text-muted">+{items.length - (mode === 'week' ? 12 : 4)} more</span>}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          <div className="md:hidden" aria-label="Calendar agenda">
            {agenda.length === 0 ? (
              <EmptyState title="No changes in this period" icon={<CalendarDays size={18} />} />
            ) : (
              <ol className="m-0 grid list-none gap-2 p-0">
                {agenda.map(({ day, items, frozen }) => (
                  <li key={localDateKey(day)}>
                    <b className="text-[11px]">{day.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}</b>
                    {frozen.length > 0 && (
                      <small className="ml-2 inline-flex items-center gap-1 text-muted">
                        <Snowflake size={10} aria-hidden="true" /> {frozen.map((freeze) => freeze.name).join(', ')}
                      </small>
                    )}
                    <div className="mt-1 grid gap-1">
                      {items.map((entry) => (
                        <EntryChip key={entry.id} entry={entry} />
                      ))}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </div>

          {entries.length === 0 && <p className="mt-2 hidden text-[11px] text-muted md:block">No planned changes in this period.</p>}

          {(calendar.data.conflicts.length > 0 || freezes.length > 0) && (
            <div className="mt-3 grid gap-3 lg:grid-cols-2">
              {calendar.data.conflicts.length > 0 && (
                <div>
                  <h3 className="mb-1 text-[11px] uppercase text-gold">Conflicts</h3>
                  <ul className="m-0 grid list-none gap-1 p-0">
                    {calendar.data.conflicts.map((conflict) => (
                      <li key={`${conflict.changeA}-${conflict.changeB}`} className="list-row !py-1.5">
                        <div className="min-w-0">
                          <b className="block text-[11px]">
                            {conflict.changeA} ↔ {conflict.changeB}
                          </b>
                          <small className="block break-words">
                            {[...conflict.services, ...conflict.cis].join(', ')} · {formatDateTime(conflict.overlapStart)} – {formatTime(conflict.overlapEnd)}
                          </small>
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              {freezes.length > 0 && (
                <div>
                  <h3 className="mb-1 text-[11px] uppercase text-gold">Freeze windows</h3>
                  <ul className="m-0 grid list-none gap-1 p-0">
                    {freezes.map((freeze) => (
                      <li key={freeze.id} className="list-row !py-1.5">
                        <div className="min-w-0">
                          <b className="block text-[11px]">{freeze.name}</b>
                          <small className="block break-words">
                            {formatDateTime(freeze.startsAt)} – {formatDateTime(freeze.endsAt)}
                            {freeze.serviceIds.length === 0 ? ' · all services' : ` · ${freeze.serviceIds.length} services`}
                            {freeze.reason ? ` · ${freeze.reason}` : ''}
                          </small>
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </Panel>
  );
}
