import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { Maximize2, Minus, Plus } from 'lucide-react';
import { PALETTE } from '../../lib/constants';
import { CI_TYPE_GLYPHS, CI_TYPE_LABELS } from '../../lib/ops';
import type { TwinGraph, TwinNode } from '../../lib/r4Types';
import { NODE_HEIGHT, NODE_WIDTH } from '../cmdb/graphLayout';
import '../cmdb/cmdb.css';
import { layoutTwin } from './twinLayout';

const MIN_ZOOM = 0.2;
const MAX_ZOOM = 2.5;

export const HEALTH_COLORS: Record<string, string> = {
  OPERATIONAL: PALETTE.success,
  DEGRADED: PALETTE.warning,
  OUTAGE: PALETTE.critical,
  MAINTENANCE: PALETTE.muted,
};

export interface TwinHighlight {
  /** Impacted CI id → impact level. */
  impacted: Map<number, 'DOWN' | 'DEGRADED'>;
  targets: Set<number>;
  /** Edge ids on impact paths. */
  edges: Set<number>;
}

function clampZoom(value: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(value * 100) / 100));
}

function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function stateCounts(node: TwinNode): string {
  const state = node.state;
  if (!state) return '';
  const parts = [state.openIncidents ? `${state.openIncidents} incidents` : '', state.firingAlerts ? `${state.firingAlerts} alerts` : '', state.openAnomalies ? `${state.openAnomalies} anomalies` : ''].filter(Boolean);
  return parts.join(', ');
}

/**
 * SVG digital twin: three bands (business services → applications → infrastructure) laid out by `layoutTwin`, live
 * state overlays (health colour, open incidents / firing alerts / open anomalies) and, after a simulation, impacted
 * nodes (DOWN red, DEGRADED amber) and the impact paths. Redundant relationships are dashed. Drag to pan, buttons or
 * Ctrl/⌘ + wheel to zoom; click or Enter selects a node.
 */
