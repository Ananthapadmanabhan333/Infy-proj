import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { BellRing, BookOpenCheck, GitPullRequestArrow, PhoneCall, Radar, Repeat2, Timer, Wrench } from 'lucide-react';
import { useMetricDefinitions, useOperationsMetrics, useSlaRiskOverview } from '../../api/analytics';
import { axisTick, CHART, tooltipItemStyle, tooltipLabelStyle, tooltipStyle } from '../../lib/chartTheme';
import { formatDate, formatMinutes, formatNumber, formatPercent, formatRelative, formatShortDate, titleCase } from '../../lib/format';
import { ASSET_STATUS_LABELS } from '../../lib/ops';
import { RISK_LEVEL_TONES } from '../../lib/response';
import type { DepartmentPerformance, MetricDefinition, OnCallSchedulePerformance, OperationsMetrics, ServiceAvailabilityRow, SlaRisk } from '../../lib/responseTypes';
import { Link } from 'react-router-dom';
import { PriorityBadge } from '../Badge';
import { DataTable } from '../DataTable';
import { ToneBadge } from '../itsm/ItsmParts';
import { KpiCard } from '../KpiCard';
import '../oncall/oncall.css';
import { Panel } from '../Panel';
import { EmptyState, ErrorState, LoadingState } from '../States';
import type { AnalyticsFilterState } from './useAnalyticsFilters';
import type { Priority } from '../../lib/types';

function Breakdown({ values, labels, label }: { values: Record<string, number>; labels?: Record<string, string>; label: string }) {
  const entries = Object.entries(values ?? {});
  const total = entries.reduce((sum, [, count]) => sum + count, 0);
  if (!entries.length) return <p className="m-0 text-[11px] text-muted">No data.</p>;
  return (
    <ul className="m-0 grid list-none gap-1.5 p-0" aria-label={label}>
      {entries.map(([key, count]) => (
        <li key={key} className="grid grid-cols-[minmax(0,110px)_minmax(0,1fr)_auto] items-center gap-2 text-[11px]">
          <span className="truncate">{labels?.[key] ?? titleCase(key)}</span>
          <span className="risk-meter" aria-hidden="true">
            <span style={{ width: `${total ? (count / total) * 100 : 0}%`, background: 'var(--gold)' }} />
          </span>
          <b className="tabular-nums">{formatNumber(count)}</b>
        </li>
      ))}
    </ul>
  );
}

export function DefinitionsPanel() {
  const definitions = useMetricDefinitions();
  return (
    <Panel title="How metrics are calculated" subtitle="Served by the API from the same code that computes the numbers">
      {definitions.isLoading && <LoadingState rows={3} />}
      {definitions.isError && <ErrorState error={definitions.error} onRetry={() => void definitions.refetch()} compact />}
      {definitions.data && definitions.data.length === 0 && <EmptyState title="No metric definitions" />}
      {definitions.data && definitions.data.length > 0 && (
        <div className="grid gap-1.5">
          {definitions.data.map((definition: MetricDefinition) => (
            <details key={definition.key} className="list-row !block">
              <summary className="cursor-pointer text-[12px] font-semibold">
                {definition.name}
                {definition.unit && <span className="font-normal text-muted"> · {definition.unit}</span>}
              </summary>
              <p className="m-0 mt-1.5 text-[11px]">{definition.formula}</p>
              {definition.sources.length > 0 && <p className="m-0 mt-1 text-[10px] text-muted">Sources: {definition.sources.join(', ')}</p>}
              {definition.filters.length > 0 && <p className="m-0 text-[10px] text-muted">Filters: {definition.filters.join(', ')}</p>}
              {definition.notes && <p className="m-0 mt-1 text-[10px] text-muted">{definition.notes}</p>}
            </details>
          ))}
        </div>
      )}
    </Panel>
  );
}

