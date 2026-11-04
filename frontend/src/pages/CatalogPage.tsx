import { useMemo, useState } from 'react';
import { Clock3, FolderPlus, LayoutGrid, Pencil, Plus, Search, ShieldCheck } from 'lucide-react';
import { useCatalogCategories, useCatalogItems } from '../api/catalog';
import { Badge } from '../components/Badge';
import { PageHeading } from '../components/PageHeading';
import { PanelTitle } from '../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../components/States';
import { useAuth } from '../hooks/useAuth';
import { useSearchInput, useTabParam, useUrlFilters } from '../hooks/useUrlState';
import { FormBuilder, FORM_EDITORS } from '../components/forms/FormBuilder';
import { Tabs } from '../components/Tabs';
import { iconByName } from '../lib/icons';
import type { CatalogCategoryDto, CatalogItemDto } from '../lib/types';
import { CategoryEditor, ItemEditor } from './catalog/CatalogEditors';
import { NewRequestModal } from '../components/servicedesk/NewRequestModal';
import { CONFIG_ADMINS, roleLabel } from '../lib/constants';

/**
 * `/catalog` — the Service Catalogue page (RELEASE3_SPEC §3): categories and items to request from, with their
 * administration for configuration admins. The Service Desk "Service Catalog" tab links here.
 */
