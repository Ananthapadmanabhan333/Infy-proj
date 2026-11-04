import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { formatSlaRemaining } from '../lib/format';
import type { SlaSnapshot } from '../lib/types';
import { describeSla, liveRemaining, SlaCountdown } from './SlaCountdown';

function sla(overrides: Partial<SlaSnapshot> = {}): SlaSnapshot {
  return {
    policyName: 'P2 — High',
    businessHoursOnly: false,
    clockStatus: 'RUNNING',
    health: 'ON_TRACK',
    outcome: null,
    responseDueAt: '2026-11-03T10:30:00Z',
    respondedAt: null,
    responseBreached: false,
    resolutionDueAt: '2026-11-03T14:00:00Z',
    remainingSeconds: 3725,
    percentElapsed: 40,
    escalationLevel: 0,
    ...overrides,
  };
}

describe('SLA countdown formatting', () => {
  it('formats positive remaining time', () => {
    expect(formatSlaRemaining(3725)).toBe('1h 02m');
    expect(formatSlaRemaining(725)).toBe('12m 05s');
    expect(formatSlaRemaining(42)).toBe('42s');
    expect(formatSlaRemaining(2 * 86_400 + 4 * 3600)).toBe('2d 04h');
  });

  it('formats breached (negative) time', () => {
    expect(formatSlaRemaining(-725)).toBe('Breached 12m 05s ago');
    expect(describeSla(sla({ health: 'BREACHED' }), -3700)).toEqual({ text: 'Breached 1h 01m ago', tone: 'breached' });
  });

  it('describes paused and stopped clocks', () => {
    expect(describeSla(sla({ clockStatus: 'PAUSED' }), 600)).toEqual({ text: 'Paused · 10m 00s left', tone: 'paused' });
    expect(describeSla(sla({ clockStatus: 'STOPPED', outcome: 'MET' }), 0)).toEqual({ text: 'Met', tone: 'on-track' });
    expect(describeSla(sla({ clockStatus: 'STOPPED', outcome: 'MISSED' }), -10)).toEqual({ text: 'Missed', tone: 'breached' });
    expect(describeSla(sla({ health: 'AT_RISK' }), 300).tone).toBe('at-risk');
    expect(describeSla(null, 0).text).toBe('—');
  });

  it('only ticks running clocks', () => {
    expect(liveRemaining(sla(), 0, 60_000)).toBe(3665);
    expect(liveRemaining(sla({ clockStatus: 'PAUSED' }), 0, 60_000)).toBe(3725);
  });
});

describe('<SlaCountdown />', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-03T12:00:00Z'));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('ticks down every second from remainingSeconds', () => {
    render(<SlaCountdown sla={sla({ remainingSeconds: 125 })} />);
    expect(screen.getByText('2m 05s')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(screen.getByText('2m 00s')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(130_000);
    });
    expect(screen.getByText('Breached 10s ago')).toBeInTheDocument();
  });

  it('does not tick while paused', () => {
    render(<SlaCountdown sla={sla({ clockStatus: 'PAUSED', resolutionDueAt: null, remainingSeconds: 600 })} />);
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(screen.getByText('Paused · 10m 00s left')).toBeInTheDocument();
  });

  it('renders a dash when there is no SLA', () => {
    render(<SlaCountdown sla={null} />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });
});
