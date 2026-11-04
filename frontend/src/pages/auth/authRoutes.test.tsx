import { Suspense } from 'react';
import { screen } from '@testing-library/react';
import { Outlet, useLocation } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../App';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';

vi.mock('../../components/layout/AppShell', () => ({ AppShell: () => <Outlet /> }));
vi.mock('../CommandCenter', () => ({ default: () => <h1>Command Center page</h1> }));

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>;
}

function renderApp(route: string, auth = makeAuth(null)) {
  return renderWithProviders(
    <Suspense fallback={<p>Loading route…</p>}>
      <AppRoutes />
      <LocationProbe />
    </Suspense>,
    { route, auth },
  );
}

describe('public account routes', () => {
  it.each([
    ['/register', 'Ask for an SA account'],
    ['/forgot-password', 'Forgot your password?'],
    ['/reset-password', 'This reset link is invalid or has expired'],
    ['/login', 'Sign in to SA'],
  ])('%s is reachable without a session', async (route, heading) => {
    renderApp(route);
    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument();
  });

  it.each(['/login', '/register', '/register?invite=abc', '/forgot-password', '/reset-password?token=abc'])(
    'sends a signed-in user from %s to /dashboard',
    async (route) => {
      renderApp(route, makeAuth(makeUser('EMPLOYEE')));
      expect(await screen.findByRole('heading', { name: 'Command Center page' })).toBeInTheDocument();
      expect(screen.getByTestId('location')).toHaveTextContent(/^\/dashboard$/);
    },
  );
});
