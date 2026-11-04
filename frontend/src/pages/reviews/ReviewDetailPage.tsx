import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { CheckCircle2, Circle, Download, History, Plus, RefreshCw, RotateCcw, Save, Send, Stamp, X } from 'lucide-react';
import { useAddReviewAction, useReview, useReviewCommand, useReviewPdf, useUpdateReview } from '../../api/majorIncidents';
import { useAssignableUsers } from '../../api/users';
import { DemoBadge, StatusBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { Fact, TextBlock, ToneBadge } from '../../components/itsm/ItsmParts';
import { ActionItemsPanel } from '../../components/major/ActionItemsPanel';
import '../../components/oncall/oncall.css';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { PIR_APPROVERS } from '../../lib/constants';
import { formatDateTime, formatMinutes, formatNumber, formatRelative, titleCase } from '../../lib/format';
import { APPROVAL_STATUS_TONES } from '../../lib/itsm';
import type { PirDetail, PirMetrics, PirUpdateRequest } from '../../lib/responseTypes';
import { PirStatusBadge } from './ReviewsPage';

type SectionKey = 'summary' | 'rootCause' | 'impact' | 'detectionGaps' | 'responseAnalysis' | 'lessonsLearned';
const SECTIONS: { key: SectionKey; label: string; required?: boolean; hint?: string }[] = [
  { key: 'summary', label: 'Summary', required: true, hint: 'What happened, in a few sentences' },
  { key: 'rootCause', label: 'Root cause', required: true },
  { key: 'impact', label: 'Impact', required: true, hint: 'Who and what was affected, for how long' },
  { key: 'detectionGaps', label: 'Detection gaps', hint: 'Why it was not detected sooner' },
  { key: 'responseAnalysis', label: 'Response analysis', hint: 'What went well, what slowed the response' },
  { key: 'lessonsLearned', label: 'Lessons learned' },
];

type Draft = Record<SectionKey, string> & { title: string; ownerId: string; contributingFactors: string[] };

function draftOf(review: PirDetail): Draft {
  return {
    title: review.title,
    ownerId: review.owner ? String(review.owner.id) : '',
    contributingFactors: review.contributingFactors ?? [],
    summary: review.summary ?? '',
    rootCause: review.rootCause ?? '',
    impact: review.impact ?? '',
    detectionGaps: review.detectionGaps ?? '',
    responseAnalysis: review.responseAnalysis ?? '',
    lessonsLearned: review.lessonsLearned ?? '',
  };
}

function AnalysisEditor({ review }: { review: PirDetail }) {
  const update = useUpdateReview(review.id);
  const staff = useAssignableUsers(null);
  const toast = useToast();
  const saved = useMemo(() => draftOf(review), [review]);
  const [draft, setDraft] = useState<Draft>(saved);
  const [factor, setFactor] = useState('');
  useEffect(() => setDraft(saved), [saved]);
  const errors = update.isError ? toApiError(update.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field || error.field.startsWith(`${field}[`))?.message;
  const dirty = JSON.stringify(draft) !== JSON.stringify(saved);
  const people = staff.data ?? [];

  const save = () => {
    const body: PirUpdateRequest = {
      title: draft.title.trim(),
      ownerId: draft.ownerId ? Number(draft.ownerId) : null,
      contributingFactors: draft.contributingFactors,
      summary: draft.summary,
      rootCause: draft.rootCause,
      impact: draft.impact,
      detectionGaps: draft.detectionGaps,
      responseAnalysis: draft.responseAnalysis,
      lessonsLearned: draft.lessonsLearned,
    };
    update.mutate(body, { onSuccess: () => toast.success(`${review.number} saved`) });
  };

  const addFactor = () => {
    const value = factor.trim();
    if (!value || draft.contributingFactors.includes(value) || draft.contributingFactors.length >= 20) return;
    setDraft((current) => ({ ...current, contributingFactors: [...current.contributingFactors, value] }));
    setFactor('');
  };

  return (
    <Panel
      title="Analysis"
      subtitle="Editable while the review is a draft or was rejected"
      actions={
        <span className="flex gap-1">
          <button type="button" className="subtle-btn compact" disabled={!dirty || update.isPending} onClick={() => setDraft(saved)}>
            <RotateCcw size={12} aria-hidden="true" /> Reset
          </button>
          <button type="button" className="gold-btn compact" disabled={!dirty || update.isPending || draft.title.trim().length < 5} onClick={save}>
            <Save size={12} aria-hidden="true" /> {update.isPending ? 'Saving…' : 'Save'}
          </button>
        </span>
      }
    >
      <div className="form-row">
        <TextField label="Title" required maxLength={200} value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} error={fieldError('title')} />
        <SelectField label="Owner" value={draft.ownerId} onChange={(event) => setDraft({ ...draft, ownerId: event.target.value })} error={fieldError('ownerId')}>
          <option value="">No owner</option>
          {review.owner && !people.some((person) => person.id === review.owner?.id) && <option value={review.owner.id}>{review.owner.name}</option>}
          {people.map((person) => (
            <option key={person.id} value={person.id}>
              {person.fullName}
            </option>
          ))}
        </SelectField>
      </div>
      {SECTIONS.map((section) => (
        <TextareaField
          key={section.key}
          label={section.label}
          required={section.required}
          hint={section.hint}
          rows={section.key === 'summary' || section.key === 'rootCause' ? 4 : 3}
          maxLength={20000}
          value={draft[section.key]}
          onChange={(event) => setDraft({ ...draft, [section.key]: event.target.value })}
          error={fieldError(section.key)}
        />
      ))}
      <div className="field" data-invalid={fieldError('contributingFactors') ? 'true' : undefined}>
        <div className="field-label">
          <label htmlFor="pir-factor">Contributing factors</label>
        </div>
        {draft.contributingFactors.length > 0 && (
          <div className="tag-row mt-2" aria-label="Contributing factors added">
            {draft.contributingFactors.map((entry) => (
              <span key={entry} className="chip max-w-full">
                <span className="break-words">{entry}</span>
                <button
                  type="button"
                  className="icon-btn sm !h-4 !w-4"
                  aria-label={`Remove ${entry}`}
                  onClick={() => setDraft((current) => ({ ...current, contributingFactors: current.contributingFactors.filter((other) => other !== entry) }))}
                >
                  <X size={10} />
                </button>
              </span>
            ))}
          </div>
        )}
        <div className="mt-2 flex gap-2">
          <input
            id="pir-factor"
            className="control sm min-w-0 flex-1"
            maxLength={500}
            placeholder="e.g. Batch job started before the cache warmed up"
            value={factor}
            onChange={(event) => setFactor(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault();
                addFactor();
              }
            }}
          />
          <button type="button" className="subtle-btn compact shrink-0" disabled={!factor.trim() || draft.contributingFactors.length >= 20} onClick={addFactor}>
            <Plus size={12} aria-hidden="true" /> Add
          </button>
        </div>
        {fieldError('contributingFactors') && (
          <span className="field-error" role="alert">
            {fieldError('contributingFactors')}
          </span>
        )}
      </div>
      {update.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(update.error)}
        </p>
      )}
    </Panel>
  );
}

