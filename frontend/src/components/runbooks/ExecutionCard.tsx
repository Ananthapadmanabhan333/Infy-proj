import { useState } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle2, Clock3, Hourglass, Save, ShieldAlert, XCircle } from 'lucide-react';
import { useRunbook, useRunbookExecutionActions } from '../../api/runbooks';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage } from '../../lib/api';
import { roleLabel, RUNBOOK_APPROVERS } from '../../lib/constants';
import { formatDateTime, formatRelative } from '../../lib/format';
import type { RunbookExecutionDto, RunbookStepState } from '../../lib/types';
import { Badge, StatusBadge } from '../Badge';
import { ConfirmDialog } from '../ConfirmDialog';
import { useToast } from '../Toast';
import './runbooks.css';

function StepNotes({ initial, disabled, onSave, pending }: { initial: string; disabled: boolean; onSave: (notes: string) => void; pending: boolean }) {
  const [notes, setNotes] = useState(initial);
  const dirty = notes !== initial;
  return (
    <div className="mt-2 flex gap-2">
      <input
        className="control sm flex-1"
        value={notes}
        onChange={(event) => setNotes(event.target.value)}
        placeholder="Step notes (optional)"
        aria-label="Step notes"
        disabled={disabled}
        maxLength={1000}
      />
      {dirty && !disabled && (
        <button type="button" className="subtle-btn compact" onClick={() => onSave(notes)} disabled={pending}>
          <Save size={12} /> Save
        </button>
      )}
    </div>
  );
}

/** Approval state of a sensitive step, e.g. "Awaiting Incident Manager approval". */
function ApprovalState({ state }: { state: RunbookStepState }) {
  if (state.approvalStatus === 'PENDING') return <Badge value="pending-approval" label="Awaiting Incident Manager approval" />;
  if (state.approvalStatus === 'APPROVED') return <Badge value="approved" label="Approved" />;
  if (state.approvalStatus === 'REJECTED') return <Badge value="rejected" label="Approval rejected" />;
  return null;
}

/**
 * Runbook execution with its step checklist, notes and complete / abort controls (§10).
 * Sensitive steps: a non-approver ticking one requests INCIDENT_MANAGER approval (the step stays undone until
 * approved); an approver (Incident Manager / System Admin) completing it directly counts as the approval.
 */
