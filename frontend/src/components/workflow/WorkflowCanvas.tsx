import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent, type PointerEvent } from 'react';
import { GitBranch, Maximize2, Minus, Plus, Zap } from 'lucide-react';
import { useClickOutside } from '../../hooks/useClickOutside';
import type { WorkflowActionType, WorkflowMeta, WorkflowTrigger } from '../../lib/types';
import { actionIcon } from './icons';
import {
  ACTION_ORDER,
  actionDetail,
  actionLabel,
  branchAt,
  canvasSize,
  conditionLines,
  layoutEdges,
  layoutNodes,
  setPosition,
  triggerDetail,
  triggerLabel,
  type Branch,
  type CanvasNode,
  type NameResolver,
  type Point,
  type WorkflowModel,
} from './model';

/** dataTransfer type used by palette items. */
export const PALETTE_MIME = 'application/x-nexus360-workflow';

export type PaletteItem = { kind: 'action'; type: WorkflowActionType } | { kind: 'trigger'; trigger: WorkflowTrigger };

export function writePaletteItem(event: DragEvent, item: PaletteItem) {
  const payload = JSON.stringify(item);
  event.dataTransfer.setData(PALETTE_MIME, payload);
  event.dataTransfer.setData('text/plain', payload);
  event.dataTransfer.effectAllowed = 'copy';
}

function readPaletteItem(event: DragEvent): PaletteItem | null {
  const raw = event.dataTransfer.getData(PALETTE_MIME) || event.dataTransfer.getData('text/plain');
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as PaletteItem;
    if (parsed.kind === 'action' && typeof parsed.type === 'string') return parsed;
    if (parsed.kind === 'trigger' && typeof parsed.trigger === 'string') return parsed;
  } catch {
    return null;
  }
  return null;
}

function isPaletteDrag(event: DragEvent): boolean {
  const types = Array.from(event.dataTransfer?.types ?? []);
  return types.includes(PALETTE_MIME) || types.includes('text/plain');
}

export interface WorkflowCanvasProps {
  model: WorkflowModel;
  meta: WorkflowMeta | null;
  selectedId: string | null;
  /** Node ids flagged by validation (red outline). */
  invalidIds?: ReadonlySet<string>;
  readOnly?: boolean;
  resolve?: NameResolver;
  onSelect: (id: string | null) => void;
  /** Enter on a node: select it and move focus to the side panel. */
  onOpen?: (id: string) => void;
  onMove: (id: string, point: Point) => void;
  onRemove: (uid: string) => void;
  onAddAction: (branch: Branch, type: WorkflowActionType) => void;
  onSetTrigger: (trigger: WorkflowTrigger) => void;
}

interface DragState {
  id: string;
  pointerId: number;
  startX: number;
  startY: number;
  originX: number;
  originY: number;
  moved: boolean;
}

const NUDGE = 8;
const NUDGE_FAST = 40;
export const MIN_ZOOM = 0.5;
export const MAX_ZOOM = 1.5;
const roundZoom = (value: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(value * 20) / 20));

/**
 * Visual workflow canvas: trigger → condition diamond → Yes / No action chains, with SVG connectors.
 * Nodes can be dragged (pointer) or nudged (arrow keys); palette items can be dropped onto a branch.
 */
