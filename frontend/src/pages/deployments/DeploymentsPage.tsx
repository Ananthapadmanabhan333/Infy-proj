import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Clock3, PackagePlus, Plus, Rocket, ShieldAlert, Siren, TrendingUp } from 'lucide-react';
import { useDeploymentCalendar, useDeploymentMetrics, useDeployments, useReleases } from '../../api/deployments';
import { useServices } from '../../api/services';
import { DemoBadge, SimulatedBadge } from '../../components/Badge';
import { DataTable } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { DeploymentStatusBadge, ToneBadge } from '../../components/itsm/ItsmParts';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { FILTER_CLASS, FILTER_ROW_CLASS } from '../../components/servicedesk/requestModel';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useTabParam, useUrlFilters } from '../../hooks/useUrlState';
import { DEPLOYMENT_EDITORS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatPercent, formatRelative, titleCase } from '../../lib/format';
import {
  DEPLOYMENT_STATUSES,
  ENVIRONMENT_LABELS,
  ENVIRONMENT_TONES,
  ENVIRONMENTS,
  formatDurationSeconds,
  localDateKey,
  RELEASE_STATUS_TONES,
  RELEASE_STATUSES,
} from '../../lib/itsm';
import type { DeploymentDto, ReleaseDto } from '../../lib/itsmTypes';
import { RecordDeploymentModal, ReleaseModal } from './DeploymentDialogs';
import { WebhookSourcesPanel } from './WebhookSourcesPanel';

type TabId = 'deployments' | 'releases' | 'calendar' | 'integrations';
const TABS: TabId[] = ['deployments', 'releases', 'calendar', 'integrations'];
const KEYS = ['search', 'environment', 'status', 'service', 'releaseStatus'] as const;

function DeploymentTable({ rows, onOpen }: { rows: DeploymentDto[]; onOpen: (deployment: DeploymentDto) => void }) {
  return (
    <DataTable<DeploymentDto>
      caption="Deployments"
      rows={rows}
      rowKey={(deployment) => deployment.id}
      rowLabel={(deployment) => `${deployment.number} ${deployment.serviceName} ${deployment.version}`}
      onRowClick={onOpen}
      columns={[
        { key: 'number', header: 'Deployment', render: (deployment) => <span className="id-cell">{deployment.number}</span> },
        {
          key: 'service',
          header: 'Service · version',
          className: 'summary-cell',
          render: (deployment) => (
            <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
              <span className="font-semibold">{deployment.serviceName}</span>
              <span className="text-muted">{deployment.version}</span>
              {deployment.rollbackOfNumber && <ToneBadge value="rollback" label={`Rollback of ${deployment.rollbackOfNumber}`} tone="warning" />}
              <DemoBadge demo={deployment.demo} /> <SimulatedBadge simulated={deployment.simulated} />
            </span>
          ),
        },
        { key: 'environment', header: 'Environment', render: (deployment) => <ToneBadge value={deployment.environment} label={ENVIRONMENT_LABELS[deployment.environment]} tone={ENVIRONMENT_TONES[deployment.environment]} /> },
        { key: 'status', header: 'Status', render: (deployment) => <DeploymentStatusBadge status={deployment.status} /> },
        {
          key: 'change',
          header: 'Change',
          render: (deployment) =>
            deployment.changeId ? (
              <Link to={`/changes/${deployment.changeId}`} className="id-cell" onClick={(event) => event.stopPropagation()}>
                {deployment.changeNumber}
              </Link>
            ) : deployment.environment === 'PRODUCTION' ? (
              <span className="warning-text">No change</span>
            ) : (
              '—'
            ),
        },
        { key: 'source', header: 'Source', render: (deployment) => deployment.sourceName ?? titleCase(deployment.source) },
        { key: 'started', header: 'Started', render: (deployment) => <span title={formatDateTime(deployment.startedAt)}>{formatRelative(deployment.startedAt)}</span> },
        { key: 'duration', header: 'Duration', render: (deployment) => formatDurationSeconds(deployment.durationSeconds) },
      ]}
    />
  );
}

