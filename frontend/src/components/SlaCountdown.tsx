import { useMemo } from 'react';
import { Clock3, PauseCircle } from 'lucide-react';
import { useNow } from '../hooks/useNow';
import { formatCountdown, titleCase } from '../lib/format';
import type { SlaSnapshot } from '../lib/types';

export type SlaTone = 'on-track' | 'at-risk' | 'breached' | 'paused' | 'stopped';

/** Seconds left right now, ticking down from the value the API returned at `baselineAt`. */
export function liveRemaining(sla: Pick<SlaSnapshot, 'remainingSeconds' | 'clockStatus'>, baselineAt: number, now: number): number {
  if (sla.clockStatus !== 'RUNNING') return sla.remainingSeconds;
  return Math.round(sla.remainingSeconds - (now - baselineAt) / 1000);
}

/** Human label + tone for an SLA clock state. */
export function describeSla(
  sla: Pick<SlaSnapshot, 'clockStatus' | 'health' | 'outcome'> | null | undefined,
  remainingSeconds: number,
): { text: string; tone: SlaTone } {
  if (!sla) return { text: '—', tone: 'stopped' };
  if (sla.clockStatus === 'STOPPED') {
    if (sla.outcome === 'MET') return { text: 'Met', tone: 'on-track' };
    if (sla.outcome === 'MISSED') return { text: 'Missed', tone: 'breached' };
    return { text: sla.outcome ? titleCase(sla.outcome) : 'Stopped', tone: 'stopped' };
  }
  if (sla.clockStatus === 'PAUSED') {
    return {
      text: remainingSeconds >= 0 ? `Paused · ${formatCountdown(remainingSeconds)} left` : `Paused · ${formatCountdown(remainingSeconds)} over`,
      tone: 'paused',
    };
  }
  if (remainingSeconds < 0) return { text: `Breached ${formatCountdown(remainingSeconds)} ago`, tone: 'breached' };
  const tone: SlaTone = sla.health === 'BREACHED' ? 'breached' : sla.health === 'AT_RISK' ? 'at-risk' : 'on-track';
  return { text: formatCountdown(remainingSeconds), tone };
}

interface SlaCountdownProps {
  sla: SlaSnapshot | null | undefined;
  size?: 'sm' | 'lg';
  showIcon?: boolean;
}

/** Live SLA resolution countdown; ticks every second, re-based whenever fresh data arrives. */
export function SlaCountdown({ sla, size = 'sm', showIcon = true }: SlaCountdownProps) {
  const now = useNow();
  // Re-base the countdown whenever the API returns a new snapshot.
  const baselineAt = useMemo(() => Date.now(), [sla?.remainingSeconds, sla?.clockStatus, sla?.resolutionDueAt]);
  if (!sla) return <span className="sla-countdown stopped">—</span>;
  const remaining = liveRemaining(sla, baselineAt, now);
  const { text, tone } = describeSla(sla, remaining);
  const title =
    sla.clockStatus === 'PAUSED'
      ? 'SLA clock paused'
      : sla.resolutionDueAt
        ? `Resolution due ${new Date(sla.resolutionDueAt).toLocaleString()}`
        : undefined;
  return (
    <span className={`sla-countdown ${tone} ${size === 'lg' ? 'large' : ''}`} title={title}>
      {showIcon && size === 'sm' && (sla.clockStatus === 'PAUSED' ? <PauseCircle size={12} /> : <Clock3 size={12} />)}
      {text}
    </span>
  );
}
