import { useAnalyticsOverview } from '../api/analytics';
import { useDepartments } from '../api/users';
import { AnalyticsFilterBar } from '../components/analytics/AnalyticsFilterBar';
import '../components/analytics/analytics.css';
import { CustomTab } from '../components/analytics/CustomTab';
import { ExportMenu } from '../components/analytics/ExportMenu';
import { IncidentsTab } from '../components/analytics/IncidentsTab';
import { OperationsTab } from '../components/analytics/OperationsTab';
import { OpsMetricsTab, SlaRiskTab } from '../components/analytics/OpsMetricsTab';
import { ScheduledReportsTab } from '../components/analytics/ScheduledReportsTab';
import { ServicesTab } from '../components/analytics/ServicesTab';
import { useAnalyticsFilters } from '../components/analytics/useAnalyticsFilters';
import { UsersTab } from '../components/analytics/UsersTab';
import { PageHeader } from '../components/PageHeader';
import { ErrorState, LoadingState } from '../components/States';
import { Tabs } from '../components/Tabs';
import { useAuth } from '../hooks/useAuth';
import { useTabParam } from '../hooks/useUrlState';
import { REPORT_EXPORTERS } from '../lib/constants';

export type AnalyticsTab = 'operations' | 'metrics' | 'sla-risk' | 'incidents' | 'services' | 'users' | 'custom' | 'reports';

const TAB_IDS: AnalyticsTab[] = ['operations', 'metrics', 'sla-risk', 'incidents', 'services', 'users', 'custom', 'reports'];
const TABS: { id: AnalyticsTab; label: string }[] = [
  { id: 'operations', label: 'Operations' },
  { id: 'metrics', label: 'Ops metrics' },
  { id: 'sla-risk', label: 'SLA risk' },
  { id: 'incidents', label: 'Incidents' },
  { id: 'services', label: 'Services' },
  { id: 'users', label: 'Users' },
  { id: 'custom', label: 'Custom' },
  { id: 'reports', label: 'Scheduled reports' },
];

export default function AnalyticsPage() {
  const { hasRole } = useAuth();
  const filters = useAnalyticsFilters();
  const [tab, setTab] = useTabParam<AnalyticsTab>(TAB_IDS, 'operations');
  const ownData = tab === 'metrics' || tab === 'sla-risk' || tab === 'reports';
  const overview = useAnalyticsOverview(filters.filters, !ownData);
  const departments = useDepartments();
  const departmentName = departments.data?.find((entry) => entry.id === filters.departmentId)?.name;
  const scopeLabel = `${filters.rangeLabel}${filters.departmentId ? ` · ${departmentName ?? 'selected department'}` : ' · all departments'}`;
  const data = overview.data;

  return (
    <>
      <PageHeader
        title="Analytics & Reports"
        subtitle="Operational insights, trends and performance"
        actions={hasRole(...REPORT_EXPORTERS) ? <ExportMenu filters={filters.filters} scopeLabel={scopeLabel} /> : undefined}
      />
      <div className="page-stack">
        <Tabs<AnalyticsTab> label="Analytics views" value={tab} onChange={setTab} tabs={TABS}>
          {tab !== 'reports' && tab !== 'sla-risk' && (
            <AnalyticsFilterBar state={filters} departments={departments.data} departmentsUnavailable={departments.isError} onCustom={() => setTab('custom')} />
          )}
        </Tabs>

        {tab === 'metrics' ? (
          <OpsMetricsTab state={filters} />
        ) : tab === 'sla-risk' ? (
          <SlaRiskTab />
        ) : tab === 'reports' ? (
          <ScheduledReportsTab />
        ) : tab === 'custom' ? (
          <CustomTab state={filters} departments={departments.data ?? []} data={data} />
        ) : (
          <>
            {overview.isLoading && (
              <>
                <LoadingState variant="kpis" label="Loading analytics…" />
                <LoadingState variant="panel" />
              </>
            )}
            {overview.isError && !data && <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />}
            {data && (
              <div className={`page-stack transition-opacity ${overview.isPlaceholderData ? 'opacity-60' : ''}`} aria-busy={overview.isPlaceholderData}>
                {tab === 'operations' && <OperationsTab data={data} state={filters} />}
                {tab === 'incidents' && <IncidentsTab data={data} rangeLabel={filters.rangeLabel} />}
                {tab === 'services' && <ServicesTab data={data} rangeLabel={filters.rangeLabel} />}
                {tab === 'users' && <UsersTab data={data} rangeLabel={filters.rangeLabel} />}
              </div>
            )}
          </>
        )}
      </div>
    </>
  );
}
