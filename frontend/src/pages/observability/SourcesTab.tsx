import { useState } from 'react';
import { Copy, KeyRound, Pencil, Plug, Plus, RefreshCw, Trash2, Zap } from 'lucide-react';
import { useDeleteTelemetrySource, usePullSource, useRotateSourceToken, useSaveTelemetrySource, useTelemetryKinds, useTelemetrySources, useTestSource } from '../../api/observability';
import { DemoBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { apiOrigin, errorMessage, toApiError } from '../../lib/api';
import { formatDateTime, formatNumber, formatRelative } from '../../lib/format';
import type { TelemetryAuthType, TelemetryKindInfo, TelemetrySourceDto, TelemetrySourceKind, TelemetrySourceRequest } from '../../lib/r4Types';
import { SOURCE_KIND_LABELS, SourceStatusBadge } from './obsShared';

const DEFAULT_OTLP_PATHS = { metrics: '/api/otel/v1/metrics', logs: '/api/otel/v1/logs', traces: '/api/otel/v1/traces' };

/** Absolute OTLP endpoints of a source (`ingestPaths` from the API, else the documented defaults). */
export function otlpEndpoints(source: Pick<TelemetrySourceDto, 'ingestPaths'> | null) {
  const paths = { ...DEFAULT_OTLP_PATHS, ...(source?.ingestPaths ?? {}) };
  const origin = apiOrigin();
  return { metrics: `${origin}${paths.metrics}`, logs: `${origin}${paths.logs}`, traces: `${origin}${paths.traces}`, base: `${origin}${paths.metrics.replace(/\/v1\/metrics$/, '')}` };
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

/** OTLP/HTTP JSON setup: exact endpoint paths, the bearer header and what is not supported. */
export function OtlpInstructions({ source, token }: { source: TelemetrySourceDto | null; token?: string | null }) {
  const endpoints = otlpEndpoints(source);
  const shownToken = token ?? `<source token${source?.tokenHint ? ` ${source.tokenHint}` : ''}>`;
  return (
    <div className="grid gap-2 text-[11px]" aria-label="OTLP setup">
      <b>OTLP/HTTP setup (JSON encoding only)</b>
      <ul className="m-0 grid gap-1 pl-4">
        <li>
          Metrics: <code className="break-all">POST {endpoints.metrics}</code>
        </li>
        <li>
          Logs: <code className="break-all">POST {endpoints.logs}</code>
        </li>
        <li>
          Traces: <code className="break-all">POST {endpoints.traces}</code>
        </li>
        <li>
          Headers: <code>Authorization: Bearer {shownToken}</code>, <code>Content-Type: application/json</code>, optional <code>Content-Encoding: gzip</code>
        </li>
        <li>Binary protobuf is not supported (415). Bodies up to 2 MB and 10,000 items per request; invalid items are reported in partialSuccess.</li>
        <li>The source becomes CONNECTED with the first authenticated delivery.</li>
      </ul>
      <small className="text-muted">OpenTelemetry Collector exporter</small>
      <pre className="code-block">{`exporters:
  otlphttp/sa:
    endpoint: ${endpoints.base}
    encoding: json
    compression: gzip
    headers:
      Authorization: "Bearer ${shownToken}"`}</pre>
      <small className="text-muted">Test with curl</small>
      <pre className="code-block">{`curl -X POST '${endpoints.metrics}' \\
  -H 'Authorization: Bearer ${shownToken}' \\
  -H 'Content-Type: application/json' \\
  -d '{"resourceMetrics":[]}'`}</pre>
    </div>
  );
}

function TokenModal({ token, source, onClose }: { token: string; source: TelemetrySourceDto; onClose: () => void }) {
  return (
    <Modal
      open
      onClose={onClose}
      title={`Token for ${source.name}`}
      eyebrow="SHOWN ONCE"
      description="Store it in your collector or SDK configuration now. Only a SHA-256 hash is kept, so it cannot be shown again — rotate the token if it is lost."
      size="lg"
      footer={
        <button type="button" className="gold-btn" onClick={onClose}>
          I have stored the token
        </button>
      }
    >
      <CopyField id="otlp-token" label="Source token" value={token} />
      <OtlpInstructions source={source} token={token} />
    </Modal>
  );
}

interface Draft {
  kind: TelemetrySourceKind;
  name: string;
  baseUrl: string;
  authType: TelemetryAuthType;
  username: string;
  secret: string;
  config: string;
  pullEnabled: boolean;
  pullIntervalSeconds: string;
  active: boolean;
}

function draftOf(source: TelemetrySourceDto | null, kind: TelemetryKindInfo | undefined): Draft {
  return {
    kind: source?.kind ?? kind?.kind ?? 'OTLP',
    name: source?.name ?? '',
    baseUrl: source?.baseUrl ?? kind?.defaultBaseUrl ?? '',
    authType: source?.authType ?? 'NONE',
    username: source?.username ?? '',
    secret: '',
    config: source?.config && Object.keys(source.config).length ? JSON.stringify(source.config, null, 2) : '',
    pullEnabled: source?.pullEnabled ?? false,
    pullIntervalSeconds: String(source?.pullIntervalSeconds ?? 60),
    active: source?.active ?? true,
  };
}

function SourceEditor({ source, kinds, onClose, onToken }: { source: TelemetrySourceDto | null; kinds: TelemetryKindInfo[]; onClose: () => void; onToken: (token: string, source: TelemetrySourceDto) => void }) {
  const save = useSaveTelemetrySource();
  const toast = useToast();
  const [draft, setDraft] = useState<Draft>(() => draftOf(source, kinds[0]));
  const [error, setError] = useState<string | null>(null);
  const kind = kinds.find((entry) => entry.kind === draft.kind);
  const isOtlp = draft.kind === 'OTLP';
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }));

  const submit = () => {
    setError(null);
    if (draft.name.trim().length < 2) return setError('Name: at least 2 characters');
    let config: Record<string, unknown> | null = null;
    if (!isOtlp && draft.config.trim()) {
      try {
        const parsed: unknown = JSON.parse(draft.config);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return setError('Configuration must be a JSON object');
        config = parsed as Record<string, unknown>;
      } catch {
        return setError('Configuration is not valid JSON');
      }
    }
    const interval = Number(draft.pullIntervalSeconds);
    if (draft.pullEnabled && (!Number.isFinite(interval) || interval < 30 || interval > 3600)) return setError('Pull interval: 30–3600 seconds');
    const body: TelemetrySourceRequest = isOtlp
      ? { name: draft.name.trim(), kind: draft.kind, authType: 'NONE', active: draft.active }
      : {
          name: draft.name.trim(),
          kind: draft.kind,
          baseUrl: draft.baseUrl.trim() || null,
          authType: draft.authType,
          username: draft.authType === 'BASIC' ? draft.username.trim() || null : null,
          secret: draft.secret ? draft.secret : null,
          config,
          pullEnabled: kind?.pullSupported ? draft.pullEnabled : false,
          pullIntervalSeconds: interval,
          active: draft.active,
        };
    save.mutate(
      { id: source?.id, body },
      {
        onSuccess: (saved) => {
          toast.success(source ? `Saved ${saved.source.name}` : `Created ${saved.source.name}`);
          onClose();
          if (saved.token) onToken(saved.token, saved.source);
        },
        onError: (err) => {
          const apiError = toApiError(err);
          setError(apiError.fieldErrors?.length ? apiError.fieldErrors.map((field) => `${field.field}: ${field.message}`).join('; ') : apiError.message);
        },
      },
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={source ? `Edit ${source.name}` : 'New telemetry source'}
      eyebrow="TELEMETRY SOURCE"
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={save.isPending}>
            {save.isPending ? 'Saving…' : source ? 'Save source' : 'Create source'}
          </button>
        </>
      }
    >
      <div className="grid gap-2 sm:grid-cols-2">
        <div className="field">
          <div className="field-label">
            <label htmlFor="src-kind">Kind</label>
          </div>
          <select
            id="src-kind"
            className="control"
            value={draft.kind}
            disabled={Boolean(source)}
            onChange={(event) => {
              const next = kinds.find((entry) => entry.kind === event.target.value);
              setDraft((current) => ({ ...current, kind: event.target.value as TelemetrySourceKind, baseUrl: next?.defaultBaseUrl ?? '', pullEnabled: false, config: '' }));
            }}
          >
            {kinds.map((entry) => (
              <option key={entry.kind} value={entry.kind}>
                {SOURCE_KIND_LABELS[entry.kind] ?? entry.kind}
                {entry.prepared ? ' — prepared' : ''}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <div className="field-label">
            <label htmlFor="src-name">Name</label>
          </div>
          <input id="src-name" className="control" maxLength={120} value={draft.name} onChange={(event) => set('name', event.target.value)} />
        </div>
      </div>
      {kind && (
        <p className={`callout ${kind.prepared ? 'red' : ''} my-2`} role="note">
          {kind.prepared ? (
            <span>
              <b>Prepared adapter — not operational until tested.</b> Configuration, request builder and response parser are implemented and tested with fixtures; it
              stays NOT_CONNECTED until a real test call succeeds{kind.testSupported ? '' : ' (no test call is implemented for this kind, so it stays NOT_CONNECTED)'} and it cannot pull data.{' '}
            </span>
          ) : null}
          {kind.description}
        </p>
      )}
      {!isOtlp && (
        <div className="grid gap-2 sm:grid-cols-2">
          <div className="field sm:col-span-2">
            <div className="field-label">
              <label htmlFor="src-url">Base URL</label>
            </div>
            <input id="src-url" className="control" maxLength={500} value={draft.baseUrl} onChange={(event) => set('baseUrl', event.target.value)} placeholder={kind?.defaultBaseUrl ?? 'https://prometheus.example.com'} />
            <span className="field-hint">Checked by the SSRF guard before every call; redirects are not followed.</span>
          </div>
          <div className="field">
            <div className="field-label">
              <label htmlFor="src-auth">Authentication</label>
            </div>
            <select id="src-auth" className="control" value={draft.authType} onChange={(event) => set('authType', event.target.value as TelemetryAuthType)}>
              <option value="NONE">None</option>
              <option value="BEARER">Bearer token</option>
              <option value="BASIC">Basic (user + password)</option>
              <option value="API_KEY">API key / access token</option>
            </select>
          </div>
          {draft.authType === 'BASIC' && (
            <div className="field">
              <div className="field-label">
                <label htmlFor="src-user">Username</label>
              </div>
              <input id="src-user" className="control" maxLength={200} value={draft.username} onChange={(event) => set('username', event.target.value)} autoComplete="off" />
            </div>
          )}
          {draft.authType !== 'NONE' && (
            <div className="field">
              <div className="field-label">
                <label htmlFor="src-secret">{draft.authType === 'BASIC' ? 'Password' : 'Secret'}</label>
              </div>
              <input id="src-secret" className="control" type="password" autoComplete="new-password" maxLength={4000} value={draft.secret} onChange={(event) => set('secret', event.target.value)} placeholder={source?.hasSecret ? 'Stored — leave blank to keep' : ''} />
              <span className="field-hint">Write-only: encrypted at rest and never shown again.</span>
            </div>
          )}
          <div className="field sm:col-span-2">
            <div className="field-label">
              <label htmlFor="src-config">Configuration (JSON)</label>
            </div>
            <textarea id="src-config" className="control font-mono" rows={5} value={draft.config} onChange={(event) => set('config', event.target.value)} placeholder={kind?.configKeys?.length ? `{ ${kind.configKeys.map((key) => `"${key}": …`).join(', ')} }` : '{}'} />
            {kind?.configKeys?.length ? <span className="field-hint">Keys: {kind.configKeys.join(', ')}</span> : null}
          </div>
          {kind?.pullSupported && (
            <>
              <label className="inline-flex items-center gap-2 text-[11px]">
                <input type="checkbox" checked={draft.pullEnabled} onChange={(event) => set('pullEnabled', event.target.checked)} /> Scheduled pull
              </label>
              <div className="field">
                <div className="field-label">
                  <label htmlFor="src-interval">Pull interval (seconds)</label>
                </div>
                <input id="src-interval" className="control" type="number" min={30} max={3600} value={draft.pullIntervalSeconds} onChange={(event) => set('pullIntervalSeconds', event.target.value)} disabled={!draft.pullEnabled} />
              </div>
            </>
          )}
        </div>
      )}
      {isOtlp && !source && <p className="m-0 text-[11px] text-muted">An OTLP source has no URL or credentials: SA generates a bearer token, shown once after creation.</p>}
      <label className="mt-2 inline-flex items-center gap-2 text-[11px]">
        <input type="checkbox" checked={draft.active} onChange={(event) => set('active', event.target.checked)} /> Active
      </label>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </Modal>
  );
}

/** Sources (TELEMETRY_CONFIG): OTLP push sources (token shown once), Prometheus pull and prepared cloud adapters. */
export function SourcesTab() {
  const sources = useTelemetrySources();
  const kinds = useTelemetryKinds();
  const test = useTestSource();
  const pull = usePullSource();
  const rotate = useRotateSourceToken();
  const remove = useDeleteTelemetrySource();
  const toast = useToast();
  const [editing, setEditing] = useState<TelemetrySourceDto | null | 'new'>(null);
  const [token, setToken] = useState<{ token: string; source: TelemetrySourceDto } | null>(null);
  const [rotating, setRotating] = useState<TelemetrySourceDto | null>(null);
  const [deleting, setDeleting] = useState<TelemetrySourceDto | null>(null);
  const [setupFor, setSetupFor] = useState<TelemetrySourceDto | null>(null);
  const kindList = kinds.data ?? [];
  const kindOf = (kind: TelemetrySourceKind) => kindList.find((entry) => entry.kind === kind);

  const runTest = (source: TelemetrySourceDto) =>
    test.mutate(source.id, {
      onSuccess: (result) => {
        const text = `${source.name}: ${result.success ? 'test succeeded' : 'test failed'}${result.httpStatus ? ` (HTTP ${result.httpStatus})` : ''}${result.message ? ` — ${result.message}` : ''}`;
        if (result.success) toast.success(text);
        else toast.warning(text);
      },
      onError: (error) => toast.error(error),
    });
  const runPull = (source: TelemetrySourceDto) =>
    pull.mutate(
      { id: source.id },
      {
        onSuccess: (result) => toast.success(`${source.name}: ${formatNumber(result.queries)} queries, ${formatNumber(result.series)} series, ${formatNumber(result.points)} points${result.rejected ? `, ${formatNumber(result.rejected)} rejected` : ''}${result.message ? ` — ${result.message}` : ''}`),
        onError: (error) => toast.error(error),
      },
    );

  return (
    <Panel
      title="Telemetry sources"
      subtitle="Integrations are “prepared” until verified: a source is CONNECTED only after a successful real call or delivery"
      actions={
        <button type="button" className="gold-btn compact" onClick={() => setEditing('new')} disabled={!kindList.length}>
          <Plus size={13} aria-hidden="true" /> New source
        </button>
      }
    >
      {(sources.isLoading || kinds.isLoading) && <LoadingState rows={4} />}
      {sources.isError && <ErrorState error={sources.error} onRetry={() => void sources.refetch()} />}
      {kinds.isError && <ErrorState error={kinds.error} onRetry={() => void kinds.refetch()} compact />}
      {sources.data && sources.data.length === 0 && <EmptyState icon={<Plug size={20} />} title="No telemetry sources" description="Create an OTLP source for collectors and SDKs, or a Prometheus HTTP API source to pull from." />}
      {sources.data && sources.data.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Telemetry sources">
          {sources.data.map((source) => {
            const info = kindOf(source.kind);
            const testable = source.kind !== 'OTLP' && (info?.testSupported ?? true);
            const pullable = source.kind === 'PROMETHEUS_API' && (info?.pullSupported ?? true) && source.active && !source.prepared;
            return (
              <li key={source.id} className="list-row flex-wrap !items-start sm:flex-nowrap">
                <div className="min-w-0">
                  <b className="flex flex-wrap items-center gap-1.5">
                    <span className="break-words">{source.name}</span>
                    <ToneBadge value={source.kind} label={SOURCE_KIND_LABELS[source.kind] ?? source.kind} tone="champagne" />
                    <SourceStatusBadge status={source.status} lastVerifiedAt={source.lastVerifiedAt} />
                    {source.prepared && <ToneBadge value="prepared" label="Prepared — not operational until tested" tone="warning" />}
                    {!source.active && <ToneBadge value="inactive" label="Inactive" tone="muted" />}
                    <DemoBadge demo={source.demo} />
                  </b>
                  <small className="block break-all">
                    {source.kind === 'OTLP' ? `Push · token ${source.tokenHint ?? 'not set'}` : source.baseUrl ?? 'No base URL'}
                    {source.authType && source.authType !== 'NONE' ? ` · ${source.authType}${source.hasSecret ? ' (secret stored)' : ' (no secret)'}` : ''}
                    {source.pullEnabled ? ` · pulls every ${formatNumber(source.pullIntervalSeconds)} s` : ''}
                  </small>
                  <small className="block text-muted">
                    {formatNumber(source.receivedCount)} items received
                    {source.lastReceivedAt ? ` · last ${formatRelative(source.lastReceivedAt)}` : ''}
                    {source.lastPulledAt ? ` · last pull ${formatRelative(source.lastPulledAt)}` : ''}
                    {source.lastTestedAt ? ` · tested ${formatRelative(source.lastTestedAt)}` : ' · never tested'}
                  </small>
                  {source.lastError && <small className="block red-text break-words">Last error: {source.lastError}</small>}
                  {source.lastVerifiedAt && <small className="block text-muted">Verified {formatDateTime(source.lastVerifiedAt)}</small>}
                </div>
                <span className="flex shrink-0 flex-wrap gap-1">
                  {source.kind === 'OTLP' && (
                    <>
                      <button type="button" className="subtle-btn compact" onClick={() => setSetupFor(source)} aria-label={`Show setup for ${source.name}`}>
                        Setup
                      </button>
                      <button type="button" className="subtle-btn compact" onClick={() => setRotating(source)} aria-label={`Rotate the token of ${source.name}`}>
                        <KeyRound size={11} aria-hidden="true" /> Rotate token
                      </button>
                    </>
                  )}
                  {testable && (
                    <button type="button" className="subtle-btn compact" onClick={() => runTest(source)} disabled={test.isPending} aria-label={`Test ${source.name}`}>
                      <Zap size={11} aria-hidden="true" /> Test
                    </button>
                  )}
                  {pullable && (
                    <button type="button" className="subtle-btn compact" onClick={() => runPull(source)} disabled={pull.isPending} aria-label={`Pull ${source.name} now`}>
                      <RefreshCw size={11} aria-hidden="true" /> Pull now
                    </button>
                  )}
                  <button type="button" className="icon-btn sm" onClick={() => setEditing(source)} aria-label={`Edit ${source.name}`}>
                    <Pencil size={12} />
                  </button>
                  <button type="button" className="icon-btn sm" onClick={() => setDeleting(source)} aria-label={`Delete ${source.name}`}>
                    <Trash2 size={12} />
                  </button>
                </span>
              </li>
            );
          })}
        </ul>
      )}
      {editing && kindList.length > 0 && <SourceEditor source={editing === 'new' ? null : editing} kinds={kindList} onClose={() => setEditing(null)} onToken={(value, source) => setToken({ token: value, source })} />}
      {token && <TokenModal token={token.token} source={token.source} onClose={() => setToken(null)} />}
      {setupFor && (
        <Modal open onClose={() => setSetupFor(null)} title={`Setup for ${setupFor.name}`} size="lg" footer={<button type="button" className="subtle-btn" onClick={() => setSetupFor(null)}>Close</button>}>
          <OtlpInstructions source={setupFor} />
          <p className="mb-0 mt-2 text-[11px] text-muted">The token itself was shown once when it was created or rotated. Rotate it to obtain a new one.</p>
        </Modal>
      )}
      <ConfirmDialog
        open={rotating !== null}
        onClose={() => setRotating(null)}
        title={`Rotate the token of ${rotating?.name ?? 'this source'}?`}
        message="A new token is generated and shown once. The old token stops working immediately, so senders using it are rejected (401) until they are updated."
        confirmLabel="Rotate token"
        tone="danger"
        pending={rotate.isPending}
        error={rotate.isError ? errorMessage(rotate.error) : null}
        onConfirm={() =>
          rotating &&
          rotate.mutate(rotating.id, {
            onSuccess: (saved) => {
              setRotating(null);
              if (saved.token) setToken({ token: saved.token, source: saved.source });
            },
          })
        }
      />
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title={`Delete ${deleting?.name ?? 'source'}?`}
        message="Deliveries with its token are rejected and scheduled pulls stop. Telemetry already stored is kept until retention removes it."
        confirmLabel="Delete source"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              toast.success(`Deleted ${deleting.name}`);
              setDeleting(null);
            },
          })
        }
      />
    </Panel>
  );
}
