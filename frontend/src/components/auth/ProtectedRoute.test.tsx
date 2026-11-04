import { screen } from '@testing-library/react';
import { Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import { ProtectedRoute } from './ProtectedRoute';

function LoginProbe() {
  const location = useLocation();
  return <div>Login screen {location.search}</div>;
}

function renderRoutes(route: string, auth = makeAuth(null)) {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginProbe />} />
      <Route
        path="/admin/configuration"
        element={
          <ProtectedRoute roles={['SYSTEM_ADMIN']}>
            <div>Configuration content</div>
          </ProtectedRoute>
        }
      />
      <Route element={<ProtectedRoute />}>
        <Route path="/incidents" element={<div>Incident register</div>} />
      </Route>
    </Routes>,
    { route, auth },
  );
}

describe('ProtectedRoute', () => {
  it('redirects unauthenticated users to /login with the original destination', () => {
    renderRoutes('/incidents?tab=open');
    expect(screen.getByText(/Login screen/)).toHaveTextContent('?next=%2Fincidents%3Ftab%3Dopen');
    expect(screen.queryByText('Incident register')).not.toBeInTheDocument();
  });

  it('uses the session-end redirect (expiry / sign-out) chosen by the auth provider', () => {
    renderRoutes('/incidents', makeAuth(null, { sessionEndRedirect: '/login?reason=signed-out' }));
    expect(screen.getByText(/Login screen/)).toHaveTextContent('?reason=signed-out');
    expect(screen.getByText(/Login screen/)).not.toHaveTextContent('next=');
  });

  it('shows the branded loader while the session is being restored', () => {
    renderRoutes('/incidents', makeAuth(null, { status: 'loading' }));
    expect(screen.getByRole('status')).toHaveTextContent('SA');
    expect(screen.queryByText(/Login screen/)).not.toBeInTheDocument();
  });

  it('renders a 403 panel when the role is not allowed', () => {
    renderRoutes('/admin/configuration', makeAuth(makeUser('AUDITOR')));
    expect(screen.getByRole('heading', { name: 'Access restricted' })).toBeInTheDocument();
    expect(screen.getByText(/Your role \(Auditor\) does not have access/)).toBeInTheDocument();
    expect(screen.queryByText('Configuration content')).not.toBeInTheDocument();
  });

  it('renders the page for an allowed role', () => {
    renderRoutes('/admin/configuration', makeAuth(makeUser('SYSTEM_ADMIN')));
    expect(screen.getByText('Configuration content')).toBeInTheDocument();
  });

  it('allows any authenticated role through an unrestricted layout route', () => {
    renderRoutes('/incidents', makeAuth(makeUser('EMPLOYEE')));
    expect(screen.getByText('Incident register')).toBeInTheDocument();
  });
});
