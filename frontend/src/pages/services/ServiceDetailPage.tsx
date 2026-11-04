import { useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Activity, AlertTriangle, Gauge, Pencil, Plus, ShieldCheck, Siren, Timer, Wrench } from 'lucide-react';
import { useIncidents } from '../../api/incidents';
import { useService } from '../../api/services';
import { UserChip } from '../../components/Avatar';
import { Badge, DemoBadge, StatusBadge } from '../../components/Badge';
import { IncidentTable } from '../../components/incidents/IncidentTable';
import { useNewIncident } from '../../components/incidents/NewIncidentContext';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { AvailabilityChart } from '../../components/services/AvailabilityChart';
import { ServiceEditor } from '../../components/services/ServiceEditor';
import { ServiceRefList } from '../../components/services/ServiceRefList';
import { TIER_LABELS, availabilityTone, formatAvailability } from '../../components/services/serviceModel';
import { useAuth } from '../../hooks/useAuth';
import { useUrlFilters } from '../../hooks/useUrlState';
import { toApiError } from '../../lib/api';
import { ACTIVE_STATUSES, CONFIG_ADMINS } from '../../lib/constants';
import { formatMinutes, formatNumber, formatPercent } from '../../lib/format';
import type { ServiceDetail } from '../../lib/types';

const INCIDENT_PAGE_SIZE = 10;
type IncidentScope = 'active' | 'all';

/** Incidents linked to the service, paged from `/incidents?serviceId=`. */
function LinkedIncidents({ serviceId }: { serviceId: number }) {
  const { values, setValues, page, setPage } = useUrlFilters(['incidents'] as const);
  const scope: IncidentScope = values.incidents === 'all' ? 'all' : 'active';
  const incidents = useIncidents({
    serviceId,
    status: scope === 'active' ? ACTIVE_STATUSES.join(',') : undefined,
    sort: 'createdAt,desc',
    page,
    size: INCIDENT_PAGE_SIZE,
  });

  return (
    <Panel
      title="Linked incidents"
      subtitle="Incidents raised against this service"
      actions={
        <Tabs<IncidentScope>
          inner
          label="Incident scope"
          value={scope}
          onChange={(next) => setValues({ incidents: next === 'all' ? 'all' : '' })}
          tabs={[
            { id: 'active', label: 'Active' },
            { id: 'all', label: 'All' },
          ]}
        />
      }
    >
      {incidents.isLoading && <LoadingState rows={4} label="Loading incidents…" />}
      {incidents.isError && !incidents.data && <ErrorState error={incidents.error} onRetry={() => void incidents.refetch()} compact />}
      {incidents.data && (
        <>
          <IncidentTable
            incidents={incidents.data.content}
            columns={['number', 'title', 'priority', 'assignee', 'sla', 'status', 'created']}
            empty={scope === 'active' ? 'No active incidents on this service.' : 'No incidents have been raised against this service.'}
          />
          <Pagination
            page={incidents.data.page}
            totalPages={incidents.data.totalPages}
            totalElements={incidents.data.totalElements}
            size={incidents.data.size}
            onPageChange={setPage}
            label="incidents"
          />
        </>
      )}
    </Panel>
  );
}

function ServiceHeader({ service, actions }: { service: ServiceDetail; actions: ReactNode }) {
  return (
    <PageHeader crumbs={[{ label: 'Services', to: '/services' }, { label: service.code }]} title={service.name} actions={actions}>
      <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
        <StatusBadge status={service.status} />
        <Badge value="outline" label={TIER_LABELS[service.tier] ?? `Tier ${service.tier}`} />
        {service.maintenance && (
          <Badge
            value="maintenance"
            label={
              <>
                <Wrench size={10} aria-hidden="true" /> Planned maintenance
              </>
            }
          />
        )}
        <DemoBadge demo={service.demo} />
        <span className="inline-flex items-center gap-1.5">
          Owner <UserChip name={service.ownerName} emptyLabel="No owner" />
        </span>
        <span>· {service.departmentName}</span>
      </div>
    </PageHeader>
  );
}

