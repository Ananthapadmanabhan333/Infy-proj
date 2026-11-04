import type { ShowIf, StagedUpload, UserOption } from './itsmTypes';
import type { FormField } from './types';

/**
 * Client side of the dynamic form builder (mirrors `form/FormValidator.java`, which stays authoritative): visibility
 * (`showIf`), validation with the server's wording, and the submission shape (`formData`).
 */

/** Editing value per field: strings for text-like inputs, booleans, string arrays (multiselect), a user, staged files. */
export type DynamicValue = string | boolean | string[] | UserOption | StagedUpload[] | null | undefined;
export type DynamicValues = Record<string, DynamicValue>;

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const DEFAULT_MAX_FILES = 5;

export function initialValue(field: FormField): DynamicValue {
  switch (field.type) {
    case 'checkbox':
      return false;
    case 'multiselect':
      return [];
    case 'file':
      return [];
    case 'user':
      return null;
    default:
      return '';
  }
}

export function initialValues(fields: FormField[]): DynamicValues {
  return Object.fromEntries(fields.map((field) => [field.key, initialValue(field)]));
}

function isUser(value: unknown): value is UserOption {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value) && 'id' in (value as object));
}

/** Submitted value of one field (undefined = omitted). */
export function submissionValue(field: FormField, value: DynamicValue): unknown {
  switch (field.type) {
    case 'checkbox':
      return Boolean(value);
    case 'number': {
      if (typeof value !== 'string' || !value.trim()) return undefined;
      const parsed = Number(value);
      return Number.isNaN(parsed) ? value.trim() : parsed;
    }
    case 'multiselect':
      return Array.isArray(value) && value.length ? [...(value as string[])] : undefined;
    case 'user':
      return isUser(value) ? value.id : undefined;
    case 'file':
      return Array.isArray(value) && value.length ? (value as StagedUpload[]).map((file) => file.id) : undefined;
    default:
      return typeof value === 'string' && value.trim() ? value.trim() : undefined;
  }
}

function asList(value: unknown): string[] {
  if (value === undefined || value === null) return [];
  if (typeof value === 'string' && !value.trim()) return [];
  if (Array.isArray(value)) return value.map((entry) => String(entry).trim());
  return [String(value).trim()];
}

/** Same semantics as `FormValidator.matches`. */
export function matchesShowIf(rule: ShowIf, submitted: unknown): boolean {
  const values = asList(submitted);
  const expected = (rule.value ?? '').trim();
  const eq = (a: string, b: string) => a.toLowerCase() === b.toLowerCase();
  switch (rule.op) {
    case 'EMPTY':
      return values.length === 0;
    case 'NOT_EMPTY':
      return values.length > 0 && !(values.length === 1 && values[0].toLowerCase() === 'false');
    case 'EQUALS':
      return values.some((value) => eq(value, expected));
    case 'NOT_EQUALS':
      return !values.some((value) => eq(value, expected));
    case 'IN':
      return expected
        .split(',')
        .map((entry) => entry.trim())
        .some((entry) => values.some((value) => eq(value, entry)));
    case 'CONTAINS':
      return Boolean(expected) && values.some((value) => value.toLowerCase().includes(expected.toLowerCase()));
    default:
      return true;
  }
}

/** Keys of the fields shown for the current values (a field whose referenced field is hidden is hidden too). */
export function visibleKeys(fields: FormField[], values: DynamicValues): Set<string> {
  const visible = new Set<string>();
  fields.forEach((field) => {
    const rule = field.showIf;
    const shown = !rule || (visible.has(rule.field) && matchesShowIf(rule, submissionValue(fields.find((other) => other.key === rule.field) ?? field, values[rule.field])));
    if (shown) visible.add(field.key);
  });
  return visible;
}

/** `formData` for the API: visible fields only, typed. */
export function toFormData(fields: FormField[], values: DynamicValues): Record<string, unknown> {
  const visible = visibleKeys(fields, values);
  const result: Record<string, unknown> = {};
  fields.forEach((field) => {
    if (!visible.has(field.key)) return;
    const value = submissionValue(field, values[field.key]);
    if (value !== undefined) result[field.key] = value;
  });
  return result;
}

/** `today`, `today+N`, `today-N` or an ISO date → `YYYY-MM-DD` (null when not set / invalid). */
export function resolveDate(spec: string | null | undefined, today = new Date()): string | null {
  if (!spec || !spec.trim()) return null;
  const s = spec.trim().toLowerCase();
  const relative = /^today([+-]\d{1,4})?$/.exec(s);
  if (relative) {
    const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() + (relative[1] ? Number(relative[1]) : 0));
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  }
  return ISO_DATE.test(s) ? s : null;
}

function fmt(n: number): string {
  return Number.isInteger(n) ? String(n) : String(n);
}

function patternMatches(pattern: string, value: string): boolean {
  try {
    return new RegExp(`^(?:${pattern})$`).test(value);
  } catch {
    return true; // Java-only syntax: leave it to the server
  }
}

