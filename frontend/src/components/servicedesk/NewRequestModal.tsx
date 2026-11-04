import { useState } from 'react';
import { ChevronRight, LayoutGrid, ShieldCheck } from 'lucide-react';
import { useCatalogCategories, useCatalogItems } from '../../api/catalog';
import { Modal } from '../Modal';
import { SearchInput } from '../SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useDebounce } from '../../hooks/useDebounce';
import type { CatalogItemDto, ServiceRequestDto } from '../../lib/types';
import { MODAL_SEARCH_CLASS, categoryIcon } from './requestModel';
import { RequestForm } from './RequestForm';

interface NewRequestModalProps {
  onClose: () => void;
  onCreated?: (request: ServiceRequestDto) => void;
  /** Skips the picker (e.g. "Request" on a catalogue card). */
  initialItem?: CatalogItemDto | null;
}

/** "+ New Request": pick a catalogue item (search + category), then fill its dynamic request form. */
export function NewRequestModal({ onClose, onCreated, initialItem = null }: NewRequestModalProps) {
  const [item, setItem] = useState<CatalogItemDto | null>(initialItem);
  const [search, setSearch] = useState('');
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const debouncedSearch = useDebounce(search, 250);
  const categories = useCatalogCategories();
  const items = useCatalogItems({ categoryId, search: debouncedSearch.trim() || undefined }, item === null);
  const activeItems = (items.data ?? []).filter((candidate) => candidate.active);
  const iconFor = (candidate: CatalogItemDto) => {
    const category = categories.data?.find((entry) => entry.id === candidate.categoryId);
    return categoryIcon(category?.code, category?.icon);
  };

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      eyebrow={item ? `NEW REQUEST · ${item.categoryName.toUpperCase()}` : 'NEW REQUEST'}
      title={item ? item.name : 'What do you need?'}
      description={item ? item.description ?? undefined : 'Choose a service from the catalogue. Its request form opens next.'}
    >
      {item ? (
        <RequestForm
          key={item.id}
          item={item}
          onCancel={onClose}
          onBack={initialItem ? undefined : () => setItem(null)}
          onCreated={(request) => {
            onCreated?.(request);
            onClose();
          }}
        />
      ) : (
        <div className="grid gap-3">
          <SearchInput value={search} onChange={setSearch} placeholder="Search the catalogue…" label="Search catalogue items" className={MODAL_SEARCH_CLASS} autoFocus />
          {categories.data && categories.data.length > 0 && (
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter by category">
              <button type="button" className={`chip ${categoryId === null ? 'border-gold text-gold-light' : ''}`} aria-pressed={categoryId === null} onClick={() => setCategoryId(null)}>
                All
              </button>
              {categories.data.map((category) => {
                const Icon = categoryIcon(category.code, category.icon);
                const selected = category.id === categoryId;
                return (
                  <button
                    key={category.id}
                    type="button"
                    className={`chip ${selected ? 'border-gold text-gold-light' : ''}`}
                    aria-pressed={selected}
                    onClick={() => setCategoryId(selected ? null : category.id)}
                  >
                    <Icon size={11} aria-hidden="true" /> {category.name}
                  </button>
                );
              })}
            </div>
          )}
          {categories.isError && <ErrorState error={categories.error} onRetry={() => void categories.refetch()} compact />}
          {items.isLoading && <LoadingState rows={4} label="Loading catalogue items…" />}
          {items.isError && <ErrorState error={items.error} onRetry={() => void items.refetch()} compact />}
          {items.data && activeItems.length === 0 && (
            <EmptyState title="No matching catalogue items" description="Try another search or category." icon={<LayoutGrid size={20} />} />
          )}
          {activeItems.length > 0 && (
            <ul className="grid max-h-[46vh] gap-1.5 overflow-y-auto pr-1" aria-label="Catalogue items">
              {activeItems.map((candidate) => {
                const Icon = iconFor(candidate);
                return (
                  <li key={candidate.id}>
                    <button
                      type="button"
                      className="flex w-full items-center gap-3 rounded-[10px] border border-line bg-panel-raised/60 px-3 py-2.5 text-left hover:border-bronze"
                      onClick={() => setItem(candidate)}
                      aria-label={`Choose ${candidate.name}`}
                    >
                      <span className="icon-tile" aria-hidden="true">
                        <Icon size={17} />
                      </span>
                      <span className="min-w-0 flex-1">
                        <b className="block truncate text-[12px] text-ivory">{candidate.name}</b>
                        <small className="block truncate text-[10px] text-muted">
                          {candidate.categoryName} · ~{candidate.fulfillmentHours} h{candidate.description ? ` · ${candidate.description}` : ''}
                        </small>
                      </span>
                      {candidate.requiresApproval && (
                        <span className="chip hidden sm:inline-flex" title="Needs approval before fulfilment">
                          <ShieldCheck size={11} aria-hidden="true" /> Approval
                        </span>
                      )}
                      <ChevronRight size={15} className="text-gold" aria-hidden="true" />
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
          <div className="modal-foot">
            <button type="button" className="subtle-btn" onClick={onClose}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </Modal>
  );
}
