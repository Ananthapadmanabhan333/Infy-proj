import { useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { AlertTriangle, Pencil, Tags } from 'lucide-react';
import { useIncidentCategories, useUpdateIncidentCategories } from '../../api/admin';
import { useDepartments } from '../../api/users';
import { Badge } from '../../components/Badge';
import { Toggle } from '../../components/Controls';
import { DataTable } from '../../components/DataTable';
import { Drawer } from '../../components/Drawer';
import { CheckboxField, idOrNull, SelectField, TextareaField, TextField, textOrNull } from '../../components/FormField';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage } from '../../lib/api';
import { CONFIG_ADMINS } from '../../lib/constants';
import type { IncidentCategoryConfig, IncidentCategoryUpdate } from '../../lib/types';

/** PUT /admin/incident-categories always receives the FULL list (spec §13). */
export function toCategoryUpdates(categories: IncidentCategoryConfig[], replace?: IncidentCategoryUpdate): IncidentCategoryUpdate[] {
  return categories.map((category) => {
    const next = replace && replace.code === category.code ? replace : category;
    return {
      code: next.code,
      name: next.name,
      description: next.description,
      defaultDepartmentId: next.defaultDepartmentId,
      keywords: next.keywords,
      active: next.active,
    };
  });
}

/** "vpn, Wi-Fi ,  vpn" → ["vpn", "Wi-Fi"] (trimmed, de-duplicated case-insensitively). */
export function parseKeywords(text: string): string[] {
  const seen = new Set<string>();
  return text
    .split(/[,\n]/)
    .map((keyword) => keyword.trim())
    .filter((keyword) => {
      const key = keyword.toLowerCase();
      if (!keyword || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}

const categorySchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(80, 'At most 80 characters'),
  description: z.string().trim().max(500, 'At most 500 characters'),
  defaultDepartmentId: z.string(),
  keywords: z.string().max(2000, 'Too many keywords'),
  active: z.boolean(),
});
type CategoryValues = z.infer<typeof categorySchema>;

function CategoryDrawer({ category, all, onClose }: { category: IncidentCategoryConfig; all: IncidentCategoryConfig[]; onClose: () => void }) {
  const update = useUpdateIncidentCategories();
  const departments = useDepartments();
  const toast = useToast();
  const formId = useId();
  const {
    register,
    handleSubmit,
    formState: { errors, isDirty },
  } = useForm<CategoryValues>({
    resolver: zodResolver(categorySchema),
    defaultValues: {
      name: category.name,
      description: category.description ?? '',
      defaultDepartmentId: category.defaultDepartmentId ? String(category.defaultDepartmentId) : '',
      keywords: category.keywords.join(', '),
      active: category.active,
    },
  });

  const onSubmit = handleSubmit((values) =>
    update.mutate(
      toCategoryUpdates(all, {
        code: category.code,
        name: values.name.trim(),
        description: textOrNull(values.description),
        defaultDepartmentId: idOrNull(values.defaultDepartmentId),
        keywords: parseKeywords(values.keywords),
        active: values.active,
      }),
      {
        onSuccess: () => {
          toast.success(`${values.name.trim()} saved`);
          onClose();
        },
      },
    ),
  );

  return (
    <Drawer
      open
      onClose={() => !update.isPending && onClose()}
      label="INCIDENT CATEGORY"
      title={category.name}
      subtitle={`Code ${category.code} — codes are fixed; everything else is configurable.`}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={update.isPending}>
            Cancel
          </button>
          <button type="submit" form={formId} className="gold-btn" disabled={update.isPending || !isDirty}>
            {update.isPending ? 'Saving…' : 'Save category'}
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={onSubmit} noValidate>
        <TextField label="Display name" required error={errors.name?.message} {...register('name')} />
        <TextareaField label="Description" rows={3} error={errors.description?.message} {...register('description')} />
        <SelectField
          label="Default department"
          hint="Incidents in this category are routed here when the affected service has no owning team."
          error={errors.defaultDepartmentId?.message}
          {...register('defaultDepartmentId')}
        >
          <option value="">Service owner (no default)</option>
          {departments.data?.map((department) => (
            <option key={department.id} value={department.id}>
              {department.name}
            </option>
          ))}
        </SelectField>
        <TextareaField
          label="Extra classifier keywords"
          rows={3}
          placeholder="vpn, wi-fi, latency"
          hint="Comma separated. Used by the classifier in addition to its built-in signals."
          error={errors.keywords?.message}
          {...register('keywords')}
        />
        <CheckboxField label="Active (shown in category pickers)" {...register('active')} />
        {update.isError && (
          <div className="callout red mt-2" role="alert">
            <AlertTriangle size={16} />
            <div>{errorMessage(update.error)}</div>
          </div>
        )}
      </form>
    </Drawer>
  );
}

/** Settings → Incident Categories: table editor over the fixed Category codes. */
export function IncidentCategoriesTab() {
  const { hasRole } = useAuth();
  const canEdit = hasRole(...CONFIG_ADMINS);
  const categories = useIncidentCategories();
  const update = useUpdateIncidentCategories();
  const toast = useToast();
  const [editing, setEditing] = useState<IncidentCategoryConfig | null>(null);
  const all = categories.data ?? [];
  const activeCount = all.filter((category) => category.active).length;

  const setActive = (category: IncidentCategoryConfig, active: boolean) =>
    update.mutate(toCategoryUpdates(all, { ...category, active }), {
      onSuccess: () => toast.success(`${category.name} ${active ? 'activated' : 'hidden from pickers'}`),
      onError: (error) => toast.error(error),
    });

  return (
    <Panel
      title="Incident categories"
      subtitle={
        categories.data
          ? `${activeCount} of ${all.length} active. Inactive categories are hidden from pickers; keywords extend the classifier.`
          : 'Inactive categories are hidden from pickers; keywords extend the classifier.'
      }
    >
      {categories.isLoading && <LoadingState rows={6} label="Loading categories…" />}
      {categories.isError && <ErrorState error={categories.error} onRetry={() => void categories.refetch()} />}
      {categories.data && (
        <DataTable<IncidentCategoryConfig>
          caption="Incident categories"
          rows={all}
          rowKey={(row) => row.code}
          onRowClick={canEdit ? (row) => setEditing(row) : undefined}
          rowLabel={(row) => `Edit ${row.name}`}
          rowClassName={(row) => (row.active ? '' : 'opacity-70')}
          empty={<EmptyState title="No categories configured" icon={<Tags size={20} />} />}
          columns={[
            {
              key: 'name',
              header: 'Category',
              className: 'wrap',
              render: (row) => (
                <span className="block">
                  <b className="text-ivory">{row.name}</b> <small className="font-mono text-[9px] text-muted">{row.code}</small>
                  {row.description && <small className="block text-[9px] text-muted">{row.description}</small>}
                </span>
              ),
            },
            { key: 'department', header: 'Default department', render: (row) => row.defaultDepartmentName ?? <span className="muted-text">Service owner</span> },
            {
              key: 'keywords',
              header: 'Extra keywords',
              className: 'wrap',
              render: (row) =>
                row.keywords.length ? (
                  <span className="flex flex-wrap gap-1">
                    {row.keywords.slice(0, 6).map((keyword) => (
                      <span key={keyword} className="chip">
                        {keyword}
                      </span>
                    ))}
                    {row.keywords.length > 6 && <span className="chip">+{row.keywords.length - 6}</span>}
                  </span>
                ) : (
                  <span className="muted-text">Built-in signals only</span>
                ),
            },
            {
              key: 'active',
              header: 'Active',
              render: (row) =>
                canEdit ? (
                  <Toggle checked={row.active} label={`${row.name} active`} disabled={update.isPending} onChange={(next) => setActive(row, next)} />
                ) : (
                  <Badge value={row.active ? 'active' : 'inactive'} label={row.active ? 'Active' : 'Inactive'} />
                ),
            },
            ...(canEdit
              ? [
                  {
                    key: 'edit',
                    header: <span className="sr-only">Edit</span>,
                    render: (row: IncidentCategoryConfig) => (
                      <button
                        type="button"
                        className="icon-btn sm"
                        aria-label={`Edit category ${row.name}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          setEditing(row);
                        }}
                      >
                        <Pencil size={14} />
                      </button>
                    ),
                  },
                ]
              : []),
          ]}
        />
      )}
      {editing && <CategoryDrawer category={editing} all={all} onClose={() => setEditing(null)} />}
    </Panel>
  );
}