/** Client validation of the visible fields; messages follow the server wording without quotes ("Cost centre is required"). */
export function validateDynamic(fields: FormField[], values: DynamicValues, today = new Date()): Record<string, string> {
  const visible = visibleKeys(fields, values);
  const errors: Record<string, string> = {};
  fields.forEach((field) => {
    if (!visible.has(field.key)) return;
    const label = field.label || field.key;
    const raw = values[field.key];
    const submitted = submissionValue(field, raw);
    const blank = submitted === undefined || (field.type === 'checkbox' && submitted === false);
    if (field.type === 'checkbox') {
      if (field.required && !submitted) errors[field.key] = `${label} must be confirmed`;
      return;
    }
    if (blank) {
      if (field.required) errors[field.key] = `${label} is required`;
      return;
    }
    const min = field.min ?? null;
    const max = field.max ?? null;
    switch (field.type) {
      case 'number': {
        if (typeof submitted !== 'number' || !Number.isFinite(submitted)) errors[field.key] = `${label} must be a number`;
        else if (min !== null && submitted < min) errors[field.key] = `${label} must be at least ${fmt(min)}`;
        else if (max !== null && submitted > max) errors[field.key] = `${label} must be at most ${fmt(max)}`;
        return;
      }
      case 'date': {
        const value = String(submitted);
        if (!ISO_DATE.test(value)) {
          errors[field.key] = `${label} must be a date (YYYY-MM-DD)`;
          return;
        }
        const from = resolveDate(field.minDate, today);
        const to = resolveDate(field.maxDate, today);
        if (from && value < from) errors[field.key] = `${label} must be on or after ${from}`;
        else if (to && value > to) errors[field.key] = `${label} must be on or before ${to}`;
        return;
      }
      case 'select':
        if (!(field.options ?? []).includes(String(submitted))) errors[field.key] = `Choose a valid option for ${label}`;
        return;
      case 'multiselect': {
        const chosen = submitted as string[];
        if (min !== null && chosen.length < min) errors[field.key] = `${label} needs at least ${fmt(min)} selections`;
        else if (max !== null && chosen.length > max) errors[field.key] = `${label} allows at most ${fmt(max)} selections`;
        return;
      }
      case 'file': {
        const count = (submitted as number[]).length;
        const limit = max !== null ? Math.round(max) : DEFAULT_MAX_FILES;
        if (count > limit) errors[field.key] = `${label} allows at most ${limit} file${limit === 1 ? '' : 's'}`;
        else if (min !== null && count < min) errors[field.key] = `${label} needs at least ${fmt(min)} files`;
        return;
      }
      case 'user':
        return;
      default: {
        const value = String(submitted);
        const hardMax = field.type === 'textarea' ? 5000 : field.type === 'email' ? 254 : 2000;
        if (field.type === 'email' && !EMAIL.test(value)) errors[field.key] = `${label} must be a valid e-mail address`;
        else if (value.length > hardMax || (max !== null && value.length > max)) errors[field.key] = `${label} is too long${max !== null ? ` (at most ${fmt(max)} characters)` : ''}`;
        else if (min !== null && value.length < min) errors[field.key] = `${label} must be at least ${fmt(min)} characters`;
        else if (field.pattern && !patternMatches(field.pattern, value)) errors[field.key] = `${label} has an invalid format`;
      }
    }
  });
  return errors;
}

/** Maps API errors (`fieldErrors[{field: "formData.<key>"}]`, or a message naming the label in quotes) onto field keys. */
export function serverFieldErrors(fields: FormField[], fieldErrors: { field: string; message: string }[] | undefined, message: string): Record<string, string> {
  const result: Record<string, string> = {};
  (fieldErrors ?? []).forEach(({ field, message: text }) => {
    const key = field.startsWith('formData.') ? field.slice('formData.'.length) : field;
    if (fields.some((candidate) => candidate.key === key)) result[key] = text;
  });
  if (Object.keys(result).length === 0) {
    const match = /^'([^']+)'/.exec(message.trim());
    if (match) {
      const named = match[1].trim().toLowerCase();
      const field = fields.find((candidate) => candidate.label.trim().toLowerCase() === named || candidate.key.toLowerCase() === named);
      if (field) result[field.key] = message;
    }
  }
  return result;
}

/** Display text of a stored form value (R2 scalars, multiselect lists, user `{id,name}`, files `[{fileName}]`). */
export function displayFormValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (Array.isArray(value)) {
    return value
      .map((entry) => (entry && typeof entry === 'object' ? String((entry as Record<string, unknown>).fileName ?? (entry as Record<string, unknown>).name ?? (entry as Record<string, unknown>).id ?? '') : String(entry)))
      .filter(Boolean)
      .join(', ');
  }
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>;
    return String(record.name ?? record.fullName ?? record.fileName ?? record.id ?? '—');
  }
  return String(value);
}
