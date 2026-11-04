import { screen, waitFor } from '@testing-library/react';
import { Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AppRoutes, LEGACY_REDIRECTS, LegacyRedirect } from './App';
import { makeAuth, makeUser, renderWithProviders } from './test/utils';

vi.mock('./components/layout/AppShell', () => ({ AppShell: () => <Outlet /> }));
vi.mock('./pages/CommandCenter', () => ({ default: () => <h1>Command Center page</h1> }));
vi.mock('./pages/admin/SettingsPage', () => ({ default: () => <h1>Settings page</h1> }));
vi.mock('./pages/CatalogPage', () => ({ default: () => <h1>Service Catalogue page</h1> }));

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{`${location.pathname}${location.search}`}</output>;
}

function renderRedirect(route: string) {
  const { unmount } = renderWithProviders(
    <Routes>
      {LEGACY_REDIRECTS.map(([from, to]) => (
        <Route key={from} path={from} element={<LegacyRedirect to={to} />} />
      ))}
      <Route path="*" element={<LocationProbe />} />
    </Routes>,
    { route },
  );
  const location = screen.getByTestId('location').textContent;
  unmount();
  return location;
}

describe('Release 2 routes', () => {
  it.each([
    ['/sla', '/sla-monitor'],
    ['/ai', '/sa-ai'],
    ['/nexus-ai', '/sa-ai'],
    ['/workflows', '/automation'],
    ['/workflows/12', '/automation/12'],
    ['/workflows/new', '/automation/new'],
    ['/admin/users', '/users'],
    ['/admin/audit-logs', '/audit-logs'],
    ['/admin/configuration', '/settings'],
    ['/forecast', '/analytics?tab=operations'],
    ['/solutions', '/knowledge?type=SOLUTION'],
  ])('redirects %s to %s', (from, to) => {
    expect(renderRedirect(from)).toBe(to);
  });

  it('keeps the original query string when redirecting', () => {
    expect(renderRedirect('/ai?q=payroll')).toBe('/sa-ai?q=payroll');
    expect(renderRedirect('/forecast?period=30')).toBe('/analytics?period=30&tab=operations');
    expect(renderRedirect('/admin/users?search=priya')).toBe('/users?search=priya');
  });

  it('shows the public landing page at / to guests (no redirect to /dashboard)', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    renderWithProviders(<AppRoutes />, { route: '/', auth: makeAuth(null) });
    expect(await screen.findByRole('heading', { level: 1, name: /Complex Operations\.\s*Under Control\./ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Login' })).toHaveAttribute('href', '/login');
    expect(screen.queryByRole('heading', { name: 'Command Center page' })).not.toBeInTheDocument();
  });

  it('shows the landing page at / to signed-in users too, with Open Workspace', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    renderWithProviders(<AppRoutes />, { route: '/', auth: makeAuth(makeUser('EMPLOYEE')) });
    expect(await screen.findByRole('heading', { level: 1, name: /Complex Operations\.\s*Under Control\./ })).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /Open Workspace/ })[0]).toHaveAttribute('href', '/dashboard');
    expect(screen.queryByRole('link', { name: 'Login' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Command Center page' })).not.toBeInTheDocument();
  });

  it.each(['/docs', '/support', '/privacy', '/terms'])('serves %s publicly', async (route) => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    renderWithProviders(<AppRoutes />, { route, auth: makeAuth(null) });
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });

  it('guards role-restricted pages with the role groups', async () => {
    renderWithProviders(<AppRoutes />, { route: '/settings', auth: makeAuth(makeUser('IT_MANAGER')) });
    expect(await screen.findByRole('heading', { name: 'Access restricted' })).toBeInTheDocument();
    expect(screen.getByText(/Your role \(IT Manager\) does not have access/)).toBeInTheDocument();
  });

  it('lets configuration admins into Settings', async () => {
    renderWithProviders(<AppRoutes />, { route: '/settings', auth: makeAuth(makeUser('IT_ADMIN')) });
    expect(await screen.findByRole('heading', { name: 'Settings page' })).toBeInTheDocument();
  });

  it('serves /catalog as a real page again (no redirect to the Service Desk tab)', async () => {
    expect(LEGACY_REDIRECTS.map(([from]) => from)).not.toContain('/catalog');
    renderWithProviders(
      <>
        <AppRoutes />
        <LocationProbe />
      </>,
      { route: '/catalog?category=3', auth: makeAuth(makeUser('EMPLOYEE')) },
    );
    expect(await screen.findByRole('heading', { name: 'Service Catalogue page' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/catalog?category=3');
  });

  it.each(['/status', '/status/confirm?token=abc', '/status/unsubscribe?token=abc'])('serves %s publicly', async (route) => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    renderWithProviders(<AppRoutes />, { route, auth: makeAuth(null) });
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });

  it('redirects anonymous visitors of an old path to login with the new destination', async () => {
    renderWithProviders(
      <>
        <AppRoutes />
        <LocationProbe />
      </>,
      { route: '/sla', auth: makeAuth(null) },
    );
    // /login is a lazy chunk, so the redirect settles asynchronously.
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe(`/login?next=${encodeURIComponent('/sla-monitor')}`));
  });
});
