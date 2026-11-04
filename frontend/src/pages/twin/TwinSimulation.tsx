import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { FlaskConical, History, Layers3, Network, Play, ShieldAlert, Timer, Users } from 'lucide-react';
import { KpiCard } from '../../components/KpiCard';
import { useRunTwinSimulation, useTwinGraph, useTwinSimulation, useTwinSimulations } from '../../api/twin';
import { CiMultiPicker } from '../../components/cmdb/CiPicker';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { TwinGraphView, type TwinHighlight } from '../../components/twin/TwinGraphView';
import { impactPathEdges, TWIN_LAYER_LABELS } from '../../components/twin/twinLayout';
import { errorMessage, getJson } from '../../lib/api';
import { formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import type { CiRef } from '../../lib/opsTypes';
import type { ChangeSummary } from '../../lib/itsmTypes';
import type { TwinLayer, TwinSimulationMode, TwinSimulationResult, TwinSimulationType } from '../../lib/r4Types';
import type { PageResponse } from '../../lib/types';

export const SIMULATION_LABEL = 'Simulation — no real systems were changed';

const USERS_BASIS: Record<string, string> = {
  CI_ATTRIBUTE: 'from the business service CI attribute users/userCount',
  MAX_RECORDED_INCIDENT_IMPACT_180D: 'highest affected-user count recorded on its incidents in 180 days',
  UNKNOWN: 'unknown — no user data recorded',
  NO_SERVICES_IMPACTED: 'no services impacted',
  MAX_OVER_SERVICES: 'largest estimate over the impacted services',
  PARTIAL_MAX_OVER_SERVICES: 'largest estimate over the services that have user data (others unknown)',
};

/** Highlight for the twin graph from a simulation result. */
export function simulationHighlight(result: TwinSimulationResult | undefined, edges: { id: number; dependentId: number; dependencyId: number }[]): TwinHighlight | null {
  if (!result) return null;
  return {
    impacted: new Map(result.impactedCis.map((ci) => [ci.ciId, ci.impact])),
    targets: new Set(result.targets.map((target) => target.id)),
    edges: impactPathEdges(
      result.impactedCis.map((ci) => ci.pathIds ?? []),
      edges,
    ),
  };
}

function ImpactTone({ impact }: { impact: string }) {
  return <ToneBadge value={impact} label={titleCase(impact)} tone={impact === 'DOWN' ? 'critical' : 'warning'} />;
}

export function SimulationResultView({ result }: { result: TwinSimulationResult }) {
  return (
    <div className="grid gap-[14px]" aria-label="Simulation result">
      <div className="callout" role="note">
        <FlaskConical size={16} aria-hidden="true" />
        <div>
          <b>{SIMULATION_LABEL}.</b> {result.label} #{result.id} · {titleCase(result.type)} · {titleCase(result.mode)}
          {result.name ? ` · “${result.name}”` : ''} · run {formatDateTime(result.createdAt)}
          {result.createdByName ? ` by ${result.createdByName}` : ''}
          {result.changeNumber ? (
            <>
              {' '}
              · change{' '}
              <Link to={`/changes/${result.changeId}`} className="id-cell">
                {result.changeNumber}
              </Link>
            </>
          ) : null}
        </div>
      </div>
      <div className="kpi-grid">
        <KpiCard
          icon={<Network />}
          label="Impacted CIs"
          value={formatNumber(result.impactedCis.length)}
          tone={result.impactedCis.length ? 'critical' : 'success'}
          caption={(['BUSINESS', 'APPLICATION', 'INFRASTRUCTURE'] as TwinLayer[]).map((layer) => `${formatNumber(result.impactByLayer?.[layer] ?? 0)} ${TWIN_LAYER_LABELS[layer].toLowerCase()}`).join(' · ')}
        />
        <KpiCard icon={<Layers3 />} label="Impacted services" value={formatNumber(result.impactedServices.length)} tone={result.impactedServices.length ? 'warning' : 'success'} />
        <KpiCard
          icon={<Users />}
          label="Estimated affected users"
          value={result.estimatedAffectedUsers === null ? 'Unknown' : formatNumber(result.estimatedAffectedUsers)}
          tone="neutral"
          caption={USERS_BASIS[result.affectedUsersBasis ?? 'UNKNOWN'] ?? result.affectedUsersBasis ?? undefined}
        />
        <KpiCard icon={<Timer />} label="SLAs at risk" value={formatNumber(result.slasAtRisk.length)} tone={result.slasAtRisk.length ? 'critical' : 'success'} caption={`${formatNumber(result.scheduledChanges.length)} scheduled changes touch the impact`} />
      </div>
      {result.notes && result.notes.length > 0 && (
        <ul className="m-0 grid gap-1 pl-4 text-[11px] text-muted" aria-label="Simulation notes">
          {result.notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
      <div className="grid items-start gap-[14px] xl:grid-cols-2">
        <Panel title="Impacted services" subtitle={SIMULATION_LABEL}>
          {result.impactedServices.length === 0 ? (
            <EmptyState title="No services impacted" />
          ) : (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Impacted services">
              {result.impactedServices.map((service) => (
                <li key={service.serviceId} className="list-row">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <Link to={`/services/${service.serviceId}`}>{service.name}</Link> <ImpactTone impact={service.impact} /> <span className="text-muted">Tier {service.tier}</span>
                    </b>
                    {service.path && service.path.length > 0 && <small className="block break-words">{service.path.join(' → ')}</small>}
                    <small className="block text-muted">
                      Users: {service.estimatedAffectedUsers === null ? 'unknown' : formatNumber(service.estimatedAffectedUsers)} ({USERS_BASIS[service.affectedUsersBasis] ?? service.affectedUsersBasis})
                    </small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <Panel title="Impacted configuration items" subtitle="Propagated through dependency edges; redundancy lowers the impact by one level">
          {result.impactedCis.length === 0 ? (
            <EmptyState title="No configuration items impacted" />
          ) : (
            <div className="table-scroll">
              <table className="data-table">
                <caption className="sr-only">Impacted configuration items</caption>
                <thead>
                  <tr>
                    <th scope="col">CI</th>
                    <th scope="col">Layer</th>
                    <th scope="col">Impact</th>
                    <th scope="col">Depth</th>
                    <th scope="col">Path</th>
                  </tr>
                </thead>
                <tbody>
                  {result.impactedCis.map((ci) => (
                    <tr key={ci.ciId}>
                      <td>
                        <Link to={`/cmdb/${ci.ciId}`}>{ci.name}</Link>
                        {ci.redundancyApplied && <small className="block text-muted">redundancy applied</small>}
                      </td>
                      <td>{TWIN_LAYER_LABELS[ci.layer] ?? ci.layer}</td>
                      <td>
                        <ImpactTone impact={ci.impact} />
                      </td>
                      <td>{ci.depth}</td>
                      <td className="text-muted" title={ci.reason ?? undefined}>
                        {ci.path.join(' → ')}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
        <Panel title="SLAs at risk" subtitle="Active incidents with an open SLA on impacted services">
          {result.slasAtRisk.length === 0 ? (
            <EmptyState title="No open SLAs on impacted services" />
          ) : (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="SLAs at risk">
              {result.slasAtRisk.map((sla) => (
                <li key={sla.incidentId} className="list-row">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <Link to={`/incidents/${sla.incidentId}`} className="id-cell">
                        {sla.number}
                      </Link>
                      <span className="break-words">{sla.title}</span>
                    </b>
                    <small className="block">
                      {titleCase(sla.priority)} · {sla.serviceName ?? '—'}
                      {sla.health ? ` · SLA ${titleCase(sla.health)}` : ''}
                      {sla.resolutionDueAt ? ` · resolution due ${formatDateTime(sla.resolutionDueAt)}` : ''}
                    </small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <Panel title="Scheduled changes" subtitle="Awaiting approval, approved, scheduled or implementing changes touching impacted CIs or services">
          {result.scheduledChanges.length === 0 ? (
            <EmptyState title="No scheduled changes touch the impact" />
          ) : (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Scheduled changes">
              {result.scheduledChanges.map((change) => (
                <li key={change.changeId} className="list-row">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <Link to={`/changes/${change.changeId}`} className="id-cell">
                        {change.number}
                      </Link>
                      <span className="break-words">{change.title}</span>
                    </b>
                    <small className="block">
                      {titleCase(change.status)} · touches {change.touches === 'CI' ? 'an impacted CI' : 'an impacted service'}
                      {change.plannedStart ? ` · ${formatDateTime(change.plannedStart)} → ${formatDateTime(change.plannedEnd)}` : ''}
                    </small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </div>
  );
}

/** Simulation: failure or change impact (read-only, persisted with who/when) with the impact highlighted on the twin. */
export function TwinSimulationTab({ includeSimulated, initialTargets }: { includeSimulated: boolean; initialTargets: CiRef[] }) {
  const run = useRunTwinSimulation();
  const history = useTwinSimulations(20);
  const toast = useToast();
  const [type, setType] = useState<TwinSimulationType>('FAILURE');
  const [mode, setMode] = useState<TwinSimulationMode>('HARD');
  const [targets, setTargets] = useState<CiRef[]>(initialTargets);
  const [changeNumber, setChangeNumber] = useState('');
  const [name, setName] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [resultId, setResultId] = useState<number | null>(null);
  const result = useTwinSimulation(resultId);
  const graph = useTwinGraph({ layer: 'ALL', depth: 10, includeState: true, includeSimulated }, Boolean(result.data));
  const highlight = useMemo(() => simulationHighlight(result.data, graph.data?.edges ?? []), [result.data, graph.data]);

  const submit = async () => {
    setFormError(null);
    let changeId: number | undefined;
    if (type === 'FAILURE' && targets.length === 0) return setFormError('Pick at least one configuration item to fail');
    if (type === 'CHANGE') {
      const number = changeNumber.trim().toUpperCase();
      if (!number) return setFormError('Enter the change number to simulate');
      try {
        const page = await getJson<PageResponse<ChangeSummary>>('/changes', { search: number, size: 5 });
        const change = page.content.find((entry) => entry.number.toUpperCase() === number);
        if (!change) return setFormError(`${number} was not found`);
        changeId = change.id;
      } catch (error) {
        return setFormError(errorMessage(error));
      }
    }
    run.mutate(
      { type, mode, targets: targets.length ? targets.map((ci) => ci.id) : undefined, changeId, name: name.trim() || undefined },
      {
        onSuccess: (data) => {
          setResultId(data.id);
          toast.success(`Simulation #${data.id}: ${formatNumber(data.impactedCis.length)} CIs, ${formatNumber(data.impactedServices.length)} services impacted`);
        },
        onError: (error) => setFormError(errorMessage(error)),
      },
    );
  };

  return (
    <div className="grid gap-[14px]">
      <div className="grid items-start gap-[14px] xl:grid-cols-[minmax(0,1fr)_340px]">
        <Panel title="Run a simulation" subtitle="Read-only: computes the impact from the twin and stores it in the history; real data is never changed">
          <div className="grid gap-2 sm:grid-cols-2">
            <div className="field">
              <div className="field-label">
                <label htmlFor="sim-type">Type</label>
              </div>
              <select
                id="sim-type"
                className="control"
                value={type}
                onChange={(event) => {
                  const next = event.target.value as TwinSimulationType;
                  setType(next);
                  setMode(next === 'FAILURE' ? 'HARD' : 'DEGRADED');
                }}
              >
                <option value="FAILURE">Failure of configuration items</option>
                <option value="CHANGE">Change request impact</option>
              </select>
            </div>
            <div className="field">
              <div className="field-label">
                <label htmlFor="sim-mode">Mode</label>
              </div>
              <select id="sim-mode" className="control" value={mode} onChange={(event) => setMode(event.target.value as TwinSimulationMode)}>
                <option value="HARD">Hard (targets down)</option>
                <option value="DEGRADED">Degraded (targets degraded)</option>
              </select>
            </div>
            {type === 'CHANGE' && (
              <div className="field">
                <div className="field-label">
                  <label htmlFor="sim-change">Change number</label>
                </div>
                <input id="sim-change" className="control" placeholder="CHG-1001" value={changeNumber} onChange={(event) => setChangeNumber(event.target.value)} />
                <span className="field-hint">Targets default to the change’s CIs and service CIs.</span>
              </div>
            )}
            <div className="field">
              <div className="field-label">
                <label htmlFor="sim-name">Name (optional)</label>
              </div>
              <input id="sim-name" className="control" maxLength={200} value={name} onChange={(event) => setName(event.target.value)} />
            </div>
            <div className="sm:col-span-2">
              <CiMultiPicker label={type === 'FAILURE' ? 'Configuration items to fail' : 'Extra targets (optional)'} value={targets} onChange={setTargets} max={50} />
            </div>
          </div>
          {formError && (
            <p className="field-error" role="alert">
              {formError}
            </p>
          )}
          <div className="mt-3 flex justify-end">
            <button type="button" className="gold-btn" onClick={() => void submit()} disabled={run.isPending}>
              <Play size={14} aria-hidden="true" /> {run.isPending ? 'Simulating…' : 'Run simulation'}
            </button>
          </div>
        </Panel>
        <Panel title="History" subtitle="Simulations run by anyone, newest first">
          {history.isLoading && <LoadingState rows={4} />}
          {history.isError && <ErrorState error={history.error} onRetry={() => void history.refetch()} compact />}
          {history.data && history.data.length === 0 && <EmptyState icon={<History size={18} />} title="No simulations yet" />}
          {history.data && history.data.length > 0 && (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Simulation history">
              {history.data.map((entry) => (
                <li key={entry.id}>
                  <button type="button" className={`list-row w-full text-left ${entry.id === resultId ? 'active' : ''}`} aria-pressed={entry.id === resultId} onClick={() => setResultId(entry.id)}>
                    <span className="min-w-0">
                      <b className="block break-words">
                        #{entry.id} {entry.name ?? `${titleCase(entry.type)} · ${titleCase(entry.mode)}`}
                      </b>
                      <small className="block">
                        {formatNumber(entry.impactedCiCount)} CIs · {formatNumber(entry.impactedServiceCount)} services · {entry.createdByName ?? 'Unknown'} · {formatRelative(entry.createdAt)}
                      </small>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      {resultId !== null && result.isLoading && <LoadingState variant="panel" label="Loading simulation…" />}
      {resultId !== null && result.isError && <ErrorState error={result.error} onRetry={() => void result.refetch()} />}
      {result.data && (
        <>
          <SimulationResultView result={result.data} />
          <Panel title="Impact on the twin" subtitle={SIMULATION_LABEL}>
            {graph.isLoading && <LoadingState variant="panel" label="Loading the twin…" />}
            {graph.isError && <ErrorState error={graph.error} onRetry={() => void graph.refetch()} />}
            {graph.data && graph.data.nodes.length === 0 && <EmptyState title="The twin has no nodes" />}
            {graph.data && graph.data.nodes.length > 0 && (
              <>
                {graph.data.truncated && (
                  <p className="callout mb-2" role="note">
                    <ShieldAlert size={14} aria-hidden="true" /> The twin was truncated to {formatNumber(graph.data.nodes.length)} nodes; impacted CIs outside it are listed above.
                  </p>
                )}
                <TwinGraphView graph={graph.data} selected={null} onSelect={() => undefined} highlight={highlight} height={520} />
              </>
            )}
          </Panel>
        </>
      )}
    </div>
  );
}

