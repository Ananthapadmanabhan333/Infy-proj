import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { pageOf } from '../../test/itsmFixtures';
import { makeActionItem, makeMajorDetail, makeMajorSummary } from '../../test/responseFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { MajorDetail } from '../../lib/responseTypes';
import type { Role } from '../../lib/types';
import MajorIncidentDetailPage from './MajorIncidentDetailPage';
import MajorIncidentsPage from './MajorIncidentsPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);
const USERS = [{ id: 21, fullName: 'Riya Varma', email: 'riya@sa.test', role: 'SUPPORT_AGENT', departmentId: 2, departmentName: 'IT', openAssignments: 0 }];

describe('Major incidents list', () => {
  it('lists command records with status, commander and review, and filters open ones by default', async () => {
    mockApi({ 'GET /major-incidents': json(pageOf([makeMajorSummary()])), 'GET /users/assignable': json(USERS), 'GET /services': json([]) });
    renderWithProviders(<MajorIncidentsPage />, { route: '/major-incidents', auth: makeAuth(makeUser('INCIDENT_MANAGER')) });
    expect(await screen.findByText('MI-002')).toBeInTheDocument();
    expect(screen.getByText('Payroll portal returns 503')).toBeInTheDocument();
    expect(screen.getByText('PIR-006 · Draft')).toBeInTheDocument();
    expect(last('GET', '/major-incidents')?.params).toMatchObject({ status: 'ACTIVE,MONITORING' });
    expect(screen.getByRole('link', { name: /Post-incident reviews/ })).toHaveAttribute('href', '/reviews');
    await userEvent.click(screen.getByRole('button', { name: /Declare major incident/ }));
    expect(await screen.findByRole('dialog', { name: 'Declare a major incident' })).toBeInTheDocument();
  });

  it('does not offer declaration to support agents or auditors', async () => {
    mockApi({ 'GET /major-incidents': json(pageOf([])) });
    renderWithProviders(<MajorIncidentsPage />, { route: '/major-incidents', auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByText('No open major incidents')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Declare major incident/ })).not.toBeInTheDocument();
  });
});

function detailRoutes(detail: MajorDetail = makeMajorDetail()) {
  mockApi({
    'GET /major-incidents/2': json(detail),
    'POST /major-incidents/2/updates': (config) => {
      const body = JSON.parse(String(config.data));
      return json({ id: 9, status: body.status, title: body.title ?? 'Service disruption: Payroll', message: body.message, published: Boolean(body.publishToStatusPage), statusUpdateId: null, publishedAt: null, postedByName: 'Ada', postedAt: '2026-11-04T06:00:00Z', demo: false }, 201);
    },
    'POST /major-incidents/2/updates/8/publish': json({ ...detail.updates[1], published: true }),
    'POST /major-incidents/2/timeline': json({ id: 3, entryType: 'NOTE', body: 'x', actorId: 7, actorName: 'Ada', occurredAt: '2026-11-04T06:00:00Z', demo: false }, 201),
    'POST /major-incidents/2/status': json({ ...detail, status: 'MONITORING' }),
    'POST /major-incidents/2/actions': json(makeActionItem({ id: 42, title: 'New follow-up' }), 201),
    'PUT /action-items/41': json(makeActionItem({ status: 'DONE' })),
    'PUT /major-incidents/2/roles': json(detail),
    'GET /users/assignable': json(USERS),
    'GET /services': json([]),
  });
}

function renderDetail(role: Role) {
  return renderWithProviders(
    <Routes>
      <Route path="/major-incidents/:id" element={<MajorIncidentDetailPage />} />
    </Routes>,
    { route: '/major-incidents/2', auth: makeAuth(makeUser(role)) },
  );
}

