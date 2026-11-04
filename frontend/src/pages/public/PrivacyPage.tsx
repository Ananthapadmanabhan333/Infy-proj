import { Link } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import { ContentPage, type ContentSection } from '../../components/public/ContentPage';
import { usePageMeta } from '../../hooks/usePageMeta';

const SECTIONS: ContentSection[] = [
  { id: 'responsibility', label: 'Who is responsible' },
  { id: 'data', label: 'What is stored' },
  { id: 'cookies', label: 'Cookies & browser storage' },
  { id: 'sharing', label: 'Data leaving the platform' },
  { id: 'retention', label: 'Retention' },
  { id: 'security', label: 'Security' },
  { id: 'contact', label: 'Questions' },
];

export default function PrivacyPage() {
  usePageMeta(
    'SA | Privacy Policy',
    'What SA stores — accounts and roles, operational records, the audit and AI interaction logs — and its single HttpOnly session cookie. No tracking cookies, no third-party analytics.',
  );
  return (
    <ContentPage
      kicker="Privacy"
      title="Privacy Policy"
      lead="What the SA platform stores about you, why, and what it deliberately does not do."
      sections={SECTIONS}
    >
      <section id="responsibility" aria-labelledby="responsibility-title">
        <h2 id="responsibility-title">Who is responsible</h2>
        <p>
          SA is software that an organisation runs for its own employees. This page describes what the software
          stores and how. The organisation operating this deployment (see <Link to="/support">Support</Link>) decides how it
          is used and is responsible for the data in it, including how long it is kept.
        </p>
      </section>

      <section id="data" aria-labelledby="data-title">
        <h2 id="data-title">What is stored</h2>
        <h3>Accounts and roles</h3>
        <p>
          Name, work e-mail address, role, department, job title and optional phone number; a BCrypt hash of your password
          (never the password itself); failed sign-in count, lockout time and last sign-in; your notification preferences.
        </p>
        <h3>Access and password-reset requests</h3>
        <p>
          Name, e-mail address, department, job title and reason you give, the administrator&apos;s decision and the IP
          address the request came from. Invitation and reset links are stored only as SHA-256 hashes.
        </p>
        <h3>Operational records</h3>
        <p>
          Incidents, service requests, comments, attachments, approvals, services, SLA clocks and escalations, workflows and
          their runs, runbooks and executions, knowledge articles and ratings, announcements and notifications — including
          who created or changed them and when.
        </p>
        <h3>Audit log</h3>
        <p>
          An append-only record of sign-ins (successful and failed), access denials, changes, AI actions and exports, with
          the acting user, IP address and browser user agent. The application cannot edit or delete audit entries.
        </p>
        <h3>AI interaction log</h3>
        <p>
          For each SA AI request: the feature used, the user, its subject and related incident, whether the configured
          LLM or the deterministic engine answered, the provider and model, latency and outcome.
        </p>
        <h3>Sessions</h3>
        <p>Each refresh token is stored as a SHA-256 hash with its expiry, browser user agent and IP address.</p>
      </section>

      <section id="cookies" aria-labelledby="cookies-title">
        <h2 id="cookies-title">Cookies &amp; browser storage</h2>
        <ul>
          <li>
            <strong>Exactly one cookie:</strong> an HttpOnly refresh cookie (<code>nexus_refresh</code>), limited to the{' '}
            <code>/api/auth</code> path, used only to keep you signed in. Signing out clears it.
          </li>
          <li>The short-lived access token is held in memory and never written to local or session storage.</li>
          <li>
            <strong>No tracking or advertising cookies, and no third-party analytics</strong> or tracking scripts.
          </li>
          <li>
            The web interface loads its typefaces from Google Fonts, so your browser requests font files from Google&apos;s
            servers.
          </li>
        </ul>
      </section>

      <section id="sharing" aria-labelledby="sharing-title">
        <h2 id="sharing-title">Data leaving the platform</h2>
        <ul>
          <li>
            <strong>AI provider (optional).</strong> When the operating organisation configures an LLM provider, the record
            text needed for a request — for example an incident&apos;s title and description, similar incidents and
            knowledge excerpts — is sent to that provider to produce the answer. With no provider configured, every AI
            feature runs on the built-in deterministic engine and nothing is sent.
          </li>
          <li>
            <strong>Integrations (optional).</strong> Webhooks, Slack and Microsoft Teams channels configured by
            administrators receive the event details they are set up to send.
          </li>
          <li>
            <strong>Exports.</strong> Authorised users can export reports and logs (CSV / PDF); every export is audited.
          </li>
        </ul>
      </section>

      <section id="retention" aria-labelledby="retention-title">
        <h2 id="retention-title">Retention</h2>
        <p>
          The software does not delete operational records or audit entries on a schedule. Retention periods, backups,
          archiving and requests to correct or delete personal data are the responsibility of the organisation operating
          this deployment.
        </p>
      </section>

      <section id="security" aria-labelledby="privacy-security-title">
        <h2 id="privacy-security-title">Security</h2>
        <div className="pub-callout">
          <ShieldCheck size={18} aria-hidden="true" />
          <p>
            Server-side role checks on every request, short-lived in-memory access tokens, rotating refresh tokens with reuse
            detection, account lockout, per-IP rate limits, validated uploads, encrypted integration secrets and an
            append-only audit log. Details are in the <Link to="/docs#security">documentation</Link>.
          </p>
        </div>
      </section>

      <section id="contact" aria-labelledby="contact-title">
        <h2 id="contact-title">Questions</h2>
        <p>
          For questions about your data, or to exercise your rights, contact the organisation operating this deployment
          through the details on the <Link to="/support">Support page</Link>.
        </p>
      </section>
    </ContentPage>
  );
}
