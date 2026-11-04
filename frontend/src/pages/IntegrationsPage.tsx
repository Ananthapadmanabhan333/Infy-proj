import { useMemo, useState } from 'react';
import { AlertTriangle, CircleCheck, Clock3, Lock, Plug, Plus, Webhook } from 'lucide-react';
import { useIntegrations } from '../api/integrations';
import {
  INTEGRATION_TYPE_ICONS,
  IntegrationDrawer,
  IntegrationEditor,
  integrationTarget,
  integrationTypeLabel,
  isFailing,
  TokenPanel,
} from '../components/admin/IntegrationDialogs';
import { BooleanBadge, DemoBadge, StatusBadge } from '../components/Badge';
import { KpiCard } from '../components/KpiCard';
import { PageHeader } from '../components/PageHeader';
import { Panel } from '../components/Panel';
import { SearchInput } from '../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { Tabs } from '../components/Tabs';
import { useAuth } from '../hooks/useAuth';
import { useTabParam } from '../hooks/useUrlState';
import { CONFIG_ADMINS } from '../lib/constants';
import { formatDateTime, formatNumber, formatRelative } from '../lib/format';
import type { IntegrationDto } from '../lib/types';
import '../components/admin/admin.css';

type View = 'all' | 'outbound' | 'inbound' | 'failing';

function matchesView(integration: IntegrationDto, view: View): boolean {
  if (view === 'outbound') return integration.type !== 'MONITORING_INBOUND';
  if (view === 'inbound') return integration.type === 'MONITORING_INBOUND';
  if (view === 'failing') return isFailing(integration);
  return true;
}

function IntegrationCard({ integration, onOpen }: { integration: IntegrationDto; onOpen: () => void }) {
  const Icon = INTEGRATION_TYPE_ICONS[integration.type] ?? Webhook;
  const target = integrationTarget(integration);
  const failing = isFailing(integration);
  return (
    <article className={`panel admin-card ${integration.enabled ? '' : 'opacity-75'}`} aria-label={integration.name}>
      <div className="flex items-start gap-3">
        <span className={`icon-tile ${failing ? 'critical' : integration.enabled ? '' : 'neutral'}`} aria-hidden="true">
          <Icon size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="m-0 truncate font-heading text-[13px] text-ivory">{integration.name}</h3>
          <p className="m-0 mt-0.5 text-[10px] text-muted">{integrationTypeLabel(integration.type)}</p>
        </div>
        <BooleanBadge value={integration.enabled} on="Enabled" off="Disabled" />
      </div>
      <div className="card-target" title={target ?? undefined}>
        {target ?? 'No endpoint configured'}
      </div>
      <div className="card-meta">
        <span className="inline-flex items-center gap-1.5">
          {integration.lastDeliveryStatus ? <StatusBadge status={integration.lastDeliveryStatus} /> : <span>No deliveries yet</span>}
          {integration.lastDeliveryAt && <span title={formatDateTime(integration.lastDeliveryAt)}>{formatRelative(integration.lastDeliveryAt)}</span>}
        </span>
        <span className={integration.failureCount ? 'red-text' : ''}>
          {formatNumber(integration.failureCount)} {integration.failureCount === 1 ? 'failure' : 'failures'}
        </span>
        {integration.type !== 'MONITORING_INBOUND' && <span>{formatNumber(integration.subscribedEvents.length)} {integration.subscribedEvents.length === 1 ? 'event' : 'events'}</span>}
        <DemoBadge demo={integration.demo} />
      </div>
      <button type="button" className="card-hit" aria-label={`Open ${integration.name}`} onClick={onOpen} />
    </article>
  );
}

