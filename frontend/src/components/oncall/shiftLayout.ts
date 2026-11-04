/**
 * Pure helpers for the on-call shift timeline: wall-clock arithmetic in the schedule's IANA time zone (Intl only, no
 * library) and the layout of computed shifts (`GET /oncall/schedules/{id}/shifts`) onto one row per local day.
 * Days are measured between real local midnights, so a daylight-saving day is 23 or 25 hours long.
 */
import type { ShiftDto } from '../../lib/responseTypes';

export interface ZonedParts {
  year: number;
  month: number; // 1–12
  day: number;
  hour: number;
  minute: number;
  /** ISO weekday: 1 = Monday … 7 = Sunday. */
  weekday: number;
}

const partsFormatters = new Map<string, Intl.DateTimeFormat>();

function partsFormatter(timeZone: string): Intl.DateTimeFormat {
  let formatter = partsFormatters.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      weekday: 'short',
    });
    partsFormatters.set(timeZone, formatter);
  }
  return formatter;
}

const WEEKDAYS: Record<string, number> = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };

/** True when the runtime knows the IANA zone. */
export function isValidTimeZone(timeZone: string): boolean {
  try {
    partsFormatter(timeZone);
    return true;
  } catch {
    return false;
  }
}

/** Wall-clock parts of `instant` in `timeZone`. */
export function zonedParts(instant: Date | number, timeZone: string): ZonedParts {
  const parts = partsFormatter(timeZone).formatToParts(typeof instant === 'number' ? new Date(instant) : instant);
  const get = (type: string) => parts.find((part) => part.type === type)?.value ?? '0';
  return {
    year: Number(get('year')),
    month: Number(get('month')),
    day: Number(get('day')),
    hour: Number(get('hour')) % 24,
    minute: Number(get('minute')),
    weekday: WEEKDAYS[get('weekday')] ?? 1,
  };
}

/** Offset of `timeZone` from UTC at `instant`, in milliseconds (IST → +19 800 000). */
function offsetAt(instant: number, timeZone: string): number {
  const p = partsFormatter(timeZone).formatToParts(new Date(instant));
  const get = (type: string) => Number(p.find((part) => part.type === type)?.value ?? '0');
  const asUtc = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour') % 24, get('minute'), get('second'));
  return asUtc - Math.floor(instant / 1000) * 1000;
}

/** UTC instant (ms) of the local wall-clock time `y-m-d hh:mm` in `timeZone` (gaps resolve forward, overlaps to the first). */
export function zonedTimeToUtc(year: number, month: number, day: number, timeZone: string, hour = 0, minute = 0): number {
  const guess = Date.UTC(year, month - 1, day, hour, minute);
  const firstOffset = offsetAt(guess, timeZone);
  const candidate = guess - firstOffset;
  const secondOffset = offsetAt(candidate, timeZone);
  return secondOffset === firstOffset ? candidate : guess - secondOffset;
}

/** Local calendar date key "YYYY-MM-DD" of `instant` in `timeZone`. */
export function zonedDateKey(instant: Date | number, timeZone: string): string {
  const p = zonedParts(instant, timeZone);
  return `${p.year}-${String(p.month).padStart(2, '0')}-${String(p.day).padStart(2, '0')}`;
}

/** Adds `days` calendar days to a "YYYY-MM-DD" key. */
export function addDaysToKey(key: string, days: number): string {
  const [y, m, d] = key.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1, d + days));
  return date.toISOString().slice(0, 10);
}

/** Local midnight of the date key, as a UTC instant (ms). */
export function startOfDayKey(key: string, timeZone: string): number {
  const [y, m, d] = key.split('-').map(Number);
  return zonedTimeToUtc(y, m, d, timeZone);
}

/** Date key of the Monday that starts the local week containing `instant`. */
export function weekStartKey(instant: Date | number, timeZone: string): string {
  const p = zonedParts(instant, timeZone);
  return addDaysToKey(zonedDateKey(instant, timeZone), -(p.weekday - 1));
}

