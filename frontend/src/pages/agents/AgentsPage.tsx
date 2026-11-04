import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ExternalLink,
  FlaskConical,
  Play,
  Square,
  ThumbsDown,
  ThumbsUp,
  Wrench,
} from 'lucide-react';
import {
  useAgentCatalogue,
  useAgentEvalCases,
  useAgentEvalRuns,
  useAgentGovernance,
  useAgentPolicies,
  useAgentRun,
  useAgentRuns,
  useAgentTools,
  useCancelAgentRun,
  useProposeAgentAction,
  useRunAgentEvaluations,
  useSaveAgentFeedback,
  useStartAgentRun,
  useUpdateAgentPolicy,
} from '../../api/agents';
import { useChanges } from '../../api/changes';
import { useIncidents } from '../../api/incidents';
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
import { AGENT_USERS, AI_GOVERNANCE, type Tone } from '../../lib/constants';
import { formatDateTime, formatNumber, formatPercent, formatRelative } from '../../lib/format';
import type {
  AgentActionDto,
  AgentDto,
  AgentEvalCaseDto,
  AgentEvalRunDto,
  AgentFeedbackDto,
  AgentPolicyDto,
  AgentRunSummaryDto,
  AgentRunType,
  AgentStepDto,
  AgentToolCallDto,
  AgentToolDto,
} from '../../lib/r4Types';

export type AgentTab = 'runs' | 'catalogue' | 'governance' | 'evaluations';
const TABS: AgentTab[] = ['runs', 'catalogue', 'governance', 'evaluations'];
const TAB_LABELS: Record<AgentTab, string> = {
  runs: 'Runs & Orchestration',
  catalogue: 'Agents & Read-Only Tools',
  governance: 'Governance & Policies',
  evaluations: 'Evaluation Suites',
};

const RUN_TYPES: { value: AgentRunType; label: string; description: string }[] = [
  {
    value: 'INVESTIGATE_INCIDENT',
    label: 'Investigate Incident (Multi-Agent Fan-out)',
    description:
      'Runs INCIDENT, ROOT_CAUSE, INFRA_INVESTIGATION and KNOWLEDGE in parallel, then synthesises a RESOLUTION plan.',
  },
  {
    value: 'ASSESS_CHANGE',
    label: 'Assess Change Risk',
    description: 'Evaluates blast radius, freeze windows, recent failures and rollback readiness for a change request.',
  },
  {
    value: 'ANSWER_QUESTION',
    label: 'Operational Knowledge Question',
    description: 'Searches published Knowledge Base articles, runbooks and historical incidents with grounded citations.',
  },
];

function statusTone(status: string | null | undefined): Tone {
  switch (status) {
    case 'SUCCEEDED':
    case 'COMPLETED':
    case 'APPLIED':
    case 'OK':
    case 'ACTIVE':
      return 'success';
    case 'RUNNING':
    case 'PENDING':
    case 'PENDING_APPROVAL':
    case 'PROPOSED':
      return 'warning';
    case 'FAILED':
    case 'REJECTED':
    case 'DENIED':
    case 'ERROR':
    case 'TIMED_OUT':
      return 'critical';
    default:
      return 'muted';
  }
}

