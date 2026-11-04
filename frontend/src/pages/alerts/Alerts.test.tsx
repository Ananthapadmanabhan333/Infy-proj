import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { pageOf } from '../../test/itsmFixtures';
import { ALERT_COUNTS, makeAlertDetail, makeAlertSource, makeAlertSummary, ROUTING_RULE, SUPPRESSION_RULE } from '../../test/opsFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import AlertDetailPage, { safeExternalUrl } from './AlertDetailPage';
import AlertsPage from './AlertsPage';
import { adapterGuide } from './AlertConfig';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);
const all = (method: string, url: string) => requests.filter((request) => request.method === method && request.url === url);

const TEAMS = [{ id: 3, code: 'PAY-APPS', name: 'Payroll & HR Apps', description: null, departmentId: 2, departmentCode: 'IT', departmentName: 'IT', leadId: null, leadName: null, email: null, active: true, departmentDefault: false, memberCount: 3, openIncidents: 1, unassignedIncidents: 0, openRequests: 0, demo: true, members: [] }];

function inboxRoutes() {
  mockApi({
    'GET /alerts': json(pageOf([makeAlertSummary(), makeAlertSummary({ id: 62, number: 'ALR-00062', title: 'VPN tunnel flapping', severity: 'MAJOR', status: 'ACKNOWLEDGED', occurrenceCount: 1 })])),
    'GET /alerts/counts': json(ALERT_COUNTS),
    'GET /alerts/groups': json([
      { groupKey: '{}:{service="payroll"}', title: 'HighErrorRate', alertCount: 3, firingCount: 2, maxSeverity: 'CRITICAL', occurrences: 19, firstSeenAt: '2026-11-04T06:00:00Z', lastSeenAt: '2026-11-04T07:55:00Z', incidentId: 9, incidentNumber: 'INC-7009', sourceKind: 'PROMETHEUS', byStatus: { OPEN: 2, ACKNOWLEDGED: 1 } },
    ]),
    'POST /alerts/bulk': (config) => json({ processed: JSON.parse(String(config.data)).ids.length - 1, skipped: 1, messages: ['ALR-00062 is not open'] }),
    'GET /alerts/suppression-rules': json([SUPPRESSION_RULE]),
    'GET /alerts/routing-rules': json([ROUTING_RULE]),
    'GET /alerts/sources': json([makeAlertSource(), makeAlertSource({ id: 2, name: 'aws-cloudwatch-payroll', provider: 'CLOUDWATCH', topicArn: 'arn:aws:sns:ap-south-1:123456789012:payroll-alarms', subscriptionStatus: 'CONFIRMED', webhookPath: '/api/alerts/webhooks/2' })]),
    'GET /alerts/sources/1/deliveries': json([{ id: 1, deliveryId: 'abc', eventType: 'alertmanager', outcome: 'PROCESSED', message: '2 alerts', alertCount: 2, receivedAt: '2026-11-04T07:55:00Z' }]),
    'POST /alerts/sources': (config) => json({ source: makeAlertSource({ id: 5, ...JSON.parse(String(config.data)), webhookPath: '/api/alerts/webhooks/5' }), secret: 'alsec_secret123' }, 201),
    'POST /alerts/sources/1/rotate-secret': json({ source: makeAlertSource(), secret: 'alsec_rotated456' }),
    'POST /alerts/suppression-rules': (config) => json({ ...SUPPRESSION_RULE, id: 9, ...JSON.parse(String(config.data)) }, 201),
    'DELETE /alerts/routing-rules/1': { status: 204 },
    'GET /services': json([{ id: 4, code: 'PAY', name: 'Payroll', tier: 1 }]),
    'GET /teams': json(TEAMS),
  });
}

