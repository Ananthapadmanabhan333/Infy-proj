import type { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import { ToastProvider } from '../components/Toast';
import { AuthContext, type AuthContextValue } from '../hooks/useAuth';
import type { CurrentUser, Role } from '../lib/types';

export function makeUser(role: Role, overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: 7,
    email: `${role.toLowerCase()}@nexus360.test`,
    fullName: 'Ada Lovelace',
    role,
    departmentId: 3,
    departmentName: 'IT Service Desk',
    jobTitle: 'Engineer',
    mustChangePassword: false,
    demo: false,
    ...overrides,
  };
}

export function makeAuth(user: CurrentUser | null, overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    user,
    status: user ? 'authenticated' : 'anonymous',
    login: vi.fn(),
    logout: vi.fn(),
    hasRole: (...roles: Role[]) => Boolean(user && roles.includes(user.role)),
    reloadUser: vi.fn(),
    ...overrides,
  };
}

interface Options {
  route?: string;
  auth?: AuthContextValue;
}

export function renderWithProviders(ui: ReactElement, { route = '/', auth = makeAuth(makeUser('SYSTEM_ADMIN')) }: Options = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } },
  });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter initialEntries={[route]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>
        </ToastProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
  return { ...render(ui, { wrapper: Wrapper }), queryClient };
}
