import { useEffect, useMemo, useState } from 'react';
import { Mail, RotateCcw, Save, SlidersHorizontal } from 'lucide-react';
import { useSystemHealth } from '../../api/admin';
import { useNotificationPreferences, useUpdateNotificationPreferences } from '../../api/notifications';
import { useStatusPageView } from '../../api/statusPageAdmin';
import { useAuth } from '../../hooks/useAuth';
import { HEALTH_READERS, NOTIFICATION_CATEGORY_LABELS } from '../../lib/constants';
import { formatNumber, titleCase } from '../../lib/format';
import type { NotificationPreferencesUpdate, NotificationType } from '../../lib/types';
import { Toggle } from '../Controls';
import { Panel } from '../Panel';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';

type Channels = { inApp: boolean; email: boolean };

/**
 * Whether the deployment can send e-mail: system health for admins, otherwise the status page view (any signed-in
 * user). `null` while unknown — the e-mail column then stays usable.
 */
export function useEmailConfigured(): boolean | null {
  const { hasRole } = useAuth();
  const admin = hasRole(...HEALTH_READERS);
  const health = useSystemHealth(admin);
  const statusPage = useStatusPageView(!admin);
  if (admin) return health.data?.email ? health.data.email.configured : null;
  return typeof statusPage.data?.emailConfigured === 'boolean' ? statusPage.data.emailConfigured : null;
}

/** Current user's notification preferences (§12, R3 e-mail channel): one toggle per type and channel, saved together. */
export function NotificationPreferencesPanel() {
  const preferences = useNotificationPreferences();
  const update = useUpdateNotificationPreferences();
  const toast = useToast();
  const list = useMemo(() => preferences.data ?? [], [preferences.data]);
  // Release 3 backends return the `email` channel; older ones only `inApp`.
  const hasEmail = list.some((preference) => typeof preference.email === 'boolean');
  const emailConfigured = useEmailConfigured();
  const emailDisabled = emailConfigured === false;

  const saved = useMemo(
    () => Object.fromEntries(list.map((preference) => [preference.type, { inApp: preference.inApp, email: Boolean(preference.email) }])) as Record<NotificationType, Channels>,
    [list],
  );
  const [draft, setDraft] = useState<Record<NotificationType, Channels>>(saved);
  useEffect(() => setDraft(saved), [saved]);

  const value = (type: NotificationType): Channels => draft[type] ?? { inApp: false, email: false };
  const dirty = list.some((preference) => value(preference.type).inApp !== preference.inApp || (hasEmail && value(preference.type).email !== Boolean(preference.email)));
  const enabledCount = list.filter((preference) => value(preference.type).inApp).length;
  const emailCount = list.filter((preference) => value(preference.type).email).length;

  const set = (type: NotificationType, channel: keyof Channels, next: boolean) => setDraft((current) => ({ ...current, [type]: { ...value(type), ...current[type], [channel]: next } }));

  const save = () => {
    const body: NotificationPreferencesUpdate = {};
    list.forEach((preference) => {
      const channels = value(preference.type);
      body[preference.type] = hasEmail ? { inApp: Boolean(channels.inApp), email: Boolean(channels.email) } : Boolean(channels.inApp);
    });
    update.mutate(body, {
      onSuccess: () => toast.success('Notification preferences saved'),
      onError: (error) => toast.error(error),
    });
  };

  const subtitle = preferences.data
    ? `${formatNumber(enabledCount)} of ${formatNumber(list.length)} types in-app${hasEmail ? `, ${formatNumber(emailCount)} by e-mail` : ''}. Disabled channels are not delivered.`
    : 'Choose which notifications you receive';

  return (
    <Panel
      id="preferences"
      title={
        <span className="inline-flex items-center gap-2">
          <SlidersHorizontal size={15} className="gold-text" /> Preferences
        </span>
      }
      subtitle={subtitle}
    >
      {preferences.isLoading && <LoadingState rows={6} label="Loading preferences…" />}
      {preferences.isError && <ErrorState error={preferences.error} onRetry={() => void preferences.refetch()} compact />}
      {preferences.data && list.length === 0 && <EmptyState title="No notification types to configure" />}
      {hasEmail && emailDisabled && (
        <div className="callout mb-2" role="note">
          <Mail size={16} aria-hidden="true" />
          <div>
            <b>E-mail is not configured on this server.</b> The e-mail channel is unavailable until an administrator sets up SMTP; your choices are kept and apply once it is.
          </div>
        </div>
      )}
      {list.length > 0 && hasEmail && (
        <div className="setting-row !py-1 text-[10px] font-semibold uppercase tracking-wide text-muted" aria-hidden="true">
          <div>Notification</div>
          <span className="flex gap-6">
            <span className="w-10 text-center">In-app</span>
            <span className="w-10 text-center">E-mail</span>
          </span>
        </div>
      )}
      {list.map((preference) => {
        const label = preference.label || NOTIFICATION_CATEGORY_LABELS[preference.type] || titleCase(preference.type);
        return (
          <div key={preference.type} className="setting-row">
            <div>
              <b>{label}</b>
              {preference.description && <small>{preference.description}</small>}
            </div>
            {hasEmail ? (
              <span className="flex shrink-0 gap-6">
                <span className="flex w-10 justify-center">
                  <Toggle checked={value(preference.type).inApp} label={`${label} in-app`} disabled={update.isPending} onChange={(next) => set(preference.type, 'inApp', next)} />
                </span>
                <span className="flex w-10 justify-center" title={emailDisabled ? 'E-mail is not configured on this server' : preference.emailDefault ? 'On by default' : undefined}>
                  <Toggle checked={value(preference.type).email} label={`${label} e-mail`} disabled={update.isPending || emailDisabled} onChange={(next) => set(preference.type, 'email', next)} />
                </span>
              </span>
            ) : (
              <Toggle checked={value(preference.type).inApp} label={label} disabled={update.isPending} onChange={(next) => set(preference.type, 'inApp', next)} />
            )}
          </div>
        );
      })}
      {list.length > 0 && (
        <div className="mt-3 flex flex-wrap justify-end gap-2">
          <button type="button" className="subtle-btn compact" disabled={!dirty || update.isPending} onClick={() => setDraft(saved)}>
            <RotateCcw size={13} /> Reset
          </button>
          <button type="button" className="gold-btn compact" disabled={!dirty || update.isPending} onClick={save}>
            <Save size={13} /> {update.isPending ? 'Saving…' : 'Save preferences'}
          </button>
        </div>
      )}
    </Panel>
  );
}
