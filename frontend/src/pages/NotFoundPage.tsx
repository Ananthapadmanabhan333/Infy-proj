import { Link, useLocation } from 'react-router-dom';
import { Compass } from 'lucide-react';

export default function NotFoundPage() {
  const location = useLocation();
  return (
    <div className="page-stack">
      <section className="panel placeholder-panel" aria-labelledby="not-found-title">
        <div className="placeholder-icon">
          <Compass size={28} />
        </div>
        <h2 id="not-found-title">Page not found</h2>
        <p>
          There is nothing at <code className="gold-text">{location.pathname}</code>. The link may be outdated, or the record may have been removed.
        </p>
        <div className="placeholder-actions">
          <Link className="gold-btn" to="/dashboard">
            Back to Command Center
          </Link>
          <Link className="subtle-btn" to="/incidents">
            Open incidents
          </Link>
        </div>
      </section>
    </div>
  );
}
