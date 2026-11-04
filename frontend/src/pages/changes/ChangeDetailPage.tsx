import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { AlertTriangle, ArrowUpRight, Cpu, Link2, Pencil, Rocket, Snowflake, Sparkles, Unlink } from 'lucide-react';
import {
  useChange,
  useChangeCorrelation,
  useChangeHistory,
  useChangeReview,
  useChangeRiskAnalysis,
  useChangeTransition,
  useLinkChangeIncident,
  useRiskQuestions,
  useUnlinkChangeIncident,
} from '../../api/changes';
import { AffectedCis } from '../../components/cmdb/AffectedCis';
import { AiResultCard } from '../../components/AiResultCard';
import { UserChip } from '../../components/Avatar';
import { DemoBadge, PriorityBadge, StatusBadge } from '../../components/Badge';
import { CheckboxField, SelectField, TextareaField, TextField } from '../../components/FormField';
import { IncidentPicker } from '../../components/incidents/IncidentPicker';
import { Fact, HistoryTimeline, ServiceList, TextBlock, ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Stepper } from '../../components/Stepper';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { CHANGE_APPROVERS, CHANGE_EDITORS, STAFF } from '../../lib/constants';
import { formatDateTime, formatRelative, isoToLocalInput, localInputToIso, titleCase } from '../../lib/format';
import {
  APPROVAL_STATUS_TONES,
  CHANGE_STATUS_LABELS,
  CHANGE_STATUS_TONES,
  CHANGE_TYPE_LABELS,
  CHANGE_TYPE_TONES,
  DEPLOYMENT_STATUS_TONES,
  changeTransitionLabel,
  RISK_TONES,
  toneOf,
} from '../../lib/itsm';
import type { ChangeDetail, ChangeIncidentRelation, ChangeStatus } from '../../lib/itsmTypes';
import type { IncidentSummary } from '../../lib/types';
import { ChangeWizard } from './ChangeWizard';

const PROGRESS: { label: string; statuses: ChangeStatus[] }[] = [
  { label: 'Draft', statuses: ['DRAFT'] },
  { label: 'Submitted', statuses: ['SUBMITTED', 'ASSESSMENT'] },
  { label: 'Approval', statuses: ['AWAITING_APPROVAL', 'REJECTED'] },
  { label: 'Approved', statuses: ['APPROVED', 'SCHEDULED'] },
  { label: 'Implementing', statuses: ['IMPLEMENTING'] },
  { label: 'Closed', statuses: ['COMPLETED', 'FAILED', 'ROLLED_BACK', 'CANCELLED'] },
];
const IMPLEMENTATION: ChangeStatus[] = ['IMPLEMENTING', 'COMPLETED', 'FAILED', 'ROLLED_BACK'];
const RELATIONS: { value: ChangeIncidentRelation; label: string }[] = [
  { value: 'CAUSED_BY', label: 'Caused by this change' },
  { value: 'RESOLVES', label: 'Resolved by this change' },
  { value: 'RELATED', label: 'Related' },
];

