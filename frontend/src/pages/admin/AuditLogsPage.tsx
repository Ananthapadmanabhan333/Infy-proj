import { useId, useMemo, useState } from 'react';
import { Activity, Download, FileClock, ListFilter, ShieldAlert, X, XCircle } from 'lucide-react';
import { useAuditActions, useAuditLogs, type AuditParams } from '../../api/audit';
import { AuditEventDrawer, entityLabel } from '../../components/admin/AuditEventDrawer';
import { Badge, StatusBadge } from '../../components/Badge';
import { DataTable } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useSearchInput, useUrlFilters } from '../../hooks/useUrlState';
import { downloadFile } from '../../lib/api';
import { DEFAULT_PAGE_SIZE } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import type { AuditLogDto } from '../../lib/types';
import '../../components/admin/admin.css';

const KEYS = ['actor', 'action', 'entityType', 'entityId', 'outcome', 'from', 'to'] as const;

const OUTCOMES = [
  { value: 'SUCCESS', label: 'Success' },
  { value: 'FAILURE', label: 'Failure' },
  { value: 'DENIED', label: 'Denied' },
];

/** Calendar date (local) → ISO instant at the start / end of that day. */
export function dayBoundary(date: string, end: boolean): string | undefined {
  if (!date) return undefined;
  const value = new Date(`${date}T00:00:00`);
  if (Number.isNaN(value.getTime())) return undefined;
  if (end) value.setDate(value.getDate() + 1);
  return new Date(value.getTime() - (end ? 1 : 0)).toISOString();
}