export default function CatalogPage({ embedded = false }: { embedded?: boolean }) {
  const { user, hasRole } = useAuth();
  const admin = hasRole(...CONFIG_ADMINS);
  const formEditor = !embedded && hasRole(...FORM_EDITORS);
  const [view, setView] = useTabParam<'items' | 'forms'>(formEditor ? ['items', 'forms'] : ['items'], 'items');
  // Auditors have read-only access (the API rejects their requests too).
  const canRequest = user?.role !== 'AUDITOR';
  const { values, setValues } = useUrlFilters(['category', 'search'] as const);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const categories = useCatalogCategories();
  const categoryId = values.category ? Number(values.category) : null;
  const items = useCatalogItems({ categoryId, search: values.search || undefined, includeInactive: admin });
  const [requesting, setRequesting] = useState<CatalogItemDto | null>(null);
  const [editingItem, setEditingItem] = useState<CatalogItemDto | null | 'new'>(null);
  const [editingCategory, setEditingCategory] = useState<CatalogCategoryDto | null | 'new'>(null);

  const selectedCategory = useMemo(() => categories.data?.find((category) => category.id === categoryId) ?? null, [categories.data, categoryId]);

  const adminActions = admin && (
    <>
      <button type="button" className="date-btn" onClick={() => setEditingCategory('new')}>
        <FolderPlus size={15} /> New category
      </button>
      <button type="button" className={embedded ? 'subtle-btn' : 'gold-btn'} onClick={() => setEditingItem('new')} disabled={!categories.data?.length}>
        <Plus size={16} /> New item
      </button>
    </>
  );

  return (
    <>
      {embedded ? adminActions && <div className="flex flex-wrap justify-end gap-2">{adminActions}</div> : <PageHeading page="Service Catalogue" actions={view === 'items' ? adminActions : undefined} />}
      {formEditor && (
        <div className="mb-[14px]">
          <Tabs<'items' | 'forms'>
            label="Catalogue views"
            value={view}
            onChange={setView}
            tabs={[
              { id: 'items', label: 'Catalogue' },
              { id: 'forms', label: 'Request forms' },
            ]}
          />
        </div>
      )}
      {view === 'forms' ? (
        <FormBuilder canEdit />
      ) : (
      <div className="page-stack">
        {categories.isLoading && <LoadingState variant="cards" rows={6} />}
        {categories.isError && <ErrorState error={categories.error} onRetry={() => void categories.refetch()} />}
        {categories.data && categories.data.length === 0 && <EmptyState title="The catalogue is empty" description={admin ? 'Create a category to get started.' : 'No services can be requested yet.'} />}
        {categories.data && categories.data.length > 0 && (
          <div className="service-catalog" role="list" aria-label="Catalogue categories">
            {categories.data.map((category) => {
              const Icon = iconByName(category.icon);
              const selected = category.id === categoryId;
              return (
                <div key={category.id} role="listitem" className="relative">
                  <button
                    type="button"
                    className={`panel catalog-item w-full ${selected ? 'selected' : ''}`}
                    aria-pressed={selected}
                    onClick={() => setValues({ category: selected ? '' : String(category.id) })}
                  >
                    <Icon size={22} aria-hidden="true" />
                    <b>{category.name}</b>
                    <small>{category.description ?? `${category.itemCount} services available`}</small>
                    <small className="gold-text">{category.itemCount} items</small>
                  </button>
                  {admin && (
                    <button type="button" className="icon-btn sm absolute top-3 right-3" aria-label={`Edit category ${category.name}`} onClick={() => setEditingCategory(category)}>
                      <Pencil size={13} />
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        )}

        <section className="panel" aria-labelledby="items-title">
          <PanelTitle id="items-title" title={selectedCategory ? selectedCategory.name : 'All catalogue items'} subtitle="Choose an item to open its request form">
            {selectedCategory && (
              <button type="button" className="panel-action" onClick={() => setValues({ category: '' })}>
                Show all
              </button>
            )}
          </PanelTitle>
          <div className="knowledge-search mb-3">
            <Search size={18} aria-hidden="true" />
            <input type="search" placeholder="Search catalogue items…" aria-label="Search catalogue items" value={searchInput} onChange={(event) => setSearchInput(event.target.value)} />
          </div>
          {items.isLoading && <LoadingState variant="cards" rows={4} />}
          {items.isError && <ErrorState error={items.error} onRetry={() => void items.refetch()} />}
          {items.data && items.data.length === 0 && <EmptyState title="No matching items" icon={<LayoutGrid size={20} />} />}
          <div className="knowledge-grid">
            {items.data?.map((item) => (
              <article key={item.id} className={`panel raised flex flex-col gap-2 ${item.active ? '' : 'opacity-60'}`} aria-label={item.name}>
                <div className="flex items-start gap-2">
                  <div className="min-w-0 flex-1">
                    <small className="text-[9px] text-gold">{item.categoryName}</small>
                    <h3 className="my-1 text-[13px]">{item.name}</h3>
                    <p className="text-[10px] leading-relaxed text-muted">{item.description ?? 'No description.'}</p>
                  </div>
                  {!item.active && <Badge value="inactive" />}
                </div>
                <div className="flex flex-wrap gap-2">
                  <span className="chip">
                    <Clock3 size={11} /> ~{item.fulfillmentHours} h · {item.fulfillmentDepartmentName}
                  </span>
                  <span className="chip">
                    <ShieldCheck size={11} /> {item.requiresApproval ? `${roleLabel(item.approverRole ?? 'IT_MANAGER')} approval` : 'No approval'}
                  </span>
                  {item.formFields.length > 0 && <span className="chip">{item.formFields.length} form fields</span>}
                </div>
                <div className="mt-auto flex justify-end gap-2">
                  {admin && (
                    <button type="button" className="subtle-btn compact" onClick={() => setEditingItem(item)} aria-label={`Edit ${item.name}`}>
                      <Pencil size={13} /> Edit
                    </button>
                  )}
                  {canRequest && (
                    <button type="button" className="gold-btn compact" disabled={!item.active} onClick={() => setRequesting(item)} aria-label={`Request ${item.name}`}>
                      Request
                    </button>
                  )}
                </div>
              </article>
            ))}
          </div>
        </section>
      </div>
      )}

      {requesting && <NewRequestModal initialItem={requesting} onClose={() => setRequesting(null)} />}
      {editingItem && categories.data && (
        <ItemEditor item={editingItem === 'new' ? null : editingItem} categories={categories.data} onClose={() => setEditingItem(null)} />
      )}
      {editingCategory && <CategoryEditor category={editingCategory === 'new' ? null : editingCategory} onClose={() => setEditingCategory(null)} />}
    </>
  );
}
