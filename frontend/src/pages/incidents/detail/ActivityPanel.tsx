import { useMemo, useState } from 'react';
import { AlertTriangle, Clock3, History, MessageSquare, PlayCircle, ShieldCheck, type LucideIcon } from 'lucide-react';
import { useIncidentActivity } from '../../../api/incidents';
import { Badge } from '../../../components/Badge';
import { Panel } from '../../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../../components/States';
import { roleLabel } from '../../../lib/constants';
import { formatDateTime, formatNumber, formatRelative } from '../../../lib/format';
import type { ActivityItem, ActivityKind, IncidentDetail } from '../../../lib/types';

const KINDS: { kind: ActivityKind; label: string; icon: LucideIcon }[] = [
  { kind: 'HISTORY', label: 'Changes', icon: History },
  { kind: 'COMMENT', label: 'Comments', icon: MessageSquare },
  { kind: 'SLA', label: 'SLA', icon: Clock3 },
  { kind: 'ESCALATION', label: 'Escalations', icon: AlertTriangle },
  { kind: 'APPROVAL', label: 'Approvals', icon: ShieldCheck },
  { kind: 'RUNBOOK', label: 'Runbooks', icon: PlayCircle },
];
const KIND_META = Object.fromEntries(KINDS.map((entry) => [entry.kind, entry])) as Record<ActivityKind, (typeof KINDS)[number]>;

/** Role codes in server text ("Routed to INCIDENT_MANAGER") are shown with their labels. */
const ROLE_TOKEN = /\b(SYSTEM_ADMIN|IT_ADMIN|IT_MANAGER|INCIDENT_MANAGER|SUPPORT_AGENT|EMPLOYEE|AUDITOR)\b/g;
export const readable = (text: string | null) => (text ? text.replace(ROLE_TOKEN, (role) => roleLabel(role)) : text);

function isBreach(item: ActivityItem) {
  return item.kind === 'SLA' && /breach|missed/i.test(item.title);
}

function EscalationHistory({ incident }: { incident: IncidentDetail }) {
  if (!incident.escalations?.length) return null;
  return (
    <Panel title="Escalation history" subtitle={`Current escalation level ${incident.escalationLevel}`}>
      {[...incident.escalations].reverse().map((escalation, index) => (
        <div key={`${escalation.at}-${index}`} className="list-row">
          <AlertTriangle size={14} className="red-text" aria-hidden="true" />
          <div>
            <b>{escalation.reason || 'Escalated'}</b>
            <small title={formatDateTime(escalation.at)}>
              {escalation.actorName ?? 'SLA engine'} · {formatRelative(escalation.at)}
            </small>
          </div>
          <Badge value="escalated" label={`Level ${escalation.level}`} />
        </div>
      ))}
    </Panel>
  );
}

/** §3 merged timeline (history, comments, SLA, escalations, approvals, runbooks), newest first. */
export function ActivityPanel({ incident }: { incident: IncidentDetail }) {
  const activity = useIncidentActivity(incident.id);
  const [filter, setFilter] = useState<ActivityKind | 'ALL'>('ALL');
  const counts = useMemo(() => {
    const result: Partial<Record<ActivityKind, number>> = {};
    activity.data?.forEach((item) => {
      result[item.kind] = (result[item.kind] ?? 0) + 1;
    });
    return result;
  }, [activity.data]);
  const visible = (activity.data ?? []).filter((item) => filter === 'ALL' || item.kind === filter);

  return (
    <>
      <EscalationHistory incident={incident} />
      <Panel title="Activity" subtitle="Changes, conversation, SLA events, escalations, approvals and runbooks — newest first">
        {activity.data && activity.data.length > 0 && (
          <div className="activity-filters" role="group" aria-label="Filter activity">
            <button type="button" className="chip" aria-pressed={filter === 'ALL'} onClick={() => setFilter('ALL')}>
              All ({formatNumber(activity.data.length)})
            </button>
            {KINDS.filter((entry) => counts[entry.kind]).map((entry) => (
              <button key={entry.kind} type="button" className="chip" aria-pressed={filter === entry.kind} onClick={() => setFilter(entry.kind)}>
                {entry.label} ({formatNumber(counts[entry.kind] ?? 0)})
              </button>
            ))}
          </div>
        )}
        {activity.isLoading && <LoadingState rows={5} label="Loading activity…" />}
        {activity.isError && !activity.data && <ErrorState error={activity.error} onRetry={() => void activity.refetch()} compact />}
        {activity.data && activity.data.length === 0 && <EmptyState title="No activity yet" icon={<History size={20} />} />}
        {visible.length > 0 && (
          <ol className="activity-feed" aria-label="Activity timeline">
            {visible.map((item, index) => {
              const meta = KIND_META[item.kind] ?? KIND_META.HISTORY;
              const Icon = meta.icon;
              return (
                <li key={`${item.at}-${index}`} className={`kind-${item.kind.toLowerCase()} ${isBreach(item) ? 'breach' : ''}`.trim()}>
                  <span className="activity-icon" aria-hidden="true">
                    <Icon size={14} />
                  </span>
                  <div className="min-w-0">
                    <b>{readable(item.title)}</b>
                    {item.detail && <p>{readable(item.detail)}</p>}
                    <small title={formatDateTime(item.at)}>
                      <span className="sr-only">{meta.label}: </span>
                      {item.actorName ?? 'SA'} · {formatRelative(item.at)}
                    </small>
                  </div>
                </li>
              );
            })}
          </ol>
        )}
      </Panel>
    </>
  );
}
