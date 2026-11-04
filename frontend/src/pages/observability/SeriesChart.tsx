import { useMemo } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ReferenceArea, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { axisTick, CHART, SERIES_COLORS, tooltipItemStyle, tooltipLabelStyle, tooltipStyle } from '../../lib/chartTheme';
import { PALETTE } from '../../lib/constants';
import type { AnomalyDto, SeriesDto } from '../../lib/r4Types';
import { attributesText, formatValue, timeTick } from './obsShared';

export const MAX_CHART_SERIES = 10;

export interface ChartSeries {
  key: string;
  seriesId: number;
  name: string;
  color: string;
  simulated: boolean;
}

/** Display name of a series: its service plus its attributes. */
export function seriesLabel(series: SeriesDto): string {
  const attrs = attributesText(series.attributes, 3);
  const base = series.resourceServiceName ?? `series ${series.seriesId}`;
  return attrs ? `${base} · ${attrs}` : base;
}

/**
 * Merges the API's series into Recharts rows keyed by bucket time (ms). Only the first `max` series are charted; values
 * are exactly the API's bucket values (no interpolation, missing buckets stay empty).
 */
export function buildSeriesRows(series: SeriesDto[], max = MAX_CHART_SERIES) {
  const charted = series.slice(0, max);
  const lines: ChartSeries[] = charted.map((entry, index) => ({
    key: `s${index}`,
    seriesId: entry.seriesId,
    name: seriesLabel(entry),
    color: SERIES_COLORS[index % SERIES_COLORS.length],
    simulated: entry.simulated,
  }));
  const rows = new Map<number, Record<string, number>>();
  charted.forEach((entry, index) => {
    entry.points.forEach((point) => {
      const t = Date.parse(point.t);
      if (!Number.isFinite(t)) return;
      const row = rows.get(t) ?? { t };
      row[`s${index}`] = point.v;
      rows.set(t, row);
    });
  });
  return { lines, rows: [...rows.values()].sort((a, b) => a.t - b.t), hidden: Math.max(0, series.length - charted.length) };
}

export interface AnomalyOverlay {
  id: number;
  x1: number;
  x2: number;
  lower: number;
  upper: number;
  observed: number;
  open: boolean;
}

/** Anomaly spans of the charted series, clipped to the window; the band is the API's expected range at the peak. */
export function anomalyOverlays(anomalies: AnomalyDto[] | undefined, seriesIds: number[], from: string, to: string): AnomalyOverlay[] {
  const start = Date.parse(from);
  const end = Date.parse(to);
  const ids = new Set(seriesIds);
  return (anomalies ?? [])
    .filter((anomaly) => ids.has(anomaly.seriesId))
    .map((anomaly) => {
      const x1 = Math.max(start, Date.parse(anomaly.startedAt));
      const last = anomaly.endedAt ?? anomaly.lastPointAt ?? anomaly.startedAt;
      const x2 = Math.min(end, Math.max(Date.parse(last), x1 + 60_000));
      return { id: anomaly.id, x1, x2, lower: anomaly.expectedLower, upper: anomaly.expectedUpper, observed: anomaly.observedValue, open: anomaly.status === 'OPEN' };
    })
    .filter((overlay) => Number.isFinite(overlay.x1) && Number.isFinite(overlay.x2) && overlay.x2 > overlay.x1 - 1);
}

/** Line chart of `/observability/metrics/series` with anomaly spans (red) and the expected range band (gold) of each anomaly. */
export function SeriesChart({
  series,
  anomalies,
  from,
  to,
  unit,
  spanMinutes,
  height = 300,
}: {
  series: SeriesDto[];
  anomalies?: AnomalyDto[];
  from: string;
  to: string;
  unit?: string | null;
  spanMinutes: number;
  height?: number;
}) {
  const { lines, rows, hidden } = useMemo(() => buildSeriesRows(series), [series]);
  const overlays = useMemo(() => anomalyOverlays(anomalies, lines.map((line) => line.seriesId), from, to), [anomalies, lines, from, to]);
  if (!rows.length) return null;
  return (
    <div>
      <div style={{ height }} role="img" aria-label={`Metric chart: ${lines.length} series${overlays.length ? `, ${overlays.length} anomal${overlays.length === 1 ? 'y' : 'ies'} marked` : ''}`}>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={rows} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
            <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" vertical={false} />
            <XAxis dataKey="t" type="number" scale="time" domain={[Date.parse(from), Date.parse(to)]} tick={axisTick} tickFormatter={(value: number) => timeTick(value, spanMinutes)} stroke={CHART.grid} minTickGap={28} />
            <YAxis tick={axisTick} stroke={CHART.grid} width={56} tickFormatter={(value: number) => formatValue(value)} />
            <Tooltip
              contentStyle={tooltipStyle}
              labelStyle={tooltipLabelStyle}
              itemStyle={tooltipItemStyle}
              labelFormatter={(value: number) => new Date(value).toLocaleString()}
              formatter={(value: number) => formatValue(value, unit)}
            />
            {overlays.map((overlay) => (
              <ReferenceArea key={`a${overlay.id}`} x1={overlay.x1} x2={overlay.x2} fill={PALETTE.critical} fillOpacity={0.1} stroke={PALETTE.critical} strokeOpacity={0.35} ifOverflow="hidden" />
            ))}
            {overlays.map((overlay) => (
              <ReferenceArea key={`b${overlay.id}`} x1={overlay.x1} x2={overlay.x2} y1={overlay.lower} y2={overlay.upper} fill={PALETTE.gold} fillOpacity={0.22} ifOverflow="extendDomain" />
            ))}
            {lines.map((line) => (
              <Line key={line.key} type="monotone" dataKey={line.key} name={line.name} stroke={line.color} strokeWidth={1.6} dot={false} connectNulls={false} isAnimationActive={false} />
            ))}
            {lines.length > 1 && <Legend wrapperStyle={{ fontSize: 10 }} />}
          </LineChart>
        </ResponsiveContainer>
      </div>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[10px] text-muted" aria-hidden="true">
        <span>
          <i className="mr-1 inline-block h-2 w-3 align-middle" style={{ background: 'rgba(229,109,104,0.35)' }} /> Anomaly span
        </span>
        <span>
          <i className="mr-1 inline-block h-2 w-3 align-middle" style={{ background: 'rgba(214,183,122,0.45)' }} /> Expected range (detector baseline ± threshold·σ)
        </span>
      </div>
      {hidden > 0 && <p className="mb-0 mt-1 text-[11px] text-muted">{hidden} more series not charted — narrow the filters to see them.</p>}
    </div>
  );
}