function TransitionDialog({ change, target, onClose }: { change: ChangeDetail; target: ChangeStatus; onClose: () => void }) {
  const transition = useChangeTransition(change.id);
  const questions = useRiskQuestions(target === 'ASSESSMENT');
  const { hasRole } = useAuth();
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [outcome, setOutcome] = useState('');
  const [answers, setAnswers] = useState<Record<string, string>>(change.riskAnswers ?? {});
  const [start, setStart] = useState(isoToLocalInput(change.plannedStart));
  const [end, setEnd] = useState(isoToLocalInput(change.plannedEnd));
  const [override, setOverride] = useState(false);
  const [overrideReason, setOverrideReason] = useState('');
  const reasonRequired = target === 'CANCELLED' || (target === 'DRAFT' && change.status === 'ASSESSMENT');
  const outcomeRequired = target === 'FAILED' || target === 'ROLLED_BACK';
  const canOverride = hasRole(...CHANGE_APPROVERS);
  const conflictError = transition.isError && toApiError(transition.error).status === 409 && /conflict/i.test(errorMessage(transition.error));
  const errors = transition.isError ? toApiError(transition.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field || error.field.startsWith(`${field}.`))?.message;
  const unanswered = target === 'ASSESSMENT' && (questions.data ?? []).some((question) => !answers[question.key]);
  const blocked =
    transition.isPending ||
    (reasonRequired && reason.trim().length < 3) ||
    (outcomeRequired && !outcome.trim()) ||
    unanswered ||
    (target === 'SCHEDULED' && (!start || !end)) ||
    (override && overrideReason.trim().length < 3);

  const submit = () =>
    transition.mutate(
      {
        status: target,
        reason: reason.trim() || null,
        ...(target === 'ASSESSMENT' ? { riskAnswers: answers } : {}),
        ...(target === 'SCHEDULED' ? { plannedStart: localInputToIso(start), plannedEnd: localInputToIso(end), overrideConflicts: override || undefined, overrideReason: override ? overrideReason.trim() : null } : {}),
        ...(IMPLEMENTATION.includes(target) && target !== 'IMPLEMENTING' ? { outcomeNotes: outcome.trim() || null } : {}),
      },
      {
        onSuccess: (updated) => {
          toast.success(`${updated.number} is now ${CHANGE_STATUS_LABELS[updated.status].toLowerCase()}`);
          onClose();
        },
      },
    );

  return (
    <Modal
      open
      onClose={onClose}
      title={`${changeTransitionLabel(change.status, target)} — ${change.number}`}
      eyebrow="CHANGE LIFECYCLE"
      size={target === 'ASSESSMENT' ? 'lg' : 'md'}
      dismissible={!transition.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={transition.isPending}>
            Cancel
          </button>
          <button type="button" className={target === 'CANCELLED' || target === 'FAILED' || target === 'ROLLED_BACK' ? 'danger-btn' : 'gold-btn'} onClick={submit} disabled={blocked}>
            {transition.isPending ? 'Saving…' : changeTransitionLabel(change.status, target)}
          </button>
        </>
      }
    >
      {target === 'AWAITING_APPROVAL' && <p className="text-[11px]">Requests the CAB approval through the approval engine (flow CHANGE_NORMAL; a high-risk change adds the CAB stage).</p>}
      {target === 'ASSESSMENT' && (
        <>
          {questions.isLoading && <LoadingState variant="inline" />}
          {(questions.data ?? []).map((question) => (
            <SelectField key={question.key} label={question.text} required value={answers[question.key] ?? ''} onChange={(event) => setAnswers((current) => ({ ...current, [question.key]: event.target.value }))} error={fieldError(`riskAnswers.${question.key}`)}>
              <option value="">Choose…</option>
              {question.options.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </SelectField>
          ))}
        </>
      )}
      {target === 'SCHEDULED' && (
        <>
          <div className="form-row">
            <TextField label="Planned start" type="datetime-local" required value={start} onChange={(event) => setStart(event.target.value)} error={fieldError('plannedStart')} />
            <TextField label="Planned end" type="datetime-local" required value={end} onChange={(event) => setEnd(event.target.value)} error={fieldError('plannedEnd')} />
          </div>
          {(conflictError || change.conflicts.length > 0) && canOverride && (
            <>
              <CheckboxField label="Schedule despite the conflict (change approvers)" checked={override} onChange={(event) => setOverride(event.target.checked)} />
              {override && <TextareaField label="Reason for overriding" required rows={2} value={overrideReason} onChange={(event) => setOverrideReason(event.target.value)} error={fieldError('overrideReason')} />}
            </>
          )}
        </>
      )}
      {IMPLEMENTATION.includes(target) && target !== 'IMPLEMENTING' && (
        <TextareaField label={outcomeRequired ? 'What happened' : 'Outcome notes (optional)'} required={outcomeRequired} rows={4} value={outcome} onChange={(event) => setOutcome(event.target.value)} error={fieldError('outcomeNotes')} />
      )}
      {(reasonRequired || target === 'APPROVED') && (
        <TextareaField label={reasonRequired ? (target === 'CANCELLED' ? 'Reason for cancelling' : 'What needs rework') : 'Note (optional)'} required={reasonRequired} rows={3} maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)} error={fieldError('reason')} />
      )}
      {transition.isError && (
        <p className="field-error" role="alert">
          {errors.length ? errors.map((error) => error.message).join(' · ') : errorMessage(transition.error)}
        </p>
      )}
    </Modal>
  );
}

