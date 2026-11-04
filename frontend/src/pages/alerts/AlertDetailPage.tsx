import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { BellOff, CheckCheck, CheckCircle2, ExternalLink, Eye, FilePlus2, Link2, UsersRound } from 'lucide-react';
import { useAlert, useAlertCommand } from '../../api/alerts';
import { DemoBadge, PriorityBadge, StatusBadge, SimulatedBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Fact, TextBlock, ToneBadge } from '../../components/itsm/ItsmParts';
import { AlertStatusBadge, EventTimeline, SeverityBadge } from '../../components/ops/OpsParts';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { ALERT_RESPONDERS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import { ALERT_EVENT_LABELS, ALERT_SOURCE_LABELS } from '../../lib/ops';
import type { AlertAction, AlertDetail } from '../../lib/opsTypes';
import { AssignTeamDialog, ConvertDialog, LinkIncidentDialog, SuppressDialog } from './AlertDialogs';

/** Only http(s) links from alert payloads are rendered as links. */
export function safeExternalUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : null;
  } catch {
    return null;
  }
}

function KeyValues({ values, label }: { values: Record<string, string> | null; label: string }) {
  const entries = Object.entries(values ?? {});
  if (!entries.length) return <p className="m-0 text-[11px] text-muted">None.</p>;
  return (
    <ul className="kv-list" aria-label={label}>
      {entries.map(([key, value]) => (
        <li key={key}>
          <span>{key}</span>
          <span>{value}</span>
        </li>
      ))}
    </ul>
  );
}

function eventDetail(details: Record<string, unknown> | null): string | null {
  if (!details) return null;
  const parts = Object.entries(details)
    .filter(([, value]) => value !== null && value !== undefined && value !== '' && typeof value !== 'object')
    .slice(0, 6)
    .map(([key, value]) => `${key}: ${String(value)}`);
  return parts.length ? `(${parts.join(', ')})` : null;
}

type Dialog = 'ACKNOWLEDGE' | 'RESOLVE' | 'SUPPRESS' | 'UNSUPPRESS' | 'ASSIGN' | 'CONVERT' | 'LINK_INCIDENT';

function ActionButtons({ detail, onAction }: { detail: AlertDetail; onAction: (action: Dialog) => void }) {
  const allowed = new Set<AlertAction>(detail.allowedActions);
  const buttons: { action: AlertAction; label: string; icon: JSX.Element; className: string }[] = [
    { action: 'ACKNOWLEDGE', label: 'Acknowledge', icon: <CheckCheck size={14} aria-hidden="true" />, className: 'gold-btn' },
    { action: 'CONVERT', label: 'Convert to incident', icon: <FilePlus2 size={14} aria-hidden="true" />, className: allowed.has('ACKNOWLEDGE') ? 'subtle-btn' : 'gold-btn' },
    { action: 'LINK_INCIDENT', label: 'Link incident', icon: <Link2 size={14} aria-hidden="true" />, className: 'subtle-btn' },
    { action: 'ASSIGN', label: 'Assign team', icon: <UsersRound size={14} aria-hidden="true" />, className: 'subtle-btn' },
    { action: 'RESOLVE', label: 'Resolve', icon: <CheckCircle2 size={14} aria-hidden="true" />, className: 'subtle-btn' },
    { action: 'SUPPRESS', label: 'Suppress', icon: <BellOff size={14} aria-hidden="true" />, className: 'subtle-btn' },
    { action: 'UNSUPPRESS', label: 'Unsuppress', icon: <Eye size={14} aria-hidden="true" />, className: 'gold-btn' },
  ];
  return (
    <>
      {buttons
        .filter((button) => allowed.has(button.action))
        .map((button) => (
          <button key={button.action} type="button" className={button.className} onClick={() => onAction(button.action)}>
            {button.icon} {button.label}
          </button>
        ))}
    </>
  );
}

