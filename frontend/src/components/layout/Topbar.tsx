import { Menu } from 'lucide-react';
import { GlobalSearch } from './GlobalSearch';
import { NotificationBell } from './NotificationBell';
import { ProfileMenu } from './ProfileMenu';

/**
 * Top bar from the reference: wide global search (Ctrl K), notification bell and profile.
 * "New Incident" lives in the Incidents and Command Center page headers (spec §14).
 */
export function Topbar({ onMenu }: { onMenu: () => void }) {
  return (
    <header className="topbar">
      <button type="button" className="menu-btn" onClick={onMenu} aria-label="Open navigation">
        <Menu size={20} />
      </button>
      <GlobalSearch />
      <div className="top-actions">
        <NotificationBell />
        <ProfileMenu />
      </div>
    </header>
  );
}
