import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import type { WorkflowDefinition, WorkflowMeta } from '../../lib/types';
import { fromDefinition } from './model';
import { PALETTE_MIME, WorkflowCanvas, type WorkflowCanvasProps } from './WorkflowCanvas';

const meta: WorkflowMeta = {
  triggers: ['INCIDENT_CREATED', 'SCHEDULED'],
  fields: [
    { field: 'priority', label: 'Priority', values: ['CRITICAL', 'HIGH'] },
    { field: 'serviceCode', label: 'Service code', values: null },
  ],
  operators: ['EQUALS', 'IN'],
  actions: [
    { type: 'START_RUNBOOK', label: 'Trigger approved runbook', params: [{ key: 'runbookId', label: 'Runbook id', required: true, options: null }] },
    { type: 'NOTIFY_DEPARTMENT', label: 'Send notification to department', params: [] },
    { type: 'ASSIGN_DEPARTMENT', label: 'Assign to department', params: [{ key: 'departmentCode', label: 'Department code', required: true, options: null }] },
    { type: 'DECLARE_MAJOR', label: 'Declare major incident', params: [] },
  ],
};

const definition: WorkflowDefinition = {
  conditions: { match: 'ALL', rules: [{ field: 'priority', operator: 'EQUALS', value: 'CRITICAL' }] },
  actions: [
    { type: 'START_RUNBOOK', params: { runbookId: '4' } },
    { type: 'NOTIFY_DEPARTMENT', params: {} },
  ],
  elseActions: [{ type: 'ASSIGN_DEPARTMENT', params: { departmentCode: 'IT-SD' } }],
  schedule: null,
  layout: null,
};

// jsdom has no PointerEvent: give pointer events the MouseEvent fields (button, clientX/Y) plus pointerId.
beforeAll(() => {
  if (typeof window.PointerEvent === 'undefined') {
    class TestPointerEvent extends MouseEvent {
      pointerId: number;
      constructor(type: string, init: PointerEventInit = {}) {
        super(type, init);
        this.pointerId = init.pointerId ?? 0;
      }
    }
    (window as unknown as { PointerEvent: typeof TestPointerEvent }).PointerEvent = TestPointerEvent;
  }
});

function renderCanvas(overrides: Partial<WorkflowCanvasProps> = {}) {
  const model = fromDefinition(definition, 'INCIDENT_CREATED');
  const props: WorkflowCanvasProps = {
    model,
    meta,
    selectedId: null,
    onSelect: vi.fn(),
    onOpen: vi.fn(),
    onMove: vi.fn(),
    onRemove: vi.fn(),
    onAddAction: vi.fn(),
    onSetTrigger: vi.fn(),
    resolve: (kind, key) => (kind === 'runbook' && key === '4' ? 'Payroll service restart' : undefined),
    ...overrides,
  };
  render(<WorkflowCanvas {...props} />);
  return { props, model };
}

function dataTransfer(item: unknown) {
  const payload = JSON.stringify(item);
  return { types: [PALETTE_MIME], getData: (type: string) => (type === PALETTE_MIME ? payload : ''), dropEffect: 'none' };
}

