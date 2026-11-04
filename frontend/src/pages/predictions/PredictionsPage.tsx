import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Activity,
  AlertTriangle,
  BrainCircuit,
  CheckCircle2,
  Gauge,
  HelpCircle,
  Play,
  RefreshCw,
  ShieldAlert,
  TrendingUp,
  XCircle,
} from 'lucide-react';
import {
  useActivateModel,
  useCapacityForecast,
  useEvaluateModel,
  useIncidentPredictions,
  useModelPerformance,
  usePredictionJobs,
  usePredictionMeta,
  usePredictionModels,
  usePredictionRecords,
  useReconcileOutcomes,
  useRetireModel,
  useServiceHealthForecast,
  useTrainModel,
} from '../../api/predictions';
import { useIncidents } from '../../api/incidents';
import { useServices } from '../../api/services';
import { SimulatedBadge } from '../../components/Badge';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { IncludeSimulatedToggle } from '../../components/sim/SimulationParts';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { errorMessage } from '../../lib/api';
import { PREDICTION_ADMINS, type Tone } from '../../lib/constants';
import { formatDateTime, formatNumber, formatPercent, formatRelative, titleCase } from '../../lib/format';
import type {
  CalibrationBin,
  ClassificationPredictionDto,
  PredictionModelDto,
  PredictionModelStatus,
  PredictionModelType,
} from '../../lib/r4Types';

export type PredTab = 'models' | 'incidents' | 'service-health' | 'capacity' | 'records';
const TABS: PredTab[] = ['models', 'incidents', 'service-health', 'capacity', 'records'];
const TAB_LABELS: Record<PredTab, string> = {
  models: 'Models & Training',
  incidents: 'Incident Risk (SLA & Recurrence)',
  'service-health': 'Service Health Forecast',
  capacity: 'Capacity Risk Forecast',
  records: 'Prediction Ledger & Jobs',
};

const MODEL_TYPES: PredictionModelType[] = ['SLA_BREACH', 'INCIDENT_RECURRENCE', 'CAPACITY_RISK', 'SERVICE_HEALTH', 'ANOMALY'];
const MODEL_TYPE_LABELS: Record<PredictionModelType, string> = {
  SLA_BREACH: 'SLA Breach Probability',
  INCIDENT_RECURRENCE: 'Incident Recurrence (14 d)',
  CAPACITY_RISK: 'Capacity Saturation Forecast',
  SERVICE_HEALTH: 'Service Health Forecast',
  ANOMALY: 'Telemetry Anomaly Detector',
};

const STATUS_TONES: Record<PredictionModelStatus, Tone> = {
  ACTIVE: 'success',
  TRAINED: 'gold',
  RETIRED: 'muted',
  INSUFFICIENT_DATA: 'warning',
  FAILED: 'critical',
};

const RISK_TONES: Record<string, Tone> = {
  CRITICAL: 'critical',
  HIGH: 'warning',
  MEDIUM: 'champagne',
  LOW: 'success',
};

function numMetric(metrics: Record<string, unknown> | null | undefined, path: string): number | null {
  if (!metrics) return null;
  const parts = path.split('.');
  let cur: unknown = metrics;
  for (const part of parts) {
    if (!cur || typeof cur !== 'object') return null;
    cur = (cur as Record<string, unknown>)[part];
  }
  return typeof cur === 'number' && Number.isFinite(cur) ? cur : null;
}

function calibrationBins(metrics: Record<string, unknown> | null | undefined): CalibrationBin[] {
  if (!metrics) return [];
  const test = (metrics.test as Record<string, unknown> | undefined) ?? metrics;
  const cal = test?.calibration;
  return Array.isArray(cal) ? (cal as CalibrationBin[]) : [];
}

