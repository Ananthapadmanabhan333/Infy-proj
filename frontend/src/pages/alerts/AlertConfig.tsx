import { useMemo, useState } from 'react';
import { Copy, KeyRound, Pencil, Plus, Radio, Route, ShieldOff, Trash2 } from 'lucide-react';
import {
  useAlertSourceDeliveries,
  useAlertSources,
  useDeleteRoutingRule,
  useDeleteSuppressionRule,
  useRotateAlertSourceSecret,
  useRoutingRules,
  useSaveAlertSource,
  useSaveRoutingRule,
  useSaveSuppressionRule,
  useSuppressionRules,
} from '../../api/alerts';
import { useServices } from '../../api/services';
import { useTeams } from '../../api/teams';
import { BooleanBadge, DemoBadge } from '../../components/Badge';
import { describeMatch, describeWindow, draftFromMatch, matchFromDraft, type MatchDraft } from '../../components/alerts/matchCriteria';
import { MatchCriteriaEditor } from '../../components/alerts/MatchCriteriaEditor';
import { CiSinglePicker, type CiChoice } from '../../components/cmdb/CiPicker';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { CheckboxField, SelectField, TextareaField, TextField } from '../../components/FormField';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { SeverityBadge } from '../../components/ops/OpsParts';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { apiOrigin, errorMessage, toApiError } from '../../lib/api';
import { formatDateTime, formatNumber, formatRelative, isoToLocalInput, localInputToIso, titleCase } from '../../lib/format';
import { ALERT_PROVIDERS, ALERT_SEVERITIES, ALERT_SOURCE_LABELS, WEEKDAY_SHORT } from '../../lib/ops';
import type { AlertProvider, AlertSeverity, AlertSourceDto, RoutingRuleDto, SuppressionRuleDto } from '../../lib/opsTypes';

const TOPIC_ARN = /^arn:aws:sns:[a-z0-9-]+:\d{12}:[A-Za-z0-9_-]{1,256}$/;

export function webhookUrl(path: string): string {
  return `${apiOrigin()}${path}`;
}

function useCopy() {
  const toast = useToast();
  return (value: string) =>
    void navigator.clipboard?.writeText(value).then(
      () => toast.success('Copied'),
      () => toast.error('Copy failed — select the text instead'),
    );
}

