import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { CalendarDays, ChevronLeft, ChevronRight, List, MessageSquarePlus, Pencil, Repeat2, X } from 'lucide-react';
import { useOverrideDecision, useSchedule, useScheduleOverrides, useScheduleShifts } from '../../api/oncall';
import { useMyTeams } from '../../api/teams';
import { Avatar } from '../../components/Avatar';
import { DemoBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Fact, ToneBadge } from '../../components/itsm/ItsmParts';
import { OnCallPersonCard, ShiftList, ShiftTimeline, TimeZoneLabel } from '../../components/oncall/OnCallParts';
import { addDaysToKey, formatDayKey, formatZoned, startOfDayKey, weekStartKey } from '../../components/oncall/shiftLayout';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { ONCALL_EDITORS, STAFF } from '../../lib/constants';
import { formatDate, formatDateTime, formatRelative } from '../../lib/format';
import { ESCALATION_TARGET_LABELS, OVERRIDE_STATUS_TONES, ROTATION_LABELS } from '../../lib/response';
import type { OverrideDto, ParticipantDto, ScheduleDetail } from '../../lib/responseTypes';
import { HandoverDialog, OverrideDialog } from './OnCallDialogs';
import { rotationText } from './OnCallPage';
import { ScheduleEditor } from './ScheduleEditor';

const WEEK_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

function ShiftsPanel({ detail }: { detail: ScheduleDetail }) {
  const timeZone = detail.schedule.timeZone;
  const [params, setParams] = useSearchParams();
  const requested = params.get('week');
  const currentWeek = weekStartKey(Date.now(), timeZone);
  const week = requested && WEEK_PATTERN.test(requested) ? weekStartKey(startOfDayKey(requested, timeZone) + 3_600_000, timeZone) : currentWeek;
  const [view, setView] = useState<'timeline' | 'list'>('timeline');
  const from = new Date(startOfDayKey(week, timeZone)).toISOString();
  const to = new Date(startOfDayKey(addDaysToKey(week, 7), timeZone)).toISOString();
  const shifts = useScheduleShifts(detail.schedule.id, from, to);
  const setWeek = (key: string) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (key === currentWeek) next.delete('week');
        else next.set('week', key);
        return next;
      },
      { replace: true },
    );

  return (
    <Panel
      title="Shifts"
      subtitle={
        <>
          Week of {formatDayKey(week)} – {formatDayKey(addDaysToKey(week, 6))} · <TimeZoneLabel timeZone={timeZone} />
        </>
      }
      actions={
        <span className="flex flex-wrap items-center gap-1">
          <button type="button" className="icon-btn sm" aria-label="Previous week" onClick={() => setWeek(addDaysToKey(week, -7))}>
            <ChevronLeft size={14} />
          </button>
          <button type="button" className="subtle-btn compact" disabled={week === currentWeek} onClick={() => setWeek(currentWeek)}>
            This week
          </button>
          <button type="button" className="icon-btn sm" aria-label="Next week" onClick={() => setWeek(addDaysToKey(week, 7))}>
            <ChevronRight size={14} />
          </button>
          <button
            type="button"
            className="subtle-btn compact"
            aria-pressed={view === 'list'}
            onClick={() => setView((current) => (current === 'timeline' ? 'list' : 'timeline'))}
          >
            {view === 'timeline' ? <List size={12} aria-hidden="true" /> : <CalendarDays size={12} aria-hidden="true" />} {view === 'timeline' ? 'List' : 'Timeline'}
          </button>
        </span>
      }
    >
      {shifts.isLoading && <LoadingState variant="panel" label="Computing shifts…" />}
      {shifts.isError && <ErrorState error={shifts.error} onRetry={() => void shifts.refetch()} compact />}
      {shifts.data &&
        (view === 'timeline' ? (
          <ShiftTimeline primary={shifts.data.primary} secondary={shifts.data.secondary} firstDayKey={week} days={7} timeZone={shifts.data.timeZone || timeZone} />
        ) : (
          <div className="grid gap-3 lg:grid-cols-2">
            <div className="min-w-0">
              <h4 className="mb-1.5 text-[11px] font-semibold">Primary</h4>
              <ShiftList shifts={shifts.data.primary} timeZone={timeZone} />
            </div>
            <div className="min-w-0">
              <h4 className="mb-1.5 text-[11px] font-semibold">Secondary</h4>
              <ShiftList shifts={shifts.data.secondary} timeZone={timeZone} />
            </div>
          </div>
        ))}
    </Panel>
  );
}