function ModelDetailPanel({
  model,
  canAdmin,
  onClose,
}: {
  model: PredictionModelDto;
  canAdmin: boolean;
  onClose: () => void;
}) {
  const perf = useModelPerformance(model.id);
  const activate = useActivateModel();
  const retire = useRetireModel();
  const evaluate = useEvaluateModel();
  const toast = useToast();

  const auc = numMetric(model.metrics, 'test.auc') ?? numMetric(model.metrics, 'auc');
  const brier = numMetric(model.metrics, 'test.brier') ?? numMetric(model.metrics, 'brier');
  const precision = numMetric(model.metrics, 'test.precision') ?? numMetric(model.metrics, 'precision');
  const recall = numMetric(model.metrics, 'test.recall') ?? numMetric(model.metrics, 'recall');
  const f1 = numMetric(model.metrics, 'test.f1') ?? numMetric(model.metrics, 'f1');
  const mae = numMetric(model.metrics, 'test.mae') ?? numMetric(model.metrics, 'mae');
  const rmse = numMetric(model.metrics, 'test.rmse') ?? numMetric(model.metrics, 'rmse');
  const mape = numMetric(model.metrics, 'test.mape') ?? numMetric(model.metrics, 'mape');
  const coverage = numMetric(model.metrics, 'test.intervalCoverage') ?? numMetric(model.metrics, 'intervalCoverage');
  const bins = calibrationBins(model.metrics);

  return (
    <Panel
      title={`${MODEL_TYPE_LABELS[model.type]} · v${model.version}`}
      subtitle={`Scope ${model.scopeKey} · ${model.algorithm}`}
      actions={
        <div className="flex flex-wrap items-center gap-2">
          {canAdmin && model.status === 'TRAINED' && (
            <button
              type="button"
              className="gold-btn compact"
              disabled={activate.isPending}
              onClick={() =>
                activate.mutate(model.id, {
                  onSuccess: () => toast.success(`Activated ${model.type} v${model.version}`),
                  onError: (err) => toast.error(err),
                })
              }
            >
              <CheckCircle2 size={13} /> Activate model
            </button>
          )}
          {canAdmin && (model.status === 'ACTIVE' || model.status === 'TRAINED') && (
            <button
              type="button"
              className="subtle-btn compact"
              disabled={retire.isPending}
              onClick={() =>
                retire.mutate(model.id, {
                  onSuccess: () => toast.success(`Retired ${model.type} v${model.version}`),
                  onError: (err) => toast.error(err),
                })
              }
            >
              <XCircle size={13} /> Retire
            </button>
          )}
          {canAdmin && (model.status === 'ACTIVE' || model.status === 'TRAINED') && (
            <button
              type="button"
              className="subtle-btn compact"
              disabled={evaluate.isPending}
              onClick={() =>
                evaluate.mutate(
                  { id: model.id },
                  {
                    onSuccess: () => toast.success(`Evaluated ${model.type} v${model.version}`),
                    onError: (err) => toast.error(err),
                  },
                )
              }
            >
              <RefreshCw size={13} /> Out-of-time evaluate
            </button>
          )}
          <button type="button" className="subtle-btn compact" onClick={onClose}>
            Close
          </button>
        </div>
      }
    >
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <ToneBadge value={model.status} label={titleCase(model.status)} tone={STATUS_TONES[model.status] ?? 'neutral'} />
        <SimulatedBadge simulated={model.simulated} />
        {model.driftStatus === 'DRIFT' && <ToneBadge value="DRIFT" label="Drift warning" tone="critical" />}
        {model.driftStatus === 'OK' && <ToneBadge value="OK" label="No drift" tone="success" />}
      </div>

      {model.status === 'INSUFFICIENT_DATA' && (
        <div className="callout mb-3" role="status">
          <AlertTriangle size={16} />
          <div>
            <b>Insufficient data to fit a reliable model</b>
            <div className="text-[11px]">{model.statusMessage ?? `Requires at least ${model.minSamples} samples.`}</div>
          </div>
        </div>
      )}

      {model.method && (
        <div className="callout mb-3">
          <HelpCircle size={15} />
          <div>
            <b>How this model works</b>
            <div className="text-[11px] text-muted">{model.method}</div>
          </div>
        </div>
      )}

      <div className="detail-grid three tight mb-4">
        <div>
          <small>Samples (train / test)</small>
          <b>
            {formatNumber(model.sampleCount)} ({formatNumber(model.trainCount)} / {formatNumber(model.testCount)})
          </b>
        </div>
        <div>
          <small>Positive outcomes</small>
          <b>{model.positiveCount !== null ? formatNumber(model.positiveCount) : '—'}</b>
        </div>
        <div>
          <small>Minimum required</small>
          <b>{formatNumber(model.minSamples)}</b>
        </div>
        <div>
          <small>Training window</small>
          <b>
            {model.trainingFrom ? formatDateTime(model.trainingFrom) : '—'} → {model.trainingTo ? formatDateTime(model.trainingTo) : '—'}
          </b>
        </div>
        <div>
          <small>Trained by</small>
          <b>{model.trainedByName ? `${model.trainedByName} · ${formatRelative(model.trainedAt)}` : '—'}</b>
        </div>
        <div>
          <small>Activated by</small>
          <b>{model.activatedByName ? `${model.activatedByName} · ${formatRelative(model.activatedAt)}` : 'Not active'}</b>
        </div>
      </div>

      <h4 className="mb-2 text-[12px] font-semibold">Offline hold-out evaluation metrics (time-based split)</h4>
      <div className="detail-grid three tight mb-4">
        {auc !== null && (
          <div>
            <small>ROC AUC</small>
            <b>{auc.toFixed(3)}</b>
          </div>
        )}
        {brier !== null && (
          <div>
            <small>Brier score</small>
            <b>{brier.toFixed(3)}</b>
          </div>
        )}
        {precision !== null && (
          <div>
            <small>Precision</small>
            <b>{formatPercent(precision * 100, 1)}</b>
          </div>
        )}
        {recall !== null && (
          <div>
            <small>Recall</small>
            <b>{formatPercent(recall * 100, 1)}</b>
          </div>
        )}
        {f1 !== null && (
          <div>
            <small>F1 score</small>
            <b>{f1.toFixed(3)}</b>
          </div>
        )}
        {mae !== null && (
          <div>
            <small>MAE</small>
            <b>{mae.toFixed(2)}</b>
          </div>
        )}
        {rmse !== null && (
          <div>
            <small>RMSE</small>
            <b>{rmse.toFixed(2)}</b>
          </div>
        )}
        {mape !== null && (
          <div>
            <small>MAPE</small>
            <b>{formatPercent(mape, 1)}</b>
          </div>
        )}
        {coverage !== null && (
          <div>
            <small>95% interval coverage</small>
            <b>{formatPercent(coverage * 100, 1)}</b>
          </div>
        )}
      </div>

      {bins.length > 0 && (
        <div className="mb-4">
          <h4 className="mb-2 text-[12px] font-semibold">10-bin reliability calibration (hold-out test split)</h4>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Bin</th>
                  <th>Predicted range</th>
                  <th>Samples</th>
                  <th>Mean predicted</th>
                  <th>Observed positive rate</th>
                </tr>
              </thead>
              <tbody>
                {bins.map((b) => (
                  <tr key={b.bin}>
                    <td>#{b.bin}</td>
                    <td>
                      {(b.lower * 100).toFixed(0)}% – {(b.upper * 100).toFixed(0)}%
                    </td>
                    <td>{formatNumber(b.count)}</td>
                    <td>{b.meanPredicted !== null && b.meanPredicted !== undefined ? formatPercent(b.meanPredicted * 100, 1) : '—'}</td>
                    <td>{b.fractionPositive !== null && b.fractionPositive !== undefined ? formatPercent(b.fractionPositive * 100, 1) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="mt-3">
        <h4 className="mb-2 text-[12px] font-semibold">Live performance & drift monitoring</h4>
        {perf.isLoading && <LoadingState label="Loading live performance…" />}
        {perf.data && (
          <>
            {perf.data.driftStatus === 'DRIFT' && (
              <div className="callout mb-3" role="alert">
                <ShieldAlert size={16} />
                <div>
                  <b>Model drift detected on live outcomes</b>
                  <ul className="m-0 mt-1 list-disc pl-4 text-[11px]">
                    {perf.data.driftReasons.map((reason, idx) => (
                      <li key={idx}>{reason}</li>
                    ))}
                  </ul>
                </div>
              </div>
            )}
            <div className="detail-grid three tight">
              <div>
                <small>Recorded outcomes</small>
                <b>
                  {formatNumber(perf.data.outcomes)} known · {formatNumber(perf.data.pending)} pending
                </b>
              </div>
              <div>
                <small>Drift status</small>
                <b>{perf.data.driftStatus}</b>
              </div>
              <div>
                <small>Min outcomes (metrics / drift)</small>
                <b>
                  {perf.data.minOutcomesForLiveMetrics} / {perf.data.minOutcomesForDrift}
                </b>
              </div>
            </div>
          </>
        )}
      </div>
    </Panel>
  );
}

function ClassificationCard({ title, pred }: { title: string; pred: ClassificationPredictionDto }) {
  return (
    <Panel title={title} subtitle={pred.method ?? undefined}>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <ToneBadge
          value={pred.status}
          label={titleCase(pred.status)}
          tone={pred.status === 'OK' ? 'success' : pred.status === 'NO_ACTIVE_MODEL' ? 'warning' : 'neutral'}
        />
        <ToneBadge
          value={pred.basis}
          label={pred.basis === 'MODEL' ? `Active model v${pred.modelVersion ?? 1}` : pred.basis === 'STATISTICAL_FALLBACK' ? 'Release 3 statistical fallback' : 'No basis'}
          tone={pred.basis === 'MODEL' ? 'gold' : 'champagne'}
        />
        {pred.riskLevel && <ToneBadge value={pred.riskLevel} label={`Risk: ${pred.riskLevel}`} tone={RISK_TONES[pred.riskLevel] ?? 'neutral'} />}
        <SimulatedBadge simulated={pred.simulated} />
      </div>

      {pred.message && (
        <div className="callout mb-3">
          <div>{pred.message}</div>
        </div>
      )}

      {pred.probability !== null && pred.probability !== undefined && (
        <div className="detail-grid three tight mb-3">
          <div>
            <small>Predicted probability</small>
            <b className="text-[16px]">{formatPercent(pred.probability * 100, 1)}</b>
          </div>
          <div>
            <small>Decision threshold</small>
            <b>{pred.threshold !== null ? formatPercent(pred.threshold * 100, 1) : '—'}</b>
          </div>
          <div>
            <small>Classification</small>
            <b>{pred.predictedPositive ? 'Positive (High Risk)' : 'Negative (Normal)'}</b>
          </div>
        </div>
      )}

      {pred.howComputed && (
        <div className="callout mb-3">
          <HelpCircle size={15} />
          <div>
            <b>How this was computed</b>
            <div className="text-[11px] text-muted">{pred.howComputed}</div>
          </div>
        </div>
      )}

      {pred.contributions?.length > 0 && (
        <div>
          <h4 className="mb-1.5 text-[12px] font-semibold">Feature contributions (weight × standardised feature)</h4>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Feature</th>
                  <th>Raw value</th>
                  <th>Standardised (z)</th>
                  <th>Weight</th>
                  <th>Log-odds contribution</th>
                </tr>
              </thead>
              <tbody>
                {pred.contributions.slice(0, 10).map((c) => (
                  <tr key={c.feature}>
                    <td>
                      <code>{c.feature}</code>
                    </td>
                    <td>{c.value.toFixed(2)}</td>
                    <td>{c.standardized.toFixed(2)}</td>
                    <td>{c.weight.toFixed(3)}</td>
                    <td>{c.contribution >= 0 ? `+${c.contribution.toFixed(3)}` : c.contribution.toFixed(3)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {pred.fallback && (
        <div className="mt-3">
          <h4 className="mb-1 text-[12px] font-semibold">Statistical fallback breakdown (Release 3 cohort statistic)</h4>
          <pre className="m-0 overflow-x-auto rounded p-2 text-[11px]">{JSON.stringify(pred.fallback, null, 2)}</pre>
        </div>
      )}
    </Panel>
  );
}

export default function PredictionsPage() {
  const { hasRole } = useAuth();
  const canAdmin = hasRole(...PREDICTION_ADMINS);
  const [tab, setTab] = useTabParam<PredTab>(TABS, 'models');
  const [params, setParams] = useSearchParams();
  const includeSimulated = params.get('sim') === '1';
  const toast = useToast();

  const setIncludeSimulated = (next: boolean) =>
    setParams(
      (current) => {
        const updated = new URLSearchParams(current);
        if (next) updated.set('sim', '1');
        else updated.delete('sim');
        return updated;
      },
      { replace: true },
    );

  // Filters & local state
  const [typeFilter, setTypeFilter] = useState<PredictionModelType | ''>('');
  const [statusFilter, setStatusFilter] = useState<PredictionModelStatus | ''>('');
  const [selectedModelId, setSelectedModelId] = useState<number | null>(null);
  const [trainOpen, setTrainOpen] = useState(false);
  const [trainType, setTrainType] = useState<PredictionModelType>('SLA_BREACH');
  const [trainDays, setTrainDays] = useState(180);
  const [trainTestFraction, setTrainTestFraction] = useState(0.25);
  const [trainSim, setTrainSim] = useState(false);

  // Queries
  const meta = usePredictionMeta();
  const models = usePredictionModels({ type: typeFilter, status: statusFilter, includeSimulated });
  const train = useTrainModel();
  const activate = useActivateModel();
  const retire = useRetireModel();
  const reconcile = useReconcileOutcomes();

  // Incident predictions tab
  const incidentsList = useIncidents({ page: 0, size: 25 });
  const [incidentIdInput, setIncidentIdInput] = useState<string>('');
  const activeIncidentId = Number(incidentIdInput) || incidentsList.data?.content?.[0]?.id || null;
  const incidentPreds = useIncidentPredictions(tab === 'incidents' ? activeIncidentId : null, includeSimulated);

  // Service health tab
  const servicesList = useServices();
  const [selectedServiceId, setSelectedServiceId] = useState<number | null>(null);
  const [horizonDays, setHorizonDays] = useState(7);
  const effectiveServiceId = selectedServiceId ?? servicesList.data?.[0]?.id ?? null;
  const serviceHealth = useServiceHealthForecast(tab === 'service-health' ? effectiveServiceId : null, horizonDays, includeSimulated);

  // Capacity forecast tab
  const [capServiceId, setCapServiceId] = useState<number | ''>('');
  const [capThreshold, setCapThreshold] = useState(85);
  const [capHorizonHours, setCapHorizonHours] = useState(48);
  const capacity = useCapacityForecast(
    {
      serviceId: capServiceId === '' ? undefined : capServiceId,
      threshold: capThreshold,
      horizonHours: capHorizonHours,
      includeSimulated,
    },
    tab === 'capacity',
  );

  // Ledger & jobs tab
  const [recordPage, setRecordPage] = useState(0);
  const records = usePredictionRecords({ includeSimulated, page: recordPage, size: 15 });
  const jobs = usePredictionJobs({ limit: 25 });

  const selectedModel = (models.data ?? []).find((m) => m.id === selectedModelId) ?? null;

  const submitTrain = () => {
    const now = new Date();
    const from = new Date(now.getTime() - trainDays * 86_400_000).toISOString();
    train.mutate(
      {
        type: trainType,
        from,
        to: now.toISOString(),
        testFraction: trainTestFraction,
        includeSimulated: trainSim,
      },
      {
        onSuccess: (created) => {
          toast.success(`Training finished: ${created.type} v${created.version} (${created.status})`);
          setTrainOpen(false);
          setSelectedModelId(created.id);
        },
        onError: (err) => toast.error(err),
      },
    );
  };

  return (
    <>
      <PageHeader
        title="Predictions"
        subtitle="Calibrated statistical & ML models for SLA breach risk, incident recurrence, service health and capacity saturation"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <IncludeSimulatedToggle value={includeSimulated} onChange={setIncludeSimulated} />
            {canAdmin && (
              <>
                <button
                  type="button"
                  className="subtle-btn"
                  disabled={reconcile.isPending}
                  onClick={() =>
                    reconcile.mutate(undefined, {
                      onSuccess: (res) =>
                        toast.success(`Reconciled outcomes: ${res.known} known, ${res.expired} expired, ${res.driftWarnings} drift warnings`),
                      onError: (err) => toast.error(err),
                    })
                  }
                >
                  <RefreshCw size={14} /> Reconcile outcomes
                </button>
                <button type="button" className="gold-btn" onClick={() => setTrainOpen(true)}>
                  <Play size={14} /> Train model
                </button>
              </>
            )}
          </div>
        }
      />

      <div className="page-stack">
        <Tabs<PredTab>
          label="Predictive intelligence views"
          value={tab}
          onChange={setTab}
          tabs={TABS.map((id) => ({ id, label: TAB_LABELS[id] }))}
        />

        {tab === 'models' && (
          <>
            {meta.data && (
              <div className="callout">
                <BrainCircuit size={16} />
                <div>
                  <b>Model registry & governance</b> — Minimum training sample gate: <b>{meta.data.minSamples}</b> rows (classification) /{' '}
                  <b>100</b> points (time-series). At most one model per `(type, scopeKey)` may be <b>ACTIVE</b> at a time; activating a new version
                  retires the previous active version.
                </div>
              </div>
            )}

            <Panel
              title="Registered prediction models"
              subtitle="Trained, active, retired and insufficient-data model versions"
              actions={
                <div className="flex flex-wrap items-center gap-2">
                  <select
                    aria-label="Filter by model type"
                    className="input compact"
                    value={typeFilter}
                    onChange={(e) => setTypeFilter(e.target.value as PredictionModelType | '')}
                  >
                    <option value="">All model types</option>
                    {MODEL_TYPES.map((t) => (
                      <option key={t} value={t}>
                        {MODEL_TYPE_LABELS[t]}
                      </option>
                    ))}
                  </select>
                  <select
                    aria-label="Filter by status"
                    className="input compact"
                    value={statusFilter}
                    onChange={(e) => setStatusFilter(e.target.value as PredictionModelStatus | '')}
                  >
                    <option value="">All statuses</option>
                    <option value="ACTIVE">Active</option>
                    <option value="TRAINED">Trained</option>
                    <option value="INSUFFICIENT_DATA">Insufficient data</option>
                    <option value="RETIRED">Retired</option>
                  </select>
                </div>
              }
            >
              {models.isLoading && <LoadingState label="Loading models…" />}
              {models.isError && <ErrorState error={models.error} onRetry={() => void models.refetch()} />}
              {models.data && models.data.length === 0 && (
                <EmptyState
                  title="No prediction models registered yet"
                  description={
                    canAdmin
                      ? 'Train an SLA Breach, Incident Recurrence, Service Health or Capacity Risk model from historical data.'
                      : 'A prediction administrator has not trained or activated a model yet.'
                  }
                  icon={<BrainCircuit size={22} />}
                />
              )}
              {models.data && models.data.length > 0 && (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Model type</th>
                        <th>Version</th>
                        <th>Status</th>
                        <th>Samples (Train / Test)</th>
                        <th>Key metric</th>
                        <th>Drift</th>
                        <th>Trained</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {models.data.map((m) => {
                        const auc = numMetric(m.metrics, 'test.auc') ?? numMetric(m.metrics, 'auc');
                        const mae = numMetric(m.metrics, 'test.mae') ?? numMetric(m.metrics, 'mae');
                        return (
                          <tr key={m.id}>
                            <td>
                              <b>{MODEL_TYPE_LABELS[m.type] ?? m.type}</b>
                              <div className="text-[10px] text-muted">
                                {m.algorithm} · scope {m.scopeKey}
                              </div>
                            </td>
                            <td>
                              v{m.version} <SimulatedBadge simulated={m.simulated} />
                            </td>
                            <td>
                              <ToneBadge value={m.status} label={titleCase(m.status)} tone={STATUS_TONES[m.status] ?? 'neutral'} />
                              {m.status === 'INSUFFICIENT_DATA' && m.statusMessage && (
                                <div className="mt-0.5 text-[10px] text-muted">{m.statusMessage}</div>
                              )}
                            </td>
                            <td>
                              {formatNumber(m.sampleCount)} ({formatNumber(m.trainCount)} / {formatNumber(m.testCount)})
                            </td>
                            <td>
                              {auc !== null ? `AUC ${auc.toFixed(3)}` : mae !== null ? `MAE ${mae.toFixed(2)}` : '—'}
                            </td>
                            <td>
                              {m.driftStatus === 'DRIFT' ? (
                                <ToneBadge value="DRIFT" label="Drift warning" tone="critical" />
                              ) : m.driftStatus ? (
                                <span className="text-[11px] text-muted">{m.driftStatus}</span>
                              ) : (
                                '—'
                              )}
                            </td>
                            <td>{m.trainedAt ? formatRelative(m.trainedAt) : '—'}</td>
                            <td>
                              <div className="flex flex-wrap gap-1.5">
                                <button type="button" className="subtle-btn compact" onClick={() => setSelectedModelId(m.id)}>
                                  Inspect
                                </button>
                                {canAdmin && m.status === 'TRAINED' && (
                                  <button
                                    type="button"
                                    className="gold-btn compact"
                                    disabled={activate.isPending}
                                    onClick={() =>
                                      activate.mutate(m.id, {
                                        onSuccess: () => toast.success(`Activated ${m.type} v${m.version}`),
                                        onError: (err) => toast.error(err),
                                      })
                                    }
                                  >
                                    Activate
                                  </button>
                                )}
                                {canAdmin && m.status === 'ACTIVE' && (
                                  <button
                                    type="button"
                                    className="subtle-btn compact"
                                    disabled={retire.isPending}
                                    onClick={() =>
                                      retire.mutate(m.id, {
                                        onSuccess: () => toast.success(`Retired ${m.type} v${m.version}`),
                                        onError: (err) => toast.error(err),
                                      })
                                    }
                                  >
                                    Retire
                                  </button>
                                )}
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>

            {selectedModel && <ModelDetailPanel model={selectedModel} canAdmin={canAdmin} onClose={() => setSelectedModelId(null)} />}
          </>
        )}

        {tab === 'incidents' && (
          <>
            <Panel
              title="Incident SLA breach & 14-day recurrence predictions"
              subtitle="Select an incident to inspect its calibrated model prediction (or the labelled Release 3 statistical fallback when no ACTIVE model exists)"
              actions={
                <div className="flex flex-wrap items-center gap-2">
                  <select
                    aria-label="Select incident"
                    className="input compact"
                    value={activeIncidentId ?? ''}
                    onChange={(e) => setIncidentIdInput(e.target.value)}
                  >
                    {(incidentsList.data?.content ?? []).map((inc) => (
                      <option key={inc.id} value={inc.id}>
                        {inc.number} · {inc.title} ({inc.status})
                      </option>
                    ))}
                  </select>
                  {activeIncidentId && (
                    <Link to={`/incidents/${activeIncidentId}`} className="subtle-btn compact">
                      Open incident
                    </Link>
                  )}
                </div>
              }
            >
              {incidentPreds.isLoading && <LoadingState label="Computing incident predictions…" />}
              {incidentPreds.isError && <ErrorState error={incidentPreds.error} onRetry={() => void incidentPreds.refetch()} />}
              {incidentPreds.data && (
                <div className="grid gap-[14px] lg:grid-cols-2">
                  <ClassificationCard title={`SLA Breach Prediction (${incidentPreds.data.number})`} pred={incidentPreds.data.slaBreach} />
                  <ClassificationCard title={`14-Day Recurrence Prediction (${incidentPreds.data.number})`} pred={incidentPreds.data.recurrence} />
                </div>
              )}
            </Panel>
          </>
        )}

        {tab === 'service-health' && (
          <Panel
            title="Service health forecast"
            subtitle="Daily incident count, alert volume and server-span error rate forecast with 95% prediction intervals"
            actions={
              <div className="flex flex-wrap items-center gap-2">
                <select
                  aria-label="Select business service"
                  className="input compact"
                  value={effectiveServiceId ?? ''}
                  onChange={(e) => setSelectedServiceId(Number(e.target.value))}
                >
                  {(servicesList.data ?? []).map((svc) => (
                    <option key={svc.id} value={svc.id}>
                      {svc.code} · {svc.name} (Tier {svc.tier})
                    </option>
                  ))}
                </select>
                <select
                  aria-label="Forecast horizon days"
                  className="input compact"
                  value={horizonDays}
                  onChange={(e) => setHorizonDays(Number(e.target.value))}
                >
                  <option value={7}>7 days</option>
                  <option value={14}>14 days</option>
                  <option value={30}>30 days</option>
                </select>
              </div>
            }
          >
            {serviceHealth.isLoading && <LoadingState label="Forecasting service health…" />}
            {serviceHealth.isError && <ErrorState error={serviceHealth.error} onRetry={() => void serviceHealth.refetch()} />}
            {serviceHealth.data && (
              <>
                <div className="mb-3 flex flex-wrap items-center gap-2">
                  <ToneBadge
                    value={serviceHealth.data.status}
                    label={titleCase(serviceHealth.data.status)}
                    tone={serviceHealth.data.status === 'OK' ? 'success' : 'warning'}
                  />
                  {serviceHealth.data.modelVersion && (
                    <ToneBadge value="MODEL" label={`Model v${serviceHealth.data.modelVersion}`} tone="gold" />
                  )}
                </div>
                {serviceHealth.data.message && (
                  <div className="callout mb-3" role="status">
                    <Activity size={16} />
                    <div>{serviceHealth.data.message}</div>
                  </div>
                )}
                {serviceHealth.data.howComputed && (
                  <div className="callout mb-3">
                    <HelpCircle size={15} />
                    <div>
                      <b>How this was computed</b>
                      <div className="text-[11px] text-muted">{serviceHealth.data.howComputed}</div>
                    </div>
                  </div>
                )}
                <div className="grid gap-[14px] lg:grid-cols-2">
                  {serviceHealth.data.metrics.map((mf) => (
                    <Panel key={mf.metric} title={`Metric: ${mf.metric}`} subtitle={mf.method ?? `${mf.historyPoints} historical days`}>
                      {mf.message && <p className="mb-2 text-[11px] text-muted">{mf.message}</p>}
                      {mf.forecast.length > 0 ? (
                        <div className="table-wrap">
                          <table className="data-table">
                            <thead>
                              <tr>
                                <th>Date (UTC)</th>
                                <th>Point forecast</th>
                                <th>95% lower bound</th>
                                <th>95% upper bound</th>
                              </tr>
                            </thead>
                            <tbody>
                              {mf.forecast.map((pt) => (
                                <tr key={pt.at}>
                                  <td>{pt.at.slice(0, 10)}</td>
                                  <td>
                                    <b>{pt.predicted.toFixed(2)}</b>
                                  </td>
                                  <td>{pt.lower.toFixed(2)}</td>
                                  <td>{pt.upper.toFixed(2)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      ) : (
                        <p className="text-[11px] text-muted">No forecast points available.</p>
                      )}
                    </Panel>
                  ))}
                </div>
              </>
            )}
          </Panel>
        )}

        {tab === 'capacity' && (
          <Panel
            title="Capacity saturation risk forecast"
            subtitle="Holt linear trend on hourly telemetry utilisation series with time-to-threshold and 95% intervals"
            actions={
              <div className="flex flex-wrap items-center gap-2">
                <select
                  aria-label="Filter capacity by service"
                  className="input compact"
                  value={capServiceId}
                  onChange={(e) => setCapServiceId(e.target.value ? Number(e.target.value) : '')}
                >
                  <option value="">All services</option>
                  {(servicesList.data ?? []).map((svc) => (
                    <option key={svc.id} value={svc.id}>
                      {svc.code} · {svc.name}
                    </option>
                  ))}
                </select>
                <select
                  aria-label="Capacity horizon hours"
                  className="input compact"
                  value={capHorizonHours}
                  onChange={(e) => setCapHorizonHours(Number(e.target.value))}
                >
                  <option value={24}>24 hours</option>
                  <option value={48}>48 hours</option>
                  <option value={72}>72 hours</option>
                  <option value={168}>7 days (168 h)</option>
                </select>
                <input
                  type="number"
                  aria-label="Threshold"
                  className="input compact w-24"
                  min={1}
                  max={1000}
                  value={capThreshold}
                  onChange={(e) => setCapThreshold(Number(e.target.value) || 85)}
                />
              </div>
            }
          >
            {capacity.isLoading && <LoadingState label="Computing capacity forecast…" />}
            {capacity.isError && <ErrorState error={capacity.error} onRetry={() => void capacity.refetch()} />}
            {capacity.data && (
              <>
                <div className="mb-3 flex flex-wrap items-center gap-2">
                  <ToneBadge
                    value={capacity.data.status}
                    label={titleCase(capacity.data.status)}
                    tone={capacity.data.status === 'OK' ? 'success' : 'warning'}
                  />
                  {capacity.data.modelVersion && <ToneBadge value="MODEL" label={`Model v${capacity.data.modelVersion}`} tone="gold" />}
                </div>
                {capacity.data.message && (
                  <div className="callout mb-3" role="status">
                    <Gauge size={16} />
                    <div>{capacity.data.message}</div>
                  </div>
                )}
                {capacity.data.howComputed && (
                  <div className="callout mb-3">
                    <HelpCircle size={15} />
                    <div>
                      <b>How this was computed</b>
                      <div className="text-[11px] text-muted">{capacity.data.howComputed}</div>
                    </div>
                  </div>
                )}
                {capacity.data.series.length === 0 ? (
                  <EmptyState
                    title="No capacity series to forecast"
                    description="Activate a CAPACITY_RISK model and ingest utilisation metrics to forecast saturation."
                    icon={<TrendingUp size={20} />}
                  />
                ) : (
                  <div className="table-wrap">
                    <table className="data-table">
                      <thead>
                        <tr>
                          <th>Metric / Resource</th>
                          <th>Current</th>
                          <th>Threshold</th>
                          <th>Risk</th>
                          <th>Hours to threshold (point / 95% band)</th>
                          <th>History points</th>
                        </tr>
                      </thead>
                      <tbody>
                        {capacity.data.series.map((s) => (
                          <tr key={s.seriesId}>
                            <td>
                              <b>{s.metric}</b> <SimulatedBadge simulated={s.simulated} />
                              <div className="text-[10px] text-muted">{s.resourceServiceName ?? `Series #${s.seriesId}`}</div>
                            </td>
                            <td>{s.current !== null ? s.current.toFixed(2) : '—'}</td>
                            <td>
                              {s.threshold.toFixed(1)} <small className="text-muted">({s.thresholdBasis})</small>
                            </td>
                            <td>
                              <ToneBadge value={s.riskLevel} label={s.riskLevel} tone={RISK_TONES[s.riskLevel] ?? 'neutral'} />
                            </td>
                            <td>
                              {s.hoursToThreshold !== null ? `${s.hoursToThreshold.toFixed(1)} h` : 'Beyond horizon'}
                              {s.earliestHours !== null && (
                                <span className="ml-1 text-[10px] text-muted">
                                  (earliest {s.earliestHours.toFixed(1)} h{s.latestHours !== null ? ` · latest ${s.latestHours.toFixed(1)} h` : ''})
                                </span>
                              )}
                            </td>
                            <td>{formatNumber(s.historyPoints)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            )}
          </Panel>
        )}

        {tab === 'records' && (
          <div className="grid gap-[14px]">
            <Panel title="Served prediction ledger" subtitle="Predictions served by ACTIVE models and their reconciled outcomes">
              {records.isLoading && <LoadingState label="Loading prediction ledger…" />}
              {records.data && records.data.content.length === 0 && (
                <EmptyState title="No served predictions recorded yet" description="Predictions from ACTIVE models are persisted here for live accuracy and drift checks." />
              )}
              {records.data && records.data.content.length > 0 && (
                <>
                  <div className="table-wrap">
                    <table className="data-table">
                      <thead>
                        <tr>
                          <th>ID</th>
                          <th>Model</th>
                          <th>Subject</th>
                          <th>Score / Forecast</th>
                          <th>Risk</th>
                          <th>Outcome status</th>
                          <th>Actual outcome</th>
                          <th>Created</th>
                        </tr>
                      </thead>
                      <tbody>
                        {records.data.content.map((r) => (
                          <tr key={r.id}>
                            <td className="id-cell">#{r.id}</td>
                            <td>
                              {r.modelType} v{r.modelVersion} <SimulatedBadge simulated={r.simulated} />
                            </td>
                            <td>
                              {r.subjectLabel ?? `${r.subjectType} #${r.subjectId}`}
                            </td>
                            <td>{r.score.toFixed(3)}</td>
                            <td>{r.riskLevel ? <ToneBadge value={r.riskLevel} label={r.riskLevel} tone={RISK_TONES[r.riskLevel] ?? 'neutral'} /> : '—'}</td>
                            <td>
                              <ToneBadge
                                value={r.outcomeStatus}
                                label={r.outcomeStatus}
                                tone={r.outcomeStatus === 'KNOWN' ? 'success' : r.outcomeStatus === 'PENDING' ? 'champagne' : 'muted'}
                              />
                            </td>
                            <td>
                              {r.outcomePositive !== null
                                ? r.outcomePositive
                                  ? 'Positive (1)'
                                  : 'Negative (0)'
                                : r.outcomeValue !== null
                                  ? r.outcomeValue.toFixed(2)
                                  : '—'}
                            </td>
                            <td>{formatRelative(r.createdAt)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <Pagination page={recordPage} size={20} totalPages={records.data.totalPages} totalElements={records.data.totalElements} onPageChange={setRecordPage} />
                </>
              )}
            </Panel>

            <Panel title="Training & evaluation jobs" subtitle="Audit trail of manual and scheduled model training and evaluation jobs">
              {jobs.isLoading && <LoadingState label="Loading jobs…" />}
              {jobs.data && jobs.data.length === 0 && <EmptyState title="No training or evaluation jobs yet" />}
              {jobs.data && jobs.data.length > 0 && (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Job</th>
                        <th>Model type</th>
                        <th>Model ID</th>
                        <th>Status</th>
                        <th>Message</th>
                        <th>Duration</th>
                        <th>Requested by</th>
                        <th>Started</th>
                      </tr>
                    </thead>
                    <tbody>
                      {jobs.data.map((j) => (
                        <tr key={j.id}>
                          <td>
                            <b>{j.jobType}</b> <small className="text-muted">({j.trigger})</small>
                          </td>
                          <td>{j.modelType}</td>
                          <td>{j.modelId ? `#${j.modelId}` : '—'}</td>
                          <td>
                            <ToneBadge
                              value={j.status}
                              label={j.status}
                              tone={j.status === 'SUCCEEDED' ? 'success' : j.status === 'INSUFFICIENT_DATA' ? 'warning' : 'critical'}
                            />
                          </td>
                          <td className="text-[11px]">{j.message ?? '—'}</td>
                          <td>{j.durationMs} ms</td>
                          <td>{j.requestedByName ?? 'System'}</td>
                          <td>{formatRelative(j.startedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>
          </div>
        )}
      </div>

      {trainOpen && (
        <Modal open={trainOpen} onClose={() => setTrainOpen(false)} title="Train a new prediction model">
          <div className="grid gap-3">
            <label className="field">
              <span>Model type</span>
              <select className="input" value={trainType} onChange={(e) => setTrainType(e.target.value as PredictionModelType)}>
                {MODEL_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {MODEL_TYPE_LABELS[t]}
                  </option>
                ))}
              </select>
            </label>
            <div className="grid grid-cols-2 gap-3">
              <label className="field">
                <span>Historical window (days)</span>
                <input
                  type="number"
                  className="input"
                  min={7}
                  max={730}
                  value={trainDays}
                  onChange={(e) => setTrainDays(Number(e.target.value) || 180)}
                />
              </label>
              <label className="field">
                <span>Hold-out test split fraction</span>
                <input
                  type="number"
                  step="0.05"
                  min={0.1}
                  max={0.5}
                  className="input"
                  value={trainTestFraction}
                  onChange={(e) => setTrainTestFraction(Number(e.target.value) || 0.25)}
                />
              </label>
            </div>
            <label className="flex items-center gap-2 text-[12px]">
              <input type="checkbox" checked={trainSim} onChange={(e) => setTrainSim(e.target.checked)} />
              <span>Include simulated records in training data (flags the resulting model as SIMULATED)</span>
            </label>
            {train.isError && <p className="m-0 text-[11px] text-red-400">{errorMessage(train.error)}</p>}
            <div className="flex justify-end gap-2 pt-2">
              <button type="button" className="subtle-btn" onClick={() => setTrainOpen(false)}>
                Cancel
              </button>
              <button type="button" className="gold-btn" disabled={train.isPending} onClick={submitTrain}>
                {train.isPending ? 'Training…' : 'Train model'}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}
