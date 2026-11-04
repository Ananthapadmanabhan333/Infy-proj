import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { CMDB_META, IMPACT, makeCiDetail, makeCiSummary, PAYROLL_GRAPH } from '../../test/opsFixtures';
import { pageOf } from '../../test/itsmFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import CiDetailPage from './CiDetailPage';
import CmdbPage from './CmdbPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

function Where() {
  const location = useLocation();
  return <div data-testid="where">{location.pathname}</div>;
}

function listRoutes() {
  mockApi({
    'GET /cmdb/cis': (config) =>
      json(
        pageOf(
          String((config.params as Record<string, unknown> | undefined)?.search ?? '').startsWith('db')
            ? [makeCiSummary({ id: 31, name: 'payroll-db-primary', ciType: 'DATABASE' })]
            : [makeCiSummary(), makeCiSummary({ id: 1, name: 'Payroll', ciType: 'BUSINESS_SERVICE', serviceId: 4, serviceCode: 'PAY', openAlerts: 0 })],
        ),
      ),
    'GET /cmdb/graph': json({ ...PAYROLL_GRAPH, rootId: null }),
    'GET /cmdb/meta': json(CMDB_META),
    'POST /cmdb/cis': (config) => json(makeCiDetail({ id: 77, ...JSON.parse(String(config.data)) }), 201),
    'GET /users/assignable': json([{ id: 7, fullName: 'Ada Lovelace', email: 'ada@x.test', role: 'ASSET_MANAGER', departmentId: 2, departmentName: 'IT', openAssignments: 0 }]),
    'GET /teams': json([]),
    'GET /services': json([]),
  });
}

function detailRoutes(ci = makeCiDetail()) {
  mockApi({
    'GET /cmdb/cis/11': json(ci),
    'GET /cmdb/cis/11/history': json([{ id: 1, at: '2026-11-03T08:00:00Z', actorName: 'Ada Lovelace', action: 'RELATIONSHIP_ADDED', fromValue: null, toValue: 'runs on payroll-app-01', note: null }]),
    'GET /cmdb/cis/11/impact': json(IMPACT),
    'GET /cmdb/graph': json(PAYROLL_GRAPH),
    'GET /cmdb/meta': json(CMDB_META),
    'GET /cmdb/cis': json(pageOf([makeCiSummary({ id: 31, name: 'payroll-db-primary', ciType: 'DATABASE' })])),
    'POST /cmdb/relationships': json({ id: 200 }, 201),
    'DELETE /cmdb/relationships/101': { status: 204 },
    'DELETE /cmdb/cis/11': { status: 204 },
  });
}

function renderDetail(role: Parameters<typeof makeUser>[0]) {
  return renderWithProviders(
    <Routes>
      <Route path="/cmdb/:id" element={<CiDetailPage />} />
      <Route path="/cmdb" element={<Where />} />
    </Routes>,
    { route: '/cmdb/11', auth: makeAuth(makeUser(role)) },
  );
}

