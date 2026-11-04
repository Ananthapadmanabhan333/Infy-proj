import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { BellRing, CalendarClock, CalendarOff, Check, Inbox, Pencil, Plus, ShieldAlert, Trash2, UserRound, X } from 'lucide-react';
import {
  useAvailability,
  useEscalationPolicies,
  useMyShifts,
  useOnCallNow,
  useOverrideDecision,
  useOverrideOffers,
  usePages,
  useRemoveUnavailability,
  useSchedules,
} from '../../api/oncall';
import { useTeams } from '../../api/teams';
import { DemoBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { DataTable } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { OnCallPersonCard, SourceBadge, TimeZoneLabel } from '../../components/oncall/OnCallParts';
import { SeverityBadge, AlertStatusBadge } from '../../components/ops/OpsParts';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam, useUrlFilters } from '../../hooks/useUrlState';
import { errorMessage } from '../../lib/api';
import { ONCALL_EDITORS, STAFF } from '../../lib/constants';
import { formatDateTime, formatMinutes, formatNumber, formatRelative } from '../../lib/format';
import { ESCALATION_TARGET_LABELS, ROTATION_LABELS, WEEKDAY_LABELS } from '../../lib/response';
import type { OverrideDto, PolicyDto, ScheduleSummary } from '../../lib/responseTypes';
import { PolicyEditor, UnavailabilityDialog } from './OnCallDialogs';
import { ScheduleEditor } from './ScheduleEditor';

type TabId = 'now' | 'schedules' | 'mine' | 'policies' | 'pages';
const TABS: TabId[] = ['now', 'schedules', 'mine', 'policies', 'pages'];
const TAB_LABELS: Record<TabId, string> = { now: 'On call now', schedules: 'Schedules', mine: 'My on-call', policies: 'Escalation policies', pages: 'Page log' };

/** "Weekly · Monday 09:00" / "Every 12 h from 08:00". */
export function rotationText(schedule: Pick<ScheduleSummary, 'rotationType' | 'handoffTime' | 'handoffDay' | 'shiftHours'>): string {
  const time = schedule.handoffTime ?? '09:00';
  if (schedule.rotationType === 'WEEKLY') return `Weekly · ${WEEKDAY_LABELS[(schedule.handoffDay ?? 1) - 1]} ${time}`;
  if (schedule.rotationType === 'DAILY') return `Daily · ${time}`;
  return `Every ${schedule.shiftHours ?? '?'} h from ${time}`;
}

function NowTab() {
  const teams = useTeams({ active: true });
  const { values, setValues } = useUrlFilters(['team'] as const);
  const teamId = values.team ? Number(values.team) : null;
  const now = useOnCallNow(teamId);
  return (
    <Panel
      title="Who is on call now"
      subtitle="Primary and secondary for every active schedule — overrides, holiday cover and unavailability applied"
      actions={
        <FilterSelect label="Team" allLabel="All teams" value={values.team} onChange={(team) => setValues({ team })} options={(teams.data ?? []).map((team) => ({ value: String(team.id), label: team.name }))} />
      }
    >
      {now.isLoading && <LoadingState variant="cards" rows={3} label="Loading who is on call…" />}
      {now.isError && <ErrorState error={now.error} onRetry={() => void now.refetch()} compact />}
      {now.data && now.data.length === 0 && <EmptyState title="No active on-call schedules" description={teamId ? 'This team has no active schedule.' : undefined} icon={<CalendarClock size={20} />} />}
      {now.data && now.data.length > 0 && (
        <div className="oncall-now-grid">
          {now.data.map((entry) => (
            <article key={entry.scheduleId} className="oncall-card" aria-label={`${entry.teamName ?? entry.scheduleName} on call`}>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <b className="block break-words text-[13px]">{entry.teamName ?? '—'}</b>
                  <Link to={`/on-call/${entry.scheduleId}`} className="text-[11px]">
                    {entry.scheduleName}
                  </Link>
                </div>
                <small className="text-[10px] text-muted">
                  <TimeZoneLabel timeZone={entry.timeZone} />
                </small>
              </div>
              <OnCallPersonCard person={entry.primary} layer="Primary" timeZone={entry.timeZone} />
              <OnCallPersonCard person={entry.secondary} layer="Secondary" timeZone={entry.timeZone} />
              <small className="text-[10px] text-muted">{entry.escalationPolicyName ? `Escalation: ${entry.escalationPolicyName}` : 'No escalation policy — alerts use the team-lead fallback'}</small>
            </article>
          ))}
        </div>
      )}
    </Panel>
  );
}

