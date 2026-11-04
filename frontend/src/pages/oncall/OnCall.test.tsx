import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeNowEntry, makeOverride, makePolicy, makeScheduleDetail, makeScheduleSummary } from '../../test/responseFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { Role } from '../../lib/types';
import OnCallPage from './OnCallPage';
import ScheduleDetailPage from './ScheduleDetailPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const STAFF_USERS = [
  { id: 21, fullName: 'Riya Varma', email: 'riya@sa.test', role: 'SUPPORT_AGENT', departmentId: 2, departmentName: 'IT', openAssignments: 0 },
  { id: 22, fullName: 'Dev Nair', email: 'dev@sa.test', role: 'SUPPORT_AGENT', departmentId: 2, departmentName: 'IT', openAssignments: 0 },
];

function listRoutes() {
  mockApi({
    'GET /oncall/now': json([makeNowEntry()]),
    'GET /oncall/schedules': json([makeScheduleSummary()]),
    'GET /oncall/my-shifts': json([
      { scheduleId: 3, scheduleName: 'Payroll & HR Apps on-call', teamId: 4, teamName: 'Payroll & HR Apps', layer: 'PRIMARY', start: '2026-11-04T03:30:00Z', end: '2026-11-11T03:30:00Z', source: 'ROTATION', current: true },
    ]),
    'GET /oncall/overrides/offers': json([makeOverride({ id: 12, status: 'OFFERED', userId: 7, userName: 'Ada Lovelace', canTake: true, scheduleName: 'Network Operations' })]),
    'POST /oncall/overrides/12/take': json(makeOverride({ id: 12, status: 'ACTIVE' })),
    'POST /oncall/overrides/12/decline': json(makeOverride({ id: 12, status: 'DECLINED' })),
    'GET /oncall/availability': json([{ id: 4, userId: 7, userName: 'Ada Lovelace', startsAt: '2026-11-20T00:00:00Z', endsAt: '2026-11-22T00:00:00Z', reason: 'Annual leave', demo: false, createdAt: '2026-11-01T00:00:00Z' }]),
    'DELETE /oncall/availability/4': { status: 204 },
    'GET /oncall/escalation-policies': json([makePolicy()]),
    'GET /oncall/pages': json([
      { id: 1, alertId: 61, alertNumber: 'ALR-00061', alertTitle: 'Payroll API 5xx', severity: 'CRITICAL', alertStatus: 'ACKNOWLEDGED', level: 1, target: 'PRIMARY', userId: 21, userName: 'Riya Varma', scheduleId: 3, scheduleName: 'Payroll & HR Apps on-call', pagedAt: '2026-11-04T05:00:00Z', acknowledgedAt: '2026-11-04T05:04:00Z', ackMinutes: 4, demo: true },
    ]),
    'GET /teams': json([]),
    'GET /users/assignable': json(STAFF_USERS),
    'GET /sla/calendars': json([]),
  });
}

function renderList(role: Role, route = '/on-call') {
  return renderWithProviders(<OnCallPage />, { route, auth: makeAuth(makeUser(role)) });
}

