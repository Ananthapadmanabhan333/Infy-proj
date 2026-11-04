import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { AlertTriangle, Building2, Clock3, Mail, Save } from 'lucide-react';
import { useOrganisationSettings, useUpdateSetting } from '../../api/admin';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { Panel } from '../../components/Panel';
import { SettingsList } from '../../components/settings/SettingRow';
import { ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useNow } from '../../hooks/useNow';
import { ORG_SETTING_KEYS } from '../../lib/constants';
import { emailSchema } from '../../lib/schemas';

const organisationSchema = z.object({
  name: z.string().trim().min(2, 'Organisation name is required').max(120, 'At most 120 characters'),
  supportEmail: z.union([z.literal(''), emailSchema]),
  timezone: z.string().trim().min(1, 'Choose a timezone').max(64),
  businessHoursNote: z.string().trim().max(500, 'At most 500 characters'),
});
type OrganisationValues = z.infer<typeof organisationSchema>;
type OrgField = keyof OrganisationValues;

const FIELD_KEYS: Record<OrgField, string> = {
  name: ORG_SETTING_KEYS.name,
  supportEmail: ORG_SETTING_KEYS.supportEmail,
  timezone: ORG_SETTING_KEYS.timezone,
  businessHoursNote: ORG_SETTING_KEYS.businessHoursNote,
};

/** IANA zones supported by the browser (plus the stored value, so it is never dropped). */
export function timezoneOptions(current: string): string[] {
  let zones: string[] = [];
  try {
    zones = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [];
  } catch {
    zones = [];
  }
  const all = new Set(zones);
  if (current) all.add(current);
  if (!all.has('UTC')) all.add('UTC');
  return [...all].sort();
}

function localTime(now: number, timezone: string): string | null {
  if (!timezone) return null;
  try {
    return new Intl.DateTimeFormat('en-GB', { timeZone: timezone, weekday: 'short', hour: '2-digit', minute: '2-digit' }).format(now);
  } catch {
    return null;
  }
}

function OrganisationPreview({ values }: { values: OrganisationValues }) {
  const now = useNow();
  const time = localTime(now, values.timezone);
  return (
    <Panel title="Preview" subtitle="How the organisation appears to users" raised>
      <div className="flex items-center gap-3">
        <span className="icon-tile" aria-hidden="true">
          <Building2 size={18} />
        </span>
        <div className="min-w-0">
          <b className="block truncate font-heading text-[14px] text-ivory">{values.name || 'Organisation name'}</b>
          <small className="text-[10px] text-muted">Shown in the sidebar and on exports</small>
        </div>
      </div>
      <div className="mt-3 grid gap-2 text-[11px] text-[#d6cab8]">
        <span className="inline-flex items-center gap-2">
          <Mail size={13} className="gold-text" /> {values.supportEmail || <span className="muted-text">No support e-mail</span>}
        </span>
        <span className="inline-flex items-center gap-2">
          <Clock3 size={13} className="gold-text" />
          {time ? (
            <>
              {time} <span className="muted-text">({values.timezone})</span>
            </>
          ) : (
            <span className="muted-text">{values.timezone ? 'Unknown timezone' : 'No timezone'}</span>
          )}
        </span>
      </div>
      {values.businessHoursNote && <p className="description-text mt-3 text-[11px]">{values.businessHoursNote}</p>}
    </Panel>
  );
}

