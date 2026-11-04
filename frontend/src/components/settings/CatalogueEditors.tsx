import { useEffect, useId, useState } from 'react';
import { useFieldArray, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, ArrowDown, ArrowUp, ListPlus, Trash2 } from 'lucide-react';
import { useSaveCatalogCategory, useSaveCatalogItem, type CatalogItemBody } from '../../api/catalog';
import { useDepartments } from '../../api/users';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { ROLES, roleLabel } from '../../lib/constants';
import { FORM_FIELD_TYPES } from '../../lib/itsm';
import { useForms } from '../../api/forms';
import { CATALOG_ICON_NAMES, iconByName } from '../../lib/icons';
import { catalogCategorySchema, catalogItemSchema, type CatalogCategoryValues, type CatalogItemValues } from '../../lib/schemas';
import type { ApproverRole, CatalogCategoryDto, CatalogItemDto, FormField, Role } from '../../lib/types';
import { Drawer } from '../Drawer';
import { CheckboxField, idOrNull, SelectField, TextareaField, TextField, textOrNull } from '../FormField';
import { Modal } from '../Modal';
import { useToast } from '../Toast';

/** Service requests are approved by an IT Manager or a System Admin (spec §1 / §13). */
export const CATALOGUE_APPROVER_ROLES: ApproverRole[] = ['IT_MANAGER', 'SYSTEM_ADMIN'];

const FIELD_TYPES: { value: FormField['type']; label: string }[] = FORM_FIELD_TYPES;
const OWN_FORM = 'own';

/** Items linked to a shared form (not their own `ITEM-<id>` form) keep it; their fields are edited in the form builder. */
function initialFormSource(item: CatalogItemDto | null): string {
  if (item?.formDefinitionId && item.formCode && item.formCode !== `ITEM-${item.id}`) return String(item.formDefinitionId);
  return OWN_FORM;
}

/** Keeps the advanced properties (validation, showIf, accept, roles) the inline editor does not show. */
function mergeField(original: FormField | undefined, edited: FormField): FormField {
  if (!original || original.type !== edited.type) return edited;
  const advanced: Partial<FormField> = { ...original };
  delete advanced.options;
  delete advanced.placeholder;
  return { ...advanced, ...edited };
}

/** PUT/POST body from an existing item (used by the inline Active toggle). */
export function itemBodyFrom(item: CatalogItemDto, patch: Partial<CatalogItemBody> = {}): CatalogItemBody {
  return {
    categoryId: item.categoryId,
    name: item.name,
    description: item.description,
    requiresApproval: item.requiresApproval,
    approverRole: item.approverRole,
    fulfillmentHours: item.fulfillmentHours,
    fulfillmentDepartmentId: item.fulfillmentDepartmentId,
    formFields: item.formFields,
    active: item.active,
    formDefinitionId: item.formDefinitionId ?? null,
    allowedRoles: item.allowedRoles ?? [],
    allowedDepartmentIds: item.allowedDepartmentIds ?? [],
    ...patch,
  };
}

function FormError({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div className="callout red mt-2" role="alert">
      <AlertTriangle size={16} />
      <div>{message}</div>
    </div>
  );
}

