import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Lock } from 'lucide-react';
import { useAuth } from '../../hooks/useAuth';
import { ROLE_LABELS } from '../../lib/constants';
import type { Role } from '../../lib/types';
import { BrandLoader } from '../BrandLoader';

export function Forbidden({ role }: { role?: Role }) {
  const navigate = useNavigate();
  return (
    <div className="page-stack">
      <section className="panel placeholder-panel" role="alert" aria-labelledby="forbidden-title">
        <div className="placeholder-icon">
          <Lock size={28} />
        </div>
        <h2 id="forbidden-title">Access restricted</h2>
        <p>
          {role ? `Your role (${ROLE_LABELS[role]}) does not have access to this area.` : 'You do not have access to this area.'} If you
          need it, ask an SA administrator to review your role.
        </p>
        <div className="placeholder-actions">
          <button type="button" className="gold-btn" onClick={() => navigate('/dashboard')}>
            Back to Command Center
          </button>
          <button type="button" className="subtle-btn" onClick={() => navigate(-1)}>
            Go back
          </button>
        </div>
      </section>
    </div>
  );
}

function loginRedirect(pathname: string, search: string): string {
  const next = `${pathname}${search}`;
  return next && next !== '/' ? `/login?next=${encodeURIComponent(next)}` : '/login';
}

/**
 * (a) Redirects unauthenticated users to /login?next=… — or, right after the session ended, to the URL chosen by
 *     the auth provider (`…&reason=expired` / `?reason=signed-out`) so the login page can explain why.
 * (b) Renders a branded 403 panel when the user's role is not allowed.
 */
export function ProtectedRoute({ roles, children }: { roles?: Role[]; children?: ReactNode }) {
  const { status, user, sessionEndRedirect } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <BrandLoader />;
  if (!user) return <Navigate to={sessionEndRedirect ?? loginRedirect(location.pathname, location.search)} replace />;
  if (roles && !roles.includes(user.role)) return <Forbidden role={user.role} />;
  return <>{children ?? <Outlet />}</>;
}
