import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { DepartmentDto, PageResponse, TeamDto, UserDto } from '../../lib/types';
import { canManageDepartmentTeams } from './teamAccess';
import TeamsPage from './TeamsPage';

const DEPARTMENTS: DepartmentDto[] = [
  { id: 2, code: 'IT-APPS', name: 'IT Applications', description: null, managerId: 30, managerName: 'Meera Manager', email: null, memberCount: 8, demo: true },
  { id: 3, code: 'IT-SD', name: 'IT Service Desk', description: null, managerId: 31, managerName: 'Other Manager', email: null, memberCount: 5, demo: true },
];

function team(id: number, name: string, overrides: Partial<TeamDto> = {}): TeamDto {
  return {
    id,
    code: name.toUpperCase().replace(/\W+/g, '-'),
    name,
    description: null,
    departmentId: 2,
    departmentCode: 'IT-APPS',
    departmentName: 'IT Applications',
    leadId: 11,
    leadName: 'Lena Lead',
    email: 'team@nexus360.test',
    active: true,
    departmentDefault: false,
    memberCount: 2,
    openIncidents: 4,
    unassignedIncidents: 1,
    openRequests: 2,
    demo: true,
    members: [
      { userId: 11, fullName: 'Lena Lead', email: 'lena@nexus360.test', role: 'SUPPORT_AGENT', jobTitle: 'Engineer', memberRole: 'LEAD', active: true, openAssignments: 3, joinedAt: '2026-10-01T08:00:00Z' },
      { userId: 12, fullName: 'Mo Member', email: 'mo@nexus360.test', role: 'SUPPORT_AGENT', jobTitle: null, memberRole: 'MEMBER', active: true, openAssignments: 1, joinedAt: '2026-10-02T08:00:00Z' },
    ],
    ...overrides,
  };
}

const PAYROLL = team(1, 'Payroll & HR Apps', { departmentDefault: true });
const DESK = team(2, 'Service Desk L1', { departmentId: 3, departmentName: 'IT Service Desk', openIncidents: 6, unassignedIncidents: 0, openRequests: 5, memberCount: 3 });

function staffUser(id: number, fullName: string): UserDto {
  return {
    id,
    email: `${fullName.split(' ')[0].toLowerCase()}@nexus360.test`,
    fullName,
    role: 'SUPPORT_AGENT',
    departmentId: 2,
    departmentName: 'IT Applications',
    jobTitle: null,
    phone: null,
    active: true,
    lastLoginAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    openAssignments: 0,
    demo: false,
  };
}

const USERS: PageResponse<UserDto> = { content: [staffUser(11, 'Lena Lead'), staffUser(12, 'Mo Member'), staffUser(13, 'Nina New')], page: 0, size: 100, totalElements: 3, totalPages: 1 };

function setup() {
  mockApi({
    'GET /teams': json([PAYROLL, DESK]),
    'GET /teams/1': json(PAYROLL),
    'GET /teams/2': json(DESK),
    'GET /departments': json(DEPARTMENTS),
    'GET /users': json(USERS),
    'POST /teams': (config) => json({ ...team(9, 'Network Ops'), ...JSON.parse(String(config.data)) }, 201),
    'PUT /teams/1/members/12': json({ ...PAYROLL }),
    'DELETE /teams/1/members/12': { status: 204 },
    'POST /teams/1/members': json(PAYROLL),
  });
}

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

describe('team permissions', () => {
  it('lets user admins manage every team and IT managers only their departments', () => {
    expect(canManageDepartmentTeams({ id: 1, role: 'IT_ADMIN', departmentId: null }, 3, DEPARTMENTS)).toBe(true);
    expect(canManageDepartmentTeams({ id: 30, role: 'IT_MANAGER', departmentId: 9 }, 2, DEPARTMENTS)).toBe(true); // manages IT-APPS
    expect(canManageDepartmentTeams({ id: 40, role: 'IT_MANAGER', departmentId: 3 }, 3, DEPARTMENTS)).toBe(true); // belongs to IT-SD
    expect(canManageDepartmentTeams({ id: 40, role: 'IT_MANAGER', departmentId: 3 }, 2, DEPARTMENTS)).toBe(false);
    expect(canManageDepartmentTeams({ id: 5, role: 'INCIDENT_MANAGER', departmentId: 2 }, 2, DEPARTMENTS)).toBe(false);
  });
});

