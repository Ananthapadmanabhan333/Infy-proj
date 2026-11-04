import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Crosshair, ExternalLink, FlaskConical, History, Waypoints, X } from 'lucide-react';
import { useSetRedundancy, useTwinGraph } from '../../api/twin';
import { useCi } from '../../api/cmdb';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { IncludeSimulatedToggle } from '../../components/sim/SimulationParts';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { TwinGraphView } from '../../components/twin/TwinGraphView';
import { TWIN_LAYER_LABELS, TWIN_LAYERS } from '../../components/twin/twinLayout';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { errorMessage } from '../../lib/api';
import { TWIN_EDITORS } from '../../lib/constants';
import { formatDateTime, formatNumber, titleCase } from '../../lib/format';
import { CI_TYPE_LABELS } from '../../lib/ops';
import type { CiRef } from '../../lib/opsTypes';
import type { TwinEdge, TwinGraph, TwinNode } from '../../lib/r4Types';
import { HealthBadge, TwinTreeTab } from './TwinTree';
import { TwinSimulationTab } from './TwinSimulation';
import { TwinTimelineTab } from './TwinTimeline';

type TwinTab = 'model' | 'services' | 'simulate' | 'changes';
const TABS: TwinTab[] = ['model', 'services', 'simulate', 'changes'];

function asCiRef(node: TwinNode): CiRef {
  return { id: node.id, name: node.name, ciType: node.ciType, status: node.status as CiRef['status'], environment: node.environment as CiRef['environment'] };
}

function NodeDetail({
  node,
  graph,
  canEdit,
  onClose,
  onFocus,
  onSimulate,
  onTimeline,
}: {
  node: TwinNode;
  graph: TwinGraph;
  canEdit: boolean;
  onClose: () => void;
  onFocus: (id: number) => void;
  onSimulate: (node: TwinNode) => void;
  onTimeline: (id: number) => void;
}) {
  const setRedundancy = useSetRedundancy();
  const toast = useToast();
  const [toggling, setToggling] = useState<TwinEdge | null>(null);
  const byId = new Map(graph.nodes.map((entry) => [entry.id, entry]));
  const needs = graph.edges.filter((edge) => edge.dependentId === node.id);
  const neededBy = graph.edges.filter((edge) => edge.dependencyId === node.id);
  const state = node.state;

  const EdgeRow = ({ edge, other }: { edge: TwinEdge; other: number }) => (
    <li className="flex flex-wrap items-center gap-1.5 py-0.5 text-[11px]">
      <span className="min-w-0 break-words">{byId.get(other)?.name ?? `CI #${other}`}</span>
      <span className="text-muted">{titleCase(edge.type)}</span>
      {edge.redundant && <ToneBadge value="redundant" label="Redundant" tone="success" />}
      {canEdit && (
        <button type="button" className="link-btn" onClick={() => setToggling(edge)} aria-label={`${edge.redundant ? 'Clear' : 'Mark'} redundancy of the relationship with ${byId.get(other)?.name ?? other}`}>
          {edge.redundant ? 'Mark not redundant' : 'Mark redundant'}
        </button>
      )}
    </li>
  );

  return (
    <Panel
      title={node.name}
      subtitle={`${CI_TYPE_LABELS[node.ciType] ?? node.ciType} · ${TWIN_LAYER_LABELS[node.layer] ?? node.layer}`}
      actions={
        <button type="button" className="icon-btn sm" aria-label="Close node detail" onClick={onClose}>
          <X size={13} />
        </button>
      }
    >
      <div className="mb-2 flex flex-wrap items-center gap-1.5 text-[11px]">
        <HealthBadge state={state} />
        <ToneBadge value={node.status} label={titleCase(node.status)} tone="neutral" />
        {node.environment && <ToneBadge value={node.environment} label={titleCase(node.environment)} tone="neutral" />}
        {node.criticality && <ToneBadge value={node.criticality} label={`${titleCase(node.criticality)} criticality`} tone={node.criticality === 'CRITICAL' ? 'critical' : 'neutral'} />}
        {node.cluster && <ToneBadge value="cluster" label={`Cluster ${node.cluster}`} tone="champagne" />}
      </div>
      {state ? (
        <ul className="kv-list mb-2" aria-label="Live state">
          <li>
            <span>Open incidents</span>
            <span>{formatNumber(state.openIncidents)}</span>
          </li>
          <li>
            <span>Firing alerts</span>
            <span>{formatNumber(state.firingAlerts)}</span>
          </li>
          <li>
            <span>Open anomalies</span>
            <span>{formatNumber(state.openAnomalies)}</span>
          </li>
          <li>
            <span>Last change</span>
            <span>{state.lastChangeAt ? `${formatDateTime(state.lastChangeAt)}${state.lastChange ? ` · ${state.lastChange}` : ''}` : 'None recorded'}</span>
          </li>
        </ul>
      ) : (
        <small className="mb-2 block text-muted">State overlays are off.</small>
      )}
      {state?.healthReason && <small className="block text-muted">{state.healthReason}</small>}
      <div className="my-3 flex flex-wrap gap-1.5">
        <Link to={`/cmdb/${node.id}`} className="subtle-btn compact">
          <ExternalLink size={11} aria-hidden="true" /> Open CI
        </Link>
        <button type="button" className="subtle-btn compact" onClick={() => onFocus(node.id)}>
          <Crosshair size={11} aria-hidden="true" /> Focus here
        </button>
        <button type="button" className="subtle-btn compact" onClick={() => onSimulate(node)}>
          <FlaskConical size={11} aria-hidden="true" /> Simulate failure
        </button>
        <button type="button" className="subtle-btn compact" onClick={() => onTimeline(node.id)}>
          <History size={11} aria-hidden="true" /> Changes
        </button>
      </div>
      <b className="block text-[11px]">Needs ({needs.length})</b>
      {needs.length ? (
        <ul className="m-0 mb-2 list-none p-0" aria-label="Dependencies">
          {needs.map((edge) => (
            <EdgeRow key={edge.id} edge={edge} other={edge.dependencyId} />
          ))}
        </ul>
      ) : (
        <small className="mb-2 block text-muted">No dependencies in this view.</small>
      )}
      <b className="block text-[11px]">Needed by ({neededBy.length})</b>
      {neededBy.length ? (
        <ul className="m-0 list-none p-0" aria-label="Dependents">
          {neededBy.map((edge) => (
            <EdgeRow key={edge.id} edge={edge} other={edge.dependentId} />
          ))}
        </ul>
      ) : (
        <small className="block text-muted">Nothing depends on it in this view.</small>
      )}
      <ConfirmDialog
        open={toggling !== null}
        onClose={() => {
          setRedundancy.reset();
          setToggling(null);
        }}
        title={toggling?.redundant ? 'Mark the relationship as not redundant?' : 'Mark the relationship as redundant?'}
        message="Redundancy lowers the simulated impact through this dependency by one level when a healthy peer exists. The change is recorded in the CI history and audited."
        confirmLabel={toggling?.redundant ? 'Mark not redundant' : 'Mark redundant'}
        reasonLabel="Note (optional)"
        pending={setRedundancy.isPending}
        error={setRedundancy.isError ? errorMessage(setRedundancy.error) : null}
        onConfirm={(note) =>
          toggling &&
          setRedundancy.mutate(
            { id: toggling.id, redundant: !toggling.redundant, note },
            {
              onSuccess: (edge) => {
                toast.success(edge.redundant ? 'Marked redundant' : 'Redundancy cleared');
                setToggling(null);
              },
            },
          )
        }
      />
    </Panel>
  );
}

