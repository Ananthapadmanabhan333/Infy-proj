import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { NewIncidentProvider } from '../../components/incidents/NewIncidentContext';
import { RequestDrawer } from '../../components/servicedesk/RequestDrawer';
import { makeRequest } from '../../components/servicedesk/fixtures.test-utils';
import { ServiceEditor } from '../../components/services/ServiceEditor';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { ServiceDto, TeamDto } from '../../lib/types';
import { AssignDialog } from './detail/IncidentDialogs';
import { OverviewTab } from './detail/OverviewTab';
import { makeDetail, makeSummary, page } from './fixtures.test-utils';
import IncidentsPage from './IncidentsPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

function team(id: number, name: string, departmentId = 2, overrides: Partial<TeamDto> = {}): TeamDto {
  return {
    id,
    code: `T${id}`,
    name,
    description: null,
    departmentId,
    departmentCode: null,
    departmentName: departmentId === 2 ? 'Payroll Team' : 'Network',
    leadId: null,
    leadName: null,
    email: null,
    active: true,
    departmentDefault: false,
    memberCount: 1,
    openIncidents: 0,
    unassignedIncidents: 0,
    openRequests: 0,
    demo: false,
    members: [{ userId: 15, fullName: 'Tara Member', email: 'tara@nexus360.test', role: 'SUPPORT_AGENT', jobTitle: null, memberRole: 'MEMBER', active: true, openAssignments: 2, joinedAt: '2026-10-01T00:00:00Z' }],
    ...overrides,
  };
}

const TEAMS = [team(4, 'Payroll & HR Apps'), team(5, 'Network Operations', 3)];

describe('incidents list: team filter and "My team" scope', () => {
  it('sends teamId and scope=team', async () => {
    mockApi({
      'GET /incidents': json(page([makeSummary({ teamId: 4, teamName: 'Payroll & HR Apps' })])),
      'GET /incidents/counts': json({ all: 1, open: 1, inProgress: 1, resolved: 0 }),
      'GET /services': json([]),
      'GET /users/assignable': json([]),
      'GET /teams': json(TEAMS),
    });
    const user = userEvent.setup();
    renderWithProviders(
      <NewIncidentProvider>
        <IncidentsPage />
      </NewIncidentProvider>,
      { route: '/incidents', auth: makeAuth(makeUser('SUPPORT_AGENT')) },
    );
    const teamFilter = await screen.findByRole('combobox', { name: 'Team' });
    await waitFor(() => expect(within(teamFilter).getByRole('option', { name: 'Network Operations' })).toBeInTheDocument());
    await user.selectOptions(teamFilter, '5');
    await waitFor(() => expect(last('GET', '/incidents')?.params).toMatchObject({ teamId: '5' }));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Scope' }), 'team');
    await waitFor(() => expect(last('GET', '/incidents')?.params).toMatchObject({ scope: 'team', teamId: '5' }));
    expect(within(screen.getByRole('combobox', { name: 'Scope' })).getByRole('option', { name: 'My department' })).toBeInTheDocument();
  });

  it('hides the team filter from employees', async () => {
    mockApi({
      'GET /incidents': json(page([])),
      'GET /incidents/counts': json({ all: 0, open: 0, inProgress: 0, resolved: 0 }),
      'GET /services': json([]),
    });
    renderWithProviders(
      <NewIncidentProvider>
        <IncidentsPage />
      </NewIncidentProvider>,
      { route: '/incidents', auth: makeAuth(makeUser('EMPLOYEE')) },
    );
    await screen.findByRole('combobox', { name: 'Priority' });
    expect(screen.queryByRole('combobox', { name: 'Team' })).not.toBeInTheDocument();
    expect(requests.some((request) => request.url === '/teams')).toBe(false);
  });
});

