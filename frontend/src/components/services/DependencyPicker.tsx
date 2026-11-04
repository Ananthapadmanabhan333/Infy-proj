import { useId, useMemo, useState } from 'react';
import { X } from 'lucide-react';
import { StatusBadge } from '../Badge';
import { SearchInput } from '../SearchInput';
import type { ServiceDto } from '../../lib/types';
import { MODAL_SEARCH_CLASS } from '../servicedesk/requestModel';

interface DependencyPickerProps {
  services: ServiceDto[];
  value: number[];
  onChange: (ids: number[]) => void;
  /** Itself and services that already depend on it (would create a cycle). */
  blocked: Set<number>;
  selfId: number | null;
  disabled?: boolean;
  error?: string | null;
}

/** Multi-select of the services this one depends on ("Depends on"), with search and the current status of each. */
export function DependencyPicker({ services, value, onChange, blocked, selfId, disabled, error }: DependencyPickerProps) {
  const [term, setTerm] = useState('');
  const labelId = useId();
  const selected = useMemo(() => new Set(value), [value]);
  const byId = useMemo(() => new Map(services.map((service) => [service.id, service])), [services]);
  const options = services
    .filter((service) => service.id !== selfId)
    .filter((service) => !term.trim() || `${service.name} ${service.code}`.toLowerCase().includes(term.trim().toLowerCase()));

  const toggle = (id: number) => onChange(selected.has(id) ? value.filter((existing) => existing !== id) : [...value, id]);

  return (
    <div className="field" data-invalid={error ? 'true' : undefined}>
      <div className="field-label">
        <span id={labelId} className="text-[11px] font-semibold text-[#d9cfc1]">
          Depends on
        </span>
      </div>
      <p className="field-hint mb-2">If any of these is in outage, this service shows as degraded.</p>
      {value.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-1.5" aria-label="Selected dependencies">
          {value.map((id) => {
            const service = byId.get(id);
            return (
              <span key={id} className="chip">
                {service ? service.name : `Service #${id}`}
                <button type="button" className="ml-0.5 inline-flex text-muted hover:text-gold-light" onClick={() => toggle(id)} disabled={disabled} aria-label={`Remove dependency ${service?.name ?? id}`}>
                  <X size={11} />
                </button>
              </span>
            );
          })}
        </div>
      )}
      <SearchInput value={term} onChange={setTerm} placeholder="Find a service…" label="Find a dependency" className={MODAL_SEARCH_CLASS} />
      <ul className="mt-2 grid max-h-[190px] gap-1 overflow-y-auto rounded-[8px] border border-line bg-secondary p-1.5" role="group" aria-labelledby={labelId}>
        {options.length === 0 && <li className="px-2 py-1.5 text-[11px] text-muted">No other services match.</li>}
        {options.map((service) => {
          const isBlocked = blocked.has(service.id) && !selected.has(service.id);
          return (
            <li key={service.id}>
              <label className={`m-0 flex items-center gap-2 rounded-[6px] px-2 py-1.5 text-[11px] text-ivory ${isBlocked ? 'opacity-55' : 'cursor-pointer hover:bg-panel-raised'}`}>
                <input type="checkbox" className="h-[14px] w-[14px] accent-gold" checked={selected.has(service.id)} onChange={() => toggle(service.id)} disabled={disabled || isBlocked} />
                <span className="min-w-0 flex-1 truncate">
                  {service.name} <span className="text-muted">· {service.code}</span>
                </span>
                {isBlocked && <span className="text-[9px] text-muted">depends on this service</span>}
                <StatusBadge status={service.status} />
              </label>
            </li>
          );
        })}
      </ul>
      {error && (
        <span className="field-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}
