import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { CalendarClock, KeyRound, Laptop, Layers, ListChecks } from 'lucide-react';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { SourceBadge } from '../../components/oncall/OnCallParts';
import { ACTION_ITEM_STATUS_LABELS, ACTION_ITEM_STATUS_TONES, ACTION_ITEM_TYPE_LABELS } from '../../lib/response';
import type { MyActionItem, MyShift } from '../../lib/responseTypes';
import { PriorityBadge, StatusBadge } from '../../components/Badge';
import { Panel } from '../../components/Panel';
import { SlaCountdown } from '../../components/SlaCountdown';
import { EmptyState } from '../../components/States';
import { AlertStatusBadge, SeverityBadge } from '../../components/ops/OpsParts';
import { formatDate, formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import { ASSET_TYPE_LABELS } from '../../lib/ops';
import type { AlertSummary, AssetStatus, AssetType } from '../../lib/opsTypes';
import type { IncidentSummary, ServiceRequestDto } from '../../lib/types';
import type { GenericSection } from './sections';

/** Panel with "title (count)" and "View all" when the section holds more than it shows. */
export function SectionPanel({
  title,
  total,
  shown,
  to,
  empty,
  children,
  actions,
}: {
  title: string;
  total: number;
  shown: number;
  to?: string;
  empty: string;
  children: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <Panel
      title={
        <span>
          {title} <span className="muted-text">({formatNumber(total)})</span>
        </span>
      }
      subtitle={total > shown ? `Showing ${formatNumber(shown)} of ${formatNumber(total)}` : undefined}
      action={to ? 'View all' : undefined}
      to={to}
      actions={actions}
    >
      {shown === 0 ? <EmptyState title={empty} /> : <ul className="m-0 list-none p-0">{children}</ul>}
    </Panel>
  );
}

export function IncidentRow({ incident, actions, showTeam }: { incident: IncidentSummary; actions?: ReactNode; showTeam?: boolean }) {
  return (
    <li className="list-row flex-wrap sm:flex-nowrap">
      <div>
        <b className="flex flex-wrap items-center gap-1.5">
          <Link to={`/incidents/${incident.id}`} className="id-cell">
            {incident.number}
          </Link>
          <span className="min-w-0 truncate">{incident.title}</span>
        </b>
        <small className="flex flex-wrap items-center gap-1.5">
          <PriorityBadge priority={incident.priority} />
          <StatusBadge status={incident.status} />
          {incident.serviceName && <span>{incident.serviceName}</span>}
          {showTeam && incident.teamName && <span>· {incident.teamName}</span>}
          <span>· {formatRelative(incident.createdAt)}</span>
        </small>
      </div>
      {incident.sla && (
        <span className="shrink-0">
          <SlaCountdown sla={incident.sla} />
        </span>
      )}
      {actions && <span className="flex shrink-0 gap-1">{actions}</span>}
    </li>
  );
}

export function RequestRow({ request, actions, showTeam }: { request: ServiceRequestDto; actions?: ReactNode; showTeam?: boolean }) {
  return (
    <li className="list-row flex-wrap sm:flex-nowrap">
      <div>
        <b className="flex flex-wrap items-center gap-1.5">
          <Link to={`/service-desk?request=${request.id}`} className="id-cell">
            {request.number}
          </Link>
          <span className="min-w-0 truncate">{request.title}</span>
        </b>
        <small className="flex flex-wrap items-center gap-1.5">
          <PriorityBadge priority={request.priority} />
          <StatusBadge status={request.status} />
          <span>{request.catalogItemName}</span>
          {showTeam && request.teamName && <span>· {request.teamName}</span>}
          <span>· {request.requesterName}</span>
          {request.dueAt && <span>· due {formatRelative(request.dueAt)}</span>}
        </small>
      </div>
      {actions && <span className="flex shrink-0 gap-1">{actions}</span>}
    </li>
  );
}

/** A section contributed by a later module, rendered from its keys only. */
export function GenericSectionPanel({ section }: { section: GenericSection }) {
  return (
    <SectionPanel title={section.title} total={section.total} shown={section.items.length} empty={`Nothing in ${section.title.toLowerCase()}`}>
      {section.items.map((item) => (
        <li key={item.key} className="list-row">
          <span className="icon-tile sm" aria-hidden="true">
            <Layers size={14} />
          </span>
          <div>
            <b className="flex flex-wrap items-center gap-1.5">
              {item.reference && <span className="id-cell">{item.reference}</span>}
              {item.to ? <Link to={item.to}>{item.label}</Link> : <span>{item.label}</span>}
            </b>
            {item.meta && <small>{titleCase(item.meta)}</small>}
          </div>
        </li>
      ))}
    </SectionPanel>
  );
}

/** `{ total, items }` contributed by a later module, or null when absent / not a section. */
export function asSection<T>(value: unknown): { total: number; items: T[] } | null {
  if (!value || typeof value !== 'object' || !Array.isArray((value as { items?: unknown }).items)) return null;
  const section = value as { total?: unknown; items: T[] };
  return { total: typeof section.total === 'number' ? section.total : section.items.length, items: section.items };
}

/** My Work `alerts` (R3 BE-3): open and acknowledged alerts of my teams, most severe first. */
export function AlertsSectionPanel({ section }: { section: { total: number; items: AlertSummary[] } }) {
  return (
    <SectionPanel title="Alerts for my teams" total={section.total} shown={section.items.length} to="/alerts?scope=mine" empty="No active alerts for your teams">
      {section.items.map((alert) => (
        <li key={alert.id} className="list-row flex-wrap sm:flex-nowrap">
          <div>
            <b className="flex flex-wrap items-center gap-1.5">
              <Link to={`/alerts/${alert.id}`} className="id-cell">
                {alert.number}
              </Link>
              <span className="min-w-0 truncate">{alert.title}</span>
            </b>
            <small className="flex flex-wrap items-center gap-1.5">
              <SeverityBadge severity={alert.severity} />
              <AlertStatusBadge status={alert.status} />
              {(alert.serviceName || alert.ciName) && <span>{[alert.serviceName, alert.ciName].filter(Boolean).join(' · ')}</span>}
              {alert.teamName && <span>· {alert.teamName}</span>}
              {alert.occurrenceCount > 1 && <span>· ×{formatNumber(alert.occurrenceCount)}</span>}
              <span>· {formatRelative(alert.lastSeenAt)}</span>
            </small>
          </div>
          {alert.incidentId && (
            <Link to={`/incidents/${alert.incidentId}`} className="id-cell shrink-0">
              {alert.incidentNumber}
            </Link>
          )}
        </li>
      ))}
    </SectionPanel>
  );
}

export interface MyAssetItem {
  id: number;
  assetTag: string;
  name: string;
  assetType: AssetType;
  status: AssetStatus;
  manufacturer: string | null;
  model: string | null;
  serialNumber: string | null;
  expiresOn: string | null;
  assignedAt: string | null;
}

/** Portal `myAssets` (R3 BE-3): hardware I hold and licence seats assigned to me. Links to the asset only for readers. */
export function MyAssetsPanel({ section, linkAssets }: { section: { total: number; items: MyAssetItem[] }; linkAssets: boolean }) {
  return (
    <SectionPanel title="My assets" total={section.total} shown={section.items.length} empty="No devices or licences are assigned to you">
      {section.items.map((asset, index) => {
        const licence = asset.assetType === 'SOFTWARE_LICENSE';
        return (
          <li key={`${asset.id}-${index}`} className="list-row">
            <span className="icon-tile sm" aria-hidden="true">
              {licence ? <KeyRound size={14} /> : <Laptop size={14} />}
            </span>
            <div>
              <b className="flex flex-wrap items-center gap-1.5">
                {linkAssets ? (
                  <Link to={`/assets/${asset.id}`} className="id-cell">
                    {asset.assetTag}
                  </Link>
                ) : (
                  <span className="id-cell">{asset.assetTag}</span>
                )}
                <span className="min-w-0 truncate">{asset.name}</span>
              </b>
              <small className="flex flex-wrap items-center gap-1.5">
                <span>{ASSET_TYPE_LABELS[asset.assetType] ?? titleCase(asset.assetType)}</span>
                {(asset.manufacturer || asset.model) && <span>· {[asset.manufacturer, asset.model].filter(Boolean).join(' ')}</span>}
                {asset.serialNumber && <span>· S/N {asset.serialNumber}</span>}
                {asset.assignedAt && <span>· since {formatDate(asset.assignedAt)}</span>}
                {asset.expiresOn && <span>· {licence ? 'licence' : 'warranty'} ends {formatDate(asset.expiresOn)}</span>}
              </small>
            </div>
          </li>
        );
      })}
    </SectionPanel>
  );
}

/** My Work `onCallShifts` (R3 BE-4): my current and upcoming on-call shifts in the next 14 days. */
export function OnCallShiftsPanel({ section }: { section: { total: number; items: MyShift[] } }) {
  return (
    <SectionPanel title="My on-call shifts" total={section.total} shown={section.items.length} to="/on-call?tab=mine" empty="No on-call shifts in the next 14 days">
      {section.items.map((shift, index) => (
        <li key={`${shift.scheduleId}-${shift.layer}-${shift.start}-${index}`} className="list-row flex-wrap sm:flex-nowrap">
          <span className="icon-tile sm" aria-hidden="true">
            <CalendarClock size={14} />
          </span>
          <div className="min-w-0 flex-1">
            <b className="flex flex-wrap items-center gap-1.5">
              <Link to={`/on-call/${shift.scheduleId}`}>{shift.scheduleName}</Link>
              <ToneBadge value={shift.layer} tone={shift.layer === 'PRIMARY' ? 'gold' : 'neutral'} />
              {shift.current && <ToneBadge value="now" label="On call now" tone="critical" />}
            </b>
            <small className="flex flex-wrap items-center gap-1.5">
              <span>
                {formatDateTime(shift.start)} – {formatDateTime(shift.end)}
              </span>
              {shift.teamName && <span>· {shift.teamName}</span>}
              {shift.source !== 'ROTATION' && <SourceBadge source={shift.source} />}
            </small>
          </div>
        </li>
      ))}
    </SectionPanel>
  );
}

/** My Work `actionItems` (R3 BE-4): my open major incident follow-ups and review actions, soonest due first. */
export function ActionItemsSectionPanel({ section }: { section: { total: number; items: MyActionItem[] } }) {
  return (
    <SectionPanel title="My action items" total={section.total} shown={section.items.length} empty="No open action items assigned to you">
      {section.items.map((item) => {
        const route = item.link && item.link.startsWith('/') && !item.link.startsWith('//') ? item.link : item.source === 'PIR' ? `/reviews/${item.sourceId}` : `/major-incidents/${item.sourceId}`;
        return (
          <li key={item.id} className="list-row flex-wrap sm:flex-nowrap">
            <span className="icon-tile sm" aria-hidden="true">
              <ListChecks size={14} />
            </span>
            <div className="min-w-0 flex-1">
              <b className="flex flex-wrap items-center gap-1.5">
                <Link to={route} className="min-w-0 break-words">
                  {item.title}
                </Link>
                {item.overdue && <ToneBadge value="overdue" label="Overdue" tone="critical" />}
              </b>
              <small className="flex flex-wrap items-center gap-1.5">
                <ToneBadge value={item.status} label={ACTION_ITEM_STATUS_LABELS[item.status] ?? item.status} tone={ACTION_ITEM_STATUS_TONES[item.status] ?? 'neutral'} />
                <span>{ACTION_ITEM_TYPE_LABELS[item.actionType] ?? item.actionType}</span>
                <span>
                  · {item.sourceNumber ?? (item.source === 'PIR' ? 'Review' : 'Major incident')}
                  {item.sourceTitle ? ` ${item.sourceTitle}` : ''}
                </span>
                {item.dueDate && <span className={item.overdue ? 'red-text' : ''}>· due {formatDate(item.dueDate)}</span>}
              </small>
            </div>
          </li>
        );
      })}
    </SectionPanel>
  );
}