describe('Major incident command view', () => {
  it('shows the header with status, allowed transitions, roles, bridge, timeline, updates, actions, alerts and review', async () => {
    detailRoutes();
    renderDetail('INCIDENT_MANAGER');
    expect(await screen.findByRole('heading', { name: 'Payroll portal returns 503' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Move to monitoring' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark resolved' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /meet\.example\.test/ })).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText('Sunita Joshi', { selector: 'b' })).toBeInTheDocument();
    const timeline = screen.getByRole('list', { name: 'War-room timeline' });
    expect(within(timeline).getByText('Pause the batch')).toBeInTheDocument();
    expect(screen.getByText('On status page')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'ALR-00061' })).toHaveAttribute('href', '/alerts/61');
    expect(screen.getByRole('link', { name: /PIR-006/ })).toHaveAttribute('href', '/reviews/6');
    expect(screen.getByRole('link', { name: 'INC-7009' })).toHaveAttribute('href', '/incidents/9');
  });

  it('asks for confirmation before publishing a stakeholder update to the public status page', async () => {
    detailRoutes();
    renderDetail('INCIDENT_MANAGER');
    const form = await screen.findByRole('form', { name: 'Compose stakeholder update' });
    await userEvent.type(within(form).getByLabelText(/Message/), 'Fix deployed, monitoring.');
    await userEvent.click(within(form).getByLabelText('Also publish on the public status page'));
    await userEvent.click(within(form).getByRole('button', { name: /Post and publish/ }));
    // Nothing is sent until the dialog is confirmed.
    const dialog = await screen.findByRole('dialog', { name: 'Publish to the public status page?' });
    expect(within(dialog).getByText(/confirmed subscribers are e-mailed/)).toBeInTheDocument();
    expect(last('POST', '/major-incidents/2/updates')).toBeUndefined();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Post and publish' }));
    await waitFor(() => expect(last('POST', '/major-incidents/2/updates')?.body).toEqual({ status: 'INVESTIGATING', title: null, message: 'Fix deployed, monitoring.', publishToStatusPage: true }));
  });

  it('posts an internal update without a dialog, and publishes an existing one after confirmation', async () => {
    detailRoutes();
    renderDetail('SUPPORT_AGENT');
    const form = await screen.findByRole('form', { name: 'Compose stakeholder update' });
    await userEvent.type(within(form).getByLabelText(/Message/), 'Internal note for leadership');
    await userEvent.click(within(form).getByRole('button', { name: /Post update/ }));
    await waitFor(() => expect(last('POST', '/major-incidents/2/updates')?.body).toMatchObject({ publishToStatusPage: false }));

    await userEvent.click(screen.getByRole('button', { name: 'Publish “Cause identified” to the status page' }));
    const dialog = await screen.findByRole('dialog', { name: 'Publish this update to the status page?' });
    expect(last('POST', '/major-incidents/2/updates/8/publish')).toBeUndefined();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }));
    await waitFor(() => expect(last('POST', '/major-incidents/2/updates/8/publish')).toBeTruthy());
  });

  it('hides publishing and command controls without the permissions', async () => {
    detailRoutes(makeMajorDetail({ canPublish: false, canCommand: false, allowedTransitions: [] }));
    renderDetail('SUPPORT_AGENT');
    const form = await screen.findByRole('form', { name: 'Compose stakeholder update' });
    expect(within(form).queryByLabelText('Also publish on the public status page')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Publish “Cause identified”/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Move to monitoring' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Assign/ })).not.toBeInTheDocument();
  });

  it('is read-only for auditors', async () => {
    detailRoutes(makeMajorDetail({ canPublish: false, canCommand: false, allowedTransitions: [] }));
    renderDetail('AUDITOR');
    expect(await screen.findByRole('heading', { name: 'Payroll portal returns 503' })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Compose stakeholder update' })).not.toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Add timeline entry' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit details/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Status of Add a connection-pool alert/)).not.toBeInTheDocument();
  });

  it('adds a war-room timeline entry', async () => {
    detailRoutes();
    renderDetail('SUPPORT_AGENT');
    const form = await screen.findByRole('form', { name: 'Add timeline entry' });
    await userEvent.selectOptions(within(form).getByLabelText('Entry type'), 'DECISION');
    await userEvent.type(within(form).getByLabelText(/^Entry$/), 'Roll back the release');
    await userEvent.click(within(form).getByRole('button', { name: 'Add to timeline' }));
    await waitFor(() => expect(last('POST', '/major-incidents/2/timeline')?.body).toEqual({ entryType: 'DECISION', body: 'Roll back the release', occurredAt: null }));
  });

  it('moves the status and requires a resolution note when there is no summary', async () => {
    detailRoutes();
    renderDetail('INCIDENT_MANAGER');
    await userEvent.click(await screen.findByRole('button', { name: 'Mark resolved' }));
    const dialog = await screen.findByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Mark resolved' });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(/Resolution note/), 'Pool size raised');
    await userEvent.click(confirm);
    await waitFor(() => expect(last('POST', '/major-incidents/2/status')?.body).toEqual({ status: 'RESOLVED', note: 'Pool size raised' }));
  });

  it('assigns and completes action items', async () => {
    detailRoutes();
    renderDetail('SUPPORT_AGENT');
    await userEvent.selectOptions(await screen.findByLabelText('Status of Add a connection-pool alert'), 'DONE');
    await waitFor(() => expect(last('PUT', '/action-items/41')?.body).toMatchObject({ title: 'Add a connection-pool alert', status: 'DONE', ownerId: 21 }));
    await userEvent.click(screen.getByRole('button', { name: /Add action/ }));
    const card = screen.getByRole('group', { name: 'New action item' });
    await userEvent.type(within(card).getByLabelText(/^Action/), 'New follow-up');
    await userEvent.click(within(card).getByRole('button', { name: 'Add action' }));
    await waitFor(() => expect(last('POST', '/major-incidents/2/actions')?.body).toMatchObject({ title: 'New follow-up', actionType: 'FOLLOW_UP' }));
  });

  it('edits the command roles (0 clears a role)', async () => {
    detailRoutes();
    renderDetail('INCIDENT_MANAGER');
    await userEvent.click(await screen.findByRole('button', { name: /Assign/ }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.selectOptions(within(dialog).getByLabelText('Communications lead'), '0');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save roles' }));
    await waitFor(() => expect(last('PUT', '/major-incidents/2/roles')?.body).toEqual({ commanderId: 31, techLeadId: 21, commsLeadId: 0 }));
  });
});
