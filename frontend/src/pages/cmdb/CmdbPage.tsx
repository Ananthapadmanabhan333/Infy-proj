import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Network, Plus, Share2 } from 'lucide-react';
import { useCis, useCmdbGraph } from '../../api/cmdb';
import { UserChip } from '../../components/Avatar';
import { DemoBadge } from '../../components/Badge';
import { DependencyGraph } from '../../components/cmdb/DependencyGraph';
import { DataTable, type SortState } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { CiStatusBadge, CiTypeBadge } from '../../components/ops/OpsParts';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { FILTER_CLASS, FILTER_ROW_CLASS } from '../../components/servicedesk/requestModel';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useTabParam, useUrlFilters } from '../../hooks/useUrlState';
import { CMDB_EDITORS } from '../../lib/constants';
import { formatNumber, formatRelative, titleCase } from '../../lib/format';
import { CI_ENVIRONMENT_LABELS, CI_ENVIRONMENTS, CI_STATUSES, CI_TYPE_LABELS, CI_TYPES, CRITICALITIES, CRITICALITY_TONES } from '../../lib/ops';
import type { CiSummary } from '../../lib/opsTypes';
import { CiEditor } from './CiEditor';

type TabId = 'items' | 'map';
const TABS: TabId[] = ['items', 'map'];
const KEYS = ['search', 'type', 'status', 'environment', 'criticality', 'sort', 'mapType', 'mapEnv', 'retired'] as const;
const PAGE_SIZE = 20;

function parseSort(value: string): SortState | null {
  const [key, dir] = value.split(',');
  return key ? { key, dir: dir === 'desc' ? 'desc' : 'asc' } : null;
}

function MapView({ type, environment, includeRetired }: { type: string; environment: string; includeRetired: boolean }) {
  const navigate = useNavigate();
  const graph = useCmdbGraph({ type: type || undefined, environment: environment || undefined, includeRetired });
  if (graph.isLoading) return <LoadingState variant="panel" label="Loading the dependency map…" />;
  if (graph.isError) return <ErrorState error={graph.error} onRetry={() => void graph.refetch()} />;
  if (!graph.data || graph.data.nodes.length === 0) return <EmptyState title="No configuration items to map" description="Change the filters, or add CIs and relationships." icon={<Share2 size={20} />} />;
  return (
    <>
      {graph.data.truncated && (
        <div className="callout mb-3" role="status">
          <div>The map shows the first {formatNumber(graph.data.nodes.length)} CIs (business services first). Filter by type or environment, or open a CI for its own neighbourhood.</div>
        </div>
      )}
      <DependencyGraph graph={graph.data} onOpen={(id) => navigate(`/cmdb/${id}`)} />
    </>
  );
}