function ParticipantRows({ title, participants }: { title: string; participants: ParticipantDto[] }) {
  return (
    <div className="min-w-0">
      <h4 className="mb-1.5 text-[11px] font-semibold">
        {title} <span className="muted-text">({participants.length})</span>
      </h4>
      {participants.length === 0 ? (
        <p className="m-0 text-[11px] text-muted">{title === 'Secondary' ? 'None — the next available primary is secondary.' : 'Nobody.'}</p>
      ) : (
        <ol className="m-0 grid list-none gap-1 p-0" aria-label={`${title} participants`}>
          {participants.map((participant) => (
            <li key={participant.userId} className="list-row !py-1.5">
              <span className="id-cell w-5 shrink-0 text-center">{participant.position}</span>
              <Avatar name={participant.userName} size="xs" />
              <span className="min-w-0 flex-1 truncate text-[12px]">{participant.userName}</span>
              {!participant.active && <ToneBadge value="inactive" label="Inactive" tone="muted" />}
              {participant.active && !participant.availableNow && <ToneBadge value="unavailable" label="Unavailable now" tone="warning" />}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function OverridesPanel({ detail, editor, staff }: { detail: ScheduleDetail; editor: boolean; staff: boolean }) {
  const { user } = useAuth();
  const [includePast, setIncludePast] = useState(false);
  const overrides = useScheduleOverrides(detail.schedule.id, includePast);
  const decide = useOverrideDecision();
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [cancelling, setCancelling] = useState<OverrideDto | null>(null);
  const timeZone = detail.schedule.timeZone;
  const participant = [...detail.primary, ...detail.secondary].some((entry) => entry.userId === user?.id);
  const canCreate = editor || (staff && participant);
  const canCancel = (override: OverrideDto) =>
    (override.status === 'OFFERED' || override.status === 'ACTIVE') && Date.parse(override.endsAt) > Date.now() && (editor || override.originalUserId === user?.id || override.userId === user?.id);
  const list = overrides.data ?? detail.overrides;

  return (
    <Panel
      title="Overrides and offers"
      subtitle={`Times in ${timeZone}`}
      actions={
        <span className="flex flex-wrap gap-1">
          <button type="button" className="subtle-btn compact" aria-pressed={includePast} onClick={() => setIncludePast((value) => !value)}>
            {includePast ? 'Hide past' : 'Last 90 days'}
          </button>
          {canCreate && (
            <button type="button" className="gold-btn compact" onClick={() => setCreating(true)}>
              <Repeat2 size={12} aria-hidden="true" /> {editor ? 'Override / offer' : 'Offer my shift'}
            </button>
          )}
        </span>
      }
    >
      {overrides.isError && <ErrorState error={overrides.error} onRetry={() => void overrides.refetch()} compact />}
      {list.length === 0 && <p className="m-0 text-[11px] text-muted">No overrides or open offers.</p>}
      {list.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Overrides">
          {list.map((override) => (
            <li key={override.id} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  <span className="break-words">{override.userName ?? (override.status === 'OFFERED' ? 'Open to the rotation' : '—')}</span>
                  <ToneBadge value={override.status} tone={OVERRIDE_STATUS_TONES[override.status]} />
                  <ToneBadge value={override.layer} tone="neutral" />
                  <DemoBadge demo={override.demo} />
                </b>
                <small className="block">
                  {formatZoned(override.startsAt, timeZone)} – {formatZoned(override.endsAt, timeZone)}
                  {override.originalUserName ? ` · instead of ${override.originalUserName}` : ''}
                  {override.reason ? ` · “${override.reason}”` : ''}
                  {override.decidedNote ? ` · note: ${override.decidedNote}` : ''}
                </small>
              </div>
              <span className="flex shrink-0 gap-1">
                {override.canTake && override.status === 'OFFERED' && (
                  <button
                    type="button"
                    className="gold-btn compact"
                    disabled={decide.isPending}
                    onClick={() => decide.mutate({ id: override.id, action: 'take' }, { onSuccess: () => toast.success('Shift taken'), onError: (error) => toast.error(error) })}
                  >
                    Take shift
                  </button>
                )}
                {canCancel(override) && (
                  <button type="button" className="icon-btn sm" aria-label={`Cancel override ${formatZoned(override.startsAt, timeZone)}`} onClick={() => setCancelling(override)}>
                    <X size={13} />
                  </button>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      {creating && <OverrideDialog detail={detail} editor={editor} onClose={() => setCreating(false)} />}
      <ConfirmDialog
        open={cancelling !== null}
        onClose={() => setCancelling(null)}
        title="Cancel this override?"
        message={cancelling ? `${cancelling.userName ?? 'Open offer'} · ${formatZoned(cancelling.startsAt, timeZone)} – ${formatZoned(cancelling.endsAt, timeZone)}. The rotation applies again.` : ''}
        confirmLabel="Cancel override"
        tone="danger"
        pending={decide.isPending}
        error={decide.isError ? errorMessage(decide.error) : null}
        onConfirm={() =>
          cancelling &&
          decide.mutate(
            { id: cancelling.id, action: 'cancel' },
            {
              onSuccess: () => {
                toast.success('Override cancelled');
                setCancelling(null);
              },
            },
          )
        }
      />
    </Panel>
  );
}

function HandoversPanel({ detail, canWrite }: { detail: ScheduleDetail; canWrite: boolean }) {
  const [adding, setAdding] = useState(false);
  return (
    <Panel
      title="Handover notes"
      actions={
        canWrite ? (
          <button type="button" className="subtle-btn compact" onClick={() => setAdding(true)}>
            <MessageSquarePlus size={12} aria-hidden="true" /> Add note
          </button>
        ) : undefined
      }
    >
      {detail.handovers.length === 0 && <p className="m-0 text-[11px] text-muted">No handover notes yet.</p>}
      {detail.handovers.length > 0 && (
        <ul className="m-0 grid list-none gap-2 p-0" aria-label="Handover notes">
          {detail.handovers.map((note) => (
            <li key={note.id} className="list-row !items-start">
              <div className="min-w-0">
                <small className="block">
                  {note.authorName ?? 'Someone'}
                  {note.toUserName ? ` → ${note.toUserName}` : ''} · <span title={formatDateTime(note.createdAt)}>{formatRelative(note.createdAt)}</span>
                  {note.shiftStart ? ` · shift ${formatZoned(note.shiftStart, detail.schedule.timeZone)}` : ''}
                </small>
                <p className="m-0 mt-1 whitespace-pre-wrap break-words text-[12px]">{note.body}</p>
              </div>
              <DemoBadge demo={note.demo} />
            </li>
          ))}
        </ul>
      )}
      {adding && <HandoverDialog detail={detail} onClose={() => setAdding(false)} />}
    </Panel>
  );
}

/** /on-call/:scheduleId — who is on call, the week's shifts (schedule time zone), participants, overrides, holidays, handovers. */
export default function ScheduleDetailPage() {
  const params = useParams();
  const id = Number(params.scheduleId);
  const valid = Number.isInteger(id) && id > 0;
  const { user, hasRole } = useAuth();
  const editorRole = hasRole(...ONCALL_EDITORS);
  const staff = hasRole(...STAFF);
  const query = useSchedule(valid ? id : null);
  const myTeams = useMyTeams(staff);
  const [editing, setEditing] = useState(false);
  const crumbs = [{ label: 'On-call', to: '/on-call?tab=schedules' }];
  const detail = query.data;

  if (!valid || (query.isError && toApiError(query.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[...crumbs, { label: 'Not found' }]} title="Schedule not found" />
        <EmptyState title="This schedule does not exist" action={<Link to="/on-call?tab=schedules" className="subtle-btn compact">Back to schedules</Link>} />
      </>
    );
  }
  if (query.isLoading) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Loading schedule…" />
        <LoadingState variant="panel" />
      </>
    );
  }
  if (!detail) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Schedule" />
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      </>
    );
  }

  const schedule = detail.schedule;
  const editor = editorRole && detail.canEdit;
  const participant = [...detail.primary, ...detail.secondary].some((entry) => entry.userId === user?.id);
  const teamMember = (myTeams.data ?? []).some((team) => team.id === schedule.teamId);

  return (
    <>
      <PageHeader
        crumbs={[...crumbs, { label: schedule.name }]}
        title={schedule.name}
        actions={
          editor ? (
            <button type="button" className="subtle-btn" onClick={() => setEditing(true)}>
              <Pencil size={14} aria-hidden="true" /> Edit schedule
            </button>
          ) : undefined
        }
      >
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
          <span>{schedule.teamName}</span>
          <span>·</span>
          <span>
            {ROTATION_LABELS[schedule.rotationType]} — {rotationText(schedule)}
          </span>
          <span>·</span>
          <TimeZoneLabel timeZone={schedule.timeZone} />
          {!schedule.active && <ToneBadge value="inactive" label="Inactive" tone="muted" />}
          <DemoBadge demo={schedule.demo} />
        </div>
      </PageHeader>
      <div className="page-stack">
        <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="On call now" subtitle={`Times in ${schedule.timeZone}`}>
              <div className="grid gap-3 sm:grid-cols-2">
                <OnCallPersonCard person={schedule.primaryNow} layer="Primary" timeZone={schedule.timeZone} />
                <OnCallPersonCard person={schedule.secondaryNow} layer="Secondary" timeZone={schedule.timeZone} />
              </div>
            </Panel>
            <ShiftsPanel detail={detail} />
            <OverridesPanel detail={detail} editor={editor} staff={staff} />
          </div>
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Rotation">
              {detail.description && <p className="m-0 mb-3 whitespace-pre-wrap text-[12px]">{detail.description}</p>}
              <div className="detail-grid">
                <Fact label="Starts">{formatDate(schedule.rotationStart)}</Fact>
                <Fact label="Handoff">{rotationText(schedule)}</Fact>
              </div>
              <div className="mt-3 grid gap-3">
                <ParticipantRows title="Primary" participants={detail.primary} />
                <ParticipantRows title="Secondary" participants={detail.secondary} />
              </div>
            </Panel>
            <Panel title="Holidays">
              {detail.holidayCalendarName ? (
                <>
                  <p className="m-0 text-[11px]">
                    Calendar <b>{detail.holidayCalendarName}</b>
                    {detail.holidayCoverUserName ? (
                      <>
                        {' '}
                        · cover <b>{detail.holidayCoverUserName}</b> is primary on holidays (the rotation’s primary becomes secondary)
                      </>
                    ) : (
                      ' · no holiday cover — the rotation continues'
                    )}
                  </p>
                  {detail.upcomingHolidays.length > 0 ? (
                    <ul className="m-0 mt-2 grid list-none gap-1 p-0" aria-label="Upcoming holidays">
                      {detail.upcomingHolidays.map((holiday) => (
                        <li key={holiday.date} className="list-row !py-1.5">
                          <span className="id-cell shrink-0">{formatDate(holiday.date)}</span>
                          <span className="min-w-0 truncate text-[12px]">{holiday.name}</span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="m-0 mt-2 text-[11px] text-muted">No upcoming holidays.</p>
                  )}
                </>
              ) : (
                <p className="m-0 text-[11px] text-muted">No holiday calendar.</p>
              )}
            </Panel>
            <Panel title="Escalation policy" action={detail.escalationPolicy ? 'All policies' : undefined} to="/on-call?tab=policies">
              {detail.escalationPolicy ? (
                <>
                  <b className="block text-[12px]">{detail.escalationPolicy.name}</b>
                  <ol className="level-ladder mt-2" aria-label="Escalation levels">
                    {detail.escalationPolicy.levels.map((level) => (
                      <li key={level.level}>
                        <span className="level-no">{level.level}</span>
                        <span className="min-w-0 break-words">
                          {level.label || ESCALATION_TARGET_LABELS[level.target]} <span className="text-muted">· t+{level.minutesFromStart} min</span>
                        </span>
                      </li>
                    ))}
                  </ol>
                </>
              ) : (
                <p className="m-0 text-[11px] text-muted">None — alerts for this team use the built-in team-lead escalation.</p>
              )}
            </Panel>
            <HandoversPanel detail={detail} canWrite={staff && (editorRole || participant || teamMember)} />
          </div>
        </div>
      </div>
      {editing && <ScheduleEditor detail={detail} onClose={() => setEditing(false)} />}
    </>
  );
}
