import { AppWindow, Building2, KeyRound, Laptop, LifeBuoy, Network, UserRound, type LucideIcon } from 'lucide-react';
import { iconByName } from '../../lib/icons';
import { REQUEST_STATUS_LABELS, SERVICE_DESK_CATEGORIES } from '../../lib/constants';
import { titleCase } from '../../lib/format';
import type { CatalogCategoryDto, FormField, Role, ServiceRequestDto, ServiceRequestStatus } from '../../lib/types';

/**
 * Classes for a SearchInput placed inside a Modal: the generic `.modal input` field styling would otherwise draw a
 * second box inside the search field (utilities win over the component layer).
 */
export const MODAL_SEARCH_CLASS = '[&_input]:m-0 [&_input]:rounded-none [&_input]:border-0 [&_input]:bg-transparent [&_input]:p-0 [&_input]:shadow-none';

/** Filter dropdowns: two per row on phones, inline from `sm` up. */
export const FILTER_ROW_CLASS = 'grid w-full grid-cols-2 gap-2 sm:flex sm:w-auto sm:flex-wrap sm:items-center';
export const FILTER_CLASS = 'w-full sm:w-auto';

/** Icon tile per seeded service-desk category code (§4); configurable categories fall back to their own icon name. */
const CATEGORY_ICONS: Record<string, LucideIcon> = {
  HARDWARE: Laptop,
  SOFTWARE: AppWindow,
  NETWORK: Network,
  ACCESS: KeyRound,
  ACCOUNT: UserRound,
  FACILITIES: Building2,
  GENERAL: LifeBuoy,
};

export function categoryIcon(code: string | null | undefined, iconName?: string | null): LucideIcon {
  const known = code ? CATEGORY_ICONS[code.toUpperCase()] : undefined;
  return known ?? iconByName(iconName);
}

/** Category filter options: the live catalogue categories when loaded, else the seeded seven. */
export function categoryOptions(categories: CatalogCategoryDto[] | undefined): { value: string; label: string }[] {
  if (categories && categories.length > 0) return categories.map((category) => ({ value: category.code, label: category.name }));
  return SERVICE_DESK_CATEGORIES.map((category) => ({ value: category.code, label: category.name }));
}

export const FINAL_REQUEST_STATUSES: ServiceRequestStatus[] = ['FULFILLED', 'CANCELLED', 'REJECTED'];

export function isFinal(status: ServiceRequestStatus): boolean {
  return FINAL_REQUEST_STATUSES.includes(status);
}

/** Progress stepper for a request: Submitted → Approved → In Fulfilment → Fulfilled. */
export const REQUEST_PROGRESS_STEPS = ['Submitted', 'Approved', 'In Fulfilment', 'Fulfilled'] as const;

export function requestProgressIndex(status: ServiceRequestStatus): number {
  switch (status) {
    case 'PENDING_APPROVAL':
      return 0;
    case 'APPROVED':
      return 1;
    case 'IN_FULFILLMENT':
      return 2;
    case 'FULFILLED':
      return 3;
    default:
      return 0;
  }
}

export interface RequestPermissions {
  assign: boolean;
  start: boolean;
  fulfil: boolean;
  cancel: boolean;
  comment: boolean;
  internalNotes: boolean;
  /** Approver who may decide the pending approval from the drawer (the API still enforces routing and segregation of duties). */
  decide: boolean;
}

/**
 * What the current user may do with a request. Mirrors the API rules (staff assign/start/fulfil; requester or staff
 * cancel open requests; auditors are read-only; approvals routed to the approver role, SYSTEM_ADMIN decides all).
 */
export function requestPermissions(request: ServiceRequestDto, user: { id: number; role: Role } | null, staff: boolean): RequestPermissions {
  const open = !isFinal(request.status);
  const requester = Boolean(user && request.requesterId === user.id);
  const auditor = user?.role === 'AUDITOR';
  const approval = request.approval;
  const decide = Boolean(
    user && approval && approval.status === 'PENDING' && (user.role === 'SYSTEM_ADMIN' || user.role === approval.approverRole),
  );
  return {
    assign: staff && open,
    start: staff && request.status === 'APPROVED',
    fulfil: staff && (request.status === 'APPROVED' || request.status === 'IN_FULFILLMENT'),
    cancel: open && (staff || requester),
    comment: !auditor && (staff || requester),
    internalNotes: staff,
    decide,
  };
}

/** "costCentre" → "Cost Centre" for form data without a matching field definition. */
export function humaniseKey(key: string): string {
  return titleCase(key.replace(/([a-z])([A-Z])/g, '$1_$2'));
}

export function formatFormValue(value: string | number | boolean): string {
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  return String(value);
}

/** Display label for a request status or any other value found in history rows. */
export function requestValueLabel(value: string | null | undefined): string {
  if (!value) return '';
  return (REQUEST_STATUS_LABELS as Record<string, string>)[value] ?? titleCase(value);
}

/**
 * The API reports catalogue form violations as a message naming the field label in quotes
 * ("'Cost centre' is required"). Returns the key of the field it refers to, if any.
 */
export function fieldKeyFromServerMessage(fields: FormField[], message: string): string | null {
  const match = /^'([^']+)'/.exec(message.trim());
  if (!match) return null;
  const named = match[1].trim().toLowerCase();
  const field = fields.find((candidate) => candidate.label.trim().toLowerCase() === named || candidate.key.toLowerCase() === named);
  return field ? field.key : null;
}

export type FormFieldValue = string | number | boolean | undefined;

/** Collects only the values the requester provided, trimmed and typed as the API expects. */
export function buildFormData(fields: FormField[], values: Record<string, FormFieldValue>): Record<string, string | number | boolean> {
  const result: Record<string, string | number | boolean> = {};
  fields.forEach((field) => {
    const value = values[field.key];
    if (value === undefined || (typeof value === 'string' && !value.trim()) || (typeof value === 'number' && Number.isNaN(value))) return;
    result[field.key] = typeof value === 'string' ? value.trim() : value;
  });
  return result;
}
