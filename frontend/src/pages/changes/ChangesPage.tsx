import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CalendarClock, CheckCircle2, GitPullRequestArrow, Plus, ShieldAlert, Snowflake, Stamp, Trash2 } from 'lucide-react';
import { useChangeMetrics, useChanges, useDeleteFreezeWindow, useFreezeWindows, useSaveFreezeWindow } from '../../api/changes';
import { UserChip } from '../../components/Avatar';
import { DemoBadge } from '../../components/Badge';
import { DataTable } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { TextField } from '../../components/FormField';
import { ServiceList, ServiceMultiPicker, ToneBadge } from '../../components/itsm/ItsmParts';
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
import { useSearchInput, useUrlFilters } from '../../hooks/useUrlState';
import { errorMessage, toApiError } from '../../lib/api';
import { CHANGE_EDITORS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatPercent } from '../../lib/format';
import { CHANGE_OPEN_STATUSES, CHANGE_RISKS, CHANGE_STATUS_LABELS, CHANGE_STATUS_TONES, CHANGE_TYPE_LABELS, CHANGE_TYPE_TONES, CHANGE_TYPES, RISK_TONES } from '../../lib/itsm';
import type { ChangeSummary } from '../../lib/itsmTypes';
import type { Role } from '../../lib/types';
import { ChangeCalendarView } from './ChangeCalendarView';
import { ChangeWizard } from './ChangeWizard';

/** Change raisers = change editors + incident managers (emergency changes only). */
export const CHANGE_RAISERS: Role[] = [...CHANGE_EDITORS, 'INCIDENT_MANAGER'];
type View = 'list' | 'calendar' | 'freezes';
type StatusTab = 'open' | 'AWAITING_APPROVAL' | 'SCHEDULED' | 'IMPLEMENTING' | 'closed' | 'all';
const STATUS_TABS: { id: StatusTab; label: string; status?: string }[] = [
  { id: 'open', label: 'Open', status: CHANGE_OPEN_STATUSES.join(',') },
  { id: 'AWAITING_APPROVAL', label: 'Awaiting approval', status: 'AWAITING_APPROVAL' },
  { id: 'SCHEDULED', label: 'Scheduled', status: 'APPROVED,SCHEDULED' },
  { id: 'IMPLEMENTING', label: 'Implementing', status: 'IMPLEMENTING' },
  { id: 'closed', label: 'Closed', status: 'COMPLETED,FAILED,ROLLED_BACK,REJECTED,CANCELLED' },
  { id: 'all', label: 'All' },
];
const KEYS = ['search', 'type', 'risk', 'scope', 'status'] as const;
const SCOPES = [
  { value: 'mine', label: 'I implement' },
  { value: 'requested', label: 'I raised' },
  { value: 'team', label: 'My teams' },
];

