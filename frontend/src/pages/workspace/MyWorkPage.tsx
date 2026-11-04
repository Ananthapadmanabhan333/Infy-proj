import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Check, ClipboardList, Hand, Inbox, ListTodo, RefreshCw, Stamp, UserPlus, X } from 'lucide-react';
import { useAssignAnyIncident } from '../../api/incidents';
import { useAssignAnyRequest, useDecideApproval } from '../../api/serviceDesk';
import { useMyWork } from '../../api/workspace';
import { Badge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { KpiCard } from '../../components/KpiCard';
import { greetingFor, PageHeader } from '../../components/PageHeader';
import { ErrorState, LoadingState } from '../../components/States';
import { TeamMemberAssignDialog } from '../../components/teams/TeamMemberAssignDialog';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage } from '../../lib/api';
import { APPROVAL_TYPE_LABELS, roleLabel } from '../../lib/constants';
import { formatNumber, formatRelative, formatTime } from '../../lib/format';
import type { AlertSummary } from '../../lib/opsTypes';
import type { ApprovalDto, ApproverRole, IncidentSummary, ServiceRequestDto } from '../../lib/types';
import { extraSections } from './sections';
import { ActionItemsSectionPanel, AlertsSectionPanel, asSection, GenericSectionPanel, IncidentRow, OnCallShiftsPanel, RequestRow, SectionPanel } from './WorkspaceParts';
import type { MyActionItem, MyShift } from '../../lib/responseTypes';

/** Keys this page renders itself; anything else in the response is a later module's section. */
const KNOWN_KEYS = ['generatedAt', 'teams', 'incidents', 'teamQueue', 'requests', 'teamRequests', 'approvals', 'alerts', 'onCallShifts', 'actionItems'] as const;

type AssignTarget = { kind: 'incident'; item: IncidentSummary } | { kind: 'request'; item: ServiceRequestDto };
type Decision = { approval: ApprovalDto; approved: boolean };

/** Approvals open in the Approvals inbox (flow, stages, history), on the caller's own task when known. */
function approvalLink(approval: ApprovalDto): string {
  return `/approvals?approval=${approval.id}${approval.myTaskId ? `&task=${approval.myTaskId}` : ''}`;
}

