import { useCallback, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import type { Tone } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import type { LogSeverity, TelemetrySourceKind, TelemetrySourceStatus } from '../../lib/r4Types';

/** Shared bits of the Observability tabs: time window, formatting, badges. */

export const RANGE_OPTIONS = [
  { value: '15m', label: 'Last 15 minutes', minutes: 15 },
  { value: '1h', label: 'Last hour', minutes: 60 },
  { value: '6h', label: 'Last 6 hours', minutes: 360 },
  { value: '24h', label: 'Last 24 hours', minutes: 1440 },
  { value: '7d', label: 'Last 7 days', minutes: 10080 },
] as const;
export type RangeValue = (typeof RANGE_OPTIONS)[number]['value'];

export function rangeMinutes(range: string): number {
  return RANGE_OPTIONS.find((option) => option.value === range)?.minutes ?? 60;
}

export function isRange(value: string | null | undefined): value is RangeValue {
  return RANGE_OPTIONS.some((option) => option.value === value);
}

/**
 * A fixed [from, to] window for a relative range. The end is anchored to the minute when the range is chosen (or
 * "Refresh" is pressed) so query keys stay stable between renders.
 */
export function useTimeWindow(range: string) {
  const [anchor, setAnchor] = useState(() => Date.now());
  const window = useMemo(() => {
    const to = Math.ceil(anchor / 60_000) * 60_000;
    return { from: new Date(to - rangeMinutes(range) * 60_000).toISOString(), to: new Date(to).toISOString() };
  }, [anchor, range]);
  const refresh = useCallback(() => setAnchor(Date.now()), []);
  return { ...window, refresh };
}

export function RangeSelect({ value, onChange, onRefresh, id = 'obs-range' }: { value: string; onChange: (value: RangeValue) => void; onRefresh?: () => void; id?: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <label htmlFor={id} className="sr-only">
        Time range
      </label>
      <select id={id} className="control sm w-auto" value={value} onChange={(event) => onChange(event.target.value as RangeValue)}>
        {RANGE_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {onRefresh && (
        <button type="button" className="icon-btn sm" onClick={onRefresh} aria-label="Refresh to now">
          <RefreshCw size={13} />
        </button>
      )}
    </span>
  );
}

/** Milliseconds as "820 ms" / "1.42 s". */
export function formatMs(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  if (Math.abs(value) >= 1000) return `${(value / 1000).toFixed(2)} s`;
  if (Math.abs(value) >= 10) return `${Math.round(value)} ms`;
  return `${value.toFixed(1)} ms`;
}

/** Metric values: compact but faithful (no rounding of small numbers to 0). */
export function formatValue(value: number | null | undefined, unit?: string | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  const abs = Math.abs(value);
  let text: string;
  if (abs === 0) text = '0';
  else if (abs >= 1_000_000) text = `${(value / 1_000_000).toFixed(2)}M`;
  else if (abs >= 10_000) text = `${(value / 1000).toFixed(1)}k`;
  else if (abs >= 100) text = value.toFixed(0);
  else if (abs >= 1) text = value.toFixed(2);
  else text = value.toPrecision(3);
  return unit && unit !== '1' ? `${text} ${unit}` : text;
}

export function formatRatio(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return `${(value * 100).toFixed(value < 0.1 ? 2 : 1)}%`;
}

export function attributesText(attributes: Record<string, string> | null | undefined, max = 4): string {
  const entries = Object.entries(attributes ?? {});
  if (!entries.length) return '';
  const shown = entries.slice(0, max).map(([key, value]) => `${key}=${value}`);
  return entries.length > max ? `${shown.join(', ')} +${entries.length - max}` : shown.join(', ');
}

export function KeyValueList({ values, label }: { values: Record<string, unknown> | null | undefined; label: string }) {
  const entries = Object.entries(values ?? {});
  if (!entries.length) return <small className="text-muted">No attributes.</small>;
  return (
    <ul className="kv-list" aria-label={label}>
      {entries.map(([key, value]) => (
        <li key={key}>
          <span>{key}</span>
          <span>{typeof value === 'string' ? value : JSON.stringify(value)}</span>
        </li>
      ))}
    </ul>
  );
}

export const LOG_SEVERITIES: LogSeverity[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];
const LOG_TONES: Record<LogSeverity, Tone> = { TRACE: 'muted', DEBUG: 'muted', INFO: 'neutral', WARN: 'warning', ERROR: 'critical', FATAL: 'critical' };

export function LogSeverityBadge({ severity }: { severity: string }) {
  return <ToneBadge value={severity} label={severity} tone={LOG_TONES[severity as LogSeverity] ?? 'neutral'} />;
}

export const SOURCE_KIND_LABELS: Record<TelemetrySourceKind, string> = {
  OTLP: 'OTLP/HTTP (push)',
  PROMETHEUS_API: 'Prometheus HTTP API (pull)',
  GRAFANA: 'Grafana data source',
  CLOUDWATCH: 'AWS CloudWatch',
  AZURE_MONITOR: 'Azure Monitor',
  GCP_MONITORING: 'Google Cloud Monitoring',
};

const SOURCE_STATUS: Record<TelemetrySourceStatus, { label: string; tone: Tone }> = {
  NOT_CONNECTED: { label: 'Not connected', tone: 'muted' },
  CONNECTED: { label: 'Connected', tone: 'success' },
  ERROR: { label: 'Error', tone: 'critical' },
};

export function SourceStatusBadge({ status, lastVerifiedAt }: { status: TelemetrySourceStatus; lastVerifiedAt?: string | null }) {
  const info = SOURCE_STATUS[status] ?? { label: status, tone: 'neutral' as Tone };
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <ToneBadge value={status} label={info.label} tone={info.tone} />
      {lastVerifiedAt && (
        <small className="text-muted" title={formatDateTime(lastVerifiedAt)}>
          verified {formatRelative(lastVerifiedAt)}
        </small>
      )}
    </span>
  );
}

export function countText(value: number | null | undefined, noun: string): string {
  const n = value ?? 0;
  return `${formatNumber(n)} ${noun}${n === 1 ? '' : 's'}`;
}

/** Chart x-axis tick for a window: time of day for ≤ 24 h, else day + time. */
export function timeTick(ms: number, spanMinutes: number): string {
  const date = new Date(ms);
  if (spanMinutes > 1440) return date.toLocaleString(undefined, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
  return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}
