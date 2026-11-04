import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useSaveCatalogCategory } from '../../api/catalog';
import { CatalogueItemModal } from '../../components/settings/CatalogueEditors';
import { SelectField, TextareaField, TextField, textOrNull } from '../../components/FormField';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { CATALOG_ICON_NAMES, iconByName } from '../../lib/icons';
import { catalogCategorySchema, type CatalogCategoryValues } from '../../lib/schemas';
import type { CatalogCategoryDto, CatalogItemDto } from '../../lib/types';

export function CategoryEditor({ category, onClose }: { category: CatalogCategoryDto | null; onClose: () => void }) {
  const save = useSaveCatalogCategory();
  const toast = useToast();
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
      { id: category?.id, body: { code: values.code, name: values.name.trim(), description: textOrNull(values.description), icon: textOrNull(values.icon) } },
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
    <Modal open onClose={onClose} title={category ? `Edit ${category.name}` : 'New catalogue category'} eyebrow="SERVICE CATALOGUE" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <div className="form-row">
          <TextField label="Code" required error={errors.code?.message} {...register('code')} />
          <TextField label="Name" required error={errors.name?.message} {...register('name')} />
        </div>
        <TextareaField label="Description" error={errors.description?.message} {...register('description')} />
        <div className="flex items-end gap-3">
          <SelectField label="Icon" className="flex-1" error={errors.icon?.message} {...register('icon')}>
            <option value="">Default</option>
            {CATALOG_ICON_NAMES.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </SelectField>
          <span className="quick-icon mb-[13px]" aria-hidden="true">
            <Preview />
          </span>
        </div>
        {save.isError && !Object.keys(errors).length && <p className="field-error">{errorMessage(save.error)}</p>}
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save category'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** Catalogue item editor — the same editor as Settings → Service Catalogue (form definition, permissions, fields). */
export function ItemEditor({ item, categories, onClose }: { item: CatalogItemDto | null; categories: CatalogCategoryDto[]; onClose: () => void }) {
  return <CatalogueItemModal item={item} categories={categories} onClose={onClose} />;
}