describe('incident detail: team', () => {
  it('shows the team on the overview', () => {
    mockApi({ 'GET /incidents/1/comments': json([]), 'GET /incidents/1/attachments': json([]) });
    renderWithProviders(<OverviewTab incident={makeDetail({ teamId: 4, teamName: 'Payroll & HR Apps' })} readOnly />);
    expect(screen.getByText('Payroll & HR Apps')).toBeInTheDocument();
  });

  it('assigns within a team: the assignee list is the team members and the body carries teamId', async () => {
    mockApi({
      'GET /departments': json([{ id: 2, code: 'PAY', name: 'Payroll Team' }]),
      'GET /teams': json(TEAMS),
      'GET /teams/5': json(team(5, 'Network Operations', 3)),
      'GET /users/assignable': json([]),
      'POST /incidents/1/assign': json(makeDetail({ teamId: 5, teamName: 'Network Operations', assigneeName: 'Tara Member' })),
    });
    const user = userEvent.setup();
    renderWithProviders(<AssignDialog incident={makeDetail({ teamId: null, teamName: null })} open onClose={() => undefined} />);
    const teamSelect = await screen.findByLabelText('Team');
    await waitFor(() => expect(within(teamSelect).getByRole('option', { name: /Network Operations/ })).toBeInTheDocument());
    await user.selectOptions(teamSelect, '5');
    expect(screen.getByLabelText('Department')).toBeDisabled();
    const assignee = screen.getByLabelText('Assignee');
    await waitFor(() => expect(within(assignee).getByRole('option', { name: /Tara Member/ })).toBeInTheDocument());
    await user.selectOptions(assignee, '15');
    await user.click(screen.getByRole('button', { name: 'Save assignment' }));
    await waitFor(() => expect(last('POST', '/incidents/1/assign')?.body).toEqual({ teamId: 5, assigneeId: 15 }));
  });

  it('can queue an incident for a team without an assignee', async () => {
    mockApi({
      'GET /departments': json([]),
      'GET /teams': json(TEAMS),
      'GET /teams/4': json(team(4, 'Payroll & HR Apps')),
      'POST /incidents/1/assign': json(makeDetail({ teamId: 4, teamName: 'Payroll & HR Apps', assigneeId: null, assigneeName: null })),
    });
    const user = userEvent.setup();
    renderWithProviders(<AssignDialog incident={makeDetail({ assigneeId: null, assigneeName: null })} open onClose={() => undefined} />);
    const teamSelect = await screen.findByLabelText('Team');
    await waitFor(() => expect(within(teamSelect).getByRole('option', { name: /Payroll & HR Apps/ })).toBeInTheDocument());
    await user.selectOptions(teamSelect, '4');
    await user.click(screen.getByRole('button', { name: 'Save assignment' }));
    await waitFor(() => expect(last('POST', '/incidents/1/assign')?.body).toEqual({ teamId: 4, assigneeId: null }));
  });
});

describe('service request drawer: team', () => {
  it('shows the team and moves the request to another team queue', async () => {
    mockApi({
      'GET /service-requests/1': json(makeRequest({ teamId: 4, teamName: 'Payroll & HR Apps', assigneeId: null, assigneeName: null, status: 'APPROVED' })),
      'GET /service-requests/1/comments': json([]),
      'GET /service-requests/1/history': json([]),
      'GET /users/assignable': json([]),
      'GET /teams': json(TEAMS),
      'GET /teams/5': json(team(5, 'Network Operations', 3)),
      'POST /service-requests/1/assign': json(makeRequest({ teamId: 5, teamName: 'Network Operations' })),
    });
    const user = userEvent.setup();
    renderWithProviders(<RequestDrawer id={1} onClose={() => undefined} />, { auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('Payroll & HR Apps')).toBeInTheDocument();
    const teamSelect = within(drawer).getByRole('combobox', { name: 'Team' });
    await waitFor(() => expect(within(teamSelect).getByRole('option', { name: /Network Operations/ })).toBeInTheDocument());
    await user.selectOptions(teamSelect, '5');
    await user.click(within(drawer).getByRole('button', { name: /^Assign$/ }));
    await waitFor(() => expect(last('POST', '/service-requests/1/assign')?.body).toEqual({ teamId: 5 }));
  });
});

describe('service editor: default team', () => {
  const SERVICE = {
    id: 1,
    code: 'PAYROLL',
    name: 'Payroll',
    description: null,
    departmentId: 2,
    departmentName: 'Payroll Team',
    ownerId: null,
    ownerName: null,
    tier: 1,
    status: 'OPERATIONAL',
    statusReason: null,
    availability30d: 100,
    openIncidents: 0,
    criticalOpen: 0,
    maintenance: false,
    keywords: [],
    defaultTeamId: 4,
    defaultTeamName: 'Payroll & HR Apps',
    demo: false,
  } as ServiceDto;

  function routes() {
    mockApi({
      'GET /departments': json([{ id: 2, code: 'PAY', name: 'Payroll Team' }]),
      'GET /services': json([SERVICE]),
      'GET /services/1': json({ ...SERVICE, dependencies: [], dependents: [], recentIncidents: [], availabilityHistory: [] }),
      'GET /users/assignable': json([]),
      'GET /teams': json([TEAMS[0], team(6, 'Payroll Ops')]),
      'PUT /services/1': json(SERVICE),
    });
  }

  it('sends null when unchanged and 0 to clear', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<ServiceEditor service={SERVICE} onClose={() => undefined} />);
    const select = await screen.findByLabelText('Default team');
    await waitFor(() => expect(select).toHaveValue('4'));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save service' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Save service' }));
    await waitFor(() => expect(last('PUT', '/services/1')?.body).toMatchObject({ defaultTeamId: null }));

    requests.length = 0;
    await user.selectOptions(select, '');
    await user.click(screen.getByRole('button', { name: 'Save service' }));
    await waitFor(() => expect(last('PUT', '/services/1')?.body).toMatchObject({ defaultTeamId: 0 }));
  });

  it('sends the chosen team id', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<ServiceEditor service={SERVICE} onClose={() => undefined} />);
    const select = await screen.findByLabelText('Default team');
    await waitFor(() => expect(within(select).getByRole('option', { name: 'Payroll Ops' })).toBeInTheDocument());
    await user.selectOptions(select, '6');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save service' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Save service' }));
    await waitFor(() => expect(last('PUT', '/services/1')?.body).toMatchObject({ defaultTeamId: 6 }));
  });
});
