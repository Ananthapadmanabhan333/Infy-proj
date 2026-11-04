import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Clock3, LayoutGrid, Settings2, ShieldCheck } from 'lucide-react';
import { useCatalogCategories, useCatalogItems } from '../../api/catalog';
import { Panel } from '../Panel';
import { SearchInput } from '../SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useAuth } from '../../hooks/useAuth';
import { useSearchInput } from '../../hooks/useUrlState';
import { CONFIG_ADMINS, roleLabel } from '../../lib/constants';
import { formatNumber } from '../../lib/format';
import type { CatalogItemDto } from '../../lib/types';
import { categoryIcon } from './requestModel';

interface CatalogBrowserProps {
  /** Selected category id ('' = all) — `?category=` keeps `/catalog?category=3` links working. */
  categoryId: string;
  search: string;
  onChange: (patch: { category?: string; search?: string }) => void;
  /** Omitted for read-only users (auditors): items are listed without a Request button. */
  onRequest?: (item: CatalogItemDto) => void;
}

/** Service Catalog tab: category tiles and the requestable items; "Request" opens the item's dynamic form. */
export function CatalogBrowser({ categoryId, search, onChange, onRequest }: CatalogBrowserProps) {
  const { hasRole } = useAuth();
  const configAdmin = hasRole(...CONFIG_ADMINS);
  const categories = useCatalogCategories();
  const selectedId = categoryId ? Number(categoryId) : null;
  const items = useCatalogItems({ categoryId: selectedId, search: search || undefined });
  const [searchInput, setSearchInput] = useSearchInput(search, (value) => onChange({ search: value }));
  const selected = useMemo(() => categories.data?.find((category) => category.id === selectedId) ?? null, [categories.data, selectedId]);
  const activeItems = (items.data ?? []).filter((item) => item.active);

  return (
    <div className="page-stack">
      {categories.isLoading && <LoadingState variant="cards" rows={6} label="Loading catalogue…" />}
      {categories.isError && <ErrorState error={categories.error} onRetry={() => void categories.refetch()} />}
      {categories.data && categories.data.length === 0 && (
        <EmptyState
          title="The catalogue is empty"
          description={configAdmin ? 'Add categories and items under Settings → Service Catalogue.' : 'No services can be requested yet.'}
          icon={<LayoutGrid size={20} />}
          action={
            configAdmin ? (
              <Link to="/settings?tab=catalogue" className="subtle-btn compact">
                <Settings2 size={13} aria-hidden="true" /> Manage catalogue
              </Link>
            ) : undefined
          }
        />
      )}
      {categories.data && categories.data.length > 0 && (
        <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="Catalogue categories">
          {categories.data.map((category) => {
            const Icon = categoryIcon(category.code, category.icon);
            const isSelected = category.id === selectedId;
            return (
              <li key={category.id}>
                <button
                  type="button"
                  className={`flex h-full w-full items-start gap-3 rounded-[12px] border bg-panel p-3.5 text-left transition-colors hover:border-bronze ${isSelected ? 'border-gold bg-panel-raised' : 'border-line'}`}
                  aria-pressed={isSelected}
                  onClick={() => onChange({ category: isSelected ? '' : String(category.id) })}
                >
                  <span className="icon-tile" aria-hidden="true">
                    <Icon size={18} />
                  </span>
                  <span className="min-w-0 flex-1">
                    <b className="block text-[12px] text-ivory">{category.name}</b>
                    {category.description && <small className="mt-0.5 line-clamp-2 block text-[10px] leading-relaxed text-muted">{category.description}</small>}
                    <small className="mt-1 block text-[10px] font-semibold text-gold">
                      {formatNumber(category.itemCount)} {category.itemCount === 1 ? 'item' : 'items'}
                    </small>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <Panel
        title={selected ? selected.name : 'All catalogue items'}
        subtitle={onRequest ? 'Choose an item to open its request form' : 'Requestable services and their fulfilment terms'}
        actions={
          <>
            {selected && (
              <button type="button" className="panel-action" onClick={() => onChange({ category: '' })}>
                Show all
              </button>
            )}
            {configAdmin && (
              <Link to="/settings?tab=catalogue" className="panel-action">
                <Settings2 size={13} aria-hidden="true" /> Manage catalogue
              </Link>
            )}
          </>
        }
      >
        <div className="toolbar">
          <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search catalogue items…" label="Search catalogue items" />
        </div>
        {items.isLoading && <LoadingState variant="cards" rows={3} label="Loading items…" />}
        {items.isError && <ErrorState error={items.error} onRetry={() => void items.refetch()} />}
        {items.data && activeItems.length === 0 && <EmptyState title="No matching items" description="Try another search or category." icon={<LayoutGrid size={20} />} />}
        {activeItems.length > 0 && (
          <ul className="grid gap-3 md:grid-cols-2 2xl:grid-cols-3" aria-label="Catalogue items">
            {activeItems.map((item) => {
              const category = categories.data?.find((entry) => entry.id === item.categoryId);
              const Icon = categoryIcon(category?.code, category?.icon);
              return (
                <li key={item.id}>
                  <article className="flex h-full flex-col gap-2.5 rounded-[12px] border border-line bg-panel-raised/60 p-3.5" aria-label={item.name}>
                    <div className="flex items-start gap-3">
                      <span className="icon-tile" aria-hidden="true">
                        <Icon size={17} />
                      </span>
                      <div className="min-w-0 flex-1">
                        <small className="text-[9px] font-semibold tracking-wide text-gold uppercase">{item.categoryName}</small>
                        <h3 className="my-0.5 text-[13px] text-ivory">{item.name}</h3>
                        <p className="text-[10px] leading-relaxed text-muted">{item.description ?? 'No description.'}</p>
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      <span className="chip">
                        <Clock3 size={11} aria-hidden="true" /> ~{item.fulfillmentHours} h · {item.fulfillmentDepartmentName}
                      </span>
                      <span className="chip">
                        <ShieldCheck size={11} aria-hidden="true" /> {item.requiresApproval ? `${roleLabel(item.approverRole ?? 'IT_MANAGER')} approval` : 'No approval'}
                      </span>
                      {item.formFields.length > 0 && <span className="chip">{item.formFields.length} form fields</span>}
                    </div>
                    {onRequest && (
                      <div className="mt-auto flex justify-end">
                        <button type="button" className="gold-btn compact" onClick={() => onRequest(item)} aria-label={`Request ${item.name}`}>
                          Request
                        </button>
                      </div>
                    )}
                  </article>
                </li>
              );
            })}
          </ul>
        )}
      </Panel>
    </div>
  );
}
