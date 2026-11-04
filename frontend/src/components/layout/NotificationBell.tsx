import { useCallback, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AlertTriangle, Bell, CheckCheck, CheckCircle2, Info } from 'lucide-react';
import { useMarkAllNotificationsRead, useMarkNotificationRead, useNotifications, useUnreadCount } from '../../api/notifications';
import { useClickOutside } from '../../hooks/useClickOutside';
import { useNotificationStreamState } from '../../hooks/useNotificationStream';
import { formatRelative } from '../../lib/format';
import type { NotificationDto, Severity } from '../../lib/types';
import { Tabs } from '../Tabs';
import { useToast } from '../Toast';

const SEVERITY_CLASS: Record<Severity, string> = { CRITICAL: 'critical', WARNING: 'warning', SUCCESS: 'success', INFO: '' };

function SeverityIcon({ severity }: { severity: Severity }) {
  if (severity === 'CRITICAL' || severity === 'WARNING') return <AlertTriangle size={14} />;
  if (severity === 'SUCCESS') return <CheckCircle2 size={14} />;
  return <Info size={14} />;
}

/** Bell with unread badge; opens the notifications panel (All / Unread, mark read, links). */
export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<'all' | 'unread'>('all');
  const ref = useRef<HTMLDivElement>(null);
  const stream = useNotificationStreamState();
  const unread = useUnreadCount(stream.pollInterval);
  const list = useNotifications({ page: 0, size: 8, unreadOnly: view === 'unread' }, open);
  const markRead = useMarkNotificationRead();
  const markAll = useMarkAllNotificationsRead();
  const navigate = useNavigate();
  const toast = useToast();
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(ref, open, close);

  const count = unread.data ?? 0;

  const openNotification = (notification: NotificationDto) => {
    if (!notification.read) markRead.mutate(notification.id);
    setOpen(false);
    if (notification.link?.startsWith('/')) navigate(notification.link);
  };

  return (
    <div className="dropdown-anchor" ref={ref}>
      <button
        type="button"
        className="icon-btn"
        aria-label={count ? `Notifications, ${count} unread` : 'Notifications'}
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <Bell size={19} />
        {count > 0 && (
          <em className="count" aria-hidden="true">
            {count > 99 ? '99+' : count}
          </em>
        )}
      </button>
      {open && (
        <div className="dropdown notif-panel" role="region" aria-label="Recent notifications">
          <div className="dropdown-head">
            <span className="flex items-center gap-2">
              Notifications
              <span className={`live-dot ${stream.status === 'open' ? 'on' : ''}`} title={stream.status === 'open' ? 'Live updates connected' : 'Refreshing every 30 seconds'}>
                {stream.status === 'open' ? 'Live' : 'Auto-refresh'}
              </span>
            </span>
            <button
              type="button"
              className="link-btn text-[10px]"
              disabled={!count || markAll.isPending}
              onClick={() =>
                markAll.mutate(undefined, {
                  onSuccess: () => toast.success('All notifications marked as read'),
                  onError: (error) => toast.error(error),
                })
              }
            >
              <CheckCheck size={13} /> Mark all read
            </button>
          </div>
          <Tabs<'all' | 'unread'>
            label="Notification filter"
            value={view}
            onChange={setView}
            tabs={[
              { id: 'all', label: 'All' },
              { id: 'unread', label: 'Unread', count },
            ]}
          />
          <div className="notif-list">
            {list.isLoading && <div className="dropdown-item">Loading…</div>}
            {list.isError && <div className="dropdown-item red-text">Could not load notifications.</div>}
            {list.data && list.data.content.length === 0 && (
              <div className="dropdown-item">{view === 'unread' ? 'No unread notifications.' : 'You are all caught up.'}</div>
            )}
            {list.data?.content.map((notification) => (
              <div key={notification.id} className={`notif-item ${SEVERITY_CLASS[notification.severity] ?? ''} ${notification.read ? '' : 'unread'}`}>
                <span className="notif-icon" aria-hidden="true">
                  <SeverityIcon severity={notification.severity} />
                </span>
                <button type="button" className="notif-body" onClick={() => openNotification(notification)}>
                  <b>{notification.title}</b>
                  <small>{notification.message}</small>
                  <small>{formatRelative(notification.createdAt)}</small>
                </button>
                {!notification.read && (
                  <button
                    type="button"
                    className="icon-btn sm"
                    title="Mark as read"
                    aria-label={`Mark “${notification.title}” as read`}
                    onClick={() => markRead.mutate(notification.id)}
                  >
                    <CheckCheck size={14} />
                  </button>
                )}
              </div>
            ))}
          </div>
          <div className="dropdown-foot">
            <Link className="link-btn text-[10px]" to="/notifications" onClick={close}>
              View all notifications
            </Link>
            <Link className="link-btn text-[10px]" to="/announcements" onClick={close}>
              Announcements
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}
