import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  CheckCircle2,
  DollarSign,
  FileSpreadsheet,
  PiggyBank,
  Plus,
  TrendingUp,
  XCircle,
} from 'lucide-react';
import {
  useAcceptCostRecommendation,
  useAllocationRules,
  useCostAnomalies,
  useCostRecommendations,
  useCostSources,
  useCreateAllocationRule,
  useCreateBudget,
  useDismissCostRecommendation,
  useFinOpsBudgets,
  useFinOpsOverview,
  useIngestCosts,
} from '../../api/finops';
import { SimulatedBadge } from '../../components/Badge';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { IncludeSimulatedToggle } from '../../components/sim/SimulationParts';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { errorMessage } from '../../lib/api';
import { FINOPS } from '../../lib/constants';
import { formatNumber, formatPercent } from '../../lib/format';
import type {
  AllocationRuleDto,
  BudgetStatusDto,
  CostAnomalyDto,
  CostIngestResultDto,
  CostRecommendationDto,
  CostSourceDto,
} from '../../lib/r4Types';

export type FinOpsTab = 'overview' | 'ingestion' | 'budgets' | 'anomalies' | 'recommendations';
const TABS: FinOpsTab[] = ['overview', 'ingestion', 'budgets', 'anomalies', 'recommendations'];
const TAB_LABELS: Record<FinOpsTab, string> = {
  overview: 'Cost Overview & Allocation',
  ingestion: 'Cost Ingestion (Dry-Run & Apply)',
  budgets: 'Budgets & Forecasts',
  anomalies: 'Cost Anomalies & Idle Detection',
  recommendations: 'Savings Recommendations',
};

