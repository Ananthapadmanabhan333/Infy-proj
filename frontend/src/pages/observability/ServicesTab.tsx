import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Gauge, X } from 'lucide-react';
import { useMetricNames, useMetricSeries, useRedSummaries, useServiceRed } from '../../api/observability';
import { SimulatedBadge } from '../../components/Badge';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Panel } from '../../components/Panel';
import { SimulatedDataNotice } from '../../components/sim/SimulationParts';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { formatNumber } from '../../lib/format';
import type { MetricAggregation, RedSummary } from '../../lib/r4Types';
import { formatMs, formatRatio, RangeSelect, rangeMinutes, useTimeWindow, type RangeValue } from './obsShared';
import { SeriesChart } from './SeriesChart';

const BASIS_LABELS: Record<RedSummary['basis'], string> = { SPANS: 'Spans', METRICS: 'HTTP metrics', NONE: 'No data' };

function RedCells({ red }: { red: RedSummary }) {
  if (red.status === 'INSUFFICIENT_DATA') {
    return (
      <td colSpan={5} className="text-muted">
        {red.message ?? 'Insufficient data'}
      </td>
    );
  }
  return (
    <>
      <td>{red.ratePerMinute === null ? '—' : `${red.ratePerMinute.toFixed(red.ratePerMinute < 10 ? 2 : 0)}/min`}</td>
      <td>{formatNumber(red.requests)}</td>
      <td className={red.errorRate && red.errorRate > 0.05 ? 'red-text' : undefined}>
        {formatRatio(red.errorRate)} ({formatNumber(red.errors)})
      </td>
      <td>{red.durationP95Ms === null ? <span className="text-muted" title="The HTTP metrics fallback has means only">n/a</span> : formatMs(red.durationP95Ms)}</td>
      <td>{formatMs(red.durationAvgMs)}</td>
    </>
  );
}

function ServiceMetricChart({ serviceId, metric, aggregation, from, to, includeSimulated, spanMinutes }: { serviceId: number; metric: string; aggregation: MetricAggregation; from: string; to: string; includeSimulated: boolean; spanMinutes: number }) {
  const series = useMetricSeries({ name: metric, serviceId, aggregation, from, to, includeSimulated });
  if (series.isLoading) return <LoadingState variant="panel" label="Loading series…" />;
  if (series.isError) return <ErrorState error={series.error} onRetry={() => void series.refetch()} compact />;
  const data = series.data;
  if (!data || data.status === 'NO_DATA' || data.series.length === 0) return <EmptyState title="No data in this window" description={data?.message ?? undefined} />;
  return (
    <>
      <SimulatedDataNotice show={data.simulated} text="This chart includes simulated sample data." />
      <SeriesChart series={data.series} from={data.from} to={data.to} unit={data.series[0]?.unit} spanMinutes={spanMinutes} height={220} />
    </>
  );
}

