import { useState } from 'react';
import { Copy, KeyRound, Plus, RefreshCw, Webhook } from 'lucide-react';
import { useDeploymentSources, useRotateSourceSecret, useSaveDeploymentSource, useSourceDeliveries } from '../../api/deployments';
import { useServices } from '../../api/services';
import { DemoBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { SelectField, TextField } from '../../components/FormField';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { apiOrigin, errorMessage, toApiError } from '../../lib/api';
import { formatRelative, titleCase } from '../../lib/format';
import { ENVIRONMENT_LABELS, ENVIRONMENTS } from '../../lib/itsm';
import type { DeploymentEnvironment, DeploymentSourceDto, WebhookProvider } from '../../lib/itsmTypes';

/** Provider set-up hints (docs/API.md "Deployments and releases"). */
export const PROVIDER_HINTS: Record<WebhookProvider, { label: string; events: string; headers: string[]; note: string }> = {
  GITHUB: {
    label: 'GitHub',
    events: 'workflow_run, deployment_status (ping is acknowledged)',
    headers: ['X-Hub-Signature-256: sha256=<HMAC-SHA256(secret, raw body)>', 'X-GitHub-Event', 'X-GitHub-Delivery'],
    note: 'Repository → Settings → Webhooks: content type application/json, paste the secret.',
  },
  GITLAB: {
    label: 'GitLab',
    events: 'Pipeline Hook, Deployment Hook',
    headers: ['X-Gitlab-Token: <secret>', 'X-Gitlab-Event', 'X-Gitlab-Event-UUID'],
    note: 'Project → Settings → Webhooks: paste the secret as the secret token.',
  },
  GENERIC: {
    label: 'Generic signed JSON',
    events: 'Deployment JSON {externalId, version, status, environment?, service?, commit?, pipelineUrl?, changeNumber?, …}',
    headers: ['X-Nexus-Timestamp: <unix seconds, ±5 min>', 'X-Nexus-Delivery: <unique id>', 'X-Nexus-Signature: sha256=<HMAC-SHA256(secret, timestamp + "." + raw body)>'],
    note: 'Any CI system that can sign a request body with HMAC-SHA256.',
  },
};

function webhookUrl(path: string): string {
  return `${apiOrigin()}${path}`;
}

/** One-time secret display (create / rotate). */
function SecretModal({ secret, source, onClose }: { secret: string; source: DeploymentSourceDto; onClose: () => void }) {
  const toast = useToast();
  const copy = (value: string) =>
    void navigator.clipboard?.writeText(value).then(
      () => toast.success('Copied'),
      () => toast.error('Copy failed — select the text instead'),
    );
  return (
    <Modal
      open
      onClose={onClose}
      title={`Webhook secret for ${source.name}`}
      eyebrow="SHOWN ONCE"
      description="Store it in your CI provider now. It is kept encrypted and cannot be shown again — rotate it if it is lost."
      footer={
        <button type="button" className="gold-btn" onClick={onClose}>
          I have stored the secret
        </button>
      }
    >
      <div className="field">
        <div className="field-label">
          <label htmlFor="source-secret">Secret</label>
        </div>
        <div className="flex gap-2">
          <input id="source-secret" className="control font-mono" readOnly value={secret} onFocus={(event) => event.target.select()} />
          <button type="button" className="icon-btn" aria-label="Copy secret" onClick={() => copy(secret)}>
            <Copy size={14} />
          </button>
        </div>
      </div>
      <div className="field">
        <div className="field-label">
          <label htmlFor="source-url">Payload URL</label>
        </div>
        <div className="flex gap-2">
          <input id="source-url" className="control font-mono" readOnly value={webhookUrl(source.webhookPath)} onFocus={(event) => event.target.select()} />
          <button type="button" className="icon-btn" aria-label="Copy payload URL" onClick={() => copy(webhookUrl(source.webhookPath))}>
            <Copy size={14} />
          </button>
        </div>
      </div>
    </Modal>
  );
}

function Deliveries({ sourceId }: { sourceId: number }) {
  const deliveries = useSourceDeliveries(sourceId);
  if (deliveries.isLoading) return <LoadingState variant="inline" />;
  if (deliveries.isError) return <ErrorState error={deliveries.error} compact />;
  if (!deliveries.data?.length) return <p className="m-0 text-[11px] text-muted">No deliveries received yet.</p>;
  return (
    <ul className="m-0 grid list-none gap-1 p-0" aria-label="Recent deliveries">
      {deliveries.data.map((delivery) => (
        <li key={delivery.id} className="flex flex-wrap items-center justify-between gap-2 text-[11px]">
          <span className="min-w-0 break-words">
            {delivery.eventType ?? 'event'} · {delivery.message ?? '—'} · {formatRelative(delivery.receivedAt)}
          </span>
          <ToneBadge value={delivery.outcome} tone={delivery.outcome === 'PROCESSED' ? 'success' : delivery.outcome === 'FAILED' ? 'critical' : 'neutral'} />
        </li>
      ))}
    </ul>
  );
}

/** Generic CI/CD webhook hints for every reader (no source list). */
export function WebhookHints() {
  return (
    <div className="grid gap-2 md:grid-cols-3">
      {(Object.keys(PROVIDER_HINTS) as WebhookProvider[]).map((provider) => {
        const hint = PROVIDER_HINTS[provider];
        return (
          <div key={provider} className="panel raised min-w-0">
            <b className="text-[12px]">{hint.label}</b>
            <p className="mt-1 mb-1 text-[10px] text-muted">{hint.note}</p>
            <small className="block text-[10px]">Endpoint: </small>
            <code className="block break-all text-[10px]">POST /api/deployments/webhooks/&lt;sourceId&gt;</code>
            <small className="mt-1 block text-[10px]">Headers:</small>
            <ul className="m-0 pl-4 text-[10px]">
              {hint.headers.map((header) => (
                <li key={header} className="break-all">
                  <code>{header}</code>
                </li>
              ))}
            </ul>
            <small className="mt-1 block text-[10px] text-muted">Events: {hint.events}</small>
          </div>
        );
      })}
    </div>
  );
}

/** Deployment editors: CI/CD webhook sources (GitHub, GitLab, generic signed JSON); the secret is shown once. */
export function WebhookSourcesPanel({ canEdit }: { canEdit: boolean }) {
  const sources = useDeploymentSources(canEdit);
  const save = useSaveDeploymentSource();
  const rotate = useRotateSourceSecret();
  const services = useServices();
  const toast = useToast();
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [provider, setProvider] = useState<WebhookProvider>('GITHUB');
  const [serviceId, setServiceId] = useState('');
  const [environment, setEnvironment] = useState<DeploymentEnvironment>('PRODUCTION');
  const [secret, setSecret] = useState<{ secret: string; source: DeploymentSourceDto } | null>(null);
  const [rotating, setRotating] = useState<DeploymentSourceDto | null>(null);
  const [openDeliveries, setOpenDeliveries] = useState<number | null>(null);
  const fieldErrors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];

  const create = () =>
    save.mutate(
      { body: { name: name.trim(), provider, serviceId: Number(serviceId), defaultEnvironment: environment, active: true } },
      {
        onSuccess: (result) => {
          setAdding(false);
          setName('');
          setServiceId('');
          if (result.secret) setSecret({ secret: result.secret, source: result.source });
          toast.success(`Source ${result.source.name} created`);
        },
      },
    );

  const toggle = (source: DeploymentSourceDto) =>
    save.mutate(
      { id: source.id, body: { name: source.name, provider: source.provider, serviceId: source.serviceId, defaultEnvironment: source.defaultEnvironment, active: !source.active } },
      { onSuccess: () => toast.success(`${source.name} ${source.active ? 'deactivated' : 'activated'}`), onError: (error) => toast.error(error) },
    );

  return (
    <div className="grid gap-[14px]">
      <Panel title="CI/CD integration" subtitle="Pipelines report deployments to a source's webhook endpoint; unsigned or wrongly signed requests are rejected with 401.">
        <WebhookHints />
      </Panel>
      {canEdit && (
        <Panel
          title="Webhook sources"
          actions={
            !adding ? (
              <button type="button" className="gold-btn compact" onClick={() => setAdding(true)}>
                <Plus size={13} aria-hidden="true" /> New source
              </button>
            ) : undefined
          }
        >
          {adding && (
            <div className="action-card mb-3" role="group" aria-label="New webhook source">
              <div className="form-row">
                <TextField label="Name" required maxLength={120} placeholder="payroll-github" value={name} onChange={(event) => setName(event.target.value)} error={fieldErrors.find((error) => error.field === 'name')?.message} />
                <SelectField label="Provider" required value={provider} onChange={(event) => setProvider(event.target.value as WebhookProvider)} hint="Cannot change later.">
                  {(Object.keys(PROVIDER_HINTS) as WebhookProvider[]).map((value) => (
                    <option key={value} value={value}>
                      {PROVIDER_HINTS[value].label}
                    </option>
                  ))}
                </SelectField>
              </div>
              <div className="form-row">
                <SelectField label="Service" required value={serviceId} onChange={(event) => setServiceId(event.target.value)} error={fieldErrors.find((error) => error.field === 'serviceId')?.message}>
                  <option value="">Choose a service</option>
                  {(services.data ?? []).map((service) => (
                    <option key={service.id} value={service.id}>
                      {service.name}
                    </option>
                  ))}
                </SelectField>
                <SelectField label="Default environment" value={environment} onChange={(event) => setEnvironment(event.target.value as DeploymentEnvironment)}>
                  {ENVIRONMENTS.map((value) => (
                    <option key={value} value={value}>
                      {ENVIRONMENT_LABELS[value]}
                    </option>
                  ))}
                </SelectField>
              </div>
              {save.isError && !fieldErrors.length && (
                <p className="field-error" role="alert">
                  {errorMessage(save.error)}
                </p>
              )}
              <div className="flex justify-end gap-2">
                <button type="button" className="subtle-btn compact" onClick={() => setAdding(false)} disabled={save.isPending}>
                  Cancel
                </button>
                <button type="button" className="gold-btn compact" onClick={create} disabled={!name.trim() || !serviceId || save.isPending}>
                  {save.isPending ? 'Creating…' : 'Create source'}
                </button>
              </div>
            </div>
          )}
          {sources.isLoading && <LoadingState rows={2} />}
          {sources.isError && <ErrorState error={sources.error} onRetry={() => void sources.refetch()} compact />}
          {sources.data && sources.data.length === 0 && <EmptyState title="No webhook sources" description="Create one per pipeline to record deployments automatically." icon={<Webhook size={18} />} />}
          {sources.data && sources.data.length > 0 && (
            <ul className="m-0 grid list-none gap-2 p-0" aria-label="Webhook sources">
              {sources.data.map((source) => (
                <li key={source.id} className="rounded-[10px] border border-line p-3">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="min-w-0">
                      <b className="flex flex-wrap items-center gap-1.5 text-[12px]">
                        {source.name} <ToneBadge value={source.provider} label={PROVIDER_HINTS[source.provider]?.label ?? titleCase(source.provider)} tone="champagne" /> <DemoBadge demo={source.demo} />
                      </b>
                      <small className="block text-muted">
                        {source.serviceName} · default {source.defaultEnvironment ? ENVIRONMENT_LABELS[source.defaultEnvironment] : '—'} · last delivery {source.lastDeliveryAt ? formatRelative(source.lastDeliveryAt) : 'never'}
                      </small>
                      <code className="mt-1 block break-all text-[10px]">POST {webhookUrl(source.webhookPath)}</code>
                      <small className="block break-all text-[10px] text-muted">Signature: {PROVIDER_HINTS[source.provider]?.headers[0]}</small>
                    </div>
                    <span className="flex shrink-0 flex-wrap items-center gap-1">
                      <button type="button" className="subtle-btn compact" onClick={() => toggle(source)} disabled={save.isPending} aria-label={`${source.active ? 'Deactivate' : 'Activate'} ${source.name}`}>
                        {source.active ? 'Active' : 'Inactive'}
                      </button>
                      <button type="button" className="subtle-btn compact" onClick={() => setRotating(source)} aria-label={`Rotate secret of ${source.name}`}>
                        <KeyRound size={12} aria-hidden="true" /> Rotate secret
                      </button>
                      <button type="button" className="subtle-btn compact" onClick={() => setOpenDeliveries(openDeliveries === source.id ? null : source.id)} aria-expanded={openDeliveries === source.id}>
                        <RefreshCw size={12} aria-hidden="true" /> Deliveries
                      </button>
                    </span>
                  </div>
                  {openDeliveries === source.id && (
                    <div className="mt-2 border-t border-line pt-2">
                      <Deliveries sourceId={source.id} />
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Panel>
      )}
      {secret && <SecretModal secret={secret.secret} source={secret.source} onClose={() => setSecret(null)} />}
      <ConfirmDialog
        open={rotating !== null}
        onClose={() => {
          setRotating(null);
          rotate.reset();
        }}
        title={`Rotate the secret of ${rotating?.name ?? ''}?`}
        message="The current secret stops working immediately; update the pipeline with the new one."
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
    </div>
  );
}
