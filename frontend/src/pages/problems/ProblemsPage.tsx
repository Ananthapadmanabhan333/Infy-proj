import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BookMarked, Layers, Plus, Repeat2, SearchCheck, Timer } from 'lucide-react';
import { useProblemAnalytics, useProblemFromCluster, useProblems, useRecurringClusters } from '../../api/problems';
import { UserChip } from '../../components/Avatar';
import { DemoBadge, PriorityBadge } from '../../components/Badge';
import { DataTable } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { ServiceList, ToneBadge } from '../../components/itsm/ItsmParts';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { FILTER_CLASS, FILTER_ROW_CLASS } from '../../components/servicedesk/requestModel';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useTabParam, useUrlFilters } from '../../hooks/useUrlState';
import { PRIORITIES, PRIORITY_LABELS, PROBLEM_EDITORS } from '../../lib/constants';
import { formatDate, formatNumber, formatRelative } from '../../lib/format';
import { formatHours, PROBLEM_OPEN_STATUSES, PROBLEM_STATUS_LABELS, PROBLEM_STATUS_TONES } from '../../lib/itsm';
import type { ProblemStatus, ProblemSummary, RecurringCluster } from '../../lib/itsmTypes';
import { ProblemEditor } from './ProblemEditor';

type TabId = 'open' | 'NEW' | 'INVESTIGATING' | 'ROOT_CAUSE_IDENTIFIED' | 'KNOWN_ERROR' | 'RESOLVED' | 'CLOSED' | 'all';
const TABS: TabId[] = ['open', 'NEW', 'INVESTIGATING', 'ROOT_CAUSE_IDENTIFIED', 'KNOWN_ERROR', 'RESOLVED', 'CLOSED', 'all'];
const KEYS = ['search', 'priority', 'scope', 'knownError'] as const;
const SCOPES = [
  { value: 'mine', label: 'Owned by me' },
  { value: 'team', label: 'My teams' },
];
const PAGE_SIZE = 20;

function statusFilter(tab: TabId): string | undefined {
  if (tab === 'all') return undefined;
  if (tab === 'open') return PROBLEM_OPEN_STATUSES.join(',');
  return tab;
}

