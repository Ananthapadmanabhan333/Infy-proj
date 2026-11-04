import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeDeployment, makeDeploymentDetail, pageOf } from '../../test/itsmFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import DeploymentDetailPage from './DeploymentDetailPage';
import DeploymentsPage from './DeploymentsPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);
const SOURCE = {
  id: 1,
  name: 'payroll-github',
  provider: 'GITHUB',
  serviceId: 3,
  serviceName: 'Payroll',
  defaultEnvironment: 'PRODUCTION',
  active: true,
  webhookPath: '/api/deployments/webhooks/1',
  lastDeliveryAt: null,
  demo: true,
  createdAt: '2026-10-01T08:00:00Z',
};

function routes() {
  mockApi({
    'GET /deployments': json(pageOf([makeDeployment(), makeDeployment({ id: 32, number: 'DEP-0032', changeId: null, changeNumber: null, status: 'FAILED', version: '9.19' })])),
    'GET /deployments/metrics': json({ from: '2026-10-05', to: '2026-11-04', total: 6, byEnvironment: { PRODUCTION: 4 }, byStatus: {}, successRate: 66.7, failureRate: 16.7, rollbackRate: 16.7, avgDurationMinutes: 6, productionWithoutChange: 1, followedByIncidents: 1 }),
    'GET /services': json([{ id: 3, code: 'PAYROLL', name: 'Payroll', tier: 1 }]),
    'GET /deployments/sources': json([SOURCE]),
    'POST /deployments/sources/1/rotate-secret': json({ source: SOURCE, secret: 'whsec_rotated_123' }),
    'GET /deployments/31': json(makeDeploymentDetail()),
    'POST /deployments/31/rollback': json(makeDeployment({ id: 33, number: 'DEP-0033', version: '9.17', rollbackOfId: 31, status: 'IN_PROGRESS' }), 201),
  });
}

describe('DeploymentsPage', () => {
  it('lists deployments with environment, status and change link', async () => {
    routes();
    renderWithProviders(<DeploymentsPage />, { route: '/deployments', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const table = await screen.findByRole('table', { name: 'Deployments' });
    expect(within(table).getByRole('link', { name: 'CHG-1014' })).toHaveAttribute('href', '/changes/14');
    expect(within(table).getByText('No change')).toBeInTheDocument();
    expect(within(table).getAllByText('Production').length).toBe(2);
    expect(screen.queryByRole('button', { name: /Record deployment/ })).not.toBeInTheDocument();
  });

  it('shows webhook set-up hints to readers without the source list', async () => {
    routes();
    renderWithProviders(<DeploymentsPage />, { route: '/deployments?tab=integrations', auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByText(/X-Hub-Signature-256/)).toBeInTheDocument();
    expect(screen.getByText(/X-Gitlab-Token/)).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Webhook sources' })).not.toBeInTheDocument();
    expect(requests.some((request) => request.url === '/deployments/sources')).toBe(false);
  });

  it('lets deployment editors rotate a source secret, shown once', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<DeploymentsPage />, { route: '/deployments?tab=integrations', auth: makeAuth(makeUser('CHANGE_MANAGER')) });
    const sources = await screen.findByRole('list', { name: 'Webhook sources' });
    expect(within(sources).getByText(/POST .*\/api\/deployments\/webhooks\/1/)).toBeInTheDocument();
    await user.click(within(sources).getByRole('button', { name: 'Rotate secret of payroll-github' }));
    const confirm = await screen.findByRole('dialog', { name: /Rotate the secret/ });
    await user.click(within(confirm).getByRole('button', { name: 'Rotate secret' }));
    const secret = await screen.findByRole('dialog', { name: /Webhook secret for payroll-github/ });
    expect(within(secret).getByLabelText('Secret')).toHaveValue('whsec_rotated_123');
    await user.click(within(secret).getByRole('button', { name: /I have stored the secret/ }));
    await waitFor(() => expect(screen.queryByDisplayValue('whsec_rotated_123')).not.toBeInTheDocument());
  });
});

describe('DeploymentDetailPage', () => {
  it('shows commit, pipeline, rules-based risk and correlated incidents, and rolls back', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/deployments/:id" element={<DeploymentDetailPage />} />
      </Routes>,
      { route: '/deployments/31', auth: makeAuth(makeUser('CHANGE_MANAGER')) },
    );
    expect(await screen.findByRole('heading', { level: 1, name: 'VPN 9.18' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open pipeline/ })).toHaveAttribute('href', 'https://ci.example.test/pipelines/77');
    expect(screen.getByText('a1b2c3d4e5f6')).toBeInTheDocument();
    expect(screen.getByText(/Rules-based score — not AI-generated/)).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Correlated incidents' })).toHaveTextContent('INC-7090');

    await user.click(screen.getByRole('button', { name: /Roll back/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Roll back DEP-0031' });
    await user.type(within(dialog).getByLabelText(/Reason/), 'Tunnels drop after the upgrade');
    await user.click(within(dialog).getByRole('button', { name: 'Roll back' }));
    await waitFor(() => expect(last('POST', '/deployments/31/rollback')?.body).toEqual({ reason: 'Tunnels drop after the upgrade' }));
  });
});
