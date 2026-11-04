import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { hrPortal, makeDetail, payroll, payrollDb } from '../components/services/fixtures.test-utils';
import { apiErrorBody, json, mockApi, requests } from '../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../test/utils';
import ServicesPage from './ServicesPage';

const departments = [
  { id: 2, code: 'APP', name: 'Application Support', description: null, managerId: null, managerName: null, email: null, memberCount: 4, demo: false },
  { id: 4, code: 'INFRA', name: 'Infrastructure', description: null, managerId: null, managerName: null, email: null, memberCount: 3, demo: false },
];

function routes(extra: Parameters<typeof mockApi>[0] = {}) {
  mockApi({
    'GET /services': json([payroll, payrollDb, hrPortal]),
    'GET /departments': json(departments),
    'GET /users/assignable': json([]),
    'GET /services/1': json(makeDetail()),
    ...extra,
  });
}

const lastRequest = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

describe('ServicesPage', () => {
  it('lists services with status, dependency reason, tier, availability, incidents and owner', async () => {
    routes();
    renderWithProviders(<ServicesPage />, { route: '/services', auth: makeAuth(makeUser('SUPPORT_AGENT')) });

    const card = await screen.findByRole('article', { name: 'Payroll' });
    expect(within(card).getByText('Degraded')).toBeInTheDocument();
    expect(within(card).getByText('Dependency Payroll DB is in outage')).toBeInTheDocument();
    expect(within(card).getByText('Tier 1')).toBeInTheDocument();
    expect(within(card).getByText('99.9%')).toBeInTheDocument();
    expect(within(card).getByText('3 open')).toBeInTheDocument();
    expect(within(card).getByText('1 critical')).toBeInTheDocument();
    expect(within(card).getByText('Riya V.')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Payroll' })).toHaveAttribute('href', '/services/1');
    // KPIs are computed from the loaded services.
    expect(screen.getByText('Degraded or outage').closest('.kpi')).toHaveTextContent('2');
    // Not a config admin: no create / edit.
    expect(screen.queryByRole('button', { name: /New Service/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit Payroll' })).not.toBeInTheDocument();
  });

  it('filters by search, status and tier and switches to the table view', async () => {
    routes();
    renderWithProviders(<ServicesPage />, { route: '/services' });
    const user = userEvent.setup();
    await screen.findByRole('article', { name: 'Payroll' });

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'OUTAGE');
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Payroll' })).not.toBeInTheDocument());
    expect(screen.getByRole('article', { name: 'Payroll DB' })).toBeInTheDocument();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), '');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Tier' }), '2');
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(1));
    expect(screen.getByRole('article', { name: 'HR Portal' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Table view' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(screen.getByRole('row', { name: 'HR Portal' })).toBeInTheDocument();
  });

  it('creates a service and always sends dependsOnIds', async () => {
    routes({ 'POST /services': json({ ...payrollDb, id: 9, code: 'BI', name: 'Business Intelligence' }, 201) });
    renderWithProviders(<ServicesPage />, { route: '/services' });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /New Service/ }));
    const dialog = await screen.findByRole('dialog', { name: 'New business service' });
    await user.type(within(dialog).getByLabelText(/^Code/), 'BI');
    await user.type(within(dialog).getByLabelText(/^Name/), 'Business Intelligence');
    await user.selectOptions(within(dialog).getByLabelText(/Owning department/), '2');
    await user.click(await within(dialog).findByRole('checkbox', { name: /Payroll DB/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Save service' }));

    await waitFor(() => expect(lastRequest('POST', '/services')).toBeTruthy());
    expect(lastRequest('POST', '/services')?.body).toMatchObject({ code: 'BI', name: 'Business Intelligence', departmentId: 2, tier: 2, dependsOnIds: [2] });
  });

  it('edits a service: loads its dependencies, blocks itself and its dependents, and shows the cycle error', async () => {
    routes({ 'PUT /services/1': { status: 400, data: apiErrorBody(400, 'Dependencies would create a cycle: PAYROLL → HR-PORTAL → PAYROLL') } });
    renderWithProviders(<ServicesPage />, { route: '/services' });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Edit Payroll' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit Payroll' });
    const db = await within(dialog).findByRole('checkbox', { name: /Payroll DB/ });
    expect(db).toBeChecked();
    // The service itself is not offered; HR Portal depends on Payroll, so choosing it would be a cycle.
    expect(within(dialog).queryByRole('checkbox', { name: /^Payroll · PAYROLL/ })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('checkbox', { name: /HR Portal/ })).toBeDisabled();

    await user.click(db);
    await user.click(within(dialog).getByRole('button', { name: 'Save service' }));
    expect(await within(dialog).findByText('Dependencies would create a cycle: PAYROLL → HR-PORTAL → PAYROLL')).toBeInTheDocument();
    expect(lastRequest('PUT', '/services/1')?.body).toMatchObject({ code: 'PAYROLL', dependsOnIds: [] });
  });
});
