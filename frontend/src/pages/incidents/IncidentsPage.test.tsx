import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NewIncidentProvider } from '../../components/incidents/NewIncidentContext';
import { apiErrorBody, json, mockApi, requests, type MockHandler } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { Role } from '../../lib/types';
import { makeSla, makeSummary, page } from './fixtures.test-utils';
import IncidentsPage from './IncidentsPage';

const rows = [
  makeSummary(),
  makeSummary({
    id: 2,
    number: 'INC-7845',
    title: 'High latency in VPN connection',
    priority: 'HIGH',
    serviceId: 4,
    serviceName: 'Network',
    assigneeName: 'Arjun Kumar',
    status: 'IN_PROGRESS',
    sla: makeSla({ health: 'ON_TRACK', remainingSeconds: 42 * 60 }),
  }),
  makeSummary({
    id: 3,
    number: 'INC-7842',
    title: 'Printer not working in Block A',
    priority: 'MEDIUM',
    serviceName: 'Facilities',
    assigneeName: 'Vishal Kapoor',
    status: 'RESOLVED',
    sla: null,
  }),
];

const lastRequest = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

function routes(extra: Record<string, MockHandler | ReturnType<typeof json>> = {}) {
  mockApi({
    'GET /incidents': json(page(rows, { totalElements: 1248, totalPages: 63 })),
    'GET /incidents/counts': json({ all: 1248, open: 246, inProgress: 134, resolved: 876 }),
    'GET /services': json([
      { id: 3, name: 'Payroll' },
      { id: 4, name: 'Network' },
    ]),
    'GET /users/assignable': json([{ id: 9, fullName: 'Riya Varma', email: 'riya@nexus360.test', role: 'SUPPORT_AGENT', departmentId: 2, departmentName: 'Payroll Team', openAssignments: 4 }]),
    'GET /departments': json([{ id: 2, code: 'PAY', name: 'Payroll Team' }]),
    ...extra,
  });
}

function renderPage(role: Role = 'SUPPORT_AGENT', route = '/incidents') {
  return renderWithProviders(
    <NewIncidentProvider>
      <IncidentsPage />
    </NewIncidentProvider>,
    { route, auth: makeAuth(makeUser(role)) },
  );
}