function usd(amount: number | null | undefined): string {
  if (amount == null || !Number.isFinite(amount)) return '$0.00';
  return `$${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export default function FinOpsPage() {
  const { hasRole } = useAuth();
  const canFinOps = hasRole(...FINOPS);
  const toast = useToast();

  const [tab, setTab] = useTabParam<FinOpsTab>(TABS, 'overview');
  const [params, setParams] = useSearchParams();
  const includeSimulated = params.get('sim') === '1';

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

  // Queries
  const overviewQuery = useFinOpsOverview(includeSimulated);
  const sourcesQuery = useCostSources();
  const rulesQuery = useAllocationRules();
  const budgetsQuery = useFinOpsBudgets(includeSimulated);
  const anomaliesQuery = useCostAnomalies(includeSimulated);
  const recommendationsQuery = useCostRecommendations(includeSimulated);

  // Mutations
  const ingestCosts = useIngestCosts();
  const createRule = useCreateAllocationRule();
  const createBudget = useCreateBudget();
  const acceptRec = useAcceptCostRecommendation();
  const dismissRec = useDismissCostRecommendation();

  // Cost ingestion state
  const [csvFormat, setCsvFormat] = useState<'FOCUS_CSV' | 'MAPPED_CSV'>('FOCUS_CSV');
  const [csvContent, setCsvContent] = useState(
    'ChargePeriodStart,ServiceName,ResourceId,ResourceName,BilledCost,EffectiveCost,ProviderName,SubAccountId,Tags\n2026-11-01,AmazonRDS,rds-payroll-01,Payroll Primary DB,420.50,395.00,AWS,acct-prod-01,"service=payroll;cost_center=CC-FIN"',
  );
  const [lastIngestResult, setLastIngestResult] = useState<CostIngestResultDto | null>(null);

  // Allocation rule state
  const [ruleName, setRuleName] = useState('');
  const [ruleTagKey, setRuleTagKey] = useState('service');
  const [ruleTagVal, setRuleTagVal] = useState('payroll');
  const [ruleCostCenter, setRuleCostCenter] = useState('CC-FIN');

  // Budget form state
  const [budgetName, setBudgetName] = useState('');
  const [budgetTargetType, setBudgetTargetType] = useState('COST_CENTER');
  const [budgetTargetKey, setBudgetTargetKey] = useState('CC-FIN');
  const [budgetMonth] = useState('2026-11');
  const [budgetAmount, setBudgetAmount] = useState(15000);

  const overview = overviewQuery.data;
  const sources = sourcesQuery.data ?? [];
  const rules = rulesQuery.data ?? [];
  const budgets = budgetsQuery.data ?? [];
  const anomalies = anomaliesQuery.data ?? [];
  const recommendations = recommendationsQuery.data ?? [];

  function runIngestion(dryRun: boolean) {
    if (!csvContent.trim()) {
      toast.error('Provide FOCUS 1.x or mapped CSV content');
      return;
    }
    ingestCosts.mutate(
      {
        sourceId: sources[0]?.id ?? null,
        format: csvFormat,
        dryRun,
        csvContent,
      },
      {
        onSuccess: (res) => {
          setLastIngestResult(res);
          if (dryRun) {
            toast.info(`Dry-run validated ${res.validRows} rows (${res.errorRows} errors)`);
          } else {
            toast.success(`Applied ${res.appliedCount} cost records (${usd(res.totalBilledCost)})`);
          }
        },
        onError: (err) => toast.error(errorMessage(err)),
      },
    );
  }

  return (
    <>
      <PageHeader
        title="FinOps"
        subtitle="FOCUS 1.x cloud cost ingestion, tag & pattern allocation rules, budgets with linear month-end forecasts, EWMA/MAD cost anomalies and savings recommendations."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <IncludeSimulatedToggle value={includeSimulated} onChange={setIncludeSimulated} />
            {overview && <SimulatedBadge simulated={overview.simulated} />}
          </div>
        }
      />

      <div className="page-stack">
        <Tabs<FinOpsTab>
          label="FinOps views"
          value={tab}
          onChange={setTab}
          tabs={TABS.map((id) => ({ id, label: TAB_LABELS[id] }))}
        />

        {tab === 'overview' && (
          <div className="space-y-4">
            {overviewQuery.isLoading && <LoadingState label="Loading FinOps overview…" />}
            {overviewQuery.isError && <ErrorState error={overviewQuery.error} onRetry={() => void overviewQuery.refetch()} />}

            {overview && overview.status === 'INSUFFICIENT_DATA' && (
              <div className="callout" role="status">
                <AlertTriangle size={16} />
                <div>
                  <b>Insufficient data — no cloud cost records ingested yet</b>
                  <div className="text-xs text-muted">
                    SA never fabricates live cloud billing figures. Ingest a FOCUS 1.x CSV under Cost Ingestion or enable
                    Include Simulated Data to inspect simulated cost spike scenarios.
                  </div>
                </div>
              </div>
            )}

            {overview && overview.status === 'OK' && (
              <>
                <div className="kpi-grid">
                  <KpiCard
                    icon={<DollarSign size={18} />}
                    label="Total Billed Cost"
                    value={usd(overview.totalBilledCost)}
                    tone="gold"
                    caption={`Effective cost: ${usd(overview.totalEffectiveCost)}`}
                  />
                  <KpiCard
                    icon={<AlertTriangle size={18} />}
                    label="Unallocated Cost Bucket"
                    value={usd(overview.unallocatedCost)}
                    tone={overview.unallocatedCost > 0 ? 'warning' : 'success'}
                  />
                  <KpiCard
                    icon={<PiggyBank size={18} />}
                    label="Potential Monthly Savings"
                    value={usd(overview.potentialMonthlySavings)}
                    tone="success"
                    caption={`${formatNumber(overview.idleResourcesCount)} idle resources`}
                  />
                  <KpiCard
                    icon={<TrendingUp size={18} />}
                    label="Open Cost Anomalies"
                    value={formatNumber(overview.openAnomalies)}
                    tone={overview.openAnomalies > 0 ? 'critical' : 'neutral'}
                  />
                </div>

                <div className="grid gap-4 lg:grid-cols-2">
                  <Panel title="Allocated Cost by Service" subtitle="Billed vs effective cost share">
                    <div className="table-wrap">
                      <table className="data-table">
                        <thead>
                          <tr>
                            <th>Service</th>
                            <th>Billed Cost</th>
                            <th>Effective Cost</th>
                            <th>Share</th>
                          </tr>
                        </thead>
                        <tbody>
                          {overview.byService.map((item) => (
                            <tr key={item.key}>
                              <td><b>{item.name ?? item.label}</b></td>
                              <td>{usd(item.billedCost)}</td>
                              <td>{usd(item.effectiveCost)}</td>
                              <td>{formatPercent(item.sharePercent, 1)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </Panel>

                  <Panel title="Allocated Cost by Cost Centre" subtitle="Includes UNALLOCATED bucket when tags/rules do not match">
                    <div className="table-wrap">
                      <table className="data-table">
                        <thead>
                          <tr>
                            <th>Cost Centre</th>
                            <th>Billed Cost</th>
                            <th>Effective Cost</th>
                            <th>Share</th>
                          </tr>
                        </thead>
                        <tbody>
                          {overview.byCostCenter.map((item) => (
                            <tr key={item.key}>
                              <td><b>{item.name ?? item.label}</b></td>
                              <td>{usd(item.billedCost)}</td>
                              <td>{usd(item.effectiveCost)}</td>
                              <td>{formatPercent(item.sharePercent, 1)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </Panel>
                </div>
              </>
            )}

            <Panel
              title={`Cost Allocation Rules (${rules.length})`}
              subtitle="Priority-ordered tag, sub-account and resource pattern rules with percentage splits"
            >
              {canFinOps && (
                <div className="grid gap-2 md:grid-cols-5 mb-3">
                  <input
                    aria-label="Rule name"
                    className="input-control"
                    value={ruleName}
                    onChange={(e) => setRuleName(e.target.value)}
                    placeholder="Payroll tag allocation"
                  />
                  <input
                    aria-label="Tag key"
                    className="input-control"
                    value={ruleTagKey}
                    onChange={(e) => setRuleTagKey(e.target.value)}
                    placeholder="Tag key (e.g. service)"
                  />
                  <input
                    aria-label="Tag value"
                    className="input-control"
                    value={ruleTagVal}
                    onChange={(e) => setRuleTagVal(e.target.value)}
                    placeholder="Tag value (e.g. payroll)"
                  />
                  <input
                    aria-label="Target cost centre"
                    className="input-control"
                    value={ruleCostCenter}
                    onChange={(e) => setRuleCostCenter(e.target.value)}
                    placeholder="Cost centre (e.g. CC-FIN)"
                  />
                  <button
                    type="button"
                    className="gold-btn"
                    onClick={() => {
                      if (!ruleName.trim()) return;
                      createRule.mutate(
                        {
                          name: ruleName.trim(),
                          priority: 10,
                          tagKey: ruleTagKey,
                          tagValue: ruleTagVal,
                          targetCostCenter: ruleCostCenter,
                          splitPercent: 100,
                          enabled: true,
                        },
                        {
                          onSuccess: () => {
                            setRuleName('');
                            toast.success('Created cost allocation rule');
                          },
                        },
                      );
                    }}
                  >
                    <Plus size={14} /> Add Rule
                  </button>
                </div>
              )}

              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Priority</th>
                      <th>Rule Name</th>
                      <th>Matcher (Tag / Account / Pattern)</th>
                      <th>Target Cost Centre</th>
                      <th>Split %</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rules.map((r: AllocationRuleDto) => (
                      <tr key={r.id}>
                        <td>#{r.priority}</td>
                        <td><b>{r.name}</b></td>
                        <td>
                          <code>
                            {r.tagKey ? `${r.tagKey}=${r.tagValue ?? '*'}` : r.resourcePattern ?? r.subAccountId ?? '*'}
                          </code>
                        </td>
                        <td><code>{r.targetCostCenter ?? '—'}</code></td>
                        <td>{r.splitPercent}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Panel>
          </div>
        )}

        {tab === 'ingestion' && (
          <div className="space-y-4">
            <Panel
              title="Cloud Billing Sources & Prepared Adapters"
              subtitle="FOCUS 1.x CSV, Mapped CSV, and prepared cloud billing adapters (AWS CUR, Azure Cost Management, GCP Billing Export)"
            >
              <div className="grid gap-3 md:grid-cols-3">
                {sources.map((s: CostSourceDto) => (
                  <div key={s.id} className="rounded border border-slate-200 p-3 text-xs dark:border-slate-800 space-y-1">
                    <div className="flex items-center justify-between">
                      <b>{s.name}</b>
                      <ToneBadge value={s.status} label={s.status} tone={s.status === 'CONNECTED' ? 'success' : 'muted'} />
                    </div>
                    <div className="text-muted">
                      Provider: {s.providerName} · Kind: <code>{s.kind}</code>
                    </div>
                  </div>
                ))}
              </div>
            </Panel>

            {canFinOps ? (
              <Panel
                title="FOCUS 1.x / Mapped CSV Cost Ingestion"
                subtitle="Run a non-destructive dry-run first to inspect row-level validation errors before applying idempotently"
              >
                <div className="space-y-3">
                  <div className="flex flex-wrap items-center gap-3">
                    <select
                      aria-label="CSV format"
                      className="input-control"
                      value={csvFormat}
                      onChange={(e) => setCsvFormat(e.target.value as 'FOCUS_CSV' | 'MAPPED_CSV')}
                    >
                      <option value="FOCUS_CSV">FOCUS 1.x Specification CSV</option>
                      <option value="MAPPED_CSV">Generic Mapped CSV</option>
                    </select>
                    <button
                      type="button"
                      className="subtle-btn"
                      disabled={ingestCosts.isPending}
                      onClick={() => runIngestion(true)}
                    >
                      <FileSpreadsheet size={14} /> Validate (Dry-Run)
                    </button>
                    <button
                      type="button"
                      className="gold-btn"
                      disabled={ingestCosts.isPending}
                      onClick={() => runIngestion(false)}
                    >
                      <CheckCircle2 size={14} /> Apply Cost Ingestion
                    </button>
                  </div>

                  <textarea
                    aria-label="Cost CSV content"
                    rows={4}
                    className="input-control font-mono text-xs w-full"
                    value={csvContent}
                    onChange={(e) => setCsvContent(e.target.value)}
                  />

                  {lastIngestResult && (
                    <div className="rounded border border-slate-200 p-3 text-xs dark:border-slate-800 space-y-2">
                      <div className="flex flex-wrap items-center gap-3">
                        <ToneBadge
                          value={lastIngestResult.dryRun ? 'DRY_RUN' : 'APPLIED'}
                          label={lastIngestResult.dryRun ? 'DRY-RUN RESULT' : 'APPLIED'}
                          tone={lastIngestResult.dryRun ? 'gold' : 'success'}
                        />
                        <span>Valid rows: <b>{lastIngestResult.validRows}</b></span>
                        <span>Row errors: <b>{lastIngestResult.errorRows}</b></span>
                        <span>Total Billed: <b>{usd(lastIngestResult.totalBilledCost)}</b></span>
                        <span>Applied count: <b>{lastIngestResult.appliedCount}</b></span>
                      </div>
                      {lastIngestResult.errors.length > 0 && (
                        <ul className="m-0 list-disc pl-4 text-red-500">
                          {lastIngestResult.errors.map((err, idx) => (
                            <li key={idx}>
                              Row #{err.rowNumber} ({err.field}): {err.message}
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  )}
                </div>
              </Panel>
            ) : (
              <Panel title="Cost Ingestion (Read-Only)">
                <p className="text-xs text-muted">Only FINOPS roles may upload or apply cloud billing CSV batches.</p>
              </Panel>
            )}
          </div>
        )}

        {tab === 'budgets' && (
          <div className="space-y-4">
            {canFinOps && (
              <Panel title="Create Monthly Budget & Alert Thresholds" subtitle="Tracks spend-to-date and linear month-end forecast against 50% / 80% / 100% thresholds">
                <div className="grid gap-2 md:grid-cols-5">
                  <input
                    aria-label="Budget name"
                    className="input-control"
                    value={budgetName}
                    onChange={(e) => setBudgetName(e.target.value)}
                    placeholder="Payroll Production Budget"
                  />
                  <select
                    aria-label="Budget target type"
                    className="input-control"
                    value={budgetTargetType}
                    onChange={(e) => setBudgetTargetType(e.target.value)}
                  >
                    <option value="COST_CENTER">COST_CENTER</option>
                    <option value="SERVICE">SERVICE</option>
                  </select>
                  <input
                    aria-label="Budget target key"
                    className="input-control"
                    value={budgetTargetKey}
                    onChange={(e) => setBudgetTargetKey(e.target.value)}
                    placeholder="CC-FIN"
                  />
                  <input
                    aria-label="Budget amount"
                    type="number"
                    className="input-control"
                    value={budgetAmount}
                    onChange={(e) => setBudgetAmount(Number(e.target.value))}
                  />
                  <button
                    type="button"
                    className="gold-btn"
                    onClick={() => {
                      if (!budgetName.trim()) return;
                      createBudget.mutate(
                        {
                          name: budgetName.trim(),
                          targetType: budgetTargetType,
                          targetKey: budgetTargetKey,
                          periodMonth: budgetMonth,
                          amount: budgetAmount,
                          thresholds: [50, 80, 100],
                        },
                        {
                          onSuccess: () => {
                            setBudgetName('');
                            toast.success('Created FinOps budget');
                          },
                        },
                      );
                    }}
                  >
                    <Plus size={14} /> Create Budget
                  </button>
                </div>
              </Panel>
            )}

            <Panel title="Budgets & Linear End-of-Month Forecasts">
              {budgets.length === 0 ? (
                <EmptyState title="No budgets configured" />
              ) : (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Budget</th>
                        <th>Target</th>
                        <th>Period</th>
                        <th>Monthly Limit</th>
                        <th>Spent to Date</th>
                        <th>Utilisation</th>
                        <th>Linear EOM Forecast</th>
                        <th>Triggered Thresholds</th>
                        <th>Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {budgets.map((b: BudgetStatusDto) => (
                        <tr key={b.id}>
                          <td><b>{b.name}</b></td>
                          <td><code>{b.targetType}:{b.targetKey}</code></td>
                          <td>{b.periodMonth}</td>
                          <td>{usd(b.amount)}</td>
                          <td>{usd(b.spentToDate)}</td>
                          <td><b>{formatPercent(b.utilizationPercent, 1)}</b></td>
                          <td>{usd(b.forecastedEndOfMonth)}</td>
                          <td>{b.triggeredThresholds.length > 0 ? b.triggeredThresholds.map((t) => `${t}%`).join(', ') : 'None'}</td>
                          <td>
                            <ToneBadge
                              value={b.status}
                              label={b.status}
                              tone={b.status === 'EXCEEDED' ? 'critical' : b.status === 'WARNING' ? 'warning' : 'success'}
                            />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>
          </div>
        )}

        {tab === 'anomalies' && (
          <Panel
            title="Daily Cost Anomalies (EWMA / MAD Baseline)"
            subtitle="Detects statistically significant daily spend spikes above expected range"
          >
            {anomalies.length === 0 ? (
              <EmptyState title="No cost anomalies detected" />
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Scope</th>
                      <th>Charge Date</th>
                      <th>Actual Cost</th>
                      <th>Expected Cost</th>
                      <th>Upper Bound</th>
                      <th>Deviation (z)</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {anomalies.map((a: CostAnomalyDto) => (
                      <tr key={a.id}>
                        <td>
                          <code>{a.scopeKey}</code> <SimulatedBadge simulated={a.simulated} />
                        </td>
                        <td>{a.chargeDate}</td>
                        <td><b>{usd(a.actualCost)}</b></td>
                        <td>{usd(a.expectedCost)}</td>
                        <td>{usd(a.upperBound)}</td>
                        <td>{a.deviationScore.toFixed(2)}σ</td>
                        <td>
                          <ToneBadge value={a.status} label={a.status} tone={a.status === 'OPEN' ? 'critical' : 'neutral'} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        )}

        {tab === 'recommendations' && (
          <Panel
            title="Right-Sizing, Idle Resource & Savings Recommendations"
            subtitle="Computed strictly from observed utilisation & cost records; accepting creates a governed draft Change Request"
          >
            {recommendations.length === 0 ? (
              <EmptyState title="No cost recommendations open" />
            ) : (
              <div className="space-y-3">
                {recommendations.map((rec: CostRecommendationDto) => (
                  <div
                    key={rec.id}
                    className="rounded-lg border border-slate-200 p-4 dark:border-slate-800 flex flex-wrap items-center justify-between gap-3"
                  >
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <ToneBadge value={rec.category} label={rec.category} tone="gold" />
                        <SimulatedBadge simulated={rec.simulated} />
                        <ToneBadge
                          value={rec.status}
                          label={rec.status}
                          tone={rec.status === 'ACCEPTED' ? 'success' : rec.status === 'DISMISSED' ? 'muted' : 'warning'}
                        />
                      </div>
                      <h4 className="text-sm font-semibold">{rec.title}</h4>
                      <div className="text-xs text-muted">
                        Resource: <code>{rec.resourceId}</code> ({rec.resourceName}) · Service: {rec.serviceName ?? 'Unmapped'} · Evidence:{' '}
                        <code>{JSON.stringify(rec.evidence)}</code>
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      <div className="text-right">
                        <small className="block text-[10px] text-muted">Est. Monthly Savings</small>
                        <b className="text-sm green-text">{usd(rec.estimatedMonthlySavings)}/mo</b>
                      </div>

                      {rec.changeRequestId && (
                        <Link to={`/changes/${rec.changeRequestId}`} className="subtle-btn compact">
                          Draft Change #{rec.changeRequestId}
                        </Link>
                      )}

                      {canFinOps && rec.status === 'OPEN' && (
                        <div className="flex items-center gap-1.5">
                          <button
                            type="button"
                            className="gold-btn compact"
                            disabled={acceptRec.isPending}
                            onClick={() =>
                              acceptRec.mutate(rec.id, {
                                onSuccess: (updated) =>
                                  toast.success(
                                    `Accepted recommendation — Draft Change Request #${updated.changeRequestId ?? 'created'}`,
                                  ),
                                onError: (err) => toast.error(errorMessage(err)),
                              })
                            }
                          >
                            <CheckCircle2 size={13} /> Accept → Draft Change
                          </button>
                          <button
                            type="button"
                            className="subtle-btn compact"
                            disabled={dismissRec.isPending}
                            onClick={() =>
                              dismissRec.mutate(
                                { id: rec.id, reason: 'Reserved burst capacity for month-end payroll close' },
                                {
                                  onSuccess: () => toast.info('Recommendation dismissed with reason'),
                                  onError: (err) => toast.error(errorMessage(err)),
                                },
                              )
                            }
                          >
                            <XCircle size={13} /> Dismiss
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Panel>
        )}
      </div>
    </>
  );
}
