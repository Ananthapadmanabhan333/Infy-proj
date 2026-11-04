import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Link2, Pencil, Share2, Trash2, Unlink, Waypoints } from 'lucide-react';
import { useAddRelationship, useCi, useCiHistory, useCiImpact, useCmdbGraph, useCmdbMeta, useDeleteCi, useRemoveRelationship } from '../../api/cmdb';
import { UserChip } from '../../components/Avatar';
import { DemoBadge, PriorityBadge, StatusBadge } from '../../components/Badge';
import { CiSinglePicker } from '../../components/cmdb/CiPicker';
import { DependencyGraph } from '../../components/cmdb/DependencyGraph';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { SelectField, TextField } from '../../components/FormField';
import { Fact, TextBlock, ToneBadge } from '../../components/itsm/ItsmParts';
import { AlertStatusBadge, changeDetail, CiStatusBadge, CiTypeBadge, CriticalityBadge, EventTimeline, SeverityBadge } from '../../components/ops/OpsParts';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { CmdbImpactPanel } from '../../components/ai/OpsAiPanels';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { CMDB_EDITORS, OBSERVABILITY_READERS } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import { CHANGE_STATUS_LABELS, labelOf, PROBLEM_STATUS_LABELS } from '../../lib/itsm';
import { ASSET_STATUS_LABELS, CI_ENVIRONMENT_LABELS, CI_TYPE_LABELS, RELATIONSHIP_LABELS, RELATIONSHIP_TYPES } from '../../lib/ops';
import type { CiDetail, CiRef, ImpactDirection, ImpactNode, RelationshipDto, RelationshipType } from '../../lib/opsTypes';
import { CiEditor } from './CiEditor';

function RelationshipRow({ relationship, canRemove, onRemove }: { relationship: RelationshipDto; canRemove: boolean; onRemove: () => void }) {
  return (
    <li className="list-row flex-wrap sm:flex-nowrap">
      <div className="min-w-0">
        <b className="flex flex-wrap items-center gap-1.5">
          <span className="font-normal text-muted">{relationship.label}</span>
          <Link to={`/cmdb/${relationship.ci.id}`}>{relationship.ci.name}</Link>
        </b>
        <small className="flex flex-wrap items-center gap-1.5">
          {CI_TYPE_LABELS[relationship.ci.ciType]}
          {relationship.ci.environment ? ` · ${CI_ENVIRONMENT_LABELS[relationship.ci.environment]}` : ''}
          {relationship.ci.status !== 'ACTIVE' ? ` · ${titleCase(relationship.ci.status)}` : ''}
          {relationship.origin === 'SERVICE_MAP' && <ToneBadge value="service-map" label="Service map" tone="champagne" />}
          {relationship.description && <span className="break-words">· {relationship.description}</span>}
        </small>
      </div>
      {canRemove && relationship.origin === 'MANUAL' && (
        <button type="button" className="icon-btn sm shrink-0" aria-label={`Remove relationship ${relationship.label} ${relationship.ci.name}`} onClick={onRemove}>
          <Unlink size={13} />
        </button>
      )}
    </li>
  );
}

