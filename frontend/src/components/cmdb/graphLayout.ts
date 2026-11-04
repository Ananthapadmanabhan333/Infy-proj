import type { CiType, GraphEdge, GraphNode, RelationshipType } from '../../lib/opsTypes';

/**
 * Deterministic layered layout for the CMDB dependency map (no force simulation, no dependency).
 *
 * Every stored relationship is normalised to a "needs" edge — dependent → dependency (docs/API.md "CMDB"):
 * `DEPENDS_ON`, `RUNS_ON`, `CONNECTS_TO`: the source needs the target; `HOSTS`: the target runs on the source;
 * `PART_OF`: the whole (target) depends on its part (source). Dependents are drawn above their dependencies, so
 * business services sit on top and infrastructure at the bottom. Cycles (allowed in the data) are broken by a
 * depth-first search in a fixed order; the back edges are still drawn (as loops) but ignored for layering.
 * The same input always gives the same coordinates.
 */

export const NODE_WIDTH = 172;
export const NODE_HEIGHT = 48;
export const COLUMN_GAP = 26;
export const ROW_GAP = 70;
export const PADDING = 24;

export interface LayoutNode {
  id: number;
  node: GraphNode;
  layer: number;
  order: number;
  /** Top-left corner. */
  x: number;
  y: number;
}

export interface LayoutEdge {
  id: number;
  edge: GraphEdge;
  /** The CI that needs the other one. */
  dependent: number;
  dependency: number;
  /** Ignored for layering because it closes a cycle (or points sideways / upwards). */
  back: boolean;
  path: string;
}

export interface GraphLayout {
  nodes: LayoutNode[];
  edges: LayoutEdge[];
  width: number;
  height: number;
  layers: number;
}

/** dependent → dependency for a stored edge. */
export function needsDirection(type: RelationshipType, source: number, target: number): { dependent: number; dependency: number } {
  if (type === 'HOSTS' || type === 'PART_OF') return { dependent: target, dependency: source };
  return { dependent: source, dependency: target };
}

const TYPE_RANK: Record<CiType, number> = {
  BUSINESS_SERVICE: 0,
  APPLICATION: 1,
  SOFTWARE: 2,
  DATABASE: 3,
  CLOUD_RESOURCE: 4,
  SERVER: 5,
  OPERATING_SYSTEM: 6,
  NETWORK_DEVICE: 7,
  INFRASTRUCTURE: 8,
};

function compareNodes(a: GraphNode, b: GraphNode): number {
  return (TYPE_RANK[a.ciType] ?? 9) - (TYPE_RANK[b.ciType] ?? 9) || a.name.localeCompare(b.name) || a.id - b.id;
}

