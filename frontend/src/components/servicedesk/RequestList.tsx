import { useMemo, type ReactNode } from 'react';
import { ClipboardList, FilterX } from 'lucide-react';
import { useCatalogCategories } from '../../api/catalog';
import { useServiceRequests, type ServiceRequestScope } from '../../api/serviceDesk';
import { FilterSelect } from '../FilterSelect';
import { Pagination } from '../Pagination';
import { SearchInput } from '../SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useSearchInput } from '../../hooks/useUrlState';
import { DEFAULT_PAGE_SIZE, PRIORITIES, PRIORITY_LABELS, REQUEST_STATUSES, REQUEST_STATUS_LABELS } from '../../lib/constants';
import type { Priority, ServiceRequestDto, ServiceRequestStatus } from '../../lib/types';
import { RequestCard } from './RequestCard';
import { FILTER_CLASS, FILTER_ROW_CLASS, categoryOptions } from './requestModel';

export interface RequestFilters {
  search: string;
  category: string;
  priority: string;
  status: string;
  /** Staff queue only: '' = all teams, 'department' = my team. */
  team: string;
}

interface RequestListProps {
  /** 'queue' = staff request queue (all or my team); 'mine' = requests raised by the current user. */
  mode: 'queue' | 'mine';
  filters: RequestFilters;
  onFiltersChange: (patch: Partial<RequestFilters>) => void;
  page: number;
  onPageChange: (page: number) => void;
  onOpen: (request: ServiceRequestDto) => void;
  /** Shown in the empty state of "My Requests" (e.g. a "New Request" button). */
  emptyAction?: ReactNode;
}

/** Search + Category / Priority / Status filters and the request cards, paged server-side. */
export function RequestList({ mode, filters, onFiltersChange, page, onPageChange, onOpen, emptyAction }: RequestListProps) {
  const categories = useCatalogCategories();
  const scope: ServiceRequestScope = mode === 'mine' ? 'mine' : filters.team === 'department' ? 'department' : 'all';
  const requests = useServiceRequests({
    scope,
    search: filters.search || undefined,
    category: filters.category || undefined,
    priority: filters.priority || undefined,
    status: filters.status || undefined,
    page,
    size: DEFAULT_PAGE_SIZE,
  });
  const [searchInput, setSearchInput] = useSearchInput(filters.search, (search) => onFiltersChange({ search }));
  const filtered = Boolean(filters.search || filters.category || filters.priority || filters.status || filters.team);
  const iconByCode = useMemo(() => new Map((categories.data ?? []).map((category) => [category.code, category.icon])), [categories.data]);

  return (
    <>
      <div className="toolbar">
        <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search requests…" label="Search requests" />
        <div className={FILTER_ROW_CLASS}>
          <FilterSelect className={FILTER_CLASS} label="Category" allLabel="Category" value={filters.category} onChange={(category) => onFiltersChange({ category })} options={categoryOptions(categories.data)} />
          <FilterSelect
            className={FILTER_CLASS}
            label="Priority"
            value={filters.priority}
            onChange={(priority) => onFiltersChange({ priority })}
            options={PRIORITIES}
            formatOption={(value) => PRIORITY_LABELS[value as Priority] ?? value}
          />
          <FilterSelect
            className={FILTER_CLASS}
            label="Status"
            value={filters.status}
            onChange={(status) => onFiltersChange({ status })}
            options={REQUEST_STATUSES}
            formatOption={(value) => REQUEST_STATUS_LABELS[value as ServiceRequestStatus] ?? value}
          />
          {mode === 'queue' && (
            <FilterSelect className={FILTER_CLASS} label="Team" allLabel="All teams" value={filters.team} onChange={(team) => onFiltersChange({ team })} options={[{ value: 'department', label: 'My team' }]} />
          )}
        </div>
      </div>

      {requests.isLoading && <LoadingState rows={5} label="Loading requests…" />}
      {requests.isError && !requests.data && <ErrorState error={requests.error} onRetry={() => void requests.refetch()} />}
      {requests.data && requests.data.content.length === 0 && (
        <EmptyState
          title={filtered ? 'No requests match these filters' : mode === 'mine' ? 'You have not raised any requests yet' : 'The request queue is empty'}
          description={filtered ? 'Try a different search or clear the filters.' : mode === 'mine' ? 'Request access, hardware or software from the service catalogue.' : 'New catalogue requests appear here.'}
          icon={filtered ? <FilterX size={20} /> : <ClipboardList size={20} />}
          action={
            filtered ? (
              <button type="button" className="subtle-btn compact" onClick={() => onFiltersChange({ search: '', category: '', priority: '', status: '', team: '' })}>
                Clear filters
              </button>
            ) : (
              emptyAction
            )
          }
        />
      )}
      {requests.data && requests.data.content.length > 0 && (
        <>
          <ul className={`grid gap-2 ${requests.isFetching && !requests.isLoading ? 'opacity-70' : ''}`} aria-label={mode === 'mine' ? 'My requests' : 'Request queue'}>
            {requests.data.content.map((request) => (
              <RequestCard key={request.id} request={request} onOpen={onOpen} showRequester={mode === 'queue'} iconName={iconByCode.get(request.categoryCode)} />
            ))}
          </ul>
          <Pagination
            page={requests.data.page}
            totalPages={requests.data.totalPages}
            totalElements={requests.data.totalElements}
            size={requests.data.size}
            onPageChange={onPageChange}
            label="requests"
          />
        </>
      )}
    </>
  );
}