function CopyField({ id, label, value }: { id: string; label: string; value: string }) {
  const copy = useCopy();
  return (
    <div className="field">
      <div className="field-label">
        <label htmlFor={id}>{label}</label>
      </div>
      <div className="flex gap-2">
        <input id={id} className="control font-mono" readOnly value={value} onFocus={(event) => event.target.select()} />
        <button type="button" className="icon-btn" aria-label={`Copy ${label.toLowerCase()}`} onClick={() => copy(value)}>
          <Copy size={14} />
        </button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ adapter setup

/** Setup instructions per provider with the exact endpoint and authentication (docs/API.md "Webhooks"). */
export function adapterGuide(provider: AlertProvider, url: string, topicArn?: string | null): { title: string; steps: string[]; headers: string[]; snippet: string } {
  switch (provider) {
    case 'PROMETHEUS':
      return {
        title: 'Prometheus Alertmanager',
        steps: ['Add a receiver with a webhook config pointing at the endpoint.', 'Send the secret as a bearer credential (or HTTP Basic auth with the secret as the password).', 'Keep send_resolved: true so resolved alerts close here.'],
        headers: ['Authorization: Bearer <secret>'],
        snippet: `receivers:\n  - name: sa\n    webhook_configs:\n      - url: ${url}\n        send_resolved: true\n        http_config:\n          authorization:\n            type: Bearer\n            credentials: <secret>\nroute:\n  receiver: sa`,
      };
    case 'GRAFANA':
      return {
        title: 'Grafana alerting',
        steps: [
          'Alerting → Contact points → New contact point → Webhook.',
          'URL: the endpoint, HTTP method POST. Use the contact point in a notification policy.',
          'Authenticate with either option (one is enough): the HMAC signature (Optional Webhook settings → HMAC Signature: secret = the source secret, header X-Grafana-Alerting-Signature) or an Authorization header with scheme Bearer and credentials = the secret.',
          'HMAC signature: the header carries the hex HMAC-SHA256 of the raw body with the secret. If you also set a timestamp header (X-Grafana-Alerting-Timestamp), Grafana signs "timestamp:body" instead and the timestamp must be within ±5 minutes.',
        ],
        headers: [
          'X-Grafana-Alerting-Signature: <hex HMAC-SHA256(secret, raw body)>',
          'X-Grafana-Alerting-Timestamp: <unix seconds> (optional; the signature then covers timestamp + ":" + raw body, ±5 min)',
          'Authorization: Bearer <secret>',
        ],
        snippet: `Integration: Webhook\nURL: ${url}\nHTTP method: POST\n\n# Option 1 — HMAC signature (Optional Webhook settings → HMAC Signature)\nSecret: <secret>\nHeader: X-Grafana-Alerting-Signature\nTimestamp header (optional): X-Grafana-Alerting-Timestamp\n\n# Option 2 — Authorization header\nAuthorization header — scheme: Bearer\nAuthorization header — credentials: <secret>`,
      };
    case 'CLOUDWATCH':
      return {
        title: 'AWS CloudWatch via SNS',
        steps: [
          `Create (or reuse) the SNS topic ${topicArn ?? '<topic ARN of this source>'}.`,
          'Add an HTTPS subscription with the endpoint as URL; SA confirms the subscription automatically (status below).',
          'Point the CloudWatch alarm actions (ALARM and OK) at the topic. Optional tags in the alarm description: severity:critical service:<code> ci:<name>.',
        ],
        headers: ['No secret: every message is verified with its SNS signature (certificate from sns.<region>.amazonaws.com) and the topic ARN.'],
        snippet: `aws sns subscribe \\\n  --topic-arn ${topicArn ?? '<topic ARN>'} \\\n  --protocol https \\\n  --notification-endpoint ${url}`,
      };
    default:
      return {
        title: 'Generic signed JSON',
        steps: [
          'POST one alert, or {"alerts": [...]} (≤ 200), with the manual-alert fields; use "service" (service code) and "ci" (CI name) instead of ids.',
          'Sign timestamp + "." + raw body with HMAC-SHA256 and the secret; the timestamp must be within ±5 minutes.',
          'X-Nexus-Delivery must be unique per delivery (replays are answered DUPLICATE). Send "status": "resolved" to resolve.',
        ],
        headers: ['X-Nexus-Timestamp: <unix seconds>', 'X-Nexus-Delivery: <unique id>', 'X-Nexus-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + raw body)>', 'X-Nexus-Event: <optional event name>'],
        snippet: `URL='${url}'\nSECRET='<secret>'\nBODY='{"title":"Disk 90% full on erp-app-01","severity":"MAJOR","service":"ERP","ci":"erp-app-01","labels":{"alertname":"DiskFull"}}'\nTS=$(date +%s)\nSIG=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')\ncurl -X POST "$URL" -H 'Content-Type: application/json' \\\n  -H "X-Nexus-Timestamp: $TS" -H "X-Nexus-Delivery: $(uuidgen)" \\\n  -H "X-Nexus-Signature: sha256=$SIG" -d "$BODY"`,
      };
  }
}

function AdapterGuide({ source }: { source: AlertSourceDto }) {
  const url = webhookUrl(source.webhookPath);
  const guide = adapterGuide(source.provider, url, source.topicArn);
  return (
    <div className="grid gap-2 text-[11px]" aria-label={`Setup for ${source.name}`}>
      <b>{guide.title} setup</b>
      <code className="break-all">POST {url}</code>
      <ol className="m-0 grid gap-1 pl-4">
        {guide.steps.map((step) => (
          <li key={step}>{step}</li>
        ))}
      </ol>
      <div>
        <small className="text-muted">Authentication</small>
        <ul className="m-0 pl-4">
          {guide.headers.map((header) => (
            <li key={header} className="break-all">
              <code>{header}</code>
            </li>
          ))}
        </ul>
      </div>
      <pre className="code-block">{guide.snippet}</pre>
    </div>
  );
}

// ------------------------------------------------------------------ sources

function SecretModal({ secret, source, onClose }: { secret: string; source: AlertSourceDto; onClose: () => void }) {
  return (
    <Modal
      open
      onClose={onClose}
      title={`Secret for ${source.name}`}
      eyebrow="SHOWN ONCE"
      description="Store it in the monitoring system now. It is kept encrypted and cannot be shown again — rotate it if it is lost."
      size="lg"
      footer={
        <button type="button" className="gold-btn" onClick={onClose}>
          I have stored the secret
        </button>
      }
    >
      <CopyField id="alert-source-secret" label="Secret" value={secret} />
      <CopyField id="alert-source-url" label="Webhook URL" value={webhookUrl(source.webhookPath)} />
      <AdapterGuide source={source} />
    </Modal>
  );
}

function SourceEditor({ source, onClose, onSecret }: { source: AlertSourceDto | null; onClose: () => void; onSecret: (secret: string, source: AlertSourceDto) => void }) {
  const save = useSaveAlertSource();
  const services = useServices();
  const toast = useToast();
  const [name, setName] = useState(source?.name ?? '');
  const [provider, setProvider] = useState<AlertProvider>(source?.provider ?? 'PROMETHEUS');
  const [serviceId, setServiceId] = useState(source?.defaultServiceId ? String(source.defaultServiceId) : '');
  const [ci, setCi] = useState<CiChoice | null>(source?.defaultCiId ? { id: source.defaultCiId, name: source.defaultCiName ?? `CI #${source.defaultCiId}` } : null);
  const [severity, setSeverity] = useState<string>(source?.defaultSeverity ?? '');
  const [topicArn, setTopicArn] = useState(source?.topicArn ?? '');
  const [active, setActive] = useState(source?.active ?? true);
  const errors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field)?.message;
  const cloudwatch = provider === 'CLOUDWATCH';
  const arnInvalid = cloudwatch && !TOPIC_ARN.test(topicArn.trim());

  const submit = () =>
    save.mutate(
      {
        id: source?.id,
        body: {
          name: name.trim(),
          provider,
          defaultServiceId: serviceId ? Number(serviceId) : null,
          defaultCiId: ci?.id ?? null,
          defaultSeverity: (severity || null) as AlertSeverity | null,
          topicArn: cloudwatch ? topicArn.trim() : null,
          active,
        },
      },
      {
        onSuccess: (result) => {
          toast.success(source ? `${result.source.name} saved` : `${result.source.name} created`);
          if (result.secret) onSecret(result.secret, result.source);
          onClose();
        },
      },
    );

  return (
    <Modal
      open
      onClose={onClose}
      title={source ? `Edit ${source.name}` : 'New alert source'}
      eyebrow="ALERT SOURCES"
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={!name.trim() || arnInvalid || save.isPending}>
            {save.isPending ? 'Saving…' : source ? 'Save source' : 'Create source'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <TextField label="Name" required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} error={fieldError('name')} hint="Unique, e.g. prometheus-prod." />
        <SelectField label="Provider" required value={provider} onChange={(event) => setProvider(event.target.value as AlertProvider)} disabled={Boolean(source)} hint={source ? 'Cannot change after creation.' : undefined}>
          {ALERT_PROVIDERS.map((value) => (
            <option key={value} value={value}>
              {ALERT_SOURCE_LABELS[value]}
            </option>
          ))}
        </SelectField>
      </div>
      {cloudwatch && (
        <TextField
          label="SNS topic ARN"
          required
          placeholder="arn:aws:sns:ap-south-1:123456789012:alarms"
          maxLength={300}
          value={topicArn}
          onChange={(event) => setTopicArn(event.target.value)}
          error={topicArn && arnInvalid ? 'arn:aws:sns:<region>:<12-digit account>:<topic>' : fieldError('topicArn')}
          hint={source ? 'A new topic resets the subscription.' : 'Only messages from this topic are accepted.'}
        />
      )}
      <div className="form-row">
        <SelectField label="Default service" value={serviceId} onChange={(event) => setServiceId(event.target.value)} hint="When an alert names no service and the CMDB cannot derive one.">
          <option value="">None</option>
          {(services.data ?? []).map((service) => (
            <option key={service.id} value={service.id}>
              {service.name} ({service.code})
            </option>
          ))}
        </SelectField>
        <SelectField label="Default severity" value={severity} onChange={(event) => setSeverity(event.target.value)} hint="When the payload's severity is unknown.">
          <option value="">Platform default</option>
          {ALERT_SEVERITIES.map((value) => (
            <option key={value} value={value}>
              {titleCase(value)}
            </option>
          ))}
        </SelectField>
      </div>
      <CiSinglePicker label="Default configuration item" value={ci} onChange={setCi} error={fieldError('defaultCiId')} />
      <CheckboxField label="Active (inactive sources reject every delivery)" checked={active} onChange={(event) => setActive(event.target.checked)} />
      {save.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}

function Deliveries({ sourceId }: { sourceId: number }) {
  const deliveries = useAlertSourceDeliveries(sourceId);
  if (deliveries.isLoading) return <LoadingState variant="inline" />;
  if (deliveries.isError) return <ErrorState error={deliveries.error} compact onRetry={() => void deliveries.refetch()} />;
  if (!deliveries.data?.length) return <p className="m-0 text-[11px] text-muted">No deliveries received yet.</p>;
  return (
    <ul className="m-0 grid list-none gap-1 p-0" aria-label="Recent deliveries">
      {deliveries.data.map((delivery) => (
        <li key={delivery.id} className="flex flex-wrap items-center justify-between gap-2 text-[11px]">
          <span className="min-w-0 break-words">
            {delivery.eventType ?? 'delivery'} · {delivery.message ?? '—'} · {formatNumber(delivery.alertCount)} alert{delivery.alertCount === 1 ? '' : 's'} · {formatRelative(delivery.receivedAt)}
          </span>
          <ToneBadge value={delivery.outcome} tone={delivery.outcome === 'PROCESSED' ? 'success' : delivery.outcome === 'FAILED' ? 'critical' : 'neutral'} />
        </li>
      ))}
    </ul>
  );
}

/** Alert config (admins): webhook sources, one-time secrets, rotation, deliveries and setup instructions. */
export function SourcesPanel() {
  const sources = useAlertSources();
  const rotate = useRotateAlertSourceSecret();
  const [editing, setEditing] = useState<AlertSourceDto | 'new' | null>(null);
  const [secret, setSecret] = useState<{ secret: string; source: AlertSourceDto } | null>(null);
  const [rotating, setRotating] = useState<AlertSourceDto | null>(null);
  const [open, setOpen] = useState<Record<number, 'setup' | 'deliveries' | undefined>>({});

  return (
    <Panel
      title="Alert sources"
      subtitle="Each monitoring system posts to its own webhook URL; unknown, inactive or unauthenticated deliveries are rejected"
      actions={
        <button type="button" className="gold-btn compact" onClick={() => setEditing('new')}>
          <Plus size={13} aria-hidden="true" /> New source
        </button>
      }
    >
      {sources.isLoading && <LoadingState rows={3} />}
      {sources.isError && <ErrorState error={sources.error} onRetry={() => void sources.refetch()} />}
      {sources.data && sources.data.length === 0 && <EmptyState title="No alert sources" description="Create one per monitoring system (Prometheus, Grafana, CloudWatch or a signed JSON sender)." icon={<Radio size={18} />} />}
      {sources.data && sources.data.length > 0 && (
        <ul className="m-0 grid list-none gap-2 p-0" aria-label="Alert sources">
          {sources.data.map((source) => (
            <li key={source.id} className="panel raised min-w-0">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <b className="flex flex-wrap items-center gap-1.5 text-[12px]">
                    {source.name}
                    <ToneBadge value={source.provider} label={ALERT_SOURCE_LABELS[source.provider]} tone="gold" />
                    <BooleanBadge value={source.active} />
                    {source.provider === 'CLOUDWATCH' && (
                      <ToneBadge value={source.subscriptionStatus} label={`SNS ${titleCase(source.subscriptionStatus)}`} tone={source.subscriptionStatus === 'CONFIRMED' ? 'success' : source.subscriptionStatus === 'PENDING' ? 'warning' : 'muted'} />
                    )}
                    <DemoBadge demo={source.demo} />
                  </b>
                  <small className="block break-all text-muted">{webhookUrl(source.webhookPath)}</small>
                  <small className="block break-words">{source.authentication}</small>
                  <small className="block">
                    {source.defaultServiceName ? `Default service ${source.defaultServiceName} · ` : ''}
                    {source.defaultCiName ? `default CI ${source.defaultCiName} · ` : ''}
                    {source.defaultSeverity ? `default severity ${titleCase(source.defaultSeverity)} · ` : ''}
                    {source.lastReceivedAt ? `last delivery ${formatRelative(source.lastReceivedAt)}` : 'nothing received yet'}
                  </small>
                </div>
                <div className="flex flex-wrap gap-1">
                  <button type="button" className="subtle-btn compact" aria-expanded={open[source.id] === 'setup'} onClick={() => setOpen((current) => ({ ...current, [source.id]: current[source.id] === 'setup' ? undefined : 'setup' }))}>
                    Setup
                  </button>
                  <button type="button" className="subtle-btn compact" aria-expanded={open[source.id] === 'deliveries'} onClick={() => setOpen((current) => ({ ...current, [source.id]: current[source.id] === 'deliveries' ? undefined : 'deliveries' }))}>
                    Deliveries
                  </button>
                  {source.provider !== 'CLOUDWATCH' && (
                    <button type="button" className="subtle-btn compact" onClick={() => setRotating(source)} aria-label={`Rotate secret of ${source.name}`}>
                      <KeyRound size={12} aria-hidden="true" /> Rotate secret
                    </button>
                  )}
                  <button type="button" className="icon-btn sm" onClick={() => setEditing(source)} aria-label={`Edit ${source.name}`}>
                    <Pencil size={13} />
                  </button>
                </div>
              </div>
              {open[source.id] === 'setup' && (
                <div className="mt-3">
                  <AdapterGuide source={source} />
                </div>
              )}
              {open[source.id] === 'deliveries' && (
                <div className="mt-3">
                  <Deliveries sourceId={source.id} />
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {editing && <SourceEditor source={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSecret={(value, saved) => setSecret({ secret: value, source: saved })} />}
      {secret && <SecretModal secret={secret.secret} source={secret.source} onClose={() => setSecret(null)} />}
      <ConfirmDialog
        open={rotating !== null}
        onClose={() => {
          setRotating(null);
          rotate.reset();
        }}
        title={`Rotate the secret of ${rotating?.name ?? ''}?`}
        message="The old secret stops working immediately; update the monitoring system with the new one."
        confirmLabel="Rotate secret"
        tone="danger"
        pending={rotate.isPending}
        error={rotate.isError ? errorMessage(rotate.error) : null}
        onConfirm={() =>
          rotating &&
          rotate.mutate(rotating.id, {
            onSuccess: (result) => {
              setRotating(null);
              if (result.secret) setSecret({ secret: result.secret, source: result.source });
            },
          })
        }
      />
    </Panel>
  );
}

// ------------------------------------------------------------------ rules (shared bits)

/** Names for matcher ids (sources are readable by alert config only). */
function useLookups(canReadSources: boolean) {
  const services = useServices();
  const sources = useAlertSources(canReadSources);
  return useMemo(
    () => ({
      services: new Map((services.data ?? []).map((service) => [service.id, service.name])),
      sources: new Map((sources.data ?? []).map((source) => [source.id, source.name])),
    }),
    [services.data, sources.data],
  );
}

function RuleMeta({ matchCount, lastMatchedAt, createdByName }: { matchCount: number; lastMatchedAt: string | null; createdByName: string | null }) {
  return (
    <small className="block text-muted">
      {formatNumber(matchCount)} match{matchCount === 1 ? '' : 'es'}
      {lastMatchedAt ? ` · last ${formatRelative(lastMatchedAt)}` : ''}
      {createdByName ? ` · by ${createdByName}` : ''}
    </small>
  );
}

// ------------------------------------------------------------------ suppression rules

function SuppressionEditor({ rule, onClose }: { rule: SuppressionRuleDto | null; onClose: () => void }) {
  const save = useSaveSuppressionRule();
  const sources = useAlertSources();
  const toast = useToast();
  const [name, setName] = useState(rule?.name ?? '');
  const [description, setDescription] = useState(rule?.description ?? '');
  const [enabled, setEnabled] = useState(rule?.enabled ?? true);
  const [match, setMatch] = useState<MatchDraft>(() => draftFromMatch(rule?.match));
  const [startsAt, setStartsAt] = useState(isoToLocalInput(rule?.startsAt));
  const [endsAt, setEndsAt] = useState(isoToLocalInput(rule?.endsAt));
  const [dailyStart, setDailyStart] = useState(rule?.dailyStart ?? '');
  const [dailyEnd, setDailyEnd] = useState(rule?.dailyEnd ?? '');
  const [days, setDays] = useState<number[]>(rule?.daysOfWeek ?? []);
  const [localError, setLocalError] = useState<string | null>(null);
  const errors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];

  const submit = () => {
    const matcher = matchFromDraft(match);
    if (typeof matcher === 'string') return setLocalError(matcher);
    if (name.trim().length < 2) return setLocalError('Give a name of at least 2 characters');
    if (Boolean(dailyStart) !== Boolean(dailyEnd)) return setLocalError('Give both the daily start and end, or neither');
    const start = localInputToIso(startsAt);
    const end = localInputToIso(endsAt);
    if (start && end && end <= start) return setLocalError('The end must be after the start');
    if (!matcher && !start && !end && !dailyStart && !days.length) return setLocalError('A rule needs a matcher or a time window');
    setLocalError(null);
    save.mutate(
      {
        id: rule?.id,
        body: { name: name.trim(), description: description.trim() || null, enabled, match: matcher, startsAt: start, endsAt: end, dailyStart: dailyStart || null, dailyEnd: dailyEnd || null, daysOfWeek: days.length ? [...days].sort() : null },
      },
      {
        onSuccess: (saved) => {
          toast.success(rule ? `${saved.name} saved` : `${saved.name} created`);
          onClose();
        },
      },
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={rule ? `Edit ${rule.name}` : 'New suppression rule'}
      eyebrow="SUPPRESSION"
      description="Matching alerts are recorded as SUPPRESSED while the window is open: nobody is notified, nothing escalates or converts."
      size="xl"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={save.isPending}>
            {save.isPending ? 'Saving…' : rule ? 'Save rule' : 'Create rule'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <TextField label="Name" required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} error={errors.find((error) => error.field === 'name')?.message} />
        <CheckboxField label="Enabled" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />
      </div>
      <TextareaField label="Description" rows={2} maxLength={500} value={description} onChange={(event) => setDescription(event.target.value)} />
      <h4 className="m-0 mb-2 text-[12px]">Which alerts</h4>
      <MatchCriteriaEditor value={match} onChange={setMatch} sources={sources.data} />
      <h4 className="mt-3 mb-2 text-[12px]">When (organisation time zone)</h4>
      <div className="form-row">
        <TextField label="From (optional)" type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} error={errors.find((error) => error.field === 'startsAt')?.message} />
        <TextField label="Until (optional)" type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} error={errors.find((error) => error.field === 'endsAt')?.message} />
      </div>
      <div className="form-row">
        <TextField label="Daily from" type="time" value={dailyStart} onChange={(event) => setDailyStart(event.target.value)} hint="May cross midnight, e.g. 22:00 → 02:00." error={errors.find((error) => error.field === 'dailyStart')?.message} />
        <TextField label="Daily until" type="time" value={dailyEnd} onChange={(event) => setDailyEnd(event.target.value)} error={errors.find((error) => error.field === 'dailyEnd')?.message} />
      </div>
      <fieldset className="m-0 mb-3 min-w-0 border-0 p-0">
        <legend className="mb-1 text-[11px] font-semibold">Days (none = every day)</legend>
        <div className="flex flex-wrap gap-1.5">
          {WEEKDAY_SHORT.map((label, index) => {
            const day = index + 1;
            const on = days.includes(day);
            return (
              <button key={label} type="button" className="chip chip-toggle" aria-pressed={on} onClick={() => setDays((current) => (on ? current.filter((other) => other !== day) : [...current, day]))}>
                {label}
              </button>
            );
          })}
        </div>
      </fieldset>
      {localError && (
        <p className="field-error" role="alert">
          {localError}
        </p>
      )}
      {save.isError && (
        <p className="field-error" role="alert">
          {errors.length ? errors.map((error) => error.message).join(' · ') : errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}

/** Suppression rules: readers see them; alert config (admins) edits. */
export function SuppressionRulesPanel({ canEdit }: { canEdit: boolean }) {
  const rules = useSuppressionRules();
  const remove = useDeleteSuppressionRule();
  const toast = useToast();
  const lookups = useLookups(canEdit);
  const [editing, setEditing] = useState<SuppressionRuleDto | 'new' | null>(null);
  const [deleting, setDeleting] = useState<SuppressionRuleDto | null>(null);
  return (
    <Panel
      title="Suppression rules"
      subtitle="Silence expected noise — maintenance windows, backups — without losing the record"
      actions={
        canEdit ? (
          <button type="button" className="gold-btn compact" onClick={() => setEditing('new')}>
            <Plus size={13} aria-hidden="true" /> New rule
          </button>
        ) : undefined
      }
    >
      {rules.isLoading && <LoadingState rows={3} />}
      {rules.isError && <ErrorState error={rules.error} onRetry={() => void rules.refetch()} />}
      {rules.data && rules.data.length === 0 && <EmptyState title="No suppression rules" icon={<ShieldOff size={18} />} />}
      {rules.data && rules.data.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Suppression rules">
          {rules.data.map((rule) => (
            <li key={rule.id} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  {rule.name}
                  {!rule.enabled ? <ToneBadge value="disabled" label="Disabled" tone="muted" /> : rule.expired ? <ToneBadge value="expired" label="Expired" tone="muted" /> : rule.activeNow ? <ToneBadge value="active" label="Active now" tone="warning" /> : <ToneBadge value="waiting" label="Outside window" tone="neutral" />}
                  <DemoBadge demo={rule.demo} />
                </b>
                {rule.description && <small className="block break-words">{rule.description}</small>}
                <small className="block break-words">
                  {describeMatch(rule.match, lookups)} · {describeWindow(rule, formatDateTime)}
                </small>
                <RuleMeta matchCount={rule.matchCount} lastMatchedAt={rule.lastMatchedAt} createdByName={rule.createdByName} />
              </div>
              {canEdit && (
                <span className="flex shrink-0 gap-1">
                  <button type="button" className="icon-btn sm" aria-label={`Edit ${rule.name}`} onClick={() => setEditing(rule)}>
                    <Pencil size={13} />
                  </button>
                  <button type="button" className="icon-btn sm" aria-label={`Delete ${rule.name}`} onClick={() => setDeleting(rule)}>
                    <Trash2 size={13} />
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {editing && <SuppressionEditor rule={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => {
          setDeleting(null);
          remove.reset();
        }}
        title={`Delete ${deleting?.name ?? ''}?`}
        message="Alerts it suppressed reopen when they fire again."
        confirmLabel="Delete rule"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              toast.success(`${deleting.name} deleted`);
              setDeleting(null);
            },
          })
        }
      />
    </Panel>
  );
}

// ------------------------------------------------------------------ routing rules

function RoutingEditor({ rule, onClose }: { rule: RoutingRuleDto | null; onClose: () => void }) {
  const save = useSaveRoutingRule();
  const sources = useAlertSources();
  const teams = useTeams({ active: true });
  const services = useServices();
  const toast = useToast();
  const [name, setName] = useState(rule?.name ?? '');
  const [description, setDescription] = useState(rule?.description ?? '');
  const [enabled, setEnabled] = useState(rule?.enabled ?? true);
  const [sortOrder, setSortOrder] = useState(String(rule?.sortOrder ?? 100));
  const [match, setMatch] = useState<MatchDraft>(() => draftFromMatch(rule?.match));
  const [teamId, setTeamId] = useState(rule?.teamId ? String(rule.teamId) : '');
  const [serviceId, setServiceId] = useState(rule?.serviceId ? String(rule.serviceId) : '');
  const [ci, setCi] = useState<CiChoice | null>(rule?.ciId ? { id: rule.ciId, name: rule.ciName ?? `CI #${rule.ciId}` } : null);
  const [createIncident, setCreateIncident] = useState(rule?.createIncident ?? false);
  const [minSeverity, setMinSeverity] = useState<AlertSeverity>(rule?.incidentMinSeverity ?? 'MAJOR');
  const [localError, setLocalError] = useState<string | null>(null);
  const errors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];

  const submit = () => {
    const matcher = matchFromDraft(match);
    if (typeof matcher === 'string') return setLocalError(matcher);
    if (name.trim().length < 2) return setLocalError('Give a name of at least 2 characters');
    const order = Number(sortOrder);
    if (!Number.isInteger(order) || order < 0 || order > 10_000) return setLocalError('Order is a whole number from 0 to 10,000');
    if (!teamId && !serviceId && !ci && !createIncident) return setLocalError('Route to a team, set a service or CI, or create an incident');
    setLocalError(null);
    save.mutate(
      {
        id: rule?.id,
        body: {
          name: name.trim(),
          description: description.trim() || null,
          enabled,
          sortOrder: order,
          match: matcher,
          teamId: teamId ? Number(teamId) : null,
          serviceId: serviceId ? Number(serviceId) : null,
          ciId: ci?.id ?? null,
          createIncident,
          incidentMinSeverity: createIncident ? minSeverity : null,
        },
      },
      {
        onSuccess: (saved) => {
          toast.success(rule ? `${saved.name} saved` : `${saved.name} created`);
          onClose();
        },
      },
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={rule ? `Edit ${rule.name}` : 'New routing rule'}
      eyebrow="ROUTING"
      description="The first enabled rule (lowest order) whose matcher matches sets the team, fills in a missing service or CI and may open an incident."
      size="xl"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={save.isPending}>
            {save.isPending ? 'Saving…' : rule ? 'Save rule' : 'Create rule'}
          </button>
        </>
      }
    >
      <div className="form-row three">
        <TextField label="Name" required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} error={errors.find((error) => error.field === 'name')?.message} />
        <TextField label="Order" inputMode="numeric" value={sortOrder} onChange={(event) => setSortOrder(event.target.value)} hint="Lower runs first." />
        <CheckboxField label="Enabled" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />
      </div>
      <TextareaField label="Description" rows={2} maxLength={500} value={description} onChange={(event) => setDescription(event.target.value)} />
      <h4 className="m-0 mb-2 text-[12px]">Which alerts</h4>
      <MatchCriteriaEditor value={match} onChange={setMatch} sources={sources.data} />
      <h4 className="mt-3 mb-2 text-[12px]">Then</h4>
      <div className="form-row">
        <SelectField label="Route to team" value={teamId} onChange={(event) => setTeamId(event.target.value)} disabled={teams.isError}>
          <option value="">Keep the default team</option>
          {(teams.data ?? []).map((team) => (
            <option key={team.id} value={team.id}>
              {team.name}
            </option>
          ))}
        </SelectField>
        <SelectField label="Set service (when missing)" value={serviceId} onChange={(event) => setServiceId(event.target.value)} disabled={services.isError}>
          <option value="">Leave as is</option>
          {(services.data ?? []).map((service) => (
            <option key={service.id} value={service.id}>
              {service.name} ({service.code})
            </option>
          ))}
        </SelectField>
      </div>
      <CiSinglePicker label="Set CI (when missing)" value={ci} onChange={setCi} />
      <div className="form-row">
        <CheckboxField label="Create an incident" checked={createIncident} onChange={(event) => setCreateIncident(event.target.checked)} />
        {createIncident && (
          <SelectField label="From severity" value={minSeverity} onChange={(event) => setMinSeverity(event.target.value as AlertSeverity)} hint="This severity or higher.">
            {ALERT_SEVERITIES.map((value) => (
              <option key={value} value={value}>
                {titleCase(value)}
              </option>
            ))}
          </SelectField>
        )}
      </div>
      {localError && (
        <p className="field-error" role="alert">
          {localError}
        </p>
      )}
      {save.isError && (
        <p className="field-error" role="alert">
          {errors.length ? errors.map((error) => error.message).join(' · ') : errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}

/** Routing rules: readers see them; alert config (admins) edits. */
export function RoutingRulesPanel({ canEdit }: { canEdit: boolean }) {
  const rules = useRoutingRules();
  const remove = useDeleteRoutingRule();
  const toast = useToast();
  const lookups = useLookups(canEdit);
  const [editing, setEditing] = useState<RoutingRuleDto | 'new' | null>(null);
  const [deleting, setDeleting] = useState<RoutingRuleDto | null>(null);
  return (
    <Panel
      title="Routing rules"
      subtitle="Evaluated in order; without a match the service's, then the department's default team, then the CI's team gets the alert"
      actions={
        canEdit ? (
          <button type="button" className="gold-btn compact" onClick={() => setEditing('new')}>
            <Plus size={13} aria-hidden="true" /> New rule
          </button>
        ) : undefined
      }
    >
      {rules.isLoading && <LoadingState rows={3} />}
      {rules.isError && <ErrorState error={rules.error} onRetry={() => void rules.refetch()} />}
      {rules.data && rules.data.length === 0 && <EmptyState title="No routing rules" icon={<Route size={18} />} />}
      {rules.data && rules.data.length > 0 && (
        <ol className="m-0 grid list-none gap-1 p-0" aria-label="Routing rules">
          {rules.data.map((rule) => (
            <li key={rule.id} className="list-row flex-wrap sm:flex-nowrap">
              <span className="icon-tile sm shrink-0" aria-label={`Order ${rule.sortOrder}`}>
                {rule.sortOrder}
              </span>
              <div className="min-w-0 flex-1">
                <b className="flex flex-wrap items-center gap-1.5">
                  {rule.name}
                  {!rule.enabled && <ToneBadge value="disabled" label="Disabled" tone="muted" />}
                  {rule.createIncident && (
                    <span className="inline-flex items-center gap-1">
                      <ToneBadge value="incident" label="Creates incident" tone="critical" /> {rule.incidentMinSeverity && <SeverityBadge severity={rule.incidentMinSeverity} />}
                    </span>
                  )}
                  <DemoBadge demo={rule.demo} />
                </b>
                {rule.description && <small className="block break-words">{rule.description}</small>}
                <small className="block break-words">
                  {describeMatch(rule.match, lookups)} →{' '}
                  {[rule.teamName && `team ${rule.teamName}`, rule.serviceName && `service ${rule.serviceName}`, rule.ciName && `CI ${rule.ciName}`, rule.createIncident && 'incident'].filter(Boolean).join(', ')}
                </small>
                <RuleMeta matchCount={rule.matchCount} lastMatchedAt={rule.lastMatchedAt} createdByName={rule.createdByName} />
              </div>
              {canEdit && (
                <span className="flex shrink-0 gap-1">
                  <button type="button" className="icon-btn sm" aria-label={`Edit ${rule.name}`} onClick={() => setEditing(rule)}>
                    <Pencil size={13} />
                  </button>
                  <button type="button" className="icon-btn sm" aria-label={`Delete ${rule.name}`} onClick={() => setDeleting(rule)}>
                    <Trash2 size={13} />
                  </button>
                </span>
              )}
            </li>
          ))}
        </ol>
      )}
      {editing && <RoutingEditor rule={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => {
          setDeleting(null);
          remove.reset();
        }}
        title={`Delete ${deleting?.name ?? ''}?`}
        message="New alerts are routed by the remaining rules and the default teams."
        confirmLabel="Delete rule"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              toast.success(`${deleting.name} deleted`);
              setDeleting(null);
            },
          })
        }
      />
    </Panel>
  );
}
