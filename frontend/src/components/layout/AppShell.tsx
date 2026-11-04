import { Suspense, useCallback, useEffect, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { LogOut, ShieldAlert } from 'lucide-react';
import { useAuth } from '../../hooks/useAuth';
import { NotificationStreamContext, useNotificationStream } from '../../hooks/useNotificationStream';
import { pageTitle } from '../../lib/brand';
import { pageLabelFor } from '../../lib/nav';
import { ChangePasswordForm } from '../auth/ChangePasswordForm';
import { NewIncidentProvider } from '../incidents/NewIncidentContext';
import { SimulationBanner } from '../sim/SimulationParts';
import { LoadingState } from '../States';
import { useToast } from '../Toast';
import { BrandLockup } from './BrandMark';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';

/** Shown instead of the app while the account must set a new password. */
function ForcePasswordChange() {
  const { user, reloadUser, logout } = useAuth();
  const toast = useToast();
  return (
    <div className="auth-main min-h-screen">
      <section className="auth-card" aria-labelledby="force-password-title">
        <div className="brand mb-5 border-0 p-0">
          <BrandLockup />
        </div>
        <div className="callout mb-3">
          <ShieldAlert size={16} />
          <div>
            Your administrator requires you to set a new password before continuing{user ? `, ${user.fullName.split(' ')[0]}` : ''}.
          </div>
        </div>
        <h2 id="force-password-title">Set a new password</h2>
        <p>Choose a password you have not used before.</p>
        <ChangePasswordForm
          submitLabel="Set password and continue"
          onChanged={async () => {
            await reloadUser();
            toast.success('Password updated');
          }}
        />
        <button type="button" className="subtle-btn full" onClick={() => void logout()}>
          <LogOut size={15} /> Sign out
        </button>
      </section>
    </div>
  );
}

export function AppShell() {
  const { user } = useAuth();
  const [navOpen, setNavOpen] = useState(false);
  const closeNav = useCallback(() => setNavOpen(false), []);
  const location = useLocation();
  // One live notification stream per session (falls back to 30 s polling when unavailable).
  const stream = useNotificationStream({ enabled: Boolean(user && !user.mustChangePassword) });

  useEffect(() => {
    setNavOpen(false);
    window.scrollTo({ top: 0 });
  }, [location.pathname]);

  // Document title `SA | <page>` for every workspace route (public and auth pages set their own).
  useEffect(() => {
    document.title = pageTitle(pageLabelFor(location.pathname));
  }, [location.pathname]);

  if (!user) return null;
  if (user.mustChangePassword) return <ForcePasswordChange />;

  return (
    <NotificationStreamContext.Provider value={stream}>
    <NewIncidentProvider>
      <a href="#main-content" className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[100] gold-btn">
        Skip to content
      </a>
      <div className="app-shell">
        <Sidebar open={navOpen} onClose={closeNav} />
        <main className="main-area">
          <Topbar onMenu={() => setNavOpen(true)} />
          <section className="page-content" id="main-content" tabIndex={-1}>
            <SimulationBanner />
            <Suspense fallback={<LoadingState variant="panel" label="Loading page…" />}>
              <Outlet />
            </Suspense>
          </section>
        </main>
      </div>
    </NewIncidentProvider>
    </NotificationStreamContext.Provider>
  );
}
