import { useMemo, useState } from 'react';
import { FormBuilder } from '../../components/forms/FormBuilder';
import { Tabs } from '../../components/Tabs';
import { FolderPlus, LayoutGrid, Package, PackagePlus, Pencil } from 'lucide-react';
import { useCatalogCategories, useCatalogItems, useSaveCatalogItem } from '../../api/catalog';
import { Badge } from '../../components/Badge';
import { Toggle } from '../../components/Controls';
import { DataTable } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { CatalogueCategoryDrawer, CatalogueItemModal, itemBodyFrom } from '../../components/settings/CatalogueEditors';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { roleLabel } from '../../lib/constants';
import { formatNumber } from '../../lib/format';
import { iconByName } from '../../lib/icons';
import type { CatalogCategoryDto, CatalogItemDto } from '../../lib/types';

function CategoryList({ categories, selected, onSelect, onEdit }: { categories: CatalogCategoryDto[]; selected: string; onSelect: (id: string) => void; onEdit: (category: CatalogCategoryDto) => void }) {
  return (
    <div className="grid gap-1.5">
      {categories.map((category) => {
        const Icon = iconByName(category.icon);
        const active = selected === String(category.id);
        return (
          <div key={category.id} className={`list-row !py-2 ${active ? 'rounded-lg bg-[#2a241c] px-2' : 'px-2'}`}>
            <span className="icon-tile sm" aria-hidden="true">
              <Icon size={15} />
            </span>
            <button type="button" className="min-w-0 flex-1 border-0 bg-transparent p-0 text-left" aria-pressed={active} onClick={() => onSelect(active ? '' : String(category.id))}>
              <b className="truncate">{category.name}</b>
              <small>
                {category.code} · {formatNumber(category.itemCount)} {category.itemCount === 1 ? 'item' : 'items'}
              </small>
            </button>
            <button type="button" className="icon-btn sm" aria-label={`Edit category ${category.name}`} onClick={() => onEdit(category)}>
              <Pencil size={13} />
            </button>
          </div>
        );
      })}
    </div>
  );
}

