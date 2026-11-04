import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '../../components/PageHeader';
import { IncludeSimulatedToggle } from '../../components/sim/SimulationParts';
import { Tabs } from '../../components/Tabs';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { ALERT_RESPONDERS, TELEMETRY_CONFIG } from '../../lib/constants';
import { AnomaliesTab } from './AnomaliesTab';
import { CorrelationTab } from './CorrelationTab';
import { LogsTab } from './LogsTab';
import { MappingsTab } from './MappingsTab';
import { MetricsExplorer } from './MetricsExplorer';
import { OverviewTab } from './OverviewTab';
import { RetentionTab } from './RetentionTab';
import { ServicesTab } from './ServicesTab';
import { SourcesTab } from './SourcesTab';
import { TracesTab } from './TracesTab';
import './observability.css';

export type ObsTab = 'overview' | 'metrics' | 'logs' | 'traces' | 'services' | 'anomalies' | 'correlation' | 'sources' | 'mappings' | 'retention';
const TABS: ObsTab[] = ['overview', 'metrics', 'logs', 'traces', 'services', 'anomalies', 'correlation', 'sources', 'mappings', 'retention'];
const LABELS: Record<ObsTab, string> = {
  overview: 'Overview',
  metrics: 'Metrics',
  logs: 'Logs',
  traces: 'Traces',
  services: 'Services',
  anomalies: 'Anomalies',
  correlation: 'Correlation',
  sources: 'Sources',
  mappings: 'Mappings',
  retention: 'Retention',
};

/**
 * /observability (AUTONOMOUS OPERATIONS, OBSERVABILITY_READERS): telemetry queries for readers; sources and detector /
 * mapping / retention changes for TELEMETRY_CONFIG; correlation actions for staff. Simulated data is excluded unless the
 * "Include simulated data" switch (`?sim=1`) is on.
 */
export default function ObservabilityPage() {
  const { hasRole } = useAuth();
  const canConfigure = hasRole(...TELEMETRY_CONFIG);
  const canRespond = hasRole(...ALERT_RESPONDERS);
  const allowed = canConfigure ? TABS : TABS.filter((tab) => tab !== 'sources');
  const [tab, setTab] = useTabParam<ObsTab>(allowed, 'overview', ['group', 'trace', 'metric', 'service']);
  const [params, setParams] = useSearchParams();
  const includeSimulated = params.get('sim') === '1';
  const setIncludeSimulated = (next: boolean) =>
    setParams(
      (current) => {
        const updated = new URLSearchParams(current);
        if (next) updated.set('sim', '1');
        else updated.delete('sim');
        updated.delete('page');
        return updated;
      },
      { replace: true },
    );

  return (
    <>
      <PageHeader title="Observability" subtitle="Metrics, logs, traces, anomalies and alert correlation from your telemetry" actions={<IncludeSimulatedToggle value={includeSimulated} onChange={setIncludeSimulated} />} />
      <div className="page-stack">
        <Tabs<ObsTab> label="Observability views" value={tab} onChange={setTab} tabs={TABS.map((id) => ({ id, label: LABELS[id], hidden: !allowed.includes(id) }))} />
        {tab === 'overview' && <OverviewTab includeSimulated={includeSimulated} canConfigure={canConfigure} onOpenTab={setTab} />}
        {tab === 'metrics' && <MetricsExplorer includeSimulated={includeSimulated} />}
        {tab === 'logs' && <LogsTab includeSimulated={includeSimulated} />}
        {tab === 'traces' && <TracesTab includeSimulated={includeSimulated} />}
        {tab === 'services' && <ServicesTab includeSimulated={includeSimulated} />}
        {tab === 'anomalies' && <AnomaliesTab includeSimulated={includeSimulated} canConfigure={canConfigure} />}
        {tab === 'correlation' && <CorrelationTab includeSimulated={includeSimulated} canRespond={canRespond} />}
        {tab === 'sources' && canConfigure && <SourcesTab />}
        {tab === 'mappings' && <MappingsTab includeSimulated={includeSimulated} canConfigure={canConfigure} />}
        {tab === 'retention' && <RetentionTab canConfigure={canConfigure} />}
      </div>
    </>
  );
}