function AnalysisView({ review }: { review: PirDetail }) {
  return (
    <Panel title="Analysis">
      <div className="grid gap-3">
        {SECTIONS.map((section) => (
          <div key={section.key}>
            <h4 className="mb-1 text-[11px] font-semibold">{section.label}</h4>
            <TextBlock text={review[section.key]} />
          </div>
        ))}
        <div>
          <h4 className="mb-1 text-[11px] font-semibold">Contributing factors</h4>
          {review.contributingFactors.length ? (
            <ul className="m-0 grid gap-1 pl-4 text-[12px]">
              {review.contributingFactors.map((entry) => (
                <li key={entry} className="break-words">
                  {entry}
                </li>
              ))}
            </ul>
          ) : (
            <p className="m-0 text-[11px] text-muted">Not recorded yet.</p>
          )}
        </div>
      </div>
    </Panel>
  );
}

const METRIC_TILES: { key: keyof PirMetrics; label: string; minutes?: boolean }[] = [
  { key: 'minutesToDetect', label: 'Time to detect', minutes: true },
  { key: 'minutesToAcknowledge', label: 'Time to first response', minutes: true },
  { key: 'minutesToDeclare', label: 'Time to declare major', minutes: true },
  { key: 'minutesToResolve', label: 'Time to resolve', minutes: true },
  { key: 'alerts', label: 'Alerts' },
  { key: 'pages', label: 'On-call pages' },
  { key: 'linkedReports', label: 'Linked reports' },
];

