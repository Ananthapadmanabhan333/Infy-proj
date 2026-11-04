import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Activity, AlertTriangle, LayoutGrid, List, Plus, Server, ShieldCheck, Siren } from 'lucide-react';
import { useServices } from '../api/services';
import { Badge, DemoBadge, StatusBadge } from '../components/Badge';
import { UserChip } from '../components/Avatar';
import { DataTable } from '../components/DataTable';
import { FilterSelect } from '../components/FilterSelect';
import { KpiCard } from '../components/KpiCard';
import { PageHeader } from '../components/PageHeader';
import { Panel } from '../components/Panel';
import { SearchInput } from '../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { FILTER_CLASS, FILTER_ROW_CLASS } from '../components/servicedesk/requestModel';
import { ServiceCard } from '../components/services/ServiceCard';
import { ServiceEditor } from '../components/services/ServiceEditor';
import { SERVICE_STATUSES, SERVICE_STATUS_LABELS, availabilityTone, filterServices, formatAvailability } from '../components/services/serviceModel';
import { useAuth } from '../hooks/useAuth';
import { useSearchInput, useUrlFilters } from '../hooks/useUrlState';
import { CONFIG_ADMINS } from '../lib/constants';
import { formatNumber } from '../lib/format';
import type { ServiceDto, ServiceStatus } from '../lib/types';

const KEYS = ['q', 'status', 'tier', 'department', 'view', 'service'] as const;