/** /alerts/:id — facts, labels and annotations, group, incident, history; responders act through `allowedActions`. */
export default function AlertDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const valid = Number.isInteger(id) && id > 0;
  const { hasRole } = useAuth();
  const canRespond = hasRole(...ALERT_RESPONDERS);
  const detail = useAlert(valid ? id : null);
  const command = useAlertCommand(valid ? id : 0);
  const toast = useToast();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const crumbs = [{ label: 'Alerts', to: '/alerts' }];
  const data = detail.data;

  if (!valid || (detail.isError && toApiError(detail.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[...crumbs, { label: 'Not found' }]} title="Alert not found" />
        <EmptyState title="This alert does not exist" description="The link may be wrong." action={<Link to="/alerts" className="subtle-btn compact">Back to alerts</Link>} />
      </>
    );
  }
  if (detail.isLoading) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Loading alert…" />
        <LoadingState variant="panel" />
      </>
    );
  }
  if (!data) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Alert" />
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
      </>
    );
  }

  const alert = data.alert;
  const link = safeExternalUrl(data.externalUrl);
  const close = () => {
    setDialog(null);
    command.reset();
  };
  const runNote = (action: 'acknowledge' | 'resolve', note: string) =>
    command.mutate(
      { action, note: note.trim() || null },
      {
        onSuccess: () => {
          toast.success(`${alert.number} ${action === 'acknowledge' ? 'acknowledged' : 'resolved'}`);
          setDialog(null);
        },
      },
    );

  return (
    <>
      <PageHeader crumbs={[...crumbs, { label: alert.number }]} title={alert.title} actions={canRespond ? <ActionButtons detail={data} onAction={setDialog} /> : undefined}>
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
          <span className="id-cell">{alert.number}</span>
          <SeverityBadge severity={alert.severity} />
          <AlertStatusBadge status={alert.status} />
          {alert.firing ? <ToneBadge value="firing" label="Firing" tone="critical" /> : <ToneBadge value="not-firing" label="Not firing" tone="muted" />}
          {alert.occurrenceCount > 1 && <ToneBadge value="repeats" label={`${formatNumber(alert.occurrenceCount)} occurrences`} tone="champagne" />}
          {alert.escalationLevel > 0 && <ToneBadge value="escalated" label={`Escalated · level ${alert.escalationLevel}`} tone="critical" />}
          <DemoBadge demo={alert.demo} /> <SimulatedBadge simulated={alert.simulated} />
          <span>· {alert.sourceName ?? ALERT_SOURCE_LABELS[alert.sourceKind]}</span>
          <span>· last seen {formatRelative(alert.lastSeenAt)}</span>
        </div>
      </PageHeader>
      <div className="page-stack">
        {alert.status === 'SUPPRESSED' && (
          <div className="callout" role="status">
            <div>
              Suppressed {data.suppressionRuleName ? `by the rule “${data.suppressionRuleName}”` : 'manually'}
              {data.suppressedAt ? ` ${formatRelative(data.suppressedAt)}` : ''}
              {data.suppressedUntil ? ` until ${formatDateTime(data.suppressedUntil)}` : data.suppressionRuleName ? '' : ' until resolved or unsuppressed'}. Nobody is notified and it does not escalate.
            </div>
          </div>
        )}
        {alert.status === 'RESOLVED' && data.resolutionNote && (
          <div className="callout green" role="status">
            <div>
              Resolved {alert.resolvedAt ? formatRelative(alert.resolvedAt) : ''}
              {data.resolvedByName ? ` by ${data.resolvedByName}` : ''}: {data.resolutionNote}
            </div>
          </div>
        )}
        <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Overview">
              <TextBlock text={data.description} empty="No description from the source." />
              <div className="detail-grid mt-3">
                <Fact label="Service">{alert.serviceId ? <Link to={`/services/${alert.serviceId}`}>{alert.serviceName}</Link> : '—'}</Fact>
                <Fact label="Configuration item">{alert.ciId ? <Link to={`/cmdb/${alert.ciId}`}>{alert.ciName}</Link> : '—'}</Fact>
                <Fact label="Team">{alert.teamName ?? 'No team'}</Fact>
                <Fact label="Source">{`${ALERT_SOURCE_LABELS[alert.sourceKind]}${alert.sourceName ? ` · ${alert.sourceName}` : ''}${data.integrationName ? ` · ${data.integrationName}` : ''}`}</Fact>
                <Fact label="First seen">{formatDateTime(alert.firstSeenAt)}</Fact>
                <Fact label="Last seen">{formatDateTime(alert.lastSeenAt)}</Fact>
                <Fact label="Started (source)">{data.startsAt ? formatDateTime(data.startsAt) : '—'}</Fact>
                <Fact label="Ended (source)">{data.endsAt ? formatDateTime(data.endsAt) : '—'}</Fact>
                <Fact label="Acknowledged">{alert.acknowledgedAt ? `${formatDateTime(alert.acknowledgedAt)}${alert.acknowledgedByName ? ` by ${alert.acknowledgedByName}` : ''}` : '—'}</Fact>
                <Fact label="Routing rule">{data.routingRuleName ?? 'Default routing'}</Fact>
                <Fact label="Escalation">
                  {alert.escalationLevel > 0 ? `Level ${alert.escalationLevel}` : 'Not escalated'}
                  {alert.nextEscalationAt ? ` · next ${formatRelative(alert.nextEscalationAt)}` : ''}
                  {data.escalationPolicy ? ` · policy ${data.escalationPolicy}` : ''}
                </Fact>
                <Fact label="Fingerprint">
                  <span className="break-all font-mono text-[10px]">{data.fingerprint}</span>
                </Fact>
                {alert.groupKey && (
                  <Fact label="Group">
                    <Link to={`/alerts?groupKey=${encodeURIComponent(alert.groupKey)}&status=all`} className="break-all">
                      {alert.groupKey}
                    </Link>
                  </Fact>
                )}
                {alert.correlationGroupId && (
                  <Fact label="Correlation group">
                    <Link to={`/observability?tab=correlation&group=${alert.correlationGroupId}${alert.simulated ? '&sim=1' : ''}`}>
                      Correlation group #{alert.correlationGroupId}
                    </Link>
                  </Fact>
                )}
              </div>
              {link && (
                <a href={link} target="_blank" rel="noopener noreferrer" className="link-btn mt-3 text-[11px]">
                  <ExternalLink size={12} aria-hidden="true" /> Open in the monitoring system
                </a>
              )}
            </Panel>
            <div className="grid gap-[14px] lg:grid-cols-2">
              <Panel title="Labels">
                <KeyValues values={data.labels} label="Labels" />
              </Panel>
              <Panel title="Annotations">
                <KeyValues values={data.annotations} label="Annotations" />
              </Panel>
            </div>
            <Panel title={`Other alerts in the group (${data.groupAlerts.length})`}>
              {data.groupAlerts.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">This alert is the only one in its group.</p>
              ) : (
                <ul className="m-0 grid list-none gap-1 p-0" aria-label="Group alerts">
                  {data.groupAlerts.map((other) => (
                    <li key={other.id} className="list-row flex-wrap sm:flex-nowrap">
                      <div className="min-w-0">
                        <b className="flex flex-wrap items-center gap-1.5">
                          <Link to={`/alerts/${other.id}`} className="id-cell">
                            {other.number}
                          </Link>
                          <span className="break-words">{other.title}</span>
                        </b>
                        <small>
                          {other.ciName ?? 'No CI'} · ×{formatNumber(other.occurrenceCount)} · {formatRelative(other.lastSeenAt)}
                        </small>
                      </div>
                      <span className="flex shrink-0 gap-1">
                        <SeverityBadge severity={other.severity} />
                        <AlertStatusBadge status={other.status} />
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Incident">
              {data.incident ? (
                <div className="list-row flex-wrap">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <Link to={`/incidents/${data.incident.id}`} className="id-cell">
                        {data.incident.number}
                      </Link>
                      <span className="break-words">{data.incident.title}</span>
                    </b>
                    <small className="flex flex-wrap items-center gap-1.5">
                      <PriorityBadge priority={data.incident.priority} />
                      <StatusBadge status={data.incident.status} />
                      {data.incident.assigneeName ?? data.incident.teamName ?? 'Unassigned'}
                    </small>
                  </div>
                </div>
              ) : (
                <p className="m-0 text-[11px] text-muted">Not linked to an incident.{canRespond && data.allowedActions.includes('CONVERT') ? ' Convert it or link an existing one.' : ''}</p>
              )}
            </Panel>
            <Panel title="History">
              <EventTimeline
                events={data.history.map((event) => ({
                  id: event.id,
                  at: event.at,
                  actorName: event.actorName,
                  title: ALERT_EVENT_LABELS[event.type] ?? titleCase(event.type),
                  detail: eventDetail(event.details),
                  note: event.note,
                }))}
              />
            </Panel>
          </div>
        </div>
      </div>

      <ConfirmDialog
        open={dialog === 'ACKNOWLEDGE' || dialog === 'RESOLVE'}
        onClose={close}
        title={`${dialog === 'RESOLVE' ? 'Resolve' : 'Acknowledge'} ${alert.number}?`}
        message={dialog === 'RESOLVE' ? 'The alert is closed; if it fires again within the grouping window it reopens.' : 'Acknowledging stops the escalation and tells the team someone is on it.'}
        reasonLabel="Note (optional)"
        confirmLabel={dialog === 'RESOLVE' ? 'Resolve' : 'Acknowledge'}
        pending={command.isPending}
        error={command.isError ? errorMessage(command.error) : null}
        onConfirm={(note) => runNote(dialog === 'RESOLVE' ? 'resolve' : 'acknowledge', note)}
      />
      <ConfirmDialog
        open={dialog === 'UNSUPPRESS'}
        onClose={close}
        title={`Unsuppress ${alert.number}?`}
        message="It becomes OPEN again, is routed if it has no team, and escalates when nobody acknowledges it."
        confirmLabel="Unsuppress"
        pending={command.isPending}
        error={command.isError ? errorMessage(command.error) : null}
        onConfirm={() =>
          command.mutate(
            { action: 'unsuppress' },
            {
              onSuccess: () => {
                toast.success(`${alert.number} is open again`);
                setDialog(null);
              },
            },
          )
        }
      />
      {dialog === 'SUPPRESS' && <SuppressDialog alert={alert} onClose={() => setDialog(null)} />}
      {dialog === 'ASSIGN' && <AssignTeamDialog alert={alert} onClose={() => setDialog(null)} />}
      {dialog === 'CONVERT' && <ConvertDialog detail={data} onClose={() => setDialog(null)} onConverted={() => undefined} />}
      {dialog === 'LINK_INCIDENT' && <LinkIncidentDialog alert={alert} onClose={() => setDialog(null)} />}
    </>
  );
}
