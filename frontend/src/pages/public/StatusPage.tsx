import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertOctagon,
  AlertTriangle,
  Bell,
  CalendarClock,
  CheckCircle2,
  CircleHelp,
  EyeOff,
  RefreshCw,
  Wrench,
  type LucideIcon,
} from 'lucide-react';
import {
  usePublicStatus,
  useSubscribeToStatus,
  type ComponentStatus,
  type PublicStatus,
  type StatusComponent,
  type StatusIncident,
  type StatusMaintenance,
  type UpdateStatus,
  type UptimeDay,
} from '../../api/statusPage';
import { PublicLayout } from '../../components/public/PublicLayout';
import { usePageMeta } from '../../hooks/usePageMeta';
import { toApiError } from '../../lib/api';
import { pageTitle } from '../../lib/brand';
import { formatDate, formatDateTime } from '../../lib/format';
import { emailSchema } from '../../lib/schemas';
import StatusSubscriptionPage from './StatusSubscriptionPage';
import './status.css';

const STATUS_META: Record<ComponentStatus, { label: string; banner: string; tone: string; icon: LucideIcon }> = {
  OPERATIONAL: { label: 'Operational', banner: 'All systems operational', tone: 'success', icon: CheckCircle2 },
  DEGRADED: { label: 'Degraded performance', banner: 'Some systems are degraded', tone: 'warning', icon: AlertTriangle },
  PARTIAL_OUTAGE: { label: 'Partial outage', banner: 'Partial outage in progress', tone: 'warning', icon: AlertTriangle },
  OUTAGE: { label: 'Outage', banner: 'Some systems are unavailable', tone: 'critical', icon: AlertOctagon },
  MAINTENANCE: { label: 'Under maintenance', banner: 'Maintenance in progress', tone: 'gold', icon: Wrench },
  UNKNOWN: { label: 'Status unknown', banner: 'Current status is not available', tone: 'muted', icon: CircleHelp },
};

const UPDATE_LABELS: Record<UpdateStatus, string> = {
  INVESTIGATING: 'Investigating',
  IDENTIFIED: 'Identified',
  MONITORING: 'Monitoring',
  RESOLVED: 'Resolved',
};

function formatUptime(value: number | null): string {
  if (value === null) return 'No data';
  return `${value >= 99.995 ? '100' : value.toFixed(2)}% uptime`;
}

/** Colour of one day in the uptime bar: the recorded status wins, otherwise the day's uptime. */
export function dayTone(day: UptimeDay | undefined): string {
  if (!day || (day.uptime === null && (day.status === null || day.status === 'UNKNOWN'))) return 'none';
  if (day.status === 'MAINTENANCE') return 'gold';
  if (day.status === 'OUTAGE') return 'critical';
  if (day.status === 'DEGRADED' || day.status === 'PARTIAL_OUTAGE') return 'warning';
  if (day.uptime === null) return 'success';
  if (day.uptime >= 99.5) return 'success';
  if (day.uptime >= 95) return 'warning';
  return 'critical';
}

/** The last `days` calendar days (UTC dates, oldest first), filled from the component history. */
function windowDays(history: UptimeDay[], days: number): (UptimeDay & { missing?: boolean })[] {
  const byDate = new Map(history.map((day) => [day.date.slice(0, 10), day]));
  const today = new Date();
  return Array.from({ length: days }, (_, index) => {
    const date = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() - (days - 1 - index)));
    const key = date.toISOString().slice(0, 10);
    return byDate.get(key) ?? { date: key, uptime: null, status: null, missing: true };
  });
}

function UptimeBars({ component, days }: { component: StatusComponent; days: number }) {
  const cells = windowDays(component.history, days);
  return (
    <div className="sp-uptime">
      <div className="sp-bars" role="img" aria-label={`${component.name}: ${formatUptime(component.uptime)} over the last ${days} days`}>
        {cells.map((day) => (
          <i
            key={day.date}
            className={`sp-bar ${dayTone(day)}`}
            title={`${formatDate(day.date)} — ${day.uptime === null ? 'no data' : `${day.uptime.toFixed(2)}% uptime`}${day.status && day.status !== 'OPERATIONAL' ? ` · ${STATUS_META[day.status].label}` : ''}`}
          />
        ))}
      </div>
      <div className="sp-uptime-scale" aria-hidden="true">
        <span>{days} days ago</span>
        <span className="sp-uptime-value">{formatUptime(component.uptime)}</span>
        <span>Today</span>
      </div>
    </div>
  );
}