/** /cmdb (OPERATIONS, readers): configuration items and the dependency map; CMDB editors create CIs. */
export default function CmdbPage() {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const canEdit = hasRole(...CMDB_EDITORS);
  const [tab, setTab] = useTabParam<TabId>(TABS, 'items');
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const [creating, setCreating] = useState(false);
  const sort = parseSort(values.sort) ?? { key: 'name', dir: 'asc' };
  const cis = useCis(
    {
      search: values.search || undefined,
      type: values.type || undefined,
      status: values.status || undefined,
      environment: values.environment || undefined,
      criticality: values.criticality || undefined,
      page,
      size: PAGE_SIZE,
      sort: `${sort.key},${sort.dir}`,
    },
    tab === 'items',
  );
  const filtering = Boolean(values.search || values.type || values.status || values.environment || values.criticality);

  return (
    <>
      <PageHeader
        title="CMDB"
        subtitle="Configuration items, relationships and impact"
        actions={
          canEdit ? (
            <button type="button" className="gold-btn" onClick={() => setCreating(true)}>
              <Plus size={16} aria-hidden="true" /> New CI
            </button>
          ) : undefined
        }
      />
      <div className="page-stack">
        <Tabs<TabId>
          label="CMDB views"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'items', label: 'Configuration items', count: cis.data?.totalElements },
            { id: 'map', label: 'Dependency map' },
          ]}
        />

        {tab === 'items' && (
          <Panel aria-label="Configuration item list">
            <div className="toolbar">
              <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search CIs by name or description…" label="Search configuration items" />
              <div className={FILTER_ROW_CLASS}>
                <FilterSelect className={FILTER_CLASS} label="Type" value={values.type} onChange={(type) => setValues({ type })} options={CI_TYPES.map((value) => ({ value, label: CI_TYPE_LABELS[value] }))} />
                <FilterSelect className={FILTER_CLASS} label="Environment" value={values.environment} onChange={(environment) => setValues({ environment })} options={CI_ENVIRONMENTS.map((value) => ({ value, label: CI_ENVIRONMENT_LABELS[value] }))} />
                <FilterSelect className={FILTER_CLASS} label="Status" value={values.status} onChange={(status) => setValues({ status })} options={CI_STATUSES.map((value) => ({ value, label: titleCase(value) }))} />
                <FilterSelect className={FILTER_CLASS} label="Criticality" value={values.criticality} onChange={(criticality) => setValues({ criticality })} options={CRITICALITIES.map((value) => ({ value, label: titleCase(value) }))} />
              </div>
            </div>
            {cis.isLoading && <LoadingState rows={6} label="Loading configuration items…" />}
            {cis.isError && <ErrorState error={cis.error} onRetry={() => void cis.refetch()} />}
            {cis.data && cis.data.content.length === 0 && (
              <EmptyState
                title={filtering ? 'No configuration items match these filters' : 'The CMDB is empty'}
                description={canEdit && !filtering ? 'Add applications, servers, databases and network devices, then relate them.' : undefined}
                icon={<Network size={20} />}
              />
            )}
            {cis.data && cis.data.content.length > 0 && (
              <>
                <DataTable<CiSummary>
                  caption="Configuration items"
                  rows={cis.data.content}
                  rowKey={(ci) => ci.id}
                  rowLabel={(ci) => ci.name}
                  onRowClick={(ci) => navigate(`/cmdb/${ci.id}`)}
                  sort={sort}
                  onSortChange={(next) => setValues({ sort: `${next.key},${next.dir}` })}
                  columns={[
                    {
                      key: 'name',
                      header: 'Name',
                      sortKey: 'name',
                      className: 'summary-cell',
                      render: (ci) => (
                        <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                          <span className="break-words font-semibold">{ci.name}</span>
                          {ci.serviceCode && <span className="id-cell">{ci.serviceCode}</span>}
                          <DemoBadge demo={ci.demo} />
                        </span>
                      ),
                    },
                    { key: 'type', header: 'Type', sortKey: 'ciType', render: (ci) => <CiTypeBadge type={ci.ciType} /> },
                    { key: 'status', header: 'Status', sortKey: 'status', render: (ci) => <CiStatusBadge status={ci.status} /> },
                    { key: 'environment', header: 'Environment', sortKey: 'environment', render: (ci) => (ci.environment ? CI_ENVIRONMENT_LABELS[ci.environment] : '—') },
                    { key: 'criticality', header: 'Criticality', sortKey: 'criticality', render: (ci) => (ci.criticality ? <ToneBadge value={ci.criticality} tone={CRITICALITY_TONES[ci.criticality]} /> : '—') },
                    { key: 'relationships', header: 'Relations', render: (ci) => formatNumber(ci.relationshipCount) },
                    {
                      key: 'alerts',
                      header: 'Open alerts',
                      render: (ci) => (ci.openAlerts ? <ToneBadge value="alerts" label={formatNumber(ci.openAlerts)} tone="critical" /> : <span className="text-muted">0</span>),
                    },
                    { key: 'owner', header: 'Owner', render: (ci) => <UserChip name={ci.ownerName} emptyLabel={ci.teamName ?? 'No owner'} /> },
                    { key: 'updated', header: 'Updated', sortKey: 'updatedAt', render: (ci) => formatRelative(ci.updatedAt) },
                  ]}
                />
                <Pagination page={cis.data.page} totalPages={cis.data.totalPages} totalElements={cis.data.totalElements} size={cis.data.size} onPageChange={setPage} label="configuration items" />
              </>
            )}
          </Panel>
        )}

        {tab === 'map' && (
          <Panel title="Dependency map" subtitle="Dependents above what they need — business services at the top. Select a CI to highlight its upstream and downstream.">
            <div className="toolbar !mb-3">
              <div className={FILTER_ROW_CLASS}>
                <FilterSelect className={FILTER_CLASS} label="Type" value={values.mapType} onChange={(mapType) => setValues({ mapType })} options={CI_TYPES.map((value) => ({ value, label: CI_TYPE_LABELS[value] }))} />
                <FilterSelect className={FILTER_CLASS} label="Environment" value={values.mapEnv} onChange={(mapEnv) => setValues({ mapEnv })} options={CI_ENVIRONMENTS.map((value) => ({ value, label: CI_ENVIRONMENT_LABELS[value] }))} />
                <label className="inline-flex items-center gap-2 text-[11px]">
                  <input type="checkbox" checked={values.retired === 'true'} onChange={(event) => setValues({ retired: event.target.checked ? 'true' : '' })} /> Include retired
                </label>
              </div>
            </div>
            <MapView type={values.mapType} environment={values.mapEnv} includeRetired={values.retired === 'true'} />
          </Panel>
        )}
      </div>
      {creating && <CiEditor ci={null} onClose={() => setCreating(false)} onSaved={(ci) => navigate(`/cmdb/${ci.id}`)} />}
    </>
  );
}
