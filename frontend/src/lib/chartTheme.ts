import type { CSSProperties } from 'react';
import { PALETTE } from './constants';
import type { Priority } from './types';

// Chart styling, pointed at the spec §15 palette (gold / champagne / bronze + status colours; no blue).
export const CHART = {
  gold: PALETTE.gold,
  goldLight: PALETTE.champagne,
  bronze: PALETTE.bronze,
  ivory: PALETTE.champagne,
  amber: PALETTE.warning,
  red: PALETTE.critical,
  green: PALETTE.success,
  success: PALETTE.success,
  warning: PALETTE.warning,
  critical: PALETTE.critical,
  /** Release 1 key; re-toned to a warm taupe. */
  blue: '#B9A78A',
  grid: '#332b23',
  tick: '#958b7c',
};

export const PRIORITY_COLORS: Record<Priority, string> = {
  CRITICAL: PALETTE.critical,
  HIGH: PALETTE.warning,
  MEDIUM: PALETTE.gold,
  LOW: '#CDBFA6',
};

/** Ordered series colours for multi-series charts (e.g. "Incident Volume by Service"). */
export const SERIES_COLORS = [PALETTE.gold, PALETTE.champagne, PALETTE.bronze, PALETTE.warning, '#B9A78A', PALETTE.success, PALETTE.critical, '#6F5A3E', '#E8D3A9'];

export const tooltipStyle: CSSProperties = {
  background: '#211e1b',
  border: '1px solid #59452b',
  borderRadius: 10,
  color: '#f5f1ea',
  fontSize: 11,
};

export const tooltipLabelStyle: CSSProperties = { color: PALETTE.champagne, fontWeight: 700 };
export const tooltipItemStyle: CSSProperties = { color: '#f5f1ea' };

export const axisTick = { fill: CHART.tick, fontSize: 10 };
