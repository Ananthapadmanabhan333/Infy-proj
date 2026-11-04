import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { CalendarClock, ExternalLink, Eye, EyeOff, Globe2, Lock, Mail, Megaphone, Pencil, Plus, RadioTower, Trash2, Users } from 'lucide-react';
import { useDeleteStatusComponent, useMaintenanceStatus, useSaveStatusSettings, useStatusPageView } from '../../api/statusPageAdmin';
import { Badge, DemoBadge, StatusBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Toggle } from '../../components/Controls';
import { DataTable } from '../../components/DataTable';
import { TextareaField, TextField } from '../../components/FormField';
import { KpiCard } from '../../components/KpiCard';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { errorMessage } from '../../lib/api';
import { STATUS_PAGE_EDITORS } from '../../lib/constants';
import { formatDate, formatDateTime, formatNumber, formatPercent, formatRelative } from '../../lib/format';
import type { MaintenanceWindowStatus, ServiceStatus, StatusComponentDto, StatusMaintenanceDto, StatusPageView, StatusUpdateDto, StatusUptimeDay } from '../../lib/types';
import { ComponentDialog, MaintenanceDialog, UPDATE_STATUS_LABELS, UpdateDialog } from './StatusDialogs';
import '../../components/admin/admin.css';

type StatusTab = 'components' | 'maintenance' | 'updates' | 'settings';
const TAB_IDS: StatusTab[] = ['components', 'maintenance', 'updates', 'settings'];

const OVERALL_LABEL: Record<ServiceStatus, string> = {
  OPERATIONAL: 'All systems operational',
  DEGRADED: 'Degraded performance',
  OUTAGE: 'Service outage',
  MAINTENANCE: 'Maintenance in progress',
};