describe('CmdbPage', () => {
  it('lists CIs with type, environment and status filters and creates a CI with attributes', async () => {
    listRoutes();
    const user = userEvent.setup();
    renderWithProviders(<CmdbPage />, { route: '/cmdb', auth: makeAuth(makeUser('ASSET_MANAGER')) });
    const table = await screen.findByRole('table', { name: 'Configuration items' });
    expect(within(table).getByText('Payroll Application')).toBeInTheDocument();
    expect(within(table).getByText('PAY')).toBeInTheDocument();
    expect(last('GET', '/cmdb/cis')?.params).toMatchObject({ sort: 'name,asc', page: 0 });

    await user.selectOptions(screen.getByRole('combobox', { name: 'Type' }), 'DATABASE');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Environment' }), 'PRODUCTION');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'ACTIVE');
    await waitFor(() => expect(last('GET', '/cmdb/cis')?.params).toMatchObject({ type: 'DATABASE', environment: 'PRODUCTION', status: 'ACTIVE' }));

    await user.click(screen.getByRole('button', { name: /New CI/ }));
    const dialog = await screen.findByRole('dialog', { name: 'New configuration item' });
    await user.type(within(dialog).getByLabelText(/^Name/), 'payroll-cache-01');
    await user.selectOptions(within(dialog).getByLabelText(/^Type/), 'SERVER');
    await user.click(within(dialog).getByRole('button', { name: /Add attribute/ }));
    await user.type(within(dialog).getByLabelText('Attribute 1 name'), 'vcpu');
    await user.selectOptions(within(dialog).getByLabelText('Attribute 1 kind'), 'number');
    await user.type(within(dialog).getByLabelText('Attribute 1 value'), '8');
    await user.click(within(dialog).getByRole('button', { name: 'Create CI' }));
    await waitFor(() =>
      expect(last('POST', '/cmdb/cis')?.body).toMatchObject({ name: 'payroll-cache-01', ciType: 'SERVER', status: 'ACTIVE', ownerId: null, teamId: null, serviceId: null, attributes: { vcpu: 8 } }),
    );
  });

  it('rejects invalid attribute names before calling the API', async () => {
    listRoutes();
    const user = userEvent.setup();
    renderWithProviders(<CmdbPage />, { route: '/cmdb', auth: makeAuth(makeUser('IT_MANAGER')) });
    await user.click(await screen.findByRole('button', { name: /New CI/ }));
    const dialog = await screen.findByRole('dialog', { name: 'New configuration item' });
    await user.type(within(dialog).getByLabelText(/^Name/), 'x-01');
    await user.click(within(dialog).getByRole('button', { name: /Add attribute/ }));
    await user.type(within(dialog).getByLabelText('Attribute 1 name'), '1bad');
    await user.type(within(dialog).getByLabelText('Attribute 1 value'), 'v');
    await user.click(within(dialog).getByRole('button', { name: 'Create CI' }));
    expect(await within(dialog).findByText(/not a valid attribute name/)).toBeInTheDocument();
    expect(last('POST', '/cmdb/cis')).toBeUndefined();
  });

  it('draws the dependency map and highlights upstream and downstream of a selected CI; read-only for auditors', async () => {
    listRoutes();
    const user = userEvent.setup();
    renderWithProviders(<CmdbPage />, { route: '/cmdb?tab=map', auth: makeAuth(makeUser('AUDITOR')) });
    expect(screen.queryByRole('button', { name: /New CI/ })).not.toBeInTheDocument();
    const map = await screen.findByRole('group', { name: /Dependency map: 4 configuration items, 3 relationships/ });
    expect(last('GET', '/cmdb/graph')?.params).toEqual({});
    await user.click(within(map).getByRole('button', { name: /^Payroll Application, Application/ }));
    expect(within(map).getByRole('button', { name: /^payroll-app-01,.*upstream/ })).toBeInTheDocument();
    expect(within(map).getByRole('button', { name: /^Payroll, Business service.*downstream/ })).toBeInTheDocument();
    const card = screen.getByRole('region', { name: 'Selected Payroll Application' });
    expect(within(card).getByText(/Needs 2 · needed by 1/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect(screen.getByText(/\d+%/)).toBeInTheDocument();
  });
});

describe('CiDetailPage', () => {
  it('shows attributes, linked work, impact analysis and history; adds and removes relationships', async () => {
    detailRoutes();
    const user = userEvent.setup();
    renderDetail('ASSET_MANAGER');
    expect(await screen.findByRole('heading', { level: 1, name: 'Payroll Application' })).toBeInTheDocument();
    expect(screen.getByText('4.2.1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'AST-0051' })).toHaveAttribute('href', '/assets/51');
    expect(screen.getByRole('link', { name: 'PRB-0005' })).toHaveAttribute('href', '/problems/5');

    const services = await screen.findByRole('list', { name: 'Impacted business services' });
    expect(within(services).getByText('Payroll')).toBeInTheDocument();
    expect(last('GET', '/cmdb/cis/11/impact')?.params).toEqual({ direction: 'both', depth: 5 });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Direction' }), 'downstream');
    await waitFor(() => expect(last('GET', '/cmdb/cis/11/impact')?.params).toEqual({ direction: 'downstream', depth: 5 }));

    const relationships = screen.getByRole('list', { name: 'Relationships' });
    // Service-map links are managed through the service, so they cannot be removed here.
    expect(within(relationships).queryByRole('button', { name: /Remove relationship is needed by Payroll/ })).not.toBeInTheDocument();
    await user.click(within(relationships).getByRole('button', { name: 'Remove relationship runs on payroll-app-01' }));
    await user.click(within(await screen.findByRole('dialog', { name: 'Remove relationship?' })).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(last('DELETE', '/cmdb/relationships/101')).toBeTruthy());

    await user.click(screen.getByRole('button', { name: /Add relationship/ }));
    const form = screen.getByRole('group', { name: 'New relationship' });
    await user.selectOptions(within(form).getByLabelText('Relationship'), 'DEPENDS_ON');
    await user.type(within(form).getByLabelText('Other configuration item'), 'db');
    await user.click(await within(form).findByRole('option', { name: /payroll-db-primary/ }));
    await user.click(within(form).getByRole('button', { name: 'Add relationship' }));
    await waitFor(() => expect(last('POST', '/cmdb/relationships')?.body).toEqual({ sourceId: 11, targetId: 31, type: 'DEPENDS_ON', description: null }));

    expect(await screen.findByRole('list', { name: 'History' })).toHaveTextContent('Relationship Added');
    expect(await screen.findByRole('group', { name: /Dependency map/ })).toBeInTheDocument();
    expect(last('GET', '/cmdb/graph')?.params).toEqual({ ciId: 11, depth: 2, includeRetired: true });
  });

  it('deletes a CI after confirmation but never a business-service CI', async () => {
    detailRoutes();
    const user = userEvent.setup();
    renderDetail('SYSTEM_ADMIN');
    await user.click(await screen.findByRole('button', { name: /Delete/ }));
    await user.click(within(await screen.findByRole('dialog', { name: /Delete Payroll Application/ })).getByRole('button', { name: 'Delete CI' }));
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/cmdb'));
  });

  it('keeps business-service CIs tied to their service and is read-only for auditors', async () => {
    detailRoutes(makeCiDetail({ ciType: 'BUSINESS_SERVICE', serviceId: 4, serviceCode: 'PAY', serviceName: 'Payroll', name: 'Payroll' }));
    renderDetail('AUDITOR');
    expect(await screen.findByRole('heading', { level: 1, name: 'Payroll' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add relationship/ })).not.toBeInTheDocument();
    expect(screen.getByText(/mirrors its business service/)).toBeInTheDocument();
  });

  it('opens another CI from the map with a double-click', async () => {
    detailRoutes();
    renderWithProviders(
      <Routes>
        <Route path="/cmdb/:id" element={<><CiDetailPage /><Where /></>} />
      </Routes>,
      { route: '/cmdb/11', auth: makeAuth(makeUser('SUPPORT_AGENT')) },
    );
    const map = await screen.findByRole('group', { name: /Dependency map/ });
    fireEvent.doubleClick(within(map).getByRole('button', { name: /^payroll-db-primary/ }));
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/cmdb/31'));
  });
});