function MetricsBody({ data }: { data: OperationsMetrics }) {
  const perDay = data.alertVolume.perDay.map((point) => ({ ...point, label: formatShortDate(point.date) }));
  return (
    <>
      <p className="m-0 text-[10px] text-muted">
        {formatDate(data.from)} – {formatDate(data.to)} · computed {formatRelative(data.generatedAt)}
      </p>
      <div className="kpi-grid !mb-0">
        <KpiCard icon={<Timer />} label="MTTA" value={formatMinutes(data.mtta.minutes)} tone="gold" caption={`Created → first response · ${formatNumber(data.mtta.samples)} incidents`} />
        <KpiCard
          icon={<Radar />}
          label="MTTD"
          value={data.mttd.minutes === null ? 'n/a' : formatMinutes(data.mttd.minutes)}
          tone="neutral"
          caption={`First alert → incident · ${formatNumber(data.mttd.samples)} with an alert, ${formatNumber(data.mttd.incidentsWithoutAlert)} without`}
        />
        <KpiCard
          icon={<PhoneCall />}
          label="First-contact resolution"
          value={formatPercent(data.firstContactResolution.rate, 1)}
          tone="success"
          caption={`${formatNumber(data.firstContactResolution.resolvedAtFirstContact)} of ${formatNumber(data.firstContactResolution.resolved)} resolved`}
        />
        <KpiCard
          icon={<GitPullRequestArrow />}
          label="Change success"
          value={formatPercent(data.changes.successRate, 1)}
          tone={data.changes.failureRate && data.changes.failureRate > 15 ? 'warning' : 'success'}
          caption={`Failure ${formatPercent(data.changes.failureRate, 1)} · ${formatNumber(data.changes.failed)} failed, ${formatNumber(data.changes.rolledBack)} rolled back of ${formatNumber(data.changes.finished)}`}
        />
      </div>
      <div className="kpi-grid !mb-0">
        <KpiCard
          icon={<Repeat2 />}
          label="Problem recurrence"
          value={formatPercent(data.problemRecurrence.rate, 1)}
          tone={data.problemRecurrence.rate ? 'warning' : 'success'}
          caption={`${formatNumber(data.problemRecurrence.problemsWithRecurrence)} of ${formatNumber(data.problemRecurrence.knownErrors)} known errors · ${formatNumber(data.problemRecurrence.recurringIncidents)} incidents`}
        />
        <KpiCard
          icon={<BellRing />}
          label="Alert volume"
          value={formatNumber(data.alertVolume.total)}
          tone="neutral"
          caption={`${formatNumber(data.alertVolume.occurrences)} occurrences · ${formatNumber(data.alertVolume.withIncident)} with incident · ${formatNumber(data.alertVolume.suppressed)} suppressed`}
        />
        <KpiCard
          icon={<BookOpenCheck />}
          label="On-call acknowledgement"
          value={formatPercent(data.onCall.ackRate, 1)}
          tone="gold"
          caption={`Avg ${formatMinutes(data.onCall.avgAckMinutes)} · ${formatNumber(data.onCall.alertsPaged)} alerts paged, ${formatNumber(data.onCall.escalated)} escalated`}
        />
        <KpiCard
          icon={<Wrench />}
          label="Asset lifecycle"
          value={formatNumber(data.assetLifecycle.acquired)}
          tone="neutral"
          caption={`Acquired · ${formatNumber(data.assetLifecycle.retired)} retired, ${formatNumber(data.assetLifecycle.disposed)} disposed · ${formatNumber(data.assetLifecycle.warrantyExpiringNext30Days)} warranties end in 30 days`}
        />
      </div>

      <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
        <Panel title="Alert volume" subtitle="Alerts first seen per day (organisation time zone)">
          {perDay.every((point) => point.count === 0) ? (
            <EmptyState title="No alerts in this period" />
          ) : (
            <div className="chart-wrap medium" role="img" aria-label={`Alerts per day: ${perDay.map((point) => `${point.label} ${point.count}`).join(', ')}`}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={perDay}>
                  <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" vertical={false} />
                  <XAxis dataKey="label" tick={axisTick} axisLine={false} tickLine={false} minTickGap={12} />
                  <YAxis tick={axisTick} axisLine={false} tickLine={false} allowDecimals={false} width={30} />
                  <Tooltip contentStyle={tooltipStyle} labelStyle={tooltipLabelStyle} itemStyle={tooltipItemStyle} cursor={{ fill: 'rgba(214,183,122,0.08)' }} />
                  <Bar dataKey="count" name="Alerts" fill={CHART.gold} radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </Panel>
        <Panel title="Alerts by severity and source">
          <div className="grid gap-3">
            <Breakdown label="Alerts by severity" values={data.alertVolume.bySeverity} />
            <Breakdown label="Alerts by source" values={data.alertVolume.bySource} />
          </div>
        </Panel>
      </div>

      <div className="grid items-start gap-[14px] xl:grid-cols-2">
        <Panel title="On-call performance" subtitle="Per alert whose first page is in the period">
          {data.onCall.bySchedule.length === 0 ? (
            <EmptyState title="No on-call pages in this period" />
          ) : (
            <DataTable<OnCallSchedulePerformance>
              caption="On-call performance by schedule"
              rows={data.onCall.bySchedule}
              rowKey={(row) => row.scheduleId}
              columns={[
                { key: 'schedule', header: 'Schedule', render: (row) => <Link to={`/on-call/${row.scheduleId}`}>{row.scheduleName}</Link> },
                { key: 'paged', header: 'Alerts paged', render: (row) => formatNumber(row.alertsPaged) },
                { key: 'ack', header: 'Ack rate', render: (row) => formatPercent(row.ackRate, 1) },
                { key: 'avg', header: 'Avg ack', render: (row) => formatMinutes(row.avgAckMinutes) },
                { key: 'escalated', header: 'Escalated', render: (row) => formatNumber(row.escalated) },
              ]}
            />
          )}
        </Panel>
        <Panel title="Asset lifecycle" subtitle={`Average hardware age ${data.assetLifecycle.averageAgeDays === null ? '—' : `${formatNumber(Math.round(data.assetLifecycle.averageAgeDays))} days`}`}>
          <Breakdown label="Assets by status" values={data.assetLifecycle.byStatus} labels={ASSET_STATUS_LABELS} />
        </Panel>
      </div>

      <Panel title="Department performance">
        {data.departmentPerformance.length === 0 ? (
          <EmptyState title="No department data in this period" />
        ) : (
          <DataTable<DepartmentPerformance>
            caption="Department performance"
            rows={data.departmentPerformance}
            rowKey={(row) => row.departmentId}
            columns={[
              { key: 'name', header: 'Department', render: (row) => row.name },
              { key: 'created', header: 'Created', render: (row) => formatNumber(row.created) },
              { key: 'resolved', header: 'Resolved', render: (row) => formatNumber(row.resolved) },
              { key: 'open', header: 'Open now', render: (row) => formatNumber(row.open) },
              { key: 'mtta', header: 'MTTA', render: (row) => formatMinutes(row.mttaMinutes) },
              { key: 'mttr', header: 'MTTR', render: (row) => formatMinutes(row.mttrMinutes) },
              { key: 'sla', header: 'SLA', render: (row) => formatPercent(row.slaCompliance, 1) },
              { key: 'fcr', header: 'FCR', render: (row) => formatPercent(row.firstContactResolution, 1) },
            ]}
          />
        )}
      </Panel>

      <Panel title="Service availability" subtitle="100 − (P1 downtime + 25 % of P2 time) ÷ window — least available first">
        {data.serviceAvailability.length === 0 ? (
          <EmptyState title="No services" />
        ) : (
          <DataTable<ServiceAvailabilityRow>
            caption="Service availability"
            rows={data.serviceAvailability}
            rowKey={(row) => row.serviceId}
            columns={[
              { key: 'name', header: 'Service', render: (row) => <Link to={`/services/${row.serviceId}`}>{row.name}</Link> },
              { key: 'tier', header: 'Tier', render: (row) => row.tier },
              {
                key: 'availability',
                header: 'Availability',
                render: (row) => <span className={row.availability !== null && row.availability < 99 ? 'red-text' : row.availability !== null && row.availability < 99.9 ? 'warning-text' : 'green-text'}>{formatPercent(row.availability, 2)}</span>,
              },
              { key: 'outage', header: 'Outage', render: (row) => formatMinutes(row.outageMinutes) },
              { key: 'degraded', header: 'Degraded', render: (row) => formatMinutes(row.degradedMinutes) },
              { key: 'incidents', header: 'Incidents', render: (row) => formatNumber(row.incidents) },
            ]}
          />
        )}
      </Panel>
    </>
  );
}

/** Analytics "Ops metrics" tab — `GET /analytics/operations` with the page's period and department filters. */
export function OpsMetricsTab({ state }: { state: AnalyticsFilterState }) {
  const metrics = useOperationsMetrics(state.filters);
  return (
    <div className="page-stack">
      {metrics.isLoading && (
        <>
          <LoadingState variant="kpis" label="Loading operations metrics…" />
          <LoadingState variant="panel" />
        </>
      )}
      {metrics.isError && !metrics.data && <ErrorState error={metrics.error} onRetry={() => void metrics.refetch()} />}
      {metrics.data && (
        <div className={`page-stack transition-opacity ${metrics.isPlaceholderData ? 'opacity-60' : ''}`} aria-busy={metrics.isPlaceholderData}>
          <MetricsBody data={metrics.data} />
        </div>
      )}
      <DefinitionsPanel />
    </div>
  );
}

/** Analytics "SLA risk" tab — active incidents ranked by computed breach probability. Statistics, not AI. */
export function SlaRiskTab() {
  const risk = useSlaRiskOverview(25);
  return (
    <Panel
      title="SLA breach risk"
      subtitle={
        <>
          <ToneBadge value="statistical" label="Statistical — not AI" tone="neutral" /> Active incidents with a running resolution clock, ranked by the computed probability of breaching
        </>
      }
    >
      {risk.isLoading && <LoadingState rows={5} label="Ranking incidents…" />}
      {risk.isError && <ErrorState error={risk.error} onRetry={() => void risk.refetch()} compact />}
      {risk.data && (
        <>
          {risk.data.method && <p className="m-0 mb-2 text-[10px] text-muted">{risk.data.method}</p>}
          {risk.data.incidents.length === 0 ? (
            <EmptyState title="No running resolution clocks" description="Nothing to rank right now." />
          ) : (
            <DataTable<SlaRisk>
              caption="SLA risk ranking"
              rows={risk.data.incidents}
              rowKey={(row) => row.incidentId}
              columns={[
                {
                  key: 'incident',
                  header: 'Incident',
                  className: 'summary-cell',
                  render: (row) => (
                    <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                      <Link to={`/incidents/${row.incidentId}`} className="id-cell">
                        {row.number}
                      </Link>
                      <span className="break-words">{row.title}</span>
                    </span>
                  ),
                },
                { key: 'priority', header: 'Priority', render: (row) => <PriorityBadge priority={row.priority as Priority} /> },
                {
                  key: 'probability',
                  header: 'Breach probability',
                  render: (row) => (
                    <span className="inline-flex items-center gap-1.5" title={row.probabilityLabel ?? undefined}>
                      <b>{row.probability === null ? '—' : formatPercent(row.probability * 100, 0)}</b>
                      <ToneBadge value={row.riskLevel} label={titleCase(row.riskLevel)} tone={RISK_LEVEL_TONES[row.riskLevel] ?? 'neutral'} />
                    </span>
                  ),
                },
                { key: 'elapsed', header: 'Elapsed', render: (row) => formatPercent(row.elapsedPercent, 0) },
                { key: 'remaining', header: 'Remaining', render: (row) => (row.remainingMinutes >= 0 ? formatMinutes(row.remainingMinutes) : <span className="red-text">{formatMinutes(-row.remainingMinutes)} over</span>) },
                { key: 'sample', header: 'Sample', render: (row) => `${formatNumber(row.historicalBreaches)} / ${formatNumber(row.sampleSize)} breached` },
              ]}
            />
          )}
        </>
      )}
    </Panel>
  );
}