function ModelTab({ includeSimulated, onSimulate, onTimeline }: { includeSimulated: boolean; onSimulate: (node: TwinNode) => void; onTimeline: (id: number) => void }) {
  const { hasRole } = useAuth();
  const canEdit = hasRole(...TWIN_EDITORS);
  const [params, setParams] = useSearchParams();
  const root = Number(params.get('root')) || undefined;
  const [layer, setLayer] = useState('ALL');
  const [depth, setDepth] = useState(root ? 2 : 4);
  const [includeState, setIncludeState] = useState(true);
  const [selected, setSelected] = useState<number | null>(root ?? null);
  const rootCi = useCi(root ?? null);
  const graph = useTwinGraph({ root, depth, layer, includeState, includeSimulated });
  const data = graph.data;
  const selectedNode = data?.nodes.find((node) => node.id === selected) ?? null;
  const setRoot = (id: number | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (id) next.set('root', String(id));
        else next.delete('root');
        return next;
      },
      { replace: true },
    );

  return (
    <div className="grid items-start gap-[14px] xl:grid-cols-[minmax(0,1fr)_320px]">
      <Panel
        title="Twin model"
        subtitle="Built from CMDB CIs, relationships and business services — no second inventory"
        actions={
          <span className="flex flex-wrap items-center gap-2">
            <label htmlFor="twin-layer" className="sr-only">
              Layer
            </label>
            <select id="twin-layer" className="control sm w-auto" value={layer} onChange={(event) => setLayer(event.target.value)}>
              <option value="ALL">All layers</option>
              {TWIN_LAYERS.map((value) => (
                <option key={value} value={value}>
                  {TWIN_LAYER_LABELS[value]}
                </option>
              ))}
            </select>
            <label htmlFor="twin-depth" className="sr-only">
              Depth
            </label>
            <select id="twin-depth" className="control sm w-auto" value={depth} onChange={(event) => setDepth(Number(event.target.value))}>
              {[1, 2, 3, 4, 6, 8, 10].map((value) => (
                <option key={value} value={value}>
                  Depth {value}
                </option>
              ))}
            </select>
            <label className="inline-flex items-center gap-1.5 text-[11px]">
              <input type="checkbox" checked={includeState} onChange={(event) => setIncludeState(event.target.checked)} /> State overlays
            </label>
          </span>
        }
      >
        {root && (
          <div className="tag-row mb-2" aria-label="Focus">
            <span className="chip">
              Focused on {rootCi.data?.name ?? `CI #${root}`}
              <button type="button" className="icon-btn sm !h-4 !w-4" aria-label="Show the whole twin" onClick={() => setRoot(null)}>
                <X size={10} />
              </button>
            </span>
          </div>
        )}
        {graph.isLoading && <LoadingState variant="panel" label="Loading the twin…" />}
        {graph.isError && <ErrorState error={graph.error} onRetry={() => void graph.refetch()} />}
        {data && data.nodes.length === 0 && <EmptyState icon={<Waypoints size={20} />} title="The twin is empty" description="Add business services, applications and infrastructure CIs with relationships in the CMDB." />}
        {data && data.nodes.length > 0 && (
          <>
            <p className="mb-2 mt-0 text-[11px] text-muted">
              {formatNumber(data.nodes.length)} nodes · {formatNumber(data.edges.length)} relationships ·{' '}
              {TWIN_LAYERS.map((value) => `${formatNumber(data.layerCounts?.[value] ?? 0)} ${TWIN_LAYER_LABELS[value].toLowerCase()}`).join(' · ')}
              {data.truncated ? ' · truncated to 300 nodes, focus on a CI to see more' : ''}
            </p>
            <TwinGraphView graph={data} selected={selected} onSelect={setSelected} />
          </>
        )}
      </Panel>
      {selectedNode && data ? (
        <NodeDetail node={selectedNode} graph={data} canEdit={canEdit} onClose={() => setSelected(null)} onFocus={(id) => setRoot(id)} onSimulate={onSimulate} onTimeline={onTimeline} />
      ) : (
        <Panel>
          <EmptyState title="Select a node" description="State, dependencies, redundancy and actions for the selected configuration item appear here." />
        </Panel>
      )}
    </div>
  );
}

