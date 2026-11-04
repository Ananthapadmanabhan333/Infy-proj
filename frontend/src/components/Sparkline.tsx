import { useId } from 'react';
import { PALETTE } from '../lib/constants';

interface SparklineProps {
  /** Values in order (e.g. incidents per hour). Fewer than 2 points renders nothing. */
  data: number[];
  width?: number;
  height?: number;
  color?: string;
  /** Soft area fill under the line (default true). */
  fill?: boolean;
  /** Highlight the last point with a glowing dot (default true). */
  showLast?: boolean;
  /** Accessible description, e.g. "Incidents by hour of day, peak 10:00–12:00". */
  label: string;
  className?: string;
}

/** Tiny dependency-free SVG trend line (reference "Insight of the Day"). */
export function Sparkline({ data, width = 160, height = 48, color = PALETTE.gold, fill = true, showLast = true, label, className = '' }: SparklineProps) {
  const id = useId().replace(/:/g, '');
  if (data.length < 2) return null;
  const pad = 3;
  const max = Math.max(...data);
  const min = Math.min(...data, 0);
  const range = max - min || 1;
  const step = (width - pad * 2) / (data.length - 1);
  const points = data.map((value, index) => [pad + index * step, pad + (height - pad * 2) * (1 - (value - min) / range)] as const);
  const line = points.map(([x, y], index) => `${index ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `${line} L${points[points.length - 1][0].toFixed(1)},${height} L${points[0][0].toFixed(1)},${height} Z`;
  const [lastX, lastY] = points[points.length - 1];
  return (
    <svg className={`sparkline ${className}`} width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label}>
      <defs>
        <linearGradient id={`spark-${id}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity={0.32} />
          <stop offset="100%" stopColor={color} stopOpacity={0} />
        </linearGradient>
      </defs>
      {fill && <path d={area} fill={`url(#spark-${id})`} stroke="none" />}
      <path d={line} fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      {showLast && <circle cx={lastX} cy={lastY} r={3} fill={color} style={{ filter: `drop-shadow(0 0 4px ${color})` }} />}
    </svg>
  );
}
