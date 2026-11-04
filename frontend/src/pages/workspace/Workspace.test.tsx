import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { makeSummary } from '../incidents/fixtures.test-utils';
import { categories, makeApproval, makeRequest } from '../../components/servicedesk/fixtures.test-utils';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { MyWorkDto, PortalOverview, TeamDto } from '../../lib/types';
import { extraSections, sectionTitle } from './sections';
import MyWorkPage from './MyWorkPage';
import PortalPage from './PortalPage';

const openNewIncident = vi.fn();
vi.mock('../../components/incidents/NewIncidentContext', () => ({ useNewIncident: () => ({ open: openNewIncident }) }));

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const QUEUED = makeSummary({ id: 21, number: 'INC-7021', title: 'VPN drops every hour', assigneeId: null, assigneeName: null, status: 'NEW', teamId: 4, teamName: 'Network Operations', sla: null });
const TEAM: TeamDto = {
  id: 4,
  code: 'NET-OPS',
  name: 'Network Operations',
  description: null,
  departmentId: 2,
  departmentCode: 'IT-NET',
  departmentName: 'Network',
  leadId: 5,
  leadName: 'Neha Lead',
  email: null,
  active: true,
  departmentDefault: false,
  memberCount: 2,
  openIncidents: 3,
  unassignedIncidents: 1,
  openRequests: 0,
  demo: true,
  members: [
    { userId: 5, fullName: 'Neha Lead', email: 'neha@nexus360.test', role: 'SUPPORT_AGENT', jobTitle: null, memberRole: 'LEAD', active: true, openAssignments: 2, joinedAt: '2026-10-01T00:00:00Z' },
    { userId: 6, fullName: 'Omar Agent', email: 'omar@nexus360.test', role: 'SUPPORT_AGENT', jobTitle: null, memberRole: 'MEMBER', active: true, openAssignments: 0, joinedAt: '2026-10-01T00:00:00Z' },
  ],
};

const MY_WORK: MyWorkDto = {
  generatedAt: '2026-11-04T08:00:00Z',
  teams: [{ id: 4, code: 'NET-OPS', name: 'Network Operations', departmentId: 2, departmentName: 'Network', memberRole: 'MEMBER' }],
  incidents: { total: 12, items: [makeSummary({ id: 1, number: 'INC-7001', title: 'Payroll export failing' })] },
  teamQueue: { total: 1, items: [QUEUED] },
  requests: { total: 0, items: [] },
  teamRequests: { total: 1, items: [makeRequest({ id: 31, number: 'REQ-3031', title: 'New monitor', assigneeId: null, assigneeName: null, teamId: 4, teamName: 'Network Operations' })] },
  approvals: { total: 1, items: [makeApproval({ id: 41, referenceNumber: 'REQ-3040', title: 'Power BI licence' })] },
  // A section contributed by a later module: rendered generically.
  problems: { total: 3, items: [{ id: 8, number: 'PRB-1008', title: 'Recurring VPN drops', status: 'INVESTIGATING' }] },
};

describe('generic sections', () => {
  it('titles and links unknown section keys', () => {
    expect(sectionTitle('onCallShifts')).toBe('On-call shifts');
    expect(sectionTitle('vendorTickets')).toBe('Vendor tickets');
    const [problems, other] = extraSections(
      { incidents: { total: 1, items: [] }, problems: MY_WORK.problems, vendorTickets: { total: 1, items: [{ id: 'x', name: 'Ticket', link: 'https://evil.test' }] }, generatedAt: 'x' },
      ['incidents', 'generatedAt'],
    );
    expect(problems).toMatchObject({ key: 'problems', title: 'Problems', total: 3 });
    expect(problems.items[0]).toMatchObject({ reference: 'PRB-1008', label: 'Recurring VPN drops', to: '/problems/8' });
    // Absolute links from data are never rendered.
    expect(other.items[0].to).toBeNull();
  });
});

