import { Modal } from '../../components/Modal';
import { RequestForm } from '../../components/servicedesk/RequestForm';
import type { CatalogItemDto, FormField } from '../../lib/types';

type FieldValue = string | number | boolean | undefined;

/** Release 2 helper (scalar fields): collects only the values the requester provided, typed as the API expects. */
export function toFormData(fields: FormField[], values: Record<string, FieldValue>): Record<string, string | number | boolean> {
  const result: Record<string, string | number | boolean> = {};
  fields.forEach((field) => {
    const value = values[field.key];
    if (value === undefined || (typeof value === 'string' && !value.trim()) || (typeof value === 'number' && Number.isNaN(value))) return;
    result[field.key] = typeof value === 'string' ? value.trim() : value;
  });
  return result;
}

/** Request one catalogue item: the shared dynamic request form (form definitions incl. conditional fields and uploads). */
export function RequestItemModal({ item, onClose, onCreated }: { item: CatalogItemDto; onClose: () => void; onCreated: (id: number, number: string) => void }) {
  return (
    <Modal open onClose={onClose} title={item.name} eyebrow={`REQUEST · ${item.categoryName.toUpperCase()}`} description={item.description ?? undefined} size="lg">
      <RequestForm
        item={item}
        onCancel={onClose}
        onCreated={(request) => {
          onCreated(request.id, request.number);
          onClose();
        }}
      />
    </Modal>
  );
}
