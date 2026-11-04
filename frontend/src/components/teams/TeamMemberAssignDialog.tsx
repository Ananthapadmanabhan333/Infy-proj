import { useEffect, useState } from 'react';
import { useTeam } from '../../api/teams';
import { errorMessage } from '../../lib/api';
import { formatNumber } from '../../lib/format';
import { Modal } from '../Modal';
import { ErrorState, LoadingState } from '../States';

interface TeamMemberAssignDialogProps {
  open: boolean;
  onClose: () => void;
  /** e.g. "Assign INC-7001". */
  title: string;
  teamId: number | null;
  teamName?: string | null;
  pending: boolean;
  error: unknown;
  onAssign: (assigneeId: number) => void;
}

/** Picks an active member of a team (with their open workload) as the assignee. */
export function TeamMemberAssignDialog({ open, onClose, title, teamId, teamName, pending, error, onAssign }: TeamMemberAssignDialogProps) {
  const team = useTeam(teamId, open);
  const [assignee, setAssignee] = useState('');
  useEffect(() => {
    if (open) setAssignee('');
  }, [open]);
  const members = (team.data?.members ?? []).filter((member) => member.active);

  return (
    <Modal open={open} onClose={onClose} title={title} eyebrow="ASSIGNMENT" description={teamName ? `Members of ${teamName}` : undefined} dismissible={!pending}>
      {team.isLoading && <LoadingState variant="inline" label="Loading team members…" />}
      {team.isError && <ErrorState error={team.error} onRetry={() => void team.refetch()} compact />}
      {team.data && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (assignee) onAssign(Number(assignee));
          }}
        >
          <div className="field">
            <label htmlFor="team-member-assignee">Assignee</label>
            <select id="team-member-assignee" className="control" value={assignee} onChange={(event) => setAssignee(event.target.value)}>
              <option value="">{members.length ? 'Choose a team member…' : 'The team has no active members'}</option>
              {members.map((member) => (
                <option key={member.userId} value={member.userId}>
                  {member.fullName}
                  {member.memberRole === 'LEAD' ? ' (lead)' : ''} — {formatNumber(member.openAssignments)} open
                </option>
              ))}
            </select>
          </div>
          {Boolean(error) && (
            <p className="field-error" role="alert">
              {errorMessage(error)}
            </p>
          )}
          <div className="modal-foot">
            <button type="button" className="subtle-btn" onClick={onClose} disabled={pending}>
              Cancel
            </button>
            <button type="submit" className="gold-btn" disabled={!assignee || pending}>
              {pending ? 'Assigning…' : 'Assign'}
            </button>
          </div>
        </form>
      )}
    </Modal>
  );
}