export default function AgentsPage() {
  const { hasRole } = useAuth();
  const canRun = hasRole(...AGENT_USERS);
  const canGovern = hasRole(...AI_GOVERNANCE);
  const toast = useToast();
  const [tab, setTab] = useTabParam<AgentTab>(TABS, 'runs');

  const [includeSimulated, setIncludeSimulated] = useState(true);
  const [runType, setRunType] = useState<AgentRunType>('INVESTIGATE_INCIDENT');
  const [incidentId, setIncidentId] = useState<number | ''>('');
  const [changeId, setChangeId] = useState<number | ''>('');
  const [question, setQuestion] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);
  const [feedbackComment, setFeedbackComment] = useState('');

  const runsQuery = useAgentRuns({ status: statusFilter || undefined, includeSimulated, size: 25 });
  const runs = runsQuery.data?.content ?? [];
  const effectiveRunId = selectedRunId ?? runs[0]?.id ?? null;
  const runDetailQuery = useAgentRun(effectiveRunId);

  const catalogueQuery = useAgentCatalogue();
  const toolsQuery = useAgentTools();
  const policiesQuery = useAgentPolicies();
  const govQuery = useAgentGovernance(30);
  const evalCasesQuery = useAgentEvalCases();
  const evalRunsQuery = useAgentEvalRuns(20);

  const incidentsQuery = useIncidents({ page: 0, size: 25 });
  const changesQuery = useChanges({ page: 0, size: 25 });
  const incidents = incidentsQuery.data?.content ?? [];
  const changes = changesQuery.data?.content ?? [];

  const startRun = useStartAgentRun();
  const cancelRun = useCancelAgentRun();
  const proposeAction = useProposeAgentAction();
  const saveFeedback = useSaveAgentFeedback();
  const updatePolicy = useUpdateAgentPolicy();
  const runEvalSuite = useRunAgentEvaluations();

  const selectedRunMeta = useMemo(
    () => RUN_TYPES.find((r) => r.value === runType) ?? RUN_TYPES[0],
    [runType],
  );

  const needsIncident = runType === 'INVESTIGATE_INCIDENT';
  const needsChange = runType === 'ASSESS_CHANGE';

  function handleStartRun() {
    const incId = incidentId === '' ? (incidents[0]?.id ?? null) : Number(incidentId);
    const chgId = changeId === '' ? (changes[0]?.id ?? null) : Number(changeId);

    if (needsIncident && !incId) {
      toast.error('Select an incident to investigate');
      return;
    }
    if (needsChange && !chgId) {
      toast.error('Select a change request to assess');
      return;
    }
    if (runType === 'ANSWER_QUESTION' && !question.trim()) {
      toast.error('Enter a question for the Knowledge Agent');
      return;
    }

    startRun.mutate(
      {
        runType,
        incidentId: needsIncident ? incId : null,
        changeId: needsChange ? chgId : null,
        question: question.trim() || null,
        includeSimulated,
      },
      {
        onSuccess: (detail) => {
          setSelectedRunId(detail.run.id);
          toast.success(`Agent run #${detail.run.id} (${detail.run.status}) — Source: ${detail.run.source ?? 'FALLBACK'}`);
        },
        onError: (err) => toast.error(errorMessage(err)),
      },
    );
  }

  const detail = runDetailQuery.data;

  return (
    <>
      <PageHeader
        title="SA Agents"
        subtitle="Specialist operations agents with read-only user-scoped tools, schema-validated outputs, deterministic fallbacks and approval-gated action proposals."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <IncludeSimulatedToggle value={includeSimulated} onChange={setIncludeSimulated} />
            {govQuery.data && (
              <ToneBadge
                value="GOV"
                label={`${formatNumber(govQuery.data.totalRuns)} runs (30d) · ${formatNumber(govQuery.data.fallbackSteps)} fallback steps`}
                tone="gold"
              />
            )}
          </div>
        }
      />

      <div className="page-stack">
        <Tabs<AgentTab>
          label="SA Agentic AI views"
          value={tab}
          onChange={setTab}
          tabs={TABS.map((id) => ({ id, label: TAB_LABELS[id] }))}
        />

        {tab === 'runs' && (
          <div className="space-y-6">
            {canRun && (
              <Panel
                title="Start an Agent Investigation or Assessment"
                subtitle={selectedRunMeta.description}
                actions={
                  <ToneBadge
                    value="SAFE"
                    label="Read-only user-scoped tools · AI_ACTION approvals"
                    tone="muted"
                  />
                }
              >
                <div className="grid gap-3 md:grid-cols-4">
                  <div>
                    <label htmlFor="agent-run-type" className="field-label">
                      Workflow / specialist agent
                    </label>
                    <select
                      id="agent-run-type"
                      className="input-control"
                      value={runType}
                      onChange={(e) => setRunType(e.target.value as AgentRunType)}
                    >
                      {RUN_TYPES.map((rt) => (
                        <option key={rt.value} value={rt.value}>
                          {rt.label}
                        </option>
                      ))}
                    </select>
                  </div>

                  {needsIncident && (
                    <div>
                      <label htmlFor="agent-incident" className="field-label">
                        Target incident
                      </label>
                      <select
                        id="agent-incident"
                        className="input-control"
                        value={incidentId}
                        onChange={(e) => setIncidentId(e.target.value ? Number(e.target.value) : '')}
                      >
                        {incidents.map((inc) => (
                          <option key={inc.id} value={inc.id}>
                            {inc.number} · {inc.priority} · {inc.title}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}

                  {needsChange && (
                    <div>
                      <label htmlFor="agent-change" className="field-label">
                        Target change request
                      </label>
                      <select
                        id="agent-change"
                        className="input-control"
                        value={changeId}
                        onChange={(e) => setChangeId(e.target.value ? Number(e.target.value) : '')}
                      >
                        {changes.map((c) => (
                          <option key={c.id} value={c.id}>
                            {c.number} · {c.type} · {c.title}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}

                  <div className="md:col-span-2">
                    <label htmlFor="agent-question" className="field-label">
                      Operator question / context {runType === 'ANSWER_QUESTION' ? '(required)' : '(optional)'}
                    </label>
                    <div className="flex gap-2">
                      <input
                        id="agent-question"
                        type="text"
                        className="input-control flex-1"
                        value={question}
                        onChange={(e) => setQuestion(e.target.value)}
                        placeholder="e.g. What caused the Payroll DB connection pool saturation and how do we recover?"
                      />
                      <button
                        type="button"
                        className="gold-btn inline-flex items-center gap-1.5"
                        onClick={handleStartRun}
                        disabled={startRun.isPending}
                      >
                        <Play className="h-4 w-4" />
                        {startRun.isPending ? 'Running…' : 'Start Run'}
                      </button>
                    </div>
                  </div>
                </div>
              </Panel>
            )}

            <div className="grid gap-6 lg:grid-cols-12">
              <div className="lg:col-span-4">
                <Panel
                  title="Agent Runs"
                  subtitle="Click a run to inspect its steps, tool calls and proposed actions"
                  actions={
                    <select
                      aria-label="Filter runs by status"
                      className="input-control text-xs py-1"
                      value={statusFilter}
                      onChange={(e) => setStatusFilter(e.target.value)}
                    >
                      <option value="">All statuses</option>
                      <option value="SUCCEEDED">SUCCEEDED</option>
                      <option value="RUNNING">RUNNING</option>
                      <option value="FAILED">FAILED</option>
                      <option value="CANCELLED">CANCELLED</option>
                    </select>
                  }
                >
                  {runsQuery.isLoading && <LoadingState label="Loading agent runs…" />}
                  {runsQuery.isError && <ErrorState error={runsQuery.error} />}
                  {!runsQuery.isLoading && runs.length === 0 && (
                    <EmptyState title="No agent runs yet" description="Start an investigation or change assessment above." />
                  )}
                  {runs.length > 0 && (
                    <div className="space-y-2 max-h-[640px] overflow-y-auto pr-1">
                      {runs.map((r: AgentRunSummaryDto) => {
                        const active = r.id === effectiveRunId;
                        return (
                          <button
                            key={r.id}
                            type="button"
                            onClick={() => setSelectedRunId(r.id)}
                            className={`w-full text-left rounded-lg border p-3 transition ${
                              active
                                ? 'border-amber-500 bg-amber-50/30 dark:bg-amber-950/20'
                                : 'border-slate-200 hover:border-slate-300 dark:border-slate-800'
                            }`}
                          >
                            <div className="flex items-center justify-between gap-2">
                              <span className="font-mono text-xs font-semibold">
                                #{r.id} · {r.runType}
                              </span>
                              <div className="flex items-center gap-1.5">
                                <SimulatedBadge simulated={r.simulated} />
                                {r.source && (
                                  <ToneBadge
                                    value={r.source}
                                    label={r.source}
                                    tone={r.source === 'LLM' ? 'gold' : 'muted'}
                                  />
                                )}
                                <ToneBadge value={r.status} label={r.status} tone={statusTone(r.status)} />
                              </div>
                            </div>
                            <p className="mt-1 text-xs text-slate-600 dark:text-slate-300 line-clamp-2">
                              {r.summary || r.subjectTitle || r.question || r.subjectNumber || `Run #${r.id}`}
                            </p>
                            <div className="mt-1.5 flex items-center justify-between text-[11px] text-slate-500">
                              <span>
                                {r.stepCount} steps · {r.actionCount} actions · {r.durationMs ?? 0} ms
                              </span>
                              <span>{formatRelative(r.createdAt)}</span>
                            </div>
                          </button>
                        );
                      })}
                    </div>
                  )}
                </Panel>
              </div>

              <div className="lg:col-span-8">
                <Panel
                  title={detail ? `Run #${detail.run.id} · ${detail.run.runType}` : 'Run Timeline & Evidence'}
                  subtitle={
                    detail
                      ? `Started ${formatDateTime(detail.run.startedAt || detail.run.createdAt)} · ${detail.steps.length} specialist steps · ${detail.actions.length} proposed actions`
                      : 'Select an agent run on the left'
                  }
                  actions={
                    detail ? (
                      <div className="flex items-center gap-2">
                        <SimulatedBadge simulated={detail.run.simulated} />
                        <ToneBadge
                          value={detail.run.source ?? 'FALLBACK'}
                          label={`Source: ${detail.run.source ?? 'FALLBACK'}`}
                          tone={detail.run.source === 'LLM' ? 'gold' : 'muted'}
                        />
                        <ToneBadge
                          value={detail.run.status}
                          label={detail.run.status}
                          tone={statusTone(detail.run.status)}
                        />
                        {(detail.run.status === 'RUNNING' || detail.run.status === 'PENDING') && canRun && (
                          <button
                            type="button"
                            className="subtle-btn compact inline-flex items-center gap-1"
                            onClick={() =>
                              cancelRun.mutate(detail.run.id, {
                                onSuccess: () => toast.info('Cancellation requested'),
                              })
                            }
                          >
                            <Square className="h-3.5 w-3.5" /> Cancel
                          </button>
                        )}
                      </div>
                    ) : undefined
                  }
                >
                  {!detail ? (
                    <EmptyState
                      title="No run selected"
                      description="Choose a run from the list or start a new investigation above."
                    />
                  ) : (
                    <div className="space-y-6">
                      {detail.run.summary && (
                        <div className="rounded-lg border border-slate-200 bg-slate-50/70 p-4 dark:border-slate-800 dark:bg-slate-800/40">
                          <div className="flex items-center justify-between text-xs text-slate-500 mb-1">
                            <span className="font-semibold uppercase tracking-wider">
                              Synthesised Assessment ({detail.run.source ?? 'FALLBACK'})
                            </span>
                            {detail.run.confidence !== null && (
                              <span>Confidence: {formatPercent(detail.run.confidence * 100)}</span>
                            )}
                          </div>
                          <p className="text-sm text-slate-800 dark:text-slate-200">{detail.run.summary}</p>
                          {detail.run.subjectType === 'INCIDENT' && detail.run.subjectId && (
                            <div className="mt-2">
                              <Link
                                to={`/incidents/${detail.run.subjectId}`}
                                className="inline-flex items-center gap-1 text-xs font-medium text-sky-600 hover:underline dark:text-sky-400"
                              >
                                Subject Incident {detail.run.subjectNumber ?? `#${detail.run.subjectId}`}{' '}
                                <ExternalLink className="h-3 w-3" />
                              </Link>
                            </div>
                          )}
                        </div>
                      )}

                      {/* Proposed Actions */}
                      <div className="space-y-3">
                        <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                          Proposed Actions ({detail.actions.length}) — Gated by Approval Engine (AI_ACTION)
                        </h4>
                        {detail.actions.length === 0 ? (
                          <p className="text-xs text-slate-500">No state-changing actions proposed in this run.</p>
                        ) : (
                          <div className="grid gap-3 md:grid-cols-2">
                            {detail.actions.map((act: AgentActionDto) => (
                              <div
                                key={act.id}
                                className="rounded-lg border border-slate-200 p-3.5 dark:border-slate-800 flex flex-col justify-between gap-2"
                              >
                                <div>
                                  <div className="flex items-center justify-between gap-2">
                                    <span className="font-mono text-xs font-semibold">{act.kind}</span>
                                    <ToneBadge value={act.status} label={act.status} tone={statusTone(act.status)} />
                                  </div>
                                  <div className="mt-1 text-sm font-medium">{act.title}</div>
                                  {act.rationale && (
                                    <p className="mt-1 text-xs text-slate-600 dark:text-slate-400">{act.rationale}</p>
                                  )}
                                </div>
                                <div className="mt-2 flex items-center justify-between border-t border-slate-100 pt-2 dark:border-slate-800">
                                  <span className="text-[11px] text-slate-500">
                                    Target: {act.targetType} {act.targetId ? `#${act.targetId}` : ''}
                                  </span>
                                  <div className="flex items-center gap-2">
                                    {act.approvalId && (
                                      <Link
                                        to="/approvals"
                                        className="inline-flex items-center gap-1 text-xs font-medium text-sky-600 hover:underline dark:text-sky-400"
                                      >
                                        Approval #{act.approvalId} <ExternalLink className="h-3 w-3" />
                                      </Link>
                                    )}
                                    {act.status === 'PROPOSED' && canRun && (
                                      <button
                                        type="button"
                                        className="gold-btn compact"
                                        disabled={proposeAction.isPending}
                                        onClick={() =>
                                          proposeAction.mutate(
                                            { runId: detail.run.id, actionId: act.id },
                                            {
                                              onSuccess: (updated) =>
                                                toast.success(
                                                  `Submitted to Approval Engine (Approval #${updated.approvalId ?? 'created'})`,
                                                ),
                                              onError: (err) => toast.error(errorMessage(err)),
                                            },
                                          )
                                        }
                                      >
                                        Submit for Approval
                                      </button>
                                    )}
                                  </div>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>

                      {/* Specialist Steps & Tool Calls */}
                      <div className="space-y-3">
                        <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                          Specialist Steps & Read-Only Tool Calls ({detail.steps.length} steps)
                        </h4>
                        <div className="space-y-3">
                          {detail.steps.map((step: AgentStepDto) => (
                            <div key={step.id} className="rounded-lg border border-slate-200 p-3.5 dark:border-slate-800 space-y-2">
                              <div className="flex flex-wrap items-center justify-between gap-2">
                                <div className="flex items-center gap-2">
                                  <span className="font-mono text-xs font-semibold">
                                    Stage {step.stage} · #{step.seq} {step.agentCode} (v{step.agentVersion})
                                  </span>
                                </div>
                                <div className="flex items-center gap-2 text-xs">
                                  <ToneBadge
                                    value={step.source ?? 'FALLBACK'}
                                    label={step.source ?? 'FALLBACK'}
                                    tone={step.source === 'LLM' ? 'gold' : 'muted'}
                                  />
                                  <ToneBadge value={step.status} label={step.status} tone={statusTone(step.status)} />
                                  <span className="text-slate-500">{step.durationMs ?? 0} ms</span>
                                </div>
                              </div>

                              {step.fallbackReason && (
                                <p className="text-[11px] text-amber-700 dark:text-amber-300">
                                  Fallback reason: {step.fallbackReason}
                                </p>
                              )}

                              {typeof step.output?.summary === 'string' && (
                                <p className="text-xs text-slate-700 dark:text-slate-300">{step.output.summary}</p>
                              )}

                              {step.evidence.length > 0 && (
                                <div className="flex flex-wrap gap-1.5 pt-1">
                                  {step.evidence.map((ev, idx) => (
                                    <span
                                      key={`${ev.type}-${ev.id}-${idx}`}
                                      className="rounded border border-slate-200 bg-slate-50 px-2 py-0.5 font-mono text-[11px] dark:border-slate-700 dark:bg-slate-800"
                                    >
                                      {ev.type}:{ev.id} {ev.label ? `(${ev.label})` : ''}
                                    </span>
                                  ))}
                                </div>
                              )}

                              {step.toolCalls.length > 0 && (
                                <div className="mt-2 space-y-1.5 border-t border-slate-100 pt-2 dark:border-slate-800">
                                  <div className="text-[11px] font-medium text-slate-500">
                                    Read-only Tool Calls ({step.toolCalls.length}):
                                  </div>
                                  <div className="flex flex-wrap gap-1.5">
                                    {step.toolCalls.map((tc: AgentToolCallDto) => (
                                      <span
                                        key={tc.id}
                                        title={JSON.stringify(tc.args)}
                                        className="inline-flex items-center gap-1 rounded border border-slate-200 bg-slate-50 px-2 py-0.5 font-mono text-[11px] dark:border-slate-700 dark:bg-slate-800"
                                      >
                                        <Wrench className="h-3 w-3" />
                                        {tc.tool} ({tc.status} · {tc.durationMs}ms)
                                      </span>
                                    ))}
                                  </div>
                                </div>
                              )}
                            </div>
                          ))}
                        </div>
                      </div>

                      {/* Operator Feedback */}
                      <div className="border-t border-slate-200 pt-4 dark:border-slate-800 space-y-3">
                        <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                          Operator Feedback ({detail.feedback.length})
                        </h4>
                        {canRun && (
                          <div className="flex flex-wrap items-center gap-2">
                            <input
                              type="text"
                              className="input-control flex-1 min-w-[200px] text-xs"
                              value={feedbackComment}
                              onChange={(e) => setFeedbackComment(e.target.value)}
                              placeholder="Add operator note on accuracy or usefulness…"
                            />
                            <button
                              type="button"
                              className="subtle-btn compact inline-flex items-center gap-1"
                              onClick={() =>
                                saveFeedback.mutate(
                                  { runId: detail.run.id, helpful: true, comment: feedbackComment || null },
                                  {
                                    onSuccess: () => {
                                      setFeedbackComment('');
                                      toast.success('Feedback recorded (Helpful)');
                                    },
                                  },
                                )
                              }
                            >
                              <ThumbsUp className="h-3.5 w-3.5" /> Helpful
                            </button>
                            <button
                              type="button"
                              className="subtle-btn compact inline-flex items-center gap-1"
                              onClick={() =>
                                saveFeedback.mutate(
                                  { runId: detail.run.id, helpful: false, comment: feedbackComment || null },
                                  {
                                    onSuccess: () => {
                                      setFeedbackComment('');
                                      toast.info('Feedback recorded (Not helpful)');
                                    },
                                  },
                                )
                              }
                            >
                              <ThumbsDown className="h-3.5 w-3.5" /> Not Helpful
                            </button>
                          </div>
                        )}
                        {detail.feedback.length > 0 && (
                          <div className="space-y-1.5">
                            {detail.feedback.map((f: AgentFeedbackDto) => (
                              <div key={f.id} className="flex items-center justify-between text-xs text-slate-600 dark:text-slate-400">
                                <span>
                                  <strong>{f.helpful ? 'HELPFUL' : 'NOT_HELPFUL'}</strong> by {f.userName ?? 'Operator'}
                                  {f.comment ? ` — ${f.comment}` : ''}
                                </span>
                                <span>{formatRelative(f.createdAt)}</span>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </Panel>
              </div>
            </div>
          </div>
        )}

        {tab === 'catalogue' && (
          <div className="grid gap-6 lg:grid-cols-2">
            <Panel
              title={`Specialist Agents (${catalogueQuery.data?.length ?? 0})`}
              subtitle="Defined specialist agents with JSON-schema-validated outputs and deterministic fallbacks"
            >
              {catalogueQuery.isLoading && <LoadingState label="Loading agent catalogue…" />}
              <div className="space-y-3">
                {(catalogueQuery.data ?? []).map((agent: AgentDto) => (
                  <div key={agent.code} className="rounded-lg border border-slate-200 p-3.5 dark:border-slate-800 space-y-1.5">
                    <div className="flex items-center justify-between">
                      <span className="font-semibold text-sm">{agent.name}</span>
                      <div className="flex items-center gap-1.5">
                        <ToneBadge value={agent.code} label={`${agent.code} v${agent.version}`} tone="gold" />
                        <ToneBadge
                          value={agent.enabled ? 'ENABLED' : 'DISABLED'}
                          label={agent.enabled ? 'ENABLED' : 'DISABLED'}
                          tone={agent.enabled ? 'success' : 'muted'}
                        />
                      </div>
                    </div>
                    <p className="text-xs text-slate-600 dark:text-slate-400">{agent.description}</p>
                    <div className="flex flex-wrap gap-1 pt-1">
                      {agent.allowedTools.map((t: string) => (
                        <span
                          key={t}
                          className="rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 font-mono text-[10px] dark:border-slate-700 dark:bg-slate-800"
                        >
                          {t}
                        </span>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </Panel>

            <Panel
              title={`Read-Only User-Scoped Tools (${toolsQuery.data?.length ?? 0})`}
              subtitle="Every tool executes read-only under the requesting user's RBAC permissions"
            >
              {toolsQuery.isLoading && <LoadingState label="Loading tools…" />}
              <div className="space-y-2.5 max-h-[640px] overflow-y-auto pr-1">
                {(toolsQuery.data ?? []).map((tool: AgentToolDto) => (
                  <div key={tool.name} className="rounded-lg border border-slate-200 p-3 dark:border-slate-800">
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-xs font-semibold">{tool.name}</span>
                      <ToneBadge value="READ_ONLY" label="READ-ONLY" tone="success" />
                    </div>
                    <p className="mt-1 text-xs text-slate-600 dark:text-slate-400">{tool.description}</p>
                  </div>
                ))}
              </div>
            </Panel>
          </div>
        )}

        {tab === 'governance' && (
          <div className="space-y-6">
            {govQuery.data && (
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                <Panel title="Runs (30 days)">
                  <div className="text-2xl font-bold">{formatNumber(govQuery.data.totalRuns)}</div>
                  <div className="mt-1 text-xs text-slate-500">
                    {formatNumber(govQuery.data.succeededRuns)} succeeded · {formatNumber(govQuery.data.failedRuns)} failed
                  </div>
                </Panel>
                <Panel title="LLM vs Fallback Steps">
                  <div className="text-2xl font-bold">
                    {formatNumber(govQuery.data.llmSteps)} / {formatNumber(govQuery.data.fallbackSteps)}
                  </div>
                  <div className="mt-1 text-xs text-slate-500">
                    {formatNumber(govQuery.data.toolCalls)} tool calls ({govQuery.data.deniedToolCalls} denied)
                  </div>
                </Panel>
                <Panel title="Approval-Gated Actions">
                  <div className="text-2xl font-bold">
                    {formatNumber(govQuery.data.appliedActions)} / {formatNumber(govQuery.data.proposedActions)}
                  </div>
                  <div className="mt-1 text-xs text-slate-500">
                    {formatNumber(govQuery.data.rejectedActions)} rejected via Approval Engine
                  </div>
                </Panel>
                <Panel title="Operator Feedback & Eval">
                  <div className="text-2xl font-bold">
                    {govQuery.data.helpfulCount} / {govQuery.data.helpfulCount + govQuery.data.notHelpfulCount}
                  </div>
                  <div className="mt-1 text-xs text-slate-500">
                    Last eval score: {govQuery.data.lastEvalScore != null ? formatPercent(govQuery.data.lastEvalScore * 100) : '—'}
                  </div>
                </Panel>
              </div>
            )}

            <Panel
              title="Per-Agent Governance Policies"
              subtitle="Configure tool allow-lists, step limits, timeouts and enablement per specialist agent"
            >
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Agent</th>
                      <th>Status</th>
                      <th>Allowed Tools</th>
                      <th>Max Steps</th>
                      <th>Timeout (s)</th>
                      <th>Telemetry Window</th>
                      {canGovern && <th>Action</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {(policiesQuery.data ?? []).map((p: AgentPolicyDto) => (
                      <tr key={p.agentCode}>
                        <td className="font-mono font-semibold">{p.agentCode}</td>
                        <td>
                          <ToneBadge
                            value={p.enabled ? 'ENABLED' : 'DISABLED'}
                            label={p.enabled ? 'ENABLED' : 'DISABLED'}
                            tone={p.enabled ? 'success' : 'muted'}
                          />
                        </td>
                        <td>{p.allowedTools.length} tools</td>
                        <td className="font-mono">{p.maxSteps}</td>
                        <td className="font-mono">{p.timeoutSeconds}s</td>
                        <td className="font-mono">{p.telemetryMinutes}m</td>
                        {canGovern && (
                          <td>
                            <button
                              type="button"
                              className="subtle-btn compact"
                              onClick={() =>
                                updatePolicy.mutate(
                                  {
                                    code: p.agentCode,
                                    body: {
                                      enabled: !p.enabled,
                                      allowedTools: p.allowedTools,
                                      maxSteps: p.maxSteps,
                                      timeoutSeconds: p.timeoutSeconds,
                                      telemetryMinutes: p.telemetryMinutes,
                                      historyDays: p.historyDays,
                                      allowedRoles: p.allowedRoles,
                                      approvalActionKinds: p.approvalActionKinds,
                                    },
                                  },
                                  {
                                    onSuccess: (u) => toast.success(`${u.agentCode} policy updated (enabled=${u.enabled})`),
                                    onError: (err) => toast.error(errorMessage(err)),
                                  },
                                )
                              }
                            >
                              {p.enabled ? 'Disable' : 'Enable'}
                            </button>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Panel>
          </div>
        )}

        {tab === 'evaluations' && (
          <div className="space-y-6">
            <Panel
              title={`Agent Evaluation Suite (${evalCasesQuery.data?.length ?? 0} benchmark cases)`}
              subtitle="Deterministic benchmark cases verify tool selection, evidence citations and schema compliance"
              actions={
                canGovern ? (
                  <button
                    type="button"
                    className="gold-btn inline-flex items-center gap-1.5 text-xs"
                    disabled={runEvalSuite.isPending}
                    onClick={() =>
                      runEvalSuite.mutate(null, {
                        onSuccess: (res) =>
                          toast.success(
                            `Evaluation suite #${res.id}: ${res.passed}/${res.caseCount} passed (score ${formatPercent((res.score ?? 0) * 100)})`,
                          ),
                        onError: (err) => toast.error(errorMessage(err)),
                      })
                    }
                  >
                    <FlaskConical className="h-4 w-4" />
                    {runEvalSuite.isPending ? 'Evaluating…' : 'Run Evaluation Suite'}
                  </button>
                ) : undefined
              }
            >
              <div className="grid gap-6 lg:grid-cols-2">
                <div className="space-y-2.5">
                  <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500">Benchmark Cases</h4>
                  {(evalCasesQuery.data ?? []).map((c: AgentEvalCaseDto) => (
                    <div key={c.id} className="rounded-lg border border-slate-200 p-3 dark:border-slate-800">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-semibold">{c.name}</span>
                        <ToneBadge value={c.runType} label={`${c.runType} · ${c.agentCode}`} tone="gold" />
                      </div>
                      {c.description && <p className="mt-1 text-xs text-slate-500">{c.description}</p>}
                    </div>
                  ))}
                </div>

                <div className="space-y-2.5">
                  <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-500">Evaluation Suite Runs</h4>
                  {(evalRunsQuery.data ?? []).length === 0 ? (
                    <EmptyState
                      title="No evaluation suite runs yet"
                      description="Run the benchmark evaluation suite to score specialist agents."
                    />
                  ) : (
                    (evalRunsQuery.data ?? []).map((er: AgentEvalRunDto) => (
                      <div key={er.id} className="rounded-lg border border-slate-200 p-3 dark:border-slate-800">
                        <div className="flex items-center justify-between">
                          <span className="font-mono text-xs font-semibold">
                            Suite #{er.id} · {er.passed}/{er.caseCount} passed
                          </span>
                          <ToneBadge
                            value={er.status}
                            label={`Score: ${er.score != null ? formatPercent(er.score * 100) : '—'}`}
                            tone={statusTone(er.status)}
                          />
                        </div>
                        <div className="mt-1 text-[11px] text-slate-500">
                          Started {formatRelative(er.startedAt)} · {er.durationMs ?? 0} ms
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>
            </Panel>
          </div>
        )}
      </div>
    </>
  );
}