describe('On-call page', () => {
  it('shows who is on call now with the assignment source and the covered participant', async () => {
    listRoutes();
    renderList('AUDITOR');
    const card = await screen.findByRole('article', { name: 'Payroll & HR Apps on call' });
    expect(within(card).getByText('Dev Nair')).toBeInTheDocument();
    expect(within(card).getByText('Override')).toBeInTheDocument();
    expect(within(card).getByText(/covering Riya Varma/)).toBeInTheDocument();
    expect(within(card).getByText('Nobody on call')).toBeInTheDocument();
    expect(within(card).getByText(/Asia\/Kolkata/)).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Payroll & HR Apps on-call' })).toHaveAttribute('href', '/on-call/3');
    // Auditors read; the staff-only tab is not offered.
    expect(screen.queryByRole('tab', { name: 'My on-call' })).not.toBeInTheDocument();
  });

  it('lists schedules and offers "New schedule" only to on-call editors', async () => {
    listRoutes();
    renderList('INCIDENT_MANAGER', '/on-call?tab=schedules');
    expect(await screen.findByText('Payroll & HR Apps on-call')).toBeInTheDocument();
    expect(screen.getByText(/Weekly · Monday 09:00/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /New schedule/ }));
    expect(await screen.findByRole('dialog', { name: 'New on-call schedule' })).toBeInTheDocument();
  });

  it('hides schedule editing from support agents', async () => {
    listRoutes();
    renderList('SUPPORT_AGENT', '/on-call?tab=schedules');
    expect(await screen.findByText('Payroll & HR Apps on-call')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New schedule/ })).not.toBeInTheDocument();
  });

  it('lets staff take an offered shift and manage their availability', async () => {
    listRoutes();
    renderList('SUPPORT_AGENT', '/on-call?tab=mine');
    expect(await screen.findByText('On call now')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Take offer on Network Operations' }));
    await waitFor(() => expect(last('POST', '/oncall/overrides/12/take')).toBeTruthy());
    expect(await screen.findByText('Annual leave')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Remove unavailability/ }));
    await waitFor(() => expect(last('DELETE', '/oncall/availability/4')).toBeTruthy());
  });

  it('declines an offer with a note after confirmation', async () => {
    listRoutes();
    renderList('SUPPORT_AGENT', '/on-call?tab=mine');
    await userEvent.click(await screen.findByRole('button', { name: 'Decline offer on Network Operations' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Already on leave');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Decline' }));
    await waitFor(() => expect(last('POST', '/oncall/overrides/12/decline')?.body).toEqual({ note: 'Already on leave' }));
  });

  it('shows escalation levels with cumulative timing and gates the editor', async () => {
    listRoutes();
    renderList('AUDITOR', '/on-call?tab=policies');
    const levels = await screen.findByRole('list', { name: 'Levels of Payroll critical path' });
    expect(within(levels).getAllByRole('listitem')).toHaveLength(3);
    expect(within(levels).getByText('Priya Raman')).toBeInTheDocument();
    expect(within(levels).getByText(/t\+15 min/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit Payroll critical path/ })).not.toBeInTheDocument();
  });

  it('saves an escalation policy with ordered levels', async () => {
    listRoutes();
    mockApi({
      'GET /oncall/escalation-policies': json([makePolicy()]),
      'PUT /oncall/escalation-policies/1': (config) => json({ ...makePolicy(), ...JSON.parse(String(config.data)), levels: makePolicy().levels }),
      'GET /teams': json([]),
      'GET /users/assignable': json(STAFF_USERS),
    });
    renderList('IT_MANAGER', '/on-call?tab=policies');
    await userEvent.click(await screen.findByRole('button', { name: 'Edit Payroll critical path' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit Payroll critical path' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save policy' }));
    await waitFor(() => expect(last('PUT', '/oncall/escalation-policies/1')).toBeTruthy());
    const body = last('PUT', '/oncall/escalation-policies/1')?.body as { levels: { delayMinutes: number; target: string; userId: number | null }[] };
    expect(body.levels).toEqual([
      { delayMinutes: 0, target: 'PRIMARY', userId: null },
      { delayMinutes: 5, target: 'SECONDARY', userId: null },
      { delayMinutes: 10, target: 'USER', userId: 30 },
    ]);
  });

  it('shows the page log with acknowledgement minutes', async () => {
    listRoutes();
    renderList('AUDITOR', '/on-call?tab=pages');
    expect(await screen.findByRole('link', { name: 'ALR-00061' })).toHaveAttribute('href', '/alerts/61');
    expect(screen.getByText('after 4 min')).toBeInTheDocument();
  });
});

