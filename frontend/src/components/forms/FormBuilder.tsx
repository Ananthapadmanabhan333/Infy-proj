import { useEffect, useMemo, useState } from 'react';
import { ArrowDown, ArrowUp, CheckCircle2, Copy, FilePlus2, History, ListPlus, Save, Trash2 } from 'lucide-react';
import { useFormDetail, useForms, useFormVersion, useFormVersions, useSaveForm, useValidateFormPreview } from '../../api/forms';
import { useDebounce } from '../../hooks/useDebounce';
import { errorMessage, toApiError } from '../../lib/api';
import { ROLES, roleLabel } from '../../lib/constants';
import { initialValues, serverFieldErrors, toFormData, validateDynamic, type DynamicValue, type DynamicValues } from '../../lib/dynamicForm';
import { formatRelative } from '../../lib/format';
import { FILE_EXTENSIONS, FORM_FIELD_TYPES } from '../../lib/itsm';
import type { FormDetail, FormFieldType, ShowIfOperator } from '../../lib/itsmTypes';
import type { FormField, Role } from '../../lib/types';
import { DemoBadge } from '../Badge';
import { CheckboxField, SelectField, TextareaField, TextField } from '../FormField';
import { ToneBadge } from '../itsm/ItsmParts';
import { Modal } from '../Modal';
import { Panel } from '../Panel';
import { SearchInput } from '../SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';
import { DynamicFormFields } from './DynamicFormFields';

/** Form editors (backend `Roles.FORM_EDITORS`). */
export const FORM_EDITORS: Role[] = ['SYSTEM_ADMIN', 'IT_ADMIN', 'IT_MANAGER'];
const KEY_PATTERN = /^[a-zA-Z][a-zA-Z0-9_]{0,39}$/;
const CODE_PATTERN = /^[A-Z0-9_-]{2,40}$/;
const OPERATORS: { value: ShowIfOperator; label: string }[] = [
  { value: 'EQUALS', label: 'equals' },
  { value: 'NOT_EQUALS', label: 'does not equal' },
  { value: 'IN', label: 'is one of (comma-separated)' },
  { value: 'CONTAINS', label: 'contains' },
  { value: 'NOT_EMPTY', label: 'is filled in' },
  { value: 'EMPTY', label: 'is empty' },
];
const HAS_OPTIONS: FormFieldType[] = ['select', 'multiselect'];
const HAS_LENGTH: FormFieldType[] = ['text', 'textarea', 'email'];

/** Editor row: options as comma-separated text while editing. */
interface DraftField extends Omit<FormField, 'options'> {
  uid: string;
  optionsText: string;
}

let uidCounter = 0;
const nextUid = () => `f${(uidCounter += 1)}`;

function toDraft(field: FormField): DraftField {
  const { options, ...rest } = field;
  return { ...rest, uid: nextUid(), optionsText: (options ?? []).join(', ') };
}

