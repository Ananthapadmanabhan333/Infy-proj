import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Activity, AlertTriangle, Copy, KeyRound, MessagesSquare, Pencil, Send, Trash2, Webhook, type LucideIcon } from 'lucide-react';
import { useDeleteIntegration, useDeliveries, useRotateInboundToken, useSaveIntegration, useTestIntegration } from '../../api/integrations';
import { useServices } from '../../api/services';
import { apiOrigin, applyFieldErrors, errorMessage } from '../../lib/api';
import { INTEGRATION_EVENTS, INTEGRATION_TYPES } from '../../lib/constants';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import { integrationSchema, type IntegrationValues } from '../../lib/schemas';
import type { IntegrationDto, IntegrationType } from '../../lib/types';
import { Badge, BooleanBadge, DemoBadge, StatusBadge } from '../Badge';
import { ConfirmDialog } from '../ConfirmDialog';
import { DataTable } from '../DataTable';
import { Drawer } from '../Drawer';
import { CheckboxField, idOrNull, SelectField, TextField } from '../FormField';
import { Modal } from '../Modal';
import { ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';

export const INTEGRATION_TYPE_LABELS: Record<IntegrationType, string> = {
  WEBHOOK: 'Webhook',
  SLACK: 'Slack channel',
  TEAMS: 'Microsoft Teams channel',
  MONITORING_INBOUND: 'Monitoring alerts (inbound)',
};

export const INTEGRATION_TYPE_ICONS: Record<IntegrationType, LucideIcon> = {
  WEBHOOK: Webhook,
  SLACK: MessagesSquare,
  TEAMS: MessagesSquare,
  MONITORING_INBOUND: Activity,
};

export function integrationTypeLabel(type: string): string {
  return (INTEGRATION_TYPE_LABELS as Record<string, string>)[type] ?? titleCase(type);
}

export function inboundUrl(integration: Pick<IntegrationDto, 'inboundPath'>): string | null {
  if (!integration.inboundPath) return null;
  return `${apiOrigin()}${integration.inboundPath.startsWith('/') ? '' : '/'}${integration.inboundPath}`;
}

/** Outbound target or inbound URL shown on cards and in the drawer. */
export function integrationTarget(integration: IntegrationDto): string | null {
  return integration.type === 'MONITORING_INBOUND' ? inboundUrl(integration) : integration.endpointUrl;
}

/** True when the last delivery failed or failures have accumulated. */
export function isFailing(integration: IntegrationDto): boolean {
  return integration.lastDeliveryStatus === 'FAILED' || integration.failureCount > 0;
}

function CopyButton({ value, label }: { value: string; label: string }) {
  const toast = useToast();
  return (
    <button
      type="button"
      className="subtle-btn compact"
      onClick={() => {
        if (!navigator.clipboard) {
          toast.error('Copy is not available here — select the text and copy it manually');
          return;
        }
        navigator.clipboard
          .writeText(value)
          .then(() => toast.success(`${label} copied`))
          .catch(() => toast.error('Copy failed — select the text and copy it manually'));
      }}
    >
      <Copy size={12} /> Copy {label.toLowerCase()}
    </button>
  );
}

/** Shows a freshly issued inbound token exactly once. */
export function TokenPanel({ integration, token, onClose }: { integration: IntegrationDto; token: string; onClose: () => void }) {
  const url = inboundUrl(integration);
  return (
    <Modal open onClose={onClose} title="Inbound monitoring token" eyebrow="COPY ONCE" size="lg">
      <div className="copy-once">
        <div className="callout red mb-3">
          <AlertTriangle size={16} />
          <div>
            <b>This token is shown only once.</b> Copy it into your monitoring tool now — it cannot be retrieved again. Rotate the token if it is ever exposed.
          </div>
        </div>
        {url && (
          <>
            <small className="text-[9px] tracking-wider text-muted uppercase">Inbound URL (POST)</small>
            <code>{url}</code>
            <CopyButton value={url} label="URL" />
          </>
        )}
        <small className="mt-3 block text-[9px] tracking-wider text-muted uppercase">X-Nexus-Token header</small>
        <code>{token}</code>
        <CopyButton value={token} label="Token" />
      </div>
      <div className="modal-foot">
        <button type="button" className="gold-btn" onClick={onClose}>
          I have stored the token
        </button>
      </div>
    </Modal>
  );
}

export function IntegrationEditor({
  integration,
  onClose,
  onToken,
}: {
  integration: IntegrationDto | null;
  onClose: () => void;
  onToken: (integration: IntegrationDto, token: string) => void;
}) {
  const save = useSaveIntegration();
  const services = useServices();
  const toast = useToast();
  const {
    register,
    handleSubmit,
    control,
    setError,
    formState: { errors },
  } = useForm<IntegrationValues>({
    resolver: zodResolver(integrationSchema),
    defaultValues: {
      name: integration?.name ?? '',
      type: integration?.type ?? 'WEBHOOK',
      enabled: integration?.enabled ?? true,
      endpointUrl: integration?.endpointUrl ?? '',
      secret: '',
      subscribedEvents: integration?.subscribedEvents ?? [],
      defaultServiceId: integration?.defaultServiceId ? String(integration.defaultServiceId) : '',
    },
  });
  const type = useWatch({ control, name: 'type' });
  const inbound = type === 'MONITORING_INBOUND';
  const eventOptions = Array.from(new Set<string>([...INTEGRATION_EVENTS, ...(integration?.subscribedEvents ?? [])]));

  const onSubmit = handleSubmit((values) =>
    save.mutate(
      {
        id: integration?.id,
        body: {
          name: values.name.trim(),
          type: values.type,
          enabled: values.enabled,
          endpointUrl: inbound ? null : values.endpointUrl.trim(),
          secret: values.secret ? values.secret : undefined,
          subscribedEvents: inbound ? [] : values.subscribedEvents,
          defaultServiceId: inbound ? idOrNull(values.defaultServiceId) : null,
        },
      },
      {
        onSuccess: (result) => {
          toast.success(integration ? 'Integration updated' : 'Integration created');
          onClose();
          if (result.inboundToken) onToken(result.integration, result.inboundToken);
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  return (
    <Modal open onClose={onClose} title={integration ? `Edit ${integration.name}` : 'New integration'} eyebrow="INTEGRATIONS" size="lg" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <div className="form-row">
          <TextField label="Name" required error={errors.name?.message} {...register('name')} />
          <SelectField label="Type" error={errors.type?.message} disabled={Boolean(integration)} hint={integration ? 'The type cannot be changed.' : undefined} {...register('type')}>
            {INTEGRATION_TYPES.map((value) => (
              <option key={value} value={value}>
                {integrationTypeLabel(value)}
              </option>
            ))}
          </SelectField>
        </div>
        {inbound ? (
          <SelectField label="Default service" hint="Used when the alert payload does not name a service." {...register('defaultServiceId')}>
            <option value="">Let SA classify</option>
            {services.data?.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </SelectField>
        ) : (
          <>
            <TextField label="Endpoint URL" required placeholder="https://hooks.example.com/…" error={errors.endpointUrl?.message} {...register('endpointUrl')} />
            <TextField
              label={integration?.hasSecret ? 'Replace signing secret' : 'Signing secret'}
              type="password"
              autoComplete="new-password"
              hint={integration?.hasSecret ? 'A secret is set. Leave blank to keep it.' : 'Optional — used to sign payloads (HMAC). Write-only.'}
              error={errors.secret?.message}
              {...register('secret')}
            />
            {integration?.hasSecret && (
              <span className="chip mb-2">
                <KeyRound size={11} /> Secret set
              </span>
            )}
            <fieldset className="field border-0 p-0">
              <legend className="mb-1">Subscribed events</legend>
              <div className="grid gap-x-4 sm:grid-cols-2">
                {eventOptions.map((event) => (
                  <label key={event} className="check !my-1.5">
                    <input type="checkbox" value={event} {...register('subscribedEvents')} />
                    <span>{titleCase(event)}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          </>
        )}
        <CheckboxField label="Enabled" {...register('enabled')} />
        {inbound && !integration && <p className="field-hint">An inbound token is generated when you save and shown once.</p>}
        {save.isError && !Object.keys(errors).length && <p className="field-error">{errorMessage(save.error)}</p>}
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save integration'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** Integration detail: configuration, actions for CONFIG_ADMINS and the delivery log. */
export function IntegrationDrawer({
  integration,
  admin,
  onClose,
  onEdit,
  onToken,
}: {
  integration: IntegrationDto;
  admin: boolean;
  onClose: () => void;
  onEdit: () => void;
  onToken: (integration: IntegrationDto, token: string) => void;
}) {
  const deliveries = useDeliveries(integration.id);
  const test = useTestIntegration();
  const remove = useDeleteIntegration();
  const rotate = useRotateInboundToken();
  const toast = useToast();
  const [confirm, setConfirm] = useState<'delete' | 'rotate' | null>(null);
  const url = inboundUrl(integration);
  const inbound = integration.type === 'MONITORING_INBOUND';
  const Icon = INTEGRATION_TYPE_ICONS[integration.type] ?? Webhook;

  return (
    <Drawer
      open
      onClose={onClose}
      label="INTEGRATION"
      title={
        <span className="flex items-center gap-3">
          <span className="icon-tile" aria-hidden="true">
            <Icon size={18} />
          </span>
          <span className="min-w-0">
            {integration.name}
            <small className="block text-[11px] font-normal text-muted">{integrationTypeLabel(integration.type)}</small>
          </span>
        </span>
      }
      wide
      footer={
        admin ? (
          <>
            <button type="button" className="danger-btn compact" onClick={() => setConfirm('delete')}>
              <Trash2 size={13} /> Delete
            </button>
            {inbound && (
              <button type="button" className="subtle-btn compact" onClick={() => setConfirm('rotate')}>
                <KeyRound size={13} /> Rotate token
              </button>
            )}
            <button
              type="button"
              className="subtle-btn compact"
              disabled={test.isPending}
              onClick={() =>
                test.mutate(integration.id, {
                  onSuccess: (result) =>
                    result && result.status === 'FAILED'
                      ? toast.error(`Test delivery failed${result.httpStatus ? ` (HTTP ${result.httpStatus})` : ''}`)
                      : toast.success('Test delivery sent'),
                  onError: (error) => toast.error(error),
                })
              }
            >
              <Send size={13} /> {test.isPending ? 'Testing…' : 'Send test'}
            </button>
            <button type="button" className="gold-btn compact" onClick={onEdit}>
              <Pencil size={13} /> Edit
            </button>
          </>
        ) : undefined
      }
    >
      <div className="tag-row mt-1">
        <BooleanBadge value={integration.enabled} on="Enabled" off="Disabled" />
        {integration.lastDeliveryStatus && <StatusBadge status={integration.lastDeliveryStatus} />}
        {integration.failureCount > 0 && <Badge value="failed" label={`${integration.failureCount} failures`} />}
        <DemoBadge demo={integration.demo} />
      </div>
      <div className="detail-grid">
        {inbound ? (
          <div className="sm:col-span-2">
            <small>Inbound URL (POST)</small>
            <b className="font-mono">{url ?? '—'}</b>
            {url && (
              <div className="mt-2">
                <CopyButton value={url} label="URL" />
              </div>
            )}
          </div>
        ) : (
          <div className="sm:col-span-2">
            <small>Endpoint</small>
            <b className="font-mono">{integration.endpointUrl ?? '—'}</b>
          </div>
        )}
        <div>
          <small>{inbound ? 'Token' : 'Signing secret'}</small>
          <b>{inbound ? 'Issued once; rotate to replace' : integration.hasSecret ? 'Secret set (hidden)' : 'Not set'}</b>
        </div>
        <div>
          <small>Failures</small>
          <b className={integration.failureCount ? 'red-text' : ''}>{integration.failureCount}</b>
        </div>
        <div>
          <small>Last delivery</small>
          <b>{integration.lastDeliveryAt ? `${formatRelative(integration.lastDeliveryAt)} · ${formatDateTime(integration.lastDeliveryAt)}` : 'Never'}</b>
        </div>
        <div>
          <small>{inbound ? 'Default service' : 'Created'}</small>
          <b>{inbound ? (integration.defaultServiceName ?? 'Classified automatically') : formatDateTime(integration.createdAt)}</b>
        </div>
      </div>
      {integration.subscribedEvents.length > 0 && (
        <div className="drawer-section">
          <h3>Subscribed events</h3>
          <div className="flex flex-wrap gap-1.5">
            {integration.subscribedEvents.map((event) => (
              <span key={event} className="chip">
                {titleCase(event)}
              </span>
            ))}
          </div>
        </div>
      )}
      {!admin && <p className="chip">Read-only — configuration requires an IT Admin or System Admin.</p>}
      <div className="drawer-section">
        <h3>Delivery log</h3>
        {deliveries.isLoading && <LoadingState rows={3} label="Loading deliveries…" />}
        {deliveries.isError && <ErrorState error={deliveries.error} onRetry={() => void deliveries.refetch()} compact />}
        {deliveries.data && (
          <DataTable
            caption="Deliveries"
            rows={deliveries.data}
            rowKey={(row) => row.id}
            empty="No deliveries yet."
            columns={[
              { key: 'event', header: 'Event', render: (row) => titleCase(row.eventType) },
              { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
              { key: 'http', header: 'HTTP', render: (row) => row.httpStatus ?? '—' },
              { key: 'duration', header: 'Duration', render: (row) => (row.durationMs !== null ? `${row.durationMs} ms` : '—') },
              { key: 'when', header: 'When', render: (row) => <span title={formatDateTime(row.createdAt)}>{formatRelative(row.createdAt)}</span> },
              { key: 'snippet', header: 'Response', className: 'wrap', render: (row) => <span className="text-[9px] text-muted">{row.responseSnippet ?? ''}</span> },
            ]}
          />
        )}
      </div>
      <ConfirmDialog
        open={confirm === 'delete'}
        onClose={() => setConfirm(null)}
        title={`Delete ${integration.name}?`}
        message="Workflows that send to this integration will fail until they are updated."
        confirmLabel="Delete"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          remove.mutate(integration.id, {
            onSuccess: () => {
              toast.success('Integration deleted');
              setConfirm(null);
              onClose();
            },
          })
        }
      />
      <ConfirmDialog
        open={confirm === 'rotate'}
        onClose={() => setConfirm(null)}
        title="Rotate inbound token?"
        message="The current token stops working immediately. Update your monitoring tool with the new token."
        confirmLabel="Rotate token"
        tone="danger"
        pending={rotate.isPending}
        error={rotate.isError ? errorMessage(rotate.error) : null}
        onConfirm={() =>
          rotate.mutate(integration.id, {
            onSuccess: (result) => {
              setConfirm(null);
              onToken(integration, result.inboundToken);
            },
          })
        }
      />
    </Drawer>
  );
}
