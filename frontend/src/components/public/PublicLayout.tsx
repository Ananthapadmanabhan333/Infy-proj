import { useEffect, useRef, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { PublicFooter } from './PublicFooter';
import { PublicNavbar } from './PublicNavbar';
import { scrollToSection } from './scroll';
import './public.css';

/** Shared frame of the pre-login pages: skip link, fixed navbar, `<main>` and footer. */
export function PublicLayout({ children, className = '' }: { children: ReactNode; className?: string }) {
  const location = useLocation();
  const mainRef = useRef<HTMLElement>(null);

  // Route change: jump to the requested section (e.g. /#how-it-works, /docs#roles) or back to the top.
  useEffect(() => {
    const id = decodeURIComponent(location.hash.replace(/^#/, ''));
    if (id) scrollToSection(id, { updateHash: false, focus: false });
    else window.scrollTo?.(0, 0);
  }, [location.pathname, location.hash]);

  return (
    <div className={`pub ${className}`.trim()}>
      <a
        href="#main"
        className="pub-skip"
        onClick={(event) => {
          event.preventDefault();
          mainRef.current?.focus();
          mainRef.current?.scrollIntoView?.({ block: 'start' });
        }}
      >
        Skip to main content
      </a>
      <PublicNavbar />
      <main id="main" ref={mainRef} tabIndex={-1} className="pub-main">
        {children}
      </main>
      <PublicFooter />
    </div>
  );
}
