import { useId } from 'react';
import { Link } from 'react-router-dom';
import { FlaskConical } from 'lucide-react';
import { simulatedRecordTotal, simulationActive, useSimulationStatus } from '../../api/simulation';
import { useAuth } from '../../hooks/useAuth';
import { SIMULATION_ADMINS } from '../../lib/constants';
import { formatNumber } from '../../lib/format';
import './sim.css';

export { SimulatedBadge } from '../Badge';

/**
 * Global banner (RELEASE4_SPEC §3): shown while simulation mode is on and a scenario runs or simulated rows are stored.
 * Reads `GET /simulation/status` (every signed-in user), polled every 60 s; nothing is rendered when the call fails.
 */
export function SimulationBanner() {
  const { user, hasRole } = useAuth();
  const status = useSimulationStatus(Boolean(user && !user.mustChangePassword));
  const data = status.data;
  if (!simulationActive(data)) return null;
  const running = data?.runningRuns ?? [];
  const total = simulatedRecordTotal(data);
  return (
    <div className="sim-banner" role="status" aria-label="Simulation mode">
      <FlaskConical size={15} aria-hidden="true" />
      <span>
        <b>Simulation mode</b> — figures marked SIMULATION are sample data
      </span>
      <span className="sim-banner-meta">
        {running.length ? `${running.length} scenario${running.length === 1 ? '' : 's'} running (${running.map((run) => run.scenario).join(', ')})` : 'No scenario running'}
        {' · '}
        {formatNumber(total)} simulated record{total === 1 ? '' : 's'} stored
      </span>
      {hasRole(...SIMULATION_ADMINS) && <Link to="/settings?tab=simulation">Manage simulation</Link>}
    </div>
  );
}

/** "Include simulated data" switch for Release 4 list/query views (default off → `includeSimulated=false`). */
export function IncludeSimulatedToggle({ value, onChange, className = '' }: { value: boolean; onChange: (next: boolean) => void; className?: string }) {
  const id = useId();
  return (
    <span className={`sim-toggle ${className}`.trim()} data-on={value}>
      <button type="button" role="switch" id={id} aria-checked={value} className="toggle" onClick={() => onChange(!value)} aria-label="Include simulated data" />
      <label htmlFor={id}>Include simulated data</label>
    </span>
  );
}

/** Label for every response whose figures include simulated data. */
export function SimulatedDataNotice({ show, text = 'These figures include simulated sample data.' }: { show: boolean | null | undefined; text?: string }) {
  if (!show) return null;
  return (
    <p className="sim-label m-0" role="note">
      <FlaskConical size={13} aria-hidden="true" /> {text}
    </p>
  );
}
