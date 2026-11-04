import { useMemo } from 'react';
import { Area, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis, type TooltipProps } from 'recharts';
import { axisTick, CHART, tooltipStyle } from '../../lib/chartTheme';
import { formatNumber, formatPercent, formatShortDate } from '../../lib/format';
import type { SlaOverview } from '../../lib/types';
import { EmptyState } from '../States';

type TrendPoint = SlaOverview['trend'][number];

/** Y-axis floor: a little below the lowest day, rounded down to 5 (90–100% in the reference). */
export function complianceDomain(trend: TrendPoint[]): [number, number] {
  const values = trend.map((point) => point.compliance).filter((value): value is number => value !== null && value !== undefined);
  if (!values.length) return [0, 100];
  const min = Math.min(...values);
  return [Math.max(0, Math.floor((min - 2) / 5) * 5), 100];
}

export function latestCompliance(trend: TrendPoint[]): TrendPoint | null {
  for (let index = trend.length - 1; index >= 0; index -= 1) {
    const point = trend[index];
    if (point.compliance !== null && point.compliance !== undefined) return point;
  }
  return null;
}

function TrendTooltip({ active, payload }: TooltipProps<number, string>) {
  if (!active || !payload?.length) return null;
  const point = payload[0].payload as TrendPoint;
  return (
    <div style={{ ...tooltipStyle, padding: '8px 10px' }}>
      <b style={{ color: CHART.goldLight }}>{formatShortDate(point.date)}</b>
      <div>Compliance: {formatPercent(point.compliance)}</div>
      <div>
        Met {formatNumber(point.met)} · Missed {formatNumber(point.missed)}
      </div>
    </div>
  );
}

/** Reference "SLA Compliance Trend": daily resolution-SLA compliance with the latest value called out. */
export function SlaComplianceTrend({ trend }: { trend: TrendPoint[] }) {
  const domain = useMemo(() => complianceDomain(trend), [trend]);
  const latest = useMemo(() => latestCompliance(trend), [trend]);
  if (!latest) return <EmptyState title="No completed SLAs in this period" description="Compliance appears once incidents with an SLA are resolved." />;
  const met = trend.reduce((sum, point) => sum + point.met, 0);
  const missed = trend.reduce((sum, point) => sum + point.missed, 0);
  return (
    <div className="sla-trend">
      <div className="sla-trend-callout" data-testid="sla-latest">
        <b>{formatPercent(latest.compliance)}</b>
        <small>{formatShortDate(latest.date)}</small>
      </div>
      <div className="chart-wrap large" role="img" aria-label={`SLA compliance by day; latest ${formatPercent(latest.compliance)}; ${met} met and ${missed} missed in the period`}>
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={trend} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
            <defs>
              <linearGradient id="slaComplianceFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={CHART.gold} stopOpacity={0.3} />
                <stop offset="100%" stopColor={CHART.gold} stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" vertical={false} />
            <XAxis dataKey="date" tickFormatter={formatShortDate} tick={axisTick} axisLine={false} tickLine={false} minTickGap={14} />
            <YAxis domain={domain} tick={axisTick} axisLine={false} tickLine={false} width={40} tickFormatter={(value: number) => `${value}%`} allowDecimals={false} />
            <Tooltip content={<TrendTooltip />} />
            <Area type="monotone" dataKey="compliance" stroke="none" fill="url(#slaComplianceFill)" connectNulls isAnimationActive={false} />
            <Line
              type="monotone"
              dataKey="compliance"
              name="Compliance"
              stroke={CHART.gold}
              strokeWidth={2.2}
              dot={{ r: 2.6, fill: CHART.gold, strokeWidth: 0 }}
              activeDot={{ r: 4.5 }}
              connectNulls
            />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
