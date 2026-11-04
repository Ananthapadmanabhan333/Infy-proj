import { formatMinutes, formatNumber } from '../../lib/format';
import type { AnalyticsOverview } from '../../lib/types';
import { UserChip } from '../Avatar';
import { DataTable } from '../DataTable';
import { Panel } from '../Panel';
import { ComplianceMeter } from './ComplianceMeter';
import { useSortedRows } from './sortRows';

type AgentRow = AnalyticsOverview['agentPerformance'][number];
type DepartmentRow = AnalyticsOverview['byDepartment'][number];

/** Users tab: agent performance and department (owning team) performance. */
export function UsersTab({ data, rangeLabel }: { data: AnalyticsOverview; rangeLabel: string }) {
  const agents = useSortedRows<AgentRow>(data.agentPerformance, { key: 'resolved', dir: 'desc' }, {
    name: (row) => row.name,
    department: (row) => row.departmentName,
    resolved: (row) => row.resolved,
    avg: (row) => row.avgResolutionMinutes,
    open: (row) => row.open,
  });
  const departments = useSortedRows<DepartmentRow>(data.byDepartment, { key: 'resolved', dir: 'desc' }, {
    name: (row) => row.name,
    open: (row) => row.open,
    resolved: (row) => row.resolved,
    mttr: (row) => row.mttrMinutes,
    sla: (row) => row.slaCompliance,
  });
  const maxResolved = Math.max(1, ...data.agentPerformance.map((row) => row.resolved));
  const range = rangeLabel.toLowerCase();

  return (
    <div className="an-row even">
      <Panel title="Agent Performance" subtitle={`Resolved incidents and average resolution time · ${range}`}>
        <DataTable
          caption="Agent performance"
          rows={agents.sorted}
          rowKey={(row) => row.userId}
          sort={agents.sort}
          onSortChange={agents.setSort}
          columns={[
            { key: 'name', header: 'Agent', sortKey: 'name', render: (row) => <UserChip name={row.name} full /> },
            { key: 'department', header: 'Team', sortKey: 'department', render: (row) => row.departmentName ?? <span className="muted-text">—</span> },
            {
              key: 'resolved',
              header: 'Resolved',
              sortKey: 'resolved',
              render: (row) => (
                <span className="an-meter">
                  <span className="health-track" aria-hidden="true">
                    <i style={{ width: `${(row.resolved / maxResolved) * 100}%` }} />
                  </span>
                  <b>{formatNumber(row.resolved)}</b>
                </span>
              ),
            },
            { key: 'avg', header: 'Avg. resolution', sortKey: 'avg', render: (row) => formatMinutes(row.avgResolutionMinutes) },
            { key: 'open', header: 'Open now', sortKey: 'open', render: (row) => formatNumber(row.open) },
          ]}
          empty="No agent activity in this period."
        />
      </Panel>
      <Panel title="Department Performance" subtitle={`Workload, MTTR and SLA by owning team · ${range}`}>
        <DataTable
          caption="Department performance"
          rows={departments.sorted}
          rowKey={(row) => row.name}
          sort={departments.sort}
          onSortChange={departments.setSort}
          columns={[
            { key: 'name', header: 'Department', sortKey: 'name', render: (row) => <b>{row.name}</b> },
            { key: 'open', header: 'Open', sortKey: 'open', render: (row) => formatNumber(row.open) },
            { key: 'resolved', header: 'Resolved', sortKey: 'resolved', render: (row) => formatNumber(row.resolved) },
            { key: 'mttr', header: 'MTTR', sortKey: 'mttr', render: (row) => formatMinutes(row.mttrMinutes) },
            { key: 'sla', header: 'SLA compliance', sortKey: 'sla', className: 'min-w-[130px]', render: (row) => <ComplianceMeter value={row.slaCompliance} /> },
          ]}
          empty="No department data in this period."
        />
      </Panel>
    </div>
  );
}
