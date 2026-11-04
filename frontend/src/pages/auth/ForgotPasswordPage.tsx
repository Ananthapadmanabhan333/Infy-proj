import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link } from 'react-router-dom';
import { KeyRound, MailCheck, Send } from 'lucide-react';
import { useForgotPassword, usePublicInfo } from '../../api/accounts';
import { TextField } from '../../components/FormField';
import { errorMessage } from '../../lib/api';
import { forgotPasswordSchema, type ForgotPasswordValues } from '../../lib/accountSchemas';
import { AuthCardHeader, AuthLayout } from './AuthLayout';
import { FormAlert } from './AuthStates';
import { applyKnownFieldErrors, rateLimitMessage, useFocusOnMount } from './authUtils';

const TITLE_ID = 'forgot-title';

function SupportContact() {
  const info = usePublicInfo();
  const supportEmail = info.data?.supportEmail;
  if (!supportEmail) return null;
  return (
    <p className="mt-3 text-[12px] leading-relaxed text-muted">
      Need help sooner? Contact{' '}
      <a className="auth-inline-link" href={`mailto:${supportEmail}`}>
        {supportEmail}
      </a>
      .
    </p>
  );
}

function ResetRequested() {
  const headingRef = useFocusOnMount<HTMLHeadingElement>();
  return (
    <>
      <AuthCardHeader
        id={TITLE_ID}
        headingRef={headingRef}
        icon={<MailCheck size={20} />}
        tone="success"
        eyebrow="Request received"
        title="An administrator will help you"
        description="If an active account exists for that address, an SA administrator has been asked to create a password reset link."
      />
      <ol className="auth-steps" aria-label="What happens next">
        <li>If e-mail is configured for this workspace, the link is also e-mailed to you; otherwise no message arrives in your inbox.</li>
        <li>The administrator gives you a single-use reset link through a trusted channel, such as in person or by phone.</li>
        <li>Open the link within 2 hours of it being issued and choose a new password.</li>
      </ol>
      <SupportContact />
      <div className="auth-links">
        <Link to="/login">Back to sign in</Link>
        <Link to="/">Back to home</Link>
      </div>
    </>
  );
}

/** /forgot-password — records a reset request for administrators; the response is generic (spec §2, §4). */
export default function ForgotPasswordPage() {
  const forgot = useForgotPassword();
  const [done, setDone] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ForgotPasswordValues>({ resolver: zodResolver(forgotPasswordSchema), defaultValues: { email: '' } });

  const onSubmit = handleSubmit((values) => {
    setFormError(null);
    forgot.mutate(
      { email: values.email },
      {
        onSuccess: () => setDone(true),
        onError: (error) => {
          if (applyKnownFieldErrors(error, setError, ['email'])) return;
          setFormError(rateLimitMessage(error) ?? errorMessage(error));
        },
      },
    );
  });

  return (
    <AuthLayout title="Forgot password" description="Ask an SA administrator for a password reset link." labelledBy={TITLE_ID}>
      {done ? (
        <ResetRequested />
      ) : (
        <>
          <AuthCardHeader
            id={TITLE_ID}
            icon={<KeyRound size={20} />}
            eyebrow="Password recovery"
            title="Forgot your password?"
            description="Enter the e-mail you sign in with. An administrator reviews the request and gives you a reset link."
          />
          <form onSubmit={onSubmit} noValidate aria-labelledby={TITLE_ID}>
            <TextField label="Email" type="email" autoComplete="username" required autoFocus error={errors.email?.message} {...register('email')} />
            <FormAlert message={formError} />
            <button type="submit" className="gold-btn full" disabled={forgot.isPending} aria-busy={forgot.isPending || undefined}>
              <Send size={15} aria-hidden="true" /> {forgot.isPending ? 'Sending…' : 'Request a reset link'}
            </button>
          </form>
          <div className="auth-links">
            <span>
              Remembered it? <Link to="/login">Back to sign in</Link>
            </span>
            <Link to="/register">Request access</Link>
          </div>
        </>
      )}
    </AuthLayout>
  );
}
