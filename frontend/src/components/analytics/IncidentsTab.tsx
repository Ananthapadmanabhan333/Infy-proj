import { Repeat } from 'lucide-react';
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { axisTick, CHART, PRIORITY_COLORS, SERIES_COLORS, tooltipItemStyle, tooltipLabelStyle, tooltipStyle } from '../../lib/chartTheme';
import { PALETTE, PRIORITIES, PRIORITY_LABELS } from '../../lib/constants';
import { formatMinutes, formatNumber, formatPercent, titleCase } from '../../lib/format';
import type { AnalyticsOverview } from '../../lib/types';
import { PriorityBadge } from '../Badge';
import { DonutChart } from '../DonutChart';
import { Panel } from '../Panel';
import { EmptyState } from '../States';
import { ComplianceMeter } from './ComplianceMeter';

const cursor = { fill: '#ffffff08' };
// Categories are not statuses: keep the warm series colours and leave green/red for good/bad.
const CATEGORY_COLORS = SERIES_COLORS.filter((color) => color !== PALETTE.success && color !== PALETTE.critical);

/** Incidents tab: by priority, by category, backlog aging and recurring patterns. */
export function IncidentsTab({ data, rangeLabel }: { data: AnalyticsOverview; rangeLabel: string }) {
  const byPriority = PRIORITIES.map(
    (priority) => data.byPriority.find((row) => row.priority === priority) ?? { priority, count: 0, mttrMinutes: null, slaCompliance: null },
  );
  const byCategory = [...data.byCategory].sort((a, b) => b.count - a.count).map((row) => ({ ...row, label: titleCase(row.category) }));
  const aging = data.backlogAging;
  const range = rangeLabel.toLowerCase();

  return (
    <>
      <div className="an-row even">
        <Panel title="By Priority" subtitle={`Incidents created · ${range}`}>
          <DonutChart
            data={byPriority.map((row) => ({ key: row.priority, label: PRIORITY_LABELS[row.priority], value: row.count, color: PRIORITY_COLORS[row.priority] }))}
            centerLabel="Incidents"
            size={140}
            ariaLabel="Incidents by priority"
            empty={<EmptyState title="No incidents in this period" />}
          />
          <div className="table-scroll mt-3">
            <table className="static-table data-table">
              <caption className="sr-only">MTTR and SLA compliance by priority</caption>
              <thead>
                <tr>
                  <th scope="col">Priority</th>
                  <th scope="col">Incidents</th>
                  <th scope="col">MTTR</th>
                  <th scope="col">SLA compliance</th>
                </tr>
              </thead>
              <tbody>
                {byPriority.map((row) => (
                  <tr key={row.priority}>
                    <td>
                      <PriorityBadge priority={row.priority} />
                    </td>
                    <td>{formatNumber(row.count)}</td>
                    <td>{formatMinutes(row.mttrMinutes)}</td>
                    <td className="min-w-[130px]">
                      <ComplianceMeter value={row.slaCompliance} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
        <Panel title="By Category" subtitle={`Incidents created · ${range}`}>
          {byCategory.length === 0 ? (
            <EmptyState title="No incidents in this period" />
          ) : (
            <div className="chart-wrap large" role="img" aria-label={`Incidents by category: ${byCategory.map((row) => `${row.label} ${row.count}`).join(', ')}`}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={byCategory} layout="vertical" margin={{ left: 4, right: 12 }}>
                  <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" horizontal={false} />
                  <XAxis type="number" tick={axisTick} axisLine={false} tickLine={false} allowDecimals={false} />
                  <YAxis type="category" dataKey="label" tick={axisTick} axisLine={false} tickLine={false} width={92} />
                  <Tooltip contentStyle={tooltipStyle} labelStyle={tooltipLabelStyle} itemStyle={tooltipItemStyle} cursor={cursor} />
                  <Bar dataKey="count" name="Incidents" radius={[0, 5, 5, 0]} barSize={14}>
                    {byCategory.map((row, index) => (
                      <Cell key={row.category} fill={CATEGORY_COLORS[index % CATEGORY_COLORS.length]} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </Panel>
      </div>
      <div className="an-row even">
        <Panel title="Backlog Aging" subtitle="Open incidents by age (now)">
          {aging.every((row) => row.count === 0) ? (
            <EmptyState title="No open backlog" description="Every incident is resolved or closed." />
          ) : (
            <div className="chart-wrap medium" role="img" aria-label={`Open incidents by age: ${aging.map((row) => `${row.bucket} ${row.count}`).join(', ')}`}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={aging}>
                  <CartesianGrid stroke={CHART.grid} strokeDasharray="3 5" vertical={false} />
                  <XAxis dataKey="bucket" tick={axisTick} axisLine={false} tickLine={false} />
                  <YAxis tick={axisTick} axisLine={false} tickLine={false} allowDecimals={false} width={30} />
                  <Tooltip contentStyle={tooltipStyle} labelStyle={tooltipLabelStyle} itemStyle={tooltipItemStyle} cursor={cursor} />
                  <Bar dataKey="count" name="Open incidents" radius={[5, 5, 0, 0]} barSize={34}>
                    {aging.map((row, index) => (
                      <Cell key={row.bucket} fill={[CHART.goldLight, CHART.gold, CHART.warning, CHART.critical][Math.min(index, 3)]} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </Panel>
        <Panel title="Top Recurring Issues" subtitle={`Patterns seen at least twice · ${range}`}>
          {data.topRecurring.length === 0 ? (
            <EmptyState title="No recurring patterns detected" icon={<Repeat size={20} />} />
          ) : (
            <ol className="an-recurring">
              {data.topRecurring.map((row, index) => (
                <li key={`${row.pattern}-${index}`}>
                  <span className="an-recurring-count">{formatNumber(row.count)}</span>
                  <span className="min-w-0">
                    <b>{row.pattern}</b>
                    <small>{row.serviceName ?? 'Multiple services'}</small>
                  </span>
                  <span className="an-recurring-share">{formatPercent((row.count / Math.max(1, data.kpis.createdInPeriod)) * 100, 0)} of volume</span>
                </li>
              ))}
            </ol>
          )}
        </Panel>
      </div>
    </>
  );
}
