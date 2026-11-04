import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { GitMerge, Link2, Scissors, Siren, X, XCircle } from 'lucide-react';
import { useCorrelationAction, useCorrelationGroup, useCorrelationGroups, type GroupAction } from '../../api/observability';
import { SimulatedBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { SeverityBadge } from '../../components/ops/OpsParts';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { errorMessage, getJson } from '../../lib/api';
import { LEVELS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import type { CorrelationGroupDto, CorrelationReason } from '../../lib/r4Types';
import type { IncidentSummary, Level, PageResponse } from '../../lib/types';

const STATUS_TONES = { OPEN: 'warning', CLOSED: 'muted', DISMISSED: 'muted' } as const;

function Reasons({ reasons, label }: { reasons: CorrelationReason[] | null | undefined; label: string }) {
  if (!reasons?.length) return <small className="text-muted">No reasons recorded.</small>;
  return (
    <ul className="m-0 grid list-none gap-1 p-0" aria-label={label}>
      {reasons.map((reason, index) => (
        <li key={`${reason.code}-${index}`} className="flex flex-wrap items-baseline gap-1.5 text-[11px]">
          <ToneBadge value={reason.code} label={titleCase(reason.code)} tone="champagne" />
          <span className="min-w-0 break-words">{reason.text}</span>
          {reason.weight > 0 && <span className="text-muted">+{reason.weight.toFixed(2)}</span>}
        </li>
      ))}
    </ul>
  );
}

type Dialog = 'link' | 'convert' | 'split' | 'merge' | 'dismiss' | null;

function GroupDetail({ id, canRespond, includeSimulated, onClose, onOpen }: { id: number; canRespond: boolean; includeSimulated: boolean; onClose: () => void; onOpen: (id: number) => void }) {
  const group = useCorrelationGroup(id);
  const others = useCorrelationGroups({ status: 'OPEN', includeSimulated, size: 50 }, canRespond);
  const action = useCorrelationAction();
  const toast = useToast();
  const [dialog, setDialog] = useState<Dialog>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [incidentNumber, setIncidentNumber] = useState('');
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [impact, setImpact] = useState<Level | ''>('');
  const [urgency, setUrgency] = useState<Level | ''>('');
  const [mergeIds, setMergeIds] = useState<Set<number>>(new Set());

  const close = () => {
    setDialog(null);
    setLookupError(null);
    action.reset();
  };
  const perform = (next: GroupAction, done: string, after?: (result: CorrelationGroupDto) => void) =>
    action.mutate(
      { id, action: next },
      {
        onSuccess: (result) => {
          toast.success(done);
          close();
          setSelected(new Set());
          after?.(result);
        },
      },
    );

  if (group.isLoading) return <LoadingState variant="panel" label="Loading correlation group…" />;
  if (group.isError || !group.data) return <ErrorState error={group.error} onRetry={() => void group.refetch()} />;
  const g = group.data;
  const members = g.members ?? [];
  const open = g.status === 'OPEN';
  const hasAlert = members.some((member) => member.memberType === 'ALERT');
  const mergeCandidates = (others.data?.content ?? []).filter((other) => other.id !== g.id && other.simulated === g.simulated);

  const linkIncident = async () => {
    setLookupError(null);
    const number = incidentNumber.trim().toUpperCase();
    if (!number) return setLookupError('Enter an incident number');
    try {
      const page = await getJson<PageResponse<IncidentSummary>>('/incidents', { search: number, size: 5 });
      const incident = page.content.find((entry) => entry.number.toUpperCase() === number);
      if (!incident) return setLookupError(`${number} was not found`);
      perform({ kind: 'link', incidentId: incident.id }, `Linked to ${incident.number}`);
    } catch (error) {
      setLookupError(errorMessage(error));
    }
  };

  return (
    <Panel
      title={
        <span className="inline-flex flex-wrap items-center gap-1.5">
          <span className="break-words">{g.title}</span>
          <SimulatedBadge simulated={g.simulated} />
        </span>
      }
      subtitle={`Group #${g.id} · score ${g.score.toFixed(2)} · ${formatNumber(g.memberCount)} members · first seen ${formatDateTime(g.firstSeenAt)}`}
      actions={
        <button type="button" className="icon-btn sm" aria-label="Close group" onClick={onClose}>
          <X size={13} />
        </button>
      }
    >
      <div className="mb-3 flex flex-wrap items-center gap-1.5 text-[11px]">
        <ToneBadge value={g.status} label={titleCase(g.status)} tone={STATUS_TONES[g.status] ?? 'neutral'} />
        {g.highestSeverity && <SeverityBadge severity={g.highestSeverity} />}
        {g.incidentId ? (
          <Link to={`/incidents/${g.incidentId}`} className="id-cell">
            {g.incidentNumber}
          </Link>
        ) : (
          <span className="text-muted">No incident linked</span>
        )}
        <span className="text-muted">· last activity {formatRelative(g.lastActivityAt)}</span>
      </div>
      {g.status === 'DISMISSED' && (
        <p className="callout mb-3">
          Dismissed {g.dismissedAt ? formatRelative(g.dismissedAt) : ''}
          {g.dismissedByName ? ` by ${g.dismissedByName}` : ''}: {g.dismissedReason}
        </p>
      )}
      <div className="grid gap-[14px] lg:grid-cols-2">
        <div>
          <b className="mb-1 block text-[11px]">Suggested root</b>
          {g.rootCiId || g.rootServiceId ? (
            <p className="m-0 text-[11px]">
              {g.rootCiId ? <Link to={`/cmdb/${g.rootCiId}`}>{g.rootCiName}</Link> : null}
              {g.rootServiceName ? <span className="text-muted"> · {g.rootServiceName}</span> : null}
              {g.rootReason && <small className="block text-muted">{g.rootReason}</small>}
              {g.rootCiId && (
                <Link to={`/digital-twin?root=${g.rootCiId}`} className="link-btn mt-1 inline-flex">
                  Show in Digital Twin
                </Link>
              )}
            </p>
          ) : (
            <small className="text-muted">No root component suggested.</small>
          )}
        </div>
        <div>
          <b className="mb-1 block text-[11px]">Why these belong together</b>
          <Reasons reasons={g.reasons} label="Group reasons" />
        </div>
      </div>

      {canRespond && open && (
        <div className="bulk-bar mt-3" role="toolbar" aria-label="Group actions">
          {!g.incidentId && hasAlert && (
            <button type="button" className="gold-btn compact" onClick={() => setDialog('convert')}>
              <Siren size={12} aria-hidden="true" /> Convert to incident
            </button>
          )}
          <button type="button" className="subtle-btn compact" onClick={() => setDialog('link')}>
            <Link2 size={12} aria-hidden="true" /> Link to incident
          </button>
          <button type="button" className="subtle-btn compact" onClick={() => setDialog('split')} disabled={selected.size === 0 || selected.size >= members.length} title={selected.size === 0 ? 'Select the members to move into a new group' : undefined}>
            <Scissors size={12} aria-hidden="true" /> Split {selected.size ? `(${selected.size})` : ''}
          </button>
          <button type="button" className="subtle-btn compact" onClick={() => setDialog('merge')} disabled={mergeCandidates.length === 0}>
            <GitMerge size={12} aria-hidden="true" /> Merge…
          </button>
          <button type="button" className="subtle-btn compact" onClick={() => setDialog('dismiss')}>
            <XCircle size={12} aria-hidden="true" /> Dismiss
          </button>
        </div>
      )}

      <b className="mb-1 mt-3 block text-[11px]">Members</b>
      {members.length === 0 ? (
        <EmptyState title="No members" />
      ) : (
        <div className="table-scroll">
          <table className="data-table">
            <caption className="sr-only">Group members</caption>
            <thead>
              <tr>
                {canRespond && open && <th scope="col"><span className="sr-only">Select</span></th>}
                <th scope="col">Member</th>
                <th scope="col">Severity</th>
                <th scope="col">Service / CI</th>
                <th scope="col">Score</th>
                <th scope="col">Reasons</th>
                <th scope="col">First seen</th>
              </tr>
            </thead>
            <tbody>
              {members.map((member) => (
                <tr key={member.id}>
                  {canRespond && open && (
                    <td>
                      <input
                        type="checkbox"
                        aria-label={`Select ${member.title}`}
                        checked={selected.has(member.id)}
                        onChange={(event) =>
                          setSelected((current) => {
                            const next = new Set(current);
                            if (event.target.checked) next.add(member.id);
                            else next.delete(member.id);
                            return next;
                          })
                        }
                      />
                    </td>
                  )}
                  <td className="summary-cell">
                    <ToneBadge value={member.memberType} label={titleCase(member.memberType)} tone="neutral" />{' '}
                    {member.alertId ? (
                      <Link to={`/alerts/${member.alertId}`} className="id-cell">
                        {member.alertNumber}
                      </Link>
                    ) : null}{' '}
                    <span className="break-words">{member.title}</span> <SimulatedBadge simulated={member.simulated} />
                  </td>
                  <td>{member.severity ? <SeverityBadge severity={member.severity} /> : '—'}</td>
                  <td>{[member.serviceName, member.ciName].filter(Boolean).join(' · ') || '—'}</td>
                  <td>{member.score.toFixed(2)}</td>
                  <td className="text-muted">{(member.reasons ?? []).map((reason) => titleCase(reason.code)).join(', ') || '—'}</td>
                  <td title={formatDateTime(member.firstSeenAt)}>{formatRelative(member.firstSeenAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Link */}
      <Modal
        open={dialog === 'link'}
        onClose={close}
        title="Link group to an incident"
        description="Member alerts that have no incident yet are linked to the incident you name."
        dismissible={!action.isPending}
        footer={
          <>
            <button type="button" className="subtle-btn" onClick={close} disabled={action.isPending}>
              Cancel
            </button>
            <button type="button" className="gold-btn" onClick={() => void linkIncident()} disabled={action.isPending || !incidentNumber.trim()}>
              {action.isPending ? 'Linking…' : 'Link'}
            </button>
          </>
        }
      >
        <div className="field">
          <div className="field-label">
            <label htmlFor="corr-incident">Incident number</label>
          </div>
          <input id="corr-incident" className="control" placeholder="INC-7846" value={incidentNumber} onChange={(event) => setIncidentNumber(event.target.value)} />
        </div>
        {(lookupError || action.isError) && (
          <p className="field-error" role="alert">
            {lookupError ?? errorMessage(action.error)}
          </p>
        )}
      </Modal>

      {/* Convert */}
      <Modal
        open={dialog === 'convert'}
        onClose={close}
        title="Convert group to an incident"
        description="Creates an incident from the root alert through the regular intake pipeline (classification, routing, SLA) and links the other member alerts."
        dismissible={!action.isPending}
        footer={
          <>
            <button type="button" className="subtle-btn" onClick={close} disabled={action.isPending}>
              Cancel
            </button>
            <button
              type="button"
              className="gold-btn"
              disabled={action.isPending || (title.trim().length > 0 && title.trim().length < 5)}
              onClick={() =>
                perform({ kind: 'convert', body: { title: title.trim() || undefined, impact: impact || undefined, urgency: urgency || undefined } }, 'Incident created from the group')
              }
            >
              {action.isPending ? 'Converting…' : 'Create incident'}
            </button>
          </>
        }
      >
        <div className="field">
          <div className="field-label">
            <label htmlFor="corr-title">Title (optional, 5–200 characters)</label>
          </div>
          <input id="corr-title" className="control" maxLength={200} value={title} onChange={(event) => setTitle(event.target.value)} placeholder={g.title} />
        </div>
        <div className="grid gap-2 sm:grid-cols-2">
          <div className="field">
            <div className="field-label">
              <label htmlFor="corr-impact">Impact</label>
            </div>
            <select id="corr-impact" className="control" value={impact} onChange={(event) => setImpact(event.target.value as Level | '')}>
              <option value="">From the alert</option>
              {LEVELS.map((level) => (
                <option key={level} value={level}>
                  {titleCase(level)}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <div className="field-label">
              <label htmlFor="corr-urgency">Urgency</label>
            </div>
            <select id="corr-urgency" className="control" value={urgency} onChange={(event) => setUrgency(event.target.value as Level | '')}>
              <option value="">From the alert</option>
              {LEVELS.map((level) => (
                <option key={level} value={level}>
                  {titleCase(level)}
                </option>
              ))}
            </select>
          </div>
        </div>
        {action.isError && (
          <p className="field-error" role="alert">
            {errorMessage(action.error)}
          </p>
        )}
      </Modal>

      {/* Split */}
      <Modal
        open={dialog === 'split'}
        onClose={close}
        title={`Split ${selected.size} member${selected.size === 1 ? '' : 's'} into a new group`}
        description="The selected members leave this group and form a new open group."
        dismissible={!action.isPending}
        footer={
          <>
            <button type="button" className="subtle-btn" onClick={close} disabled={action.isPending}>
              Cancel
            </button>
            <button type="button" className="gold-btn" disabled={action.isPending} onClick={() => perform({ kind: 'split', memberIds: [...selected], title: title.trim() || undefined }, 'Group split', (result) => onOpen(result.id))}>
              {action.isPending ? 'Splitting…' : 'Split'}
            </button>
          </>
        }
      >
        <div className="field">
          <div className="field-label">
            <label htmlFor="corr-split-title">Title of the new group (optional)</label>
          </div>
          <input id="corr-split-title" className="control" maxLength={300} value={title} onChange={(event) => setTitle(event.target.value)} />
        </div>
        {action.isError && (
          <p className="field-error" role="alert">
            {errorMessage(action.error)}
          </p>
        )}
      </Modal>

      {/* Merge */}
      <Modal
        open={dialog === 'merge'}
        onClose={close}
        title="Merge groups into this one"
        description="Members of the chosen groups move here; the merged groups become CLOSED."
        dismissible={!action.isPending}
        footer={
          <>
            <button type="button" className="subtle-btn" onClick={close} disabled={action.isPending}>
              Cancel
            </button>
            <button type="button" className="gold-btn" disabled={action.isPending || mergeIds.size === 0} onClick={() => perform({ kind: 'merge', groupIds: [...mergeIds] }, `Merged ${mergeIds.size} group${mergeIds.size === 1 ? '' : 's'}`, () => setMergeIds(new Set()))}>
              {action.isPending ? 'Merging…' : `Merge ${mergeIds.size || ''}`.trim()}
            </button>
          </>
        }
      >
        {mergeCandidates.length === 0 ? (
          <EmptyState title="No other open groups to merge" />
        ) : (
          <ul className="m-0 grid list-none gap-1 p-0" aria-label="Groups to merge">
            {mergeCandidates.map((other) => (
              <li key={other.id}>
                <label className="list-row cursor-pointer">
                  <input
                    type="checkbox"
                    checked={mergeIds.has(other.id)}
                    onChange={(event) =>
                      setMergeIds((current) => {
                        const next = new Set(current);
                        if (event.target.checked) next.add(other.id);
                        else next.delete(other.id);
                        return next;
                      })
                    }
                  />
                  <span className="min-w-0">
                    <b className="block break-words">{other.title}</b>
                    <small>
                      #{other.id} · {formatNumber(other.memberCount)} members · last activity {formatRelative(other.lastActivityAt)}
                    </small>
                  </span>
                </label>
              </li>
            ))}
          </ul>
        )}
        {action.isError && (
          <p className="field-error" role="alert">
            {errorMessage(action.error)}
          </p>
        )}
      </Modal>

      <ConfirmDialog
        open={dialog === 'dismiss'}
        onClose={close}
        title="Dismiss this correlation group?"
        message="The group is marked DISMISSED and its members are no longer grouped automatically with each other. The reason is recorded and audited."
        confirmLabel="Dismiss group"
        tone="danger"
        reasonLabel="Reason"
        reasonRequired
        pending={action.isPending}
        error={action.isError ? errorMessage(action.error) : null}
        onConfirm={(reason) => perform({ kind: 'dismiss', reason }, 'Group dismissed')}
      />
    </Panel>
  );
}

/** Correlation: groups of alerts and anomalies with reasons, members and a suggested root; staff link, convert, split, merge, dismiss. */
export function CorrelationTab({ includeSimulated, canRespond }: { includeSimulated: boolean; canRespond: boolean }) {
  const [params, setParams] = useSearchParams();
  const [status, setStatus] = useState('OPEN');
  const [page, setPage] = useState(0);
  const groups = useCorrelationGroups({ status: status || undefined, includeSimulated, page, size: 20 });
  const selected = Number(params.get('group')) || null;
  const select = (id: number | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (id) next.set('group', String(id));
        else next.delete('group');
        return next;
      },
      { replace: true },
    );
  const rows = groups.data?.content ?? [];

  return (
    <div className="grid items-start gap-[14px] xl:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
      <Panel title="Correlation groups" subtitle="Grouped within the correlation window by time, topology, labels and change proximity">
        <div className="toolbar">
          <label className="sr-only" htmlFor="obs-group-status">
            Status
          </label>
          <select id="obs-group-status" className="control sm w-auto" value={status} onChange={(event) => { setPage(0); setStatus(event.target.value); }}>
            <option value="OPEN">Open</option>
            <option value="CLOSED">Closed</option>
            <option value="DISMISSED">Dismissed</option>
            <option value="">Every status</option>
          </select>
        </div>
        {groups.isLoading && <LoadingState rows={5} />}
        {groups.isError && <ErrorState error={groups.error} onRetry={() => void groups.refetch()} />}
        {groups.data && rows.length === 0 && <EmptyState icon={<GitMerge size={20} />} title="No correlation groups" description="Groups form when related alerts or anomalies arrive within the correlation window." />}
        {rows.length > 0 && groups.data && (
          <>
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Correlation groups">
              {rows.map((group) => (
                <li key={group.id}>
                  <button type="button" className={`list-row w-full text-left ${group.id === selected ? 'active' : ''}`} aria-pressed={group.id === selected} onClick={() => select(group.id)}>
                    <span className="min-w-0">
                      <b className="flex flex-wrap items-center gap-1.5">
                        {group.highestSeverity && <SeverityBadge severity={group.highestSeverity} />}
                        <span className="break-words">{group.title}</span>
                        <SimulatedBadge simulated={group.simulated} />
                      </b>
                      <small className="block">
                        {formatNumber(group.memberCount)} members · score {group.score.toFixed(2)}
                        {group.rootCiName ? ` · root ${group.rootCiName}` : ''}
                        {group.incidentNumber ? ` · ${group.incidentNumber}` : ''}
                      </small>
                      <small className="block text-muted">
                        {titleCase(group.status)} · last activity {formatRelative(group.lastActivityAt)}
                      </small>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            <Pagination page={groups.data.page} totalPages={groups.data.totalPages} totalElements={groups.data.totalElements} size={groups.data.size} onPageChange={setPage} label="groups" />
          </>
        )}
      </Panel>
      {selected ? (
        <GroupDetail key={selected} id={selected} canRespond={canRespond} includeSimulated={includeSimulated} onClose={() => select(null)} onOpen={(id) => select(id)} />
      ) : (
        <Panel>
          <EmptyState title="Select a group" description="Members, reasons, the suggested root component and the available actions appear here." />
        </Panel>
      )}
    </div>
  );
}