describe('AlertsPage — inbox', () => {
  it('shows counts, filters by status/severity/source/scope and runs bulk acknowledge', async () => {
    inboxRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AlertsPage />, { route: '/alerts', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const table = await screen.findByRole('table', { name: 'Alerts' });
    expect(within(table).getByText('HTTP 503 on payroll-app-01')).toBeInTheDocument();
    expect(within(table).getByText('×7')).toBeInTheDocument();
    expect(screen.getByText(/4 open · 3 acknowledged/)).toBeInTheDocument();
    expect(last('GET', '/alerts')?.params).toMatchObject({ status: 'OPEN,ACKNOWLEDGED', sort: 'lastSeenAt,desc', page: 0, size: 25 });

    await user.selectOptions(screen.getByRole('combobox', { name: 'Severity' }), 'CRITICAL');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Source' }), 'PROMETHEUS');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Scope' }), 'mine');
    await waitFor(() => expect(last('GET', '/alerts')?.params).toMatchObject({ severity: 'CRITICAL', source: 'PROMETHEUS', scope: 'mine' }));
    expect(last('GET', '/alerts/counts')?.params).toEqual({ scope: 'mine' });

    await user.click(screen.getByRole('button', { name: /More filters/ }));
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Incident' }), 'false');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Firing' }), 'true');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Team' }), '3');
    await waitFor(() => expect(last('GET', '/alerts')?.params).toMatchObject({ hasIncident: false, firing: true, teamId: 3 }));

    const rows = within(await screen.findByRole('table', { name: 'Alerts' }));
    await user.click(rows.getByRole('checkbox', { name: /Select ALR-00061/ }));
    await user.click(rows.getByRole('checkbox', { name: /Select ALR-00062/ }));
    const bar = screen.getByRole('toolbar', { name: 'Bulk actions' });
    expect(bar).toHaveTextContent('2 selected');
    await user.click(within(bar).getByRole('button', { name: /Acknowledge/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Acknowledge 2 alerts' });
    await user.click(within(dialog).getByRole('button', { name: 'Acknowledge' }));
    await waitFor(() => expect(last('POST', '/alerts/bulk')?.body).toEqual({ ids: [61, 62], action: 'ACKNOWLEDGE', note: null }));
    expect(await screen.findByText(/Acknowledged 1 alert · 1 skipped/)).toBeInTheDocument();
  });

  it('bulk-suppresses with one POST /alerts/bulk call and reports the processed/skipped summary', async () => {
    inboxRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AlertsPage />, { route: '/alerts', auth: makeAuth(makeUser('INCIDENT_MANAGER')) });
    const rows = within(await screen.findByRole('table', { name: 'Alerts' }));
    await user.click(rows.getByRole('checkbox', { name: /Select all/i }));
    await user.click(within(screen.getByRole('toolbar', { name: 'Bulk actions' })).getByRole('button', { name: /Suppress/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Suppress 2 alerts' });
    const confirm = within(dialog).getByRole('button', { name: 'Suppress' });
    expect(confirm).toBeDisabled();
    await user.selectOptions(within(dialog).getByLabelText('Suppress for'), '60');
    await user.type(within(dialog).getByLabelText(/Reason/), 'Known VPN firmware issue');
    await user.click(confirm);
    await waitFor(() => expect(all('POST', '/alerts/bulk')).toHaveLength(1));
    expect(last('POST', '/alerts/bulk')?.body).toEqual({ ids: [61, 62], action: 'SUPPRESS', minutes: 60, reason: 'Known VPN firmware issue' });
    expect(all('POST', '/alerts/61/suppress')).toHaveLength(0);
    expect(all('POST', '/alerts/62/suppress')).toHaveLength(0);
    expect(await screen.findByText(/Suppressed 1 alert · 1 skipped: ALR-00062 is not open/)).toBeInTheDocument();
  });

  it('bulk-suppresses until resolved with minutes null', async () => {
    inboxRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AlertsPage />, { route: '/alerts', auth: makeAuth(makeUser('INCIDENT_MANAGER')) });
    const rows = within(await screen.findByRole('table', { name: 'Alerts' }));
    await user.click(rows.getByRole('checkbox', { name: /Select ALR-00061/ }));
    await user.click(within(screen.getByRole('toolbar', { name: 'Bulk actions' })).getByRole('button', { name: /Suppress/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Suppress 1 alert' });
    await user.selectOptions(within(dialog).getByLabelText('Suppress for'), 'until');
    await user.type(within(dialog).getByLabelText(/Reason/), 'Planned maintenance');
    await user.click(within(dialog).getByRole('button', { name: 'Suppress' }));
    await waitFor(() => expect(last('POST', '/alerts/bulk')?.body).toEqual({ ids: [61], action: 'SUPPRESS', minutes: null, reason: 'Planned maintenance' }));
  });

  it('shows the grouped view and opens a group in the inbox', async () => {
    inboxRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AlertsPage />, { route: '/alerts?tab=groups', auth: makeAuth(makeUser('AUDITOR')) });
    const groups = await screen.findByRole('list', { name: 'Alert groups' });
    expect(within(groups).getByText('HighErrorRate')).toBeInTheDocument();
    expect(within(groups).getByRole('link', { name: 'INC-7009' })).toHaveAttribute('href', '/incidents/9');
    await user.click(within(groups).getByRole('button', { name: 'Show the alerts of HighErrorRate' }));
    await screen.findByRole('table', { name: 'Alerts' });
    expect(last('GET', '/alerts')?.params).toMatchObject({ groupKey: '{}:{service="payroll"}' });
    expect((last('GET', '/alerts')?.params as Record<string, unknown>).status).toBeUndefined();
  });

  it('is read-only for auditors: no bulk selection, no raise, no sources tab; rules are visible', async () => {
    inboxRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AlertsPage />, { route: '/alerts', auth: makeAuth(makeUser('AUDITOR')) });
    const table = await screen.findByRole('table', { name: 'Alerts' });
    expect(within(table).queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Raise alert/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /Sources/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: /Suppression rules/ }));
    const rules = await screen.findByRole('list', { name: 'Suppression rules' });
    expect(within(rules).getByText(/Labels job=backup · daily 01:00–03:00/)).toBeInTheDocument();
    expect(within(rules).queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument();
  });
});

