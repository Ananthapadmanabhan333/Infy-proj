import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { InvitationDetails } from '../../lib/accountTypes';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, renderWithProviders } from '../../test/utils';
import RegisterPage from './RegisterPage';

const TOKEN = 'Vq3nY2xUaJk_8pWz-MbT9sQe1LrC4dHf6oNg0iKyA7E';

const INVITATION: InvitationDetails = {
  email: 'meera.nair@nexus360.test',
  fullName: 'Meera Nair',
  role: 'SUPPORT_AGENT',
  departmentName: 'IT Service Desk',
  organisationName: 'Acme Corporation',
  expiresAt: '2026-11-10T10:00:00Z',
};

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>;
}

function renderRegister(route: string) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
      </Routes>
      <LocationProbe />
    </>,
    { route, auth: makeAuth(null) },
  );
}

describe('RegisterPage — request access', () => {
  it('validates the request before sending it', async () => {
    renderRegister('/register');
    const user = userEvent.setup();
    expect(screen.getByRole('heading', { level: 1, name: 'Ask for an SA account' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Why do you need access?'), 'short');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(await screen.findByText('Enter your full name (at least 2 characters)')).toBeInTheDocument();
    expect(screen.getByText('Email is required')).toBeInTheDocument();
    expect(screen.getByText(/at least 10 characters\)$/)).toBeInTheDocument();
    expect(screen.getByLabelText('Full name')).toHaveFocus();
    expect(requests.some((request) => request.url === '/auth/access-requests')).toBe(false);
  });

  it('submits the request and shows the generic confirmation', async () => {
    let body: unknown;
    mockApi({
      'POST /auth/access-requests': (config) => {
        body = JSON.parse(String(config.data));
        return json({ message: 'If the request can be processed, an administrator will review it.' }, 202);
      },
    });
    renderRegister('/register');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Full name'), 'Meera Nair');
    await user.type(screen.getByLabelText('Work e-mail'), ' meera.nair@nexus360.test ');
    await user.type(screen.getByLabelText('Department'), 'IT Service Desk');
    await user.type(screen.getByLabelText('Why do you need access?'), 'I join the service desk team on Monday and need to work tickets.');
    await user.click(screen.getByRole('button', { name: 'Send request' }));

    const heading = await screen.findByRole('heading', { level: 1, name: /your request is in/ });
    expect(heading).toHaveFocus();
    expect(body).toEqual({
      fullName: 'Meera Nair',
      email: 'meera.nair@nexus360.test',
      department: 'IT Service Desk',
      jobTitle: null,
      reason: 'I join the service desk team on Monday and need to work tickets.',
    });
    expect(screen.getByText(/administrator will review your request/)).toBeInTheDocument();
    expect(screen.getByText(/personal, single-use invitation link through a trusted channel/)).toBeInTheDocument();
    expect(screen.getByText(/If e-mail is configured for this workspace, it is also e-mailed to you/)).toBeInTheDocument();
  });

  it('maps API field errors and explains rate limiting', async () => {
    let calls = 0;
    mockApi({
      'POST /auth/access-requests': () => {
        calls += 1;
        return calls === 1
          ? json(apiErrorBody(400, 'Validation failed', [{ field: 'email', message: 'Use your corporate address' }]), 400)
          : { status: 429, data: { message: 'Too many requests' }, headers: { 'retry-after': '30' } };
      },
    });
    renderRegister('/register');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Full name'), 'Meera Nair');
    await user.type(screen.getByLabelText('Work e-mail'), 'meera@gmail.example');
    await user.type(screen.getByLabelText('Why do you need access?'), 'Service desk onboarding next week.');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(await screen.findByText('Use your corporate address')).toBeInTheDocument();
    expect(screen.getByLabelText('Work e-mail')).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(await screen.findByText('Too many attempts. Try again in 30 seconds.')).toBeInTheDocument();
  });
});

describe('RegisterPage — invitation', () => {
  it('validates the invitation, removes the token from the address bar and shows its details', async () => {
    mockApi({ [`GET /auth/invitations/${TOKEN}`]: json(INVITATION) });
    renderRegister(`/register?invite=${TOKEN}`);
    expect(screen.getByText(/Checking your invitation/)).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 1, name: 'Set up your account' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/register$/));

    const details = screen.getByLabelText('Invitation details');
    expect(within(details).getByText('meera.nair@nexus360.test')).toBeInTheDocument();
    expect(within(details).getByText('Support Agent')).toBeInTheDocument();
    expect(within(details).getByText('IT Service Desk')).toBeInTheDocument();
    expect(within(details).getByText('Acme Corporation')).toBeInTheDocument();
    expect(within(details).getByText(/2026/)).toBeInTheDocument();
    expect(screen.getByLabelText('Full name')).toHaveValue('Meera Nair');
  });

  it('accepts an invitation without a pre-set name and asks for one', async () => {
    mockApi({ [`GET /auth/invitations/${TOKEN}`]: json({ ...INVITATION, fullName: null }) });
    renderRegister(`/register?invite=${TOKEN}`);
    const user = userEvent.setup();
    const name = await screen.findByLabelText('Full name');
    expect(name).toHaveValue('');
    await user.type(screen.getByLabelText('Password'), 'Welcome2026!');
    await user.type(screen.getByLabelText('Confirm password'), 'Welcome2026!');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Enter your full name (at least 2 characters)')).toBeInTheDocument();
    expect(name).toHaveFocus();
  });

  it('shows the live password policy and blocks a password containing the e-mail name', async () => {
    mockApi({ [`GET /auth/invitations/${TOKEN}`]: json(INVITATION) });
    renderRegister(`/register?invite=${TOKEN}`);
    const user = userEvent.setup();
    const password = await screen.findByLabelText('Password');
    const checklist = screen.getByRole('list', { name: 'Password requirements' });
    expect(password.getAttribute('aria-describedby')).toContain(checklist.id);
    expect(within(checklist).getByText(/At least 10 characters/).closest('li')).not.toHaveClass('met');

    await user.type(password, 'meera.nair2026x');
    expect(within(checklist).getByText(/At least 10 characters/).closest('li')).toHaveClass('met');
    expect(within(checklist).getByText(/At least one digit/).closest('li')).toHaveClass('met');
    expect(within(checklist).getByText(/Does not contain your e-mail name/).closest('li')).not.toHaveClass('met');

    await user.type(screen.getByLabelText('Confirm password'), 'meera.nair2026x');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Password must not contain your e-mail name')).toBeInTheDocument();
    expect(requests.some((request) => request.url === '/auth/register')).toBe(false);
  });

  it('shows password policy errors returned by the API on the password field', async () => {
    let body: unknown;
    mockApi({
      [`GET /auth/invitations/${TOKEN}`]: json(INVITATION),
      'POST /auth/register': (config) => {
        body = JSON.parse(String(config.data));
        return json(apiErrorBody(400, 'Validation failed', [{ field: 'password', message: 'Password is too common' }]), 400);
      },
    });
    renderRegister(`/register?invite=${TOKEN}`);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Password'), 'Welcome2026!');
    await user.type(screen.getByLabelText('Confirm password'), 'Welcome2026!');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    const error = await screen.findByText('Password is too common');
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-describedby', expect.stringContaining(error.id));
    expect(screen.getByLabelText('Password')).toHaveFocus();
    // The token travels in the body, never in the URL.
    expect(body).toEqual({ token: TOKEN, fullName: 'Meera Nair', password: 'Welcome2026!' });
  });

  it('creates the account and offers sign-in with the e-mail prefilled', async () => {
    mockApi({
      [`GET /auth/invitations/${TOKEN}`]: json(INVITATION),
      'POST /auth/register': json({ email: INVITATION.email }, 201),
    });
    renderRegister(`/register?invite=${TOKEN}`);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Password'), 'Welcome2026!');
    await user.type(screen.getByLabelText('Confirm password'), 'Welcome2026!');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Your account is ready' })).toHaveFocus();
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', `/login?email=${encodeURIComponent(INVITATION.email)}&reason=registered`);
  });

  it('shows one generic message for an invalid or expired invitation', async () => {
    mockApi({ [`GET /auth/invitations/${TOKEN}`]: json(apiErrorBody(404, 'This link is invalid or has expired. Ask an administrator for a new one.'), 404) });
    renderRegister(`/register?invite=${TOKEN}`);
    const heading = await screen.findByRole('heading', { level: 1, name: 'This invitation link is invalid or has expired' });
    expect(heading).toHaveFocus();
    
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/register$/);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Request access instead' }));
    expect(screen.getByRole('heading', { level: 1, name: 'Ask for an SA account' })).toBeInTheDocument();
  });

  it('switches to the invalid state when the invitation is used up before submitting', async () => {
    mockApi({
      [`GET /auth/invitations/${TOKEN}`]: json(INVITATION),
      'POST /auth/register': json(apiErrorBody(404, 'This link is invalid or has expired. Ask an administrator for a new one.'), 404),
    });
    renderRegister(`/register?invite=${TOKEN}`);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Password'), 'Welcome2026!');
    await user.type(screen.getByLabelText('Confirm password'), 'Welcome2026!');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'This invitation link is invalid or has expired' })).toBeInTheDocument();
  });

  it('does not call the API for an empty token', () => {
    renderRegister('/register?invite=');
    expect(screen.getByRole('heading', { level: 1, name: 'This invitation link is invalid or has expired' })).toBeInTheDocument();
    expect(requests.some((request) => request.url.startsWith('/auth/invitations'))).toBe(false);
  });
});