function SchedulesTab({ editor }: { editor: boolean }) {
  const navigate = useNavigate();
  const schedules = useSchedules();
  const [creating, setCreating] = useState(false);
  return (
    <Panel
      title="Schedules"
      subtitle="One schedule per team rotation, each in its own time zone"
      actions={
        editor ? (
          <button type="button" className="gold-btn compact" onClick={() => setCreating(true)}>
            <Plus size={13} aria-hidden="true" /> New schedule
          </button>
        ) : undefined
      }
    >
      {schedules.isLoading && <LoadingState rows={5} label="Loading schedules…" />}
      {schedules.isError && <ErrorState error={schedules.error} onRetry={() => void schedules.refetch()} compact />}
      {schedules.data && schedules.data.length === 0 && <EmptyState title="No on-call schedules yet" description={editor ? 'Create a schedule for a team rotation.' : undefined} icon={<CalendarClock size={20} />} />}
      {schedules.data && schedules.data.length > 0 && (
        <DataTable<ScheduleSummary>
          caption="On-call schedules"
          rows={schedules.data}
          rowKey={(schedule) => schedule.id}
          rowLabel={(schedule) => schedule.name}
          onRowClick={(schedule) => navigate(`/on-call/${schedule.id}`)}
          columns={[
            {
              key: 'name',
              header: 'Schedule',
              className: 'summary-cell',
              render: (schedule) => (
                <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                  <span className="break-words font-semibold">{schedule.name}</span>
                  {!schedule.active && <ToneBadge value="inactive" label="Inactive" tone="muted" />}
                  <DemoBadge demo={schedule.demo} />
                </span>
              ),
            },
            { key: 'team', header: 'Team', render: (schedule) => schedule.teamName ?? '—' },
            { key: 'rotation', header: 'Rotation', render: (schedule) => `${ROTATION_LABELS[schedule.rotationType]} — ${rotationText(schedule)}` },
            { key: 'zone', header: 'Time zone', render: (schedule) => schedule.timeZone },
            {
              key: 'primary',
              header: 'Primary now',
              render: (schedule) =>
                schedule.primaryNow ? (
                  <span className="inline-flex flex-wrap items-center gap-1">
                    {schedule.primaryNow.name} <SourceBadge source={schedule.primaryNow.source} />
                  </span>
                ) : (
                  <span className="text-muted">Nobody</span>
                ),
            },
            { key: 'secondary', header: 'Secondary now', render: (schedule) => schedule.secondaryNow?.name ?? <span className="text-muted">Nobody</span> },
            { key: 'people', header: 'Participants', render: (schedule) => `${schedule.primaryCount} + ${schedule.secondaryCount}` },
            { key: 'policy', header: 'Escalation', render: (schedule) => schedule.escalationPolicyName ?? '—' },
          ]}
        />
      )}
      {creating && <ScheduleEditor detail={null} onClose={() => setCreating(false)} onSaved={(saved) => navigate(`/on-call/${saved.schedule.id}`)} />}
    </Panel>
  );
}

