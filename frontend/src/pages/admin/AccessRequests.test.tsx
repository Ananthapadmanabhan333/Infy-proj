import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { InternalAxiosRequestConfig } from 'axios';
import { describe, expect, it } from 'vitest';
import type { AccountRequestDto } from '../../lib/accountTypes';
import type { DepartmentDto, PageResponse } from '../../lib/types';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import UsersPage from './UsersPage';

function request(id: number, fullName: string, type: AccountRequestDto['type'], overrides: Partial<AccountRequestDto> = {}): AccountRequestDto {
  return {
    id,
    type,
    fullName,
    email: `${fullName.split(' ')[0].toLowerCase()}@nexus360.test`,
    department: null,
    jobTitle: null,
    reason: null,
    status: 'PENDING',
    createdAt: '2026-11-03T08:00:00Z',
    decidedByName: null,
    decidedAt: null,
    decisionNote: null,
    userId: null,
    ...overrides,
  };
}

const REQUESTS = [
  request(11, 'Meera Nair', 'ACCESS', { department: 'IT Service Desk', jobTitle: 'Analyst', reason: 'Joining the service desk team next week.' }),
  request(12, 'Arjun Das', 'PASSWORD_RESET', { userId: 40 }),
];

const DEPARTMENTS: DepartmentDto[] = [
  { id: 2, code: 'NET', name: 'Network Operations', description: null, managerId: null, managerName: null, email: null, memberCount: 4, demo: false },
  { id: 3, code: 'IT-SD', name: 'IT Service Desk', description: null, managerId: null, managerName: null, email: null, memberCount: 12, demo: false },
];

function page<T>(content: T[]): PageResponse<T> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 };
}

const ORIGIN = () => window.location.origin;

function setup(extra: Parameters<typeof mockApi>[0] = {}) {
  mockApi({
    'GET /users': json(page([])),
    'GET /departments': json(DEPARTMENTS),
    'GET /admin/account-requests/counts': json({ pendingAccess: 1, pendingReset: 1 }),
    'GET /admin/account-requests': (config: InternalAxiosRequestConfig) => {
      const params = (config.params ?? {}) as Record<string, unknown>;
      let rows = REQUESTS;
      if (params.type) rows = rows.filter((row) => row.type === params.type);
      if (params.status) rows = rows.filter((row) => row.status === params.status);
      return json(page(rows));
    },
    ...extra,
  });
}

