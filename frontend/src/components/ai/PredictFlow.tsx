import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Sigma } from 'lucide-react';
import { Area, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useForecast } from '../../api/analytics';
import { axisTick, CHART, tooltipItemStyle, tooltipLabelStyle, tooltipStyle } from '../../lib/chartTheme';
import { forecastLabel, formatDelta, formatNumber, formatShortDate } from '../../lib/format';
import type { ForecastDto } from '../../lib/types';
import { EmptyState, ErrorState, LoadingState } from '../States';

const HISTORY_DAYS = 30;
const HORIZONS = [7, 14, 30] as const;

function sum(values: number[]): number {
  return values.reduce((total, value) => total + value, 0);
}

/** Expected volume over the horizon vs the same number of most recent days. */
export function forecastSummary(data: ForecastDto, horizon: number) {
  const days = Math.min(horizon, data.forecast.length);
  const next = sum(data.forecast.slice(0, days).map((point) => point.predicted));
  const lower = sum(data.forecast.slice(0, days).map((point) => point.lower));
  const upper = sum(data.forecast.slice(0, days).map((point) => point.upper));
  const recent = sum(data.history.slice(-days).map((point) => point.count));
  const change = recent > 0 ? ((next - recent) / recent) * 100 : null;
  return { days, next: Math.round(next), lower: Math.max(0, Math.round(lower)), upper: Math.round(upper), recent, change };
}

function ForecastChart({ data }: { data: ForecastDto }) {
  const rows = useMemo(() => {
    const history = data.history.map((point) => ({
      label: formatShortDate(point.date),
      actual: point.count as number | undefined,
      predicted: undefined as number | undefined,
      band: undefined as [number, number] | undefined,
    }));
    if (history.length && data.forecast.length) history[history.length - 1].predicted = history[history.length - 1].actual;
    const future = data.forecast.map((point) => ({
      label: formatShortDate(point.date),
      actual: undefined,
      predicted: Math.round(point.predicted * 10) / 10,
      band: [Math.max(0, Math.round(point.lower * 10) / 10), Math.round(point.upper * 10) / 10] as [number, number],
    }));
    return [...history, ...future];
  }, [data]);

  return (
    <div className="nx-chart" role="img" aria-label="Incidents per day for the last 30 days and the statistical forecast with its confidence band">
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={rows} margin={{ top: 6, right: 6, bottom: 0, left: -12 }}>
          <defs>
            <linearGradient id="nxPredictFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={CHART.gold} stopOpacity={0.25} />
              <stop offset="100%" stopColor={CHART.gold} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" vertical={false} />
          <XAxis dataKey="label" tick={axisTick} axisLine={false} tickLine={false} minTickGap={18} />
          <YAxis tick={axisTick} axisLine={false} tickLine={false} width={34} allowDecimals={false} />
          <Tooltip contentStyle={tooltipStyle} labelStyle={tooltipLabelStyle} itemStyle={tooltipItemStyle} />
          <Area type="monotone" dataKey="band" name="Confidence band" stroke="none" fill={CHART.gold} fillOpacity={0.14} isAnimationActive={false} />
          <Area type="monotone" dataKey="actual" name="Actual" stroke={CHART.gold} fill="url(#nxPredictFill)" strokeWidth={2} />
          <Line type="monotone" dataKey="predicted" name="Forecast" stroke={CHART.goldLight} strokeDasharray="5 5" strokeWidth={2} dot={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

/** Predict tile: the analytics statistical forecast. Deliberately labelled as a statistical model, never as AI. */
export function PredictFlow() {
  const [horizon, setHorizon] = useState<(typeof HORIZONS)[number]>(7);
  const forecast = useForecast(HISTORY_DAYS, horizon);

  if (forecast.isLoading) return <LoadingState variant="panel" label="Loading forecast…" />;
  if (forecast.isError || !forecast.data) return <ErrorState error={forecast.error} onRetry={() => void forecast.refetch()} compact />;
  const data = forecast.data;
  if (!data.history.length && !data.forecast.length) {
    return <EmptyState title="Not enough history to forecast" description="The model needs incident history from the last 30 days." icon={<Sigma size={20} />} />;
  }
  const summary = forecastSummary(data, horizon);
  const services = [...data.byService].sort((a, b) => b.next7Days - a.next7Days).slice(0, 5);
  const maxService = Math.max(1, ...services.map((service) => Math.max(service.next7Days, service.last7Days)));

  return (
    <div className={`grid gap-3 ${forecast.isPlaceholderData ? 'opacity-60' : ''}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="badge fallback" title="Computed by a statistical model from incident history">
          <Sigma size={10} /> Statistical model — not AI
        </span>
        <span className="text-[10px] text-muted">
          {forecastLabel(data.method)}
          {data.rSquared !== null ? ` · fit R² ${data.rSquared.toFixed(2)}` : ''}
        </span>
        <div className="nx-segment ml-auto" role="group" aria-label="Forecast horizon">
          {HORIZONS.map((days) => (
            <button key={days} type="button" aria-pressed={horizon === days} className={horizon === days ? 'on' : ''} onClick={() => setHorizon(days)}>
              {days} days
            </button>
          ))}
        </div>
      </div>

      <div className="grid gap-3 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <div>
          <div className="nx-forecast-head">
            <div>
              <small>Expected next {summary.days} days</small>
              <b>{formatNumber(summary.next)}</b>
              <span className="muted-text">
                range {formatNumber(summary.lower)}–{formatNumber(summary.upper)}
              </span>
            </div>
            <div>
              <small>Last {summary.days} days</small>
              <b>{formatNumber(summary.recent)}</b>
              {summary.change !== null && (
                <span className={summary.change > 0 ? 'red-text' : 'green-text'}>{formatDelta(Math.round(summary.change), '%')}</span>
              )}
            </div>
          </div>
          <ForecastChart data={data} />
        </div>
        <div>
          <h5 className="nx-sub">Services to watch · next 7 days</h5>
          {services.length === 0 ? (
            <p className="muted-text text-[10px]">No per-service forecast available.</p>
          ) : (
            <table className="nx-data" aria-label="Forecast by service">
              <thead>
                <tr>
                  <th scope="col">Service</th>
                  <th scope="col">
                    <span className="sr-only">Expected volume</span>
                  </th>
                  <th scope="col">Last 7 d</th>
                  <th scope="col">Next 7 d</th>
                </tr>
              </thead>
              <tbody>
                {services.map((service) => (
                  <tr key={service.serviceName}>
                    <th scope="row" title={service.serviceName}>
                      {service.serviceName}
                    </th>
                    <td aria-hidden="true">
                      <div className="health-track">
                        <i style={{ width: `${(service.next7Days / maxService) * 100}%` }} />
                      </div>
                    </td>
                    <td>{formatNumber(service.last7Days)}</td>
                    <td>
                      <b>{formatNumber(Math.round(service.next7Days))}</b>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <Link className="link-btn mt-3 text-[11px]" to="/analytics?tab=operations">
            Open the full forecast in Analytics <ArrowRight size={12} />
          </Link>
        </div>
      </div>
    </div>
  );
}
