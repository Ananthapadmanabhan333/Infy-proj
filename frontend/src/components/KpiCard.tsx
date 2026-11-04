import type { ReactNode } from 'react';
import { ArrowDown, ArrowUp } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Skeleton } from './States';

/** Icon-tile colour. `red`/`green`/`blue` are Release 1 aliases (blue renders neutral — no blue in the palette). */
export type KpiTone = 'gold' | 'critical' | 'success' | 'warning' | 'neutral' | 'red' | 'green' | 'blue';
export type DeltaTone = 'good' | 'bad' | 'neutral';

export interface KpiCardProps {
  icon: ReactNode;
  label: string;
  value: ReactNode;
  tone?: KpiTone;
  /**
   * Change vs the previous period. A number is formatted with `deltaUnit` and an up/down arrow and
   * coloured using `higherIsBetter`; a string is shown as is. null/undefined/'—' hides the delta.
   */
  delta?: number | string | null;
  /** e.g. '%' or 'pp' (percentage points) or ' min'. */
  deltaUnit?: string;
  /** For numeric deltas: true when an increase is good (SLA compliance), false when bad (incidents, MTTR). */
  higherIsBetter?: boolean;
  /** Explicit colour override for the delta. */
  deltaTone?: DeltaTone;
  /** Tooltip for the delta, e.g. "vs previous 7 days". */
  deltaLabel?: string;
  /** Small caption under the value (e.g. "12 created in 7 days"). */
  caption?: ReactNode;
  /** Makes the whole card a link. */
  to?: string;
  loading?: boolean;
}

/** Picks the delta colour: for "higher is worse" metrics an increase is bad. */
export function deltaTone(value: number | null | undefined, higherIsBetter: boolean): DeltaTone {
  if (value === null || value === undefined || value === 0 || Number.isNaN(value)) return 'neutral';
  const improved = higherIsBetter ? value > 0 : value < 0;
  return improved ? 'good' : 'bad';
}

function formatDeltaNumber(value: number, unit: string): string {
  const magnitude = Math.abs(value);
  const text = Number.isInteger(magnitude) ? String(magnitude) : magnitude.toFixed(1);
  return `${text}${unit}`;
}

const TONE_CLASS: Record<KpiTone, string> = {
  gold: 'gold',
  critical: 'critical',
  success: 'success',
  warning: 'warning',
  neutral: 'neutral',
  red: 'critical',
  green: 'success',
  blue: 'neutral',
};

/** KPI card from the reference: icon tile, label, large value and a coloured delta. */
export function KpiCard({
  icon,
  label,
  value,
  tone = 'gold',
  delta,
  deltaUnit = '%',
  higherIsBetter = true,
  deltaTone: toneOverride,
  deltaLabel,
  caption,
  to,
  loading,
}: KpiCardProps) {
  let deltaNode: ReactNode = null;
  if (typeof delta === 'number' && Number.isFinite(delta)) {
    const toneName = toneOverride ?? deltaTone(delta, higherIsBetter);
    const Arrow = delta > 0 ? ArrowUp : delta < 0 ? ArrowDown : null;
    deltaNode = (
      <span className={`kpi-delta ${toneName}`} title={deltaLabel} data-testid="kpi-delta">
        {Arrow ? <Arrow aria-hidden="true" /> : '± '}
        <span className="sr-only">{delta > 0 ? 'up' : delta < 0 ? 'down' : 'unchanged'} </span>
        {formatDeltaNumber(delta, deltaUnit)}
      </span>
    );
  } else if (typeof delta === 'string' && delta && delta !== '—') {
    deltaNode = (
      <span className={`kpi-delta ${toneOverride ?? 'neutral'}`} title={deltaLabel} data-testid="kpi-delta">
        {delta}
      </span>
    );
  }

  const body = (
    <>
      <div className="kpi-icon" aria-hidden="true">
        {icon}
      </div>
      <div className="kpi-data">
        <small>{label}</small>
        {loading ? (
          <Skeleton height={24} width={90} className="mt-1" />
        ) : (
          <div className="kpi-value-row">
            <b>{value}</b>
            {deltaNode}
          </div>
        )}
        {caption && !loading && <em>{caption}</em>}
      </div>
    </>
  );

  const className = `kpi ${TONE_CLASS[tone]}`;
  if (to) {
    return (
      <Link to={to} className={className} aria-label={`${label}: ${typeof value === 'string' || typeof value === 'number' ? value : ''}`.trim()}>
        {body}
      </Link>
    );
  }
  return <div className={className}>{body}</div>;
}