/** Business services (§11): health, availability and incidents per service; config admins create and edit them. */
export default function ServicesPage() {
  const { hasRole } = useAuth();
  const admin = hasRole(...CONFIG_ADMINS);
  const services = useServices();
  const navigate = useNavigate();
  const { values, setValues } = useUrlFilters(KEYS);
  const [searchInput, setSearchInput] = useSearchInput(values.q, (q) => setValues({ q }));
  const [editing, setEditing] = useState<ServiceDto | null | 'new'>(null);
  const view = values.view === 'table' ? 'table' : 'grid';

  // Release 1 links used `?service=<id>`; the detail now lives at /services/:id.
  useEffect(() => {
    if (values.service && Number(values.service) > 0) navigate(`/services/${values.service}`, { replace: true });
  }, [values.service, navigate]);

  const all = services.data ?? [];
  const filtered = useMemo(() => filterServices(all, values), [all, values]);
  const departmentOptions = useMemo(() => {
    const seen = new Map<number, string>();
    all.forEach((service) => seen.set(service.departmentId, service.departmentName));
    return [...seen.entries()].sort((a, b) => a[1].localeCompare(b[1])).map(([id, name]) => ({ value: String(id), label: name }));
  }, [all]);

  const summary = useMemo(() => {
    const impacted = all.filter((service) => service.status === 'DEGRADED' || service.status === 'OUTAGE').length;
    const operational = all.filter((service) => service.status === 'OPERATIONAL').length;
    const critical = all.reduce((sum, service) => sum + service.criticalOpen, 0);
    const average = all.length ? all.reduce((sum, service) => sum + service.availability30d, 0) / all.length : null;
    return { impacted, operational, critical, average };
  }, [all]);
  const filtering = Boolean(values.q || values.status || values.tier || values.department);

  return (
    <>
      <PageHeader
        title="Services"
        subtitle="Business services, their health, dependencies and incidents"
        actions={
          admin ? (
            <button type="button" className="gold-btn" onClick={() => setEditing('new')}>
              <Plus size={16} aria-hidden="true" /> New Service
            </button>
          ) : undefined
        }
      />
      <div className="page-stack">
        {services.isLoading && <LoadingState variant="kpis" label="Loading services…" />}
        {services.data && (
          <div className="kpi-grid">
            <KpiCard icon={<Server />} label="Business services" value={formatNumber(all.length)} caption={`${formatNumber(summary.operational)} operational`} />
            <KpiCard
              icon={<AlertTriangle />}
              label="Degraded or outage"
              value={formatNumber(summary.impacted)}
              tone={summary.impacted ? 'warning' : 'success'}
              caption={summary.impacted ? 'Includes dependency impact' : 'All services healthy'}
            />
            <KpiCard icon={<Siren />} label="Critical incidents open" value={formatNumber(summary.critical)} tone={summary.critical ? 'critical' : 'success'} />
            <KpiCard icon={<Activity />} label="Avg. availability · 30 days" value={formatAvailability(summary.average)} tone="gold" />
          </div>
        )}

        <Panel aria-label="Service list">
          <div className="toolbar">
            <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search services, codes, owners…" label="Search services" />
            <div className={FILTER_ROW_CLASS}>
              <FilterSelect
                className={FILTER_CLASS}
                label="Status"
                value={values.status}
                onChange={(status) => setValues({ status })}
                options={SERVICE_STATUSES}
                formatOption={(value) => SERVICE_STATUS_LABELS[value as ServiceStatus] ?? value}
              />
              <FilterSelect className={FILTER_CLASS} label="Tier" value={values.tier} onChange={(tier) => setValues({ tier })} options={['1', '2', '3'].map((tier) => ({ value: tier, label: `Tier ${tier}` }))} />
              <FilterSelect className={FILTER_CLASS} label="Department" value={values.department} onChange={(department) => setValues({ department })} options={departmentOptions} />
            </div>
            <div className="ml-auto flex gap-1" role="group" aria-label="Layout">
              <button type="button" className={`icon-btn sm ${view === 'grid' ? 'text-gold' : ''}`} aria-pressed={view === 'grid'} aria-label="Grid view" onClick={() => setValues({ view: '' })}>
                <LayoutGrid size={15} />
              </button>
              <button type="button" className={`icon-btn sm ${view === 'table' ? 'text-gold' : ''}`} aria-pressed={view === 'table'} aria-label="Table view" onClick={() => setValues({ view: 'table' })}>
                <List size={15} />
              </button>
            </div>
          </div>

          {services.isLoading && <LoadingState variant="cards" rows={6} label="Loading services…" />}
          {services.isError && <ErrorState error={services.error} onRetry={() => void services.refetch()} />}
          {services.data && filtered.length === 0 && (
            <EmptyState
              title={filtering ? 'No services match these filters' : 'No business services yet'}
              description={filtering ? 'Try another search or clear the filters.' : admin ? 'Create the first service to start tracking its health.' : undefined}
              icon={<ShieldCheck size={20} />}
              action={
                filtering ? (
                  <button type="button" className="subtle-btn compact" onClick={() => setValues({ q: '', status: '', tier: '', department: '' })}>
                    Clear filters
                  </button>
                ) : undefined
              }
            />
          )}
          {filtered.length > 0 && view === 'grid' && (
            <ul className="grid gap-3 sm:grid-cols-2 2xl:grid-cols-3" aria-label="Business services">
              {filtered.map((service) => (
                <ServiceCard key={service.id} service={service} onEdit={admin ? setEditing : undefined} />
              ))}
            </ul>
          )}
          {filtered.length > 0 && view === 'table' && (
            <DataTable<ServiceDto>
              caption="Business services"
              rows={filtered}
              rowKey={(service) => service.id}
              onRowClick={(service) => navigate(`/services/${service.id}`)}
              rowLabel={(service) => service.name}
              columns={[
                {
                  key: 'name',
                  header: 'Service',
                  className: 'summary-cell',
                  render: (service) => (
                    <span className="inline-flex min-w-0 items-center gap-1.5">
                      <span className="truncate font-semibold">{service.name}</span>
                      <span className="text-muted">{service.code}</span>
                      <DemoBadge demo={service.demo} />
                    </span>
                  ),
                },
                {
                  key: 'status',
                  header: 'Status',
                  render: (service) => (
                    <span className="inline-flex flex-col items-start gap-0.5">
                      <StatusBadge status={service.status} />
                      {service.statusReason && <small className="max-w-[220px] truncate text-[9px] text-warning" title={service.statusReason}>{service.statusReason}</small>}
                    </span>
                  ),
                },
                { key: 'tier', header: 'Tier', render: (service) => <Badge value="outline" label={`Tier ${service.tier}`} /> },
                {
                  key: 'availability',
                  header: 'Availability 30d',
                  render: (service) => <b className={availabilityTone(service.availability30d)}>{formatAvailability(service.availability30d)}</b>,
                },
                {
                  key: 'incidents',
                  header: 'Open / critical',
                  render: (service) => (
                    <span>
                      {formatNumber(service.openIncidents)} / <span className={service.criticalOpen ? 'red-text' : ''}>{formatNumber(service.criticalOpen)}</span>
                    </span>
                  ),
                },
                { key: 'owner', header: 'Owner', render: (service) => <UserChip name={service.ownerName} emptyLabel="No owner" /> },
                { key: 'department', header: 'Department', render: (service) => service.departmentName },
              ]}
            />
          )}
        </Panel>
      </div>
      {editing && <ServiceEditor service={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}