/** Local calendar date "YYYY-MM-DD". */
export function localDate(date: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** /audit-logs (AUDIT_READERS): immutable activity log with filters, detail drawer and CSV export. */
export default function AuditLogsPage() {
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [actor, setActor] = useSearchInput(values.actor, (value) => setValues({ actor: value }));
  const [entityType, setEntityType] = useSearchInput(values.entityType, (value) => setValues({ entityType: value.trim().toUpperCase() }));
  const actions = useAuditActions();
  const toast = useToast();
  const entityListId = useId();
  const [selected, setSelected] = useState<AuditLogDto | null>(null);
  const [exporting, setExporting] = useState(false);

  const filters = useMemo<AuditParams>(
    () => ({
      actor: values.actor || undefined,
      action: values.action || undefined,
      entityType: values.entityType || undefined,
      entityId: values.entityId || undefined,
      outcome: values.outcome || undefined,
      from: dayBoundary(values.from, false),
      to: dayBoundary(values.to, true),
    }),
    [values],
  );
  const logs = useAuditLogs({ ...filters, page, size: DEFAULT_PAGE_SIZE });
  const hasFilters = KEYS.some((key) => values[key]);

  // KPI strip: real counts from the list endpoint (size 1 → totalElements).
  const today = localDate();
  const todayStart = dayBoundary(today, false);
  const eventsToday = useAuditLogs({ from: todayStart, size: 1 });
  const failuresToday = useAuditLogs({ from: todayStart, outcome: 'FAILURE', size: 1 });
  const deniedToday = useAuditLogs({ from: todayStart, outcome: 'DENIED', size: 1 });

  const entityTypes = useMemo(
    () => Array.from(new Set((logs.data?.content ?? []).map((row) => row.entityType).filter((value): value is string => Boolean(value)))).sort(),
    [logs.data],
  );

  const exportCsv = async () => {
    setExporting(true);
    try {
      await downloadFile('/audit-logs/export', 'nexus360-audit-log.csv', { ...filters, entityId: undefined });
      toast.success('Audit log exported (CSV)');
    } catch (error) {
      toast.error(error);
    } finally {
      setExporting(false);
    }
  };

  const clearAll = () => setValues({ actor: '', action: '', entityType: '', entityId: '', outcome: '', from: '', to: '' });

  return (
    <>
      <PageHeader
        title="Audit Logs"
        subtitle="Review security and operational activity"
        actions={
          <button type="button" className="gold-btn" onClick={() => void exportCsv()} disabled={exporting}>
            <Download size={15} /> {exporting ? 'Exporting…' : 'Export CSV'}
          </button>
        }
      />
      <div className="page-stack">
        <div className="kpi-grid">
          <KpiCard icon={<Activity />} label="Events today" value={formatNumber(eventsToday.data?.totalElements)} loading={eventsToday.isLoading} to={`/audit-logs?from=${today}`} caption="Since midnight (local time)" />
          <KpiCard
            icon={<XCircle />}
            label="Failures today"
            tone="critical"
            value={formatNumber(failuresToday.data?.totalElements)}
            loading={failuresToday.isLoading}
            to={`/audit-logs?from=${today}&outcome=FAILURE`}
            caption="Operations that failed"
          />
          <KpiCard
            icon={<ShieldAlert />}
            label="Access denied today"
            tone="warning"
            value={formatNumber(deniedToday.data?.totalElements)}
            loading={deniedToday.isLoading}
            to={`/audit-logs?from=${today}&outcome=DENIED`}
            caption="Blocked by permissions"
          />
          <KpiCard
            icon={<ListFilter />}
            label="Matching events"
            tone="neutral"
            value={formatNumber(logs.data?.totalElements)}
            loading={logs.isLoading}
            caption={hasFilters ? 'With the current filters' : 'All recorded events'}
          />
        </div>
        <Panel title="Activity" subtitle="Immutable record of sign-ins, changes, approvals, exports and AI actions">
          <div className="toolbar">
            <SearchInput value={actor} onChange={setActor} placeholder="Search by actor email…" label="Filter by actor" />
            <FilterSelect
              label="Action"
              allLabel="All actions"
              value={values.action}
              onChange={(action) => setValues({ action })}
              options={actions.data ?? []}
              formatOption={titleCase}
              disabled={actions.isError}
            />
            <label className="toolbar-field">
              <span className="sr-only">Entity type</span>
              <input
                className={`toolbar-input w-[130px] ${values.entityType ? 'active' : ''}`}
                placeholder="Entity type"
                aria-label="Filter by entity type"
                list={entityListId}
                value={entityType}
                onChange={(event) => setEntityType(event.target.value)}
              />
              <datalist id={entityListId}>
                {entityTypes.map((type) => (
                  <option key={type} value={type}>
                    {titleCase(type)}
                  </option>
                ))}
              </datalist>
            </label>
            <FilterSelect label="Outcome" allLabel="Any outcome" value={values.outcome} onChange={(outcome) => setValues({ outcome })} options={OUTCOMES} />
            <label className="toolbar-field">
              From
              <input
                className={`toolbar-input ${values.from ? 'active' : ''}`}
                type="date"
                aria-label="From date"
                value={values.from}
                max={values.to || undefined}
                onChange={(event) => setValues({ from: event.target.value })}
              />
            </label>
            <label className="toolbar-field">
              To
              <input
                className={`toolbar-input ${values.to ? 'active' : ''}`}
                type="date"
                aria-label="To date"
                value={values.to}
                min={values.from || undefined}
                onChange={(event) => setValues({ to: event.target.value })}
              />
            </label>
            {hasFilters && (
              <button type="button" className="subtle-btn compact" onClick={clearAll}>
                <X size={13} /> Clear
              </button>
            )}
          </div>
          {values.entityId && (
            <p className="mb-3 flex flex-wrap items-center gap-2 text-[10px] text-muted">
              Showing events for
              <span className="chip">
                {titleCase(values.entityType)} #{values.entityId}
                <button type="button" className="link-btn" aria-label="Remove entity filter" onClick={() => setValues({ entityId: '' })}>
                  <X size={11} />
                </button>
              </span>
            </p>
          )}
          {logs.isLoading && <LoadingState rows={10} label="Loading audit events…" />}
          {logs.isError && !logs.data && <ErrorState error={logs.error} onRetry={() => void logs.refetch()} />}
          {logs.data && (
            <>
              <DataTable<AuditLogDto>
                caption="Audit log"
                rows={logs.data.content}
                rowKey={(row) => row.id}
                onRowClick={setSelected}
                rowLabel={(row) => `${titleCase(row.action)} by ${row.actorEmail ?? 'system'}`}
                rowClassName={(row) => (row.outcome === 'SUCCESS' ? '' : 'attention')}
                empty={hasFilters ? 'No audit events match these filters.' : <EmptyState title="No audit events recorded yet" icon={<FileClock size={20} />} />}
                columns={[
                  {
                    key: 'time',
                    header: 'Time',
                    render: (row) => (
                      <span className="block">
                        <span className="block text-ivory">{formatDateTime(row.createdAt)}</span>
                        <small className="text-[9px] text-muted">{formatRelative(row.createdAt)}</small>
                      </span>
                    ),
                  },
                  { key: 'actor', header: 'Actor', render: (row) => row.actorEmail ?? <span className="muted-text">System</span> },
                  { key: 'action', header: 'Action', render: (row) => <Badge value="outline" label={titleCase(row.action)} /> },
                  { key: 'entity', header: 'Entity', render: (row) => entityLabel(row) ?? <span className="muted-text">—</span> },
                  { key: 'outcome', header: 'Outcome', render: (row) => <StatusBadge status={row.outcome} /> },
                  { key: 'ip', header: 'IP address', render: (row) => <span className="font-mono text-[10px]">{row.ipAddress ?? '—'}</span> },
                ]}
              />
              <Pagination page={logs.data.page} totalPages={logs.data.totalPages} totalElements={logs.data.totalElements} size={logs.data.size} onPageChange={setPage} label="events" />
            </>
          )}
        </Panel>
      </div>
      {selected && (
        <AuditEventDrawer
          event={selected}
          onClose={() => setSelected(null)}
          onFilterActor={(email) => {
            setValues({ actor: email });
            setSelected(null);
          }}
          onFilterEntity={(type, id) => {
            setValues({ entityType: type, entityId: id });
            setSelected(null);
          }}
        />
      )}
    </>
  );
}