/** Allowed maintenance transitions (API): SCHEDULED → IN_PROGRESS | COMPLETED | CANCELLED; IN_PROGRESS → COMPLETED | CANCELLED. */
export function maintenanceTransitions(stored: MaintenanceWindowStatus): MaintenanceWindowStatus[] {
  if (stored === 'SCHEDULED') return ['IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
  if (stored === 'IN_PROGRESS') return ['COMPLETED', 'CANCELLED'];
  return [];
}

const TRANSITION_TEXT: Record<MaintenanceWindowStatus, string> = { SCHEDULED: 'Schedule', IN_PROGRESS: 'Start now', COMPLETED: 'Complete', CANCELLED: 'Cancel' };

function dayTone(day: StatusUptimeDay): string {
  if (day.maintenance) return 'bg-gold';
  if (day.availability === null || day.availability === undefined) return 'bg-line';
  if (day.availability >= 99.9) return 'bg-success';
  if (day.availability >= 99) return 'bg-warning';
  return 'bg-critical';
}

function UptimeBars({ history, label }: { history: StatusUptimeDay[]; label: string }) {
  if (!history.length) return <span className="muted-text">No history</span>;
  return (
    <span className="flex h-4 w-full max-w-[220px] items-stretch gap-px" role="img" aria-label={label}>
      {history.map((day) => (
        <span key={day.date} className={`min-w-px flex-1 rounded-[1px] ${dayTone(day)}`} title={`${formatDate(day.date)} · ${day.maintenance ? 'maintenance' : day.availability === null ? 'no data' : `${day.availability.toFixed(2)}%`}`} />
      ))}
    </span>
  );
}

function SettingsForm({ view }: { view: StatusPageView }) {
  const save = useSaveStatusSettings();
  const toast = useToast();
  const [title, setTitle] = useState(view.settings.title);
  const [description, setDescription] = useState(view.settings.description ?? '');
  const [confirming, setConfirming] = useState<boolean | null>(null);
  useEffect(() => {
    setTitle(view.settings.title);
    setDescription(view.settings.description ?? '');
  }, [view.settings.title, view.settings.description]);

  const titleError = title.trim().length < 1 ? 'Title is required' : title.trim().length > 120 ? 'At most 120 characters' : undefined;
  const descriptionError = description.length > 1000 ? 'At most 1000 characters' : undefined;
  const dirty = title !== view.settings.title || description !== (view.settings.description ?? '');

  const submit = (publicEnabled: boolean, message: string) =>
    save.mutate(
      { title: title.trim(), description: description.trim() || null, publicEnabled },
      {
        onSuccess: () => {
          toast.success(message);
          setConfirming(null);
        },
        onError: (error) => {
          if (confirming === null) toast.error(error);
        },
      },
    );

  return (
    <Panel title="Page settings" subtitle={view.settings.updatedAt ? `Last changed ${formatRelative(view.settings.updatedAt)}` : undefined}>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (!titleError && !descriptionError) submit(view.settings.publicEnabled, 'Status page settings saved');
        }}
        noValidate
        aria-label="Status page settings"
      >
        <TextField label="Title" required value={title} onChange={(event) => setTitle(event.target.value)} error={titleError} />
        <TextareaField label="Description" value={description} onChange={(event) => setDescription(event.target.value)} error={descriptionError} />
        <div className="setting-row">
          <div>
            <b>Publish the public status page</b>
            <small>
              {view.settings.publicEnabled
                ? 'Anyone with the link can see public components, maintenance and public updates at /status.'
                : 'The public /status page answers "not published" until you turn this on.'}
            </small>
          </div>
          <Toggle checked={view.settings.publicEnabled} label="Publish the public status page" disabled={save.isPending || Boolean(titleError)} onChange={(next) => setConfirming(next)} />
        </div>
        <div className="mt-3 flex justify-end">
          <button type="submit" className="gold-btn compact" disabled={!dirty || save.isPending || Boolean(titleError || descriptionError)}>
            {save.isPending ? 'Saving…' : 'Save settings'}
          </button>
        </div>
      </form>
      <ConfirmDialog
        open={confirming !== null}
        onClose={() => {
          setConfirming(null);
          save.reset();
        }}
        title={confirming ? 'Publish the status page?' : 'Unpublish the status page?'}
        message={
          confirming
            ? 'The page becomes visible without sign-in at /status: public components with their status and 90-day uptime, maintenance on public components and public updates. No incident data or status reasons are shown.'
            : 'Visitors of /status will see a "not published" page. Subscribers keep their subscription but receive no e-mails while the page is unpublished.'
        }
        confirmLabel={confirming ? 'Publish' : 'Unpublish'}
        tone={confirming ? 'gold' : 'danger'}
        pending={save.isPending}
        error={save.isError && confirming !== null ? errorMessage(save.error) : null}
        onConfirm={() => confirming !== null && submit(confirming, confirming ? 'Status page published' : 'Status page unpublished')}
      />
    </Panel>
  );
}

