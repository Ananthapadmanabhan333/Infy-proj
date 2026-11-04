import type { ReactNode } from 'react';
import { Activity, AlertTriangle, Bot, CalendarClock, Database, HardDrive, HeartPulse, Mail, RefreshCw, Server, Timer, Users, Workflow, type LucideIcon } from 'lucide-react';
import { useSystemHealth } from '../../api/admin';
import { Badge } from '../../components/Badge';
import { KpiCard } from '../../components/KpiCard';
import { ErrorState, LoadingState } from '../../components/States';
import { formatDateTime, formatNumber, formatRelative, formatTime, titleCase } from '../../lib/format';
import type { HealthState } from '../../lib/types';
import '../../components/admin/admin.css';

type Tone = 'success' | 'warning' | 'critical' | 'neutral' | '';

export function formatUptime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '—';
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days) return `${days} d ${hours} h`;
  if (hours) return `${hours} h ${minutes} m`;
  return `${minutes} m`;
}

/** Tile tone for a component state. */
export function stateTone(state: HealthState | null | undefined): Tone {
  const value = (state ?? '').toUpperCase();
  if (value === 'UP' || value === 'OK' || value === 'HEALTHY') return 'success';
  if (value === 'DEGRADED' || value === 'WARNING' || value === 'UNKNOWN') return 'warning';
  if (value === 'DOWN' || value === 'ERROR' || value === 'FAILED') return 'critical';
  return 'neutral';
}

function StateBadge({ state, label }: { state: HealthState | null | undefined; label?: string }) {
  const tone = stateTone(state);
  return <Badge value={state ?? 'unknown'} tone={tone === '' ? undefined : tone} label={label ?? titleCase(state ?? 'Unknown')} />;
}

