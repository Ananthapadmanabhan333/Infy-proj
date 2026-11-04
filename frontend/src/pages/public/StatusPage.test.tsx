import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { normalisePublicStatus, worstStatus } from '../../api/statusPage';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, renderWithProviders } from '../../test/utils';
import StatusPage, { dayTone } from './StatusPage';
import StatusSubscriptionPage from './StatusSubscriptionPage';

const TODAY = new Date().toISOString().slice(0, 10);
const daysAgo = (days: number) => new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10);

/** Shape of the backend's `StatusPageDtos.PublicStatus`. */
const STATUS = {
  title: 'Acme service status',
  description: 'Live status of Acme business services.',
  overall: 'DEGRADED',
  generatedAt: '2026-11-04T09:30:00Z',
  subscriptionsAvailable: true,
  components: [
    {
      id: 1,
      name: 'Payroll',
      group: 'Business applications',
      status: 'DEGRADED',
      uptime90d: 98.25,
      history: [
        { date: daysAgo(2), availability: 100, maintenance: false },
        { date: daysAgo(1), availability: 100, maintenance: true },
        { date: TODAY, availability: 96.5, maintenance: false },
      ],
    },
    { id: 2, name: 'Email', group: 'Business applications', status: 'OPERATIONAL', uptime90d: 100, history: [{ date: TODAY, availability: 100, maintenance: false }] },
  ],
  updates: [
    { id: 1, title: 'Payroll exports are slow', status: 'INVESTIGATING', message: 'We are looking into slow exports.', postedAt: '2026-11-04T08:00:00Z' },
    { id: 2, title: 'Payroll exports are slow', status: 'IDENTIFIED', message: 'A database index is being rebuilt.', postedAt: '2026-11-04T09:00:00Z' },
    { id: 3, title: 'VPN reconnects', status: 'RESOLVED', message: 'Fixed.', postedAt: '2026-11-03T10:00:00Z' },
  ],
  maintenance: [
    { id: 4, title: 'ERP upgrade', description: 'Planned upgrade of the ERP platform.', status: 'SCHEDULED', startsAt: '2026-11-08T22:00:00Z', endsAt: '2026-11-09T02:00:00Z', components: ['ERP'] },
    { id: 5, title: 'Old window', description: null, status: 'COMPLETED', startsAt: '2026-10-01T22:00:00Z', endsAt: '2026-10-01T23:00:00Z', components: [] },
  ],
};

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});

function renderStatus() {
  return renderWithProviders(<StatusPage />, { route: '/status', auth: makeAuth(null) });
}

