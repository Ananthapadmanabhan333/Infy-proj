import { useId, useRef, useState } from 'react';
import { Paperclip, X } from 'lucide-react';
import { useStageUpload } from '../../api/forms';
import { errorMessage } from '../../lib/api';
import { resolveDate, visibleKeys, type DynamicValue, type DynamicValues } from '../../lib/dynamicForm';
import { formatBytes } from '../../lib/format';
import type { StagedUpload, UserOption } from '../../lib/itsmTypes';
import type { FormField } from '../../lib/types';
import { CheckboxField, SelectField, TextareaField, TextField } from '../FormField';
import { UserSearchPicker } from '../itsm/ItsmParts';

interface DynamicFormFieldsProps {
  fields: FormField[];
  values: DynamicValues;
  onChange: (key: string, value: DynamicValue) => void;
  errors?: Record<string, string>;
  /** Form builder preview: file fields do not upload. */
  preview?: boolean;
  disabled?: boolean;
}

function MultiSelectField({ field, value, onChange, error, disabled }: { field: FormField; value: string[]; onChange: (value: string[]) => void; error?: string; disabled?: boolean }) {
  const id = useId();
  return (
    <fieldset className="field" data-invalid={error ? 'true' : undefined} aria-describedby={error ? `${id}-error` : undefined}>
      <legend className="field-label">
        <span>{field.label}</span>
        {field.required && (
          <span className="required-mark" aria-hidden="true">
            *
          </span>
        )}
      </legend>
      <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1">
        {(field.options ?? []).map((option) => (
          <label key={option} className="inline-flex items-center gap-1.5 text-[11px]">
            <input
              type="checkbox"
              checked={value.includes(option)}
              disabled={disabled}
              onChange={(event) => onChange(event.target.checked ? [...value, option] : value.filter((entry) => entry !== option))}
            />
            {option}
          </label>
        ))}
      </div>
      {field.helpText && !error && <span className="field-hint">{field.helpText}</span>}
      {error && (
        <span className="field-error" id={`${id}-error`} role="alert">
          {error}
        </span>
      )}
    </fieldset>
  );
}

