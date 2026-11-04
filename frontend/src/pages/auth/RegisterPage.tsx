import { useId, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link } from 'react-router-dom';
import { CheckCircle2, LogIn, MailCheck, Send, UserPlus } from 'lucide-react';
import { useInvitation, useRegister, useRequestAccess } from '../../api/accounts';
import { TextareaField, TextField, textOrNull } from '../../components/FormField';
import { errorMessage, toApiError } from '../../lib/api';
import { accessRequestSchema, registerSchema, type AccessRequestValues, type RegisterValues } from '../../lib/accountSchemas';
import type { InvitationDetails } from '../../lib/accountTypes';
import { roleLabel } from '../../lib/constants';
import { formatDateTime, formatRelative } from '../../lib/format';
import { AuthCardHeader, AuthLayout, AuthLoading } from './AuthLayout';
import { FormAlert, InvalidLinkState, LinkCheckFailed } from './AuthStates';
import { applyKnownFieldErrors, rateLimitMessage, useFocusOnMount, useUrlToken } from './authUtils';
import { PasswordChecklist, PasswordField } from './PasswordField';

const TITLE_ID = 'register-title';
const ACCESS_FIELDS = ['fullName', 'email', 'department', 'jobTitle', 'reason'] as const;
const REGISTER_FIELDS = ['fullName', 'password'] as const;

// ------------------------------------------------------------------ request access
function RequestReceived() {
  const headingRef = useFocusOnMount<HTMLHeadingElement>();
  return (
    <>
      <AuthCardHeader
        id={TITLE_ID}
        headingRef={headingRef}
        icon={<MailCheck size={20} />}
        tone="success"
        eyebrow="Request received"
        title="Thank you — your request is in"
        description="If access can be granted for this address, an SA administrator will review your request."
      />
      <ol className="auth-steps" aria-label="What happens next">
        <li>An administrator reviews the request. Accounts are only ever created by administrators.</li>
        <li>
          If it is approved, the administrator gives you a personal, single-use invitation link through a trusted channel — in person, by phone or
          your organisation&apos;s secure messaging. If e-mail is configured for this workspace, it is also e-mailed to you.
        </li>
        <li>Open the link (valid for 7 days) to set your name and password, then sign in.</li>
      </ol>
      <div className="auth-links">
        <Link to="/">Back to home</Link>
        <Link to="/login">Go to sign in</Link>
      </div>
    </>
  );
}

function RequestAccessForm() {
  const request = useRequestAccess();
  const [done, setDone] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<AccessRequestValues>({
    resolver: zodResolver(accessRequestSchema),
    defaultValues: { fullName: '', email: '', department: '', jobTitle: '', reason: '' },
  });
  const reason = useWatch({ control, name: 'reason' }) ?? '';

  if (done) return <RequestReceived />;

  const onSubmit = handleSubmit((values) => {
    setFormError(null);
    request.mutate(
      {
        fullName: values.fullName,
        email: values.email,
        department: textOrNull(values.department),
        jobTitle: textOrNull(values.jobTitle),
        reason: values.reason,
      },
      {
        onSuccess: () => setDone(true),
        onError: (error) => {
          if (applyKnownFieldErrors(error, setError, ACCESS_FIELDS)) return;
          setFormError(rateLimitMessage(error) ?? errorMessage(error));
        },
      },
    );
  });

  return (
    <>
      <AuthCardHeader
        id={TITLE_ID}
        icon={<UserPlus size={20} />}
        eyebrow="Request access"
        title="Ask for an SA account"
        description="There is no open sign-up: an administrator reviews every request and, if approved, gives you a personal invitation link."
      />
      <form onSubmit={onSubmit} noValidate aria-labelledby={TITLE_ID}>
        <TextField label="Full name" autoComplete="name" required error={errors.fullName?.message} {...register('fullName')} />
        <TextField
          label="Work e-mail"
          type="email"
          autoComplete="email"
          required
          hint="Use your corporate address; it becomes your sign-in name."
          error={errors.email?.message}
          {...register('email')}
        />
        <div className="form-row">
          <TextField label="Department" error={errors.department?.message} {...register('department')} />
          <TextField label="Job title" autoComplete="organization-title" error={errors.jobTitle?.message} {...register('jobTitle')} />
        </div>
        <TextareaField
          label="Why do you need access?"
          required
          maxLength={1000}
          hint={`${reason.trim().length}/1000 · at least 10 characters. Only SA administrators see this.`}
          error={errors.reason?.message}
          {...register('reason')}
        />
        <FormAlert message={formError} />
        <button type="submit" className="gold-btn full" disabled={request.isPending} aria-busy={request.isPending || undefined}>
          <Send size={15} aria-hidden="true" /> {request.isPending ? 'Sending request…' : 'Send request'}
        </button>
      </form>
      <div className="auth-links">
        <span>
          Already have an account? <Link to="/login">Sign in</Link>
        </span>
        <Link to="/forgot-password">Forgot password?</Link>
      </div>
    </>
  );
}

// ------------------------------------------------------------------ invitation
function AccountReady({ email }: { email: string }) {
  const headingRef = useFocusOnMount<HTMLHeadingElement>();
  return (
    <>
      <AuthCardHeader
        id={TITLE_ID}
        headingRef={headingRef}
        icon={<CheckCircle2 size={20} />}
        tone="success"
        eyebrow="Account created"
        title="Your account is ready"
        description={
          <>
            Sign in as <b className="text-ivory">{email}</b> with the password you just set. The invitation link can no longer be used.
          </>
        }
      />
      <Link to={`/login?email=${encodeURIComponent(email)}&reason=registered`} className="gold-btn full">
        <LogIn size={15} aria-hidden="true" /> Sign in
      </Link>
    </>
  );
}

