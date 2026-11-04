import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { InternalAxiosRequestConfig } from 'axios';
import { describe, expect, it } from 'vitest';
import { canManageUser, roleOptions } from '../../components/admin/userAccess';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { DepartmentDto, PageResponse, Role, UserDto } from '../../lib/types';
import UsersPage from './UsersPage';

function user(id: number, fullName: string, role: Role, overrides: Partial<UserDto> = {}): UserDto {
  return {
    id,
    email: `${fullName.split(' ')[0].toLowerCase()}@nexus360.test`,
    fullName,
    role,
    departmentId: 3,
    departmentName: 'IT Service Desk',
    jobTitle: 'Engineer',
    phone: null,
    active: true,
    lastLoginAt: '2026-11-03T08:00:00Z',
    createdAt: '2026-01-10T08:00:00Z',
    openAssignments: 2,
    demo: false,
    ...overrides,
  };
}

const USERS = [
  user(1, 'Asha Admin', 'SYSTEM_ADMIN'),
  user(2, 'Rajesh Iyer', 'IT_ADMIN'),
  user(3, 'Priya Raman', 'IT_MANAGER', { lastLoginAt: null }),
  user(4, 'Kiran Rao', 'SUPPORT_AGENT', { active: false }),
];

const DEPARTMENTS: DepartmentDto[] = [
  { id: 3, code: 'IT-SD', name: 'IT Service Desk', description: 'First line', managerId: 3, managerName: 'Priya Raman', email: 'sd@nexus360.test', memberCount: 12, demo: false },
];

function page<T>(content: T[], totalElements = content.length): PageResponse<T> {
  return { content, page: 0, size: 20, totalElements, totalPages: 1 };
}

function setup(extra: Parameters<typeof mockApi>[0] = {}) {
  mockApi({
    'GET /users': (config: InternalAxiosRequestConfig) => {
      const params = (config.params ?? {}) as Record<string, unknown>;
      let rows = USERS;
      if (params.active === 'true') rows = rows.filter((row) => row.active);
      if (params.active === 'false') rows = rows.filter((row) => !row.active);
      if (params.role) rows = rows.filter((row) => row.role === params.role);
      if (params.size === 1) return json(page(rows.slice(0, 1), rows.length));
      return json(page(rows));
    },
    'GET /departments': json(DEPARTMENTS),
    ...extra,
  });
}

describe('user permission helpers', () => {
  it('lets IT_ADMIN manage everyone except System Admins and never offers SYSTEM_ADMIN', () => {
    const itAdmin = { id: 2, role: 'IT_ADMIN' as Role };
    expect(canManageUser(itAdmin, { role: 'SYSTEM_ADMIN' })).toBe(false);
    expect(canManageUser(itAdmin, { role: 'SUPPORT_AGENT' })).toBe(true);
    expect(roleOptions(itAdmin)).not.toContain('SYSTEM_ADMIN');
    expect(roleOptions({ id: 1, role: 'SYSTEM_ADMIN' })).toContain('SYSTEM_ADMIN');
    expect(canManageUser({ id: 3, role: 'IT_MANAGER' }, { role: 'EMPLOYEE' })).toBe(false);
  });
});

describe('UsersPage', () => {
  it('lists users with role labels, KPI counts and admin actions for a system admin', async () => {
    setup();
    renderWithProviders(<UsersPage />, { route: '/users' });
    const table = await screen.findByRole('table', { name: 'Users' });
    expect(within(table).getByText('Asha Admin')).toBeInTheDocument();
    expect(within(table).getByText('IT Manager')).toBeInTheDocument();
    expect(within(table).getByText('Support Agent')).toBeInTheDocument();
    expect(within(table).getByText('Never')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New User/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit Asha Admin' })).toBeInTheDocument();
    // KPI strip: real totals from the list endpoint.
    const active = await screen.findByRole('link', { name: /^Active:/ });
    await waitFor(() => expect(active).toHaveTextContent('3'));
    expect(screen.getByRole('link', { name: /^Inactive:/ })).toHaveTextContent('1');
  });

  it('is read-only for managers', async () => {
    setup();
    renderWithProviders(<UsersPage />, { route: '/users', auth: makeAuth(makeUser('IT_MANAGER')) });
    await screen.findByRole('table', { name: 'Users' });
    expect(screen.queryByRole('button', { name: /New User/ })).not.toBeInTheDocument();
    expect(screen.getByText('Read-only access')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Edit / })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('row', { name: /Rajesh Iyer/ }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('Your role has read-only access to the user directory.')).toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: /Edit user/ })).not.toBeInTheDocument();
  });

  it('hides SYSTEM_ADMIN from IT_ADMIN role choices and blocks editing System Admin accounts', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users', auth: makeAuth(makeUser('IT_ADMIN', { id: 2 })) });
    await screen.findByRole('table', { name: 'Users' });
    expect(screen.queryByRole('button', { name: 'Edit Asha Admin' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit Priya Raman' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /New User/ }));
    const drawer = await screen.findByRole('dialog', { name: 'Create user' });
    const roleSelect = within(drawer).getByLabelText(/^Role/);
    const options = within(roleSelect).getAllByRole('option').map((option) => option.textContent);
    expect(options).not.toContain('System Admin');
    expect(options).toContain('IT Admin');
  });

  it('deactivates a user after confirmation, sending the full update body', async () => {
    let body: unknown;
    setup({
      'PUT /users/4': (config) => {
        body = JSON.parse(String(config.data));
        return json({ ...USERS[3], active: true });
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users' });
    await screen.findByRole('table', { name: 'Users' });
    await user.click(screen.getByRole('button', { name: 'Activate Kiran Rao' }));
    const dialog = await screen.findByRole('dialog', { name: 'Activate Kiran Rao?' });
    await user.click(within(dialog).getByRole('button', { name: 'Activate' }));
    await waitFor(() => expect(body).toEqual({ fullName: 'Kiran Rao', role: 'SUPPORT_AGENT', departmentId: 3, jobTitle: 'Engineer', phone: null, active: true }));
    expect(await screen.findByText('Kiran Rao reactivated')).toBeInTheDocument();
  });

  it('surfaces backend errors when saving a user', async () => {
    setup({ 'PUT /users/3': json(apiErrorBody(403, 'IT_ADMIN cannot grant SYSTEM_ADMIN'), 403) });
    const user = userEvent.setup();
    renderWithProviders(<UsersPage />, { route: '/users' });
    await screen.findByRole('table', { name: 'Users' });
    await user.click(screen.getByRole('button', { name: 'Edit Priya Raman' }));
    const drawer = await screen.findByRole('dialog', { name: 'Priya Raman' });
    const name = within(drawer).getByLabelText(/Full name/);
    await user.clear(name);
    await user.type(name, 'Priya R. Raman');
    await user.click(within(drawer).getByRole('button', { name: 'Save changes' }));
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('IT_ADMIN cannot grant SYSTEM_ADMIN');
    expect(requests.some((request) => request.method === 'PUT' && request.url === '/users/3')).toBe(true);
  });

  it('lists departments with manager and members on the Departments tab', async () => {
    setup();
    renderWithProviders(<UsersPage />, { route: '/users?tab=departments' });
    const table = await screen.findByRole('table', { name: 'Departments' });
    expect(within(table).getByText('IT Service Desk')).toBeInTheDocument();
    expect(within(table).getByText('Priya Raman')).toBeInTheDocument();
    expect(within(table).getByText('12')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New Department/ })).toBeInTheDocument();
  });
});