export function TwinGraphView({
  graph,
  selected,
  onSelect,
  highlight,
  height = 560,
}: {
  graph: TwinGraph;
  selected: number | null;
  onSelect: (id: number | null) => void;
  highlight?: TwinHighlight | null;
  height?: number;
}) {
  const layout = useMemo(() => layoutTwin(graph.nodes, graph.edges), [graph]);
  const containerRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const [view, setView] = useState({ x: 0, y: 0, k: 1 });
  const drag = useRef<{ startX: number; startY: number; viewX: number; viewY: number } | null>(null);
  const moved = useRef(0);

  // The canvas shrinks to the fitted drawing (phones), up to `height`.
  const [canvasHeight, setCanvasHeight] = useState(height);
  const fit = useCallback(() => {
    const width = containerRef.current?.clientWidth || 800;
    const k = clampZoom(Math.min(1, (width - 16) / layout.width, (height - 16) / layout.height));
    const fitted = Math.min(height, Math.max(260, Math.ceil(layout.height * k) + 56));
    setCanvasHeight(fitted);
    setView({ k, x: (width - layout.width * k) / 2, y: Math.max(8, (fitted - layout.height * k) / 2) });
  }, [layout, height]);

  useEffect(() => fit(), [fit]);

  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return undefined;
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      const rect = svg.getBoundingClientRect();
      const px = event.clientX - rect.left;
      const py = event.clientY - rect.top;
      setView((current) => {
        const k = clampZoom(current.k * (event.deltaY < 0 ? 1.12 : 1 / 1.12));
        const ratio = k / current.k;
        return { k, x: px - (px - current.x) * ratio, y: py - (py - current.y) * ratio };
      });
    };
    svg.addEventListener('wheel', onWheel, { passive: false });
    return () => svg.removeEventListener('wheel', onWheel);
  }, []);

  const zoomBy = (factor: number) =>
    setView((current) => {
      const width = containerRef.current?.clientWidth || 800;
      const k = clampZoom(current.k * factor);
      const ratio = k / current.k;
      return { k, x: width / 2 - (width / 2 - current.x) * ratio, y: canvasHeight / 2 - (canvasHeight / 2 - current.y) * ratio };
    });

  const onPointerDown = (event: PointerEvent<SVGSVGElement>) => {
    if (event.button !== 0) return;
    drag.current = { startX: event.clientX, startY: event.clientY, viewX: view.x, viewY: view.y };
    moved.current = 0;
  };
  const onPointerMove = (event: PointerEvent<SVGSVGElement>) => {
    const state = drag.current;
    if (!state) return;
    const dx = event.clientX - state.startX;
    const dy = event.clientY - state.startY;
    moved.current = Math.max(moved.current, Math.abs(dx) + Math.abs(dy));
    if (moved.current > 3) setView((current) => ({ ...current, x: state.viewX + dx, y: state.viewY + dy }));
  };
  const endDrag = () => {
    drag.current = null;
  };
  const select = (id: number) => {
    if (moved.current > 3) return;
    onSelect(selected === id ? null : id);
  };
  const onKey = (event: KeyboardEvent<SVGGElement>, id: number) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSelect(selected === id ? null : id);
    } else if (event.key === 'Escape') onSelect(null);
  };

  if (!layout.nodes.length) return null;
  const simulating = Boolean(highlight && (highlight.impacted.size || highlight.targets.size));

  return (
    <div className="cmdb-graph" ref={containerRef}>
      <div className="cmdb-graph-tools" role="toolbar" aria-label="Twin map controls">
        <button type="button" className="icon-btn sm" onClick={() => zoomBy(1.2)} aria-label="Zoom in" disabled={view.k >= MAX_ZOOM}>
          <Plus size={14} />
        </button>
        <button type="button" className="icon-btn sm" onClick={() => zoomBy(1 / 1.2)} aria-label="Zoom out" disabled={view.k <= MIN_ZOOM}>
          <Minus size={14} />
        </button>
        <button type="button" className="icon-btn sm" onClick={fit} aria-label="Fit to view">
          <Maximize2 size={13} />
        </button>
        <span className="cmdb-graph-zoom" aria-live="polite">
          {Math.round(view.k * 100)}%
        </span>
      </div>
      <svg
        ref={svgRef}
        className="cmdb-graph-canvas"
        width="100%"
        height={canvasHeight}
        role="group"
        aria-label={`Digital twin: ${graph.nodes.length} nodes, ${graph.edges.length} relationships${simulating ? `, ${highlight!.impacted.size} impacted by the simulation` : ''}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerLeave={endDrag}
      >
        <defs>
          <marker id="twin-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill={PALETTE.bronze} />
          </marker>
          <marker id="twin-arrow-hot" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill={PALETTE.critical} />
          </marker>
        </defs>
        <g transform={`translate(${view.x},${view.y}) scale(${view.k})`}>
          {layout.bands.map((band) => (
            <g key={band.layer} data-band={band.layer}>
              <rect x={6} y={band.y} width={layout.width - 12} height={band.height} rx={12} fill="rgba(214,183,122,0.035)" stroke="rgba(150,118,75,0.35)" strokeDasharray="2 6" />
              <text x={18} y={band.y + 15} className="cmdb-node-meta" fill={PALETTE.gold}>
                {band.label.toUpperCase()} · {band.count}
              </text>
            </g>
          ))}
          {layout.edges.map((entry) => {
            const hot = highlight?.edges.has(entry.id) ?? false;
            const dim = simulating && !hot;
            return (
              <path
                key={entry.id}
                d={entry.path}
                fill="none"
                stroke={hot ? PALETTE.critical : PALETTE.bronze}
                strokeWidth={hot ? 2.4 : 1.2}
                strokeDasharray={entry.edge.redundant ? '6 4' : undefined}
                opacity={dim ? 0.2 : hot ? 1 : 0.7}
                markerEnd={`url(#${hot ? 'twin-arrow-hot' : 'twin-arrow'})`}
                data-edge={entry.id}
                data-redundant={entry.edge.redundant || undefined}
              >
                <title>{`${entry.edge.type.replace(/_/g, ' ').toLowerCase()}${entry.edge.redundant ? ' (redundant)' : ''}`}</title>
              </path>
            );
          })}
          {layout.nodes.map(({ id, node, x, y }) => {
            const health = node.state?.health ?? null;
            const impact = highlight?.impacted.get(id);
            const target = highlight?.targets.has(id) ?? false;
            const isSelected = id === selected;
            const dim = simulating && !impact && !target;
            const stroke = target ? PALETTE.critical : impact === 'DOWN' ? PALETTE.critical : impact === 'DEGRADED' ? PALETTE.warning : isSelected ? PALETTE.gold : health ? HEALTH_COLORS[health] ?? PALETTE.bronze : PALETTE.bronze;
            const counts = stateCounts(node);
            const badge = (node.state?.openIncidents ?? 0) + (node.state?.firingAlerts ?? 0) + (node.state?.openAnomalies ?? 0);
            return (
              <g
                key={id}
                transform={`translate(${x},${y})`}
                className="cmdb-node"
                role="button"
                tabIndex={0}
                aria-pressed={isSelected}
                aria-label={`${node.name}, ${CI_TYPE_LABELS[node.ciType] ?? node.ciType}${health ? `, ${health.toLowerCase()}` : ''}${counts ? `, ${counts}` : ''}${target ? ', simulation target' : impact ? `, simulated impact ${impact.toLowerCase()}` : ''}`}
                data-impact={target ? 'TARGET' : impact}
                data-health={health ?? undefined}
                opacity={dim ? 0.3 : 1}
                onClick={() => select(id)}
                onKeyDown={(event) => onKey(event, id)}
              >
                <rect width={NODE_WIDTH} height={NODE_HEIGHT} rx={9} fill={isSelected ? PALETTE.elevated : PALETTE.card} stroke={stroke} strokeWidth={target || impact || isSelected ? 2.6 : 1.4} strokeDasharray={impact === 'DEGRADED' ? '5 3' : undefined} />
                <rect x={8} y={10} width={28} height={28} rx={6} fill={node.layer === 'BUSINESS' ? 'rgba(214,183,122,0.28)' : 'rgba(214,183,122,0.12)'} />
                <text x={22} y={28} textAnchor="middle" className="cmdb-node-glyph" fill={PALETTE.gold}>
                  {CI_TYPE_GLYPHS[node.ciType] ?? '•'}
                </text>
                <text x={44} y={21} className="cmdb-node-name" fill={PALETTE.text}>
                  {truncate(node.name, badge ? 15 : 18)}
                </text>
                <text x={44} y={36} className="cmdb-node-meta" fill={PALETTE.muted}>
                  {truncate(`${CI_TYPE_LABELS[node.ciType] ?? node.ciType}${node.cluster ? ` · ${node.cluster}` : ''}`, 24)}
                </text>
                {health && <circle cx={NODE_WIDTH - 8} cy={NODE_HEIGHT - 8} r={4} fill={HEALTH_COLORS[health] ?? PALETTE.muted} />}
                {badge > 0 && (
                  <g transform={`translate(${NODE_WIDTH - 16},12)`}>
                    <circle r={9} fill={PALETTE.critical} />
                    <text y={3.5} textAnchor="middle" className="cmdb-node-alerts" fill={PALETTE.bg}>
                      {badge > 99 ? '99+' : badge}
                    </text>
                  </g>
                )}
                <title>{`${node.name}${node.state?.healthReason ? ` — ${node.state.healthReason}` : ''}`}</title>
              </g>
            );
          })}
        </g>
      </svg>
      <div className="cmdb-graph-legend" aria-hidden="true">
        <span>
          <i className="swatch" style={{ background: PALETTE.success }} /> Operational
        </span>
        <span>
          <i className="swatch warning" /> Degraded
        </span>
        <span>
          <i className="swatch" style={{ background: PALETTE.critical }} /> Outage / simulated down
        </span>
        <span>
          <i className="swatch dashed" /> Redundant relationship
        </span>
        <span className="muted-text">Badge = open incidents + firing alerts + open anomalies</span>
      </div>
    </div>
  );
}
