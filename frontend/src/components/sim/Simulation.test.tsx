import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { simulationActive } from '../../api/simulation';
import { PURGE_CONFIRMATION, SimulationTab } from '../../pages/settings/SimulationTab';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeSimulationStatus } from '../../test/r4Fixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import { SimulatedBadge } from '../Badge';
import { IncludeSimulatedToggle, SimulationBanner } from './SimulationParts';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

describe('SimulatedBadge', () => {
  it('marks simulated records only', () => {
    const { container, rerender } = render(<SimulatedBadge simulated />);
    expect(screen.getByText('SIMULATION')).toHaveClass('badge', 'simulated');
    rerender(<SimulatedBadge simulated={false} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<SimulatedBadge simulated={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('simulationActive', () => {
  it('needs the mode on and a running scenario or stored simulated rows', () => {
    expect(simulationActive(undefined)).toBe(false);
    expect(simulationActive(makeSimulationStatus({ enabled: false }))).toBe(false);
    expect(simulationActive(makeSimulationStatus())).toBe(true);
    expect(simulationActive(makeSimulationStatus({ runningRuns: [], simulatedRecords: { alerts: 0 } }))).toBe(false);
    expect(simulationActive(makeSimulationStatus({ runningRuns: [], simulatedRecords: { alerts: 2 } }))).toBe(true);
  });
});

describe('SimulationBanner', () => {
  it('tells every signed-in user that SIMULATION figures are sample data', async () => {
    mockApi({ 'GET /simulation/status': json(makeSimulationStatus()) });
    renderWithProviders(<SimulationBanner />, { auth: makeAuth(makeUser('EMPLOYEE')) });
    const banner = await screen.findByRole('status', { name: 'Simulation mode' });
    expect(banner).toHaveTextContent('Simulation mode — figures marked SIMULATION are sample data');
    expect(banner).toHaveTextContent('1 scenario running (PAYROLL_DB_SATURATION)');
    expect(banner).toHaveTextContent('28 simulated records stored');
    // Only simulation admins get the management link.
    expect(within(banner).queryByRole('link')).not.toBeInTheDocument();
  });

  it('links system admins to the settings tab', async () => {
    mockApi({ 'GET /simulation/status': json(makeSimulationStatus()) });
    renderWithProviders(<SimulationBanner />, { auth: makeAuth(makeUser('SYSTEM_ADMIN')) });
    expect(await screen.findByRole('link', { name: 'Manage simulation' })).toHaveAttribute('href', '/settings?tab=simulation');
  });

  it('stays hidden while the mode is off, and when the status call fails', async () => {
    mockApi({ 'GET /simulation/status': json(makeSimulationStatus({ enabled: false })) });
    renderWithProviders(<SimulationBanner />);
    await waitFor(() => expect(last('GET', '/simulation/status')).toBeDefined());
    expect(screen.queryByRole('status', { name: 'Simulation mode' })).not.toBeInTheDocument();
  });

  it('does not poll for signed-out visitors', () => {
    renderWithProviders(<SimulationBanner />, { auth: makeAuth(null) });
    expect(last('GET', '/simulation/status')).toBeUndefined();
  });
});

describe('IncludeSimulatedToggle', () => {
  it('is a labelled switch, off by default', async () => {
    const user = userEvent.setup();
    let value = false;
    const { rerender } = render(<IncludeSimulatedToggle value={value} onChange={(next) => (value = next)} />);
    const toggle = screen.getByRole('switch', { name: 'Include simulated data' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    await user.click(toggle);
    expect(value).toBe(true);
    rerender(<IncludeSimulatedToggle value onChange={() => undefined} />);
    expect(screen.getByRole('switch', { name: 'Include simulated data' })).toHaveAttribute('aria-checked', 'true');
  });
});

describe('Settings → Simulation mode', () => {
  it('explains that the server disallows simulation and offers no switch', async () => {
    mockApi({
      'GET /simulation/status': json(makeSimulationStatus({ enabled: false, allowed: false, settingEnabled: false, runningRuns: [], simulatedRecords: {}, scenarios: makeSimulationStatus().scenarios.map((s) => ({ ...s, running: false })) })),
      'GET /simulation/runs': json([]),
    });
    renderWithProviders(<SimulationTab />);
    expect(await screen.findByText(/The server does not allow simulation mode/)).toBeInTheDocument();
    expect(screen.getAllByText('SIMULATION_ALLOWED=false').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /Enable simulation mode/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Start / })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Delete simulated data/ })).not.toBeInTheDocument();
  });

  it('enables the mode only after confirmation', async () => {
    const off = makeSimulationStatus({ enabled: false, settingEnabled: false, runningRuns: [], simulatedRecords: {}, scenarios: makeSimulationStatus().scenarios.map((s) => ({ ...s, running: false })) });
    mockApi({
      'GET /simulation/status': json(off),
      'GET /simulation/runs': json([]),
      'PUT /simulation/mode': json(makeSimulationStatus({ runningRuns: [] })),
    });
    const user = userEvent.setup();
    renderWithProviders(<SimulationTab />);
    await user.click(await screen.findByRole('button', { name: /Enable simulation mode/ }));
    const dialog = screen.getByRole('dialog', { name: 'Enable simulation mode?' });
    expect(last('PUT', '/simulation/mode')).toBeUndefined();
    await user.click(within(dialog).getByRole('button', { name: 'Enable' }));
    await waitFor(() => expect(last('PUT', '/simulation/mode')?.body).toEqual({ enabled: true }));
  });

  it('lists scenarios, runs and records; starts and stops scenarios', async () => {
    mockApi({
      'GET /simulation/status': json(makeSimulationStatus()),
      'GET /simulation/runs': json(makeSimulationStatus().runningRuns),
      'POST /simulation/scenarios/BASELINE/start': json({ ...makeSimulationStatus().runningRuns[0], id: 13, scenario: 'BASELINE' }),
      'POST /simulation/scenarios/PAYROLL_DB_SATURATION/stop': json({ ...makeSimulationStatus().runningRuns[0], status: 'STOPPED' }),
    });
    const user = userEvent.setup();
    renderWithProviders(<SimulationTab />);
    const scenarios = await screen.findByRole('list', { name: 'Simulation scenarios' });
    expect(within(scenarios).getByText('Payroll DB connection pool saturation')).toBeInTheDocument();
    const records = screen.getByRole('list', { name: 'Simulated records per table' });
    expect(within(records).getByText('telemetry_metric_series')).toBeInTheDocument();
    expect(within(records).getByText('24')).toBeInTheDocument();
    expect(await screen.findByRole('list', { name: 'Scenario runs' })).toHaveTextContent('1,200 metric points');

    await user.click(screen.getByRole('button', { name: 'Start Baseline telemetry' }));
    await waitFor(() => expect(last('POST', '/simulation/scenarios/BASELINE/start')).toBeDefined());
    await user.click(screen.getByRole('button', { name: 'Stop Payroll DB connection pool saturation' }));
    await waitFor(() => expect(last('POST', '/simulation/scenarios/PAYROLL_DB_SATURATION/stop')).toBeDefined());
  });

  it('deletes simulated data only after the exact confirmation text is typed', async () => {
    mockApi({
      'GET /simulation/status': json(makeSimulationStatus()),
      'GET /simulation/runs': json([]),
      'DELETE /simulation/data': json({ alerts: 3, incidents: 1, telemetry_metric_series: 24 }),
    });
    const user = userEvent.setup();
    renderWithProviders(<SimulationTab />);
    await user.click(await screen.findByRole('button', { name: /Delete simulated data/ }));
    const dialog = screen.getByRole('dialog', { name: 'Delete simulated data' });
    const confirm = within(dialog).getByRole('button', { name: 'Delete simulated data' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Confirmation'), 'delete');
    expect(confirm).toBeDisabled();
    await user.clear(within(dialog).getByLabelText('Confirmation'));
    await user.type(within(dialog).getByLabelText('Confirmation'), PURGE_CONFIRMATION);
    await user.click(confirm);
    await waitFor(() => expect(last('DELETE', '/simulation/data')).toBeDefined());
    expect(await screen.findByText('Deleted 28 simulated records')).toBeInTheDocument();
  });
});
