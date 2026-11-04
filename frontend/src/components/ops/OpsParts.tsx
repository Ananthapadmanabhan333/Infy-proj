import { History } from 'lucide-react';
import './ops.css';
import { ToneBadge } from '../itsm/ItsmParts';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import {
  ALERT_SEVERITY_TONES,
  ALERT_STATUS_LABELS,
  ALERT_STATUS_TONES,
  ASSET_STATUS_LABELS,
  ASSET_STATUS_TONES,
  CI_STATUS_TONES,
  CI_TYPE_LABELS,
  CRITICALITY_TONES,
  WARRANTY_LABELS,
  WARRANTY_TONES,
} from '../../lib/ops';
import type { AlertSeverity, AlertStatus, AssetStatus, CiStatus, CiType, Criticality, WarrantyStatus } from '../../lib/opsTypes';
import { labelOf, toneOf } from '../../lib/itsm';

export function SeverityBadge({ severity }: { severity: AlertSeverity | string | null | undefined }) {
  return <ToneBadge value={severity ?? null} tone={toneOf(ALERT_SEVERITY_TONES, severity)} />;
}

export function AlertStatusBadge({ status }: { status: AlertStatus | string | null | undefined }) {
  return <ToneBadge value={status ?? null} label={labelOf(ALERT_STATUS_LABELS, status)} tone={toneOf(ALERT_STATUS_TONES, status)} />;
}

export function CiStatusBadge({ status }: { status: CiStatus | string | null | undefined }) {
  return <ToneBadge value={status ?? null} tone={toneOf(CI_STATUS_TONES, status)} />;
}

export function CriticalityBadge({ criticality }: { criticality: Criticality | null | undefined }) {
  if (!criticality) return null;
  return <ToneBadge value={criticality} label={`${titleCase(criticality)} criticality`} tone={CRITICALITY_TONES[criticality]} />;
}

export function CiTypeBadge({ type }: { type: CiType }) {
  return <ToneBadge value={type} label={CI_TYPE_LABELS[type]} tone={type === 'BUSINESS_SERVICE' ? 'gold' : 'neutral'} />;
}

export function AssetStatusBadge({ status }: { status: AssetStatus | string | null | undefined }) {
  return <ToneBadge value={status ?? null} label={labelOf(ASSET_STATUS_LABELS, status)} tone={toneOf(ASSET_STATUS_TONES, status)} />;
}

export function WarrantyBadge({ status }: { status: WarrantyStatus | null | undefined }) {
  if (!status || status === 'NONE') return null;
  return <ToneBadge value={status} label={WARRANTY_LABELS[status]} tone={WARRANTY_TONES[status]} />;
}

export interface TimelineEvent {
  id: number | string;
  at: string;
  title: string;
  detail?: string | null;
  note?: string | null;
  actorName?: string | null;
}

/** Event list for alerts, assets and CIs (newest first, as delivered). */
export function EventTimeline({
  events,
  query,
  label = 'History',
}: {
  events?: TimelineEvent[];
  query?: { isLoading: boolean; isError: boolean; error: unknown; refetch: () => unknown };
  label?: string;
}) {
  if (query?.isLoading) return <LoadingState variant="inline" label="Loading history…" />;
  if (query?.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} compact />;
  if (!events?.length) return <EmptyState title="No history yet" icon={<History size={18} />} />;
  return (
    <ol className="m-0 grid list-none gap-2 p-0" aria-label={label}>
      {events.map((event) => (
        <li key={event.id} className="list-row !items-start">
          <div className="min-w-0">
            <b className="block break-words">
              {event.title}
              {event.detail && <span className="font-normal text-muted"> {event.detail}</span>}
            </b>
            {event.note && <small className="block whitespace-pre-wrap break-words">{event.note}</small>}
            <small className="block" title={formatDateTime(event.at)}>
              {event.actorName ?? 'System'} · {formatRelative(event.at)}
            </small>
          </div>
        </li>
      ))}
    </ol>
  );
}

/** "from → to" for history rows (either side may be empty). */
export function changeDetail(fromValue: string | null | undefined, toValue: string | null | undefined, label: (value: string) => string = (value) => value): string | null {
  if (!fromValue && !toValue) return null;
  return `${fromValue ? label(fromValue) : '—'} → ${toValue ? label(toValue) : '—'}`;
}
