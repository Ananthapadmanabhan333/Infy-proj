import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { makeApprovalDetail, makeFlowApproval, makeTask, pageOf } from '../../test/itsmFixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import ApprovalsPage from './ApprovalsPage';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

function setup(extra: Record<string, ReturnType<typeof json>> = {}) {
  mockApi({
    'GET /approvals/counts': json({ pendingTasks: 1, overdueTasks: 0, requestedPending: 2 }),
    'GET /approvals/tasks': json(pageOf([makeTask()])),
    'GET /approvals': json(pageOf([makeFlowApproval({ id: 802, title: 'Laptop for Sam', type: 'SERVICE_REQUEST', canDecide: false })])),
    'GET /approvals/801': json(makeApprovalDetail()),
    'POST /approvals/tasks/9002/decision': json(makeFlowApproval({ status: 'APPROVED' })),
    'POST /approvals/tasks/9002/delegate': json(makeFlowApproval()),
    'GET /approvals/delegations': json([]),
    'GET /service-requests/user-options': json([{ id: 55, fullName: 'Priya Raman', departmentName: 'Finance', role: 'IT_MANAGER' }]),
    ...extra,
  });
}

describe('ApprovalsPage', () => {
  it('lists my tasks across subject types and approves one from the inbox', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />, { route: '/approvals', auth: makeAuth(makeUser('IT_MANAGER')) });

    const list = await screen.findByRole('list', { name: 'Tasks waiting for me' });
    expect(within(list).getByText('Change CHG-1012: Payroll pool fix')).toBeInTheDocument();
    expect(within(list).getByText(/Stage 2: Change Advisory Board · as IT Manager/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Waiting for me/ })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /All approvals/ })).not.toBeInTheDocument();

    await user.click(within(list).getByRole('button', { name: 'Approve Change CHG-1012: Payroll pool fix' }));
    const dialog = await screen.findByRole('dialog', { name: /Approve CHG-1012/ });
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(last('POST', '/approvals/tasks/9002/decision')?.body).toEqual({ approved: true }));
    expect(last('GET', '/approvals/tasks')?.params).toMatchObject({ status: 'PENDING' });
  });

  it('requires a reason to reject and shows the server message', async () => {
    setup({ 'POST /approvals/tasks/9002/decision': { status: 403, data: apiErrorBody(403, 'Segregation of duties: you cannot approve your own request') } });
    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />, { route: '/approvals', auth: makeAuth(makeUser('IT_MANAGER')) });

    await user.click(await screen.findByRole('button', { name: 'Reject Change CHG-1012: Payroll pool fix' }));
    const dialog = await screen.findByRole('dialog', { name: /Reject CHG-1012/ });
    const confirm = within(dialog).getByRole('button', { name: 'Reject' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/Reason for rejecting/), 'Too risky this week');
    await user.click(confirm);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Segregation of duties');
    expect(last('POST', '/approvals/tasks/9002/decision')?.body).toEqual({ approved: false, comment: 'Too risky this week' });
  });

  it('opens the flow with stages, quorum, decisions and history, and delegates my task', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />, { route: '/approvals?approval=801&task=9002', auth: makeAuth(makeUser('IT_MANAGER')) });

    const drawer = await screen.findByRole('dialog', { name: /Change CHG-1012: Payroll pool fix/ });
    const cab = await within(drawer).findByRole('listitem', { name: 'Stage 2: Change Advisory Board' });
    expect(within(cab).getByText(/Quorum: 2 of 2/)).toBeInTheDocument();
    expect(within(cab).getByText(/Only when changeRisk = HIGH/)).toBeInTheDocument();
    expect(within(drawer).getByText(/Approved by Cara CAB/)).toBeInTheDocument();
    expect(within(drawer).getByRole('link', { name: /Open CHG-1012/ })).toHaveAttribute('href', '/changes/12');

    await user.click(within(drawer).getByRole('button', { name: /Delegate/ }));
    const modal = await screen.findByRole('dialog', { name: 'Delegate this approval task' });
    await user.type(within(modal).getByLabelText('Delegate to'), 'Pri');
    await user.click(await within(modal).findByRole('option', { name: /Priya Raman/ }));
    await user.click(within(modal).getByRole('button', { name: 'Delegate' }));
    await waitFor(() => expect(last('POST', '/approvals/tasks/9002/delegate')?.body).toEqual({ userId: 55 }));
  });

  it('shows my requests and, for admins and auditors, all approvals', async () => {
    setup();
    renderWithProviders(<ApprovalsPage />, { route: '/approvals?tab=requested', auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByText('Laptop for Sam')).toBeInTheDocument();
    expect(last('GET', '/approvals')?.params).toMatchObject({ scope: 'requested' });
    expect(screen.getByRole('tab', { name: /All approvals/ })).toBeInTheDocument();
  });

  it('links staff and auditors to the approval flows, not employees', async () => {
    setup();
    const { unmount } = renderWithProviders(<ApprovalsPage />, { route: '/approvals', auth: makeAuth(makeUser('AUDITOR')) });
    expect(await screen.findByRole('link', { name: /Approval flows/ })).toHaveAttribute('href', '/approvals/flows');
    unmount();
    renderWithProviders(<ApprovalsPage />, { route: '/approvals', auth: makeAuth(makeUser('EMPLOYEE')) });
    await screen.findByRole('heading', { name: 'Approvals' });
    expect(screen.queryByRole('link', { name: /Approval flows/ })).not.toBeInTheDocument();
  });
});