describe('TeamsPage', () => {
  it('lists teams with queue KPIs and opens the detail drawer with members', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<TeamsPage />, { route: '/teams' });
    const table = await screen.findByRole('table', { name: 'Teams' });
    expect(within(table).getByText('Payroll & HR Apps')).toBeInTheDocument();
    expect(within(table).getByText('Default')).toBeInTheDocument();
    // KPIs are sums over the listed teams: members 5, open incidents 10, unassigned 1, open requests 7.
    expect(screen.getByText('10')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
    expect(last('GET', '/teams')?.params).toMatchObject({ active: 'true' });

    await user.click(within(table).getByText('Payroll & HR Apps'));
    const drawer = await screen.findByRole('dialog');
    const members = await within(drawer).findByRole('list', { name: 'Team members' });
    expect(within(members).getByText('Lena Lead')).toBeInTheDocument();
    expect(within(members).getAllByText('Lead').length).toBeGreaterThan(0);
  });

  it('changes a member role, removes and adds members as a team admin', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<TeamsPage />, { route: '/teams?team=1', auth: makeAuth(makeUser('IT_ADMIN')) });
    const drawer = await screen.findByRole('dialog');
    await user.selectOptions(await within(drawer).findByRole('combobox', { name: 'Team role of Mo Member' }), 'LEAD');
    await waitFor(() => expect(last('PUT', '/teams/1/members/12')?.body).toEqual({ memberRole: 'LEAD' }));

    await user.click(within(drawer).getByRole('button', { name: 'Remove Mo Member' }));
    const confirm = await screen.findByRole('dialog', { name: /Remove Mo Member/ });
    await user.click(within(confirm).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(last('DELETE', '/teams/1/members/12')).toBeDefined());

    const staff = await within(drawer).findByRole('combobox', { name: 'Staff member' });
    await waitFor(() => expect(within(staff).getByRole('option', { name: /Nina New/ })).toBeInTheDocument());
    expect(within(staff).queryByRole('option', { name: /Lena Lead/ })).not.toBeInTheDocument();
    await user.selectOptions(staff, '13');
    await user.click(within(drawer).getByRole('button', { name: /^Add$/ }));
    await waitFor(() => expect(last('POST', '/teams/1/members')?.body).toEqual({ userId: 13, memberRole: 'MEMBER' }));
  });

  it('creates a team with the API request shape', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<TeamsPage />, { route: '/teams' });
    await user.click(await screen.findByRole('button', { name: /New team/ }));
    const form = await screen.findByRole('form', { name: 'New team' });
    await user.type(within(form).getByLabelText(/Code/), 'NET-OPS');
    await user.type(within(form).getByLabelText(/^Name/), 'Network Ops');
    await user.selectOptions(within(form).getByLabelText(/Department/), '2');
    await user.click(within(form).getByLabelText(/Default team of the department/));
    await user.click(within(form).getByRole('button', { name: 'Create team' }));
    await waitFor(() =>
      expect(last('POST', '/teams')?.body).toEqual({
        code: 'NET-OPS',
        name: 'Network Ops',
        departmentId: 2,
        leadId: null,
        email: null,
        description: null,
        active: true,
        departmentDefault: true,
      }),
    );
  });

  it('rejects an invalid team code before calling the API', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<TeamsPage />, { route: '/teams' });
    await user.click(await screen.findByRole('button', { name: /New team/ }));
    const form = await screen.findByRole('form', { name: 'New team' });
    await user.type(within(form).getByLabelText(/Code/), 'bad code');
    await user.click(within(form).getByRole('button', { name: 'Create team' }));
    expect(await within(form).findByText(/upper-case letters/)).toBeInTheDocument();
    expect(last('POST', '/teams')).toBeUndefined();
  });

  it('is read-only for readers who are not team admins', async () => {
    setup();
    renderWithProviders(<TeamsPage />, { route: '/teams?team=1', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const drawer = await screen.findByRole('dialog');
    await within(drawer).findByRole('list', { name: 'Team members' });
    expect(screen.queryByRole('button', { name: /New team/ })).not.toBeInTheDocument();
    expect(within(drawer).queryByRole('button', { name: /Edit team/ })).not.toBeInTheDocument();
    expect(within(drawer).queryByRole('combobox', { name: /Team role of/ })).not.toBeInTheDocument();
  });

  it('limits IT managers to the teams of their own departments', async () => {
    setup();
    renderWithProviders(<TeamsPage />, { route: '/teams?team=1', auth: makeAuth(makeUser('IT_MANAGER', { id: 40, departmentId: 3 })) });
    const drawer = await screen.findByRole('dialog');
    await within(drawer).findByRole('list', { name: 'Team members' });
    // Team 1 belongs to IT-APPS, which this manager neither manages nor belongs to.
    expect(within(drawer).queryByRole('button', { name: /Edit team/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /New team/ })).toBeInTheDocument();
  });
});
