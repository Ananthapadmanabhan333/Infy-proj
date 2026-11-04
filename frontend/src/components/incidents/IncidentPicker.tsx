import { useId, useState } from 'react';
import { Search, X } from 'lucide-react';
import { useIncidents } from '../../api/incidents';
import { useDebounce } from '../../hooks/useDebounce';
import type { IncidentSummary } from '../../lib/types';
import { PriorityBadge, StatusBadge } from '../Badge';

interface IncidentPickerProps {
  label: string;
  value: IncidentSummary | null;
  onChange: (incident: IncidentSummary | null) => void;
  excludeIds?: number[];
  error?: string;
  /** Only parent-level incidents (for "link as child"). */
  parentOnly?: boolean;
}

/** Search-as-you-type incident selector (by number or title). */
export function IncidentPicker({ label, value, onChange, excludeIds = [], error, parentOnly }: IncidentPickerProps) {
  const [term, setTerm] = useState('');
  const debounced = useDebounce(term.trim(), 300);
  const inputId = useId();
  const results = useIncidents({ search: debounced, size: 8, parentOnly: parentOnly || undefined, sort: 'createdAt,desc' }, debounced.length >= 2 && !value);
  const options = (results.data?.content ?? []).filter((incident) => !excludeIds.includes(incident.id));

  return (
    <div className="field" data-invalid={error ? 'true' : undefined}>
      <label htmlFor={inputId}>{label}</label>
      {value ? (
        <div className="mt-[7px] flex items-center gap-2 rounded-[7px] border border-[#51402c] bg-[#121112] p-2.5 text-[11px]">
          <span className="id-cell">{value.number}</span>
          <span className="min-w-0 flex-1 truncate">{value.title}</span>
          <StatusBadge status={value.status} />
          <button type="button" className="icon-btn sm" onClick={() => onChange(null)} aria-label={`Clear ${value.number}`}>
            <X size={14} />
          </button>
        </div>
      ) : (
        <>
          <div className="relative">
            <Search size={14} className="absolute left-3 top-[22px] text-gold" aria-hidden="true" />
            <input
              id={inputId}
              className="control !pl-8"
              placeholder="Type an incident number (INC-7001) or title"
              value={term}
              onChange={(event) => setTerm(event.target.value)}
              autoComplete="off"
              aria-invalid={error ? true : undefined}
            />
          </div>
          {debounced.length >= 2 && (
            <div className="mt-1.5 max-h-56 overflow-auto rounded-[8px] border border-line bg-[#171513] p-1" role="listbox" aria-label={`${label} results`}>
              {results.isFetching && !results.data && <div className="dropdown-item">Searching…</div>}
              {results.isError && <div className="dropdown-item red-text">Search failed.</div>}
              {results.data && options.length === 0 && <div className="dropdown-item">No matching incidents.</div>}
              {options.map((incident) => (
                <button
                  key={incident.id}
                  type="button"
                  role="option"
                  aria-selected={false}
                  className="dropdown-item items-center"
                  onClick={() => {
                    onChange(incident);
                    setTerm('');
                  }}
                >
                  <span className="id-cell">{incident.number}</span>
                  <span className="min-w-0 flex-1 truncate">{incident.title}</span>
                  <PriorityBadge priority={incident.priority} />
                </button>
              ))}
            </div>
          )}
        </>
      )}
      {error && (
        <span className="field-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}
