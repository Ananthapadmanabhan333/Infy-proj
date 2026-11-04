import type { ReactNode, RefObject } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, History, KeyRound, ShieldCheck, UserCheck } from 'lucide-react';
import { BrandLockup } from '../../components/layout/BrandMark';
import { BRAND, pageTitle } from '../../lib/brand';
import { AuthShowcase } from './AuthShowcase';
import { usePageMeta } from './authUtils';
import './auth.css';

interface AuthLayoutProps {
  /** Page name for the document title ("Sign in" → "SA | Sign in"). */
  title: string;
  description?: string;
  /** Wider card (registration forms). */
  wide?: boolean;
  /** Accessible name of the card (id of its heading). */
  labelledBy?: string;
  /** Brand panel content under the message: the account facts (default) or the illustrative product visual. */
  aside?: 'facts' | 'showcase';
  children: ReactNode;
}

function Logo() {
  return (
    <Link to="/" className="auth-shell-logo" aria-label={`${BRAND.name} — ${BRAND.subtitle}, home`}>
      <BrandLockup label={null} />
    </Link>
  );
}

function AccountFacts() {
  return (
    <ul className="auth-shell-facts">
      <li>
        <UserCheck size={16} aria-hidden="true" /> Administrator-approved accounts — no open self-registration
      </li>
      <li>
        <KeyRound size={16} aria-hidden="true" /> Roles with server-enforced access
      </li>
      <li>
        <History size={16} aria-hidden="true" /> Append-only audit trail of account changes
      </li>
    </ul>
  );
}

/**
 * Split-screen frame of the public account pages: brand panel (hidden below 960 px) and a centred form card,
 * with "Back to home" and the legal links.
 */
export function AuthLayout({ title, description, wide, labelledBy, aside = 'facts', children }: AuthLayoutProps) {
  usePageMeta(pageTitle(title), description);
  return (
    <div className={`auth-shell ${aside === 'showcase' ? 'with-showcase' : ''}`.trim()}>
      <aside className="auth-shell-brand" aria-label={`About ${BRAND.name}`}>
        <Logo />
        <div className="auth-shell-pitch">
          <span className="auth-shell-eyebrow">Enterprise Operations</span>
          <p className="auth-shell-headline">
            Intelligent Service Management. <span>Unified Operations.</span>
          </p>
          <p className="auth-shell-lead">
            Incidents, service requests, problems, changes, SLAs, automation and knowledge in one command center — with every AI
            suggestion labelled by its source.
          </p>
          {aside === 'showcase' ? <AuthShowcase /> : <AccountFacts />}
        </div>
        <p className="auth-shell-note">
          <ShieldCheck size={14} aria-hidden="true" /> Sessions use short-lived access tokens and an HttpOnly refresh cookie.
        </p>
      </aside>
      <div className="auth-shell-main">
        <div className="auth-shell-top">
          <Logo />
          <Link to="/" className="auth-shell-back">
            <ArrowLeft size={14} aria-hidden="true" /> Back to home
          </Link>
        </div>
        <main className="auth-shell-content">
          <section className={`auth-shell-card ${wide ? 'wide' : ''}`.trim()} aria-labelledby={labelledBy}>
            {children}
          </section>
        </main>
        <footer className="auth-shell-footer">
          <nav aria-label="Legal">
            <Link to="/privacy">Privacy</Link>
            <Link to="/terms">Terms</Link>
            <Link to="/status">Status</Link>
          </nav>
          <small>
            © {new Date().getFullYear()} {BRAND.name} · {BRAND.subtitle}
          </small>
        </footer>
      </div>
    </div>
  );
}

interface AuthCardHeaderProps {
  id: string;
  eyebrow?: string;
  title: ReactNode;
  description?: ReactNode;
  icon?: ReactNode;
  tone?: 'gold' | 'success' | 'critical';
  /** Focus target for success / invalid-link states (the heading gets tabIndex -1). */
  headingRef?: RefObject<HTMLHeadingElement>;
}

export function AuthCardHeader({ id, eyebrow, title, description, icon, tone = 'gold', headingRef }: AuthCardHeaderProps) {
  return (
    <header className="auth-card-head">
      {icon && (
        <div className={`auth-card-icon ${tone === 'gold' ? '' : tone}`.trim()} aria-hidden="true">
          {icon}
        </div>
      )}
      {eyebrow && <span className="auth-shell-eyebrow">{eyebrow}</span>}
      <h1 id={id} ref={headingRef} tabIndex={headingRef ? -1 : undefined}>
        {title}
      </h1>
      {description && <p>{description}</p>}
    </header>
  );
}

export function AuthLoading({ label }: { label: string }) {
  return (
    <div className="auth-loading" role="status">
      <span className="auth-spinner" aria-hidden="true" />
      {label}
    </div>
  );
}
