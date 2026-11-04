import { screen, within } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '../../test/utils';
import type { ForecastPoint, ServiceDto, TrendPoint } from '../../lib/types';
import { topInsight } from './AiInsightCard';
import { buildTrendData, isoDate, predictedTomorrow, ServiceHealthTiles, sortServicesByAttention, TrendChart } from './Widgets';

beforeAll(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});

const NOW = new Date(2026, 10, 3, 9, 30); // 3 Nov 2026, local time
const trend: TrendPoint[] = [
  { date: '2026-11-01', created: 10, resolved: 8 },
  { date: '2026-11-02', created: 12, resolved: 11 },
  { date: '2026-11-03', created: 4, resolved: 2 }, // today, partial
];
// The API fits on full days, so the forecast starts today.
const forecast: ForecastPoint[] = [
  { date: '2026-11-03', predicted: 12.5, lower: 9, upper: 16 },
  { date: '2026-11-04', predicted: 13, lower: 9.4, upper: 16.6 },
  { date: '2026-11-05', predicted: 13.5, lower: 10, upper: 17 },
];

function service(overrides: Partial<ServiceDto>): ServiceDto {
  return {
    id: 1,
    code: 'SVC',
    name: 'Service',
    description: null,
    departmentId: 1,
    departmentName: 'IT',
    ownerId: null,
    ownerName: null,
    tier: 2,
    status: 'OPERATIONAL',
    statusReason: null,
    availability30d: 99.9,
    openIncidents: 0,
    criticalOpen: 0,
    maintenance: false,
    keywords: [],
    demo: false,
    ...overrides,
  };
}

describe('trend & forecast data', () => {
  it('merges overlapping forecast days into the observed series and joins the dashed line to the last full day', () => {
    const data = buildTrendData(trend, forecast);
    expect(data.map((row) => row.date)).toEqual(['2026-11-01', '2026-11-02', '2026-11-03', '2026-11-04', '2026-11-05']);
    const today = data[2];
    expect(today).toMatchObject({ created: 4, resolved: 2, predicted: 12.5, band: [9, 16] });
    // Yesterday anchors the forecast line on the observed value.
    expect(data[1]).toMatchObject({ created: 12, predicted: 12, band: [12, 12] });
    expect(data[3].created).toBeUndefined();
  });

  it('computes the predicted-tomorrow callout against today’s expected volume', () => {
    const callout = predictedTomorrow(trend, forecast, NOW);
    expect(callout).toMatchObject({ date: '2026-11-04', tomorrow: true, predicted: 13, baselineLabel: 'today' });
    expect(callout?.changePercent).toBe(4); // 13 vs 12.5
  });

  it('falls back to the latest observed day when the forecast starts tomorrow', () => {
    const callout = predictedTomorrow(trend.slice(0, 2), forecast.slice(1), NOW);
    expect(callout).toMatchObject({ date: '2026-11-04', baselineLabel: '2 Nov' });
    expect(callout?.changePercent).toBeCloseTo(8.3, 1); // 13 vs 12
    expect(predictedTomorrow(trend, [], NOW)).toBeNull();
  });

  it('formats local calendar dates', () => {
    expect(isoDate(NOW)).toBe('2026-11-03');
  });

  it('renders the legend, forecast note and callout', () => {
    renderWithProviders(<TrendChart trend={trend} forecast={forecast} method="Statistical forecast: least-squares linear trend" />);
    expect(screen.getByText('Created')).toBeInTheDocument();
    expect(screen.getByText('Resolved')).toBeInTheDocument();
    expect(screen.getByText('Forecast')).toBeInTheDocument();
    expect(screen.getByTestId('predicted-callout')).toBeInTheDocument();
    expect(screen.getByText(/statistical projection, not an AI prediction/)).toBeInTheDocument();
  });

  it('shows an empty state without data', () => {
    renderWithProviders(<TrendChart trend={[{ date: '2026-11-03', created: 0, resolved: 0 }]} forecast={[]} method="" />);
    expect(screen.getByText('No incidents in this period')).toBeInTheDocument();
  });
});

describe('service health tiles', () => {
  const services = [
    service({ id: 1, name: 'Payroll', code: 'PAYROLL', availability30d: 99.9 }),
    service({ id: 2, name: 'HR Portal', code: 'HR', status: 'DEGRADED', availability30d: 98.2, openIncidents: 3 }),
    service({ id: 3, name: 'Network', code: 'NET', status: 'OUTAGE', availability30d: 97.1, criticalOpen: 1, openIncidents: 2 }),
    service({ id: 4, name: 'Email', code: 'MAIL', availability30d: 99.8, openIncidents: 1 }),
    service({ id: 5, name: 'Facilities', code: 'FAC', availability30d: 100 }),
  ];

  it('orders services needing attention first', () => {
    expect(sortServicesByAttention(services).map((entry) => entry.name)).toEqual(['Network', 'HR Portal', 'Email', 'Payroll', 'Facilities']);
  });

  it('renders up to the limit as links with availability', () => {
    renderWithProviders(<ServiceHealthTiles services={services} limit={4} />);
    const list = screen.getByRole('list', { name: 'Service health' });
    const links = within(list).getAllByRole('link');
    expect(links).toHaveLength(4);
    expect(links[0]).toHaveAttribute('href', '/services/3');
    expect(links[0]).toHaveAccessibleName(/Network: Outage, 97.1% availability, 2 open incidents/);
    expect(within(list).getByText('98.2%')).toBeInTheDocument();
    expect(within(list).queryByText('Facilities')).not.toBeInTheDocument();
  });
});

describe('top AI insight', () => {
  it('prefers the most severe, then most confident insight', () => {
    const top = topInsight([
      { title: 'SLA healthy', detail: '', severity: 'INFO', metric: null, confidence: 'HIGH' },
      { title: 'Payroll peak', detail: '', severity: 'WARNING', metric: '78%', confidence: 'MEDIUM' },
      { title: 'Network spike', detail: '', severity: 'WARNING', metric: null, confidence: 'HIGH' },
    ]);
    expect(top?.title).toBe('Network spike');
    expect(topInsight([])).toBeNull();
  });
});
