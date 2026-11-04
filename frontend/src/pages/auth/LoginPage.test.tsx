import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiRequestError } from '../../lib/api';
import { json, mockApi } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import LoginPage, { safeNext } from './LoginPage';
import LegacyLoginPage, { safeNext as legacySafeNext } from '../LoginPage';

function apiError(status: number, message: string, retryAfterSeconds?: number) {
  return new ApiRequestError({ status, error: 'Error', message, path: '/api/auth/login', timestamp: '2026-11-03T10:00:00Z', retryAfterSeconds });
}

async function submit(email = 'ada@nexus360.test', password = 'Passw0rd123') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Email'), email);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  return user;
}

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>;
}

function renderWithRoutes(route: string, auth = makeAuth(null)) {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="*" element={<LocationProbe />} />
    </Routes>,
    { route, auth },
  );
}

describe('LoginPage', () => {
  it('shows the API error message for invalid credentials', async () => {
    const login = vi.fn().mockRejectedValue(apiError(401, 'Invalid email or password'));
    renderWithProviders(<LoginPage />, { route: '/login', auth: makeAuth(null, { login }) });
    await submit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
    expect(login).toHaveBeenCalledWith('ada@nexus360.test', 'Passw0rd123', false);
  });

  it('sends rememberMe when "Remember me" is ticked (unticked by default)', async () => {
    const login = vi.fn().mockResolvedValue(makeUser('SUPPORT_AGENT'));
    renderWithRoutes('/login', makeAuth(null, { login }));
    const remember = screen.getByRole('checkbox', { name: 'Remember me' });
    expect(remember).not.toBeChecked();
    expect(remember).toHaveAccessibleDescription(/Keeps you signed in on this device/);
    const user = userEvent.setup();
    await user.click(remember);
    await submit();
    expect(login).toHaveBeenCalledWith('ada@nexus360.test', 'Passw0rd123', true);
    expect(await screen.findByTestId('location')).toHaveTextContent(/^\/dashboard$/);
  });

  it('shows the redesigned split screen: SA brand panel with an illustrative visual and the form', () => {
    renderWithProviders(<LoginPage />, { route: '/login', auth: makeAuth(null) });
    const brand = screen.getByRole('complementary', { name: 'About SA' });
    expect(brand).toHaveTextContent('Enterprise Operations');
    expect(brand).toHaveTextContent('Intelligent Service Management. Unified Operations.');
    expect(within(brand).getByRole('figure', { name: /Illustrative preview/ })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'SA — Enterprise Operations Platform, home' })[0]).toHaveAttribute('href', '/');
    expect(screen.getByRole('heading', { level: 1, name: 'Sign in to SA' })).toBeInTheDocument();
    expect(document.title).toBe('SA | Sign in');
  });

  it('explains a locked account (423)', async () => {
    const login = vi.fn().mockRejectedValue(apiError(423, 'Account locked for 15 minutes after 5 failed attempts'));
    renderWithProviders(<LoginPage />, { route: '/login', auth: makeAuth(null, { login }) });
    await submit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Account locked for 15 minutes');
  });

  it('explains rate limiting (429) with the retry delay', async () => {
    const login = vi.fn().mockRejectedValue(apiError(429, 'Too many requests', 42));
    renderWithProviders(<LoginPage />, { route: '/login', auth: makeAuth(null, { login }) });
    await submit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 42 seconds');
  });

  it('validates the form before calling the API and focuses the first invalid field', async () => {
    const login = vi.fn();
    renderWithProviders(<LoginPage />, { route: '/login', auth: makeAuth(null, { login }) });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Email is required')).toBeInTheDocument();
    expect(screen.getByText('Password is required')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveFocus();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-describedby', expect.stringContaining('-error'));
    expect(login).not.toHaveBeenCalled();
  });

  it('toggles password visibility with an accessible pressed button', async () => {
    renderWithProviders(<LoginPage />, { route: '/login', auth: makeAuth(null) });
    const user = userEvent.setup();
    const input = screen.getByLabelText('Password');
    const toggle = screen.getByRole('button', { name: 'Show password' });
    expect(input).toHaveAttribute('type', 'password');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(toggle).toHaveAttribute('aria-controls', input.id);
    await user.click(toggle);
    expect(input).toHaveAttribute('type', 'text');
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await user.click(toggle);
    expect(input).toHaveAttribute('type', 'password');
  });

  it('links to password recovery and the access request form', () => {
    renderWithProviders(<LoginPage />, { route: '/login', auth: makeAuth(null) });
    expect(screen.getByRole('link', { name: 'Forgot password?' })).toHaveAttribute('href', '/forgot-password');
    expect(screen.getByRole('link', { name: 'Request access' })).toHaveAttribute('href', '/register');
    expect(screen.getByRole('link', { name: /Back to home/ })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: 'Privacy' })).toHaveAttribute('href', '/privacy');
    expect(screen.getByRole('link', { name: 'Terms' })).toHaveAttribute('href', '/terms');
  });

  it.each([
    ['expired', 'Your session expired. Please sign in again.'],
    ['signed-out', 'You have signed out.'],
  ])('shows the notice for ?reason=%s', (reason, text) => {
    renderWithProviders(<LoginPage />, { route: `/login?reason=${reason}`, auth: makeAuth(null) });
    expect(screen.getByRole('status')).toHaveTextContent(text);
    // Notices are not alerts, so a later sign-in error remains the only alert on the page.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('ignores unknown reasons', () => {
    renderWithProviders(<LoginPage />, { route: '/login?reason=<script>', auth: makeAuth(null) });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('returns to the safe next destination after signing in', async () => {
    const login = vi.fn().mockResolvedValue(makeUser('SUPPORT_AGENT'));
    renderWithRoutes(`/login?next=${encodeURIComponent('/incidents/4?tab=sla')}&reason=expired`, makeAuth(null, { login }));
    await submit();
    expect(await screen.findByTestId('location')).toHaveTextContent('/incidents/4?tab=sla');
  });

  it('goes to /dashboard when next is missing or unsafe', async () => {
    const login = vi.fn().mockResolvedValue(makeUser('SUPPORT_AGENT'));
    renderWithRoutes('/login?next=//evil.example.com', makeAuth(null, { login }));
    await submit();
    expect(await screen.findByTestId('location')).toHaveTextContent(/^\/dashboard$/);
  });

  it('prefills a valid ?email= and focuses the password', async () => {
    renderWithProviders(<LoginPage />, { route: '/login?email=meera%40nexus360.test&reason=registered', auth: makeAuth(null) });
    expect(screen.getByLabelText('Email')).toHaveValue('meera@nexus360.test');
    await waitFor(() => expect(screen.getByLabelText('Password')).toHaveFocus());
    expect(screen.getByRole('status')).toHaveTextContent('Your account is ready');
  });

  it('lists demo accounts when enabled and fills the email on click', async () => {
    mockApi({
      'GET /auth/demo-accounts': json({
        enabled: true,
        password: null,
        accounts: [{ email: 'manager@demo.nexus360.test', fullName: 'Maya Manager', role: 'IT_MANAGER', departmentName: 'Service Management' }],
      }),
    });
    renderWithProviders(<LoginPage />, { route: '/login', auth: makeAuth(null) });
    expect(await screen.findByRole('heading', { name: 'Demo accounts' })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: /Maya Manager/ }));
    expect(screen.getByLabelText('Email')).toHaveValue('manager@demo.nexus360.test');
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('hides the demo list when demo accounts are disabled', async () => {
    mockApi({ 'GET /auth/demo-accounts': json({ enabled: false, password: null, accounts: [] }) });
    renderWithProviders(<LoginPage />, { route: '/login', auth: makeAuth(null) });
    await screen.findByRole('button', { name: 'Sign in' });
    expect(screen.queryByRole('heading', { name: 'Demo accounts' })).not.toBeInTheDocument();
  });

  it('sends a signed-in visitor to next (or /dashboard) instead of rendering the form', async () => {
    renderWithRoutes('/login', makeAuth(makeUser('SUPPORT_AGENT')));
    expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeInTheDocument();
    expect(await screen.findByTestId('location')).toHaveTextContent(/^\/dashboard$/);
  });

  it('only accepts in-app relative redirect targets', () => {
    expect(safeNext('/incidents/4')).toBe('/incidents/4');
    expect(safeNext('/users?tab=access-requests')).toBe('/users?tab=access-requests');
    expect(safeNext('//evil.example.com')).toBe('/dashboard');
    expect(safeNext('/\\evil.example.com')).toBe('/dashboard');
    expect(safeNext('https://evil.example.com')).toBe('/dashboard');
    expect(safeNext('/login?next=/x')).toBe('/dashboard');
    expect(safeNext('/reset-password?token=abc')).toBe('/dashboard');
    expect(safeNext('/')).toBe('/dashboard');
    expect(safeNext(null)).toBe('/dashboard');
  });

  it('keeps the old pages/LoginPage import path working', () => {
    expect(LegacyLoginPage).toBe(LoginPage);
    expect(legacySafeNext).toBe(safeNext);
  });
});
