import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, BookOpen, CalendarClock, ExternalLink, KeyRound, Laptop, Package, RadioTower, Search, Stamp } from 'lucide-react';
import { useCatalogCategories } from '../../api/catalog';
import { useMyDexDevice } from '../../api/dex';
import { usePortalOverview } from '../../api/workspace';
import { Badge, SimulatedBadge, StatusBadge } from '../../components/Badge';
import { useNewIncident } from '../../components/incidents/NewIncidentContext';
import { greetingFor, PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { SERVICE_STATUSES, SERVICE_STATUS_LABELS } from '../../components/services/serviceModel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useAuth } from '../../hooks/useAuth';
import { APPROVAL_TYPE_LABELS, READERS, roleLabel } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import type { ApprovalType, ApproverRole, CatalogCategoryDto, ServiceStatus } from '../../lib/types';
import { extraSections } from './sections';
import { asSection, GenericSectionPanel, IncidentRow, MyAssetsPanel, RequestRow, SectionPanel, type MyAssetItem } from './WorkspaceParts';

const KNOWN_KEYS = ['generatedAt', 'openIncidents', 'openRequests', 'awaitingApproval', 'serviceStatus', 'recommendedArticles', 'myAssets'] as const;

const OVERALL_TEXT: Record<ServiceStatus, string> = {
  OPERATIONAL: 'All services are operating normally',
  DEGRADED: 'Some services are degraded',
  OUTAGE: 'A service outage is in progress',
  MAINTENANCE: 'Planned maintenance is in progress',
};

function catalogLink(categories: CatalogCategoryDto[] | undefined, code: string, fallbackSearch: string): string {
  const category = categories?.find((entry) => entry.code?.toUpperCase() === code);
  return category ? `/catalog?category=${category.id}` : `/catalog?search=${encodeURIComponent(fallbackSearch)}`;
}

function QuickAction({ icon, title, caption, to, onClick }: { icon: ReactNode; title: string; caption: string; to?: string; onClick?: () => void }) {
  const body = (
    <>
      <span className="icon-tile" aria-hidden="true">
        {icon}
      </span>
      <span className="min-w-0">
        <b className="block text-[12px] text-ivory">{title}</b>
        <small className="block text-[10px] text-muted">{caption}</small>
      </span>
    </>
  );
  const className = 'panel flex w-full items-center gap-3 p-3 text-left no-underline text-inherit hover:border-gold';
  return to ? (
    <Link to={to} className={className}>
      {body}
    </Link>
  ) : (
    <button type="button" className={className} onClick={onClick}>
      {body}
    </button>
  );
}

/** /portal (WORKSPACE, everyone): self-service quick actions, my tickets, service status, DEX device health and recommended articles. */
export default function PortalPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const newIncident = useNewIncident();
  const overview = usePortalOverview();
  const categories = useCatalogCategories();
  const dexMe = useMyDexDevice(true);
  const [query, setQuery] = useState('');
  const data = overview.data;
  const firstName = user?.fullName.split(' ')[0];
  const canRaise = user?.role !== 'AUDITOR';

  const searchKnowledge = (event: FormEvent) => {
    event.preventDefault();
    const term = query.trim();
    navigate(term ? `/knowledge?q=${encodeURIComponent(term)}` : '/knowledge');
  };

  const status = data?.serviceStatus;
  const impacted = status?.services.filter((service) => service.status !== 'OPERATIONAL') ?? [];
  const extras = extraSections(data, KNOWN_KEYS);
  const myAssets = asSection<MyAssetItem>(data?.myAssets);

  return (
    <>
      <PageHeader
        title="Employee Portal"
        greeting={firstName ? `${greetingFor()}, ${firstName}` : greetingFor()}
        subtitle="Raise requests, follow your tickets and check service status"
        actions={
          canRaise ? (
            <button type="button" className="gold-btn" onClick={() => newIncident.open()}>
              <AlertTriangle size={16} aria-hidden="true" /> Report an incident
            </button>
          ) : undefined
        }
      />
      <div className="page-stack">
        <section aria-label="Quick actions" className="grid gap-[10px] sm:grid-cols-2 xl:grid-cols-4">
          {canRaise && (
            <>
              <QuickAction icon={<Laptop size={18} />} title="Request hardware" caption="Laptops, monitors, peripherals" to={catalogLink(categories.data, 'HARDWARE', 'hardware')} />
              <QuickAction icon={<Package size={18} />} title="Request software" caption="Licences and installations" to={catalogLink(categories.data, 'SOFTWARE', 'software')} />
              <QuickAction icon={<KeyRound size={18} />} title="Request access" caption="Applications, folders, groups" to={catalogLink(categories.data, 'ACCESS', 'access')} />
            </>
          )}
          <form onSubmit={searchKnowledge} className="panel flex items-center gap-2 p-3" role="search" aria-label="Search knowledge">
            <span className="icon-tile" aria-hidden="true">
              <BookOpen size={18} />
            </span>
            <input className="control sm min-w-0 flex-1" type="search" placeholder="Search knowledge…" aria-label="Search the knowledge base" value={query} onChange={(event) => setQuery(event.target.value)} />
            <button type="submit" className="icon-btn sm" aria-label="Search">
              <Search size={14} />
            </button>
          </form>
        </section>

        {overview.isLoading && <LoadingState variant="panel" label="Loading your portal…" />}
        {overview.isError && !data && <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />}
        {data && status && (
          <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
            <div className="grid gap-[14px]">
              <SectionPanel title="My open incidents" total={data.openIncidents.total} shown={data.openIncidents.items.length} to="/incidents?scope=reported&tab=open" empty="You have no open incidents">
                {data.openIncidents.items.map((incident) => (
                  <IncidentRow key={incident.id} incident={incident} />
                ))}
              </SectionPanel>
              <SectionPanel title="My open requests" total={data.openRequests.total} shown={data.openRequests.items.length} to="/service-desk?tab=mine" empty="You have no open requests">
                {data.openRequests.items.map((request) => (
                  <RequestRow key={request.id} request={request} />
                ))}
              </SectionPanel>
              <SectionPanel title="Waiting for approval" total={data.awaitingApproval.total} shown={data.awaitingApproval.items.length} empty="None of your requests is waiting for approval">
                {data.awaitingApproval.items.map((approval) => (
                  <li key={approval.id} className="list-row">
                    <span className="icon-tile sm" aria-hidden="true">
                      <Stamp size={14} />
                    </span>
                    <div>
                      <b className="flex flex-wrap items-center gap-1.5">
                        {approval.referenceNumber && <span className="id-cell">{approval.referenceNumber}</span>}
                        {approval.title}
                      </b>
                      <small>
                        {APPROVAL_TYPE_LABELS[approval.type as ApprovalType] ?? approval.type} · waiting for {roleLabel(approval.approverRole as ApproverRole)} · {formatRelative(approval.createdAt)}
                      </small>
                    </div>
                  </li>
                ))}
              </SectionPanel>
              {myAssets && <MyAssetsPanel section={myAssets} linkAssets={Boolean(user && READERS.includes(user.role))} />}
              <Panel
                title={
                  <span className="inline-flex items-center gap-2">
                    <Laptop size={15} className="gold-text" /> My Device Health (DEX)
                  </span>
                }
                subtitle="Digital Employee Experience telemetry for your primary endpoint"
                actions={dexMe.data?.device ? <SimulatedBadge simulated={dexMe.data.device.simulated} /> : undefined}
              >
                {dexMe.data?.status === 'OK' && dexMe.data.device ? (
                  <div className="detail-grid three tight">
                    <div>
                      <small>Device &amp; Model</small>
                      <b>
                        {dexMe.data.device.deviceModel} ({dexMe.data.device.deviceId})
                      </b>
                    </div>
                    <div>
                      <small>Operating System</small>
                      <b>
                        {dexMe.data.device.osName} {dexMe.data.device.osVersion}
                      </b>
                    </div>
                    <div>
                      <small>Experience Score</small>
                      <b>{dexMe.data.device.experienceScore.toFixed(1)} / 100</b>
                    </div>
                    <div>
                      <small>CPU / Memory / Disk</small>
                      <b>
                        {dexMe.data.device.cpuUsagePct.toFixed(0)}% / {dexMe.data.device.memoryUsagePct.toFixed(0)}% /{' '}
                        {dexMe.data.device.diskUsagePct.toFixed(0)}%
                      </b>
                    </div>
                    <div>
                      <small>Device Health Score</small>
                      <b>{dexMe.data.device.deviceHealthScore.toFixed(1)} / 100</b>
                    </div>
                    <div>
                      <small>Boot Time &amp; 7d Crashes</small>
                      <b>
                        {dexMe.data.device.bootTimeSeconds}s · {dexMe.data.device.crashCount7d} crashes
                      </b>
                    </div>
                  </div>
                ) : (
                  <p className="m-0 text-[11px] text-muted">
                    {dexMe.data?.message || 'No endpoint telemetry reported for your account yet'}
                  </p>
                )}
              </Panel>
              {extras.map((section) => (
                <GenericSectionPanel key={section.key} section={section} />
              ))}
            </div>
            <div className="grid gap-[14px]">
              <Panel
                title={
                  <span className="inline-flex items-center gap-2">
                    <RadioTower size={15} className="gold-text" /> Service status
                  </span>
                }
                subtitle={OVERALL_TEXT[status.overall] ?? 'Service status'}
                actions={
                  <a href="/status" target="_blank" rel="noopener noreferrer" className="link-btn inline-flex items-center gap-1">
                    Status page <ExternalLink size={11} aria-hidden="true" />
                  </a>
                }
              >
                <div className="tag-row">
                  <StatusBadge status={status.overall} />
                  {SERVICE_STATUSES.map((key) => (
                    <Badge key={key} value={key} label={`${SERVICE_STATUS_LABELS[key]} ${formatNumber(status.counts[key] ?? 0)}`} />
                  ))}
                </div>
                {impacted.length > 0 ? (
                  <ul className="m-0 mt-2 list-none p-0" aria-label="Affected services">
                    {impacted.map((service) => (
                      <li key={service.id} className="list-row">
                        <div>
                          <b>{service.name}</b>
                          {service.statusReason && <small>{service.statusReason}</small>}
                        </div>
                        <StatusBadge status={service.status} />
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="m-0 mt-3 text-[11px] text-muted">No service is degraded or down.</p>
                )}
                <h4 className="mb-1 mt-4 flex items-center gap-1.5 text-[11px] font-semibold text-ivory">
                  <CalendarClock size={13} className="gold-text" aria-hidden="true" /> Planned maintenance (next 14 days)
                </h4>
                {status.maintenance.length === 0 ? (
                  <p className="m-0 text-[11px] text-muted">No maintenance is scheduled.</p>
                ) : (
                  <ul className="m-0 list-none p-0" aria-label="Planned maintenance">
                    {status.maintenance.map((window) => (
                      <li key={window.id} className="list-row">
                        <div>
                          <b>{window.title}</b>
                          <small>
                            {formatDateTime(window.startsAt)} – {formatDateTime(window.endsAt)}
                            {window.components.length ? ` · ${window.components.join(', ')}` : ''}
                          </small>
                        </div>
                        <StatusBadge status={window.status} />
                      </li>
                    ))}
                  </ul>
                )}
              </Panel>
              <Panel title="Recommended articles" action="Knowledge base" to="/knowledge">
                {data.recommendedArticles.length === 0 ? (
                  <EmptyState title="No articles to recommend yet" />
                ) : (
                  <ul className="m-0 list-none p-0">
                    {data.recommendedArticles.map((article) => (
                      <li key={article.id} className="list-row">
                        <span className="icon-tile sm" aria-hidden="true">
                          <BookOpen size={14} />
                        </span>
                        <div>
                          <b>
                            <Link to={`/knowledge/${article.id}`}>{article.title}</Link>
                          </b>
                          <small>
                            {article.number} · {formatNumber(article.viewCount)} views · {article.readMinutes} min read
                          </small>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </Panel>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
