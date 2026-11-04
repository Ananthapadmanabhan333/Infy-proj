import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ClipboardCheck, Plus, Siren } from 'lucide-react';
import { useDeclareMajor, useMajorIncidents } from '../../api/majorIncidents';
import { useAssignableUsers } from '../../api/users';
import { UserChip } from '../../components/Avatar';
import { DemoBadge, PriorityBadge } from '../../components/Badge';
import { DataTable } from '../../components/DataTable';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { IncidentPicker } from '../../components/incidents/IncidentPicker';
import { ServiceMultiPicker, ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useTabParam, useUrlFilters } from '../../hooks/useUrlState';
import { errorMessage, toApiError } from '../../lib/api';
import { MAJOR_INCIDENT_COMMAND, READERS } from '../../lib/constants';
import { formatDateTime, formatMinutes, formatNumber, formatRelative } from '../../lib/format';
import { MAJOR_STATUS_LABELS, MAJOR_STATUS_TONES, PIR_STATUS_LABELS, PIR_STATUS_TONES } from '../../lib/response';
import type { MajorDetail, MajorStatus, MajorSummary } from '../../lib/responseTypes';
import type { IncidentSummary } from '../../lib/types';

type TabId = 'open' | MajorStatus | 'all';
const TABS: TabId[] = ['open', 'ACTIVE', 'MONITORING', 'RESOLVED', 'CLOSED', 'all'];
const PAGE_SIZE = 20;

function statusFilter(tab: TabId): string | undefined {
  if (tab === 'all') return undefined;
  if (tab === 'open') return 'ACTIVE,MONITORING';
  return tab;
}

export function MajorStatusBadge({ status }: { status: MajorStatus }) {
  return <ToneBadge value={status} label={MAJOR_STATUS_LABELS[status] ?? status} tone={MAJOR_STATUS_TONES[status] ?? 'neutral'} />;
}

/** Declare a major incident on an existing incident and open its command record (major incident command roles). */
export function DeclareMajorDialog({ initialIncident = null, onClose, onDeclared }: { initialIncident?: IncidentSummary | null; onClose: () => void; onDeclared?: (detail: MajorDetail) => void }) {
  const declare = useDeclareMajor();
  const staff = useAssignableUsers(null);
  const toast = useToast();
  const [incident, setIncident] = useState<IncidentSummary | null>(initialIncident);
  const [commanderId, setCommanderId] = useState('');
  const [techLeadId, setTechLeadId] = useState('');
  const [commsLeadId, setCommsLeadId] = useState('');
  const [summary, setSummary] = useState('');
  const [serviceIds, setServiceIds] = useState<number[]>(initialIncident?.serviceId ? [initialIncident.serviceId] : []);
  const [bridgeUrl, setBridgeUrl] = useState('');
  const errors = declare.isError ? toApiError(declare.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field)?.message;
  const bridgeInvalid = bridgeUrl.trim() !== '' && !/^https?:\/\/.+/.test(bridgeUrl.trim());
  const people = staff.data ?? [];
  const roleSelect = (label: string, value: string, onChange: (value: string) => void, field: string, hint?: string) => (
    <SelectField label={label} value={value} onChange={(event) => onChange(event.target.value)} error={fieldError(field)} hint={hint}>
      <option value="">{field === 'commanderId' ? 'Me' : 'Not assigned yet'}</option>
      {people.map((person) => (
        <option key={person.id} value={person.id}>
          {person.fullName}
        </option>
      ))}
    </SelectField>
  );

  return (
    <Modal
      open
      onClose={onClose}
      title="Declare a major incident"
      eyebrow="MAJOR INCIDENT COMMAND"
      description="Sets the incident’s major flag, notifies leadership and opens the command record."
      size="lg"
      dismissible={!declare.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={declare.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="danger-btn"
            disabled={!incident || bridgeInvalid || declare.isPending}
            onClick={() =>
              incident &&
              declare.mutate(
                {
                  incidentId: incident.id,
                  commanderId: commanderId ? Number(commanderId) : null,
                  techLeadId: techLeadId ? Number(techLeadId) : null,
                  commsLeadId: commsLeadId ? Number(commsLeadId) : null,
                  summary: summary.trim() || null,
                  serviceIds,
                  bridgeUrl: bridgeUrl.trim() || null,
                },
                {
                  onSuccess: (detail) => {
                    toast.success(`${detail.number} declared for ${detail.incident.number}`, { action: { label: `Open ${detail.number}`, to: `/major-incidents/${detail.id}` } });
                    onDeclared?.(detail);
                    onClose();
                  },
                },
              )
            }
          >
            <Siren size={14} aria-hidden="true" /> {declare.isPending ? 'Declaring…' : 'Declare major incident'}
          </button>
        </>
      }
    >
      {initialIncident ? (
        <p className="m-0 text-[12px]">
          <span className="id-cell">{initialIncident.number}</span> {initialIncident.title}
        </p>
      ) : (
        <IncidentPicker label="Incident" value={incident} onChange={setIncident} parentOnly error={fieldError('incidentId')} />
      )}
      <div className="form-row three">
        {roleSelect('Incident commander', commanderId, setCommanderId, 'commanderId', 'Defaults to you')}
        {roleSelect('Technical lead', techLeadId, setTechLeadId, 'techLeadId')}
        {roleSelect('Communications lead', commsLeadId, setCommsLeadId, 'commsLeadId')}
      </div>
      <TextareaField label="Summary" rows={3} maxLength={2000} value={summary} onChange={(event) => setSummary(event.target.value)} error={fieldError('summary')} />
      <ServiceMultiPicker label="Impacted services" value={serviceIds} onChange={setServiceIds} error={fieldError('serviceIds')} hint="Defaults to the incident’s service" />
      <TextField
        label="Bridge / war-room link"
        type="url"
        placeholder="https://"
        maxLength={500}
        value={bridgeUrl}
        onChange={(event) => setBridgeUrl(event.target.value)}
        error={fieldError('bridgeUrl') ?? (bridgeInvalid ? 'Must be an http(s) link' : undefined)}
      />
      {declare.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(declare.error)}
        </p>
      )}
    </Modal>
  );
}

