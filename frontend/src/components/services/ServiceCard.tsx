import { Link } from 'react-router-dom';
import { AlertTriangle, Pencil, Server, Wrench } from 'lucide-react';
import { Badge, DemoBadge, StatusBadge } from '../Badge';
import { UserChip } from '../Avatar';
import { formatNumber } from '../../lib/format';
import type { ServiceDto } from '../../lib/types';
import { SERVICE_STATUS_COLOR, availabilityTone, formatAvailability } from './serviceModel';

interface ServiceCardProps {
  service: ServiceDto;
  /** Config admins: shows an edit button. */
  onEdit?: (service: ServiceDto) => void;
}

/** Business service tile: status (with the dependency reason), tier, 30-day availability, open/critical incidents, owner, department. */
export function ServiceCard({ service, onEdit }: ServiceCardProps) {
  const Icon = service.maintenance ? Wrench : Server;
  return (
    <li className="relative">
      <article className="flex h-full flex-col gap-3 rounded-[12px] border border-line bg-panel p-3.5 transition-colors hover:border-bronze" aria-label={service.name}>
        <div className="flex items-start gap-3">
          <span className="icon-tile" aria-hidden="true">
            <Icon size={17} />
          </span>
          <div className="min-w-0 flex-1">
            <Link to={`/services/${service.id}`} className="block truncate text-[13px] font-semibold text-ivory no-underline after:absolute after:inset-0 after:content-[''] hover:text-gold-light">
              {service.name}
            </Link>
            <small className="block truncate text-[10px] text-muted">
              {service.code} · {service.departmentName}
            </small>
          </div>
          <StatusBadge status={service.status} />
          {onEdit && (
            <button type="button" className="icon-btn sm relative z-10 -mt-1 -mr-1" aria-label={`Edit ${service.name}`} onClick={() => onEdit(service)}>
              <Pencil size={13} />
            </button>
          )}
        </div>
        {service.statusReason && (
          <p className="flex items-start gap-1.5 text-[10px] leading-snug text-warning">
            <AlertTriangle size={12} className="mt-px" aria-hidden="true" />
            {service.statusReason}
          </p>
        )}
        <div>
          <div className="mb-1 flex items-baseline justify-between text-[10px] text-muted">
            <span>Availability · 30 days</span>
            <b className={`text-[12px] ${availabilityTone(service.availability30d)}`}>{formatAvailability(service.availability30d)}</b>
          </div>
          <div className="health-track" aria-hidden="true">
            <i style={{ width: `${Math.max(2, Math.min(100, service.availability30d))}%`, background: SERVICE_STATUS_COLOR[service.status] }} />
          </div>
        </div>
        <div className="mt-auto flex flex-wrap items-center gap-1.5">
          <Badge value="outline" label={`Tier ${service.tier}`} />
          <span className="chip">{formatNumber(service.openIncidents)} open</span>
          <span className={`chip ${service.criticalOpen ? 'red-text' : ''}`}>{formatNumber(service.criticalOpen)} critical</span>
          {service.maintenance && <Badge value="maintenance" label="Maintenance" />}
          <DemoBadge demo={service.demo} />
          <span className="ml-auto text-[10px]">
            <UserChip name={service.ownerName} emptyLabel="No owner" />
          </span>
        </div>
      </article>
    </li>
  );
}
