import { screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { json, mockApi } from '../../test/mockApi';
import { makeAlertSummary } from '../../test/opsFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { MyWorkDto, PortalOverview } from '../../lib/types';
import MyWorkPage from './MyWorkPage';
import PortalPage from './PortalPage';

vi.mock('../../components/incidents/NewIncidentContext', () => ({ useNewIncident: () => ({ open: vi.fn() }) }));

const empty = { total: 0, items: [] };

const MY_WORK: MyWorkDto = {
  generatedAt: '2026-11-04T08:00:00Z',
  teams: [],
  incidents: empty,
  teamQueue: empty,
  requests: empty,
  teamRequests: empty,
  approvals: empty,
  alerts: { total: 4, items: [makeAlertSummary({ incidentId: 9, incidentNumber: 'INC-7009' }), makeAlertSummary({ id: 62, number: 'ALR-00062', title: 'VPN tunnel flapping', severity: 'MAJOR', status: 'ACKNOWLEDGED' })] },
};

const PORTAL: PortalOverview = {
  generatedAt: '2026-11-04T08:00:00Z',
  openIncidents: empty,
  openRequests: empty,
  awaitingApproval: empty,
  serviceStatus: { overall: 'OPERATIONAL', counts: { OPERATIONAL: 3 }, services: [], maintenance: [] },
  recommendedArticles: [],
  myAssets: {
    total: 2,
    items: [
      { id: 51, assetTag: 'AST-0051', name: 'ThinkPad T14', assetType: 'LAPTOP', status: 'IN_USE', manufacturer: 'Lenovo', model: 'T14 Gen 4', serialNumber: 'PF-123', expiresOn: '2027-09-01', assignedAt: '2026-09-01T08:00:00Z' },
      { id: 52, assetTag: 'AST-0052', name: 'Microsoft 365 E3', assetType: 'SOFTWARE_LICENSE', status: 'IN_USE', manufacturer: 'Microsoft', model: null, serialNumber: null, expiresOn: null, assignedAt: '2026-10-01T08:00:00Z' },
    ],
  },
};

describe('My Work — alerts section (R3 BE-3)', () => {
  it('renders alerts of my teams with severity, status and links instead of the generic panel', async () => {
    mockApi({ 'GET /my-work': json(MY_WORK) });
    renderWithProviders(<MyWorkPage />, { route: '/my-work', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const panel = (await screen.findByText('Alerts for my teams')).closest('section') ?? document.body;
    expect(within(panel as HTMLElement).getByRole('link', { name: 'ALR-00061' })).toHaveAttribute('href', '/alerts/61');
    expect(within(panel as HTMLElement).getByRole('link', { name: 'INC-7009' })).toHaveAttribute('href', '/incidents/9');
    expect(within(panel as HTMLElement).getByText('Critical')).toBeInTheDocument();
    expect(within(panel as HTMLElement).getByText('Acknowledged')).toBeInTheDocument();
    expect(screen.getByText('Showing 2 of 4')).toBeInTheDocument();
    expect(screen.queryByText(/^Alerts$/)).not.toBeInTheDocument();
  });
});

describe('Employee portal — my assets (R3 BE-3)', () => {
  it('lists my devices and licence seats without linking employees to the asset pages', async () => {
    mockApi({ 'GET /portal/overview': json(PORTAL), 'GET /catalog/categories': json([]) });
    renderWithProviders(<PortalPage />, { route: '/portal', auth: makeAuth(makeUser('EMPLOYEE')) });
    expect(await screen.findByText('My assets')).toBeInTheDocument();
    expect(screen.getByText('ThinkPad T14')).toBeInTheDocument();
    expect(screen.getByText(/S\/N PF-123/)).toBeInTheDocument();
    expect(screen.getByText(/licence/i, { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'AST-0051' })).not.toBeInTheDocument();
  });

  it('links to the asset for staff', async () => {
    mockApi({ 'GET /portal/overview': json(PORTAL), 'GET /catalog/categories': json([]) });
    renderWithProviders(<PortalPage />, { route: '/portal', auth: makeAuth(makeUser('ASSET_MANAGER')) });
    expect(await screen.findByRole('link', { name: 'AST-0051' })).toHaveAttribute('href', '/assets/51');
  });
});

describe('My Work — on-call shifts and action items (R3 BE-4)', () => {
  it('renders my shifts and my open action items with links instead of the generic panels', async () => {
    mockApi({
      'GET /my-work': json({
        ...MY_WORK,
        alerts: empty,
        onCallShifts: {
          total: 2,
          items: [
            { scheduleId: 3, scheduleName: 'Payroll & HR Apps on-call', teamId: 4, teamName: 'Payroll & HR Apps', layer: 'PRIMARY', start: '2026-11-04T03:30:00Z', end: '2026-11-11T03:30:00Z', source: 'ROTATION', current: true },
            { scheduleId: 5, scheduleName: 'Network Operations on-call', teamId: 6, teamName: 'Network Operations', layer: 'SECONDARY', start: '2026-11-12T02:30:00Z', end: '2026-11-13T02:30:00Z', source: 'OVERRIDE', current: false },
          ],
        },
        actionItems: {
          total: 1,
          items: [{ id: 51, title: 'Raise the pool size', actionType: 'CORRECTIVE', status: 'IN_PROGRESS', dueDate: '2026-11-01', overdue: true, source: 'PIR', sourceId: 6, sourceNumber: 'PIR-006', sourceTitle: 'Payroll 503s', link: '/reviews/6' }],
        },
      }),
    });
    renderWithProviders(<MyWorkPage />, { route: '/my-work', auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const shifts = (await screen.findByText('My on-call shifts')).closest('section') as HTMLElement;
    expect(within(shifts).getByRole('link', { name: 'Payroll & HR Apps on-call' })).toHaveAttribute('href', '/on-call/3');
    expect(within(shifts).getByText('On call now')).toBeInTheDocument();
    expect(within(shifts).getByText('Override')).toBeInTheDocument();
    const actions = (screen.getByText('My action items').closest('section') as HTMLElement) ?? document.body;
    expect(within(actions).getByRole('link', { name: 'Raise the pool size' })).toHaveAttribute('href', '/reviews/6');
    expect(within(actions).getByText('Overdue')).toBeInTheDocument();
    expect(within(actions).getByText('In progress')).toBeInTheDocument();
    expect(screen.queryByText(/^On-call shifts$/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Action items$/)).not.toBeInTheDocument();
  });
});
