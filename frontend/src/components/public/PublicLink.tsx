import type { AnchorHTMLAttributes, MouseEvent, ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { scrollToSection } from './scroll';

interface PublicLinkProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> {
  /** Route with an optional hash: `/#capabilities`, `/docs#roles`, `/privacy`. */
  to: string;
  children: ReactNode;
}

/**
 * Router link that understands landing-page anchors: on the same page it smooth-scrolls to the section (loading
 * lazy sections on demand); on another page it navigates and `PublicLayout` scrolls once the page has rendered.
 */
export function PublicLink({ to, onClick, children, ...rest }: PublicLinkProps) {
  const location = useLocation();
  const [pathname, hash = ''] = to.split('#');
  const targetPath = pathname || location.pathname;

  const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(event);
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (hash && targetPath === location.pathname) {
      event.preventDefault();
      scrollToSection(hash);
    }
  };

  return (
    <Link to={{ pathname: targetPath, hash: hash ? `#${hash}` : '' }} onClick={handleClick} {...rest}>
      {children}
    </Link>
  );
}
