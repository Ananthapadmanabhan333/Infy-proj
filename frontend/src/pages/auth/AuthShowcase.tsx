import { Activity, AlertTriangle, GitPullRequestArrow, ShieldCheck } from 'lucide-react';

/**
 * ILLUSTRATIVE SAMPLE DATA for the sign-in brand panel — not read from any API and not a claim about any
 * deployment. The figure is always captioned "Illustrative preview".
 */
const SHOWCASE = {
  tiles: [
    { icon: AlertTriangle, label: 'Open incidents', value: '42', tone: 'warning' },
    { icon: ShieldCheck, label: 'SLA compliance', value: '96.4%', tone: 'success' },
    { icon: GitPullRequestArrow, label: 'Changes today', value: '7', tone: 'gold' },
  ],
  trend: [18, 22, 19, 27, 24, 31, 28, 35, 30, 26, 29, 24],
  services: [
    { name: 'Email & Collaboration', status: 'Operational', tone: 'success' },
    { name: 'Payroll', status: 'Degraded', tone: 'warning' },
    { name: 'VPN Gateway', status: 'Operational', tone: 'success' },
  ],
} as const;

function Trend() {
  const max = Math.max(...SHOWCASE.trend);
  const width = 260;
  const height = 56;
  const step = width / (SHOWCASE.trend.length - 1);
  const points = SHOWCASE.trend.map((value, index) => `${(index * step).toFixed(1)},${(height - 4 - (value / max) * (height - 12)).toFixed(1)}`);
  return (
    <svg className="auth-showcase-trend" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id="auth-showcase-area" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#D6B77A" stopOpacity="0.38" />
          <stop offset="1" stopColor="#D6B77A" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`M${points.join(' L')} L${width},${height} L0,${height} Z`} fill="url(#auth-showcase-area)" />
      <polyline points={points.join(' ')} fill="none" stroke="#D6B77A" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

/** Subtle product visual of the sign-in brand panel (React + CSS + inline SVG, no raster, no API calls). */
export function AuthShowcase() {
  return (
    <figure className="auth-showcase" aria-labelledby="auth-showcase-caption">
      <div className="auth-showcase-card" aria-hidden="true">
        <div className="auth-showcase-head">
          <span>
            <Activity size={14} /> Operations pulse
          </span>
          <em>Last 12 hours</em>
        </div>
        <div className="auth-showcase-tiles">
          {SHOWCASE.tiles.map((tile) => {
            const Icon = tile.icon;
            return (
              <div key={tile.label} className={`auth-showcase-tile ${tile.tone}`}>
                <Icon size={15} />
                <small>{tile.label}</small>
                <b>{tile.value}</b>
              </div>
            );
          })}
        </div>
        <Trend />
        <ul className="auth-showcase-services">
          {SHOWCASE.services.map((service) => (
            <li key={service.name}>
              <i className={service.tone} />
              <span>{service.name}</span>
              <em className={service.tone}>{service.status}</em>
            </li>
          ))}
        </ul>
      </div>
      <figcaption id="auth-showcase-caption">
        <span className="auth-showcase-badge">Illustrative preview</span>
        <span className="sr-only"> — sample figures showing open incidents, SLA compliance, changes and service health; not live data.</span>
      </figcaption>
    </figure>
  );
}