describe('AlertsPage — admin configuration', () => {
  it('creates a source, shows the secret once with the setup, rotates and lists deliveries', async () => {
    inboxRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AlertsPage />, { route: '/alerts?tab=sources', auth: makeAuth(makeUser('IT_ADMIN')) });
    const list = await screen.findByRole('list', { name: 'Alert sources' });
    expect(within(list).getByText('SNS Confirmed')).toBeInTheDocument();
    expect(within(list).queryByRole('button', { name: 'Rotate secret of aws-cloudwatch-payroll' })).not.toBeInTheDocument();

    await user.click(within(list).getAllByRole('button', { name: 'Deliveries' })[0]);
    expect(await screen.findByRole('list', { name: 'Recent deliveries' })).toHaveTextContent('2 alerts');

    await user.click(screen.getByRole('button', { name: /New source/ }));
    const editor = await screen.findByRole('dialog', { name: 'New alert source' });
    await user.type(within(editor).getByLabelText(/^Name/), 'grafana-cloud');
    await user.selectOptions(within(editor).getByLabelText(/^Provider/), 'GRAFANA');
    await user.selectOptions(within(editor).getByLabelText('Default severity'), 'MAJOR');
    await user.click(within(editor).getByRole('button', { name: 'Create source' }));
    await waitFor(() => expect(last('POST', '/alerts/sources')?.body).toEqual({ name: 'grafana-cloud', provider: 'GRAFANA', defaultServiceId: null, defaultCiId: null, defaultSeverity: 'MAJOR', topicArn: null, active: true }));
    const secret = await screen.findByRole('dialog', { name: 'Secret for grafana-cloud' });
    expect(within(secret).getByLabelText('Secret')).toHaveValue('alsec_secret123');
    expect((within(secret).getByLabelText('Webhook URL') as HTMLInputElement).value).toMatch(/\/api\/alerts\/webhooks\/5$/);
    expect(within(secret).getAllByText('Authorization: Bearer <secret>').length).toBeGreaterThan(0);
    await user.click(within(secret).getByRole('button', { name: 'I have stored the secret' }));

    await user.click(within(list).getByRole('button', { name: 'Rotate secret of prometheus-prod' }));
    await user.click(within(await screen.findByRole('dialog', { name: /Rotate the secret of prometheus-prod/ })).getByRole('button', { name: 'Rotate secret' }));
    expect(await screen.findByDisplayValue('alsec_rotated456')).toBeInTheDocument();
  });

  it('validates the CloudWatch topic ARN', async () => {
    inboxRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AlertsPage />, { route: '/alerts?tab=sources', auth: makeAuth(makeUser('SYSTEM_ADMIN')) });
    await user.click(await screen.findByRole('button', { name: /New source/ }));
    const editor = await screen.findByRole('dialog', { name: 'New alert source' });
    await user.type(within(editor).getByLabelText(/^Name/), 'cw');
    await user.selectOptions(within(editor).getByLabelText(/^Provider/), 'CLOUDWATCH');
    await user.type(within(editor).getByLabelText(/SNS topic ARN/), 'arn:aws:sns:eu-west-1:12:x');
    expect(within(editor).getByRole('button', { name: 'Create source' })).toBeDisabled();
    await user.clear(within(editor).getByLabelText(/SNS topic ARN/));
    await user.type(within(editor).getByLabelText(/SNS topic ARN/), 'arn:aws:sns:eu-west-1:123456789012:alarms');
    expect(within(editor).getByRole('button', { name: 'Create source' })).toBeEnabled();
  });

  it('creates a suppression rule with the MatchCriteria editor and deletes a routing rule', async () => {
    inboxRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AlertsPage />, { route: '/alerts?tab=suppression', auth: makeAuth(makeUser('IT_ADMIN')) });
    await user.click(await screen.findByRole('button', { name: /New rule/ }));
    const editor = await screen.findByRole('dialog', { name: 'New suppression rule' });
    await user.type(within(editor).getByLabelText(/^Name/), 'Patch Tuesday');
    await user.click(within(editor).getByRole('button', { name: 'Warning' }));
    await user.click(within(editor).getByRole('button', { name: /Add label matcher/ }));
    await user.type(within(editor).getByLabelText('Label 1 name'), 'env');
    await user.type(within(editor).getByLabelText('Label 1 value'), 'test|staging');
    await user.type(within(editor).getByLabelText('Daily from'), '22:00');
    await user.type(within(editor).getByLabelText('Daily until'), '02:00');
    await user.click(within(editor).getByRole('button', { name: 'Tue' }));
    await user.click(within(editor).getByRole('button', { name: 'Create rule' }));
    await waitFor(() =>
      expect(last('POST', '/alerts/suppression-rules')?.body).toEqual({
        name: 'Patch Tuesday',
        description: null,
        enabled: true,
        match: { labels: { env: 'test|staging' }, severities: ['WARNING'] },
        startsAt: null,
        endsAt: null,
        dailyStart: '22:00',
        dailyEnd: '02:00',
        daysOfWeek: [2],
      }),
    );

    await user.click(screen.getByRole('tab', { name: /Routing rules/ }));
    const routing = await screen.findByRole('list', { name: 'Routing rules' });
    expect(within(routing).getByText(/Severity Critical · Labels service=payroll → team Payroll & HR Apps, incident/)).toBeInTheDocument();
    await user.click(within(routing).getByRole('button', { name: `Delete ${ROUTING_RULE.name}` }));
    await user.click(within(await screen.findByRole('dialog', { name: /Delete Payroll alerts/ })).getByRole('button', { name: 'Delete rule' }));
    await waitFor(() => expect(last('DELETE', '/alerts/routing-rules/1')).toBeTruthy());
  });

  it('requires a target for a routing rule', async () => {
    inboxRoutes();
    const user = userEvent.setup();
    renderWithProviders(<AlertsPage />, { route: '/alerts?tab=routing', auth: makeAuth(makeUser('IT_ADMIN')) });
    await user.click(await screen.findByRole('button', { name: /New rule/ }));
    const editor = await screen.findByRole('dialog', { name: 'New routing rule' });
    await user.type(within(editor).getByLabelText(/^Name/), 'Nothing');
    await user.click(within(editor).getByRole('button', { name: 'Create rule' }));
    expect(await within(editor).findByText(/Route to a team, set a service or CI, or create an incident/)).toBeInTheDocument();
    expect(last('POST', '/alerts/routing-rules')).toBeUndefined();
  });
});

