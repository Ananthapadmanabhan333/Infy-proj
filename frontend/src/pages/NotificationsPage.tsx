import { useEffect } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { AlertTriangle, BellOff, CheckCheck, CheckCircle2, ChevronRight, Info, OctagonAlert } from 'lucide-react';
import { useMarkAllNotificationsRead, useMarkNotificationRead, useNotificationPreferences, useNotifications, useUnreadCount } from '../api/notifications';
import { NotificationPreferencesPanel } from '../components/admin/NotificationPreferencesPanel';
import { PageHeader } from '../components/PageHeader';
import { Pagination } from '../components/Pagination';
import { Panel } from '../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { Tabs } from '../components/Tabs';
import { useToast } from '../components/Toast';
import { useNotificationStreamState } from '../hooks/useNotificationStream';
import { useTabParam } from '../hooks/useUrlState';
import { DEFAULT_PAGE_SIZE } from '../lib/constants';
import { formatDateTime, formatRelative, titleCase } from '../lib/format';
import type { NotificationDto, Severity } from '../lib/types';
import '../components/admin/admin.css';

type View = 'all' | 'unread';

const SEVERITY_TILE: Record<Severity, string> = { CRITICAL: 'critical', WARNING: 'warning', SUCCESS: 'success', INFO: '' };
const SEVERITY_LABEL: Record<Severity, string> = { CRITICAL: 'Critical', WARNING: 'Warning', SUCCESS: 'Success', INFO: 'Information' };

function SeverityIcon({ severity }: { severity: Severity }) {
  if (severity === 'CRITICAL') return <OctagonAlert size={16} />;
  if (severity === 'WARNING') return <AlertTriangle size={16} />;
  if (severity === 'SUCCESS') return <CheckCircle2 size={16} />;
  return <Info size={16} />;
}

function InboxRow({ notification, typeLabel, onOpen, onMarkRead, marking }: { notification: NotificationDto; typeLabel: string; onOpen: () => void; onMarkRead: () => void; marking: boolean }) {
  const internalLink = notification.link?.startsWith('/') ? notification.link : null;
  return (
    <article className={`inbox-row ${notification.read ? '' : 'unread'}`} aria-label={`${notification.read ? '' : 'Unread: '}${notification.title}`}>
      <span className={`icon-tile sm ${SEVERITY_TILE[notification.severity] ?? ''}`} title={SEVERITY_LABEL[notification.severity]}>
        <SeverityIcon severity={notification.severity} />
        <span className="sr-only">{SEVERITY_LABEL[notification.severity]}</span>
      </span>
      <div className="inbox-body">
        <b>
          {!notification.read && <span className="unread-dot mr-1.5 align-middle" aria-hidden="true" />}
          {notification.title}
        </b>
        <p>{notification.message}</p>
        <div className="inbox-meta">
          <span title={formatDateTime(notification.createdAt)}>{formatRelative(notification.createdAt)}</span>
          <span>{typeLabel}</span>
          {internalLink && (
            <button type="button" className="link-btn text-[10px]" onClick={onOpen}>
              Open <ChevronRight size={12} />
            </button>
          )}
        </div>
      </div>
      {!notification.read && (
        <button type="button" className="subtle-btn compact" onClick={onMarkRead} disabled={marking} aria-label={`Mark “${notification.title}” as read`}>
          <CheckCheck size={13} /> Mark read
        </button>
      )}
    </article>
  );
}