function RelationshipsPanel({ ci, canEdit }: { ci: CiDetail; canEdit: boolean }) {
  const meta = useCmdbMeta();
  const add = useAddRelationship();
  const remove = useRemoveRelationship();
  const toast = useToast();
  const [adding, setAdding] = useState(false);
  const [direction, setDirection] = useState<'OUTGOING' | 'INCOMING'>('OUTGOING');
  const [type, setType] = useState<RelationshipType>('DEPENDS_ON');
  const [other, setOther] = useState<CiRef | null>(null);
  const [description, setDescription] = useState('');
  const [removing, setRemoving] = useState<RelationshipDto | null>(null);
  const specs = meta.data?.relationshipTypes ?? RELATIONSHIP_TYPES.map((value) => ({ type: value, outgoingLabel: RELATIONSHIP_LABELS[value].outgoing, incomingLabel: RELATIONSHIP_LABELS[value].incoming, sourceDependsOnTarget: true }));
  const errors = add.isError ? toApiError(add.error).fieldErrors ?? [] : [];
  const all = [...ci.outgoing, ...ci.incoming];

  const submit = () => {
    if (!other) return;
    const body = direction === 'OUTGOING' ? { sourceId: ci.id, targetId: other.id } : { sourceId: other.id, targetId: ci.id };
    add.mutate(
      { ...body, type, description: description.trim() || null },
      {
        onSuccess: () => {
          toast.success('Relationship added');
          setAdding(false);
          setOther(null);
          setDescription('');
        },
      },
    );
  };

  return (
    <Panel
      title={`Relationships (${all.length})`}
      actions={
        canEdit && !adding ? (
          <button type="button" className="subtle-btn compact" onClick={() => setAdding(true)}>
            <Link2 size={13} aria-hidden="true" /> Add relationship
          </button>
        ) : undefined
      }
    >
      {all.length === 0 && !adding && <p className="m-0 text-[11px] text-muted">No relationships yet.</p>}
      {all.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Relationships">
          {all.map((relationship) => (
            <RelationshipRow key={`${relationship.direction}-${relationship.id}`} relationship={relationship} canRemove={canEdit} onRemove={() => setRemoving(relationship)} />
          ))}
        </ul>
      )}
      {adding && (
        <div className="action-card mt-2" role="group" aria-label="New relationship">
          <div className="form-row">
            <SelectField label="Direction" value={direction} onChange={(event) => setDirection(event.target.value as 'OUTGOING' | 'INCOMING')}>
              <option value="OUTGOING">{ci.name} → other CI</option>
              <option value="INCOMING">Other CI → {ci.name}</option>
            </SelectField>
            <SelectField label="Relationship" value={type} onChange={(event) => setType(event.target.value as RelationshipType)} error={errors.find((error) => error.field === 'type')?.message}>
              {specs.map((spec) => (
                <option key={spec.type} value={spec.type}>
                  {direction === 'OUTGOING' ? `${ci.name} ${spec.outgoingLabel} …` : `… ${spec.outgoingLabel} ${ci.name}`}
                </option>
              ))}
            </SelectField>
          </div>
          <CiSinglePicker
            label="Other configuration item"
            value={other}
            onChange={setOther}
            excludeIds={[ci.id]}
            error={errors.find((error) => error.field === 'sourceId' || error.field === 'targetId')?.message}
          />
          <TextField label="Description (optional)" maxLength={300} value={description} onChange={(event) => setDescription(event.target.value)} />
          {add.isError && !errors.length && (
            <p className="field-error" role="alert">
              {errorMessage(add.error)}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" className="subtle-btn compact" onClick={() => setAdding(false)} disabled={add.isPending}>
              Cancel
            </button>
            <button type="button" className="gold-btn compact" onClick={submit} disabled={!other || add.isPending}>
              {add.isPending ? 'Adding…' : 'Add relationship'}
            </button>
          </div>
        </div>
      )}
      <ConfirmDialog
        open={removing !== null}
        onClose={() => {
          setRemoving(null);
          remove.reset();
        }}
        title="Remove relationship?"
        message={removing ? `${ci.name} ${removing.label} ${removing.ci.name}` : ''}
        confirmLabel="Remove"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          removing &&
          remove.mutate(removing.id, {
            onSuccess: () => {
              toast.success('Relationship removed');
              setRemoving(null);
            },
          })
        }
      />
    </Panel>
  );
}