function Tile({ icon: Icon, title, caption, tone, badge, rows }: { icon: LucideIcon; title: string; caption: string; tone: Tone; badge: ReactNode; rows: [string, ReactNode][] }) {
  return (
    <section className="panel health-tile" aria-label={title}>
      <div className="tile-head">
        <span className={`icon-tile ${tone}`} aria-hidden="true">
          <Icon size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <h3>{title}</h3>
          <small>{caption}</small>
        </div>
        {badge}
      </div>
      <dl>
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

/** Settings → System Health (HEALTH_READERS): live component status, refreshed every 30 seconds. */
export function SystemHealthTab() {
  const health = useSystemHealth();
  if (health.isLoading) return <LoadingState variant="kpis" label="Checking system health…" />;
  if (health.isError && !health.data) return <ErrorState error={health.error} onRetry={() => void health.refetch()} />;
  if (!health.data) return null;
  const data = health.data;
  const overall = stateTone(data.status);
  const workflowTone: Tone = data.workflowEngine.failedLast24h > 0 ? 'warning' : data.workflowEngine.pendingRetries > 0 ? 'neutral' : 'success';

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-2 text-[10px] text-muted">
        <span className="inline-flex items-center gap-2">
          <span className={`live-dot ${health.isError ? '' : 'on'}`}>{health.isError ? 'Last refresh failed' : 'Live'}</span>
          Updated {formatTime(new Date(health.dataUpdatedAt).toISOString())} · refreshes every 30 s
        </span>
        <button type="button" className="subtle-btn compact" onClick={() => void health.refetch()} disabled={health.isFetching}>
          <RefreshCw size={13} className={health.isFetching ? 'animate-spin' : ''} /> {health.isFetching ? 'Refreshing…' : 'Refresh now'}
        </button>
      </div>
      <div className="kpi-grid">
        <KpiCard
          icon={<HeartPulse />}
          label="Overall status"
          value={titleCase(data.status)}
          tone={overall === 'success' ? 'success' : overall === 'critical' ? 'critical' : 'warning'}
          caption={`Version ${data.version}`}
        />
        <KpiCard icon={<Activity />} label="Uptime" value={formatUptime(data.uptimeSeconds)} tone="gold" caption="Since the API last started" />
        <KpiCard icon={<Users />} label="Users" value={formatNumber(data.counts.users)} tone="neutral" caption="Accounts in the directory" />
        <KpiCard
          icon={<AlertTriangle />}
          label="Active incidents"
          value={formatNumber(data.counts.activeIncidents)}
          tone={data.counts.activeIncidents ? 'warning' : 'success'}
          caption={`${formatNumber(data.counts.incidents)} incidents in total`}
        />
      </div>
      <div className="grid gap-[14px] md:grid-cols-2 xl:grid-cols-3">
        <Tile
          icon={Server}
          title="API"
          caption="SA application server"
          tone={overall}
          badge={<StateBadge state={data.status} />}
          rows={[
            ['Version', data.version],
            ['Uptime', formatUptime(data.uptimeSeconds)],
          ]}
        />
        <Tile
          icon={Database}
          title="Database"
          caption="PostgreSQL"
          tone={stateTone(data.database.status)}
          badge={<StateBadge state={data.database.status} />}
          rows={[
            ['Latency', data.database.latencyMs !== null ? `${formatNumber(data.database.latencyMs)} ms` : '—'],
            ['Schema version', data.database.schemaVersion ?? 'Unknown'],
          ]}
        />
        <Tile
          icon={HardDrive}
          title="Redis"
          caption={data.redis.enabled ? 'Rate limiting and caching' : 'Not enabled — in-memory fallback'}
          tone={data.redis.enabled ? stateTone(data.redis.status) : 'neutral'}
          badge={data.redis.enabled ? <StateBadge state={data.redis.status} /> : <Badge value="disabled" label="Disabled" />}
          rows={[
            ['Enabled', data.redis.enabled ? 'Yes' : 'No'],
            ['Status', data.redis.enabled ? titleCase(data.redis.status) : 'Not used'],
          ]}
        />
        <Tile
          icon={Timer}
          title="SLA scheduler"
          caption="Evaluates SLA clocks and escalations"
          tone={data.slaScheduler.enabled ? 'success' : 'warning'}
          badge={<Badge value={data.slaScheduler.enabled ? 'running' : 'disabled'} label={data.slaScheduler.enabled ? 'Running' : 'Disabled'} />}
          rows={[
            ['Last run', data.slaScheduler.lastRunAt ? formatRelative(data.slaScheduler.lastRunAt) : 'Not yet run'],
            ['At', formatDateTime(data.slaScheduler.lastRunAt)],
          ]}
        />
        <Tile
          icon={Workflow}
          title="Workflow engine"
          caption="Automation executions"
          tone={workflowTone}
          badge={
            <Badge
              value={data.workflowEngine.failedLast24h ? 'failed' : 'succeeded'}
              label={data.workflowEngine.failedLast24h ? `${formatNumber(data.workflowEngine.failedLast24h)} failed` : 'Healthy'}
            />
          }
          rows={[
            ['Pending retries', formatNumber(data.workflowEngine.pendingRetries)],
            ['Failed (24 h)', formatNumber(data.workflowEngine.failedLast24h)],
          ]}
        />
        <Tile
          icon={Bot}
          title="AI provider"
          caption={titleCase(data.ai.provider) || 'Not configured'}
          tone={data.ai.keyConfigured ? 'success' : 'neutral'}
          badge={<Badge value={data.ai.keyConfigured ? 'active' : 'inactive'} label={data.ai.keyConfigured ? 'Key configured' : 'Fallback only'} />}
          rows={[
            ['Provider', titleCase(data.ai.provider) || '—'],
            ['API key', data.ai.keyConfigured ? 'Configured on the server' : 'Not configured'],
          ]}
        />
        {data.email && (
          <Tile
            icon={Mail}
            title="E-mail"
            caption={data.email.configured ? 'SMTP outbox delivery' : 'Not configured — messages are stored as skipped'}
            tone={!data.email.configured ? 'warning' : data.email.failedLast24h ? 'warning' : 'success'}
            badge={<Badge value={data.email.configured ? 'active' : 'inactive'} label={data.email.configured ? 'Configured' : 'Not configured'} />}
            rows={[
              ['Pending', formatNumber(data.email.pending)],
              ['Failed (24 h)', formatNumber(data.email.failedLast24h)],
              ['Last sent', data.email.lastSentAt ? formatRelative(data.email.lastSentAt) : 'Never'],
            ]}
          />
        )}
        {data.reportScheduler && (
          <Tile
            icon={CalendarClock}
            title="Report scheduler"
            caption="Runs scheduled reports"
            tone={data.reportScheduler.enabled ? 'success' : 'warning'}
            badge={<Badge value={data.reportScheduler.enabled ? 'running' : 'disabled'} label={data.reportScheduler.enabled ? 'Running' : 'Disabled'} />}
            rows={[
              ['Last run', data.reportScheduler.lastRunAt ? formatRelative(data.reportScheduler.lastRunAt) : 'Not yet run'],
              ['At', formatDateTime(data.reportScheduler.lastRunAt)],
            ]}
          />
        )}
      </div>
    </>
  );
}
