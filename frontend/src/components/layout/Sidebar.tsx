import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { ChevronDown, X } from 'lucide-react';
import { useHealth } from '../../api/health';
import { useOpenIncidentCount } from '../../api/incidents';
import { useAuth } from '../../hooks/useAuth';
import { BRAND } from '../../lib/brand';
import { formatNumber } from '../../lib/format';
import { activeGroupId, isLeafActive, navForRole, type NavGroup, type NavGroupId, type NavLeaf } from '../../lib/nav';
import { version } from '../../../package.json';
import { BrandLockup } from './BrandMark';

/** The organisation name under the wordmark — the platform subtitle until an organisation name is configured. */
export function brandSubtitle(organisation: string | null | undefined): string {
  const name = organisation?.trim();
  const isProductName = !name || ['SA', 'SA 360'].includes(name.toUpperCase());
  return isProductName ? BRAND.subtitle : name;
}

export function SystemStatus() {
  const health = useHealth();
  const state = health.isError ? 'down' : health.isSuccess ? 'up' : 'checking';
  return (
    <div className="system-status" role="status" aria-live="polite">
      <span className={`pulse-dot ${state === 'up' ? '' : state}`} aria-hidden="true" />
      <div>
        <b>{state === 'up' ? 'API Connected' : state === 'down' ? 'API Unreachable' : 'Connecting'}</b>
        <small>
          {state === 'up'
            ? 'Backend services online'
            : state === 'down'
              ? 'Health check failed — retrying every 30 s'
              : 'Checking backend connection'}
        </small>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ remembered group state (per user)
export const NAV_STATE_KEY_PREFIX = 'sa.nav.collapsed.v1';

function storageKey(userId: number | string | undefined): string {
  return `${NAV_STATE_KEY_PREFIX}.${userId ?? 'anonymous'}`;
}

/** Collapsed group ids remembered for this user; storage may be unavailable (private mode, blocked site data). */
export function readCollapsedGroups(userId: number | string | undefined): Set<NavGroupId> {
  try {
    const raw = window.localStorage.getItem(storageKey(userId));
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return new Set(Array.isArray(parsed) ? parsed.filter((value): value is NavGroupId => typeof value === 'string') : []);
  } catch {
    return new Set();
  }
}

function writeCollapsedGroups(userId: number | string | undefined, collapsed: Set<NavGroupId>): void {
  try {
    window.localStorage.setItem(storageKey(userId), JSON.stringify([...collapsed]));
  } catch {
    // Not persisted — the sidebar still works for this visit.
  }
}

/**
 * Groups are expanded unless this user collapsed them; the group holding the current page is always opened when
 * the page is entered (so the active item is never hidden behind a collapsed header).
 */
function useCollapsedGroups(userId: number | undefined, activeGroup: NavGroupId | undefined) {
  const [collapsed, setCollapsed] = useState<Set<NavGroupId>>(() => {
    const stored = readCollapsedGroups(userId);
    if (activeGroup) stored.delete(activeGroup);
    return stored;
  });

  // A different user signed in on this browser: load their own preferences.
  const loadedFor = useRef(userId);
  useEffect(() => {
    if (loadedFor.current === userId) return;
    loadedFor.current = userId;
    const stored = readCollapsedGroups(userId);
    if (activeGroup) stored.delete(activeGroup);
    setCollapsed(stored);
  }, [userId, activeGroup]);

  // Entering a page inside a collapsed group opens that group.
  useEffect(() => {
    if (!activeGroup) return;
    setCollapsed((current) => {
      if (!current.has(activeGroup)) return current;
      const next = new Set(current);
      next.delete(activeGroup);
      return next;
    });
  }, [activeGroup]);

  const toggle = useCallback(
    (id: NavGroupId) => {
      setCollapsed((current) => {
        const next = new Set(current);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        writeCollapsedGroups(userId, next);
        return next;
      });
    },
    [userId],
  );

  return { collapsed, toggle };
}

function NavItemLink({ leaf, pathname, badge, onNavigate }: { leaf: NavLeaf; pathname: string; badge?: number; onNavigate: () => void }) {
  const Icon = leaf.icon;
  const active = isLeafActive(leaf, pathname);
  return (
    <Link
      to={leaf.to}
      aria-label={leaf.label}
      aria-current={active ? 'page' : undefined}
      className={`nav-item ${active ? 'active' : ''}`}
      onClick={onNavigate}
    >
      <Icon size={17} aria-hidden="true" />
      <span>{leaf.label}</span>
      {badge !== undefined && badge > 0 && (
        <em className="nav-badge" aria-hidden="true" title={`${badge} open incidents`}>
          {badge > 999 ? '999+' : formatNumber(badge)}
        </em>
      )}
    </Link>
  );
}

interface NavSectionProps {
  group: NavGroup;
  pathname: string;
  expanded: boolean;
  active: boolean;
  onToggle: () => void;
  onNavigate: () => void;
  openIncidents?: number;
}

function NavSection({ group, pathname, expanded, active, onToggle, onNavigate, openIncidents }: NavSectionProps) {
  const headerId = `nav-group-${group.id}`;
  const listId = `nav-group-${group.id}-items`;
  return (
    <div className={`nav-group ${active ? 'has-active' : ''}`.trim()}>
      <button
        type="button"
        id={headerId}
        className="nav-group-toggle"
        aria-expanded={expanded}
        aria-controls={listId}
        onClick={onToggle}
      >
        <span>{group.label}</span>
        <ChevronDown size={13} className="chevron" aria-hidden="true" />
      </button>
      <div className="nav-group-items" id={listId} role="group" aria-labelledby={headerId} hidden={!expanded}>
        {group.items.map((item) => (
          <NavItemLink
            key={item.to}
            leaf={item}
            pathname={pathname}
            onNavigate={onNavigate}
            badge={item.badge === 'openIncidents' ? openIncidents : undefined}
          />
        ))}
      </div>
    </div>
  );
}

/** Grouped, role-aware sidebar (RELEASE3_SPEC §3); an off-canvas drawer below 1024 px (`open` / `onClose`). */
export function Sidebar({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { user } = useAuth();
  const { pathname } = useLocation();
  const groups = useMemo(() => navForRole(user?.role), [user?.role]);
  const activeGroup = activeGroupId(pathname, groups);
  const { collapsed, toggle } = useCollapsedGroups(user?.id, activeGroup);
  const openCount = useOpenIncidentCount(Boolean(user));
  const closeRef = useRef<HTMLButtonElement>(null);

  // Drawer behaviour: Escape closes, focus moves into the drawer when it opens.
  useEffect(() => {
    if (!open) return undefined;
    closeRef.current?.focus({ preventScroll: true });
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  return (
    <>
      <div className={`sidebar-scrim ${open ? 'show' : ''}`} onClick={onClose} aria-hidden="true" />
      <aside className={`sidebar ${open ? 'show' : ''}`} aria-label="Sidebar">
        <div className="brand">
          <Link to="/dashboard" className="brand-home" aria-label={`${BRAND.name} — Command Center`} onClick={onClose}>
            <BrandLockup label={null} subtitle={brandSubtitle(user?.organisation?.name)} />
          </Link>
          <button ref={closeRef} type="button" className="mobile-close" onClick={onClose} aria-label="Close navigation">
            <X size={17} />
          </button>
        </div>
        <nav aria-label="Main navigation">
          {groups.map((group) => (
            <NavSection
              key={group.id}
              group={group}
              pathname={pathname}
              active={group.id === activeGroup}
              expanded={!collapsed.has(group.id)}
              onToggle={() => toggle(group.id)}
              onNavigate={onClose}
              openIncidents={openCount.data}
            />
          ))}
        </nav>
        <div className="sidebar-bottom">
          <SystemStatus />
          <div className="version">
            {BRAND.name} <span>v{version}</span>
          </div>
        </div>
      </aside>
    </>
  );
}
