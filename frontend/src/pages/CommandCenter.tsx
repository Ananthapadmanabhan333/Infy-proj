import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, BookOpen, Megaphone, Plus, Search } from 'lucide-react';
import { useAnnouncements } from '../api/announcements';
import { useDashboard } from '../api/analytics';
import { useServiceRequests } from '../api/serviceDesk';
import { AiInsightCard } from '../components/dashboard/AiInsightCard';
import { CommandCenterBlock } from '../components/dashboard/CommandCenterBlock';
import { PayrollScenarioButton } from '../components/dashboard/ScenarioRunner';
import { KpiRow, PriorityDonut, QuickCards, ServiceHealthList, ServiceHealthTiles, TrendChart } from '../components/dashboard/Widgets';
import { Badge, DemoBadge, StatusBadge } from '../components/Badge';
import { PeriodSelector } from '../components/Controls';
import { IncidentTable } from '../components/incidents/IncidentTable';
import { useNewIncident } from '../components/incidents/NewIncidentContext';
import { greetingFor, PageHeader } from '../components/PageHeader';
import { Panel } from '../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { useAuth } from '../hooks/useAuth';
import { usePeriod } from '../hooks/useUrlState';
import { formatNumber, formatRelative } from '../lib/format';
import type { DashboardDto } from '../lib/types';
import { ADMIN_OR_MANAGER, READERS, SETTINGS, STAFF } from '../lib/constants';

function StaffDashboard({ data, showQueue }: { data: DashboardDto; showQueue: boolean }) {
  const navigate = useNavigate();
  return (
    <div className="page-stack">
      <KpiRow kpis={data.kpis} days={data.days} className="!mb-0" />

      {data.commandCenter && <CommandCenterBlock data={data.commandCenter} days={data.days} />}

      {showQueue && (
        <Panel
          title="My queue"
          subtitle={`${formatNumber(data.myQueue.length)} incidents assigned to you · live SLA countdowns`}
          action="Open queue"
          to="/incidents?tab=mine"
        >
          <IncidentTable incidents={data.myQueue} columns={['number', 'title', 'priority', 'service', 'sla', 'status', 'updated']} empty="Your queue is clear." />
        </Panel>
      )}

      <div className="cc-row">
        <Panel className="trend-panel" title="Incident Trend & Forecast" subtitle={`Created vs. resolved · last ${data.days} days`}>
          <TrendChart trend={data.trend} forecast={data.forecast} method={data.forecastMethod} />
        </Panel>
        <Panel className="distribution-panel" title="Incident Distribution" subtitle={`By priority · created in the last ${data.days} days`}>
          <PriorityDonut distribution={data.distribution} />
        </Panel>
      </div>

      <div className="cc-row">
        <Panel title="Service Health" subtitle="Availability over the last 30 days" action="View all" to="/services">
          <ServiceHealthTiles services={data.serviceHealth} limit={4} />
        </Panel>
        <AiInsightCard />
      </div>

      <Panel title="Active Incidents" subtitle="Live operational queue" action="View all" onAction={() => navigate('/incidents?tab=open')}>
        <IncidentTable incidents={data.activeIncidents} empty="No active incidents right now." />
      </Panel>

      <div className="cc-quick">
        <QuickCards counts={data.counts} />
      </div>
    </div>
  );
}

