import { useMemo } from 'react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { axisTick, CHART, SERIES_COLORS, tooltipItemStyle, tooltipLabelStyle, tooltipStyle } from '../../lib/chartTheme';
import { formatShortDate } from '../../lib/format';
import type { ServiceVolumePoint } from '../../lib/types';
import { EmptyState } from '../States';

export interface VolumeSeries {
  /** Safe Recharts data key (service names can contain dots). */
  key: string;
  name: string;
  color: string;
  total: number;
}

export function buildServiceVolume(points: ServiceVolumePoint[] | undefined, names: string[] | undefined) {
  const list = points ?? [];
  const totals = new Map<string, number>();
  list.forEach((point) => Object.entries(point.counts ?? {}).forEach(([name, count]) => totals.set(name, (totals.get(name) ?? 0) + (count || 0))));
  // Series order comes from the API (`serviceNames`); otherwise biggest first.
  const order = names && names.length ? names : [...totals.entries()].sort((a, b) => b[1] - a[1]).map(([name]) => name);
  const series: VolumeSeries[] = order.map((name, index) => ({ key: `s${index}`, name, color: SERIES_COLORS[index % SERIES_COLORS.length], total: totals.get(name) ?? 0 }));
  const rows = list.map((point) => {
    const row: Record<string, string | number> = { date: point.date };
    series.forEach((entry) => {
      row[entry.key] = point.counts?.[entry.name] ?? 0;
    });
    return row;
  });
  return { rows, series, total: [...totals.values()].reduce((sum, value) => sum + value, 0) };
}

/** Reference "Incident Volume by Service": stacked daily areas for the top services. */
export function ServiceVolumeChart({ points, names }: { points: ServiceVolumePoint[] | undefined; names: string[] | undefined }) {
  const { rows, series, total } = useMemo(() => buildServiceVolume(points, names), [points, names]);
  if (!rows.length || !series.length || total === 0) {
    return <EmptyState title="No incidents in this period" description="Daily volume for the busiest services appears here as incidents are logged." />;
  }
  return (
    <>
      <ul className="an-legend" aria-label="Services">
        {series.map((entry) => (
          <li key={entry.key}>
            <i style={{ background: entry.color }} aria-hidden="true" />
            {entry.name}
          </li>
        ))}
      </ul>
      <div className="chart-wrap large" role="img" aria-label={`Daily incident volume for ${series.map((entry) => entry.name).join(', ')}`}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={rows} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
            <defs>
              {series.map((entry) => (
                <linearGradient key={entry.key} id={`anVol-${entry.key}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={entry.color} stopOpacity={0.42} />
                  <stop offset="100%" stopColor={entry.color} stopOpacity={0.06} />
                </linearGradient>
              ))}
            </defs>
            <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" vertical={false} />
            <XAxis dataKey="date" tickFormatter={formatShortDate} tick={axisTick} axisLine={false} tickLine={false} minTickGap={14} />
            <YAxis tick={axisTick} axisLine={false} tickLine={false} width={32} allowDecimals={false} />
            <Tooltip contentStyle={tooltipStyle} labelStyle={tooltipLabelStyle} itemStyle={tooltipItemStyle} labelFormatter={(value) => formatShortDate(String(value))} />
            {series.map((entry) => (
              <Area
                key={entry.key}
                type="monotone"
                dataKey={entry.key}
                name={entry.name}
                stackId="volume"
                stroke={entry.color}
                strokeWidth={1.6}
                fill={`url(#anVol-${entry.key})`}
                isAnimationActive={false}
              />
            ))}
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </>
  );
}