function FileField({ field, value, onChange, error, preview, disabled }: { field: FormField; value: StagedUpload[]; onChange: (value: StagedUpload[]) => void; error?: string; preview?: boolean; disabled?: boolean }) {
  const upload = useStageUpload();
  const inputRef = useRef<HTMLInputElement>(null);
  const id = useId();
  const [uploadError, setUploadError] = useState<string | null>(null);
  const limit = field.max != null ? Math.round(field.max) : 5;
  const accept = field.accept && field.accept.length ? field.accept.map((extension) => `.${extension}`).join(',') : undefined;

  const pick = async (files: FileList | null) => {
    if (!files || !files.length) return;
    setUploadError(null);
    const room = Math.max(0, limit - value.length);
    const chosen = Array.from(files).slice(0, room);
    if (files.length > room) setUploadError(`${field.label} allows at most ${limit} file${limit === 1 ? '' : 's'}`);
    let next = [...value];
    for (const file of chosen) {
      try {
        const staged = await upload.mutateAsync(file);
        next = [...next, staged];
        onChange(next);
      } catch (failure) {
        setUploadError(`${file.name}: ${errorMessage(failure)}`);
      }
    }
    if (inputRef.current) inputRef.current.value = '';
  };

  const shownError = error ?? uploadError ?? undefined;
  return (
    <div className="field" data-invalid={shownError ? 'true' : undefined}>
      <div className="field-label">
        <label htmlFor={id}>{field.label}</label>
        {field.required && (
          <span className="required-mark" aria-hidden="true">
            *
          </span>
        )}
      </div>
      {preview ? (
        <p className="m-0 mt-1 text-[11px] text-muted">File upload ({accept ?? 'png, jpg, pdf, docx, xlsx, txt, log, csv, json'}; up to {limit}) — disabled in the preview.</p>
      ) : (
        <>
          <input
            id={id}
            ref={inputRef}
            type="file"
            className="control"
            multiple={limit > 1}
            accept={accept}
            disabled={disabled || upload.isPending || value.length >= limit}
            onChange={(event) => void pick(event.target.files)}
          />
          {upload.isPending && <span className="field-hint">Uploading…</span>}
          {value.length > 0 && (
            <ul className="m-0 mt-1.5 grid list-none gap-1 p-0" aria-label={`${field.label} files`}>
              {value.map((file) => (
                <li key={file.id} className="flex items-center gap-2 text-[11px]">
                  <Paperclip size={12} aria-hidden="true" />
                  <span className="min-w-0 flex-1 truncate">{file.fileName}</span>
                  <span className="text-muted">{formatBytes(file.sizeBytes)}</span>
                  <button type="button" className="icon-btn sm" aria-label={`Remove ${file.fileName}`} onClick={() => onChange(value.filter((entry) => entry.id !== file.id))} disabled={disabled}>
                    <X size={12} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {field.helpText && !shownError && <span className="field-hint">{field.helpText}</span>}
      {shownError && (
        <span className="field-error" role="alert">
          {shownError}
        </span>
      )}
    </div>
  );
}

/**
 * Renders a form definition (all R3 field types) with conditional visibility. Controlled: values live in the parent,
 * which validates with `validateDynamic` and submits `toFormData` (hidden fields are dropped, as on the server).
 */
export function DynamicFormFields({ fields, values, onChange, errors = {}, preview = false, disabled = false }: DynamicFormFieldsProps) {
  const visible = visibleKeys(fields, values);
  return (
    <>
      {fields
        .filter((field) => visible.has(field.key))
        .map((field) => {
          const error = errors[field.key];
          const value = values[field.key];
          const common = { label: field.label, required: field.required, error, hint: field.helpText ?? undefined, disabled };
          const text = typeof value === 'string' ? value : '';
          switch (field.type) {
            case 'textarea':
              return (
                <TextareaField
                  key={field.key}
                  {...common}
                  rows={3}
                  placeholder={field.placeholder ?? undefined}
                  maxLength={field.max ?? 5000}
                  value={text}
                  onChange={(event) => onChange(field.key, event.target.value)}
                />
              );
            case 'select':
              return (
                <SelectField key={field.key} {...common} value={text} onChange={(event) => onChange(field.key, event.target.value)}>
                  <option value="">{field.placeholder || 'Choose…'}</option>
                  {(field.options ?? []).map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </SelectField>
              );
            case 'multiselect':
              return <MultiSelectField key={field.key} field={field} value={Array.isArray(value) ? (value as string[]) : []} onChange={(next) => onChange(field.key, next)} error={error} disabled={disabled} />;
            case 'number':
              return (
                <TextField
                  key={field.key}
                  {...common}
                  type="number"
                  placeholder={field.placeholder ?? undefined}
                  min={field.min ?? undefined}
                  max={field.max ?? undefined}
                  value={text}
                  onChange={(event) => onChange(field.key, event.target.value)}
                />
              );
            case 'date':
              return (
                <TextField
                  key={field.key}
                  {...common}
                  type="date"
                  min={resolveDate(field.minDate) ?? undefined}
                  max={resolveDate(field.maxDate) ?? undefined}
                  value={text}
                  onChange={(event) => onChange(field.key, event.target.value)}
                />
              );
            case 'email':
              return <TextField key={field.key} {...common} type="email" placeholder={field.placeholder ?? undefined} value={text} onChange={(event) => onChange(field.key, event.target.value)} />;
            case 'checkbox':
              return (
                <CheckboxField
                  key={field.key}
                  label={`${field.label}${field.required ? ' *' : ''}`}
                  error={error}
                  disabled={disabled}
                  checked={Boolean(value)}
                  onChange={(event) => onChange(field.key, event.target.checked)}
                />
              );
            case 'user':
              return (
                <UserSearchPicker
                  key={field.key}
                  label={`${field.label}${field.required ? ' *' : ''}`}
                  value={(value as UserOption | null) ?? null}
                  onChange={(user) => onChange(field.key, user)}
                  roles={field.roles}
                  error={error}
                  hint={field.helpText ?? undefined}
                />
              );
            case 'file':
              return <FileField key={field.key} field={field} value={Array.isArray(value) ? (value as StagedUpload[]) : []} onChange={(next) => onChange(field.key, next)} error={error} preview={preview} disabled={disabled} />;
            default:
              return (
                <TextField
                  key={field.key}
                  {...common}
                  placeholder={field.placeholder ?? undefined}
                  maxLength={field.max ?? 2000}
                  value={text}
                  onChange={(event) => onChange(field.key, event.target.value)}
                />
              );
          }
        })}
    </>
  );
}
