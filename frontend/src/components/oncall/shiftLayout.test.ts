import { describe, expect, it } from 'vitest';
import type { ShiftDto } from '../../lib/responseTypes';
import { addDaysToKey, formatZoned, layoutShifts, offsetLabel, startOfDayKey, weekStartKey, zonedDateKey, zonedParts, zonedTimeToUtc } from './shiftLayout';
import { zonedInputToIso } from '../../pages/oncall/OnCallDialogs';

function shift(start: string, end: string, overrides: Partial<ShiftDto> = {}): ShiftDto {
  return { layer: 'PRIMARY', userId: 1, userName: 'Riya Varma', start, end, source: 'ROTATION', scheduledUserId: 1, scheduledUserName: 'Riya Varma', holiday: false, overrideId: null, ...overrides };
}

describe('time-zone arithmetic (Intl only)', () => {
  it('converts a wall-clock time in a fixed-offset zone', () => {
    // 09:00 IST = 03:30 UTC
    expect(new Date(zonedTimeToUtc(2026, 11, 2, 'Asia/Kolkata', 9, 0)).toISOString()).toBe('2026-11-02T03:30:00.000Z');
    expect(zonedParts(Date.parse('2026-11-02T03:30:00Z'), 'Asia/Kolkata')).toMatchObject({ year: 2026, month: 11, day: 2, hour: 9, minute: 0, weekday: 1 });
    expect(offsetLabel('Asia/Kolkata', Date.parse('2026-11-02T00:00:00Z'))).toBe('UTC+05:30');
  });

  it('handles daylight saving in Europe/London (25-hour day on 25 Oct 2026)', () => {
    expect(new Date(startOfDayKey('2026-10-25', 'Europe/London')).toISOString()).toBe('2026-10-24T23:00:00.000Z');
    expect(new Date(startOfDayKey('2026-10-26', 'Europe/London')).toISOString()).toBe('2026-10-26T00:00:00.000Z');
    expect(new Date(zonedTimeToUtc(2026, 7, 1, 'Europe/London', 9, 0)).toISOString()).toBe('2026-07-01T08:00:00.000Z');
  });

  it('finds the Monday that starts the local week', () => {
    // Sunday 22:00 UTC is already Monday 03:30 in Kolkata.
    expect(weekStartKey(Date.parse('2026-11-08T22:00:00Z'), 'Asia/Kolkata')).toBe('2026-11-09');
    expect(weekStartKey(Date.parse('2026-11-08T22:00:00Z'), 'UTC')).toBe('2026-11-02');
    expect(zonedDateKey(Date.parse('2026-11-08T22:00:00Z'), 'Asia/Kolkata')).toBe('2026-11-09');
    expect(addDaysToKey('2026-12-30', 3)).toBe('2027-01-02');
  });

  it('formats instants in the schedule zone and parses zone-local form input', () => {
    expect(formatZoned('2026-11-02T03:30:00Z', 'Asia/Kolkata')).toMatch(/09:00/);
    expect(zonedInputToIso('2026-11-05T09:00', 'Asia/Kolkata')).toBe('2026-11-05T03:30:00.000Z');
    expect(zonedInputToIso('', 'Asia/Kolkata')).toBeNull();
  });
});

describe('layoutShifts', () => {
  it('splits a multi-day shift into one segment per local day with continuation flags', () => {
    const days = layoutShifts([shift('2026-11-02T03:30:00Z', '2026-11-04T03:30:00Z')], [], '2026-11-02', 3, 'Asia/Kolkata');
    expect(days.map((day) => day.primary.length)).toEqual([1, 1, 1]);
    const [first, middle, last] = days.map((day) => day.primary[0]);
    expect(first.left).toBeCloseTo((9 / 24) * 100);
    expect(first.continuesAfter).toBe(true);
    expect(middle.left).toBe(0);
    expect(middle.width).toBeCloseTo(100);
    expect(last.width).toBeCloseTo((9 / 24) * 100);
    expect(last.continuesBefore).toBe(true);
  });

  it('keeps primary and secondary lanes apart and skips shifts outside the window', () => {
    const days = layoutShifts(
      [shift('2026-11-01T00:00:00Z', '2026-11-01T06:00:00Z')],
      [shift('2026-11-02T06:30:00Z', '2026-11-02T12:30:00Z', { layer: 'SECONDARY', source: 'OVERRIDE' })],
      '2026-11-02',
      1,
      'Asia/Kolkata',
    );
    expect(days[0].primary).toHaveLength(0);
    expect(days[0].secondary).toHaveLength(1);
    expect(days[0].secondary[0].left).toBeCloseTo(50);
    expect(days[0].secondary[0].width).toBeCloseTo(25);
  });

  it('reports a 25-hour day across the DST change', () => {
    const days = layoutShifts([], [], '2026-10-24', 3, 'Europe/London');
    expect(days.map((day) => day.hours)).toEqual([24, 25, 24]);
  });
});
