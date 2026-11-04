import { Link } from 'react-router-dom';
import { ClipboardCheck, Flame } from 'lucide-react';
import { useMajorByIncident } from '../../../api/majorIncidents';
import { ToneBadge } from '../../../components/itsm/ItsmParts';
import { Panel } from '../../../components/Panel';
import { ErrorState, LoadingState } from '../../../components/States';
import { toApiError } from '../../../lib/api';
import { formatMinutes } from '../../../lib/format';
import { MAJOR_STATUS_LABELS, MAJOR_STATUS_TONES, PIR_STATUS_LABELS, PIR_STATUS_TONES } from '../../../lib/response';
import type { IncidentDetail } from '../../../lib/types';

/** Incident detail side card for a major incident: its command record (`GET /major-incidents/by-incident/{id}`) and review. */
export function MajorCommandCard({ incident }: { incident: IncidentDetail }) {
  const command = useMajorByIncident(incident.id, incident.major);
  if (!incident.major) return null;
  const notFound = command.isError && toApiError(command.error).status === 404;
  const major = command.data;
  return (
    <Panel
      title={
        <span className="inline-flex items-center gap-2">
          <Flame size={14} className="red-text" aria-hidden="true" /> Major incident command
        </span>
      }
    >
      {command.isLoading && <LoadingState variant="inline" label="Loading the command record…" />}
      {notFound && <p className="m-0 text-[11px] text-muted">The command record is being opened — refresh in a moment.</p>}
      {command.isError && !notFound && <ErrorState error={command.error} onRetry={() => void command.refetch()} compact />}
      {major && (
        <div className="grid gap-2">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="id-cell">{major.number}</span>
            <ToneBadge value={major.status} label={MAJOR_STATUS_LABELS[major.status]} tone={MAJOR_STATUS_TONES[major.status]} />
            <span className="text-[11px] text-muted">· {formatMinutes(major.durationMinutes)}</span>
          </div>
          <p className="m-0 text-[11px]">
            Commander <b>{major.commander?.name ?? 'not assigned'}</b>
            {major.techLead ? ` · tech lead ${major.techLead.name}` : ''}
            {major.commsLead ? ` · comms ${major.commsLead.name}` : ''}
          </p>
          <p className="m-0 text-[11px] text-muted">
            {major.timeline.length} timeline entries · {major.updates.length} stakeholder updates · {major.actionItems.filter((item) => item.status === 'OPEN' || item.status === 'IN_PROGRESS').length} open actions
          </p>
          <div className="flex flex-wrap gap-2">
            <Link to={`/major-incidents/${major.id}`} className="gold-btn compact">
              Open command view
            </Link>
            {major.pir && (
              <Link to={`/reviews/${major.pir.id}`} className="subtle-btn compact">
                <ClipboardCheck size={12} aria-hidden="true" /> {major.pir.number} · {PIR_STATUS_LABELS[major.pir.status]}
              </Link>
            )}
          </div>
          {major.pir && PIR_STATUS_TONES[major.pir.status] === 'critical' && <p className="m-0 text-[10px] red-text">The review was rejected and needs changes.</p>}
        </div>
      )}
    </Panel>
  );
}
