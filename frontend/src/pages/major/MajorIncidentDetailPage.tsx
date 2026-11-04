import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ClipboardCheck, ExternalLink, Globe, Megaphone, Pencil, Send, UsersRound } from 'lucide-react';
import {
  useAddMajorAction,
  useAddTimelineEntry,
  useCreateReview,
  useMajorIncident,
  useMajorRoles,
  useMajorStatus,
  usePostStakeholderUpdate,
  usePublishStakeholderUpdate,
  useUpdateMajor,
} from '../../api/majorIncidents';
import { useAssignableUsers } from '../../api/users';
import { Avatar } from '../../components/Avatar';
import { DemoBadge, PriorityBadge, StatusBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { CheckboxField, SelectField, TextareaField, TextField } from '../../components/FormField';
import { Fact, ServiceMultiPicker, TextBlock, ToneBadge } from '../../components/itsm/ItsmParts';
import { ActionItemsPanel } from '../../components/major/ActionItemsPanel';
import { Modal } from '../../components/Modal';
import '../../components/oncall/oncall.css';
import { AlertStatusBadge, SeverityBadge } from '../../components/ops/OpsParts';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { STAFF } from '../../lib/constants';
import { formatDateTime, formatMinutes, formatRelative, isoToLocalInput, localInputToIso } from '../../lib/format';
import {
  MAJOR_STATUS_LABELS,
  majorTransitionLabel,
  PIR_STATUS_LABELS,
  PIR_STATUS_TONES,
  STAKEHOLDER_STATUSES,
  STAKEHOLDER_TONES,
  TIMELINE_ENTRY_TYPES,
  TIMELINE_LABELS,
  TIMELINE_TONES,
} from '../../lib/response';
import type { MajorDetail, MajorStatus, PersonRef, StakeholderStatus, StakeholderUpdateRequest, TimelineEntryType } from '../../lib/responseTypes';
import { MajorStatusBadge } from './MajorIncidentsPage';

function fieldErrors(error: unknown) {
  const errors = error ? toApiError(error).fieldErrors ?? [] : [];
  return { errors, get: (field: string) => errors.find((entry) => entry.field === field)?.message };
}

function StatusDialog({ major, target, onClose }: { major: MajorDetail; target: MajorStatus; onClose: () => void }) {
  const status = useMajorStatus(major.id);
  const toast = useToast();
  const [note, setNote] = useState('');
  const { errors, get } = fieldErrors(status.error);
  const needsNote = target === 'RESOLVED' && !major.resolutionSummary?.trim();
  return (
    <Modal
      open
      onClose={onClose}
      title={`${majorTransitionLabel(major.status, target)} — ${major.number}`}
      eyebrow="MAJOR INCIDENT STATUS"
      description={
        target === 'CLOSED'
          ? 'Closing needs the incident resolved or closed, and opens the post-incident review (draft, owned by the commander) when there is none.'
          : target === 'RESOLVED'
            ? needsNote
              ? 'Record how the incident was resolved — the note becomes the resolution summary.'
              : 'The existing resolution summary is kept; a note is optional.'
            : undefined
      }
      dismissible={!status.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={status.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={status.isPending || (needsNote && note.trim().length === 0)}
            onClick={() =>
              status.mutate(
                { status: target, note: note.trim() || null },
                {
                  onSuccess: (updated) => {
                    toast.success(`${updated.number} is ${MAJOR_STATUS_LABELS[updated.status].toLowerCase()}`, updated.pir && target === 'CLOSED' ? { action: { label: `Open ${updated.pir.number}`, to: `/reviews/${updated.pir.id}` } } : undefined);
                    onClose();
                  },
                },
              )
            }
          >
            {status.isPending ? 'Saving…' : majorTransitionLabel(major.status, target)}
          </button>
        </>
      }
    >
      <TextareaField label={needsNote ? 'Resolution note' : 'Note (optional)'} required={needsNote} rows={4} maxLength={2000} value={note} onChange={(event) => setNote(event.target.value)} error={get('note')} />
      {status.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(status.error)}
        </p>
      )}
    </Modal>
  );
}

