import { Link } from 'react-router-dom';
import { BRAND } from '../../lib/brand';
import { BrandLockup } from '../layout/BrandMark';
import { CAPABILITIES } from './content';
import { PublicLink } from './PublicLink';

const PRODUCT_LINKS = [
  { label: 'Capabilities', to: '/#capabilities' },
  { label: 'How it works', to: '/#how-it-works' },
  { label: 'Enterprise reliability', to: '/#reliability' },
  { label: 'Inside the platform', to: '/#platform' },
];

const COMPANY_LINKS = [
  { label: 'About SA', to: '/#about' },
  { label: 'Contact', to: '/support#contact' },
];

export function PublicFooter() {
  const year = new Date().getFullYear();
  return (
    <footer className="pub-footer">
      <div className="pub-container">
        <div className="pub-footer-grid">
          <div className="pub-footer-brand">
            <div className="pub-logo">
              <BrandLockup />
            </div>
            <p className="pub-footer-tagline">{BRAND.tagline}</p>
            <p>Incidents, service requests, problems, changes, assets, SLAs, automation, analytics and knowledge in one workspace.</p>
            <Link to="/login" className="pub-footer-signin">
              Sign in to your workspace
            </Link>
          </div>

          <nav aria-labelledby="footer-product">
            <h2 id="footer-product">Product</h2>
            <ul>
              {PRODUCT_LINKS.map((link) => (
                <li key={link.to}>
                  <PublicLink to={link.to}>{link.label}</PublicLink>
                </li>
              ))}
            </ul>
          </nav>

          <nav aria-labelledby="footer-platform" className="pub-footer-platform">
            <h2 id="footer-platform">Platform</h2>
            <ul>
              {CAPABILITIES.map((capability) => (
                <li key={capability.id}>
                  <PublicLink to={`/#${capability.id}`}>{capability.title}</PublicLink>
                </li>
              ))}
            </ul>
          </nav>

          <nav aria-labelledby="footer-resources">
            <h2 id="footer-resources">Resources</h2>
            <ul>
              <li>
                <Link to="/docs">Documentation</Link>
              </li>
              <li>
                <Link to="/status">Service status</Link>
              </li>
              <li>
                <Link to="/support">Support</Link>
              </li>
              <li>
                <Link to="/privacy">Privacy Policy</Link>
              </li>
              <li>
                <Link to="/terms">Terms and Conditions</Link>
              </li>
            </ul>
          </nav>

          <nav aria-labelledby="footer-company">
            <h2 id="footer-company">Company</h2>
            <ul>
              {COMPANY_LINKS.map((link) => (
                <li key={link.to}>
                  <PublicLink to={link.to}>{link.label}</PublicLink>
                </li>
              ))}
            </ul>
          </nav>
        </div>
        <div className="pub-footer-bottom">
          <p>
            © {year} {BRAND.name} · {BRAND.subtitle}
          </p>
          <p>Accounts are administrator-controlled · No tracking or advertising cookies</p>
        </div>
      </div>
    </footer>
  );
}
