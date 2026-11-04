import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { Link } from 'react-router-dom';
import { ExternalLink, Maximize2, Minus, Plus, X } from 'lucide-react';
import { PALETTE, TONE_COLORS } from '../../lib/constants';
import { CI_ENVIRONMENT_LABELS, CI_TYPE_GLYPHS, CI_TYPE_LABELS, CRITICALITY_TONES } from '../../lib/ops';
import type { CmdbGraph, GraphNode } from '../../lib/opsTypes';
import { formatNumber, titleCase } from '../../lib/format';
import { highlightFor, layoutGraph, NODE_HEIGHT, NODE_WIDTH } from './graphLayout';
import './cmdb.css';

const MIN_ZOOM = 0.2;
const MAX_ZOOM = 2.5;

interface View {
  x: number;
  y: number;
  k: number;
}

function clampZoom(value: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(value * 100) / 100));
}

function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function nodeStroke(node: GraphNode): string {
  return node.criticality ? TONE_COLORS[CRITICALITY_TONES[node.criticality]] : PALETTE.bronze;
}

/**
 * SVG dependency map of `GET /cmdb/graph` with a deterministic layered layout (graphLayout.ts), pan (drag), zoom
 * (buttons, Ctrl/⌘ + wheel), click/Enter to select a CI and highlight what it needs (upstream) and what needs it
 * (downstream); double-click or "Open" opens the CI.
 */
