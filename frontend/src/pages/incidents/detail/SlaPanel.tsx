import { PauseCircle, ShieldAlert } from 'lucide-react';
import { useIncidentSla } from '../../../api/incidents';
import { Badge } from '../../../components/Badge';
import { PanelTitle } from '../../../components/Panel';
import { SlaCountdown } from '../../../components/SlaCountdown';
import { ErrorState, LoadingState } from '../../../components/States';
import { toApiError } from '../../../lib/api';
import { PALETTE } from '../../../lib/constants';
import { formatCountdown, formatDateTime, formatMinutes, formatRelative, titleCase } from '../../../lib/format';
import type { IncidentDetail } from '../../../lib/types';

const ESCALATION_LABELS = ['None', 'At risk', 'Breached', 'Severely breached'];

export function SlaPanel({ incident }: { incident: IncidentDetail }) {
  const sla = useIncidentSla(incident.id, Boolean(incident.sla));

  if (!incident.sla) {
    return (
      <section className="panel" aria-labelledby="sla-title">
        <PanelTitle id="sla-title" title="Service level" subtitle="No SLA policy applies to this incident" />
      </section>
    );
  }

  const detail = sla.data;
  const snapshot = detail ?? incident.sla;
  const percent = Math.max(0, Math.min(100, snapshot.percentElapsed));

  return (
    <section className="panel" aria-labelledby="sla-title">
      <PanelTitle id="sla-title" title="Service level" subtitle={`${snapshot.policyName}${snapshot.businessHoursOnly ? ' · business hours' : ' · 24×7'}`}>
        <Badge value={snapshot.health} />
      </PanelTitle>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <small className="block text-[9px] tracking-wider text-muted uppercase">Resolution time remaining</small>
          <SlaCountdown sla={snapshot} size="lg" />
        </div>
        <div className="text-right text-[10px] text-muted">
          {snapshot.resolutionDueAt ? <>Due {formatDateTime(snapshot.resolutionDueAt)}</> : snapshot.clockStatus === 'PAUSED' ? 'Due date recalculated on resume' : '—'}
          <div>{Math.round(snapshot.percentElapsed)}% of target consumed</div>
        </div>
      </div>
      <div className="progress mt-2" role="progressbar" aria-valuenow={Math.round(percent)} aria-valuemin={0} aria-valuemax={100} aria-label="SLA consumed">
        <i style={{ width: `${percent}%`, background: snapshot.health === 'BREACHED' ? PALETTE.critical : snapshot.health === 'AT_RISK' ? PALETTE.warning : undefined }} />
      </div>

      {snapshot.clockStatus === 'PAUSED' && (
        <div className="callout mt-3">
          <PauseCircle size={16} />
          <div>The SLA clock is paused while the incident is pending.</div>
        </div>
      )}
      {snapshot.escalationLevel > 0 && (
        <div className="callout red mt-3">
          <ShieldAlert size={16} />
          <div>
            Escalation level <b>{snapshot.escalationLevel}</b> — {ESCALATION_LABELS[snapshot.escalationLevel] ?? 'Escalated'}
          </div>
        </div>
      )}

      <div className="detail-grid">
        <div>
          <small>Response due</small>
          <b className={snapshot.responseBreached ? 'red-text' : ''}>{formatDateTime(snapshot.responseDueAt)}</b>
        </div>
        <div>
          <small>Responded</small>
          <b>{snapshot.respondedAt ? formatDateTime(snapshot.respondedAt) : snapshot.responseBreached ? 'Breached — no response' : 'Awaiting response'}</b>
        </div>
        <div>
          <small>Clock</small>
          <b>{titleCase(snapshot.clockStatus)}</b>
        </div>
        <div>
          <small>Outcome</small>
          <b>{snapshot.outcome ? titleCase(snapshot.outcome) : 'In progress'}</b>
        </div>
        {detail && (
          <>
            <div>
              <small>Targets</small>
              <b>
                {formatMinutes(detail.responseTargetMinutes)} / {formatMinutes(detail.resolutionTargetMinutes)}
              </b>
            </div>
            <div>
              <small>Paused total</small>
              <b>{detail.totalPausedSeconds ? formatCountdown(detail.totalPausedSeconds) : 'Never paused'}</b>
            </div>
          </>
        )}
      </div>

      <h4 className="mb-2 text-[12px]">SLA event history</h4>
      {sla.isLoading && <LoadingState rows={3} />}
      {sla.isError && toApiError(sla.error).status !== 404 && <ErrorState error={sla.error} onRetry={() => void sla.refetch()} compact />}
      {detail && detail.events.length === 0 && <p className="text-[10px] text-muted">No SLA events recorded yet.</p>}
      {detail && detail.events.length > 0 && (
        <div className="timeline">
          {detail.events.map((event) => (
            <div key={event.id}>
              <i style={event.eventType === 'BREACHED' || event.eventType === 'MISSED' ? { background: PALETTE.critical } : undefined} />
              <span className="inline-flex items-center gap-2">
                <Badge value={event.eventType} /> {event.details}
              </span>
              <small>
                {formatDateTime(event.createdAt)} · {formatRelative(event.createdAt)}
              </small>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
