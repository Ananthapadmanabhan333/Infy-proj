import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronDown, ChevronRight, GitFork } from 'lucide-react';
import { useServices } from '../../api/services';
import { useBusinessServiceTree } from '../../api/twin';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { TWIN_LAYER_LABELS } from '../../components/twin/twinLayout';
import type { Tone } from '../../lib/constants';
import { formatRelative, titleCase } from '../../lib/format';
import { CI_TYPE_LABELS } from '../../lib/ops';
import type { TwinNodeState, TwinTreeNode } from '../../lib/r4Types';

export const HEALTH_TONES: Record<string, Tone> = { OPERATIONAL: 'success', DEGRADED: 'warning', OUTAGE: 'critical', MAINTENANCE: 'muted' };

export function HealthBadge({ state }: { state: TwinNodeState | null | undefined }) {
  if (!state) return null;
  return <ToneBadge value={state.health} label={titleCase(state.health)} tone={HEALTH_TONES[state.health] ?? 'neutral'} />;
}

export function StateSummary({ state }: { state: TwinNodeState | null | undefined }) {
  if (!state) return null;
  const parts = [
    state.openIncidents ? `${state.openIncidents} open incident${state.openIncidents === 1 ? '' : 's'}` : null,
    state.firingAlerts ? `${state.firingAlerts} firing alert${state.firingAlerts === 1 ? '' : 's'}` : null,
    state.openAnomalies ? `${state.openAnomalies} open anomal${state.openAnomalies === 1 ? 'y' : 'ies'}` : null,
    state.lastChangeAt ? `last change ${formatRelative(state.lastChangeAt)}${state.lastChange ? ` (${state.lastChange})` : ''}` : null,
  ].filter(Boolean);
  if (!parts.length && !state.healthReason) return null;
  return (
    <small className="block text-muted">
      {state.healthReason ? `${state.healthReason}${parts.length ? ' · ' : ''}` : ''}
      {parts.join(' · ')}
    </small>
  );
}

function TreeItem({ node, depth }: { node: TwinTreeNode; depth: number }) {
  const [open, setOpen] = useState(depth < 2);
  const hasChildren = node.children.length > 0;
  return (
    <li role="treeitem" aria-expanded={hasChildren ? open : undefined} aria-label={node.name}>
      <div className="flex items-start gap-1.5 py-1" style={{ paddingLeft: Math.min(depth, 10) * 14 }}>
        {hasChildren ? (
          <button type="button" className="icon-btn sm !h-5 !w-5 shrink-0" aria-label={`${open ? 'Collapse' : 'Expand'} ${node.name}`} onClick={() => setOpen((value) => !value)}>
            {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
          </button>
        ) : (
          <span className="inline-block w-5 shrink-0" aria-hidden="true" />
        )}
        <div className="min-w-0 text-[11px]">
          <span className="flex flex-wrap items-center gap-1.5">
            <Link to={`/cmdb/${node.ciId}`} className="font-semibold">
              {node.name}
            </Link>
            <span className="text-muted">
              {CI_TYPE_LABELS[node.ciType] ?? node.ciType} · {TWIN_LAYER_LABELS[node.layer] ?? node.layer}
            </span>
            <HealthBadge state={node.state} />
            {node.via && <ToneBadge value={node.via} label={titleCase(node.via)} tone="neutral" />}
            {node.redundant && <ToneBadge value="redundant" label="Redundant" tone="success" />}
            {node.cluster && <ToneBadge value="cluster" label={`Cluster ${node.cluster}`} tone="champagne" />}
            {node.repeated && <ToneBadge value="repeated" label="Already on this path" tone="muted" />}
          </span>
          <StateSummary state={node.state} />
        </div>
      </div>
      {hasChildren && open && (
        <ul role="group" className="m-0 list-none p-0">
          {node.children.map((child, index) => (
            <TreeItem key={`${child.ciId}-${index}`} node={child} depth={depth + 1} />
          ))}
        </ul>
      )}
    </li>
  );
}

/** Business service dependency tree (`/twin/business-services/{id}`) down to infrastructure, with state. */
export function TwinTreeTab({ includeSimulated }: { includeSimulated: boolean }) {
  const services = useServices();
  const [serviceId, setServiceId] = useState('');
  const chosen = serviceId ? Number(serviceId) : services.data?.[0]?.id ?? null;
  const tree = useBusinessServiceTree(chosen, includeSimulated);
  const data = tree.data;
  return (
    <Panel
      title="Business service dependency tree"
      subtitle="From the business service down through applications to infrastructure"
      actions={
        <span>
          <label htmlFor="twin-tree-service" className="sr-only">
            Business service
          </label>
          <select id="twin-tree-service" className="control sm w-auto max-w-full" value={chosen ?? ''} onChange={(event) => setServiceId(event.target.value)} disabled={!services.data?.length}>
            {(services.data ?? []).map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </select>
        </span>
      }
    >
      {services.isLoading && <LoadingState rows={4} />}
      {services.isError && <ErrorState error={services.error} onRetry={() => void services.refetch()} />}
      {services.data && services.data.length === 0 && <EmptyState title="No services" description="Business services come from the service catalogue and the CMDB." />}
      {chosen !== null && tree.isLoading && <LoadingState rows={6} />}
      {chosen !== null && tree.isError && <ErrorState error={tree.error} onRetry={() => void tree.refetch()} />}
      {data && (
        <>
          <p className="mb-2 mt-0 flex flex-wrap items-center gap-1.5 text-[11px] text-muted">
            <b className="text-[var(--text)]">{data.service.name}</b> · Tier {data.service.tier} · {titleCase(data.service.status)} ·{' '}
            {Object.entries(data.layerCounts ?? {})
              .map(([layer, count]) => `${count} ${(TWIN_LAYER_LABELS as Record<string, string>)[layer]?.toLowerCase() ?? layer}`)
              .join(' · ')}
            {data.truncated && <ToneBadge value="truncated" label="Truncated" tone="warning" />}
          </p>
          {data.root ? (
            <ul role="tree" aria-label={`Dependency tree of ${data.service.name}`} className="m-0 list-none p-0">
              <TreeItem node={data.root} depth={0} />
            </ul>
          ) : (
            <EmptyState icon={<GitFork size={18} />} title="This service has no business service CI" description="Link the service to a BUSINESS_SERVICE configuration item in the CMDB to model its dependencies." />
          )}
        </>
      )}
    </Panel>
  );
}