function numberOrNull(value: string): number | null {
  if (!value.trim()) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** Clean `FormField` for the API (null/empty properties dropped; the API omits them too). */
export function fromDraft(draft: DraftField): FormField {
  const field: FormField = { key: draft.key.trim(), label: draft.label.trim(), type: draft.type, required: draft.required };
  const type = draft.type as FormFieldType;
  if (HAS_OPTIONS.includes(type)) field.options = Array.from(new Set(draft.optionsText.split(',').map((option) => option.trim()).filter(Boolean)));
  if (draft.placeholder?.trim()) field.placeholder = draft.placeholder.trim();
  if (draft.helpText?.trim()) field.helpText = draft.helpText.trim();
  if (draft.min != null) field.min = draft.min;
  if (draft.max != null) field.max = draft.max;
  if (HAS_LENGTH.includes(type) && draft.pattern?.trim()) field.pattern = draft.pattern.trim();
  if (type === 'date' && draft.minDate?.trim()) field.minDate = draft.minDate.trim();
  if (type === 'date' && draft.maxDate?.trim()) field.maxDate = draft.maxDate.trim();
  if (draft.showIf?.field) field.showIf = { field: draft.showIf.field, op: draft.showIf.op, ...(draft.showIf.op === 'EMPTY' || draft.showIf.op === 'NOT_EMPTY' ? {} : { value: draft.showIf.value ?? '' }) };
  if (type === 'file' && draft.accept?.length) field.accept = draft.accept;
  if (type === 'user' && draft.roles?.length) field.roles = draft.roles;
  return field;
}

/** Definition checks the server also makes (unique keys, options, earlier-field showIf, ranges). */
export function definitionErrors(drafts: DraftField[]): Record<string, string> {
  const errors: Record<string, string> = {};
  const seen = new Set<string>();
  drafts.forEach((draft, index) => {
    const key = draft.key.trim();
    if (!KEY_PATTERN.test(key)) errors[draft.uid] = 'Key: a letter, then letters, digits or _ (max 40)';
    else if (seen.has(key)) errors[draft.uid] = `Key “${key}” is used twice`;
    else if (!draft.label.trim()) errors[draft.uid] = 'Give the field a label';
    else if (HAS_OPTIONS.includes(draft.type as FormFieldType) && !draft.optionsText.split(',').some((option) => option.trim())) errors[draft.uid] = 'Choice fields need at least one option';
    else if (draft.min != null && draft.max != null && draft.min > draft.max) errors[draft.uid] = 'Min cannot be greater than max';
    else if (draft.showIf?.field && !drafts.slice(0, index).some((earlier) => earlier.key.trim() === draft.showIf?.field)) errors[draft.uid] = 'Conditions must refer to an earlier field';
    seen.add(key);
  });
  return errors;
}

function FieldCard({
  draft,
  index,
  count,
  earlier,
  error,
  readOnly,
  onChange,
  onMove,
  onRemove,
}: {
  draft: DraftField;
  index: number;
  count: number;
  earlier: DraftField[];
  error?: string;
  readOnly: boolean;
  onChange: (patch: Partial<DraftField>) => void;
  onMove: (direction: -1 | 1) => void;
  onRemove: () => void;
}) {
  const type = draft.type as FormFieldType;
  const rangeLabel = type === 'number' ? 'value' : type === 'multiselect' ? 'selections' : type === 'file' ? 'files' : 'characters';
  return (
    <div className="action-card" role="group" aria-label={`Field ${index + 1}: ${draft.label || draft.key || 'new field'}`}>
      <div className="form-row three">
        <TextField label="Key" required value={draft.key} disabled={readOnly} onChange={(event) => onChange({ key: event.target.value })} />
        <TextField label="Label" required maxLength={80} value={draft.label} disabled={readOnly} onChange={(event) => onChange({ label: event.target.value })} />
        <SelectField label="Type" value={draft.type} disabled={readOnly} onChange={(event) => onChange({ type: event.target.value as FormField['type'] })}>
          {FORM_FIELD_TYPES.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </SelectField>
      </div>
      {HAS_OPTIONS.includes(type) && <TextField label="Options (comma-separated)" required value={draft.optionsText} disabled={readOnly} onChange={(event) => onChange({ optionsText: event.target.value })} />}
      <div className="form-row">
        {type !== 'checkbox' && type !== 'file' && type !== 'multiselect' && (
          <TextField label="Placeholder" maxLength={120} value={draft.placeholder ?? ''} disabled={readOnly} onChange={(event) => onChange({ placeholder: event.target.value })} />
        )}
        <TextField label="Help text" maxLength={300} value={draft.helpText ?? ''} disabled={readOnly} onChange={(event) => onChange({ helpText: event.target.value })} />
      </div>
      {type !== 'checkbox' && type !== 'select' && type !== 'user' && type !== 'date' && (
        <div className="form-row">
          <TextField label={`Min (${rangeLabel})`} type="number" value={draft.min ?? ''} disabled={readOnly} onChange={(event) => onChange({ min: numberOrNull(event.target.value) })} />
          <TextField label={`Max (${rangeLabel})`} type="number" value={draft.max ?? ''} disabled={readOnly} onChange={(event) => onChange({ max: numberOrNull(event.target.value) })} hint={type === 'file' ? '1–10 files (default 5)' : undefined} />
        </div>
      )}
      {HAS_LENGTH.includes(type) && (
        <TextField label="Pattern (regular expression for the whole value)" maxLength={200} placeholder="^[A-Z]{3}-\d{2}$" value={draft.pattern ?? ''} disabled={readOnly} onChange={(event) => onChange({ pattern: event.target.value })} />
      )}
      {type === 'date' && (
        <div className="form-row">
          <TextField label="Earliest date" placeholder="today, today+3 or 2026-12-01" maxLength={20} value={draft.minDate ?? ''} disabled={readOnly} onChange={(event) => onChange({ minDate: event.target.value })} />
          <TextField label="Latest date" placeholder="today+90" maxLength={20} value={draft.maxDate ?? ''} disabled={readOnly} onChange={(event) => onChange({ maxDate: event.target.value })} />
        </div>
      )}
      {type === 'file' && (
        <fieldset className="m-0 mb-3 border-0 p-0" aria-label="Accepted file types">
          <small className="text-muted">Accepted file types (none = all allowed)</small>
          <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1">
            {FILE_EXTENSIONS.map((extension) => (
              <label key={extension} className="inline-flex items-center gap-1 text-[11px]">
                <input
                  type="checkbox"
                  disabled={readOnly}
                  checked={(draft.accept ?? []).includes(extension)}
                  onChange={(event) => onChange({ accept: event.target.checked ? [...(draft.accept ?? []), extension] : (draft.accept ?? []).filter((entry) => entry !== extension) })}
                />
                .{extension}
              </label>
            ))}
          </div>
        </fieldset>
      )}
      {type === 'user' && (
        <fieldset className="m-0 mb-3 border-0 p-0" aria-label="Pick from roles">
          <small className="text-muted">Restrict to roles (none = any active user)</small>
          <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1">
            {ROLES.map((role) => (
              <label key={role} className="inline-flex items-center gap-1 text-[11px]">
                <input
                  type="checkbox"
                  disabled={readOnly}
                  checked={(draft.roles ?? []).includes(role)}
                  onChange={(event) => onChange({ roles: event.target.checked ? [...(draft.roles ?? []), role] : (draft.roles ?? []).filter((entry) => entry !== role) })}
                />
                {roleLabel(role)}
              </label>
            ))}
          </div>
        </fieldset>
      )}
      <div className="form-row three">
        <SelectField
          label="Show only when"
          value={draft.showIf?.field ?? ''}
          disabled={readOnly || earlier.length === 0}
          onChange={(event) => onChange({ showIf: event.target.value ? { field: event.target.value, op: draft.showIf?.op ?? 'EQUALS', value: draft.showIf?.value ?? '' } : null })}
          hint={earlier.length === 0 ? 'The first field is always shown.' : undefined}
        >
          <option value="">Always shown</option>
          {earlier
            .filter((candidate) => candidate.key.trim())
            .map((candidate) => (
              <option key={candidate.uid} value={candidate.key.trim()}>
                {candidate.label || candidate.key}
              </option>
            ))}
        </SelectField>
        {draft.showIf?.field && (
          <>
            <SelectField label="Condition" value={draft.showIf.op} disabled={readOnly} onChange={(event) => onChange({ showIf: { ...draft.showIf!, op: event.target.value as ShowIfOperator } })}>
              {OPERATORS.map((operator) => (
                <option key={operator.value} value={operator.value}>
                  {operator.label}
                </option>
              ))}
            </SelectField>
            {draft.showIf.op !== 'EMPTY' && draft.showIf.op !== 'NOT_EMPTY' && (
              <TextField label="Value" maxLength={500} value={draft.showIf.value ?? ''} disabled={readOnly} onChange={(event) => onChange({ showIf: { ...draft.showIf!, value: event.target.value } })} />
            )}
          </>
        )}
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <CheckboxField label="Required" checked={draft.required} disabled={readOnly} onChange={(event) => onChange({ required: event.target.checked })} />
        {!readOnly && (
          <div className="flex gap-1">
            <button type="button" className="icon-btn sm" aria-label={`Move field ${index + 1} up`} disabled={index === 0} onClick={() => onMove(-1)}>
              <ArrowUp size={14} />
            </button>
            <button type="button" className="icon-btn sm" aria-label={`Move field ${index + 1} down`} disabled={index === count - 1} onClick={() => onMove(1)}>
              <ArrowDown size={14} />
            </button>
            <button type="button" className="icon-btn sm" aria-label={`Remove field ${index + 1}`} onClick={onRemove}>
              <Trash2 size={14} />
            </button>
          </div>
        )}
      </div>
      {error && (
        <p className="field-error m-0" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

function NewFormModal({ onClose, onCreated }: { onClose: () => void; onCreated: (form: FormDetail) => void }) {
  const save = useSaveForm();
  const templates = useForms({ template: true, active: true });
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [copyFromId, setCopyFromId] = useState('');
  const [template, setTemplate] = useState(false);
  const errors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];
  const codeOk = CODE_PATTERN.test(code);
  return (
    <Modal
      open
      onClose={onClose}
      title="New request form"
      eyebrow="FORM BUILDER"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={!codeOk || !name.trim() || save.isPending}
            onClick={() =>
              save.mutate(
                { body: { code, name: name.trim(), template, active: true, ...(copyFromId ? { copyFromId: Number(copyFromId) } : { fields: [] }) } },
                { onSuccess: (form) => onCreated(form) },
              )
            }
          >
            {save.isPending ? 'Creating…' : 'Create form'}
          </button>
        </>
      }
    >
      <TextField
        label="Code"
        required
        placeholder="ACCESS-REQUEST"
        value={code}
        onChange={(event) => setCode(event.target.value.toUpperCase())}
        error={(code && !codeOk ? 'Capital letters, digits, _ and - (2–40)' : undefined) ?? errors.find((error) => error.field === 'code')?.message}
        hint="Cannot change later."
      />
      <TextField label="Name" required maxLength={150} value={name} onChange={(event) => setName(event.target.value)} error={errors.find((error) => error.field === 'name')?.message} />
      <SelectField label="Start from" value={copyFromId} onChange={(event) => setCopyFromId(event.target.value)}>
        <option value="">A blank form</option>
        {(templates.data?.content ?? []).map((form) => (
          <option key={form.id} value={form.id}>
            Template: {form.name} ({form.fieldCount} fields)
          </option>
        ))}
      </SelectField>
      <CheckboxField label="This form is a template (offered as a starting point)" checked={template} onChange={(event) => setTemplate(event.target.checked)} />
      {save.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}

function FormEditor({ form, canEdit }: { form: FormDetail; canEdit: boolean }) {
  const save = useSaveForm();
  const validate = useValidateFormPreview(form.id);
  const versions = useFormVersions(form.id);
  const toast = useToast();
  const [name, setName] = useState(form.name);
  const [description, setDescription] = useState(form.description ?? '');
  const [template, setTemplate] = useState(form.template);
  const [active, setActive] = useState(form.active);
  const [changeNote, setChangeNote] = useState('');
  const [drafts, setDrafts] = useState<DraftField[]>(() => form.fields.map((field) => toDraft(field as FormField)));
  const [viewVersion, setViewVersion] = useState<number | null>(null);
  const versionDetail = useFormVersion(form.id, viewVersion);
  const [previewValues, setPreviewValues] = useState<DynamicValues>({});
  const [previewErrors, setPreviewErrors] = useState<Record<string, string>>({});
  const [serverCheck, setServerCheck] = useState<string | null>(null);

  const fields = useMemo(() => drafts.map(fromDraft), [drafts]);
  const debouncedFields = useDebounce(fields, 200);
  const defErrors = definitionErrors(drafts);
  const originalFields = useMemo(() => JSON.stringify(form.fields.map((field) => fromDraft(toDraft(field as FormField)))), [form.fields]);
  const fieldsChanged = JSON.stringify(fields) !== originalFields;
  const dirty = fieldsChanged || name !== form.name || description !== (form.description ?? '') || template !== form.template || active !== form.active;

  useEffect(() => {
    setPreviewValues((current) => ({ ...initialValues(debouncedFields), ...current }));
  }, [debouncedFields]);

  const update = (uid: string, patch: Partial<DraftField>) => setDrafts((current) => current.map((draft) => (draft.uid === uid ? { ...draft, ...patch } : draft)));
  const move = (index: number, direction: -1 | 1) =>
    setDrafts((current) => {
      const next = [...current];
      const [moved] = next.splice(index, 1);
      next.splice(index + direction, 0, moved);
      return next;
    });

  const serverErrors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];
  const saveForm = () =>
    save.mutate(
      { id: form.id, body: { code: form.code, name: name.trim(), description: description.trim() || null, template, active, fields, changeNote: changeNote.trim() || null } },
      {
        onSuccess: (saved) => {
          toast.success(saved.currentVersion !== form.currentVersion ? `${saved.name} saved as version ${saved.currentVersion}` : `${saved.name} saved`);
          setChangeNote('');
        },
      },
    );

  const previewChange = (key: string, value: DynamicValue) => setPreviewValues((current) => ({ ...current, [key]: value }));
  const checkPreview = () => {
    const local = validateDynamic(fields, previewValues);
    setPreviewErrors(local);
    setServerCheck(null);
    if (Object.keys(local).length) return;
    if (dirty) {
      setServerCheck('Valid in the browser. Save the form to check it against the server rules.');
      return;
    }
    validate.mutate(toFormData(fields, previewValues), {
      onSuccess: () => setServerCheck('The server accepts this data.'),
      onError: (error) => {
        const apiError = toApiError(error);
        setPreviewErrors(serverFieldErrors(fields, apiError.fieldErrors, apiError.message));
        setServerCheck(apiError.message);
      },
    });
  };

  return (
    <div className="grid items-start gap-[14px] 2xl:grid-cols-[1.2fr_1fr]">
      <Panel
        title={`${form.name} · v${form.currentVersion}`}
        subtitle={`${form.code}${form.catalogItems.length ? ` · used by ${form.catalogItems.map((item) => item.name).join(', ')}` : ' · not used by a catalogue item'}`}
        actions={
          canEdit ? (
            <button type="button" className="gold-btn compact" onClick={saveForm} disabled={!dirty || Object.keys(defErrors).length > 0 || save.isPending}>
              <Save size={13} aria-hidden="true" /> {save.isPending ? 'Saving…' : fieldsChanged ? 'Save new version' : 'Save'}
            </button>
          ) : undefined
        }
      >
        <div className="form-row">
          <TextField label="Name" required maxLength={150} value={name} disabled={!canEdit} onChange={(event) => setName(event.target.value)} />
          <TextField label="Change note" maxLength={500} placeholder="What changed in this version" value={changeNote} disabled={!canEdit || !fieldsChanged} onChange={(event) => setChangeNote(event.target.value)} />
        </div>
        <TextareaField label="Description" rows={2} maxLength={1000} value={description} disabled={!canEdit} onChange={(event) => setDescription(event.target.value)} />
        <div className="flex flex-wrap gap-6">
          <CheckboxField label="Template" checked={template} disabled={!canEdit} onChange={(event) => setTemplate(event.target.checked)} />
          <CheckboxField label="Active" checked={active} disabled={!canEdit} onChange={(event) => setActive(event.target.checked)} />
        </div>
        {serverErrors.length > 0 && (
          <div className="callout red" role="alert">
            <div>{serverErrors.map((error) => `${error.field}: ${error.message}`).join(' · ')}</div>
          </div>
        )}
        {save.isError && !serverErrors.length && (
          <p className="field-error" role="alert">
            {errorMessage(save.error)}
          </p>
        )}
        <div className="form-section-title">Fields ({drafts.length}/40)</div>
        {drafts.length === 0 && <p className="field-hint">No fields yet — requesters only give a title and details.</p>}
        {drafts.map((draft, index) => (
          <FieldCard
            key={draft.uid}
            draft={draft}
            index={index}
            count={drafts.length}
            earlier={drafts.slice(0, index)}
            error={defErrors[draft.uid] ?? serverErrors.find((error) => error.field.startsWith(`fields[${index}]`))?.message}
            readOnly={!canEdit}
            onChange={(patch) => update(draft.uid, patch)}
            onMove={(direction) => move(index, direction)}
            onRemove={() => setDrafts((current) => current.filter((entry) => entry.uid !== draft.uid))}
          />
        ))}
        {canEdit && drafts.length < 40 && (
          <button type="button" className="subtle-btn compact" onClick={() => setDrafts((current) => [...current, toDraft({ key: `field${current.length + 1}`, label: '', type: 'text', required: false })])}>
            <ListPlus size={13} aria-hidden="true" /> Add field
          </button>
        )}
      </Panel>

      <div className="grid min-w-0 gap-[14px]">
        <Panel title="Live preview" subtitle="Exactly what requesters see, including conditional fields">
          {fields.length === 0 ? (
            <p className="m-0 text-[11px] text-muted">Add fields to preview them.</p>
          ) : (
            <form aria-label="Form preview" onSubmit={(event) => event.preventDefault()} noValidate>
              <DynamicFormFields fields={debouncedFields} values={previewValues} onChange={previewChange} errors={previewErrors} preview />
              <div className="flex flex-wrap items-center justify-end gap-2">
                {serverCheck && (
                  <span className={`mr-auto text-[11px] ${Object.keys(previewErrors).length ? 'red-text' : 'green-text'}`} role="status">
                    {serverCheck}
                  </span>
                )}
                <button type="button" className="subtle-btn compact" onClick={checkPreview} disabled={validate.isPending}>
                  <CheckCircle2 size={13} aria-hidden="true" /> {validate.isPending ? 'Checking…' : 'Check preview'}
                </button>
              </div>
            </form>
          )}
        </Panel>
        <Panel title="Versions">
          {versions.isLoading && <LoadingState rows={2} />}
          {versions.isError && <ErrorState error={versions.error} compact />}
          {versions.data && (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Form versions">
              {versions.data.map((version) => (
                <li key={version.version} className="list-row">
                  <button type="button" className="min-w-0 flex-1 border-0 bg-transparent p-0 text-left" onClick={() => setViewVersion(version.version === viewVersion ? null : version.version)} aria-expanded={viewVersion === version.version}>
                    <b className="flex items-center gap-1.5">
                      <History size={12} aria-hidden="true" /> Version {version.version}
                      {version.version === form.currentVersion && <ToneBadge value="current" label="Current" tone="gold" />}
                    </b>
                    <small>
                      {version.fieldCount} fields · {version.changeNote ?? 'No note'} · {version.createdByName ?? 'System'} · {formatRelative(version.createdAt)}
                    </small>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {viewVersion && versionDetail.data && (
            <div className="mt-2 rounded-[8px] border border-line p-2 text-[11px]">
              <b>Version {viewVersion} fields:</b> {versionDetail.data.fields.map((field) => `${field.label} (${field.type}${field.required ? ', required' : ''})`).join('; ') || 'none'}
              {canEdit && viewVersion !== form.currentVersion && (
                <div className="mt-1">
                  <button type="button" className="link-btn text-[11px]" onClick={() => setDrafts(versionDetail.data.fields.map((field) => toDraft(field as FormField)))}>
                    <Copy size={11} aria-hidden="true" /> Load these fields into the editor
                  </button>
                </div>
              )}
            </div>
          )}
        </Panel>
      </div>
    </div>
  );
}

/** Request form builder: versioned form definitions and templates (readers view, form editors build). */
export function FormBuilder({ canEdit }: { canEdit: boolean }) {
  const [search, setSearch] = useState('');
  const [kind, setKind] = useState<'all' | 'templates'>('all');
  const debounced = useDebounce(search, 300);
  const forms = useForms({ search: debounced.trim() || undefined, template: kind === 'templates' ? true : undefined });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const list = forms.data?.content ?? [];
  const activeId = selectedId ?? list[0]?.id ?? null;
  const detail = useFormDetail(activeId);

  return (
    <div className="grid items-start gap-[14px] xl:grid-cols-[300px_1fr]">
      <Panel
        title="Request forms"
        actions={
          canEdit ? (
            <button type="button" className="gold-btn compact" onClick={() => setCreating(true)}>
              <FilePlus2 size={13} aria-hidden="true" /> New form
            </button>
          ) : undefined
        }
      >
        <SearchInput value={search} onChange={setSearch} placeholder="Search forms…" label="Search forms" />
        <div className="my-2 flex gap-1" role="group" aria-label="Form kind">
          {(['all', 'templates'] as const).map((value) => (
            <button key={value} type="button" className={`chip ${kind === value ? 'border-gold text-gold-light' : ''}`} aria-pressed={kind === value} onClick={() => setKind(value)}>
              {value === 'all' ? 'All forms' : 'Templates'}
            </button>
          ))}
        </div>
        {forms.isLoading && <LoadingState rows={4} />}
        {forms.isError && <ErrorState error={forms.error} onRetry={() => void forms.refetch()} compact />}
        {forms.data && list.length === 0 && <EmptyState title="No forms" />}
        <ul className="m-0 grid max-h-[520px] list-none gap-1 overflow-auto p-0" aria-label="Forms">
          {list.map((form) => (
            <li key={form.id}>
              <button
                type="button"
                className={`list-row w-full border-0 text-left ${form.id === activeId ? 'rounded-lg bg-[#2a241c] px-2' : 'bg-transparent px-2'}`}
                aria-pressed={form.id === activeId}
                onClick={() => setSelectedId(form.id)}
              >
                <span className="min-w-0">
                  <b className="flex flex-wrap items-center gap-1.5">
                    <span className="truncate">{form.name}</span>
                    {form.template && <ToneBadge value="template" label="Template" tone="champagne" />}
                    {!form.active && <ToneBadge value="inactive" label="Inactive" tone="muted" />}
                    <DemoBadge demo={form.demo} />
                  </b>
                  <small className="block">
                    {form.code} · v{form.currentVersion} · {form.fieldCount} fields · {form.catalogItemCount} items
                  </small>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </Panel>
      <div className="min-w-0">
        {detail.isLoading && <LoadingState variant="panel" />}
        {detail.isError && <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />}
        {detail.data && <FormEditor key={`${detail.data.id}-${detail.data.currentVersion}-${detail.data.updatedAt}`} form={detail.data} canEdit={canEdit} />}
        {!activeId && forms.data && <EmptyState title="Choose or create a form" />}
      </div>
      {creating && (
        <NewFormModal
          onClose={() => setCreating(false)}
          onCreated={(form) => {
            setCreating(false);
            setSelectedId(form.id);
          }}
        />
      )}
    </div>
  );
}