describe('IncidentsPage', () => {
  // jsdom has no object URLs; the CSV export needs them.
  let createObjectURL: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    createObjectURL = vi.fn(() => 'blob:incidents');
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
  });
  afterEach(() => vi.useRealTimers());

  it('renders the reference header, live tab counts and the register columns', async () => {
    routes();
    renderPage();

    expect(screen.getByRole('heading', { level: 1, name: 'Incidents' })).toBeInTheDocument();
    expect(screen.getByText('Manage, track and resolve IT incidents')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New Incident/ })).toBeInTheDocument();

    const tabs = screen.getByRole('tablist', { name: 'Incident status' });
    expect(await within(tabs).findByRole('tab', { name: 'All (1,248)' })).toHaveAttribute('aria-selected', 'true');
    expect(within(tabs).getByRole('tab', { name: 'Open (246)' })).toBeInTheDocument();
    expect(within(tabs).getByRole('tab', { name: 'In Progress (134)' })).toBeInTheDocument();
    expect(within(tabs).getByRole('tab', { name: 'Resolved (876)' })).toBeInTheDocument();

    const table = await screen.findByRole('table', { name: 'Incidents' });
    const headers = within(table).getAllByRole('columnheader').map((cell) => cell.textContent);
    expect(headers).toEqual(['', 'ID', 'Title', 'Priority', 'Service', 'Assignee', 'SLA', 'Status']);
    const first = within(table).getByRole('row', { name: /INC-7846/ });
    expect(within(first).getByText('Critical')).toBeInTheDocument();
    expect(within(first).getByText('Payroll')).toBeInTheDocument();
    expect(within(first).getByText('Riya V.')).toBeInTheDocument();
    expect(within(first).getByText('In Progress')).toBeInTheDocument();
    expect(within(first).getByText(/18m/)).toHaveClass('at-risk');
    expect(within(within(table).getByRole('row', { name: /INC-7842/ })).getByText('—')).toBeInTheDocument();

    expect(screen.getByText('Showing 1–20 of 1,248')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Page 63' })).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Search by ID, title or user…')).toBeInTheDocument();
  });

  it('sends the tab, filters and search to both the list and the counts', async () => {
    routes();
    renderPage();
    const user = userEvent.setup();
    await screen.findByRole('row', { name: /INC-7846/ });

    await user.click(screen.getByRole('tab', { name: /Open/ }));
    await waitFor(() => expect(lastRequest('GET', '/incidents')?.params).toMatchObject({ status: 'NEW,ASSIGNED,IN_PROGRESS,PENDING,REOPENED' }));

    await user.selectOptions(screen.getByRole('combobox', { name: 'Priority' }), 'HIGH');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Service' }), '4');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Assignee' }), '9');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'PENDING');
    await user.type(screen.getByRole('searchbox', { name: 'Search incidents' }), 'vpn');

    await waitFor(() =>
      expect(lastRequest('GET', '/incidents')?.params).toMatchObject({ status: 'PENDING', priority: 'HIGH', serviceId: '4', assigneeId: '9', search: 'vpn', page: 0 }),
    );
    // Tab counts use the same filters but not the status (each tab is its own status set).
    const countParams = lastRequest('GET', '/incidents/counts')?.params as Record<string, unknown>;
    expect(countParams).toMatchObject({ priority: 'HIGH', serviceId: '4', assigneeId: '9', search: 'vpn' });
    expect(countParams.status).toBeUndefined();

    // Status options are limited to the open tab's statuses.
    const statusOptions = within(screen.getByRole('combobox', { name: 'Status' })).getAllByRole('option').map((option) => option.textContent);
    expect(statusOptions).toEqual(['Status', 'New', 'Assigned', 'In Progress', 'Pending', 'Reopened']);

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    // Back to the (cached) unfiltered open view.
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Priority' })).toHaveValue(''));
    expect(screen.getByRole('combobox', { name: 'Assignee' })).toHaveValue('');
    expect(screen.getByRole('searchbox', { name: 'Search incidents' })).toHaveValue('');
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  });

  it('maps the Release 1 "my queue" link to the staff scope filter', async () => {
    routes();
    renderPage('SUPPORT_AGENT', '/incidents?tab=mine');
    await waitFor(() => expect(lastRequest('GET', '/incidents')?.params).toMatchObject({ scope: 'mine' }));
    expect(screen.getByRole('combobox', { name: 'Scope' })).toHaveValue('mine');
  });

  it('applies a bulk status change and reports per-incident failures', async () => {
    routes({
      'POST /incidents/1/transition': json(makeSummary({ status: 'PENDING' })),
      'POST /incidents/2/transition': json(apiErrorBody(409, 'Cannot move INC-7845 from IN_PROGRESS to PENDING'), 409),
    });
    renderPage();
    const user = userEvent.setup();
    await screen.findByRole('row', { name: /INC-7846/ });

    await user.click(screen.getByRole('checkbox', { name: /Select INC-7846/ }));
    await user.click(screen.getByRole('checkbox', { name: /Select INC-7845/ }));
    const bar = screen.getByRole('region', { name: 'Bulk actions' });
    expect(within(bar).getByText('2 selected')).toBeInTheDocument();

    await user.click(within(bar).getByRole('button', { name: /Change status/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Change status of 2 incidents' });
    // IN_PROGRESS → PENDING or RESOLVED only (spec §2 transition table).
    expect(within(dialog).getAllByRole('radio').map((radio) => (radio as HTMLInputElement).value)).toEqual(['PENDING', 'RESOLVED']);
    await user.click(within(dialog).getByRole('radio', { name: 'Pending' }));
    await user.click(within(dialog).getByRole('button', { name: 'Update 2 incidents' }));
    expect(await within(dialog).findByText('A reason of at least 3 characters is required')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Reason (required)'), 'Waiting for the vendor');
    await user.click(within(dialog).getByRole('button', { name: 'Update 2 incidents' }));

    expect(await within(dialog).findByText('1 incident updated.')).toBeInTheDocument();
    expect(within(dialog).getByText(/Cannot move INC-7845 from IN_PROGRESS to PENDING/)).toBeInTheDocument();
    expect(lastRequest('POST', '/incidents/1/transition')?.body).toEqual({ status: 'PENDING', comment: 'Waiting for the vendor' });
    // The failed incident stays selected, the updated one leaves the selection.
    expect(within(screen.getByRole('region', { name: 'Bulk actions' })).getByText('1 selected')).toBeInTheDocument();
  });

  it('bulk-assigns the selection and exports the selected rows as CSV', async () => {
    routes({
      'POST /incidents/1/assign': json(makeSummary()),
      'POST /incidents/3/assign': json(makeSummary({ id: 3 })),
    });
    renderPage('IT_MANAGER');
    const user = userEvent.setup();
    await screen.findByRole('row', { name: /INC-7846/ });

    await user.click(screen.getByRole('checkbox', { name: /Select INC-7846/ }));
    await user.click(screen.getByRole('checkbox', { name: /Select INC-7842/ }));
    const bar = screen.getByRole('region', { name: 'Bulk actions' });

    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    await user.click(within(bar).getByRole('button', { name: /Export selected/ }));
    expect(click).toHaveBeenCalledTimes(1);
    const blob = createObjectURL.mock.calls[0][0] as Blob;
    expect(blob.type).toBe('text/csv;charset=utf-8');
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.readAsText(blob);
    });
    expect(text).toContain('Number,Title,Priority,Status');
    expect(text).toContain('INC-7846,Payroll application not accessible,Critical,In Progress');
    expect(text).toContain('INC-7842');
    expect(text).not.toContain('INC-7845');

    await user.click(within(bar).getByRole('button', { name: /Assign/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Assign 2 incidents' });
    await user.click(within(dialog).getByRole('button', { name: 'Assign 2 incidents' }));
    expect(await within(dialog).findByText('Choose a team or an assignee')).toBeInTheDocument();
    await within(dialog).findByRole('option', { name: /Riya Varma/ });
    await user.selectOptions(within(dialog).getByLabelText('Assignee'), '9');
    await user.click(within(dialog).getByRole('button', { name: 'Assign 2 incidents' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(lastRequest('POST', '/incidents/1/assign')?.body).toEqual({ departmentId: null, assigneeId: 9 });
    expect(lastRequest('POST', '/incidents/3/assign')?.body).toEqual({ departmentId: null, assigneeId: 9 });
    expect(screen.queryByRole('region', { name: 'Bulk actions' })).not.toBeInTheDocument();
  });

  it('hides New Incident and selection for auditors, and staff filters for employees', async () => {
    routes();
    const { unmount } = renderPage('AUDITOR');
    await screen.findByRole('row', { name: /INC-7846/ });
    expect(screen.queryByRole('button', { name: /New Incident/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeInTheDocument();
    unmount();

    renderPage('EMPLOYEE');
    await screen.findByRole('row', { name: /INC-7846/ });
    expect(screen.getByRole('button', { name: /New Incident/ })).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Assignee' })).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Scope' })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument();
    expect(requests.some((request) => request.url === '/users/assignable')).toBe(false);
  });

  it('shows an error state with retry when the register cannot load', async () => {
    mockApi({
      'GET /incidents': json(apiErrorBody(500, 'Database unavailable'), 500),
      'GET /incidents/counts': json({ all: 0, open: 0, inProgress: 0, resolved: 0 }),
      'GET /services': json([]),
      'GET /users/assignable': json([]),
    });
    renderPage();
    expect(await screen.findByText('Database unavailable')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retry/ })).toBeInTheDocument();
  });
});