/** /status-page (GOVERNANCE): internal status view for everyone; editors manage settings, components, maintenance and updates. */
export default function StatusPageAdminPage() {
  const { hasRole } = useAuth();
  const editor = hasRole(...STATUS_PAGE_EDITORS);
  const toast = useToast();
  const view = useStatusPageView();
  const allowed = editor ? TAB_IDS : TAB_IDS.filter((id) => id !== 'settings');
  const [tab, setTab] = useTabParam<StatusTab>(allowed, 'components');
  const [componentDialog, setComponentDialog] = useState<StatusComponentDto | 'new' | null>(null);
  const [deleting, setDeleting] = useState<StatusComponentDto | null>(null);
  const [maintenanceDialog, setMaintenanceDialog] = useState<StatusMaintenanceDto | 'new' | null>(null);
  const [transition, setTransition] = useState<{ window: StatusMaintenanceDto; status: MaintenanceWindowStatus } | null>(null);
  const [updateDialog, setUpdateDialog] = useState<StatusUpdateDto | 'new' | null>(null);
  const remove = useDeleteStatusComponent();
  const changeStatus = useMaintenanceStatus();
  const data = view.data;

  const groups = useMemo(() => {
    const byGroup = new Map<string, StatusComponentDto[]>();
    [...(data?.components ?? [])]
      .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.displayName.localeCompare(b.displayName))
      .forEach((component) => {
        const key = component.groupName ?? 'Other services';
        byGroup.set(key, [...(byGroup.get(key) ?? []), component]);
      });
    return [...byGroup.entries()];
  }, [data?.components]);

  const headerAction = editor ? (
    tab === 'maintenance' ? (
      <button type="button" className="gold-btn" onClick={() => setMaintenanceDialog('new')} disabled={!data?.components.length}>
        <CalendarClock size={16} aria-hidden="true" /> Schedule maintenance
      </button>
    ) : tab === 'components' ? (
      <button type="button" className="gold-btn" onClick={() => setComponentDialog('new')}>
        <Plus size={16} aria-hidden="true" /> Add component
      </button>
    ) : tab === 'updates' ? (
      <button type="button" className="gold-btn" onClick={() => setUpdateDialog('new')}>
        <Megaphone size={16} aria-hidden="true" /> Post update
      </button>
    ) : undefined
  ) : undefined;

  return (
    <>
      <PageHeader
        title="Status Page"
        subtitle="Service status and maintenance communicated to users"
        actions={
          <>
            <a href="/status" target="_blank" rel="noopener noreferrer" className="date-btn inline-flex items-center gap-1.5">
              <Eye size={14} aria-hidden="true" /> {editor ? 'Preview public page' : 'Public page'} <ExternalLink size={11} aria-hidden="true" />
            </a>
            {headerAction}
          </>
        }
      >
        {data && (
          <div className="tag-row mt-2">
            <StatusBadge status={data.overall} />
            {data.settings.publicEnabled ? (
              <Badge value="published" tone="success" label={<><Globe2 size={10} aria-hidden="true" /> Published</>} />
            ) : (
              <Badge value="unpublished" tone="muted" label={<><Lock size={10} aria-hidden="true" /> Not published</>} />
            )}
          </div>
        )}
      </PageHeader>
      <div className="page-stack">
        {view.isLoading && <LoadingState variant="kpis" label="Loading the status page…" />}
        {view.isError && !data && <ErrorState error={view.error} onRetry={() => void view.refetch()} />}
        {data && (
          <>
            <div className="kpi-grid">
              <KpiCard
                icon={<RadioTower />}
                label="Overall"
                value={OVERALL_LABEL[data.overall] ?? data.overall}
                tone={data.overall === 'OPERATIONAL' ? 'success' : data.overall === 'OUTAGE' ? 'critical' : 'warning'}
                caption={`Updated ${formatRelative(data.generatedAt)}`}
              />
              <KpiCard icon={<Globe2 />} label="Components" value={formatNumber(data.components.length)} caption={`${formatNumber(data.components.filter((c) => c.publicVisible).length)} public`} />
              <KpiCard
                icon={<CalendarClock />}
                label="Maintenance"
                value={formatNumber(data.maintenance.filter((m) => m.status === 'SCHEDULED' || m.status === 'IN_PROGRESS').length)}
                tone="gold"
                caption="Scheduled or in progress"
              />
              {data.subscribers ? (
                <KpiCard
                  icon={<Users />}
                  label="Subscribers"
                  value={formatNumber(data.subscribers.confirmed)}
                  tone="neutral"
                  caption={data.emailConfigured ? `${formatNumber(data.subscribers.pending)} awaiting confirmation` : 'E-mail is not configured — no updates are sent'}
                />
              ) : (
                <KpiCard icon={<Megaphone />} label="Updates (30 days)" value={formatNumber(data.updates.length)} tone="neutral" />
              )}
            </div>
            {editor && !data.emailConfigured && (
              <div className="callout" role="note">
                <Mail size={16} aria-hidden="true" />
                <div>
                  <b>E-mail is not configured.</b> Subscriptions are not offered and subscribers are not notified until SMTP is set up (see Settings → E-mail).
                </div>
              </div>
            )}

            <Tabs<StatusTab>
              label="Status page sections"
              value={tab}
              onChange={setTab}
              tabs={[
                { id: 'components', label: 'Components', count: data.components.length },
                { id: 'maintenance', label: 'Maintenance', count: data.maintenance.length },
                { id: 'updates', label: 'Incident updates', count: data.updates.length },
                { id: 'settings', label: 'Settings', hidden: !editor },
              ]}
            />

            {tab === 'components' &&
              (data.components.length === 0 ? (
                <Panel>
                  <EmptyState title="No components yet" description={editor ? 'Add business services to the status page.' : 'No services are shown on the status page yet.'} icon={<RadioTower size={20} />} />
                </Panel>
              ) : (
                groups.map(([group, components]) => (
                  <Panel key={group} title={group}>
                    <DataTable<StatusComponentDto>
                      caption={`${group} components`}
                      rows={components}
                      rowKey={(component) => component.id}
                      columns={[
                        {
                          key: 'name',
                          header: 'Component',
                          className: 'summary-cell',
                          render: (component) => (
                            <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                              <span className="font-semibold">{component.displayName}</span>
                              <Link to={`/services/${component.serviceId}`} className="text-muted">
                                {component.serviceCode}
                              </Link>
                              {component.publicVisible ? (
                                <span className="chip" title="Shown on the public page">
                                  <Eye size={10} aria-hidden="true" /> Public
                                </span>
                              ) : (
                                <span className="chip" title="Internal only">
                                  <EyeOff size={10} aria-hidden="true" /> Internal
                                </span>
                              )}
                              <DemoBadge demo={component.demo} />
                            </span>
                          ),
                        },
                        {
                          key: 'status',
                          header: 'Status',
                          render: (component) => (
                            <span title={component.statusReason ?? undefined}>
                              <StatusBadge status={component.status} />
                            </span>
                          ),
                        },
                        { key: 'uptime', header: 'Uptime 90 d', render: (component) => (component.uptime90d === null ? '—' : formatPercent(component.uptime90d, 2)) },
                        { key: 'history', header: 'Last 90 days', render: (component) => <UptimeBars history={component.history} label={`${component.displayName} daily availability, last 90 days`} /> },
                        ...(editor
                          ? [
                              {
                                key: 'actions',
                                header: <span className="sr-only">Actions</span>,
                                render: (component: StatusComponentDto) => (
                                  <span className="flex gap-1">
                                    <button type="button" className="icon-btn sm" aria-label={`Edit ${component.displayName}`} onClick={() => setComponentDialog(component)}>
                                      <Pencil size={13} />
                                    </button>
                                    <button type="button" className="icon-btn sm" aria-label={`Delete ${component.displayName}`} onClick={() => setDeleting(component)}>
                                      <Trash2 size={13} />
                                    </button>
                                  </span>
                                ),
                              },
                            ]
                          : []),
                      ]}
                    />
                  </Panel>
                ))
              ))}

            {tab === 'maintenance' && (
              <Panel title="Maintenance windows" subtitle="Ending within the last 7 days or later">
                {data.maintenance.length === 0 ? (
                  <EmptyState title="No maintenance windows" icon={<CalendarClock size={20} />} />
                ) : (
                  <ul className="m-0 list-none p-0" aria-label="Maintenance windows">
                    {data.maintenance.map((window) => {
                      const next = editor ? maintenanceTransitions(window.storedStatus) : [];
                      const editable = editor && (window.storedStatus === 'SCHEDULED' || window.storedStatus === 'IN_PROGRESS');
                      return (
                        <li key={window.id} className="list-row flex-wrap">
                          <div>
                            <b className="flex flex-wrap items-center gap-1.5">
                              {window.title} <StatusBadge status={window.status} />
                              <DemoBadge demo={window.demo} />
                            </b>
                            <small>
                              {formatDateTime(window.startsAt)} – {formatDateTime(window.endsAt)} · {window.components.map((component) => component.displayName).join(', ') || 'No components'}
                            </small>
                            {window.description && <small>{window.description}</small>}
                          </div>
                          {(editable || next.length > 0) && (
                            <span className="flex flex-wrap gap-1">
                              {editable && (
                                <button type="button" className="subtle-btn compact" onClick={() => setMaintenanceDialog(window)} aria-label={`Edit ${window.title}`}>
                                  <Pencil size={12} aria-hidden="true" /> Edit
                                </button>
                              )}
                              {next.map((status) => (
                                <button
                                  key={status}
                                  type="button"
                                  className={status === 'CANCELLED' ? 'danger-btn compact' : 'subtle-btn compact'}
                                  onClick={() => {
                                    changeStatus.reset();
                                    setTransition({ window, status });
                                  }}
                                >
                                  {TRANSITION_TEXT[status]}
                                </button>
                              ))}
                            </span>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                )}
              </Panel>
            )}

            {tab === 'updates' && (
              <Panel title="Incident updates" subtitle="Last 30 days, newest first">
                {data.updates.length === 0 ? (
                  <EmptyState title="No updates posted" icon={<Megaphone size={20} />} />
                ) : (
                  <ul className="m-0 list-none p-0" aria-label="Status updates">
                    {data.updates.map((update) => (
                      <li key={update.id} className="list-row items-start">
                        <div>
                          <b className="flex flex-wrap items-center gap-1.5">
                            {update.title}
                            <Badge value={update.status} label={UPDATE_STATUS_LABELS[update.status] ?? update.status} />
                            {update.publicVisible ? (
                              <span className="chip">
                                <Eye size={10} aria-hidden="true" /> Public
                              </span>
                            ) : (
                              <span className="chip">
                                <EyeOff size={10} aria-hidden="true" /> Internal
                              </span>
                            )}
                            <DemoBadge demo={update.demo} />
                          </b>
                          <small className="whitespace-pre-wrap">{update.message}</small>
                          <small>
                            {formatDateTime(update.postedAt)}
                            {update.incidentId && (
                              <>
                                {' · '}
                                <Link to={`/incidents/${update.incidentId}`}>{update.incidentNumber ?? 'Linked incident'}</Link>
                              </>
                            )}
                          </small>
                        </div>
                        {editor && (
                          <button type="button" className="icon-btn sm" aria-label={`Edit update ${update.title}`} onClick={() => setUpdateDialog(update)}>
                            <Pencil size={13} />
                          </button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </Panel>
            )}

            {tab === 'settings' && editor && <SettingsForm view={data} />}
          </>
        )}
      </div>

      {data && componentDialog !== null && <ComponentDialog component={componentDialog === 'new' ? null : componentDialog} existing={data.components} onClose={() => setComponentDialog(null)} />}
      {data && maintenanceDialog !== null && (
        <MaintenanceDialog maintenance={maintenanceDialog === 'new' ? null : maintenanceDialog} components={data.components} onClose={() => setMaintenanceDialog(null)} />
      )}
      {data && updateDialog !== null && <UpdateDialog update={updateDialog === 'new' ? null : updateDialog} publicEnabled={data.settings.publicEnabled} onClose={() => setUpdateDialog(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => {
          setDeleting(null);
          remove.reset();
        }}
        title={`Remove ${deleting?.displayName ?? 'component'}?`}
        message="The component disappears from the status page. The business service itself is not changed."
        confirmLabel="Remove"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              toast.success(`${deleting.displayName} removed`);
              setDeleting(null);
            },
          })
        }
      />
      <ConfirmDialog
        open={transition !== null}
        onClose={() => setTransition(null)}
        title={transition ? `${TRANSITION_TEXT[transition.status]} “${transition.window.title}”?` : ''}
        message={
          transition?.status === 'COMPLETED'
            ? 'Completing early sets the end to now; the services leave maintenance.'
            : transition?.status === 'CANCELLED'
              ? 'The window is cancelled and no longer shown as planned.'
              : 'The affected services show MAINTENANCE everywhere until the window ends.'
        }
        confirmLabel={transition ? TRANSITION_TEXT[transition.status] : 'Confirm'}
        tone={transition?.status === 'CANCELLED' ? 'danger' : 'gold'}
        pending={changeStatus.isPending}
        error={changeStatus.isError ? errorMessage(changeStatus.error) : null}
        onConfirm={() =>
          transition &&
          changeStatus.mutate(
            { id: transition.window.id, status: transition.status },
            {
              onSuccess: () => {
                toast.success('Maintenance status changed');
                setTransition(null);
              },
            },
          )
        }
      />
    </>
  );
}