describe('StatusPage (/status)', () => {
  it('shows the overall status, active incidents, maintenance and components with uptime bars', async () => {
    mockApi({ 'GET /public/status': json(STATUS), 'GET /health': json({ status: 'UP' }) });
    renderStatus();
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme service status' })).toBeInTheDocument();
    expect(screen.getByText('Live status of Acme business services.')).toBeInTheDocument();
    expect(screen.getByText('Some systems are degraded')).toBeInTheDocument();
    expect(document.title).toBe('SA | Service status');

    const incidents = screen.getByRole('region', { name: 'Active incidents' });
    expect(within(incidents).getByRole('heading', { name: 'Payroll exports are slow' })).toBeInTheDocument();
    // Updates with the same title form one timeline, newest first; its status is the latest update's.
    const updates = within(incidents).getAllByRole('listitem');
    expect(updates).toHaveLength(2);
    expect(updates[0]).toHaveTextContent('A database index is being rebuilt.');
    expect(within(incidents).getByText('Identified', { selector: '.sp-chip' })).toBeInTheDocument();
    expect(within(incidents).queryByText('VPN reconnects')).not.toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Recently resolved' })).getByText('VPN reconnects')).toBeInTheDocument();

    const maintenance = screen.getByRole('region', { name: 'Scheduled maintenance' });
    expect(within(maintenance).getByText('ERP upgrade')).toBeInTheDocument();
    expect(within(maintenance).getByText('Affects: ERP')).toBeInTheDocument();
    expect(within(maintenance).queryByText('Old window')).not.toBeInTheDocument();

    const components = screen.getByRole('region', { name: 'Components' });
    expect(within(components).getByRole('heading', { name: 'Business applications' })).toBeInTheDocument();
    const payrollBars = within(components).getByRole('img', { name: 'Payroll: 98.25% uptime over the last 90 days' });
    const cells = Array.from(payrollBars.querySelectorAll('.sp-bar')).map((cell) => cell.className);
    expect(cells).toHaveLength(90);
    expect(cells.slice(-3)).toEqual(['sp-bar success', 'sp-bar gold', 'sp-bar warning']);
    expect(cells[0]).toBe('sp-bar none');
    expect(within(components).getByRole('img', { name: 'Email: 100% uptime over the last 90 days' })).toBeInTheDocument();
    expect(within(components).getByText('Outage', { selector: '.sp-legend li' })).toBeInTheDocument();
  });

  it('renders a friendly "not published" state on 404', async () => {
    mockApi({ 'GET /public/status': { status: 404, data: apiErrorBody(404, 'Not found') } });
    renderStatus();
    expect(await screen.findByRole('heading', { name: 'This status page is not published' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Contact support' })).toHaveAttribute('href', '/support');
    expect(screen.queryByRole('region', { name: 'Get status updates' })).not.toBeInTheDocument();
  });

  it('distinguishes an unreachable API from an unpublished page and retries', async () => {
    let calls = 0;
    mockApi({
      'GET /public/status': () => {
        calls += 1;
        return calls === 1 ? { status: 503, data: apiErrorBody(503, 'Unavailable') } : json({ ...STATUS, overall: 'OPERATIONAL', updates: [], maintenance: [] });
      },
    });
    renderStatus();
    expect(await screen.findByRole('heading', { name: 'Status information is unavailable right now' })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: /Try again/ }));
    expect(await screen.findByText('All systems operational')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Active incidents' })).not.toBeInTheDocument();
  });

  it('subscribes with a generic confirmation and validates the address first', async () => {
    mockApi({
      'GET /public/status': json(STATUS),
      'POST /public/status/subscribe': json({ message: 'Thank you. If this address can receive status updates, a confirmation e-mail is on its way.' }, 202),
    });
    const user = userEvent.setup();
    renderStatus();
    const form = await screen.findByRole('region', { name: 'Get status updates' });
    await user.type(within(form).getByLabelText('Email'), 'not-an-email');
    await user.click(within(form).getByRole('button', { name: 'Subscribe' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Enter a valid email address');
    expect(requests.some((request) => request.url === '/public/status/subscribe')).toBe(false);

    await user.clear(within(form).getByLabelText('Email'));
    await user.type(within(form).getByLabelText('Email'), 'ops@acme.test');
    await user.click(within(form).getByRole('button', { name: 'Subscribe' }));
    expect(await within(form).findByRole('status')).toHaveTextContent('If this address can receive status updates, a confirmation e-mail is on its way.');
    expect(requests.find((request) => request.url === '/public/status/subscribe')?.body).toEqual({ email: 'ops@acme.test' });
  });

  it('does not offer the subscribe form when the deployment cannot send e-mail', async () => {
    mockApi({ 'GET /public/status': json({ ...STATUS, subscriptionsAvailable: false }) });
    renderStatus();
    expect(await screen.findByText(/E-mail updates are not offered for this status page/)).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Get status updates' })).not.toBeInTheDocument();
  });

  it('handles the e-mail links of the API (/status?confirm=… and /status?unsubscribe=…)', async () => {
    const token = 'AbCdEfGhIjKlMnOpQrStUv-_12';
    mockApi({ 'POST /public/status/unsubscribe': json({ message: 'If the link was valid, you will no longer receive status updates.' }) });
    renderWithProviders(<StatusPage />, { route: `/status?unsubscribe=${token}`, auth: makeAuth(null) });
    expect(await screen.findByRole('heading', { level: 1, name: 'Unsubscribe request received' })).toBeInTheDocument();
    expect(screen.getByText('If the link was valid, you will no longer receive status updates.')).toBeInTheDocument();
    expect(requests.find((request) => request.url === '/public/status/unsubscribe')?.body).toEqual({ token });
    expect(requests.some((request) => request.url === '/public/status')).toBe(false);
  });

  it('explains rate limiting on subscribe', async () => {
    mockApi({ 'GET /public/status': json(STATUS), 'POST /public/status/subscribe': { status: 429, data: apiErrorBody(429, 'Too many requests') } });
    const user = userEvent.setup();
    renderStatus();
    const form = await screen.findByRole('region', { name: 'Get status updates' });
    await user.type(within(form).getByLabelText('Email'), 'ops@acme.test');
    await user.click(within(form).getByRole('button', { name: 'Subscribe' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Too many requests');
  });
});

describe('status response normalisation', () => {
  it('accepts field-name variants and derives the overall status from the components', () => {
    const status = normalisePublicStatus({
      settings: { title: 'Status', description: null },
      components: [
        { id: 'a', name: 'API', status: 'major_outage', availabilityHistory: [{ date: '2026-11-03', availability: 0.5 }] },
        { id: 'b', serviceName: 'Portal', currentStatus: 'UP' },
      ],
      activeUpdates: [{ id: 9, title: 'API down', status: 'investigating', message: 'Looking into it' }],
      maintenanceWindows: [{ id: 3, title: 'DB patch', status: 'IN_PROGRESS', startAt: '2026-11-04T01:00:00Z', endAt: '2026-11-04T02:00:00Z' }],
    });
    expect(status.title).toBe('Status');
    expect(status.overall).toBe('OUTAGE');
    expect(status.components.map((component) => [component.name, component.status, component.uptime])).toEqual([
      ['API', 'OUTAGE', 50],
      ['Portal', 'OPERATIONAL', null],
    ]);
    expect(status.incidents).toEqual([
      expect.objectContaining({ title: 'API down', status: 'INVESTIGATING', updates: [expect.objectContaining({ message: 'Looking into it' })] }),
    ]);
    expect(status.maintenance[0]).toEqual(expect.objectContaining({ title: 'DB patch', status: 'IN_PROGRESS', startsAt: '2026-11-04T01:00:00Z' }));
    expect(status.historyDays).toBe(90);
    expect(normalisePublicStatus({ historyDays: 30 }).historyDays).toBe(30);
  });

  it('degrades to an empty, unknown status for an unexpected payload', () => {
    const status = normalisePublicStatus('nonsense');
    expect(status).toEqual(expect.objectContaining({ title: 'Service status', overall: 'UNKNOWN', components: [], incidents: [], maintenance: [], historyDays: 90 }));
    expect(worstStatus(['OPERATIONAL', 'MAINTENANCE', 'DEGRADED'])).toBe('DEGRADED');
  });

  it('colours uptime days by recorded status first, then by uptime', () => {
    expect(dayTone(undefined)).toBe('none');
    expect(dayTone({ date: TODAY, uptime: null, status: null })).toBe('none');
    expect(dayTone({ date: TODAY, uptime: 100, status: 'MAINTENANCE' })).toBe('gold');
    expect(dayTone({ date: TODAY, uptime: 99.8, status: null })).toBe('success');
    expect(dayTone({ date: TODAY, uptime: 97, status: null })).toBe('warning');
    expect(dayTone({ date: TODAY, uptime: 60, status: null })).toBe('critical');
    expect(dayTone({ date: TODAY, uptime: null, status: 'OUTAGE' })).toBe('critical');
  });
});

describe('StatusSubscriptionPage (/status/confirm, /status/unsubscribe)', () => {
  const TOKEN = 'tok_0123456789abcdefghij';

  it('confirms a subscription once with the token from the link (generic answer)', async () => {
    mockApi({ 'POST /public/status/confirm': json({ message: 'If the link was valid, your subscription to status updates is now confirmed.' }) });
    renderWithProviders(<StatusSubscriptionPage action="confirm" />, { route: `/status/confirm?token=${TOKEN}`, auth: makeAuth(null) });
    expect(await screen.findByRole('heading', { level: 1, name: 'Confirmation received' })).toBeInTheDocument();
    expect(screen.getByText('If the link was valid, your subscription to status updates is now confirmed.')).toBeInTheDocument();
    const calls = requests.filter((request) => request.url === '/public/status/confirm');
    expect(calls).toHaveLength(1);
    expect(calls[0].body).toEqual({ token: TOKEN });
    expect(screen.getByRole('link', { name: 'Open the status page' })).toHaveAttribute('href', '/status');
  });

  it('unsubscribes', async () => {
    mockApi({ 'POST /public/status/unsubscribe': { status: 200, data: { ok: true } } });
    renderWithProviders(<StatusSubscriptionPage action="unsubscribe" />, { route: `/status/unsubscribe?token=${TOKEN}`, auth: makeAuth(null) });
    expect(await screen.findByRole('heading', { level: 1, name: 'Unsubscribe request received' })).toBeInTheDocument();
    // No message in the answer → the same conditional wording as the API.
    expect(screen.getByText('If the link was valid, you will no longer receive status updates.')).toBeInTheDocument();
  });

  it('shows one generic message for an invalid, used or expired link', async () => {
    mockApi({ 'POST /public/status/confirm': { status: 400, data: apiErrorBody(400, 'Invalid token') } });
    renderWithProviders(<StatusSubscriptionPage action="confirm" />, { route: `/status/confirm?token=${TOKEN}`, auth: makeAuth(null) });
    expect(await screen.findByRole('heading', { level: 1, name: 'This link is invalid or has expired' })).toBeInTheDocument();
  });

  it('does not call the API without a usable token', () => {
    renderWithProviders(<StatusSubscriptionPage action="unsubscribe" />, { route: '/status/unsubscribe?token=<script>', auth: makeAuth(null) });
    expect(screen.getByRole('heading', { level: 1, name: 'This link is invalid or has expired' })).toBeInTheDocument();
    expect(requests.filter((request) => request.url.startsWith('/public/status'))).toHaveLength(0);
  });

  it('offers a retry when the API cannot be reached', async () => {
    let calls = 0;
    mockApi({
      'POST /public/status/confirm': () => {
        calls += 1;
        return calls === 1 ? { status: 503, data: apiErrorBody(503, 'Unavailable') } : { status: 204 };
      },
    });
    renderWithProviders(<StatusSubscriptionPage action="confirm" />, { route: `/status/confirm?token=${TOKEN}`, auth: makeAuth(null) });
    expect(await screen.findByRole('heading', { level: 1, name: 'We could not complete this right now' })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: /Try again/ }));
    await waitFor(() => expect(screen.getByRole('heading', { level: 1, name: 'Confirmation received' })).toBeInTheDocument());
  });
});
