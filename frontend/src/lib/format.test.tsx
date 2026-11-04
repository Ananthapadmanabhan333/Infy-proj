import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Badge, DemoBadge, PriorityBadge, StatusBadge } from '../components/Badge';
import { badgeClass, forecastLabel, formatCountdown, formatDelta, formatMinutes, formatPercent, titleCase } from './format';

describe('enum formatting', () => {
  it('converts enums to Title Case', () => {
    expect(titleCase('IN_PROGRESS')).toBe('In Progress');
    expect(titleCase('PENDING_APPROVAL')).toBe('Pending Approval');
    expect(titleCase('KNOWN_ERROR')).toBe('Known Error');
    expect(titleCase('SLA_AT_RISK')).toBe('SLA At Risk');
    expect(titleCase('CRITICAL')).toBe('Critical');
    expect(titleCase(null)).toBe('');
  });

  it('derives badge modifier classes', () => {
    expect(badgeClass('IN_PROGRESS')).toBe('in-progress');
    expect(badgeClass('ON_HOLD')).toBe('on-hold');
    expect(badgeClass('AT_RISK')).toBe('at-risk');
    expect(badgeClass('LLM')).toBe('llm');
  });

  it('formats numbers, durations and deltas', () => {
    expect(formatPercent(96.42)).toBe('96.4%');
    expect(formatPercent(100)).toBe('100%');
    expect(formatPercent(null)).toBe('—');
    expect(formatMinutes(28)).toBe('28 min');
    expect(formatMinutes(185)).toBe('3 h 05 m');
    expect(formatMinutes(null)).toBe('—');
    expect(formatDelta(12, '%')).toBe('↑ 12%');
    expect(formatDelta(-2.14, ' pp')).toBe('↓ 2.1 pp');
    expect(formatDelta(null, '%')).toBe('—');
    expect(formatCountdown(90061)).toBe('1d 01h');
  });

  it('labels forecasts as statistical, never AI', () => {
    expect(forecastLabel('LINEAR_REGRESSION')).toBe('Statistical forecast · Linear Regression');
    expect(forecastLabel('Holt-Winters exponential smoothing')).toBe('Statistical forecast · Holt-Winters exponential smoothing');
    expect(forecastLabel(null)).toBe('Statistical forecast');
    expect(forecastLabel('LINEAR_REGRESSION')).not.toMatch(/\bAI\b/);
  });
});

describe('Badge components', () => {
  it('renders the Title Case label with a status class', () => {
    render(<StatusBadge status="PENDING_APPROVAL" />);
    const badge = screen.getByText('Pending Approval');
    expect(badge).toHaveClass('badge', 'pending-approval');
  });

  it('renders priority badges', () => {
    render(<PriorityBadge priority="CRITICAL" />);
    expect(screen.getByText('Critical')).toHaveClass('badge', 'critical');
  });

  it('supports custom labels and renders nothing for empty values', () => {
    const { container } = render(
      <>
        <Badge value="BREACHED" label="Breached 5m ago" />
        <Badge value={null} />
      </>,
    );
    expect(screen.getByText('Breached 5m ago')).toHaveClass('breached');
    expect(container.querySelectorAll('.badge')).toHaveLength(1);
  });

  it('only shows the DEMO badge for demo records', () => {
    const { rerender } = render(<DemoBadge demo />);
    expect(screen.getByText('DEMO')).toHaveClass('badge', 'demo');
    rerender(<DemoBadge demo={false} />);
    expect(screen.queryByText('DEMO')).not.toBeInTheDocument();
  });
});
