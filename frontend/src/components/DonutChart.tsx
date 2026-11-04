import type { CSSProperties, ReactNode } from 'react';
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { SERIES_COLORS, tooltipItemStyle, tooltipStyle } from '../lib/chartTheme';
import { formatNumber, formatPercent, percentOf } from '../lib/format';

export interface DonutDatum {
  /** Stable key (defaults to label). */
  key?: string;
  label: string;
  value: number;
  /** Defaults to the series palette. */
  color?: string;
}

interface DonutChartProps {
  data: DonutDatum[];
  /** Small text under the centre total, e.g. "Incidents". */
  centerLabel?: string;
  /** Centre value; defaults to the sum of `data`. */
  centerValue?: ReactNode;
  /** Diameter in px (default 150). */
  size?: number;
  /** Legend with count and percent (default true). */
  legend?: boolean;
  ariaLabel?: string;
  /** Rendered instead of the chart when every value is 0. */
  empty?: ReactNode;
}

/** Donut with the total in the centre and a legend of counts / percentages (reference "Incident Distribution"). */
export function DonutChart({ data, centerLabel, centerValue, size = 150, legend = true, ariaLabel, empty = null }: DonutChartProps) {
  const rows = data.map((row, index) => ({ ...row, key: row.key ?? row.label, color: row.color ?? SERIES_COLORS[index % SERIES_COLORS.length] }));
  const total = rows.reduce((sum, row) => sum + (row.value > 0 ? row.value : 0), 0);
  if (!total) return <>{empty}</>;
  const inner = Math.round(size * 0.36);
  const outer = Math.round(size * 0.5) - 2;
  return (
    <div className="donut-chart distribution-body" style={{ '--donut-size': `${size}px` } as CSSProperties}>
      <div className="donut" role="img" aria-label={`${ariaLabel ?? centerLabel ?? 'Distribution'}: ${formatNumber(total)} in total`}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={rows} dataKey="value" nameKey="label" innerRadius={inner} outerRadius={outer} paddingAngle={3} stroke="none" isAnimationActive={false}>
              {rows.map((row) => (
                <Cell key={row.key} fill={row.color} />
              ))}
            </Pie>
            <Tooltip contentStyle={tooltipStyle} itemStyle={tooltipItemStyle} />
          </PieChart>
        </ResponsiveContainer>
        <div className="donut-center">
          <b>{centerValue ?? formatNumber(total)}</b>
          {centerLabel && <small>{centerLabel}</small>}
        </div>
      </div>
      {legend && (
        <div className="legend-list">
          {rows.map((row) => (
            <div key={row.key}>
              <i style={{ background: row.color }} />
              <span>{row.label}</span>
              <b>{formatNumber(row.value)}</b>
              <small>{formatPercent(percentOf(row.value, total), 0)}</small>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
