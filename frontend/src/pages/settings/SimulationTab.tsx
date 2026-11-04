import { useState } from 'react';
import { FlaskConical, Play, ShieldOff, Square, Trash2 } from 'lucide-react';
import { simulatedRecordTotal, useSetSimulationMode, useSimulationRuns, useSimulationStatus, useStartScenario, useStopScenario, usePurgeSimulatedData } from '../../api/simulation';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { errorMessage } from '../../lib/api';
import { formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import type { SimulationRunDto, SimulationRunStatus } from '../../lib/r4Types';
import '../../components/sim/sim.css';

const RUN_TONES: Record<SimulationRunStatus, 'gold' | 'muted' | 'success' | 'critical'> = { RUNNING: 'gold', STOPPED: 'muted', COMPLETED: 'success', FAILED: 'critical' };

/** The exact text the administrator types to confirm the purge. */
export const PURGE_CONFIRMATION = 'DELETE SIMULATED DATA';

function summaryText(run: SimulationRunDto): string {
  const summary = run.summary ?? {};
  const parts = (['metricPoints', 'logRecords', 'spans', 'deployments'] as const)
    .filter((key) => typeof summary[key] === 'number')
    .map((key) => `${formatNumber(summary[key] as number)} ${titleCase(key.replace(/([A-Z])/g, ' $1')).toLowerCase()}`);
  return parts.join(' · ');
}

function PurgeDialog({ total, onClose }: { total: number; onClose: () => void }) {
  const purge = usePurgeSimulatedData();
  const toast = useToast();
  const [typed, setTyped] = useState('');
  const ok = typed.trim() === PURGE_CONFIRMATION;
  const run = () =>
    purge.mutate(undefined, {
      onSuccess: (counts) => {
        const deleted = Object.values(counts ?? {}).reduce((sum, value) => sum + (value || 0), 0);
        toast.success(`Deleted ${formatNumber(deleted)} simulated record${deleted === 1 ? '' : 's'}`);
        onClose();
      },
    });
  return (
    <Modal
      open
      onClose={onClose}
      title="Delete simulated data"
      eyebrow="PERMANENT"
      description="Stops running scenarios and deletes every row flagged simulated = true (telemetry, anomalies, correlation groups, alerts, incidents, deployments and fault windows). Real and seeded demo records are not touched. The purge is audited."
      dismissible={!purge.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={purge.isPending}>
            Cancel
          </button>
          <button type="button" className="danger-btn" onClick={run} disabled={!ok || purge.isPending}>
            {purge.isPending ? 'Deleting…' : 'Delete simulated data'}
          </button>
        </>
      }
    >
      <p className="mb-2 text-[12px]">
        {formatNumber(total)} simulated record{total === 1 ? '' : 's'} are stored. Type <code>{PURGE_CONFIRMATION}</code> to confirm.
      </p>
      <div className="field">
        <div className="field-label">
          <label htmlFor="sim-purge-confirm">Confirmation</label>
        </div>
        <input id="sim-purge-confirm" className="control" value={typed} onChange={(event) => setTyped(event.target.value)} autoComplete="off" />
      </div>
      {purge.isError && (
        <p className="field-error" role="alert">
          {errorMessage(purge.error)}
        </p>
      )}
    </Modal>
  );
}

/** Settings → Simulation mode (SIMULATION_ADMINS = SYSTEM_ADMIN): mode switch, scenarios, runs, stored simulated rows and purge. */
export function SimulationTab() {
  const status = useSimulationStatus(true, 15_000);
  const runs = useSimulationRuns(20);
  const setMode = useSetSimulationMode();
  const start = useStartScenario();
  const stop = useStopScenario();
  const toast = useToast();
  const [confirmMode, setConfirmMode] = useState<boolean | null>(null);
  const [purging, setPurging] = useState(false);
  const [pendingCode, setPendingCode] = useState<string | null>(null);

  if (status.isLoading) return <LoadingState variant="panel" label="Loading simulation status…" />;
  if (status.isError || !status.data) return <ErrorState error={status.error} onRetry={() => void status.refetch()} />;
  const data = status.data;
  const records = Object.entries(data.simulatedRecords ?? {}).sort((a, b) => b[1] - a[1]);
  const total = simulatedRecordTotal(data);

  const runScenario = (code: string, running: boolean) => {
    setPendingCode(code);
    (running ? stop : start).mutate(code, {
      onSuccess: (run) => toast.success(`${run.scenario} ${running ? 'stopped' : 'started'}`),
      onError: (error) => toast.error(error),
      onSettled: () => setPendingCode(null),
    });
  };

  return (
    <div className="grid gap-[14px]">
      <Panel
        title={
          <span className="inline-flex items-center gap-2">
            <FlaskConical size={15} className="gold-text" /> Simulation mode
          </span>
        }
        subtitle="The only source of sample data: scenarios generate telemetry and deployments through the real ingestion services, flagged SIMULATION"
        actions={
          data.settingEnabled ? (
            <button type="button" className="subtle-btn" onClick={() => setConfirmMode(false)}>
              <ShieldOff size={14} aria-hidden="true" /> Disable simulation mode
            </button>
          ) : data.allowed ? (
            <button type="button" className="gold-btn" onClick={() => setConfirmMode(true)}>
              <FlaskConical size={14} aria-hidden="true" /> Enable simulation mode
            </button>
          ) : undefined
        }
      >
        <div className="flex flex-wrap items-center gap-2 text-[11px]" aria-label="Simulation status">
          <ToneBadge value={data.enabled ? 'on' : 'off'} label={data.enabled ? 'On' : 'Off'} tone={data.enabled ? 'warning' : 'muted'} />
          <span className="text-muted">Setting {data.settingEnabled ? 'on' : 'off'}</span>
          <span className="text-muted">· Deployment {data.allowed ? 'allows simulation' : 'does not allow simulation'}</span>
          <span className="text-muted">
            · {formatNumber(data.runningRuns.length)} running · {formatNumber(total)} simulated records stored
          </span>
        </div>
        {!data.allowed && (
          <div className="callout red mt-3" role="note">
            <ShieldOff size={16} aria-hidden="true" />
            <div>
              <b>The server does not allow simulation mode.</b> This deployment runs with <code>SIMULATION_ALLOWED=false</code>{' '}
              (<code>app.simulation.allowed</code>, the default outside the local profile), so the mode cannot be switched on here and no
              scenario can start. An operator must set <code>SIMULATION_ALLOWED=true</code> on the API to permit it.
              {data.settingEnabled ? ' The stored setting is still on but has no effect; switch it off to tidy up.' : ''}
            </div>
          </div>
        )}
        {data.allowed && !data.enabled && (
          <p className="mb-0 mt-3 text-[11px] text-muted">Simulation mode is off. Switch it on to start scenarios; switching it off stops every running scenario.</p>
        )}
      </Panel>

      <div className="grid items-start gap-[14px] xl:grid-cols-[1.4fr_1fr]">
        <Panel title="Scenarios" subtitle="Generated data is flagged SIMULATION and excluded from analytics unless included explicitly">
          {data.scenarios.length === 0 ? (
            <EmptyState title="No scenarios registered" description="The API exposes no simulation scenarios." />
          ) : (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Simulation scenarios">
              {data.scenarios.map((scenario) => (
                <li key={scenario.code} className="list-row flex-wrap !items-start sm:flex-nowrap">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <span className="break-words">{scenario.title}</span>
                      <code className="text-[10px] text-muted">{scenario.code}</code>
                      {scenario.running && <ToneBadge value="running" label="Running" tone="gold" />}
                      <ToneBadge value={scenario.module} label={titleCase(scenario.module)} tone="neutral" />
                    </b>
                    <small className="block whitespace-pre-wrap break-words">{scenario.description}</small>
                    <small className="block text-muted">
                      Tick every {formatNumber(scenario.tickIntervalSeconds)} s
                      {scenario.running && scenario.startedAt ? ` · started ${formatRelative(scenario.startedAt)} · ${formatNumber(scenario.tickCount)} ticks` : ''}
                      {scenario.running && scenario.lastTickAt ? ` · last tick ${formatRelative(scenario.lastTickAt)}` : ''}
                    </small>
                  </div>
                  {(scenario.running || data.enabled) && (
                    <button
                      type="button"
                      className={`${scenario.running ? 'subtle-btn' : 'gold-btn'} compact shrink-0`}
                      onClick={() => runScenario(scenario.code, scenario.running)}
                      disabled={pendingCode !== null}
                      aria-label={`${scenario.running ? 'Stop' : 'Start'} ${scenario.title}`}
                    >
                      {scenario.running ? <Square size={12} aria-hidden="true" /> : <Play size={12} aria-hidden="true" />}
                      {pendingCode === scenario.code ? 'Working…' : scenario.running ? 'Stop' : 'Start'}
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel
          title="Simulated records"
          subtitle="Rows flagged simulated = true, per table"
          actions={
            total > 0 ? (
              <button type="button" className="danger-btn compact" onClick={() => setPurging(true)}>
                <Trash2 size={12} aria-hidden="true" /> Delete simulated data
              </button>
            ) : undefined
          }
        >
          {records.length === 0 || total === 0 ? (
            <EmptyState title="No simulated data stored" description="Start a scenario to generate labelled sample data." />
          ) : (
            <ul className="kv-list" aria-label="Simulated records per table">
              {records
                .filter(([, count]) => count > 0)
                .map(([table, count]) => (
                  <li key={table}>
                    <span>{table}</span>
                    <span>{formatNumber(count)}</span>
                  </li>
                ))}
            </ul>
          )}
        </Panel>
      </div>

      <Panel title="Runs" subtitle="Newest first">
        {runs.isLoading && <LoadingState rows={3} />}
        {runs.isError && <ErrorState error={runs.error} onRetry={() => void runs.refetch()} compact />}
        {runs.data && runs.data.length === 0 && <EmptyState title="No scenario runs yet" />}
        {runs.data && runs.data.length > 0 && (
          <ul className="m-0 grid list-none gap-1 p-0" aria-label="Scenario runs">
            {runs.data.map((run) => (
              <li key={run.id} className="list-row flex-wrap">
                <div className="min-w-0">
                  <b className="flex flex-wrap items-center gap-1.5">
                    {run.scenario} <ToneBadge value={run.status} label={titleCase(run.status)} tone={RUN_TONES[run.status] ?? 'neutral'} />
                  </b>
                  <small className="block">
                    Started {formatDateTime(run.startedAt)}
                    {run.startedByName ? ` by ${run.startedByName}` : ''}
                    {run.stoppedAt ? ` · stopped ${formatDateTime(run.stoppedAt)}${run.stoppedByName ? ` by ${run.stoppedByName}` : ''}` : ''} · {formatNumber(run.tickCount)} ticks
                  </small>
                  {summaryText(run) && <small className="block text-muted">{summaryText(run)}</small>}
                  {run.error && <small className="block red-text break-words">{run.error}</small>}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <ConfirmDialog
        open={confirmMode !== null}
        onClose={() => {
          setMode.reset();
          setConfirmMode(null);
        }}
        title={confirmMode ? 'Enable simulation mode?' : 'Disable simulation mode?'}
        message={
          confirmMode
            ? 'Scenarios can then generate sample telemetry, anomalies, alerts, incidents and deployments flagged SIMULATION. Every signed-in user sees the simulation banner while simulated data exists. The change is audited.'
            : 'Every running scenario is stopped. Simulated records already stored are kept until you delete them. The change is audited.'
        }
        confirmLabel={confirmMode ? 'Enable' : 'Disable'}
        tone={confirmMode ? 'gold' : 'danger'}
        pending={setMode.isPending}
        error={setMode.isError ? errorMessage(setMode.error) : null}
        onConfirm={() =>
          setMode.mutate(Boolean(confirmMode), {
            onSuccess: (next) => {
              toast.success(next.settingEnabled ? 'Simulation mode enabled' : 'Simulation mode disabled');
              setConfirmMode(null);
            },
          })
        }
      />
      {purging && <PurgeDialog total={total} onClose={() => setPurging(false)} />}
    </div>
  );
}