/** /major-incidents (SERVICE MANAGEMENT, readers): command records by status; command roles declare new ones. */
export default function MajorIncidentsPage() {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const canDeclare = hasRole(...MAJOR_INCIDENT_COMMAND);
  const reader = hasRole(...READERS);
  const [tab, setTab] = useTabParam<TabId>(TABS, 'open');
  const { values, setValues, page, setPage } = useUrlFilters(['search'] as const);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const [declaring, setDeclaring] = useState(false);
  const list = useMajorIncidents({ status: statusFilter(tab), search: values.search || undefined, page, size: PAGE_SIZE });
  const data = list.data;

  return (
    <>
      <PageHeader
        title="Major Incidents"
        subtitle="Command, coordinate and communicate during major incidents"
        actions={
          <>
            {reader && (
              <Link to="/reviews" className="subtle-btn">
                <ClipboardCheck size={14} aria-hidden="true" /> Post-incident reviews
              </Link>
            )}
            {canDeclare && (
              <button type="button" className="gold-btn" onClick={() => setDeclaring(true)}>
                <Plus size={16} aria-hidden="true" /> Declare major incident
              </button>
            )}
          </>
        }
      />
      <div className="page-stack">
        <Tabs<TabId>
          label="Major incident status"
          value={tab}
          onChange={setTab}
          tabs={TABS.map((id) => ({ id, label: id === 'open' ? 'Open' : id === 'all' ? 'All' : MAJOR_STATUS_LABELS[id as MajorStatus] }))}
        />
        <Panel aria-label="Major incident list">
          <div className="toolbar">
            <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search by number or title…" label="Search major incidents" />
          </div>
          {list.isLoading && <LoadingState rows={5} label="Loading major incidents…" />}
          {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} />}
          {data && data.content.length === 0 && (
            <EmptyState
              title={values.search ? 'No major incidents match this search' : tab === 'open' ? 'No open major incidents' : 'No major incidents here'}
              description={tab === 'open' && !values.search ? 'Declared major incidents and their command records appear here.' : undefined}
              icon={<Siren size={20} />}
            />
          )}
          {data && data.content.length > 0 && (
            <>
              <DataTable<MajorSummary>
                caption="Major incidents"
                rows={data.content}
                rowKey={(major) => major.id}
                rowLabel={(major) => `${major.number} ${major.title}`}
                onRowClick={(major) => navigate(`/major-incidents/${major.id}`)}
                columns={[
                  { key: 'number', header: 'Major incident', render: (major) => <span className="id-cell">{major.number}</span> },
                  {
                    key: 'title',
                    header: 'Incident',
                    className: 'summary-cell',
                    render: (major) => (
                      <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                        <span className="id-cell">{major.incidentNumber}</span>
                        <span className="break-words font-semibold">{major.title}</span>
                        <DemoBadge demo={major.demo} />
                      </span>
                    ),
                  },
                  { key: 'status', header: 'Status', render: (major) => <MajorStatusBadge status={major.status} /> },
                  { key: 'priority', header: 'Priority', render: (major) => <PriorityBadge priority={major.priority} /> },
                  { key: 'commander', header: 'Commander', render: (major) => <UserChip name={major.commander?.name} emptyLabel="Unassigned" /> },
                  { key: 'declared', header: 'Declared', render: (major) => <span title={formatDateTime(major.declaredAt)}>{formatRelative(major.declaredAt)}</span> },
                  { key: 'duration', header: 'Duration', render: (major) => formatMinutes(major.durationMinutes) },
                  { key: 'actions', header: 'Open actions', render: (major) => formatNumber(major.openActions) },
                  {
                    key: 'pir',
                    header: 'Review',
                    render: (major) => (major.pir ? <ToneBadge value={major.pir.status} label={`${major.pir.number} · ${PIR_STATUS_LABELS[major.pir.status]}`} tone={PIR_STATUS_TONES[major.pir.status]} /> : <span className="text-muted">—</span>),
                  },
                ]}
              />
              <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} size={data.size} onPageChange={setPage} label="major incidents" />
            </>
          )}
        </Panel>
      </div>
      {declaring && <DeclareMajorDialog onClose={() => setDeclaring(false)} onDeclared={(detail) => navigate(`/major-incidents/${detail.id}`)} />}
    </>
  );
}