export function layoutGraph(nodes: GraphNode[], edges: GraphEdge[]): GraphLayout {
  const sorted = [...nodes].sort(compareNodes);
  const known = new Set(sorted.map((node) => node.id));
  const normalised = [...edges]
    .filter((edge) => known.has(edge.source) && known.has(edge.target) && edge.source !== edge.target)
    .sort((a, b) => a.id - b.id)
    .map((edge) => ({ edge, ...needsDirection(edge.type, edge.source, edge.target) }));

  // Adjacency (dependent → dependencies), in deterministic order.
  const rank = new Map(sorted.map((node, index) => [node.id, index]));
  const out = new Map<number, typeof normalised>();
  sorted.forEach((node) => out.set(node.id, []));
  normalised.forEach((entry) => out.get(entry.dependent)!.push(entry));
  out.forEach((list) => list.sort((a, b) => rank.get(a.dependency)! - rank.get(b.dependency)! || a.edge.id - b.edge.id));

  // 1. Break cycles: iterative DFS, an edge to a node on the stack is a back edge.
  const backEdges = new Set<number>();
  const state = new Map<number, 0 | 1 | 2>(); // 0 new, 1 on stack, 2 done
  for (const start of sorted) {
    if (state.get(start.id)) continue;
    const stack: { id: number; next: number }[] = [{ id: start.id, next: 0 }];
    state.set(start.id, 1);
    while (stack.length) {
      const top = stack[stack.length - 1];
      const list = out.get(top.id)!;
      if (top.next >= list.length) {
        state.set(top.id, 2);
        stack.pop();
        continue;
      }
      const entry = list[top.next++];
      const seen = state.get(entry.dependency) ?? 0;
      if (seen === 1) backEdges.add(entry.edge.id);
      else if (seen === 0) {
        state.set(entry.dependency, 1);
        stack.push({ id: entry.dependency, next: 0 });
      }
    }
  }
  const forward = normalised.filter((entry) => !backEdges.has(entry.edge.id));

  // 2. Longest-path layering over the acyclic edges (Kahn, ready nodes taken in sorted order).
  const indegree = new Map(sorted.map((node) => [node.id, 0]));
  forward.forEach((entry) => indegree.set(entry.dependency, indegree.get(entry.dependency)! + 1));
  const layer = new Map(sorted.map((node) => [node.id, 0]));
  const ready = sorted.filter((node) => indegree.get(node.id) === 0).map((node) => node.id);
  while (ready.length) {
    ready.sort((a, b) => rank.get(a)! - rank.get(b)!);
    const id = ready.shift()!;
    for (const entry of out.get(id)!) {
      if (backEdges.has(entry.edge.id)) continue;
      layer.set(entry.dependency, Math.max(layer.get(entry.dependency)!, layer.get(id)! + 1));
      indegree.set(entry.dependency, indegree.get(entry.dependency)! - 1);
      if (indegree.get(entry.dependency) === 0) ready.push(entry.dependency);
    }
  }

  // 3. Order within layers: start from the sorted order, then barycentre sweeps (down, up) with stable ties.
  const layerCount = sorted.length ? Math.max(...sorted.map((node) => layer.get(node.id)!)) + 1 : 0;
  const rows: number[][] = Array.from({ length: layerCount }, () => []);
  sorted.forEach((node) => rows[layer.get(node.id)!].push(node.id));
  const position = new Map<number, number>();
  const reindex = () => rows.forEach((row) => row.forEach((id, index) => position.set(id, index)));
  reindex();
  const parents = new Map<number, number[]>();
  const children = new Map<number, number[]>();
  sorted.forEach((node) => {
    parents.set(node.id, []);
    children.set(node.id, []);
  });
  forward.forEach((entry) => {
    parents.get(entry.dependency)!.push(entry.dependent);
    children.get(entry.dependent)!.push(entry.dependency);
  });
  const sweep = (row: number[], neighbours: Map<number, number[]>) => {
    const keyed = row.map((id, index) => {
      const linked = neighbours.get(id)!;
      const centre = linked.length ? linked.reduce((sum, other) => sum + position.get(other)!, 0) / linked.length : index;
      return { id, centre, index };
    });
    keyed.sort((a, b) => a.centre - b.centre || a.index - b.index);
    return keyed.map((entry) => entry.id);
  };
  for (let iteration = 0; iteration < 4; iteration += 1) {
    for (let i = 1; i < rows.length; i += 1) {
      rows[i] = sweep(rows[i], parents);
      reindex();
    }
    for (let i = rows.length - 2; i >= 0; i -= 1) {
      rows[i] = sweep(rows[i], children);
      reindex();
    }
  }

  // 4. Coordinates: each layer centred on the widest one.
  const widest = rows.reduce((max, row) => Math.max(max, row.length), 0);
  const width = widest ? PADDING * 2 + widest * NODE_WIDTH + (widest - 1) * COLUMN_GAP : PADDING * 2;
  const height = layerCount ? PADDING * 2 + layerCount * NODE_HEIGHT + (layerCount - 1) * ROW_GAP + 40 : PADDING * 2;
  const byId = new Map(sorted.map((node) => [node.id, node]));
  const placed = new Map<number, LayoutNode>();
  rows.forEach((row, layerIndex) => {
    const rowWidth = row.length * NODE_WIDTH + (row.length - 1) * COLUMN_GAP;
    const offset = (width - rowWidth) / 2;
    row.forEach((id, order) => {
      placed.set(id, {
        id,
        node: byId.get(id)!,
        layer: layerIndex,
        order,
        x: Math.round(offset + order * (NODE_WIDTH + COLUMN_GAP)),
        y: PADDING + layerIndex * (NODE_HEIGHT + ROW_GAP),
      });
    });
  });

  const layoutEdges: LayoutEdge[] = normalised.map((entry) => {
    const from = placed.get(entry.dependent)!;
    const to = placed.get(entry.dependency)!;
    const downward = to.layer > from.layer;
    return {
      id: entry.edge.id,
      edge: entry.edge,
      dependent: entry.dependent,
      dependency: entry.dependency,
      back: backEdges.has(entry.edge.id) || !downward,
      path: edgePath(from, to, downward),
    };
  });

  return { nodes: sorted.map((node) => placed.get(node.id)!), edges: layoutEdges, width, height, layers: layerCount };
}

function edgePath(from: LayoutNode, to: LayoutNode, downward: boolean): string {
  const x1 = from.x + NODE_WIDTH / 2;
  const x2 = to.x + NODE_WIDTH / 2;
  if (downward) {
    const y1 = from.y + NODE_HEIGHT;
    const y2 = to.y;
    const bend = (y2 - y1) / 2;
    return `M${x1},${y1} C${x1},${y1 + bend} ${x2},${y2 - bend} ${x2},${y2}`;
  }
  // Same layer or upwards (cycles): loop below both nodes.
  const y1 = from.y + NODE_HEIGHT;
  const y2 = to.y + NODE_HEIGHT;
  const drop = Math.max(y1, y2) + 34;
  return `M${x1},${y1} C${x1},${drop} ${x2},${drop} ${x2},${y2}`;
}

export interface Highlight {
  /** What the CI needs, transitively (possible causes). */
  upstream: Set<number>;
  /** What needs the CI, transitively (impacted when it fails). */
  downstream: Set<number>;
  /** Edges on those paths. */
  edges: Set<number>;
}

/** Breadth-first reachability from `id` over the "needs" edges (visited set, so cycles terminate). */
export function highlightFor(id: number, edges: Pick<LayoutEdge, 'id' | 'dependent' | 'dependency'>[]): Highlight {
  const walk = (forward: boolean) => {
    const seen = new Set<number>();
    const queue = [id];
    while (queue.length) {
      const current = queue.shift()!;
      for (const edge of edges) {
        const from = forward ? edge.dependent : edge.dependency;
        const to = forward ? edge.dependency : edge.dependent;
        if (from === current && to !== id && !seen.has(to)) {
          seen.add(to);
          queue.push(to);
        }
      }
    }
    return seen;
  };
  const upstream = walk(true);
  const downstream = walk(false);
  const onPath = new Set<number>();
  for (const edge of edges) {
    const upPath = (edge.dependent === id || upstream.has(edge.dependent)) && upstream.has(edge.dependency);
    const downPath = downstream.has(edge.dependent) && (edge.dependency === id || downstream.has(edge.dependency));
    if (upPath || downPath) onPath.add(edge.id);
  }
  return { upstream, downstream, edges: onPath };
}
