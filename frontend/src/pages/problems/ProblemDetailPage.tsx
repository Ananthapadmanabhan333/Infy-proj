import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { BookOpen, Link2, ListPlus, Pencil, Sparkles, Unlink } from 'lucide-react';
import {
  useKnownErrorArticle,
  useLinkProblemIncidents,
  useProblem,
  useProblemHistory,
  useProblemRootCause,
  useProblemTransition,
  useSaveProblemAction,
  useUnlinkProblemIncident,
} from '../../api/problems';
import { useAssignableUsers } from '../../api/users';
import { AiResultCard } from '../../components/AiResultCard';
import { ProblemCorrelationPanel } from '../../components/ai/OpsAiPanels';
import { UserChip } from '../../components/Avatar';
import { DemoBadge, PriorityBadge, StatusBadge } from '../../components/Badge';
import { CheckboxField, SelectField, TextareaField, TextField } from '../../components/FormField';
import { AffectedCis } from '../../components/cmdb/AffectedCis';
import { IncidentPicker } from '../../components/incidents/IncidentPicker';
import { Fact, HistoryTimeline, ServiceList, TextBlock, ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { KB_PUBLISHERS, PROBLEM_EDITORS, PROBLEM_OWNERS_CLOSE } from '../../lib/constants';
import { formatDate, formatDateTime, formatRelative, titleCase } from '../../lib/format';
import { ACTION_STATUSES, ACTION_STATUS_TONES, CHANGE_STATUS_LABELS, labelOf, PROBLEM_STATUS_LABELS, PROBLEM_STATUS_TONES, problemTransitionLabel } from '../../lib/itsm';
import type { ProblemActionDto, ProblemActionStatus, ProblemActionType, ProblemDetail, ProblemStatus, RootCauseSuggestion } from '../../lib/itsmTypes';
import type { AiResult, IncidentSummary } from '../../lib/types';
import { ProblemEditor } from './ProblemEditor';

function TransitionDialog({ problem, target, onClose }: { problem: ProblemDetail; target: ProblemStatus; onClose: () => void }) {
  const transition = useProblemTransition(problem.id);
  const toast = useToast();
  const [rootCause, setRootCause] = useState(problem.rootCause ?? '');
  const [workaround, setWorkaround] = useState(problem.workaround ?? '');
  const [permanentFix, setPermanentFix] = useState(problem.permanentFix ?? '');
  const [note, setNote] = useState('');
  const [publish, setPublish] = useState(false);
  const cancelling = target === 'CLOSED' && (problem.status === 'NEW' || problem.status === 'INVESTIGATING');
  const needsRootCause = target === 'ROOT_CAUSE_IDENTIFIED' || target === 'KNOWN_ERROR';
  const errors = transition.isError ? toApiError(transition.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field)?.message;
  const blocked =
    transition.isPending ||
    (needsRootCause && !rootCause.trim()) ||
    (target === 'KNOWN_ERROR' && !workaround.trim()) ||
    (target === 'RESOLVED' && !permanentFix.trim()) ||
    (cancelling && note.trim().length < 3);

  const submit = () =>
    transition.mutate(
      {
        status: target,
        note: note.trim() || null,
        ...(needsRootCause ? { rootCause: rootCause.trim() } : {}),
        ...(target === 'KNOWN_ERROR' ? { workaround: workaround.trim(), publishKnownError: publish } : {}),
        ...(target === 'RESOLVED' ? { permanentFix: permanentFix.trim() } : {}),
      },
      {
        onSuccess: (updated) => {
          toast.success(`${updated.number} is now ${PROBLEM_STATUS_LABELS[updated.status].toLowerCase()}`);
          onClose();
        },
      },
    );

  return (
    <Modal
      open
      onClose={onClose}
      title={`${problemTransitionLabel(problem.status, target)} — ${problem.number}`}
      eyebrow="PROBLEM LIFECYCLE"
      size="lg"
      dismissible={!transition.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={transition.isPending}>
            Cancel
          </button>
          <button type="button" className={cancelling ? 'danger-btn' : 'gold-btn'} onClick={submit} disabled={blocked}>
            {transition.isPending ? 'Saving…' : problemTransitionLabel(problem.status, target)}
          </button>
        </>
      }
    >
      {needsRootCause && <TextareaField label="Root cause" required rows={4} value={rootCause} onChange={(event) => setRootCause(event.target.value)} error={fieldError('rootCause')} />}
      {target === 'KNOWN_ERROR' && (
        <>
          <TextareaField label="Workaround" required rows={4} value={workaround} onChange={(event) => setWorkaround(event.target.value)} error={fieldError('workaround')} />
          <CheckboxField label="Also create the knowledge-base KNOWN_ERROR article" checked={publish} onChange={(event) => setPublish(event.target.checked)} />
        </>
      )}
      {target === 'RESOLVED' && <TextareaField label="Permanent fix" required rows={4} value={permanentFix} onChange={(event) => setPermanentFix(event.target.value)} error={fieldError('permanentFix')} />}
      <TextareaField
        label={cancelling ? 'Reason for cancelling' : 'Note (optional)'}
        required={cancelling}
        rows={3}
        maxLength={1000}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        error={fieldError('note')}
      />
      {transition.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(transition.error)}
        </p>
      )}
    </Modal>
  );
}