const KEY_TIMES: { key: keyof PirMetrics; label: string }[] = [
  { key: 'detectedAt', label: 'Detected' },
  { key: 'reportedAt', label: 'Reported' },
  { key: 'firstResponseAt', label: 'First response' },
  { key: 'acknowledgedAt', label: 'First alert acknowledged' },
  { key: 'declaredAt', label: 'Declared major' },
  { key: 'resolvedAt', label: 'Resolved' },
];

function MetricsPanel({ metrics }: { metrics: PirMetrics | null }) {
  if (!metrics) return null;
  const number = (value: unknown) => (typeof value === 'number' ? value : null);
  return (
    <Panel title="Metrics" subtitle="Computed with the timeline from the incident, alerts and pages">
      <div className="metric-tiles">
        {METRIC_TILES.map((tile) => {
          const value = number(metrics[tile.key]);
          return (
            <div key={tile.key}>
              <small>{tile.label}</small>
              <b>{tile.minutes ? (value === null ? 'n/a' : formatMinutes(value)) : formatNumber(value)}</b>
            </div>
          );
        })}
        <div>
          <small>SLA outcome</small>
          <b>{typeof metrics.slaOutcome === 'string' && metrics.slaOutcome ? titleCase(metrics.slaOutcome) : '—'}</b>
        </div>
      </div>
      <dl className="detail-grid mt-3">
        {KEY_TIMES.map((entry) => (
          <div key={entry.key}>
            <small>{entry.label}</small>
            <b>{typeof metrics[entry.key] === 'string' ? formatDateTime(metrics[entry.key] as string) : '—'}</b>
          </div>
        ))}
      </dl>
    </Panel>
  );
}

const TIMELINE_PREVIEW = 40;

function ReviewTimeline({ review, canRebuild }: { review: PirDetail; canRebuild: boolean }) {
  const command = useReviewCommand(review.id);
  const toast = useToast();
  const [confirm, setConfirm] = useState(false);
  const [all, setAll] = useState(false);
  const items = all ? review.timeline : review.timeline.slice(0, TIMELINE_PREVIEW);
  return (
    <Panel
      title={`Timeline (${review.timeline.length})`}
      subtitle={review.timelineBuiltAt ? `Built ${formatRelative(review.timelineBuiltAt)} from activity, SLA events, alerts, pages, the war room and stakeholder updates` : 'Not built yet'}
      actions={
        canRebuild ? (
          <button type="button" className="subtle-btn compact" onClick={() => setConfirm(true)}>
            <RefreshCw size={12} aria-hidden="true" /> Rebuild
          </button>
        ) : undefined
      }
    >
      {review.timeline.length === 0 ? (
        <EmptyState title="No timeline entries" icon={<History size={18} />} />
      ) : (
        <ol className="war-room" aria-label="Review timeline">
          {items.map((item, index) => (
            <li key={`${item.at}-${index}`}>
              <small className="flex flex-wrap items-center gap-1.5 text-[10px] text-muted">
                <ToneBadge value={item.kind} tone={item.kind === 'ALERT' || item.kind === 'PAGE' ? 'warning' : item.kind === 'WAR_ROOM' ? 'gold' : 'neutral'} />
                <span>{formatDateTime(item.at)}</span>
                {item.actorName && <span>· {item.actorName}</span>}
              </small>
              <b className="break-words text-[12px]">{item.title}</b>
              {item.detail && <p className="m-0 whitespace-pre-wrap break-words text-[11px] text-muted">{item.detail}</p>}
            </li>
          ))}
        </ol>
      )}
      {review.timeline.length > TIMELINE_PREVIEW && (
        <button type="button" className="link-btn mt-2 text-[11px]" onClick={() => setAll((value) => !value)}>
          {all ? 'Show fewer' : `Show all ${review.timeline.length} entries`}
        </button>
      )}
      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        title="Rebuild the timeline?"
        message="The timeline and metrics are recomputed from the current incident activity, alerts, pages, war-room entries and stakeholder updates. The written analysis is not changed."
        confirmLabel="Rebuild timeline"
        pending={command.isPending}
        error={command.isError ? errorMessage(command.error) : null}
        onConfirm={() =>
          command.mutate('timeline/rebuild', {
            onSuccess: (updated) => {
              toast.success(`Timeline rebuilt · ${updated.timeline.length} entries`);
              setConfirm(false);
            },
          })
        }
      />
    </Panel>
  );
}