/** /my-work (WORKSPACE, staff): my incidents, team queues, requests to fulfil and approvals waiting for me. */
export default function MyWorkPage() {
  const { user } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const work = useMyWork(10);
  const assignIncident = useAssignAnyIncident();
  const assignRequest = useAssignAnyRequest();
  const decide = useDecideApproval();
  const [assigning, setAssigning] = useState<AssignTarget | null>(null);
  const [decision, setDecision] = useState<Decision | null>(null);
  const data = work.data;
  const firstName = user?.fullName.split(' ')[0];

  const take = (target: AssignTarget) => {
    if (!user) return;
    const done = () => toast.success(`${target.item.number} is now assigned to you`);
    if (target.kind === 'incident')
      assignIncident.mutate({ id: target.item.id, body: { assigneeId: user.id, teamId: target.item.teamId ?? undefined } }, { onSuccess: done, onError: (error) => toast.error(error) });
    else assignRequest.mutate({ id: target.item.id, assigneeId: user.id, teamId: target.item.teamId }, { onSuccess: done, onError: (error) => toast.error(error) });
  };

  const assignTo = (assigneeId: number) => {
    if (!assigning) return;
    const onSuccess = () => {
      toast.success(`${assigning.item.number} assigned`);
      setAssigning(null);
    };
    if (assigning.kind === 'incident') assignIncident.mutate({ id: assigning.item.id, body: { assigneeId, teamId: assigning.item.teamId ?? undefined } }, { onSuccess });
    else assignRequest.mutate({ id: assigning.item.id, assigneeId, teamId: assigning.item.teamId }, { onSuccess });
  };

  const decideApproval = (comment: string) => {
    if (!decision) return;
    decide.mutate(
      { id: decision.approval.id, approved: decision.approved, comment },
      {
        onSuccess: () => {
          toast.success(decision.approved ? 'Approved' : 'Rejected');
          setDecision(null);
          void queryClient.invalidateQueries({ queryKey: ['my-work'] });
        },
      },
    );
  };

  const busy = assignIncident.isPending || assignRequest.isPending;
  const takeButtons = (target: AssignTarget) => (
    <>
      <button type="button" className="subtle-btn compact" disabled={busy} onClick={() => take(target)} aria-label={`Take ${target.item.number}`}>
        <Hand size={12} aria-hidden="true" /> Take
      </button>
      {target.item.teamId && (
        <button
          type="button"
          className="subtle-btn compact"
          disabled={busy}
          onClick={() => {
            assignIncident.reset();
            assignRequest.reset();
            setAssigning(target);
          }}
          aria-label={`Assign ${target.item.number}`}
        >
          <UserPlus size={12} aria-hidden="true" /> Assign
        </button>
      )}
    </>
  );

  const extras = extraSections(data, KNOWN_KEYS);
  const alerts = asSection<AlertSummary>(data?.alerts);
  const onCallShifts = asSection<MyShift>(data?.onCallShifts);
  const actionItems = asSection<MyActionItem>(data?.actionItems);

  return (
    <>
      <PageHeader
        title="My Work"
        greeting={firstName ? `${greetingFor()}, ${firstName}` : greetingFor()}
        subtitle="Everything assigned to you and your teams"
        actions={
          <button type="button" className="subtle-btn" onClick={() => void work.refetch()} disabled={work.isFetching}>
            <RefreshCw size={14} className={work.isFetching ? 'animate-spin' : ''} aria-hidden="true" /> Refresh
          </button>
        }
      >
        {data && (
          <div className="tag-row mt-2" aria-label="My teams">
            {data.teams.length ? (
              data.teams.map((team) => <Badge key={team.id} value={team.memberRole} tone={team.memberRole === 'LEAD' ? 'gold' : 'neutral'} label={`${team.name}${team.memberRole === 'LEAD' ? ' · lead' : ''}`} />)
            ) : (
              <span className="muted-text text-[11px]">You are not a member of any team yet — team queues appear once a team admin adds you.</span>
            )}
          </div>
        )}
      </PageHeader>
      <div className="page-stack">
        {work.isLoading && (
          <>
            <LoadingState variant="kpis" label="Loading your work…" />
            <LoadingState variant="panel" />
          </>
        )}
        {work.isError && !data && <ErrorState error={work.error} onRetry={() => void work.refetch()} />}
        {data && (
          <>
            <div className="kpi-grid">
              <KpiCard icon={<ListTodo />} label="My incidents" value={formatNumber(data.incidents.total)} tone={data.incidents.total ? 'gold' : 'success'} caption="Active, assigned to you" />
              <KpiCard
                icon={<Inbox />}
                label="Team queue"
                value={formatNumber(data.teamQueue.total)}
                tone={data.teamQueue.total ? 'warning' : 'success'}
                caption="Unassigned incidents of my teams"
              />
              <KpiCard
                icon={<ClipboardList />}
                label="Requests to fulfil"
                value={formatNumber(data.requests.total + data.teamRequests.total)}
                tone="neutral"
                caption={`${formatNumber(data.requests.total)} mine · ${formatNumber(data.teamRequests.total)} team`}
              />
              <KpiCard icon={<Stamp />} label="Approvals waiting" value={formatNumber(data.approvals.total)} tone={data.approvals.total ? 'warning' : 'success'} caption="Routed to you" />
            </div>
            <p className="m-0 text-[10px] text-muted">Updated {formatTime(data.generatedAt)} · refreshes every minute</p>

            <div className="grid items-start gap-[14px] xl:grid-cols-2">
              <SectionPanel title="My incidents" total={data.incidents.total} shown={data.incidents.items.length} to="/incidents?scope=mine&tab=open" empty="No active incidents assigned to you">
                {data.incidents.items.map((incident) => (
                  <IncidentRow key={incident.id} incident={incident} showTeam />
                ))}
              </SectionPanel>
              <SectionPanel title="Team queue" total={data.teamQueue.total} shown={data.teamQueue.items.length} to="/incidents?scope=team&tab=open" empty="Your team queues are empty">
                {data.teamQueue.items.map((incident) => (
                  <IncidentRow key={incident.id} incident={incident} showTeam actions={takeButtons({ kind: 'incident', item: incident })} />
                ))}
              </SectionPanel>
              <SectionPanel title="My requests to fulfil" total={data.requests.total} shown={data.requests.items.length} to="/service-desk" empty="No open requests assigned to you">
                {data.requests.items.map((request) => (
                  <RequestRow key={request.id} request={request} />
                ))}
              </SectionPanel>
              <SectionPanel title="Team requests" total={data.teamRequests.total} shown={data.teamRequests.items.length} to="/service-desk" empty="No unassigned requests in your teams">
                {data.teamRequests.items.map((request) => (
                  <RequestRow key={request.id} request={request} showTeam actions={takeButtons({ kind: 'request', item: request })} />
                ))}
              </SectionPanel>
              <SectionPanel title="Approvals waiting for me" total={data.approvals.total} shown={data.approvals.items.length} to="/approvals" empty="No approvals waiting for you">
                {data.approvals.items.map((approval) => (
                  <li key={approval.id} className="list-row flex-wrap sm:flex-nowrap">
                    <div>
                      <b className="flex flex-wrap items-center gap-1.5">
                        {approval.referenceNumber && (
                          <Link to={approvalLink(approval)} className="id-cell">
                            {approval.referenceNumber}
                          </Link>
                        )}
                        <span className="min-w-0 truncate">{approval.title}</span>
                      </b>
                      <small>
                        {APPROVAL_TYPE_LABELS[approval.type] ?? approval.type} · {approval.currentStageName ?? (approval.approverRole ? `${roleLabel(approval.approverRole as ApproverRole)} approval` : 'approval')}
                        {approval.requestedByName ? ` · requested by ${approval.requestedByName}` : ''} · {formatRelative(approval.createdAt)}
                      </small>
                    </div>
                    <span className="flex shrink-0 gap-1">
                      <button type="button" className="danger-btn compact" onClick={() => setDecision({ approval, approved: false })} aria-label={`Reject ${approval.title}`}>
                        <X size={12} aria-hidden="true" /> Reject
                      </button>
                      <button type="button" className="gold-btn compact" onClick={() => setDecision({ approval, approved: true })} aria-label={`Approve ${approval.title}`}>
                        <Check size={12} aria-hidden="true" /> Approve
                      </button>
                    </span>
                  </li>
                ))}
              </SectionPanel>
              {alerts && <AlertsSectionPanel section={alerts} />}
              {onCallShifts && <OnCallShiftsPanel section={onCallShifts} />}
              {actionItems && <ActionItemsSectionPanel section={actionItems} />}
              {extras.map((section) => (
                <GenericSectionPanel key={section.key} section={section} />
              ))}
            </div>
          </>
        )}
      </div>

      <TeamMemberAssignDialog
        open={assigning !== null}
        onClose={() => setAssigning(null)}
        title={assigning ? `Assign ${assigning.item.number}` : 'Assign'}
        teamId={assigning?.item.teamId ?? null}
        teamName={assigning?.item.teamName}
        pending={busy}
        error={assigning?.kind === 'incident' ? assignIncident.error : assignRequest.error}
        onAssign={assignTo}
      />
      <ConfirmDialog
        open={decision !== null}
        onClose={() => {
          setDecision(null);
          decide.reset();
        }}
        title={decision ? `${decision.approved ? 'Approve' : 'Reject'} ${decision.approval.referenceNumber ?? 'approval'}?` : ''}
        message={decision ? decision.approval.title : ''}
        reasonLabel={decision?.approved ? 'Comment (optional)' : 'Reason'}
        reasonRequired={decision ? !decision.approved : false}
        confirmLabel={decision?.approved ? 'Approve' : 'Reject'}
        tone={decision?.approved ? 'gold' : 'danger'}
        pending={decide.isPending}
        error={decide.isError ? errorMessage(decide.error) : null}
        onConfirm={decideApproval}
      />
    </>
  );
}