function StatusDot({ status }: { status: ComponentStatus }) {
  return <span className={`sp-dot ${STATUS_META[status].tone}`} aria-hidden="true" />;
}

function ComponentsPanel({ status }: { status: PublicStatus }) {
  const groups = new Map<string, StatusComponent[]>();
  status.components.forEach((component) => {
    const key = component.group ?? '';
    groups.set(key, [...(groups.get(key) ?? []), component]);
  });
  return (
    <section className="sp-panel" aria-labelledby="sp-components-title">
      <div className="sp-panel-head">
        <h2 id="sp-components-title">Components</h2>
        <p>Current status and daily uptime for the last {status.historyDays} days.</p>
      </div>
      {status.components.length === 0 ? (
        <p className="sp-empty">No components are published on this status page yet.</p>
      ) : (
        [...groups.entries()].map(([group, components]) => (
          <div key={group || 'ungrouped'} className="sp-group">
            {group && <h3 className="sp-group-title">{group}</h3>}
            <ul className="sp-components">
              {components.map((component) => (
                <li key={component.id} className="sp-component">
                  <div className="sp-component-head">
                    <b>{component.name}</b>
                    <span className={`sp-state ${STATUS_META[component.status].tone}`}>
                      <StatusDot status={component.status} /> {STATUS_META[component.status].label}
                    </span>
                  </div>
                  <UptimeBars component={component} days={status.historyDays} />
                </li>
              ))}
            </ul>
          </div>
        ))
      )}
      <ul className="sp-legend" aria-label="Uptime colours">
        <li>
          <i className="sp-bar success" aria-hidden="true" /> No downtime
        </li>
        <li>
          <i className="sp-bar warning" aria-hidden="true" /> Degraded or partial outage
        </li>
        <li>
          <i className="sp-bar critical" aria-hidden="true" /> Outage
        </li>
        <li>
          <i className="sp-bar gold" aria-hidden="true" /> Maintenance
        </li>
        <li>
          <i className="sp-bar none" aria-hidden="true" /> No data
        </li>
      </ul>
    </section>
  );
}

function IncidentCard({ incident }: { incident: StatusIncident }) {
  return (
    <article className="sp-card" aria-labelledby={`sp-incident-${incident.id}`}>
      <div className="sp-card-head">
        <h3 id={`sp-incident-${incident.id}`}>{incident.title}</h3>
        <span className={`sp-chip ${incident.status === 'RESOLVED' ? 'success' : 'warning'}`}>{UPDATE_LABELS[incident.status]}</span>
      </div>
      {incident.components.length > 0 && <p className="sp-meta">Affects: {incident.components.join(', ')}</p>}
      <ol className="sp-timeline">
        {incident.updates.map((update) => (
          <li key={update.id}>
            <b>{UPDATE_LABELS[update.status]}</b>
            {update.message && <p>{update.message}</p>}
            {update.postedAt && <time dateTime={update.postedAt}>{formatDateTime(update.postedAt)}</time>}
          </li>
        ))}
      </ol>
    </article>
  );
}

function MaintenanceCard({ window: maintenance }: { window: StatusMaintenance }) {
  return (
    <article className="sp-card" aria-labelledby={`sp-maintenance-${maintenance.id}`}>
      <div className="sp-card-head">
        <h3 id={`sp-maintenance-${maintenance.id}`}>{maintenance.title}</h3>
        <span className={`sp-chip ${maintenance.status === 'IN_PROGRESS' ? 'gold' : 'muted'}`}>{maintenance.status === 'IN_PROGRESS' ? 'In progress' : 'Scheduled'}</span>
      </div>
      <p className="sp-meta">
        <CalendarClock size={14} aria-hidden="true" />{' '}
        {maintenance.startsAt ? <time dateTime={maintenance.startsAt}>{formatDateTime(maintenance.startsAt)}</time> : 'Start to be announced'}
        {maintenance.endsAt && (
          <>
            {' '}
            – <time dateTime={maintenance.endsAt}>{formatDateTime(maintenance.endsAt)}</time>
          </>
        )}
      </p>
      {maintenance.description && <p>{maintenance.description}</p>}
      {maintenance.components.length > 0 && <p className="sp-meta">Affects: {maintenance.components.join(', ')}</p>}
    </article>
  );
}

