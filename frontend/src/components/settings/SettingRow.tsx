import { useEffect, useState } from 'react';
import { Save } from 'lucide-react';
import { useSettings, useUpdateSetting } from '../../api/admin';
import { formatDateTime, titleCase } from '../../lib/format';
import type { SettingDto } from '../../lib/types';
import { Toggle } from '../Controls';
import { Panel } from '../Panel';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';

/** Human label for a system_settings key: "incident.duplicate.window_hours" → "Duplicate window hours". */
export function settingLabel(key: string): string {
  const parts = key.split('.');
  const tail = parts.length > 1 ? parts.slice(1).join(' ') : key;
  return titleCase(tail.replace(/\./g, ' '));
}

/** One editable system setting (SETTINGS write). Booleans save immediately; text/number save on demand. */
export function SettingRow({ setting, label, description }: { setting: SettingDto; label?: string; description?: string }) {
  const [value, setValue] = useState(setting.value);
  const update = useUpdateSetting();
  const toast = useToast();
  useEffect(() => setValue(setting.value), [setting.value]);
  const dirty = value !== setting.value;
  const invalid = setting.type === 'NUMBER' && (value.trim() === '' || Number.isNaN(Number(value)));
  const name = label ?? settingLabel(setting.key);

  const persist = (next: string) =>
    update.mutate(
      { key: setting.key, value: next },
      {
        onSuccess: () => toast.success(`${name} updated`),
        onError: (error) => {
          setValue(setting.value);
          toast.error(error);
        },
      },
    );

  return (
    <div className="setting-row flex-wrap">
      <div className="min-w-[220px]">
        <b>{name}</b>
        <small>{description ?? setting.description ?? titleCase(setting.type)}</small>
        <small>
          <code>{setting.key}</code> · updated {formatDateTime(setting.updatedAt)}
        </small>
      </div>
      {setting.type === 'BOOLEAN' ? (
        <Toggle
          checked={value === 'true'}
          label={name}
          disabled={update.isPending}
          onChange={(next) => {
            setValue(String(next));
            persist(String(next));
          }}
        />
      ) : (
        <span className="flex items-center gap-2">
          <input
            className="control sm w-[200px] max-w-full"
            type={setting.type === 'NUMBER' ? 'number' : 'text'}
            value={value}
            aria-label={name}
            aria-invalid={invalid || undefined}
            onChange={(event) => setValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && dirty && !invalid) persist(value.trim());
            }}
          />
          <button type="button" className="gold-btn compact" disabled={!dirty || invalid || update.isPending} onClick={() => persist(value.trim())}>
            <Save size={12} /> Save
          </button>
        </span>
      )}
    </div>
  );
}

/** System settings filtered by key prefix; `exclude` drops keys handled by another tab. */
export function SettingsList({
  title,
  subtitle,
  include,
  exclude = [],
  empty = 'No settings exposed',
}: {
  title: string;
  subtitle?: string;
  include?: (key: string) => boolean;
  exclude?: string[];
  empty?: string;
}) {
  const settings = useSettings();
  const rows = (settings.data ?? []).filter((setting) => (!include || include(setting.key)) && !exclude.some((prefix) => setting.key.startsWith(prefix)));
  return (
    <Panel title={title} subtitle={subtitle ?? 'Changes take effect immediately and are audited'}>
      {settings.isLoading && <LoadingState rows={4} label="Loading settings…" />}
      {settings.isError && <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />}
      {settings.data && rows.length === 0 && <EmptyState title={empty} />}
      {rows.map((setting) => (
        <SettingRow key={setting.key} setting={setting} />
      ))}
    </Panel>
  );
}
