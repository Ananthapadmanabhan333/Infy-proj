import { useState } from 'react';
import { Pencil, Plus, RefreshCcw, Route, Trash2 } from 'lucide-react';
import { useDeleteMappingRule, useMappingRules, useReapplyMappings, useSaveMappingRule, useTelemetryResources } from '../../api/observability';
import { useServices } from '../../api/services';
import { DemoBadge, SimulatedBadge } from '../../components/Badge';
import { CiSinglePicker } from '../../components/cmdb/CiPicker';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { formatNumber, formatRelative } from '../../lib/format';
import type { MappingMatchType, MappingRuleDto } from '../../lib/r4Types';
import { attributesText } from './obsShared';

interface Draft {
  name: string;
  attributeKey: string;
  matchType: MappingMatchType;
  matchValue: string;
  serviceId: string;
  ci: { id: number; name: string } | null;
  sortOrder: string;
  enabled: boolean;
}

function RuleEditor({ rule, prefill, onClose }: { rule: MappingRuleDto | null; prefill?: Partial<Draft>; onClose: () => void }) {
  const save = useSaveMappingRule();
  const services = useServices();
  const toast = useToast();
  const [draft, setDraft] = useState<Draft>(() => ({
    name: rule?.name ?? '',
    attributeKey: rule?.attributeKey ?? 'service.name',
    matchType: rule?.matchType ?? 'EXACT',
    matchValue: rule?.matchValue ?? '',
    serviceId: rule?.serviceId ? String(rule.serviceId) : '',
    ci: rule?.ciId ? { id: rule.ciId, name: rule.ciName ?? `CI #${rule.ciId}` } : null,
    sortOrder: String(rule?.sortOrder ?? 100),
    enabled: rule?.enabled ?? true,
    ...prefill,
  }));
  const [error, setError] = useState<string | null>(null);
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }));

  const submit = () => {
    setError(null);
    if (draft.name.trim().length < 2) return setError('Name: at least 2 characters');
    if (!draft.attributeKey.trim() || !draft.matchValue.trim()) return setError('Attribute key and match value are required');
    if (draft.matchType === 'REGEX') {
      try {
        new RegExp(draft.matchValue);
      } catch {
        return setError('The regular expression is not valid');
      }
    }
    if (!draft.serviceId && !draft.ci) return setError('Choose a service, a configuration item or both');
    save.mutate(
      {
        id: rule?.id,
        body: {
          name: draft.name.trim(),
          attributeKey: draft.attributeKey.trim(),
          matchType: draft.matchType,
          matchValue: draft.matchValue.trim(),
          serviceId: draft.serviceId ? Number(draft.serviceId) : null,
          ciId: draft.ci?.id ?? null,
          sortOrder: Number(draft.sortOrder) || 0,
          enabled: draft.enabled,
        },
      },
      {
        onSuccess: (saved) => {
          toast.success(rule ? `Saved ${saved.name}` : `Created ${saved.name} — reapply to map existing resources`);
          onClose();
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
      title={rule ? `Edit ${rule.name}` : 'New mapping rule'}
      eyebrow="MAPPING RULE"
      description="Telemetry resources whose attribute matches (EXACT, or a full-match REGEX) are mapped to the service and/or CI. Rules are tried by sort order."
      size="lg"
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
      <div className="grid gap-2 sm:grid-cols-2">
        <div className="field">
          <div className="field-label">
            <label htmlFor="map-name">Name</label>
          </div>
          <input id="map-name" className="control" maxLength={120} value={draft.name} onChange={(event) => set('name', event.target.value)} />
        </div>
        <div className="field">
          <div className="field-label">
            <label htmlFor="map-key">Resource attribute</label>
          </div>
          <input id="map-key" className="control" list="map-key-options" maxLength={120} value={draft.attributeKey} onChange={(event) => set('attributeKey', event.target.value)} />
          <datalist id="map-key-options">
            {['service.name', 'service.namespace', 'deployment.environment', 'host.name', 'k8s.namespace.name', 'k8s.deployment.name', 'cloud.region'].map((key) => (
              <option key={key} value={key} />
            ))}
          </datalist>
        </div>
        <div className="field">
          <div className="field-label">
            <label htmlFor="map-type">Match</label>
          </div>
          <select id="map-type" className="control" value={draft.matchType} onChange={(event) => set('matchType', event.target.value as MappingMatchType)}>
            <option value="EXACT">Exact value</option>
            <option value="REGEX">Regular expression (full match)</option>
          </select>
        </div>
        <div className="field">
          <div className="field-label">
            <label htmlFor="map-value">Value</label>
          </div>
          <input id="map-value" className="control font-mono" maxLength={300} value={draft.matchValue} onChange={(event) => set('matchValue', event.target.value)} />
        </div>
        <div className="field">
          <div className="field-label">
            <label htmlFor="map-service">Service</label>
          </div>
          <select id="map-service" className="control" value={draft.serviceId} onChange={(event) => set('serviceId', event.target.value)}>
            <option value="">No service</option>
            {(services.data ?? []).map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <div className="field-label">
            <label htmlFor="map-order">Sort order</label>
          </div>
          <input id="map-order" className="control" type="number" min={0} max={10000} value={draft.sortOrder} onChange={(event) => set('sortOrder', event.target.value)} />
        </div>
        <div className="sm:col-span-2">
          <CiSinglePicker label="Configuration item (optional)" value={draft.ci} onChange={(ci) => set('ci', ci ? { id: ci.id, name: ci.name } : null)} />
        </div>
        <label className="inline-flex items-center gap-2 text-[11px]">
          <input type="checkbox" checked={draft.enabled} onChange={(event) => set('enabled', event.target.checked)} /> Enabled
        </label>
      </div>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </Modal>
  );
}

/** Mappings: rules from resource attributes to services/CIs (CRUD + reapply for TELEMETRY_CONFIG) and the unmapped resources. */
export function MappingsTab({ includeSimulated, canConfigure }: { includeSimulated: boolean; canConfigure: boolean }) {
  const rules = useMappingRules();
  const [page, setPage] = useState(0);
  const unmapped = useTelemetryResources({ mapped: false, includeSimulated, page, size: 20 });
  const reapply = useReapplyMappings();
  const remove = useDeleteMappingRule();
  const toast = useToast();
  const [editing, setEditing] = useState<{ rule: MappingRuleDto | null; prefill?: Partial<Draft> } | null>(null);
  const [deleting, setDeleting] = useState<MappingRuleDto | null>(null);

  const runReapply = () =>
    reapply.mutate(undefined, {
      onSuccess: (result) => toast.success(`Reapplied to ${formatNumber(result.resources)} resources: ${formatNumber(result.changed)} changed, ${formatNumber(result.mapped)} mapped, ${formatNumber(result.unmapped)} unmapped`),
      onError: (error) => toast.error(error),
    });

  return (
    <div className="grid gap-[14px]">
      <Panel
        title="Mapping rules"
        subtitle="Resource attributes (service.name, host.name, k8s.*, cloud.*) → SA services and CMDB CIs"
        actions={
          canConfigure ? (
            <span className="flex flex-wrap gap-1.5">
              <button type="button" className="subtle-btn compact" onClick={runReapply} disabled={reapply.isPending}>
                <RefreshCcw size={12} aria-hidden="true" /> {reapply.isPending ? 'Reapplying…' : 'Reapply rules'}
              </button>
              <button type="button" className="gold-btn compact" onClick={() => setEditing({ rule: null })}>
                <Plus size={13} aria-hidden="true" /> New rule
              </button>
            </span>
          ) : undefined
        }
      >
        {rules.isLoading && <LoadingState rows={4} />}
        {rules.isError && <ErrorState error={rules.error} onRetry={() => void rules.refetch()} />}
        {rules.data && rules.data.length === 0 && <EmptyState icon={<Route size={20} />} title="No mapping rules" description="Without rules telemetry is kept unmapped (no service or CI)." />}
        {rules.data && rules.data.length > 0 && (
          <ul className="m-0 grid list-none gap-1 p-0" aria-label="Mapping rules">
            {rules.data.map((rule) => (
              <li key={rule.id} className="list-row flex-wrap sm:flex-nowrap">
                <div className="min-w-0">
                  <b className="flex flex-wrap items-center gap-1.5">
                    <span className="break-words">{rule.name}</span>
                    {!rule.enabled && <ToneBadge value="disabled" label="Disabled" tone="muted" />}
                    <DemoBadge demo={rule.demo} />
                  </b>
                  <small className="block break-all">
                    <code>{rule.attributeKey}</code> {rule.matchType === 'EXACT' ? '=' : '~'} <code>{rule.matchValue}</code> → {[rule.serviceName, rule.ciName].filter(Boolean).join(' + ') || '—'}
                  </small>
                  <small className="block text-muted">
                    Order {rule.sortOrder} · {formatNumber(rule.matchCount)} matches{rule.lastMatchedAt ? ` · last ${formatRelative(rule.lastMatchedAt)}` : ''}
                  </small>
                </div>
                {canConfigure && (
                  <span className="flex shrink-0 gap-1">
                    <button type="button" className="icon-btn sm" onClick={() => setEditing({ rule })} aria-label={`Edit ${rule.name}`}>
                      <Pencil size={12} />
                    </button>
                    <button type="button" className="icon-btn sm" onClick={() => setDeleting(rule)} aria-label={`Delete ${rule.name}`}>
                      <Trash2 size={12} />
                    </button>
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel title="Unmapped resources" subtitle="Telemetry kept with no service or CI — add a rule to map it">
        {unmapped.isLoading && <LoadingState rows={4} />}
        {unmapped.isError && <ErrorState error={unmapped.error} onRetry={() => void unmapped.refetch()} />}
        {unmapped.data && unmapped.data.content.length === 0 && <EmptyState title="Every resource is mapped" />}
        {unmapped.data && unmapped.data.content.length > 0 && (
          <>
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Unmapped resources">
              {unmapped.data.content.map((resource) => (
                <li key={resource.id} className="list-row flex-wrap sm:flex-nowrap">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <span className="break-all">{resource.serviceName ?? resource.hostName ?? `Resource #${resource.id}`}</span>
                      <SimulatedBadge simulated={resource.simulated} />
                    </b>
                    <small className="block break-all">
                      {[resource.serviceNamespace, resource.environment, resource.hostName].filter(Boolean).join(' · ') || attributesText(resource.attributes, 4) || 'No identifying attributes'}
                    </small>
                    <small className="block text-muted">
                      First seen {formatRelative(resource.firstSeenAt)} · last seen {formatRelative(resource.lastSeenAt)}
                    </small>
                  </div>
                  {canConfigure && resource.serviceName && (
                    <button
                      type="button"
                      className="subtle-btn compact shrink-0"
                      onClick={() => setEditing({ rule: null, prefill: { name: `Map ${resource.serviceName}`, attributeKey: 'service.name', matchType: 'EXACT', matchValue: resource.serviceName ?? '' } })}
                      aria-label={`Create a rule for ${resource.serviceName}`}
                    >
                      <Plus size={11} aria-hidden="true" /> Create rule
                    </button>
                  )}
                </li>
              ))}
            </ul>
            <Pagination page={unmapped.data.page} totalPages={unmapped.data.totalPages} totalElements={unmapped.data.totalElements} size={unmapped.data.size} onPageChange={setPage} label="resources" />
          </>
        )}
      </Panel>

      {editing && <RuleEditor rule={editing.rule} prefill={editing.prefill} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title={`Delete ${deleting?.name ?? 'rule'}?`}
        message="Resources it mapped keep their mapping until the rules are reapplied."
        confirmLabel="Delete rule"
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
    </div>
  );
}
