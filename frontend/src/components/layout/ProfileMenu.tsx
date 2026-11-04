import { useCallback, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bell, ChevronDown, LogOut, Megaphone, UserRound } from 'lucide-react';
import { useAuth } from '../../hooks/useAuth';
import { useClickOutside } from '../../hooks/useClickOutside';
import { ROLE_LABELS } from '../../lib/constants';
import { shortName } from '../../lib/format';
import { Avatar } from '../Avatar';

/** Avatar initials, name and role label (reference top bar) with the account menu. */
export function ProfileMenu() {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(ref, open, close);
  if (!user) return null;

  return (
    <div className="dropdown-anchor" ref={ref}>
      <button
        type="button"
        className="profile"
        aria-label={`${user.fullName}, ${ROLE_LABELS[user.role]}`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <Avatar name={user.fullName} />
        <div>
          <b>{shortName(user.fullName)}</b>
          <small>{ROLE_LABELS[user.role]}</small>
        </div>
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      {open && (
        <div className="dropdown" role="menu" aria-label="Account">
          <div className="dropdown-head flex-col items-start">
            <span>{user.fullName}</span>
            <small className="text-[10px] font-normal text-muted">{user.email}</small>
            <small className="text-[10px] font-normal text-muted">
              {ROLE_LABELS[user.role]}
              {user.departmentName ? ` · ${user.departmentName}` : ''}
              {user.jobTitle ? ` · ${user.jobTitle}` : ''}
            </small>
          </div>
          <Link to="/profile" role="menuitem" className="dropdown-item" onClick={close}>
            <UserRound size={15} /> Profile
          </Link>
          <Link to="/notifications" role="menuitem" className="dropdown-item" onClick={close}>
            <Bell size={15} /> Notifications
          </Link>
          <Link to="/announcements" role="menuitem" className="dropdown-item" onClick={close}>
            <Megaphone size={15} /> Announcements
          </Link>
          <button
            type="button"
            role="menuitem"
            className="dropdown-item"
            onClick={() => {
              close();
              void logout();
            }}
          >
            <LogOut size={15} /> Sign out
          </button>
        </div>
      )}
    </div>
  );
}
