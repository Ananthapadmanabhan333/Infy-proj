import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, renderWithProviders } from '../../test/utils';
import ForgotPasswordPage from './ForgotPasswordPage';
import ResetPasswordPage from './ResetPasswordPage';

const TOKEN = 'r3S3t-T0ken_abcdefghijklmnopqrstuvwxyz0123456';

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>;
}

function renderPage(route: string) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
      </Routes>
      <LocationProbe />
    </>,
    { route, auth: makeAuth(null) },
  );
}

describe('ForgotPasswordPage', () => {
  it('records the request and shows the generic administrator hand-over explanation', async () => {
    let body: unknown;
    mockApi({
      'POST /auth/forgot-password': (config) => {
        body = JSON.parse(String(config.data));
        return json({ message: 'If an account exists, an administrator will be notified.' }, 202);
      },
      'GET /public/info': json({ organisationName: 'Acme', supportEmail: 'it-help@acme.test', version: '2.1.0' }),
    });
    renderPage('/forgot-password');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Email'), 'ada@nexus360.test');
    await user.click(screen.getByRole('button', { name: 'Request a reset link' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'An administrator will help you' })).toHaveFocus();
    expect(body).toEqual({ email: 'ada@nexus360.test' });
    expect(screen.getByText(/If an active account exists for that address/)).toBeInTheDocument();
    expect(screen.getByText(/the link is also e-mailed to you; otherwise no message arrives/)).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'it-help@acme.test' })).toHaveAttribute('href', 'mailto:it-help@acme.test');
  });

  it('validates the e-mail and reports rate limiting', async () => {
    mockApi({ 'POST /auth/forgot-password': { status: 429, data: { message: 'Too many requests' } } });
    renderPage('/forgot-password');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Request a reset link' }));
    expect(await screen.findByText('Email is required')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Email'), 'ada@nexus360.test');
    await user.click(screen.getByRole('button', { name: 'Request a reset link' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Please wait a minute and try again.');
  });
});

describe('ResetPasswordPage', () => {
  it('validates the link, hides the token and sets the new password', async () => {
    let body: unknown;
    mockApi({
      [`GET /auth/reset-password/${TOKEN}`]: json({ email: 'ada@nexus360.test', expiresAt: '2026-11-03T12:00:00Z' }),
      'POST /auth/reset-password': (config) => {
        body = JSON.parse(String(config.data));
        return { status: 204 };
      },
    });
    renderPage(`/reset-password?token=${TOKEN}`);
    const user = userEvent.setup();
    const password = await screen.findByLabelText('New password');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/reset-password$/));
    expect(screen.getByText('ada@nexus360.test')).toBeInTheDocument();

    await user.type(password, 'Brighter2026');
    await user.type(screen.getByLabelText('Confirm new password'), 'Brighter2027');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
    expect(screen.getByLabelText('Confirm new password')).toHaveFocus();

    await user.clear(screen.getByLabelText('Confirm new password'));
    await user.type(screen.getByLabelText('Confirm new password'), 'Brighter2026');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Your new password is set' })).toHaveFocus();
    expect(body).toEqual({ token: TOKEN, newPassword: 'Brighter2026' });
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login?email=ada%40nexus360.test&reason=password-reset');
  });

  it('maps policy errors from the API onto the new password field', async () => {
    mockApi({
      [`GET /auth/reset-password/${TOKEN}`]: json({ email: 'ada@nexus360.test', expiresAt: '2026-11-03T12:00:00Z' }),
      'POST /auth/reset-password': json(apiErrorBody(400, 'Validation failed', [{ field: 'newPassword', message: 'Password was used recently' }]), 400),
    });
    renderPage(`/reset-password?token=${TOKEN}`);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('New password'), 'Brighter2026');
    await user.type(screen.getByLabelText('Confirm new password'), 'Brighter2026');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(await screen.findByText('Password was used recently')).toBeInTheDocument();
    expect(screen.getByLabelText('New password')).toHaveFocus();
  });

  it('shows a generic invalid-link message with a way to request a new link', async () => {
    mockApi({ [`GET /auth/reset-password/${TOKEN}`]: json(apiErrorBody(404, 'Reset token expired'), 404) });
    renderPage(`/reset-password?token=${TOKEN}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'This reset link is invalid or has expired' })).toBeInTheDocument();
    expect(screen.queryByText('Reset token expired')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Request a new link' })).toHaveAttribute('href', '/forgot-password');
  });

  it('treats a missing token as invalid without calling the API', () => {
    renderPage('/reset-password');
    expect(screen.getByRole('heading', { level: 1, name: 'This reset link is invalid or has expired' })).toBeInTheDocument();
    expect(requests).toHaveLength(0);
  });

  it('offers a retry instead of "invalid" when the API is unreachable', async () => {
    mockApi({});
    renderPage(`/reset-password?token=${TOKEN}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'We could not check this link' })).toBeInTheDocument();
    mockApi({ [`GET /auth/reset-password/${TOKEN}`]: json({ email: 'ada@nexus360.test', expiresAt: '2026-11-03T12:00:00Z' }) });
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByLabelText('New password')).toBeInTheDocument();
  });
});
