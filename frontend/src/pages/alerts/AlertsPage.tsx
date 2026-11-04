import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { BellRing, CheckCheck, CheckCircle2, ChevronsUp, EyeOff, Flame, Layers, Plus, SlidersHorizontal, X } from 'lucide-react';
import { useAlertCounts, useAlertGroups, useAlerts, useBulkAlerts } from '../../api/alerts';
import { useCi } from '../../api/cmdb';
import { useServices } from '../../api/services';
import { useTeams } from '../../api/teams';
import { DemoBadge, SimulatedBadge } from '../../components/Badge';
import { CiSinglePicker } from '../../components/cmdb/CiPicker';
import { DataTable, type SortState } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { KpiCard } from '../../components/KpiCard';
import { Modal } from '../../components/Modal';
import { AlertStatusBadge, SeverityBadge } from '../../components/ops/OpsParts';
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
import { errorMessage } from '../../lib/api';
import { ALERT_CONFIG, ALERT_RESPONDERS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import { ALERT_ACTIVE_STATUSES, ALERT_SEVERITIES, ALERT_SOURCE_KINDS, ALERT_SOURCE_LABELS, ALERT_STATUS_LABELS, ALERT_STATUSES } from '../../lib/ops';
import type { AlertGroup, AlertScope, AlertSummary } from '../../lib/opsTypes';
import { titleCase } from '../../lib/format';
import { RoutingRulesPanel, SourcesPanel, SuppressionRulesPanel } from './AlertConfig';
import { CreateAlertModal, durationMinutes, SuppressFields } from './AlertDialogs';

type TabId = 'inbox' | 'groups' | 'suppression' | 'routing' | 'sources';
const TABS: TabId[] = ['inbox', 'groups', 'suppression', 'routing', 'sources'];
const KEYS = ['search', 'status', 'severity', 'source', 'scope', 'serviceId', 'ciId', 'teamId', 'firing', 'hasIncident', 'groupKey', 'sort'] as const;
const PAGE_SIZE = 25;
const STATUS_OPTIONS = [
  { value: 'active', label: 'Active (open + acknowledged)' },
  ...ALERT_STATUSES.map((status) => ({ value: status, label: ALERT_STATUS_LABELS[status] })),
  { value: 'all', label: 'Every status' },
];
const SCOPES: { value: AlertScope; label: string }[] = [
  { value: 'mine', label: 'My teams' },
  { value: 'unassigned', label: 'No team' },
];

function statusParam(value: string): string | undefined {
  if (!value || value === 'active') return ALERT_ACTIVE_STATUSES.join(',');
  if (value === 'all') return undefined;
  return value;
}

function parseSort(value: string): SortState | null {
  const [key, dir] = value.split(',');
  return key ? { key, dir: dir === 'asc' ? 'asc' : 'desc' } : null;
}

function boolParam(value: string): boolean | undefined {
  return value === 'true' ? true : value === 'false' ? false : undefined;
}

type BulkKind = 'ACKNOWLEDGE' | 'RESOLVE' | 'SUPPRESS';

function BulkDialog({ kind, ids, onClose, onDone }: { kind: BulkKind; ids: number[]; onClose: () => void; onDone: () => void }) {
  const bulk = useBulkAlerts();
  const toast = useToast();
  const [note, setNote] = useState('');
  const [duration, setDuration] = useState('240');
  const verb = kind === 'ACKNOWLEDGE' ? 'Acknowledge' : kind === 'RESOLVE' ? 'Resolve' : 'Suppress';
  const finish = (processed: number, skipped: number, messages: string[]) => {
    const text = `${verb.replace(/e$/, '')}ed ${processed} alert${processed === 1 ? '' : 's'}${skipped ? ` · ${skipped} skipped` : ''}`;
    if (skipped) toast.warning(messages.length ? `${text}: ${messages.slice(0, 3).join('; ')}` : text);
    else toast.success(text);
    onDone();
    onClose();
  };
  const run = () => {
    if (kind === 'SUPPRESS') bulk.mutate({ ids, action: 'SUPPRESS', reason: note.trim(), minutes: durationMinutes(duration) }, { onSuccess: (result) => finish(result.processed, result.skipped, result.messages) });
    else bulk.mutate({ ids, action: kind, note: note.trim() || null }, { onSuccess: (result) => finish(result.processed, result.skipped, result.messages) });
  };
  return (
    <Modal
      open
      onClose={onClose}
      title={`${verb} ${ids.length} alert${ids.length === 1 ? '' : 's'}`}
      eyebrow="BULK ACTION"
      description={kind === 'SUPPRESS' ? 'The selected alerts are suppressed in one request with the same reason and duration; alerts that cannot be suppressed are skipped and listed.' : 'Alerts that are not in a suitable state are skipped.'}
      dismissible={!bulk.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={bulk.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={run} disabled={bulk.isPending || (kind === 'SUPPRESS' && note.trim().length < 3)}>
            {bulk.isPending ? 'Working…' : verb}
          </button>
        </>
      }
    >
      {kind === 'SUPPRESS' ? (
        <SuppressFields reason={note} onReason={setNote} duration={duration} onDuration={setDuration} />
      ) : (
        <div className="field">
          <div className="field-label">
            <label htmlFor="bulk-note">Note (optional)</label>
          </div>
          <textarea id="bulk-note" className="control" rows={3} maxLength={1000} value={note} onChange={(event) => setNote(event.target.value)} />
        </div>
      )}
      {bulk.isError && (
        <p className="field-error" role="alert">
          {errorMessage(bulk.error)}
        </p>
      )}
    </Modal>
  );
}

function GroupsView({ scope, onScope, onOpenGroup }: { scope: AlertScope; onScope: (scope: string) => void; onOpenGroup: (group: AlertGroup) => void }) {
  const [status, setStatus] = useState('active');
  const groups = useAlertGroups({ status: statusParam(status), scope, limit: 100 });
  return (
    <Panel title="Alert groups" subtitle="Alerts of one notification group (Alertmanager / Grafana group, CloudWatch alarm), most severe first">
      <div className="toolbar !mb-3">
        <div className={FILTER_ROW_CLASS}>
          <FilterSelect className={FILTER_CLASS} label="Status" allLabel="Active (open + acknowledged)" value={status === 'active' ? '' : status} onChange={(value) => setStatus(value || 'active')} options={STATUS_OPTIONS.filter((option) => option.value !== 'active')} />
          <FilterSelect className={FILTER_CLASS} label="Scope" allLabel="All alerts" value={scope === 'all' ? '' : scope} onChange={onScope} options={SCOPES} />
        </div>
      </div>
      {groups.isLoading && <LoadingState rows={4} />}
      {groups.isError && <ErrorState error={groups.error} onRetry={() => void groups.refetch()} />}
      {groups.data && groups.data.length === 0 && <EmptyState title="No alert groups" description="Grouped alerts from monitoring sources appear here." icon={<Layers size={20} />} />}
      {groups.data && groups.data.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Alert groups">
          {groups.data.map((group) => (
            <li key={group.groupKey} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  <SeverityBadge severity={group.maxSeverity} />
                  <span className="break-words">{group.title}</span>
                </b>
                <small className="block">
                  {formatNumber(group.alertCount)} alert{group.alertCount === 1 ? '' : 's'} · {formatNumber(group.firingCount)} firing · {formatNumber(group.occurrences)} occurrences
                  {group.sourceKind ? ` · ${ALERT_SOURCE_LABELS[group.sourceKind]}` : ''} · last {formatRelative(group.lastSeenAt)}
                </small>
                <small className="block break-all text-muted">{group.groupKey}</small>
              </div>
              <span className="flex shrink-0 items-center gap-1.5">
                {group.incidentId && (
                  <Link to={`/incidents/${group.incidentId}`} className="id-cell">
                    {group.incidentNumber}
                  </Link>
                )}
                <button type="button" className="subtle-btn compact" onClick={() => onOpenGroup(group)} aria-label={`Show the alerts of ${group.title}`}>
                  Show alerts
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** /alerts (OPERATIONS, readers): inbox with counts, filters, groups and bulk actions; admins configure sources and rules. */
export default function AlertsPage() {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const canRespond = hasRole(...ALERT_RESPONDERS);
  const canConfigure = hasRole(...ALERT_CONFIG);
  const allowedTabs = canConfigure ? TABS : TABS.filter((tab) => tab !== 'sources');
  const [tab, setTab] = useTabParam<TabId>(allowedTabs, 'inbox');
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const [more, setMore] = useState(Boolean(values.serviceId || values.ciId || values.teamId || values.firing || values.hasIncident));
  const [selected, setSelected] = useState<Set<string | number>>(new Set());
  const [bulk, setBulk] = useState<BulkKind | null>(null);
  const [creating, setCreating] = useState(false);
  const scope = (values.scope as AlertScope) || 'all';
  const sort = parseSort(values.sort) ?? { key: 'lastSeenAt', dir: 'desc' };
  const counts = useAlertCounts(scope);
  const services = useServices(more);
  const teams = useTeams({ active: true }, more);
  const ciFilter = useCi(values.ciId ? Number(values.ciId) : null);
  const alerts = useAlerts(
    {
      search: values.search || undefined,
      status: statusParam(values.status),
      severity: values.severity || undefined,
      source: values.source || undefined,
      scope: scope === 'all' ? undefined : scope,
      serviceId: values.serviceId ? Number(values.serviceId) : undefined,
      ciId: values.ciId ? Number(values.ciId) : undefined,
      teamId: values.teamId ? Number(values.teamId) : undefined,
      firing: boolParam(values.firing),
      hasIncident: boolParam(values.hasIncident),
      groupKey: values.groupKey || undefined,
      page,
      size: PAGE_SIZE,
      sort: `${sort.key},${sort.dir}`,
    },
    tab === 'inbox',
  );
  const c = counts.data;
  const filtering = Boolean(values.search || values.severity || values.source || values.serviceId || values.ciId || values.teamId || values.firing || values.hasIncident || values.groupKey || (values.status && values.status !== 'active'));
  const rows = alerts.data?.content ?? [];
  const selectedIds = rows.filter((alert) => selected.has(alert.id)).map((alert) => alert.id);
  const clearSelection = () => setSelected(new Set());

  const setFilter = (patch: Partial<Record<(typeof KEYS)[number], string>>) => {
    clearSelection();
    setValues(patch);
  };

  return (
    <>
      <PageHeader
        title="Alerts"
        subtitle="Monitoring alerts, deduplicated and routed to the right team"
        actions={
          canRespond ? (
            <button type="button" className="gold-btn" onClick={() => setCreating(true)}>
              <Plus size={16} aria-hidden="true" /> Raise alert
            </button>
          ) : undefined
        }
      />
      <div className="page-stack">
        <div className="kpi-grid">
          <KpiCard icon={<BellRing />} label="Active alerts" value={formatNumber(c?.active)} tone={c?.active ? 'warning' : 'success'} loading={counts.isLoading} caption={c ? `${formatNumber(c.open)} open · ${formatNumber(c.acknowledged)} acknowledged` : undefined} />
          <KpiCard icon={<Flame />} label="Critical open" value={formatNumber(c?.criticalOpen)} tone={c?.criticalOpen ? 'critical' : 'success'} loading={counts.isLoading} caption={c ? `${formatNumber(c.bySeverity.MAJOR ?? 0)} major active` : undefined} />
          <KpiCard icon={<ChevronsUp />} label="Escalated" value={formatNumber(c?.escalated)} tone={c?.escalated ? 'critical' : 'neutral'} loading={counts.isLoading} caption={c ? `${formatNumber(c.withIncident)} with an incident` : undefined} />
          <KpiCard icon={<EyeOff />} label="Suppressed" value={formatNumber(c?.suppressed)} tone="neutral" loading={counts.isLoading} caption={c ? `${formatNumber(c.resolvedLast24h)} resolved in 24 h` : undefined} />
        </div>
        {counts.isError && <ErrorState error={counts.error} onRetry={() => void counts.refetch()} compact />}

        <Tabs<TabId>
          label="Alert views"
          value={tab}
          onChange={(next) => {
            clearSelection();
            setTab(next);
          }}
          tabs={[
            { id: 'inbox', label: 'Inbox', count: alerts.data?.totalElements },
            { id: 'groups', label: 'Groups' },
            { id: 'suppression', label: 'Suppression rules' },
            { id: 'routing', label: 'Routing rules' },
            { id: 'sources', label: 'Sources', hidden: !canConfigure },
          ]}
        />

        {tab === 'inbox' && (
          <Panel aria-label="Alert inbox">
            <div className="toolbar">
              <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search by number, title or fingerprint…" label="Search alerts" />
              <div className={FILTER_ROW_CLASS}>
                <FilterSelect className={FILTER_CLASS} label="Status" allLabel="Active (open + acknowledged)" value={values.status === 'active' ? '' : values.status} onChange={(status) => setFilter({ status })} options={STATUS_OPTIONS.filter((option) => option.value !== 'active')} />
                <FilterSelect className={FILTER_CLASS} label="Severity" value={values.severity} onChange={(severity) => setFilter({ severity })} options={ALERT_SEVERITIES.map((value) => ({ value, label: titleCase(value) }))} />
                <FilterSelect className={FILTER_CLASS} label="Source" value={values.source} onChange={(source) => setFilter({ source })} options={ALERT_SOURCE_KINDS.map((value) => ({ value, label: ALERT_SOURCE_LABELS[value] }))} />
                <FilterSelect className={FILTER_CLASS} label="Scope" allLabel="All alerts" value={values.scope} onChange={(value) => setFilter({ scope: value })} options={SCOPES} />
                <button type="button" className="subtle-btn compact" aria-expanded={more} onClick={() => setMore((open) => !open)}>
                  <SlidersHorizontal size={12} aria-hidden="true" /> More filters
                </button>
              </div>
            </div>
            {more && (
              <div className="mb-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3" role="group" aria-label="More alert filters">
                <FilterSelect label="Service" value={values.serviceId} onChange={(serviceId) => setFilter({ serviceId })} options={(services.data ?? []).map((service) => ({ value: String(service.id), label: service.name }))} disabled={services.isLoading} />
                <FilterSelect label="Team" value={values.teamId} onChange={(teamId) => setFilter({ teamId })} options={(teams.data ?? []).map((team) => ({ value: String(team.id), label: team.name }))} disabled={teams.isLoading} />
                <FilterSelect
                  label="Firing"
                  allLabel="Firing or not"
                  value={values.firing}
                  onChange={(firing) => setFilter({ firing })}
                  options={[
                    { value: 'true', label: 'Still firing' },
                    { value: 'false', label: 'No longer firing' },
                  ]}
                />
                <FilterSelect
                  label="Incident"
                  allLabel="With or without incident"
                  value={values.hasIncident}
                  onChange={(hasIncident) => setFilter({ hasIncident })}
                  options={[
                    { value: 'true', label: 'Has an incident' },
                    { value: 'false', label: 'No incident' },
                  ]}
                />
                <div className="sm:col-span-2">
                  <CiSinglePicker
                    label="Configuration item"
                    value={values.ciId ? { id: Number(values.ciId), name: ciFilter.data?.name ?? `CI #${values.ciId}`, ciType: ciFilter.data?.ciType } : null}
                    onChange={(ci) => setFilter({ ciId: ci ? String(ci.id) : '' })}
                  />
                </div>
              </div>
            )}
            {values.groupKey && (
              <div className="tag-row mb-3" aria-label="Group filter">
                <span className="chip">
                  Group <span className="break-all">{values.groupKey}</span>
                  <button type="button" className="icon-btn sm !h-4 !w-4" aria-label="Clear the group filter" onClick={() => setFilter({ groupKey: '' })}>
                    <X size={10} />
                  </button>
                </span>
              </div>
            )}
            {canRespond && selectedIds.length > 0 && (
              <div className="bulk-bar" role="toolbar" aria-label="Bulk actions">
                <b>{selectedIds.length} selected</b>
                <button type="button" className="subtle-btn compact" onClick={() => setBulk('ACKNOWLEDGE')}>
                  <CheckCheck size={12} aria-hidden="true" /> Acknowledge
                </button>
                <button type="button" className="subtle-btn compact" onClick={() => setBulk('RESOLVE')}>
                  <CheckCircle2 size={12} aria-hidden="true" /> Resolve
                </button>
                <button type="button" className="subtle-btn compact" onClick={() => setBulk('SUPPRESS')}>
                  <EyeOff size={12} aria-hidden="true" /> Suppress
                </button>
                <button type="button" className="link-btn" onClick={clearSelection}>
                  Clear
                </button>
              </div>
            )}
            {alerts.isLoading && <LoadingState rows={8} label="Loading alerts…" />}
            {alerts.isError && <ErrorState error={alerts.error} onRetry={() => void alerts.refetch()} />}
            {alerts.data && rows.length === 0 && (
              <EmptyState
                title={filtering ? 'No alerts match these filters' : 'No active alerts'}
                description={filtering ? undefined : 'Alerts from Prometheus, Grafana, CloudWatch and signed webhooks appear here.'}
                icon={<BellRing size={20} />}
              />
            )}
            {alerts.data && rows.length > 0 && (
              <>
                <DataTable<AlertSummary>
                  caption="Alerts"
                  rows={rows}
                  rowKey={(alert) => alert.id}
                  rowLabel={(alert) => `${alert.number} ${alert.title}`}
                  onRowClick={(alert) => navigate(`/alerts/${alert.id}`)}
                  sort={sort}
                  onSortChange={(next) => setValues({ sort: `${next.key},${next.dir}` })}
                  selected={canRespond ? selected : undefined}
                  onSelectedChange={canRespond ? setSelected : undefined}
                  isSelectable={(alert) => alert.status !== 'RESOLVED'}
                  columns={[
                    { key: 'number', header: 'Alert', sortKey: 'number', render: (alert) => <span className="id-cell">{alert.number}</span> },
                    {
                      key: 'title',
                      header: 'Title',
                      className: 'summary-cell',
                      render: (alert) => (
                        <span className="inline-flex min-w-0 flex-col">
                          <span className="inline-flex flex-wrap items-center gap-1.5">
                            <span className="break-words font-semibold">{alert.title}</span>
                            {alert.occurrenceCount > 1 && <ToneBadge value="repeats" label={`×${formatNumber(alert.occurrenceCount)}`} tone="champagne" />}
                            {alert.escalationLevel > 0 && <ToneBadge value="escalated" label={`Escalated L${alert.escalationLevel}`} tone="critical" />}
                            {!alert.firing && alert.status !== 'RESOLVED' && <ToneBadge value="not-firing" label="Not firing" tone="muted" />}
                            <DemoBadge demo={alert.demo} /> <SimulatedBadge simulated={alert.simulated} />
                          </span>
                          <small className="text-muted">
                            {[alert.serviceName, alert.ciName].filter(Boolean).join(' · ') || 'No service or CI'} · {alert.sourceName ?? ALERT_SOURCE_LABELS[alert.sourceKind]}
                          </small>
                        </span>
                      ),
                    },
                    { key: 'severity', header: 'Severity', sortKey: 'severity', render: (alert) => <SeverityBadge severity={alert.severity} /> },
                    { key: 'status', header: 'Status', sortKey: 'status', render: (alert) => <AlertStatusBadge status={alert.status} /> },
                    { key: 'team', header: 'Team', render: (alert) => alert.teamName ?? <span className="text-muted">No team</span> },
                    {
                      key: 'incident',
                      header: 'Incident',
                      render: (alert) =>
                        alert.incidentId ? (
                          <Link to={`/incidents/${alert.incidentId}`} className="id-cell" onClick={(event) => event.stopPropagation()}>
                            {alert.incidentNumber}
                          </Link>
                        ) : (
                          '—'
                        ),
                    },
                    { key: 'count', header: 'Count', sortKey: 'occurrenceCount', render: (alert) => formatNumber(alert.occurrenceCount) },
                    { key: 'last', header: 'Last seen', sortKey: 'lastSeenAt', render: (alert) => <span title={formatDateTime(alert.lastSeenAt)}>{formatRelative(alert.lastSeenAt)}</span> },
                  ]}
                />
                <Pagination page={alerts.data.page} totalPages={alerts.data.totalPages} totalElements={alerts.data.totalElements} size={alerts.data.size} onPageChange={(next) => { clearSelection(); setPage(next); }} label="alerts" />
              </>
            )}
          </Panel>
        )}

        {tab === 'groups' && (
          <GroupsView
            scope={scope}
            onScope={(value) => setFilter({ scope: value })}
            onOpenGroup={(group) => {
              // One URL update: back to the inbox (default tab) filtered to the group.
              clearSelection();
              setValues({ groupKey: group.groupKey, status: 'all', tab: '' } as Partial<Record<(typeof KEYS)[number], string>>);
            }}
          />
        )}
        {tab === 'suppression' && <SuppressionRulesPanel canEdit={canConfigure} />}
        {tab === 'routing' && <RoutingRulesPanel canEdit={canConfigure} />}
        {tab === 'sources' && canConfigure && <SourcesPanel />}
      </div>
      {bulk && <BulkDialog kind={bulk} ids={selectedIds} onClose={() => setBulk(null)} onDone={clearSelection} />}
      {creating && <CreateAlertModal onClose={() => setCreating(false)} onCreated={(detail) => navigate(`/alerts/${detail.alert.id}`)} />}
    </>
  );
}