function RolesDialog({ major, onClose }: { major: MajorDetail; onClose: () => void }) {
  const roles = useMajorRoles(major.id);
  const staff = useAssignableUsers(null);
  const toast = useToast();
  const [commanderId, setCommanderId] = useState(major.commander ? String(major.commander.id) : '0');
  const [techLeadId, setTechLeadId] = useState(major.techLead ? String(major.techLead.id) : '0');
  const [commsLeadId, setCommsLeadId] = useState(major.commsLead ? String(major.commsLead.id) : '0');
  const { errors, get } = fieldErrors(roles.error);
  const people = staff.data ?? [];
  const select = (label: string, value: string, onChange: (value: string) => void, current: PersonRef | null, field: string) => (
    <SelectField label={label} value={value} onChange={(event) => onChange(event.target.value)} error={get(field)}>
      <option value="0">Not assigned</option>
      {current && !people.some((person) => person.id === current.id) && <option value={current.id}>{current.name}</option>}
      {people.map((person) => (
        <option key={person.id} value={person.id}>
          {person.fullName}
        </option>
      ))}
    </SelectField>
  );
  return (
    <Modal
      open
      onClose={onClose}
      title={`Command roles — ${major.number}`}
      eyebrow="MAJOR INCIDENT COMMAND"
      description="New role holders are notified."
      dismissible={!roles.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={roles.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={roles.isPending}
            onClick={() =>
              roles.mutate(
                { commanderId: Number(commanderId), techLeadId: Number(techLeadId), commsLeadId: Number(commsLeadId) },
                {
                  onSuccess: () => {
                    toast.success('Command roles updated');
                    onClose();
                  },
                },
              )
            }
          >
            {roles.isPending ? 'Saving…' : 'Save roles'}
          </button>
        </>
      }
    >
      {select('Incident commander', commanderId, setCommanderId, major.commander, 'commanderId')}
      {select('Technical lead', techLeadId, setTechLeadId, major.techLead, 'techLeadId')}
      {select('Communications lead', commsLeadId, setCommsLeadId, major.commsLead, 'commsLeadId')}
      {roles.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(roles.error)}
        </p>
      )}
    </Modal>
  );
}

function DetailsDialog({ major, onClose }: { major: MajorDetail; onClose: () => void }) {
  const update = useUpdateMajor(major.id);
  const toast = useToast();
  const [summary, setSummary] = useState(major.summary ?? '');
  const [impactSummary, setImpact] = useState(major.impactSummary ?? '');
  const [investigationNotes, setInvestigation] = useState(major.investigationNotes ?? '');
  const [resolutionSummary, setResolution] = useState(major.resolutionSummary ?? '');
  const [bridgeUrl, setBridgeUrl] = useState(major.bridgeUrl ?? '');
  const [serviceIds, setServiceIds] = useState(major.services.map((service) => service.id));
  const { errors, get } = fieldErrors(update.error);
  const bridgeInvalid = bridgeUrl.trim() !== '' && !/^https?:\/\/.+/.test(bridgeUrl.trim());
  return (
    <Modal
      open
      onClose={onClose}
      title={`Edit ${major.number}`}
      eyebrow="MAJOR INCIDENT"
      size="lg"
      dismissible={!update.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={update.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={update.isPending || bridgeInvalid}
            onClick={() =>
              update.mutate(
                { summary, impactSummary, investigationNotes, resolutionSummary, bridgeUrl: bridgeUrl.trim(), serviceIds },
                {
                  onSuccess: () => {
                    toast.success(`${major.number} updated`);
                    onClose();
                  },
                },
              )
            }
          >
            {update.isPending ? 'Saving…' : 'Save'}
          </button>
        </>
      }
    >
      <TextareaField label="Summary" rows={3} maxLength={2000} value={summary} onChange={(event) => setSummary(event.target.value)} error={get('summary')} />
      <TextField label="Bridge / war-room link" type="url" placeholder="https://" maxLength={500} value={bridgeUrl} onChange={(event) => setBridgeUrl(event.target.value)} error={get('bridgeUrl') ?? (bridgeInvalid ? 'Must be an http(s) link' : undefined)} />
      <ServiceMultiPicker label="Impacted services" value={serviceIds} onChange={setServiceIds} error={get('serviceIds')} />
      <TextareaField label="Impact" rows={4} maxLength={20000} value={impactSummary} onChange={(event) => setImpact(event.target.value)} error={get('impactSummary')} />
      <TextareaField label="Investigation notes" rows={5} maxLength={50000} value={investigationNotes} onChange={(event) => setInvestigation(event.target.value)} error={get('investigationNotes')} />
      <TextareaField label="Resolution" rows={4} maxLength={20000} value={resolutionSummary} onChange={(event) => setResolution(event.target.value)} error={get('resolutionSummary')} />
      {update.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(update.error)}
        </p>
      )}
    </Modal>
  );
}

