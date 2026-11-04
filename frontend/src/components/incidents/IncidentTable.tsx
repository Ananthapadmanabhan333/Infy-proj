import { useNavigate } from 'react-router-dom';
import { Flame, GitBranch } from 'lucide-react';
import { formatRelative } from '../../lib/format';
import type { IncidentSummary } from '../../lib/types';
import { UserChip } from '../Avatar';
import { DemoBadge, PriorityBadge, StatusBadge, SimulatedBadge } from '../Badge';
import { DataTable, type Column, type SortState } from '../DataTable';
import { SlaCountdown } from '../SlaCountdown';

export type IncidentColumnKey =
  | 'number'
  | 'title'
  | 'priority'
  | 'service'
  | 'department'
  | 'assignee'
  | 'requester'
  | 'sla'
  | 'status'
  | 'created'
  | 'updated';

/** Reference incident list (screen 2): ID, Title, Priority, Service, Assignee, SLA, Status. */
const DEFAULT_COLUMNS: IncidentColumnKey[] = ['number', 'title', 'priority', 'service', 'assignee', 'sla', 'status'];

interface IncidentTableProps {
  incidents: IncidentSummary[];
  columns?: IncidentColumnKey[];
  /** Server-side sort of the Created / Updated columns; headers become clickable only when `onSortChange` is given. */
  sort?: SortState | null;
  onSortChange?: (sort: SortState) => void;
  empty?: string;
  /** Checkbox selection for bulk actions (controlled). */
  selected?: ReadonlySet<number>;
  onSelectedChange?: (next: Set<number>) => void;
  caption?: string;
}

export function IncidentTable({ incidents, columns = DEFAULT_COLUMNS, sort, onSortChange, empty, selected, onSelectedChange, caption = 'Incidents' }: IncidentTableProps) {
  const navigate = useNavigate();
  const all: Record<IncidentColumnKey, Column<IncidentSummary>> = {
    number: {
      key: 'number',
      header: 'ID',
      className: 'id-cell',
      render: (incident) => incident.number,
    },
    title: {
      key: 'title',
      header: 'Title',
      className: 'summary-cell',
      render: (incident) => (
        <span className="inline-flex max-w-full items-center gap-1.5">
          {incident.major && <Flame size={12} className="red-text" aria-label="Major incident" />}
          {incident.parentNumber && <GitBranch size={12} className="gold-text" aria-label={`Child of ${incident.parentNumber}`} />}
          <span className="truncate" title={incident.title}>
            {incident.title}
          </span>
          <DemoBadge demo={incident.demo} /> <SimulatedBadge simulated={incident.simulated} />
        </span>
      ),
    },
    priority: { key: 'priority', header: 'Priority', render: (incident) => <PriorityBadge priority={incident.priority} /> },
    service: { key: 'service', header: 'Service', render: (incident) => incident.serviceName ?? <span className="muted-text">—</span> },
    department: { key: 'department', header: 'Team', render: (incident) => incident.departmentName ?? <span className="muted-text">—</span> },
    assignee: { key: 'assignee', header: 'Assignee', render: (incident) => <UserChip name={incident.assigneeName} /> },
    requester: { key: 'requester', header: 'Requester', render: (incident) => <UserChip name={incident.requesterName} emptyLabel="—" /> },
    sla: { key: 'sla', header: 'SLA', render: (incident) => <SlaCountdown sla={incident.sla} /> },
    status: { key: 'status', header: 'Status', render: (incident) => <StatusBadge status={incident.status} /> },
    created: {
      key: 'created',
      header: 'Created',
      sortKey: 'createdAt',
      render: (incident) => <span title={new Date(incident.createdAt).toLocaleString()}>{formatRelative(incident.createdAt)}</span>,
    },
    updated: {
      key: 'updated',
      header: 'Updated',
      sortKey: 'updatedAt',
      render: (incident) => <span title={new Date(incident.updatedAt).toLocaleString()}>{formatRelative(incident.updatedAt)}</span>,
    },
  };

  return (
    <DataTable<IncidentSummary>
      columns={columns.map((key) => all[key])}
      rows={incidents}
      rowKey={(incident) => incident.id}
      rowLabel={(incident) => `${incident.number} ${incident.title}`}
      onRowClick={(incident) => navigate(`/incidents/${incident.id}`)}
      sort={sort}
      onSortChange={onSortChange}
      empty={empty ?? 'No incidents match these filters.'}
      caption={caption}
      selected={selected}
      onSelectedChange={onSelectedChange ? (next) => onSelectedChange(new Set([...next].map(Number))) : undefined}
    />
  );
}
