import { useId, useMemo } from 'react';
import { Area, AreaChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { CHART, axisTick, tooltipItemStyle, tooltipLabelStyle, tooltipStyle } from '../../lib/chartTheme';
import { formatShortDate } from '../../lib/format';
import { formatAvailability } from './serviceModel';
import { EmptyState } from '../States';

interface AvailabilityChartProps {
  /** Daily availability, oldest first (ServiceDetail.availabilityHistory, last 90 days). */
  history: { date: string; availability: number }[];
}

/** Lower bound of the y axis: a little below the worst day, never above 99 and never below 0. */
export function availabilityFloor(values: number[]): number {
  if (values.length === 0) return 0;
  const min = Math.min(...values);
  return Math.max(0, Math.min(99, Math.floor(min - 1)));
}

/** 90-day availability area chart with the period average as a reference line. */
export function AvailabilityChart({ history }: AvailabilityChartProps) {
  const gradientId = useId().replace(/:/g, '');
  const values = useMemo(() => history.map((point) => point.availability), [history]);
  if (history.length === 0) return <EmptyState title="No availability history yet" description="Daily availability is recorded once the service has been monitored." />;
  const average = values.reduce((sum, value) => sum + value, 0) / values.length;
  const worst = history.reduce((lowest, point) => (point.availability < lowest.availability ? point : lowest), history[0]);
  const floor = availabilityFloor(values);

  return (
    <div>
      <div className="mb-2 flex flex-wrap gap-x-5 gap-y-1 text-[10px] text-muted">
        <span>
          Average <b className="text-[12px] text-gold-light">{formatAvailability(average)}</b>
        </span>
        <span>
          Lowest <b className="text-[12px] text-ivory">{formatAvailability(worst.availability)}</b> on {formatShortDate(worst.date)}
        </span>
        <span>{history.length} days</span>
      </div>
      <div className="chart-wrap medium" role="img" aria-label={`Daily availability over ${history.length} days, average ${formatAvailability(average)}, lowest ${formatAvailability(worst.availability)}`}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={history} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
            <defs>
              <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={CHART.gold} stopOpacity={0.3} />
                <stop offset="100%" stopColor={CHART.gold} stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" vertical={false} />
            <XAxis dataKey="date" tickFormatter={(value) => formatShortDate(String(value))} tick={axisTick} axisLine={false} tickLine={false} minTickGap={24} />
            <YAxis domain={[floor, 100]} tick={axisTick} axisLine={false} tickLine={false} width={40} unit="%" allowDecimals={false} />
            <Tooltip
              contentStyle={tooltipStyle}
              labelStyle={tooltipLabelStyle}
              itemStyle={tooltipItemStyle}
              labelFormatter={(value) => formatShortDate(String(value))}
              formatter={(value) => [formatAvailability(Number(value)), 'Availability']}
            />
            <ReferenceLine y={average} stroke={CHART.bronze} strokeDasharray="4 4" />
            <Area type="monotone" dataKey="availability" name="Availability" stroke={CHART.gold} strokeWidth={2} fill={`url(#${gradientId})`} dot={false} activeDot={{ r: 4 }} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