type SubscribeState = { kind: 'idle' } | { kind: 'done'; message: string | null } | { kind: 'error'; message: string };

/** Same wording as the API's generic answer (used when it sends none). */
const SUBSCRIBED_TEXT =
  'Thank you. If this address can receive status updates, a confirmation e-mail is on its way; the subscription starts once you open the link in it.';

function SubscribeForm() {
  const subscribe = useSubscribeToStatus();
  const [email, setEmail] = useState('');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [state, setState] = useState<SubscribeState>({ kind: 'idle' });

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const parsed = emailSchema.safeParse(email);
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message ?? 'Enter a valid email address');
      return;
    }
    setFieldError(null);
    subscribe.mutate(parsed.data, {
      onSuccess: (message) => setState({ kind: 'done', message }),
      onError: (error) => {
        const apiError = toApiError(error);
        if (apiError.status === 429) setState({ kind: 'error', message: 'Too many requests. Please wait a minute and try again.' });
        else if (apiError.status === 404) setState({ kind: 'error', message: 'Subscriptions are not available for this status page.' });
        else setState({ kind: 'error', message: 'We could not process the request right now. Please try again later.' });
      },
    });
  };

  return (
    <section className="sp-panel sp-subscribe" aria-labelledby="sp-subscribe-title">
      <div className="sp-panel-head">
        <h2 id="sp-subscribe-title">
          <Bell size={18} aria-hidden="true" /> Get status updates
        </h2>
        <p>Receive an e-mail when an incident or maintenance is posted. You can unsubscribe from any message.</p>
      </div>
      {state.kind === 'done' ? (
        <p className="sp-confirm" role="status">
          <CheckCircle2 size={16} aria-hidden="true" /> {state.message ?? SUBSCRIBED_TEXT}
        </p>
      ) : (
        <form className="sp-subscribe-form" onSubmit={onSubmit} noValidate>
          <label htmlFor="sp-email">Email</label>
          <div className="sp-subscribe-row">
            <input
              id="sp-email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              aria-invalid={fieldError ? true : undefined}
              aria-describedby={fieldError ? 'sp-email-error' : undefined}
              placeholder="you@company.com"
            />
            <button type="submit" className="pub-btn pub-btn-gold" disabled={subscribe.isPending} aria-busy={subscribe.isPending || undefined}>
              {subscribe.isPending ? 'Subscribing…' : 'Subscribe'}
            </button>
          </div>
          {fieldError && (
            <p id="sp-email-error" className="sp-error" role="alert">
              {fieldError}
            </p>
          )}
          {state.kind === 'error' && (
            <p className="sp-error" role="alert">
              {state.message}
            </p>
          )}
        </form>
      )}
    </section>
  );
}

function NotPublished() {
  return (
    <div className="sp-state-card" role="status">
      <EyeOff size={30} strokeWidth={1.5} aria-hidden="true" />
      <h2>This status page is not published</h2>
      <p>The organisation running this workspace has not made its service status public. Signed-in users can check service health inside the workspace.</p>
      <div className="pub-btn-row">
        <Link to="/login" className="pub-btn pub-btn-gold">
          Sign in
        </Link>
        <Link to="/support" className="pub-btn pub-btn-outline">
          Contact support
        </Link>
      </div>
    </div>
  );
}

function Unavailable({ onRetry, retrying }: { onRetry: () => void; retrying: boolean }) {
  return (
    <div className="sp-state-card" role="alert">
      <AlertTriangle size={30} strokeWidth={1.5} aria-hidden="true" />
      <h2>Status information is unavailable right now</h2>
      <p>The status service could not be reached. This does not necessarily mean the platform is down — please try again in a moment.</p>
      <div className="pub-btn-row">
        <button type="button" className="pub-btn pub-btn-gold" onClick={onRetry} disabled={retrying}>
          <RefreshCw size={16} aria-hidden="true" /> {retrying ? 'Retrying…' : 'Try again'}
        </button>
      </div>
    </div>
  );
}

