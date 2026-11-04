import { useState } from 'react';
import { Link } from 'react-router-dom';
import { GitBranch, Layers, Link2, Trash2, Unlink } from 'lucide-react';
import { useAddIncidentLink, useRemoveIncidentLink, useUnlinkParent } from '../../../api/incidents';
import { PriorityBadge, StatusBadge } from '../../../components/Badge';
import { ConfirmDialog } from '../../../components/ConfirmDialog';
import { IncidentTable } from '../../../components/incidents/IncidentTable';
import { Panel } from '../../../components/Panel';
import { SlaCountdown } from '../../../components/SlaCountdown';
import { EmptyState } from '../../../components/States';
import { useToast } from '../../../components/Toast';
import { errorMessage } from '../../../lib/api';
import { formatRelative, titleCase } from '../../../lib/format';
import type { IncidentDetail, IncidentLinkDto } from '../../../lib/types';

/** Number shown on the "Related (n)" tab: parent, children, links and other active incidents on the same service. */
export function relatedCount(incident: IncidentDetail): number {
  return (incident.parentId ? 1 : 0) + incident.children.length + incident.links.length + (incident.relatedSameService?.length ?? 0);
}

interface RelationsPanelProps {
  incident: IncidentDetail;
  staff: boolean;
  onLinkParent: () => void;
  onAddLink: () => void;
}

export function RelationsPanel({ incident, staff, onLinkParent, onAddLink }: RelationsPanelProps) {
  const unlink = useUnlinkParent(incident.id);
  const removeLink = useRemoveIncidentLink(incident.id);
  const addLink = useAddIncidentLink(incident.id);
  const toast = useToast();
  const [confirmUnlink, setConfirmUnlink] = useState(false);
  const [pendingLink, setPendingLink] = useState<IncidentLinkDto | null>(null);
  const linkedIds = new Set(incident.links.map((link) => link.incident.id));
  const sameService = (incident.relatedSameService ?? []).filter((other) => other.id !== incident.id);

  return (
    <>
      <Panel
        title="Parent, children & links"
        subtitle="Duplicate grouping and incident relationships"
        actions={
          staff && (
            <>
              {!incident.parentId && (
                <button type="button" className="subtle-btn compact" onClick={onLinkParent}>
                  <GitBranch size={13} /> Link Parent
                </button>
              )}
              <button type="button" className="subtle-btn compact" onClick={onAddLink}>
                <Link2 size={13} /> Add Link
              </button>
            </>
          )
        }
      >
        {incident.parentId && incident.parentNumber && (
          <div className="callout mb-3">
            <GitBranch size={16} />
            <div className="flex-1">
              Child of{' '}
              <Link to={`/incidents/${incident.parentId}`}>
                <b>{incident.parentNumber}</b>
              </Link>
              {incident.duplicateScore !== null && <span className="text-muted"> · {Math.round(incident.duplicateScore * 100)}% duplicate match</span>}
              <div className="text-[10px] text-muted">This report follows the parent's resolution.</div>
            </div>
            {staff && (
              <button type="button" className="subtle-btn compact" onClick={() => setConfirmUnlink(true)}>
                <Unlink size={13} /> Unlink Parent
              </button>
            )}
          </div>
        )}

        {incident.children.length > 0 && (
          <>
            <h4 className="mb-1 text-[12px]">Child reports ({incident.children.length})</h4>
            <IncidentTable incidents={incident.children} columns={['number', 'title', 'priority', 'requester', 'status', 'created']} caption="Child reports" />
          </>
        )}

        <h4 className="mt-3 mb-1 text-[12px]">Linked incidents ({incident.links.length})</h4>
        {incident.links.length === 0 && <p className="text-[11px] text-muted">No related incidents linked.</p>}
        {incident.links.map((link) => (
          <div className="list-row" key={link.id}>
            <Link2 size={14} className="gold-text" aria-hidden="true" />
            <div>
              <Link to={`/incidents/${link.incident.id}`}>
                <b>
                  {link.incident.number} · {link.incident.title}
                </b>
              </Link>
              <small>
                {link.direction === 'OUTGOING' ? `This incident ${titleCase(link.linkType).toLowerCase()} it` : `${titleCase(link.linkType)} this incident`}
              </small>
            </div>
            <PriorityBadge priority={link.incident.priority} />
            <StatusBadge status={link.incident.status} />
            {staff && (
              <button type="button" className="icon-btn sm" aria-label={`Remove link to ${link.incident.number}`} onClick={() => setPendingLink(link)}>
                <Trash2 size={14} />
              </button>
            )}
          </div>
        ))}
      </Panel>

      {(sameService.length > 0 || (staff && incident.serviceName)) && (
        <Panel
          title={`Other active incidents on ${incident.serviceName ?? 'this service'}`}
          subtitle="Up to five open incidents on the same service — candidates for linking or grouping"
        >
          {sameService.length === 0 && <EmptyState title="No other active incidents on this service" icon={<Layers size={20} />} />}
          {sameService.map((other) => {
            const linked = linkedIds.has(other.id) || other.parentId === incident.id || incident.parentId === other.id;
            return (
              <div className="list-row" key={other.id}>
                <Layers size={14} className="gold-text" aria-hidden="true" />
                <div>
                  <Link to={`/incidents/${other.id}`}>
                    <b>
                      {other.number} · {other.title}
                    </b>
                  </Link>
                  <small>
                    {other.assigneeName ?? 'Unassigned'} · opened {formatRelative(other.createdAt)}
                  </small>
                </div>
                <SlaCountdown sla={other.sla} />
                <PriorityBadge priority={other.priority} />
                <StatusBadge status={other.status} />
                {staff && (
                  <button
                    type="button"
                    className="subtle-btn compact"
                    disabled={linked || addLink.isPending}
                    onClick={() =>
                      addLink.mutate(
                        { targetId: other.id, linkType: 'RELATED_TO' },
                        { onSuccess: () => toast.success(`Linked ${other.number} as related`), onError: (error) => toast.error(error) },
                      )
                    }
                  >
                    <Link2 size={12} /> {linked ? 'Linked' : 'Link'}
                  </button>
                )}
              </div>
            );
          })}
        </Panel>
      )}

      <ConfirmDialog
        open={confirmUnlink}
        onClose={() => setConfirmUnlink(false)}
        title={`Unlink ${incident.number} from ${incident.parentNumber ?? 'parent'}?`}
        message="The incident becomes independent again and keeps its own SLA."
        confirmLabel="Unlink"
        pending={unlink.isPending}
        error={unlink.isError ? errorMessage(unlink.error) : null}
        onConfirm={() =>
          unlink.mutate(undefined, {
            onSuccess: () => {
              toast.success('Parent link removed');
              setConfirmUnlink(false);
            },
          })
        }
      />
      <ConfirmDialog
        open={pendingLink !== null}
        onClose={() => setPendingLink(null)}
        title="Remove related link?"
        message={`The relationship with ${pendingLink?.incident.number ?? ''} will be removed.`}
        confirmLabel="Remove"
        tone="danger"
        pending={removeLink.isPending}
        error={removeLink.isError ? errorMessage(removeLink.error) : null}
        onConfirm={() =>
          pendingLink &&
          removeLink.mutate(pendingLink.id, {
            onSuccess: () => {
              toast.success('Link removed');
              setPendingLink(null);
            },
          })
        }
      />
    </>
  );
}