export function WorkflowCanvas({
  model,
  meta,
  selectedId,
  invalidIds,
  readOnly = false,
  resolve,
  onSelect,
  onOpen,
  onMove,
  onRemove,
  onAddAction,
  onSetTrigger,
}: WorkflowCanvasProps) {
  const stageRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = useState(1);
  const fitted = useRef(false);
  const nodeRefs = useRef(new Map<string, HTMLElement>());
  const drag = useRef<DragState | null>(null);
  const suppressClick = useRef(false);
  const pendingFocus = useRef<string | null>(null);
  const [live, setLive] = useState<{ id: string; x: number; y: number } | null>(null);
  const [dropBranch, setDropBranch] = useState<Branch | null>(null);
  const [menuBranch, setMenuBranch] = useState<Branch | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const closeMenu = useCallback(() => setMenuBranch(null), []);
  useClickOutside(menuRef, menuBranch !== null, closeMenu);

  const effective = useMemo(() => (live ? setPosition(model, live.id, live) : model), [model, live]);
  const nodes = useMemo(() => layoutNodes(effective, { slots: !readOnly }), [effective, readOnly]);
  const edges = useMemo(() => layoutEdges(nodes), [nodes]);
  const size = useMemo(() => canvasSize(nodes), [nodes]);
  const condition = useMemo(() => conditionLines(model, meta), [model, meta]);
  const menuTypes = useMemo(() => {
    const known = new Set(meta?.actions.map((entry) => entry.type) ?? ACTION_ORDER);
    const base = ACTION_ORDER.filter((type) => known.has(type));
    // Release 3: alert workflows offer "Create incident" first (it only works with ALERT_RECEIVED).
    return model.trigger === 'ALERT_RECEIVED' && known.has('CREATE_INCIDENT') ? (['CREATE_INCIDENT', ...base] as typeof base) : base;
  }, [meta, model.trigger]);

  /** Scales the canvas down so the whole flow fits the visible width (never above 100 %). */
  const fit = useCallback(() => {
    const available = scrollRef.current?.clientWidth ?? 0;
    if (!available) return;
    setZoom(Math.min(1, roundZoom(Math.floor(((available - 12) / size.width) * 20) / 20)));
  }, [size.width]);

  useLayoutEffect(() => {
    if (fitted.current) return;
    fitted.current = true;
    fit();
  }, [fit]);

  useEffect(() => {
    const id = pendingFocus.current;
    if (!id) return;
    pendingFocus.current = null;
    nodeRefs.current.get(id)?.focus();
  });

  // ------------------------------------------------------------ pointer drag
  const onPointerDown = (event: PointerEvent<HTMLElement>, node: CanvasNode) => {
    if (readOnly || event.button !== 0 || node.kind === 'slot') return;
    drag.current = { id: node.id, pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, originX: node.x, originY: node.y, moved: false };
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };

  const onPointerMove = (event: PointerEvent<HTMLElement>) => {
    const state = drag.current;
    if (!state || state.pointerId !== event.pointerId) return;
    const dx = event.clientX - state.startX;
    const dy = event.clientY - state.startY;
    if (!state.moved && Math.hypot(dx, dy) < 4) return;
    state.moved = true;
    setLive({ id: state.id, x: Math.max(0, state.originX + dx / zoom), y: Math.max(0, state.originY + dy / zoom) });
  };

  const finishDrag = (event: PointerEvent<HTMLElement>, cancelled = false) => {
    const state = drag.current;
    if (!state || state.pointerId !== event.pointerId) return;
    drag.current = null;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    if (state.moved) {
      suppressClick.current = true;
      if (!cancelled) {
        onMove(state.id, {
          x: Math.max(0, state.originX + (event.clientX - state.startX) / zoom),
          y: Math.max(0, state.originY + (event.clientY - state.startY) / zoom),
        });
      }
    }
    setLive(null);
  };

  const onNodeClick = (node: CanvasNode) => {
    if (suppressClick.current) {
      suppressClick.current = false;
      return;
    }
    onSelect(node.id);
  };

  // ------------------------------------------------------------ keyboard
  const onNodeKeyDown = (event: KeyboardEvent<HTMLElement>, node: CanvasNode) => {
    if (event.target !== event.currentTarget) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      if (onOpen) onOpen(node.id);
      else onSelect(node.id);
      return;
    }
    if (event.key === 'Escape') {
      onSelect(null);
      return;
    }
    if (readOnly) return;
    if ((event.key === 'Delete' || event.key === 'Backspace') && node.kind === 'action') {
      event.preventDefault();
      const chain = nodes.filter((entry) => entry.branch === node.branch && entry.kind === 'action');
      const position = chain.findIndex((entry) => entry.id === node.id);
      pendingFocus.current = chain[position + 1]?.id ?? chain[position - 1]?.id ?? 'condition';
      onRemove(node.id);
      return;
    }
    const step = event.shiftKey ? NUDGE_FAST : NUDGE;
    const delta: Record<string, Point> = { ArrowLeft: { x: -step, y: 0 }, ArrowRight: { x: step, y: 0 }, ArrowUp: { x: 0, y: -step }, ArrowDown: { x: 0, y: step } };
    const move = delta[event.key];
    if (move) {
      event.preventDefault();
      onMove(node.id, { x: Math.max(0, node.x + move.x), y: Math.max(0, node.y + move.y) });
    }
  };

  // ------------------------------------------------------------ palette drop
  const stagePoint = (event: DragEvent) => {
    const rect = stageRef.current?.getBoundingClientRect();
    return { x: (event.clientX - (rect?.left ?? 0)) / zoom, y: (event.clientY - (rect?.top ?? 0)) / zoom };
  };

  const onStageDragOver = (event: DragEvent<HTMLDivElement>) => {
    if (readOnly || !isPaletteDrag(event)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
    setDropBranch(branchAt(nodes, stagePoint(event).x));
  };

  const dropItem = (event: DragEvent, branch: Branch | null) => {
    setDropBranch(null);
    if (readOnly) return;
    const item = readPaletteItem(event);
    if (!item) return;
    event.preventDefault();
    if (item.kind === 'trigger') {
      onSetTrigger(item.trigger);
      return;
    }
    onAddAction(branch ?? branchAt(nodes, stagePoint(event).x), item.type);
  };

  const nodeLabel = (node: CanvasNode): string => {
    if (node.kind === 'trigger') return `Trigger: ${triggerLabel(model.trigger)}. ${triggerDetail(model)}`;
    if (node.kind === 'condition') return `Condition: ${condition.full}`;
    const action = model[node.branch as Branch][node.index];
    if (!action) return '';
    return `${node.branch === 'yes' ? 'Yes' : 'No'} branch, step ${node.index + 1}: ${actionLabel(action.type, meta)} — ${actionDetail(action, resolve)}`;
  };

  const register = (id: string) => (element: HTMLElement | null) => {
    if (element) nodeRefs.current.set(id, element);
    else nodeRefs.current.delete(id);
  };

  const nodeProps = (node: CanvasNode) => ({
    ref: register(node.id),
    role: 'button' as const,
    tabIndex: 0,
    'aria-label': nodeLabel(node),
    'aria-pressed': selectedId === node.id,
    'data-node-id': node.id,
    className: [
      'wf-node',
      `wf-node-${node.kind}`,
      node.branch ? `wf-branch-${node.branch}` : '',
      selectedId === node.id ? 'selected' : '',
      invalidIds?.has(node.id) ? 'invalid' : '',
      live?.id === node.id ? 'dragging' : '',
      readOnly ? 'readonly' : '',
    ]
      .filter(Boolean)
      .join(' '),
    style: { left: node.x, top: node.y, width: node.w, height: node.h },
    onPointerDown: (event: PointerEvent<HTMLElement>) => onPointerDown(event, node),
    onPointerMove,
    onPointerUp: (event: PointerEvent<HTMLElement>) => finishDrag(event),
    onPointerCancel: (event: PointerEvent<HTMLElement>) => finishDrag(event, true),
    onClick: () => onNodeClick(node),
    onKeyDown: (event: KeyboardEvent<HTMLElement>) => onNodeKeyDown(event, node),
  });

  return (
    <div className="wf-canvas-wrap">
      <div className="wf-canvas-scroll" ref={scrollRef} data-testid="workflow-canvas">
        <div
          className="wf-stage-frame"
          style={{ width: Math.round(size.width * zoom), height: Math.round(size.height * zoom), minWidth: '100%', minHeight: '100%' }}
          onClick={(event) => {
            if (event.target === event.currentTarget || event.target === stageRef.current) onSelect(null);
          }}
          onDragOver={onStageDragOver}
          onDragLeave={(event) => {
            if (event.target === event.currentTarget) setDropBranch(null);
          }}
          onDrop={(event) => dropItem(event, null)}
        >
          <div
            ref={stageRef}
            className={`wf-stage ${dropBranch ? `drop-${dropBranch}` : ''}`}
            style={{ width: size.width, height: size.height, transform: zoom === 1 ? undefined : `scale(${zoom})` }}
          >
            <svg className="wf-edges" width={size.width} height={size.height} aria-hidden="true" focusable="false">
              <defs>
                <marker id="wf-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" className="wf-arrow-head" />
                </marker>
                <marker id="wf-arrow-muted" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" className="wf-arrow-head muted" />
                </marker>
              </defs>
              {edges.map((edge) => (
                <g key={edge.id}>
                  <path d={edge.d} className={`wf-edge ${edge.dashed ? 'dashed' : ''}`} markerEnd={edge.dashed ? 'url(#wf-arrow-muted)' : 'url(#wf-arrow)'} />
                  {edge.label && (
                    <text x={edge.labelX} y={edge.labelY} className={`wf-edge-label ${edge.label === 'Yes' ? 'yes' : 'no'}`} textAnchor="middle" dominantBaseline="middle">
                      {edge.label}
                    </text>
                  )}
                </g>
              ))}
            </svg>

            {nodes.map((node) => {
              if (node.kind === 'trigger') {
                return (
                  <div key={node.id} {...nodeProps(node)}>
                    <span className="wf-node-icon gold">
                      <Zap size={17} aria-hidden="true" />
                    </span>
                    <span className="wf-node-text">
                      <small>Trigger</small>
                      <b>{triggerLabel(model.trigger)}</b>
                      <span>{triggerDetail(model)}</span>
                    </span>
                  </div>
                );
              }
              if (node.kind === 'condition') {
                return (
                  <div key={node.id} {...nodeProps(node)}>
                    <svg className="wf-diamond" viewBox={`0 0 ${node.w} ${node.h}`} width={node.w} height={node.h} aria-hidden="true" focusable="false">
                      <polygon points={`${node.w / 2},2 ${node.w - 2},${node.h / 2} ${node.w / 2},${node.h - 2} 2,${node.h / 2}`} />
                    </svg>
                    <span className="wf-diamond-text">
                      <small>
                        <GitBranch size={10} aria-hidden="true" /> Condition
                      </small>
                      <b>{condition.headline}</b>
                      {condition.more && <span>{condition.more}</span>}
                    </span>
                  </div>
                );
              }
              if (node.kind === 'action') {
                const action = model[node.branch as Branch][node.index];
                if (!action) return null;
                const Icon = actionIcon(action.type);
                return (
                  <div key={node.id} {...nodeProps(node)}>
                    <span className="wf-node-icon">
                      <Icon size={16} aria-hidden="true" />
                    </span>
                    <span className="wf-node-text">
                      <b>{actionLabel(action.type, meta)}</b>
                      <span>{actionDetail(action, resolve)}</span>
                    </span>
                    <span className="wf-step" aria-hidden="true">
                      {node.index + 1}
                    </span>
                  </div>
                );
              }
              // add-action slot (drop target + click menu)
              const branch = node.branch as Branch;
              return (
                <div
                  key={node.id}
                  className={`wf-slot wf-branch-${branch} ${dropBranch === branch ? 'active' : ''}`}
                  style={{ left: node.x, top: node.y, width: node.w, height: node.h }}
                  onDragOver={(event) => {
                    if (!isPaletteDrag(event)) return;
                    event.preventDefault();
                    event.stopPropagation();
                    setDropBranch(branch);
                  }}
                  onDrop={(event) => {
                    event.stopPropagation();
                    dropItem(event, branch);
                  }}
                  ref={menuBranch === branch ? menuRef : undefined}
                  data-testid={`slot-${branch}`}
                >
                  <button
                    type="button"
                    className="wf-slot-btn"
                    aria-haspopup="menu"
                    aria-expanded={menuBranch === branch}
                    onClick={() => setMenuBranch(menuBranch === branch ? null : branch)}
                  >
                    <Plus size={13} aria-hidden="true" />
                    {model[branch].length === 0 && branch === 'no' ? 'No branch: do nothing · add action' : `Add action to ${branch === 'yes' ? 'Yes' : 'No'}`}
                  </button>
                  {menuBranch === branch && (
                    <div
                      className="wf-slot-menu"
                      role="menu"
                      aria-label={`Add an action to the ${branch === 'yes' ? 'Yes' : 'No'} branch`}
                      onKeyDown={(event) => {
                        if (event.key === 'Escape') setMenuBranch(null);
                      }}
                    >
                      {menuTypes.map((type) => {
                        const Icon = actionIcon(type);
                        return (
                          <button
                            key={type}
                            type="button"
                            role="menuitem"
                            onClick={() => {
                              setMenuBranch(null);
                              onAddAction(branch, type);
                            }}
                          >
                            <Icon size={13} aria-hidden="true" /> {actionLabel(type, meta)}
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>
      <div className="wf-zoom" role="group" aria-label="Canvas zoom">
        <button type="button" aria-label="Zoom out" disabled={zoom <= MIN_ZOOM} onClick={() => setZoom((value) => roundZoom(value - 0.1))}>
          <Minus size={12} />
        </button>
        <output aria-live="polite" aria-label="Zoom level">
          {Math.round(zoom * 100)}%
        </output>
        <button type="button" aria-label="Zoom in" disabled={zoom >= MAX_ZOOM} onClick={() => setZoom((value) => roundZoom(value + 0.1))}>
          <Plus size={12} />
        </button>
        <button type="button" aria-label="Fit canvas to view" onClick={fit}>
          <Maximize2 size={11} /> Fit
        </button>
      </div>
    </div>
  );
}
