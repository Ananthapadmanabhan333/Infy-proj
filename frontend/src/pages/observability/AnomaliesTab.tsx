import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { FlaskConical, Pencil, Play, Plus, Radar, Trash2 } from 'lucide-react';
import { useAnomalies, useDeleteDetector, useDetectors, useEvaluateDetector, useMetricNames, useRunDetector, useSaveDetector } from '../../api/observability';
import { useServices } from '../../api/services';
import { DemoBadge, SimulatedBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { formatDateTime, formatNumber, formatRelative, isoToLocalInput, localInputToIso, titleCase } from '../../lib/format';
import { ALERT_SEVERITIES } from '../../lib/ops';
import type { AlertSeverity } from '../../lib/opsTypes';
import type { DetectorAlgorithm, DetectorDirection, DetectorDto, DetectorRequest, DetectorSeasonality, EvaluationResult, MetricAggregation } from '../../lib/r4Types';
import { attributesText, formatRatio, formatValue, RangeSelect, useTimeWindow, type RangeValue } from './obsShared';

// ------------------------------------------------------------------ evaluation summary

/** One line for a detector's stored or fresh evaluation — INSUFFICIENT_DATA is shown as such, never as a score. */
export function evaluationSummary(evaluation: EvaluationResult | null | undefined): string {
  if (!evaluation) return 'Not evaluated yet';
  if (evaluation.status === 'INSUFFICIENT_DATA') return `Insufficient data${evaluation.message ? ` — ${evaluation.message}` : ''}`;
  const precision = evaluation.precision === null ? 'precision undefined (no detections)' : `precision ${formatRatio(evaluation.precision)}`;
  const recall = evaluation.recall === null ? 'recall undefined' : `recall ${formatRatio(evaluation.recall)}`;
  return `${precision} · ${recall} · ${formatNumber(evaluation.labels.length)} labels`;
}

function EvaluationView({ result }: { result: EvaluationResult }) {
  if (result.status === 'INSUFFICIENT_DATA') {
    return (
      <div className="callout" role="status">
        <FlaskConical size={15} aria-hidden="true" />
        <div>
          <b>Insufficient data.</b> {result.message ?? 'There are no labelled faults in this window, so precision and recall cannot be computed.'} Evaluated{' '}
          {formatNumber(result.seriesEvaluated)} series, {formatNumber(result.pointsEvaluated)} points.
        </div>
      </div>
    );
  }
  return (
    <div className="grid gap-2 text-[11px]" aria-label="Evaluation result">
      <ul className="kv-list">
        <li>
          <span>Precision</span>
          <span>{result.precision === null ? 'undefined' : formatRatio(result.precision)}</span>
        </li>
        <li>
          <span>Recall</span>
          <span>{result.recall === null ? 'undefined' : formatRatio(result.recall)}</span>
        </li>
        <li>
          <span>F1</span>
          <span>{result.f1 === null ? 'undefined' : result.f1.toFixed(2)}</span>
        </li>
        <li>
          <span>TP / FP / FN</span>
          <span>
            {result.truePositives} / {result.falsePositives} / {result.falseNegatives}
          </span>
        </li>
        <li>
          <span>Evaluated</span>
          <span>
            {formatNumber(result.seriesEvaluated)} series · {formatNumber(result.pointsEvaluated)} points
          </span>
        </li>
      </ul>
      {result.message && <small className="text-muted">{result.message}</small>}
      <b>Labels ({formatNumber(result.labels.length)})</b>
      <ul className="m-0 grid list-none gap-1 p-0" aria-label="Evaluation labels">
        {result.labels.map((label, index) => (
          <li key={`${label.source}-${index}`} className="flex flex-wrap items-center gap-1.5">
            <ToneBadge value={label.source} label={titleCase(label.source)} tone="neutral" />
            <span>{label.reference ?? '—'}</span>
            <span className="text-muted">
              {formatDateTime(label.from)} → {formatDateTime(label.to)}
            </span>
            <ToneBadge value={label.detected ? 'detected' : 'missed'} label={label.detected ? 'Detected' : 'Missed'} tone={label.detected ? 'success' : 'critical'} />
          </li>
        ))}
      </ul>
      <small className="text-muted">
        {formatNumber(result.detections.length)} detection{result.detections.length === 1 ? '' : 's'} between {formatDateTime(result.from)} and {formatDateTime(result.to)}.
      </small>
    </div>
  );
}

function EvaluateModal({ detector, onClose }: { detector: DetectorDto; onClose: () => void }) {
  const evaluate = useEvaluateDetector();
  const now = Date.now();
  const [from, setFrom] = useState(isoToLocalInput(new Date(now - 7 * 86_400_000).toISOString()));
  const [to, setTo] = useState(isoToLocalInput(new Date(now).toISOString()));
  const [useFaults, setUseFaults] = useState(detector.includeSimulated);
  const [useAlerts, setUseAlerts] = useState(true);
  const [tolerance, setTolerance] = useState('5');
  const run = () =>
    evaluate.mutate({
      id: detector.id,
      body: { from: localInputToIso(from) ?? undefined, to: localInputToIso(to) ?? undefined, useSimulatedFaults: useFaults, useAlerts, toleranceMinutes: Number(tolerance) },
    });
  return (
    <Modal
      open
      onClose={onClose}
      title={`Evaluate ${detector.name}`}
      eyebrow="MEASURED ON LABELLED HISTORY"
      description="Replays the detector over the window and compares its detections with labelled faults: simulated fault windows, past alerts on the series' services (not raised by this detector) and none other. Up to 31 days."
      size="lg"
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose}>
            Close
          </button>
          <button type="button" className="gold-btn" onClick={run} disabled={evaluate.isPending}>
            {evaluate.isPending ? 'Evaluating…' : 'Evaluate'}
          </button>
        </>
      }
    >
      <div className="grid gap-2 sm:grid-cols-2">
        <div className="field">
          <div className="field-label">
            <label htmlFor="eval-from">From</label>
          </div>
          <input id="eval-from" type="datetime-local" className="control" value={from} onChange={(event) => setFrom(event.target.value)} />
        </div>
        <div className="field">
          <div className="field-label">
            <label htmlFor="eval-to">To</label>
          </div>
          <input id="eval-to" type="datetime-local" className="control" value={to} onChange={(event) => setTo(event.target.value)} />
        </div>
        <div className="field">
          <div className="field-label">
            <label htmlFor="eval-tolerance">Tolerance (minutes)</label>
          </div>
          <input id="eval-tolerance" type="number" min={0} max={60} className="control" value={tolerance} onChange={(event) => setTolerance(event.target.value)} />
        </div>
        <div className="grid content-end gap-1 text-[11px]">
          <label className="inline-flex items-center gap-2">
            <input type="checkbox" checked={useFaults} onChange={(event) => setUseFaults(event.target.checked)} /> Use simulated fault windows as labels
          </label>
          <label className="inline-flex items-center gap-2">
            <input type="checkbox" checked={useAlerts} onChange={(event) => setUseAlerts(event.target.checked)} /> Use past alerts as labels
          </label>
        </div>
      </div>
      {evaluate.isError && (
        <p className="field-error" role="alert">
          {errorMessage(evaluate.error)}
        </p>
      )}
      {evaluate.data && (
        <div className="mt-3">
          <EvaluationView result={evaluate.data} />
        </div>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ detector editor

interface DetectorDraft {
  name: string;
  description: string;
  metricName: string;
  serviceId: string;
  attributeFilters: string;
  valueAggregation: MetricAggregation;
  algorithm: DetectorAlgorithm;
  windowPoints: string;
  threshold: string;
  consecutivePoints: string;
  minSamples: string;
  direction: DetectorDirection;
  seasonality: DetectorSeasonality;
  includeSimulated: boolean;
  raiseAlert: boolean;
  alertSeverity: AlertSeverity;
  createIncident: boolean;
  enabled: boolean;
}

function draftOf(detector: DetectorDto | null): DetectorDraft {
  return {
    name: detector?.name ?? '',
    description: detector?.description ?? '',
    metricName: detector?.metricName ?? '',
    serviceId: detector?.serviceId ? String(detector.serviceId) : '',
    attributeFilters: Object.entries(detector?.attributeFilters ?? {})
      .map(([key, value]) => `${key}=${value}`)
      .join('\n'),
    valueAggregation: detector?.valueAggregation ?? 'avg',
    algorithm: detector?.algorithm ?? 'EWMA',
    windowPoints: String(detector?.windowPoints ?? 60),
    threshold: String(detector?.threshold ?? 3),
    consecutivePoints: String(detector?.consecutivePoints ?? 3),
    minSamples: String(detector?.minSamples ?? 30),
    direction: detector?.direction ?? 'UP',
    seasonality: detector?.seasonality ?? 'NONE',
    includeSimulated: detector?.includeSimulated ?? false,
    raiseAlert: detector?.raiseAlert ?? true,
    alertSeverity: detector?.alertSeverity ?? 'MAJOR',
    createIncident: detector?.createIncident ?? false,
    enabled: detector?.enabled ?? true,
  };
}

/** "key=value" lines → filters; returns an error text for malformed lines. */
export function parseAttributeLines(text: string): { filters: Record<string, string>; error: string | null } {
  const filters: Record<string, string> = {};
  const lines = text.split('\n').map((line) => line.trim()).filter(Boolean);
  for (const line of lines) {
    const index = line.indexOf('=');
    if (index <= 0) return { filters, error: `"${line}" is not key=value` };
    filters[line.slice(0, index).trim()] = line.slice(index + 1).trim();
  }
  if (Object.keys(filters).length > 10) return { filters, error: 'At most 10 attribute filters' };
  return { filters, error: null };
}

const BOUNDS: Record<'windowPoints' | 'threshold' | 'consecutivePoints' | 'minSamples', [number, number, string]> = {
  windowPoints: [5, 10080, 'Window (points)'],
  threshold: [1, 20, 'Threshold (|z|)'],
  consecutivePoints: [1, 60, 'Consecutive points'],
  minSamples: [5, 100000, 'Minimum samples'],
};

function Field({ id, label, children, hint }: { id: string; label: string; children: ReactNode; hint?: string }) {
  return (
    <div className="field">
      <div className="field-label">
        <label htmlFor={id}>{label}</label>
      </div>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </div>
  );
}

function DetectorEditor({ detector, onClose }: { detector: DetectorDto | null; onClose: () => void }) {
  const save = useSaveDetector();
  const toast = useToast();
  const services = useServices();
  const metrics = useMetricNames({ includeSimulated: true });
  const [draft, setDraft] = useState<DetectorDraft>(() => draftOf(detector));
  const [error, setError] = useState<string | null>(null);
  const set = <K extends keyof DetectorDraft>(key: K, value: DetectorDraft[K]) => setDraft((current) => ({ ...current, [key]: value }));

  const submit = () => {
    setError(null);
    if (draft.name.trim().length < 2) return setError('Name: at least 2 characters');
    if (!draft.metricName.trim()) return setError('Metric name is required');
    const attrs = parseAttributeLines(draft.attributeFilters);
    if (attrs.error) return setError(`Attribute filters: ${attrs.error}`);
    for (const [key, [min, max, label]] of Object.entries(BOUNDS)) {
      const value = Number(draft[key as keyof typeof BOUNDS]);
      if (!Number.isFinite(value) || value < min || value > max) return setError(`${label}: between ${min} and ${max}`);
    }
    const body: DetectorRequest = {
      name: draft.name.trim(),
      description: draft.description.trim() || null,
      metricName: draft.metricName.trim(),
      serviceId: draft.serviceId ? Number(draft.serviceId) : null,
      ciId: detector?.ciId ?? null,
      attributeFilters: attrs.filters,
      valueAggregation: draft.valueAggregation,
      algorithm: draft.algorithm,
      windowPoints: Number(draft.windowPoints),
      threshold: Number(draft.threshold),
      consecutivePoints: Number(draft.consecutivePoints),
      minSamples: Number(draft.minSamples),
      direction: draft.direction,
      seasonality: draft.seasonality,
      includeSimulated: draft.includeSimulated,
      raiseAlert: draft.raiseAlert,
      alertSeverity: draft.alertSeverity,
      createIncident: draft.raiseAlert && draft.createIncident,
      enabled: draft.enabled,
    };
    save.mutate(
      { id: detector?.id, body },
      {
        onSuccess: (saved) => {
          toast.success(detector ? `Saved ${saved.name}` : `Created ${saved.name}`);
          onClose();
        },
        onError: (err) => {
          const apiError = toApiError(err);
          setError(apiError.fieldErrors?.length ? apiError.fieldErrors.map((field) => `${field.field}: ${field.message}`).join('; ') : apiError.message);
        },
      },
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={detector ? `Edit ${detector.name}` : 'New anomaly detector'}
      eyebrow="ANOMALY DETECTOR"
      description="Statistical model per series of minute values: EWMA or median/MAD baseline, optional hour-of-week seasonality; an anomaly is a run of consecutive points beyond the threshold."
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={save.isPending}>
            {save.isPending ? 'Saving…' : detector ? 'Save detector' : 'Create detector'}
          </button>
        </>
      }
    >
      <div className="grid gap-2 sm:grid-cols-2">
        <Field id="det-name" label="Name">
          <input id="det-name" className="control" value={draft.name} maxLength={120} onChange={(event) => set('name', event.target.value)} />
        </Field>
        <Field id="det-metric" label="Metric name">
          <input id="det-metric" className="control" list="det-metric-options" value={draft.metricName} maxLength={200} onChange={(event) => set('metricName', event.target.value)} />
          <datalist id="det-metric-options">
            {(metrics.data ?? []).map((entry) => (
              <option key={entry.name} value={entry.name} />
            ))}
          </datalist>
        </Field>
        <Field id="det-service" label="Service (optional)">
          <select id="det-service" className="control" value={draft.serviceId} onChange={(event) => set('serviceId', event.target.value)}>
            <option value="">Any service</option>
            {(services.data ?? []).map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </select>
        </Field>
        <Field id="det-agg" label="Value aggregation">
          <select id="det-agg" className="control" value={draft.valueAggregation} onChange={(event) => set('valueAggregation', event.target.value as MetricAggregation)}>
            {(['avg', 'max', 'min', 'sum', 'p95'] as MetricAggregation[]).map((value) => (
              <option key={value}>{value}</option>
            ))}
          </select>
        </Field>
        <Field id="det-algorithm" label="Algorithm">
          <select id="det-algorithm" className="control" value={draft.algorithm} onChange={(event) => set('algorithm', event.target.value as DetectorAlgorithm)}>
            <option value="EWMA">EWMA mean / variance</option>
            <option value="MEDIAN_MAD">Median / MAD</option>
          </select>
        </Field>
        <Field id="det-direction" label="Direction">
          <select id="det-direction" className="control" value={draft.direction} onChange={(event) => set('direction', event.target.value as DetectorDirection)}>
            <option value="UP">Up (z ≥ threshold)</option>
            <option value="DOWN">Down (z ≤ −threshold)</option>
            <option value="BOTH">Both (|z| ≥ threshold)</option>
          </select>
        </Field>
        {(Object.keys(BOUNDS) as (keyof typeof BOUNDS)[]).map((key) => (
          <Field key={key} id={`det-${key}`} label={BOUNDS[key][2]} hint={`${BOUNDS[key][0]}–${formatNumber(BOUNDS[key][1])}`}>
            <input id={`det-${key}`} type="number" step={key === 'threshold' ? 0.1 : 1} min={BOUNDS[key][0]} max={BOUNDS[key][1]} className="control" value={draft[key]} onChange={(event) => set(key, event.target.value)} />
          </Field>
        ))}
        <Field id="det-seasonality" label="Seasonality">
          <select id="det-seasonality" className="control" value={draft.seasonality} onChange={(event) => set('seasonality', event.target.value as DetectorSeasonality)}>
            <option value="NONE">None</option>
            <option value="HOUR_OF_WEEK">Hour of week</option>
            <option value="AUTO">Auto (when ≥ 14 days of history)</option>
          </select>
        </Field>
        <Field id="det-severity" label="Alert severity">
          <select id="det-severity" className="control" value={draft.alertSeverity} onChange={(event) => set('alertSeverity', event.target.value as AlertSeverity)} disabled={!draft.raiseAlert}>
            {ALERT_SEVERITIES.map((value) => (
              <option key={value} value={value}>
                {titleCase(value)}
              </option>
            ))}
          </select>
        </Field>
        <div className="sm:col-span-2">
          <Field id="det-attrs" label="Attribute filters (one key=value per line, optional)">
            <textarea id="det-attrs" className="control" rows={2} value={draft.attributeFilters} onChange={(event) => set('attributeFilters', event.target.value)} />
          </Field>
        </div>
        <div className="sm:col-span-2">
          <Field id="det-description" label="Description (optional)">
            <textarea id="det-description" className="control" rows={2} maxLength={1000} value={draft.description} onChange={(event) => set('description', event.target.value)} />
          </Field>
        </div>
        <div className="grid gap-1 text-[11px] sm:col-span-2 sm:grid-cols-2">
          <label className="inline-flex items-center gap-2">
            <input type="checkbox" checked={draft.enabled} onChange={(event) => set('enabled', event.target.checked)} /> Enabled (runs every minute)
          </label>
          <label className="inline-flex items-center gap-2">
            <input type="checkbox" checked={draft.includeSimulated} onChange={(event) => set('includeSimulated', event.target.checked)} /> Include simulated series
          </label>
          <label className="inline-flex items-center gap-2">
            <input type="checkbox" checked={draft.raiseAlert} onChange={(event) => set('raiseAlert', event.target.checked)} /> Raise an OBSERVABILITY alert for new anomalies
          </label>
          <label className="inline-flex items-center gap-2">
            <input type="checkbox" checked={draft.createIncident} disabled={!draft.raiseAlert} onChange={(event) => set('createIncident', event.target.checked)} /> Convert the alert to an incident
          </label>
        </div>
      </div>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ detectors panel

function DetectorsPanel({ canConfigure }: { canConfigure: boolean }) {
  const detectors = useDetectors();
  const run = useRunDetector();
  const remove = useDeleteDetector();
  const toast = useToast();
  const [editing, setEditing] = useState<DetectorDto | null | 'new'>(null);
  const [evaluating, setEvaluating] = useState<DetectorDto | null>(null);
  const [deleting, setDeleting] = useState<DetectorDto | null>(null);

  const runNow = (detector: DetectorDto) =>
    run.mutate(detector.id, {
      onSuccess: (result) => {
        const text =
          result.status === 'OK'
            ? `${detector.name}: ${formatNumber(result.seriesEvaluated)} series, ${formatNumber(result.anomaliesOpened)} opened, ${formatNumber(result.anomaliesResolved)} resolved, ${formatNumber(result.alertsRaised)} alerts`
            : `${detector.name}: ${titleCase(result.status)}${result.message ? ` — ${result.message}` : ''}`;
        if (result.status === 'OK') toast.success(text);
        else toast.warning(text);
      },
      onError: (error) => toast.error(error),
    });

  return (
    <Panel
      title="Detectors"
      subtitle="Anomaly detectors over chosen metric series, with their measured precision and recall"
      actions={
        canConfigure ? (
          <button type="button" className="gold-btn compact" onClick={() => setEditing('new')}>
            <Plus size={13} aria-hidden="true" /> New detector
          </button>
        ) : undefined
      }
    >
      {detectors.isLoading && <LoadingState rows={3} />}
      {detectors.isError && <ErrorState error={detectors.error} onRetry={() => void detectors.refetch()} />}
      {detectors.data && detectors.data.length === 0 && <EmptyState title="No detectors yet" description={canConfigure ? 'Create a detector for a metric series to start detecting anomalies.' : 'Detectors are created by telemetry administrators.'} />}
      {detectors.data && detectors.data.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Anomaly detectors">
          {detectors.data.map((detector) => (
            <li key={detector.id} className="list-row flex-wrap !items-start sm:flex-nowrap">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  <span className="break-words">{detector.name}</span>
                  <ToneBadge value={detector.enabled ? 'enabled' : 'disabled'} label={detector.enabled ? 'Enabled' : 'Disabled'} tone={detector.enabled ? 'success' : 'muted'} />
                  {detector.openAnomalies > 0 && <ToneBadge value="open" label={`${formatNumber(detector.openAnomalies)} open`} tone="critical" />}
                  <DemoBadge demo={detector.demo} />
                </b>
                <small className="block break-all">
                  {detector.metricName} ({detector.valueAggregation}){detector.serviceName ? ` · ${detector.serviceName}` : ''}
                  {detector.ciName ? ` · ${detector.ciName}` : ''}
                  {attributesText(detector.attributeFilters) ? ` · ${attributesText(detector.attributeFilters)}` : ''}
                </small>
                <small className="block text-muted">
                  {detector.algorithm === 'EWMA' ? 'EWMA' : 'Median/MAD'} · window {formatNumber(detector.windowPoints)} · |z| ≥ {detector.threshold} for {detector.consecutivePoints} · min {formatNumber(detector.minSamples)} samples · {titleCase(detector.direction)}
                  {detector.seasonality !== 'NONE' ? ` · ${titleCase(detector.seasonality)}` : ''}
                  {detector.raiseAlert ? ` · alerts ${titleCase(detector.alertSeverity ?? 'MAJOR')}${detector.createIncident ? ' + incident' : ''}` : ' · no alerts'}
                  {detector.includeSimulated ? ' · includes simulated series' : ''}
                </small>
                <small className={`block ${detector.lastEvaluation?.status === 'INSUFFICIENT_DATA' ? 'warning-text' : ''}`}>
                  {evaluationSummary(detector.lastEvaluation)}
                  {detector.lastEvaluatedAt ? ` · evaluated ${formatRelative(detector.lastEvaluatedAt)}` : ''}
                  {detector.lastRunAt ? ` · last run ${formatRelative(detector.lastRunAt)}` : ''}
                </small>
              </div>
              {canConfigure && (
                <span className="flex shrink-0 flex-wrap gap-1">
                  <button type="button" className="subtle-btn compact" onClick={() => runNow(detector)} disabled={run.isPending} aria-label={`Run ${detector.name} now`}>
                    <Play size={11} aria-hidden="true" /> Run
                  </button>
                  <button type="button" className="subtle-btn compact" onClick={() => setEvaluating(detector)} aria-label={`Evaluate ${detector.name}`}>
                    Evaluate
                  </button>
                  <button type="button" className="icon-btn sm" onClick={() => setEditing(detector)} aria-label={`Edit ${detector.name}`}>
                    <Pencil size={12} />
                  </button>
                  <button type="button" className="icon-btn sm" onClick={() => setDeleting(detector)} aria-label={`Delete ${detector.name}`}>
                    <Trash2 size={12} />
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {editing && <DetectorEditor detector={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      {evaluating && <EvaluateModal detector={evaluating} onClose={() => setEvaluating(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title={`Delete ${deleting?.name ?? 'detector'}?`}
        message="The detector stops running. Anomalies it already recorded stay in the history."
        confirmLabel="Delete detector"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              toast.success(`Deleted ${deleting.name}`);
              setDeleting(null);
            },
          })
        }
      />
    </Panel>
  );
}

// ------------------------------------------------------------------ tab

/** Anomalies: detected anomalies (status, detector, service, window) and the detectors that produce them. */
export function AnomaliesTab({ includeSimulated, canConfigure }: { includeSimulated: boolean; canConfigure: boolean }) {
  const [status, setStatus] = useState('OPEN');
  const [detectorId, setDetectorId] = useState('');
  const [range, setRange] = useState<RangeValue>('24h');
  const [page, setPage] = useState(0);
  const window = useTimeWindow(range);
  const detectors = useDetectors();
  const anomalies = useAnomalies({ status: status || undefined, detectorId: detectorId ? Number(detectorId) : undefined, from: window.from, to: window.to, includeSimulated, page, size: 25 });
  const rows = anomalies.data?.content ?? [];
  return (
    <div className="grid gap-[14px]">
      <Panel title="Anomalies" subtitle="Runs of points beyond the detector's expected range">
        <div className="toolbar">
          <div className="grid w-full grid-cols-2 gap-2 sm:flex sm:w-auto sm:flex-wrap sm:items-center">
            <label className="sr-only" htmlFor="obs-anomaly-status">
              Status
            </label>
            <select id="obs-anomaly-status" className="control sm w-full sm:w-auto" value={status} onChange={(event) => { setPage(0); setStatus(event.target.value); }}>
              <option value="OPEN">Open</option>
              <option value="RESOLVED">Resolved</option>
              <option value="">Every status</option>
            </select>
            <label className="sr-only" htmlFor="obs-anomaly-detector">
              Detector
            </label>
            <select id="obs-anomaly-detector" className="control sm w-full sm:w-auto" value={detectorId} onChange={(event) => { setPage(0); setDetectorId(event.target.value); }}>
              <option value="">All detectors</option>
              {(detectors.data ?? []).map((detector) => (
                <option key={detector.id} value={detector.id}>
                  {detector.name}
                </option>
              ))}
            </select>
            <RangeSelect id="obs-anomaly-range" value={range} onChange={(value) => { setPage(0); setRange(value); }} onRefresh={window.refresh} />
          </div>
        </div>
        {anomalies.isLoading && <LoadingState rows={5} />}
        {anomalies.isError && <ErrorState error={anomalies.error} onRetry={() => void anomalies.refetch()} />}
        {anomalies.data && rows.length === 0 && <EmptyState icon={<Radar size={20} />} title="No anomalies in this window" />}
        {rows.length > 0 && anomalies.data && (
          <>
            <div className="table-scroll">
              <table className="data-table">
                <caption className="sr-only">Anomalies</caption>
                <thead>
                  <tr>
                    <th scope="col">Metric</th>
                    <th scope="col">Service / CI</th>
                    <th scope="col">Observed</th>
                    <th scope="col">Expected range</th>
                    <th scope="col">|z|</th>
                    <th scope="col">Status</th>
                    <th scope="col">Started</th>
                    <th scope="col">Alert</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((anomaly) => (
                    <tr key={anomaly.id}>
                      <td>
                        <span className="font-semibold">{anomaly.metricName}</span> <SimulatedBadge simulated={anomaly.simulated} />
                        <small className="block text-muted">{anomaly.detectorName ?? `Detector #${anomaly.detectorId}`}</small>
                      </td>
                      <td>{[anomaly.serviceName, anomaly.ciName].filter(Boolean).join(' · ') || 'Unmapped'}</td>
                      <td>
                        {formatValue(anomaly.observedValue)} {anomaly.direction === 'UP' ? '↑' : '↓'}
                      </td>
                      <td>
                        {formatValue(anomaly.expectedLower)} – {formatValue(anomaly.expectedUpper)}
                      </td>
                      <td>{anomaly.peakScore.toFixed(1)}</td>
                      <td>
                        <ToneBadge value={anomaly.status} label={titleCase(anomaly.status)} tone={anomaly.status === 'OPEN' ? 'critical' : 'success'} />
                      </td>
                      <td title={formatDateTime(anomaly.startedAt)}>{formatRelative(anomaly.startedAt)}</td>
                      <td>
                        {anomaly.alertId ? (
                          <Link to={`/alerts/${anomaly.alertId}`} className="id-cell">
                            {anomaly.alertNumber}
                          </Link>
                        ) : anomaly.correlationGroupId ? (
                          <Link to={`/observability?tab=correlation&group=${anomaly.correlationGroupId}`} className="link-btn">
                            Group #{anomaly.correlationGroupId}
                          </Link>
                        ) : (
                          '—'
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={anomalies.data.page} totalPages={anomalies.data.totalPages} totalElements={anomalies.data.totalElements} size={anomalies.data.size} onPageChange={setPage} label="anomalies" />
          </>
        )}
      </Panel>
      <DetectorsPanel canConfigure={canConfigure} />
    </div>
  );
}
