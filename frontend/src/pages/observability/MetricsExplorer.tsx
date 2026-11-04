import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { LineChart as LineChartIcon, Plus, X } from 'lucide-react';
import { useAnomalies, useMetricNames, useMetricSeries, type SeriesParams } from '../../api/observability';
import { useServices } from '../../api/services';
import { SimulatedBadge } from '../../components/Badge';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { SimulatedDataNotice } from '../../components/sim/SimulationParts';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useDebounce } from '../../hooks/useDebounce';
import { formatNumber, formatRelative } from '../../lib/format';
import type { MetricAggregation } from '../../lib/r4Types';
import { attributesText, formatValue, RangeSelect, rangeMinutes, useTimeWindow, type RangeValue } from './obsShared';
import { SeriesChart } from './SeriesChart';

export const AGGREGATIONS: MetricAggregation[] = ['avg', 'max', 'min', 'sum', 'p95'];
const STEP_OPTIONS = [
  { value: '', label: 'Auto step' },
  { value: '60', label: '1 minute' },
  { value: '300', label: '5 minutes' },
  { value: '900', label: '15 minutes' },
  { value: '3600', label: '1 hour' },
];

interface AttrFilter {
  key: string;
  value: string;
}

/** Metrics explorer: metric picker, service + attribute filters, aggregation, step and range → chart with anomaly overlays. */
export function MetricsExplorer({ includeSimulated }: { includeSimulated: boolean }) {
  const [params] = useSearchParams();
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounce(search, 300);
  const [metric, setMetric] = useState(params.get('metric') ?? '');
  const [serviceId, setServiceId] = useState(params.get('service') ?? '');
  const [aggregation, setAggregation] = useState<MetricAggregation>('avg');
  const [step, setStep] = useState('');
  const [range, setRange] = useState<RangeValue>('1h');
  const [attrs, setAttrs] = useState<AttrFilter[]>([]);
  const window = useTimeWindow(range);
  const services = useServices();
  const names = useMetricNames({ search: debouncedSearch || undefined, serviceId: serviceId ? Number(serviceId) : undefined, includeSimulated });
  const selected = names.data?.find((entry) => entry.name === metric);

  const attrMap = useMemo(() => Object.fromEntries(attrs.filter((attr) => attr.key.trim() && attr.value.trim()).map((attr) => [attr.key.trim(), attr.value.trim()])), [attrs]);
  const query: SeriesParams | null = metric
    ? {
        name: metric,
        serviceId: serviceId ? Number(serviceId) : undefined,
        aggregation,
        step: step ? Number(step) : undefined,
        from: window.from,
        to: window.to,
        includeSimulated,
        attrs: attrMap,
      }
    : null;
  const series = useMetricSeries(query);
  const anomalies = useAnomalies({ from: window.from, to: window.to, serviceId: serviceId ? Number(serviceId) : undefined, includeSimulated, size: 100 }, Boolean(metric));
  const metricAnomalies = (anomalies.data?.content ?? []).filter((anomaly) => anomaly.metricName === metric);
  const data = series.data;

  return (
    <div className="grid items-start gap-[14px] xl:grid-cols-[300px_minmax(0,1fr)]">
      <Panel title="Metrics" subtitle="Metric names received from your sources">
        <SearchInput value={search} onChange={setSearch} placeholder="Search metric names…" label="Search metrics" />
        <div className="mt-2">
          <label htmlFor="obs-metric-service" className="sr-only">
            Service
          </label>
          <select id="obs-metric-service" className="control sm" value={serviceId} onChange={(event) => setServiceId(event.target.value)}>
            <option value="">All services</option>
            {(services.data ?? []).map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </select>
        </div>
        <div className="mt-2">
          {names.isLoading && <LoadingState variant="inline" label="Loading metrics…" />}
          {names.isError && <ErrorState error={names.error} onRetry={() => void names.refetch()} compact />}
          {names.data && names.data.length === 0 && (
            <EmptyState title={debouncedSearch || serviceId ? 'No metrics match' : 'No metrics received yet'} description={debouncedSearch || serviceId ? undefined : 'Metric names appear once a source delivers data.'} />
          )}
          {names.data && names.data.length > 0 && (
            <ul className="obs-metric-list" aria-label="Metric names">
              {names.data.map((entry) => (
                <li key={entry.name}>
                  <button type="button" aria-pressed={entry.name === metric} onClick={() => setMetric(entry.name)}>
                    <b className="block">{entry.name}</b>
                    <small className="text-muted">
                      {entry.type}
                      {entry.unit ? ` · ${entry.unit}` : ''} · {formatNumber(entry.seriesCount)} series{entry.lastSeenAt ? ` · ${formatRelative(entry.lastSeenAt)}` : ''}
                    </small>{' '}
                    <SimulatedBadge simulated={entry.simulated} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </Panel>

      <Panel title={metric || 'Choose a metric'} subtitle={selected ? `${selected.type}${selected.unit ? ` · unit ${selected.unit}` : ''}` : 'Pick a metric on the left to chart it'}>
        <div className="obs-filters" role="group" aria-label="Series query">
          <div className="field">
            <div className="field-label">
              <label htmlFor="obs-agg">Aggregation</label>
            </div>
            <select id="obs-agg" className="control sm" value={aggregation} onChange={(event) => setAggregation(event.target.value as MetricAggregation)}>
              {AGGREGATIONS.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <div className="field-label">
              <label htmlFor="obs-step">Step</label>
            </div>
            <select id="obs-step" className="control sm" value={step} onChange={(event) => setStep(event.target.value)}>
              {STEP_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <div className="field-label">
              <label htmlFor="obs-metric-range">Time range</label>
            </div>
            <RangeSelect id="obs-metric-range" value={range} onChange={setRange} onRefresh={window.refresh} />
          </div>
        </div>
        <fieldset className="mb-3 grid gap-1.5 border-0 p-0" aria-label="Attribute filters">
          <legend className="mb-1 text-[11px] text-muted">Attribute filters (exact match, sent as attr.&lt;key&gt;=&lt;value&gt;)</legend>
          {attrs.map((attr, index) => (
            <div key={index} className="obs-attr-row">
              <input className="control sm" aria-label={`Attribute ${index + 1} key`} placeholder="key, e.g. state" value={attr.key} onChange={(event) => setAttrs((list) => list.map((item, i) => (i === index ? { ...item, key: event.target.value } : item)))} />
              <input className="control sm" aria-label={`Attribute ${index + 1} value`} placeholder="value, e.g. used" value={attr.value} onChange={(event) => setAttrs((list) => list.map((item, i) => (i === index ? { ...item, value: event.target.value } : item)))} />
              <button type="button" className="icon-btn sm" aria-label={`Remove attribute ${index + 1}`} onClick={() => setAttrs((list) => list.filter((_, i) => i !== index))}>
                <X size={12} />
              </button>
            </div>
          ))}
          {attrs.length < 10 && (
            <button type="button" className="link-btn justify-self-start text-[11px]" onClick={() => setAttrs((list) => [...list, { key: '', value: '' }])}>
              <Plus size={11} aria-hidden="true" /> Add attribute filter
            </button>
          )}
        </fieldset>

        {!metric && <EmptyState icon={<LineChartIcon size={20} />} title="No metric selected" description="Choose a metric name to chart its series from the stored minute roll-ups." />}
        {metric && series.isLoading && <LoadingState variant="panel" label="Loading series…" />}
        {metric && series.isError && <ErrorState error={series.error} onRetry={() => void series.refetch()} />}
        {metric && data && (
          <>
            <SimulatedDataNotice show={data.simulated} text="This chart includes simulated sample data." />
            {data.status === 'NO_DATA' || data.series.length === 0 ? (
              <EmptyState title="No data in this window" description={data.message ?? 'No points were stored for this metric and filters in the selected time range.'} />
            ) : (
              <>
                <p className="mb-2 mt-2 text-[11px] text-muted">
                  {data.aggregation} over {formatNumber(data.stepSeconds)} s buckets · {formatNumber(data.series.length)} series
                  {metricAnomalies.length ? ` · ${formatNumber(metricAnomalies.length)} anomal${metricAnomalies.length === 1 ? 'y' : 'ies'} in the window` : ' · no anomalies in the window'}
                </p>
                <SeriesChart series={data.series} anomalies={metricAnomalies} from={data.from} to={data.to} unit={data.series[0]?.unit} spanMinutes={rangeMinutes(range)} />
                <div className="table-scroll mt-3">
                  <table className="data-table">
                    <caption className="sr-only">Series</caption>
                    <thead>
                      <tr>
                        <th scope="col">Series</th>
                        <th scope="col">Attributes</th>
                        <th scope="col">Points</th>
                        <th scope="col">Last value</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.series.map((entry) => {
                        const last = entry.points[entry.points.length - 1];
                        return (
                          <tr key={entry.seriesId}>
                            <td>
                              {entry.resourceServiceName ?? `#${entry.seriesId}`} <SimulatedBadge simulated={entry.simulated} />
                            </td>
                            <td className="break-all">{attributesText(entry.attributes, 8) || '—'}</td>
                            <td>{formatNumber(entry.points.length)}</td>
                            <td>{last ? formatValue(last.v, entry.unit) : '—'}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </>
        )}
      </Panel>
    </div>
  );
}