describe('Users → Access requests', () => {
  it('lists pending requests with the pending count on the tab', async () => {
    setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=access-requests' });
    const table = await screen.findByRole('table', { name: 'Access requests' });
    expect(within(table).getByText('Meera Nair')).toBeInTheDocument();
    expect(within(table).getByText('meera@nexus360.test')).toBeInTheDocument();
    expect(within(table).getByText('Password reset')).toBeInTheDocument();
    expect(within(table).getByText('Joining the service desk team next week.')).toBeInTheDocument();
    expect(within(table).getAllByText('Pending')).toHaveLength(2);
    const tab = screen.getByRole('tab', { name: /Access requests/ });
    expect(tab).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(tab).toHaveTextContent('(2)'));
    const listCall = requests.find((call) => call.url === '/admin/account-requests');
    expect(listCall?.params).toMatchObject({ status: 'PENDING' });
  });

  it('accepts ?tab=requests as an alias of the access-requests tab', async () => {
    setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=requests' });
    expect(await screen.findByRole('table', { name: 'Access requests' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Access requests/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('surfaces a 403 when an IT Admin may not reset a System Admin password', async () => {
    const message = 'Only a System Admin can reset the password of a System Admin account';
    setup({ 'POST /admin/account-requests/12/approve': json(apiErrorBody(403, message), 403) });
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=access-requests', auth: makeAuth(makeUser('IT_ADMIN', { id: 2 })) });
    await screen.findByRole('table', { name: 'Access requests' });
    await user.click(screen.getByRole('button', { name: 'Approve request from Arjun Das' }));
    const dialog = await screen.findByRole('dialog', { name: 'Create a reset link for Arjun Das?' });
    await user.click(within(dialog).getByRole('button', { name: 'Create reset link' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(message);
    expect(screen.queryByRole('dialog', { name: 'Password reset link ready' })).not.toBeInTheDocument();
  });

  it('approves an access request and shows the invitation link once', async () => {
    let body: unknown;
    setup({
      'POST /admin/account-requests/11/approve': (config) => {
        body = JSON.parse(String(config.data));
        return json({ link: '/register?invite=INVITE_TOKEN_123', expiresAt: '2026-11-10T08:00:00Z' });
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=access-requests' });
    await screen.findByRole('table', { name: 'Access requests' });
    await user.click(screen.getByRole('button', { name: 'Approve request from Meera Nair' }));

    const dialog = await screen.findByRole('dialog', { name: 'Approve Meera Nair' });
    // The stated department is pre-selected; the job title is carried over.
    await waitFor(() => expect(within(dialog).getByLabelText(/^Department/)).toHaveValue('3'));
    expect(within(dialog).getByLabelText(/^Job title/)).toHaveValue('Analyst');
    await user.selectOptions(within(dialog).getByLabelText(/^Role/), 'SUPPORT_AGENT');
    await user.click(within(dialog).getByRole('button', { name: /Approve and create link/ }));

    const panel = await screen.findByRole('dialog', { name: 'Invitation link ready' });
    expect(body).toEqual({ role: 'SUPPORT_AGENT', departmentId: 3, jobTitle: 'Analyst' });
    const url = `${ORIGIN()}/register?invite=INVITE_TOKEN_123`;
    expect(within(panel).getByLabelText('Invitation link')).toHaveValue(url);
    expect(within(panel).getByText(/Shown only once/)).toBeInTheDocument();
    expect(within(panel).getByText(/trusted channel/)).toBeInTheDocument();
    expect(within(panel).getByText(/Expires/)).toBeInTheDocument();

    await user.click(within(panel).getByRole('button', { name: 'Copy link' }));
    expect(await navigator.clipboard.readText()).toBe(url);
    expect(within(panel).getByRole('status')).toHaveTextContent('Link copied');

    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Invitation link ready' })).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: /Done/ }));
    expect(screen.queryByRole('dialog', { name: 'Invitation link ready' })).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue(url)).not.toBeInTheDocument();
  });

  it('creates a reset link for a password reset request with an empty body', async () => {
    let body: unknown;
    setup({
      'POST /admin/account-requests/12/approve': (config) => {
        body = JSON.parse(String(config.data));
        return json({ link: '/reset-password?token=RESET_TOKEN_456', expiresAt: '2026-11-03T10:00:00Z' });
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=access-requests' });
    await screen.findByRole('table', { name: 'Access requests' });
    await user.click(screen.getByRole('button', { name: 'Approve request from Arjun Das' }));
    const dialog = await screen.findByRole('dialog', { name: 'Create a reset link for Arjun Das?' });
    await user.click(within(dialog).getByRole('button', { name: 'Create reset link' }));
    const panel = await screen.findByRole('dialog', { name: 'Password reset link ready' });
    expect(body).toEqual({});
    expect(within(panel).getByLabelText('Password reset link')).toHaveValue(`${ORIGIN()}/reset-password?token=RESET_TOKEN_456`);
  });

  it('shows conflicts from the API in the approve dialog', async () => {
    setup({ 'POST /admin/account-requests/11/approve': json(apiErrorBody(409, 'An account already exists for this e-mail'), 409) });
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=access-requests' });
    await screen.findByRole('table', { name: 'Access requests' });
    await user.click(screen.getByRole('button', { name: 'Approve request from Meera Nair' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve Meera Nair' });
    await waitFor(() => expect(within(dialog).getByLabelText(/^Department/)).toHaveValue('3'));
    await user.click(within(dialog).getByRole('button', { name: /Approve and create link/ }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('An account already exists for this e-mail');
    expect(screen.queryByRole('dialog', { name: 'Invitation link ready' })).not.toBeInTheDocument();
  });

  it('requires a note to reject a request', async () => {
    let body: unknown;
    setup({
      'POST /admin/account-requests/11/reject': (config) => {
        body = JSON.parse(String(config.data));
        return json({ ...REQUESTS[0], status: 'REJECTED', decisionNote: 'Not an employee' });
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=access-requests' });
    await screen.findByRole('table', { name: 'Access requests' });
    await user.click(screen.getByRole('button', { name: 'Reject request from Meera Nair' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reject the request from Meera Nair?' });
    await user.click(within(dialog).getByRole('button', { name: 'Reject request' }));
    expect(await within(dialog).findByText(/Add a note explaining the decision/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Decision note/)).toHaveFocus();
    expect(requests.some((call) => call.method === 'POST' && call.url.endsWith('/reject'))).toBe(false);

    await user.type(within(dialog).getByLabelText(/^Decision note/), 'Not an employee');
    await user.click(within(dialog).getByRole('button', { name: 'Reject request' }));
    await waitFor(() => expect(body).toEqual({ note: 'Not an employee' }));
    expect(await screen.findByText('Access request from Meera Nair rejected')).toBeInTheDocument();
  });

  it('never offers SYSTEM_ADMIN to an IT Admin when approving or inviting', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=access-requests', auth: makeAuth(makeUser('IT_ADMIN', { id: 2 })) });
    await screen.findByRole('table', { name: 'Access requests' });
    await user.click(screen.getByRole('button', { name: 'Approve request from Meera Nair' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve Meera Nair' });
    const options = within(within(dialog).getByLabelText(/^Role/)).getAllByRole('option').map((option) => option.textContent);
    expect(options).not.toContain('System Admin');
    expect(options).toContain('IT Admin');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    await user.click(screen.getByRole('button', { name: /Invite user/ }));
    const drawer = await screen.findByRole('dialog', { name: 'Invite a user' });
    const inviteOptions = within(within(drawer).getByLabelText(/^Role/)).getAllByRole('option').map((option) => option.textContent);
    expect(inviteOptions).not.toContain('System Admin');
  });

  it('invites a user directly and shows the same show-once panel', async () => {
    let body: unknown;
    setup({
      'POST /admin/invitations': (config) => {
        body = JSON.parse(String(config.data));
        return json({ link: '/register?invite=DIRECT_789', expiresAt: '2026-11-10T08:00:00Z' });
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users' });
    await user.click(screen.getByRole('button', { name: /Invite user/ }));
    const drawer = await screen.findByRole('dialog', { name: 'Invite a user' });
    await user.click(within(drawer).getByRole('button', { name: /Create invitation link/ }));
    expect(await within(drawer).findByText('Choose a department', { selector: '.field-error' })).toBeInTheDocument();

    await user.type(within(drawer).getByLabelText(/^Email/), 'kiran@nexus360.test');
    await user.selectOptions(within(drawer).getByLabelText(/^Department/), '2');
    await user.click(within(drawer).getByRole('button', { name: /Create invitation link/ }));
    const panel = await screen.findByRole('dialog', { name: 'Invitation link ready' });
    expect(body).toEqual({ email: 'kiran@nexus360.test', fullName: null, role: 'EMPLOYEE', departmentId: 2, jobTitle: null });
    expect(within(panel).getByLabelText('Invitation link')).toHaveValue(`${ORIGIN()}/register?invite=DIRECT_789`);
  });

  it('explains when an invited e-mail already has an account', async () => {
    setup({ 'POST /admin/invitations': json(apiErrorBody(409, 'Conflict'), 409) });
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users' });
    await user.click(screen.getByRole('button', { name: /Invite user/ }));
    const drawer = await screen.findByRole('dialog', { name: 'Invite a user' });
    await user.type(within(drawer).getByLabelText(/^Email/), 'asha@nexus360.test');
    await user.selectOptions(within(drawer).getByLabelText(/^Department/), '3');
    await user.click(within(drawer).getByRole('button', { name: /Create invitation link/ }));
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('already has an account');
  });

  it('hides the tab and its data from managers', async () => {
    setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=access-requests', auth: makeAuth(makeUser('IT_MANAGER')) });
    await screen.findByRole('table', { name: 'Users' });
    expect(screen.queryByRole('tab', { name: /Access requests/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Invite user/ })).not.toBeInTheDocument();
    expect(requests.some((call) => call.url.startsWith('/admin/account-requests'))).toBe(false);
  });

  it('filters by type and can show every status', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=access-requests' });
    await screen.findByRole('table', { name: 'Access requests' });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Type' }), 'PASSWORD_RESET');
    await waitFor(() => expect(screen.queryByText('Meera Nair')).not.toBeInTheDocument());
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), '');
    await waitFor(() => {
      const last = requests.filter((call) => call.url === '/admin/account-requests').pop();
      expect(last?.params).toEqual({ type: 'PASSWORD_RESET', page: 0, size: 20 });
    });
  });
});
