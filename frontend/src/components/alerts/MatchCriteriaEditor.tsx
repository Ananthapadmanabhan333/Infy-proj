import { useEffect } from 'react';
import '../ops/ops.css';
import { Plus, Trash2 } from 'lucide-react';
import { useCiNames } from '../../api/cmdb';
import { ALERT_SEVERITIES, ALERT_SOURCE_KINDS, ALERT_SOURCE_LABELS } from '../../lib/ops';
import type { AlertSourceDto } from '../../lib/opsTypes';
import { titleCase } from '../../lib/format';
import { CiMultiPicker } from '../cmdb/CiPicker';
import { TextField } from '../FormField';
import { ServiceMultiPicker } from '../itsm/ItsmParts';
import type { MatchDraft } from './matchCriteria';

function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((other) => other !== value) : [...list, value];
}

function ChipToggles<T extends string | number>({ legend, options, value, onChange, label }: { legend: string; options: T[]; value: T[]; onChange: (next: T[]) => void; label: (value: T) => string }) {
  return (
    <fieldset className="m-0 mb-3 min-w-0 border-0 p-0">
      <legend className="mb-1 text-[11px] font-semibold">{legend}</legend>
      <div className="flex flex-wrap gap-1.5">
        {options.map((option) => {
          const on = value.includes(option);
          return (
            <button key={String(option)} type="button" className="chip chip-toggle" aria-pressed={on} onClick={() => onChange(toggle(value, option))}>
              {label(option)}
            </button>
          );
        })}
      </div>
    </fieldset>
  );
}

/**
 * Editor for a rule's `MatchCriteria`: every criterion that is set must match; nothing set = every alert.
 * Label values: an exact value, alternatives `a|b`, or `*` for "label present" (values ignore case).
 */
export function MatchCriteriaEditor({ value, onChange, sources, error }: { value: MatchDraft; onChange: (next: MatchDraft) => void; sources?: AlertSourceDto[]; error?: string | null }) {
  const set = (patch: Partial<MatchDraft>) => onChange({ ...value, ...patch });
  const unnamed = value.cis.filter((ci) => ci.name.startsWith('CI #')).map((ci) => ci.id);
  const names = useCiNames(unnamed);
  useEffect(() => {
    if (!names.size) return;
    if (value.cis.some((ci) => names.has(ci.id) && ci.name !== names.get(ci.id))) {
      onChange({ ...value, cis: value.cis.map((ci) => (names.has(ci.id) ? { ...ci, name: names.get(ci.id)! } : ci)) });
    }
  }, [names, value, onChange]);

  return (
    <div className="action-card" role="group" aria-label="Alert matcher">
      <p className="m-0 mb-2 text-[11px] text-muted">Every criterion you set must match. Leave everything empty to match every alert.</p>
      <ChipToggles legend="Severities" options={ALERT_SEVERITIES} value={value.severities} onChange={(severities) => set({ severities })} label={(severity) => titleCase(severity)} />
      <ChipToggles legend="Source kinds" options={ALERT_SOURCE_KINDS} value={value.sources} onChange={(next) => set({ sources: next })} label={(kind) => ALERT_SOURCE_LABELS[kind]} />
      {sources && sources.length > 0 && (
        <ChipToggles legend="Specific sources" options={sources.map((source) => source.id)} value={value.sourceIds} onChange={(sourceIds) => set({ sourceIds })} label={(id) => sources.find((source) => source.id === id)?.name ?? `#${id}`} />
      )}
      <ServiceMultiPicker label="Business services" value={value.serviceIds} onChange={(serviceIds) => set({ serviceIds })} />
      <CiMultiPicker
        label="Configuration items"
        value={value.cis.map((ci) => ({ id: ci.id, name: ci.name, ciType: 'APPLICATION', status: 'ACTIVE', environment: null }))}
        onChange={(cis) => set({ cis: cis.map((ci) => ({ id: ci.id, name: ci.name })) })}
        max={50}
      />
      <fieldset className="m-0 mb-3 min-w-0 border-0 p-0">
        <legend className="mb-1 text-[11px] font-semibold">Labels</legend>
        {value.labels.length === 0 && <p className="m-0 mb-1 text-[11px] text-muted">No label matchers.</p>}
        <div className="grid gap-2">
          {value.labels.map((row, index) => (
            <div key={index} className="grid grid-cols-[1fr_1fr_auto] gap-2" role="group" aria-label={`Label matcher ${index + 1}`}>
              <input
                className="control sm"
                aria-label={`Label ${index + 1} name`}
                placeholder="label, e.g. job"
                value={row.name}
                onChange={(event) => set({ labels: value.labels.map((other, i) => (i === index ? { ...other, name: event.target.value } : other)) })}
              />
              <input
                className="control sm"
                aria-label={`Label ${index + 1} value`}
                placeholder="value, a|b or *"
                value={row.value}
                onChange={(event) => set({ labels: value.labels.map((other, i) => (i === index ? { ...other, value: event.target.value } : other)) })}
              />
              <button type="button" className="icon-btn sm self-center" aria-label={`Remove label matcher ${row.name || index + 1}`} onClick={() => set({ labels: value.labels.filter((_, i) => i !== index) })}>
                <Trash2 size={13} />
              </button>
            </div>
          ))}
        </div>
        {value.labels.length < 20 && (
          <button type="button" className="link-btn mt-2 text-[11px]" onClick={() => set({ labels: [...value.labels, { name: '', value: '' }] })}>
            <Plus size={12} aria-hidden="true" /> Add label matcher
          </button>
        )}
      </fieldset>
      <TextField label="Title contains" maxLength={200} value={value.titleContains} onChange={(event) => set({ titleContains: event.target.value })} hint="Case-insensitive text in the alert title." />
      {error && (
        <p className="field-error m-0" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
