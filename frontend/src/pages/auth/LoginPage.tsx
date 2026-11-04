import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { AlertTriangle, CheckCircle2, FlaskConical, Info, LogIn } from 'lucide-react';
import { useDemoAccounts } from '../../api/auth';
import { RoleBadge } from '../../components/Badge';
import { BrandLoader } from '../../components/BrandLoader';
import { TextField } from '../../components/FormField';
import { useAuth } from '../../hooks/useAuth';
import { applyFieldErrors, toApiError } from '../../lib/api';
import { emailSchema, loginSchema, type LoginValues } from '../../lib/schemas';
import { AuthCardHeader, AuthLayout } from './AuthLayout';
import { safeNext } from './authUtils';
import { PasswordField } from './PasswordField';

export { safeNext };

/** Notices for `?reason=` (spec §1) plus the hand-offs from registration and password reset. */
const NOTICES: Record<string, { text: string; tone: 'info' | 'green' }> = {
  expired: { text: 'Your session expired. Please sign in again.', tone: 'info' },
  'signed-out': { text: 'You have signed out.', tone: 'green' },
  registered: { text: 'Your account is ready. Sign in with the password you just set.', tone: 'green' },
  'password-reset': { text: 'Your password has been changed. Sign in with your new password.', tone: 'green' },
};

function loginErrorMessage(error: unknown): string {
  const apiError = toApiError(error);
  switch (apiError.status) {
    case 401:
      return apiError.message && apiError.message !== 'Your session has expired. Please sign in again.'
        ? apiError.message
        : 'Invalid email or password.';
    case 423:
      return apiError.message || 'This account is locked. Try again in 15 minutes or contact an administrator.';
    case 429:
      return apiError.retryAfterSeconds
        ? `Too many sign-in attempts. Try again in ${apiError.retryAfterSeconds} seconds.`
        : apiError.message || 'Too many sign-in attempts. Please wait a minute and try again.';
    default:
      return apiError.message;
  }
}

function DemoAccounts({ onPick }: { onPick: (email: string, password: string | null) => void }) {
  const demo = useDemoAccounts();
  if (!demo.data?.enabled || !demo.data.accounts.length) return null;
  return (
    <section className="demo-accounts" aria-labelledby="demo-accounts-title">
      <div className="flex items-center gap-2">
        <FlaskConical size={14} className="gold-text" />
        <h2 id="demo-accounts-title" className="m-0 text-[12px]">
          Demo accounts
        </h2>
        <span className="badge demo">DEMO</span>
      </div>
      <p className="mt-1 text-[10px] leading-relaxed text-muted">
        Seeded demonstration users for evaluating SA.{' '}
        {demo.data.password ? 'Selecting one fills the email and password.' : 'Selecting one fills the email; use the demo password you were given.'}
      </p>
      {demo.data.accounts.map((account) => (
        <button key={account.email} type="button" onClick={() => onPick(account.email, demo.data?.password ?? null)}>
          <div className="min-w-0 flex-1">
            <b className="block truncate">{account.fullName}</b>
            <small className="block truncate text-[10px] text-muted">
              {account.email}
              {account.departmentName ? ` · ${account.departmentName}` : ''}
            </small>
          </div>
          <RoleBadge role={account.role} />
        </button>
      ))}
    </section>
  );
}

/**
 * /login — e-mail + password sign-in (spec §1) with "Remember me" (RELEASE3_SPEC §4: `rememberMe` true → persistent
 * refresh cookie, false → browser-session cookie). Signed-in visitors go straight to the safe `next` or /dashboard.
 */
export default function LoginPage() {
  const { status, user, login } = useAuth();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const next = safeNext(params.get('next'));
  const notice = NOTICES[params.get('reason') ?? ''];
  const prefill = emailSchema.safeParse(params.get('email') ?? '');
  const prefilledEmail = prefill.success ? prefill.data : '';
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setValue,
    setFocus,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: prefilledEmail, password: '', rememberMe: false } });

  const ready = status !== 'loading' && !user;
  useEffect(() => {
    if (ready) setFocus(prefilledEmail ? 'password' : 'email');
    // Only on first render of the form.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  if (status === 'loading') return <BrandLoader />;
  if (user) return <Navigate to={next} replace />;

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await login(values.email.trim(), values.password, Boolean(values.rememberMe));
      navigate(next, { replace: true });
    } catch (error) {
      if (!applyFieldErrors(error, setError)) setFormError(loginErrorMessage(error));
    }
  });

  return (
    <AuthLayout title="Sign in" description="Sign in to SA — the enterprise operations workspace." labelledBy="login-title" aside="showcase">
      <AuthCardHeader id="login-title" eyebrow="Secure sign-in" title="Sign in to SA" description="Welcome back. Use your corporate e-mail address." />
      {notice && (
        <div className={`callout ${notice.tone}`} role="status">
          {notice.tone === 'green' ? <CheckCircle2 size={16} aria-hidden="true" /> : <Info size={16} aria-hidden="true" />}
          <div>{notice.text}</div>
        </div>
      )}
      <form onSubmit={onSubmit} noValidate aria-labelledby="login-title">
        <TextField label="Email" type="email" autoComplete="username" required error={errors.email?.message} {...register('email')} />
        <PasswordField label="Password" autoComplete="current-password" required error={errors.password?.message} {...register('password')} />
        <div className="auth-row">
          <label className="auth-remember">
            <input type="checkbox" aria-describedby="remember-hint" {...register('rememberMe')} />
            <span>Remember me</span>
          </label>
          <Link to="/forgot-password" className="auth-inline-link">
            Forgot password?
          </Link>
        </div>
        <p id="remember-hint" className="auth-remember-hint">
          Keeps you signed in on this device. Leave it unticked on shared computers — the session then ends when the browser closes.
        </p>
        {formError && (
          <div className="callout red" role="alert">
            <AlertTriangle size={16} aria-hidden="true" />
            <div>{formError}</div>
          </div>
        )}
        <button type="submit" className="gold-btn full" disabled={isSubmitting} aria-busy={isSubmitting || undefined}>
          <LogIn size={15} aria-hidden="true" /> {isSubmitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <div className="auth-links">
        <span>
          New to SA? <Link to="/register">Request access</Link>
        </span>
        <Link to="/support">Need help?</Link>
      </div>
      <DemoAccounts
        onPick={(email, password) => {
          setValue('email', email, { shouldValidate: true });
          setValue('password', password ?? '', { shouldValidate: Boolean(password) });
          setFocus('password');
        }}
      />
    </AuthLayout>
  );
}