describe('MyWorkPage', () => {
  function setup() {
    mockApi({
      'GET /my-work': json(MY_WORK),
      'GET /teams/4': json(TEAM),
      'POST /incidents/21/assign': json({ ...QUEUED, assigneeId: 7 }),
      'POST /service-requests/31/assign': json(MY_WORK.teamRequests.items[0]),
      'POST /approvals/41/decision': json({ ...MY_WORK.approvals.items[0], status: 'APPROVED' }),
    });
  }

  it('renders every section, my teams and a generic section from a later module', async () => {
    setup();
    renderWithProviders(<MyWorkPage />, { route: '/my-work', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    expect(await screen.findByRole('region', { name: /My incidents/ })).toBeInTheDocument();
    expect(screen.getByText('INC-7001')).toHaveAttribute('href', '/incidents/1');
    expect(screen.getByText('Showing 1 of 12')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: /Team queue/ })).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: /Team queue/ })).getByText('VPN drops every hour')).toBeInTheDocument();
    expect(screen.getByText('No open requests assigned to you')).toBeInTheDocument();
    expect(screen.getByText('Network Operations', { selector: '.badge' })).toBeInTheDocument();
    const problems = screen.getByRole('region', { name: /Problems/ });
    expect(within(problems).getByRole('link', { name: 'Recurring VPN drops' })).toHaveAttribute('href', '/problems/8');
  });

  it('takes a queued incident and request for myself with the team kept', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<MyWorkPage />, { route: '/my-work', auth: makeAuth(makeUser('SUPPORT_AGENT', { id: 7 })) });
    await user.click(await screen.findByRole('button', { name: 'Take INC-7021' }));
    await waitFor(() => expect(last('POST', '/incidents/21/assign')?.body).toEqual({ assigneeId: 7, teamId: 4 }));
    await user.click(screen.getByRole('button', { name: 'Take REQ-3031' }));
    await waitFor(() => expect(last('POST', '/service-requests/31/assign')?.body).toEqual({ assigneeId: 7, teamId: 4 }));
  });

  it('assigns a queued incident to a team member picked from the team', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<MyWorkPage />, { route: '/my-work', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    await user.click(await screen.findByRole('button', { name: 'Assign INC-7021' }));
    const dialog = await screen.findByRole('dialog', { name: /Assign INC-7021/ });
    const select = await within(dialog).findByRole('combobox', { name: 'Assignee' });
    await waitFor(() => expect(within(select).getByRole('option', { name: /Omar Agent/ })).toBeInTheDocument());
    await user.selectOptions(select, '6');
    await user.click(within(dialog).getByRole('button', { name: 'Assign' }));
    await waitFor(() => expect(last('POST', '/incidents/21/assign')?.body).toEqual({ assigneeId: 6, teamId: 4 }));
  });

  it('approves an approval waiting for me', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<MyWorkPage />, { route: '/my-work', auth: makeAuth(makeUser('IT_MANAGER')) });
    await user.click(await screen.findByRole('button', { name: 'Approve Power BI licence' }));
    const dialog = await screen.findByRole('dialog', { name: /Approve REQ-3040/ });
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(last('POST', '/approvals/41/decision')?.body).toMatchObject({ approved: true }));
  });

  it('shows an error state with retry when the API fails', async () => {
    mockApi({ 'GET /my-work': { status: 500, data: { status: 500, message: 'Boom' } } });
    renderWithProviders(<MyWorkPage />, { route: '/my-work', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    expect(await screen.findByRole('button', { name: /Retry|Try again/ })).toBeInTheDocument();
  });
});

