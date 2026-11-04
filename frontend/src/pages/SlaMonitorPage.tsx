import { AlertTriangle, Clock3, RefreshCw, ShieldCheck, Timer } from 'lucide-react';
import { useEvaluateSla, useSlaOverview } from '../api/sla';
import { ComplianceMeter } from '../components/analytics/ComplianceMeter';
import { PriorityBadge } from '../components/Badge';
import { PeriodSelector } from '../components/Controls';
import { KpiCard } from '../components/KpiCard';
import { PageHeader } from '../components/PageHeader';
import { Panel } from '../components/Panel';
import { BreachesByDepartment, BreachesByService } from '../components/sla/Breaches';
import { slaKpis } from '../components/sla/kpis';
import { SlaComplianceTrend } from '../components/sla/SlaComplianceTrend';
import { SlaLiveQueue } from '../components/sla/SlaLiveQueue';
import '../components/sla/sla.css';
import { ErrorState, LoadingState } from '../components/States';
import { useToast } from '../components/Toast';
import { useAuth } from '../hooks/useAuth';
import { usePeriod } from '../hooks/useUrlState';
import { CONFIG_ADMINS } from '../lib/constants';
import { formatMinutes, formatNumber, formatPercent, titleCase } from '../lib/format';
import type { SlaOverview } from '../lib/types';

function OverviewPanels({ overview }: { overview: SlaOverview }) {
  const kpis = slaKpis(overview);
  const period = `last ${overview.days} days`;
  return (
    <>
      <div className="kpi-grid !mb-0">
        <KpiCard
          icon={<ShieldCheck />}
          label="SLA Compliance"
          value={formatPercent(kpis.compliance)}
          caption={`${formatNumber(overview.metCount)} met / ${formatNumber(overview.missedCount)} missed · response ${formatPercent(overview.responseCompliancePercent)}`}
          tone="gold"
        />
        <KpiCard
          icon={<AlertTriangle />}
          label="Breaches"
          value={formatNumber(kpis.breachesInPeriod)}
          caption={`In the ${period} · ${formatNumber(overview.breachedActiveCount)} open past target`}
          tone="critical"
        />
        <KpiCard
          icon={<Timer />}
          label="At Risk"
          value={formatNumber(kpis.atRiskNow)}
          caption={`Now · ${formatNumber(overview.onTrackCount)} on track of ${formatNumber(overview.activeCount)} active`}
          tone="warning"
        />
        <KpiCard
          icon={<Clock3 />}
          label="Avg. Resolution"
          value={formatMinutes(kpis.avgResolutionMinutes)}
          caption={`SLA clock time of completed incidents · ${period}`}
          tone="gold"
        />
      </div>

      <div className="sla-row">
        <Panel title="SLA Compliance Trend" subtitle={`Resolution SLAs met per day · ${period}`}>
          <SlaComplianceTrend trend={overview.trend} />
        </Panel>
        <Panel title="Breaches by Service" subtitle={`Missed in the ${period} + open past target`}>
          <BreachesByService rows={overview.byService} />
        </Panel>
      </div>

      <div className="sla-row even">
        <Panel title="Breaches by Department" subtitle={`Owning team · ${period}; at risk = now`}>
          <BreachesByDepartment rows={overview.byDepartment ?? []} />
        </Panel>
        <Panel title="Compliance by Priority" subtitle="Met and missed in the period, active clocks now">
          <div className="table-scroll">
            <table className="static-table data-table">
              <caption className="sr-only">SLA compliance by priority</caption>
              <thead>
                <tr>
                  <th scope="col">Priority</th>
                  <th scope="col">Met</th>
                  <th scope="col">Missed</th>
                  <th scope="col">Active</th>
                  <th scope="col">Compliance</th>
                </tr>
              </thead>
              <tbody>
                {overview.byPriority.map((row) => (
                  <tr key={row.priority}>
                    <td>
                      <PriorityBadge priority={row.priority} />
                    </td>
                    <td>{formatNumber(row.met)}</td>
                    <td className={row.missed ? 'red-text' : ''}>{formatNumber(row.missed)}</td>
                    <td>{formatNumber(row.active)}</td>
                    <td className="min-w-[140px]">
                      <ComplianceMeter value={row.compliance} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      </div>
    </>
  );
}

export default function SlaMonitorPage() {
  const { hasRole } = useAuth();
  const [days, setDays] = usePeriod(30);
  const overview = useSlaOverview(days);
  const evaluate = useEvaluateSla();
  const toast = useToast();
  const data = overview.data;

  const runEvaluation = () =>
    evaluate.mutate(undefined, {
      onSuccess: (result) => {
        const counters = Object.entries(result ?? {})
          .filter(([, value]) => typeof value === 'number')
          .map(([key, value]) => `${titleCase(key.replace(/([a-z])([A-Z])/g, '$1_$2'))}: ${value}`);
        toast.success(counters.length ? `SLA evaluation complete — ${counters.join(' · ')}` : 'SLA evaluation complete');
      },
      onError: (error) => toast.error(error),
    });

  return (
    <>
      <PageHeader
        title="SLA Monitoring"
        subtitle="Track SLA compliance, breaches and escalations"
        actions={
          <>
            {hasRole(...CONFIG_ADMINS) && (
              <button type="button" className="date-btn" onClick={runEvaluation} disabled={evaluate.isPending}>
                <RefreshCw size={15} className={evaluate.isPending ? 'animate-spin' : ''} aria-hidden="true" /> Evaluate now
              </button>
            )}
            <PeriodSelector value={days} onChange={setDays} />
          </>
        }
      />
      <div className="page-stack">
        {overview.isLoading && (
          <>
            <LoadingState variant="kpis" label="Loading SLA overview…" />
            <LoadingState variant="panel" />
          </>
        )}
        {overview.isError && !data && <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />}
        {data && (
          <div className={`page-stack transition-opacity ${overview.isPlaceholderData ? 'opacity-60' : ''}`} aria-busy={overview.isPlaceholderData}>
            <OverviewPanels overview={data} />
          </div>
        )}
        <SlaLiveQueue
          counts={
            data ? { AT_RISK: data.atRiskCount, BREACHED: data.breachedActiveCount, ON_TRACK: data.onTrackCount, PAUSED: data.pausedCount } : undefined
          }
        />
      </div>
    </>
  );
}