function RiskPanel({ change }: { change: ChangeDetail }) {
  const analysis = useChangeRiskAnalysis(change.id);
  const questions = useRiskQuestions();
  const result = analysis.data;
  return (
    <Panel
      title="Risk"
      actions={
        <button type="button" className="subtle-btn compact" onClick={() => analysis.mutate()} disabled={analysis.isPending}>
          <Sparkles size={13} aria-hidden="true" /> {analysis.isPending ? 'Analysing…' : result ? 'Analyse again' : 'SA AI risk analysis'}
        </button>
      }
    >
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <ToneBadge value={change.risk} label={`${titleCase(change.risk)} risk · score ${change.riskScore}`} tone={RISK_TONES[change.risk]} />
        <small className="inline-flex items-center gap-1 text-muted">
          <Cpu size={11} aria-hidden="true" /> Risk model{change.riskAssessedAt ? ` · assessed ${formatRelative(change.riskAssessedAt)}` : ' · not assessed yet'}
        </small>
      </div>
      {change.riskFactors.length > 0 && (
        <ul className="m-0 mb-2 grid gap-1 pl-4 text-[11px]">
          {change.riskFactors.map((factor) => (
            <li key={factor.key}>
              {factor.label} <span className="text-muted">(+{factor.points}{factor.source === 'COMPUTED' ? ', computed' : ''})</span>
            </li>
          ))}
        </ul>
      )}
      {Object.keys(change.riskAnswers ?? {}).length > 0 && questions.data && (
        <details className="mb-2 text-[11px]">
          <summary className="cursor-pointer text-muted">Questionnaire answers</summary>
          <ul className="m-0 mt-1 grid gap-0.5 pl-4">
            {questions.data.map((question) => (
              <li key={question.key}>
                {question.text}: <b>{question.options.find((option) => option.value === change.riskAnswers[question.key])?.label ?? 'Not answered'}</b>
              </li>
            ))}
          </ul>
        </details>
      )}
      {analysis.isError && <ErrorState error={analysis.error} compact />}
      {result && (
        <AiResultCard result={result} title="Change-risk analysis">
          <p className="m-0 mb-1 text-[11px]">
            Level <b>{titleCase(result.data.riskLevel)}</b> · score {result.data.score}
          </p>
          {result.data.recommendations.length > 0 && (
            <ul className="m-0 grid gap-1 pl-4 text-[11px]">
              {result.data.recommendations.map((recommendation, index) => (
                <li key={index}>{recommendation}</li>
              ))}
            </ul>
          )}
          {result.data.factors.some((factor) => factor.source === 'AI') && (
            <p className="mt-1 mb-0 text-[11px]">AI factors: {result.data.factors.filter((factor) => factor.source === 'AI').map((factor) => factor.label).join('; ')}</p>
          )}
          {result.data.similarChanges.length > 0 && (
            <p className="mt-1 mb-0 text-[11px] text-muted">
              Similar changes: {result.data.similarChanges.map((similar) => `${similar.number} (${titleCase(similar.status)})`).join(', ')}
            </p>
          )}
          <p className="mt-1 mb-0 text-[10px] text-muted">{result.data.basis}</p>
        </AiResultCard>
      )}
    </Panel>
  );
}

