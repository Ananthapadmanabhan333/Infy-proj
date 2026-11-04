import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowUpRight, Check, Clock3, Forward, Layers, X } from 'lucide-react';
import { useApprovalDetail, useDecideTask, useDelegateTask } from '../../api/approvals';
import { DemoBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Drawer } from '../../components/Drawer';
import { TextareaField } from '../../components/FormField';
import { ToneBadge, UserSearchPicker } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { APPROVAL_TYPE_LABELS } from '../../lib/constants';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import {
  APPROVAL_EVENT_LABELS,
  APPROVAL_STATUS_TONES,
  STAGE_MODE_LABELS,
  STAGE_STATUS_TONES,
  TASK_STATUS_TONES,
  quorumLabel,
  toneOf,
} from '../../lib/itsm';
import type { ApprovalStageDto, ApprovalTaskDto, UserOption } from '../../lib/itsmTypes';
import type { ApprovalDto } from '../../lib/types';

/** In-app route of an approval's subject: the API `link`, else a sensible page per type. */
export function approvalSubjectLink(approval: Pick<ApprovalDto, 'type' | 'referenceId' | 'link'>): string | null {
  if (approval.link && approval.link.startsWith('/')) return approval.link;
  switch (approval.type) {
    case 'SERVICE_REQUEST':
      return `/service-desk?request=${approval.referenceId}`;
    case 'CHANGE':
      return `/changes/${approval.referenceId}`;
    case 'RUNBOOK_EXECUTION':
    case 'RUNBOOK_STEP':
      return '/runbooks';
    default:
      return null;
  }
}

export function approvalTypeLabel(type: string): string {
  return (APPROVAL_TYPE_LABELS as Record<string, string>)[type] ?? titleCase(type);
}

function conditionText(stage: ApprovalStageDto): string | null {
  if (!stage.condition?.rules?.length) return null;
  const joiner = stage.condition.match === 'ANY' ? ' or ' : ' and ';
  return stage.condition.rules.map((rule) => `${rule.field} ${rule.operator === 'EQUALS' ? '=' : rule.operator.toLowerCase().replace('_', ' ')} ${rule.value ?? ''}`.trim()).join(joiner);
}

function TaskRow({ task }: { task: ApprovalTaskDto }) {
  return (
    <li className="flex flex-wrap items-start justify-between gap-2 rounded-[8px] border border-line px-2.5 py-2">
      <div className="min-w-0">
        <b className="block text-[11px]">{task.approverLabel}</b>
        {task.delegatedFromName && <small className="block text-muted">Delegated by {task.delegatedFromName}{task.delegatedAt ? ` · ${formatRelative(task.delegatedAt)}` : ''}</small>}
        {task.decidedByName && (
          <small className="block text-muted">
            {task.status === 'APPROVED' ? 'Approved' : task.status === 'REJECTED' ? 'Rejected' : 'Decided'} by {task.decidedByName}
            {task.decidedAt ? ` · ${formatDateTime(task.decidedAt)}` : ''}
          </small>
        )}
        {task.comment && <small className="block whitespace-pre-wrap break-words">“{task.comment}”</small>}
      </div>
      <ToneBadge value={task.status} tone={toneOf(TASK_STATUS_TONES, task.status)} />
    </li>
  );
}

function StageCard({ stage, current }: { stage: ApprovalStageDto; current: boolean }) {
  const condition = conditionText(stage);
  return (
    <li className={`rounded-[10px] border p-3 ${current ? 'border-[#8a6d43] bg-[#1d1914]' : 'border-line'}`} aria-label={`Stage ${stage.order}: ${stage.name}`}>
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <b className="text-[12px]">
          {stage.order}. {stage.name}
        </b>
        <ToneBadge value={stage.status} tone={toneOf(STAGE_STATUS_TONES, stage.status)} />
      </div>
      <div className="mb-2 flex flex-wrap gap-1.5 text-[10px]">
        <span className="chip">{STAGE_MODE_LABELS[stage.mode] ?? stage.mode}</span>
        <span className="chip">
          Quorum: {quorumLabel(stage.quorum, stage.requiredCount, stage.tasks.length)} · {stage.approvedCount} approved
        </span>
        {stage.deadlineHours ? <span className="chip">Deadline {stage.deadlineHours} h</span> : null}
        {stage.dueAt && (
          <span className="chip" title={formatDateTime(stage.dueAt)}>
            <Clock3 size={10} aria-hidden="true" /> Due {formatRelative(stage.dueAt)}
          </span>
        )}
        {stage.escalatedAt && <span className="chip red-text">Escalated {formatRelative(stage.escalatedAt)}</span>}
        {stage.escalation?.autoReject && <span className="chip">Auto-reject when overdue</span>}
        {condition && <span className="chip">Only when {condition}</span>}
      </div>
      <ul className="m-0 grid list-none gap-1.5 p-0">
        {stage.tasks.map((task) => (
          <TaskRow key={task.id} task={task} />
        ))}
      </ul>
    </li>
  );
}