function OffersPanel() {
  const offers = useOverrideOffers();
  const decide = useOverrideDecision();
  const toast = useToast();
  const [declining, setDeclining] = useState<OverrideDto | null>(null);
  const take = (offer: OverrideDto) =>
    decide.mutate({ id: offer.id, action: 'take' }, { onSuccess: () => toast.success(`You now cover ${offer.scheduleName} from ${formatDateTime(offer.startsAt)}`), onError: (error) => toast.error(error) });
  return (
    <Panel title={`Shift offers for me${offers.data ? ` (${offers.data.length})` : ''}`} subtitle="Offers addressed to you, or open offers on schedules you take part in">
      {offers.isLoading && <LoadingState rows={2} />}
      {offers.isError && <ErrorState error={offers.error} onRetry={() => void offers.refetch()} compact />}
      {offers.data && offers.data.length === 0 && <EmptyState title="No shift offers" icon={<Inbox size={18} />} />}
      {offers.data && offers.data.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Shift offers">
          {offers.data.map((offer) => (
            <li key={offer.id} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  <Link to={`/on-call/${offer.scheduleId}`}>{offer.scheduleName}</Link>
                  <ToneBadge value={offer.layer} tone="neutral" />
                  {!offer.userId && <ToneBadge value="open" label="Open offer" tone="champagne" />}
                </b>
                <small className="block">
                  {formatDateTime(offer.startsAt)} – {formatDateTime(offer.endsAt)} · from {offer.originalUserName ?? offer.createdByName ?? 'the rotation'}
                  {offer.reason ? ` · “${offer.reason}”` : ''}
                </small>
              </div>
              {offer.canTake && (
                <span className="flex shrink-0 gap-1">
                  {offer.userId && (
                    <button type="button" className="subtle-btn compact" disabled={decide.isPending} onClick={() => setDeclining(offer)} aria-label={`Decline offer on ${offer.scheduleName}`}>
                      <X size={12} aria-hidden="true" /> Decline
                    </button>
                  )}
                  <button type="button" className="gold-btn compact" disabled={decide.isPending} onClick={() => take(offer)} aria-label={`Take offer on ${offer.scheduleName}`}>
                    <Check size={12} aria-hidden="true" /> Take shift
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={declining !== null}
        onClose={() => setDeclining(null)}
        title={`Decline the ${declining?.scheduleName ?? ''} offer?`}
        message="The person who offered the shift is told you declined."
        reasonLabel="Note (optional)"
        confirmLabel="Decline"
        tone="danger"
        pending={decide.isPending}
        error={decide.isError ? errorMessage(decide.error) : null}
        onConfirm={(note) =>
          declining &&
          decide.mutate(
            { id: declining.id, action: 'decline', note },
            {
              onSuccess: () => {
                toast.success('Offer declined');
                setDeclining(null);
              },
            },
          )
        }
      />
    </Panel>
  );
}

function AvailabilityPanel({ editor }: { editor: boolean }) {
  const { user } = useAuth();
  const [everyone, setEveryone] = useState(false);
  const availability = useAvailability(everyone ? { all: true } : {});
  const remove = useRemoveUnavailability();
  const toast = useToast();
  const [adding, setAdding] = useState(false);
  return (
    <Panel
      title={everyone ? 'Everyone’s unavailability' : 'My availability'}
      subtitle="While you are unavailable the next available participant covers your slots"
      actions={
        <span className="flex flex-wrap gap-2">
          {editor && (
            <button type="button" className="subtle-btn compact" aria-pressed={everyone} onClick={() => setEveryone((value) => !value)}>
              <UserRound size={12} aria-hidden="true" /> {everyone ? 'Only mine' : 'Everyone'}
            </button>
          )}
          <button type="button" className="gold-btn compact" onClick={() => setAdding(true)}>
            <CalendarOff size={12} aria-hidden="true" /> Add unavailability
          </button>
        </span>
      }
    >
      {availability.isLoading && <LoadingState rows={2} />}
      {availability.isError && <ErrorState error={availability.error} onRetry={() => void availability.refetch()} compact />}
      {availability.data && availability.data.length === 0 && <EmptyState title={everyone ? 'Nobody is unavailable' : 'You have no unavailability recorded'} icon={<CalendarOff size={18} />} />}
      {availability.data && availability.data.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Unavailability">
          {availability.data.map((entry) => (
            <li key={entry.id} className="list-row">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  {everyone && <span>{entry.userName}</span>}
                  <span>
                    {formatDateTime(entry.startsAt)} – {formatDateTime(entry.endsAt)}
                  </span>
                  <DemoBadge demo={entry.demo} />
                </b>
                {entry.reason && <small className="block break-words">{entry.reason}</small>}
              </div>
              {(editor || entry.userId === user?.id) && (
                <button
                  type="button"
                  className="icon-btn sm shrink-0"
                  aria-label={`Remove unavailability ${formatDateTime(entry.startsAt)}`}
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(entry.id, { onSuccess: () => toast.success('Unavailability removed'), onError: (error) => toast.error(error) })}
                >
                  <Trash2 size={13} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {adding && <UnavailabilityDialog editor={editor} onClose={() => setAdding(false)} />}
    </Panel>
  );
}

function MyTab({ editor }: { editor: boolean }) {
  const shifts = useMyShifts(14);
  return (
    <div className="grid items-start gap-[14px] xl:grid-cols-2">
      <Panel title="My shifts" subtitle="Current and upcoming shifts in the next 14 days, both layers">
        {shifts.isLoading && <LoadingState rows={3} />}
        {shifts.isError && <ErrorState error={shifts.error} onRetry={() => void shifts.refetch()} compact />}
        {shifts.data && shifts.data.length === 0 && <EmptyState title="No shifts in the next 14 days" icon={<CalendarClock size={18} />} />}
        {shifts.data && shifts.data.length > 0 && (
          <ul className="m-0 grid list-none gap-1 p-0" aria-label="My shifts">
            {shifts.data.map((shift, index) => (
              <li key={`${shift.scheduleId}-${shift.layer}-${shift.start}-${index}`} className="list-row flex-wrap sm:flex-nowrap">
                <div className="min-w-0">
                  <b className="flex flex-wrap items-center gap-1.5">
                    <Link to={`/on-call/${shift.scheduleId}`}>{shift.scheduleName}</Link>
                    <ToneBadge value={shift.layer} tone={shift.layer === 'PRIMARY' ? 'gold' : 'neutral'} />
                    <SourceBadge source={shift.source} />
                    {shift.current && <ToneBadge value="now" label="On call now" tone="critical" />}
                  </b>
                  <small className="block">
                    {formatDateTime(shift.start)} – {formatDateTime(shift.end)}
                    {shift.teamName ? ` · ${shift.teamName}` : ''}
                  </small>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      <div className="grid min-w-0 gap-[14px]">
        <OffersPanel />
        <AvailabilityPanel editor={editor} />
      </div>
    </div>
  );
}

function PoliciesTab({ editor }: { editor: boolean }) {
  const policies = useEscalationPolicies();
  const [editing, setEditing] = useState<PolicyDto | 'new' | null>(null);
  return (
    <Panel
      title="Escalation policies"
      subtitle="Unacknowledged alerts page level after level; an empty layer falls back to the team lead and manager"
      actions={
        editor ? (
          <button type="button" className="gold-btn compact" onClick={() => setEditing('new')}>
            <Plus size={13} aria-hidden="true" /> New policy
          </button>
        ) : undefined
      }
    >
      {policies.isLoading && <LoadingState rows={3} />}
      {policies.isError && <ErrorState error={policies.error} onRetry={() => void policies.refetch()} compact />}
      {policies.data && policies.data.length === 0 && <EmptyState title="No escalation policies" icon={<ShieldAlert size={18} />} />}
      {policies.data && policies.data.length > 0 && (
        <div className="grid gap-[10px] lg:grid-cols-2">
          {policies.data.map((policy) => (
            <article key={policy.id} className="oncall-card" aria-label={policy.name}>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <b className="flex flex-wrap items-center gap-1.5 text-[13px]">
                    <span className="break-words">{policy.name}</span>
                    {!policy.active && <ToneBadge value="inactive" label="Inactive" tone="muted" />}
                    <DemoBadge demo={policy.demo} />
                  </b>
                  <small className="block text-[10px] text-muted">
                    {policy.teamName ?? 'Any team'} · from {policy.minSeverity ? policy.minSeverity.toLowerCase() : 'major'} severity · used by {formatNumber(policy.scheduleCount)} schedule{policy.scheduleCount === 1 ? '' : 's'}
                  </small>
                </div>
                {editor && (
                  <button type="button" className="subtle-btn compact" onClick={() => setEditing(policy)} aria-label={`Edit ${policy.name}`}>
                    <Pencil size={12} aria-hidden="true" /> Edit
                  </button>
                )}
              </div>
              {policy.description && <p className="m-0 text-[11px] text-muted">{policy.description}</p>}
              <ol className="level-ladder" aria-label={`Levels of ${policy.name}`}>
                {policy.levels.map((level) => (
                  <li key={level.level}>
                    <span className="level-no">{level.level}</span>
                    <span className="min-w-0 break-words">
                      <b>{level.label || (level.target === 'USER' ? level.userName : ESCALATION_TARGET_LABELS[level.target])}</b>
                      <span className="text-muted">
                        {' '}
                        · {level.level === 1 ? `after ${level.delayMinutes} min` : `+${level.delayMinutes} min`} (t+{level.minutesFromStart} min)
                      </span>
                    </span>
                  </li>
                ))}
              </ol>
            </article>
          ))}
        </div>
      )}
      {editing && <PolicyEditor policy={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Panel>
  );
}

function PagesTab() {
  const schedules = useSchedules();
  const { values, setValues } = useUrlFilters(['schedule'] as const);
  const pages = usePages({ scheduleId: values.schedule ? Number(values.schedule) : undefined, size: 100 });
  return (
    <Panel
      title="Page log"
      subtitle="Every on-call page with its acknowledgement time, newest first"
      actions={
        <FilterSelect label="Schedule" allLabel="All schedules" value={values.schedule} onChange={(schedule) => setValues({ schedule })} options={(schedules.data ?? []).map((schedule) => ({ value: String(schedule.id), label: schedule.name }))} />
      }
    >
      {pages.isLoading && <LoadingState rows={5} />}
      {pages.isError && <ErrorState error={pages.error} onRetry={() => void pages.refetch()} compact />}
      {pages.data && pages.data.length === 0 && <EmptyState title="No pages yet" description="Pages appear when an unacknowledged alert escalates through an on-call policy." icon={<BellRing size={18} />} />}
      {pages.data && pages.data.length > 0 && (
        <DataTable
          caption="On-call pages"
          rows={pages.data}
          rowKey={(page) => page.id}
          columns={[
            {
              key: 'alert',
              header: 'Alert',
              className: 'summary-cell',
              render: (page) => (
                <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                  <Link to={`/alerts/${page.alertId}`} className="id-cell">
                    {page.alertNumber}
                  </Link>
                  <span className="break-words">{page.alertTitle}</span>
                  <DemoBadge demo={page.demo} />
                </span>
              ),
            },
            { key: 'severity', header: 'Severity', render: (page) => <SeverityBadge severity={page.severity} /> },
            { key: 'status', header: 'Alert status', render: (page) => <AlertStatusBadge status={page.alertStatus} /> },
            { key: 'level', header: 'Level', render: (page) => `${page.level} · ${ESCALATION_TARGET_LABELS[page.target] ?? page.target}` },
            { key: 'person', header: 'Paged', render: (page) => page.userName ?? '—' },
            { key: 'schedule', header: 'Schedule', render: (page) => page.scheduleName ?? '—' },
            { key: 'pagedAt', header: 'Paged at', render: (page) => <span title={formatDateTime(page.pagedAt)}>{formatRelative(page.pagedAt)}</span> },
            {
              key: 'ack',
              header: 'Acknowledged',
              render: (page) =>
                page.acknowledgedAt ? <span className="green-text">after {formatMinutes(page.ackMinutes)}</span> : <ToneBadge value="unacknowledged" label="Not acknowledged" tone="warning" />,
            },
          ]}
        />
      )}
    </Panel>
  );
}

/** /on-call (OPERATIONS, readers): who is on call, schedules, my shifts/offers/availability, escalation policies, page log. */
export default function OnCallPage() {
  const { hasRole } = useAuth();
  const editor = hasRole(...ONCALL_EDITORS);
  const staff = hasRole(...STAFF);
  const tabs = TABS.filter((id) => id !== 'mine' || staff);
  const [tab, setTab] = useTabParam<TabId>(tabs, 'now', ['team', 'schedule']);
  return (
    <>
      <PageHeader title="On-call" subtitle="Schedules, rotations and escalation policies" />
      <div className="page-stack">
        <Tabs<TabId> label="On-call views" value={tab} onChange={setTab} tabs={tabs.map((id) => ({ id, label: TAB_LABELS[id] }))} />
        {tab === 'now' && <NowTab />}
        {tab === 'schedules' && <SchedulesTab editor={editor} />}
        {tab === 'mine' && staff && <MyTab editor={editor} />}
        {tab === 'policies' && <PoliciesTab editor={editor} />}
        {tab === 'pages' && <PagesTab />}
      </div>
    </>
  );
}
