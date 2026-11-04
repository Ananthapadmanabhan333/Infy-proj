import { useId, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link } from 'react-router-dom';
import { CheckCircle2, KeyRound, LogIn } from 'lucide-react';
import { useCompletePasswordReset, useResetToken } from '../../api/accounts';
import { errorMessage, toApiError } from '../../lib/api';
import { completeResetSchema, type CompleteResetValues } from '../../lib/accountSchemas';
import type { ResetTokenDetails } from '../../lib/accountTypes';
import { formatDateTime, formatRelative } from '../../lib/format';
import { AuthCardHeader, AuthLayout, AuthLoading } from './AuthLayout';
import { FormAlert, InvalidLinkState, LinkCheckFailed } from './AuthStates';
import { applyKnownFieldErrors, rateLimitMessage, useFocusOnMount, useUrlToken } from './authUtils';
import { PasswordChecklist, PasswordField } from './PasswordField';

const TITLE_ID = 'reset-title';
const RESET_FIELDS = ['newPassword'] as const;

function PasswordChanged({ email }: { email: string }) {
  const headingRef = useFocusOnMount<HTMLHeadingElement>();
  return (
    <>
      <AuthCardHeader
        id={TITLE_ID}
        headingRef={headingRef}
        icon={<CheckCircle2 size={20} />}
        tone="success"
        eyebrow="Password changed"
        title="Your new password is set"
        description="Any other sessions on this account have been signed out, and the reset link can no longer be used."
      />
      <Link to={`/login?email=${encodeURIComponent(email)}&reason=password-reset`} className="gold-btn full">
        <LogIn size={15} aria-hidden="true" /> Sign in
      </Link>
    </>
  );
}

function ResetForm({ token, details, onInvalid }: { token: string; details: ResetTokenDetails; onInvalid: () => void }) {
  const complete = useCompletePasswordReset();
  const checklistId = useId();
  const [done, setDone] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const schema = useMemo(() => completeResetSchema(details.email), [details.email]);
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<CompleteResetValues>({ resolver: zodResolver(schema), defaultValues: { newPassword: '', confirmPassword: '' } });
  const password = useWatch({ control, name: 'newPassword' }) ?? '';

  if (done) return <PasswordChanged email={details.email} />;

  const onSubmit = handleSubmit((values) => {
    setFormError(null);
    complete.mutate(
      { token, newPassword: values.newPassword },
      {
        onSuccess: () => setDone(true),
        onError: (error) => {
          if (toApiError(error).status === 404) {
            onInvalid();
            return;
          }
          if (applyKnownFieldErrors(error, setError, RESET_FIELDS, { password: 'newPassword' })) return;
          setFormError(rateLimitMessage(error) ?? errorMessage(error));
        },
      },
    );
  });

  return (
    <>
      <AuthCardHeader
        id={TITLE_ID}
        icon={<KeyRound size={20} />}
        eyebrow="Password reset"
        title="Choose a new password"
        description="Signing in again is required everywhere once the password is changed."
      />
      <dl className="auth-summary" aria-label="Reset link details">
        <div className="span">
          <dt>Account</dt>
          <dd>{details.email}</dd>
        </div>
        <div className="span">
          <dt>Link expires</dt>
          <dd>
            {formatDateTime(details.expiresAt)} ({formatRelative(details.expiresAt)})
          </dd>
        </div>
      </dl>
      <form onSubmit={onSubmit} noValidate aria-labelledby={TITLE_ID}>
        {/* Lets password managers associate the new password with the right account. */}
        <input type="email" name="username" autoComplete="username" value={details.email} readOnly hidden />
        <PasswordField
          label="New password"
          autoComplete="new-password"
          required
          describedBy={checklistId}
          error={errors.newPassword?.message}
          {...register('newPassword')}
        />
        <PasswordChecklist id={checklistId} password={password} email={details.email} />
        <PasswordField label="Confirm new password" autoComplete="new-password" required error={errors.confirmPassword?.message} {...register('confirmPassword')} />
        <FormAlert message={formError} />
        <button type="submit" className="gold-btn full" disabled={complete.isPending} aria-busy={complete.isPending || undefined}>
          <KeyRound size={15} aria-hidden="true" /> {complete.isPending ? 'Saving…' : 'Set new password'}
        </button>
      </form>
      <div className="auth-links">
        <Link to="/login">Back to sign in</Link>
      </div>
    </>
  );
}

function ResetFlow({ token }: { token: string | null }) {
  const [invalid, setInvalid] = useState(!token || token.trim() === '' || token.length > 512);
  const details = useResetToken(invalid ? null : token);
  const failure = details.isError ? toApiError(details.error) : null;

  if (invalid || !token || (failure && failure.status !== 0 && failure.status < 500)) {
    return (
      <InvalidLinkState
        id={TITLE_ID}
        title="This reset link is invalid or has expired"
        actions={
          <>
            <Link to="/forgot-password">Request a new link</Link>
            <Link to="/login">Back to sign in</Link>
          </>
        }
      >
        Reset links work once and expire 2 hours after an administrator issues them. Request a new one and your administrator will hand it to you.
      </InvalidLinkState>
    );
  }
  if (failure) return <LinkCheckFailed id={TITLE_ID} onRetry={() => void details.refetch()} retrying={details.isFetching} />;
  if (!details.data) return <AuthLoading label="Checking your reset link…" />;
  return <ResetForm token={token} details={details.data} onInvalid={() => setInvalid(true)} />;
}

/** /reset-password?token= — validates the single-use reset link and sets a new password (spec §2, §4). */
export default function ResetPasswordPage() {
  const [token] = useUrlToken('token');
  return (
    <AuthLayout title="Reset password" description="Choose a new SA password with your single-use reset link." labelledBy={TITLE_ID}>
      <ResetFlow token={token} />
    </AuthLayout>
  );
}