describe('Schedule detail — shift timeline', () => {
  const SHIFTS = {
    scheduleId: 3,
    timeZone: 'Asia/Kolkata',
    from: '2026-11-01T18:30:00Z',
    to: '2026-11-08T18:30:00Z',
    primary: [
      { layer: 'PRIMARY', userId: 21, userName: 'Riya Varma', start: '2026-10-26T03:30:00Z', end: '2026-11-05T03:30:00Z', source: 'ROTATION', scheduledUserId: 21, scheduledUserName: 'Riya Varma', holiday: false, overrideId: null },
      { layer: 'PRIMARY', userId: 22, userName: 'Dev Nair', start: '2026-11-05T03:30:00Z', end: '2026-11-05T15:30:00Z', source: 'OVERRIDE', scheduledUserId: 21, scheduledUserName: 'Riya Varma', holiday: false, overrideId: 11 },
      { layer: 'PRIMARY', userId: 30, userName: 'Priya Raman', start: '2026-11-07T18:30:00Z', end: '2026-11-08T18:30:00Z', source: 'HOLIDAY_COVER', scheduledUserId: 21, scheduledUserName: 'Riya Varma', holiday: true, overrideId: null },
    ],
    secondary: [{ layer: 'SECONDARY', userId: 22, userName: 'Dev Nair', start: '2026-11-01T18:30:00Z', end: '2026-11-08T18:30:00Z', source: 'ROTATION', scheduledUserId: 22, scheduledUserName: 'Dev Nair', holiday: false, overrideId: null }],
  };

  function detailRoutes(detail = makeScheduleDetail()) {
    mockApi({
      'GET /oncall/schedules/3': json(detail),
      'GET /oncall/schedules/3/shifts': json(SHIFTS),
      'GET /oncall/schedules/3/overrides': json(detail.overrides),
      'POST /oncall/schedules/3/overrides': (config) => json(makeOverride({ ...JSON.parse(String(config.data)), status: 'ACTIVE', userName: 'Dev Nair' }), 201),
      'POST /oncall/schedules/3/handovers': json({ ...detail.handovers[0], id: 9 }, 201),
      'GET /teams/mine': json([]),
      'GET /users/assignable': json(STAFF_USERS),
    });
  }

  function renderDetail(role: Role, route = '/on-call/3?week=2026-11-02') {
    return renderWithProviders(
      <Routes>
        <Route path="/on-call/:scheduleId" element={<ScheduleDetailPage />} />
      </Routes>,
      { route, auth: makeAuth(makeUser(role, { id: 21 })) },
    );
  }

  it('renders seven local days with primary and secondary bars coloured by source, in the schedule zone', async () => {
    detailRoutes();
    renderDetail('AUDITOR');
    const timeline = await screen.findByRole('group', { name: 'Shift timeline in Asia/Kolkata' });
    expect(within(timeline).getAllByTestId('shift-day')).toHaveLength(7);
    expect(within(timeline).getByText('Mon 2 Nov')).toBeInTheDocument();
    expect(within(timeline).getByText('Sun 8 Nov')).toBeInTheDocument();
    const bars = within(timeline).getAllByTestId('shift-bar');
    // Riya Mon–Thu 09:00 (4 day segments) + Dev override + Priya holiday cover on Sunday + Dev secondary all week (7).
    expect(bars).toHaveLength(4 + 1 + 1 + 7);
    expect(bars.filter((bar) => bar.dataset.source === 'OVERRIDE')).toHaveLength(1);
    const holiday = bars.find((bar) => bar.dataset.source === 'HOLIDAY_COVER');
    expect(holiday?.getAttribute('title')).toMatch(/Priya Raman \(scheduled: Riya Varma\) · Holiday cover · Sun 8 Nov, 00:00 – Mon 9 Nov, 00:00/);
    const override = bars.find((bar) => bar.dataset.source === 'OVERRIDE');
    expect(override?.getAttribute('title')).toMatch(/Thu 5 Nov, 09:00 – Thu 5 Nov, 21:00/);
    // Riya's shift started the week before, so Monday's bar continues from the previous day.
    const mondayBars = within(within(timeline).getAllByTestId('shift-day')[0]).getAllByTestId('shift-bar');
    expect(mondayBars[0].dataset.before).toBe('true');
    // The week boundaries are local midnights of Asia/Kolkata.
    const shiftsRequest = last('GET', '/oncall/schedules/3/shifts');
    expect(shiftsRequest?.params).toEqual({ from: '2026-11-01T18:30:00.000Z', to: '2026-11-08T18:30:00.000Z' });
    expect(screen.getAllByText(/UTC\+05:30/).length).toBeGreaterThan(0);
  });

  it('navigates weeks and switches to the list view', async () => {
    detailRoutes();
    renderDetail('AUDITOR');
    await screen.findByRole('group', { name: 'Shift timeline in Asia/Kolkata' });
    await userEvent.click(screen.getByRole('button', { name: 'Next week' }));
    await waitFor(() => expect(last('GET', '/oncall/schedules/3/shifts')?.params).toEqual({ from: '2026-11-08T18:30:00.000Z', to: '2026-11-15T18:30:00.000Z' }));
    await userEvent.click(screen.getByRole('button', { name: 'Previous week' }));
    await userEvent.click(screen.getByRole('button', { name: /List/ }));
    const lists = await screen.findAllByRole('list', { name: 'Shifts' });
    expect(within(lists[0]).getByText('Priya Raman')).toBeInTheDocument();
    expect(within(lists[0]).getByText('Holiday')).toBeInTheDocument();
  });

  it('shows participants, holidays, the escalation ladder and handovers; read-only for auditors', async () => {
    detailRoutes();
    renderDetail('AUDITOR');
    expect(await screen.findByText('Diwali')).toBeInTheDocument();
    expect(screen.getByText('Unavailable now')).toBeInTheDocument();
    expect(screen.getByText('Watch the payroll batch at 02:00')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Escalation levels' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit schedule/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Override \/ offer|Offer my shift/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add note/ })).not.toBeInTheDocument();
  });

  it('creates a direct override with times entered in the schedule time zone', async () => {
    detailRoutes();
    renderDetail('INCIDENT_MANAGER');
    await userEvent.click(await screen.findByRole('button', { name: /Override \/ offer/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Add an override' });
    await userEvent.selectOptions(within(dialog).getByLabelText(/Covered by/), '22');
    fireEvent.change(within(dialog).getByLabelText('Starts (Asia/Kolkata)'), { target: { value: '2026-11-06T09:00' } });
    fireEvent.change(within(dialog).getByLabelText('Ends (Asia/Kolkata)'), { target: { value: '2026-11-06T21:00' } });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add override' }));
    await waitFor(() => expect(last('POST', '/oncall/schedules/3/overrides')).toBeTruthy());
    expect(last('POST', '/oncall/schedules/3/overrides')?.body).toMatchObject({ offer: false, layer: 'PRIMARY', userId: 22, startsAt: '2026-11-06T03:30:00.000Z', endsAt: '2026-11-06T15:30:00.000Z' });
  });

  it('lets a participant offer their own shift (originalUserId = me)', async () => {
    detailRoutes(makeScheduleDetail({ canEdit: false }));
    renderDetail('SUPPORT_AGENT');
    await userEvent.click(await screen.findByRole('button', { name: /Offer my shift/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Offer a shift' });
    fireEvent.change(within(dialog).getByLabelText('Starts (Asia/Kolkata)'), { target: { value: '2026-11-06T09:00' } });
    fireEvent.change(within(dialog).getByLabelText('Ends (Asia/Kolkata)'), { target: { value: '2026-11-06T21:00' } });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Offer shift' }));
    await waitFor(() => expect(last('POST', '/oncall/schedules/3/overrides')?.body).toMatchObject({ offer: true, originalUserId: 21, userId: null }));
  });
});
