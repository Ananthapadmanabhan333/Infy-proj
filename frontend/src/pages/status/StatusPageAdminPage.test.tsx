import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { StatusPageView } from '../../lib/types';
import StatusPageAdminPage, { maintenanceTransitions } from './StatusPageAdminPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const history = Array.from({ length: 90 }, (_, index) => ({ date: `2026-08-${String((index % 28) + 1).padStart(2, '0')}`, availability: index === 3 ? 97.5 : 100, maintenance: index === 10 }));

const VIEW: StatusPageView = {
  settings: { title: 'SA service status', description: 'Live status', publicEnabled: false, updatedAt: '2026-11-01T08:00:00Z' },
  overall: 'DEGRADED',
  components: [
    { id: 1, serviceId: 11, serviceCode: 'PAYROLL', serviceName: 'Payroll', displayName: 'Payroll', groupName: 'Business applications', description: null, sortOrder: 1, publicVisible: true, status: 'DEGRADED', statusReason: 'Dependency down', uptime90d: 99.73, history, demo: true },
    { id: 2, serviceId: 12, serviceCode: 'VPN', serviceName: 'VPN', displayName: 'Remote access', groupName: null, description: null, sortOrder: 2, publicVisible: false, status: 'OPERATIONAL', statusReason: null, uptime90d: 100, history, demo: true },
  ],
  maintenance: [
    {
      id: 5,
      title: 'Payroll upgrade',
      description: 'Database upgrade',
      startsAt: '2026-11-08T20:00:00Z',
      endsAt: '2026-11-08T23:00:00Z',
      status: 'SCHEDULED',
      storedStatus: 'SCHEDULED',
      components: [{ id: 1, displayName: 'Payroll', serviceId: 11, publicVisible: true }],
      demo: true,
    },
  ],
  updates: [
    { id: 8, title: 'Payroll slowness', status: 'MONITORING', message: 'A fix is deployed.', publicVisible: true, incidentId: 70, incidentNumber: 'INC-7070', postedAt: '2026-11-04T07:00:00Z', demo: true },
  ],
  subscribers: { confirmed: 4, pending: 1 },
  emailConfigured: false,
  generatedAt: '2026-11-04T08:00:00Z',
};

function setup(view: StatusPageView = VIEW) {
  mockApi({
    'GET /status-page': json(view),
    'PUT /status-page/settings': (config) => json({ ...JSON.parse(String(config.data)), updatedAt: '2026-11-04T09:00:00Z' }),
    'POST /status-page/maintenance/5/status': json({ ...VIEW.maintenance[0], status: 'IN_PROGRESS', storedStatus: 'IN_PROGRESS' }),
    'POST /status-page/updates': (config) => json({ id: 9, ...JSON.parse(String(config.data)), postedAt: '2026-11-04T09:00:00Z', demo: false }, 201),
    'GET /services': json([]),
  });
}

describe('maintenance transitions', () => {
  it('follows the API state machine', () => {
    expect(maintenanceTransitions('SCHEDULED')).toEqual(['IN_PROGRESS', 'COMPLETED', 'CANCELLED']);
    expect(maintenanceTransitions('IN_PROGRESS')).toEqual(['COMPLETED', 'CANCELLED']);
    expect(maintenanceTransitions('COMPLETED')).toEqual([]);
  });
});

describe('StatusPageAdminPage', () => {
  it('shows all components (including internal ones), uptime and the e-mail warning to editors', async () => {
    setup();
    renderWithProviders(<StatusPageAdminPage />, { route: '/status-page', auth: makeAuth(makeUser('INCIDENT_MANAGER')) });
    const table = await screen.findByRole('table', { name: 'Business applications components' });
    expect(within(table).getByText('99.73%')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Other services components' })).toBeInTheDocument();
    expect(screen.getAllByText('Internal').length).toBeGreaterThan(0);
    expect(screen.getByText(/E-mail is not configured\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Preview public page/ })).toHaveAttribute('href', '/status');
    expect(screen.getByRole('button', { name: /Add component/ })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Settings/ })).toBeInTheDocument();
  });

  it('publishes the page only after confirmation', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<StatusPageAdminPage />, { route: '/status-page?tab=settings', auth: makeAuth(makeUser('SYSTEM_ADMIN')) });
    await user.click(await screen.findByRole('switch', { name: 'Publish the public status page' }));
    const dialog = await screen.findByRole('dialog', { name: 'Publish the status page?' });
    expect(last('PUT', '/status-page/settings')).toBeUndefined();
    await user.click(within(dialog).getByRole('button', { name: 'Publish' }));
    await waitFor(() => expect(last('PUT', '/status-page/settings')?.body).toEqual({ title: 'SA service status', description: 'Live status', publicEnabled: true }));
  });

  it('starts a maintenance window through the status endpoint', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<StatusPageAdminPage />, { route: '/status-page?tab=maintenance', auth: makeAuth(makeUser('IT_ADMIN')) });
    await user.click(await screen.findByRole('button', { name: 'Start now' }));
    const dialog = await screen.findByRole('dialog', { name: /Start now/ });
    await user.click(within(dialog).getByRole('button', { name: 'Start now' }));
    await waitFor(() => expect(last('POST', '/status-page/maintenance/5/status')?.body).toEqual({ status: 'IN_PROGRESS' }));
  });

  it('posts an incident update', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<StatusPageAdminPage />, { route: '/status-page?tab=updates', auth: makeAuth(makeUser('INCIDENT_MANAGER')) });
    expect(await screen.findByRole('link', { name: 'INC-7070' })).toHaveAttribute('href', '/incidents/70');
    await user.click(screen.getByRole('button', { name: /Post update/ }));
    const form = await screen.findByRole('form', { name: 'Post status update' });
    await user.type(within(form).getByLabelText(/^Title/), 'VPN degraded');
    await user.selectOptions(within(form).getByLabelText(/^Status/), 'IDENTIFIED');
    await user.type(within(form).getByLabelText(/^Message/), 'Root cause found.');
    await user.click(within(form).getByLabelText(/Public update/));
    await user.click(within(form).getByRole('button', { name: 'Post update' }));
    await waitFor(() =>
      expect(last('POST', '/status-page/updates')?.body).toEqual({ title: 'VPN degraded', status: 'IDENTIFIED', message: 'Root cause found.', incidentId: null, publicVisible: true }),
    );
  });

  it('is read-only for employees', async () => {
    setup({ ...VIEW, subscribers: null, components: [VIEW.components[0]] });
    renderWithProviders(<StatusPageAdminPage />, { route: '/status-page?tab=settings', auth: makeAuth(makeUser('EMPLOYEE')) });
    await screen.findByRole('table', { name: 'Business applications components' });
    expect(screen.queryByRole('tab', { name: /Settings/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add component/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit Payroll/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Public page/ })).toBeInTheDocument();
  });
});
