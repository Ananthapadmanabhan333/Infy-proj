import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { useSaveCi } from '../../api/cmdb';
import { useServices } from '../../api/services';
import { useTeams } from '../../api/teams';
import { useAssignableUsers } from '../../api/users';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { CI_ENVIRONMENT_LABELS, CI_ENVIRONMENTS, CI_STATUSES, CI_TYPE_LABELS, CI_TYPES, CRITICALITIES } from '../../lib/ops';
import type { CiDetail, CiEnvironment, CiStatus, CiType, Criticality } from '../../lib/opsTypes';
import { titleCase } from '../../lib/format';

type AttrKind = 'text' | 'number' | 'boolean';
interface AttrRow {
  key: string;
  value: string;
  kind: AttrKind;
}

const ATTRIBUTE_NAME = /^[A-Za-z][A-Za-z0-9_.-]{0,59}$/;

function toRows(attributes: CiDetail['attributes']): AttrRow[] {
  return Object.entries(attributes ?? {}).map(([key, value]) => ({
    key,
    value: value === null || value === undefined ? '' : String(value),
    kind: typeof value === 'number' ? 'number' : typeof value === 'boolean' ? 'boolean' : 'text',
  }));
}

/** Attribute rows → the API map (text ≤ 500, number, boolean or null for an empty value). Returns an error message instead when invalid. */
export function rowsToAttributes(rows: AttrRow[]): Record<string, string | number | boolean | null> | string {
  const result: Record<string, string | number | boolean | null> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (!key && !row.value.trim()) continue;
    if (!ATTRIBUTE_NAME.test(key)) return `“${key || '(empty)'}” is not a valid attribute name (letters, digits, _ . -; starts with a letter)`;
    if (key in result) return `Attribute “${key}” is listed twice`;
    const raw = row.value.trim();
    if (!raw) result[key] = null;
    else if (row.kind === 'number') {
      const parsed = Number(raw);
      if (!Number.isFinite(parsed)) return `Attribute “${key}” must be a number`;
      result[key] = parsed;
    } else if (row.kind === 'boolean') result[key] = raw === 'true';
    else if (raw.length > 500) return `Attribute “${key}” is longer than 500 characters`;
    else result[key] = raw;
  }
  if (Object.keys(result).length > 50) return 'At most 50 attributes';
  return result;
}

