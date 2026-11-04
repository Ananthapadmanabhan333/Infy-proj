import { Link } from 'react-router-dom';
import { ArrowRight, Cpu, Play } from 'lucide-react';
import { useAuth } from '../../hooks/useAuth';
import { HERO_FACTS } from './content';
import { DashboardPreview } from './DashboardPreview';
import { Globe } from './Globe';
import { PublicLink } from './PublicLink';

/** Thin gold light streaks along the lower-left of the hero (reference ambience). */
function LightStreaks() {
  return (
    <svg className="pub-streaks" viewBox="0 0 900 260" preserveAspectRatio="none" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id="ps-stroke" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#D6B77A" stopOpacity="0" />
          <stop offset="0.45" stopColor="#D6B77A" stopOpacity="0.55" />
          <stop offset="1" stopColor="#F1DFBA" stopOpacity="0" />
        </linearGradient>
      </defs>
      <g fill="none" stroke="url(#ps-stroke)">
        <path d="M0 210 C 220 150, 430 245, 900 120" strokeWidth="1.2" />
        <path d="M0 232 C 260 170, 470 250, 900 150" strokeWidth="0.8" opacity="0.7" />
        <path d="M0 186 C 240 120, 420 220, 900 96" strokeWidth="0.6" opacity="0.5" />
        <path d="M0 250 C 300 205, 520 255, 900 178" strokeWidth="0.6" opacity="0.45" />
      </g>
    </svg>
  );
}

export function HeroSection() {
  const { status, user } = useAuth();
  const signedIn = status === 'authenticated' && Boolean(user);
  return (
    <section className="pub-hero" aria-labelledby="hero-title">
      <div className="pub-hero-ambient" aria-hidden="true">
        <div className="pub-hero-glow" />
        <LightStreaks />
      </div>
      <div className="pub-container pub-hero-grid">
        <div className="pub-hero-copy">
          <p className="pub-eyebrow">
            <Cpu size={15} strokeWidth={1.8} aria-hidden="true" />
            AI-POWERED IT OPERATIONS PLATFORM
          </p>
          <h1 id="hero-title" className="pub-hero-title">
            <span>Complex Operations.</span> <span className="pub-gold">Under Control.</span>
          </h1>
          <p className="pub-hero-lead">
            Manage incidents, automate workflows, monitor SLAs and resolve enterprise issues with intelligent operations.
          </p>
          <div className="pub-hero-ctas">
            <Link to={signedIn ? '/dashboard' : '/login'} className="pub-btn pub-btn-gold pub-btn-lg">
              Enter Workspace <ArrowRight size={18} aria-hidden="true" />
            </Link>
            <PublicLink to="/#capabilities" className="pub-btn pub-btn-outline pub-btn-lg">
              Explore Platform <Play size={17} aria-hidden="true" />
            </PublicLink>
          </div>
          <ul className="pub-facts" aria-label="Platform facts">
            {HERO_FACTS.map((fact) => {
              const Icon = fact.icon;
              return (
                <li key={fact.value}>
                  <Icon size={28} strokeWidth={1.5} aria-hidden="true" />
                  <span>
                    <b>{fact.value}</b>
                    <small>{fact.label}</small>
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
        <div className="pub-hero-visual">
          <Globe />
          <DashboardPreview />
        </div>
      </div>
    </section>
  );
}