export interface TimelineSegment {
  key: string;
  shift: ShiftDto;
  /** Position inside the day, 0–100 (%). */
  left: number;
  width: number;
  /** The shift continues from the previous day / into the next day. */
  continuesBefore: boolean;
  continuesAfter: boolean;
}

export interface TimelineDay {
  key: string;
  start: number;
  end: number;
  /** Hours in this local day (23/24/25). */
  hours: number;
  primary: TimelineSegment[];
  secondary: TimelineSegment[];
}

function segmentsFor(shifts: ShiftDto[], dayStart: number, dayEnd: number, dayKey: string): TimelineSegment[] {
  const length = dayEnd - dayStart;
  return shifts.flatMap((shift, index) => {
    const start = Date.parse(shift.start);
    const end = Date.parse(shift.end);
    if (!(end > dayStart && start < dayEnd)) return [];
    const from = Math.max(start, dayStart);
    const to = Math.min(end, dayEnd);
    return [
      {
        key: `${dayKey}-${shift.layer}-${index}`,
        shift,
        left: ((from - dayStart) / length) * 100,
        width: ((to - from) / length) * 100,
        continuesBefore: start < dayStart,
        continuesAfter: end > dayEnd,
      },
    ];
  });
}

/** One row per local day of `[firstDayKey, firstDayKey + days)`, each with the primary and secondary segments of that day. */
export function layoutShifts(primary: ShiftDto[], secondary: ShiftDto[], firstDayKey: string, days: number, timeZone: string): TimelineDay[] {
  return Array.from({ length: days }, (_, index) => {
    const key = addDaysToKey(firstDayKey, index);
    const start = startOfDayKey(key, timeZone);
    const end = startOfDayKey(addDaysToKey(key, 1), timeZone);
    return {
      key,
      start,
      end,
      hours: Math.round((end - start) / 3_600_000),
      primary: segmentsFor(primary, start, end, key),
      secondary: segmentsFor(secondary, start, end, key),
    };
  });
}

/** "09:00" wall-clock time of an instant in the schedule zone. */
export function zonedTime(instant: string | number, timeZone: string): string {
  const p = zonedParts(typeof instant === 'string' ? Date.parse(instant) : instant, timeZone);
  return `${String(p.hour).padStart(2, '0')}:${String(p.minute).padStart(2, '0')}`;
}

/** "Mon 4 Nov, 09:00" in the schedule zone. */
export function formatZoned(instant: string | number | null | undefined, timeZone: string, withDate = true): string {
  if (instant === null || instant === undefined || instant === '') return '—';
  const date = new Date(typeof instant === 'string' ? Date.parse(instant) : instant);
  if (Number.isNaN(date.getTime())) return '—';
  try {
    return new Intl.DateTimeFormat('en-GB', {
      timeZone,
      ...(withDate ? { weekday: 'short', day: 'numeric', month: 'short' } : {}),
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).format(date);
  } catch {
    return date.toISOString();
  }
}

/** "Mon 4 Nov" label of a date key. */
export function formatDayKey(key: string): string {
  const [y, m, d] = key.split('-').map(Number);
  return new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short' }).format(new Date(Date.UTC(y, m - 1, d)));
}

/** "UTC+05:30" offset label of the zone at `instant`. */
export function offsetLabel(timeZone: string, instant: number = Date.now()): string {
  try {
    const minutes = Math.round(offsetAt(instant, timeZone) / 60_000);
    const sign = minutes < 0 ? '−' : '+';
    const abs = Math.abs(minutes);
    return `UTC${sign}${String(Math.floor(abs / 60)).padStart(2, '0')}:${String(abs % 60).padStart(2, '0')}`;
  } catch {
    return '';
  }
}

/** Hours between two ISO instants (one decimal). */
export function hoursBetween(start: string, end: string): number {
  return Math.round(((Date.parse(end) - Date.parse(start)) / 3_600_000) * 10) / 10;
}
