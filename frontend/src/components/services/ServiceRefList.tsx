import { Link } from 'react-router-dom';
import { ChevronRight, GitBranch } from 'lucide-react';
import { StatusBadge } from '../Badge';
import { EmptyState } from '../States';
import type { ServiceRef } from '../../lib/types';
import { SERVICE_STATUS_COLOR } from './serviceModel';

/** Clickable list of related services (dependencies or dependents) with their current status. */
export function ServiceRefList({ refs, empty, label }: { refs: ServiceRef[]; empty: string; label: string }) {
  if (refs.length === 0) return <EmptyState title={empty} icon={<GitBranch size={20} />} />;
  return (
    <ul aria-label={label}>
      {refs.map((ref) => (
        <li key={ref.id}>
          <Link to={`/services/${ref.id}`} className="list-row no-underline hover:text-gold-light">
            <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: SERVICE_STATUS_COLOR[ref.status] }} aria-hidden="true" />
            <div>
              <b>{ref.name}</b>
              <small>{ref.code}</small>
            </div>
            <StatusBadge status={ref.status} />
            <ChevronRight size={14} className="text-gold" aria-hidden="true" />
          </Link>
        </li>
      ))}
    </ul>
  );
}
