import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  CheckCircle2,
  FileCheck2,
  Lock,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  Send,
  ShieldAlert,
  ShieldCheck,
  Square,
} from 'lucide-react';
import {
  useApprovePlaybookVersion,
  useCancelRemediationRun,
  useConfirmRemediationStep,
  useCreateExecutionSystem,
  useCreatePlaybook,
  useExecutionSystems,
  useOverrideRemediationRun,
  usePlaybooks,
  useRemediationAudit,
  useRemediationMeta,
  useRemediationRun,
  useRemediationRuns,
  useStartRemediationRun,
  useSubmitPlaybookVersion,
  useTestExecutionSystem,
  useVerifyRemediationAudit,
} from '../../api/remediation';
import { SimulatedBadge } from '../../components/Badge';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { IncludeSimulatedToggle } from '../../components/sim/SimulationParts';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { errorMessage } from '../../lib/api';
import {
  EXECUTION_SYSTEM_ADMINS,
  PLAYBOOK_APPROVERS,
  PLAYBOOK_EDITORS,
  REMEDIATION_OPERATORS,
  type Tone,
} from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import type {
  AuditVerifyResultDto,
  ExecutionSystemDto,
  PlaybookDto,
  PlaybookVersionDto,
  RemediationAuditDto,
  RemediationRunDto,
  RemediationRunStepDto,
} from '../../lib/r4Types';

export type RemediationTab = 'playbooks' | 'runs' | 'systems' | 'audit';
const TABS: RemediationTab[] = ['playbooks', 'runs', 'systems', 'audit'];
const TAB_LABELS: Record<RemediationTab, string> = {
  playbooks: 'Playbooks & Versions',
  runs: 'Execution Runs & Rollback Timeline',
  systems: 'Execution Systems & Allow-listed Templates',
  audit: 'Tamper-Evident Audit Chain',
};

function statusTone(status: string | null | undefined): Tone {
  switch (status) {
    case 'APPROVED':
    case 'SUCCEEDED':
    case 'CONNECTED':
    case 'COMPLETED':
      return 'success';
    case 'SIMULATED':
    case 'DRAFT':
      return 'gold';
    case 'IN_REVIEW':
    case 'RUNNING':
    case 'VERIFYING':
    case 'PENDING_APPROVAL':
    case 'WAITING_CONFIRMATION':
    case 'ROLLING_BACK':
    case 'ROLLED_BACK':
      return 'warning';
    case 'BLOCKED':
    case 'FAILED':
    case 'ROLLBACK_FAILED':
    case 'ERROR':
      return 'critical';
    default:
      return 'muted';
  }
}

