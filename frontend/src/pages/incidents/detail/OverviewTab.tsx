import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Avatar } from '../../../components/Avatar';
import { Badge } from '../../../components/Badge';
import { Panel } from '../../../components/Panel';
import { formatDateTime, formatNumber, formatPercent, shortName, titleCase } from '../../../lib/format';
import type { IncidentDetail } from '../../../lib/types';
import { AttachmentsPanel } from './AttachmentsPanel';
import { CommentsPanel } from './CommentsPanel';

function Person({ name, note }: { name: string; note?: string | null }) {
  return (
    <span className="person" title={name}>
      <Avatar name={name} size="sm" />
      <span className="truncate">
        {shortName(name)}
        {note && <small> ({note})</small>}
      </span>
    </span>
  );
}

function DetailsGrid({ incident }: { incident: IncidentDetail }) {
  const rows: [string, ReactNode][] = [
    ['Service', incident.serviceName ?? '—'],
    ['Department', incident.departmentName ?? '—'],
    ['Category', titleCase(incident.category)],
    [
      'Classified by',
      `${titleCase(incident.classificationSource)}${incident.classificationConfidence !== null ? ` · ${formatPercent(incident.classificationConfidence * 100, 0)}` : ''}`,
    ],
    ['Impact / urgency', `${titleCase(incident.impact)} / ${titleCase(incident.urgency)}`],
    ['Source', titleCase(incident.source)],
    ['Created', `${formatDateTime(incident.createdAt)}${incident.createdByName ? ` · ${incident.createdByName}` : ''}`],
    ['First response', formatDateTime(incident.firstResponseAt)],
    ['Updated', formatDateTime(incident.updatedAt)],
  ];
  if (incident.resolvedAt) rows.push(['Resolved', `${formatDateTime(incident.resolvedAt)}${incident.resolvedByName ? ` · ${incident.resolvedByName}` : ''}`]);
  if (incident.closedAt) rows.push(['Closed', formatDateTime(incident.closedAt)]);
  if (incident.escalationLevel > 0) rows.push(['Escalation level', incident.escalationLevel]);
  if (incident.correlationGroupId) {
    rows.push([
      'Correlation group',
      <Link key="corr" to={`/observability?tab=correlation&group=${incident.correlationGroupId}${incident.simulated ? '&sim=1' : ''}`}>
        Correlation group #{incident.correlationGroupId}
      </Link>,
    ]);
  }
  return (
    <div className="detail-grid three tight">
      {rows.map(([label, value]) => (
        <div key={label}>
          <small>{label}</small>
          <b>{value}</b>
        </div>
      ))}
    </div>
  );
}

function ResolutionSummary({ incident }: { incident: IncidentDetail }) {
  if (!incident.resolutionCode && !incident.resolutionNotes && !incident.rootCause) return null;
  return (
    <Panel title="Resolution" subtitle={incident.resolutionCode ? titleCase(incident.resolutionCode) : undefined}>
      {incident.resolutionNotes && <p className="description-text">{incident.resolutionNotes}</p>}
      {incident.rootCause && (
        <p className="description-text mt-2">
          <b className="gold-text">Root cause: </b>
          {incident.rootCause}
        </p>
      )}
      {incident.resolutionArticle && (
        <p className="mt-2 text-[11px]">
          Knowledge article:{' '}
          <Link to={`/knowledge/${incident.resolutionArticle.id}`}>
            {incident.resolutionArticle.number} · {incident.resolutionArticle.title}
          </Link>
        </p>
      )}
    </Panel>
  );
}

/** Overview tab (reference screen 3): description, impact, reporter, assignee, details, resolution, comments, attachments. */
export function OverviewTab({ incident, readOnly }: { incident: IncidentDetail; readOnly: boolean }) {
  const loggedBy = incident.createdByName && incident.createdByName !== incident.requesterName ? `logged by ${incident.createdByName}` : null;
  return (
    <>
      <Panel title="Description">
        <p className="description-text">{incident.description || <span className="muted-text">No description provided.</span>}</p>
        <dl className="incident-facts">
          <div>
            <dt>Impact</dt>
            <dd>
              <Badge value={incident.impact} label={titleCase(incident.impact)} />
              <span className="fact-dot" aria-hidden="true" />
              <span>
                ~{formatNumber(incident.affectedUsers)} {incident.affectedUsers === 1 ? 'user' : 'users'}
              </span>
              <span className="muted-text">· urgency {titleCase(incident.urgency).toLowerCase()}</span>
            </dd>
          </div>
          <div>
            <dt>Reported by</dt>
            <dd>{incident.requesterName ? <Person name={incident.requesterName} note={loggedBy} /> : <span className="muted-text">{incident.createdByName ?? 'Unknown'}</span>}</dd>
          </div>
          <div>
            <dt>Assigned to</dt>
            <dd>
              {incident.assigneeName ? (
                <Person name={incident.assigneeName} note={incident.departmentName} />
              ) : (
                <span className="muted-text">
                  Unassigned{incident.teamName ? ` · ${incident.teamName} queue` : incident.departmentName ? ` · ${incident.departmentName} queue` : ''}
                </span>
              )}
            </dd>
          </div>
          <div>
            <dt>Team</dt>
            <dd>
              {incident.teamName ? (
                <span>
                  {incident.teamName}
                  {incident.departmentName && <span className="muted-text"> · {incident.departmentName}</span>}
                </span>
              ) : (
                <span className="muted-text">No team{incident.departmentName ? ` · ${incident.departmentName}` : ''}</span>
              )}
            </dd>
          </div>
        </dl>
      </Panel>
      <Panel title="Details">
        <DetailsGrid incident={incident} />
      </Panel>
      <ResolutionSummary incident={incident} />
      <CommentsPanel incidentId={incident.id} readOnly={readOnly} />
      <AttachmentsPanel incidentId={incident.id} readOnly={readOnly} />
    </>
  );
}
