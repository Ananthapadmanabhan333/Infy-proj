import { useId, useState, type ReactNode } from 'react';
import { History, Search, X } from 'lucide-react';
import { useUserOptions } from '../../api/approvals';
import { useServices } from '../../api/services';
import { useDebounce } from '../../hooks/useDebounce';
import { roleLabel, type Tone } from '../../lib/constants';
import { DEPLOYMENT_STATUS_TONES, toneOf } from '../../lib/itsm';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import type { HistoryDto, ServiceRef, UserOption } from '../../lib/itsmTypes';
import type { Role } from '../../lib/types';
import { Badge } from '../Badge';
import { EmptyState, ErrorState, LoadingState } from '../States';

/** A status/enum badge with an explicit tone (Release 3 enums have no global CSS modifiers). */
export function ToneBadge({ value, label, tone }: { value: string | null | undefined; label?: ReactNode; tone: Tone }) {
  if (!value) return null;
  return <Badge value={value} label={label ?? titleCase(value)} tone={tone} />;
}

export function DeploymentStatusBadge({ status }: { status: string }) {
  return <ToneBadge value={status} tone={toneOf(DEPLOYMENT_STATUS_TONES, status)} />;
}

/** Multi-line text block with an empty placeholder. */
export function TextBlock({ text, empty = 'Not recorded yet.' }: { text: string | null | undefined; empty?: string }) {
  if (!text || !text.trim()) return <p className="m-0 text-[11px] text-muted">{empty}</p>;
  return <p className="m-0 whitespace-pre-wrap break-words text-[12px] leading-relaxed">{text}</p>;
}

/** Labelled read-only value for detail grids. */
export function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <small>{label}</small>
      <b className="break-words">{children ?? '—'}</b>
    </div>
  );
}

/** `HistoryDto[]` timeline (problems, changes). */
export function HistoryTimeline({
  query,
  valueLabel = titleCase,
}: {
  query: { data?: HistoryDto[]; isLoading: boolean; isError: boolean; error: unknown; refetch: () => unknown };
  valueLabel?: (value: string) => string;
}) {
  if (query.isLoading) return <LoadingState variant="inline" label="Loading history…" />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} compact />;
  const rows = query.data ?? [];
  if (!rows.length) return <EmptyState title="No history yet" icon={<History size={18} />} />;
  return (
    <ol className="m-0 grid list-none gap-2 p-0" aria-label="History">
      {rows.map((row, index) => (
        <li key={`${row.at}-${index}`} className="list-row !items-start">
          <div className="min-w-0">
            <b className="block break-words">
              {titleCase(row.action)}
              {(row.fromValue || row.toValue) && (
                <span className="font-normal text-muted">
                  {' '}
                  {row.fromValue ? valueLabel(row.fromValue) : '—'} → {row.toValue ? valueLabel(row.toValue) : '—'}
                </span>
              )}
            </b>
            {row.note && <small className="block whitespace-pre-wrap break-words">{row.note}</small>}
            <small className="block" title={formatDateTime(row.at)}>
              {row.actorName ?? 'System'} · {formatRelative(row.at)}
            </small>
          </div>
        </li>
      ))}
    </ol>
  );
}

