import { createContext, useContext } from 'react';
import type { CurrentUser, Role } from '../lib/types';

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous';

export interface AuthContextValue {
  user: CurrentUser | null;
  status: AuthStatus;
  /** `rememberMe` true keeps the refresh cookie across browser restarts; false/omitted ends it with the browser session. */
  login: (email: string, password: string, rememberMe?: boolean) => Promise<CurrentUser>;
  logout: () => Promise<void>;
  hasRole: (...roles: Role[]) => boolean;
  /** Re-reads /auth/me (e.g. after a password change). */
  reloadUser: () => Promise<CurrentUser | null>;
  /**
   * Where protected routes send the user right after the session ended — `/login?next=…&reason=expired` after a
   * failed refresh, `/login?reason=signed-out` after logout. Cleared once /login is reached or on sign-in.
   */
  sessionEndRedirect?: string | null;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}

/** Convenience for components that only render for an authenticated user. */
export function useCurrentUser(): CurrentUser {
  const { user } = useAuth();
  if (!user) throw new Error('useCurrentUser requires an authenticated session');
  return user;
}
