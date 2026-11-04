import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { pageOf } from '../../test/itsmFixtures';
import { ASSET_STATS, makeAssetDetail, makeAssetSummary } from '../../test/opsFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { ImportResult } from '../../lib/opsTypes';
import AssetDetailPage from './AssetDetailPage';
import AssetsPage from './AssetsPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const EXPIRING = [
  { asset: makeAssetSummary({ id: 71, assetTag: 'AST-0071', name: 'vpn-gw-01 appliance', assetType: 'NETWORK', assignedToName: null }), kind: 'WARRANTY', expiresOn: '2026-11-16', daysLeft: 12, expired: false },
  { asset: makeAssetSummary({ id: 72, assetTag: 'AST-0072', name: 'core-sw-01', assetType: 'NETWORK', assignedToName: null }), kind: 'WARRANTY', expiresOn: '2026-10-15', daysLeft: -20, expired: true },
];

const DRY_RUN_ERRORS: ImportResult = {
  dryRun: true,
  applied: false,
  totalRows: 2,
  validRows: 1,
  created: 1,
  updated: 1,
  errors: [{ row: 3, field: 'assignedToEmail', message: 'No active user with this e-mail' }],
  rows: [
    { row: 2, assetTag: 'AST-0100', name: 'Spare laptop', assetType: 'LAPTOP', status: 'IN_STOCK', action: 'CREATE' },
    { row: 3, assetTag: 'AST-0051', name: 'ThinkPad T14', assetType: 'LAPTOP', status: 'IN_USE', action: 'UPDATE' },
  ],
};

function listRoutes(importReplies: ImportResult[] = []) {
  const queue = [...importReplies];
  mockApi({
    'GET /assets': json(pageOf([makeAssetSummary(), makeAssetSummary({ id: 52, assetTag: 'AST-0052', name: 'Microsoft 365 E3', assetType: 'SOFTWARE_LICENSE', category: 'SOFTWARE', assignedToName: null, licenseSeats: 50, seatsUsed: 12 })])),
    'GET /assets/summary': json(ASSET_STATS),
    'GET /assets/expiring': json(EXPIRING),
    'GET /assets/export': { status: 200, data: new Blob(['assetTag\n']), headers: { 'content-disposition': 'attachment; filename="assets.csv"' } },
    'GET /assets/import-template': { status: 200, data: new Blob(['assetTag,name\n']) },
    'POST /assets/import': () => json(queue.shift()),
    'POST /assets': (config) => json(makeAssetDetail({}, { id: 90, assetTag: 'AST-0090', ...JSON.parse(String(config.data)) }), 201),
    'GET /departments': json([{ id: 3, code: 'IT-SD', name: 'IT Service Desk', description: null, managerId: null, managerName: null, email: null, memberCount: 4, demo: false }]),
  });
}

function detailRoutes(detail = makeAssetDetail()) {
  mockApi({
    'GET /assets/51': json(detail),
    'GET /assets/51/assignments': json([{ id: 1, userId: 7, userName: 'Ada Lovelace', assignedAt: '2026-09-01T08:00:00Z', assignedByName: 'Asha Asset', returnedAt: null, returnedByName: null, note: null }]),
    'GET /assets/51/history': json([{ id: 1, type: 'STATUS_CHANGED', fromValue: 'IN_STOCK', toValue: 'IN_USE', note: null, actorName: 'Asha Asset', at: '2026-09-01T08:00:00Z' }]),
    'POST /assets/51/transition': (config) => json(makeAssetDetail({}, { status: JSON.parse(String(config.data)).status })),
    'POST /assets/51/assign': json(detail),
    'POST /assets/51/return': json(detail),
    'POST /assets/51/maintenance': json({ id: 9 }, 201),
    'GET /service-requests/user-options': json([{ id: 8, fullName: 'Grace Hopper', departmentName: 'Finance', role: 'EMPLOYEE' }]),
  });
}

function renderDetail(role: Parameters<typeof makeUser>[0]) {
  return renderWithProviders(
    <Routes>
      <Route path="/assets/:id" element={<AssetDetailPage />} />
    </Routes>,
    { route: '/assets/51', auth: makeAuth(makeUser(role)) },
  );
}