/** Service detail (§11, `/services/:id`): health KPIs, 90-day availability, dependencies, dependents and incidents. */
export default function ServiceDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const valid = Number.isInteger(id) && id > 0;
  const { hasRole } = useAuth();
  const admin = hasRole(...CONFIG_ADMINS);
  const newIncident = useNewIncident();
  const detail = useService(valid ? id : null);
  const [editing, setEditing] = useState(false);
  const service = detail.data;

  if (!valid || (detail.isError && toApiError(detail.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[{ label: 'Services', to: '/services' }, { label: 'Not found' }]} title="Service not found" />
        <EmptyState
          title="This service does not exist"
          description="It may have been removed, or the link is incorrect."
          action={
            <Link to="/services" className="subtle-btn compact">
              Back to services
            </Link>
          }
        />
      </>
    );
  }

  if (detail.isLoading || (!service && !detail.isError)) {
    return (
      <>
        <PageHeader crumbs={[{ label: 'Services', to: '/services' }]} title="Loading service…" />
        <div className="page-stack">
          <LoadingState variant="kpis" label="Loading service…" />
          <LoadingState variant="panel" />
        </div>
      </>
    );
  }

  if (!service) {
    return (
      <>
        <PageHeader crumbs={[{ label: 'Services', to: '/services' }]} title="Service" />
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
      </>
    );
  }

  const actions = (
    <>
      {admin && (
        <button type="button" className="subtle-btn" onClick={() => setEditing(true)}>
          <Pencil size={14} aria-hidden="true" /> Edit
        </button>
      )}
      <button type="button" className="gold-btn" onClick={() => newIncident.open({ serviceId: service.id })}>
        <Plus size={16} aria-hidden="true" /> Report Incident
      </button>
    </>
  );

  return (
    <>
      <ServiceHeader service={service} actions={actions} />
      <div className="page-stack">
        {service.statusReason && (
          <div className="callout" role="status">
            <AlertTriangle size={16} aria-hidden="true" />
            <div>
              <b>Degraded by a dependency.</b> {service.statusReason}
            </div>
          </div>
        )}

        <div className="kpi-grid">
          <KpiCard
            icon={<Activity />}
            label="Availability · 30 days"
            value={<span className={availabilityTone(service.availability30d)}>{formatAvailability(service.availability30d)}</span>}
            tone={service.availability30d >= 99.5 ? 'success' : service.availability30d >= 98 ? 'warning' : 'critical'}
          />
          <KpiCard icon={<ShieldCheck />} label="SLA compliance · 30 days" value={formatPercent(service.slaCompliance30d)} caption={service.slaCompliance30d === null ? 'No SLA outcomes yet' : undefined} />
          <KpiCard icon={<Timer />} label="MTTR · 30 days" value={formatMinutes(service.mttrMinutes30d)} tone="neutral" caption={service.mttrMinutes30d === null ? 'Nothing resolved yet' : undefined} />
          <KpiCard
            icon={service.criticalOpen ? <Siren /> : <Gauge />}
            label="Open incidents"
            value={formatNumber(service.openIncidents)}
            tone={service.criticalOpen ? 'critical' : service.openIncidents ? 'warning' : 'success'}
            caption={`${formatNumber(service.criticalOpen)} critical`}
          />
        </div>

        <div className="grid gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <Panel title="Availability" subtitle="Daily availability over the last 90 days">
            <AvailabilityChart history={service.availabilityHistory ?? []} />
          </Panel>
          <div className="grid content-start gap-[14px]">
            <Panel title="Depends on" subtitle="An outage here degrades this service">
              <ServiceRefList refs={service.dependencies ?? []} empty="No dependencies" label="Dependencies" />
            </Panel>
            <Panel title="Used by" subtitle="Services that depend on this one">
              <ServiceRefList refs={service.dependents ?? []} empty="No dependent services" label="Dependents" />
            </Panel>
          </div>
        </div>

        <LinkedIncidents serviceId={service.id} />

        <Panel title="About this service">
          {service.description ? <p className="description-text">{service.description}</p> : <p className="muted-text text-[11px]">No description.</p>}
          <div className="mt-3">
            <small className="mb-1.5 block text-[10px] font-semibold tracking-wide text-muted uppercase">Classification keywords</small>
            {service.keywords.length > 0 ? (
              <div className="flex flex-wrap gap-1.5">
                {service.keywords.map((keyword) => (
                  <span key={keyword} className="chip">
                    {keyword}
                  </span>
                ))}
              </div>
            ) : (
              <p className="muted-text text-[11px]">No keywords — incidents are linked to this service manually.</p>
            )}
          </div>
        </Panel>
      </div>
      {editing && <ServiceEditor service={service} onClose={() => setEditing(false)} />}
    </>
  );
}
