import { Link } from 'react-router-dom';
import { Activity, AlertTriangle, BellRing, CalendarClock, GitPullRequestArrow, Rocket, Siren, Workflow } from 'lucide-react';
import { formatMinutes, formatNumber, formatPercent, formatRelative } from '../../lib/format';
import { MAJOR_STATUS_LABELS, MAJOR_STATUS_TONES } from '../../lib/response';
import type { CommandCenterBlock as CommandCenterData, MajorStatus } from '../../lib/responseTypes';
import { ToneBadge } from '../itsm/ItsmParts';
import { SourceBadge } from '../oncall/OnCallParts';
import { Panel } from '../Panel';
import { EmptyState } from '../States';

type Stat = [label: string, value: string, tone?: 'red' | 'warning' | 'green'];

function StatPanel({ title, icon, to, stats }: { title: string; icon: JSX.Element; to: string; stats: Stat[] }) {
  return (
    <Panel
      title={
        <span className="inline-flex items-center gap-2">
          <span className="gold-text" aria-hidden="true">
            {icon}
          </span>
          {title}
        </span>
      }
      action="Open"
      to={to}
    >
      <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-2" aria-label={`${title} figures`}>
        {stats.map(([label, value, tone]) => (
          <div key={label} className="min-w-0">
            <dt className="truncate text-[10px] text-muted">{label}</dt>
            <dd className={`m-0 text-[15px] font-semibold ${tone === 'red' ? 'red-text' : tone === 'warning' ? 'warning-text' : tone === 'green' ? 'green-text' : ''}`}>{value}</dd>
          </div>
        ))}
      </dl>
    </Panel>
  );
}

const ACTIVITY_ICONS: Record<string, JSX.Element> = {
  INCIDENT: <AlertTriangle size={13} />,
  ALERT: <BellRing size={13} />,
  CHANGE: <GitPullRequestArrow size={13} />,
  DEPLOYMENT: <Rocket size={13} />,
  MAJOR_INCIDENT: <Siren size={13} />,
};

/** In-app routes only (data never decides an absolute URL). */
function safeLink(link: string | null): string | null {
  return link && link.startsWith('/') && !link.startsWith('//') ? link : null;
}

/**
 * Release 3 Command Center block of `GET /analytics/dashboard` (staff and auditors; `null` for employees, then not rendered).
 * Live counts are the current state; "in period" counts follow the dashboard period.
 */