const PORTAL: PortalOverview = {
  generatedAt: '2026-11-04T08:00:00Z',
  openIncidents: { total: 1, items: [makeSummary({ id: 3, number: 'INC-7003', title: 'Laptop will not boot' })] },
  openRequests: { total: 0, items: [] },
  awaitingApproval: {
    total: 1,
    items: [{ id: 9, type: 'SERVICE_REQUEST', referenceNumber: 'REQ-3009', title: 'Adobe licence', approverRole: 'IT_MANAGER', createdAt: '2026-11-03T08:00:00Z' }],
  },
  serviceStatus: {
    overall: 'DEGRADED',
    counts: { OPERATIONAL: 5, DEGRADED: 1, OUTAGE: 0, MAINTENANCE: 0 },
    services: [
      { id: 1, code: 'MAIL', name: 'E-mail', status: 'OPERATIONAL', statusReason: null },
      { id: 2, code: 'VPN', name: 'Remote access VPN', status: 'DEGRADED', statusReason: 'Dependency Core Network is in outage' },
    ],
    maintenance: [{ id: 5, title: 'Payroll upgrade', startsAt: '2026-11-08T20:00:00Z', endsAt: '2026-11-08T23:00:00Z', status: 'SCHEDULED', components: ['Payroll'] }],
  },
  recommendedArticles: [
    {
      id: 77,
      number: 'KB-1077',
      title: 'Fix a laptop that will not boot',
      summary: '',
      articleType: 'GUIDE',
      category: 'HARDWARE',
      serviceId: null,
      serviceName: null,
      tags: [],
      status: 'PUBLISHED',
      authorName: null,
      viewCount: 120,
      helpfulCount: 0,
      notHelpfulCount: 0,
      averageRating: null,
      ratingCount: 0,
      readMinutes: 3,
      publishedAt: null,
      updatedAt: '2026-11-01T00:00:00Z',
      score: null,
      demo: true,
    },
  ],
};

function Where() {
  const location = useLocation();
  return <p data-testid="location">{`${location.pathname}${location.search}`}</p>;
}

describe('PortalPage', () => {
  function setup() {
    mockApi({ 'GET /portal/overview': json(PORTAL), 'GET /catalog/categories': json(categories) });
  }

  it('shows quick actions, my tickets, service status, maintenance and articles', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<PortalPage />, { route: '/portal', auth: makeAuth(makeUser('EMPLOYEE')) });
    expect(await screen.findByText('Laptop will not boot')).toBeInTheDocument();
    expect(screen.getByText('You have no open requests')).toBeInTheDocument();
    expect(screen.getByText('Adobe licence')).toBeInTheDocument();
    expect(screen.getByText('Some services are degraded')).toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: 'Affected services' })).getByText('Remote access VPN')).toBeInTheDocument();
    expect(screen.queryByText('E-mail', { selector: 'b' })).not.toBeInTheDocument();
    expect(screen.getByText('Payroll upgrade')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Fix a laptop that will not boot' })).toHaveAttribute('href', '/knowledge/77');
    expect(screen.getByRole('link', { name: /Status page/ })).toHaveAttribute('href', '/status');

    const hardware = categories.find((category) => category.code === 'HARDWARE');
    if (hardware) expect(screen.getByRole('link', { name: /Request hardware/ })).toHaveAttribute('href', `/catalog?category=${hardware.id}`);
    await user.click(screen.getByRole('button', { name: /Report an incident/ }));
    expect(openNewIncident).toHaveBeenCalled();
  });

  it('searches the knowledge base from the quick action', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Routes>
          <Route path="/portal" element={<PortalPage />} />
          <Route path="/knowledge" element={<p>Knowledge</p>} />
        </Routes>
        <Where />
      </>,
      { route: '/portal', auth: makeAuth(makeUser('EMPLOYEE')) },
    );
    await user.type(await screen.findByRole('searchbox', { name: 'Search the knowledge base' }), 'vpn drops');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/knowledge?q=vpn%20drops');
  });

  it('hides raising actions for auditors (read-only)', async () => {
    setup();
    renderWithProviders(<PortalPage />, { route: '/portal', auth: makeAuth(makeUser('AUDITOR')) });
    await screen.findByText('Laptop will not boot');
    expect(screen.queryByRole('button', { name: /Report an incident/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Request hardware/ })).not.toBeInTheDocument();
  });
});