function ApprovalPanel({ change }: { change: ChangeDetail }) {
  const approval = change.approval;
  return (
    <Panel title="CAB approval">
      {!approval ? (
        <p className="m-0 text-[11px] text-muted">{change.type === 'STANDARD' ? 'Standard changes are pre-approved — no CAB.' : 'Not requested yet. It starts with “Request CAB approval” (emergency changes: on submission).'}</p>
      ) : (
        <>
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <ToneBadge value={approval.status} tone={toneOf(APPROVAL_STATUS_TONES, approval.status)} />
            {approval.flowName && <span className="chip">{approval.flowName}</span>}
            {approval.overdue && <ToneBadge value="overdue" label="Overdue" tone="critical" />}
          </div>
          {approval.stageCount ? (
            <div className="mb-2 overflow-x-auto">
              <Stepper
                label="CAB approval progress"
                steps={Array.from({ length: approval.stageCount }, (_, index) => (index + 1 === approval.currentStage && approval.currentStageName ? approval.currentStageName : `Stage ${index + 1}`))}
                current={Math.max(0, (approval.currentStage ?? approval.stageCount) - 1)}
                complete={approval.status === 'APPROVED'}
              />
            </div>
          ) : null}
          {approval.status === 'PENDING' && approval.pendingWith && approval.pendingWith.length > 0 && (
            <p className="m-0 text-[11px]">
              Waiting for <b>{approval.pendingWith.join(', ')}</b>
              {approval.dueAt ? ` · due ${formatDateTime(approval.dueAt)}` : ''}
            </p>
          )}
          {approval.decidedByName && (
            <p className="m-0 text-[11px]">
              {titleCase(approval.status)} by {approval.decidedByName}
              {approval.decisionComment ? ` — “${approval.decisionComment}”` : ''}
            </p>
          )}
          <Link to={`/approvals?approval=${approval.id}`} className="link-btn mt-2 text-[11px]">
            {approval.canDecide ? 'Decide in Approvals' : 'View the approval flow'} <ArrowUpRight size={12} aria-hidden="true" />
          </Link>
        </>
      )}
    </Panel>
  );
}

