import { screen, waitFor } from '@testing-library/react';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import type { LoginResponse } from '../../lib/types';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { AuthProvider } from './AuthProvider';
import { ProtectedRoute } from './ProtectedRoute';

const SESSION: LoginResponse = {
  accessToken: 'access-1',
  expiresIn: 900,
  user: {
    id: 1,
    email: 'agent@nexus360.test',
    fullName: 'Agent Smith',
    role: 'SUPPORT_AGENT',
    departmentId: 2,
    departmentName: 'Network Operations',
    jobTitle: null,
    mustChangePassword: false,
    demo: false,
  },
};

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>;
}

function Incidents() {
  const { logout } = useAuth();
  return (
    <div>
      <h1>Incident register</h1>
      <button type="button" onClick={() => void logout()}>
        Sign out
      </button>
      <button type="button" onClick={() => void api.get('/incidents').catch(() => undefined)}>
        Load incidents
      </button>
    </div>
  );
}

function LoginButtons() {
  const { login, user } = useAuth();
  return (
    <div>
      <h1>Login page</h1>
      <p>{user ? `Signed in as ${user.fullName}` : 'Signed out'}</p>
      <button type="button" onClick={() => void login('agent@nexus360.test', 'Passw0rd123', true).catch(() => undefined)}>
        Sign in and remember
      </button>
      <button type="button" onClick={() => void login('agent@nexus360.test', 'Passw0rd123').catch(() => undefined)}>
        Sign in
      </button>
    </div>
  );
}

function renderLogin() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter initialEntries={['/login']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <LoginButtons />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

function renderApp(route: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter initialEntries={[route]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<h1>Login page</h1>} />
            <Route element={<ProtectedRoute />}>
              <Route path="/incidents" element={<Incidents />} />
            </Route>
          </Routes>
          <LocationProbe />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

afterEach(() => window.history.pushState({}, '', '/'));

describe('AuthProvider session endings', () => {
  it('lands on /login?reason=signed-out after logout', async () => {
    mockApi({ 'POST /auth/refresh': json(SESSION), 'POST /auth/logout': { status: 204 } });
    renderApp('/incidents');
    await screen.findByRole('heading', { name: 'Incident register' });
    await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }));
    await screen.findByRole('heading', { name: 'Login page' });
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/login\?reason=signed-out$/));
  });

  it('lands on /login?next=…&reason=expired when the session cannot be refreshed', async () => {
    let refreshes = 0;
    mockApi({
      'POST /auth/refresh': () => {
        refreshes += 1;
        return refreshes === 1 ? json(SESSION) : json(apiErrorBody(401, 'Refresh token revoked'), 401);
      },
      'GET /incidents': json(apiErrorBody(401, 'Expired'), 401),
    });
    window.history.pushState({}, '', '/incidents?status=OPEN');
    renderApp('/incidents?status=OPEN');
    await screen.findByRole('heading', { name: 'Incident register' });
    await userEvent.setup().click(screen.getByRole('button', { name: 'Load incidents' }));
    await screen.findByRole('heading', { name: 'Login page' });
    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(`/login?next=${encodeURIComponent('/incidents?status=OPEN')}&reason=expired`),
    );
  });
});

describe('AuthProvider sign-in', () => {
  it.each([
    ['Sign in and remember', true],
    ['Sign in', false],
  ])('sends rememberMe with POST /auth/login (%s)', async (button, rememberMe) => {
    mockApi({ 'POST /auth/refresh': json(apiErrorBody(401, 'No session'), 401), 'POST /auth/login': json(SESSION) });
    renderLogin();
    await userEvent.setup().click(await screen.findByRole('button', { name: button }));
    expect(await screen.findByText('Signed in as Agent Smith')).toBeInTheDocument();
    const loginRequest = requests.find((request) => request.method === 'POST' && request.url === '/auth/login');
    expect(loginRequest?.body).toEqual({ email: 'agent@nexus360.test', password: 'Passw0rd123', rememberMe });
  });
});
