import { Link } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import { formatNumber, formatPercent } from '../../lib/format';
import type { SlaOverview } from '../../lib/types';
import { ComplianceMeter } from '../analytics/ComplianceMeter';
import { serviceIcon } from '../dashboard/serviceIcon';
import { EmptyState } from '../States';

type ServiceRow = SlaOverview['byService'][number];
type DepartmentRow = SlaOverview['byDepartment'][number];

/** Breaches per service: SLAs completed late in the period plus active clocks already past target. */
export const serviceBreaches = (row: ServiceRow) => row.missed + row.breached;

export function rankServiceBreaches(rows: ServiceRow[]): ServiceRow[] {
  return [...rows]
    .filter((row) => serviceBreaches(row) > 0 || row.atRisk > 0)
    .sort((a, b) => serviceBreaches(b) - serviceBreaches(a) || b.atRisk - a.atRisk || a.serviceName.localeCompare(b.serviceName));
}

/** Reference "Breaches by Service": icon, name, horizontal bar and count. */
export function BreachesByService({ rows, limit = 6 }: { rows: ServiceRow[]; limit?: number }) {
  const ranked = rankServiceBreaches(rows).slice(0, limit);
  if (!ranked.length) {
    return <EmptyState title="No SLA breaches in this period" description="Every service met its resolution targets." icon={<ShieldCheck size={20} />} />;
  }
  const max = Math.max(1, ...ranked.map(serviceBreaches));
  return (
    <ul className="sla-bars" aria-label="SLA breaches by service">
      {ranked.map((row) => {
        const Icon = serviceIcon(row.serviceName);
        const breaches = serviceBreaches(row);
        return (
          <li key={row.serviceId}>
            <Link
              to={`/services/${row.serviceId}`}
              className="sla-bar-row"
              aria-label={`${row.serviceName}: ${breaches} breaches (${row.missed} missed, ${row.breached} active past target), ${row.atRisk} at risk, ${formatPercent(row.compliance)} compliance`}
            >
              <span className="icon-tile" aria-hidden="true">
                <Icon size={14} />
              </span>
              <span className="sla-bar-name">
                <b>{row.serviceName}</b>
                <small>
                  {formatPercent(row.compliance)} compliance{row.atRisk ? ` · ${row.atRisk} at risk` : ''}
                </small>
              </span>
              <span className="sla-bar-track" aria-hidden="true">
                <i style={{ width: `${Math.max(breaches ? 6 : 0, (breaches / max) * 100)}%` }} />
              </span>
              <strong className={breaches ? '' : 'muted-text'}>{formatNumber(breaches)}</strong>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

/** Breaches by department (owning team): met, missed, at risk and compliance. */
export function BreachesByDepartment({ rows }: { rows: DepartmentRow[] }) {
  if (!rows.length) return <EmptyState title="No department SLA data in this period" />;
  const sorted = [...rows].sort((a, b) => b.missed - a.missed || b.atRisk - a.atRisk || a.name.localeCompare(b.name));
  return (
    <div className="table-scroll">
      <table className="static-table data-table">
        <caption className="sr-only">SLA breaches by department</caption>
        <thead>
          <tr>
            <th scope="col">Department</th>
            <th scope="col">Met</th>
            <th scope="col">Missed</th>
            <th scope="col">At risk</th>
            <th scope="col">Compliance</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((row) => (
            <tr key={row.name}>
              <td>
                <b>{row.name}</b>
              </td>
              <td>{formatNumber(row.met)}</td>
              <td className={row.missed ? 'red-text' : ''}>{formatNumber(row.missed)}</td>
              <td className={row.atRisk ? 'warning-text' : ''}>{formatNumber(row.atRisk)}</td>
              <td className="min-w-[140px]">
                <ComplianceMeter value={row.compliance} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
