import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { CornerDownLeft, Pencil, UserPlus, Wrench } from 'lucide-react';
import { useAsset, useAssetAssignments, useAssetHistory, useAssetTransition, useAssignAsset, useRecordMaintenance, useReturnAsset } from '../../api/assets';
import { UserChip } from '../../components/Avatar';
import { DemoBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { Fact, TextBlock, ToneBadge, UserSearchPicker } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { AssetStatusBadge, changeDetail, EventTimeline, WarrantyBadge } from '../../components/ops/OpsParts';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { ASSET_EDITORS } from '../../lib/constants';
import { formatDate, formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import type { UserOption } from '../../lib/itsmTypes';
import { labelOf } from '../../lib/itsm';
import { ASSET_STATUS_LABELS, ASSET_TYPE_LABELS, assetTransitionLabel, END_USER_DEVICE_TYPES, formatMoney, MAINTENANCE_TYPES } from '../../lib/ops';
import type { AssetDetail, AssetStatus, MaintenanceType } from '../../lib/opsTypes';
import { AssetEditor } from './AssetEditor';

function TransitionDialog({ detail, target, onClose }: { detail: AssetDetail; target: AssetStatus; onClose: () => void }) {
  const transition = useAssetTransition(detail.asset.id);
  const toast = useToast();
  const [note, setNote] = useState('');
  const [assignee, setAssignee] = useState<UserOption | null>(null);
  const asset = detail.asset;
  const needsHolder = target === 'IN_USE' && END_USER_DEVICE_TYPES.includes(asset.assetType) && !asset.assignedToId;
  const needsNote = target === 'DISPOSED';
  const releases = (target === 'IN_STOCK' || target === 'RETIRED' || target === 'DISPOSED') && (asset.assignedToId || asset.seatsUsed > 0);
  const errors = transition.isError ? toApiError(transition.error).fieldErrors ?? [] : [];
  const blocked = transition.isPending || (needsHolder && !assignee) || (needsNote && note.trim().length < 3);

  const submit = () =>
    transition.mutate(
      { status: target, note: note.trim() || null, ...(needsHolder && assignee ? { assigneeId: assignee.id } : {}) },
      {
        onSuccess: (updated) => {
          toast.success(`${updated.asset.assetTag} is now ${ASSET_STATUS_LABELS[updated.asset.status].toLowerCase()}`);
          onClose();
        },
      },
    );

  return (
    <Modal
      open
      onClose={onClose}
      title={`${assetTransitionLabel(target)} — ${asset.assetTag}`}
      eyebrow="ASSET LIFECYCLE"
      dismissible={!transition.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={transition.isPending}>
            Cancel
          </button>
          <button type="button" className={target === 'DISPOSED' || target === 'RETIRED' ? 'danger-btn' : 'gold-btn'} onClick={submit} disabled={blocked}>
            {transition.isPending ? 'Saving…' : assetTransitionLabel(target)}
          </button>
        </>
      }
    >
      <p className="mt-0 text-[12px]">
        {ASSET_STATUS_LABELS[asset.status]} → <b>{ASSET_STATUS_LABELS[target]}</b>
      </p>
      {releases && (
        <div className="callout mb-3" role="status">
          <div>{asset.category === 'SOFTWARE' ? `All ${asset.seatsUsed} assigned seats are released.` : `${asset.assignedToName ?? 'The holder'} returns the device.`}</div>
        </div>
      )}
      {target === 'DISPOSED' && <p className="m-0 mb-2 text-[11px] warning-text">Disposed assets are final and can no longer be edited.</p>}
      {needsHolder && <UserSearchPicker label="Holder" value={assignee} onChange={setAssignee} error={errors.find((error) => error.field === 'assigneeId')?.message} hint="End-user devices need a holder to be in use." />}
      <TextareaField
        label={needsNote ? 'Disposal note (how and where)' : 'Note (optional)'}
        required={needsNote}
        rows={3}
        maxLength={1000}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        error={errors.find((error) => error.field === 'note')?.message}
      />
      {transition.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(transition.error)}
        </p>
      )}
    </Modal>
  );
}

