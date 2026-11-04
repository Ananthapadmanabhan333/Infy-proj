import { useCallback, useEffect, useMemo, useState } from 'react';
import { Download, FilterX, Plus } from 'lucide-react';
import { useIncidentCounts, useIncidents, type IncidentCountParams, type IncidentListParams } from '../../api/incidents';
import { useServices } from '../../api/services';
import { useTeams } from '../../api/teams';
import { useAssignableUsers } from '../../api/users';
import { FilterSelect } from '../../components/FilterSelect';
import { IncidentTable } from '../../components/incidents/IncidentTable';
import { useNewIncident } from '../../components/incidents/NewIncidentContext';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { SearchInput } from '../../components/SearchInput';
import { ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useUrlFilters } from '../../hooks/useUrlState';
import { downloadFile } from '../../lib/api';
import {
  ACTIVE_STATUSES,
  DEFAULT_PAGE_SIZE,
  INCIDENT_STATUSES,
  PRIORITIES,
  PRIORITY_LABELS,
  REPORT_EXPORTERS,
  RESOLVED_STATUSES,
  STAFF,
  STATUS_LABELS,
} from '../../lib/constants';
import type { IncidentStatus, IncidentSummary } from '../../lib/types';
import { BulkBar } from './BulkActions';
import './incidents.css';

export type IncidentTabId = 'all' | 'open' | 'in_progress' | 'resolved';
const TAB_IDS: IncidentTabId[] = ['all', 'open', 'in_progress', 'resolved'];

/** Statuses behind each tab (§3: open = active, resolved = RESOLVED + CLOSED). */
export const TAB_STATUSES: Record<IncidentTabId, IncidentStatus[]> = {
  all: INCIDENT_STATUSES,
  open: ACTIVE_STATUSES,
  in_progress: ['IN_PROGRESS'],
  resolved: RESOLVED_STATUSES,
};

const FILTER_KEYS = ['tab', 'search', 'status', 'priority', 'serviceId', 'assigneeId', 'teamId', 'scope', 'sort'] as const;
/** R3: `team` = incidents of the teams I belong to; `department` keeps the Release 2 department scope. */
const SCOPES = [
  { value: 'mine', label: 'Assigned to me' },
  { value: 'team', label: 'My team' },
  { value: 'department', label: 'My department' },
];
const SCOPE_VALUES = SCOPES.map((option) => option.value);