export function DependencyGraph({ graph, onOpen, height = 520 }: { graph: CmdbGraph; onOpen: (id: number) => void; height?: number }) {
  const layout = useMemo(() => layoutGraph(graph.nodes, graph.edges), [graph]);
  const containerRef = useRef<HTMLDivElement>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const [view, setView] = useState<View>({ x: 0, y: 0, k: 1 });
  const [selected, setSelected] = useState<number | null>(graph.rootId ?? null);
  const drag = useRef<{ startX: number; startY: number; viewX: number; viewY: number; moved: number } | null>(null);
  const moved = useRef(0);

  const fit = useCallback(() => {
    const box = containerRef.current;
    const width = box?.clientWidth || 800;
    const k = clampZoom(Math.min(1, (width - 16) / layout.width, (height - 16) / layout.height));
    setView({ k, x: (width - layout.width * k) / 2, y: Math.max(8, (height - layout.height * k) / 2) });
  }, [layout, height]);

  useEffect(() => {
    fit();
    setSelected(graph.rootId ?? null);
  }, [fit, graph.rootId]);

  // Ctrl/⌘ + wheel zooms around the pointer (a plain wheel keeps scrolling the page).
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
      const cx = width / 2;
      const cy = height / 2;
      return { k, x: cx - (cx - current.x) * ratio, y: cy - (cy - current.y) * ratio };
    });

  const onPointerDown = (event: PointerEvent<SVGSVGElement>) => {
    if (event.button !== 0) return;
    drag.current = { startX: event.clientX, startY: event.clientY, viewX: view.x, viewY: view.y, moved: 0 };
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

  const highlight = useMemo(() => (selected !== null && layout.nodes.some((node) => node.id === selected) ? highlightFor(selected, layout.edges) : null), [selected, layout]);
  const selectedNode = selected !== null ? layout.nodes.find((node) => node.id === selected)?.node ?? null : null;

  const select = (id: number) => {
    if (moved.current > 3) return;
    setSelected((current) => (current === id ? null : id));
  };
  const onNodeKey = (event: KeyboardEvent<SVGGElement>, id: number) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      if (selected === id) onOpen(id);
      else setSelected(id);
    } else if (event.key === ' ') {
      event.preventDefault();
      setSelected((current) => (current === id ? null : id));
    } else if (event.key === 'Escape') setSelected(null);
  };

  if (!layout.nodes.length) return null;

  return (
    <div className="cmdb-graph" ref={containerRef}>
      <div className="cmdb-graph-tools" role="toolbar" aria-label="Map controls">
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
        height={height}
        role="group"
        aria-label={`Dependency map: ${graph.nodes.length} configuration items, ${graph.edges.length} relationships`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerLeave={endDrag}
      >
        <defs>
          <marker id="cmdb-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill={PALETTE.bronze} />
          </marker>
          <marker id="cmdb-arrow-on" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill={PALETTE.gold} />
          </marker>
        </defs>
        <g transform={`translate(${view.x},${view.y}) scale(${view.k})`}>
          {layout.edges.map((edge) => {
            const on = highlight?.edges.has(edge.id) ?? false;
            const dim = highlight !== null && !on;
            return (
              <path
                key={edge.id}
                d={edge.path}
                fill="none"
                stroke={on ? PALETTE.gold : PALETTE.bronze}
                strokeWidth={on ? 2.2 : 1.2}
                strokeDasharray={edge.edge.origin === 'SERVICE_MAP' ? '5 4' : undefined}
                opacity={dim ? 0.18 : on ? 1 : 0.7}
                markerEnd={`url(#${on ? 'cmdb-arrow-on' : 'cmdb-arrow'})`}
                data-edge={edge.id}
              >
                <title>{`${titleCase(edge.edge.type)}${edge.edge.origin === 'SERVICE_MAP' ? ' (service map)' : ''}`}</title>
              </path>
            );
          })}
          {layout.nodes.map(({ id, node, x, y }) => {
            const isSelected = id === selected;
            const isRoot = id === graph.rootId;
            const up = highlight?.upstream.has(id) ?? false;
            const down = highlight?.downstream.has(id) ?? false;
            const dim = highlight !== null && !isSelected && !up && !down;
            const retired = node.status === 'RETIRED';
            const role = isSelected ? 'selected' : up ? 'upstream' : down ? 'downstream' : undefined;
            return (
              <g
                key={id}
                transform={`translate(${x},${y})`}
                className="cmdb-node"
                role="button"
                tabIndex={0}
                aria-pressed={isSelected}
                aria-label={`${node.name}, ${CI_TYPE_LABELS[node.ciType]}, ${titleCase(node.status)}${node.openAlerts ? `, ${node.openAlerts} open alerts` : ''}${role && role !== 'selected' ? `, ${role}` : ''}`}
                data-role={role}
                opacity={dim ? 0.3 : retired ? 0.55 : 1}
                onClick={() => select(id)}
                onDoubleClick={() => onOpen(id)}
                onKeyDown={(event) => onNodeKey(event, id)}
              >
                <rect
                  width={NODE_WIDTH}
                  height={NODE_HEIGHT}
                  rx={9}
                  fill={isSelected ? PALETTE.elevated : PALETTE.card}
                  stroke={isSelected ? PALETTE.gold : up ? PALETTE.champagne : down ? PALETTE.warning : nodeStroke(node)}
                  strokeWidth={isSelected || isRoot ? 2.6 : up || down ? 2 : 1.2}
                  strokeDasharray={node.status === 'MAINTENANCE' || node.status === 'PLANNED' ? '4 3' : undefined}
                />
                <rect x={8} y={10} width={28} height={28} rx={6} fill={node.ciType === 'BUSINESS_SERVICE' ? 'rgba(214,183,122,0.28)' : 'rgba(214,183,122,0.12)'} />
                <text x={22} y={28} textAnchor="middle" className="cmdb-node-glyph" fill={PALETTE.gold}>
                  {CI_TYPE_GLYPHS[node.ciType]}
                </text>
                <text x={44} y={21} className="cmdb-node-name" fill={PALETTE.text}>
                  {truncate(node.name, node.openAlerts ? 15 : 18)}
                </text>
                <text x={44} y={36} className="cmdb-node-meta" fill={PALETTE.muted}>
                  {truncate(`${CI_TYPE_LABELS[node.ciType]}${node.environment ? ` · ${CI_ENVIRONMENT_LABELS[node.environment]}` : ''}`, 24)}
                </text>
                {node.openAlerts > 0 && (
                  <g transform={`translate(${NODE_WIDTH - 16},12)`}>
                    <circle r={9} fill={PALETTE.critical} />
                    <text y={3.5} textAnchor="middle" className="cmdb-node-alerts" fill={PALETTE.bg}>
                      {node.openAlerts > 99 ? '99+' : node.openAlerts}
                    </text>
                  </g>
                )}
                <title>{node.name}</title>
              </g>
            );
          })}
        </g>
      </svg>
      <div className="cmdb-graph-legend" aria-hidden="true">
        <span>
          <i className="swatch gold" /> Selected
        </span>
        <span>
          <i className="swatch champagne" /> Upstream (it needs)
        </span>
        <span>
          <i className="swatch warning" /> Downstream (impacted)
        </span>
        <span>
          <i className="swatch dashed" /> Service map link
        </span>
        <span className="muted-text">Drag to pan · Ctrl/⌘ + wheel to zoom · double-click to open</span>
      </div>
      {selectedNode && highlight && (
        <div className="cmdb-graph-card" role="region" aria-label={`Selected ${selectedNode.name}`}>
          <div className="min-w-0">
            <b className="block truncate">{selectedNode.name}</b>
            <small className="block text-muted">
              {CI_TYPE_LABELS[selectedNode.ciType]} · {titleCase(selectedNode.status)}
              {selectedNode.criticality ? ` · ${titleCase(selectedNode.criticality)} criticality` : ''}
            </small>
            <small className="block">
              Needs {formatNumber(highlight.upstream.size)} · needed by {formatNumber(highlight.downstream.size)}
              {selectedNode.openAlerts ? <span className="red-text"> · {formatNumber(selectedNode.openAlerts)} open alerts</span> : null}
            </small>
          </div>
          <div className="flex shrink-0 gap-1">
            <Link to={`/cmdb/${selectedNode.id}`} className="gold-btn compact" onClick={(event) => {
              event.preventDefault();
              onOpen(selectedNode.id);
            }}>
              <ExternalLink size={12} aria-hidden="true" /> Open
            </Link>
            <button type="button" className="icon-btn sm" aria-label="Clear selection" onClick={() => setSelected(null)}>
              <X size={13} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
