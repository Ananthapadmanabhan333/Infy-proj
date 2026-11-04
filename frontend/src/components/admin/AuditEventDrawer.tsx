import { Filter, User } from 'lucide-react';
import { JsonViewer } from '../JsonViewer';
import { Badge, StatusBadge } from '../Badge';
import { Drawer } from '../Drawer';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import type { AuditLogDto } from '../../lib/types';

export function entityLabel(event: Pick<AuditLogDto, 'entityType' | 'entityId'>): string | null {
  if (!event.entityType) return null;
  return `${titleCase(event.entityType)}${event.entityId ? ` #${event.entityId}` : ''}`;
}

/** Audit event detail with the raw JSON details and shortcuts to related events. */
export function AuditEventDrawer({
  event,
  onClose,
  onFilterActor,
  onFilterEntity,
}: {
  event: AuditLogDto;
  onClose: () => void;
  onFilterActor?: (actor: string) => void;
  onFilterEntity?: (entityType: string, entityId: string) => void;
}) {
  const entity = entityLabel(event);
  const hasDetails = event.details && Object.keys(event.details).length > 0;
  return (
    <Drawer
      open
      onClose={onClose}
      label="AUDIT EVENT"
      title={titleCase(event.action)}
      subtitle={`${formatDateTime(event.createdAt)} · ${formatRelative(event.createdAt)}`}
      wide
      footer={
        onFilterActor || onFilterEntity ? (
          <>
            {event.actorEmail && onFilterActor && (
              <button type="button" className="subtle-btn compact" onClick={() => onFilterActor(event.actorEmail as string)}>
                <User size={13} /> Events by this actor
              </button>
            )}
            {event.entityType && event.entityId && onFilterEntity && (
              <button type="button" className="subtle-btn compact" onClick={() => onFilterEntity(event.entityType as string, event.entityId as string)}>
                <Filter size={13} /> Events for this {titleCase(event.entityType).toLowerCase()}
              </button>
            )}
          </>
        ) : undefined
      }
    >
      <div className="tag-row mt-1">
        <StatusBadge status={event.outcome} />
        <Badge value="outline" label={event.action} className="font-mono" />
        {entity && <Badge value="outline" label={entity} />}
      </div>
      <div className="detail-grid">
        <div>
          <small>Actor</small>
          <b>{event.actorEmail ?? 'System'}</b>
        </div>
        <div>
          <small>IP address</small>
          <b className="font-mono">{event.ipAddress ?? '—'}</b>
        </div>
        <div>
          <small>Entity</small>
          <b>{entity ?? '—'}</b>
        </div>
        <div>
          <small>Event ID</small>
          <b className="font-mono">{event.id}</b>
        </div>
      </div>
      <div className="drawer-section">
        <h3>Details</h3>
        {hasDetails ? <JsonViewer value={event.details} label="Audit event details" /> : <p>No additional details recorded.</p>}
      </div>
    </Drawer>
  );
}
