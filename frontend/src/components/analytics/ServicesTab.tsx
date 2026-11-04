import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { axisTick, CHART, tooltipItemStyle, tooltipLabelStyle, tooltipStyle } from '../../lib/chartTheme';
import { formatMinutes, formatNumber, formatPercent, percentOf } from '../../lib/format';
import type { AnalyticsOverview } from '../../lib/types';
import { DataTable } from '../DataTable';
import { serviceIcon } from '../dashboard/serviceIcon';
import { Panel } from '../Panel';
import { EmptyState } from '../States';
import { useSortedRows } from './sortRows';

type ServiceRow = AnalyticsOverview['byService'][number];

const resolutionShare = (row: ServiceRow) => (row.created ? percentOf(row.resolved, row.created) : null);

/** Services tab: created vs. resolved by service and the per-service performance table (volume, open, MTTR). */
export function ServicesTab({ data, rangeLabel }: { data: AnalyticsOverview; rangeLabel: string }) {
  const top = [...data.byService].sort((a, b) => b.created - a.created).slice(0, 10);
  const { sorted, sort, setSort } = useSortedRows<ServiceRow>(data.byService, { key: 'created', dir: 'desc' }, {
    name: (row) => row.name,
    created: (row) => row.created,
    resolved: (row) => row.resolved,
    open: (row) => row.open,
    rate: resolutionShare,
    mttr: (row) => row.mttrMinutes,
  });
  const range = rangeLabel.toLowerCase();

  return (
    <>
      <Panel title="Created vs. Resolved by Service" subtitle={`Top ${top.length || 10} services by volume · ${range}`}>
        {top.length === 0 ? (
          <EmptyState title="No service data in this period" />
        ) : (
          <>
            <ul className="an-legend" aria-label="Series">
              <li>
                <i style={{ background: CHART.gold }} aria-hidden="true" /> Created
              </li>
              <li>
                <i style={{ background: CHART.goldLight }} aria-hidden="true" /> Resolved
              </li>
            </ul>
            <div className="chart-wrap large" role="img" aria-label={`Created and resolved incidents for ${top.map((row) => row.name).join(', ')}`}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={top} margin={{ top: 6, right: 6, left: 0, bottom: 0 }}>
                  <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" vertical={false} />
                  <XAxis dataKey="name" tick={axisTick} axisLine={false} tickLine={false} interval={0} angle={-18} textAnchor="end" height={54} />
                  <YAxis tick={axisTick} axisLine={false} tickLine={false} allowDecimals={false} width={30} />
                  <Tooltip contentStyle={tooltipStyle} labelStyle={tooltipLabelStyle} itemStyle={tooltipItemStyle} cursor={{ fill: '#ffffff08' }} />
                  <Bar dataKey="created" name="Created" fill={CHART.gold} radius={[5, 5, 0, 0]} maxBarSize={22} />
                  <Bar dataKey="resolved" name="Resolved" fill={CHART.goldLight} radius={[5, 5, 0, 0]} maxBarSize={22} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </>
        )}
      </Panel>
      <Panel title="Service Performance" subtitle={`Volume, backlog and MTTR by service · ${range}`}>
        <DataTable
          caption="Incidents by service"
          rows={sorted}
          rowKey={(row) => row.name}
          sort={sort}
          onSortChange={setSort}
          columns={[
            {
              key: 'name',
              header: 'Service',
              sortKey: 'name',
              render: (row) => {
                const Icon = serviceIcon(row.name);
                return (
                  <span className="inline-flex items-center gap-2">
                    <Icon size={14} className="gold-text" aria-hidden="true" />
                    <b>{row.name}</b>
                  </span>
                );
              },
            },
            { key: 'created', header: 'Created', sortKey: 'created', render: (row) => formatNumber(row.created) },
            { key: 'resolved', header: 'Resolved', sortKey: 'resolved', render: (row) => formatNumber(row.resolved) },
            { key: 'rate', header: 'Resolved / created', sortKey: 'rate', render: (row) => formatPercent(resolutionShare(row), 0) },
            { key: 'open', header: 'Open now', sortKey: 'open', render: (row) => <span className={row.open ? 'warning-text' : ''}>{formatNumber(row.open)}</span> },
            { key: 'mttr', header: 'MTTR', sortKey: 'mttr', render: (row) => formatMinutes(row.mttrMinutes) },
          ]}
          empty="No service data in this period."
        />
      </Panel>
    </>
  );
}
