import { useId, useState, type ReactNode } from 'react';
import { Network, Search, X } from 'lucide-react';
import { useCis } from '../../api/cmdb';
import { useDebounce } from '../../hooks/useDebounce';
import { CI_TYPE_LABELS } from '../../lib/ops';
import type { CiRef, CiSummary } from '../../lib/opsTypes';
import { titleCase } from '../../lib/format';

export function toCiRef(ci: CiSummary | CiRef): CiRef {
  return { id: ci.id, name: ci.name, ciType: ci.ciType, status: ci.status, environment: ci.environment };
}

function CiSearch({ inputId, label, excludeIds, onPick, placeholder }: { inputId: string; label: string; excludeIds: number[]; onPick: (ci: CiRef) => void; placeholder?: string }) {
  const [term, setTerm] = useState('');
  const debounced = useDebounce(term.trim(), 300);
  const results = useCis({ search: debounced, size: 8, sort: 'name,asc' }, debounced.length >= 2);
  const options = (results.data?.content ?? []).filter((ci) => !excludeIds.includes(ci.id));
  return (
    <>
      <div className="relative">
        <Search size={14} className="absolute left-3 top-[22px] text-gold" aria-hidden="true" />
        <input
          id={inputId}
          className="control !pl-8"
          placeholder={placeholder ?? 'Type at least 2 letters of a CI name'}
          value={term}
          onChange={(event) => setTerm(event.target.value)}
          autoComplete="off"
        />
      </div>
      {debounced.length >= 2 && (
        <div className="mt-1.5 max-h-48 overflow-auto rounded-[8px] border border-line bg-[#171513] p-1" role="listbox" aria-label={`${label} results`}>
          {results.isFetching && !results.data && <div className="dropdown-item">Searching…</div>}
          {results.isError && <div className="dropdown-item red-text">Search failed.</div>}
          {results.data && options.length === 0 && <div className="dropdown-item">No matching configuration items.</div>}
          {options.map((ci) => (
            <button
              key={ci.id}
              type="button"
              role="option"
              aria-selected={false}
              className="dropdown-item flex w-full items-center justify-between gap-2 text-left"
              onClick={() => {
                onPick(toCiRef(ci));
                setTerm('');
              }}
            >
              <span className="min-w-0 truncate">{ci.name}</span>
              <small className="shrink-0 text-muted">
                {CI_TYPE_LABELS[ci.ciType]}
                {ci.status !== 'ACTIVE' ? ` · ${titleCase(ci.status)}` : ''}
              </small>
            </button>
          ))}
        </div>
      )}
    </>
  );
}

function FieldFrame({ id, label, error, hint, children }: { id: string; label: string; error?: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <div className="field" data-invalid={error ? 'true' : undefined}>
      <div className="field-label">
        <label htmlFor={id}>{label}</label>
      </div>
      {children}
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && (
        <span className="field-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}

/** Search-as-you-type picker for several configuration items (`GET /cmdb/cis?search`); chosen CIs become chips. */
export function CiMultiPicker({ label = 'Configuration items', value, onChange, error, hint, max = 30 }: { label?: string; value: CiRef[]; onChange: (cis: CiRef[]) => void; error?: string; hint?: ReactNode; max?: number }) {
  const id = useId();
  return (
    <FieldFrame id={id} label={label} error={error} hint={hint}>
      {value.length < max && <CiSearch inputId={id} label={label} excludeIds={value.map((ci) => ci.id)} onPick={(ci) => onChange([...value, ci])} />}
      {value.length > 0 && (
        <div className="tag-row mt-2" aria-label={`${label} chosen`}>
          {value.map((ci) => (
            <span key={ci.id} className="chip">
              <Network size={10} aria-hidden="true" /> {ci.name}
              <button type="button" className="icon-btn sm !h-4 !w-4" aria-label={`Remove ${ci.name}`} onClick={() => onChange(value.filter((other) => other.id !== ci.id))}>
                <X size={10} />
              </button>
            </span>
          ))}
        </div>
      )}
    </FieldFrame>
  );
}

/** Single configuration item (asset link, alert CI, rule target…); `null` = none. */
/** A chosen CI; records that only carry `ciId`/`ciName` (assets, rules) have no type. */
export type CiChoice = Pick<CiRef, 'id' | 'name'> & Partial<Pick<CiRef, 'ciType'>>;

export function CiSinglePicker({ label, value, onChange, error, hint, excludeIds = [] }: { label: string; value: CiChoice | null; onChange: (ci: CiRef | null) => void; error?: string; hint?: ReactNode; excludeIds?: number[] }) {
  const id = useId();
  return (
    <FieldFrame id={id} label={label} error={error} hint={hint}>
      {value ? (
        <div className="mt-[7px] flex items-center gap-2 rounded-[7px] border border-line bg-[#121112] p-2.5 text-[11px]">
          <span className="min-w-0 flex-1 truncate">
            <b>{value.name}</b> {value.ciType && <span className="text-muted">· {CI_TYPE_LABELS[value.ciType]}</span>}
          </span>
          <button type="button" className="icon-btn sm" onClick={() => onChange(null)} aria-label={`Clear ${value.name}`}>
            <X size={14} />
          </button>
        </div>
      ) : (
        <CiSearch inputId={id} label={label} excludeIds={excludeIds} onPick={onChange} />
      )}
    </FieldFrame>
  );
}
