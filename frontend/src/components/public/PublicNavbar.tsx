import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { ArrowRight, ChevronDown, Menu, X } from 'lucide-react';
import { useAuth } from '../../hooks/useAuth';
import { Avatar } from '../Avatar';
import { BRAND } from '../../lib/brand';
import { BrandLockup } from '../layout/BrandMark';
import { NAV_GROUPS, type MenuGroup } from './content';
import { PublicLink } from './PublicLink';
import { prefersReducedMotion } from './scroll';
import { StatusPill } from './StatusPill';

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

function menuLinks(panel: HTMLElement | null): HTMLAnchorElement[] {
  return panel ? Array.from(panel.querySelectorAll<HTMLAnchorElement>('a[href]')) : [];
}

interface DropdownProps {
  group: MenuGroup;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Disclosure-style navigation menu: button + list of links (Escape / outside click / Tab-away close it). */
function NavDropdown({ group, open, onOpenChange }: DropdownProps) {
  const wrapperRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const panelId = useId();
  const focusFirst = useRef(false);

  useEffect(() => {
    if (!open) return undefined;
    if (focusFirst.current) {
      focusFirst.current = false;
      menuLinks(panelRef.current)[0]?.focus();
    }
    const onPointer = (event: MouseEvent | TouchEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target as Node)) onOpenChange(false);
    };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('touchstart', onPointer);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('touchstart', onPointer);
    };
  }, [open, onOpenChange]);

  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape' && open) {
      event.preventDefault();
      onOpenChange(false);
      buttonRef.current?.focus();
      return;
    }
    if (event.target === buttonRef.current && event.key === 'ArrowDown') {
      event.preventDefault();
      if (open) menuLinks(panelRef.current)[0]?.focus();
      else {
        focusFirst.current = true;
        onOpenChange(true);
      }
      return;
    }
    const links = menuLinks(panelRef.current);
    const index = links.indexOf(document.activeElement as HTMLAnchorElement);
    if (index < 0) return;
    const move = (next: number) => {
      event.preventDefault();
      links[(next + links.length) % links.length]?.focus();
    };
    if (event.key === 'ArrowDown') move(index + 1);
    else if (event.key === 'ArrowUp') move(index - 1);
    else if (event.key === 'Home') move(0);
    else if (event.key === 'End') move(links.length - 1);
  };

  return (
    <div
      ref={wrapperRef}
      className="pub-dropdown"
      onKeyDown={onKeyDown}
      onBlur={(event) => {
        if (open && event.relatedTarget && !wrapperRef.current?.contains(event.relatedTarget as Node)) onOpenChange(false);
      }}
    >
      <button
        ref={buttonRef}
        type="button"
        className="pub-nav-link"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => onOpenChange(!open)}
      >
        {group.label}
        <ChevronDown size={15} aria-hidden="true" className="pub-chevron" />
      </button>
      <div ref={panelRef} id={panelId} className={`pub-menu ${group.wide ? 'wide' : ''}`} hidden={!open}>
        <ul aria-label={group.label}>
          {group.items.map((item) => {
            const Icon = item.icon;
            return (
              <li key={item.label}>
                <PublicLink to={item.to} className="pub-menu-item" onClick={() => onOpenChange(false)}>
                  {Icon && (
                    <span className="pub-menu-icon" aria-hidden="true">
                      <Icon size={17} strokeWidth={1.7} />
                    </span>
                  )}
                  <span>
                    <b>{item.label}</b>
                    {item.description && <small>{item.description}</small>}
                  </span>
                </PublicLink>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}

function WorkspaceActions({ variant, onNavigate }: { variant: 'bar' | 'drawer'; onNavigate?: () => void }) {
  const { user, status } = useAuth();
  if (status === 'authenticated' && user) {
    return (
      <>
        <Link to="/dashboard" className="pub-btn pub-btn-gold" onClick={onNavigate}>
          Open Workspace <ArrowRight size={16} aria-hidden="true" />
        </Link>
        <Link to="/profile" className="pub-nav-avatar" aria-label={`Your profile (${user.fullName})`} onClick={onNavigate}>
          <Avatar name={user.fullName} size="sm" />
          {variant === 'drawer' && <span>{user.fullName}</span>}
        </Link>
      </>
    );
  }
  return (
    <>
      <Link to="/login" className="pub-btn pub-btn-outline" onClick={onNavigate}>
        Login
      </Link>
      <Link to="/login" className="pub-btn pub-btn-gold" onClick={onNavigate}>
        Access Workspace <ArrowRight size={16} aria-hidden="true" />
      </Link>
    </>
  );
}

function MobileDrawer({ onClose, returnFocusTo }: { onClose: () => void; returnFocusTo: HTMLElement | null }) {
  const panelRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    closeRef.current?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previousOverflow;
      // Give focus back to the menu button unless a link already moved it somewhere meaningful.
      const active = document.activeElement;
      if (!active || active === document.body || !active.isConnected) returnFocusTo?.focus();
    };
  }, [returnFocusTo]);

  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== 'Tab' || !panelRef.current) return;
    const items = Array.from(panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((element) => !element.closest('[hidden]'));
    if (!items.length) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return (
    <div className="pub-drawer-root" onKeyDown={onKeyDown}>
      <div className="pub-drawer-backdrop" onClick={onClose} aria-hidden="true" />
      <div ref={panelRef} id="pub-drawer" className="pub-drawer" role="dialog" aria-modal="true" aria-label="Site menu">
        <div className="pub-drawer-head">
          <div className="pub-logo">
            <BrandLockup />
          </div>
          <button ref={closeRef} type="button" className="pub-icon-btn" onClick={onClose} aria-label="Close menu">
            <X size={20} aria-hidden="true" />
          </button>
        </div>
        <StatusPill className="pub-drawer-status" />
        <nav aria-label="Main" className="pub-drawer-nav">
          {NAV_GROUPS.map((group) => {
            const isOpen = expanded === group.label;
            const listId = `pub-drawer-${group.label.toLowerCase()}`;
            return (
              <div key={group.label} className="pub-drawer-group">
                <button
                  type="button"
                  className="pub-drawer-toggle"
                  aria-expanded={isOpen}
                  aria-controls={listId}
                  onClick={() => setExpanded(isOpen ? null : group.label)}
                >
                  {group.label}
                  <ChevronDown size={17} aria-hidden="true" className="pub-chevron" />
                </button>
                <ul id={listId} hidden={!isOpen}>
                  {group.items.map((item) => (
                    <li key={item.label}>
                      <PublicLink to={item.to} className="pub-drawer-link" onClick={onClose}>
                        {item.label}
                      </PublicLink>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
          <Link to="/docs" className="pub-drawer-toggle pub-drawer-plain" onClick={onClose}>
            Documentation
          </Link>
        </nav>
        <div className="pub-drawer-actions">
          <WorkspaceActions variant="drawer" onNavigate={onClose} />
        </div>
      </div>
    </div>
  );
}

/**
 * Fixed public top navigation (reference: logo · Platform / Solutions / Resources / Company · Documentation · status ·
 * Login · Access Workspace).
 */
export function PublicNavbar() {
  const [openMenu, setOpenMenu] = useState<string | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const location = useLocation();

  useEffect(() => {
    setOpenMenu(null);
    setDrawerOpen(false);
  }, [location.pathname]);

  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  return (
    <>
      <header className="pub-nav">
        <div className="pub-container pub-nav-inner">
          <PublicLink
            to="/"
            className="pub-logo"
            aria-label={`${BRAND.name} — ${BRAND.subtitle}, home`}
            onClick={(event) => {
              if (location.pathname === '/') {
                event.preventDefault();
                window.scrollTo({ top: 0, behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
              }
            }}
          >
            <BrandLockup label={null} />
          </PublicLink>

          <nav className="pub-nav-groups" aria-label="Main">
            <ul>
              {NAV_GROUPS.map((group) => (
                <li key={group.label}>
                  <NavDropdown
                    group={group}
                    open={openMenu === group.label}
                    onOpenChange={(open) => setOpenMenu((current) => (open ? group.label : current === group.label ? null : current))}
                  />
                </li>
              ))}
              <li>
                <Link to="/docs" className="pub-nav-link" aria-current={location.pathname === '/docs' ? 'page' : undefined}>
                  Documentation
                </Link>
              </li>
            </ul>
          </nav>

          <div className="pub-nav-actions">
            <StatusPill className="pub-nav-status" />
            <div className="pub-nav-cta">
              <WorkspaceActions variant="bar" />
            </div>
            <button
              ref={toggleRef}
              type="button"
              className="pub-icon-btn pub-nav-toggle"
              aria-label="Open menu"
              aria-expanded={drawerOpen}
              aria-controls={drawerOpen ? 'pub-drawer' : undefined}
              onClick={() => setDrawerOpen(true)}
            >
              <Menu size={22} aria-hidden="true" />
            </button>
          </div>
        </div>
      </header>
      {drawerOpen && <MobileDrawer onClose={closeDrawer} returnFocusTo={toggleRef.current} />}
    </>
  );
}