function RecurringPanel({ canCreate }: { canCreate: boolean }) {
  const clusters = useRecurringClusters();
  const fromCluster = useProblemFromCluster();
  const toast = useToast();
  const navigate = useNavigate();
  const [busyKey, setBusyKey] = useState<string | null>(null);

  const create = (cluster: RecurringCluster) => {
    setBusyKey(cluster.key);
    fromCluster.mutate(
      { incidentIds: cluster.incidents.map((incident) => incident.id), title: cluster.suggestedTitle },
      {
        onSuccess: (problem) => {
          toast.success(`${problem.number} created from ${cluster.size} recurring incidents`, { action: { label: `Open ${problem.number}`, to: `/problems/${problem.id}` } });
          navigate(`/problems/${problem.id}`);
        },
        onError: (error) => toast.error(error),
        onSettled: () => setBusyKey(null),
      },
    );
  };

  return (
    <Panel title="Recurring incidents" subtitle="Clusters of similar incidents without a problem (text similarity per service, last 30 days) — computed, not AI-generated">
      {clusters.isLoading && <LoadingState rows={2} />}
      {clusters.isError && <ErrorState error={clusters.error} onRetry={() => void clusters.refetch()} compact />}
      {clusters.data && clusters.data.length === 0 && <EmptyState title="No recurring clusters" description="Similar incidents that keep coming back appear here." icon={<Repeat2 size={18} />} />}
      {clusters.data && clusters.data.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Recurring incident clusters">
          {clusters.data.map((cluster) => (
            <li key={cluster.key} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="block break-words">{cluster.suggestedTitle}</b>
                <small className="block">
                  {cluster.size} incidents · {cluster.serviceName ?? cluster.category ?? 'Unspecified'} · cohesion {Math.round(cluster.cohesion * 100)}% · {formatDate(cluster.firstSeen)} – {formatDate(cluster.lastSeen)}
                </small>
                <small className="block break-words text-muted">{cluster.incidents.map((incident) => incident.number).join(', ')}</small>
              </div>
              {canCreate && (
                <button type="button" className="subtle-btn compact shrink-0" disabled={busyKey !== null} onClick={() => create(cluster)} aria-label={`Create problem from ${cluster.suggestedTitle}`}>
                  <Plus size={12} aria-hidden="true" /> {busyKey === cluster.key ? 'Creating…' : 'Create problem'}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** /problems (SERVICE MANAGEMENT, readers): problems by status, recurring-incident suggestions; problem editors create. */
export default function ProblemsPage() {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const canEdit = hasRole(...PROBLEM_EDITORS);
  const [tab, setTab] = useTabParam<TabId>(TABS, 'open');
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const [creating, setCreating] = useState(false);
  const analytics = useProblemAnalytics();
  const problems = useProblems({
    status: statusFilter(tab),
    search: values.search || undefined,
    priority: values.priority || undefined,
    scope: (values.scope as 'mine' | 'team') || undefined,
    knownError: values.knownError === 'true' ? true : undefined,
    page,
    size: PAGE_SIZE,
  });
  const a = analytics.data;
  const filtering = Boolean(values.search || values.priority || values.scope || values.knownError);

  return (
    <>
      <PageHeader
        title="Problems"
        subtitle="Find and remove the root causes behind recurring incidents"
        actions={
          canEdit ? (
            <button type="button" className="gold-btn" onClick={() => setCreating(true)}>
              <Plus size={16} aria-hidden="true" /> New problem
            </button>
          ) : undefined
        }
      />
      <div className="page-stack">
        <div className="kpi-grid">
          <KpiCard icon={<SearchCheck />} label="Open problems" value={formatNumber(a?.open)} tone={a?.open ? 'warning' : 'success'} loading={analytics.isLoading} caption={a ? `${formatNumber(a.total)} in total` : undefined} />
          <KpiCard icon={<BookMarked />} label="Known errors" value={formatNumber(a?.knownErrors)} tone="gold" loading={analytics.isLoading} caption={a ? `${formatNumber(a.recurrencesAfterKnownError)} recurrences after` : undefined} />
          <KpiCard icon={<Timer />} label="Avg. time to root cause" value={formatHours(a?.avgHoursToRootCause)} tone="neutral" loading={analytics.isLoading} caption={a ? `Resolve: ${formatHours(a.avgHoursToResolve)} · 90 days` : undefined} />
          <KpiCard icon={<Layers />} label="Overdue actions" value={formatNumber(a?.overdueActions)} tone={a?.overdueActions ? 'critical' : 'success'} loading={analytics.isLoading} caption={a ? `${formatNumber(a.recurringClusters)} recurring clusters` : undefined} />
        </div>

        <Tabs<TabId>
          label="Problem status"
          value={tab}
          onChange={setTab}
          tabs={TABS.map((id) => ({
            id,
            label: id === 'open' ? 'Open' : id === 'all' ? 'All' : PROBLEM_STATUS_LABELS[id as ProblemStatus],
            count: id === 'open' ? a?.open : id === 'all' ? a?.total : a?.byStatus?.[id],
          }))}
        />

        <Panel aria-label="Problem list">
          <div className="toolbar">
            <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search problems by number or title…" label="Search problems" />
            <div className={FILTER_ROW_CLASS}>
              <FilterSelect className={FILTER_CLASS} label="Priority" value={values.priority} onChange={(priority) => setValues({ priority })} options={PRIORITIES.map((value) => ({ value, label: PRIORITY_LABELS[value] }))} />
              <FilterSelect className={FILTER_CLASS} label="Scope" allLabel="Everyone's" value={values.scope} onChange={(scope) => setValues({ scope })} options={SCOPES} />
              <FilterSelect className={FILTER_CLASS} label="Known error" allLabel="Any" value={values.knownError} onChange={(knownError) => setValues({ knownError })} options={[{ value: 'true', label: 'Known errors only' }]} />
            </div>
          </div>
          {problems.isLoading && <LoadingState rows={6} label="Loading problems…" />}
          {problems.isError && <ErrorState error={problems.error} onRetry={() => void problems.refetch()} />}
          {problems.data && problems.data.content.length === 0 && (
            <EmptyState
              title={filtering ? 'No problems match these filters' : 'No problems here'}
              description={canEdit && !filtering ? 'Raise a problem for recurring incidents, or create one from a recurring cluster below.' : undefined}
              icon={<SearchCheck size={20} />}
            />
          )}
          {problems.data && problems.data.content.length > 0 && (
            <>
              <DataTable<ProblemSummary>
                caption="Problems"
                rows={problems.data.content}
                rowKey={(problem) => problem.id}
                rowLabel={(problem) => `${problem.number} ${problem.title}`}
                onRowClick={(problem) => navigate(`/problems/${problem.id}`)}
                columns={[
                  { key: 'number', header: 'Problem', render: (problem) => <span className="id-cell">{problem.number}</span> },
                  {
                    key: 'title',
                    header: 'Title',
                    className: 'summary-cell',
                    render: (problem) => (
                      <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                        <span className="break-words font-semibold">{problem.title}</span>
                        {problem.knownError && <ToneBadge value="known-error" label="Known error" tone="warning" />}
                        <DemoBadge demo={problem.demo} />
                      </span>
                    ),
                  },
                  { key: 'status', header: 'Status', render: (problem) => <ToneBadge value={problem.status} label={PROBLEM_STATUS_LABELS[problem.status]} tone={PROBLEM_STATUS_TONES[problem.status]} /> },
                  { key: 'priority', header: 'Priority', render: (problem) => <PriorityBadge priority={problem.priority} /> },
                  { key: 'services', header: 'Services', render: (problem) => <ServiceList services={problem.services} /> },
                  { key: 'incidents', header: 'Incidents', render: (problem) => formatNumber(problem.incidentCount) },
                  { key: 'actions', header: 'Open actions', render: (problem) => formatNumber(problem.openActionCount) },
                  { key: 'owner', header: 'Owner', render: (problem) => <UserChip name={problem.ownerName} emptyLabel={problem.teamName ?? 'Unowned'} /> },
                  { key: 'updated', header: 'Updated', render: (problem) => formatRelative(problem.updatedAt) },
                ]}
              />
              <Pagination page={problems.data.page} totalPages={problems.data.totalPages} totalElements={problems.data.totalElements} size={problems.data.size} onPageChange={setPage} label="problems" />
            </>
          )}
        </Panel>

        <RecurringPanel canCreate={canEdit} />
      </div>
      {creating && <ProblemEditor problem={null} onClose={() => setCreating(false)} onSaved={(problem) => navigate(`/problems/${problem.id}`)} />}
    </>
  );
}