function ActionsPanel({ problem, canEdit }: { problem: ProblemDetail; canEdit: boolean }) {
  const save = useSaveProblemAction(problem.id);
  const staff = useAssignableUsers(null, canEdit);
  const toast = useToast();
  const [adding, setAdding] = useState(false);
  const [title, setTitle] = useState('');
  const [actionType, setActionType] = useState<ProblemActionType>('CORRECTIVE');
  const [ownerId, setOwnerId] = useState('');
  const [dueDate, setDueDate] = useState('');
  const closed = problem.status === 'CLOSED';

  const add = () =>
    save.mutate(
      { body: { title: title.trim(), actionType, ownerId: ownerId ? Number(ownerId) : null, dueDate: dueDate || null } },
      {
        onSuccess: () => {
          toast.success('Action added');
          setAdding(false);
          setTitle('');
          setOwnerId('');
          setDueDate('');
        },
      },
    );

  const setStatus = (action: ProblemActionDto, status: ProblemActionStatus) =>
    save.mutate(
      { actionId: action.id, body: { title: action.title, description: action.description, actionType: action.actionType, ownerId: action.ownerId, dueDate: action.dueDate, status } },
      { onSuccess: () => toast.success(`“${action.title}” is ${status.toLowerCase().replace('_', ' ')}`), onError: (error) => toast.error(error) },
    );

  return (
    <Panel
      title="Corrective and preventive actions"
      actions={
        canEdit && !closed && !adding ? (
          <button type="button" className="subtle-btn compact" onClick={() => setAdding(true)}>
            <ListPlus size={13} aria-hidden="true" /> Add action
          </button>
        ) : undefined
      }
    >
      {problem.actions.length === 0 && !adding && <p className="m-0 text-[11px] text-muted">No actions yet.</p>}
      {problem.actions.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Problem actions">
          {problem.actions.map((action) => (
            <li key={action.id} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="block break-words">{action.title}</b>
                <small className="block">
                  {titleCase(action.actionType)} · {action.ownerName ?? 'No owner'}
                  {action.dueDate ? <span className={action.overdue ? 'red-text' : ''}> · due {formatDate(action.dueDate)}{action.overdue ? ' (overdue)' : ''}</span> : ''}
                </small>
              </div>
              {canEdit && !closed ? (
                <select
                  className="control sm w-auto"
                  aria-label={`Status of ${action.title}`}
                  value={action.status}
                  disabled={save.isPending}
                  onChange={(event) => setStatus(action, event.target.value as ProblemActionStatus)}
                >
                  {ACTION_STATUSES.map((status) => (
                    <option key={status} value={status}>
                      {titleCase(status)}
                    </option>
                  ))}
                </select>
              ) : (
                <ToneBadge value={action.status} tone={ACTION_STATUS_TONES[action.status]} />
              )}
            </li>
          ))}
        </ul>
      )}
      {adding && (
        <div className="action-card mt-2" role="group" aria-label="New action">
          <TextField label="Action" required maxLength={200} value={title} onChange={(event) => setTitle(event.target.value)} />
          <div className="form-row three">
            <SelectField label="Type" value={actionType} onChange={(event) => setActionType(event.target.value as ProblemActionType)}>
              <option value="CORRECTIVE">Corrective</option>
              <option value="PREVENTIVE">Preventive</option>
            </SelectField>
            <SelectField label="Owner" value={ownerId} onChange={(event) => setOwnerId(event.target.value)}>
              <option value="">No owner</option>
              {(staff.data ?? []).map((person) => (
                <option key={person.id} value={person.id}>
                  {person.fullName}
                </option>
              ))}
            </SelectField>
            <TextField label="Due" type="date" value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
          </div>
          {save.isError && (
            <p className="field-error" role="alert">
              {errorMessage(save.error)}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" className="subtle-btn compact" onClick={() => setAdding(false)} disabled={save.isPending}>
              Cancel
            </button>
            <button type="button" className="gold-btn compact" onClick={add} disabled={!title.trim() || save.isPending}>
              {save.isPending ? 'Adding…' : 'Add action'}
            </button>
          </div>
        </div>
      )}
    </Panel>
  );
}

function IncidentsPanel({ problem, canEdit }: { problem: ProblemDetail; canEdit: boolean }) {
  const link = useLinkProblemIncidents(problem.id);
  const unlink = useUnlinkProblemIncident(problem.id);
  const toast = useToast();
  const [picked, setPicked] = useState<IncidentSummary | null>(null);
  const closed = problem.status === 'CLOSED';
  return (
    <Panel title={`Linked incidents (${problem.incidents.length})`}>
      {problem.incidents.length === 0 && <p className="m-0 text-[11px] text-muted">No incidents linked yet.</p>}
      {problem.incidents.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Linked incidents">
          {problem.incidents.map((incident) => (
            <li key={incident.id} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  <Link to={`/incidents/${incident.id}`} className="id-cell">
                    {incident.number}
                  </Link>
                  <span className="min-w-0 break-words">{incident.title}</span>
                </b>
                <small className="block">
                  {incident.serviceName ?? 'No service'} · {formatRelative(incident.createdAt)}
                </small>
              </div>
              <span className="flex shrink-0 items-center gap-1">
                <PriorityBadge priority={incident.priority} />
                <StatusBadge status={incident.status} />
                {canEdit && !closed && (
                  <button
                    type="button"
                    className="icon-btn sm"
                    aria-label={`Unlink ${incident.number}`}
                    disabled={unlink.isPending}
                    onClick={() => unlink.mutate(incident.id, { onSuccess: () => toast.success(`${incident.number} unlinked`), onError: (error) => toast.error(error) })}
                  >
                    <Unlink size={13} />
                  </button>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      {canEdit && !closed && (
        <div className="mt-3 flex flex-wrap items-end gap-2">
          <div className="min-w-0 flex-1">
            <IncidentPicker label="Link an incident" value={picked} onChange={setPicked} excludeIds={problem.incidents.map((incident) => incident.id)} error={link.isError ? errorMessage(link.error) : undefined} />
          </div>
          <button
            type="button"
            className="subtle-btn mb-3"
            disabled={!picked || link.isPending}
            onClick={() =>
              picked &&
              link.mutate([picked.id], {
                onSuccess: () => {
                  toast.success(`${picked.number} linked`);
                  setPicked(null);
                },
              })
            }
          >
            <Link2 size={13} aria-hidden="true" /> {link.isPending ? 'Linking…' : 'Link'}
          </button>
        </div>
      )}
    </Panel>
  );
}

function RootCauseAi({ problem }: { problem: ProblemDetail }) {
  const suggest = useProblemRootCause(problem.id);
  const result = suggest.data as AiResult<RootCauseSuggestion> | undefined;
  return (
    <Panel
      title="SA AI root-cause suggestion"
      subtitle="Suggestions only — record the confirmed root cause yourself"
      actions={
        <button type="button" className="subtle-btn compact" onClick={() => suggest.mutate()} disabled={suggest.isPending}>
          <Sparkles size={13} aria-hidden="true" /> {suggest.isPending ? 'Analysing…' : result ? 'Analyse again' : 'Suggest root cause'}
        </button>
      }
    >
      {suggest.isError && <ErrorState error={suggest.error} compact />}
      {!result && !suggest.isError && <p className="m-0 text-[11px] text-muted">Analyses the linked incidents (root causes, timing and symptoms).</p>}
      {result && (
        <AiResultCard result={result} title="Root-cause hypotheses">
          {result.data.hypotheses.length === 0 && <p className="m-0 text-[11px] text-muted">No hypothesis could be formed from the linked incidents.</p>}
          <ol className="m-0 grid gap-2 pl-4 text-[11px]">
            {result.data.hypotheses.map((hypothesis, index) => (
              <li key={index}>
                <b>{hypothesis.cause}</b> <span className="text-muted">· {titleCase(hypothesis.likelihood)} likelihood</span>
                {hypothesis.evidence.length > 0 && <div className="text-muted">Evidence: {hypothesis.evidence.join('; ')}</div>}
                {hypothesis.suggestedChecks.length > 0 && <div>Checks: {hypothesis.suggestedChecks.join('; ')}</div>}
              </li>
            ))}
          </ol>
          {result.data.commonFactors.length > 0 && <p className="mt-2 mb-0 text-[11px]">Common factors: {result.data.commonFactors.join(', ')}</p>}
          {result.data.suggestedWorkaround && <p className="mt-1 mb-0 text-[11px]">Suggested workaround: {result.data.suggestedWorkaround}</p>}
          <p className="mt-1 mb-0 text-[10px] text-muted">
            {result.data.incidentsAnalysed} incidents analysed · {result.data.basis}
          </p>
        </AiResultCard>
      )}
    </Panel>
  );
}

function KnownErrorPanel({ problem, canEdit }: { problem: ProblemDetail; canEdit: boolean }) {
  const { hasRole } = useAuth();
  const article = useKnownErrorArticle(problem.id);
  const toast = useToast();
  const create = (publish: boolean) =>
    article.mutate(publish, {
      onSuccess: (updated) => toast.success(`Knowledge article ${updated.kbArticleNumber ?? ''} ${updated.kbArticleStatus ? `(${titleCase(updated.kbArticleStatus)})` : 'created'}`),
      onError: (error) => toast.error(error),
    });
  return (
    <Panel title="Known error">
      {problem.knownError ? (
        <p className="m-0 text-[11px]">
          Recorded as a known error {problem.knownErrorAt ? formatRelative(problem.knownErrorAt) : ''}.
        </p>
      ) : (
        <p className="m-0 text-[11px] text-muted">Not a known error yet — record a workaround with the “Record known error” action.</p>
      )}
      {problem.kbArticleId ? (
        <Link to={`/knowledge/${problem.kbArticleId}`} className="link-btn mt-2 text-[11px]">
          <BookOpen size={12} aria-hidden="true" /> {problem.kbArticleNumber ?? 'Knowledge article'} {problem.kbArticleStatus ? `· ${titleCase(problem.kbArticleStatus)}` : ''}
        </Link>
      ) : (
        problem.knownError &&
        canEdit && (
          <div className="mt-2 flex flex-wrap gap-2">
            <button type="button" className="subtle-btn compact" disabled={article.isPending} onClick={() => create(false)}>
              Create KB draft
            </button>
            <button type="button" className="gold-btn compact" disabled={article.isPending} onClick={() => create(true)}>
              <BookOpen size={12} aria-hidden="true" /> {hasRole(...KB_PUBLISHERS) ? 'Publish to knowledge base' : 'Submit KB article for review'}
            </button>
          </div>
        )
      )}
    </Panel>
  );
}

/** /problems/:id — lifecycle, root cause, workaround, fix, known error, incidents, actions, AI suggestion, history. */
export default function ProblemDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const valid = Number.isInteger(id) && id > 0;
  const { hasRole } = useAuth();
  const canEdit = hasRole(...PROBLEM_EDITORS);
  const canClose = hasRole(...PROBLEM_OWNERS_CLOSE);
  const detail = useProblem(valid ? id : null);
  const history = useProblemHistory(valid ? id : null);
  const [editing, setEditing] = useState(false);
  const [target, setTarget] = useState<ProblemStatus | null>(null);
  const problem = detail.data;
  const crumbs = [{ label: 'Problems', to: '/problems' }];

  if (!valid || (detail.isError && toApiError(detail.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[...crumbs, { label: 'Not found' }]} title="Problem not found" />
        <EmptyState title="This problem does not exist" description="The link may be wrong." action={<Link to="/problems" className="subtle-btn compact">Back to problems</Link>} />
      </>
    );
  }
  if (detail.isLoading) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Loading problem…" />
        <LoadingState variant="panel" />
      </>
    );
  }
  if (!problem) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Problem" />
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
      </>
    );
  }

  const transitions = canEdit ? problem.allowedTransitions.filter((to) => to !== 'CLOSED' || canClose) : [];
  const closed = problem.status === 'CLOSED';

  return (
    <>
      <PageHeader
        crumbs={[...crumbs, { label: problem.number }]}
        title={problem.title}
        actions={
          <>
            {canEdit && !closed && (
              <button type="button" className="subtle-btn" onClick={() => setEditing(true)}>
                <Pencil size={14} aria-hidden="true" /> Edit
              </button>
            )}
            {transitions.map((to, index) => (
              <button key={to} type="button" className={to === 'CLOSED' && (problem.status === 'NEW' || problem.status === 'INVESTIGATING') ? 'danger-btn' : index === 0 ? 'gold-btn' : 'subtle-btn'} onClick={() => setTarget(to)}>
                {problemTransitionLabel(problem.status, to)}
              </button>
            ))}
          </>
        }
      >
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
          <span className="id-cell">{problem.number}</span>
          <ToneBadge value={problem.status} label={PROBLEM_STATUS_LABELS[problem.status]} tone={PROBLEM_STATUS_TONES[problem.status]} />
          <PriorityBadge priority={problem.priority} />
          {problem.knownError && <ToneBadge value="known-error" label="Known error" tone="warning" />}
          <DemoBadge demo={problem.demo} />
          <span className="inline-flex items-center gap-1.5">
            Owner <UserChip name={problem.ownerName} emptyLabel="Unowned" />
          </span>
          {problem.teamName && <span>· {problem.teamName}</span>}
        </div>
      </PageHeader>
      <div className="page-stack">
        {problem.closureNote && (
          <div className="callout" role="status">
            <div>{problem.closureNote}</div>
          </div>
        )}
        <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Overview">
              <TextBlock text={problem.description} empty="No description." />
              <div className="detail-grid mt-3">
                <Fact label="Category">{problem.category ? titleCase(problem.category) : '—'}</Fact>
                <Fact label="Services">
                  <ServiceList services={problem.services} />
                </Fact>
                <Fact label="Configuration items">
                  <AffectedCis cis={problem.cis} affectedCis={problem.affectedCis} />
                </Fact>
                <Fact label="Department">{problem.departmentName ?? '—'}</Fact>
                <Fact label="Created">{`${formatDateTime(problem.createdAt)}${problem.createdByName ? ` by ${problem.createdByName}` : ''}`}</Fact>
                <Fact label="Root cause found">{problem.rootCauseAt ? formatDateTime(problem.rootCauseAt) : '—'}</Fact>
                <Fact label="Resolved">{problem.resolvedAt ? formatDateTime(problem.resolvedAt) : '—'}</Fact>
                <Fact label="Closed">{problem.closedAt ? formatDateTime(problem.closedAt) : '—'}</Fact>
              </div>
            </Panel>
            <Panel title="Root cause">
              <TextBlock text={problem.rootCause} />
            </Panel>
            <div className="grid gap-[14px] lg:grid-cols-2">
              <Panel title="Workaround">
                <TextBlock text={problem.workaround} />
              </Panel>
              <Panel title="Permanent fix">
                <TextBlock text={problem.permanentFix} />
              </Panel>
            </div>
            <IncidentsPanel problem={problem} canEdit={canEdit} />
            <ActionsPanel problem={problem} canEdit={canEdit} />
          </div>
          <div className="grid min-w-0 gap-[14px]">
            <KnownErrorPanel problem={problem} canEdit={canEdit} />
            {canEdit && <RootCauseAi problem={problem} />}
            <ProblemCorrelationPanel problemId={problem.id} />
            <Panel title="Related changes">
              {problem.changes.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No change references this problem yet.</p>
              ) : (
                <ul className="m-0 grid list-none gap-1 p-0">
                  {problem.changes.map((change) => (
                    <li key={change.id} className="list-row">
                      <div className="min-w-0">
                        <b className="flex flex-wrap items-center gap-1.5">
                          <Link to={`/changes/${change.id}`} className="id-cell">
                            {change.number}
                          </Link>
                          <span className="break-words">{change.title}</span>
                        </b>
                        <small>
                          {titleCase(change.type)} · {labelOf(CHANGE_STATUS_LABELS, change.status)}
                        </small>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
            <Panel title="History">
              <HistoryTimeline query={history} valueLabel={(value) => (PROBLEM_STATUS_LABELS as Record<string, string>)[value] ?? titleCase(value)} />
            </Panel>
          </div>
        </div>
      </div>
      {editing && <ProblemEditor problem={problem} onClose={() => setEditing(false)} />}
      {target && <TransitionDialog problem={problem} target={target} onClose={() => setTarget(null)} />}
    </>
  );
}
