import { Fragment, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';

export interface Crumb {
  label: string;
  to?: string;
}

export interface PageHeaderProps {
  title: ReactNode;
  subtitle?: ReactNode;
  /** Right-hand buttons (gold primary action last, as in the reference). */
  actions?: ReactNode;
  /** Small line above the title, e.g. "Good morning, Ananta". */
  greeting?: ReactNode;
  /** Uppercase label above the title (rarely needed; prefer `crumbs`). */
  eyebrow?: ReactNode;
  /** Breadcrumb trail; the first crumb with `to` doubles as the back link ("‹ Incidents / INC-7846"). */
  crumbs?: Crumb[];
  /** Gold title (Command Center). */
  goldTitle?: boolean;
  /** Subtle CSS radial glow behind the header (Command Center hero). */
  glow?: boolean;
  /** Extra content under the subtitle (badges, meta line…). */
  children?: ReactNode;
  className?: string;
}

/** Page title block used at the top of every screen. Root keeps the `page-heading` class (e2e selectors rely on it). */
export function PageHeader({ title, subtitle, actions, greeting, eyebrow, crumbs, goldTitle, glow, children, className = '' }: PageHeaderProps) {
  return (
    <div className={`page-heading ${glow ? 'glow' : ''} ${className}`.trim()}>
      <div className="min-w-0">
        {crumbs && crumbs.length > 0 && (
          <nav className="page-crumbs" aria-label="Breadcrumb">
            {crumbs.map((crumb, index) => (
              <Fragment key={`${crumb.label}-${index}`}>
                {index > 0 && (
                  <span className="sep" aria-hidden="true">
                    /
                  </span>
                )}
                {crumb.to ? (
                  <Link to={crumb.to}>
                    {index === 0 && <ChevronLeft size={13} aria-hidden="true" />}
                    {crumb.label}
                  </Link>
                ) : (
                  <span className={index === crumbs.length - 1 ? 'current' : ''} aria-current={index === crumbs.length - 1 ? 'page' : undefined}>
                    {crumb.label}
                  </span>
                )}
              </Fragment>
            ))}
          </nav>
        )}
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        {greeting && <p className="page-greeting">{greeting}</p>}
        <h1 className={goldTitle ? 'gold' : undefined}>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
        {children}
      </div>
      {actions && <div className="heading-actions">{actions}</div>}
    </div>
  );
}

/** "Good morning" / "Good afternoon" / "Good evening" for the local time. */
export function greetingFor(date: Date = new Date()): string {
  const hour = date.getHours();
  if (hour < 12) return 'Good morning';
  if (hour < 18) return 'Good afternoon';
  return 'Good evening';
}
