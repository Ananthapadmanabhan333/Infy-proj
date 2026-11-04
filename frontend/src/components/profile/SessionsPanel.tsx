import { useState } from 'react';
import { LogOut, Monitor, ShieldCheck, Smartphone } from 'lucide-react';
import { useMySessions, useRevokeOtherSessions, useRevokeSession, useRevokeUserSessions, useUserSessions } from '../../api/sessions';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage } from '../../lib/api';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import type { SessionDto } from '../../lib/types';
import { Badge } from '../Badge';
import { ConfirmDialog } from '../ConfirmDialog';
import { Panel } from '../Panel';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';

const MOBILE = /android|iphone|ipad|mobile/i;

function deviceLabel(session: SessionDto): string {
  if (session.device) return session.device;
  const parts = [session.browser, session.os].filter(Boolean);
  return parts.length ? parts.join(' on ') : 'Unknown device';
}

function SessionRow({ session, onRevoke }: { session: SessionDto; onRevoke?: (session: SessionDto) => void }) {
  const Icon = MOBILE.test(`${session.os ?? ''} ${session.userAgent ?? ''}`) ? Smartphone : Monitor;
  const ip = session.lastIpAddress && session.lastIpAddress !== session.ipAddress ? `${session.ipAddress ?? '—'} → ${session.lastIpAddress}` : session.ipAddress ?? session.lastIpAddress;
  return (
    <li className="list-row flex-wrap sm:flex-nowrap">
      <span className="icon-tile sm" aria-hidden="true">
        <Icon size={14} />
      </span>
      <div>
        <b className="flex flex-wrap items-center gap-1.5">
          {deviceLabel(session)}
          {session.current && <Badge value="current" tone="success" label="This device" />}
          <Badge value={session.rememberMe ? 'remembered' : 'browser'} tone={session.rememberMe ? 'gold' : 'neutral'} label={session.rememberMe ? 'Remember me' : 'Browser session'} />
        </b>
        <small>
          {ip ? `IP ${ip} · ` : ''}signed in {formatDateTime(session.createdAt)} · last used {session.lastUsedAt ? formatRelative(session.lastUsedAt) : '—'}
          {session.expiresAt ? ` · expires ${formatDateTime(session.expiresAt)}` : ''}
        </small>
      </div>
      {onRevoke && (
        <button type="button" className="subtle-btn compact shrink-0" onClick={() => onRevoke(session)} aria-label={session.current ? 'Sign out of this device' : `Revoke ${deviceLabel(session)}`}>
          <LogOut size={12} aria-hidden="true" /> {session.current ? 'Sign out' : 'Revoke'}
        </button>
      )}
    </li>
  );
}

/** Profile → Sessions: own active sessions; revoke one or sign out every other session. */
export function SessionsPanel() {
  const { logout } = useAuth();
  const toast = useToast();
  const sessions = useMySessions();
  const revoke = useRevokeSession();
  const revokeOthers = useRevokeOtherSessions();
  const [target, setTarget] = useState<SessionDto | 'others' | null>(null);
  const list = sessions.data ?? [];
  const others = list.filter((session) => !session.current).length;

  const confirm = () => {
    if (target === 'others') {
      revokeOthers.mutate(undefined, {
        onSuccess: (result) => {
          toast.success(`${formatNumber(result?.revoked ?? others)} other session${(result?.revoked ?? others) === 1 ? '' : 's'} signed out`);
          setTarget(null);
        },
      });
    } else if (target) {
      const current = target.current;
      revoke.mutate(target.id, {
        onSuccess: () => {
          setTarget(null);
          if (current) void logout();
          else toast.success('Session revoked');
        },
      });
    }
  };

  const pending = revoke.isPending || revokeOthers.isPending;
  const error = revoke.error ?? revokeOthers.error;

  return (
    <Panel
      id="sessions"
      title={
        <span className="inline-flex items-center gap-2">
          <ShieldCheck size={15} className="gold-text" /> Sessions
        </span>
      }
      subtitle="Devices signed in to your account"
      actions={
        others > 0 ? (
          <button type="button" className="subtle-btn compact" onClick={() => setTarget('others')}>
            <LogOut size={12} aria-hidden="true" /> Sign out other sessions
          </button>
        ) : undefined
      }
    >
      {sessions.isLoading && <LoadingState rows={3} label="Loading sessions…" />}
      {sessions.isError && <ErrorState error={sessions.error} onRetry={() => void sessions.refetch()} compact />}
      {sessions.data && list.length === 0 && <EmptyState title="No active sessions" />}
      {list.length > 0 && (
        <ul className="m-0 list-none p-0" aria-label="Active sessions">
          {list.map((session) => (
            <SessionRow key={session.id} session={session} onRevoke={setTarget} />
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={target !== null}
        onClose={() => {
          setTarget(null);
          revoke.reset();
          revokeOthers.reset();
        }}
        title={target === 'others' ? 'Sign out every other session?' : target?.current ? 'Sign out of this device?' : 'Revoke this session?'}
        message={
          target === 'others'
            ? `Every device except this one (${formatNumber(others)}) is signed out and must sign in again.`
            : target?.current
              ? 'You will be signed out here.'
              : `${target ? deviceLabel(target) : 'The device'} must sign in again.`
        }
        confirmLabel={target === 'others' ? 'Sign out others' : 'Revoke'}
        tone="danger"
        pending={pending}
        error={error ? errorMessage(error) : null}
        onConfirm={confirm}
      />
    </Panel>
  );
}

/** Users admin drawer: a user's active sessions; admins sign the user out everywhere. */
export function UserSessionsSection({ userId, userName, canRevoke }: { userId: number; userName: string; canRevoke: boolean }) {
  const toast = useToast();
  const sessions = useUserSessions(userId);
  const revoke = useRevokeUserSessions(userId);
  const [confirming, setConfirming] = useState(false);
  const list = sessions.data ?? [];

  return (
    <div className="drawer-section">
      <h3 className="flex items-center justify-between gap-2">
        <span>Active sessions{sessions.data ? ` (${formatNumber(list.length)})` : ''}</span>
        {canRevoke && list.length > 0 && (
          <button type="button" className="danger-btn compact" onClick={() => setConfirming(true)}>
            <LogOut size={12} aria-hidden="true" /> Sign out everywhere
          </button>
        )}
      </h3>
      {sessions.isLoading && <LoadingState rows={2} label="Loading sessions…" />}
      {sessions.isError && <ErrorState error={sessions.error} onRetry={() => void sessions.refetch()} compact />}
      {sessions.data && list.length === 0 && <p>No active sessions.</p>}
      {list.length > 0 && (
        <ul className="m-0 list-none p-0" aria-label={`Sessions of ${userName}`}>
          {list.map((session) => (
            <SessionRow key={session.id} session={session} />
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={confirming}
        onClose={() => {
          setConfirming(false);
          revoke.reset();
        }}
        title={`Sign ${userName} out everywhere?`}
        message="Every active session of this user ends; they must sign in again on each device."
        confirmLabel="Sign out everywhere"
        tone="danger"
        pending={revoke.isPending}
        error={revoke.isError ? errorMessage(revoke.error) : null}
        onConfirm={() =>
          revoke.mutate(undefined, {
            onSuccess: (result) => {
              toast.success(`${formatNumber(result?.revoked ?? list.length)} session${(result?.revoked ?? list.length) === 1 ? '' : 's'} revoked`);
              setConfirming(false);
            },
          })
        }
      />
    </div>
  );
}