function IncidentsPanel({ change, canLink }: { change: ChangeDetail; canLink: boolean }) {
  const correlation = useChangeCorrelation(change.id, Boolean(change.actualStart || change.plannedStart));
  const link = useLinkChangeIncident(change.id);
  const unlink = useUnlinkChangeIncident(change.id);
  const toast = useToast();
  const [picked, setPicked] = useState<IncidentSummary | null>(null);
  const [relation, setRelation] = useState<ChangeIncidentRelation>('CAUSED_BY');
  const linkedIds = change.linkedIncidents.map((entry) => entry.incident.id);
  const correlated = (correlation.data?.correlated ?? []).filter((entry) => !entry.linked);

  return (
    <Panel title="Incidents">
      <h3 className="mb-1 text-[11px] uppercase text-gold">Linked</h3>
      {change.linkedIncidents.length === 0 ? (
        <p className="m-0 mb-2 text-[11px] text-muted">No incidents linked.</p>
      ) : (
        <ul className="m-0 mb-2 grid list-none gap-1 p-0" aria-label="Linked incidents">
          {change.linkedIncidents.map(({ relation: kind, incident }) => (
            <li key={incident.id} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  <Link to={`/incidents/${incident.id}`} className="id-cell">
                    {incident.number}
                  </Link>
                  <span className="break-words">{incident.title}</span>
                </b>
                <small>{RELATIONS.find((entry) => entry.value === kind)?.label ?? titleCase(kind)}</small>
              </div>
              <span className="flex shrink-0 items-center gap-1">
                <StatusBadge status={incident.status} />
                {canLink && (
                  <button type="button" className="icon-btn sm" aria-label={`Unlink ${incident.number}`} disabled={unlink.isPending} onClick={() => unlink.mutate(incident.id, { onSuccess: () => toast.success(`${incident.number} unlinked`), onError: (error) => toast.error(error) })}>
                    <Unlink size={13} />
                  </button>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      <h3 className="mb-1 text-[11px] uppercase text-gold">Correlated{correlation.data ? ` (within ${correlation.data.hours} h of the start)` : ''}</h3>
      {!change.actualStart && !change.plannedStart && <p className="m-0 text-[11px] text-muted">Correlation starts once a window is planned.</p>}
      {correlation.isLoading && <LoadingState variant="inline" />}
      {correlation.isError && <ErrorState error={correlation.error} compact />}
      {correlation.data && correlated.length === 0 && <p className="m-0 text-[11px] text-muted">No incidents on the changed services in that window.</p>}
      {correlated.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Correlated incidents">
          {correlated.map(({ incident, minutesAfterStart }) => (
            <li key={incident.id} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  <Link to={`/incidents/${incident.id}`} className="id-cell">
                    {incident.number}
                  </Link>
                  <span className="break-words">{incident.title}</span>
                </b>
                <small>
                  {minutesAfterStart >= 0 ? `${minutesAfterStart} min after the start` : `${-minutesAfterStart} min before the start`} · {incident.serviceName}
                </small>
              </div>
              <span className="flex shrink-0 items-center gap-1">
                <PriorityBadge priority={incident.priority} />
                {canLink && (
                  <button type="button" className="subtle-btn compact" disabled={link.isPending} onClick={() => link.mutate({ incidentId: incident.id, relation: 'CAUSED_BY' }, { onSuccess: () => toast.success(`${incident.number} linked`), onError: (error) => toast.error(error) })}>
                    <Link2 size={12} aria-hidden="true" /> Link
                  </button>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      {canLink && (
        <div className="mt-3 grid gap-2 sm:grid-cols-[1fr_auto_auto] sm:items-end">
          <IncidentPicker label="Link another incident" value={picked} onChange={setPicked} excludeIds={linkedIds} />
          <SelectField label="Relation" value={relation} onChange={(event) => setRelation(event.target.value as ChangeIncidentRelation)}>
            {RELATIONS.map((entry) => (
              <option key={entry.value} value={entry.value}>
                {entry.label}
              </option>
            ))}
          </SelectField>
          <button
            type="button"
            className="subtle-btn mb-3"
            disabled={!picked || link.isPending}
            onClick={() =>
              picked &&
              link.mutate(
                { incidentId: picked.id, relation },
                {
                  onSuccess: () => {
                    toast.success(`${picked.number} linked`);
                    setPicked(null);
                  },
                  onError: (error) => toast.error(error),
                },
              )
            }
          >
            Link
          </button>
        </div>
      )}
    </Panel>
  );
}

function ReviewPanel({ change, canReview }: { change: ChangeDetail; canReview: boolean }) {
  const review = useChangeReview(change.id);
  const toast = useToast();
  const [notes, setNotes] = useState('');
  const finished = ['COMPLETED', 'FAILED', 'ROLLED_BACK'].includes(change.status);
  if (!change.pirRequired && !change.pirNotes && !finished) return null;
  return (
    <Panel title="Post-implementation review">
      {change.pirNotes ? (
        <>
          <TextBlock text={change.pirNotes} />
          <small className="mt-1 block text-muted">
            {change.pirByName ?? 'Recorded'} · {change.pirCompletedAt ? formatDateTime(change.pirCompletedAt) : ''}
          </small>
        </>
      ) : !finished ? (
        <p className="m-0 text-[11px] text-muted">{change.pirRequired ? 'Required after implementation (emergency change).' : 'Optional after implementation.'}</p>
      ) : canReview ? (
        <>
          {change.pirRequired && <p className="m-0 mb-1 text-[11px] warning-text">A review is required for this emergency change.</p>}
          <TextareaField label="Review notes" rows={4} minLength={10} maxLength={10000} value={notes} onChange={(event) => setNotes(event.target.value)} error={review.isError ? errorMessage(review.error) : undefined} />
          <div className="flex justify-end">
            <button type="button" className="gold-btn compact" disabled={notes.trim().length < 10 || review.isPending} onClick={() => review.mutate(notes.trim(), { onSuccess: () => toast.success('Review recorded') })}>
              {review.isPending ? 'Saving…' : 'Record review'}
            </button>
          </div>
        </>
      ) : (
        <p className="m-0 text-[11px] text-muted">No review recorded yet.</p>
      )}
    </Panel>
  );
}

/** /changes/:id — state actions (API-enforced), risk + AI analysis, CAB progress, plans, window, incidents, deployments, history. */
export default function ChangeDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const valid = Number.isInteger(id) && id > 0;
  const { user, hasRole } = useAuth();
  const detail = useChange(valid ? id : null);
  const history = useChangeHistory(valid ? id : null);
  const [editing, setEditing] = useState(false);
  const [target, setTarget] = useState<ChangeStatus | null>(null);
  const change = detail.data;
  const crumbs = [{ label: 'Changes', to: '/changes' }];

  if (!valid || (detail.isError && toApiError(detail.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[...crumbs, { label: 'Not found' }]} title="Change not found" />
        <EmptyState title="This change does not exist" action={<Link to="/changes" className="subtle-btn compact">Back to changes</Link>} />
      </>
    );
  }
  if (detail.isLoading) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Loading change…" />
        <LoadingState variant="panel" />
      </>
    );
  }
  if (!change) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Change" />
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
      </>
    );
  }

  const editor = hasRole(...CHANGE_EDITORS);
  const manager = editor || (user?.role === 'INCIDENT_MANAGER' && change.type === 'EMERGENCY' && change.requesterId === user.id);
  const implementer = manager || (user != null && change.assigneeId === user.id);
  const transitions = change.allowedTransitions.filter((to) => (IMPLEMENTATION.includes(to) ? implementer : manager));
  const editable = editor && ['DRAFT', 'SUBMITTED', 'ASSESSMENT', 'REJECTED'].includes(change.status);
  const progressIndex = Math.max(0, PROGRESS.findIndex((step) => step.statuses.includes(change.status)));

  return (
    <>
      <PageHeader
        crumbs={[...crumbs, { label: change.number }]}
        title={change.title}
        actions={
          <>
            {editable && (
              <button type="button" className="subtle-btn" onClick={() => setEditing(true)}>
                <Pencil size={14} aria-hidden="true" /> Edit
              </button>
            )}
            {transitions.map((to, index) => (
              <button key={to} type="button" className={to === 'CANCELLED' ? 'danger-btn' : index === 0 ? 'gold-btn' : 'subtle-btn'} onClick={() => setTarget(to)}>
                {changeTransitionLabel(change.status, to)}
              </button>
            ))}
          </>
        }
      >
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
          <span className="id-cell">{change.number}</span>
          <ToneBadge value={change.type} label={CHANGE_TYPE_LABELS[change.type]} tone={CHANGE_TYPE_TONES[change.type]} />
          <ToneBadge value={change.status} label={CHANGE_STATUS_LABELS[change.status]} tone={CHANGE_STATUS_TONES[change.status]} />
          <ToneBadge value={change.risk} label={`${titleCase(change.risk)} risk`} tone={RISK_TONES[change.risk]} />
          <PriorityBadge priority={change.priority} />
          <DemoBadge demo={change.demo} />
          <span className="inline-flex items-center gap-1.5">
            Implementer <UserChip name={change.assigneeName} emptyLabel="Not assigned" />
          </span>
          {change.requesterName && <span>· raised by {change.requesterName}</span>}
        </div>
      </PageHeader>
      <div className="page-stack">
        <div className="overflow-x-auto">
          <Stepper label="Change progress" steps={PROGRESS.map((step) => step.label)} current={progressIndex} complete={change.status === 'COMPLETED'} />
        </div>
        {(change.conflicts.length > 0 || change.freezeWindows.length > 0) && (
          <div className="callout red" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <div>
              {change.conflicts.length > 0 && (
                <div>
                  <b>Conflicts:</b>{' '}
                  {change.conflicts.map((conflict) => (
                    <span key={conflict.changeId}>
                      <Link to={`/changes/${conflict.changeId}`}>{conflict.number}</Link> ({[...conflict.services, ...conflict.cis].join(', ')}){' '}
                    </span>
                  ))}
                </div>
              )}
              {change.freezeWindows.length > 0 && (
                <div className="inline-flex items-center gap-1">
                  <Snowflake size={12} aria-hidden="true" /> Inside freeze: {change.freezeWindows.map((freeze) => freeze.name).join(', ')}
                </div>
              )}
              {change.conflictOverrideReason && <div>Override: {change.conflictOverrideReason}</div>}
            </div>
          </div>
        )}
        <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Overview">
              <TextBlock text={change.description} empty="No description." />
              {change.justification && (
                <p className="mt-2 mb-0 text-[11px]">
                  <b>Justification:</b> {change.justification}
                </p>
              )}
              <div className="detail-grid mt-3">
                <Fact label="Services">
                  <ServiceList services={change.services} />
                </Fact>
                <Fact label="Configuration items">
                  <AffectedCis cis={change.cis} affectedCis={change.affectedCis} />
                </Fact>
                <Fact label="Impact">{change.impact ? titleCase(change.impact) : '—'}</Fact>
                <Fact label="Affected users">{String(change.affectedUsers ?? 0)}</Fact>
                <Fact label="Team">{change.teamName ?? '—'}</Fact>
                <Fact label="Category">{change.category ? titleCase(change.category) : '—'}</Fact>
                <Fact label="Template">{change.templateName ?? '—'}</Fact>
                <Fact label="Problem">{change.problemId ? <Link to={`/problems/${change.problemId}`}>{change.problemNumber}</Link> : '—'}</Fact>
              </div>
            </Panel>
            <Panel title="Plans">
              <h3 className="mb-1 text-[11px] uppercase text-gold">Implementation</h3>
              <TextBlock text={change.implementationPlan} />
              <h3 className="mt-3 mb-1 text-[11px] uppercase text-gold">Rollback</h3>
              <TextBlock text={change.rollbackPlan} />
              <h3 className="mt-3 mb-1 text-[11px] uppercase text-gold">Test</h3>
              <TextBlock text={change.testPlan} />
            </Panel>
            <Panel title="Window and implementation">
              <div className="detail-grid">
                <Fact label="Planned start">{change.plannedStart ? formatDateTime(change.plannedStart) : 'Not planned'}</Fact>
                <Fact label="Planned end">{change.plannedEnd ? formatDateTime(change.plannedEnd) : '—'}</Fact>
                <Fact label="Actual start">{change.actualStart ? formatDateTime(change.actualStart) : '—'}</Fact>
                <Fact label="Actual end">{change.actualEnd ? formatDateTime(change.actualEnd) : '—'}</Fact>
                <Fact label="Submitted">{change.submittedAt ? formatDateTime(change.submittedAt) : '—'}</Fact>
                <Fact label="Approved">{change.approvedAt ? formatDateTime(change.approvedAt) : '—'}</Fact>
              </div>
              {change.outcomeNotes && (
                <div className="mt-3">
                  <h3 className="mb-1 text-[11px] uppercase text-gold">Outcome</h3>
                  <TextBlock text={change.outcomeNotes} />
                </div>
              )}
            </Panel>
            <IncidentsPanel change={change} canLink={hasRole(...STAFF) && !['CANCELLED'].includes(change.status)} />
          </div>
          <div className="grid min-w-0 gap-[14px]">
            <ApprovalPanel change={change} />
            <RiskPanel change={change} />
            <ReviewPanel change={change} canReview={manager} />
            <Panel title="Deployments">
              {change.deployments.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No deployment references this change.</p>
              ) : (
                <ul className="m-0 grid list-none gap-1 p-0">
                  {change.deployments.map((deployment) => (
                    <li key={deployment.id} className="list-row">
                      <div className="min-w-0">
                        <b className="flex flex-wrap items-center gap-1.5">
                          <Rocket size={12} aria-hidden="true" />
                          <Link to={`/deployments/${deployment.id}`} className="id-cell">
                            {deployment.number}
                          </Link>
                          {deployment.version}
                        </b>
                        <small>
                          {titleCase(deployment.environment)} · {formatRelative(deployment.startedAt)}
                        </small>
                      </div>
                      <ToneBadge value={deployment.status} tone={toneOf(DEPLOYMENT_STATUS_TONES, deployment.status)} />
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
            <Panel title="History">
              <HistoryTimeline query={history} valueLabel={(value) => (CHANGE_STATUS_LABELS as Record<string, string>)[value] ?? titleCase(value)} />
            </Panel>
          </div>
        </div>
      </div>
      {editing && <ChangeWizard change={change} onClose={() => setEditing(false)} />}
      {target && <TransitionDialog change={change} target={target} onClose={() => setTarget(null)} />}
    </>
  );
}
