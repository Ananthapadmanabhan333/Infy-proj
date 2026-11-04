import type { ReactNode } from 'react';
import { AlertTriangle, Inbox, Lock, RefreshCw, WifiOff } from 'lucide-react';
import { toApiError } from '../lib/api';

export function Skeleton({ height = 14, width = '100%', className = '' }: { height?: number | string; width?: number | string; className?: string }) {
  return <div className={`skeleton ${className}`} style={{ height, width }} aria-hidden="true" />;
}

interface LoadingStateProps {
  variant?: 'table' | 'cards' | 'panel' | 'kpis' | 'inline';
  rows?: number;
  label?: string;
}

export function LoadingState({ variant = 'table', rows = 5, label = 'Loading…' }: LoadingStateProps) {
  return (
    <div role="status" aria-live="polite" aria-busy="true">
      <span className="sr-only">{label}</span>
      {variant === 'inline' && <Skeleton height={12} width={120} />}
      {variant === 'table' && (
        <div className="grid gap-2.5 py-2">
          <Skeleton height={12} width="40%" />
          {Array.from({ length: rows }, (_, i) => (
            <Skeleton key={i} height={30} />
          ))}
        </div>
      )}
      {variant === 'panel' && (
        <div className="grid gap-3 py-2">
          <Skeleton height={16} width="35%" />
          <Skeleton height={140} />
          <Skeleton height={12} width="60%" />
        </div>
      )}
      {variant === 'cards' && (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: rows }, (_, i) => (
            <Skeleton key={i} height={96} />
          ))}
        </div>
      )}
      {variant === 'kpis' && (
        <div className="kpi-grid">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} height={96} />
          ))}
        </div>
      )}
    </div>
  );
}

interface ErrorStateProps {
  error: unknown;
  onRetry?: () => void;
  title?: string;
  compact?: boolean;
}

export function ErrorState({ error, onRetry, title, compact }: ErrorStateProps) {
  const apiError = toApiError(error);
  const forbidden = apiError.status === 403;
  const offline = apiError.status === 0;
  const Icon = forbidden ? Lock : offline ? WifiOff : AlertTriangle;
  const heading = title ?? (forbidden ? 'Access restricted' : offline ? 'API unreachable' : 'Could not load this data');
  return (
    <div className={`state-panel error ${compact ? 'py-4' : ''}`} role="alert">
      <div className="state-icon">
        <Icon size={20} />
      </div>
      <h3>{heading}</h3>
      <p>{apiError.message}</p>
      {onRetry && !forbidden && (
        <button type="button" className="subtle-btn compact" onClick={onRetry}>
          <RefreshCw size={14} /> Retry
        </button>
      )}
    </div>
  );
}

interface EmptyStateProps {
  title: string;
  description?: ReactNode;
  icon?: ReactNode;
  action?: ReactNode;
}

export function EmptyState({ title, description, icon, action }: EmptyStateProps) {
  return (
    <div className="state-panel">
      <div className="state-icon">{icon ?? <Inbox size={20} />}</div>
      <h3>{title}</h3>
      {description && <p>{description}</p>}
      {action}
    </div>
  );
}

/** Renders loading / error / content for a query in one place. */
export function QueryState<T>({
  query,
  loading,
  children,
  isEmpty,
  empty,
}: {
  query: { data: T | undefined; isLoading: boolean; isError: boolean; error: unknown; refetch: () => unknown };
  loading?: ReactNode;
  children: (data: T) => ReactNode;
  isEmpty?: (data: T) => boolean;
  empty?: ReactNode;
}) {
  if (query.isError && query.data === undefined) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  if (query.isLoading || query.data === undefined) return <>{loading ?? <LoadingState />}</>;
  if (isEmpty?.(query.data)) return <>{empty ?? <EmptyState title="Nothing here yet" />}</>;
  return <>{children(query.data)}</>;
}