function InvitationSummary({ invitation }: { invitation: InvitationDetails }) {
  return (
    <dl className="auth-summary" aria-label="Invitation details">
      <div className="span">
        <dt>E-mail</dt>
        <dd>{invitation.email}</dd>
      </div>
      <div>
        <dt>Role</dt>
        <dd>{roleLabel(invitation.role)}</dd>
      </div>
      <div>
        <dt>Department</dt>
        <dd>{invitation.departmentName ?? '—'}</dd>
      </div>
      <div>
        <dt>Organisation</dt>
        <dd>{invitation.organisationName ?? '—'}</dd>
      </div>
      <div>
        <dt>Link expires</dt>
        <dd title={formatDateTime(invitation.expiresAt)}>
          {formatDateTime(invitation.expiresAt)} ({formatRelative(invitation.expiresAt)})
        </dd>
      </div>
    </dl>
  );
}

function RegistrationForm({ token, invitation, onInvalid }: { token: string; invitation: InvitationDetails; onInvalid: () => void }) {
  const registerAccount = useRegister();
  const checklistId = useId();
  const [created, setCreated] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const schema = useMemo(() => registerSchema(invitation.email), [invitation.email]);
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<RegisterValues>({
    resolver: zodResolver(schema),
    defaultValues: { fullName: invitation.fullName ?? '', password: '', confirmPassword: '' },
  });
  const password = useWatch({ control, name: 'password' }) ?? '';

  if (created) return <AccountReady email={created} />;

  const onSubmit = handleSubmit((values) => {
    setFormError(null);
    registerAccount.mutate(
      { token, fullName: values.fullName, password: values.password },
      {
        onSuccess: (data) => setCreated(data?.email || invitation.email),
        onError: (error) => {
          const status = toApiError(error).status;
          if (status === 404) {
            onInvalid();
            return;
          }
          if (status === 409) {
            setFormError('An account already exists for this e-mail address. Sign in, or use “Forgot password?” if you cannot.');
            return;
          }
          if (applyKnownFieldErrors(error, setError, REGISTER_FIELDS, { newPassword: 'password' })) return;
          setFormError(rateLimitMessage(error) ?? errorMessage(error));
        },
      },
    );
  });

  return (
    <>
      <AuthCardHeader
        id={TITLE_ID}
        icon={<UserPlus size={20} />}
        eyebrow="Invitation"
        title="Set up your account"
        description="Your administrator invited you to SA. Choose the name shown to colleagues and a password."
      />
      <InvitationSummary invitation={invitation} />
      <form onSubmit={onSubmit} noValidate aria-labelledby={TITLE_ID}>
        <TextField label="Full name" autoComplete="name" required error={errors.fullName?.message} {...register('fullName')} />
        <PasswordField
          label="Password"
          autoComplete="new-password"
          required
          describedBy={checklistId}
          error={errors.password?.message}
          {...register('password')}
        />
        <PasswordChecklist id={checklistId} password={password} email={invitation.email} />
        <PasswordField label="Confirm password" autoComplete="new-password" required error={errors.confirmPassword?.message} {...register('confirmPassword')} />
        <FormAlert message={formError} />
        <button type="submit" className="gold-btn full" disabled={registerAccount.isPending} aria-busy={registerAccount.isPending || undefined}>
          <UserPlus size={15} aria-hidden="true" /> {registerAccount.isPending ? 'Creating account…' : 'Create account'}
        </button>
      </form>
      <div className="auth-links">
        <span>
          Already registered? <Link to="/login">Sign in</Link>
        </span>
      </div>
    </>
  );
}

function InvitationFlow({ token, onRequestAccess }: { token: string; onRequestAccess: () => void }) {
  const [invalid, setInvalid] = useState(token.trim() === '' || token.length > 512);
  const invitation = useInvitation(invalid ? null : token);
  const failure = invitation.isError ? toApiError(invitation.error) : null;

  if (invalid || (failure && failure.status !== 0 && failure.status < 500)) {
    return (
      <InvalidLinkState
        id={TITLE_ID}
        title="This invitation link is invalid or has expired"
        actions={
          <>
            <button type="button" className="link-btn" onClick={onRequestAccess}>
              Request access instead
            </button>
            <Link to="/login">Go to sign in</Link>
          </>
        }
      >
        Invitation links work once and expire after 7 days. Ask the administrator who invited you for a new link, or send an access request.
      </InvalidLinkState>
    );
  }
  if (failure) return <LinkCheckFailed id={TITLE_ID} onRetry={() => void invitation.refetch()} retrying={invitation.isFetching} />;
  if (!invitation.data) return <AuthLoading label="Checking your invitation…" />;
  return <RegistrationForm token={token} invitation={invitation.data} onInvalid={() => setInvalid(true)} />;
}

/**
 * /register — without `?invite` a request-access form; with `?invite=<token>` the invitation is validated and the
 * invitee sets name and password (spec §2, §4). The token is removed from the address bar on arrival.
 */
export default function RegisterPage() {
  const [token, forgetToken] = useUrlToken('invite');
  const inviteMode = token !== null;
  return (
    <AuthLayout
      title={inviteMode ? 'Accept invitation' : 'Request access'}
      description="Request access to SA or accept an administrator's invitation."
      wide
      labelledBy={TITLE_ID}
    >
      {inviteMode ? <InvitationFlow token={token} onRequestAccess={forgetToken} /> : <RequestAccessForm />}
    </AuthLayout>
  );
}