function AssignDialog({ detail, onClose }: { detail: AssetDetail; onClose: () => void }) {
  const assign = useAssignAsset(detail.asset.id);
  const toast = useToast();
  const [user, setUser] = useState<UserOption | null>(null);
  const [note, setNote] = useState('');
  const asset = detail.asset;
  const licence = asset.category === 'SOFTWARE';
  const errors = assign.isError ? toApiError(assign.error).fieldErrors ?? [] : [];
  return (
    <Modal
      open
      onClose={onClose}
      title={licence ? `Assign a seat of ${asset.name}` : asset.assignedToId ? `Reassign ${asset.assetTag}` : `Assign ${asset.assetTag}`}
      eyebrow="ASSIGNMENT"
      dismissible={!assign.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={assign.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={!user || assign.isPending}
            onClick={() =>
              user &&
              assign.mutate(
                { userId: user.id, note: note.trim() || null },
                {
                  onSuccess: () => {
                    toast.success(`${licence ? 'Seat' : asset.assetTag} assigned to ${user.fullName}`);
                    onClose();
                  },
                },
              )
            }
          >
            {assign.isPending ? 'Assigning…' : 'Assign'}
          </button>
        </>
      }
    >
      {!licence && asset.assignedToName && <p className="mt-0 text-[11px] text-muted">{asset.assignedToName} returns it when you reassign it.</p>}
      {licence && (
        <p className="mt-0 text-[11px] text-muted">
          {formatNumber(asset.seatsUsed)} of {formatNumber(asset.licenseSeats ?? 0)} seats in use.
        </p>
      )}
      <UserSearchPicker
        label="Person"
        value={user}
        onChange={setUser}
        excludeIds={licence ? detail.seats.map((seat) => seat.userId) : asset.assignedToId ? [asset.assignedToId] : []}
        error={errors.find((error) => error.field === 'userId')?.message}
        hint="The person is notified (portal: My assets)."
      />
      <TextField label="Note (optional)" maxLength={500} value={note} onChange={(event) => setNote(event.target.value)} />
      {assign.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(assign.error)}
        </p>
      )}
    </Modal>
  );
}

