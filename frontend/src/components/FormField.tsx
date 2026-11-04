import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';

interface FieldChrome {
  label: string;
  error?: string;
  hint?: ReactNode;
  required?: boolean;
  className?: string;
}

function FieldShell({
  id,
  label,
  error,
  hint,
  required,
  className = '',
  children,
}: FieldChrome & { id: string; children: ReactNode }) {
  return (
    <div className={`field ${className}`} data-invalid={error ? 'true' : undefined}>
      <div className="field-label">
        <label htmlFor={id}>{label}</label>
        {required && (
          <span className="required-mark" aria-hidden="true">
            *
          </span>
        )}
      </div>
      {children}
      {hint && !error && (
        <span className="field-hint" id={`${id}-hint`}>
          {hint}
        </span>
      )}
      {error && (
        <span className="field-error" id={`${id}-error`} role="alert">
          {error}
        </span>
      )}
    </div>
  );
}

function describedBy(id: string, error?: string, hint?: ReactNode) {
  if (error) return `${id}-error`;
  if (hint) return `${id}-hint`;
  return undefined;
}

type InputProps = FieldChrome & Omit<InputHTMLAttributes<HTMLInputElement>, 'className'> & { inputClassName?: string };

export const TextField = forwardRef<HTMLInputElement, InputProps>(function TextField(
  { label, error, hint, required, className, id, inputClassName = '', ...rest },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? autoId;
  return (
    <FieldShell id={inputId} label={label} error={error} hint={hint} required={required} className={className}>
      <input
        ref={ref}
        id={inputId}
        className={`control ${inputClassName}`}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(inputId, error, hint)}
        aria-required={required || undefined}
        {...rest}
      />
    </FieldShell>
  );
});

type SelectProps = FieldChrome & Omit<SelectHTMLAttributes<HTMLSelectElement>, 'className'> & { children: ReactNode };

export const SelectField = forwardRef<HTMLSelectElement, SelectProps>(function SelectField(
  { label, error, hint, required, className, id, children, ...rest },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? autoId;
  return (
    <FieldShell id={inputId} label={label} error={error} hint={hint} required={required} className={className}>
      <select
        ref={ref}
        id={inputId}
        className="control"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(inputId, error, hint)}
        aria-required={required || undefined}
        {...rest}
      >
        {children}
      </select>
    </FieldShell>
  );
});

type TextareaProps = FieldChrome & Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'className'>;

export const TextareaField = forwardRef<HTMLTextAreaElement, TextareaProps>(function TextareaField(
  { label, error, hint, required, className, id, ...rest },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? autoId;
  return (
    <FieldShell id={inputId} label={label} error={error} hint={hint} required={required} className={className}>
      <textarea
        ref={ref}
        id={inputId}
        className="control"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(inputId, error, hint)}
        aria-required={required || undefined}
        {...rest}
      />
    </FieldShell>
  );
});

type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'className'> & {
  label: ReactNode;
  error?: string;
  className?: string;
};

export const CheckboxField = forwardRef<HTMLInputElement, CheckboxProps>(function CheckboxField(
  { label, error, className = '', ...rest },
  ref,
) {
  return (
    <div className={className}>
      <label className="check">
        <input ref={ref} type="checkbox" aria-invalid={error ? true : undefined} {...rest} />
        <span>{label}</span>
      </label>
      {error && (
        <span className="field-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
});

/** react-hook-form setValueAs for optional numeric inputs: '' → null. */
export function toNumberOrNull(value: unknown): number | null {
  if (value === '' || value === null || value === undefined) return null;
  const parsed = Number(value);
  return Number.isNaN(parsed) ? NaN : parsed;
}

/** '' → null, '12' → 12 for ID selects. */
export function idOrNull(value: string | null | undefined): number | null {
  if (!value) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

export function textOrNull(value: string | null | undefined): string | null {
  const trimmed = (value ?? '').trim();
  return trimmed ? trimmed : null;
}