function ApprovalPanel({ review, approver }: { review: PirDetail; approver: boolean }) {
  return (
    <Panel title="Approval" subtitle="Submitted reviews are approved through the approval engine (incident or IT managers)">
      <div className="detail-grid">
        <Fact label="Status">
          <PirStatusBadge status={review.status} />
        </Fact>
        <Fact label="Approval">{review.approvalStatus ? <ToneBadge value={review.approvalStatus} tone={APPROVAL_STATUS_TONES[review.approvalStatus] ?? 'neutral'} /> : '—'}</Fact>
        <Fact label="Submitted">{review.submittedAt ? formatDateTime(review.submittedAt) : '—'}</Fact>
        <Fact label="Approved">{review.approvedAt ? `${formatDateTime(review.approvedAt)}${review.approvedByName ? ` · ${review.approvedByName}` : ''}` : '—'}</Fact>
      </div>
      {review.reviewComment && (
        <div className={`callout mt-3 ${review.status === 'REJECTED' ? 'red' : ''}`} role="note">
          <div>
            <b>Reviewer comment</b>
            <p className="m-0 mt-1 whitespace-pre-wrap break-words">{review.reviewComment}</p>
          </div>
        </div>
      )}
      {review.approvalId && (
        <Link to={`/approvals?approval=${review.approvalId}`} className="link-btn mt-3 text-[11px]">
          <Stamp size={12} aria-hidden="true" /> {approver && review.status === 'IN_REVIEW' ? 'Decide in the approvals inbox' : 'View the approval'}
        </Link>
      )}
    </Panel>
  );
}

function SubmitChecklist({ review }: { review: PirDetail }) {
  const incidentDone = review.incident.status === 'RESOLVED' || review.incident.status === 'CLOSED';
  const hasAction = review.actions.some((action) => action.status !== 'CANCELLED' && (action.actionType === 'CORRECTIVE' || action.actionType === 'PREVENTIVE'));
  const checks: [string, boolean][] = [
    ['Incident resolved or closed', incidentDone],
    ['Summary', Boolean(review.summary?.trim())],
    ['Root cause', Boolean(review.rootCause?.trim())],
    ['Impact', Boolean(review.impact?.trim())],
    ['At least one corrective or preventive action', hasAction],
  ];
  return (
    <ul className="m-0 grid list-none gap-1 p-0 text-[11px]" aria-label="Ready to submit">
      {checks.map(([label, ok]) => (
        <li key={label} className={`flex items-center gap-1.5 ${ok ? 'green-text' : 'text-muted'}`}>
          {ok ? <CheckCircle2 size={12} aria-hidden="true" /> : <Circle size={12} aria-hidden="true" />} {label}
        </li>
      ))}
    </ul>
  );
}

