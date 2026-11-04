import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertTriangle,
  Ban,
  CheckCircle2,
  ChevronLeft,
  Flame,
  GitBranch,
  Link2,
  MoreHorizontal,
  Pencil,
  Server,
  Shuffle,
  UserPlus,
} from 'lucide-react';
import { Badge, DemoBadge, PriorityBadge, StatusBadge, SimulatedBadge } from '../../../components/Badge';
import { Stepper } from '../../../components/Stepper';
import { useAuth } from '../../../hooks/useAuth';
import { useClickOutside } from '../../../hooks/useClickOutside';
import {
  ESCALATORS,
  INCIDENT_PROGRESS_STEPS,
  incidentProgressIndex,
  isCancelTransition,
  MAJOR_INCIDENT,
  RESOLVED_STATUSES,
  STAFF,
  TRANSITION_ORDER,
  transitionLabel,
} from '../../../lib/constants';
import { formatDateTime, formatRelative, shortName } from '../../../lib/format';
import type { IncidentDetail, IncidentStatus } from '../../../lib/types';

export type HeaderAction = 'assign' | 'edit' | 'escalate' | 'major' | 'parent' | 'link';

interface MenuItem {
  key: string;
  label: string;
  icon: ReactNode;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
  /** Draw a divider above this item. */
  separated?: boolean;
}