function CatalogueItemsSection() {
  const categories = useCatalogCategories();
  const [categoryId, setCategoryId] = useState('');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<'' | 'active' | 'inactive'>('');
  const items = useCatalogItems({ categoryId: categoryId ? Number(categoryId) : null, includeInactive: true });
  const saveItem = useSaveCatalogItem();
  const toast = useToast();
  const [editingCategory, setEditingCategory] = useState<CatalogCategoryDto | 'new' | null>(null);
  const [editingItem, setEditingItem] = useState<CatalogItemDto | 'new' | null>(null);
  const [togglingId, setTogglingId] = useState<number | null>(null);

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return (items.data ?? [])
      .filter((item) => (status === 'active' ? item.active : status === 'inactive' ? !item.active : true))
      .filter((item) => !needle || [item.name, item.description, item.categoryName, item.fulfillmentDepartmentName].some((value) => value?.toLowerCase().includes(needle)));
  }, [items.data, search, status]);

  const setActive = (item: CatalogItemDto, active: boolean) => {
    setTogglingId(item.id);
    saveItem.mutate(
      { id: item.id, body: itemBodyFrom(item, { active }) },
      {
        onSuccess: () => toast.success(`${item.name} ${active ? 'is visible in the catalogue' : 'hidden from the catalogue'}`),
        onError: (error) => toast.error(error),
        onSettled: () => setTogglingId(null),
      },
    );
  };

  const categoryList = categories.data ?? [];

  return (
    <div className="grid items-start gap-[14px] xl:grid-cols-[320px_1fr]">
      <Panel
        title="Categories"
        subtitle="Group items on the Service Desk catalogue"
        actions={
          <button type="button" className="subtle-btn compact" onClick={() => setEditingCategory('new')}>
            <FolderPlus size={13} /> New
          </button>
        }
      >
        {categories.isLoading && <LoadingState rows={5} label="Loading categories…" />}
        {categories.isError && <ErrorState error={categories.error} onRetry={() => void categories.refetch()} compact />}
        {categories.data && categoryList.length === 0 && <EmptyState title="No categories yet" icon={<LayoutGrid size={20} />} />}
        {categoryList.length > 0 && <CategoryList categories={categoryList} selected={categoryId} onSelect={setCategoryId} onEdit={setEditingCategory} />}
      </Panel>
      <Panel
        title="Catalogue items"
        subtitle="Request forms, approval rules and fulfilment targets"
        actions={
          <button type="button" className="gold-btn compact" onClick={() => setEditingItem('new')} disabled={!categoryList.length}>
            <PackagePlus size={13} /> New item
          </button>
        }
      >
        <div className="toolbar">
          <SearchInput value={search} onChange={setSearch} placeholder="Search items…" label="Search catalogue items" />
          <FilterSelect
            label="Category"
            allLabel="All categories"
            value={categoryId}
            onChange={setCategoryId}
            options={categoryList.map((category) => ({ value: String(category.id), label: category.name }))}
          />
          <FilterSelect
            label="Status"
            allLabel="Any status"
            value={status}
            onChange={(value) => setStatus(value as '' | 'active' | 'inactive')}
            options={[
              { value: 'active', label: 'Active' },
              { value: 'inactive', label: 'Inactive' },
            ]}
          />
        </div>
        {items.isLoading && <LoadingState rows={6} label="Loading catalogue items…" />}
        {items.isError && <ErrorState error={items.error} onRetry={() => void items.refetch()} />}
        {items.data && (
          <DataTable<CatalogItemDto>
            caption="Catalogue items"
            rows={rows}
            rowKey={(row) => row.id}
            onRowClick={(row) => setEditingItem(row)}
            rowLabel={(row) => `Edit ${row.name}`}
            rowClassName={(row) => (row.active ? '' : 'opacity-70')}
            empty={search || status || categoryId ? 'No catalogue items match these filters.' : <EmptyState title="No catalogue items yet" icon={<Package size={20} />} />}
            columns={[
              {
                key: 'name',
                header: 'Item',
                className: 'wrap',
                render: (row) => (
                  <span className="block">
                    <b className="text-ivory">{row.name}</b>
                    {row.description && <small className="block line-clamp-1 text-[9px] text-muted">{row.description}</small>}
                  </span>
                ),
              },
              { key: 'category', header: 'Category', render: (row) => row.categoryName },
              {
                key: 'approval',
                header: 'Approval',
                render: (row) =>
                  row.requiresApproval ? <Badge value="pending-approval" label={row.approverRole ? roleLabel(row.approverRole) : 'Required'} /> : <span className="muted-text">None</span>,
              },
              {
                key: 'fulfilment',
                header: 'Fulfilment',
                render: (row) => (
                  <span className="block">
                    <span className="block">{row.fulfillmentDepartmentName}</span>
                    <small className="text-[9px] text-muted">within {formatNumber(row.fulfillmentHours)} h</small>
                  </span>
                ),
              },
              { key: 'fields', header: 'Form fields', render: (row) => formatNumber(row.formFields.length) },
              {
                key: 'active',
                header: 'Active',
                render: (row) => (
                  <Toggle checked={row.active} label={`${row.name} active`} disabled={togglingId === row.id} onChange={(next) => setActive(row, next)} />
                ),
              },
              {
                key: 'edit',
                header: <span className="sr-only">Edit</span>,
                render: (row) => (
                  <button
                    type="button"
                    className="icon-btn sm"
                    aria-label={`Edit item ${row.name}`}
                    onClick={(event) => {
                      event.stopPropagation();
                      setEditingItem(row);
                    }}
                  >
                    <Pencil size={14} />
                  </button>
                ),
              },
            ]}
          />
        )}
      </Panel>
      {editingCategory && <CatalogueCategoryDrawer category={editingCategory === 'new' ? null : editingCategory} onClose={() => setEditingCategory(null)} />}
      {editingItem && <CatalogueItemModal item={editingItem === 'new' ? null : editingItem} categories={categoryList} onClose={() => setEditingItem(null)} />}
    </div>
  );
}

/** Settings → Service Catalogue (CONFIG_ADMINS): categories, items (request types, permissions) and the request form builder. */
export function CatalogueTab() {
  const [section, setSection] = useState<'items' | 'forms'>('items');
  return (
    <div className="grid gap-[14px]">
      <Tabs<'items' | 'forms'>
        inner
        label="Service catalogue sections"
        value={section}
        onChange={setSection}
        tabs={[
          { id: 'items', label: 'Categories & request types' },
          { id: 'forms', label: 'Request forms' },
        ]}
      />
      {section === 'items' ? <CatalogueItemsSection /> : <FormBuilder canEdit />}
    </div>
  );
}
