import { useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { AlarmClock, CalendarRange, Check, GitBranch, Inbox, Send, Stamp, Trash2, UserRoundCheck, X } from 'lucide-react';
import {
  useApprovalCounts,
  useApprovalList,
  useApprovalTasks,
  useCreateDelegation,
  useDecideTask,
  useDelegations,
  useRevokeDelegation,
} from '../../api/approvals';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Toggle } from '../../components/Controls';
import { FilterSelect } from '../../components/FilterSelect';
import { TextField } from '../../components/FormField';
import { ToneBadge, UserSearchPicker } from '../../components/itsm/ItsmParts';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { FILTER_CLASS, FILTER_ROW_CLASS } from '../../components/servicedesk/requestModel';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput, useTabParam, useUrlFilters } from '../../hooks/useUrlState';
import { errorMessage, toApiError } from '../../lib/api';
import { APPROVAL_TYPE_LABELS, READERS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative, localInputToIso } from '../../lib/format';
import { APPROVAL_STATUS_TONES, toneOf } from '../../lib/itsm';
import type { TaskInboxItem, UserOption } from '../../lib/itsmTypes';
import type { ApprovalDto, Role } from '../../lib/types';
import { ApprovalDrawer, approvalTypeLabel } from './ApprovalDrawer';

type TabId = 'inbox' | 'decided' | 'requested' | 'all' | 'delegations';
const ALL_SCOPE_ROLES: Role[] = ['SYSTEM_ADMIN', 'IT_ADMIN', 'AUDITOR'];
const KEYS = ['type', 'status', 'overdue', 'search', 'approval', 'task'] as const;
const TYPE_OPTIONS = Object.entries(APPROVAL_TYPE_LABELS).map(([value, label]) => ({ value, label }));
const STATUS_OPTIONS = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'].map((value) => ({ value, label: value.charAt(0) + value.slice(1).toLowerCase() }));
const PAGE_SIZE = 20;

function ApprovalRow({ approval, meta, actions, onOpen }: { approval: ApprovalDto; meta?: ReactNode; actions?: ReactNode; onOpen: () => void }) {
  return (
    <li className="list-row flex-wrap sm:flex-nowrap">
      <button type="button" className="min-w-0 flex-1 border-0 bg-transparent p-0 text-left" onClick={onOpen} aria-label={`Open approval ${approval.title}`}>
        <b className="flex flex-wrap items-center gap-1.5">
          {approval.referenceNumber && <span className="id-cell">{approval.referenceNumber}</span>}
          <span className="min-w-0 break-words">{approval.title}</span>
        </b>
        <small className="block">
          {approvalTypeLabel(approval.type)}
          {approval.flowName ? ` · ${approval.flowName}` : ''}
          {approval.stageCount ? ` · stage ${approval.currentStage ?? '—'}/${approval.stageCount}${approval.currentStageName ? ` ${approval.currentStageName}` : ''}` : ''}
          {approval.requestedByName ? ` · requested by ${approval.requestedByName}` : ''} · {formatRelative(approval.createdAt)}
        </small>
        {meta}
      </button>
      <span className="flex shrink-0 flex-wrap items-center gap-1">
        {approval.overdue && <ToneBadge value="overdue" label="Overdue" tone="critical" />}
        <ToneBadge value={approval.status} tone={toneOf(APPROVAL_STATUS_TONES, approval.status)} />
        {actions}
      </span>
    </li>
  );
}

