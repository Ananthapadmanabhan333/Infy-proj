import { useState } from 'react';
import { ListPlus } from 'lucide-react';
import { useUpdateActionItem } from '../../api/majorIncidents';
import { useAssignableUsers } from '../../api/users';
import { DemoBadge } from '../Badge';
import { SelectField, TextareaField, TextField } from '../FormField';
import { ToneBadge } from '../itsm/ItsmParts';
import { Panel } from '../Panel';
import { useToast } from '../Toast';
import { errorMessage } from '../../lib/api';
import { formatDate } from '../../lib/format';
import { ACTION_ITEM_STATUSES, ACTION_ITEM_STATUS_LABELS, ACTION_ITEM_STATUS_TONES, ACTION_ITEM_TYPE_LABELS } from '../../lib/response';
import type { ActionItemDto, ActionItemStatus, ActionItemType, ActionRequest } from '../../lib/responseTypes';

function requestOf(item: ActionItemDto, patch: Partial<ActionRequest>): ActionRequest {
  return {
    title: item.title,
    description: item.description,
    actionType: item.actionType,
    ownerId: item.ownerId,
    dueDate: item.dueDate,
    status: item.status,
    ...patch,
  };
}

/**
 * Action items of a major incident (follow-ups) or a post-incident review (corrective/preventive): add, assign an owner,
 * set the due date and move the status (`PUT /action-items/{id}`). Rows are editable only when `canEdit` allows it.
 */