export default function IncidentsPage() {
  const { user, hasRole } = useAuth();
  const toast = useToast();
  const newIncident = useNewIncident();
  const staff = hasRole(...STAFF);
  const auditor = user?.role === 'AUDITOR';
  const employee = user?.role === 'EMPLOYEE';
  const canExport = hasRole(...REPORT_EXPORTERS);
  const { values, setValues, page, setPage } = useUrlFilters(FILTER_KEYS, { sort: 'createdAt,desc' });

  // Release 1 links used ?tab=mine / ?tab=critical: map them onto the new tabs and filters once.
  useEffect(() => {
    if (values.tab === 'mine') setValues({ tab: '', scope: staff ? 'mine' : '' });
    else if (values.tab === 'critical') setValues({ tab: 'open', priority: values.priority || 'CRITICAL' });
  }, [values.tab, values.priority, staff, setValues]);

  const tab: IncidentTabId = (TAB_IDS as string[]).includes(values.tab) ? (values.tab as IncidentTabId) : 'all';
  const tabStatuses = TAB_STATUSES[tab];
  const status = (tabStatuses as string[]).includes(values.status) ? values.status : '';
  const scope = staff && SCOPE_VALUES.includes(values.scope) ? (values.scope as 'mine' | 'team' | 'department') : '';
  const assigneeId = staff ? values.assigneeId : '';
  const teamId = staff ? values.teamId : '';

  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const services = useServices();
  const assignees = useAssignableUsers(null, staff);
  const teams = useTeams({ active: true }, staff);

  /** Filters shared by the list and the tab counts (the tab's own status set is not one of them). */
  const sharedFilters = useMemo<IncidentCountParams>(
    () => ({
      search: values.search || undefined,
      priority: values.priority || undefined,
      serviceId: values.serviceId || undefined,
      assigneeId: assigneeId || undefined,
      teamId: teamId || undefined,
      scope: scope || undefined,
    }),
    [values.search, values.priority, values.serviceId, assigneeId, teamId, scope],
  );

  const params = useMemo<IncidentListParams>(
    () => ({
      ...sharedFilters,
      page,
      size: DEFAULT_PAGE_SIZE,
      sort: values.sort || 'createdAt,desc',
      status: status || (tab === 'all' ? undefined : tabStatuses.join(',')),
    }),
    [sharedFilters, page, values.sort, status, tab, tabStatuses],
  );

  const incidents = useIncidents(params);
  const counts = useIncidentCounts(sharedFilters);

  // ---- selection (staff only): kept across pages, cleared when the filters change
  const [selection, setSelection] = useState<Map<number, IncidentSummary>>(() => new Map());
  const filterKey = JSON.stringify([sharedFilters, status, tab]);
  useEffect(() => setSelection(new Map()), [filterKey]);
  useEffect(() => {
    // Keep the stored rows fresh (status, assignee) after a refetch.
    const rows = incidents.data?.content;
    if (!rows?.length) return;
    setSelection((current) => {
      if (!current.size || !rows.some((row) => current.has(row.id))) return current;
      const next = new Map(current);
      rows.forEach((row) => {
        if (next.has(row.id)) next.set(row.id, row);
      });
      return next;
    });
  }, [incidents.data]);

  const selectedIds = useMemo(() => new Set(selection.keys()), [selection]);
  const onSelectedChange = (ids: Set<number>) => {
    const rows = incidents.data?.content ?? [];
    setSelection((current) => {
      const next = new Map<number, IncidentSummary>();
      ids.forEach((id) => {
        const row = current.get(id) ?? rows.find((incident) => incident.id === id);
        if (row) next.set(id, row);
      });
      return next;
    });
  };
  const onApplied = useCallback(
    (ids: number[]) =>
      setSelection((current) => {
        const next = new Map(current);
        ids.forEach((id) => next.delete(id));
        return next;
      }),
    [],
  );

  const hasFilters = Boolean(values.search || status || values.priority || values.serviceId || assigneeId || teamId || scope);

  const exportCsv = async () => {
    try {
      await downloadFile('/incidents/export', 'incidents.csv', {
        search: values.search || undefined,
        status: status || (tab === 'all' ? undefined : tabStatuses.join(',')),
        priority: values.priority || undefined,
        serviceId: values.serviceId || undefined,
      });
    } catch (error) {
      toast.error(error);
    }
  };

  const tabCount = (key: 'all' | 'open' | 'inProgress' | 'resolved') => counts.data?.[key];

  return (
    <>
      <PageHeader
        title="Incidents"
        subtitle={employee ? 'Track the incidents you have reported and their progress' : 'Manage, track and resolve IT incidents'}
        actions={
          !auditor && (
            <button type="button" className="gold-btn" onClick={() => newIncident.open()}>
              <Plus size={16} aria-hidden="true" /> New Incident
            </button>
          )
        }
      />
      <div className="page-stack">
        <Tabs<IncidentTabId>
          label="Incident status"
          value={tab}
          onChange={(id) => setValues({ tab: id === 'all' ? '' : id, status: '' })}
          tabs={[
            { id: 'all', label: 'All', count: tabCount('all') },
            { id: 'open', label: 'Open', count: tabCount('open') },
            { id: 'in_progress', label: 'In Progress', count: tabCount('inProgress') },
            { id: 'resolved', label: 'Resolved', count: tabCount('resolved') },
          ]}
        >
          {staff && (
            <FilterSelect label="Scope" allLabel="All incidents" value={scope} onChange={(value) => setValues({ scope: value })} options={SCOPES} />
          )}
        </Tabs>

        <div className="toolbar incident-toolbar">
          <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search by ID, title or user…" label="Search incidents" />
          <FilterSelect
            label="Priority"
            value={values.priority}
            onChange={(value) => setValues({ priority: value })}
            options={PRIORITIES}
            formatOption={(value) => PRIORITY_LABELS[value as keyof typeof PRIORITY_LABELS] ?? value}
          />
          <FilterSelect
            label="Service"
            value={values.serviceId}
            onChange={(value) => setValues({ serviceId: value })}
            options={(services.data ?? []).map((service) => ({ value: String(service.id), label: service.name }))}
            disabled={services.isError}
          />
          <FilterSelect
            label="Status"
            value={status}
            onChange={(value) => setValues({ status: value })}
            options={tabStatuses}
            formatOption={(value) => STATUS_LABELS[value as IncidentStatus] ?? value}
            disabled={tabStatuses.length < 2}
          />
          {staff && (
            <FilterSelect
              label="Assignee"
              value={assigneeId}
              onChange={(value) => setValues({ assigneeId: value })}
              options={(assignees.data ?? []).map((assignee) => ({ value: String(assignee.id), label: assignee.fullName }))}
              disabled={assignees.isError}
            />
          )}
          {staff && (
            <FilterSelect
              label="Team"
              value={teamId}
              onChange={(value) => setValues({ teamId: value })}
              options={(teams.data ?? []).map((team) => ({ value: String(team.id), label: team.name }))}
              disabled={teams.isError}
            />
          )}
          {hasFilters && (
            <button
              type="button"
              className="icon-btn toolbar-icon"
              aria-label="Clear filters"
              title="Clear filters"
              onClick={() => setValues({ search: '', status: '', priority: '', serviceId: '', assigneeId: '', teamId: '', scope: '' })}
            >
              <FilterX size={16} />
            </button>
          )}
          {canExport && (
            <button
              type="button"
              className="icon-btn toolbar-icon"
              aria-label="Export CSV"
              title="Export CSV (current tab, search, status, priority and service filters)"
              onClick={() => void exportCsv()}
            >
              <Download size={16} />
            </button>
          )}
        </div>

        {staff && <BulkBar incidents={[...selection.values()]} onClear={() => setSelection(new Map())} onApplied={onApplied} />}

        <section className="panel incident-register" aria-label="Incident register">
          {incidents.isLoading && <LoadingState rows={8} label="Loading incidents…" />}
          {incidents.isError && !incidents.data && <ErrorState error={incidents.error} onRetry={() => void incidents.refetch()} />}
          {incidents.data && (
            <div className={incidents.isPlaceholderData ? 'opacity-60 transition-opacity' : undefined} aria-busy={incidents.isFetching || undefined}>
              <IncidentTable
                incidents={incidents.data.content}
                empty={
                  hasFilters
                    ? 'No incidents match these filters.'
                    : employee
                      ? 'You have not reported any incidents in this view.'
                      : 'No incidents in this view yet.'
                }
                selected={staff ? selectedIds : undefined}
                onSelectedChange={staff ? onSelectedChange : undefined}
              />
            </div>
          )}
        </section>
        {incidents.data && (
          <Pagination
            page={incidents.data.page}
            totalPages={incidents.data.totalPages}
            totalElements={incidents.data.totalElements}
            size={incidents.data.size}
            onPageChange={setPage}
          />
        )}
      </div>
    </>
  );
}
