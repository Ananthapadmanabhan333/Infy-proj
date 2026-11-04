import { cloneElement, type ReactElement } from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { seriesQueryParams } from '../../api/observability';
import { pageOf } from '../../test/itsmFixtures';
import { apiErrorBody, json, mockApi, requests, type MockHandler, type MockReply } from '../../test/mockApi';
import { KINDS, makeAnomaly, makeDetector, makeGroup, makeSeries, makeSource } from '../../test/r4Fixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import { evaluationSummary, parseAttributeLines } from './AnomaliesTab';
import ObservabilityPage from './ObservabilityPage';
import { retentionError } from './RetentionTab';
import { anomalyOverlays, buildSeriesRows } from './SeriesChart';
import { flattenSpans, spanBar } from './TracesTab';

// jsdom has no layout: give the chart a fixed size instead of measuring its container.
vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('recharts')>();
  return { ...actual, ResponsiveContainer: ({ children }: { children: ReactElement }) => cloneElement(children, { width: 800, height: 300 } as never) };
});

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const OVERVIEW = { includeSimulated: false, sources: 1, connectedSources: 0, resources: 4, unmappedResources: 1, series: 24, openAnomalies: 1, openCorrelationGroups: 1, detectors: 3, simulatedDataPresent: true, lastDataAt: '2026-11-04T09:59:00Z' };

function routes(extra: Record<string, MockHandler | MockReply> = {}) {
  mockApi({
    'GET /observability/overview': (config) => json({ ...OVERVIEW, includeSimulated: Boolean(config.params?.includeSimulated) }),
    'GET /observability/anomalies': json(pageOf([makeAnomaly()])),
    'GET /observability/correlation-groups': json(pageOf([{ ...makeGroup(), members: null }, { ...makeGroup({ id: 4, title: 'HR portal latency' }), members: null }])),
    'GET /observability/correlation-groups/3': json(makeGroup()),
    'GET /observability/metrics': json([
      { name: 'db.client.connections.usage', type: 'gauge', unit: '{connection}', seriesCount: 2, lastSeenAt: '2026-11-04T09:59:00Z', simulated: false },
      { name: 'http.server.request.duration', type: 'histogram', unit: 'ms', seriesCount: 4, lastSeenAt: '2026-11-04T09:59:00Z', simulated: true },
    ]),
    'GET /observability/metrics/series': json(makeSeries()),
    'GET /observability/anomaly-detectors': json([makeDetector()]),
    'GET /observability/sources': json([makeSource()]),
    'GET /observability/sources/kinds': json(KINDS),
    'GET /services': json([{ id: 1, code: 'PAY', name: 'Payroll', tier: 1 }]),
    ...extra,
  });
}