function FreezePanel({ canEdit }: { canEdit: boolean }) {
  const freezes = useFreezeWindows(false);
  const save = useSaveFreezeWindow();
  const remove = useDeleteFreezeWindow();
  const toast = useToast();
  const [name, setName] = useState('');
  const [reason, setReason] = useState('');
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [serviceIds, setServiceIds] = useState<number[]>([]);
  const fieldError = (field: string) => (save.isError ? toApiError(save.error).fieldErrors?.find((error) => error.field === field)?.message : undefined);

  const submit = () => {
    if (!name.trim() || !startsAt || !endsAt) return;
    save.mutate(
      { body: { name: name.trim(), reason: reason.trim() || null, startsAt: new Date(startsAt).toISOString(), endsAt: new Date(endsAt).toISOString(), serviceIds, active: true } },
      {
        onSuccess: () => {
          toast.success('Freeze window created');
          setName('');
          setReason('');
          setStartsAt('');
          setEndsAt('');
          setServiceIds([]);
        },
      },
    );
  };

  return (
    <div className={`grid items-start gap-[14px] ${canEdit ? 'xl:grid-cols-[1fr_1.2fr]' : ''}`}>
      {canEdit && (
        <Panel title="New freeze window" subtitle="Only emergency changes may be scheduled inside an active freeze.">
          <TextField label="Name" required maxLength={150} value={name} onChange={(event) => setName(event.target.value)} error={fieldError('name')} />
          <TextField label="Reason" maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)} />
          <div className="form-row">
            <TextField label="Starts" type="datetime-local" required value={startsAt} onChange={(event) => setStartsAt(event.target.value)} error={fieldError('startsAt')} />
            <TextField label="Ends" type="datetime-local" required value={endsAt} onChange={(event) => setEndsAt(event.target.value)} error={fieldError('endsAt')} />
          </div>
          <ServiceMultiPicker label="Services (none = all services)" value={serviceIds} onChange={setServiceIds} />
          {save.isError && !toApiError(save.error).fieldErrors?.length && (
            <p className="field-error" role="alert">
              {errorMessage(save.error)}
            </p>
          )}
          <div className="flex justify-end">
            <button type="button" className="gold-btn" onClick={submit} disabled={!name.trim() || !startsAt || !endsAt || save.isPending}>
              <Snowflake size={14} aria-hidden="true" /> {save.isPending ? 'Saving…' : 'Create freeze'}
            </button>
          </div>
        </Panel>
      )}
      <Panel title="Current and upcoming freeze windows">
        {freezes.isLoading && <LoadingState rows={3} />}
        {freezes.isError && <ErrorState error={freezes.error} onRetry={() => void freezes.refetch()} compact />}
        {freezes.data && freezes.data.length === 0 && <EmptyState title="No freeze windows" icon={<Snowflake size={18} />} />}
        {freezes.data && freezes.data.length > 0 && (
          <ul className="m-0 grid list-none gap-1 p-0" aria-label="Freeze windows">
            {freezes.data.map((freeze) => (
              <li key={freeze.id} className="list-row flex-wrap sm:flex-nowrap">
                <div className="min-w-0">
                  <b className="flex flex-wrap items-center gap-1.5">
                    {freeze.name} <DemoBadge demo={freeze.demo} />
                  </b>
                  <small className="block">
                    {formatDateTime(freeze.startsAt)} – {formatDateTime(freeze.endsAt)} · {freeze.serviceIds.length ? `${freeze.serviceIds.length} services` : 'all services'}
                    {freeze.reason ? ` · ${freeze.reason}` : ''}
                  </small>
                </div>
                <span className="flex shrink-0 items-center gap-1">
                  <ToneBadge value={freeze.active ? 'active' : 'inactive'} label={freeze.active ? 'Active' : 'Inactive'} tone={freeze.active ? 'gold' : 'muted'} />
                  {canEdit && (
                    <button
                      type="button"
                      className="icon-btn sm"
                      aria-label={`Delete freeze ${freeze.name}`}
                      disabled={remove.isPending}
                      onClick={() => remove.mutate(freeze.id, { onSuccess: () => toast.success('Freeze window deleted'), onError: (error) => toast.error(error) })}
                    >
                      <Trash2 size={13} />
                    </button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </div>
  );
}

/** /changes and /changes/calendar (SERVICE MANAGEMENT, readers): change list, month/week calendar, freeze windows; raisers create. */
export default function ChangesPage({ view = 'list' }: { view?: View }) {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const canRaise = hasRole(...CHANGE_RAISERS);
  const canEdit = hasRole(...CHANGE_EDITORS);
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const [creating, setCreating] = useState(false);
  const statusTab = (STATUS_TABS.find((tab) => tab.id === values.status)?.id ?? 'open') as StatusTab;
  const metrics = useChangeMetrics();
  const awaiting = useChanges({ status: 'AWAITING_APPROVAL', size: 1 }, view === 'list');
  const changes = useChanges(
    {
      status: STATUS_TABS.find((tab) => tab.id === statusTab)?.status,
      search: values.search || undefined,
      type: values.type || undefined,
      risk: values.risk || undefined,
      scope: (values.scope as 'mine') || undefined,
      page,
      size: 20,
    },
    view === 'list',
  );
  const m = metrics.data;
  const filtering = Boolean(values.search || values.type || values.risk || values.scope);

  return (
    <>
      <PageHeader
        title="Changes"
        subtitle="Plan, assess, approve and schedule changes"
        actions={
          canRaise ? (
            <button type="button" className="gold-btn" onClick={() => setCreating(true)}>
              <Plus size={16} aria-hidden="true" /> New change
            </button>
          ) : undefined
        }
      />
      <div className="page-stack">
        <Tabs<View>
          label="Change views"
          value={view}
          onChange={(next) => navigate(next === 'calendar' ? '/changes/calendar' : next === 'freezes' ? '/changes/freeze-windows' : '/changes')}
          tabs={[
            { id: 'list', label: 'Changes' },
            { id: 'calendar', label: 'Calendar' },
            { id: 'freezes', label: 'Freeze windows' },
          ]}
        />

        {view === 'calendar' && <ChangeCalendarView />}
        {view === 'freezes' && <FreezePanel canEdit={canEdit} />}
        {view === 'list' && (
          <>
            <div className="kpi-grid">
              <KpiCard icon={<Stamp />} label="Awaiting CAB approval" value={formatNumber(awaiting.data?.totalElements)} tone={awaiting.data?.totalElements ? 'warning' : 'success'} loading={awaiting.isLoading} />
              <KpiCard icon={<CheckCircle2 />} label="Success rate · 90 days" value={formatPercent(m?.successRate)} tone="success" loading={metrics.isLoading} caption={m ? `${formatNumber(m.completed)} completed · ${formatNumber(m.failed + m.rolledBack)} failed/rolled back` : undefined} />
              <KpiCard icon={<ShieldAlert />} label="Emergency changes" value={formatPercent(m?.emergencyRate)} tone="neutral" loading={metrics.isLoading} caption={m ? `${formatNumber(m.created)} created in 90 days` : undefined} />
              <KpiCard icon={<CalendarClock />} label="Reviews outstanding" value={formatNumber(m?.reviewsOutstanding)} tone={m?.reviewsOutstanding ? 'warning' : 'success'} loading={metrics.isLoading} caption={m ? `${formatNumber(m.changesFollowedByIncidents)} followed by incidents` : undefined} />
            </div>
            <Tabs<StatusTab> inner label="Change status" value={statusTab} onChange={(status) => setValues({ status: status === 'open' ? '' : status })} tabs={STATUS_TABS.map(({ id, label }) => ({ id, label }))} />
            <Panel aria-label="Change list">
              <div className="toolbar">
                <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search changes by number or title…" label="Search changes" />
                <div className={FILTER_ROW_CLASS}>
                  <FilterSelect className={FILTER_CLASS} label="Type" value={values.type} onChange={(type) => setValues({ type })} options={CHANGE_TYPES.map((value) => ({ value, label: CHANGE_TYPE_LABELS[value] }))} />
                  <FilterSelect className={FILTER_CLASS} label="Risk" value={values.risk} onChange={(risk) => setValues({ risk })} options={CHANGE_RISKS.map((value) => ({ value, label: `${value.charAt(0)}${value.slice(1).toLowerCase()}` }))} />
                  <FilterSelect className={FILTER_CLASS} label="Scope" allLabel="All changes" value={values.scope} onChange={(scope) => setValues({ scope })} options={SCOPES} />
                </div>
              </div>
              {changes.isLoading && <LoadingState rows={6} label="Loading changes…" />}
              {changes.isError && <ErrorState error={changes.error} onRetry={() => void changes.refetch()} />}
              {changes.data && changes.data.content.length === 0 && (
                <EmptyState title={filtering ? 'No changes match these filters' : 'No changes here'} icon={<GitPullRequestArrow size={20} />} description={canRaise && !filtering ? 'Raise a change with “New change”.' : undefined} />
              )}
              {changes.data && changes.data.content.length > 0 && (
                <>
                  <DataTable<ChangeSummary>
                    caption="Changes"
                    rows={changes.data.content}
                    rowKey={(change) => change.id}
                    rowLabel={(change) => `${change.number} ${change.title}`}
                    onRowClick={(change) => navigate(`/changes/${change.id}`)}
                    columns={[
                      { key: 'number', header: 'Change', render: (change) => <span className="id-cell">{change.number}</span> },
                      {
                        key: 'title',
                        header: 'Title',
                        className: 'summary-cell',
                        render: (change) => (
                          <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                            <span className="break-words font-semibold">{change.title}</span>
                            {change.pirRequired && !change.pirDone && ['COMPLETED', 'FAILED', 'ROLLED_BACK'].includes(change.status) && <ToneBadge value="review" label="Review due" tone="warning" />}
                            <DemoBadge demo={change.demo} />
                          </span>
                        ),
                      },
                      { key: 'type', header: 'Type', render: (change) => <ToneBadge value={change.type} label={CHANGE_TYPE_LABELS[change.type]} tone={CHANGE_TYPE_TONES[change.type]} /> },
                      { key: 'status', header: 'Status', render: (change) => <ToneBadge value={change.status} label={CHANGE_STATUS_LABELS[change.status]} tone={CHANGE_STATUS_TONES[change.status]} /> },
                      { key: 'risk', header: 'Risk', render: (change) => <ToneBadge value={change.risk} label={`${change.risk.charAt(0)}${change.risk.slice(1).toLowerCase()} · ${change.riskScore}`} tone={RISK_TONES[change.risk]} /> },
                      { key: 'window', header: 'Planned window', render: (change) => (change.plannedStart ? `${formatDateTime(change.plannedStart)} – ${formatDateTime(change.plannedEnd)}` : '—') },
                      { key: 'services', header: 'Services', render: (change) => <ServiceList services={change.services} /> },
                      { key: 'assignee', header: 'Implementer', render: (change) => <UserChip name={change.assigneeName} emptyLabel="Not assigned" /> },
                    ]}
                  />
                  <Pagination page={changes.data.page} totalPages={changes.data.totalPages} totalElements={changes.data.totalElements} size={changes.data.size} onPageChange={setPage} label="changes" />
                </>
              )}
            </Panel>
          </>
        )}
      </div>
      {creating && <ChangeWizard onClose={() => setCreating(false)} onSaved={(change) => navigate(`/changes/${change.id}`)} />}
    </>
  );
}