function OrganisationForm({ initial, available }: { initial: OrganisationValues; available: Set<string> }) {
  const update = useUpdateSetting();
  const { reloadUser } = useAuth();
  const toast = useToast();
  const [saving, setSaving] = useState(false);
  const {
    register,
    handleSubmit,
    control,
    reset,
    formState: { errors, isDirty, dirtyFields },
  } = useForm<OrganisationValues>({ resolver: zodResolver(organisationSchema), defaultValues: initial });
  useEffect(() => reset(initial), [initial, reset]);
  const values = useWatch({ control }) as OrganisationValues;
  const zones = useMemo(() => timezoneOptions(initial.timezone), [initial.timezone]);
  const missing = (field: OrgField) => !available.has(FIELD_KEYS[field]);

  const onSubmit = handleSubmit(async (next) => {
    const changed = (Object.keys(FIELD_KEYS) as OrgField[]).filter((field) => dirtyFields[field] && !missing(field) && next[field].trim() !== initial[field]);
    if (!changed.length) return;
    setSaving(true);
    try {
      for (const field of changed) {
        await update.mutateAsync({ key: FIELD_KEYS[field], value: next[field].trim() });
      }
      toast.success('Organisation settings saved');
      if (changed.includes('name')) void reloadUser();
    } catch (error) {
      toast.error(error);
    } finally {
      setSaving(false);
    }
  });

  return (
    <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
      <Panel title="Organisation profile" subtitle="Changes take effect immediately and are recorded as SETTINGS_CHANGED in the audit log">
        <form onSubmit={onSubmit} noValidate>
          <div className="form-row">
            <TextField
              label="Organisation name"
              required
              disabled={missing('name')}
              hint={missing('name') ? 'Not available from the API yet' : undefined}
              error={errors.name?.message}
              {...register('name')}
            />
            <TextField
              label="Support e-mail"
              type="email"
              placeholder="it-support@example.com"
              disabled={missing('supportEmail')}
              hint={missing('supportEmail') ? 'Not available from the API yet' : 'Shown to employees when they need help'}
              error={errors.supportEmail?.message}
              {...register('supportEmail')}
            />
          </div>
          <SelectField
            label="Timezone"
            disabled={missing('timezone')}
            hint={missing('timezone') ? 'Not available from the API yet' : 'Default timezone for reports and business-hours calendars'}
            error={errors.timezone?.message}
            {...register('timezone')}
          >
            <option value="">Choose a timezone</option>
            {zones.map((zone) => (
              <option key={zone} value={zone}>
                {zone}
              </option>
            ))}
          </SelectField>
          <TextareaField
            label="Business hours note"
            rows={3}
            placeholder="Service desk staffed Monday–Friday, 09:00–18:00 IST. Critical incidents are handled 24×7."
            disabled={missing('businessHoursNote')}
            hint={missing('businessHoursNote') ? 'Not available from the API yet' : 'Shown next to SLA targets and on the service desk'}
            error={errors.businessHoursNote?.message}
            {...register('businessHoursNote')}
          />
          <div className="mt-3 flex flex-wrap justify-end gap-2">
            <button type="button" className="subtle-btn" disabled={!isDirty || saving} onClick={() => reset(initial)}>
              Discard changes
            </button>
            <button type="submit" className="gold-btn" disabled={!isDirty || saving}>
              <Save size={14} /> {saving ? 'Saving…' : 'Save organisation'}
            </button>
          </div>
        </form>
      </Panel>
      <OrganisationPreview values={values} />
    </div>
  );
}

/** Settings → Organisation (SETTINGS): `org.*` keys as a form, plus the remaining system settings. */
export function OrganisationTab() {
  const settings = useOrganisationSettings();
  const available = useMemo(() => new Set((settings.data ?? []).map((setting) => setting.key)), [settings.data]);
  const anyOrgKey = Object.values(ORG_SETTING_KEYS).some((key) => available.has(key));
  const initial = useMemo<OrganisationValues | null>(() => (settings.organisation ? { ...settings.organisation } : null), [settings.organisation?.name, settings.organisation?.supportEmail, settings.organisation?.timezone, settings.organisation?.businessHoursNote]);

  return (
    <>
      {settings.isLoading && <LoadingState variant="panel" label="Loading organisation settings…" />}
      {settings.isError && <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />}
      {settings.data && !anyOrgKey && (
        <div className="callout">
          <AlertTriangle size={16} />
          <div>
            <b>Organisation settings are not available from the API yet.</b> The <code>org.*</code> keys appear here once the server exposes them.
          </div>
        </div>
      )}
      {initial && anyOrgKey && <OrganisationForm initial={initial} available={available} />}
      <SettingsList title="Advanced system settings" subtitle="Duplicate detection, assignment and escalation tuning. Changes are audited." exclude={['org.', 'notifications.']} />
    </>
  );
}