export function ExecutionCard({ execution, canRun, showIncident = false }: { execution: RunbookExecutionDto; canRun: boolean; showIncident?: boolean }) {
  const runbook = useRunbook(execution.runbookId);
  const { step, complete, abort } = useRunbookExecutionActions();
  const { hasRole } = useAuth();
  const approver = hasRole(...RUNBOOK_APPROVERS);
  const toast = useToast();
  const [dialog, setDialog] = useState<'complete' | 'abort' | null>(null);
  const active = execution.status === 'READY' || execution.status === 'IN_PROGRESS';
  const editable = canRun && active;
  const doneCount = execution.stepStates.filter((state) => state.done).length;
  const total = execution.stepStates.length;
  const instructions = new Map(runbook.data?.steps.map((s) => [s.order, s.instruction]) ?? []);

  const toggle = (state: RunbookStepState, done: boolean, notes: string | null) =>
    step.mutate(
      { executionId: execution.id, order: state.order, done, notes },
      {
        onSuccess: (updated) => {
          const after = updated?.stepStates?.find((candidate) => candidate.order === state.order);
          if (done && state.requiresApproval && after?.approvalStatus === 'PENDING') toast.info(`“${state.title}” sent for Incident Manager approval`);
        },
        onError: (error) => toast.error(error),
      },
    );

  return (
    <article className="action-card" aria-label={`Runbook execution ${execution.runbookTitle}`}>
      <div className="flex flex-wrap items-center gap-2">
        <b className="text-[12px]">{execution.runbookTitle}</b>
        <StatusBadge status={execution.status} />
        {showIncident && execution.incidentNumber && (
          <Link className="id-cell text-[10px]" to={`/incidents/${execution.incidentId}`}>
            {execution.incidentNumber}
          </Link>
        )}
        <span className="ml-auto text-[10px] text-muted">
          {doneCount}/{total} steps · started by {execution.startedByName ?? '—'} · {formatRelative(execution.createdAt)}
        </span>
      </div>

      {execution.status === 'PENDING_APPROVAL' && (
        <div className="callout mt-2">
          <Hourglass size={16} />
          <div>
            Sent for {execution.approval ? roleLabel(execution.approval.approverRole) : 'Incident Manager'} approval
            {execution.approval?.requestedByName ? ` by ${execution.approval.requestedByName}` : ''} — steps unlock once approved.
          </div>
        </div>
      )}
      {execution.status === 'REJECTED' && (
        <div className="callout red mt-2">
          <XCircle size={16} />
          <div>
            Rejected{execution.approval?.decidedByName ? ` by ${execution.approval.decidedByName}` : ''}
            {execution.approval?.decisionComment ? `: “${execution.approval.decisionComment}”` : ''}
          </div>
        </div>
      )}

      <div className="step-list mt-3">
        {execution.stepStates.map((state) => {
          const awaiting = state.approvalStatus === 'PENDING';
          // A pending sensitive step can only be completed by an approver (which approves it).
          const locked = !editable || step.isPending || (awaiting && !approver);
          const label = awaiting && approver && editable ? `Approve and complete “${state.title}”` : state.title;
          return (
            <div key={state.order} className={`step-item ${state.done ? 'done' : ''} ${state.requiresApproval ? 'sensitive' : ''}`}>
              <span className="step-num">{state.done ? <CheckCircle2 size={13} /> : state.order}</span>
              <div className="min-w-0">
                <div className="rb-step-head">
                  <label className="check !m-0">
                    <input type="checkbox" checked={state.done} disabled={locked} aria-label={label} onChange={(event) => toggle(state, event.target.checked, state.notes)} />
                    <b>{state.title}</b>
                  </label>
                  {state.requiresApproval && (
                    <span className="rb-sensitive">
                      <ShieldAlert size={10} aria-hidden="true" /> Sensitive — approval required
                    </span>
                  )}
                  {state.requiresApproval && <ApprovalState state={state} />}
                </div>
                {state.requiresApproval && !state.done && editable && (
                  <small className="rb-hint">
                    {approver
                      ? awaiting
                        ? 'Ticking it approves the pending request and completes the step (audited).'
                        : 'As an approver, completing it counts as the approval (audited).'
                      : awaiting
                        ? 'Waiting for an Incident Manager to approve; it is marked done once approved.'
                        : 'Ticking it requests Incident Manager approval; it is marked done once approved.'}
                  </small>
                )}
                {instructions.get(state.order) && <p>{instructions.get(state.order)}</p>}
                {state.completedAt && (
                  <small className="mt-1 block text-[9px] text-muted">
                    <Clock3 size={10} /> {state.completedByName ?? 'Someone'} · {formatDateTime(state.completedAt)}
                  </small>
                )}
                {(editable || state.notes) && (
                  <StepNotes initial={state.notes ?? ''} disabled={!editable} pending={step.isPending} onSave={(notes) => toggle(state, state.done, notes || null)} />
                )}
              </div>
            </div>
          );
        })}
      </div>

      {execution.outcomeNotes && <p className="mt-2 text-[10px] text-muted">Outcome: {execution.outcomeNotes}</p>}

      {editable && (
        <div className="mt-3 flex flex-wrap justify-end gap-2">
          <button type="button" className="danger-btn compact" onClick={() => setDialog('abort')}>
            Abort runbook
          </button>
          <button type="button" className="gold-btn compact" onClick={() => setDialog('complete')}>
            Complete runbook
          </button>
        </div>
      )}

      <ConfirmDialog
        open={dialog === 'complete'}
        onClose={() => setDialog(null)}
        title="Complete runbook"
        message={doneCount < total ? `${total - doneCount} step(s) are not done yet.` : 'All steps are done.'}
        reasonLabel="Outcome notes"
        reasonRequired
        confirmLabel="Complete"
        pending={complete.isPending}
        error={complete.isError ? errorMessage(complete.error) : null}
        onConfirm={(outcomeNotes) =>
          complete.mutate(
            { executionId: execution.id, outcomeNotes },
            {
              onSuccess: () => {
                toast.success('Runbook completed');
                setDialog(null);
              },
            },
          )
        }
      />
      <ConfirmDialog
        open={dialog === 'abort'}
        onClose={() => setDialog(null)}
        title="Abort runbook"
        message="The execution stops and remaining steps are left undone."
        reasonLabel="Reason"
        reasonRequired
        confirmLabel="Abort"
        tone="danger"
        pending={abort.isPending}
        error={abort.isError ? errorMessage(abort.error) : null}
        onConfirm={(reason) =>
          abort.mutate(
            { executionId: execution.id, reason },
            {
              onSuccess: () => {
                toast.info('Runbook aborted');
                setDialog(null);
              },
            },
          )
        }
      />
    </article>
  );
}