function ServiceDetail({ serviceId, from, to, includeSimulated, spanMinutes, onClose }: { serviceId: number; from: string; to: string; includeSimulated: boolean; spanMinutes: number; onClose: () => void }) {
  const red = useServiceRed(serviceId, { from, to, includeSimulated });
  const metrics = useMetricNames({ serviceId, includeSimulated });
  const [metric, setMetric] = useState('');
  const [aggregation, setAggregation] = useState<MetricAggregation>('avg');
  const names = metrics.data ?? [];
  const chosen = metric || names[0]?.name || '';
  const r = red.data;
  return (
    <Panel
      title={r?.serviceName ?? `Service #${serviceId}`}
      subtitle={r ? `RED basis: ${BASIS_LABELS[r.basis]}` : undefined}
      actions={
        <span className="flex items-center gap-1.5">
          <Link to={`/services/${serviceId}`} className="subtle-btn compact">
            Service page
          </Link>
          <button type="button" className="icon-btn sm" aria-label="Close service detail" onClick={onClose}>
            <X size={13} />
          </button>
        </span>
      }
    >
      {red.isLoading && <LoadingState variant="kpis" />}
      {red.isError && <ErrorState error={red.error} onRetry={() => void red.refetch()} compact />}
      {r && r.status === 'INSUFFICIENT_DATA' && <p className="callout m-0">{r.message ?? 'Insufficient data for a RED summary in this window.'}</p>}
      {r && r.status === 'OK' && (
        <ul className="kv-list mb-3" aria-label="RED summary">
          <li>
            <span>Rate</span>
            <span>{r.ratePerMinute === null ? '—' : `${r.ratePerMinute.toFixed(2)}/min`}</span>
          </li>
          <li>
            <span>Requests</span>
            <span>{formatNumber(r.requests)}</span>
          </li>
          <li>
            <span>Errors</span>
            <span>
              {formatNumber(r.errors)} ({formatRatio(r.errorRate)})
            </span>
          </li>
          <li>
            <span>p95</span>
            <span>{r.durationP95Ms === null ? 'n/a (metrics give means only)' : formatMs(r.durationP95Ms)}</span>
          </li>
          <li>
            <span>Average</span>
            <span>{formatMs(r.durationAvgMs)}</span>
          </li>
        </ul>
      )}
      {r && <SimulatedDataNotice show={r.simulated} />}
      <div className="mt-3">
        {metrics.isLoading && <LoadingState variant="inline" label="Loading metrics…" />}
        {metrics.isError && <ErrorState error={metrics.error} onRetry={() => void metrics.refetch()} compact />}
        {metrics.data && names.length === 0 && <EmptyState title="No metrics for this service" description="Metrics appear once telemetry from this service is mapped to it." />}
        {names.length > 0 && (
          <>
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <label htmlFor="obs-service-metric" className="text-[11px] text-muted">
                Metric
              </label>
              <select id="obs-service-metric" className="control sm w-auto max-w-full" value={chosen} onChange={(event) => setMetric(event.target.value)}>
                {names.map((entry) => (
                  <option key={entry.name} value={entry.name}>
                    {entry.name}
                  </option>
                ))}
              </select>
              <label htmlFor="obs-service-agg" className="sr-only">
                Aggregation
              </label>
              <select id="obs-service-agg" className="control sm w-auto" value={aggregation} onChange={(event) => setAggregation(event.target.value as MetricAggregation)}>
                {(['avg', 'max', 'min', 'sum', 'p95'] as MetricAggregation[]).map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </div>
            {chosen && <ServiceMetricChart serviceId={serviceId} metric={chosen} aggregation={aggregation} from={from} to={to} includeSimulated={includeSimulated} spanMinutes={spanMinutes} />}
          </>
        )}
      </div>
    </Panel>
  );
}

/** Services: RED summary (rate, errors, duration) per service from spans or HTTP metrics; select a service for its charts. */
export function ServicesTab({ includeSimulated }: { includeSimulated: boolean }) {
  const [range, setRange] = useState<RangeValue>('1h');
  const [selected, setSelected] = useState<number | null>(null);
  const window = useTimeWindow(range);
  const red = useRedSummaries({ from: window.from, to: window.to, includeSimulated });
  const rows = red.data ?? [];
  return (
    <div className="grid gap-[14px]">
      <Panel title="Service RED summary" subtitle="Rate, errors and duration from SERVER/root spans, else from http.server.request.duration histograms" actions={<RangeSelect id="obs-red-range" value={range} onChange={setRange} onRefresh={window.refresh} />}>
        {red.isLoading && <LoadingState rows={5} />}
        {red.isError && <ErrorState error={red.error} onRetry={() => void red.refetch()} />}
        {red.data && rows.length === 0 && <EmptyState icon={<Gauge size={20} />} title="No services to summarise" description="Services appear once telemetry is mapped to them." />}
        {rows.length > 0 && (
          <div className="table-scroll">
            <table className="data-table">
              <caption className="sr-only">RED summary per service</caption>
              <thead>
                <tr>
                  <th scope="col">Service</th>
                  <th scope="col">Basis</th>
                  <th scope="col">Rate</th>
                  <th scope="col">Requests</th>
                  <th scope="col">Error rate</th>
                  <th scope="col">p95</th>
                  <th scope="col">Average</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((entry) => (
                  <tr key={entry.serviceId ?? entry.serviceName ?? 'unmapped'}>
                    <td>
                      {entry.serviceId ? (
                        <button type="button" className="link-btn" onClick={() => setSelected(entry.serviceId)} aria-label={`Show ${entry.serviceName ?? 'service'} detail`}>
                          {entry.serviceName ?? entry.serviceCode}
                        </button>
                      ) : (
                        entry.serviceName ?? 'Unmapped'
                      )}{' '}
                      <SimulatedBadge simulated={entry.simulated} />
                    </td>
                    <td>
                      <ToneBadge value={entry.basis} label={BASIS_LABELS[entry.basis] ?? entry.basis} tone={entry.basis === 'NONE' ? 'muted' : 'neutral'} />
                    </td>
                    <RedCells red={entry} />
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      {selected !== null && <ServiceDetail serviceId={selected} from={window.from} to={window.to} includeSimulated={includeSimulated} spanMinutes={rangeMinutes(range)} onClose={() => setSelected(null)} />}
    </div>
  );
}
