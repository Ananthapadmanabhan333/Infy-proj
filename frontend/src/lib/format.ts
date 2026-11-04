/** "IN_PROGRESS" → "In Progress", "KNOWN_ERROR" → "Known Error". */
export function titleCase(value: string | null | undefined): string {
  if (!value) return '';
  return value
    .replace(/[_-]+/g, ' ')
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .map((word) => (word === 'sla' || word === 'ai' || word === 'kb' || word === 'llm' ? word.toUpperCase() : word.charAt(0).toUpperCase() + word.slice(1)))
    .join(' ');
}

/** CSS badge modifier for an enum/status value: "PENDING_APPROVAL" → "pending-approval". */
export function badgeClass(value: string | null | undefined): string {
  if (!value) return '';
  return value
    .toString()
    .trim()
    .toLowerCase()
    .replace(/[\s_]+/g, '-')
    .replace(/[^a-z0-9-]/g, '');
}

const numberFormatter = new Intl.NumberFormat('en-US');

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return numberFormatter.format(value);
}

export function formatPercent(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${value.toFixed(digits).replace(/\.0+$/, '')}%`;
}

/** Minutes → "28 min", "3 h 05 m", "2 d 4 h". */
export function formatMinutes(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined || Number.isNaN(minutes)) return '—';
  const total = Math.round(minutes);
  if (total < 60) return `${total} min`;
  const hours = Math.floor(total / 60);
  const mins = total % 60;
  if (hours < 24) return mins ? `${hours} h ${String(mins).padStart(2, '0')} m` : `${hours} h`;
  const days = Math.floor(hours / 24);
  const remHours = hours % 24;
  return remHours ? `${days} d ${remHours} h` : `${days} d`;
}

/** Seconds → compact countdown: "2d 04h", "1h 02m", "12m 05s", "45s". Always uses the absolute value. */
export function formatCountdown(totalSeconds: number): string {
  const s = Math.floor(Math.abs(totalSeconds));
  const days = Math.floor(s / 86_400);
  const hours = Math.floor((s % 86_400) / 3600);
  const minutes = Math.floor((s % 3600) / 60);
  const seconds = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  if (days > 0) return `${days}d ${pad(hours)}h`;
  if (hours > 0) return `${hours}h ${pad(minutes)}m`;
  if (minutes > 0) return `${minutes}m ${pad(seconds)}s`;
  return `${seconds}s`;
}

/** Remaining SLA seconds → "1h 02m" or "Breached 12m 05s ago" when negative. */
export function formatSlaRemaining(seconds: number): string {
  if (seconds >= 0) return formatCountdown(seconds);
  return `Breached ${formatCountdown(seconds)} ago`;
}

const dateTimeFormatter = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});
const dateFormatter = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
const shortDateFormatter = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' });
const timeFormatter = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit' });

function toDate(value: string | null | undefined): Date | null {
  if (!value) return null;
  // Plain dates ("2026-11-03") are calendar days: parse them as local midnight.
  const date = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00`) : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDateTime(value: string | null | undefined): string {
  const date = toDate(value);
  return date ? dateTimeFormatter.format(date) : '—';
}

export function formatDate(value: string | null | undefined): string {
  const date = toDate(value);
  return date ? dateFormatter.format(date) : '—';
}

export function formatShortDate(value: string | null | undefined): string {
  const date = toDate(value);
  return date ? shortDateFormatter.format(date) : '—';
}

export function formatTime(value: string | null | undefined): string {
  const date = toDate(value);
  return date ? timeFormatter.format(date) : '—';
}

const relativeFormatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

export function formatRelative(value: string | null | undefined, now: number = Date.now()): string {
  const date = toDate(value);
  if (!date) return '—';
  const diffSeconds = Math.round((date.getTime() - now) / 1000);
  const abs = Math.abs(diffSeconds);
  if (abs < 45) return 'just now';
  if (abs < 3600) return relativeFormatter.format(Math.round(diffSeconds / 60), 'minute');
  if (abs < 86_400) return relativeFormatter.format(Math.round(diffSeconds / 3600), 'hour');
  if (abs < 30 * 86_400) return relativeFormatter.format(Math.round(diffSeconds / 86_400), 'day');
  return formatDate(value);
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function initials(name: string | null | undefined): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return '?';
  const first = parts[0].charAt(0);
  const last = parts.length > 1 ? parts[parts.length - 1].charAt(0) : '';
  return `${first}${last}`.toUpperCase();
}

export function shortName(name: string | null | undefined): string {
  if (!name) return '';
  const parts = name.trim().split(/\s+/);
  if (parts.length < 2) return name;
  return `${parts[0]} ${parts[parts.length - 1].charAt(0)}.`;
}

/** Signed delta label: (12, '%') → "↑ 12%", (-2.1, ' pp') → "↓ 2.1 pp". */
export function formatDelta(value: number | null | undefined, unit: string): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  if (value === 0) return `± 0${unit}`;
  const arrow = value > 0 ? '↑' : '↓';
  const magnitude = Math.abs(value);
  const text = Number.isInteger(magnitude) ? String(magnitude) : magnitude.toFixed(1);
  return `${arrow} ${text}${unit}`;
}

/** "LINEAR_REGRESSION" → "Statistical forecast · Linear Regression". Never labels the forecast as AI. */
export function forecastLabel(method: string | null | undefined): string {
  if (!method) return 'Statistical forecast';
  const pretty = /^[A-Z0-9_]+$/.test(method) ? titleCase(method) : method;
  return /forecast/i.test(pretty) ? pretty : `Statistical forecast · ${pretty}`;
}

export function percentOf(part: number, total: number): number {
  if (!total) return 0;
  return (part / total) * 100;
}

/** Local datetime-input value ("2026-11-03T10:24") ↔ ISO string. */
export function isoToLocalInput(value: string | null | undefined): string {
  const date = toDate(value);
  if (!date) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function localInputToIso(value: string | null | undefined): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export function splitList(value: string): string[] {
  return value
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
}
