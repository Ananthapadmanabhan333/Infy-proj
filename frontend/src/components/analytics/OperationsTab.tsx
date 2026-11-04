import { useState } from 'react';
import { CheckCircle2, ChevronDown, Layers3, PhoneCall, TrendingUp, Zap } from 'lucide-react';
import { useForecast } from '../../api/analytics';
import { forecastLabel, formatMinutes, formatNumber, formatPercent, percentOf } from '../../lib/format';
import type { AnalyticsOverview, ForecastDto } from '../../lib/types';
import { TrendChart, isoDate } from '../dashboard/Widgets';
import { KpiCard } from '../KpiCard';
import { Panel } from '../Panel';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { ServiceVolumeChart } from './ServiceVolumeChart';
import type { AnalyticsFilterState } from './useAnalyticsFilters';

export const FORECAST_HORIZONS = [7, 14, 30] as const;

/** Reference analytics KPI row: Incident Volume, Resolution Rate, MTTR, First Call Resolution with deltas. */
export function AnalyticsKpis({ data, rangeLabel }: { data: AnalyticsOverview; rangeLabel: string }) {
  const { kpis } = data;
  const previous = 'vs the previous period of the same length';
  return (
    <div className="kpi-grid !mb-0">
      <KpiCard
        icon={<Layers3 />}
        label="Incident Volume"
        value={formatNumber(kpis.createdInPeriod)}
        caption={`${rangeLabel} · ${formatNumber(kpis.resolvedInPeriod)} resolved`}
        delta={kpis.deltas.created}
        deltaUnit="%"
        higherIsBetter={false}
        deltaLabel={`Incidents created ${previous}`}
        tone="gold"
      />
      <KpiCard
        icon={<CheckCircle2 />}
        label="Resolution Rate"
        value={formatPercent(kpis.resolutionRate, 0)}
        caption="Created in the period and resolved"
        delta={kpis.deltas.resolutionRate ?? null}
        deltaUnit="pp"
        higherIsBetter
        deltaLabel={`Percentage points ${previous}`}
        tone="success"
      />
      <KpiCard
        icon={<Zap />}
        label="MTTR"
        value={formatMinutes(kpis.mttrMinutes)}
        caption={`Mean time to resolve · MTTA ${formatMinutes(kpis.mttaMinutes)}`}
        delta={kpis.deltas.mttr}
        deltaUnit="%"
        higherIsBetter={false}
        deltaLabel={`MTTR change ${previous}`}
        tone="warning"
      />
      <KpiCard
        icon={<PhoneCall />}
        label="First Call Resolution"
        value={formatPercent(kpis.firstCallResolution, 0)}
        caption="One assignee, never reopened"
        delta={kpis.deltas.firstCallResolution ?? null}
        deltaUnit="pp"
        higherIsBetter
        deltaLabel={`Percentage points ${previous}`}
        tone="gold"
      />
    </div>
  );
}

function HorizonSelect({ value, onChange }: { value: number; onChange: (value: number) => void }) {
  return (
    <label className="period-select an-compact-select">
      <TrendingUp size={14} aria-hidden="true" />
      <span className="sr-only">Forecast horizon</span>
      <select aria-label="Forecast horizon" value={value} onChange={(event) => onChange(Number(event.target.value))}>
        {FORECAST_HORIZONS.map((days) => (
          <option key={days} value={days}>
            Next {days} days
          </option>
        ))}
      </select>
      <ChevronDown size={13} aria-hidden="true" />
    </label>
  );
}