export function ActionItemsPanel({
  title,
  items,
  types,
  defaultType,
  canAdd,
  canEdit,
  adding: addState,
  onAdd,
  addHint,
}: {
  title: string;
  items: ActionItemDto[];
  types: ActionItemType[];
  defaultType: ActionItemType;
  canAdd: boolean;
  canEdit: (item: ActionItemDto) => boolean;
  adding: { isPending: boolean; error: unknown };
  onAdd: (body: ActionRequest, done: () => void) => void;
  addHint?: string;
}) {
  const update = useUpdateActionItem();
  const anyEditable = canAdd || items.some(canEdit);
  const staff = useAssignableUsers(null, anyEditable);
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [itemTitle, setItemTitle] = useState('');
  const [description, setDescription] = useState('');
  const [actionType, setActionType] = useState<ActionItemType>(defaultType);
  const [ownerId, setOwnerId] = useState('');
  const [dueDate, setDueDate] = useState('');
  const people = staff.data ?? [];

  const change = (item: ActionItemDto, patch: Partial<ActionRequest>, message: string) =>
    update.mutate({ id: item.id, body: requestOf(item, patch) }, { onSuccess: () => toast.success(message), onError: (error) => toast.error(error) });

  const reset = () => {
    setOpen(false);
    setItemTitle('');
    setDescription('');
    setOwnerId('');
    setDueDate('');
    setActionType(defaultType);
  };

  const open_ = items.filter((item) => item.status === 'OPEN' || item.status === 'IN_PROGRESS').length;

  return (
    <Panel
      title={
        <span>
          {title} <span className="muted-text">({open_} open of {items.length})</span>
        </span>
      }
      actions={
        canAdd && !open ? (
          <button type="button" className="subtle-btn compact" onClick={() => setOpen(true)}>
            <ListPlus size={13} aria-hidden="true" /> Add action
          </button>
        ) : undefined
      }
    >
      {items.length === 0 && !open && <p className="m-0 text-[11px] text-muted">No action items yet.</p>}
      {items.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label={title}>
          {items.map((item) => {
            const editable = canEdit(item);
            return (
              <li key={item.id} className="list-row flex-wrap lg:flex-nowrap">
                <div className="min-w-0 flex-1">
                  <b className="flex flex-wrap items-center gap-1.5">
                    <span className="break-words">{item.title}</span>
                    <ToneBadge value={item.actionType} label={ACTION_ITEM_TYPE_LABELS[item.actionType]} tone="neutral" />
                    {item.overdue && <ToneBadge value="overdue" label="Overdue" tone="critical" />}
                    <DemoBadge demo={item.demo} />
                  </b>
                  {item.description && <small className="block whitespace-pre-wrap break-words">{item.description}</small>}
                  {!editable && (
                    <small className="block">
                      {item.ownerName ?? 'No owner'}
                      {item.dueDate ? ` · due ${formatDate(item.dueDate)}` : ''}
                    </small>
                  )}
                </div>
                {editable ? (
                  <span className="flex flex-wrap gap-1">
                    <select
                      className="control sm w-auto max-w-[160px]"
                      aria-label={`Owner of ${item.title}`}
                      value={item.ownerId ?? ''}
                      disabled={update.isPending}
                      onChange={(event) => {
                        const next = event.target.value ? Number(event.target.value) : null;
                        const name = people.find((person) => person.id === next)?.fullName;
                        change(item, { ownerId: next }, next ? `“${item.title}” assigned to ${name ?? 'the selected person'}` : `“${item.title}” has no owner`);
                      }}
                    >
                      <option value="">No owner</option>
                      {item.ownerId && !people.some((person) => person.id === item.ownerId) && <option value={item.ownerId}>{item.ownerName ?? `User #${item.ownerId}`}</option>}
                      {people.map((person) => (
                        <option key={person.id} value={person.id}>
                          {person.fullName}
                        </option>
                      ))}
                    </select>
                    <input
                      type="date"
                      className="control sm w-auto"
                      aria-label={`Due date of ${item.title}`}
                      value={item.dueDate ?? ''}
                      disabled={update.isPending}
                      onChange={(event) => change(item, { dueDate: event.target.value || null }, `Due date of “${item.title}” updated`)}
                    />
                    <select
                      className="control sm w-auto"
                      aria-label={`Status of ${item.title}`}
                      value={item.status}
                      disabled={update.isPending}
                      onChange={(event) => change(item, { status: event.target.value as ActionItemStatus }, `“${item.title}” is ${ACTION_ITEM_STATUS_LABELS[event.target.value as ActionItemStatus].toLowerCase()}`)}
                    >
                      {ACTION_ITEM_STATUSES.map((status) => (
                        <option key={status} value={status}>
                          {ACTION_ITEM_STATUS_LABELS[status]}
                        </option>
                      ))}
                    </select>
                  </span>
                ) : (
                  <ToneBadge value={item.status} label={ACTION_ITEM_STATUS_LABELS[item.status]} tone={ACTION_ITEM_STATUS_TONES[item.status]} />
                )}
              </li>
            );
          })}
        </ul>
      )}
      {open && (
        <div className="action-card mt-2" role="group" aria-label="New action item">
          {addHint && <p className="m-0 mb-2 text-[11px] text-muted">{addHint}</p>}
          <TextField label="Action" required maxLength={200} value={itemTitle} onChange={(event) => setItemTitle(event.target.value)} />
          <TextareaField label="Description" rows={2} maxLength={2000} value={description} onChange={(event) => setDescription(event.target.value)} />
          <div className="form-row three">
            <SelectField label="Type" value={actionType} onChange={(event) => setActionType(event.target.value as ActionItemType)}>
              {types.map((type) => (
                <option key={type} value={type}>
                  {ACTION_ITEM_TYPE_LABELS[type]}
                </option>
              ))}
            </SelectField>
            <SelectField label="Owner" value={ownerId} onChange={(event) => setOwnerId(event.target.value)}>
              <option value="">No owner</option>
              {people.map((person) => (
                <option key={person.id} value={person.id}>
                  {person.fullName}
                </option>
              ))}
            </SelectField>
            <TextField label="Due" type="date" value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
          </div>
          {Boolean(addState.error) && (
            <p className="field-error" role="alert">
              {errorMessage(addState.error)}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" className="subtle-btn compact" onClick={reset} disabled={addState.isPending}>
              Cancel
            </button>
            <button
              type="button"
              className="gold-btn compact"
              disabled={!itemTitle.trim() || addState.isPending}
              onClick={() =>
                onAdd(
                  { title: itemTitle.trim(), description: description.trim() || null, actionType, ownerId: ownerId ? Number(ownerId) : null, dueDate: dueDate || null },
                  () => {
                    toast.success('Action item added');
                    reset();
                  },
                )
              }
            >
              {addState.isPending ? 'Adding…' : 'Add action'}
            </button>
          </div>
        </div>
      )}
    </Panel>
  );
}
