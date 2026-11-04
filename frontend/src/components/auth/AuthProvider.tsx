import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  api,
  clearSession,
  onSessionRefreshed,
  refreshSession,
  setAccessToken,
  setSessionExpiredHandler,
} from '../../lib/api';
import type { CurrentUser, LoginResponse, Role } from '../../lib/types';
import { AuthContext, type AuthContextValue, type AuthStatus } from '../../hooks/useAuth';

/** Where logout lands (spec §1). */
export const SIGNED_OUT_URL = '/login?reason=signed-out';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');
  // Shared with ProtectedRoute so its own redirect (rendered as soon as `user` is null) targets the same URL
  // as the navigation below instead of racing it with a plain `/login?next=…`.
  const [sessionEndRedirect, setSessionEndRedirect] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const booted = useRef(false);
  const signingOut = useRef(false);

  useEffect(() => {
    if (sessionEndRedirect && pathname === '/login') setSessionEndRedirect(null);
  }, [pathname, sessionEndRedirect]);

  // Restore the session from the HttpOnly refresh cookie on first load.
  useEffect(() => {
    if (booted.current) return;
    booted.current = true;
    refreshSession()
      .then(async (session) => {
        const current = session.user ?? (await api.get<CurrentUser>('/auth/me')).data;
        setUser(current);
        setStatus('authenticated');
      })
      .catch(() => {
        clearSession();
        setUser(null);
        setStatus('anonymous');
      });
  }, []);

  // Keep the user in sync when a silent refresh returns an updated profile.
  useEffect(
    () =>
      onSessionRefreshed((session) => {
        if (session.user) setUser(session.user);
      }),
    [],
  );

  useEffect(() => {
    setSessionExpiredHandler((loginUrl) => {
      if (signingOut.current || window.location.search.includes('reason=signed-out')) return;
      setSessionEndRedirect(loginUrl);
      setUser(null);
      setStatus('anonymous');
      queryClient.clear();
      navigate(loginUrl, { replace: true });
    });
    return () => setSessionExpiredHandler(null);
  }, [navigate, queryClient]);

  const login = useCallback(
    async (email: string, password: string, rememberMe = false) => {
      signingOut.current = false;
      const { data } = await api.post<LoginResponse>('/auth/login', { email, password, rememberMe });
      setAccessToken(data.accessToken);
      queryClient.clear();
      setSessionEndRedirect(null);
      setUser(data.user);
      setStatus('authenticated');
      return data.user;
    },
    [queryClient],
  );

  const logout = useCallback(async () => {
    signingOut.current = true;
    try {
      await queryClient.cancelQueries();
      await api.post('/auth/logout', null, { headers: { 'X-Requested-With': 'nexus360' }, skipAuthRefresh: true });
    } catch {
      // The local session is cleared regardless of the API response.
    }
    clearSession();
    setSessionEndRedirect(SIGNED_OUT_URL);
    setUser(null);
    setStatus('anonymous');
    queryClient.clear();
    navigate(SIGNED_OUT_URL, { replace: true });
  }, [navigate, queryClient]);

  const reloadUser = useCallback(async () => {
    try {
      const { data } = await api.get<CurrentUser>('/auth/me');
      setUser(data);
      return data;
    } catch {
      return null;
    }
  }, []);

  const hasRole = useCallback((...roles: Role[]) => (user ? roles.includes(user.role) : false), [user]);

  const value = useMemo<AuthContextValue>(
    () => ({ user, status, login, logout, hasRole, reloadUser, sessionEndRedirect }),
    [user, status, login, logout, hasRole, reloadUser, sessionEndRedirect],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