export function CatalogueCategoryDrawer({ category, onClose }: { category: CatalogCategoryDto | null; onClose: () => void }) {
  const save = useSaveCatalogCategory();
  const toast = useToast();
  const formId = useId();
  const {
    register,
    handleSubmit,
    control,
    setError,
    formState: { errors },
  } = useForm<CatalogCategoryValues>({
    resolver: zodResolver(catalogCategorySchema),
    defaultValues: { code: category?.code ?? '', name: category?.name ?? '', description: category?.description ?? '', icon: category?.icon ?? '' },
  });
  const iconName = useWatch({ control, name: 'icon' });
  const Preview = iconByName(iconName);

  const onSubmit = handleSubmit((values) =>
    save.mutate(
      { id: category?.id, body: { code: values.code.trim(), name: values.name.trim(), description: textOrNull(values.description), icon: textOrNull(values.icon) } },
      {
        onSuccess: () => {
          toast.success(category ? 'Category updated' : 'Category created');
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  return (
    <Drawer
      open
      onClose={() => !save.isPending && onClose()}
      label={category ? 'EDIT CATALOGUE CATEGORY' : 'NEW CATALOGUE CATEGORY'}
      title={category ? category.name : 'Create category'}
      subtitle="Categories group catalogue items on the Service Desk."
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="submit" form={formId} className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save category'}
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={onSubmit} noValidate>
        <div className="form-row">
          <TextField label="Code" required placeholder="HARDWARE" error={errors.code?.message} {...register('code')} />
          <TextField label="Name" required error={errors.name?.message} {...register('name')} />
        </div>
        <TextareaField label="Description" rows={3} error={errors.description?.message} {...register('description')} />
        <div className="flex items-end gap-3">
          <SelectField label="Icon" className="flex-1" error={errors.icon?.message} {...register('icon')}>
            <option value="">Default</option>
            {CATALOG_ICON_NAMES.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </SelectField>
          <span className="icon-tile mb-[13px]" aria-hidden="true">
            <Preview size={18} />
          </span>
        </div>
        <FormError message={save.isError && !Object.keys(errors).length ? errorMessage(save.error) : null} />
      </form>
    </Drawer>
  );
}

export function CatalogueItemModal({ item, categories, onClose }: { item: CatalogItemDto | null; categories: CatalogCategoryDto[]; onClose: () => void }) {
  const save = useSaveCatalogItem();
  const departments = useDepartments();
  const forms = useForms({ active: true, template: false });
  const toast = useToast();
  const [formSource, setFormSource] = useState(() => initialFormSource(item));
  const [allowedRoles, setAllowedRoles] = useState<Role[]>(item?.allowedRoles ?? []);
  const [allowedDepartmentIds, setAllowedDepartmentIds] = useState<number[]>(item?.allowedDepartmentIds ?? []);
  const sharedForm = formSource === OWN_FORM ? null : (forms.data?.content ?? []).find((form) => String(form.id) === formSource) ?? null;
  const approverRoles = item?.approverRole && !CATALOGUE_APPROVER_ROLES.includes(item.approverRole) ? [item.approverRole, ...CATALOGUE_APPROVER_ROLES] : CATALOGUE_APPROVER_ROLES;
  const {
    register,
    handleSubmit,
    control,
    setError,
    setValue,
    formState: { errors },
  } = useForm<CatalogItemValues>({
    resolver: zodResolver(catalogItemSchema),
    defaultValues: {
      categoryId: item ? String(item.categoryId) : categories[0] ? String(categories[0].id) : '',
      name: item?.name ?? '',
      description: item?.description ?? '',
      requiresApproval: item?.requiresApproval ?? false,
      approverRole: item?.approverRole ?? '',
      fulfillmentHours: item?.fulfillmentHours ?? 24,
      fulfillmentDepartmentId: item ? String(item.fulfillmentDepartmentId) : '',
      active: item?.active ?? true,
      formFields: (item?.formFields ?? []).map((field) => ({
        key: field.key,
        label: field.label,
        type: field.type,
        required: field.required,
        options: (field.options ?? []).join(', '),
        placeholder: field.placeholder ?? '',
      })),
    },
  });
  const fields = useFieldArray({ control, name: 'formFields' });
  const requiresApproval = useWatch({ control, name: 'requiresApproval' });
  const approverRole = useWatch({ control, name: 'approverRole' });
  const fieldValues = useWatch({ control, name: 'formFields' });

  useEffect(() => {
    if (!requiresApproval) setValue('approverRole', '');
    else if (!approverRole) setValue('approverRole', 'IT_MANAGER');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requiresApproval]);

  const onSubmit = handleSubmit((values) =>
    save.mutate(
      {
        id: item?.id,
        body: {
          categoryId: Number(values.categoryId),
          name: values.name.trim(),
          description: textOrNull(values.description),
          requiresApproval: values.requiresApproval,
          approverRole: values.requiresApproval && values.approverRole ? values.approverRole : null,
          fulfillmentHours: values.fulfillmentHours,
          fulfillmentDepartmentId: idOrNull(values.fulfillmentDepartmentId) ?? 0,
          active: values.active,
          allowedRoles,
          allowedDepartmentIds,
          ...(formSource === OWN_FORM
            ? {
                formDefinitionId: null,
                formFields: values.formFields.map((field) =>
                  mergeField(item?.formFields.find((original) => original.key === field.key.trim()), {
                    key: field.key.trim(),
                    label: field.label.trim(),
                    type: field.type,
                    required: field.required,
                    options: field.type === 'select' || field.type === 'multiselect' ? field.options.split(',').map((option) => option.trim()).filter(Boolean) : undefined,
                    placeholder: field.placeholder.trim() || undefined,
                  }),
                ),
              }
            : { formDefinitionId: Number(formSource), formFields: item?.formFields ?? [] }),
        },
      },
      {
        onSuccess: () => {
          toast.success(item ? 'Catalogue item updated' : 'Catalogue item created');
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  return (
    <Modal open onClose={onClose} title={item ? `Edit ${item.name}` : 'New catalogue item'} eyebrow="SERVICE CATALOGUE" size="xl" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <div className="form-row">
          <TextField label="Name" required error={errors.name?.message} {...register('name')} />
          <SelectField label="Category" required error={errors.categoryId?.message} {...register('categoryId')}>
            <option value="">Choose a category</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </SelectField>
        </div>
        <TextareaField label="Description" rows={3} error={errors.description?.message} {...register('description')} />

        <div className="form-section-title">Fulfilment</div>
        <div className="form-row">
          <SelectField label="Fulfilment department" required error={errors.fulfillmentDepartmentId?.message} {...register('fulfillmentDepartmentId')}>
            <option value="">Choose a department</option>
            {departments.data?.map((department) => (
              <option key={department.id} value={department.id}>
                {department.name}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Fulfilment target (hours)"
            type="number"
            min={1}
            required
            error={errors.fulfillmentHours?.message}
            {...register('fulfillmentHours', { valueAsNumber: true })}
          />
        </div>

        <div className="form-section-title">Approval rule</div>
        <div className="form-row">
          <CheckboxField label="Requires approval before fulfilment" {...register('requiresApproval')} />
          <SelectField
            label="Approver"
            error={errors.approverRole?.message}
            disabled={!requiresApproval}
            hint={requiresApproval ? 'Requests wait in the approver’s queue until decided.' : 'Requests go straight to fulfilment.'}
            {...register('approverRole')}
          >
            <option value="">No approval</option>
            {approverRoles.map((role) => (
              <option key={role} value={role}>
                {roleLabel(role)}
              </option>
            ))}
          </SelectField>
        </div>
        <CheckboxField label="Active (visible in the catalogue)" {...register('active')} />

        <div className="form-section-title">Who may submit this request type</div>
        <p className="field-hint">Nothing ticked = everyone. Administrators can always submit.</p>
        <div className="grid gap-3 md:grid-cols-2">
          <fieldset className="m-0 border-0 p-0" aria-label="Allowed roles">
            <small className="text-muted">Roles</small>
            <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1">
              {ROLES.map((role) => (
                <label key={role} className="inline-flex items-center gap-1.5 text-[11px]">
                  <input type="checkbox" checked={allowedRoles.includes(role)} onChange={(event) => setAllowedRoles((current) => (event.target.checked ? [...current, role] : current.filter((entry) => entry !== role)))} />
                  {roleLabel(role)}
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset className="m-0 border-0 p-0" aria-label="Allowed departments">
            <small className="text-muted">Departments</small>
            <div className="mt-1 flex max-h-28 flex-wrap gap-x-3 gap-y-1 overflow-auto">
              {(departments.data ?? []).map((department) => (
                <label key={department.id} className="inline-flex items-center gap-1.5 text-[11px]">
                  <input
                    type="checkbox"
                    checked={allowedDepartmentIds.includes(department.id)}
                    onChange={(event) => setAllowedDepartmentIds((current) => (event.target.checked ? [...current, department.id] : current.filter((entry) => entry !== department.id)))}
                  />
                  {department.name}
                </label>
              ))}
            </div>
          </fieldset>
        </div>

        <div className="form-section-title">Request form</div>
        <SelectField
          label="Form definition"
          value={formSource}
          onChange={(event) => setFormSource(event.target.value)}
          hint={
            sharedForm
              ? `${sharedForm.name} · v${sharedForm.currentVersion} · ${sharedForm.fieldCount} fields — edit its fields under Request forms.`
              : item?.formVersion
                ? `This item's own form (v${item.formVersion}); saving changed fields creates a new version.`
                : "The fields below are kept as this item's own versioned form."
          }
        >
          <option value={OWN_FORM}>Fields defined on this item</option>
          {(forms.data?.content ?? [])
            .filter((form) => !item || form.code !== `ITEM-${item.id}`)
            .map((form) => (
              <option key={form.id} value={form.id}>
                {form.name} ({form.code})
              </option>
            ))}
        </SelectField>
        {formSource === OWN_FORM && errors.formFields?.message && <p className="field-error">{errors.formFields.message}</p>}
        {formSource === OWN_FORM && fields.fields.length === 0 && <p className="field-hint">No extra fields — requesters only provide a title and details.</p>}
        {formSource === OWN_FORM && fields.fields.map((field, index) => {
          const fieldErrors = errors.formFields?.[index];
          return (
            <div key={field.id} className="action-card" aria-label={`Form field ${index + 1}`} role="group">
              <div className="form-row three">
                <TextField label="Key" required error={fieldErrors?.key?.message} {...register(`formFields.${index}.key`)} />
                <TextField label="Label" required error={fieldErrors?.label?.message} {...register(`formFields.${index}.label`)} />
                <SelectField label="Type" {...register(`formFields.${index}.type`)}>
                  {FIELD_TYPES.map((type) => (
                    <option key={type.value} value={type.value}>
                      {type.label}
                    </option>
                  ))}
                </SelectField>
              </div>
              <div className="form-row">
                {fieldValues?.[index]?.type === 'select' || fieldValues?.[index]?.type === 'multiselect' ? (
                  <TextField label="Options (comma separated)" required error={fieldErrors?.options?.message} {...register(`formFields.${index}.options`)} />
                ) : (
                  <TextField label="Placeholder" error={fieldErrors?.placeholder?.message} {...register(`formFields.${index}.placeholder`)} />
                )}
                <div className="flex items-end justify-between gap-2">
                  <CheckboxField label="Required" {...register(`formFields.${index}.required`)} />
                  <div className="mb-3 flex gap-1">
                    <button type="button" className="icon-btn sm" aria-label={`Move field ${index + 1} up`} disabled={index === 0} onClick={() => fields.move(index, index - 1)}>
                      <ArrowUp size={14} />
                    </button>
                    <button
                      type="button"
                      className="icon-btn sm"
                      aria-label={`Move field ${index + 1} down`}
                      disabled={index === fields.fields.length - 1}
                      onClick={() => fields.move(index, index + 1)}
                    >
                      <ArrowDown size={14} />
                    </button>
                    <button type="button" className="icon-btn sm" aria-label={`Remove field ${index + 1}`} onClick={() => fields.remove(index)}>
                      <Trash2 size={14} />
                    </button>
                  </div>
                </div>
              </div>
            </div>
          );
        })}
        {formSource === OWN_FORM && (
          <button type="button" className="subtle-btn compact" onClick={() => fields.append({ key: '', label: '', type: 'text', required: false, options: '', placeholder: '' })}>
            <ListPlus size={13} /> Add field
          </button>
        )}
        <FormError message={save.isError && !Object.keys(errors).length ? errorMessage(save.error) : null} />
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save item'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