describe('WorkflowCanvas', () => {
  it('renders the trigger, the condition diamond summary, both branches and Yes/No connectors', () => {
    const { container } = render(
      <WorkflowCanvas
        model={fromDefinition(definition, 'INCIDENT_CREATED')}
        meta={meta}
        selectedId={null}
        onSelect={vi.fn()}
        onMove={vi.fn()}
        onRemove={vi.fn()}
        onAddAction={vi.fn()}
        onSetTrigger={vi.fn()}
      />,
    );
    expect(screen.getByRole('button', { name: /^Trigger: New Incident/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Condition: Priority = Critical?' })).toHaveTextContent('Priority = Critical?');
    expect(screen.getByRole('button', { name: /Yes branch, step 1: Trigger approved runbook/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Yes branch, step 2: Send notification to team/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /No branch, step 1: Assign team — to IT-SD/ })).toBeInTheDocument();
    const labels = Array.from(container.querySelectorAll('.wf-edge-label')).map((node) => node.textContent);
    expect(labels).toEqual(['Yes', 'No']);
    // 1 trigger→condition + 2 branch edges + 1 yes chain + 2 dashed slot edges
    expect(container.querySelectorAll('path.wf-edge')).toHaveLength(6);
  });

  it('uses the resolver for card details', () => {
    renderCanvas();
    expect(screen.getByRole('button', { name: /Trigger approved runbook — Payroll service restart/ })).toBeInTheDocument();
  });

  it('selects nodes on click and opens the editor with Enter', async () => {
    const user = userEvent.setup();
    const { props, model } = renderCanvas();
    await user.click(screen.getByRole('button', { name: /^Condition:/ }));
    expect(props.onSelect).toHaveBeenCalledWith('condition');
    const action = screen.getByRole('button', { name: /No branch, step 1/ });
    action.focus();
    await user.keyboard('{Enter}');
    expect(props.onOpen).toHaveBeenCalledWith(model.no[0].uid);
  });

  it('removes an action with Delete and nudges nodes with the arrow keys', () => {
    const { props, model } = renderCanvas();
    const action = screen.getByRole('button', { name: /Yes branch, step 2/ });
    fireEvent.keyDown(action, { key: 'Delete' });
    expect(props.onRemove).toHaveBeenCalledWith(model.yes[1].uid);

    const trigger = screen.getByRole('button', { name: /^Trigger:/ });
    const left = parseInt(trigger.style.left, 10);
    const top = parseInt(trigger.style.top, 10);
    fireEvent.keyDown(trigger, { key: 'ArrowRight' });
    expect(props.onMove).toHaveBeenLastCalledWith('trigger', { x: left + 8, y: top });
    fireEvent.keyDown(trigger, { key: 'ArrowDown', shiftKey: true });
    expect(props.onMove).toHaveBeenLastCalledWith('trigger', { x: left, y: top + 40 });
    // Delete never removes the trigger
    fireEvent.keyDown(trigger, { key: 'Delete' });
    expect(props.onRemove).toHaveBeenCalledTimes(1);
  });

  it('drags a node with the pointer and commits the new position on release', () => {
    const { props, model } = renderCanvas();
    const node = screen.getByRole('button', { name: /No branch, step 1/ });
    const left = parseInt(node.style.left, 10);
    const top = parseInt(node.style.top, 10);
    fireEvent.pointerDown(node, { button: 0, pointerId: 1, clientX: 100, clientY: 100 });
    fireEvent.pointerMove(node, { pointerId: 1, clientX: 130, clientY: 160 });
    expect(parseInt(node.style.left, 10)).toBe(left + 30);
    fireEvent.pointerUp(node, { pointerId: 1, clientX: 130, clientY: 160 });
    expect(props.onMove).toHaveBeenCalledWith(model.no[0].uid, { x: left + 30, y: top + 60 });
    // the click that follows a drag does not select
    fireEvent.click(node);
    expect(props.onSelect).not.toHaveBeenCalled();
  });

  it('adds palette items dropped on a branch slot, and changes the trigger when a trigger is dropped', () => {
    const { props } = renderCanvas();
    fireEvent.dragOver(screen.getByTestId('slot-no'), { dataTransfer: dataTransfer({ kind: 'action', type: 'DECLARE_MAJOR' }) });
    fireEvent.drop(screen.getByTestId('slot-no'), { dataTransfer: dataTransfer({ kind: 'action', type: 'DECLARE_MAJOR' }) });
    expect(props.onAddAction).toHaveBeenCalledWith('no', 'DECLARE_MAJOR');
    fireEvent.drop(screen.getByTestId('slot-yes'), { dataTransfer: dataTransfer({ kind: 'trigger', trigger: 'SCHEDULED' }) });
    expect(props.onSetTrigger).toHaveBeenCalledWith('SCHEDULED');
  });

  it('adds an action from the slot menu', async () => {
    const user = userEvent.setup();
    const { props } = renderCanvas();
    await user.click(within(screen.getByTestId('slot-yes')).getByRole('button', { name: /Add action to Yes/ }));
    await user.click(screen.getByRole('menuitem', { name: /Declare major/ }));
    expect(props.onAddAction).toHaveBeenCalledWith('yes', 'DECLARE_MAJOR');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('is inspect-only when read-only: no slots, no moves, no removal', () => {
    const { props } = renderCanvas({ readOnly: true });
    expect(screen.queryByTestId('slot-yes')).not.toBeInTheDocument();
    const action = screen.getByRole('button', { name: /Yes branch, step 1/ });
    fireEvent.keyDown(action, { key: 'Delete' });
    fireEvent.keyDown(action, { key: 'ArrowUp' });
    fireEvent.pointerDown(action, { button: 0, pointerId: 1, clientX: 0, clientY: 0 });
    fireEvent.pointerMove(action, { pointerId: 1, clientX: 50, clientY: 50 });
    fireEvent.pointerUp(action, { pointerId: 1, clientX: 50, clientY: 50 });
    expect(props.onRemove).not.toHaveBeenCalled();
    expect(props.onMove).not.toHaveBeenCalled();
    fireEvent.click(action);
    expect(props.onSelect).toHaveBeenCalled();
  });

  it('marks selected and invalid nodes', () => {
    const model = fromDefinition(definition, 'INCIDENT_CREATED');
    renderCanvas({ model, selectedId: 'condition', invalidIds: new Set([model.no[0].uid]) });
    expect(screen.getByRole('button', { name: /^Condition:/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /No branch, step 1/ })).toHaveClass('invalid');
  });
});
