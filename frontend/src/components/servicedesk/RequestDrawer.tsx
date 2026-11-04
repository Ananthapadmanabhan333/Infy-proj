import { useEffect, useState } from 'react';
import { Ban, Check, CircleX, Play, UserPlus, X } from 'lucide-react';
import { useDecideApproval, useServiceRequest, useServiceRequestAction } from '../../api/serviceDesk';
import { useTeam, useTeams } from '../../api/teams';
import { useAssignableUsers } from '../../api/users';
import { DemoBadge, PriorityBadge, StatusBadge } from '../Badge';
import { UserChip } from '../Avatar';
import { ConfirmDialog } from '../ConfirmDialog';
import { Drawer } from '../Drawer';
import { ErrorState, LoadingState } from '../States';
import { Stepper } from '../Stepper';
import { Tabs } from '../Tabs';
import { useToast } from '../Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage } from '../../lib/api';
import { STAFF, roleLabel } from '../../lib/constants';
import { formatDateTime, formatRelative } from '../../lib/format';
import { displayFormValue } from '../../lib/dynamicForm';
import { RequestAttachments } from './RequestAttachments';
import { REQUEST_PROGRESS_STEPS, humaniseKey, isFinal, requestPermissions, requestProgressIndex } from './requestModel';
import { RequestComments } from './RequestComments';
import { RequestHistory } from './RequestHistory';

type Dialog = 'fulfil' | 'cancel' | 'approve' | 'reject' | null;
type Section = 'comments' | 'history';

