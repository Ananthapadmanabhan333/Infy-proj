import { Activity, AlertOctagon, Database, GitMerge, Plug, Radar } from 'lucide-react';
import { useAnomalies, useCorrelationGroups, useObservabilityOverview } from '../../api/observability';
import { SimulatedBadge } from '../../components/Badge';
import { KpiCard } from '../../components/KpiCard';
import { SeverityBadge } from '../../components/ops/OpsParts';
import { Panel } from '../../components/Panel';
import { SimulatedDataNotice } from '../../components/sim/SimulationParts';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import type { ObsTab } from './ObservabilityPage';
import { formatValue } from './obsShared';

/** Overview: counts from `/observability/overview`, open anomalies and open correlation groups. */
export function OverviewTab({ includeSimulated, canConfigure, onOpenTab }: { includeSimulated: boolean; canConfigure: boolean; onOpenTab: (tab: ObsTab) => void }) {
  const overview = useObservabilityOverview(includeSimulated);
  const anomalies = useAnomalies({ status: 'OPEN', includeSimulated, size: 5 });
  const groups = useCorrelationGroups({ status: 'OPEN', includeSimulated, size: 5 });
  const o = overview.data;
  const noTelemetry = o ? o.series === 0 && o.resources === 0 : false;

  return (
    <>
      {overview.isError && <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />}
      <div className="kpi-grid">
        <KpiCard icon={<Plug />} label="Telemetry sources" value={formatNumber(o?.sources)} loading={overview.isLoading} tone={o && o.sources > 0 && o.connectedSources === 0 ? 'warning' : 'gold'} caption={o ? `${formatNumber(o.connectedSources)} connected` : undefined} />
        <KpiCard icon={<Database />} label="Metric series" value={formatNumber(o?.series)} loading={overview.isLoading} tone="neutral" caption={o ? `${formatNumber(o.resources)} resources · ${formatNumber(o.unmappedResources)} unmapped` : undefined} />
        <KpiCard icon={<Radar />} label="Open anomalies" value={formatNumber(o?.openAnomalies)} loading={overview.isLoading} tone={o?.openAnomalies ? 'critical' : 'success'} caption={o ? `${formatNumber(o.detectors)} enabled detectors` : undefined} />
        <KpiCard icon={<GitMerge />} label="Open correlation groups" value={formatNumber(o?.openCorrelationGroups)} loading={overview.isLoading} tone={o?.openCorrelationGroups ? 'warning' : 'success'} caption={o?.lastDataAt ? `Last data ${formatRelative(o.lastDataAt)}` : o ? 'No telemetry received yet' : undefined} />
      </div>
      {o && <SimulatedDataNotice show={includeSimulated && o.simulatedDataPresent} text="Simulated sample data is included in these figures." />}
      {o && !includeSimulated && o.simulatedDataPresent && (
        <p className="m-0 text-[11px] text-muted">Simulated telemetry is stored but excluded. Switch on “Include simulated data” to see it.</p>
      )}
      {noTelemetry && (
        <Panel>
          <EmptyState
            icon={<Activity size={20} />}
            title="No telemetry received yet"
            description={
              canConfigure
                ? 'Create an OTLP source and point an OpenTelemetry collector or SDK at its endpoints, or add a Prometheus HTTP API source and test it. Figures appear here as soon as real data arrives.'
                : 'Telemetry appears here once an administrator connects a source (OTLP push or Prometheus pull).'
            }
            action={
              canConfigure ? (
                <button type="button" className="gold-btn compact" onClick={() => onOpenTab('sources')}>
                  Set up a source
                </button>
              ) : undefined
            }
          />
        </Panel>
      )}
      <div className="grid items-start gap-[14px] xl:grid-cols-2">
        <Panel title="Open anomalies" subtitle="Newest first" action="View all" onAction={() => onOpenTab('anomalies')}>
          {anomalies.isLoading && <LoadingState rows={3} />}
          {anomalies.isError && <ErrorState error={anomalies.error} onRetry={() => void anomalies.refetch()} compact />}
          {anomalies.data && anomalies.data.content.length === 0 && <EmptyState title="No open anomalies" icon={<AlertOctagon size={18} />} />}
          {anomalies.data && anomalies.data.content.length > 0 && (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Open anomalies">
              {anomalies.data.content.map((anomaly) => (
                <li key={anomaly.id} className="list-row">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <span className="break-all">{anomaly.metricName}</span> <SimulatedBadge simulated={anomaly.simulated} />
                    </b>
                    <small className="block">
                      {anomaly.serviceName ?? anomaly.ciName ?? 'Unmapped'} · observed {formatValue(anomaly.observedValue)} vs expected {formatValue(anomaly.expectedLower)}–{formatValue(anomaly.expectedUpper)} · |z| {anomaly.peakScore.toFixed(1)}
                    </small>
                    <small className="block text-muted" title={formatDateTime(anomaly.startedAt)}>
                      {anomaly.detectorName ?? `Detector #${anomaly.detectorId}`} · since {formatRelative(anomaly.startedAt)}
                    </small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <Panel title="Open correlation groups" subtitle="Alerts and anomalies grouped by time, topology and labels" action="View all" onAction={() => onOpenTab('correlation')}>
          {groups.isLoading && <LoadingState rows={3} />}
          {groups.isError && <ErrorState error={groups.error} onRetry={() => void groups.refetch()} compact />}
          {groups.data && groups.data.content.length === 0 && <EmptyState title="No open correlation groups" icon={<GitMerge size={18} />} />}
          {groups.data && groups.data.content.length > 0 && (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Open correlation groups">
              {groups.data.content.map((group) => (
                <li key={group.id} className="list-row">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      {group.highestSeverity && <SeverityBadge severity={group.highestSeverity} />}
                      <span className="break-words">{group.title}</span>
                      <SimulatedBadge simulated={group.simulated} />
                    </b>
                    <small className="block">
                      {formatNumber(group.memberCount)} members · score {group.score.toFixed(2)}
                      {group.rootCiName ? ` · suggested root ${group.rootCiName}` : ''} · last activity {formatRelative(group.lastActivityAt)}
                    </small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </>
  );
}
