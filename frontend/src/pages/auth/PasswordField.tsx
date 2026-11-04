import { forwardRef, useId, useState, type InputHTMLAttributes, type ReactNode } from 'react';
import { Check, Circle, Eye, EyeOff } from 'lucide-react';
import { passwordRules } from '../../lib/passwordPolicy';

type PasswordFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'className'> & {
  label: string;
  error?: string;
  hint?: ReactNode;
  required?: boolean;
  /** Rendered at the end of the label row (e.g. a "Forgot password?" link) — outside the <label>. */
  labelAction?: ReactNode;
  /** Extra ids for aria-describedby (e.g. the live policy checklist). */
  describedBy?: string;
};

/**
 * Password input with a show/hide toggle. The toggle is a real button whose name ("Show password") stays
 * constant while `aria-pressed` reports whether the characters are visible.
 */
export const PasswordField = forwardRef<HTMLInputElement, PasswordFieldProps>(function PasswordField(
  { label, error, hint, required, labelAction, describedBy, id, ...rest },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? autoId;
  const [visible, setVisible] = useState(false);
  const messageId = error ? `${inputId}-error` : hint ? `${inputId}-hint` : null;
  const describedIds = [messageId, describedBy].filter(Boolean).join(' ') || undefined;
  const toggleName = `Show ${label.charAt(0).toLowerCase()}${label.slice(1)}`;

  return (
    <div className="field" data-invalid={error ? 'true' : undefined}>
      <div className="field-label auth-field-label">
        <label htmlFor={inputId}>{label}</label>
        {required && (
          <span className="required-mark" aria-hidden="true">
            *
          </span>
        )}
        {labelAction && <span className="auth-field-action">{labelAction}</span>}
      </div>
      <div className="auth-password">
        <input
          ref={ref}
          id={inputId}
          type={visible ? 'text' : 'password'}
          className="control"
          aria-invalid={error ? true : undefined}
          aria-describedby={describedIds}
          aria-required={required || undefined}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          {...rest}
        />
        <button
          type="button"
          className="auth-password-toggle"
          aria-pressed={visible}
          aria-controls={inputId}
          title={visible ? 'Hide characters' : 'Show characters'}
          onClick={() => setVisible((current) => !current)}
        >
          {visible ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
          <span className="sr-only">{toggleName}</span>
        </button>
      </div>
      {hint && !error && (
        <span className="field-hint" id={`${inputId}-hint`}>
          {hint}
        </span>
      )}
      {error && (
        <span className="field-error" id={`${inputId}-error`} role="alert">
          {error}
        </span>
      )}
    </div>
  );
});

/** Live checklist of the password policy (mirrors the backend PasswordPolicy). */
export function PasswordChecklist({ id, password, email }: { id: string; password: string; email?: string | null }) {
  const rules = passwordRules(password, email);
  return (
    <ul id={id} className="auth-policy" aria-label="Password requirements">
      {rules.map((rule) => (
        <li key={rule.id} className={rule.met ? 'met' : undefined}>
          {rule.met ? <Check size={13} aria-hidden="true" /> : <Circle size={11} aria-hidden="true" />}
          <span>
            {rule.label}
            <span className="sr-only">{rule.met ? ' (met)' : ' (not met yet)'}</span>
          </span>
        </li>
      ))}
    </ul>
  );
}