/** Request details, form data, approval state, assignee, due date, comments, history and the valid actions by role. */
export function RequestDrawer({ id, onClose }: { id: number; onClose: () => void }) {
  const { user, hasRole } = useAuth();
  const staff = hasRole(...STAFF);
  const request = useServiceRequest(id);
  const action = useServiceRequestAction(id);
  const decide = useDecideApproval();
  const toast = useToast();
  const data = request.data;
  const can = data ? requestPermissions(data, user, staff) : null;
  const [assignee, setAssignee] = useState('');
  const [teamChoice, setTeamChoice] = useState('');
  const teams = useTeams({ active: true }, Boolean(can?.assign));
  // R3: with a team chosen the assignee must be one of its members; otherwise the department's staff.
  const team = useTeam(teamChoice ? Number(teamChoice) : null, Boolean(can?.assign && teamChoice));
  const assignees = useAssignableUsers(data?.departmentId ?? null, Boolean(can?.assign) && !teamChoice);
  const assigneeOptions = teamChoice
    ? (team.data?.members ?? []).filter((member) => member.active).map((member) => ({ id: member.userId, fullName: member.fullName, openAssignments: member.openAssignments }))
    : (assignees.data ?? []).map((option) => ({ id: option.id, fullName: option.fullName, openAssignments: option.openAssignments }));
  const optionsLoading = teamChoice ? team.isLoading : assignees.isLoading;
  const [dialog, setDialog] = useState<Dialog>(null);
  const [section, setSection] = useState<Section>('comments');

  useEffect(() => {
    setAssignee('');
    setTeamChoice('');
    setDialog(null);
    setSection('comments');
    action.reset();
    decide.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const closeDialog = () => {
    setDialog(null);
    action.reset();
    decide.reset();
  };

  const run = (input: Parameters<typeof action.mutate>[0], message: string, inDialog: boolean) =>
    action.mutate(input, {
      onSuccess: () => {
        toast.success(message);
        setDialog(null);
        if (input.action === 'assign') {
          setAssignee('');
          setTeamChoice('');
        }
      },
      onError: (error) => {
        if (!inDialog) toast.error(error);
      },
    });

  const runDecision = (approved: boolean, comment: string) => {
    if (!data?.approval) return;
    decide.mutate(
      { id: data.approval.id, approved, comment },
      {
        onSuccess: () => {
          toast.success(approved ? `${data.number} approved` : `${data.number} rejected`);
          setDialog(null);
        },
      },
    );
  };

  const overdue = Boolean(data?.dueAt && !isFinal(data.status) && new Date(data.dueAt).getTime() < Date.now());
  const formEntries = data ? Object.entries(data.formData ?? {}) : [];
  const footerActions = can && (can.start || can.fulfil || can.cancel || can.decide);

  return (
    <Drawer
      open
      onClose={onClose}
      label="SERVICE REQUEST"
      title={data ? data.title : request.isError ? 'Request unavailable' : 'Loading request…'}
      subtitle={data ? `${data.number} • ${data.categoryName}` : undefined}
      wide
      footer={
        data && can && footerActions ? (
          <>
            {can.decide && (
              <>
                <button type="button" className="danger-btn compact" onClick={() => setDialog('reject')}>
                  <CircleX size={13} aria-hidden="true" /> Reject
                </button>
                <button type="button" className="gold-btn compact" onClick={() => setDialog('approve')}>
                  <Check size={13} aria-hidden="true" /> Approve
                </button>
              </>
            )}
            {can.cancel && (
              <button type="button" className="subtle-btn compact" onClick={() => setDialog('cancel')}>
                <Ban size={13} aria-hidden="true" /> Cancel request
              </button>
            )}
            {can.start && (
              <button type="button" className="subtle-btn compact" disabled={action.isPending} onClick={() => run({ action: 'start' }, 'Fulfilment started', false)}>
                <Play size={13} aria-hidden="true" /> Start fulfilment
              </button>
            )}
            {can.fulfil && (
              <button type="button" className="gold-btn compact" onClick={() => setDialog('fulfil')}>
                <Check size={13} aria-hidden="true" /> Mark fulfilled
              </button>
            )}
          </>
        ) : undefined
      }
    >
      {request.isLoading && <LoadingState variant="panel" label="Loading request…" />}
      {request.isError && <ErrorState error={request.error} onRetry={() => void request.refetch()} />}
      {data && can && (
        <>
          <div className="tag-row">
            <StatusBadge status={data.status} />
            <PriorityBadge priority={data.priority} />
            <DemoBadge demo={data.demo} />
          </div>

          {data.status === 'REJECTED' || data.status === 'CANCELLED' ? (
            <div className="callout red mt-3" role="status">
              <X size={16} aria-hidden="true" />
              <div>
                <b>{data.status === 'REJECTED' ? 'Rejected' : 'Cancelled'}</b>
                {data.status === 'REJECTED' && data.approval?.decisionComment ? ` — “${data.approval.decisionComment}”` : ''}
                {data.status === 'CANCELLED' && data.fulfillmentNotes ? ` — ${data.fulfillmentNotes.replace(/^Cancelled:\s*/, '')}` : ''}
              </div>
            </div>
          ) : (
            <div className="mt-3">
              <Stepper steps={[...REQUEST_PROGRESS_STEPS]} current={requestProgressIndex(data.status)} complete={data.status === 'FULFILLED'} label="Request progress" />
            </div>
          )}

          <div className="detail-grid">
            <div>
              <small>Catalogue item</small>
              <b>{data.catalogItemName}</b>
            </div>
            <div>
              <small>Requester</small>
              <b>
                <UserChip name={data.requesterName} full />
              </b>
            </div>
            <div>
              <small>Fulfilment department</small>
              <b>{data.departmentName ?? '—'}</b>
            </div>
            <div>
              <small>Team</small>
              <b>{data.teamName ?? '—'}</b>
            </div>
            <div>
              <small>Assignee</small>
              <b>
                <UserChip name={data.assigneeName} full />
              </b>
            </div>
            <div>
              <small>Due</small>
              <b className={overdue ? 'red-text' : undefined} title={data.dueAt ? formatRelative(data.dueAt) : undefined}>
                {data.dueAt ? formatDateTime(data.dueAt) : data.status === 'PENDING_APPROVAL' ? 'Set after approval' : '—'}
                {overdue ? ' (overdue)' : ''}
              </b>
            </div>
            <div>
              <small>{data.fulfilledAt ? 'Fulfilled' : 'Created'}</small>
              <b>{formatDateTime(data.fulfilledAt ?? data.createdAt)}</b>
            </div>
          </div>

          {data.details && (
            <div className="drawer-section">
              <h3>Details</h3>
              <p className="description-text">{data.details}</p>
            </div>
          )}

          {formEntries.length > 0 && (
            <div className="drawer-section">
              <h3>Request form</h3>
              <div className="detail-grid tight">
                {formEntries.map(([key, value]) => (
                  <div key={key}>
                    <small>{humaniseKey(key)}</small>
                    <b className="break-words">{displayFormValue(value)}</b>
                  </div>
                ))}
              </div>
            </div>
          )}

          <RequestAttachments requestId={data.id} />

          {data.approval && (
            <div className="drawer-section">
              <h3>Approval</h3>
              <p className="flex flex-wrap items-center gap-2">
                <StatusBadge status={data.approval.status} />
                <span>
                  {roleLabel(data.approval.approverRole)} approval
                  {data.approval.decidedByName
                    ? ` · ${data.approval.decidedByName}, ${formatDateTime(data.approval.decidedAt)}`
                    : data.approval.status === 'PENDING'
                      ? ' · awaiting decision'
                      : ''}
                  {data.approval.decisionComment ? ` — “${data.approval.decisionComment}”` : ''}
                </span>
              </p>
            </div>
          )}

          {data.fulfillmentNotes && data.status !== 'CANCELLED' && (
            <div className="drawer-section">
              <h3>Fulfilment notes</h3>
              <p className="description-text">{data.fulfillmentNotes}</p>
            </div>
          )}

          {can.assign && (
            <div className="drawer-section">
              <h3>Assignment</h3>
              <div className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
                <select
                  className="control sm"
                  aria-label="Team"
                  value={teamChoice}
                  onChange={(event) => {
                    setTeamChoice(event.target.value);
                    setAssignee('');
                  }}
                  disabled={teams.isLoading}
                >
                  <option value="">{teams.isLoading ? 'Loading teams…' : data.teamName ? `Keep team (${data.teamName})` : 'No team change'}</option>
                  {teams.data
                    ?.filter((option) => option.id !== data.teamId)
                    .map((option) => (
                      <option key={option.id} value={option.id}>
                        {option.name}
                        {option.departmentName ? ` · ${option.departmentName}` : ''}
                      </option>
                    ))}
                </select>
                <select className="control sm" aria-label="Assignee" value={assignee} onChange={(event) => setAssignee(event.target.value)} disabled={optionsLoading}>
                  <option value="">
                    {optionsLoading ? 'Loading staff…' : teamChoice ? 'Team queue (no individual)' : data.assigneeName ? 'Reassign to…' : 'Choose an assignee…'}
                  </option>
                  {assigneeOptions
                    .filter((option) => option.id !== data.assigneeId)
                    .map((option) => (
                      <option key={option.id} value={option.id}>
                        {option.fullName} — {option.openAssignments} open
                      </option>
                    ))}
                </select>
                <button
                  type="button"
                  className="subtle-btn compact"
                  disabled={(!assignee && !teamChoice) || action.isPending}
                  onClick={() =>
                    run(
                      { action: 'assign', assigneeId: assignee ? Number(assignee) : null, teamId: teamChoice ? Number(teamChoice) : null },
                      assignee ? 'Request assigned' : 'Request moved to the team queue',
                      false,
                    )
                  }
                >
                  <UserPlus size={13} aria-hidden="true" /> Assign
                </button>
              </div>
              {(assignees.isError || team.isError) && <p className="field-error">{errorMessage(assignees.error ?? team.error)}</p>}
            </div>
          )}

          <div className="drawer-section">
            <Tabs<Section>
              inner
              label="Request activity"
              value={section}
              onChange={setSection}
              tabs={[
                { id: 'comments', label: 'Comments', count: data.commentCount },
                { id: 'history', label: 'History' },
              ]}
            />
            <div className="mt-3">
              {section === 'comments' ? <RequestComments requestId={data.id} canComment={can.comment} canInternal={can.internalNotes} /> : <RequestHistory requestId={data.id} />}
            </div>
          </div>

          <ConfirmDialog
            open={dialog === 'fulfil'}
            onClose={closeDialog}
            title={`Fulfil ${data.number}`}
            message="Describe what was delivered. The requester is notified."
            reasonLabel="Fulfilment notes"
            reasonRequired
            confirmLabel="Mark fulfilled"
            pending={action.isPending}
            error={dialog === 'fulfil' && action.isError ? errorMessage(action.error) : null}
            onConfirm={(notes) => run({ action: 'fulfill', notes }, `${data.number} fulfilled`, true)}
          />
          <ConfirmDialog
            open={dialog === 'cancel'}
            onClose={closeDialog}
            title={`Cancel ${data.number}?`}
            message="Cancelled requests cannot be reopened. Any pending approval is withdrawn."
            reasonLabel="Reason"
            reasonRequired
            confirmLabel="Cancel request"
            tone="danger"
            pending={action.isPending}
            error={dialog === 'cancel' && action.isError ? errorMessage(action.error) : null}
            onConfirm={(reason) => run({ action: 'cancel', reason }, `${data.number} cancelled`, true)}
          />
          <ConfirmDialog
            open={dialog === 'approve' || dialog === 'reject'}
            onClose={closeDialog}
            title={dialog === 'approve' ? `Approve ${data.number}?` : `Reject ${data.number}?`}
            message={dialog === 'approve' ? 'Fulfilment can start as soon as the request is approved.' : 'The requester is notified with your reason.'}
            reasonLabel={dialog === 'approve' ? 'Comment (optional)' : 'Reason'}
            reasonRequired={dialog === 'reject'}
            confirmLabel={dialog === 'approve' ? 'Approve' : 'Reject'}
            tone={dialog === 'approve' ? 'gold' : 'danger'}
            pending={decide.isPending}
            error={decide.isError ? errorMessage(decide.error) : null}
            onConfirm={(comment) => runDecision(dialog === 'approve', comment)}
          />
        </>
      )}
    </Drawer>
  );
}
