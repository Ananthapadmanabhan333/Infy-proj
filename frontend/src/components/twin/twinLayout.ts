import { COLUMN_GAP, layoutGraph, NODE_HEIGHT, NODE_WIDTH, PADDING, ROW_GAP } from '../cmdb/graphLayout';
import type { GraphEdge, GraphNode } from '../../lib/opsTypes';
import type { TwinEdge, TwinLayer, TwinNode } from '../../lib/r4Types';

/**
 * Layered layout for the digital twin, built on the CMDB layout (`graphLayout.ts`): the deterministic dependency
 * layering decides the order inside each twin layer, and the rows are then grouped into three horizontal bands —
 * business services on top, applications in the middle, infrastructure at the bottom (`TwinNode.layer` from the API).
 * Within a band, dependents stay above their dependencies. Same input → same coordinates.
 */

export const TWIN_LAYERS: TwinLayer[] = ['BUSINESS', 'APPLICATION', 'INFRASTRUCTURE'];
export const TWIN_LAYER_LABELS: Record<TwinLayer, string> = { BUSINESS: 'Business services', APPLICATION: 'Applications', INFRASTRUCTURE: 'Infrastructure' };
const BAND_LABEL = 22;
const BAND_GAP = 26;

export interface TwinLayoutNode {
  id: number;
  node: TwinNode;
  band: number;
  row: number;
  x: number;
  y: number;
}

export interface TwinLayoutEdge {
  id: number;
  edge: TwinEdge;
  dependent: number;
  dependency: number;
  /** Points sideways or upwards (cycle or a dependency in a higher band). */
  back: boolean;
  path: string;
}

export interface TwinBand {
  layer: TwinLayer;
  label: string;
  y: number;
  height: number;
  count: number;
}

export interface TwinLayout {
  nodes: TwinLayoutNode[];
  edges: TwinLayoutEdge[];
  bands: TwinBand[];
  width: number;
  height: number;
}

function bandOf(layer: TwinLayer | string): number {
  const index = TWIN_LAYERS.indexOf(layer as TwinLayer);
  return index < 0 ? TWIN_LAYERS.length - 1 : index;
}

export function layoutTwin(nodes: TwinNode[], edges: TwinEdge[]): TwinLayout {
  // Reuse the CMDB layering: every twin edge is already "dependent needs dependency".
  const graphNodes: GraphNode[] = nodes.map((node) => ({
    id: node.id,
    name: node.name,
    ciType: node.ciType,
    status: 'ACTIVE',
    environment: null,
    criticality: null,
    serviceId: node.serviceId,
    openAlerts: 0,
    depth: node.depth,
  }));
  const graphEdges: GraphEdge[] = edges.map((edge) => ({ id: edge.id, source: edge.dependentId, target: edge.dependencyId, type: 'DEPENDS_ON', origin: 'MANUAL' }));
  const base = layoutGraph(graphNodes, graphEdges);
  const byId = new Map(nodes.map((node) => [node.id, node]));

  // Rows: (band, dependency layer) pairs in order; empty bands are skipped.
  const rowKeys = new Map<string, { band: number; layer: number; ids: { id: number; x: number }[] }>();
  base.nodes.forEach((placed) => {
    const node = byId.get(placed.id)!;
    const band = bandOf(node.layer);
    const key = `${band}:${placed.layer}`;
    const row = rowKeys.get(key) ?? { band, layer: placed.layer, ids: [] };
    row.ids.push({ id: placed.id, x: placed.x });
    rowKeys.set(key, row);
  });
  const rows = [...rowKeys.values()].sort((a, b) => a.band - b.band || a.layer - b.layer);
  rows.forEach((row) => row.ids.sort((a, b) => a.x - b.x || a.id - b.id));

  const widest = rows.reduce((max, row) => Math.max(max, row.ids.length), 0);
  const width = widest ? PADDING * 2 + widest * NODE_WIDTH + (widest - 1) * COLUMN_GAP : PADDING * 2;

  const placed = new Map<number, TwinLayoutNode>();
  const bands: TwinBand[] = [];
  let y = PADDING;
  let rowIndex = 0;
  TWIN_LAYERS.forEach((layer, band) => {
    const bandRows = rows.filter((row) => row.band === band);
    if (!bandRows.length) return;
    const top = y;
    y += BAND_LABEL;
    bandRows.forEach((row, index) => {
      const rowWidth = row.ids.length * NODE_WIDTH + (row.ids.length - 1) * COLUMN_GAP;
      const offset = (width - rowWidth) / 2;
      row.ids.forEach(({ id }, order) => {
        placed.set(id, { id, node: byId.get(id)!, band, row: rowIndex, x: Math.round(offset + order * (NODE_WIDTH + COLUMN_GAP)), y });
      });
      rowIndex += 1;
      y += NODE_HEIGHT + (index < bandRows.length - 1 ? ROW_GAP : 0);
    });
    y += 14;
    bands.push({ layer, label: TWIN_LAYER_LABELS[layer], y: top, height: y - top, count: bandRows.reduce((sum, row) => sum + row.ids.length, 0) });
    y += BAND_GAP;
  });
  const height = bands.length ? y - BAND_GAP + PADDING : PADDING * 2;

  const layoutEdges: TwinLayoutEdge[] = edges
    .filter((edge) => placed.has(edge.dependentId) && placed.has(edge.dependencyId) && edge.dependentId !== edge.dependencyId)
    .sort((a, b) => a.id - b.id)
    .map((edge) => {
      const from = placed.get(edge.dependentId)!;
      const to = placed.get(edge.dependencyId)!;
      const downward = to.row > from.row;
      return { id: edge.id, edge, dependent: edge.dependentId, dependency: edge.dependencyId, back: !downward, path: edgePath(from, to, downward) };
    });

  return { nodes: base.nodes.map((node) => placed.get(node.id)!).filter(Boolean), edges: layoutEdges, bands, width, height };
}

function edgePath(from: TwinLayoutNode, to: TwinLayoutNode, downward: boolean): string {
  const x1 = from.x + NODE_WIDTH / 2;
  const x2 = to.x + NODE_WIDTH / 2;
  if (downward) {
    const y1 = from.y + NODE_HEIGHT;
    const y2 = to.y;
    const bend = (y2 - y1) / 2;
    return `M${x1},${y1} C${x1},${y1 + bend} ${x2},${y2 - bend} ${x2},${y2}`;
  }
  const y1 = from.y + NODE_HEIGHT;
  const y2 = to.y + NODE_HEIGHT;
  const drop = Math.max(y1, y2) + 30;
  return `M${x1},${y1} C${x1},${drop} ${x2},${drop} ${x2},${y2}`;
}

/** Edges on the impact paths of a simulation (consecutive ids of each `pathIds`, either direction). */
export function impactPathEdges(pathIds: number[][], edges: Pick<TwinEdge, 'id' | 'dependentId' | 'dependencyId'>[]): Set<number> {
  const pairs = new Set<string>();
  pathIds.forEach((path) => {
    for (let i = 1; i < path.length; i += 1) {
      pairs.add(`${path[i - 1]}>${path[i]}`);
      pairs.add(`${path[i]}>${path[i - 1]}`);
    }
  });
  return new Set(edges.filter((edge) => pairs.has(`${edge.dependentId}>${edge.dependencyId}`)).map((edge) => edge.id));
}