function RolePerson({ label, person }: { label: string; person: PersonRef | null }) {
  return (
    <div className="oncall-person">
      {person ? <Avatar name={person.name} size="sm" /> : null}
      <div>
        <small>{label}</small>
        {person ? <b className="block break-words text-[12px]">{person.name}</b> : <span className="muted-text text-[11px]">Not assigned</span>}
        {person?.email && <small>{person.email}</small>}
      </div>
    </div>
  );
}

function TimelinePanel({ major, canWrite }: { major: MajorDetail; canWrite: boolean }) {
  const add = useAddTimelineEntry(major.id);
  const toast = useToast();
  const [entryType, setEntryType] = useState<TimelineEntryType>('NOTE');
  const [body, setBody] = useState('');
  const [occurredAt, setOccurredAt] = useState('');
  return (
    <Panel title={`War-room timeline (${major.timeline.length})`} subtitle="Notes, decisions, actions and investigation — oldest first">
      {major.timeline.length === 0 ? (
        <p className="m-0 text-[11px] text-muted">No timeline entries yet.</p>
      ) : (
        <ol className="war-room" aria-label="War-room timeline">
          {major.timeline.map((entry) => (
            <li key={entry.id} data-type={entry.entryType}>
              <small className="flex flex-wrap items-center gap-1.5 text-[10px] text-muted">
                <ToneBadge value={entry.entryType} label={TIMELINE_LABELS[entry.entryType] ?? entry.entryType} tone={TIMELINE_TONES[entry.entryType] ?? 'neutral'} />
                <span title={formatDateTime(entry.occurredAt)}>{formatDateTime(entry.occurredAt)}</span>
                <span>· {entry.actorName ?? 'System'}</span>
                <DemoBadge demo={entry.demo} />
              </small>
              <p className="m-0 whitespace-pre-wrap break-words text-[12px]">{entry.body}</p>
            </li>
          ))}
        </ol>
      )}
      {canWrite && (
        <form
          className="action-card mt-3"
          aria-label="Add timeline entry"
          onSubmit={(event) => {
            event.preventDefault();
            add.mutate(
              { entryType, body: body.trim(), occurredAt: occurredAt ? localInputToIso(occurredAt) : null },
              {
                onSuccess: () => {
                  toast.success(`${TIMELINE_LABELS[entryType]} added to the timeline`);
                  setBody('');
                  setOccurredAt('');
                },
              },
            );
          }}
        >
          <div className="form-row">
            <SelectField label="Entry type" value={entryType} onChange={(event) => setEntryType(event.target.value as TimelineEntryType)}>
              {TIMELINE_ENTRY_TYPES.map((type) => (
                <option key={type} value={type}>
                  {TIMELINE_LABELS[type]}
                </option>
              ))}
            </SelectField>
            <TextField label="Occurred at (optional)" type="datetime-local" value={occurredAt} max={isoToLocalInput(new Date().toISOString())} onChange={(event) => setOccurredAt(event.target.value)} hint="Defaults to now" />
          </div>
          <TextareaField label="Entry" required rows={3} maxLength={4000} value={body} onChange={(event) => setBody(event.target.value)} />
          {add.isError && (
            <p className="field-error" role="alert">
              {errorMessage(add.error)}
            </p>
          )}
          <div className="flex justify-end">
            <button type="submit" className="gold-btn compact" disabled={!body.trim() || add.isPending}>
              {add.isPending ? 'Adding…' : 'Add to timeline'}
            </button>
          </div>
        </form>
      )}
    </Panel>
  );
}

/**
 * Stakeholder updates: internal by default; publishing to the public status page (status page editors, the commander or
 * the communications lead — `canPublish`) always goes through a confirmation dialog because subscribers are e-mailed.
 */