/** Create or edit a configuration item (CMDB editors). Business-service CIs keep the name and type of their service. */
export function CiEditor({ ci, onClose, onSaved }: { ci: CiDetail | null; onClose: () => void; onSaved?: (ci: CiDetail) => void }) {
  const save = useSaveCi();
  const toast = useToast();
  const staff = useAssignableUsers(null);
  const teams = useTeams({ active: true });
  const services = useServices();
  const [name, setName] = useState(ci?.name ?? '');
  const [ciType, setCiType] = useState<CiType>(ci?.ciType ?? 'APPLICATION');
  const [status, setStatus] = useState<CiStatus>(ci?.status ?? 'ACTIVE');
  const [environment, setEnvironment] = useState<string>(ci?.environment ?? '');
  const [criticality, setCriticality] = useState<string>(ci?.criticality ?? '');
  const [description, setDescription] = useState(ci?.description ?? '');
  const [ownerId, setOwnerId] = useState(ci?.ownerId ? String(ci.ownerId) : '');
  const [teamId, setTeamId] = useState(ci?.teamId ? String(ci.teamId) : '');
  const [serviceId, setServiceId] = useState(ci?.serviceId ? String(ci.serviceId) : '');
  const [rows, setRows] = useState<AttrRow[]>(() => toRows(ci?.attributes ?? null));
  const [localError, setLocalError] = useState<string | null>(null);
  const serviceCi = Boolean(ci?.serviceId);

  const errors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field || error.field.startsWith(`${field}.`) || error.field.startsWith(`${field}[`))?.message;

  const submit = () => {
    if (name.trim().length < 2) {
      setLocalError('Give a name of at least 2 characters');
      return;
    }
    const attributes = rowsToAttributes(rows);
    if (typeof attributes === 'string') {
      setLocalError(attributes);
      return;
    }
    setLocalError(null);
    save.mutate(
      {
        id: ci?.id,
        body: {
          name: name.trim(),
          ciType,
          status,
          environment: (environment || null) as CiEnvironment | null,
          criticality: (criticality || null) as Criticality | null,
          description: description.trim() || null,
          // On update 0 clears owner/team; on create null = none.
          ownerId: ownerId ? Number(ownerId) : ci ? 0 : null,
          teamId: teamId ? Number(teamId) : ci ? 0 : null,
          ...(ci ? {} : { serviceId: ciType === 'BUSINESS_SERVICE' && serviceId ? Number(serviceId) : null }),
          attributes,
        },
      },
      {
        onSuccess: (saved) => {
          toast.success(ci ? `${saved.name} updated` : `${saved.name} created`);
          onSaved?.(saved);
          onClose();
        },
      },
    );
  };

  const setRow = (index: number, patch: Partial<AttrRow>) => setRows((current) => current.map((row, i) => (i === index ? { ...row, ...patch } : row)));

  return (
    <Modal
      open
      onClose={onClose}
      title={ci ? `Edit ${ci.name}` : 'New configuration item'}
      eyebrow="CMDB"
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={save.isPending}>
            {save.isPending ? 'Saving…' : ci ? 'Save CI' : 'Create CI'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <TextField
          label="Name"
          required
          maxLength={120}
          value={name}
          onChange={(event) => setName(event.target.value)}
          disabled={serviceCi}
          hint={serviceCi ? 'Rename the business service to rename this CI.' : 'Unique, ignoring case.'}
          error={fieldError('name')}
        />
        <SelectField label="Type" required value={ciType} onChange={(event) => setCiType(event.target.value as CiType)} disabled={serviceCi} error={fieldError('ciType')}>
          {CI_TYPES.map((value) => (
            <option key={value} value={value}>
              {CI_TYPE_LABELS[value]}
            </option>
          ))}
        </SelectField>
      </div>
      <div className="form-row three">
        <SelectField label="Status" value={status} onChange={(event) => setStatus(event.target.value as CiStatus)} error={fieldError('status')}>
          {CI_STATUSES.map((value) => (
            <option key={value} value={value}>
              {titleCase(value)}
            </option>
          ))}
        </SelectField>
        <SelectField label="Environment" value={environment} onChange={(event) => setEnvironment(event.target.value)} error={fieldError('environment')}>
          <option value="">Not set</option>
          {CI_ENVIRONMENTS.map((value) => (
            <option key={value} value={value}>
              {CI_ENVIRONMENT_LABELS[value]}
            </option>
          ))}
        </SelectField>
        <SelectField label="Criticality" value={criticality} onChange={(event) => setCriticality(event.target.value)} error={fieldError('criticality')}>
          <option value="">Not set</option>
          {CRITICALITIES.map((value) => (
            <option key={value} value={value}>
              {titleCase(value)}
            </option>
          ))}
        </SelectField>
      </div>
      <TextareaField label="Description" rows={3} maxLength={2000} value={description} onChange={(event) => setDescription(event.target.value)} error={fieldError('description')} />
      <div className="form-row">
        <SelectField label="Owner" value={ownerId} onChange={(event) => setOwnerId(event.target.value)} error={fieldError('ownerId')} disabled={staff.isError}>
          <option value="">No owner</option>
          {(staff.data ?? []).map((person) => (
            <option key={person.id} value={person.id}>
              {person.fullName}
            </option>
          ))}
        </SelectField>
        <SelectField label="Team" value={teamId} onChange={(event) => setTeamId(event.target.value)} error={fieldError('teamId')} disabled={teams.isError}>
          <option value="">No team</option>
          {(teams.data ?? []).map((team) => (
            <option key={team.id} value={team.id}>
              {team.name}
            </option>
          ))}
        </SelectField>
      </div>
      {!ci && ciType === 'BUSINESS_SERVICE' && (
        <SelectField
          label="Business service"
          value={serviceId}
          onChange={(event) => setServiceId(event.target.value)}
          hint="Every business service already has its own CI; pick one only for a service without a CI."
          error={fieldError('serviceId')}
        >
          <option value="">None</option>
          {(services.data ?? []).map((service) => (
            <option key={service.id} value={service.id}>
              {service.name} ({service.code})
            </option>
          ))}
        </SelectField>
      )}
      <fieldset className="m-0 mb-3 min-w-0 border-0 p-0">
        <legend className="mb-1 text-[11px] font-semibold">Attributes</legend>
        {rows.length === 0 && <p className="m-0 mb-2 text-[11px] text-muted">No attributes. Add facts such as os, ipAddress, version or vcpu.</p>}
        <div className="grid gap-2">
          {rows.map((row, index) => (
            <div key={index} className="grid grid-cols-[1fr_1fr] gap-2 sm:grid-cols-[1fr_1.4fr_110px_auto]" role="group" aria-label={`Attribute ${index + 1}`}>
              <input className="control sm" aria-label={`Attribute ${index + 1} name`} placeholder="name" maxLength={60} value={row.key} onChange={(event) => setRow(index, { key: event.target.value })} />
              {row.kind === 'boolean' ? (
                <select className="control sm" aria-label={`Attribute ${index + 1} value`} value={row.value === 'true' ? 'true' : 'false'} onChange={(event) => setRow(index, { value: event.target.value })}>
                  <option value="true">true</option>
                  <option value="false">false</option>
                </select>
              ) : (
                <input
                  className="control sm"
                  aria-label={`Attribute ${index + 1} value`}
                  placeholder="value"
                  inputMode={row.kind === 'number' ? 'decimal' : undefined}
                  maxLength={500}
                  value={row.value}
                  onChange={(event) => setRow(index, { value: event.target.value })}
                />
              )}
              <select className="control sm" aria-label={`Attribute ${index + 1} kind`} value={row.kind} onChange={(event) => setRow(index, { kind: event.target.value as AttrKind, value: event.target.value === 'boolean' ? 'true' : row.value })}>
                <option value="text">Text</option>
                <option value="number">Number</option>
                <option value="boolean">Yes / no</option>
              </select>
              <button type="button" className="icon-btn sm self-center" aria-label={`Remove attribute ${row.key || index + 1}`} onClick={() => setRows((current) => current.filter((_, i) => i !== index))}>
                <Trash2 size={13} />
              </button>
            </div>
          ))}
        </div>
        {rows.length < 50 && (
          <button type="button" className="link-btn mt-2 text-[11px]" onClick={() => setRows((current) => [...current, { key: '', value: '', kind: 'text' }])}>
            <Plus size={12} aria-hidden="true" /> Add attribute
          </button>
        )}
        {fieldError('attributes') && (
          <p className="field-error" role="alert">
            {fieldError('attributes')}
          </p>
        )}
      </fieldset>
      {localError && (
        <p className="field-error" role="alert">
          {localError}
        </p>
      )}
      {save.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}