/**
 * /digital-twin (AUTONOMOUS OPERATIONS, OBSERVABILITY_READERS): layered twin with live state, business service tree,
 * failure/change impact simulation (read-only, open to readers) and the unified change timeline. TWIN_EDITORS toggle
 * relationship redundancy.
 */
export default function DigitalTwinPage() {
  const [tab, setTab] = useTabParam<TwinTab>(TABS, 'model');
  const [params, setParams] = useSearchParams();
  const includeSimulated = params.get('sim') === '1';
  const [targets, setTargets] = useState<CiRef[]>([]);
  const [timelineCi, setTimelineCi] = useState<number | null>(null);
  const [simKey, setSimKey] = useState(0);
  const setIncludeSimulated = (next: boolean) =>
    setParams(
      (current) => {
        const updated = new URLSearchParams(current);
        if (next) updated.set('sim', '1');
        else updated.delete('sim');
        return updated;
      },
      { replace: true },
    );

  return (
    <>
      <PageHeader title="Digital Twin" subtitle="Business services, applications and infrastructure as one live model — with impact simulation" actions={<IncludeSimulatedToggle value={includeSimulated} onChange={setIncludeSimulated} />} />
      <div className="page-stack">
        <Tabs<TwinTab>
          label="Digital twin views"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'model', label: 'Model' },
            { id: 'services', label: 'Business services' },
            { id: 'simulate', label: 'Simulation' },
            { id: 'changes', label: 'Change timeline' },
          ]}
        />
        {tab === 'model' && (
          <ModelTab
            includeSimulated={includeSimulated}
            onSimulate={(node) => {
              setTargets([asCiRef(node)]);
              setSimKey((key) => key + 1);
              setTab('simulate');
            }}
            onTimeline={(id) => {
              setTimelineCi(id);
              setTab('changes');
            }}
          />
        )}
        {tab === 'services' && <TwinTreeTab includeSimulated={includeSimulated} />}
        {tab === 'simulate' && <TwinSimulationTab key={simKey} includeSimulated={includeSimulated} initialTargets={targets} />}
        {tab === 'changes' && <TwinTimelineTab includeSimulated={includeSimulated} initialCi={timelineCi} />}
      </div>
    </>
  );
}
