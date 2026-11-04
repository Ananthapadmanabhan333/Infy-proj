import { useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Mail, Pencil, Star, Trash2, UserPlus } from 'lucide-react';
import { useAddTeamMember, useRemoveTeamMember, useTeam, useUpdateTeamMember } from '../../api/teams';
import { useUsers } from '../../api/users';
import { Avatar } from '../../components/Avatar';
import { Badge, BooleanBadge, DemoBadge, RoleBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Drawer } from '../../components/Drawer';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../lib/api';
import { formatDate, formatNumber } from '../../lib/format';
import type { TeamDto, TeamMemberDto, TeamMemberRole } from '../../lib/types';
import { isStaffRole } from './teamAccess';

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <small>{label}</small>
      <b>{children}</b>
    </div>
  );
}

function AddMember({ team }: { team: TeamDto }) {
  const toast = useToast();
  const add = useAddTeamMember(team.id);
  const [search, setSearch] = useState('');
  const [userId, setUserId] = useState('');
  const [memberRole, setMemberRole] = useState<TeamMemberRole>('MEMBER');
  const users = useUsers({ active: 'true', size: 100, search: search.trim() || undefined });
  const memberIds = useMemo(() => new Set(team.members.map((member) => member.userId)), [team.members]);
  const candidates = (users.data?.content ?? []).filter((candidate) => candidate.active && isStaffRole(candidate.role) && !memberIds.has(candidate.id));

  const submit = () =>
    add.mutate(
      { userId: Number(userId), memberRole },
      {
        onSuccess: () => {
          toast.success('Member added');
          setUserId('');
          setMemberRole('MEMBER');
        },
        onError: (error) => toast.error(error),
      },
    );

  return (
    <div className="drawer-section">
      <h3>Add a member</h3>
      <div className="grid gap-2 sm:grid-cols-[1fr_1.4fr_auto_auto]">
        <input className="control sm" type="search" aria-label="Search staff" placeholder="Search staff…" value={search} onChange={(event) => setSearch(event.target.value)} />
        <select className="control sm" aria-label="Staff member" value={userId} onChange={(event) => setUserId(event.target.value)} disabled={users.isLoading}>
          <option value="">{users.isLoading ? 'Loading staff…' : candidates.length ? 'Choose a staff member…' : 'No staff available'}</option>
          {candidates.map((candidate) => (
            <option key={candidate.id} value={candidate.id}>
              {candidate.fullName}
              {candidate.departmentName ? ` · ${candidate.departmentName}` : ''}
            </option>
          ))}
        </select>
        <select className="control sm" aria-label="Member role" value={memberRole} onChange={(event) => setMemberRole(event.target.value as TeamMemberRole)}>
          <option value="MEMBER">Member</option>
          <option value="LEAD">Lead</option>
        </select>
        <button type="button" className="gold-btn compact !mt-0" disabled={!userId || add.isPending} onClick={submit}>
          <UserPlus size={13} aria-hidden="true" /> {add.isPending ? 'Adding…' : 'Add'}
        </button>
      </div>
      {users.isError && <p className="field-error">{errorMessage(users.error)}</p>}
      <p>Members must be active staff. Members receive the team queue notifications (TEAM_QUEUE).</p>
    </div>
  );
}

function MemberRow({ team, member, manage, onRemove }: { team: TeamDto; member: TeamMemberDto; manage: boolean; onRemove: (member: TeamMemberDto) => void }) {
  const toast = useToast();
  const update = useUpdateTeamMember(team.id);
  return (
    <li className="list-row">
      <Avatar name={member.fullName} size="sm" />
      <div>
        <b className="flex flex-wrap items-center gap-1.5">
          {member.fullName}
          {member.memberRole === 'LEAD' && (
            <span className="badge tone-gold">
              <Star size={9} aria-hidden="true" /> Lead
            </span>
          )}
          {!member.active && <BooleanBadge value={false} />}
        </b>
        <small>
          {member.jobTitle ? `${member.jobTitle} · ` : ''}
          {member.email} · {formatNumber(member.openAssignments)} open · joined {formatDate(member.joinedAt)}
        </small>
      </div>
      <span className="hidden sm:inline">
        <RoleBadge role={member.role} />
      </span>
      {manage && (
        <span className="flex shrink-0 items-center gap-1">
          <select
            className="control sm !w-auto"
            aria-label={`Team role of ${member.fullName}`}
            value={member.memberRole}
            disabled={update.isPending}
            onChange={(event) =>
              update.mutate(
                { userId: member.userId, memberRole: event.target.value as TeamMemberRole },
                { onSuccess: () => toast.success(`${member.fullName} is now ${event.target.value === 'LEAD' ? 'a lead' : 'a member'}`), onError: (error) => toast.error(error) },
              )
            }
          >
            <option value="MEMBER">Member</option>
            <option value="LEAD">Lead</option>
          </select>
          <button type="button" className="icon-btn sm" aria-label={`Remove ${member.fullName}`} title="Remove from team" onClick={() => onRemove(member)}>
            <Trash2 size={13} />
          </button>
        </span>
      )}
    </li>
  );
}