function ForecastOutlook({ data, horizon }: { data: ForecastDto; horizon: number }) {
  const predictedTotal = data.forecast.reduce((sum, point) => sum + point.predicted, 0);
  const services = [...data.byService].sort((a, b) => b.next7Days - a.next7Days).slice(0, 6);
  return (
    <>
      <dl className="an-stats">
        <div>
          <dt>Next {horizon} days</dt>
          <dd>{formatNumber(Math.round(predictedTotal))}</dd>
          <small>predicted incidents</small>
        </div>
        <div>
          <dt>Trend</dt>
          <dd className={data.slope > 0 ? 'red-text' : data.slope < 0 ? 'green-text' : ''}>
            {`${data.slope > 0 ? '+' : ''}${data.slope.toFixed(2)}`}
          </dd>
          <small>incidents / day, per day</small>
        </div>
        <div>
          <dt>Fit (R²)</dt>
          <dd>{data.rSquared === null ? '—' : data.rSquared.toFixed(2)}</dd>
          <small>1.00 = perfect linear fit</small>
        </div>
      </dl>
      <h4 className="an-subhead">Per-service outlook · next 7 days</h4>
      {services.length === 0 ? (
        <EmptyState title="No per-service history yet" />
      ) : (
        <div className="table-scroll">
          <table className="static-table data-table">
            <caption className="sr-only">Per-service forecast</caption>
            <thead>
              <tr>
                <th scope="col">Service</th>
                <th scope="col">Last 7 days</th>
                <th scope="col">Forecast</th>
                <th scope="col">Change</th>
              </tr>
            </thead>
            <tbody>
              {services.map((row) => {
                const change = row.last7Days ? percentOf(row.next7Days - row.last7Days, row.last7Days) : null;
                return (
                  <tr key={row.serviceName}>
                    <td>
                      <b>{row.serviceName}</b>
                    </td>
                    <td>{formatNumber(row.last7Days)}</td>
                    <td>{formatNumber(Math.round(row.next7Days))}</td>
                    <td>
                      {change === null ? (
                        <span className="muted-text">—</span>
                      ) : (
                        <span className={change > 0 ? 'red-text' : change < 0 ? 'green-text' : 'muted-text'}>{`${change > 0 ? '+' : ''}${formatPercent(change, 0)}`}</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="cc-chart-note">{forecastLabel(data.method)} — statistical projection, not an AI prediction.</p>
    </>
  );
}

/** Operations tab: KPIs, stacked service volume, created vs. resolved with forecast, forecast outlook (folded Forecast page). */
export function OperationsTab({ data, state }: { data: AnalyticsOverview; state: AnalyticsFilterState }) {
  const [horizon, setHorizon] = useState<number>(14);
  const forecast = useForecast(state.filters, horizon);
  // The forecast projects forward from the latest full day, so it only joins a range that reaches today.
  const live = !state.custom || !state.to || state.to >= isoDate(new Date());
  const forecastPoints = live ? forecast.data?.forecast ?? [] : [];

  return (
    <>
      <AnalyticsKpis data={data} rangeLabel={state.rangeLabel} />
      <Panel title="Incident Volume by Service" subtitle={`Daily incidents for the busiest services · ${state.rangeLabel.toLowerCase()}`}>
        <ServiceVolumeChart points={data.serviceVolume} names={data.serviceNames} />
      </Panel>
      <div className="an-row">
        <Panel
          title="Incident Trend & Forecast"
          subtitle={live ? `Created vs. resolved with a ${horizon}-day projection` : 'Created vs. resolved in the selected range'}
          actions={<HorizonSelect value={horizon} onChange={setHorizon} />}
        >
          <TrendChart trend={data.trend} forecast={forecastPoints} method={forecast.data?.method ?? ''} height="chart-wrap large" staticLegend />
        </Panel>
        <Panel title="Forecast Outlook" subtitle={live ? 'Statistical projection from the latest full day' : 'Projected from the latest full day (independent of the custom range)'}>
          {forecast.isLoading && <LoadingState variant="panel" label="Loading forecast…" />}
          {forecast.isError && !forecast.data && <ErrorState error={forecast.error} onRetry={() => void forecast.refetch()} compact />}
          {forecast.data && <ForecastOutlook data={forecast.data} horizon={horizon} />}
        </Panel>
      </div>
    </>
  );
}