describe('AssetsPage', () => {
  it('shows KPIs from /summary, the expiring panel and filters; exports the filtered CSV', async () => {
    listRoutes();
    URL.createObjectURL = vi.fn(() => 'blob:assets');
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const user = userEvent.setup();
    renderWithProviders(<AssetsPage />, { route: '/assets', auth: makeAuth(makeUser('AUDITOR')) });
    const table = await screen.findByRole('table', { name: 'Assets' });
    expect(within(table).getByText('12 / 50 seats')).toBeInTheDocument();
    expect(screen.getByText('19 / 65')).toBeInTheDocument();
    expect(screen.getByText(/28 hardware · 3 licences/)).toBeInTheDocument();
    const expiring = await screen.findByRole('list', { name: 'Expiring warranties and licences' });
    expect(within(expiring).getByText('12 d left')).toBeInTheDocument();
    expect(within(expiring).getByText('ended 20 d ago')).toBeInTheDocument();
    // Auditors read and export, but cannot create or import.
    expect(screen.queryByRole('button', { name: /New asset/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Import CSV/ })).not.toBeInTheDocument();

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'IN_USE');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Warranty' }), 'EXPIRING');
    await waitFor(() => expect(last('GET', '/assets')?.params).toMatchObject({ status: 'IN_USE', warranty: 'EXPIRING', sort: 'assetTag,asc' }));
    await user.click(screen.getByRole('button', { name: /Export CSV/ }));
    await waitFor(() => expect(last('GET', '/assets/export')?.params).toEqual({ status: 'IN_USE', warranty: 'EXPIRING' }));
    expect(URL.createObjectURL).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    click.mockRestore();
  });

  it('imports a CSV: dry run shows row-level errors, a clean dry run enables apply, apply stores', async () => {
    const clean: ImportResult = { ...DRY_RUN_ERRORS, validRows: 2, errors: [] };
    listRoutes([DRY_RUN_ERRORS, clean, { ...clean, dryRun: false, applied: true }]);
    const user = userEvent.setup();
    renderWithProviders(<AssetsPage />, { route: '/assets', auth: makeAuth(makeUser('ASSET_MANAGER')) });
    await user.click(await screen.findByRole('button', { name: /Import CSV/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Import assets from CSV' });
    const file = new File(['assetTag,name,assetType\nAST-0100,Spare laptop,LAPTOP\n'], 'assets.csv', { type: 'text/csv' });
    await user.upload(within(dialog).getByLabelText('CSV file'), file);
    await user.click(within(dialog).getByRole('button', { name: /Validate \(dry run\)/ }));

    expect(await within(dialog).findByText(/1 error in 1 row — fix the file/)).toBeInTheDocument();
    expect(within(dialog).getByRole('list', { name: 'Errors in Row 3' })).toHaveTextContent('No active user with this e-mail');
    expect(within(dialog).queryByRole('button', { name: /Apply import/ })).not.toBeInTheDocument();
    expect(last('POST', '/assets/import')?.params).toEqual({ dryRun: true });
    expect(last('POST', '/assets/import')?.body).toBeInstanceOf(FormData);

    await user.click(within(dialog).getByRole('button', { name: /Validate again/ }));
    expect(await within(dialog).findByText(/All 2 rows are valid/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: /Apply import \(2 rows\)/ }));
    expect(await within(dialog).findByText(/Imported 2 rows: 1 asset created, 1 asset updated/)).toBeInTheDocument();
    expect(last('POST', '/assets/import')?.params).toEqual({ dryRun: false });
  });

  it('rejects a non-CSV file before uploading and creates an asset with the editor', async () => {
    listRoutes();
    const user = userEvent.setup({ applyAccept: false });
    renderWithProviders(<AssetsPage />, { route: '/assets', auth: makeAuth(makeUser('IT_ADMIN')) });
    await user.click(await screen.findByRole('button', { name: /Import CSV/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Import assets from CSV' });
    await user.upload(within(dialog).getByLabelText('CSV file'), new File(['x'], 'assets.xlsx', { type: 'application/vnd.ms-excel' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent(/Choose a \.csv file/);
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    await user.click(screen.getByRole('button', { name: /New asset/ }));
    const editor = await screen.findByRole('dialog', { name: 'New asset' });
    await user.type(within(editor).getByLabelText(/^Name/), 'Dell U2723 monitor');
    await user.selectOptions(within(editor).getByLabelText(/^Type/), 'MONITOR');
    await user.selectOptions(within(editor).getByLabelText('Department'), '3');
    await user.type(within(editor).getByLabelText('Cost'), '32000');
    await user.click(within(editor).getByRole('button', { name: 'Create asset' }));
    await waitFor(() =>
      expect(last('POST', '/assets')?.body).toMatchObject({ assetTag: null, name: 'Dell U2723 monitor', assetType: 'MONITOR', departmentId: 3, purchaseCost: 32000, currency: 'INR', assignedToId: null }),
    );
  });
});

describe('AssetDetailPage', () => {
  it('shows facts, assignment history and history; disposing needs a note', async () => {
    detailRoutes(makeAssetDetail({ allowedTransitions: ['IN_STOCK', 'DISPOSED'] }, { status: 'RETIRED', assignedToId: null, assignedToName: null }));
    const user = userEvent.setup();
    renderDetail('ASSET_MANAGER');
    expect(await screen.findByRole('heading', { level: 1, name: 'ThinkPad T14 — Ada' })).toBeInTheDocument();
    expect(screen.getByText('PO-778')).toBeInTheDocument();
    expect(await screen.findByRole('list', { name: 'Assignment history' })).toHaveTextContent('Ada Lovelace');
    expect(await screen.findByRole('list', { name: 'History' })).toHaveTextContent('In stock → In use');

    await user.click(screen.getByRole('button', { name: 'Dispose' }));
    const dialog = await screen.findByRole('dialog', { name: /Dispose — AST-0051/ });
    const confirm = within(dialog).getByRole('button', { name: 'Dispose' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/Disposal note/), 'E-waste partner, certificate 42');
    await user.click(confirm);
    await waitFor(() => expect(last('POST', '/assets/51/transition')?.body).toEqual({ status: 'DISPOSED', note: 'E-waste partner, certificate 42' }));
  });

  it('assigns hardware to a person and records maintenance', async () => {
    detailRoutes(makeAssetDetail({ allowedTransitions: ['IN_USE', 'IN_REPAIR', 'RETIRED'] }, { status: 'IN_STOCK', assignedToId: null, assignedToName: null }));
    const user = userEvent.setup();
    renderDetail('IT_ADMIN');
    await user.click(await screen.findByRole('button', { name: /^Assign$/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Assign AST-0051' });
    await user.type(within(dialog).getByLabelText('Person'), 'Gr');
    await user.click(await within(dialog).findByRole('option', { name: /Grace Hopper/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Assign' }));
    await waitFor(() => expect(last('POST', '/assets/51/assign')?.body).toEqual({ userId: 8, note: null }));

    await user.click(screen.getByRole('button', { name: /Record maintenance/ }));
    const form = screen.getByRole('group', { name: 'New maintenance record' });
    await user.type(within(form).getByLabelText(/Description/), 'Battery replaced under warranty');
    await user.type(within(form).getByLabelText('Cost'), '0');
    await user.click(within(form).getByRole('button', { name: 'Save record' }));
    await waitFor(() => expect(last('POST', '/assets/51/maintenance')?.body).toMatchObject({ maintenanceType: 'REPAIR', status: 'COMPLETED', description: 'Battery replaced under warranty', cost: 0 }));
  });

  it('putting an end-user device in use asks for a holder', async () => {
    detailRoutes(makeAssetDetail({ allowedTransitions: ['IN_USE'] }, { status: 'IN_REPAIR', assignedToId: null, assignedToName: null }));
    const user = userEvent.setup();
    renderDetail('ASSET_MANAGER');
    await user.click(await screen.findByRole('button', { name: 'Put in use' }));
    const dialog = await screen.findByRole('dialog', { name: /Put in use/ });
    expect(within(dialog).getByRole('button', { name: 'Put in use' })).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Holder'), 'Gr');
    await user.click(await within(dialog).findByRole('option', { name: /Grace Hopper/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Put in use' }));
    await waitFor(() => expect(last('POST', '/assets/51/transition')?.body).toEqual({ status: 'IN_USE', note: null, assigneeId: 8 }));
  });

  it('releases a licence seat and is read-only for readers', async () => {
    const licence = makeAssetDetail(
      { seats: [{ assignmentId: 3, userId: 8, userName: 'Grace Hopper', userEmail: 'grace@x.test', assignedAt: '2026-10-01T08:00:00Z', assignedByName: 'Asha Asset' }] },
      { assetType: 'SOFTWARE_LICENSE', category: 'SOFTWARE', licenseSeats: 10, seatsUsed: 1, assignedToId: null, assignedToName: null },
    );
    detailRoutes(licence);
    const user = userEvent.setup();
    const view = renderDetail('ASSET_MANAGER');
    await user.click(await screen.findByRole('button', { name: 'Release seat of Grace Hopper' }));
    await user.click(within(await screen.findByRole('dialog', { name: /Release the seat of Grace Hopper/ })).getByRole('button', { name: 'Release seat' }));
    await waitFor(() => expect(last('POST', '/assets/51/return')?.body).toEqual({ userId: 8, note: null }));
    view.unmount();

    detailRoutes(licence);
    renderDetail('SUPPORT_AGENT');
    expect(await screen.findByRole('list', { name: 'Assigned seats' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Release seat/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Assign seat/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument();
  });
});
