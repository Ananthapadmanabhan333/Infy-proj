import { History } from 'lucide-react';
import { useServiceRequestHistory } from '../../api/serviceDesk';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import type { ServiceRequestHistoryItem } from '../../lib/types';
import { requestValueLabel } from './requestModel';

const ACTION_LABELS: Record<string, string> = {
  CREATED: 'Request submitted',
  ASSIGNED: 'Assigned',
  STATUS_CHANGED: 'Status changed',
  APPROVAL_REQUESTED: 'Approval requested',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  COMMENTED: 'Comment added',
  PRIORITY_CHANGED: 'Priority changed',
};

function describe(item: ServiceRequestHistoryItem): string {
  const label = ACTION_LABELS[item.action] ?? titleCase(item.action);
  if (item.fromValue && item.toValue) return `${label}: ${requestValueLabel(item.fromValue)} → ${requestValueLabel(item.toValue)}`;
  if (item.toValue) return `${label}: ${requestValueLabel(item.toValue)}`;
  return label;
}

/** Status, assignment and approval history of a request as a timeline, newest first. */
export function RequestHistory({ requestId }: { requestId: number }) {
  const history = useServiceRequestHistory(requestId);
  if (history.isLoading) return <LoadingState rows={3} label="Loading history…" />;
  if (history.isError) return <ErrorState error={history.error} onRetry={() => void history.refetch()} compact />;
  const items = [...(history.data ?? [])].sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime());
  if (items.length === 0) return <EmptyState title="No history yet" icon={<History size={20} />} />;
  return (
    <div className="timeline" aria-label="Request history">
      {items.map((item, index) => (
        <div key={`${item.at}-${item.action}-${index}`}>
          <i aria-hidden="true" />
          <b className="font-semibold text-ivory">{describe(item)}</b>
          {item.note && <span className="block text-[#cfc3b1]">{item.note}</span>}
          <small title={formatDateTime(item.at)}>
            {item.actorName ?? 'System'} · {formatRelative(item.at)}
          </small>
        </div>
      ))}
    </div>
  );
}