export function CommandCenterBlock({ data, days }: { data: CommandCenterData; days: number }) {
  const { incidents, alerts, changes, workflows } = data;
  const successRate = workflows.executions24h ? (workflows.succeeded24h / workflows.executions24h) * 100 : null;
  return (
    <>
      <div className="grid gap-[14px] sm:grid-cols-2 xl:grid-cols-4">
        <StatPanel
          title="Incidents"
          icon={<AlertTriangle size={14} />}
          to="/incidents?tab=open"
          stats={[
            ['Open', formatNumber(incidents.open)],
            ['Critical open', formatNumber(incidents.criticalOpen), incidents.criticalOpen ? 'red' : undefined],
            ['Major open', formatNumber(incidents.majorOpen), incidents.majorOpen ? 'red' : undefined],
            ['SLA at risk now', formatNumber(incidents.slaAtRiskNow), incidents.slaAtRiskNow ? 'warning' : undefined],
            [`Created · ${days} d`, formatNumber(incidents.createdInPeriod)],
            [`Resolved · ${days} d`, formatNumber(incidents.resolvedInPeriod), 'green'],
            [`SLA breaches · ${days} d`, formatNumber(incidents.slaBreachesInPeriod), incidents.slaBreachesInPeriod ? 'red' : undefined],
            ['MTTA', formatMinutes(incidents.mttaMinutes)],
          ]}
        />
        <StatPanel
          title="Alerts"
          icon={<BellRing size={14} />}
          to="/alerts"
          stats={[
            ['Active', formatNumber(alerts.active), alerts.active ? 'warning' : undefined],
            ['Critical', formatNumber(alerts.critical), alerts.critical ? 'red' : undefined],
            ['Open', formatNumber(alerts.open)],
            ['Acknowledged', formatNumber(alerts.acknowledged)],
            ['Escalated', formatNumber(alerts.escalated), alerts.escalated ? 'warning' : undefined],
            ['Resolved · 24 h', formatNumber(alerts.resolvedLast24h), 'green'],
          ]}
        />
        <StatPanel
          title="Changes"
          icon={<GitPullRequestArrow size={14} />}
          to="/changes"
          stats={[
            ['Awaiting approval', formatNumber(changes.awaitingApproval), changes.awaitingApproval ? 'warning' : undefined],
            ['Implementing now', formatNumber(changes.implementingNow)],
            ['Scheduled · next 7 d', formatNumber(changes.scheduledNext7Days)],
            ['Emergency open', formatNumber(changes.emergencyOpen), changes.emergencyOpen ? 'red' : undefined],
            [`Completed · ${days} d`, formatNumber(changes.completedInPeriod), 'green'],
            [`Failed · ${days} d`, formatNumber(changes.failedInPeriod), changes.failedInPeriod ? 'red' : undefined],
          ]}
        />
        <StatPanel
          title="Workflows · 24 h"
          icon={<Workflow size={14} />}
          to="/automation"
          stats={[
            ['Executions', formatNumber(workflows.executions24h)],
            ['Success rate', formatPercent(successRate, 0)],
            ['Succeeded', formatNumber(workflows.succeeded24h), 'green'],
            ['Failed', formatNumber(workflows.failed24h), workflows.failed24h ? 'red' : undefined],
            ['Running now', formatNumber(workflows.running)],
          ]}
        />
      </div>

      <div className="grid items-start gap-[14px] xl:grid-cols-2">
        <Panel title="Active major incidents" action="View all" to="/major-incidents">
          {data.activeMajorIncidents.length === 0 ? (
            <EmptyState title="No active major incidents" icon={<Siren size={18} />} />
          ) : (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Active major incidents">
              {data.activeMajorIncidents.map((major) => (
                <li key={major.id} className="list-row flex-wrap sm:flex-nowrap">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <Link to={`/major-incidents/${major.id}`} className="id-cell">
                        {major.number}
                      </Link>
                      <span className="min-w-0 break-words">{major.title}</span>
                    </b>
                    <small className="block">
                      Commander {major.commanderName ?? 'not assigned'} · declared {formatRelative(major.declaredAt)}
                    </small>
                  </div>
                  <ToneBadge value={major.status} label={MAJOR_STATUS_LABELS[major.status as MajorStatus] ?? major.status} tone={MAJOR_STATUS_TONES[major.status as MajorStatus] ?? 'neutral'} />
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <Panel title="On call now" action="On-call" to="/on-call">
          {data.onCallNow.length === 0 ? (
            <EmptyState title="No active on-call schedules" icon={<CalendarClock size={18} />} />
          ) : (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="On call now">
              {data.onCallNow.map((entry) => (
                <li key={entry.scheduleId} className="list-row flex-wrap sm:flex-nowrap">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <Link to={`/on-call/${entry.scheduleId}`}>{entry.teamName ?? entry.scheduleName}</Link>
                    </b>
                    <small className="flex flex-wrap items-center gap-1.5">
                      <span>Primary {entry.primary?.name ?? 'nobody'}</span>
                      {entry.primary && <SourceBadge source={entry.primary.source} />}
                      <span>· secondary {entry.secondary?.name ?? 'nobody'}</span>
                    </small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      <div className="grid items-start gap-[14px] xl:grid-cols-[1fr_1fr]">
        <Panel title="Department performance" subtitle={`Last ${days} days`} action="Analytics" to="/analytics?tab=metrics">
          {data.departmentPerformance.length === 0 ? (
            <EmptyState title="No department data" />
          ) : (
            <div className="overflow-x-auto">
              <table className="data-table" aria-label="Department performance">
                <thead>
                  <tr>
                    <th scope="col">Department</th>
                    <th scope="col">Open</th>
                    <th scope="col">Resolved</th>
                    <th scope="col">MTTR</th>
                    <th scope="col">SLA</th>
                  </tr>
                </thead>
                <tbody>
                  {data.departmentPerformance.map((row) => (
                    <tr key={row.departmentId}>
                      <td>{row.name}</td>
                      <td>{formatNumber(row.open)}</td>
                      <td>{formatNumber(row.resolved)}</td>
                      <td>{formatMinutes(row.mttrMinutes)}</td>
                      <td>{formatPercent(row.slaCompliance, 1)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
        <Panel
          title={
            <span className="inline-flex items-center gap-2">
              <Activity size={14} className="gold-text" aria-hidden="true" /> Recent activity
            </span>
          }
        >
          {data.recentActivity.length === 0 ? (
            <EmptyState title="No recent activity" />
          ) : (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Recent activity">
              {data.recentActivity.map((entry, index) => {
                const link = safeLink(entry.link);
                return (
                  <li key={`${entry.kind}-${entry.at}-${index}`} className="list-row">
                    <span className="icon-tile sm" aria-hidden="true">
                      {ACTIVITY_ICONS[entry.kind] ?? <Activity size={13} />}
                    </span>
                    <div className="min-w-0">
                      <b className="block break-words">{link ? <Link to={link}>{entry.title}</Link> : entry.title}</b>
                      <small className="block break-words">
                        {entry.detail ? `${entry.detail} · ` : ''}
                        {entry.actorName ? `${entry.actorName} · ` : ''}
                        {formatRelative(entry.at)}
                      </small>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </Panel>
      </div>
    </>
  );
}