export function StakeholderUpdatesPanel({ major, canWrite }: { major: MajorDetail; canWrite: boolean }) {
  const post = usePostStakeholderUpdate(major.id);
  const publish = usePublishStakeholderUpdate(major.id);
  const toast = useToast();
  const [status, setStatus] = useState<StakeholderStatus>('INVESTIGATING');
  const [title, setTitle] = useState('');
  const [message, setMessage] = useState('');
  const [toStatusPage, setToStatusPage] = useState(false);
  const [confirming, setConfirming] = useState<StakeholderUpdateRequest | null>(null);
  const [publishing, setPublishing] = useState<number | null>(null);
  const { errors, get } = fieldErrors(post.error);
  const publishTarget = major.updates.find((update) => update.id === publishing);

  const send = (body: StakeholderUpdateRequest) =>
    post.mutate(body, {
      onSuccess: (created) => {
        toast.success(created.published ? 'Update posted and published on the status page' : 'Stakeholder update posted');
        setTitle('');
        setMessage('');
        setToStatusPage(false);
        setConfirming(null);
      },
      onError: (error) => {
        if (body.publishToStatusPage) {
          setConfirming(null);
          toast.error(error);
        }
      },
    });

  const submit = () => {
    const body: StakeholderUpdateRequest = { status, title: title.trim() || null, message: message.trim(), publishToStatusPage: major.canPublish && toStatusPage };
    if (body.publishToStatusPage) setConfirming(body);
    else send(body);
  };

  return (
    <Panel title={`Stakeholder updates (${major.updates.length})`} subtitle="Internal updates notify leadership, the role holders and the assignee — newest first">
      {major.updates.length === 0 ? (
        <p className="m-0 text-[11px] text-muted">No stakeholder updates yet.</p>
      ) : (
        <ul className="m-0 grid list-none gap-2 p-0" aria-label="Stakeholder updates">
          {major.updates.map((update) => (
            <li key={update.id} className="list-row !items-start flex-wrap sm:flex-nowrap">
              <div className="min-w-0 flex-1">
                <b className="flex flex-wrap items-center gap-1.5">
                  <span className="break-words">{update.title}</span>
                  <ToneBadge value={update.status} tone={STAKEHOLDER_TONES[update.status] ?? 'neutral'} />
                  {update.published ? (
                    <ToneBadge value="published" label={<><Globe size={10} aria-hidden="true" /> On status page</>} tone="success" />
                  ) : (
                    <ToneBadge value="internal" label="Internal" tone="muted" />
                  )}
                  <DemoBadge demo={update.demo} />
                </b>
                <p className="m-0 mt-1 whitespace-pre-wrap break-words text-[12px]">{update.message}</p>
                <small className="block">
                  {update.postedByName ?? 'Someone'} · <span title={formatDateTime(update.postedAt)}>{formatRelative(update.postedAt)}</span>
                  {update.publishedAt ? ` · published ${formatDateTime(update.publishedAt)}` : ''}
                </small>
              </div>
              {!update.published && major.canPublish && (
                <button type="button" className="subtle-btn compact shrink-0" onClick={() => setPublishing(update.id)} aria-label={`Publish “${update.title}” to the status page`}>
                  <Globe size={12} aria-hidden="true" /> Publish
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canWrite && (
        <form
          className="action-card mt-3"
          aria-label="Compose stakeholder update"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <div className="form-row">
            <SelectField label="Status" value={status} onChange={(event) => setStatus(event.target.value as StakeholderStatus)} error={get('status')}>
              {STAKEHOLDER_STATUSES.map((value) => (
                <option key={value} value={value}>
                  {value.charAt(0) + value.slice(1).toLowerCase()}
                </option>
              ))}
            </SelectField>
            <TextField label="Title" maxLength={200} placeholder="Service disruption: <service>" value={title} onChange={(event) => setTitle(event.target.value)} error={get('title')} />
          </div>
          <TextareaField label="Message" required rows={4} maxLength={5000} value={message} onChange={(event) => setMessage(event.target.value)} error={get('message')} />
          {major.canPublish && <CheckboxField label="Also publish on the public status page" checked={toStatusPage} onChange={(event) => setToStatusPage(event.target.checked)} />}
          {post.isError && !errors.length && !confirming && (
            <p className="field-error" role="alert">
              {errorMessage(post.error)}
            </p>
          )}
          <div className="flex justify-end">
            <button type="submit" className="gold-btn compact" disabled={!message.trim() || post.isPending}>
              <Send size={12} aria-hidden="true" /> {post.isPending ? 'Posting…' : toStatusPage && major.canPublish ? 'Post and publish…' : 'Post update'}
            </button>
          </div>
        </form>
      )}
      <ConfirmDialog
        open={confirming !== null}
        onClose={() => setConfirming(null)}
        title="Publish to the public status page?"
        message={
          <span>
            “{confirming?.title || 'Service disruption'}” will be posted as a <b>public</b> status-page update linked to {major.incident.number}, and confirmed subscribers are e-mailed. This cannot be
            unpublished from here.
          </span>
        }
        confirmLabel="Post and publish"
        pending={post.isPending}
        error={post.isError && confirming ? errorMessage(post.error) : null}
        onConfirm={() => confirming && send(confirming)}
      />
      <ConfirmDialog
        open={publishing !== null}
        onClose={() => setPublishing(null)}
        title="Publish this update to the status page?"
        message={
          <span>
            “{publishTarget?.title ?? ''}” becomes a <b>public</b> status-page update, and confirmed subscribers are e-mailed.
          </span>
        }
        confirmLabel="Publish"
        pending={publish.isPending}
        error={publish.isError ? errorMessage(publish.error) : null}
        onConfirm={() =>
          publishing !== null &&
          publish.mutate(publishing, {
            onSuccess: () => {
              toast.success('Published on the status page');
              setPublishing(null);
            },
          })
        }
      />
    </Panel>
  );
}

function ReviewPanel({ major, canCommand }: { major: MajorDetail; canCommand: boolean }) {
  const create = useCreateReview();
  const toast = useToast();
  const navigate = useNavigate();
  return (
    <Panel title="Post-incident review">
      {major.pir ? (
        <Link to={`/reviews/${major.pir.id}`} className="list-row no-underline">
          <span className="icon-tile sm" aria-hidden="true">
            <ClipboardCheck size={14} />
          </span>
          <div className="min-w-0">
            <b className="flex flex-wrap items-center gap-1.5">
              <span className="id-cell">{major.pir.number}</span>
              <ToneBadge value={major.pir.status} label={PIR_STATUS_LABELS[major.pir.status]} tone={PIR_STATUS_TONES[major.pir.status]} />
            </b>
            <small>Open the review</small>
          </div>
        </Link>
      ) : (
        <>
          <p className="m-0 text-[11px] text-muted">No review yet — closing the major incident opens one automatically.</p>
          {canCommand && (
            <button
              type="button"
              className="subtle-btn compact mt-2"
              disabled={create.isPending}
              onClick={() =>
                create.mutate(
                  { incidentId: major.incident.id },
                  {
                    onSuccess: (review) => {
                      toast.success(`${review.number} created`);
                      navigate(`/reviews/${review.id}`);
                    },
                    onError: (error) => toast.error(error),
                  },
                )
              }
            >
              <ClipboardCheck size={12} aria-hidden="true" /> {create.isPending ? 'Creating…' : 'Start review now'}
            </button>
          )}
        </>
      )}
    </Panel>
  );
}

/** /major-incidents/:id — the command view. */
export default function MajorIncidentDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const valid = Number.isInteger(id) && id > 0;
  const { hasRole } = useAuth();
  const staff = hasRole(...STAFF);
  const query = useMajorIncident(valid ? id : null);
  const addAction = useAddMajorAction(valid ? id : 0);
  const [target, setTarget] = useState<MajorStatus | null>(null);
  const [editingRoles, setEditingRoles] = useState(false);
  const [editing, setEditing] = useState(false);
  const major = query.data;
  const crumbs = [{ label: 'Major Incidents', to: '/major-incidents' }];

  if (!valid || (query.isError && toApiError(query.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[...crumbs, { label: 'Not found' }]} title="Major incident not found" />
        <EmptyState title="This major incident does not exist" action={<Link to="/major-incidents" className="subtle-btn compact">Back to major incidents</Link>} />
      </>
    );
  }
  if (query.isLoading) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Loading major incident…" />
        <LoadingState variant="panel" />
      </>
    );
  }
  if (!major) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Major incident" />
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      </>
    );
  }

  const closed = major.status === 'CLOSED';
  const canWrite = staff && !closed;
  const transitions = major.canCommand ? major.allowedTransitions : [];

  return (
    <>
      <PageHeader
        crumbs={[...crumbs, { label: major.number }]}
        title={major.incident.title}
        actions={
          <>
            {canWrite && (
              <button type="button" className="subtle-btn" onClick={() => setEditing(true)}>
                <Pencil size={14} aria-hidden="true" /> Edit details
              </button>
            )}
            {transitions.map((to, index) => (
              <button key={to} type="button" className={index === 0 ? 'gold-btn' : 'subtle-btn'} onClick={() => setTarget(to)}>
                {majorTransitionLabel(major.status, to)}
              </button>
            ))}
          </>
        }
      >
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
          <span className="id-cell">{major.number}</span>
          <MajorStatusBadge status={major.status} />
          <Link to={`/incidents/${major.incident.id}`} className="id-cell">
            {major.incident.number}
          </Link>
          <PriorityBadge priority={major.incident.priority} />
          <StatusBadge status={major.incident.status} />
          <DemoBadge demo={major.demo} />
          <span>
            Declared {formatDateTime(major.declaredAt)}
            {major.declaredByName ? ` by ${major.declaredByName}` : ''} · {formatMinutes(major.durationMinutes)}
          </span>
        </div>
      </PageHeader>
      <div className="page-stack">
        {major.bridgeUrl && (
          <div className="callout" role="note">
            <Megaphone size={16} aria-hidden="true" />
            <div className="min-w-0">
              War room:{' '}
              <a href={major.bridgeUrl} target="_blank" rel="noopener noreferrer" className="break-all">
                {major.bridgeUrl} <ExternalLink size={11} aria-hidden="true" className="inline" />
              </a>
            </div>
          </div>
        )}
        <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Situation">
              <TextBlock text={major.summary} empty="No summary yet." />
              <div className="mt-3 grid gap-3">
                <div>
                  <h4 className="mb-1 text-[11px] font-semibold">Impact</h4>
                  <TextBlock text={major.impactSummary} />
                </div>
                <div>
                  <h4 className="mb-1 text-[11px] font-semibold">Investigation</h4>
                  <TextBlock text={major.investigationNotes} />
                </div>
                <div>
                  <h4 className="mb-1 text-[11px] font-semibold">Resolution</h4>
                  <TextBlock text={major.resolutionSummary} />
                </div>
              </div>
            </Panel>
            <TimelinePanel major={major} canWrite={canWrite} />
            <StakeholderUpdatesPanel major={major} canWrite={canWrite} />
            <ActionItemsPanel
              title="Action items"
              items={major.actionItems}
              types={['FOLLOW_UP', 'CORRECTIVE', 'PREVENTIVE']}
              defaultType="FOLLOW_UP"
              canAdd={canWrite}
              canEdit={() => staff}
              adding={addAction}
              onAdd={(body, done) => addAction.mutate(body, { onSuccess: done })}
            />
          </div>
          <div className="grid min-w-0 gap-[14px]">
            <Panel
              title="Command roles"
              actions={
                major.canCommand && !closed ? (
                  <button type="button" className="subtle-btn compact" onClick={() => setEditingRoles(true)}>
                    <UsersRound size={12} aria-hidden="true" /> Assign
                  </button>
                ) : undefined
              }
            >
              <div className="grid gap-3">
                <RolePerson label="Incident commander" person={major.commander} />
                <RolePerson label="Technical lead" person={major.techLead} />
                <RolePerson label="Communications lead" person={major.commsLead} />
              </div>
            </Panel>
            <Panel title="Key facts">
              <div className="detail-grid">
                <Fact label="Impacted services">{major.services.length ? major.services.map((service) => service.name).join(', ') : '—'}</Fact>
                <Fact label="Duration">{formatMinutes(major.durationMinutes)}</Fact>
                <Fact label="Resolved">{major.resolvedAt ? formatDateTime(major.resolvedAt) : '—'}</Fact>
                <Fact label="Closed">{major.closedAt ? formatDateTime(major.closedAt) : '—'}</Fact>
              </div>
            </Panel>
            <ReviewPanel major={major} canCommand={major.canCommand} />
            <Panel title={`Linked alerts (${major.alerts.length})`}>
              {major.alerts.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No alerts are linked to the incident.</p>
              ) : (
                <ul className="m-0 grid list-none gap-1 p-0" aria-label="Linked alerts">
                  {major.alerts.map((alert) => (
                    <li key={alert.id} className="list-row flex-wrap sm:flex-nowrap">
                      <div className="min-w-0">
                        <b className="flex flex-wrap items-center gap-1.5">
                          <Link to={`/alerts/${alert.id}`} className="id-cell">
                            {alert.number}
                          </Link>
                          <span className="min-w-0 break-words">{alert.title}</span>
                        </b>
                      </div>
                      <span className="flex shrink-0 gap-1">
                        <SeverityBadge severity={alert.severity} />
                        <AlertStatusBadge status={alert.status} />
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>
        </div>
      </div>
      {target && <StatusDialog major={major} target={target} onClose={() => setTarget(null)} />}
      {editingRoles && <RolesDialog major={major} onClose={() => setEditingRoles(false)} />}
      {editing && <DetailsDialog major={major} onClose={() => setEditing(false)} />}
    </>
  );
}