/** Small accessible action menu ("More"): arrow keys move, Escape / outside click closes. */
function ActionMenu({ items, label }: { items: MenuItem[]; label: string }) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(anchor, open, close);

  useEffect(() => {
    if (open) menu.current?.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)')?.focus();
  }, [open]);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const entries = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? []);
    if (!entries.length) return;
    const index = entries.indexOf(document.activeElement as HTMLButtonElement);
    let next = -1;
    if (event.key === 'ArrowDown') next = (index + 1) % entries.length;
    else if (event.key === 'ArrowUp') next = (index - 1 + entries.length) % entries.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = entries.length - 1;
    else if (event.key === 'Escape') {
      event.preventDefault();
      close();
      trigger.current?.focus();
      return;
    } else if (event.key === 'Tab') {
      close();
      return;
    }
    if (next >= 0) {
      event.preventDefault();
      entries[next].focus();
    }
  };

  if (!items.length) return null;
  return (
    <div className="dropdown-anchor" ref={anchor}>
      <button
        ref={trigger}
        type="button"
        className="date-btn compact"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <MoreHorizontal size={14} aria-hidden="true" /> More
      </button>
      {open && (
        <div ref={menu} className="dropdown incident-more-menu" role="menu" aria-label={label} onKeyDown={onKeyDown}>
          {items.map((item) => (
            <div key={item.key} role="none">
              {item.separated && <hr />}
              <button
                type="button"
                role="menuitem"
                className={`dropdown-item ${item.danger ? 'danger' : ''}`.trim()}
                disabled={item.disabled}
                onClick={() => {
                  close();
                  item.onSelect();
                }}
              >
                {item.icon}
                <span>{item.label}</span>
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

interface IncidentHeaderProps {
  incident: IncidentDetail;
  onTransition: (status: IncidentStatus) => void;
  onAction: (action: HeaderAction) => void;
  onAutoRoute: () => void;
  busy?: boolean;
}

/** Reference screen 3 header: back link, number pill + badges, Escalate / Resolve, title, meta line. */
export function IncidentHeader({ incident, onTransition, onAction, onAutoRoute, busy = false }: IncidentHeaderProps) {
  const { hasRole } = useAuth();
  const staff = hasRole(...STAFF);
  const closed = RESOLVED_STATUSES.includes(incident.status);
  const transitions = TRANSITION_ORDER.filter((status) => incident.allowedTransitions.includes(status));
  const canResolve = transitions.includes('RESOLVED');
  const cancel = transitions.find((status) => isCancelTransition(incident.status, status));
  // Staff find "Cancel" in the More menu; requesters see it as a button on their own NEW/ASSIGNED incident.
  const inline = transitions.filter((status) => status !== 'RESOLVED' && (staff ? status !== cancel : true));
  const canEscalate = hasRole(...ESCALATORS) && !closed;

  const menu: MenuItem[] = [];
  if (staff) {
    if (!closed) {
      menu.push({ key: 'assign', label: 'Assign…', icon: <UserPlus size={14} />, onSelect: () => onAction('assign') });
      menu.push({ key: 'route', label: 'Auto-route to least-loaded agent', icon: <Shuffle size={14} />, onSelect: onAutoRoute, disabled: busy });
    }
    menu.push({ key: 'edit', label: 'Edit details…', icon: <Pencil size={14} />, onSelect: () => onAction('edit') });
    if (hasRole(...MAJOR_INCIDENT)) {
      menu.push({
        key: 'major',
        label: incident.major ? 'Remove major flag…' : 'Declare major incident…',
        icon: <Flame size={14} />,
        onSelect: () => onAction('major'),
      });
    }
    if (!incident.parentId) menu.push({ key: 'parent', label: 'Link to parent…', icon: <GitBranch size={14} />, onSelect: () => onAction('parent'), separated: true });
    menu.push({ key: 'link', label: 'Add related incident…', icon: <Link2 size={14} />, onSelect: () => onAction('link'), separated: Boolean(incident.parentId) });
    if (cancel) {
      menu.push({
        key: 'cancel',
        label: `${transitionLabel(incident.status, cancel)}…`,
        icon: <Ban size={14} />,
        onSelect: () => onTransition(cancel),
        danger: true,
        separated: true,
        disabled: busy,
      });
    }
  }

  return (
    <header className="page-heading incident-heading">
      <nav className="page-crumbs" aria-label="Breadcrumb">
        <Link to="/incidents">
          <ChevronLeft size={13} aria-hidden="true" />
          Incidents
        </Link>
        <span className="sep" aria-hidden="true">
          /
        </span>
        <span className="current" aria-current="page">
          {incident.number}
        </span>
      </nav>

      <div className="incident-heading-row">
        <div className="incident-badges">
          <span className="incident-number-pill">{incident.number}</span>
          <PriorityBadge priority={incident.priority} />
          <StatusBadge status={incident.status} />
          {incident.major && (
            <Badge
              value="major"
              label={
                <>
                  <Flame size={10} aria-hidden="true" /> Major incident
                </>
              }
            />
          )}
          {incident.parentId && incident.parentNumber && (
            <Link to={`/incidents/${incident.parentId}`} className="no-underline">
              <Badge value="outline" label={`Child of ${incident.parentNumber}`} />
            </Link>
          )}
          {incident.childCount > 0 && <Badge value="outline" label={`${incident.childCount} child reports`} />}
          <DemoBadge demo={incident.demo} /> <SimulatedBadge simulated={incident.simulated} />
        </div>

        {(inline.length > 0 || canEscalate || canResolve || menu.length > 0) && (
          <div className="incident-actions" role="toolbar" aria-label="Incident actions">
            {inline.map((status) => {
              const cancelling = isCancelTransition(incident.status, status);
              return (
                <button
                  key={status}
                  type="button"
                  className={cancelling ? 'danger-btn compact' : 'subtle-btn compact'}
                  onClick={() => onTransition(status)}
                  disabled={busy}
                >
                  {transitionLabel(incident.status, status)}
                </button>
              );
            })}
            {canEscalate && (
              <button type="button" className="date-btn compact" onClick={() => onAction('escalate')}>
                <AlertTriangle size={14} aria-hidden="true" /> Escalate
              </button>
            )}
            <ActionMenu items={menu} label="More incident actions" />
            {canResolve && (
              <button type="button" className="gold-btn compact" onClick={() => onTransition('RESOLVED')} disabled={busy}>
                <CheckCircle2 size={14} aria-hidden="true" /> Resolve
              </button>
            )}
          </div>
        )}
      </div>

      <h1>
        <span className="sr-only">{incident.number}: </span>
        {incident.title}
      </h1>
      <div className="incident-meta">
        <span>
          <Server size={13} aria-hidden="true" />
          {incident.serviceName ?? 'No service'}
        </span>
        <span title={formatDateTime(incident.createdAt)}>Created {formatDateTime(incident.createdAt)}</span>
        <span title={formatDateTime(incident.updatedAt)}>Updated {formatRelative(incident.updatedAt)}</span>
      </div>
    </header>
  );
}

/** Created → Assigned → In Progress → Resolved, with PENDING / REOPENED / cancellation shown in the captions. */
export function IncidentProgress({ incident }: { incident: IncidentDetail }) {
  const index = incidentProgressIndex(incident.status);
  const cancelled = incident.status === 'CLOSED' && incident.resolutionCode === 'CANCELLED';
  const assignedCaption =
    incident.status === 'REOPENED'
      ? 'Reopened'
      : incident.assigneeName
        ? shortName(incident.assigneeName)
        : incident.departmentName
          ? `${incident.departmentName} queue`
          : null;
  const progressCaption =
    incident.status === 'PENDING' ? 'Pending · SLA paused' : incident.firstResponseAt ? `Responded ${formatRelative(incident.firstResponseAt)}` : null;
  const resolvedCaption = cancelled
    ? formatDateTime(incident.resolvedAt ?? incident.closedAt)
    : incident.status === 'CLOSED'
      ? `Closed${incident.closedAt ? ` ${formatDateTime(incident.closedAt)}` : ''}`
      : formatDateTime(incident.resolvedAt);
  // Captions only describe steps that were reached.
  const captions = [formatDateTime(incident.createdAt), assignedCaption, progressCaption, resolvedCaption].map((caption, i) =>
    i <= index && caption !== '—' ? caption : null,
  );
  return (
    <div className="incident-stepper">
      <Stepper
        label={`Progress of ${incident.number}`}
        steps={INCIDENT_PROGRESS_STEPS.map((label, i) => ({ label: i === 3 && cancelled ? 'Cancelled' : label, caption: captions[i] }))}
        current={index}
        complete={RESOLVED_STATUSES.includes(incident.status)}
      />
    </div>
  );
}