function DelegationsPanel() {
  const delegations = useDelegations();
  const create = useCreateDelegation();
  const revoke = useRevokeDelegation();
  const toast = useToast();
  const { user } = useAuth();
  const [delegate, setDelegate] = useState<UserOption | null>(null);
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [reason, setReason] = useState('');
  const fieldError = (field: string) => (create.isError ? toApiError(create.error).fieldErrors?.find((error) => error.field === field)?.message : undefined);

  const submit = () => {
    const start = localInputToIso(startsAt);
    const end = localInputToIso(endsAt);
    if (!delegate || !start || !end) return;
    create.mutate(
      { delegateId: delegate.id, startsAt: start, endsAt: end, reason: reason.trim() || undefined },
      {
        onSuccess: () => {
          toast.success(`Out-of-office delegation to ${delegate.fullName} saved`);
          setDelegate(null);
          setStartsAt('');
          setEndsAt('');
          setReason('');
        },
      },
    );
  };

  return (
    <div className="grid items-start gap-[14px] xl:grid-cols-[1fr_1.2fr]">
      <Panel title="Out-of-office delegation" subtitle="Tasks addressed to you personally go to your delegate during the window (at most 90 days).">
        <UserSearchPicker label="Delegate" value={delegate} onChange={setDelegate} excludeIds={user ? [user.id] : []} error={fieldError('delegateId')} />
        <div className="form-row">
          <TextField label="Starts" type="datetime-local" required value={startsAt} onChange={(event) => setStartsAt(event.target.value)} error={fieldError('startsAt')} />
          <TextField label="Ends" type="datetime-local" required value={endsAt} onChange={(event) => setEndsAt(event.target.value)} error={fieldError('endsAt')} />
        </div>
        <TextField label="Reason (optional)" maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} />
        {create.isError && !toApiError(create.error).fieldErrors?.length && (
          <p className="field-error" role="alert">
            {errorMessage(create.error)}
          </p>
        )}
        <div className="flex justify-end">
          <button type="button" className="gold-btn" onClick={submit} disabled={!delegate || !startsAt || !endsAt || create.isPending}>
            <CalendarRange size={14} aria-hidden="true" /> {create.isPending ? 'Saving…' : 'Save delegation'}
          </button>
        </div>
      </Panel>
      <Panel title="Current and upcoming delegations">
        {delegations.isLoading && <LoadingState rows={3} />}
        {delegations.isError && <ErrorState error={delegations.error} onRetry={() => void delegations.refetch()} compact />}
        {delegations.data && delegations.data.length === 0 && <EmptyState title="No delegations" description="Nobody covers your approvals and you cover nobody's." icon={<UserRoundCheck size={18} />} />}
        {delegations.data && delegations.data.length > 0 && (
          <ul className="m-0 grid list-none gap-1 p-0">
            {delegations.data.map((delegation) => {
              const mine = delegation.delegatorId === user?.id;
              return (
                <li key={delegation.id} className="list-row flex-wrap sm:flex-nowrap">
                  <div className="min-w-0">
                    <b className="block break-words">{mine ? `You → ${delegation.delegateName}` : `${delegation.delegatorName} → you`}</b>
                    <small className="block">
                      {formatDateTime(delegation.startsAt)} – {formatDateTime(delegation.endsAt)}
                      {delegation.reason ? ` · ${delegation.reason}` : ''}
                    </small>
                  </div>
                  <span className="flex shrink-0 items-center gap-1">
                    <ToneBadge value={delegation.active ? 'active' : 'upcoming'} label={delegation.active ? 'Active' : 'Upcoming'} tone={delegation.active ? 'success' : 'champagne'} />
                    {mine && (
                      <button
                        type="button"
                        className="icon-btn sm"
                        aria-label={`Revoke delegation to ${delegation.delegateName}`}
                        disabled={revoke.isPending}
                        onClick={() => revoke.mutate(delegation.id, { onSuccess: () => toast.success('Delegation revoked'), onError: (error) => toast.error(error) })}
                      >
                        <Trash2 size={13} />
                      </button>
                    )}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </Panel>
    </div>
  );
}

/** /approvals (GOVERNANCE, every signed-in user): approval tasks routed to me across subject types, my requests, delegations. */
export default function ApprovalsPage() {
  const { user } = useAuth();
  const toast = useToast();
  const canSeeAll = Boolean(user && ALL_SCOPE_ROLES.includes(user.role));
  const canSeeFlows = Boolean(user && READERS.includes(user.role));
  const tabs: TabId[] = canSeeAll ? ['inbox', 'decided', 'requested', 'all', 'delegations'] : ['inbox', 'decided', 'requested', 'delegations'];
  const [tab, setTab] = useTabParam<TabId>(tabs, 'inbox', ['status', 'search']);
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const counts = useApprovalCounts();
  const overdue = values.overdue === 'true' ? true : undefined;
  const taskMode = tab === 'inbox' || tab === 'decided';
  const tasks = useApprovalTasks(
    { status: tab === 'decided' ? 'DECIDED' : 'PENDING', type: values.type || undefined, overdue: tab === 'inbox' ? overdue : undefined, page, size: PAGE_SIZE, sort: tab === 'inbox' ? 'dueAt,asc' : undefined },
    taskMode,
  );
  const list = useApprovalList(
    { scope: tab === 'all' ? 'all' : 'requested', type: values.type || undefined, status: values.status || undefined, search: values.search || undefined, overdue, page, size: PAGE_SIZE },
    tab === 'requested' || tab === 'all',
  );
  const decide = useDecideTask();
  const [quick, setQuick] = useState<{ item: TaskInboxItem; approved: boolean } | null>(null);
  const approvalId = values.approval ? Number(values.approval) : null;
  const taskParam = values.task ? Number(values.task) : null;

  // Notification links point at `/approvals?task=<id>`: open that task's approval once the inbox has loaded it.
  const inboxItems = tasks.data?.content;
  useEffect(() => {
    if (!taskParam || approvalId || !inboxItems) return;
    const match = inboxItems.find((item) => item.taskId === taskParam);
    if (match) setValues({ approval: String(match.approval.id), page: page ? String(page) : '' });
  }, [taskParam, approvalId, inboxItems, setValues, page]);

  const open = (approval: ApprovalDto, taskId?: number) => setValues({ approval: String(approval.id), task: taskId ? String(taskId) : '', page: page ? String(page) : '' });
  const closeDrawer = () => setValues({ approval: '', task: '', page: page ? String(page) : '' });

  const quickDecide = (comment: string) => {
    if (!quick) return;
    decide.mutate(
      { taskId: quick.item.taskId, approved: quick.approved, comment },
      {
        onSuccess: () => {
          toast.success(quick.approved ? 'Approved' : 'Rejected');
          setQuick(null);
        },
      },
    );
  };

  const activeQuery = taskMode ? tasks : list;
  const filtering = Boolean(values.type || values.status || values.search || values.overdue);

  return (
    <>
      <PageHeader
        title="Approvals"
        subtitle="Approval tasks waiting for your decision, across service requests, runbooks, AI actions and changes"
        actions={
          canSeeFlows ? (
            <Link className="date-btn" to="/approvals/flows">
              <GitBranch size={14} aria-hidden="true" /> Approval flows
            </Link>
          ) : undefined
        }
      />
      <div className="page-stack">
        <div className="kpi-grid">
          <KpiCard icon={<Inbox />} label="Waiting for me" value={formatNumber(counts.data?.pendingTasks)} tone={counts.data?.pendingTasks ? 'warning' : 'success'} loading={counts.isLoading} caption="Tasks I can decide now" />
          <KpiCard icon={<AlarmClock />} label="Overdue" value={formatNumber(counts.data?.overdueTasks)} tone={counts.data?.overdueTasks ? 'critical' : 'success'} loading={counts.isLoading} caption="Past the stage deadline" />
          <KpiCard icon={<Send />} label="My requests pending" value={formatNumber(counts.data?.requestedPending)} tone="gold" loading={counts.isLoading} caption="Approvals I asked for" />
        </div>

        <Tabs<TabId>
          label="Approval views"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'inbox', label: 'Waiting for me', count: counts.data?.pendingTasks },
            { id: 'decided', label: 'Decided by me' },
            { id: 'requested', label: 'My requests', count: counts.data?.requestedPending },
            { id: 'all', label: 'All approvals', hidden: !canSeeAll },
            { id: 'delegations', label: 'Delegations' },
          ]}
        />

        {tab === 'delegations' ? (
          <DelegationsPanel />
        ) : (
          <Panel aria-label="Approvals">
            <div className="toolbar">
              {!taskMode && <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search approvals…" label="Search approvals" />}
              <div className={FILTER_ROW_CLASS}>
                <FilterSelect className={FILTER_CLASS} label="Type" allLabel="All types" value={values.type} onChange={(type) => setValues({ type })} options={TYPE_OPTIONS} />
                {!taskMode && <FilterSelect className={FILTER_CLASS} label="Status" allLabel="Any status" value={values.status} onChange={(status) => setValues({ status })} options={STATUS_OPTIONS} />}
                {tab !== 'decided' && (
                  <span className="inline-flex items-center gap-2 text-[11px]">
                    <Toggle checked={values.overdue === 'true'} onChange={(on: boolean) => setValues({ overdue: on ? 'true' : '' })} label="Overdue only" />
                    Overdue only
                  </span>
                )}
              </div>
            </div>
            {activeQuery.isLoading && <LoadingState rows={5} label="Loading approvals…" />}
            {activeQuery.isError && <ErrorState error={activeQuery.error} onRetry={() => void activeQuery.refetch()} />}

            {taskMode && tasks.data && (
              <>
                {tasks.data.content.length === 0 ? (
                  <EmptyState
                    title={tab === 'inbox' ? (filtering ? 'No tasks match these filters' : 'Nothing is waiting for you') : 'You have not decided any approvals yet'}
                    description={tab === 'inbox' ? 'Tasks routed to you by name, role, team or delegation appear here.' : undefined}
                    icon={<Stamp size={20} />}
                  />
                ) : (
                  <ul className="m-0 grid list-none gap-1 p-0" aria-label={tab === 'inbox' ? 'Tasks waiting for me' : 'Tasks I decided'}>
                    {tasks.data.content.map((item) => (
                      <ApprovalRow
                        key={item.taskId}
                        approval={item.approval}
                        onOpen={() => open(item.approval, item.taskId)}
                        meta={
                          <small className="block">
                            Stage {item.stageOrder}: {item.stageName} · as {item.approverLabel}
                            {item.dueAt ? <span className={item.overdue ? 'red-text' : ''}> · due {formatRelative(item.dueAt)}</span> : null}
                            {tab === 'decided' ? ` · ${item.taskStatus.toLowerCase()}` : ''}
                          </small>
                        }
                        actions={
                          tab === 'inbox' && item.canDecide ? (
                            <>
                              <button type="button" className="danger-btn compact" onClick={() => setQuick({ item, approved: false })} aria-label={`Reject ${item.approval.title}`}>
                                <X size={12} aria-hidden="true" /> Reject
                              </button>
                              <button type="button" className="gold-btn compact" onClick={() => setQuick({ item, approved: true })} aria-label={`Approve ${item.approval.title}`}>
                                <Check size={12} aria-hidden="true" /> Approve
                              </button>
                            </>
                          ) : undefined
                        }
                      />
                    ))}
                  </ul>
                )}
                <Pagination page={tasks.data.page} totalPages={tasks.data.totalPages} totalElements={tasks.data.totalElements} size={tasks.data.size} onPageChange={setPage} label="tasks" />
              </>
            )}

            {!taskMode && list.data && (
              <>
                {list.data.content.length === 0 ? (
                  <EmptyState
                    title={filtering ? 'No approvals match these filters' : tab === 'requested' ? 'You have not requested any approvals' : 'No approvals yet'}
                    icon={<Stamp size={20} />}
                  />
                ) : (
                  <ul className="m-0 grid list-none gap-1 p-0" aria-label={tab === 'requested' ? 'My requested approvals' : 'All approvals'}>
                    {list.data.content.map((approval) => (
                      <ApprovalRow
                        key={approval.id}
                        approval={approval}
                        onOpen={() => open(approval)}
                        meta={
                          approval.status === 'PENDING' && approval.pendingWith?.length ? (
                            <small className="block">Waiting for {approval.pendingWith.join(', ')}{approval.dueAt ? ` · due ${formatRelative(approval.dueAt)}` : ''}</small>
                          ) : approval.decidedByName ? (
                            <small className="block">
                              {approval.status.toLowerCase()} by {approval.decidedByName}
                              {approval.decisionComment ? ` — “${approval.decisionComment}”` : ''}
                            </small>
                          ) : undefined
                        }
                      />
                    ))}
                  </ul>
                )}
                <Pagination page={list.data.page} totalPages={list.data.totalPages} totalElements={list.data.totalElements} size={list.data.size} onPageChange={setPage} label="approvals" />
              </>
            )}
          </Panel>
        )}
      </div>

      <ApprovalDrawer approvalId={approvalId} taskId={taskParam} onClose={closeDrawer} />
      <ConfirmDialog
        open={quick !== null}
        onClose={() => {
          setQuick(null);
          decide.reset();
        }}
        title={quick ? `${quick.approved ? 'Approve' : 'Reject'} ${quick.item.approval.referenceNumber ?? 'approval'}?` : ''}
        message={quick ? `${quick.item.approval.title} — stage ${quick.item.stageOrder}: ${quick.item.stageName}` : ''}
        reasonLabel={quick?.approved ? 'Comment (optional)' : 'Reason for rejecting'}
        reasonRequired={quick ? !quick.approved : false}
        confirmLabel={quick?.approved ? 'Approve' : 'Reject'}
        tone={quick?.approved ? 'gold' : 'danger'}
        pending={decide.isPending}
        error={decide.isError ? errorMessage(decide.error) : null}
        onConfirm={quickDecide}
      />
    </>
  );
}

