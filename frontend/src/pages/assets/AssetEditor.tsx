import { useState } from 'react';
import { useSaveAsset } from '../../api/assets';
import { useDepartments } from '../../api/users';
import { CiSinglePicker, type CiChoice } from '../../components/cmdb/CiPicker';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { UserSearchPicker } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import type { UserOption } from '../../lib/itsmTypes';
import { ASSET_TYPE_LABELS, ASSET_TYPES } from '../../lib/ops';
import type { AssetDetail, AssetRequestBody, AssetType } from '../../lib/opsTypes';

const TAG_PATTERN = /^[A-Za-z0-9][A-Za-z0-9-]{1,39}$/;

function text(value: string): string | null {
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

/** Validates the editor values and builds the request; returns field → message on errors. */
export function buildAssetRequest(
  values: {
    assetTag: string;
    name: string;
    assetType: AssetType;
    manufacturer: string;
    model: string;
    serialNumber: string;
    departmentId: string;
    location: string;
    vendor: string;
    purchaseOrder: string;
    purchaseCost: string;
    currency: string;
    purchaseDate: string;
    warrantyExpires: string;
    licenseSeats: string;
    licenseExpires: string;
    notes: string;
    ciId: number | null;
    assignedToId: number | null;
  },
  editing: boolean,
): { body: AssetRequestBody; errors: Record<string, string> } {
  const errors: Record<string, string> = {};
  const licence = values.assetType === 'SOFTWARE_LICENSE';
  if (!editing && values.assetTag.trim() && !TAG_PATTERN.test(values.assetTag.trim())) errors.assetTag = 'Letters, digits and dashes (2–40), starting with a letter or digit';
  if (values.name.trim().length < 2) errors.name = 'Give a name of at least 2 characters';
  let cost: number | null = null;
  if (values.purchaseCost.trim()) {
    cost = Number(values.purchaseCost);
    if (!Number.isFinite(cost) || cost < 0 || !/^\d+(\.\d{1,2})?$/.test(values.purchaseCost.trim())) errors.purchaseCost = 'A non-negative amount with at most 2 decimals';
  }
  if (values.currency.trim() && !/^[A-Za-z]{3}$/.test(values.currency.trim())) errors.currency = 'Three letters, e.g. INR';
  const today = new Date().toISOString().slice(0, 10);
  if (values.purchaseDate && values.purchaseDate > today) errors.purchaseDate = 'Cannot be in the future';
  if (!licence && values.warrantyExpires && values.purchaseDate && values.warrantyExpires < values.purchaseDate) errors.warrantyExpires = 'Cannot end before the purchase date';
  let seats: number | null = null;
  if (licence) {
    seats = Number(values.licenseSeats);
    if (!values.licenseSeats.trim() || !Number.isInteger(seats) || seats < 1 || seats > 100_000) errors.licenseSeats = 'Seats are required for a licence (1–100,000)';
  }
  const body: AssetRequestBody = {
    ...(editing ? {} : { assetTag: text(values.assetTag)?.toUpperCase() ?? null }),
    name: values.name.trim(),
    assetType: values.assetType,
    manufacturer: text(values.manufacturer),
    model: text(values.model),
    serialNumber: text(values.serialNumber),
    // On update 0 clears the department / CI link; on create null = none.
    departmentId: values.departmentId ? Number(values.departmentId) : editing ? 0 : null,
    location: text(values.location),
    vendor: text(values.vendor),
    purchaseOrder: text(values.purchaseOrder),
    purchaseCost: cost,
    currency: text(values.currency)?.toUpperCase() ?? null,
    purchaseDate: values.purchaseDate || null,
    warrantyExpires: licence ? null : values.warrantyExpires || null,
    licenseSeats: licence ? seats : null,
    licenseExpires: licence ? values.licenseExpires || null : null,
    ciId: values.ciId ?? (editing ? 0 : null),
    notes: text(values.notes),
    ...(editing || licence ? {} : { assignedToId: values.assignedToId }),
  };
  return { body, errors };
}

/** Create or edit an asset (asset editors). The tag is fixed after creation; hardware ⇄ licence cannot change. */
export function AssetEditor({ detail, onClose, onSaved }: { detail: AssetDetail | null; onClose: () => void; onSaved?: (detail: AssetDetail) => void }) {
  const save = useSaveAsset();
  const toast = useToast();
  const departments = useDepartments();
  const asset = detail?.asset;
  const [values, setValues] = useState({
    assetTag: '',
    name: asset?.name ?? '',
    assetType: (asset?.assetType ?? 'LAPTOP') as AssetType,
    manufacturer: asset?.manufacturer ?? '',
    model: asset?.model ?? '',
    serialNumber: asset?.serialNumber ?? '',
    departmentId: asset?.departmentId ? String(asset.departmentId) : '',
    location: asset?.location ?? '',
    vendor: detail?.vendor ?? '',
    purchaseOrder: detail?.purchaseOrder ?? '',
    purchaseCost: detail?.purchaseCost !== null && detail?.purchaseCost !== undefined ? String(detail.purchaseCost) : '',
    currency: detail?.currency ?? 'INR',
    purchaseDate: detail?.purchaseDate ?? '',
    warrantyExpires: asset?.warrantyExpires ?? '',
    licenseSeats: asset?.licenseSeats ? String(asset.licenseSeats) : '',
    licenseExpires: asset?.licenseExpires ?? '',
    notes: detail?.notes ?? '',
  });
  const [ci, setCi] = useState<CiChoice | null>(asset?.ciId ? { id: asset.ciId, name: asset.ciName ?? `CI #${asset.ciId}` } : null);
  const [holder, setHolder] = useState<UserOption | null>(null);
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const licence = values.assetType === 'SOFTWARE_LICENSE';
  const set = (patch: Partial<typeof values>) => setValues((current) => ({ ...current, ...patch }));

  const serverErrors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => localErrors[field] ?? serverErrors.find((error) => error.field === field)?.message;

  const submit = () => {
    const { body, errors } = buildAssetRequest({ ...values, ciId: ci?.id ?? null, assignedToId: holder?.id ?? null }, Boolean(detail));
    setLocalErrors(errors);
    if (Object.keys(errors).length) return;
    save.mutate(
      { id: asset?.id, body },
      {
        onSuccess: (saved) => {
          toast.success(detail ? `${saved.asset.assetTag} updated` : `${saved.asset.assetTag} created`);
          onSaved?.(saved);
          onClose();
        },
      },
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={detail ? `Edit ${asset?.assetTag}` : 'New asset'}
      eyebrow="ASSET INVENTORY"
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={save.isPending}>
            {save.isPending ? 'Saving…' : detail ? 'Save asset' : 'Create asset'}
          </button>
        </>
      }
    >
      <div className="form-row three">
        {detail ? (
          <TextField label="Asset tag" value={asset?.assetTag ?? ''} disabled readOnly hint="Fixed after creation." />
        ) : (
          <TextField label="Asset tag" placeholder="AST-…" maxLength={40} value={values.assetTag} onChange={(event) => set({ assetTag: event.target.value })} hint="Leave empty for the next AST number." error={fieldError('assetTag')} />
        )}
        <TextField label="Name" required maxLength={150} value={values.name} onChange={(event) => set({ name: event.target.value })} error={fieldError('name')} />
        <SelectField
          label="Type"
          required
          value={values.assetType}
          onChange={(event) => set({ assetType: event.target.value as AssetType })}
          error={fieldError('assetType')}
          hint={detail ? 'Hardware and licences cannot be swapped.' : undefined}
        >
          {ASSET_TYPES.filter((type) => !detail || (type === 'SOFTWARE_LICENSE') === (asset?.assetType === 'SOFTWARE_LICENSE')).map((type) => (
            <option key={type} value={type}>
              {ASSET_TYPE_LABELS[type]}
            </option>
          ))}
        </SelectField>
      </div>
      <div className="form-row three">
        <TextField label={licence ? 'Publisher' : 'Manufacturer'} maxLength={80} value={values.manufacturer} onChange={(event) => set({ manufacturer: event.target.value })} error={fieldError('manufacturer')} />
        <TextField label={licence ? 'Product / edition' : 'Model'} maxLength={120} value={values.model} onChange={(event) => set({ model: event.target.value })} error={fieldError('model')} />
        <TextField label={licence ? 'Licence key reference' : 'Serial number'} maxLength={120} value={values.serialNumber} onChange={(event) => set({ serialNumber: event.target.value })} error={fieldError('serialNumber')} />
      </div>
      <div className="form-row">
        <SelectField label="Department" value={values.departmentId} onChange={(event) => set({ departmentId: event.target.value })} error={fieldError('departmentId')} disabled={departments.isError}>
          <option value="">None</option>
          {(departments.data ?? []).map((department) => (
            <option key={department.id} value={department.id}>
              {department.name}
            </option>
          ))}
        </SelectField>
        <TextField label="Location" maxLength={160} value={values.location} onChange={(event) => set({ location: event.target.value })} error={fieldError('location')} />
      </div>
      <div className="form-row three">
        <TextField label="Vendor" maxLength={120} value={values.vendor} onChange={(event) => set({ vendor: event.target.value })} error={fieldError('vendor')} />
        <TextField label="Purchase order" maxLength={60} value={values.purchaseOrder} onChange={(event) => set({ purchaseOrder: event.target.value })} error={fieldError('purchaseOrder')} />
        <TextField label="Purchase date" type="date" value={values.purchaseDate} onChange={(event) => set({ purchaseDate: event.target.value })} error={fieldError('purchaseDate')} />
      </div>
      <div className="form-row three">
        <TextField label="Cost" inputMode="decimal" value={values.purchaseCost} onChange={(event) => set({ purchaseCost: event.target.value })} error={fieldError('purchaseCost')} />
        <TextField label="Currency" maxLength={3} value={values.currency} onChange={(event) => set({ currency: event.target.value })} error={fieldError('currency')} />
        {licence ? (
          <TextField label="Seats" required inputMode="numeric" value={values.licenseSeats} onChange={(event) => set({ licenseSeats: event.target.value })} error={fieldError('licenseSeats')} />
        ) : (
          <TextField label="Warranty ends" type="date" value={values.warrantyExpires} onChange={(event) => set({ warrantyExpires: event.target.value })} error={fieldError('warrantyExpires')} />
        )}
      </div>
      {licence && <TextField label="Licence ends" type="date" value={values.licenseExpires} onChange={(event) => set({ licenseExpires: event.target.value })} error={fieldError('licenseExpires')} />}
      <CiSinglePicker label="Configuration item (optional)" value={ci} onChange={setCi} error={fieldError('ciId')} hint="Link servers and network gear to their CI in the CMDB." />
      {!detail && !licence && (
        <UserSearchPicker label="Assign to (optional)" value={holder} onChange={setHolder} error={fieldError('assignedToId')} hint="Assigning puts the asset in use and notifies the person." />
      )}
      <TextareaField label="Notes" rows={3} maxLength={2000} value={values.notes} onChange={(event) => set({ notes: event.target.value })} error={fieldError('notes')} />
      {save.isError && !serverErrors.length && (
        <p className="field-error" role="alert">
          {errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}