export default function RemediationPage() {
  const { hasRole } = useAuth();
  const canEditPlaybook = hasRole(...PLAYBOOK_EDITORS);
  const canApprovePlaybook = hasRole(...PLAYBOOK_APPROVERS);
  const canOperate = hasRole(...REMEDIATION_OPERATORS);
  const canAdminSystems = hasRole(...EXECUTION_SYSTEM_ADMINS);
  const toast = useToast();

  const [tab, setTab] = useTabParam<RemediationTab>(TABS, 'playbooks');
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
  const metaQuery = useRemediationMeta();
  const playbooksQuery = usePlaybooks();
  const systemsQuery = useExecutionSystems();
  const runsQuery = useRemediationRuns(includeSimulated);
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);
  const effectiveRunId = selectedRunId ?? runsQuery.data?.[0]?.id ?? null;
  const runDetailQuery = useRemediationRun(effectiveRunId);
  const auditQuery = useRemediationAudit();

  // Mutations
  const createPlaybook = useCreatePlaybook();
  const submitVersion = useSubmitPlaybookVersion();
  const approveVersion = useApprovePlaybookVersion();
  const startRun = useStartRemediationRun();
  const overrideRun = useOverrideRemediationRun();
  const cancelRun = useCancelRemediationRun();
  const confirmStep = useConfirmRemediationStep();
  const createSystem = useCreateExecutionSystem();
  const testSystem = useTestExecutionSystem();
  const verifyAudit = useVerifyRemediationAudit();

  // Playbook creation form state
  const [showNewPlaybook, setShowNewPlaybook] = useState(false);
  const [pbCode, setPbCode] = useState('');
  const [pbName, setPbName] = useState('');
  const [pbDesc, setPbDesc] = useState('');
  const [pbEnv, setPbEnv] = useState('PRODUCTION');
  const [pbMaxBlast, setPbMaxBlast] = useState(5);

  // Run launch state
  const [runPlaybookId, setRunPlaybookId] = useState<number | ''>('');
  const [runTargetKey, setRunTargetKey] = useState('payroll-db-primary');
  const [runEnv, setRunEnv] = useState('PRODUCTION');
  const [runIncidentId, setRunIncidentId] = useState('');
  const [simulateFailureStep, setSimulateFailureStep] = useState('');
  const [overrideReason, setOverrideReason] = useState('');

  // Execution system creation state
  const [showNewSystem, setShowNewSystem] = useState(false);
  const [sysName, setSysName] = useState('');
  const [sysKind, setSysKind] = useState('KUBERNETES');
  const [sysEndpoint, setSysEndpoint] = useState('');
  const [sysScope, setSysScope] = useState('');

  // Audit verification result
  const [verifyResult, setVerifyResult] = useState<AuditVerifyResultDto | null>(null);

  const playbooks = playbooksQuery.data ?? [];
  const systems = systemsQuery.data ?? [];
  const runs = runsQuery.data ?? [];
  const runDetail = runDetailQuery.data;

  function handleCreatePlaybook() {
    if (!pbCode.trim() || !pbName.trim()) {
      toast.error('Playbook code and name are required');
      return;
    }
    createPlaybook.mutate(
      {
        code: pbCode.trim().toUpperCase(),
        name: pbName.trim(),
        description: pbDesc.trim() || null,
        targetEnvironment: pbEnv,
        maxBlastRadius: pbMaxBlast,
        rateLimitPerHour: 4,
        requireLinkedRecord: true,
        triggerKinds: ['MANUAL', 'AGENT_RECOMMENDATION'],
        recoveryCriteria: { criteria: 'p95 latency < 250ms and error rate < 1%' },
      },
      {
        onSuccess: (created) => {
          toast.success(`Created playbook ${created.code} (v1 DRAFT)`);
          setShowNewPlaybook(false);
          setPbCode('');
          setPbName('');
          setPbDesc('');
        },
        onError: (err) => toast.error(errorMessage(err)),
      },
    );
  }

  function handleStartRun() {
    const pbId = runPlaybookId === '' ? playbooks[0]?.id : Number(runPlaybookId);
    if (!pbId) {
      toast.error('Select a playbook to execute');
      return;
    }
    if (!runTargetKey.trim()) {
      toast.error('Target key is required');
      return;
    }
    startRun.mutate(
      {
        playbookId: pbId,
        targetKey: runTargetKey.trim(),
        environment: runEnv,
        incidentId: runIncidentId ? Number(runIncidentId) : undefined,
        triggerKind: 'MANUAL',
        parameters: {},
        simulateFailureAtStep: simulateFailureStep ? Number(simulateFailureStep) : undefined,
      },
      {
        onSuccess: (detail) => {
          setSelectedRunId(detail.run.id);
          setTab('runs');
          toast.success(`Remediation run #${detail.run.id} status: ${detail.run.status}`);
        },
        onError: (err) => toast.error(errorMessage(err)),
      },
    );
  }

  function handleCreateSystem() {
    if (!sysName.trim()) {
      toast.error('System name is required');
      return;
    }
    createSystem.mutate(
      {
        name: sysName.trim(),
        kind: sysKind,
        endpointUrl: sysEndpoint.trim() || null,
        credentialScope: sysScope.trim() || 'Least-privilege namespace/template scope',
      },
      {
        onSuccess: (created) => {
          toast.success(`Registered execution system ${created.name} (${created.status})`);
          setShowNewSystem(false);
          setSysName('');
          setSysEndpoint('');
          setSysScope('');
        },
        onError: (err) => toast.error(errorMessage(err)),
      },
    );
  }

  return (
    <>
      <PageHeader
        title="Remediation"
        subtitle="Policy-gated autonomous remediation playbooks with allow-listed action templates, automatic rollback on failure and SHA-256 tamper-evident audit chains."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <IncludeSimulatedToggle value={includeSimulated} onChange={setIncludeSimulated} />
            {canEditPlaybook && (
              <button type="button" className="gold-btn" onClick={() => setShowNewPlaybook((v) => !v)}>
                <Plus size={14} /> New Playbook
              </button>
            )}
          </div>
        }
      />

      <div className="page-stack">
        <Tabs<RemediationTab>
          label="Autonomous remediation views"
          value={tab}
          onChange={setTab}
          tabs={TABS.map((id) => ({ id, label: TAB_LABELS[id] }))}
        />

        {tab === 'playbooks' && (
          <div className="space-y-4">
            <div className="callout">
              <Lock size={16} />
              <div>
                <b>No shell, SQL or free-form command execution</b> — Every playbook step references an allow-listed,
                JSON-schema-validated action template on a scoped execution connector, declares its rollback template, and
                passes pre-execution policy checks (version approved, blast radius, freeze window, rate limit, target lock).
              </div>
            </div>

            {showNewPlaybook && canEditPlaybook && (
              <Panel title="Create Remediation Playbook (v1 DRAFT)" subtitle="Playbooks start as DRAFT and require approval before execution">
                <div className="grid gap-3 md:grid-cols-4">
                  <div>
                    <label htmlFor="pb-code" className="field-label">Playbook Code</label>
                    <input
                      id="pb-code"
                      className="input-control"
                      value={pbCode}
                      onChange={(e) => setPbCode(e.target.value)}
                      placeholder="PB-POOL-RECYCLE"
                    />
                  </div>
                  <div>
                    <label htmlFor="pb-name" className="field-label">Name</label>
                    <input
                      id="pb-name"
                      className="input-control"
                      value={pbName}
                      onChange={(e) => setPbName(e.target.value)}
                      placeholder="Recycle saturated connection pool"
                    />
                  </div>
                  <div>
                    <label htmlFor="pb-env" className="field-label">Target Environment</label>
                    <select
                      id="pb-env"
                      className="input-control"
                      value={pbEnv}
                      onChange={(e) => setPbEnv(e.target.value)}
                    >
                      <option value="PRODUCTION">PRODUCTION</option>
                      <option value="STAGING">STAGING</option>
                      <option value="DEVELOPMENT">DEVELOPMENT</option>
                    </select>
                  </div>
                  <div>
                    <label htmlFor="pb-blast" className="field-label">Max Blast Radius (services)</label>
                    <input
                      id="pb-blast"
                      type="number"
                      className="input-control"
                      value={pbMaxBlast}
                      onChange={(e) => setPbMaxBlast(Number(e.target.value))}
                    />
                  </div>
                  <div className="md:col-span-3">
                    <label htmlFor="pb-desc" className="field-label">Description</label>
                    <input
                      id="pb-desc"
                      className="input-control"
                      value={pbDesc}
                      onChange={(e) => setPbDesc(e.target.value)}
                      placeholder="Drains stale DB connections, restarts pods in rolling waves and verifies p95 latency recovery"
                    />
                  </div>
                  <div className="flex items-end gap-2">
                    <button
                      type="button"
                      className="gold-btn"
                      disabled={createPlaybook.isPending}
                      onClick={handleCreatePlaybook}
                    >
                      Create Playbook
                    </button>
                  </div>
                </div>
              </Panel>
            )}

            <Panel
              title="Remediation Playbooks & Version Governance"
              subtitle="DRAFT → IN_REVIEW → APPROVED lifecycle with per-step rollback templates and recovery verification criteria"
            >
              {playbooksQuery.isLoading && <LoadingState label="Loading playbooks…" />}
              {playbooksQuery.isError && <ErrorState error={playbooksQuery.error} onRetry={() => void playbooksQuery.refetch()} />}
              {playbooks.length === 0 && !playbooksQuery.isLoading && (
                <EmptyState title="No remediation playbooks defined yet" description="Create a playbook to define governed remediation steps." />
              )}
              <div className="space-y-4">
                {playbooks.map((pb: PlaybookDto) => (
                  <div key={pb.id} className="rounded-lg border border-slate-200 p-4 dark:border-slate-800 space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <span className="font-mono text-xs font-semibold">{pb.code}</span>
                        <h4 className="text-sm font-semibold">{pb.name}</h4>
                        {pb.description && <p className="text-xs text-muted">{pb.description}</p>}
                      </div>
                      <div className="flex flex-wrap items-center gap-2">
                        <ToneBadge value={pb.targetEnvironment} label={`Env: ${pb.targetEnvironment}`} tone="gold" />
                        <ToneBadge value="BLAST" label={`Max blast radius: ${pb.maxBlastRadius}`} tone="neutral" />
                        <ToneBadge value="RATE" label={`Rate limit: ${pb.rateLimitPerHour}/hr`} tone="neutral" />
                        {pb.activeVersion != null ? (
                          <ToneBadge value="ACTIVE" label={`Active v${pb.activeVersion}`} tone="success" />
                        ) : (
                          <ToneBadge value="NO_ACTIVE" label="No approved version" tone="warning" />
                        )}
                      </div>
                    </div>

                    {pb.versions.map((ver: PlaybookVersionDto) => (
                      <div key={ver.id} className="rounded border border-slate-200/80 p-3 dark:border-slate-800/80 space-y-2">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div className="flex items-center gap-2">
                            <span className="font-mono text-xs font-semibold">Version v{ver.version}</span>
                            <ToneBadge value={ver.status} label={ver.status} tone={statusTone(ver.status)} />
                            {ver.recoveryCriteria && (
                              <span className="text-[11px] text-muted">
                                Recovery:{' '}
                                {typeof ver.recoveryCriteria === 'string'
                                  ? ver.recoveryCriteria
                                  : JSON.stringify(ver.recoveryCriteria)}
                              </span>
                            )}
                          </div>
                          <div className="flex items-center gap-2">
                            {canEditPlaybook && ver.status === 'DRAFT' && (
                              <button
                                type="button"
                                className="subtle-btn compact"
                                disabled={submitVersion.isPending}
                                onClick={() =>
                                  submitVersion.mutate(
                                    { id: pb.id, version: ver.version },
                                    {
                                      onSuccess: () => toast.success(`Submitted ${pb.code} v${ver.version} for approval`),
                                      onError: (err) => toast.error(errorMessage(err)),
                                    },
                                  )
                                }
                              >
                                <Send size={13} /> Submit for Review
                              </button>
                            )}
                            {canApprovePlaybook && (ver.status === 'IN_REVIEW' || ver.status === 'DRAFT') && (
                              <button
                                type="button"
                                className="gold-btn compact"
                                disabled={approveVersion.isPending}
                                onClick={() =>
                                  approveVersion.mutate(
                                    { id: pb.id, version: ver.version },
                                    {
                                      onSuccess: () => toast.success(`Approved ${pb.code} v${ver.version}`),
                                      onError: (err) => toast.error(errorMessage(err)),
                                    },
                                  )
                                }
                              >
                                <CheckCircle2 size={13} /> Approve Version
                              </button>
                            )}
                          </div>
                        </div>

                        {ver.steps.length > 0 && (
                          <div className="table-wrap">
                            <table className="data-table">
                              <thead>
                                <tr>
                                  <th>#</th>
                                  <th>Step Name</th>
                                  <th>Kind</th>
                                  <th>Action Template</th>
                                  <th>Rollback Template</th>
                                  <th>Timeout / Retries</th>
                                </tr>
                              </thead>
                              <tbody>
                                {ver.steps.map((s) => (
                                  <tr key={s.stepOrder}>
                                    <td>{s.stepOrder}</td>
                                    <td><b>{s.name}</b></td>
                                    <td><ToneBadge value={s.kind} label={s.kind} tone="champagne" /></td>
                                    <td><code>{s.templateId ?? '—'}</code></td>
                                    <td><code>{s.rollbackTemplateId ?? '—'}</code></td>
                                    <td>{s.timeoutSeconds}s · {s.maxRetries} retries</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            </Panel>
          </div>
        )}

        {tab === 'runs' && (
          <div className="space-y-4">
            {canOperate && (
              <Panel
                title="Execute Remediation Playbook"
                subtitle="Evaluates pre-execution policy checks (blast radius, freeze window, rate limit, linked record) before running allow-listed steps"
              >
                <div className="grid gap-3 md:grid-cols-5">
                  <div>
                    <label htmlFor="run-playbook" className="field-label">Playbook</label>
                    <select
                      id="run-playbook"
                      className="input-control"
                      value={runPlaybookId}
                      onChange={(e) => setRunPlaybookId(e.target.value ? Number(e.target.value) : '')}
                    >
                      {playbooks.map((pb) => (
                        <option key={pb.id} value={pb.id}>
                          {pb.code} · {pb.name}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label htmlFor="run-target" className="field-label">Target Key</label>
                    <input
                      id="run-target"
                      className="input-control"
                      value={runTargetKey}
                      onChange={(e) => setRunTargetKey(e.target.value)}
                      placeholder="payroll-db-primary"
                    />
                  </div>
                  <div>
                    <label htmlFor="run-env" className="field-label">Environment</label>
                    <select
                      id="run-env"
                      className="input-control"
                      value={runEnv}
                      onChange={(e) => setRunEnv(e.target.value)}
                    >
                      <option value="PRODUCTION">PRODUCTION</option>
                      <option value="STAGING">STAGING</option>
                      <option value="DEVELOPMENT">DEVELOPMENT</option>
                    </select>
                  </div>
                  <div>
                    <label htmlFor="run-inc" className="field-label">Linked Incident ID</label>
                    <input
                      id="run-inc"
                      className="input-control"
                      value={runIncidentId}
                      onChange={(e) => setRunIncidentId(e.target.value)}
                      placeholder="e.g. 101"
                    />
                  </div>
                  <div className="flex items-end gap-2">
                    <div className="flex-1">
                      <label htmlFor="run-sim-fail" className="field-label">Fail at Step (optional)</label>
                      <input
                        id="run-sim-fail"
                        className="input-control"
                        value={simulateFailureStep}
                        onChange={(e) => setSimulateFailureStep(e.target.value)}
                        placeholder="e.g. 2"
                      />
                    </div>
                    <button
                      type="button"
                      className="gold-btn"
                      disabled={startRun.isPending}
                      onClick={handleStartRun}
                    >
                      <Play size={14} /> Run Playbook
                    </button>
                  </div>
                </div>
              </Panel>
            )}

            <div className="grid gap-4 lg:grid-cols-12">
              <div className="lg:col-span-5">
                <Panel title="Remediation Runs" subtitle="Click a run to inspect policy checks, steps and rollback timeline">
                  {runsQuery.isLoading && <LoadingState label="Loading remediation runs…" />}
                  {runs.length === 0 && !runsQuery.isLoading && (
                    <EmptyState title="No remediation runs recorded" description="Execute an approved playbook above." />
                  )}
                  <div className="space-y-2">
                    {runs.map((r: RemediationRunDto) => (
                      <button
                        key={r.id}
                        type="button"
                        onClick={() => setSelectedRunId(r.id)}
                        className={`w-full text-left rounded-lg border p-3 transition ${
                          r.id === effectiveRunId ? 'border-amber-500 bg-amber-50/20 dark:bg-amber-950/20' : 'border-slate-200 dark:border-slate-800'
                        }`}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-mono text-xs font-semibold">
                            #{r.id} · {r.playbookCode} v{r.playbookVersion}
                          </span>
                          <div className="flex items-center gap-1.5">
                            <SimulatedBadge simulated={r.simulated} />
                            <ToneBadge value={r.status} label={r.status} tone={statusTone(r.status)} />
                          </div>
                        </div>
                        <div className="mt-1 text-xs">
                          Target: <code>{r.targetKey}</code> ({r.environment})
                        </div>
                        <div className="mt-1 flex items-center justify-between text-[11px] text-muted">
                          <span>By {r.requestedByName ?? 'System'}</span>
                          <span>{formatRelative(r.createdAt)}</span>
                        </div>
                      </button>
                    ))}
                  </div>
                </Panel>
              </div>

              <div className="lg:col-span-7">
                <Panel
                  title={runDetail ? `Run #${runDetail.run.id} · ${runDetail.run.playbookName}` : 'Execution & Rollback Timeline'}
                  subtitle={
                    runDetail
                      ? `Target ${runDetail.run.targetKey} (${runDetail.run.environment}) · Trigger ${runDetail.run.triggerKind}`
                      : 'Select a run on the left'
                  }
                  actions={
                    runDetail ? (
                      <div className="flex flex-wrap items-center gap-2">
                        <SimulatedBadge simulated={runDetail.run.simulated} />
                        <ToneBadge value={runDetail.run.status} label={runDetail.run.status} tone={statusTone(runDetail.run.status)} />
                        {canOperate && runDetail.run.status === 'WAITING_CONFIRMATION' && (
                          <button
                            type="button"
                            className="gold-btn compact"
                            onClick={() =>
                              confirmStep.mutate(runDetail.run.id, {
                                onSuccess: () => toast.success('Manual confirmation step approved'),
                                onError: (err) => toast.error(errorMessage(err)),
                              })
                            }
                          >
                            <CheckCircle2 size={13} /> Confirm Step
                          </button>
                        )}
                        {canOperate &&
                          (runDetail.run.status === 'RUNNING' ||
                            runDetail.run.status === 'PENDING_APPROVAL' ||
                            runDetail.run.status === 'WAITING_CONFIRMATION') && (
                            <button
                              type="button"
                              className="subtle-btn compact"
                              onClick={() =>
                                cancelRun.mutate(runDetail.run.id, {
                                  onSuccess: () => toast.info('Remediation run cancelled'),
                                  onError: (err) => toast.error(errorMessage(err)),
                                })
                              }
                            >
                              <Square size={13} /> Cancel Run
                            </button>
                          )}
                      </div>
                    ) : undefined
                  }
                >
                  {!runDetail ? (
                    <EmptyState title="No run selected" description="Select a remediation run to view its policy gates and step timeline." />
                  ) : (
                    <div className="space-y-4">
                      {runDetail.run.failureReason && (
                        <div className="callout red" role="alert">
                          <ShieldAlert size={16} />
                          <div>
                            <b>Execution / Policy Failure:</b> {runDetail.run.failureReason}
                          </div>
                        </div>
                      )}

                      {runDetail.run.verificationResult && (
                        <div className="callout green">
                          <CheckCircle2 size={16} />
                          <div>
                            <b>Recovery Verification:</b>{' '}
                            {typeof runDetail.run.verificationResult === 'string'
                              ? runDetail.run.verificationResult
                              : JSON.stringify(runDetail.run.verificationResult)}
                          </div>
                        </div>
                      )}

                      {runDetail.run.status === 'BLOCKED' && canApprovePlaybook && (
                        <div className="rounded border border-amber-500/40 p-3 space-y-2">
                          <div className="text-xs font-semibold">Playbook Approver Policy Override (Audited)</div>
                          <div className="flex gap-2">
                            <input
                              aria-label="Override reason"
                              className="input-control flex-1"
                              value={overrideReason}
                              onChange={(e) => setOverrideReason(e.target.value)}
                              placeholder="Document emergency override justification…"
                            />
                            <button
                              type="button"
                              className="gold-btn compact"
                              disabled={overrideRun.isPending}
                              onClick={() =>
                                overrideRun.mutate(
                                  { id: runDetail.run.id, reason: overrideReason || 'Emergency incident recovery override' },
                                  {
                                    onSuccess: () => {
                                      setOverrideReason('');
                                      toast.success('Policy block overridden and run executed');
                                    },
                                    onError: (err) => toast.error(errorMessage(err)),
                                  },
                                )
                              }
                            >
                              Override & Execute
                            </button>
                          </div>
                        </div>
                      )}

                      <div>
                        <h4 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted">
                          Pre-Execution Policy Checks ({runDetail.run.policyChecks.length})
                        </h4>
                        <div className="grid gap-2 sm:grid-cols-2">
                          {runDetail.run.policyChecks.map((pc, idx) => (
                            <div key={idx} className="rounded border border-slate-200 p-2.5 text-xs dark:border-slate-800 flex items-start justify-between gap-2">
                              <div>
                                <b>{pc.check}</b>
                                <div className="text-[11px] text-muted">{pc.detail}</div>
                              </div>
                              <ToneBadge value={pc.passed ? 'PASS' : 'BLOCK'} label={pc.passed ? 'PASS' : 'BLOCK'} tone={pc.passed ? 'success' : 'critical'} />
                            </div>
                          ))}
                        </div>
                      </div>

                      <div>
                        <h4 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted">
                          Step Execution & Automatic Rollback Timeline ({runDetail.steps.length})
                        </h4>
                        <div className="table-wrap">
                          <table className="data-table">
                            <thead>
                              <tr>
                                <th>#</th>
                                <th>Step</th>
                                <th>Kind</th>
                                <th>Status</th>
                                <th>Attempts / Duration</th>
                                <th>Detail</th>
                              </tr>
                            </thead>
                            <tbody>
                              {runDetail.steps.map((step: RemediationRunStepDto) => (
                                <tr key={step.id}>
                                  <td>{step.stepOrder}</td>
                                  <td>
                                    <b>{step.name}</b>
                                    {step.isRollback && (
                                      <span className="ml-1.5 inline-flex items-center gap-1 text-[10px] text-amber-500">
                                        <RotateCcw size={11} /> ROLLBACK
                                      </span>
                                    )}
                                  </td>
                                  <td><code>{step.kind}</code></td>
                                  <td><ToneBadge value={step.status} label={step.status} tone={statusTone(step.status)} /></td>
                                  <td>{step.attempts} · {step.durationMs} ms</td>
                                  <td className="text-xs text-muted">
                                    {step.errorMessage ?? JSON.stringify(step.responseSummary ?? {})}
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    </div>
                  )}
                </Panel>
              </div>
            </div>
          </div>
        )}

        {tab === 'systems' && (
          <div className="space-y-4">
            {canAdminSystems && (
              <div className="flex justify-end">
                <button type="button" className="gold-btn compact" onClick={() => setShowNewSystem((v) => !v)}>
                  <Plus size={14} /> Add Execution System
                </button>
              </div>
            )}

            {showNewSystem && canAdminSystems && (
              <Panel title="Register Scoped Execution System" subtitle="Connectors remain NOT_CONNECTED until verified with a real call">
                <div className="grid gap-3 md:grid-cols-4">
                  <div>
                    <label htmlFor="sys-name" className="field-label">System Name</label>
                    <input
                      id="sys-name"
                      className="input-control"
                      value={sysName}
                      onChange={(e) => setSysName(e.target.value)}
                      placeholder="Prod Kubernetes API"
                    />
                  </div>
                  <div>
                    <label htmlFor="sys-kind" className="field-label">Connector Kind</label>
                    <select
                      id="sys-kind"
                      className="input-control"
                      value={sysKind}
                      onChange={(e) => setSysKind(e.target.value)}
                    >
                      {(metaQuery.data?.connectorKinds ?? ['HTTP_WEBHOOK', 'ANSIBLE_AWX', 'KUBERNETES', 'AWS_SSM', 'SIMULATED']).map((k) => (
                        <option key={k} value={k}>{k}</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label htmlFor="sys-endpoint" className="field-label">Endpoint URL</label>
                    <input
                      id="sys-endpoint"
                      className="input-control"
                      value={sysEndpoint}
                      onChange={(e) => setSysEndpoint(e.target.value)}
                      placeholder="https://k8s-api.internal.example"
                    />
                  </div>
                  <div className="flex items-end gap-2">
                    <div className="flex-1">
                      <label htmlFor="sys-scope" className="field-label">Credential Scope</label>
                      <input
                        id="sys-scope"
                        className="input-control"
                        value={sysScope}
                        onChange={(e) => setSysScope(e.target.value)}
                        placeholder="Namespace: payroll-prod"
                      />
                    </div>
                    <button type="button" className="gold-btn" onClick={handleCreateSystem}>
                      Save
                    </button>
                  </div>
                </div>
              </Panel>
            )}

            <Panel
              title="Execution Systems & Allow-listed Action Templates"
              subtitle="Prepared external adapters stay NOT_CONNECTED until verified; SIMULATED connectors run safely in-memory"
            >
              {systemsQuery.isLoading && <LoadingState label="Loading execution systems…" />}
              <div className="space-y-3">
                {systems.map((sys: ExecutionSystemDto) => (
                  <div key={sys.id} className="rounded-lg border border-slate-200 p-4 dark:border-slate-800 space-y-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <span className="font-semibold text-sm">{sys.name}</span>
                        <div className="text-xs text-muted">
                          Kind: <code>{sys.kind}</code> · Endpoint: <code>{sys.endpointUrl ?? 'in-memory'}</code> · Scope: {sys.credentialScope ?? 'Scoped'}
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <ToneBadge value={sys.status} label={sys.status} tone={statusTone(sys.status)} />
                        {canAdminSystems && (
                          <button
                            type="button"
                            className="subtle-btn compact"
                            disabled={testSystem.isPending}
                            onClick={() =>
                              testSystem.mutate(sys.id, {
                                onSuccess: (updated) => toast.info(`Connection test status: ${updated.status}`),
                                onError: (err) => toast.error(errorMessage(err)),
                              })
                            }
                          >
                            <RefreshCw size={13} /> Test Connection
                          </button>
                        )}
                      </div>
                    </div>
                    {sys.lastError && <div className="text-xs text-red-500">Last error: {sys.lastError}</div>}
                    {sys.actionTemplates.length > 0 && (
                      <div className="pt-1">
                        <div className="text-[11px] font-semibold text-muted mb-1">
                          Allow-listed Action Templates ({sys.actionTemplates.length}):
                        </div>
                        <div className="flex flex-wrap gap-2">
                          {sys.actionTemplates.map((tpl) => (
                            <span key={tpl.id} className="rounded border border-slate-200 px-2 py-1 text-xs dark:border-slate-700">
                              <code>{tpl.id}</code> — {tpl.name}{' '}
                              {tpl.targetScope
                                ? `(${typeof tpl.targetScope === 'string' ? tpl.targetScope : JSON.stringify(tpl.targetScope)})`
                                : ''}
                            </span>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </Panel>
          </div>
        )}

        {tab === 'audit' && (
          <div className="space-y-4">
            <Panel
              title="Tamper-Evident SHA-256 Hash Chain"
              subtitle="Append-only remediation_audit ledger protected against UPDATE/DELETE with cryptographic hash chaining"
              actions={
                <button
                  type="button"
                  className="gold-btn compact"
                  disabled={verifyAudit.isPending}
                  onClick={() =>
                    verifyAudit.mutate(undefined, {
                      onSuccess: (res) => {
                        setVerifyResult(res);
                        if (res.valid) toast.success(res.message);
                        else toast.error(res.message);
                      },
                      onError: (err) => toast.error(errorMessage(err)),
                    })
                  }
                >
                  <FileCheck2 size={14} /> Verify hash chain
                </button>
              }
            >
              {verifyResult && (
                <div className={`callout mb-4 ${verifyResult.valid ? 'green' : 'red'}`} role="status">
                  {verifyResult.valid ? <ShieldCheck size={16} /> : <ShieldAlert size={16} />}
                  <div>
                    <b>{verifyResult.valid ? 'Hash Chain Verified Intact' : 'Tamper / Broken Link Detected'}</b> —{' '}
                    {verifyResult.message} ({formatNumber(verifyResult.totalRecords)} records verified)
                  </div>
                </div>
              )}

              {auditQuery.isLoading && <LoadingState label="Loading tamper-evident audit ledger…" />}
              {auditQuery.data && auditQuery.data.length === 0 && (
                <EmptyState title="No remediation audit entries yet" />
              )}
              {auditQuery.data && auditQuery.data.length > 0 && (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>ID</th>
                        <th>Event Type</th>
                        <th>Actor</th>
                        <th>Run / Playbook</th>
                        <th>Prev Hash</th>
                        <th>SHA-256 Hash</th>
                        <th>Timestamp</th>
                      </tr>
                    </thead>
                    <tbody>
                      {auditQuery.data.map((entry: RemediationAuditDto) => (
                        <tr key={entry.id}>
                          <td>#{entry.id}</td>
                          <td><code>{entry.eventType}</code></td>
                          <td>{entry.actorName ?? 'System'}</td>
                          <td>
                            {entry.runId ? `Run #${entry.runId}` : ''}{' '}
                            {entry.playbookId ? `(PB #${entry.playbookId})` : ''}
                          </td>
                          <td><code className="text-[11px]">{entry.prevHash.slice(0, 12)}…</code></td>
                          <td><code className="text-[11px]">{entry.hash.slice(0, 16)}…</code></td>
                          <td>{formatDateTime(entry.createdAt)}</td>
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
    </>
  );
}
