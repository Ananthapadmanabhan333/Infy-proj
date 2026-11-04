import { Link } from 'react-router-dom';
import { Activity, ArrowRight, BookOpen, KeyRound, LifeBuoy, Mail, UserPlus } from 'lucide-react';
import { usePublicInfo } from '../../api/publicInfo';
import { ContentPage } from '../../components/public/ContentPage';
import { StatusPill } from '../../components/public/StatusPill';
import { useAuth } from '../../hooks/useAuth';
import { usePageMeta } from '../../hooks/usePageMeta';

function ContactCard() {
  const info = usePublicInfo();
  const email = info.data?.supportEmail?.trim();
  const validEmail = email && /^[^\s@]+@[^\s@]+$/.test(email) ? email : null;

  return (
    <section id="contact" className="pub-contact" aria-labelledby="contact-title" aria-busy={info.isPending}>
      <h2 id="contact-title">
        <Mail size={20} aria-hidden="true" className="pub-gold" /> Contact your administrators
      </h2>
      {info.isPending && <p>Loading the contact details published by this deployment…</p>}
      {info.data && (
        <>
          <p>
            This SA workspace is operated by <strong>{info.data.organisationName}</strong>. Support is provided by its
            own IT team.
          </p>
          <dl>
            <dt>Organisation</dt>
            <dd>{info.data.organisationName}</dd>
            <dt>Support e-mail</dt>
            <dd>{validEmail ? <a href={`mailto:${validEmail}`}>{validEmail}</a> : 'Not published — please use your usual IT service desk channel.'}</dd>
            {info.data.version && (
              <>
                <dt>Version</dt>
                <dd>{info.data.version}</dd>
              </>
            )}
          </dl>
          {validEmail && (
            <div className="pub-btn-row">
              <a href={`mailto:${validEmail}`} className="pub-btn pub-btn-gold">
                E-mail support <ArrowRight size={16} aria-hidden="true" />
              </a>
            </div>
          )}
        </>
      )}
      {info.isError && (
        <p role="status">
          Contact details are not available from this deployment right now. Please reach your organisation&apos;s IT service
          desk through your usual internal channel — or, if you can sign in, raise an incident or service request in the
          workspace.
        </p>
      )}
    </section>
  );
}

export default function SupportPage() {
  usePageMeta('SA | Support', 'Get help with SA: contact your administrators, recover your password, request access and check the platform status.');
  const { status, user } = useAuth();
  const signedIn = status === 'authenticated' && Boolean(user);

  return (
    <ContentPage
      kicker="Support"
      title="How can we help?"
      lead="SA is run by your organisation. Its administrators manage accounts, access and the service itself."
    >
      <ContactCard />
      <div className="pub-card-grid">
        <article className="pub-info-card">
          <LifeBuoy size={26} strokeWidth={1.6} aria-hidden="true" />
          <h2>Something not working?</h2>
          <p>Signed-in users report incidents and raise service requests from the workspace; the SLA clock starts straight away.</p>
          <Link to={signedIn ? '/dashboard' : '/login'}>{signedIn ? 'Open Workspace' : 'Sign in to report it'}</Link>
        </article>
        <article className="pub-info-card">
          <KeyRound size={26} strokeWidth={1.6} aria-hidden="true" />
          <h2>Can&apos;t sign in?</h2>
          <p>Request a password reset. An administrator creates a single-use reset link and hands it to you.</p>
          <Link to="/forgot-password">Forgot password</Link>
        </article>
        <article className="pub-info-card">
          <UserPlus size={26} strokeWidth={1.6} aria-hidden="true" />
          <h2>Need an account?</h2>
          <p>Accounts are administrator-controlled. Ask for access and a user administrator will review your request.</p>
          <Link to="/register">Request access</Link>
        </article>
        <article className="pub-info-card">
          <BookOpen size={26} strokeWidth={1.6} aria-hidden="true" />
          <h2>Learn the platform</h2>
          <p>Roles, modules, AI transparency and how sessions are secured.</p>
          <Link to="/docs">Read the documentation</Link>
        </article>
        <article className="pub-info-card">
          <Activity size={26} strokeWidth={1.6} aria-hidden="true" />
          <h2>Platform status</h2>
          <p>Live result of the public API health check (refreshed every 30 seconds).</p>
          <StatusPill />
          <Link to="/status">View the status page</Link>
        </article>
      </div>
    </ContentPage>
  );
}