/** Search-as-you-type user picker over `GET /service-requests/user-options` (any user, no e-mail addresses). */
export function UserSearchPicker({
  label,
  value,
  onChange,
  roles,
  error,
  excludeIds = [],
  hint,
}: {
  label: string;
  value: UserOption | null;
  onChange: (user: UserOption | null) => void;
  roles?: Role[] | null;
  error?: string;
  excludeIds?: number[];
  hint?: ReactNode;
}) {
  const [term, setTerm] = useState('');
  const debounced = useDebounce(term, 300);
  const inputId = useId();
  const options = useUserOptions(debounced, roles, !value);
  const list = (options.data ?? []).filter((option) => !excludeIds.includes(option.id));
  return (
    <div className="field" data-invalid={error ? 'true' : undefined}>
      <div className="field-label">
        <label htmlFor={inputId}>{label}</label>
      </div>
      {value ? (
        <div className="mt-[7px] flex items-center gap-2 rounded-[7px] border border-line bg-[#121112] p-2.5 text-[11px]">
          <span className="min-w-0 flex-1 truncate">
            <b>{value.fullName}</b> <span className="text-muted">· {roleLabel(value.role)}{value.departmentName ? ` · ${value.departmentName}` : ''}</span>
          </span>
          <button type="button" className="icon-btn sm" onClick={() => onChange(null)} aria-label={`Clear ${value.fullName}`}>
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
              placeholder="Type at least 2 letters of a name"
              value={term}
              onChange={(event) => setTerm(event.target.value)}
              autoComplete="off"
              aria-invalid={error ? true : undefined}
            />
          </div>
          {debounced.trim().length >= 2 && (
            <div className="mt-1.5 max-h-48 overflow-auto rounded-[8px] border border-line bg-[#171513] p-1" role="listbox" aria-label={`${label} results`}>
              {options.isFetching && !options.data && <div className="dropdown-item">Searching…</div>}
              {options.isError && <div className="dropdown-item red-text">Search failed.</div>}
              {options.data && list.length === 0 && <div className="dropdown-item">No matching people.</div>}
              {list.map((option) => (
                <button
                  key={option.id}
                  type="button"
                  role="option"
                  aria-selected={false}
                  className="dropdown-item flex w-full items-center justify-between gap-2 text-left"
                  onClick={() => {
                    onChange(option);
                    setTerm('');
                  }}
                >
                  <span className="truncate">{option.fullName}</span>
                  <small className="shrink-0 text-muted">{roleLabel(option.role)}</small>
                </button>
              ))}
            </div>
          )}
        </>
      )}
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && (
        <span className="field-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}

/** Choose affected business services (select to add, chips to remove). */
export function ServiceMultiPicker({ label = 'Affected services', value, onChange, error, hint }: { label?: string; value: number[]; onChange: (ids: number[]) => void; error?: string; hint?: ReactNode }) {
  const services = useServices();
  const id = useId();
  const all = services.data ?? [];
  const chosen = value.map((serviceId) => all.find((service) => service.id === serviceId) ?? { id: serviceId, name: `Service #${serviceId}`, code: '' });
  return (
    <div className="field" data-invalid={error ? 'true' : undefined}>
      <div className="field-label">
        <label htmlFor={id}>{label}</label>
      </div>
      <select
        id={id}
        className="control"
        value=""
        disabled={services.isLoading || services.isError}
        onChange={(event) => {
          const next = Number(event.target.value);
          if (next && !value.includes(next)) onChange([...value, next]);
        }}
      >
        <option value="">{services.isLoading ? 'Loading services…' : services.isError ? 'Services unavailable' : 'Add a service…'}</option>
        {all
          .filter((service) => !value.includes(service.id))
          .map((service) => (
            <option key={service.id} value={service.id}>
              {service.name} ({service.code}) · tier {service.tier}
            </option>
          ))}
      </select>
      {chosen.length > 0 && (
        <div className="tag-row mt-2" aria-label={`${label} chosen`}>
          {chosen.map((service) => (
            <span key={service.id} className="chip">
              {service.name}
              <button type="button" className="icon-btn sm !h-4 !w-4" aria-label={`Remove ${service.name}`} onClick={() => onChange(value.filter((other) => other !== service.id))}>
                <X size={10} />
              </button>
            </span>
          ))}
        </div>
      )}
      {hint && !error && <span className="field-hint">{hint}</span>}
      {error && (
        <span className="field-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}

export function ServiceList({ services }: { services: ServiceRef[] }) {
  if (!services.length) return <span className="text-muted">—</span>;
  return <span className="break-words">{services.map((service) => service.name).join(', ')}</span>;
}

/** "a, b, c" → ["a","b","c"] (trimmed, unique, non-empty). */
export function parseList(value: string): string[] {
  return Array.from(new Set(value.split(',').map((part) => part.trim()).filter(Boolean)));
}
