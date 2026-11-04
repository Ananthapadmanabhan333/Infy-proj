import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { categories, laptop, makeApproval, makeRequest, page, powerBi } from '../components/servicedesk/fixtures.test-utils';
import { apiErrorBody, json, mockApi, requests } from '../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../test/utils';
import ServiceDeskPage from './ServiceDeskPage';

const queue = [
  makeRequest(),
  makeRequest({
    id: 2,
    number: 'REQ-3920',
    title: 'New laptop for team member',
    categoryName: 'Hardware',
    categoryCode: 'HARDWARE',
    priority: 'MEDIUM',
    status: 'PENDING_APPROVAL',
    commentCount: 0,
  }),
];

function deskRoutes(extra: Parameters<typeof mockApi>[0] = {}) {
  mockApi({
    'GET /service-requests': json(page(queue)),
    'GET /service-requests/counts': json({ all: 12, mine: 3, pendingApproval: 4, inFulfillment: 2, fulfilled: 6 }),
    'GET /approvals': json(page([makeApproval()], { totalElements: 2 })),
    'GET /catalog/categories': json(categories),
    'GET /catalog/items': json([powerBi, laptop]),
    ...extra,
  });
}

const lastRequest = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

describe('ServiceDeskPage', () => {
  it('shows the reference header, tab counts and request cards for staff', async () => {
    deskRoutes();
    renderWithProviders(<ServiceDeskPage />, { route: '/service-desk' });

    expect(screen.getByRole('heading', { level: 1, name: 'Service Desk' })).toBeInTheDocument();
    expect(screen.getByText('Manage user requests, service catalog and approvals')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New Request/ })).toBeInTheDocument();

    const tabs = screen.getByRole('tablist', { name: 'Service desk views' });
    expect(await within(tabs).findByRole('tab', { name: 'Requests (12)' })).toHaveAttribute('aria-selected', 'true');
    expect(within(tabs).getByRole('tab', { name: 'Service Catalog' })).toBeInTheDocument();
    expect(within(tabs).getByRole('tab', { name: 'My Requests (3)' })).toBeInTheDocument();
    expect(await within(tabs).findByRole('tab', { name: 'Approvals (2)' })).toBeInTheDocument();

    const card = await screen.findByRole('button', { name: 'REQ-3921 Access to Power BI' });
    expect(within(card).getByText(/Access Management/)).toBeInTheDocument();
    expect(within(card).getByText('High')).toBeInTheDocument();
    expect(within(card).getByText('Approved')).toBeInTheDocument();
    expect(within(card).getByText('1 hour ago')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'REQ-3920 New laptop for team member' })).toHaveTextContent('Pending Approval');
    expect(lastRequest('GET', '/service-requests')?.params).toMatchObject({ scope: 'all', page: 0 });
  });

  it('sends the category, priority and status filters to the API', async () => {
    deskRoutes();
    renderWithProviders(<ServiceDeskPage />, { route: '/service-desk' });
    const user = userEvent.setup();
    await screen.findByRole('button', { name: 'REQ-3921 Access to Power BI' });

    await user.selectOptions(screen.getByRole('combobox', { name: 'Category' }), 'HARDWARE');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Priority' }), 'HIGH');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'IN_FULFILLMENT');
    await waitFor(() => expect(lastRequest('GET', '/service-requests')?.params).toMatchObject({ category: 'HARDWARE', priority: 'HIGH', status: 'IN_FULFILLMENT' }));
    // The live categories (configurable) feed the Category filter.
    expect(screen.getByRole('option', { name: 'Access Management' })).toBeInTheDocument();
  });

  it('hides the staff queue and approvals from employees and defaults to My Requests', async () => {
    deskRoutes();
    renderWithProviders(<ServiceDeskPage />, { route: '/service-desk', auth: makeAuth(makeUser('EMPLOYEE', { id: 21 })) });

    const tabs = screen.getByRole('tablist', { name: 'Service desk views' });
    expect(await within(tabs).findByRole('tab', { name: 'My Requests (3)' })).toHaveAttribute('aria-selected', 'true');
    expect(within(tabs).queryByRole('tab', { name: /^Requests/ })).not.toBeInTheDocument();
    expect(within(tabs).queryByRole('tab', { name: /Approvals/ })).not.toBeInTheDocument();
    await screen.findByRole('button', { name: 'REQ-3921 Access to Power BI' });
    expect(lastRequest('GET', '/service-requests')?.params).toMatchObject({ scope: 'mine' });
    expect(requests.some((request) => request.url === '/approvals')).toBe(false);
  });

  it('opens a request drawer with form data, comments and the staff actions', async () => {
    deskRoutes({
      'GET /service-requests/1': json(makeRequest()),
      'GET /service-requests/1/comments': json([
        { id: 1, body: 'Licence ordered.', internal: true, systemGenerated: false, authorId: 9, authorName: 'Riya Varma', createdAt: '2026-11-03T09:00:00Z' },
      ]),
      'GET /service-requests/1/history': json([{ at: '2026-11-03T08:00:00Z', actorName: 'Priya Raman', action: 'CREATED', fromValue: null, toValue: 'APPROVED', note: 'Priority high' }]),
      'GET /users/assignable': json([{ id: 9, fullName: 'Riya Varma', email: 'riya@nexus360.test', role: 'SUPPORT_AGENT', departmentId: 3, departmentName: 'IT Service Desk', openAssignments: 4 }]),
      'POST /service-requests/1/start': json(makeRequest({ status: 'IN_FULFILLMENT' })),
    });
    renderWithProviders(<ServiceDeskPage />, { route: '/service-desk' });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'REQ-3921 Access to Power BI' }));

    const drawer = await screen.findByRole('dialog', { name: 'Access to Power BI' });
    expect(within(drawer).getByText('REQ-3921 • Access Management')).toBeInTheDocument();
    expect(within(drawer).getByText('Cost Centre')).toBeInTheDocument();
    expect(within(drawer).getByText('FIN-01')).toBeInTheDocument();
    expect(within(drawer).getByText('Yes')).toBeInTheDocument();
    expect(await within(drawer).findByText('Licence ordered.')).toBeInTheDocument();
    expect(within(drawer).getByRole('switch', { name: 'Internal note (staff only)' })).toBeInTheDocument();
    expect(await within(drawer).findByRole('option', { name: 'Riya Varma — 4 open' })).toBeInTheDocument();

    await user.click(within(drawer).getByRole('tab', { name: 'History' }));
    expect(await within(drawer).findByText('Request submitted: Approved')).toBeInTheDocument();

    expect(within(drawer).getByRole('button', { name: /Mark fulfilled/ })).toBeInTheDocument();
    expect(within(drawer).getByRole('button', { name: /Cancel request/ })).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: /Start fulfilment/ }));
    await waitFor(() => expect(lastRequest('POST', '/service-requests/1/start')).toBeTruthy());
  });

  it('shows only cancel and public comments to the requester of an open request', async () => {
    deskRoutes({
      'GET /service-requests/1': json(makeRequest({ status: 'PENDING_APPROVAL' })),
      'GET /service-requests/1/comments': json([]),
      'GET /service-requests/1/history': json([]),
    });
    renderWithProviders(<ServiceDeskPage />, { route: '/service-desk?request=1', auth: makeAuth(makeUser('EMPLOYEE', { id: 21 })) });

    const drawer = await screen.findByRole('dialog', { name: 'Access to Power BI' });
    expect(within(drawer).getByRole('button', { name: /Cancel request/ })).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: /Start fulfilment/ })).not.toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: /Mark fulfilled/ })).not.toBeInTheDocument();
    expect(within(drawer).queryByRole('combobox', { name: 'Assignee' })).not.toBeInTheDocument();
    expect(within(drawer).queryByRole('switch')).not.toBeInTheDocument();
    expect(await within(drawer).findByLabelText('Add a comment')).toBeInTheDocument();
  });

  it('requires a reason to reject and surfaces the segregation-of-duties refusal', async () => {
    deskRoutes({
      'POST /approvals/501/decision': { status: 403, data: apiErrorBody(403, 'Segregation of duties: you cannot approve your own request') },
    });
    renderWithProviders(<ServiceDeskPage />, { route: '/service-desk?tab=approvals' });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Reject Power BI access for Priya Raman' }));
    const dialog = await screen.findByRole('dialog', { name: /Reject/ });
    const confirm = within(dialog).getByRole('button', { name: 'Reject' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Reason'), 'Not budgeted');
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Segregation of duties: you cannot approve your own request');
    expect(lastRequest('POST', '/approvals/501/decision')?.body).toEqual({ approved: false, comment: 'Not budgeted' });
  });

  it('creates a request from the New Request picker with priority and maps server field errors', async () => {
    let attempts = 0;
    deskRoutes({
      'POST /service-requests': () => {
        attempts += 1;
        return attempts === 1
          ? { status: 400, data: apiErrorBody(400, "'Cost centre' is required") }
          : { status: 201, data: makeRequest({ id: 7, number: 'REQ-3930', status: 'PENDING_APPROVAL' }) };
      },
    });
    renderWithProviders(<ServiceDeskPage />, { route: '/service-desk', auth: makeAuth(makeUser('EMPLOYEE', { id: 21 })) });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: /New Request/ }));
    const picker = await screen.findByRole('dialog', { name: 'What do you need?' });
    await user.click(await within(picker).findByRole('button', { name: 'Choose Power BI access' }));

    const dialog = await screen.findByRole('dialog', { name: 'Power BI access' });
    const priority = within(dialog).getByRole('combobox', { name: /Priority/ });
    expect(within(priority).getAllByRole('option').map((option) => option.textContent)).toEqual(['High', 'Medium', 'Low']);
    await user.selectOptions(priority, 'HIGH');

    // Client-side validation from the item's field definitions.
    await user.click(within(dialog).getByRole('button', { name: /Submit request/ }));
    expect(await within(dialog).findByText('Cost centre is required')).toBeInTheDocument();
    expect(attempts).toBe(0);

    // The API re-validates; its message is mapped back onto the field.
    await user.type(within(dialog).getByLabelText(/Cost centre/), 'FIN-01');
    await user.click(within(dialog).getByRole('button', { name: /Submit request/ }));
    expect(await within(dialog).findByText("'Cost centre' is required")).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: /Submit request/ }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Power BI access' })).not.toBeInTheDocument());
    expect(lastRequest('POST', '/service-requests')?.body).toEqual({
      catalogItemId: 11,
      title: 'Power BI access',
      formData: { costCentre: 'FIN-01' },
      priority: 'HIGH',
    });
    expect(await screen.findByText(/REQ-3930 submitted — awaiting approval/)).toBeInTheDocument();
  });

  it('lists catalogue categories and items in the Service Catalog tab', async () => {
    deskRoutes();
    renderWithProviders(<ServiceDeskPage />, { route: '/service-desk?tab=catalog&category=2' });
    const user = userEvent.setup();

    expect(await screen.findByRole('button', { name: /Hardware/, pressed: true })).toBeInTheDocument();
    // The tab links to the full Service Catalogue page (/catalog), keeping the selected category.
    expect(screen.getByRole('link', { name: /Open the Service Catalogue page/ })).toHaveAttribute('href', '/catalog?category=2');
    await waitFor(() => expect(lastRequest('GET', '/catalog/items')?.params).toMatchObject({ categoryId: 2 }));
    await user.click(await screen.findByRole('button', { name: 'Request New laptop' }));
    expect(await screen.findByRole('dialog', { name: 'New laptop' })).toBeInTheDocument();
    // Staff may choose any priority, including Critical.
    expect(within(screen.getByRole('combobox', { name: /Priority/ })).getByRole('option', { name: 'Critical' })).toBeInTheDocument();
  });
});
