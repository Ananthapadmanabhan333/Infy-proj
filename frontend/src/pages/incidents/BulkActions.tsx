import { useEffect, useMemo, useState } from 'react';
import { CheckCircle2, Download, ListChecks, UserPlus, X, XCircle } from 'lucide-react';
import { useBulkIncidentAction, type BulkIncidentResult } from '../../api/incidents';
import { useAssignableUsers, useDepartments } from '../../api/users';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../lib/api';
import {
  INCIDENT_TRANSITIONS,
  isCancelTransition,
  RESOLUTION_CODES,
  STATUS_LABELS,
  TRANSITION_ORDER,
  transitionNeedsReason,
} from '../../lib/constants';
import { formatNumber, titleCase } from '../../lib/format';
import type { IncidentStatus, IncidentSummary, ResolutionCode } from '../../lib/types';
import { downloadIncidentsCsv } from './incidentCsv';

const plural = (count: number, noun = 'incident') => `${formatNumber(count)} ${noun}${count === 1 ? '' : 's'}`;

/** Target statuses that are valid (§2 transition table) for every selected incident, in button order. */
export function commonTransitions(incidents: Pick<IncidentSummary, 'status'>[]): IncidentStatus[] {
  if (!incidents.length) return [];
  return TRANSITION_ORDER.filter((target) => incidents.every((incident) => INCIDENT_TRANSITIONS[incident.status]?.includes(target)));
}

/** Label for a bulk target; CLOSED is a cancellation from NEW/ASSIGNED and a plain close from RESOLVED. */
export function bulkTransitionLabel(incidents: Pick<IncidentSummary, 'status'>[], target: IncidentStatus): string {
  if (target !== 'CLOSED') return STATUS_LABELS[target];
  const cancels = incidents.filter((incident) => isCancelTransition(incident.status, target)).length;
  if (cancels === incidents.length) return 'Cancelled (closed without a fix)';
  if (cancels === 0) return STATUS_LABELS.CLOSED;
  return 'Closed / cancelled';
}

