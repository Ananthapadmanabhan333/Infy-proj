import { Link } from 'react-router-dom';
import { Bell, Building2, Briefcase, ChevronRight, KeyRound, Mail, Megaphone, ShieldCheck, SlidersHorizontal } from 'lucide-react';
import { useUnreadCount } from '../api/notifications';
import { ChangePasswordForm } from '../components/auth/ChangePasswordForm';
import { SessionsPanel } from '../components/profile/SessionsPanel';
import { Avatar } from '../components/Avatar';
import { DemoBadge, RoleBadge } from '../components/Badge';
import { PageHeader } from '../components/PageHeader';
import { Panel } from '../components/Panel';
import { useToast } from '../components/Toast';
import { useAuth } from '../hooks/useAuth';
import { useNotificationStreamState } from '../hooks/useNotificationStream';
import { ROLE_DESCRIPTIONS, roleLabel } from '../lib/constants';
import { formatNumber } from '../lib/format';
import '../components/admin/admin.css';

function Shortcut({ to, icon, title, caption }: { to: string; icon: JSX.Element; title: string; caption: string }) {
  return (
    <Link to={to} className="list-row !py-2.5 text-inherit no-underline hover:text-inherit">
      <span className="icon-tile sm" aria-hidden="true">
        {icon}
      </span>
      <div>
        <b>{title}</b>
        <small>{caption}</small>
      </div>
      <ChevronRight size={14} className="text-muted" aria-hidden="true" />
    </Link>
  );
}

/** /profile: the signed-in user's account details and password change. */
export default function ProfilePage() {
  const { user, reloadUser } = useAuth();
  const toast = useToast();
  const stream = useNotificationStreamState();
  const unread = useUnreadCount(stream.pollInterval);
  if (!user) return null;
  return (
    <>
      <PageHeader title="Your profile" subtitle="Your account details and password" />
      <div className="grid items-start gap-[14px] lg:grid-cols-2">
        <div className="grid gap-[14px]">
          <Panel title="Account" subtitle="Managed by your SA administrator">
            <div className="profile-hero">
              <Avatar name={user.fullName} size="lg" className="xl" />
              <div className="min-w-0">
                <b className="block font-heading text-[17px] text-ivory">{user.fullName}</b>
                <small className="inline-flex items-center gap-1.5 text-[11px] text-muted">
                  <Mail size={12} /> {user.email}
                </small>
                <div className="mt-2 flex flex-wrap gap-1.5">
                  <RoleBadge role={user.role} />
                  <DemoBadge demo={user.demo} />
                </div>
              </div>
            </div>
            <div className="detail-grid">
              <div>
                <small>
                  <Building2 size={10} className="mr-1 inline" /> Department
                </small>
                <b>{user.departmentName ?? '—'}</b>
              </div>
              <div>
                <small>
                  <Briefcase size={10} className="mr-1 inline" /> Job title
                </small>
                <b>{user.jobTitle ?? '—'}</b>
              </div>
              <div className="sm:col-span-2">
                <small>
                  <ShieldCheck size={10} className="mr-1 inline" /> Role · {roleLabel(user.role)}
                </small>
                <b className="font-normal text-[#d6cab8]">{ROLE_DESCRIPTIONS[user.role] ?? '—'}</b>
              </div>
              {user.organisation?.name && (
                <div className="sm:col-span-2">
                  <small>Organisation</small>
                  <b>{user.organisation.name}</b>
                </div>
              )}
            </div>
            <p className="m-0 text-[10px] text-muted">Name, role and department can only be changed by an IT Admin or System Admin.</p>
          </Panel>
          <Panel title="Shortcuts">
            <Shortcut
              to="/notifications"
              icon={<Bell size={15} />}
              title="Notifications"
              caption={unread.data === undefined ? 'Your inbox' : `${formatNumber(unread.data)} unread`}
            />
            <Shortcut to="/notifications#preferences" icon={<SlidersHorizontal size={15} />} title="Notification preferences" caption="Choose which in-app and e-mail alerts you receive" />
            <Shortcut to="/announcements" icon={<Megaphone size={15} />} title="Announcements" caption="Operational notices and planned maintenance" />
          </Panel>
        </div>
        <Panel
          title={
            <span className="inline-flex items-center gap-2">
              <KeyRound size={15} className="gold-text" /> Change password
            </span>
          }
          subtitle="At least 10 characters with letters and digits"
        >
          {user.mustChangePassword && (
            <div className="callout mb-3">
              <KeyRound size={16} />
              <div>
                <b>Password change required.</b> Your administrator issued a temporary password — choose a new one to continue.
              </div>
            </div>
          )}
          <ChangePasswordForm
            onChanged={async () => {
              toast.success('Password changed');
              await reloadUser();
            }}
          />
        </Panel>
        <div className="lg:col-span-2">
          <SessionsPanel />
        </div>
      </div>
    </>
  );
}