/** /reviews/:id — post-incident review: analysis, metrics, auto timeline, actions, submit/withdraw, approval state, PDF. */
export default function ReviewDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const valid = Number.isInteger(id) && id > 0;
  const { user, hasRole } = useAuth();
  const query = useReview(valid ? id : null);
  const command = useReviewCommand(valid ? id : 0);
  const addAction = useAddReviewAction(valid ? id : 0);
  const pdf = useReviewPdf();
  const toast = useToast();
  const [confirm, setConfirm] = useState<'submit' | 'withdraw' | null>(null);
  const review = query.data;
  const crumbs = [
    { label: 'Major Incidents', to: '/major-incidents' },
    { label: 'Reviews', to: '/reviews' },
  ];

  if (!valid || (query.isError && toApiError(query.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[...crumbs, { label: 'Not found' }]} title="Review not found" />
        <EmptyState title="This review does not exist" action={<Link to="/reviews" className="subtle-btn compact">Back to reviews</Link>} />
      </>
    );
  }
  if (query.isLoading) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Loading review…" />
        <LoadingState variant="panel" />
      </>
    );
  }
  if (!review) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Review" />
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      </>
    );
  }

  const editableStatus = review.status === 'DRAFT' || review.status === 'REJECTED';
  const editable = review.canEdit && editableStatus;
  const canSubmit = review.canSubmit && editableStatus;
  const canWithdraw = (review.canSubmit || review.canEdit) && review.status === 'IN_REVIEW';
  const submitErrors = command.isError ? toApiError(command.error).fieldErrors ?? [] : [];

  return (
    <>
      <PageHeader
        crumbs={[...crumbs, { label: review.number }]}
        title={review.title}
        actions={
          <>
            <button
              type="button"
              className="subtle-btn"
              disabled={pdf.isPending}
              onClick={() => pdf.mutate({ id: review.id, number: review.number }, { onError: (error) => toast.error(error) })}
            >
              <Download size={14} aria-hidden="true" /> {pdf.isPending ? 'Preparing…' : 'PDF'}
            </button>
            {canWithdraw && (
              <button type="button" className="subtle-btn" onClick={() => setConfirm('withdraw')}>
                <RotateCcw size={14} aria-hidden="true" /> Withdraw
              </button>
            )}
            {canSubmit && (
              <button type="button" className="gold-btn" onClick={() => setConfirm('submit')}>
                <Send size={14} aria-hidden="true" /> {review.status === 'REJECTED' ? 'Resubmit for approval' : 'Submit for approval'}
              </button>
            )}
          </>
        }
      >
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
          <span className="id-cell">{review.number}</span>
          <PirStatusBadge status={review.status} />
          <Link to={`/incidents/${review.incident.id}`} className="id-cell">
            {review.incident.number}
          </Link>
          <StatusBadge status={review.incident.status} />
          {review.majorIncidentId && (
            <Link to={`/major-incidents/${review.majorIncidentId}`} className="id-cell">
              {review.majorIncidentNumber}
            </Link>
          )}
          <span>Owner {review.owner?.name ?? '—'}</span>
          <DemoBadge demo={review.demo} />
        </div>
      </PageHeader>
      <div className="page-stack">
        {review.status === 'REJECTED' && review.reviewComment && (
          <div className="callout red" role="status">
            <div>
              <b>Rejected</b> — {review.reviewComment} Update the analysis and resubmit.
            </div>
          </div>
        )}
        <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <div className="grid min-w-0 gap-[14px]">
            {editable ? <AnalysisEditor review={review} /> : <AnalysisView review={review} />}
            <ActionItemsPanel
              title="Corrective and preventive actions"
              items={review.actions}
              types={['CORRECTIVE', 'PREVENTIVE', 'FOLLOW_UP']}
              defaultType="CORRECTIVE"
              canAdd={review.canEdit && review.status !== 'IN_REVIEW'}
              canEdit={(item) => (review.canEdit && review.status !== 'IN_REVIEW') || item.ownerId === user?.id}
              adding={addAction}
              onAdd={(body, done) => addAction.mutate(body, { onSuccess: done })}
            />
            <ReviewTimeline review={review} canRebuild={editable} />
          </div>
          <div className="grid min-w-0 gap-[14px]">
            <ApprovalPanel review={review} approver={hasRole(...PIR_APPROVERS)} />
            {canSubmit && (
              <Panel title="Ready to submit?">
                <SubmitChecklist review={review} />
              </Panel>
            )}
            <MetricsPanel metrics={review.metrics} />
          </div>
        </div>
      </div>
      <ConfirmDialog
        open={confirm !== null}
        onClose={() => {
          setConfirm(null);
          command.reset();
        }}
        title={confirm === 'withdraw' ? `Withdraw ${review.number}?` : `Submit ${review.number} for approval?`}
        message={
          confirm === 'withdraw' ? (
            'The pending approval is cancelled and the review returns to draft.'
          ) : (
            <span>
              An approval of type <b>post-incident review</b> is requested from the incident and IT managers (72 h deadline). The review is read-only while it is in review; you cannot decide it yourself.
              {submitErrors.length > 0 && (
                <ul className="mt-2 mb-0 pl-4">
                  {submitErrors.map((error) => (
                    <li key={error.field} className="red-text">
                      {titleCase(error.field)}: {error.message}
                    </li>
                  ))}
                </ul>
              )}
            </span>
          )
        }
        confirmLabel={confirm === 'withdraw' ? 'Withdraw' : 'Submit'}
        tone={confirm === 'withdraw' ? 'danger' : 'gold'}
        pending={command.isPending}
        error={command.isError && !submitErrors.length ? errorMessage(command.error) : command.isError ? 'Complete the items above, then submit again.' : null}
        onConfirm={() =>
          confirm &&
          command.mutate(confirm, {
            onSuccess: (updated) => {
              toast.success(confirm === 'withdraw' ? `${updated.number} withdrawn` : `${updated.number} submitted for approval`);
              setConfirm(null);
            },
          })
        }
      />
    </>
  );
}