/** Per-incident outcome of a bulk run (the API is called once per incident). */
function BulkOutcome({ result, incidents }: { result: BulkIncidentResult; incidents: IncidentSummary[] }) {
  const numberOf = (id: number) => incidents.find((incident) => incident.id === id)?.number ?? `#${id}`;
  return (
    <div className="grid gap-2" aria-live="polite">
      {result.succeeded.length > 0 && (
        <div className="callout green" role="status">
          <CheckCircle2 size={16} />
          <div>
            <b>{plural(result.succeeded.length)} updated.</b>
          </div>
        </div>
      )}
      {result.failed.length > 0 && (
        <div className="callout red" role="alert">
          <XCircle size={16} />
          <div className="min-w-0 flex-1">
            <b>{plural(result.failed.length)} could not be updated:</b>
            <ul className="mt-1 grid gap-0.5">
              {result.failed.map((failure) => (
                <li key={failure.id}>
                  <span className="id-cell">{numberOf(failure.id)}</span> — {failure.message}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </div>
  );
}

interface BulkDialogProps {
  open: boolean;
  onClose: () => void;
  incidents: IncidentSummary[];
  /** Called with the ids that were updated so they can leave the selection. */
  onApplied: (succeeded: number[]) => void;
}

function useBulkRun(open: boolean, onApplied: (ids: number[]) => void, onClose: () => void) {
  const bulk = useBulkIncidentAction();
  const toast = useToast();
  const [result, setResult] = useState<BulkIncidentResult | null>(null);
  useEffect(() => {
    if (open) {
      setResult(null);
      bulk.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const finish = (outcome: BulkIncidentResult, successMessage: string) => {
    onApplied(outcome.succeeded);
    if (outcome.failed.length === 0) {
      toast.success(successMessage);
      onClose();
      return;
    }
    setResult(outcome);
    toast.warning(`${plural(outcome.succeeded.length)} updated, ${formatNumber(outcome.failed.length)} failed`);
  };
  return { bulk, result, finish };
}

// ------------------------------------------------------------------ Assign
export function BulkAssignDialog({ open, onClose, incidents, onApplied }: BulkDialogProps) {
  const [departmentId, setDepartmentId] = useState('');
  const [assigneeId, setAssigneeId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const departments = useDepartments(open);
  const assignees = useAssignableUsers(departmentId ? Number(departmentId) : null, open);
  const { bulk, result, finish } = useBulkRun(open, onApplied, onClose);
  const snapshot = useMemo(() => incidents, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (open) {
      setDepartmentId('');
      setAssigneeId('');
      setError(null);
    }
  }, [open]);

  const submit = () => {
    if (!departmentId && !assigneeId) {
      setError('Choose a team or an assignee');
      return;
    }
    const assignee = assignees.data?.find((user) => String(user.id) === assigneeId);
    bulk.mutate(
      {
        action: 'assign',
        ids: snapshot.map((incident) => incident.id),
        body: { departmentId: departmentId ? Number(departmentId) : null, assigneeId: assigneeId ? Number(assigneeId) : null },
      },
      { onSuccess: (outcome) => finish(outcome, `${plural(outcome.succeeded.length)} assigned${assignee ? ` to ${assignee.fullName}` : ''}`) },
    );
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      eyebrow="BULK ACTION"
      title={`Assign ${plural(snapshot.length)}`}
      description="Each incident is assigned individually; incidents that cannot be assigned are listed with the reason."
      dismissible={!bulk.isPending}
    >
      {result ? (
        <>
          <BulkOutcome result={result} incidents={snapshot} />
          <div className="modal-foot">
            <button type="button" className="gold-btn" onClick={onClose} data-autofocus>
              Close
            </button>
          </div>
        </>
      ) : (
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <div className="field" data-invalid={error ? 'true' : undefined}>
            <label htmlFor="bulk-assign-team">Team</label>
            <select
              id="bulk-assign-team"
              className="control"
              value={departmentId}
              onChange={(event) => {
                setDepartmentId(event.target.value);
                setAssigneeId('');
                setError(null);
              }}
            >
              <option value="">Keep each incident's team</option>
              {departments.data?.map((department) => (
                <option key={department.id} value={department.id}>
                  {department.name}
                </option>
              ))}
            </select>
            {departments.isError && <span className="field-hint">Teams could not be loaded.</span>}
          </div>
          <div className="field">
            <label htmlFor="bulk-assign-user">Assignee</label>
            <select
              id="bulk-assign-user"
              className="control"
              value={assigneeId}
              onChange={(event) => {
                setAssigneeId(event.target.value);
                setError(null);
              }}
            >
              <option value="">{departmentId ? 'Team queue (no individual)' : 'Choose an assignee'}</option>
              {assignees.data?.map((user) => (
                <option key={user.id} value={user.id}>
                  {user.fullName}
                  {user.departmentName ? ` · ${user.departmentName}` : ''} — {user.openAssignments} open
                </option>
              ))}
            </select>
            <span className="field-hint">{assignees.isFetching ? 'Loading assignable staff…' : 'Agents and managers with their current open workload.'}</span>
          </div>
          {error && (
            <p className="field-error" role="alert">
              {error}
            </p>
          )}
          {bulk.isError && (
            <p className="field-error" role="alert">
              {errorMessage(bulk.error)}
            </p>
          )}
          <div className="modal-foot">
            <button type="button" className="subtle-btn" onClick={onClose} disabled={bulk.isPending}>
              Cancel
            </button>
            <button type="submit" className="gold-btn" disabled={bulk.isPending}>
              {bulk.isPending ? 'Assigning…' : `Assign ${plural(snapshot.length)}`}
            </button>
          </div>
        </form>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------- Change status
export function BulkStatusDialog({ open, onClose, incidents, onApplied }: BulkDialogProps) {
  const snapshot = useMemo(() => incidents, [open]); // eslint-disable-line react-hooks/exhaustive-deps
  const targets = useMemo(() => commonTransitions(snapshot), [snapshot]);
  const [target, setTarget] = useState<IncidentStatus | ''>('');
  const [reason, setReason] = useState('');
  const [resolutionCode, setResolutionCode] = useState<ResolutionCode | ''>('');
  const [resolutionNotes, setResolutionNotes] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const { bulk, result, finish } = useBulkRun(open, onApplied, onClose);

  useEffect(() => {
    if (open) {
      setTarget('');
      setReason('');
      setResolutionCode('');
      setResolutionNotes('');
      setErrors({});
    }
  }, [open]);

  const needsReason = target !== '' && snapshot.some((incident) => transitionNeedsReason(incident.status, target));
  const cancelling = target === 'CLOSED' && snapshot.some((incident) => isCancelTransition(incident.status, target));
  const resolving = target === 'RESOLVED';
  const statuses = [...new Set(snapshot.map((incident) => incident.status))];

  const submit = () => {
    const next: Record<string, string> = {};
    if (!target) next.target = 'Choose the new status';
    if (needsReason && reason.trim().length < 3) next.reason = 'A reason of at least 3 characters is required';
    if (resolving && !resolutionCode) next.resolutionCode = 'Choose a resolution code';
    if (resolving && resolutionNotes.trim().length < 10) next.resolutionNotes = 'Describe the fix (at least 10 characters)';
    setErrors(next);
    if (Object.keys(next).length || !target) return;
    bulk.mutate(
      {
        action: 'transition',
        ids: snapshot.map((incident) => incident.id),
        status: target,
        comment: needsReason || target === 'REOPENED' ? reason.trim() || undefined : undefined,
        resolutionCode: resolving ? (resolutionCode as ResolutionCode) : undefined,
        resolutionNotes: resolving ? resolutionNotes.trim() : undefined,
      },
      { onSuccess: (outcome) => finish(outcome, `${plural(outcome.succeeded.length)} moved to ${bulkTransitionLabel(snapshot, target)}`) },
    );
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      eyebrow="BULK ACTION"
      title={`Change status of ${plural(snapshot.length)}`}
      description={`Current status: ${statuses.map((status) => STATUS_LABELS[status] ?? titleCase(status)).join(', ')}. Only changes valid for every selected incident are offered.`}
      size="lg"
      dismissible={!bulk.isPending}
    >
      {result ? (
        <>
          <BulkOutcome result={result} incidents={snapshot} />
          <div className="modal-foot">
            <button type="button" className="gold-btn" onClick={onClose} data-autofocus>
              Close
            </button>
          </div>
        </>
      ) : targets.length === 0 ? (
        <>
          <div className="callout" role="note">
            <ListChecks size={16} />
            <div>The selected incidents have no status change in common. Select incidents that are in the same stage (for example all In Progress).</div>
          </div>
          <div className="modal-foot">
            <button type="button" className="subtle-btn" onClick={onClose}>
              Close
            </button>
          </div>
        </>
      ) : (
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <fieldset className="field" data-invalid={errors.target ? 'true' : undefined}>
            <legend className="field-label mb-2 text-[11px] font-semibold text-[#d9cdbb]">New status</legend>
            <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="New status">
              {targets.map((status) => (
                <label key={status} className={`chip cursor-pointer !text-[11px] ${target === status ? '!border-gold !text-gold-light' : ''}`}>
                  <input
                    type="radio"
                    name="bulk-status"
                    value={status}
                    checked={target === status}
                    onChange={() => {
                      setTarget(status);
                      setErrors({});
                    }}
                    className="accent-[var(--gold)]"
                  />
                  {bulkTransitionLabel(snapshot, status)}
                </label>
              ))}
            </div>
            {errors.target && <span className="field-error">{errors.target}</span>}
          </fieldset>

          {resolving && (
            <>
              <div className="field" data-invalid={errors.resolutionCode ? 'true' : undefined}>
                <label htmlFor="bulk-resolution-code">Resolution code</label>
                <select
                  id="bulk-resolution-code"
                  className="control"
                  value={resolutionCode}
                  onChange={(event) => setResolutionCode(event.target.value as ResolutionCode | '')}
                  aria-invalid={errors.resolutionCode ? true : undefined}
                >
                  <option value="">Choose a resolution code</option>
                  {RESOLUTION_CODES.map((code) => (
                    <option key={code} value={code}>
                      {titleCase(code)}
                    </option>
                  ))}
                </select>
                {errors.resolutionCode && <span className="field-error">{errors.resolutionCode}</span>}
              </div>
              <div className="field" data-invalid={errors.resolutionNotes ? 'true' : undefined}>
                <label htmlFor="bulk-resolution-notes">Resolution notes</label>
                <textarea
                  id="bulk-resolution-notes"
                  className="control"
                  value={resolutionNotes}
                  maxLength={5000}
                  onChange={(event) => setResolutionNotes(event.target.value)}
                  placeholder="What fixed the issue? Visible to every requester."
                  aria-invalid={errors.resolutionNotes ? true : undefined}
                />
                {errors.resolutionNotes && <span className="field-error">{errors.resolutionNotes}</span>}
              </div>
            </>
          )}

          {(needsReason || target === 'REOPENED') && (
            <div className="field" data-invalid={errors.reason ? 'true' : undefined}>
              <label htmlFor="bulk-reason">{needsReason ? 'Reason (required)' : 'Reason (optional)'}</label>
              <textarea
                id="bulk-reason"
                className="control"
                value={reason}
                maxLength={1000}
                onChange={(event) => setReason(event.target.value)}
                placeholder={cancelling ? 'Why are these incidents being cancelled?' : target === 'PENDING' ? 'What are you waiting for? The SLA clock pauses.' : 'Why are these being reopened?'}
                aria-invalid={errors.reason ? true : undefined}
              />
              {errors.reason && <span className="field-error">{errors.reason}</span>}
            </div>
          )}
          {cancelling && <p className="field-hint">Cancelled incidents are closed with resolution code Cancelled and leave the queue.</p>}
          {bulk.isError && (
            <p className="field-error" role="alert">
              {errorMessage(bulk.error)}
            </p>
          )}
          <div className="modal-foot">
            <button type="button" className="subtle-btn" onClick={onClose} disabled={bulk.isPending}>
              Cancel
            </button>
            <button type="submit" className={cancelling ? 'danger-btn' : 'gold-btn'} disabled={bulk.isPending}>
              {bulk.isPending ? 'Updating…' : `Update ${plural(snapshot.length)}`}
            </button>
          </div>
        </form>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ Bar
interface BulkBarProps {
  incidents: IncidentSummary[];
  onClear: () => void;
  onApplied: (succeeded: number[]) => void;
}

/** Shown above the register while rows are selected (staff only). */
export function BulkBar({ incidents, onClear, onApplied }: BulkBarProps) {
  const toast = useToast();
  const [dialog, setDialog] = useState<'assign' | 'status' | null>(null);
  if (!incidents.length && !dialog) return null;
  const close = () => setDialog(null);

  return (
    <>
      {incidents.length > 0 && (
        <div className="bulk-bar" role="region" aria-label="Bulk actions">
          <b>{formatNumber(incidents.length)} selected</b>
          <button type="button" className="subtle-btn compact" onClick={() => setDialog('assign')}>
            <UserPlus size={13} /> Assign
          </button>
          <button type="button" className="subtle-btn compact" onClick={() => setDialog('status')}>
            <ListChecks size={13} /> Change status
          </button>
          <button
            type="button"
            className="subtle-btn compact"
            onClick={() => {
              downloadIncidentsCsv(incidents);
              toast.success(`Exported ${plural(incidents.length)} to CSV`);
            }}
          >
            <Download size={13} /> Export selected
          </button>
          <button type="button" className="link-btn" onClick={onClear}>
            <X size={13} /> Clear selection
          </button>
        </div>
      )}
      <BulkAssignDialog open={dialog === 'assign'} onClose={close} incidents={incidents} onApplied={onApplied} />
      <BulkStatusDialog open={dialog === 'status'} onClose={close} incidents={incidents} onApplied={onApplied} />
    </>
  );
}
