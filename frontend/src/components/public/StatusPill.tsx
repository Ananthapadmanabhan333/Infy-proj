import { useHealth } from '../../api/health';

export type PlatformStatus = 'checking' | 'operational' | 'degraded' | 'unavailable';

const LABELS: Record<PlatformStatus, string> = {
  checking: 'Checking status',
  operational: 'System Operational',
  degraded: 'System Degraded',
  unavailable: 'System Unavailable',
};

const DESCRIPTIONS: Record<PlatformStatus, string> = {
  checking: 'Contacting the SA API health endpoint',
  operational: 'The API and its database answered the health check',
  degraded: 'The API answered, but its database check failed',
  unavailable: 'The API health check could not be reached — retrying every 30 seconds',
};

/** Maps the public `/api/health` query to a pill state — never assumes "operational". */
export function platformStatus(health: { isPending: boolean; isError: boolean; data?: Record<string, unknown> }): PlatformStatus {
  if (health.isError) return 'unavailable';
  if (health.isPending || !health.data) return 'checking';
  return String(health.data.status ?? '').toUpperCase() === 'UP' ? 'operational' : 'degraded';
}

/** Live system status from `/api/health` (spec §6). */
export function StatusPill({ className = '' }: { className?: string }) {
  const health = useHealth();
  const status = platformStatus(health);
  return (
    <span className={`pub-status ${status} ${className}`.trim()} role="status" aria-live="polite" title={DESCRIPTIONS[status]} data-status={status}>
      <span className="pub-status-dot" aria-hidden="true" />
      <span>{LABELS[status]}</span>
    </span>
  );
}
