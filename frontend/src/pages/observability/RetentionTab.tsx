import { useEffect, useState } from 'react';
import { Trash2 } from 'lucide-react';
import { usePurgeRetention, useRetention, useSaveRetention } from '../../api/observability';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Panel } from '../../components/Panel';
import { ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { formatDateTime, formatNumber } from '../../lib/format';
import type { PurgeResult, RetentionDto } from '../../lib/r4Types';

const FIELDS: { key: keyof RetentionDto; label: string; min: number; max: number; hint: string }[] = [
  { key: 'rawDays', label: 'Raw metric points (days)', min: 1, max: 90, hint: '1–90, default 7' },
  { key: 'rollupDays', label: 'Minute roll-ups (days)', min: 1, max: 3650, hint: '1–3650, default 90; at least the raw retention' },
  { key: 'logsDays', label: 'Logs (days)', min: 1, max: 365, hint: '1–365, default 14' },
  { key: 'tracesDays', label: 'Traces (days)', min: 1, max: 90, hint: '1–90, default 7' },
];

/** Client-side check of the API's bounds (the API validates again). */
export function retentionError(values: Record<keyof RetentionDto, string>): string | null {
  for (const field of FIELDS) {
    const value = Number(values[field.key]);
    if (!Number.isInteger(value) || value < field.min || value > field.max) return `${field.label}: ${field.min}–${field.max}`;
  }
  if (Number(values.rollupDays) < Number(values.rawDays)) return 'Roll-ups must be kept at least as long as raw points';
  return null;
}

/** Retention: how long telemetry is kept (nightly purge); TELEMETRY_CONFIG edits it and can purge now. */
export function RetentionTab({ canConfigure }: { canConfigure: boolean }) {
  const retention = useRetention();
  const save = useSaveRetention();
  const purge = usePurgeRetention();
  const toast = useToast();
  const [values, setValues] = useState<Record<keyof RetentionDto, string> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [lastPurge, setLastPurge] = useState<PurgeResult | null>(null);

  useEffect(() => {
    if (retention.data) setValues({ rawDays: String(retention.data.rawDays), rollupDays: String(retention.data.rollupDays), logsDays: String(retention.data.logsDays), tracesDays: String(retention.data.tracesDays) });
  }, [retention.data]);

  if (retention.isLoading || (!values && !retention.isError)) return <LoadingState variant="panel" />;
  if (retention.isError || !values) return <ErrorState error={retention.error} onRetry={() => void retention.refetch()} />;
  const dirty = retention.data ? FIELDS.some((field) => String(retention.data![field.key]) !== values[field.key]) : false;

  const submit = () => {
    const problem = retentionError(values);
    setError(problem);
    if (problem) return;
    save.mutate(
      { rawDays: Number(values.rawDays), rollupDays: Number(values.rollupDays), logsDays: Number(values.logsDays), tracesDays: Number(values.tracesDays) },
      {
        onSuccess: () => toast.success('Retention saved'),
        onError: (err) => {
          const apiError = toApiError(err);
          setError(apiError.fieldErrors?.length ? apiError.fieldErrors.map((field) => `${field.field}: ${field.message}`).join('; ') : apiError.message);
        },
      },
    );
  };

  return (
    <Panel
      title="Retention"
      subtitle="A nightly purge job deletes telemetry older than these limits"
      actions={
        canConfigure ? (
          <button type="button" className="danger-btn compact" onClick={() => setConfirming(true)}>
            <Trash2 size={12} aria-hidden="true" /> Purge now
          </button>
        ) : undefined
      }
    >
      <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
        {FIELDS.map((field) => (
          <div key={field.key} className="field">
            <div className="field-label">
              <label htmlFor={`ret-${field.key}`}>{field.label}</label>
            </div>
            <input
              id={`ret-${field.key}`}
              className="control"
              type="number"
              min={field.min}
              max={field.max}
              value={values[field.key]}
              readOnly={!canConfigure}
              onChange={(event) => setValues((current) => (current ? { ...current, [field.key]: event.target.value } : current))}
            />
            <span className="field-hint">{field.hint}</span>
          </div>
        ))}
      </div>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
      {canConfigure && (
        <div className="mt-3 flex justify-end">
          <button type="button" className="gold-btn" onClick={submit} disabled={!dirty || save.isPending}>
            {save.isPending ? 'Saving…' : 'Save retention'}
          </button>
        </div>
      )}
      {lastPurge && (
        <p className="callout green mb-0 mt-3" role="status">
          Purged at {formatDateTime(lastPurge.ranAt)}: {formatNumber(lastPurge.points)} points, {formatNumber(lastPurge.rollups)} roll-ups, {formatNumber(lastPurge.logs)} logs, {formatNumber(lastPurge.spans)} spans,{' '}
          {formatNumber(lastPurge.series)} series, {formatNumber(lastPurge.resources)} resources.
        </p>
      )}
      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title="Purge expired telemetry now?"
        message="Deletes every metric point, roll-up, log record and span older than the saved retention limits, plus series and resources left without data. This cannot be undone and is audited."
        confirmLabel="Purge now"
        tone="danger"
        pending={purge.isPending}
        error={purge.isError ? errorMessage(purge.error) : null}
        onConfirm={() =>
          purge.mutate(undefined, {
            onSuccess: (result) => {
              setLastPurge(result);
              setConfirming(false);
              toast.success('Purge finished');
            },
          })
        }
      />
    </Panel>
  );
}