/** The published status: overall state, incidents, maintenance, components and the subscribe form. */
function StatusOverview() {
  const query = usePublicStatus();
  const notPublished = query.isError && toApiError(query.error).status === 404;
  const status = query.data;
  usePageMeta(pageTitle('Service status'), 'Current status of the services, active incidents and scheduled maintenance.');

  const meta = status ? STATUS_META[status.overall] : null;
  const active = status?.incidents.filter((incident) => incident.status !== 'RESOLVED') ?? [];
  const resolved = status?.incidents.filter((incident) => incident.status === 'RESOLVED') ?? [];
  const BannerIcon = meta?.icon ?? CircleHelp;

  return (
    <PublicLayout className="pub-status-page">
      <div className="pub-container pub-page sp-page">
        <div className="pub-page-hero">
          <p className="pub-kicker">Service status</p>
          <h1>{status?.title ?? 'Service status'}</h1>
          {status?.description && <p>{status.description}</p>}
        </div>

        {query.isPending && (
          <div className="sp-loading" role="status" aria-busy="true">
            <span className="sp-skeleton banner" />
            <span className="sp-skeleton" />
            <span className="sp-skeleton" />
            <span className="pub-sr-only">Loading the current service status…</span>
          </div>
        )}

        {notPublished && <NotPublished />}
        {query.isError && !notPublished && <Unavailable onRetry={() => void query.refetch()} retrying={query.isFetching} />}

        {status && meta && (
          <div className="sp-stack">
            <div className={`sp-banner ${meta.tone}`} role="status" aria-live="polite">
              <BannerIcon size={26} aria-hidden="true" />
              <div>
                <b>{meta.banner}</b>
                {status.updatedAt && (
                  <small>
                    Updated <time dateTime={status.updatedAt}>{formatDateTime(status.updatedAt)}</time>
                  </small>
                )}
              </div>
            </div>

            {active.length > 0 && (
              <section className="sp-panel" aria-labelledby="sp-incidents-title">
                <div className="sp-panel-head">
                  <h2 id="sp-incidents-title">Active incidents</h2>
                </div>
                <div className="sp-cards">
                  {active.map((incident) => (
                    <IncidentCard key={incident.id} incident={incident} />
                  ))}
                </div>
              </section>
            )}

            {status.maintenance.length > 0 && (
              <section className="sp-panel" aria-labelledby="sp-maintenance-title">
                <div className="sp-panel-head">
                  <h2 id="sp-maintenance-title">Scheduled maintenance</h2>
                </div>
                <div className="sp-cards">
                  {status.maintenance.map((window) => (
                    <MaintenanceCard key={window.id} window={window} />
                  ))}
                </div>
              </section>
            )}

            <ComponentsPanel status={status} />

            {resolved.length > 0 && (
              <section className="sp-panel" aria-labelledby="sp-resolved-title">
                <div className="sp-panel-head">
                  <h2 id="sp-resolved-title">Recently resolved</h2>
                </div>
                <div className="sp-cards">
                  {resolved.map((incident) => (
                    <IncidentCard key={incident.id} incident={incident} />
                  ))}
                </div>
              </section>
            )}

            {status.subscriptionsAvailable ? (
              <SubscribeForm />
            ) : (
              <p className="sp-note">E-mail updates are not offered for this status page — check back here for the latest status.</p>
            )}
          </div>
        )}
      </div>
    </PublicLayout>
  );
}

/**
 * `/status` — the public status page (RELEASE3_SPEC §3–4), real data from `GET /api/public/status` only. The e-mail
 * links of the API (`/status?confirm=<token>`, `/status?unsubscribe=<token>`) land here too.
 */
export default function StatusPage() {
  const [params] = useSearchParams();
  if (params.has('confirm')) return <StatusSubscriptionPage action="confirm" token={params.get('confirm')} />;
  if (params.has('unsubscribe')) return <StatusSubscriptionPage action="unsubscribe" token={params.get('unsubscribe')} />;
  return <StatusOverview />;
}