describe('pure helpers', () => {
  it('sends attribute filters as attr.<key> and drops empty ones', () => {
    expect(seriesQueryParams({ name: 'm', aggregation: 'p95', attrs: { state: 'used', ' pool ': 'main', empty: '' } })).toEqual({ name: 'm', aggregation: 'p95', 'attr.state': 'used', 'attr.pool': 'main' });
  });

  it('merges series by bucket time and overlays anomalies of the charted series only', () => {
    const series = makeSeries().series;
    const { rows, lines } = buildSeriesRows([...series, { ...series[0], seriesId: 32, points: [{ t: '2026-11-04T09:01:00Z', v: 3, samples: 1 }] }]);
    expect(lines.map((line) => line.seriesId)).toEqual([31, 32]);
    expect(rows).toHaveLength(3);
    expect(rows[1]).toMatchObject({ s0: 14, s1: 3 });
    expect(rows[0].s1).toBeUndefined();
    const overlays = anomalyOverlays([makeAnomaly(), makeAnomaly({ id: 6, seriesId: 99 })], [31], '2026-11-04T09:00:00Z', '2026-11-04T10:00:00Z');
    expect(overlays).toEqual([{ id: 5, x1: Date.parse('2026-11-04T09:02:00Z'), x2: Date.parse('2026-11-04T09:05:00Z'), lower: 9, upper: 17, observed: 41, open: true }]);
  });

  it('flattens span trees depth-first and positions waterfall bars', () => {
    const span = (spanId: string, offsetMs: number, durationMs: number, children: never[] = []) =>
      ({ spanId, parentSpanId: null, name: spanId, kind: 'SERVER', serviceName: 'svc', serviceId: null, resourceId: null, startTime: '', endTime: '', durationMs, offsetMs, statusCode: 'OK', statusMessage: null, attributes: null, simulated: false, children }) as never;
    const flat = flattenSpans([span('root', 0, 100, [span('b', 50, 10), span('a', 10, 20)] as never)]);
    expect(flat.map((entry) => `${entry.span.spanId}:${entry.depth}`)).toEqual(['root:0', 'a:1', 'b:1']);
    expect(spanBar({ offsetMs: 50, durationMs: 10 }, 100)).toEqual({ left: 50, width: 10 });
    expect(spanBar({ offsetMs: 0, durationMs: 0 }, 0)).toEqual({ left: 0, width: 100 });
  });

  it('describes evaluations honestly, including insufficient data', () => {
    expect(evaluationSummary(null)).toBe('Not evaluated yet');
    expect(evaluationSummary({ status: 'INSUFFICIENT_DATA', message: 'No labelled faults in the window' } as never)).toBe('Insufficient data — No labelled faults in the window');
    expect(evaluationSummary({ status: 'EVALUATED', precision: 0.75, recall: null, labels: [1, 2] } as never)).toBe('precision 75.0% · recall undefined · 2 labels');
    expect(parseAttributeLines('state=used\n pool = main ')).toEqual({ filters: { state: 'used', pool: 'main' }, error: null });
    expect(parseAttributeLines('broken').error).toMatch(/not key=value/);
    expect(retentionError({ rawDays: '7', rollupDays: '5', logsDays: '14', tracesDays: '7' })).toMatch(/at least as long/);
    expect(retentionError({ rawDays: '7', rollupDays: '90', logsDays: '14', tracesDays: '91' })).toMatch(/Traces/);
    expect(retentionError({ rawDays: '7', rollupDays: '90', logsDays: '14', tracesDays: '7' })).toBeNull();
  });
});

