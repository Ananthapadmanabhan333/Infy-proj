import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { impactPathEdges, layoutTwin } from '../../components/twin/twinLayout';
import { NODE_HEIGHT } from '../../components/cmdb/graphLayout';
import { pageOf } from '../../test/itsmFixtures';
import { json, mockApi, requests, type MockHandler, type MockReply } from '../../test/mockApi';
import { makeSimulationResult, makeTwinGraph } from '../../test/r4Fixtures';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import DigitalTwinPage from './DigitalTwinPage';
import { referenceLink } from './TwinTimeline';
import { SIMULATION_LABEL, simulationHighlight } from './TwinSimulation';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

function routes(extra: Record<string, MockHandler | MockReply> = {}) {
  mockApi({
    'GET /twin/graph': json(makeTwinGraph()),
    'GET /twin/simulations': json([{ id: 40, label: 'Simulation', type: 'FAILURE', mode: 'HARD', name: 'Earlier drill', impactedCiCount: 3, impactedServiceCount: 1, createdByName: 'Grace Hopper', createdAt: '2026-11-03T10:00:00Z' }]),
    'GET /twin/simulations/40': json(makeSimulationResult({ id: 40, name: 'Earlier drill' })),
    'POST /twin/simulations': json(makeSimulationResult(), 201),
    'GET /cmdb/cis': json(pageOf([{ id: 9, name: 'payroll-db-primary', ciType: 'DATABASE', status: 'ACTIVE', environment: 'PRODUCTION' }])),
    'GET /services': json([{ id: 1, code: 'PAY', name: 'Payroll', tier: 1 }]),
    ...extra,
  });
}

describe('twin layout', () => {
  it('stacks business services above applications above infrastructure, deterministically', () => {
    const graph = makeTwinGraph();
    const layout = layoutTwin(graph.nodes, graph.edges);
    expect(layout.bands.map((band) => [band.layer, band.count])).toEqual([
      ['BUSINESS', 1],
      ['APPLICATION', 1],
      ['INFRASTRUCTURE', 3],
    ]);
    const y = (id: number) => layout.nodes.find((node) => node.id === id)!.y;
    expect(y(1)).toBeLessThan(y(2));
    expect(y(2)).toBeLessThan(y(3));
    expect(y(3)).toBe(y(9));
    // Every node sits inside its band.
    layout.nodes.forEach((node) => {
      const band = layout.bands[node.band];
      expect(node.y).toBeGreaterThanOrEqual(band.y);
      expect(node.y + NODE_HEIGHT).toBeLessThanOrEqual(band.y + band.height);
    });
    expect(layout.edges.every((edge) => !edge.back)).toBe(true);
    expect(layoutTwin(graph.nodes, graph.edges)).toEqual(layout);
    // A dependency in a higher band (infrastructure needing an application) is drawn as a back edge.
    const odd = layoutTwin(graph.nodes, [...graph.edges, { id: 99, source: 9, target: 2, type: 'DEPENDS_ON', dependentId: 9, dependencyId: 2, redundant: false, attributes: null }]);
    expect(odd.edges.find((edge) => edge.id === 99)?.back).toBe(true);
  });

  it('keeps an empty layer out of the bands and handles an empty twin', () => {
    const graph = makeTwinGraph();
    const infraOnly = graph.nodes.filter((node) => node.layer === 'INFRASTRUCTURE');
    expect(layoutTwin(infraOnly, []).bands.map((band) => band.layer)).toEqual(['INFRASTRUCTURE']);
    expect(layoutTwin([], [])).toMatchObject({ nodes: [], edges: [], bands: [] });
  });

  it('marks the edges on simulated impact paths', () => {
    const graph = makeTwinGraph();
    expect([...impactPathEdges([[9, 2, 1]], graph.edges)].sort()).toEqual([11, 14]);
    const highlight = simulationHighlight(makeSimulationResult(), graph.edges)!;
    expect(highlight.targets).toEqual(new Set([9]));
    expect(highlight.impacted.get(1)).toBe('DOWN');
  });

  it('links timeline entries to their records', () => {
    expect(referenceLink({ kind: 'CHANGE', reference: { type: 'CHANGE', id: 5, number: 'CHG-5' } } as never)).toBe('/changes/5');
    expect(referenceLink({ kind: 'DEPLOYMENT', reference: { type: 'DEPLOYMENT', id: 8 } } as never)).toBe('/deployments/8');
    expect(referenceLink({ kind: 'CMDB', ciId: 3, reference: null } as never)).toBe('/cmdb/3');
    expect(referenceLink({ kind: 'TELEMETRY', ciId: null, reference: null } as never)).toBeNull();
  });
});