function ImpactList({ title, nodes, empty }: { title: string; nodes: ImpactNode[]; empty: string }) {
  return (
    <div className="min-w-0">
      <h4 className="m-0 mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">
        {title} ({nodes.length})
      </h4>
      {nodes.length === 0 ? (
        <p className="m-0 text-[11px] text-muted">{empty}</p>
      ) : (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label={title}>
          {nodes.map((node) => (
            <li key={node.ci.id} className="flex flex-wrap items-center justify-between gap-1 text-[11px]">
              <Link to={`/cmdb/${node.ci.id}`} className="min-w-0 break-words">
                {node.ci.name}
              </Link>
              <span className="text-muted">
                {CI_TYPE_LABELS[node.ci.ciType]} · {node.depth} hop{node.depth === 1 ? '' : 's'}
                {node.via ? ` · ${titleCase(node.via)}` : ''}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ImpactPanel({ ci }: { ci: CiDetail }) {
  const [direction, setDirection] = useState<ImpactDirection>('both');
  const [depth, setDepth] = useState(5);
  const impact = useCiImpact(ci.id, direction, depth);
  const data = impact.data;
  return (
    <Panel title="Impact analysis" subtitle="Computed from the CMDB relationships (breadth-first, cycle-safe)">
      <div className="form-row">
        <SelectField label="Direction" value={direction} onChange={(event) => setDirection(event.target.value as ImpactDirection)}>
          <option value="both">Both directions</option>
          <option value="downstream">Downstream — what fails with it</option>
          <option value="upstream">Upstream — what it needs</option>
        </SelectField>
        <SelectField label="Depth" value={String(depth)} onChange={(event) => setDepth(Number(event.target.value))}>
          {[1, 2, 3, 4, 5, 6, 8, 10].map((value) => (
            <option key={value} value={value}>
              {value} hop{value === 1 ? '' : 's'}
            </option>
          ))}
        </SelectField>
      </div>
      {impact.isLoading && <LoadingState variant="inline" label="Analysing impact…" />}
      {impact.isError && <ErrorState error={impact.error} onRetry={() => void impact.refetch()} compact />}
      {data && (
        <div className="grid gap-3">
          {data.truncated && <p className="m-0 text-[11px] warning-text">The traversal hit its node limit — the lists are incomplete.</p>}
          <div>
            <h4 className="m-0 mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">Impacted business services ({data.impactedServices.length})</h4>
            {data.impactedServices.length === 0 ? (
              <p className="m-0 text-[11px] text-muted">{direction === 'upstream' ? 'Not computed for the upstream direction.' : 'No business service depends on this CI.'}</p>
            ) : (
              <ul className="m-0 grid list-none gap-1 p-0" aria-label="Impacted business services">
                {data.impactedServices.map((service) => (
                  <li key={service.ciId} className="flex flex-wrap items-center justify-between gap-1 text-[11px]">
                    <Link to={`/services/${service.serviceId}`}>
                      {service.name} <span className="id-cell">{service.code}</span>
                    </Link>
                    <span className="flex items-center gap-1">
                      <span className="text-muted">tier {service.tier}</span>
                      <ToneBadge value={service.status} tone={service.status === 'OUTAGE' ? 'critical' : service.status === 'DEGRADED' ? 'warning' : 'success'} />
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div>
            <h4 className="m-0 mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">Open incidents ({data.openIncidents.length})</h4>
            {data.openIncidents.length === 0 ? (
              <p className="m-0 text-[11px] text-muted">No active incident on the impacted services.</p>
            ) : (
              <ul className="m-0 grid list-none gap-1 p-0" aria-label="Open incidents">
                {data.openIncidents.map((incident) => (
                  <li key={incident.id} className="flex flex-wrap items-center justify-between gap-1 text-[11px]">
                    <span className="min-w-0 break-words">
                      <Link to={`/incidents/${incident.id}`} className="id-cell">
                        {incident.number}
                      </Link>{' '}
                      {incident.title}
                    </span>
                    <span className="flex gap-1">
                      <PriorityBadge priority={incident.priority} />
                      <StatusBadge status={incident.status} />
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
          {data.openAlerts.length > 0 && (
            <div>
              <h4 className="m-0 mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted">Open alerts ({data.openAlerts.length})</h4>
              <ul className="m-0 grid list-none gap-1 p-0" aria-label="Open alerts on impacted CIs">
                {data.openAlerts.map((alert) => (
                  <li key={alert.id} className="flex flex-wrap items-center justify-between gap-1 text-[11px]">
                    <span className="min-w-0 break-words">
                      <Link to={`/alerts/${alert.id}`} className="id-cell">
                        {alert.number}
                      </Link>{' '}
                      {alert.title}
                      {alert.ciName ? <span className="text-muted"> · {alert.ciName}</span> : null}
                    </span>
                    <SeverityBadge severity={alert.severity} />
                  </li>
                ))}
              </ul>
            </div>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            {direction !== 'upstream' && <ImpactList title="Downstream" nodes={data.downstream} empty="Nothing depends on this CI." />}
            {direction !== 'downstream' && <ImpactList title="Upstream" nodes={data.upstream} empty="This CI needs nothing else." />}
          </div>
        </div>
      )}
    </Panel>
  );
}

function NeighbourhoodPanel({ ci }: { ci: CiDetail }) {
  const navigate = useNavigate();
  const [depth, setDepth] = useState(2);
  const graph = useCmdbGraph({ ciId: ci.id, depth, includeRetired: true });
  return (
    <Panel
      title="Dependency map"
      subtitle="This CI's neighbourhood in both directions"
      actions={
        <select className="control sm w-auto" aria-label="Map depth" value={depth} onChange={(event) => setDepth(Number(event.target.value))}>
          {[1, 2, 3, 4].map((value) => (
            <option key={value} value={value}>
              {value} hop{value === 1 ? '' : 's'}
            </option>
          ))}
        </select>
      }
    >
      {graph.isLoading && <LoadingState variant="panel" label="Loading the map…" />}
      {graph.isError && <ErrorState error={graph.error} onRetry={() => void graph.refetch()} compact />}
      {graph.data && graph.data.nodes.length <= 1 && <EmptyState title="No related CIs" description="Add relationships to see this CI on the map." icon={<Share2 size={18} />} />}
      {graph.data && graph.data.nodes.length > 1 && (
        <>
          {graph.data.truncated && <p className="m-0 mb-2 text-[11px] warning-text">The neighbourhood was cut at the node limit.</p>}
          <DependencyGraph graph={graph.data} height={420} onOpen={(id) => (id === ci.id ? undefined : navigate(`/cmdb/${id}`))} />
        </>
      )}
    </Panel>
  );
}

function WorkList({ title, items, to, label, empty }: { title: string; items: { id: number; number: string; title: string; status: string }[]; to: (id: number) => string; label: (status: string) => string; empty: string }) {
  return (
    <Panel title={`${title} (${items.length})`}>
      {items.length === 0 ? (
        <p className="m-0 text-[11px] text-muted">{empty}</p>
      ) : (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label={title}>
          {items.map((item) => (
            <li key={item.id} className="list-row">
              <div className="min-w-0">
                <b className="flex flex-wrap items-center gap-1.5">
                  <Link to={to(item.id)} className="id-cell">
                    {item.number}
                  </Link>
                  <span className="break-words">{item.title}</span>
                </b>
                <small>{label(item.status)}</small>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** /cmdb/:id — CI facts, attributes, relationships, neighbourhood map, impact analysis, linked assets/problems/changes/alerts, history. */
export default function CiDetailPage() {
  const params = useParams();
  const navigate = useNavigate();
  const id = Number(params.id);
  const valid = Number.isInteger(id) && id > 0;
  const { hasRole } = useAuth();
  const canEdit = hasRole(...CMDB_EDITORS);
  const detail = useCi(valid ? id : null);
  const history = useCiHistory(valid ? id : null);
  const remove = useDeleteCi();
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const ci = detail.data;
  const crumbs = [{ label: 'CMDB', to: '/cmdb' }];

  if (!valid || (detail.isError && toApiError(detail.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[...crumbs, { label: 'Not found' }]} title="Configuration item not found" />
        <EmptyState title="This configuration item does not exist" description="It may have been deleted, or the link is wrong." action={<Link to="/cmdb" className="subtle-btn compact">Back to the CMDB</Link>} />
      </>
    );
  }
  if (detail.isLoading) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Loading configuration item…" />
        <LoadingState variant="panel" />
      </>
    );
  }
  if (!ci) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Configuration item" />
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
      </>
    );
  }

  const serviceCi = Boolean(ci.serviceId);
  const attributes = Object.entries(ci.attributes ?? {});

  return (
    <>
      <PageHeader
        crumbs={[...crumbs, { label: ci.name }]}
        title={ci.name}
        actions={
          canEdit ? (
            <>
              <button type="button" className="subtle-btn" onClick={() => setEditing(true)}>
                <Pencil size={14} aria-hidden="true" /> Edit
              </button>
              {!serviceCi && (
                <button type="button" className="danger-btn" onClick={() => setDeleting(true)}>
                  <Trash2 size={14} aria-hidden="true" /> Delete
                </button>
              )}
            </>
          ) : undefined
        }
      >
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
          <CiTypeBadge type={ci.ciType} />
          <CiStatusBadge status={ci.status} />
          {ci.environment && <ToneBadge value={ci.environment} label={CI_ENVIRONMENT_LABELS[ci.environment]} tone="neutral" />}
          <CriticalityBadge criticality={ci.criticality} />
          {ci.openAlertCount > 0 && <ToneBadge value="alerts" label={`${formatNumber(ci.openAlertCount)} open alert${ci.openAlertCount === 1 ? '' : 's'}`} tone="critical" />}
          <DemoBadge demo={ci.demo} />
          <span className="inline-flex items-center gap-1.5">
            Owner <UserChip name={ci.ownerName} emptyLabel="No owner" />
          </span>
          {ci.teamName && <span>· {ci.teamName}</span>}
          {hasRole(...OBSERVABILITY_READERS) && (
            <Link to={`/digital-twin?root=${ci.id}`} className="link-btn inline-flex items-center gap-1">
              <Waypoints size={12} aria-hidden="true" /> Open in Digital Twin
            </Link>
          )}
        </div>
      </PageHeader>
      <div className="page-stack">
        <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Overview">
              <TextBlock text={ci.description} empty="No description." />
              <div className="detail-grid mt-3">
                <Fact label="Business service">
                  {ci.serviceId ? (
                    <Link to={`/services/${ci.serviceId}`}>
                      {ci.serviceName ?? ci.serviceCode} {ci.serviceCode && <span className="id-cell">{ci.serviceCode}</span>}
                    </Link>
                  ) : (
                    '—'
                  )}
                </Fact>
                <Fact label="Team">{ci.teamName ?? '—'}</Fact>
                <Fact label="Created">{`${formatDateTime(ci.createdAt)}${ci.createdByName ? ` by ${ci.createdByName}` : ''}`}</Fact>
                <Fact label="Updated">{formatRelative(ci.updatedAt)}</Fact>
              </div>
              {serviceCi && <p className="mt-3 mb-0 text-[10px] text-muted">This CI mirrors its business service: rename or delete the service, and change its dependencies on the service.</p>}
            </Panel>
            <Panel title={`Attributes (${attributes.length})`}>
              {attributes.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No attributes recorded.</p>
              ) : (
                <dl className="detail-grid m-0" aria-label="Attributes">
                  {attributes.map(([key, value]) => (
                    <div key={key}>
                      <dt>
                        <small>{key}</small>
                      </dt>
                      <dd className="m-0">
                        <b className="break-words">{value === null ? '—' : typeof value === 'boolean' ? (value ? 'Yes' : 'No') : String(value)}</b>
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
            </Panel>
            <RelationshipsPanel ci={ci} canEdit={canEdit} />
            <NeighbourhoodPanel ci={ci} />
          </div>
          <div className="grid min-w-0 gap-[14px]">
            <ImpactPanel ci={ci} />
            <CmdbImpactPanel ciId={ci.id} />
            <Panel title={`Open alerts (${ci.openAlertCount})`}>
              {ci.openAlerts.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No open alerts on this CI.</p>
              ) : (
                <ul className="m-0 grid list-none gap-1 p-0" aria-label="Open alerts">
                  {ci.openAlerts.map((alert) => (
                    <li key={alert.id} className="list-row">
                      <div className="min-w-0">
                        <b className="flex flex-wrap items-center gap-1.5">
                          <Link to={`/alerts/${alert.id}`} className="id-cell">
                            {alert.number}
                          </Link>
                          <span className="break-words">{alert.title}</span>
                        </b>
                      </div>
                      <AlertStatusBadge status={alert.status} />
                    </li>
                  ))}
                </ul>
              )}
              {ci.openAlertCount > ci.openAlerts.length && (
                <Link to={`/alerts?ciId=${ci.id}`} className="link-btn mt-2 text-[11px]">
                  View all {formatNumber(ci.openAlertCount)} alerts
                </Link>
              )}
            </Panel>
            <Panel title={`Linked assets (${ci.assets.length})`}>
              {ci.assets.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No asset is linked to this CI.</p>
              ) : (
                <ul className="m-0 grid list-none gap-1 p-0" aria-label="Linked assets">
                  {ci.assets.map((asset) => (
                    <li key={asset.id} className="list-row">
                      <div className="min-w-0">
                        <b className="flex flex-wrap items-center gap-1.5">
                          <Link to={`/assets/${asset.id}`} className="id-cell">
                            {asset.assetTag}
                          </Link>
                          <span className="break-words">{asset.name}</span>
                        </b>
                        <small>
                          {titleCase(asset.assetType)} · {labelOf(ASSET_STATUS_LABELS, asset.status)}
                        </small>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
            <WorkList title="Problems" items={ci.problems} to={(problemId) => `/problems/${problemId}`} label={(status) => labelOf(PROBLEM_STATUS_LABELS, status)} empty="No problem names this CI." />
            <WorkList title="Changes" items={ci.changes} to={(changeId) => `/changes/${changeId}`} label={(status) => labelOf(CHANGE_STATUS_LABELS, status)} empty="No change names this CI." />
            <Panel title="History">
              <EventTimeline
                query={history}
                events={history.data?.map((row) => ({ id: row.id, at: row.at, actorName: row.actorName, title: titleCase(row.action), detail: changeDetail(row.fromValue, row.toValue), note: row.note }))}
              />
            </Panel>
          </div>
        </div>
      </div>
      {editing && <CiEditor ci={ci} onClose={() => setEditing(false)} />}
      <ConfirmDialog
        open={deleting}
        onClose={() => {
          setDeleting(false);
          remove.reset();
        }}
        title={`Delete ${ci.name}?`}
        message="Its relationships are deleted with it; linked assets, alerts, problems and changes are unlinked. This cannot be undone."
        confirmLabel="Delete CI"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() =>
          remove.mutate(ci.id, {
            onSuccess: () => {
              toast.success(`${ci.name} deleted`);
              navigate('/cmdb');
            },
          })
        }
      />
    </>
  );
}
