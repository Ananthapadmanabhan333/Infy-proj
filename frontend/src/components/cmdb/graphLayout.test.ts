import { describe, expect, it } from 'vitest';
import type { GraphEdge, GraphNode } from '../../lib/opsTypes';
import { PAYROLL_GRAPH } from '../../test/opsFixtures';
import { COLUMN_GAP, highlightFor, layoutGraph, needsDirection, NODE_HEIGHT, NODE_WIDTH, PADDING, ROW_GAP } from './graphLayout';

function node(id: number, name: string, ciType: GraphNode['ciType'] = 'SERVER'): GraphNode {
  return { id, name, ciType, status: 'ACTIVE', environment: 'PRODUCTION', criticality: null, serviceId: null, openAlerts: 0, depth: 0 };
}

function edge(id: number, source: number, target: number, type: GraphEdge['type'] = 'DEPENDS_ON'): GraphEdge {
  return { id, source, target, type, origin: 'MANUAL' };
}

const layerOf = (layout: ReturnType<typeof layoutGraph>, id: number) => layout.nodes.find((entry) => entry.id === id)!.layer;

describe('needsDirection', () => {
  it('normalises every relationship type to dependent → dependency', () => {
    expect(needsDirection('DEPENDS_ON', 1, 2)).toEqual({ dependent: 1, dependency: 2 });
    expect(needsDirection('RUNS_ON', 1, 2)).toEqual({ dependent: 1, dependency: 2 });
    expect(needsDirection('CONNECTS_TO', 1, 2)).toEqual({ dependent: 1, dependency: 2 });
    // HOSTS: the target runs on the source; PART_OF: the whole (target) depends on its part (source).
    expect(needsDirection('HOSTS', 1, 2)).toEqual({ dependent: 2, dependency: 1 });
    expect(needsDirection('PART_OF', 1, 2)).toEqual({ dependent: 2, dependency: 1 });
  });
});

describe('layoutGraph', () => {
  it('puts dependents above what they need (business service on top) with longest-path layers', () => {
    const layout = layoutGraph(PAYROLL_GRAPH.nodes, PAYROLL_GRAPH.edges);
    expect(layout.layers).toBe(3);
    expect(layerOf(layout, 1)).toBe(0); // Payroll (business service)
    expect(layerOf(layout, 11)).toBe(1); // Payroll Application
    expect(layerOf(layout, 21)).toBe(2);
    expect(layerOf(layout, 31)).toBe(2);
    const app = layout.nodes.find((entry) => entry.id === 11)!;
    expect(app.y).toBe(PADDING + 1 * (NODE_HEIGHT + ROW_GAP));
    // The widest layer (2 nodes) sets the width; single-node layers are centred.
    expect(layout.width).toBe(PADDING * 2 + 2 * NODE_WIDTH + COLUMN_GAP);
    expect(app.x).toBe(Math.round((layout.width - NODE_WIDTH) / 2));
    expect(layout.edges.every((entry) => !entry.back)).toBe(true);
    expect(layout.edges.find((entry) => entry.id === 101)!.path).toMatch(/^M\d+(\.\d+)?,\d+ C/);
  });

  it('honours HOSTS and PART_OF directions', () => {
    const nodes = [node(1, 'vm-host', 'SERVER'), node(2, 'erp-app', 'APPLICATION'), node(3, 'Database Platform', 'INFRASTRUCTURE'), node(4, 'db-01', 'DATABASE')];
    const edges = [edge(1, 1, 2, 'HOSTS'), edge(2, 4, 3, 'PART_OF')];
    const layout = layoutGraph(nodes, edges);
    expect(layerOf(layout, 2)).toBeLessThan(layerOf(layout, 1)); // erp-app runs on vm-host
    expect(layerOf(layout, 3)).toBeLessThan(layerOf(layout, 4)); // the platform needs its database
  });

  it('is deterministic regardless of the input order', () => {
    const a = layoutGraph(PAYROLL_GRAPH.nodes, PAYROLL_GRAPH.edges);
    const b = layoutGraph([...PAYROLL_GRAPH.nodes].reverse(), [...PAYROLL_GRAPH.edges].reverse());
    const position = (layout: typeof a) => layout.nodes.map(({ id, x, y }) => ({ id, x, y })).sort((p, q) => p.id - q.id);
    expect(position(b)).toEqual(position(a));
    expect(b.edges.map((entry) => entry.path).sort()).toEqual(a.edges.map((entry) => entry.path).sort());
  });

  it('terminates on cycles, marks the closing edge as a back edge and still draws it', () => {
    const nodes = [node(1, 'a'), node(2, 'b'), node(3, 'c')];
    const edges = [edge(1, 1, 2), edge(2, 2, 3), edge(3, 3, 1)];
    const layout = layoutGraph(nodes, edges);
    expect(layout.nodes).toHaveLength(3);
    expect(layout.edges).toHaveLength(3);
    expect(layout.edges.filter((entry) => entry.back)).toHaveLength(1);
    expect(new Set(layout.nodes.map((entry) => entry.layer))).toEqual(new Set([0, 1, 2]));
  });

  it('drops edges to unknown nodes and self-loops; handles an empty graph', () => {
    const layout = layoutGraph([node(1, 'a')], [edge(1, 1, 99), edge(2, 1, 1)]);
    expect(layout.edges).toHaveLength(0);
    expect(layout.layers).toBe(1);
    expect(layoutGraph([], []).nodes).toEqual([]);
  });

  it('orders siblings to reduce crossings (barycentre)', () => {
    // Two parents, each with its own child; children must keep their parents' order.
    const nodes = [node(1, 'A-app', 'APPLICATION'), node(2, 'B-app', 'APPLICATION'), node(3, 'z-server'), node(4, 'a-server')];
    const edges = [edge(1, 1, 3), edge(2, 2, 4)];
    const layout = layoutGraph(nodes, edges);
    const x = (id: number) => layout.nodes.find((entry) => entry.id === id)!.x;
    expect(x(1)).toBeLessThan(x(2));
    expect(x(3)).toBeLessThan(x(4));
  });
});

describe('highlightFor', () => {
  it('collects upstream (what it needs) and downstream (what needs it) transitively, cycle-safe', () => {
    const layout = layoutGraph(PAYROLL_GRAPH.nodes, PAYROLL_GRAPH.edges);
    const app = highlightFor(11, layout.edges);
    expect([...app.upstream].sort()).toEqual([21, 31]);
    expect([...app.downstream]).toEqual([1]);
    expect([...app.edges].sort()).toEqual([101, 102, 103]);

    const server = highlightFor(21, layout.edges);
    expect([...server.upstream]).toEqual([]);
    expect([...server.downstream].sort()).toEqual([1, 11]);
    expect(server.edges.has(103)).toBe(false);

    const cyclic = highlightFor(1, [
      { id: 1, dependent: 1, dependency: 2 },
      { id: 2, dependent: 2, dependency: 1 },
    ]);
    expect([...cyclic.upstream]).toEqual([2]);
    expect([...cyclic.downstream]).toEqual([2]);
  });
});
