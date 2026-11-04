import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { BrandLoader } from '../BrandLoader';

/**
 * Public account pages (register, forgot / reset password): signed-in users are sent to the Command Center
 * (spec §1). /login handles this itself so it can honour `next`.
 */
export function GuestRoute({ children }: { children: ReactNode }) {
  const { status, user } = useAuth();
  if (status === 'loading') return <BrandLoader />;
  if (user) return <Navigate to="/dashboard" replace />;
  return <>{children}</>;
}