describe('DigitalTwinPage', () => {
  it('renders the layered twin with state overlays and a node detail panel', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<DigitalTwinPage />, { route: '/digital-twin', auth: makeAuth(makeUser('AUDITOR')) });
    const map = await screen.findByRole('group', { name: /Digital twin: 5 nodes, 4 relationships/ });
    expect(last('GET', '/twin/graph')?.params).toMatchObject({ layer: 'ALL', includeState: true, includeSimulated: false });
    const db = within(map).getByRole('button', { name: /payroll-db-primary, Database, outage, 1 incidents, 1 alerts, 1 anomalies/ });
    expect(db).toHaveAttribute('data-health', 'OUTAGE');
    expect(map.querySelectorAll('[data-redundant="true"]')).toHaveLength(2);
    await user.click(db);
    const detail = screen.getByRole('region', { name: 'payroll-db-primary' });
    expect(within(detail).getByText('1 critical alert firing')).toBeInTheDocument();
    expect(within(detail).getByRole('link', { name: /Open CI/ })).toHaveAttribute('href', '/cmdb/9');
    // Auditors read; only twin editors toggle redundancy.
    expect(within(detail).queryByRole('button', { name: /redundancy/ })).not.toBeInTheDocument();
  });

  it('lets twin editors toggle redundancy after confirmation', async () => {
    routes({ 'PUT /twin/relationships/12': json({ ...makeTwinGraph().edges[1], redundant: false }) });
    const user = userEvent.setup();
    renderWithProviders(<DigitalTwinPage />, { route: '/digital-twin', auth: makeAuth(makeUser('ASSET_MANAGER')) });
    const map = await screen.findByRole('group', { name: /Digital twin/ });
    await user.click(within(map).getByRole('button', { name: /^Payroll Application/ }));
    await user.click(screen.getByRole('button', { name: 'Clear redundancy of the relationship with payroll-app-01' }));
    const dialog = screen.getByRole('dialog', { name: 'Mark the relationship as not redundant?' });
    await user.type(within(dialog).getByLabelText(/Note/), 'Second node decommissioned');
    await user.click(within(dialog).getByRole('button', { name: 'Mark not redundant' }));
    await waitFor(() => expect(last('PUT', '/twin/relationships/12')?.body).toEqual({ redundant: false, note: 'Second node decommissioned' }));
  });

  it('runs a failure simulation and labels every result as a simulation', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<DigitalTwinPage />, { route: '/digital-twin?tab=simulate', auth: makeAuth(makeUser('FINOPS_ANALYST')) });
    expect(await screen.findByRole('list', { name: 'Simulation history' })).toHaveTextContent('Earlier drill');

    await user.click(screen.getByRole('button', { name: /Run simulation/ }));
    expect(screen.getByRole('alert')).toHaveTextContent('Pick at least one configuration item to fail');
    expect(last('POST', '/twin/simulations')).toBeUndefined();

    await user.type(screen.getByLabelText('Configuration items to fail'), 'payroll-db');
    await user.click(await screen.findByRole('option', { name: /payroll-db-primary/ }));
    await user.type(screen.getByLabelText('Name (optional)'), 'DB outage drill');
    await user.click(screen.getByRole('button', { name: /Run simulation/ }));
    await waitFor(() => expect(last('POST', '/twin/simulations')?.body).toEqual({ type: 'FAILURE', mode: 'HARD', targets: [9], name: 'DB outage drill' }));

    const result = await screen.findByLabelText('Simulation result');
    expect(within(result).getAllByText(new RegExp(SIMULATION_LABEL)).length).toBeGreaterThan(0);
    expect(within(result).getByRole('list', { name: 'Impacted services' })).toHaveTextContent('Payroll');
    expect(within(result).getByText('1,800')).toBeInTheDocument();
    expect(within(result).getByRole('list', { name: 'SLAs at risk' })).toHaveTextContent('INC-7846');
    expect(within(result).getByText('No scheduled changes touch the impact')).toBeInTheDocument();

    // The impact is drawn on the twin: target, DOWN nodes and path edges.
    const map = await screen.findByRole('group', { name: /2 impacted by the simulation/ });
    expect(within(map).getByRole('button', { name: /payroll-db-primary.*simulation target/ })).toHaveAttribute('data-impact', 'TARGET');
    expect(within(map).getByRole('button', { name: /^Payroll,.*simulated impact down/ })).toHaveAttribute('data-impact', 'DOWN');
    expect(map.querySelector('[data-edge="14"]')).toHaveAttribute('stroke', '#E56D68');
  });

  it('opens an earlier simulation from the history', async () => {
    routes();
    const user = userEvent.setup();
    renderWithProviders(<DigitalTwinPage />, { route: '/digital-twin?tab=simulate' });
    await user.click(await screen.findByRole('button', { name: /#40 Earlier drill/ }));
    expect(await screen.findByLabelText('Simulation result')).toHaveTextContent('“Earlier drill”');
  });

  it('shows the change timeline with source badges and diffs', async () => {
    routes({
      'GET /twin/changes': json({
        ciId: null,
        serviceId: null,
        from: '2026-10-05T00:00:00Z',
        to: '2026-11-04T00:00:00Z',
        includeSimulated: false,
        truncated: false,
        entries: [
          { at: '2026-11-03T10:00:00Z', kind: 'DEPLOYMENT', title: 'HR-PORTAL 5.3.0 to PRODUCTION', actorName: 'ci-bot', ciId: null, ciName: null, serviceId: 2, reference: { type: 'DEPLOYMENT', id: 81, number: 'DEP-81' }, diff: [{ field: 'version', from: '5.2.0', to: '5.3.0' }], simulated: true },
          { at: '2026-11-02T10:00:00Z', kind: 'CMDB', title: 'Attribute changed', actorName: 'Ada Lovelace', ciId: 9, ciName: 'payroll-db-primary', serviceId: null, reference: null, diff: [{ field: 'max_connections', from: '40', to: '50' }], simulated: false },
        ],
      }),
    });
    const user = userEvent.setup();
    renderWithProviders(<DigitalTwinPage />, { route: '/digital-twin?tab=changes' });
    const timeline = await screen.findByRole('list', { name: 'Change timeline' });
    expect(within(timeline).getByRole('link', { name: 'HR-PORTAL 5.3.0 to PRODUCTION' })).toHaveAttribute('href', '/deployments/81');
    expect(within(timeline).getByText('SIMULATION')).toBeInTheDocument();
    expect(within(timeline).getByRole('list', { name: 'Diff of Attribute changed' })).toHaveTextContent('max_connections40 → 50');
    await user.selectOptions(screen.getByLabelText('Source'), 'CMDB');
    expect(within(timeline).queryByText('HR-PORTAL 5.3.0 to PRODUCTION')).not.toBeInTheDocument();
    await user.click(screen.getByRole('switch', { name: 'Include simulated data' }));
    await waitFor(() => expect(last('GET', '/twin/changes')?.params).toMatchObject({ includeSimulated: true, limit: 200 }));
  });
});
