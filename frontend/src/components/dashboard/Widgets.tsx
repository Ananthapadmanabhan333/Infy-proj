import { useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, BookOpen, ChevronRight, Headphones, Layers3, Server, ShieldCheck, TrendingDown, TrendingUp, Workflow, Zap } from 'lucide-react';
import { Area, CartesianGrid, ComposedChart, Line, ReferenceDot, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { axisTick, CHART, PRIORITY_COLORS, tooltipItemStyle, tooltipLabelStyle, tooltipStyle } from '../../lib/chartTheme';
import { PALETTE, PRIORITIES, PRIORITY_LABELS } from '../../lib/constants';
import { forecastLabel, formatMinutes, formatNumber, formatPercent, formatShortDate, titleCase } from '../../lib/format';
import type { DashboardDto, DashboardKpis, ForecastPoint, Priority, ServiceDto, ServiceStatus, TrendPoint } from '../../lib/types';
import { DonutChart } from '../DonutChart';
import { KpiCard } from '../KpiCard';
import { EmptyState } from '../States';
import { serviceIcon } from './serviceIcon';
import './dashboard.css';

// ------------------------------------------------------------------ KPIs

/** Command Center KPI row (reference: Total Incidents, Critical, SLA Compliance, MTTR — each with a coloured delta). */
export function KpiRow({ kpis, days, className = '' }: { kpis: DashboardKpis; days: number; className?: string }) {
  return (
    <div className={`kpi-grid ${className}`.trim()}>
      <KpiCard
        icon={<Layers3 />}
        label="Total Incidents"
        value={formatNumber(kpis.createdInPeriod)}
        caption={`Last ${days} days · ${formatNumber(kpis.openIncidents)} open`}
        delta={kpis.deltas.created}
        deltaUnit="%"
        higherIsBetter={false}
        deltaLabel={`Incidents created vs the previous ${days} days`}
        tone="gold"
        to="/incidents"
      />
      <KpiCard
        icon={<AlertTriangle />}
        label="Critical"
        value={formatNumber(kpis.criticalOpen)}
        caption="Open critical incidents"
        delta={kpis.deltas.criticalOpen}
        deltaUnit=""
        higherIsBetter={false}
        deltaLabel={`Change in critical incidents created vs the previous ${days} days`}
        tone="critical"
      />
      <KpiCard
        icon={<ShieldCheck />}
        label="SLA Compliance"
        value={formatPercent(kpis.slaCompliance)}
        caption={`Resolution SLAs met · last ${days} days`}
        delta={kpis.deltas.slaCompliance}
        deltaUnit="pp"
        higherIsBetter
        deltaLabel={`Percentage points vs the previous ${days} days`}
        tone="gold"
        to="/sla-monitor"
      />
      <KpiCard
        icon={<Zap />}
        label="MTTR"
        value={formatMinutes(kpis.mttrMinutes)}
        caption={`Mean time to resolve · MTTA ${formatMinutes(kpis.mttaMinutes)}`}
        delta={kpis.deltas.mttr}
        deltaUnit="%"
        higherIsBetter={false}
        deltaLabel={`MTTR change vs the previous ${days} days`}
        tone="gold"
        to="/analytics"
      />
    </div>
  );
}

// ------------------------------------------------------------------ trend & forecast

export interface TrendDatum {
  date: string;
  created?: number;
  resolved?: number;
  predicted?: number;
  band?: [number, number];
}

const round1 = (value: number) => Math.round(value * 10) / 10;

/** Local calendar date as "YYYY-MM-DD". */
export function isoDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function addDays(date: Date, days: number): Date {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

/**
 * Merges observed counts and the statistical forecast into one series keyed by date. The forecast can
 * overlap the observed range (the API fits on full days, so its first point is usually today); the dashed
 * line is joined to the last observed day before the forecast starts.
 */
export function buildTrendData(trend: TrendPoint[], forecast: ForecastPoint[]): TrendDatum[] {
  const rows = new Map<string, TrendDatum>();
  trend.forEach((point) => rows.set(point.date, { date: point.date, created: point.created, resolved: point.resolved }));
  forecast.forEach((point) => {
    const row = rows.get(point.date) ?? { date: point.date };
    row.predicted = round1(point.predicted);
    row.band = [round1(Math.max(0, point.lower)), round1(Math.max(point.lower, point.upper))];
    rows.set(point.date, row);
  });
  const data = [...rows.values()].sort((a, b) => a.date.localeCompare(b.date));
  const firstForecast = forecast.length ? [...forecast].sort((a, b) => a.date.localeCompare(b.date))[0].date : null;
  if (firstForecast) {
    const anchor = [...data].reverse().find((row) => row.date < firstForecast && row.created !== undefined && row.predicted === undefined);
    if (anchor && anchor.created !== undefined) {
      anchor.predicted = anchor.created;
      anchor.band = [anchor.created, anchor.created];
    }
  }
  return data;
}

export interface PredictedCallout {
  date: string;
  /** True when `date` is tomorrow in the browser's calendar. */
  tomorrow: boolean;
  predicted: number;
  lower: number;
  upper: number;
  /** % change vs the baseline; null when the baseline is 0 or unknown. */
  changePercent: number | null;
  /** "today" or a short date. */
  baselineLabel: string;
}

/**
 * "Predicted: 146 (+3% from today)" — tomorrow's forecast compared with today's expected volume (the
 * forecast for today when there is one, otherwise the latest observed day).
 */
export function predictedTomorrow(trend: TrendPoint[], forecast: ForecastPoint[], now: Date = new Date()): PredictedCallout | null {
  if (!forecast.length) return null;
  const sorted = [...forecast].sort((a, b) => a.date.localeCompare(b.date));
  const today = isoDate(now);
  const tomorrow = isoDate(addDays(now, 1));
  const point = sorted.find((p) => p.date === tomorrow) ?? sorted.find((p) => p.date > today) ?? sorted[sorted.length - 1];

  let baseline: number | null = null;
  let baselineLabel = 'today';
  const forecastToday = sorted.find((p) => p.date === today && p.date < point.date);
  if (forecastToday) {
    baseline = forecastToday.predicted;
  } else {
    const observed = [...trend].sort((a, b) => a.date.localeCompare(b.date)).filter((p) => p.date < point.date);
    const last = observed[observed.length - 1];
    if (last) {
      baseline = last.created;
      baselineLabel = last.date === today ? 'today' : formatShortDate(last.date);
    }
  }
  const changePercent = baseline !== null && baseline > 0 ? round1(((point.predicted - baseline) / baseline) * 100) : null;
  return {
    date: point.date,
    tomorrow: point.date === tomorrow,
    predicted: point.predicted,
    lower: Math.max(0, point.lower),
    upper: point.upper,
    changePercent,
    baselineLabel,
  };
}

interface TrendChartProps {
  trend: TrendPoint[];
  forecast: ForecastPoint[];
  method: string;
  /** Height class of the chart wrapper. */
  height?: string;
  /** Show the "Predicted tomorrow" callout (default true when there is a forecast). */
  callout?: boolean;
  /** Legend above the chart instead of beside the panel title (use when the title row has actions). */
  staticLegend?: boolean;
}

export function TrendChart({ trend, forecast, method, height = 'chart-wrap medium', callout = true, staticLegend = false }: TrendChartProps) {
  const data = useMemo(() => buildTrendData(trend, forecast), [trend, forecast]);
  const prediction = useMemo(() => (callout ? predictedTomorrow(trend, forecast) : null), [callout, trend, forecast]);
  if (!trend.some((point) => point.created || point.resolved) && !forecast.length) {
    return <EmptyState title="No incidents in this period" description="Created and resolved counts appear here as incidents are logged." />;
  }
  const change = prediction?.changePercent;
  return (
    <>
      <div className={`chart-legend ${staticLegend ? 'static' : ''}`.trim()}>
        <span>
          <i className="dot gold" /> Created
        </span>
        <span>
          <i className="dot ivory" /> Resolved
        </span>
        {forecast.length > 0 && (
          <span title={forecastLabel(method)}>
            <i className="dash" /> Forecast
          </span>
        )}
      </div>
      <div className="cc-trend-chart">
        {prediction && (
          <div className="cc-predicted" data-testid="predicted-callout">
            <small>{prediction.tomorrow ? 'Predicted tomorrow' : `Predicted ${formatShortDate(prediction.date)}`}</small>
            <b>{formatNumber(Math.round(prediction.predicted))}</b>
            {change !== null && change !== undefined && (
              <span className={change > 0 ? 'red-text' : change < 0 ? 'green-text' : 'muted-text'}>
                {change > 0 ? <TrendingUp size={11} aria-hidden="true" /> : change < 0 ? <TrendingDown size={11} aria-hidden="true" /> : null}
                {` ${change > 0 ? '+' : ''}${formatPercent(change)} from ${prediction.baselineLabel}`}
              </span>
            )}
            <small>
              range {formatNumber(Math.round(prediction.lower))}–{formatNumber(Math.round(prediction.upper))}
            </small>
          </div>
        )}
        <div className={height} role="img" aria-label="Incidents created versus resolved with statistical forecast">
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <defs>
                <linearGradient id="ccCreatedFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={CHART.gold} stopOpacity={0.32} />
                  <stop offset="100%" stopColor={CHART.gold} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" vertical={false} />
              <XAxis dataKey="date" tickFormatter={formatShortDate} tick={axisTick} axisLine={false} tickLine={false} minTickGap={14} />
              <YAxis tick={axisTick} axisLine={false} tickLine={false} width={32} allowDecimals={false} />
              <Tooltip contentStyle={tooltipStyle} labelStyle={tooltipLabelStyle} itemStyle={tooltipItemStyle} labelFormatter={(value) => formatShortDate(String(value))} />
              <Area type="monotone" dataKey="band" stroke="none" fill={CHART.gold} fillOpacity={0.1} name="Forecast range" isAnimationActive={false} />
              <Area type="monotone" dataKey="created" name="Created" stroke={CHART.gold} fill="url(#ccCreatedFill)" strokeWidth={2.4} dot={false} activeDot={{ r: 4 }} />
              <Line type="monotone" dataKey="resolved" name="Resolved" stroke={CHART.goldLight} strokeWidth={1.8} dot={false} activeDot={{ r: 3 }} />
              <Line type="monotone" dataKey="predicted" name="Forecast" stroke={CHART.gold} strokeDasharray="5 5" strokeWidth={2} dot={false} />
              {prediction && <ReferenceDot x={prediction.date} y={round1(prediction.predicted)} r={4.5} fill={CHART.gold} stroke={PALETTE.bg} strokeWidth={2} />}
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      </div>
      {forecast.length > 0 && <p className="cc-chart-note">{forecastLabel(method)} — statistical projection, not an AI prediction.</p>}
    </>
  );
}

// ------------------------------------------------------------------ distribution

export function PriorityDonut({ distribution, label = 'Incidents' }: { distribution: { priority: Priority; count: number }[]; label?: string }) {
  const rows = PRIORITIES.map((priority) => ({
    key: priority,
    label: PRIORITY_LABELS[priority],
    value: distribution.find((entry) => entry.priority === priority)?.count ?? 0,
    color: PRIORITY_COLORS[priority],
  }));
  return (
    <DonutChart
      data={rows}
      centerLabel={label}
      size={150}
      ariaLabel="Incidents by priority"
      empty={<EmptyState title="No incidents in this period" description="The priority mix appears once incidents are logged." />}
    />
  );
}

// ------------------------------------------------------------------ service health

export const SERVICE_STATUS_COLORS: Record<ServiceStatus, string> = {
  OPERATIONAL: PALETTE.success,
  DEGRADED: PALETTE.warning,
  OUTAGE: PALETTE.critical,
  MAINTENANCE: PALETTE.champagne,
};

const STATUS_RANK: Record<ServiceStatus, number> = { OUTAGE: 0, DEGRADED: 1, MAINTENANCE: 2, OPERATIONAL: 3 };

/** Services needing attention first: outage → degraded → maintenance, then critical/open incidents, then availability. */
export function sortServicesByAttention(services: ServiceDto[]): ServiceDto[] {
  return [...services].sort(
    (a, b) =>
      STATUS_RANK[a.status] - STATUS_RANK[b.status] ||
      b.criticalOpen - a.criticalOpen ||
      b.openIncidents - a.openIncidents ||
      a.availability30d - b.availability30d ||
      a.tier - b.tier ||
      a.name.localeCompare(b.name),
  );
}

/** Reference "Service Health" tiles: icon, service name, 30-day availability, coloured status. */
export function ServiceHealthTiles({ services, limit = 4 }: { services: ServiceDto[]; limit?: number }) {
  if (!services.length) return <EmptyState title="No services registered" icon={<Server size={20} />} />;
  return (
    <ul className="cc-service-tiles" aria-label="Service health">
      {sortServicesByAttention(services)
        .slice(0, limit)
        .map((service) => {
          const Icon = serviceIcon(service.name, service.code);
          const color = SERVICE_STATUS_COLORS[service.status];
          return (
            <li key={service.id}>
              <Link
                to={`/services/${service.id}`}
                className="cc-service-tile"
                title={service.statusReason ?? `${service.name}: ${titleCase(service.status)}`}
                aria-label={`${service.name}: ${titleCase(service.status)}, ${formatPercent(service.availability30d)} availability, ${service.openIncidents} open incidents`}
              >
                <span className="icon-tile" aria-hidden="true">
                  <Icon size={16} />
                </span>
                <span className="min-w-0">
                  <b>{service.name}</b>
                  <strong>{formatPercent(service.availability30d)}</strong>
                  <em>
                    <i style={{ background: color }} aria-hidden="true" />
                    {titleCase(service.status)}
                    {service.openIncidents > 0 ? ` · ${service.openIncidents} open` : ''}
                  </em>
                </span>
              </Link>
            </li>
          );
        })}
    </ul>
  );
}

/** Compact list variant (employee home "Service status"). */
export function ServiceHealthList({ services, limit = 6 }: { services: ServiceDto[]; limit?: number }) {
  const navigate = useNavigate();
  if (!services.length) return <EmptyState title="No services registered" icon={<Server size={20} />} />;
  return (
    <div>
      {sortServicesByAttention(services)
        .slice(0, limit)
        .map((service) => (
          <div
            className="service-row cursor-pointer"
            key={service.id}
            role="link"
            tabIndex={0}
            aria-label={`${service.name}: ${titleCase(service.status)}, ${formatPercent(service.availability30d)} availability`}
            onClick={() => navigate(`/services/${service.id}`)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') navigate(`/services/${service.id}`);
            }}
          >
            <span className="service-icon" aria-hidden="true">
              <Server size={15} />
            </span>
            <b className="truncate" title={service.name}>
              {service.name}
            </b>
            <div className="health-track" aria-hidden="true">
              <i style={{ width: `${Math.max(2, Math.min(100, service.availability30d))}%`, background: SERVICE_STATUS_COLORS[service.status] }} />
            </div>
            <small>{formatPercent(service.availability30d)}</small>
          </div>
        ))}
    </div>
  );
}

// ------------------------------------------------------------------ quick links

export function QuickCards({ counts }: { counts: DashboardDto['counts'] }) {
  return (
    <>
      <section className="quick-card">
        <div className="quick-icon">
          <Workflow />
        </div>
        <div>
          <b>Automation & Workflows</b>
          <small>
            {formatNumber(counts.activeWorkflows)} active workflows · {formatNumber(counts.executionsLast24h)} runs in 24 h
          </small>
        </div>
        <ChevronRight size={17} />
        <Link className="card-hit" to="/automation" aria-label="Open Automation" />
      </section>
      <section className="quick-card">
        <div className="quick-icon">
          <BookOpen />
        </div>
        <div>
          <b>Knowledge Base</b>
          <small>
            {formatNumber(counts.publishedArticles)} articles
            {counts.kbResolutionRate !== null ? ` · ${formatPercent(counts.kbResolutionRate, 0)} resolution rate` : ''}
          </small>
        </div>
        <ChevronRight size={17} />
        <Link className="card-hit" to="/knowledge" aria-label="Open Knowledge Base" />
      </section>
      <section className="quick-card">
        <div className="quick-icon">
          <Headphones />
        </div>
        <div>
          <b>Service Desk</b>
          <small>
            {formatNumber(counts.openServiceRequests)} open requests · {formatNumber(counts.pendingApprovals)} pending approvals
          </small>
        </div>
        <ChevronRight size={17} />
        <Link className="card-hit" to="/service-desk" aria-label="Open Service Desk" />
      </section>
    </>
  );
}