/** /notifications: inbox (All / Unread) with mark read and links, plus the user's notification preferences. */
export default function NotificationsPage() {
  const [view, setView] = useTabParam<View>(['all', 'unread'], 'all');
  const location = useLocation();
  const page = Math.max(0, Number.parseInt(new URLSearchParams(location.search).get('page') ?? '0', 10) || 0);
  const navigate = useNavigate();
  const stream = useNotificationStreamState();
  const notifications = useNotifications({ unreadOnly: view === 'unread', page, size: DEFAULT_PAGE_SIZE });
  const unread = useUnreadCount(stream.pollInterval);
  const preferences = useNotificationPreferences();
  const markRead = useMarkNotificationRead();
  const markAll = useMarkAllNotificationsRead();
  const toast = useToast();
  const unreadCount = unread.data ?? 0;
  const labels = new Map((preferences.data ?? []).map((preference) => [preference.type as string, preference.label]));

  useEffect(() => {
    if (location.hash === '#preferences') document.getElementById('preferences')?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }, [location.hash, preferences.isSuccess]);

  const setPage = (next: number) => {
    const params = new URLSearchParams(location.search);
    if (next) params.set('page', String(next));
    else params.delete('page');
    const query = params.toString();
    navigate({ search: query ? `?${query}` : '', hash: location.hash }, { replace: true });
  };

  const open = (notification: NotificationDto) => {
    if (!notification.read) markRead.mutate(notification.id);
    if (notification.link?.startsWith('/')) navigate(notification.link);
  };

  return (
    <>
      <PageHeader
        title="Notifications"
        subtitle="Alerts, assignments and approvals addressed to you"
        actions={
          <button
            type="button"
            className="gold-btn"
            disabled={!unreadCount || markAll.isPending}
            onClick={() =>
              markAll.mutate(undefined, {
                onSuccess: () => toast.success('All notifications marked as read'),
                onError: (error) => toast.error(error),
              })
            }
          >
            <CheckCheck size={15} /> {markAll.isPending ? 'Marking…' : 'Mark all read'}
          </button>
        }
      />
      <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
        <Panel
          title="Inbox"
          subtitle={unread.data === undefined ? 'Your latest notifications' : `${unreadCount} unread`}
          actions={
            <span className={`live-dot ${stream.status === 'open' ? 'on' : ''}`} title={stream.status === 'open' ? 'Live updates connected' : 'Refreshing every 30 seconds'}>
              {stream.status === 'open' ? 'Live' : 'Auto-refresh'}
            </span>
          }
        >
          <div className="mb-3">
            <Tabs<View>
              label="Notification filter"
              value={view}
              onChange={setView}
              inner
              tabs={[
                { id: 'all', label: 'All', count: view === 'all' ? notifications.data?.totalElements : undefined },
                { id: 'unread', label: 'Unread', count: unread.data },
              ]}
            />
          </div>
          {notifications.isLoading && <LoadingState rows={6} label="Loading notifications…" />}
          {notifications.isError && !notifications.data && <ErrorState error={notifications.error} onRetry={() => void notifications.refetch()} />}
          {notifications.data && notifications.data.content.length === 0 && (
            <EmptyState
              title={view === 'unread' ? 'No unread notifications' : 'No notifications yet'}
              description={view === 'unread' ? 'You are all caught up.' : 'Assignments, SLA alerts, approvals and announcements will appear here.'}
              icon={<BellOff size={20} />}
            />
          )}
          {notifications.data && notifications.data.content.length > 0 && (
            <div role="list" aria-label="Notifications">
              {notifications.data.content.map((notification) => (
                <div role="listitem" key={notification.id}>
                  <InboxRow
                    notification={notification}
                    typeLabel={labels.get(notification.type) ?? titleCase(notification.type)}
                    onOpen={() => open(notification)}
                    onMarkRead={() => markRead.mutate(notification.id, { onError: (error) => toast.error(error) })}
                    marking={markRead.isPending && markRead.variables === notification.id}
                  />
                </div>
              ))}
            </div>
          )}
          {notifications.data && (
            <Pagination
              page={notifications.data.page}
              totalPages={notifications.data.totalPages}
              totalElements={notifications.data.totalElements}
              size={notifications.data.size}
              onPageChange={setPage}
              label="notifications"
            />
          )}
          <p className="mt-3 text-[10px] text-muted">
            Looking for service-wide notices? See{' '}
            <Link className="gold-text" to="/announcements">
              Announcements
            </Link>
            .
          </p>
        </Panel>
        <NotificationPreferencesPanel />
      </div>
    </>
  );
}