function EmployeeDashboard({ data }: { data: DashboardDto }) {
  const newIncident = useNewIncident();
  const navigate = useNavigate();
  const [kb, setKb] = useState('');
  const requests = useServiceRequests({ scope: 'mine', page: 0, size: 5 });
  const announcements = useAnnouncements(true);

  return (
    <div className="grid gap-[14px] lg:grid-cols-[1.55fr_1fr]">
      <section className="panel ai-panel flex flex-col justify-between gap-4 sm:flex-row sm:items-center" aria-labelledby="report-title">
        <div className="ai-head !mb-0">
          <div className="ai-star" aria-hidden="true">
            <AlertTriangle size={22} />
          </div>
          <div>
            <h3 id="report-title">Something not working?</h3>
            <p>Describe it in a sentence — SA routes it to the right team and tracks the SLA for you.</p>
          </div>
        </div>
        <button type="button" className="gold-btn" onClick={() => newIncident.open()}>
          <Plus size={16} aria-hidden="true" /> Report an issue
        </button>
      </section>

      <Panel title="Find a solution" subtitle="Search the knowledge base before raising a ticket">
        <form
          className="knowledge-search"
          role="search"
          onSubmit={(event) => {
            event.preventDefault();
            navigate(`/knowledge${kb.trim() ? `?q=${encodeURIComponent(kb.trim())}` : ''}`);
          }}
        >
          <Search size={18} aria-hidden="true" />
          <input value={kb} onChange={(event) => setKb(event.target.value)} placeholder="e.g. VPN not connecting" aria-label="Search the knowledge base" />
          <button type="submit" className="gold-btn compact">
            <BookOpen size={14} aria-hidden="true" /> Search
          </button>
        </form>
      </Panel>

      <Panel title="My incidents" subtitle="Issues you have reported" action="View all" to="/incidents">
        <IncidentTable incidents={data.myQueue} columns={['number', 'title', 'priority', 'sla', 'status', 'updated']} empty="You have not reported any incidents." />
      </Panel>

      <Panel title="Service status" subtitle="Current availability" action="All services" to="/services">
        <ServiceHealthList services={data.serviceHealth} limit={8} />
      </Panel>

      <Panel title="My service requests" subtitle="Requests from the service catalogue" action="Service desk" to="/service-desk?tab=mine">
        {requests.isLoading && <LoadingState rows={3} />}
        {requests.isError && <ErrorState error={requests.error} onRetry={() => void requests.refetch()} compact />}
        {requests.data && requests.data.content.length === 0 && (
          <EmptyState
            title="No requests yet"
            description="Need access, hardware or software?"
            action={
              <Link className="gold-btn compact" to="/service-desk?tab=catalog">
                Browse the catalogue
              </Link>
            }
          />
        )}
        {requests.data?.content.map((request) => (
          <Link key={request.id} to={`/service-desk?tab=mine&request=${request.id}`} className="list-row no-underline">
            <div>
              <b>
                <span className="id-cell">{request.number}</span> · {request.title}
              </b>
              <small>
                {request.catalogItemName} · updated {formatRelative(request.updatedAt)}
              </small>
            </div>
            <DemoBadge demo={request.demo} />
            <StatusBadge status={request.status} />
          </Link>
        ))}
      </Panel>

      <Panel title="Announcements" subtitle="Notices and planned maintenance" action="View all" to="/announcements">
        {announcements.isLoading && <LoadingState rows={2} />}
        {announcements.isError && <ErrorState error={announcements.error} onRetry={() => void announcements.refetch()} compact />}
        {announcements.data && announcements.data.length === 0 && <EmptyState title="No active announcements" icon={<Megaphone size={20} />} />}
        {announcements.data?.slice(0, 4).map((announcement) => (
          <div key={announcement.id} className={`list-row announcement ${announcement.severity.toLowerCase()} pl-3`}>
            <div>
              <b>{announcement.title}</b>
              <small className="line-clamp-2">{announcement.body}</small>
              {announcement.serviceName && <small>{announcement.serviceName}</small>}
            </div>
            <Badge value={announcement.severity} />
          </div>
        ))}
      </Panel>
    </div>
  );
}

export default function CommandCenter() {
  const { user, hasRole } = useAuth();
  const newIncident = useNewIncident();
  const [days, setDays] = usePeriod(7);
  const dashboard = useDashboard(days);
  // Everyone outside the reader groups (employees) gets the self-service home.
  const selfService = !hasRole(...READERS);
  const staff = hasRole(...STAFF);
  const agent = staff && !hasRole(...ADMIN_OR_MANAGER);
  const firstName = user?.fullName.trim().split(/\s+/)[0] ?? '';
  const data = dashboard.data;

  return (
    <>
      <PageHeader
        greeting={firstName ? `${greetingFor()}, ${firstName}` : greetingFor()}
        title={selfService ? 'Your IT Service Hub' : 'Operations Command Center'}
        goldTitle
        glow
        subtitle={selfService ? 'Report issues, track your requests and check service status' : 'Real-time IT operations, incident intelligence and service health'}
        actions={
          <>
            {hasRole(...SETTINGS) && <PayrollScenarioButton className="date-btn" />}
            {!selfService && <PeriodSelector value={days} onChange={setDays} />}
            {(staff || selfService) && (
              <button type="button" className="gold-btn" onClick={() => newIncident.open()}>
                <Plus size={16} aria-hidden="true" /> New Incident
              </button>
            )}
          </>
        }
      />
      {dashboard.isLoading && (
        <div className="page-stack">
          <LoadingState variant="kpis" label="Loading the Command Center…" />
          <LoadingState variant="panel" />
        </div>
      )}
      {dashboard.isError && !data && <ErrorState error={dashboard.error} onRetry={() => void dashboard.refetch()} />}
      {data && (
        <div className={dashboard.isPlaceholderData ? 'opacity-60 transition-opacity' : 'transition-opacity'} aria-busy={dashboard.isPlaceholderData}>
          {selfService ? (
            <EmployeeDashboard data={data} />
          ) : (
            <StaffDashboard data={data} showQueue={agent || (staff && data.myQueue.length > 0)} />
          )}
        </div>
      )}
    </>
  );
}
