import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { inboundUrl, isFailing } from '../components/admin/IntegrationDialogs';
import { json, mockApi, requests } from '../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../test/utils';
import type { DeliveryDto, IntegrationDto } from '../lib/types';
import IntegrationsPage from './IntegrationsPage';

function integration(id: number, name: string, overrides: Partial<IntegrationDto> = {}): IntegrationDto {
  return {
    id,
    name,
    type: 'WEBHOOK',
    enabled: true,
    endpointUrl: `https://hooks.example.com/${id}`,
    hasSecret: true,
    subscribedEvents: ['incident.created'],
    defaultServiceId: null,
    defaultServiceName: null,
    lastDeliveryAt: '2026-11-03T09:00:00Z',
    lastDeliveryStatus: 'SUCCESS',
    failureCount: 0,
    inboundPath: null,
    createdAt: '2026-10-01T09:00:00Z',
    demo: false,
    ...overrides,
  };
}

const INTEGRATIONS = [
  integration(1, 'Ops webhook'),
  integration(2, 'Service desk Teams', { type: 'TEAMS', lastDeliveryStatus: 'FAILED', failureCount: 3 }),
  integration(3, 'Grafana alerts', { type: 'MONITORING_INBOUND', endpointUrl: null, inboundPath: '/api/integrations/inbound/3', subscribedEvents: [], lastDeliveryAt: null, lastDeliveryStatus: null }),
];

const DELIVERIES: DeliveryDto[] = [{ id: 7, eventType: 'incident.created', status: 'SUCCESS', httpStatus: 200, responseSnippet: 'ok', durationMs: 120, createdAt: '2026-11-03T09:00:00Z' }];

function setup() {
  mockApi({
    'GET /integrations': json(INTEGRATIONS),
    'GET /integrations/1/deliveries': json(DELIVERIES),
    'GET /integrations/3/deliveries': json([]),
    'POST /integrations/1/test': json({ status: 'SUCCESS', httpStatus: 200 }),
    'GET /services': json([]),
  });
}

describe('integration helpers', () => {
  it('derives inbound URLs and failing state', () => {
    expect(inboundUrl({ inboundPath: '/api/integrations/inbound/3' })).toBe(`${window.location.origin}/api/integrations/inbound/3`);
    expect(inboundUrl({ inboundPath: null })).toBeNull();
    expect(isFailing(INTEGRATIONS[1])).toBe(true);
    expect(isFailing(INTEGRATIONS[0])).toBe(false);
  });
});

describe('IntegrationsPage', () => {
  it('shows cards, tab counts and KPIs for administrators', async () => {
    setup();
    renderWithProviders(<IntegrationsPage />, { route: '/integrations', auth: makeAuth(makeUser('IT_ADMIN')) });
    expect(await screen.findByRole('article', { name: 'Ops webhook' })).toBeInTheDocument();
    expect(screen.getByRole('article', { name: 'Grafana alerts' })).toHaveTextContent('/api/integrations/inbound/3');
    expect(screen.getByRole('tab', { name: /Needs attention/ })).toHaveTextContent('(1)');
    expect(screen.getByRole('link', { name: /^Needs attention:/ })).toHaveTextContent('1');
    expect(screen.getByRole('button', { name: /New Integration/ })).toBeInTheDocument();
  });

  it('filters to failing integrations', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<IntegrationsPage />, { route: '/integrations' });
    await screen.findByRole('article', { name: 'Ops webhook' });
    await user.click(screen.getByRole('tab', { name: /Needs attention/ }));
    expect(screen.queryByRole('article', { name: 'Ops webhook' })).not.toBeInTheDocument();
    expect(screen.getByRole('article', { name: 'Service desk Teams' })).toBeInTheDocument();
  });

  it('lets administrators open the drawer, review deliveries and send a test', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<IntegrationsPage />, { route: '/integrations' });
    await user.click(await screen.findByRole('button', { name: 'Open Ops webhook' }));
    const drawer = await screen.findByRole('dialog', { name: /Ops webhook/ });
    expect(await within(drawer).findByRole('table', { name: 'Deliveries' })).toHaveTextContent('120 ms');
    await user.click(within(drawer).getByRole('button', { name: /Send test/ }));
    await waitFor(() => expect(requests.some((request) => request.method === 'POST' && request.url === '/integrations/1/test')).toBe(true));
    expect(await screen.findByText('Test delivery sent')).toBeInTheDocument();
  });

  it('is read-only for managers', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<IntegrationsPage />, { route: '/integrations', auth: makeAuth(makeUser('IT_MANAGER')) });
    await screen.findByRole('article', { name: 'Ops webhook' });
    expect(screen.queryByRole('button', { name: /New Integration/ })).not.toBeInTheDocument();
    expect(screen.getByText('Read-only access')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Open Grafana alerts' }));
    const drawer = await screen.findByRole('dialog', { name: /Grafana alerts/ });
    expect(within(drawer).queryByRole('button', { name: /Delete|Rotate token|Send test|Edit/ })).not.toBeInTheDocument();
    expect(within(drawer).getByText(/Read-only/)).toBeInTheDocument();
  });
});