/** Approval detail: subject, flow (stages, mode, quorum, approvers, decisions, deadlines) and history; decide or delegate my task. */
export function ApprovalDrawer({ approvalId, taskId, onClose }: { approvalId: number | null; taskId?: number | null; onClose: () => void }) {
  const detail = useApprovalDetail(approvalId);
  const decide = useDecideTask();
  const delegate = useDelegateTask();
  const toast = useToast();
  const [decision, setDecision] = useState<boolean | null>(null);
  const [delegating, setDelegating] = useState(false);
  const [delegateTo, setDelegateTo] = useState<UserOption | null>(null);
  const [delegateNote, setDelegateNote] = useState('');

  const data = detail.data;
  const approval = data?.approval;
  const decidableTask = data?.stages.flatMap((stage) => stage.tasks).find((task) => task.canDecide && (!taskId || task.id === taskId)) ?? data?.stages.flatMap((stage) => stage.tasks).find((task) => task.canDecide);
  const myTaskId = decidableTask?.id ?? (approval?.canDecide ? approval.myTaskId ?? null : null);
  const subject = approval ? approvalSubjectLink(approval) : null;

  const submitDecision = (comment: string) => {
    if (!myTaskId || decision === null) return;
    decide.mutate(
      { taskId: myTaskId, approved: decision, comment },
      {
        onSuccess: (result) => {
          toast.success(decision ? `Approved${result.status === 'PENDING' ? ' — the next stage is waiting' : ''}` : 'Rejected');
          setDecision(null);
        },
      },
    );
  };

  const submitDelegation = () => {
    if (!myTaskId || !delegateTo) return;
    delegate.mutate(
      { taskId: myTaskId, userId: delegateTo.id, comment: delegateNote },
      {
        onSuccess: () => {
          toast.success(`Delegated to ${delegateTo.fullName}`);
          setDelegating(false);
          setDelegateTo(null);
          setDelegateNote('');
        },
      },
    );
  };

  const delegateError = delegate.isError ? toApiError(delegate.error).fieldErrors?.find((field) => field.field === 'userId')?.message ?? errorMessage(delegate.error) : undefined;

  return (
    <>
      <Drawer
        open={approvalId !== null}
        onClose={onClose}
        wide
        label={approval ? `${approvalTypeLabel(approval.type).toUpperCase()} APPROVAL` : 'APPROVAL'}
        title={approval?.title ?? 'Approval'}
        subtitle={approval ? `${approval.referenceNumber ?? ''}${approval.requestedByName ? ` · requested by ${approval.requestedByName}` : ''} · ${formatRelative(approval.createdAt)}` : undefined}
        footer={
          myTaskId && approval?.status === 'PENDING' ? (
            <>
              <button type="button" className="subtle-btn" onClick={() => setDelegating(true)}>
                <Forward size={14} aria-hidden="true" /> Delegate
              </button>
              <button type="button" className="danger-btn" onClick={() => setDecision(false)}>
                <X size={14} aria-hidden="true" /> Reject
              </button>
              <button type="button" className="gold-btn" onClick={() => setDecision(true)}>
                <Check size={14} aria-hidden="true" /> Approve
              </button>
            </>
          ) : undefined
        }
      >
        {detail.isLoading && <LoadingState variant="panel" label="Loading approval…" />}
        {detail.isError && <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />}
        {data && approval && (
          <div className="grid gap-4">
            <div className="flex flex-wrap items-center gap-2">
              <ToneBadge value={approval.status} tone={toneOf(APPROVAL_STATUS_TONES, approval.status)} />
              {approval.flowName && (
                <span className="chip">
                  <Layers size={11} aria-hidden="true" /> {approval.flowName}
                </span>
              )}
              {approval.stageCount ? (
                <span className="chip">
                  Stage {approval.currentStage ?? '—'} of {approval.stageCount}
                  {approval.currentStageName ? ` · ${approval.currentStageName}` : ''}
                </span>
              ) : null}
              {approval.overdue && <ToneBadge value="overdue" label="Overdue" tone="critical" />}
              <DemoBadge demo={approval.demo} />
              {subject && (
                <Link to={subject} className="link-btn ml-auto text-[11px]">
                  Open {approval.referenceNumber ?? approvalTypeLabel(approval.type).toLowerCase()} <ArrowUpRight size={12} aria-hidden="true" />
                </Link>
              )}
            </div>
            {approval.details && <p className="m-0 whitespace-pre-wrap break-words text-[12px] text-muted">{approval.details}</p>}
            {approval.pendingWith && approval.pendingWith.length > 0 && (
              <p className="m-0 text-[11px]">
                Waiting for <b>{approval.pendingWith.join(', ')}</b>
                {approval.dueAt ? ` · due ${formatDateTime(approval.dueAt)}` : ''}
              </p>
            )}
            {approval.status !== 'PENDING' && approval.decidedByName && (
              <div className={`callout ${approval.status === 'APPROVED' ? 'green' : 'red'}`}>
                <div>
                  <b>{titleCase(approval.status)}</b> by {approval.decidedByName}
                  {approval.decidedAt ? ` · ${formatDateTime(approval.decidedAt)}` : ''}
                  {approval.decisionComment ? ` — “${approval.decisionComment}”` : ''}
                </div>
              </div>
            )}
            {!myTaskId && approval.status === 'PENDING' && approval.requestedById !== undefined && (
              <p className="m-0 text-[11px] text-muted">You have no open task on this approval right now (requesters never decide their own requests).</p>
            )}

            <section aria-labelledby="approval-flow">
              <h3 id="approval-flow" className="mb-2 text-[12px] uppercase tracking-wide text-gold">
                Approval flow
              </h3>
              {data.stages.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No stages recorded.</p>
              ) : (
                <ol className="m-0 grid list-none gap-2 p-0">
                  {data.stages.map((stage) => (
                    <StageCard key={stage.id} stage={stage} current={approval.status === 'PENDING' && stage.order === approval.currentStage} />
                  ))}
                </ol>
              )}
            </section>

            <section aria-labelledby="approval-history">
              <h3 id="approval-history" className="mb-2 text-[12px] uppercase tracking-wide text-gold">
                History
              </h3>
              {data.history.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No events yet.</p>
              ) : (
                <ol className="m-0 grid list-none gap-1.5 p-0">
                  {data.history.map((event) => (
                    <li key={event.id} className="list-row !items-start !py-1.5">
                      <div className="min-w-0">
                        <b className="block text-[11px]">
                          {APPROVAL_EVENT_LABELS[event.type] ?? titleCase(event.type)}
                          {event.stageOrder ? <span className="font-normal text-muted"> · stage {event.stageOrder}</span> : null}
                          {event.details?.administratorDecision ? <span className="font-normal text-muted"> · administrator decision</span> : null}
                        </b>
                        {event.comment && <small className="block whitespace-pre-wrap break-words">“{event.comment}”</small>}
                        <small className="block" title={formatDateTime(event.at)}>
                          {event.actorName ?? 'System'} · {formatRelative(event.at)}
                        </small>
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </section>
          </div>
        )}
      </Drawer>

      <ConfirmDialog
        open={decision !== null}
        onClose={() => {
          setDecision(null);
          decide.reset();
        }}
        title={decision ? `Approve ${approval?.referenceNumber ?? 'this approval'}?` : `Reject ${approval?.referenceNumber ?? 'this approval'}?`}
        message={approval?.title ?? ''}
        reasonLabel={decision ? 'Comment (optional)' : 'Reason for rejecting'}
        reasonRequired={decision === false}
        confirmLabel={decision ? 'Approve' : 'Reject'}
        tone={decision ? 'gold' : 'danger'}
        pending={decide.isPending}
        error={decide.isError ? errorMessage(decide.error) : null}
        onConfirm={submitDecision}
      />

      <Modal
        open={delegating}
        onClose={() => {
          setDelegating(false);
          delegate.reset();
        }}
        title="Delegate this approval task"
        eyebrow="APPROVALS"
        description="The task moves to the person you choose; they are notified. The requester cannot receive it."
        dismissible={!delegate.isPending}
        footer={
          <>
            <button type="button" className="subtle-btn" onClick={() => setDelegating(false)} disabled={delegate.isPending}>
              Cancel
            </button>
            <button type="button" className="gold-btn" onClick={submitDelegation} disabled={!delegateTo || delegate.isPending}>
              {delegate.isPending ? 'Delegating…' : 'Delegate'}
            </button>
          </>
        }
      >
        <UserSearchPicker label="Delegate to" value={delegateTo} onChange={setDelegateTo} error={delegateError} />
        <TextareaField label="Note (optional)" rows={3} maxLength={1000} value={delegateNote} onChange={(event) => setDelegateNote(event.target.value)} />
      </Modal>
    </>
  );
}