describe('ObservabilityPage', () => {
  it('shows the overview from the API and maps the simulated-data switch to includeSimulated', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />, { route: '/observability', auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByText('0 connected')).toBeInTheDocument();
    expect(last('GET', '/observability/overview')?.params).toEqual({ includeSimulated: false });
    expect(screen.getByText(/Simulated telemetry is stored but excluded/)).toBeInTheDocument();
    // Readers do not get the Sources tab.
    expect(screen.queryByRole('tab', { name: 'Sources' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('switch', { name: 'Include simulated data' }));
    await waitFor(() => expect(last('GET', '/observability/overview')?.params).toEqual({ includeSimulated: true }));
    expect(await screen.findByText('Simulated sample data is included in these figures.')).toBeInTheDocument();
  });

  it('metrics explorer: picks a metric, filters by attribute, aggregation and step, charts anomalies', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=metrics', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const list = await screen.findByRole('list', { name: 'Metric names' });
    expect(within(list).getByText('SIMULATION')).toBeInTheDocument();
    expect(screen.getByText('No metric selected')).toBeInTheDocument();
    expect(last('GET', '/observability/metrics/series')).toBeUndefined();

    await user.click(within(list).getByRole('button', { name: /db\.client\.connections\.usage/ }));
    await user.selectOptions(screen.getByLabelText('Aggregation'), 'p95');
    await user.selectOptions(screen.getByLabelText('Step'), '300');
    await user.click(screen.getByRole('button', { name: /Add attribute filter/ }));
    await user.type(screen.getByLabelText('Attribute 1 key'), 'state');
    await user.type(screen.getByLabelText('Attribute 1 value'), 'used');
    await waitFor(() =>
      expect(last('GET', '/observability/metrics/series')?.params).toMatchObject({ name: 'db.client.connections.usage', aggregation: 'p95', step: 300, includeSimulated: false, 'attr.state': 'used' }),
    );
    expect(await screen.findByRole('img', { name: 'Metric chart: 1 series, 1 anomaly marked' })).toBeInTheDocument();
    expect(screen.getByText(/1 anomaly in the window/)).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Series' })).toHaveTextContent('payroll-db');
  });

  it('metrics explorer shows the API no-data message instead of a chart', async () => {
    routes({ 'GET /observability/metrics/series': json(makeSeries({ status: 'NO_DATA', message: 'No points between 09:00 and 10:00', series: [] })) });
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=metrics&metric=db.client.connections.usage' });
    expect(await screen.findByText('No points between 09:00 and 10:00')).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: /Metric chart/ })).not.toBeInTheDocument();
  });

  it('correlation: shows reasons and members, dismisses with a reason and splits selected members', async () => {
    routes({
      'POST /observability/correlation-groups/3/dismiss': json(makeGroup({ status: 'DISMISSED' })),
      'POST /observability/correlation-groups/3/split': json(makeGroup({ id: 9, title: 'Split off' })),
      'GET /observability/correlation-groups/9': json(makeGroup({ id: 9, title: 'Split off' })),
      'POST /observability/correlation-groups/3/merge': json(makeGroup()),
    });
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=correlation&group=3', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    expect(await screen.findByRole('list', { name: 'Group reasons' })).toHaveTextContent('payroll-app-01 depends on payroll-db-primary');
    expect(screen.getByRole('link', { name: 'payroll-db-primary' })).toHaveAttribute('href', '/cmdb/9');
    const members = screen.getByRole('table', { name: 'Group members' });
    expect(within(members).getByRole('link', { name: 'ALR-00062' })).toHaveAttribute('href', '/alerts/62');

    // Dismiss needs a reason and a confirmation.
    await user.click(screen.getByRole('button', { name: /Dismiss/ }));
    const dismiss = screen.getByRole('dialog', { name: 'Dismiss this correlation group?' });
    expect(within(dismiss).getByRole('button', { name: 'Dismiss group' })).toBeDisabled();
    await user.type(within(dismiss).getByLabelText(/Reason/), 'Unrelated maintenance noise');
    await user.click(within(dismiss).getByRole('button', { name: 'Dismiss group' }));
    await waitFor(() => expect(last('POST', '/observability/correlation-groups/3/dismiss')?.body).toEqual({ reason: 'Unrelated maintenance noise' }));

    // Split: select one member, confirm in the dialog.
    const split = screen.getByRole('button', { name: /Split/ });
    expect(split).toBeDisabled();
    await user.click(within(members).getByRole('checkbox', { name: 'Select Payroll API latency' }));
    await user.click(screen.getByRole('button', { name: /Split \(1\)/ }));
    const dialog = screen.getByRole('dialog', { name: 'Split 1 member into a new group' });
    await user.type(within(dialog).getByLabelText(/Title of the new group/), 'Split off');
    await user.click(within(dialog).getByRole('button', { name: 'Split' }));
    await waitFor(() => expect(last('POST', '/observability/correlation-groups/3/split')?.body).toEqual({ memberIds: [102], title: 'Split off' }));
  });

  it('correlation: merges other open groups and converts to an incident', async () => {
    routes({
      'POST /observability/correlation-groups/3/merge': json(makeGroup()),
      'POST /observability/correlation-groups/3/convert': json(makeGroup({ incidentId: 7900, incidentNumber: 'INC-7900' })),
    });
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=correlation&group=3', auth: makeAuth(makeUser('INCIDENT_MANAGER')) });
    await user.click(await screen.findByRole('button', { name: /Merge/ }));
    const merge = screen.getByRole('dialog', { name: 'Merge groups into this one' });
    await user.click(within(merge).getByRole('checkbox'));
    await user.click(within(merge).getByRole('button', { name: 'Merge 1' }));
    await waitFor(() => expect(last('POST', '/observability/correlation-groups/3/merge')?.body).toEqual({ groupIds: [4] }));

    await user.click(screen.getByRole('button', { name: /Convert to incident/ }));
    const convert = screen.getByRole('dialog', { name: 'Convert group to an incident' });
    await user.selectOptions(within(convert).getByLabelText('Impact'), 'HIGH');
    await user.click(within(convert).getByRole('button', { name: 'Create incident' }));
    await waitFor(() => expect(last('POST', '/observability/correlation-groups/3/convert')?.body).toEqual({ impact: 'HIGH' }));
  });

  it('correlation is read-only for auditors', async () => {
    routes();
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=correlation&group=3', auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByRole('table', { name: 'Group members' })).toBeInTheDocument();
    expect(screen.queryByRole('toolbar', { name: 'Group actions' })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it('sources: shows the OTLP token once with the exact endpoints and bearer header', async () => {
    const created = makeSource({ id: 7, name: 'Prod collector', tokenHint: 'otsec_…9z9z', demo: false });
    routes({ 'POST /observability/sources': json({ source: created, token: 'otsec_live_token_123' }, 201) });
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=sources', auth: makeAuth(makeUser('IT_ADMIN')) });
    const list = await screen.findByRole('list', { name: 'Telemetry sources' });
    expect(within(list).getByText('Not connected')).toBeInTheDocument();
    expect(within(list).queryByText(/otsec_live/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /New source/ }));
    const editor = screen.getByRole('dialog', { name: 'New telemetry source' });
    await user.type(within(editor).getByLabelText('Name'), 'Prod collector');
    await user.click(within(editor).getByRole('button', { name: 'Create source' }));
    await waitFor(() => expect(last('POST', '/observability/sources')?.body).toEqual({ name: 'Prod collector', kind: 'OTLP', authType: 'NONE', active: true }));

    const tokenDialog = await screen.findByRole('dialog', { name: 'Token for Prod collector' });
    expect(within(tokenDialog).getByLabelText('Source token')).toHaveValue('otsec_live_token_123');
    const setup = within(tokenDialog).getByLabelText('OTLP setup');
    expect(setup).toHaveTextContent(`POST ${window.location.origin}/api/otel/v1/metrics`);
    expect(setup).toHaveTextContent(`POST ${window.location.origin}/api/otel/v1/traces`);
    expect(setup).toHaveTextContent('Authorization: Bearer otsec_live_token_123');
    expect(setup).toHaveTextContent('Binary protobuf is not supported (415)');
    await user.click(within(tokenDialog).getByRole('button', { name: 'I have stored the token' }));
    expect(screen.queryByText(/otsec_live_token_123/)).not.toBeInTheDocument();
  });

  it('sources: labels prepared adapters as not operational and tests pull sources', async () => {
    routes({
      'GET /observability/sources': json([
        makeSource({ id: 2, name: 'AWS metrics', kind: 'CLOUDWATCH', prepared: true, baseUrl: null, demo: false }),
        makeSource({ id: 3, name: 'Prometheus', kind: 'PROMETHEUS_API', baseUrl: 'https://prom.example.com', status: 'CONNECTED', lastVerifiedAt: '2026-11-04T09:00:00Z', demo: false }),
      ]),
      'POST /observability/sources/3/test': json({ sourceId: 3, status: 'CONNECTED', success: true, httpStatus: 200, message: 'Prometheus 2.53', testedAt: '2026-11-04T10:00:00Z', lastVerifiedAt: '2026-11-04T10:00:00Z' }),
      'POST /observability/sources/3/pull': json({ sourceId: 3, queries: 2, series: 5, points: 300, rejected: 0, message: null, from: '', to: '' }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=sources', auth: makeAuth(makeUser('SYSTEM_ADMIN')) });
    const list = await screen.findByRole('list', { name: 'Telemetry sources' });
    expect(within(list).getByText('Prepared — not operational until tested')).toBeInTheDocument();
    expect(within(list).queryByRole('button', { name: 'Test AWS metrics' })).not.toBeInTheDocument();
    expect(within(list).queryByRole('button', { name: 'Pull AWS metrics now' })).not.toBeInTheDocument();
    expect(within(list).getByText('Connected')).toBeInTheDocument();
    await user.click(within(list).getByRole('button', { name: 'Test Prometheus' }));
    expect(await screen.findByText(/Prometheus: test succeeded \(HTTP 200\)/)).toBeInTheDocument();
    await user.click(within(list).getByRole('button', { name: 'Pull Prometheus now' }));
    expect(await screen.findByText(/2 queries, 5 series, 300 points/)).toBeInTheDocument();
  });

  it('sources tab is not reachable without TELEMETRY_CONFIG', async () => {
    routes();
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=sources', auth: makeAuth(makeUser('IT_MANAGER')) });
    expect(await screen.findByRole('tab', { name: 'Overview', selected: true })).toBeInTheDocument();
    expect(last('GET', '/observability/sources')).toBeUndefined();
  });

  it('anomalies: evaluation reports INSUFFICIENT_DATA without inventing precision or recall', async () => {
    routes({
      'POST /observability/anomaly-detectors/2/evaluate': json({
        detectorId: 2,
        status: 'INSUFFICIENT_DATA',
        message: 'No labelled faults between the dates',
        from: '2026-10-28T10:00:00Z',
        to: '2026-11-04T10:00:00Z',
        seriesEvaluated: 1,
        pointsEvaluated: 900,
        detections: [],
        labels: [],
        truePositives: 0,
        falsePositives: 0,
        falseNegatives: 0,
        precision: null,
        recall: null,
        f1: null,
        params: null,
        evaluatedAt: '2026-11-04T10:00:00Z',
      }),
      'POST /observability/anomaly-detectors/2/run': json({ detectorId: 2, seriesEvaluated: 1, pointsEvaluated: 0, anomaliesOpened: 0, anomaliesUpdated: 0, anomaliesResolved: 0, alertsRaised: 0, status: 'INSUFFICIENT_DATA', message: 'Insufficient data — 12 of 30 samples' }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=anomalies', auth: makeAuth(makeUser('IT_ADMIN')) });
    expect(await screen.findByRole('table', { name: 'Anomalies' })).toHaveTextContent('9.00 – 17.0');
    const detectors = await screen.findByRole('list', { name: 'Anomaly detectors' });
    expect(within(detectors).getByText(/Not evaluated yet/)).toBeInTheDocument();

    await user.click(within(detectors).getByRole('button', { name: 'Run Payroll DB connection pool now' }));
    expect(await screen.findByText(/Insufficient data — 12 of 30 samples/)).toBeInTheDocument();

    await user.click(within(detectors).getByRole('button', { name: 'Evaluate Payroll DB connection pool' }));
    const dialog = screen.getByRole('dialog', { name: 'Evaluate Payroll DB connection pool' });
    await user.click(within(dialog).getByRole('button', { name: 'Evaluate' }));
    expect(await within(dialog).findByText(/Insufficient data\./)).toBeInTheDocument();
    expect(within(dialog).getByText(/No labelled faults between the dates/)).toBeInTheDocument();
    expect(within(dialog).queryByText('Precision')).not.toBeInTheDocument();
    expect(last('POST', '/observability/anomaly-detectors/2/evaluate')?.body).toMatchObject({ useSimulatedFaults: false, useAlerts: true, toleranceMinutes: 5 });
  });

  it('anomalies: creates a detector with validated parameters', async () => {
    routes({ 'POST /observability/anomaly-detectors': (config) => json({ ...makeDetector({ id: 8 }), ...JSON.parse(String(config.data)) }, 201) });
    const user = userEvent.setup();
    renderWithProviders(<ObservabilityPage />, { route: '/observability?tab=anomalies', auth: makeAuth(makeUser('IT_ADMIN')) });
    await user.click(await screen.findByRole('button', { name: /New detector/ }));
    const dialog = screen.getByRole('dialog', { name: 'New anomaly detector' });
    await user.type(within(dialog).getByLabelText('Name'), 'HR latency');
    await user.type(within(dialog).getByLabelText('Metric name'), 'http.server.request.duration');
    await user.clear(within(dialog).getByLabelText('Threshold (|z|)'));
    await user.type(within(dialog).getByLabelText('Threshold (|z|)'), '25');
    await user.click(within(dialog).getByRole('button', { name: 'Create detector' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Threshold (|z|): between 1 and 20');
    expect(last('POST', '/observability/anomaly-detectors')).toBeUndefined();
    await user.clear(within(dialog).getByLabelText('Threshold (|z|)'));
    await user.type(within(dialog).getByLabelText('Threshold (|z|)'), '4');
    await user.type(within(dialog).getByLabelText(/Attribute filters/), 'http.route=/payslips');
    await user.click(within(dialog).getByRole('button', { name: 'Create detector' }));
    await waitFor(() =>
      expect(last('POST', '/observability/anomaly-detectors')?.body).toMatchObject({ name: 'HR latency', metricName: 'http.server.request.duration', threshold: 4, attributeFilters: { 'http.route': '/payslips' }, includeSimulated: false, raiseAlert: true }),
    );
  });

  it('shows API errors with a retry', async () => {
    routes({ 'GET /observability/overview': { status: 500, data: apiErrorBody(500, 'Overview failed') } });
    renderWithProviders(<ObservabilityPage />, { route: '/observability' });
    expect(await screen.findByText('Overview failed')).toBeInTheDocument();
  });
});