function detailRoutes(detail = makeAlertDetail()) {
  mockApi({
    'GET /alerts/61': json(detail),
    'POST /alerts/61/acknowledge': json(makeAlertDetail({ allowedActions: ['RESOLVE', 'SUPPRESS', 'ASSIGN', 'CONVERT', 'LINK_INCIDENT'] }, { status: 'ACKNOWLEDGED' })),
    'POST /alerts/61/unsuppress': json(makeAlertDetail({}, { status: 'OPEN' })),
    'POST /alerts/61/assign': json(makeAlertDetail()),
    'POST /alerts/61/incident': json({ alert: makeAlertDetail({}, { incidentId: 90, incidentNumber: 'INC-7090' }), incidentId: 90, incidentNumber: 'INC-7090', duplicate: false, parentNumber: null, linkedAlerts: 3 }, 201),
    'GET /teams': json(TEAMS),
  });
}

function renderDetail(role: Parameters<typeof makeUser>[0]) {
  return renderWithProviders(
    <Routes>
      <Route path="/alerts/:id" element={<AlertDetailPage />} />
    </Routes>,
    { route: '/alerts/61', auth: makeAuth(makeUser(role)) },
  );
}

describe('AlertDetailPage', () => {
  it('shows labels, routing, escalation and history; acknowledges with a note', async () => {
    detailRoutes();
    const user = userEvent.setup();
    renderDetail('SUPPORT_AGENT');
    expect(await screen.findByRole('heading', { level: 1, name: 'HTTP 503 on payroll-app-01' })).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Labels' })).toHaveTextContent('alertnameHighErrorRate');
    expect(screen.getByText(/policy team-lead/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open in the monitoring system/ })).toHaveAttribute('href', 'https://prometheus.example.test/graph?g0.expr=up');
    expect(screen.getByRole('list', { name: 'History' })).toHaveTextContent('Routed');

    await user.click(screen.getByRole('button', { name: /Acknowledge/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Acknowledge ALR-00061?' });
    await user.type(within(dialog).getByLabelText(/Note/), 'Looking at the pool');
    await user.click(within(dialog).getByRole('button', { name: 'Acknowledge' }));
    await waitFor(() => expect(last('POST', '/alerts/61/acknowledge')?.body).toEqual({ note: 'Looking at the pool' }));
  });

  it('converts to an incident with optional overrides and assigns a team', async () => {
    detailRoutes();
    const user = userEvent.setup();
    renderDetail('IT_MANAGER');
    await user.click(await screen.findByRole('button', { name: /Convert to incident/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Convert ALR-00061 to an incident' });
    await user.selectOptions(within(dialog).getByLabelText('Impact'), 'HIGH');
    await user.click(within(dialog).getByRole('button', { name: 'Create incident' }));
    await waitFor(() => expect(last('POST', '/alerts/61/incident')?.body).toEqual({ title: null, description: null, impact: 'HIGH', urgency: null }));
    expect(await screen.findByText(/INC-7090 created · 3 alerts linked/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Assign team/ }));
    const assign = await screen.findByRole('dialog', { name: 'Assign ALR-00061 to a team' });
    await user.selectOptions(within(assign).getByLabelText(/^Team/), '3');
    await user.click(within(assign).getByRole('button', { name: 'Assign' }));
    await waitFor(() => expect(last('POST', '/alerts/61/assign')?.body).toEqual({ teamId: 3, note: null }));
  });

  it('offers only the actions the API allows (unsuppress) and none to auditors', async () => {
    const suppressed = makeAlertDetail({ allowedActions: ['UNSUPPRESS', 'RESOLVE'], suppressionRuleName: 'Nightly backup window', suppressedAt: '2026-11-04T01:10:00Z' }, { status: 'SUPPRESSED' });
    detailRoutes(suppressed);
    const user = userEvent.setup();
    const view = renderDetail('SUPPORT_AGENT');
    expect(await screen.findByText(/Suppressed by the rule “Nightly backup window”/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Acknowledge/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Convert to incident/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Unsuppress/ }));
    await user.click(within(await screen.findByRole('dialog', { name: 'Unsuppress ALR-00061?' })).getByRole('button', { name: 'Unsuppress' }));
    await waitFor(() => expect(last('POST', '/alerts/61/unsuppress')).toBeTruthy());
    view.unmount();

    detailRoutes(makeAlertDetail({ allowedActions: [] }));
    renderDetail('AUDITOR');
    expect(await screen.findByRole('heading', { level: 1, name: 'HTTP 503 on payroll-app-01' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Acknowledge|Resolve|Suppress|Convert/ })).not.toBeInTheDocument();
  });
});

describe('alert helpers', () => {
  it('renders only http(s) links from payloads', () => {
    expect(safeExternalUrl('https://grafana.example.test/d/1')).toBe('https://grafana.example.test/d/1');
    expect(safeExternalUrl('javascript:alert(1)')).toBeNull();
    expect(safeExternalUrl('not a url')).toBeNull();
    expect(safeExternalUrl(null)).toBeNull();
  });

  it('gives adapter setup with the exact endpoint and authentication', () => {
    const url = 'https://api.example.test/api/alerts/webhooks/7';
    expect(adapterGuide('PROMETHEUS', url).snippet).toContain(`url: ${url}`);
    expect(adapterGuide('PROMETHEUS', url).headers).toEqual(['Authorization: Bearer <secret>']);
    expect(adapterGuide('GRAFANA', url).snippet).toContain('Authorization header — scheme: Bearer');
    expect(adapterGuide('GRAFANA', url).snippet).toContain('Header: X-Grafana-Alerting-Signature');
    expect(adapterGuide('GRAFANA', url).headers).toContain('Authorization: Bearer <secret>');
    expect(adapterGuide('GRAFANA', url).headers.join(' ')).toMatch(/X-Grafana-Alerting-Timestamp.*timestamp \+ ":" \+ raw body.*±5 min/);
    expect(adapterGuide('GRAFANA', url).steps.join(' ')).toContain('"timestamp:body"');
    expect(adapterGuide('CLOUDWATCH', url, 'arn:aws:sns:ap-south-1:123456789012:payroll-alarms').snippet).toContain('--topic-arn arn:aws:sns:ap-south-1:123456789012:payroll-alarms');
    const generic = adapterGuide('GENERIC', url);
    expect(generic.headers).toContain('X-Nexus-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + raw body)>');
    expect(generic.snippet).toContain('X-Nexus-Delivery');
  });
});