function ReleasesPanel({ canEdit }: { canEdit: boolean }) {
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const releases = useReleases({ search: values.search || undefined, status: values.releaseStatus || undefined, page, size: 20 });
  const [editing, setEditing] = useState<ReleaseDto | 'new' | null>(null);
  return (
    <Panel
      aria-label="Releases"
      actions={
        canEdit ? (
          <button type="button" className="gold-btn compact" onClick={() => setEditing('new')}>
            <PackagePlus size={13} aria-hidden="true" /> New release
          </button>
        ) : undefined
      }
    >
      <div className="toolbar">
        <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search releases…" label="Search releases" />
        <div className={FILTER_ROW_CLASS}>
          <FilterSelect className={FILTER_CLASS} label="Status" value={values.releaseStatus} onChange={(releaseStatus) => setValues({ releaseStatus })} options={RELEASE_STATUSES.map((value) => ({ value, label: titleCase(value) }))} />
        </div>
      </div>
      {releases.isLoading && <LoadingState rows={4} />}
      {releases.isError && <ErrorState error={releases.error} onRetry={() => void releases.refetch()} />}
      {releases.data && releases.data.content.length === 0 && <EmptyState title="No releases" icon={<PackagePlus size={18} />} />}
      {releases.data && releases.data.content.length > 0 && (
        <>
          <DataTable<ReleaseDto>
            caption="Releases"
            rows={releases.data.content}
            rowKey={(release) => release.id}
            rowLabel={(release) => `${release.number} ${release.name}`}
            onRowClick={canEdit ? (release) => setEditing(release) : undefined}
            columns={[
              { key: 'number', header: 'Release', render: (release) => <span className="id-cell">{release.number}</span> },
              {
                key: 'name',
                header: 'Name · version',
                className: 'summary-cell',
                render: (release) => (
                  <span className="inline-flex flex-wrap items-center gap-1.5">
                    <span className="font-semibold">{release.name}</span> <span className="text-muted">{release.version}</span> <DemoBadge demo={release.demo} />
                  </span>
                ),
              },
              { key: 'status', header: 'Status', render: (release) => <ToneBadge value={release.status} tone={RELEASE_STATUS_TONES[release.status]} /> },
              { key: 'service', header: 'Service', render: (release) => release.serviceName ?? '—' },
              {
                key: 'change',
                header: 'Change',
                render: (release) =>
                  release.changeId ? (
                    <Link to={`/changes/${release.changeId}`} className="id-cell" onClick={(event) => event.stopPropagation()}>
                      {release.changeNumber}
                    </Link>
                  ) : (
                    '—'
                  ),
              },
              { key: 'deployments', header: 'Deployments', render: (release) => formatNumber(release.deploymentCount) },
              { key: 'target', header: 'Target', render: (release) => (release.targetAt ? formatDateTime(release.targetAt) : '—') },
            ]}
          />
          <Pagination page={releases.data.page} totalPages={releases.data.totalPages} totalElements={releases.data.totalElements} size={releases.data.size} onPageChange={setPage} label="releases" />
        </>
      )}
      {editing && <ReleaseModal release={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Panel>
  );
}

function DeploymentCalendarPanel() {
  const navigate = useNavigate();
  const [month, setMonth] = useState(() => new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  const [environment, setEnvironment] = useState('');
  const from = useMemo(() => new Date(month.getFullYear(), month.getMonth(), 1), [month]);
  const to = useMemo(() => new Date(month.getFullYear(), month.getMonth() + 1, 1), [month]);
  const calendar = useDeploymentCalendar(from.toISOString(), to.toISOString(), undefined, environment || undefined);
  const byDate = new Map((calendar.data?.days ?? []).map((day) => [day.date, day]));
  const lead = (from.getDay() + 6) % 7;
  const daysInMonth = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
  const cells = [...Array.from({ length: lead }, () => null), ...Array.from({ length: daysInMonth }, (_, index) => new Date(month.getFullYear(), month.getMonth(), index + 1))];
  const busyDays = (calendar.data?.days ?? []).filter((day) => day.deployments.length > 0);

  return (
    <Panel aria-label="Deployment calendar">
      <div className="toolbar">
        <div className="flex items-center gap-2">
          <button type="button" className="icon-btn" aria-label="Previous month" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1))}>
            <ChevronLeft size={16} />
          </button>
          <b className="min-w-[140px] text-center text-[13px]" aria-live="polite">
            {month.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}
          </b>
          <button type="button" className="icon-btn" aria-label="Next month" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1))}>
            <ChevronRight size={16} />
          </button>
        </div>
        <FilterSelect label="Environment" allLabel="All environments" value={environment} onChange={setEnvironment} options={ENVIRONMENTS.map((value) => ({ value, label: ENVIRONMENT_LABELS[value] }))} />
      </div>
      {calendar.isLoading && <LoadingState variant="panel" />}
      {calendar.isError && <ErrorState error={calendar.error} onRetry={() => void calendar.refetch()} />}
      {calendar.data && (
        <>
          <div className="hidden md:block">
            <div className="grid grid-cols-7 gap-1 text-center text-[10px] uppercase text-muted">
              {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((day) => (
                <div key={day}>{day}</div>
              ))}
            </div>
            <div className="mt-1 grid grid-cols-7 gap-1">
              {cells.map((day, index) => {
                if (!day) return <div key={`blank-${index}`} />;
                const entry = byDate.get(localDateKey(day));
                const total = entry ? entry.deployments.length : 0;
                return (
                  <div key={localDateKey(day)} className="min-h-[78px] min-w-0 rounded-[8px] border border-line p-1 text-[10px]" aria-label={`${day.toDateString()}: ${total} deployments`}>
                    <div className="text-muted">{day.getDate()}</div>
                    {entry && total > 0 && (
                      <div className="mt-0.5 grid gap-0.5">
                        {entry.succeeded > 0 && <span className="green-text">{entry.succeeded} succeeded</span>}
                        {entry.failed > 0 && <span className="red-text">{entry.failed} failed</span>}
                        {entry.rolledBack > 0 && <span className="red-text">{entry.rolledBack} rolled back</span>}
                        {entry.inProgress > 0 && <span className="warning-text">{entry.inProgress} running</span>}
                        {entry.deployments.slice(0, 2).map((deployment) => (
                          <Link key={deployment.id} to={`/deployments/${deployment.id}`} className="truncate text-[9px]" title={`${deployment.number} ${deployment.serviceName} ${deployment.version}`}>
                            {deployment.serviceName} {deployment.version}
                          </Link>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
          <div className="md:hidden">
            {busyDays.length === 0 ? (
              <EmptyState title="No deployments this month" icon={<Rocket size={18} />} />
            ) : (
              <ol className="m-0 grid list-none gap-2 p-0">
                {busyDays.map((day) => (
                  <li key={day.date}>
                    <b className="text-[11px]">{new Date(`${day.date}T12:00:00`).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}</b>
                    <ul className="m-0 mt-1 grid list-none gap-1 p-0">
                      {day.deployments.map((deployment) => (
                        <li key={deployment.id}>
                          <button type="button" className="list-row w-full border-0 bg-transparent text-left" onClick={() => navigate(`/deployments/${deployment.id}`)}>
                            <span className="min-w-0 truncate text-[11px]">
                              {deployment.number} · {deployment.serviceName} {deployment.version}
                            </span>
                            <DeploymentStatusBadge status={deployment.status} />
                          </button>
                        </li>
                      ))}
                    </ul>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </>
      )}
    </Panel>
  );
}

/** /deployments (OPERATIONS, readers): deployments, releases, deployment calendar and CI/CD integration. */
export default function DeploymentsPage() {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const canEdit = hasRole(...DEPLOYMENT_EDITORS);
  const [tab, setTab] = useTabParam<TabId>(TABS, 'deployments', ['search', 'status', 'releaseStatus']);
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const [recording, setRecording] = useState(false);
  const services = useServices();
  const metrics = useDeploymentMetrics();
  const deployments = useDeployments(
    { search: values.search || undefined, environment: values.environment || undefined, status: values.status || undefined, serviceId: values.service ? Number(values.service) : undefined, page, size: 20 },
    tab === 'deployments',
  );
  const m = metrics.data;
  const filtering = Boolean(values.search || values.environment || values.status || values.service);

  return (
    <>
      <PageHeader
        title="Deployments"
        subtitle="Releases and deployments across environments"
        actions={
          canEdit ? (
            <button type="button" className="gold-btn" onClick={() => setRecording(true)}>
              <Plus size={16} aria-hidden="true" /> Record deployment
            </button>
          ) : undefined
        }
      />
      <div className="page-stack">
        <div className="kpi-grid">
          <KpiCard icon={<Rocket />} label="Deployments · 30 days" value={formatNumber(m?.total)} loading={metrics.isLoading} caption={m ? `${formatNumber(m.byEnvironment?.PRODUCTION ?? 0)} to production` : undefined} />
          <KpiCard icon={<TrendingUp />} label="Success rate" value={formatPercent(m?.successRate)} tone="success" loading={metrics.isLoading} caption={m ? `Rollback rate ${formatPercent(m.rollbackRate)}` : undefined} />
          <KpiCard icon={<ShieldAlert />} label="Production without change" value={formatNumber(m?.productionWithoutChange)} tone={m?.productionWithoutChange ? 'warning' : 'success'} loading={metrics.isLoading} />
          <KpiCard icon={<Siren />} label="Followed by incidents" value={formatNumber(m?.followedByIncidents)} tone={m?.followedByIncidents ? 'critical' : 'success'} loading={metrics.isLoading} caption={m?.avgDurationMinutes != null ? `Avg. duration ${Math.round(m.avgDurationMinutes)} min` : undefined} />
        </div>
        <Tabs<TabId>
          label="Deployment views"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'deployments', label: 'Deployments' },
            { id: 'releases', label: 'Releases' },
            { id: 'calendar', label: 'Calendar' },
            { id: 'integrations', label: 'CI/CD integration' },
          ]}
        />
        {tab === 'deployments' && (
          <Panel aria-label="Deployment list">
            <div className="toolbar">
              <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search by number, version or commit…" label="Search deployments" />
              <div className={FILTER_ROW_CLASS}>
                <FilterSelect className={FILTER_CLASS} label="Environment" value={values.environment} onChange={(environment) => setValues({ environment })} options={ENVIRONMENTS.map((value) => ({ value, label: ENVIRONMENT_LABELS[value] }))} />
                <FilterSelect className={FILTER_CLASS} label="Status" value={values.status} onChange={(status) => setValues({ status })} options={DEPLOYMENT_STATUSES.map((value) => ({ value, label: titleCase(value) }))} />
                <FilterSelect className={FILTER_CLASS} label="Service" value={values.service} onChange={(service) => setValues({ service })} options={(services.data ?? []).map((service) => ({ value: String(service.id), label: service.name }))} />
              </div>
            </div>
            {deployments.isLoading && <LoadingState rows={6} label="Loading deployments…" />}
            {deployments.isError && <ErrorState error={deployments.error} onRetry={() => void deployments.refetch()} />}
            {deployments.data && deployments.data.content.length === 0 && (
              <EmptyState title={filtering ? 'No deployments match these filters' : 'No deployments recorded'} description={!filtering ? 'Connect a pipeline under “CI/CD integration”, or record a deployment manually.' : undefined} icon={<Clock3 size={18} />} />
            )}
            {deployments.data && deployments.data.content.length > 0 && (
              <>
                <DeploymentTable rows={deployments.data.content} onOpen={(deployment) => navigate(`/deployments/${deployment.id}`)} />
                <Pagination page={deployments.data.page} totalPages={deployments.data.totalPages} totalElements={deployments.data.totalElements} size={deployments.data.size} onPageChange={setPage} label="deployments" />
              </>
            )}
          </Panel>
        )}
        {tab === 'releases' && <ReleasesPanel canEdit={canEdit} />}
        {tab === 'calendar' && <DeploymentCalendarPanel />}
        {tab === 'integrations' && <WebhookSourcesPanel canEdit={canEdit} />}
      </div>
      {recording && <RecordDeploymentModal onClose={() => setRecording(false)} onCreated={(deployment) => navigate(`/deployments/${deployment.id}`)} />}
    </>
  );
}