/** /integrations: managers read; CONFIG_ADMINS create, edit, test, rotate and delete. */
export default function IntegrationsPage() {
  const { hasRole } = useAuth();
  const admin = hasRole(...CONFIG_ADMINS);
  const integrations = useIntegrations();
  const [view, setView] = useTabParam<View>(['all', 'outbound', 'inbound', 'failing'], 'all');
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [editing, setEditing] = useState<IntegrationDto | null | 'new'>(null);
  const [token, setToken] = useState<{ integration: IntegrationDto; token: string } | null>(null);
  const list = integrations.data ?? [];
  const selected = list.find((integration) => integration.id === selectedId) ?? null;

  const counts = useMemo(
    () => ({
      all: list.length,
      outbound: list.filter((integration) => matchesView(integration, 'outbound')).length,
      inbound: list.filter((integration) => matchesView(integration, 'inbound')).length,
      failing: list.filter(isFailing).length,
      enabled: list.filter((integration) => integration.enabled).length,
    }),
    [list],
  );
  const lastDelivery = useMemo(
    () =>
      list
        .map((integration) => integration.lastDeliveryAt)
        .filter((value): value is string => Boolean(value))
        .sort()
        .at(-1) ?? null,
    [list],
  );
  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return list
      .filter((integration) => matchesView(integration, view))
      .filter((integration) => !needle || [integration.name, integrationTypeLabel(integration.type), integrationTarget(integration)].some((value) => value?.toLowerCase().includes(needle)));
  }, [list, view, query]);

  const loading = integrations.isLoading;

  return (
    <>
      <PageHeader
        title="Integrations"
        subtitle="Connect enterprise tools and data sources"
        actions={
          admin ? (
            <button type="button" className="gold-btn" onClick={() => setEditing('new')}>
              <Plus size={16} /> New Integration
            </button>
          ) : (
            <span className="chip">
              <Lock size={11} /> Read-only access
            </span>
          )
        }
      />
      <div className="page-stack">
        <Tabs<View>
          label="Integration views"
          value={view}
          onChange={setView}
          tabs={[
            { id: 'all', label: 'All', count: integrations.data ? counts.all : null },
            { id: 'outbound', label: 'Outbound', count: integrations.data ? counts.outbound : null },
            { id: 'inbound', label: 'Inbound', count: integrations.data ? counts.inbound : null },
            { id: 'failing', label: 'Needs attention', count: integrations.data ? counts.failing : null },
          ]}
        />
        <div className="kpi-grid">
          <KpiCard icon={<Plug />} label="Connected systems" value={formatNumber(counts.all)} loading={loading} caption={`${formatNumber(counts.inbound)} inbound · ${formatNumber(counts.outbound)} outbound`} />
          <KpiCard icon={<CircleCheck />} label="Enabled" tone="success" value={formatNumber(counts.enabled)} loading={loading} caption="Receiving or sending events" />
          <KpiCard
            icon={<AlertTriangle />}
            label="Needs attention"
            tone={counts.failing ? 'critical' : 'neutral'}
            value={formatNumber(counts.failing)}
            loading={loading}
            to="/integrations?tab=failing"
            caption="Last delivery failed or failures recorded"
          />
          <KpiCard
            icon={<Clock3 />}
            label="Last delivery"
            tone="neutral"
            value={lastDelivery ? formatRelative(lastDelivery) : 'Never'}
            loading={loading}
            caption={lastDelivery ? formatDateTime(lastDelivery) : 'No deliveries recorded'}
          />
        </div>
        <Panel
          title="Connected systems"
          subtitle={admin ? 'Outbound webhooks and chat channels, inbound monitoring alerts. Open a card to test, rotate or review deliveries.' : 'Read-only — configuration requires an IT Admin or System Admin'}
        >
          <div className="toolbar">
            <SearchInput value={query} onChange={setQuery} placeholder="Search integrations…" label="Search integrations" />
          </div>
          {loading && <LoadingState variant="cards" rows={3} label="Loading integrations…" />}
          {integrations.isError && <ErrorState error={integrations.error} onRetry={() => void integrations.refetch()} />}
          {integrations.data && visible.length === 0 && (
            <EmptyState
              title={list.length === 0 ? 'No integrations configured' : 'No integrations in this view'}
              description={list.length === 0 ? 'Connect a webhook, chat channel or monitoring tool to automate notifications and incident intake.' : undefined}
              icon={<Plug size={20} />}
              action={
                admin && list.length === 0 ? (
                  <button type="button" className="gold-btn compact" onClick={() => setEditing('new')}>
                    <Plus size={13} /> New integration
                  </button>
                ) : undefined
              }
            />
          )}
          {visible.length > 0 && (
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {visible.map((integration) => (
                <IntegrationCard key={integration.id} integration={integration} onOpen={() => setSelectedId(integration.id)} />
              ))}
            </div>
          )}
        </Panel>
      </div>
      {selected && (
        <IntegrationDrawer
          integration={selected}
          admin={admin}
          onClose={() => setSelectedId(null)}
          onEdit={() => {
            setSelectedId(null);
            setEditing(selected);
          }}
          onToken={(integration, value) => setToken({ integration, token: value })}
        />
      )}
      {editing && (
        <IntegrationEditor
          integration={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onToken={(integration, value) => setToken({ integration, token: value })}
        />
      )}
      {token && <TokenPanel integration={token.integration} token={token.token} onClose={() => setToken(null)} />}
    </>
  );
}