function MaintenancePanel({ detail, canEdit }: { detail: AssetDetail; canEdit: boolean }) {
  const record = useRecordMaintenance(detail.asset.id);
  const toast = useToast();
  const [adding, setAdding] = useState(false);
  const [type, setType] = useState<MaintenanceType>('REPAIR');
  const [status, setStatus] = useState<'COMPLETED' | 'SCHEDULED'>('COMPLETED');
  const [description, setDescription] = useState('');
  const [vendor, setVendor] = useState('');
  const [cost, setCost] = useState('');
  const [performedOn, setPerformedOn] = useState(new Date().toISOString().slice(0, 10));
  const [nextDue, setNextDue] = useState('');
  const errors = record.isError ? toApiError(record.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field)?.message;
  const costValue = cost.trim() ? Number(cost) : null;
  const costInvalid = costValue !== null && (!Number.isFinite(costValue) || costValue < 0);
  const disposed = detail.asset.status === 'DISPOSED';

  const submit = () =>
    record.mutate(
      { maintenanceType: type, status, description: description.trim(), vendor: vendor.trim() || null, cost: costValue, performedOn, nextDue: nextDue || null },
      {
        onSuccess: () => {
          toast.success('Maintenance recorded');
          setAdding(false);
          setDescription('');
          setVendor('');
          setCost('');
          setNextDue('');
        },
      },
    );

  return (
    <Panel
      title={`Maintenance (${detail.maintenance.length})`}
      actions={
        canEdit && !disposed && !adding ? (
          <button type="button" className="subtle-btn compact" onClick={() => setAdding(true)}>
            <Wrench size={13} aria-hidden="true" /> Record maintenance
          </button>
        ) : undefined
      }
    >
      {detail.maintenance.length === 0 && !adding && <p className="m-0 text-[11px] text-muted">No maintenance recorded.</p>}
      {detail.maintenance.length > 0 && (
        <ul className="m-0 grid list-none gap-1 p-0" aria-label="Maintenance records">
          {detail.maintenance.map((item) => (
            <li key={item.id} className="list-row flex-wrap sm:flex-nowrap">
              <div className="min-w-0">
                <b className="block break-words">{item.description}</b>
                <small className="block">
                  {titleCase(item.maintenanceType)} · {formatDate(item.performedOn)}
                  {item.vendor ? ` · ${item.vendor}` : ''}
                  {item.cost !== null ? ` · ${formatMoney(item.cost, detail.currency)}` : ''}
                  {item.nextDue ? ` · next due ${formatDate(item.nextDue)}` : ''}
                  {item.createdByName ? ` · by ${item.createdByName}` : ''}
                </small>
              </div>
              <ToneBadge value={item.status} tone={item.status === 'COMPLETED' ? 'success' : 'warning'} />
            </li>
          ))}
        </ul>
      )}
      {adding && (
        <div className="action-card mt-2" role="group" aria-label="New maintenance record">
          <div className="form-row">
            <SelectField label="Type" value={type} onChange={(event) => setType(event.target.value as MaintenanceType)}>
              {MAINTENANCE_TYPES.map((value) => (
                <option key={value} value={value}>
                  {titleCase(value)}
                </option>
              ))}
            </SelectField>
            <SelectField label="Status" value={status} onChange={(event) => setStatus(event.target.value as 'COMPLETED' | 'SCHEDULED')} hint={status === 'COMPLETED' ? 'Completed work cannot be in the future.' : undefined}>
              <option value="COMPLETED">Completed</option>
              <option value="SCHEDULED">Scheduled</option>
            </SelectField>
          </div>
          <TextareaField label="Description" required rows={2} maxLength={2000} value={description} onChange={(event) => setDescription(event.target.value)} error={fieldError('description')} />
          <div className="form-row">
            <TextField label="Vendor" maxLength={120} value={vendor} onChange={(event) => setVendor(event.target.value)} />
            <TextField label="Cost" inputMode="decimal" value={cost} onChange={(event) => setCost(event.target.value)} error={costInvalid ? 'A non-negative amount' : fieldError('cost')} />
          </div>
          <div className="form-row">
            <TextField label={status === 'COMPLETED' ? 'Performed on' : 'Planned for'} type="date" required value={performedOn} onChange={(event) => setPerformedOn(event.target.value)} error={fieldError('performedOn')} />
            <TextField label="Next due (optional)" type="date" min={performedOn} value={nextDue} onChange={(event) => setNextDue(event.target.value)} error={fieldError('nextDue')} />
          </div>
          {record.isError && !errors.length && (
            <p className="field-error" role="alert">
              {errorMessage(record.error)}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" className="subtle-btn compact" onClick={() => setAdding(false)} disabled={record.isPending}>
              Cancel
            </button>
            <button type="button" className="gold-btn compact" onClick={submit} disabled={record.isPending || description.trim().length < 5 || !performedOn || costInvalid}>
              {record.isPending ? 'Saving…' : 'Save record'}
            </button>
          </div>
        </div>
      )}
    </Panel>
  );
}

/** /assets/:id — facts, holder or licence seats, lifecycle, assign/return, assignment history, maintenance, history. */
export default function AssetDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const valid = Number.isInteger(id) && id > 0;
  const { hasRole } = useAuth();
  const canEdit = hasRole(...ASSET_EDITORS);
  const detail = useAsset(valid ? id : null);
  const assignments = useAssetAssignments(valid ? id : null);
  const history = useAssetHistory(valid ? id : null);
  const giveBack = useReturnAsset(valid ? id : 0);
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [target, setTarget] = useState<AssetStatus | null>(null);
  const [assigning, setAssigning] = useState(false);
  const [returning, setReturning] = useState<{ userId: number | null; name: string } | null>(null);
  const crumbs = [{ label: 'Assets', to: '/assets' }];
  const data = detail.data;

  if (!valid || (detail.isError && toApiError(detail.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[...crumbs, { label: 'Not found' }]} title="Asset not found" />
        <EmptyState title="This asset does not exist" description="The link may be wrong." action={<Link to="/assets" className="subtle-btn compact">Back to assets</Link>} />
      </>
    );
  }
  if (detail.isLoading) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Loading asset…" />
        <LoadingState variant="panel" />
      </>
    );
  }
  if (!data) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Asset" />
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
      </>
    );
  }

  const asset = data.asset;
  const licence = asset.category === 'SOFTWARE';
  const disposed = asset.status === 'DISPOSED';
  const assignable = canEdit && (asset.status === 'IN_STOCK' || asset.status === 'IN_USE') && (!licence || asset.seatsUsed < (asset.licenseSeats ?? 0));
  const transitions = canEdit ? data.allowedTransitions : [];

  return (
    <>
      <PageHeader
        crumbs={[...crumbs, { label: asset.assetTag }]}
        title={asset.name}
        actions={
          canEdit ? (
            <>
              {!disposed && (
                <button type="button" className="subtle-btn" onClick={() => setEditing(true)}>
                  <Pencil size={14} aria-hidden="true" /> Edit
                </button>
              )}
              {assignable && (
                <button type="button" className="gold-btn" onClick={() => setAssigning(true)}>
                  <UserPlus size={14} aria-hidden="true" /> {licence ? 'Assign seat' : asset.assignedToId ? 'Reassign' : 'Assign'}
                </button>
              )}
              {!licence && asset.assignedToId && (
                <button type="button" className="subtle-btn" onClick={() => setReturning({ userId: null, name: asset.assignedToName ?? 'the holder' })}>
                  <CornerDownLeft size={14} aria-hidden="true" /> Return
                </button>
              )}
              {transitions.map((to) => (
                <button key={to} type="button" className={to === 'DISPOSED' || to === 'RETIRED' ? 'danger-btn' : 'subtle-btn'} onClick={() => setTarget(to)}>
                  {assetTransitionLabel(to)}
                </button>
              ))}
            </>
          ) : undefined
        }
      >
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
          <span className="id-cell">{asset.assetTag}</span>
          <AssetStatusBadge status={asset.status} />
          <ToneBadge value={asset.assetType} label={ASSET_TYPE_LABELS[asset.assetType]} tone="neutral" />
          <WarrantyBadge status={asset.warrantyStatus} />
          <DemoBadge demo={asset.demo} />
          {!licence && (
            <span className="inline-flex items-center gap-1.5">
              Holder <UserChip name={asset.assignedToName} emptyLabel="Unassigned" />
            </span>
          )}
        </div>
      </PageHeader>
      <div className="page-stack">
        <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Overview">
              <div className="detail-grid">
                <Fact label={licence ? 'Publisher' : 'Manufacturer'}>{asset.manufacturer ?? '—'}</Fact>
                <Fact label={licence ? 'Product' : 'Model'}>{asset.model ?? '—'}</Fact>
                <Fact label={licence ? 'Licence reference' : 'Serial number'}>{asset.serialNumber ?? '—'}</Fact>
                <Fact label="Department">{asset.departmentName ?? '—'}</Fact>
                <Fact label="Location">{asset.location ?? '—'}</Fact>
                <Fact label="Configuration item">{asset.ciId ? <Link to={`/cmdb/${asset.ciId}`}>{asset.ciName}</Link> : '—'}</Fact>
                <Fact label="Vendor">{data.vendor ?? '—'}</Fact>
                <Fact label="Purchase order">{data.purchaseOrder ?? '—'}</Fact>
                <Fact label="Cost">{formatMoney(data.purchaseCost, data.currency)}</Fact>
                <Fact label="Purchased">{data.purchaseDate ? formatDate(data.purchaseDate) : '—'}</Fact>
                {licence ? (
                  <>
                    <Fact label="Seats">{`${formatNumber(asset.seatsUsed)} of ${formatNumber(asset.licenseSeats ?? 0)} used`}</Fact>
                    <Fact label="Licence ends">{asset.licenseExpires ? formatDate(asset.licenseExpires) : '—'}</Fact>
                  </>
                ) : (
                  <>
                    <Fact label="Warranty ends">{asset.warrantyExpires ? formatDate(asset.warrantyExpires) : '—'}</Fact>
                    <Fact label="Assigned">{asset.assignedAt ? formatDateTime(asset.assignedAt) : '—'}</Fact>
                  </>
                )}
                <Fact label="Created">{`${formatDateTime(asset.createdAt)}${data.createdByName ? ` by ${data.createdByName}` : ''}`}</Fact>
                {data.retiredAt && <Fact label="Retired">{formatDateTime(data.retiredAt)}</Fact>}
                {data.disposedAt && <Fact label="Disposed">{formatDateTime(data.disposedAt)}</Fact>}
              </div>
              <div className="mt-3">
                <small className="text-muted">Notes</small>
                <TextBlock text={data.notes} empty="No notes." />
              </div>
            </Panel>
            {licence && (
              <Panel title={`Seats (${data.seats.length} of ${asset.licenseSeats ?? 0})`}>
                {data.seats.length === 0 ? (
                  <p className="m-0 text-[11px] text-muted">No seat is assigned.</p>
                ) : (
                  <ul className="m-0 grid list-none gap-1 p-0" aria-label="Assigned seats">
                    {data.seats.map((seat) => (
                      <li key={seat.assignmentId} className="list-row flex-wrap sm:flex-nowrap">
                        <div className="min-w-0">
                          <b className="block">{seat.userName}</b>
                          <small className="block">
                            {seat.userEmail ? `${seat.userEmail} · ` : ''}since {formatDate(seat.assignedAt)}
                            {seat.assignedByName ? ` · by ${seat.assignedByName}` : ''}
                          </small>
                        </div>
                        {canEdit && (
                          <button type="button" className="subtle-btn compact shrink-0" onClick={() => setReturning({ userId: seat.userId, name: seat.userName })} aria-label={`Release seat of ${seat.userName}`}>
                            <CornerDownLeft size={12} aria-hidden="true" /> Release
                          </button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </Panel>
            )}
            <MaintenancePanel detail={data} canEdit={canEdit} />
          </div>
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Assignment history">
              {assignments.isLoading && <LoadingState variant="inline" />}
              {assignments.isError && <ErrorState error={assignments.error} onRetry={() => void assignments.refetch()} compact />}
              {assignments.data && assignments.data.length === 0 && <p className="m-0 text-[11px] text-muted">Never assigned.</p>}
              {assignments.data && assignments.data.length > 0 && (
                <ul className="m-0 grid list-none gap-1 p-0" aria-label="Assignment history">
                  {assignments.data.map((row) => (
                    <li key={row.id} className="list-row !items-start">
                      <div className="min-w-0">
                        <b className="block">
                          {row.userName} {!row.returnedAt && <ToneBadge value="current" label="Current" tone="success" />}
                        </b>
                        <small className="block">
                          {formatDate(row.assignedAt)}
                          {row.assignedByName ? ` by ${row.assignedByName}` : ''} → {row.returnedAt ? `${formatDate(row.returnedAt)}${row.returnedByName ? ` by ${row.returnedByName}` : ''}` : 'now'}
                        </small>
                        {row.note && <small className="block break-words text-muted">{row.note}</small>}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
            <Panel title="History">
              <EventTimeline
                query={history}
                events={history.data?.map((event) => ({
                  id: event.id,
                  at: event.at,
                  actorName: event.actorName,
                  title: titleCase(event.type),
                  detail: changeDetail(event.fromValue, event.toValue, (value) => labelOf(ASSET_STATUS_LABELS, value)),
                  note: event.note,
                }))}
              />
            </Panel>
            <p className="m-0 text-[10px] text-muted">Updated {formatRelative(asset.updatedAt)}</p>
          </div>
        </div>
      </div>
      {editing && <AssetEditor detail={data} onClose={() => setEditing(false)} />}
      {target && <TransitionDialog detail={data} target={target} onClose={() => setTarget(null)} />}
      {assigning && <AssignDialog detail={data} onClose={() => setAssigning(false)} />}
      <ConfirmDialog
        open={returning !== null}
        onClose={() => {
          setReturning(null);
          giveBack.reset();
        }}
        title={licence ? `Release the seat of ${returning?.name ?? ''}?` : `Return ${asset.assetTag}?`}
        message={licence ? 'The seat becomes free for someone else.' : `${returning?.name ?? 'The holder'} returns the device; it goes back in stock.`}
        reasonLabel="Note (optional)"
        confirmLabel={licence ? 'Release seat' : 'Return'}
        pending={giveBack.isPending}
        error={giveBack.isError ? errorMessage(giveBack.error) : null}
        onConfirm={(note) =>
          returning &&
          giveBack.mutate(
            { userId: returning.userId, note: note.trim() || null },
            {
              onSuccess: () => {
                toast.success(licence ? 'Seat released' : `${asset.assetTag} returned`);
                setReturning(null);
              },
            },
          )
        }
      />
    </>
  );
}