/** Team detail: facts, queue counts and members; team admins add, remove and change member roles. */
export function TeamDrawer({
  teamId,
  canManage,
  onClose,
  onEdit,
}: {
  teamId: number;
  canManage: (team: TeamDto) => boolean;
  onClose: () => void;
  onEdit?: (team: TeamDto) => void;
}) {
  const team = useTeam(teamId);
  const toast = useToast();
  const remove = useRemoveTeamMember(teamId);
  const [removing, setRemoving] = useState<TeamMemberDto | null>(null);
  const data = team.data;
  const manage = data ? canManage(data) : false;

  return (
    <Drawer
      open
      onClose={onClose}
      label="TEAM"
      title={data ? data.name : team.isError ? 'Team unavailable' : 'Loading team…'}
      subtitle={data ? `${data.code} · ${data.departmentName ?? 'No department'}` : undefined}
      wide
      footer={
        data && manage && onEdit ? (
          <button type="button" className="gold-btn compact" onClick={() => onEdit(data)}>
            <Pencil size={13} aria-hidden="true" /> Edit team
          </button>
        ) : undefined
      }
    >
      {team.isLoading && <LoadingState variant="panel" label="Loading team…" />}
      {team.isError && <ErrorState error={team.error} onRetry={() => void team.refetch()} />}
      {data && (
        <>
          <div className="tag-row">
            <BooleanBadge value={data.active} />
            {data.departmentDefault && <Badge value="default" tone="gold" label="Department default" />}
            <DemoBadge demo={data.demo} />
          </div>
          {data.description && <p className="description-text mt-3">{data.description}</p>}
          <div className="detail-grid">
            <Fact label="Lead">{data.leadName ?? '—'}</Fact>
            <Fact label="Team e-mail">
              {data.email ? (
                <a href={`mailto:${data.email}`} className="inline-flex items-center gap-1">
                  <Mail size={11} aria-hidden="true" /> {data.email}
                </a>
              ) : (
                '—'
              )}
            </Fact>
            <Fact label="Open incidents">
              <Link to={`/incidents?teamId=${data.id}&tab=open`}>{formatNumber(data.openIncidents)}</Link>
            </Fact>
            <Fact label="Team queue (unassigned)">
              <span className={data.unassignedIncidents ? 'warning-text' : undefined}>{formatNumber(data.unassignedIncidents)}</span>
            </Fact>
            <Fact label="Open requests">{formatNumber(data.openRequests)}</Fact>
            <Fact label="Members">{formatNumber(data.memberCount)}</Fact>
          </div>

          <div className="drawer-section">
            <h3>Members</h3>
            {data.members.length === 0 ? (
              <EmptyState title="No members yet" description={manage ? 'Add active staff below.' : 'A team admin adds members.'} />
            ) : (
              <ul className="m-0 list-none p-0" aria-label="Team members">
                {data.members.map((member) => (
                  <MemberRow key={member.userId} team={data} member={member} manage={manage} onRemove={setRemoving} />
                ))}
              </ul>
            )}
          </div>
          {manage && <AddMember team={data} />}

          <ConfirmDialog
            open={removing !== null}
            onClose={() => {
              setRemoving(null);
              remove.reset();
            }}
            title={`Remove ${removing?.fullName ?? 'member'}?`}
            message={removing?.memberRole === 'LEAD' ? 'They are the team lead; removing them clears the lead.' : 'Their current assignments stay with them.'}
            confirmLabel="Remove"
            tone="danger"
            pending={remove.isPending}
            error={remove.isError ? errorMessage(remove.error) : null}
            onConfirm={() =>
              removing &&
              remove.mutate(removing.userId, {
                onSuccess: () => {
                  toast.success(`${removing.fullName} removed`);
                  setRemoving(null);
                },
              })
            }
          />
        </>
      )}
    </Drawer>
  );
}
