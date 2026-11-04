import { Link } from 'react-router-dom';
import { ArrowRight, Check, ShieldCheck } from 'lucide-react';
import { useAuth } from '../../hooks/useAuth';
import { BRAND } from '../../lib/brand';
import { BrandWordmark } from '../layout/BrandMark';
import { ABOUT, CAPABILITIES } from './content';
import { PublicLink } from './PublicLink';

/** "About SA" (footer → /#about): what the product is and the rules it follows. No figures about any deployment. */
function AboutSection() {
  return (
    <section id="about" className="pub-section pub-about" aria-labelledby="about-title">
      <div className="pub-container pub-about-grid">
        <div className="pub-about-intro">
          <BrandWordmark className="pub-about-word" label={null} />
          <h2 id="about-title">{ABOUT.title}</h2>
          <p className="pub-about-tagline">{BRAND.tagline}</p>
          <p>{ABOUT.lead}</p>
          <div className="pub-btn-row">
            <Link to="/docs" className="pub-btn pub-btn-outline">
              Read the documentation
            </Link>
            <PublicLink to="/support#contact" className="pub-btn pub-btn-outline">
              Contact
            </PublicLink>
          </div>
        </div>
        <ul className="pub-about-list">
          {ABOUT.principles.map((principle) => (
            <li key={principle.title}>
              <ShieldCheck size={20} strokeWidth={1.6} aria-hidden="true" />
              <span>
                <b>{principle.title}</b>
                <small>{principle.text}</small>
              </span>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

/**
 * Public explainers below the reference composition (lazy chunk): what each module does, from the README "Modules"
 * table and RELEASE3_SPEC §5. Anchor ids match the capability cards. No protected data.
 */
export default function PlatformExplainers() {
  const { status, user } = useAuth();
  const signedIn = status === 'authenticated' && Boolean(user);
  return (
    <>
      <section id="platform" className="pub-section pub-explainers" aria-labelledby="platform-title">
        <div className="pub-container">
          <div className="pub-section-head">
            <p className="pub-kicker">Inside the platform</p>
            <h2 id="platform-title">One workspace for the whole operations lifecycle</h2>
            <p>What each module does — the same modules you reach from the workspace sidebar, each gated by role on the server.</p>
          </div>
          <div className="pub-explainer-list">
            {CAPABILITIES.map((capability, index) => {
              const Icon = capability.icon;
              return (
                <article key={capability.id} id={capability.id} className="pub-explainer" aria-labelledby={`${capability.id}-title`}>
                  <div className="pub-explainer-head">
                    <span className="pub-explainer-icon" aria-hidden="true">
                      <Icon size={26} strokeWidth={1.5} />
                    </span>
                    <div>
                      <p className="pub-explainer-index">{String(index + 1).padStart(2, '0')}</p>
                      <h3 id={`${capability.id}-title`}>{capability.title}</h3>
                    </div>
                  </div>
                  <p className="pub-explainer-summary">{capability.summary}</p>
                  <ul className="pub-explainer-points">
                    {capability.points.map((point) => (
                      <li key={point}>
                        <Check size={15} aria-hidden="true" />
                        <span>{point}</span>
                      </li>
                    ))}
                  </ul>
                  <p className="pub-explainer-route">
                    In the workspace: <code>{capability.route}</code>
                  </p>
                </article>
              );
            })}
            <aside className="pub-explainer pub-explainer-cta" aria-labelledby="platform-cta-title">
              <p className="pub-kicker">Get started</p>
              <h3 id="platform-cta-title">Ready to work in {BRAND.name}?</h3>
              <p>
                Accounts are created by your administrators — there is no open sign-up. Ask for access, or sign in if you
                already have an account.
              </p>
              <div className="pub-btn-row">
                <Link to={signedIn ? '/dashboard' : '/login'} className="pub-btn pub-btn-gold">
                  {signedIn ? 'Open Workspace' : 'Enter Workspace'} <ArrowRight size={16} aria-hidden="true" />
                </Link>
                <Link to="/docs" className="pub-btn pub-btn-outline">
                  Read the docs
                </Link>
              </div>
            </aside>
          </div>
        </div>
      </section>
      <AboutSection />
    </>
  );
}
