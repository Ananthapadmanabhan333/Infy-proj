import { Link } from 'react-router-dom';
import { BellRing, Info } from 'lucide-react';
import { useSettings } from '../../api/admin';
import { Panel } from '../../components/Panel';
import { SettingRow } from '../../components/settings/SettingRow';
import { ErrorState, LoadingState } from '../../components/States';
import { NOTIFICATION_DEFAULT_KEYS } from '../../lib/constants';

/** Known §13 notification defaults with plain-language labels. */
export const NOTIFICATION_DEFAULTS: { key: string; label: string; description: string }[] = [
  {
    key: NOTIFICATION_DEFAULT_KEYS.slaAtRiskNotifyManagers,
    label: 'Notify managers when an SLA is at risk',
    description: 'IT and incident managers are alerted in-app when an incident reaches its at-risk threshold, in addition to the assignee and team.',
  },
  {
    key: NOTIFICATION_DEFAULT_KEYS.majorNotifyAllStaff,
    label: 'Notify all staff about major incidents',
    description: 'Every staff member receives an in-app alert when an incident is declared major.',
  },
];

/** Settings → Notifications (SETTINGS): organisation-wide notification defaults (`notifications.*`). */
export function NotificationDefaultsTab() {
  const settings = useSettings();
  const byKey = new Map((settings.data ?? []).map((setting) => [setting.key, setting]));
  const known = new Set(NOTIFICATION_DEFAULTS.map((entry) => entry.key));
  const others = (settings.data ?? []).filter((setting) => setting.key.startsWith('notifications.') && !known.has(setting.key));

  return (
    <div className="grid gap-[14px] xl:grid-cols-[1.55fr_1fr]">
      <Panel
        title={
          <span className="inline-flex items-center gap-2">
            <BellRing size={15} className="gold-text" /> Notification defaults
          </span>
        }
        subtitle="Who is notified by default. Changes apply to new notifications and are audited."
      >
        {settings.isLoading && <LoadingState rows={3} label="Loading notification defaults…" />}
        {settings.isError && <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />}
        {settings.data &&
          NOTIFICATION_DEFAULTS.map((entry) => {
            const setting = byKey.get(entry.key);
            if (setting) return <SettingRow key={entry.key} setting={setting} label={entry.label} description={entry.description} />;
            return (
              <div key={entry.key} className="setting-row">
                <div>
                  <b>{entry.label}</b>
                  <small>{entry.description}</small>
                  <small>
                    <code>{entry.key}</code> · not available from the API yet
                  </small>
                </div>
                <span className="chip">Unavailable</span>
              </div>
            );
          })}
        {others.map((setting) => (
          <SettingRow key={setting.key} setting={setting} />
        ))}
      </Panel>
      <Panel title="How notifications work" raised>
        <div className="callout">
          <Info size={16} />
          <div>
            Notifications are delivered in-app (bell and inbox) in real time. Each user can still switch off individual types under{' '}
            <Link className="gold-text" to="/notifications#preferences">
              Notifications → Preferences
            </Link>
            ; disabled types are not created for that user.
          </div>
        </div>
      </Panel>
    </div>
  );
}
