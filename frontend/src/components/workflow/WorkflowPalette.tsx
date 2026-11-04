import type { WorkflowActionType, WorkflowMeta, WorkflowTrigger } from '../../lib/types';
import { actionIcon, triggerIcon } from './icons';
import { ACTION_DESCRIPTIONS, ACTION_ORDER, actionLabel, TRIGGER_DESCRIPTIONS, triggerLabel, type Branch } from './model';
import { writePaletteItem } from './WorkflowCanvas';

interface WorkflowPaletteProps {
  meta: WorkflowMeta;
  trigger: WorkflowTrigger;
  /** Branch that clicked actions are added to. */
  targetBranch: Branch;
  onTargetBranch: (branch: Branch) => void;
  onSetTrigger: (trigger: WorkflowTrigger) => void;
  onAddAction: (branch: Branch, type: WorkflowActionType) => void;
}

/** Ordered action types: spec order first, then anything else the API offers. */
export function paletteActions(meta: WorkflowMeta): WorkflowActionType[] {
  const offered = meta.actions.map((entry) => entry.type);
  return [...ACTION_ORDER.filter((type) => offered.includes(type)), ...offered.filter((type) => !ACTION_ORDER.includes(type))];
}

/** Left-hand palette (reference: "Triggers" and "Actions"). Click to apply, or drag onto the canvas. */
export function WorkflowPalette({ meta, trigger, targetBranch, onTargetBranch, onSetTrigger, onAddAction }: WorkflowPaletteProps) {
  return (
    <aside className="panel wf-palette" aria-label="Workflow building blocks">
      <h3 id="wf-palette-triggers">Triggers</h3>
      <ul className="wf-palette-list" aria-labelledby="wf-palette-triggers">
        {meta.triggers.map((entry) => {
          const Icon = triggerIcon(entry);
          const current = entry === trigger;
          return (
            <li key={entry}>
              <button
                type="button"
                className={`wf-palette-item ${current ? 'current' : ''}`}
                aria-pressed={current}
                title={TRIGGER_DESCRIPTIONS[entry]}
                draggable
                onDragStart={(event) => writePaletteItem(event, { kind: 'trigger', trigger: entry })}
                onClick={() => onSetTrigger(entry)}
              >
                <Icon size={14} aria-hidden="true" />
                <span>{triggerLabel(entry)}</span>
              </button>
            </li>
          );
        })}
      </ul>

      <div className="wf-palette-head">
        <h3 id="wf-palette-actions">Actions</h3>
        <div className="wf-branch-toggle" role="group" aria-label="Add actions to branch">
          {(['yes', 'no'] as Branch[]).map((branch) => (
            <button
              key={branch}
              type="button"
              aria-pressed={targetBranch === branch}
              className={targetBranch === branch ? `on ${branch}` : ''}
              onClick={() => onTargetBranch(branch)}
            >
              {branch === 'yes' ? 'Yes' : 'No'}
            </button>
          ))}
        </div>
      </div>
      <ul className="wf-palette-list" aria-labelledby="wf-palette-actions">
        {paletteActions(meta).map((type) => {
          const Icon = actionIcon(type);
          const label = actionLabel(type, meta);
          return (
            <li key={type}>
              <button
                type="button"
                className="wf-palette-item"
                title={ACTION_DESCRIPTIONS[type]}
                aria-label={`Add ${label} to the ${targetBranch === 'yes' ? 'Yes' : 'No'} branch`}
                draggable
                onDragStart={(event) => writePaletteItem(event, { kind: 'action', type })}
                onClick={() => onAddAction(targetBranch, type)}
              >
                <Icon size={14} aria-hidden="true" />
                <span>{label}</span>
              </button>
            </li>
          );
        })}
      </ul>
      <p className="wf-palette-hint">Click to add, or drag onto a branch of the canvas.</p>
    </aside>
  );
}
