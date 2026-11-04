import { useNavigate } from 'react-router-dom';
import { ClipboardCheck } from 'lucide-react';
import { useReviews } from '../../api/majorIncidents';
import { UserChip } from '../../components/Avatar';
import { DemoBadge } from '../../components/Badge';
import { DataTable } from '../../components/DataTable';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { PageHeader } from '../../components/PageHeader';
import { Pagination } from '../../components/Pagination';
import { Panel } from '../../components/Panel';
import { SearchInput } from '../../components/SearchInput';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useSearchInput, useTabParam, useUrlFilters } from '../../hooks/useUrlState';
import { formatDate, formatNumber, formatRelative } from '../../lib/format';
import { PIR_STATUS_LABELS, PIR_STATUS_TONES } from '../../lib/response';
import type { PirStatus, PirSummary } from '../../lib/responseTypes';

type TabId = 'open' | PirStatus | 'all';
const TABS: TabId[] = ['open', 'DRAFT', 'IN_REVIEW', 'REJECTED', 'APPROVED', 'all'];
const PAGE_SIZE = 20;

function statusFilter(tab: TabId): string | undefined {
  if (tab === 'all') return undefined;
  if (tab === 'open') return 'DRAFT,IN_REVIEW,REJECTED';
  return tab;
}

export function PirStatusBadge({ status }: { status: PirStatus }) {
  return <ToneBadge value={status} label={PIR_STATUS_LABELS[status] ?? status} tone={PIR_STATUS_TONES[status] ?? 'neutral'} />;
}

/** /reviews (readers): post-incident reviews by status. Decisions happen in the /approvals inbox. */
export default function ReviewsPage() {
  const navigate = useNavigate();
  const [tab, setTab] = useTabParam<TabId>(TABS, 'open');
  const { values, setValues, page, setPage } = useUrlFilters(['search'] as const);
  const [searchInput, setSearchInput] = useSearchInput(values.search, (search) => setValues({ search }));
  const reviews = useReviews({ status: statusFilter(tab), search: values.search || undefined, page, size: PAGE_SIZE });
  const data = reviews.data;
  return (
    <>
      <PageHeader
        crumbs={[{ label: 'Major Incidents', to: '/major-incidents' }, { label: 'Post-incident reviews' }]}
        title="Post-incident reviews"
        subtitle="Root cause, contributing factors and corrective actions after major incidents — approved through the approval engine"
      />
      <div className="page-stack">
        <Tabs<TabId> label="Review status" value={tab} onChange={setTab} tabs={TABS.map((id) => ({ id, label: id === 'open' ? 'Open' : id === 'all' ? 'All' : PIR_STATUS_LABELS[id as PirStatus] }))} />
        <Panel aria-label="Review list">
          <div className="toolbar">
            <SearchInput value={searchInput} onChange={setSearchInput} placeholder="Search reviews by number or title…" label="Search reviews" />
          </div>
          {reviews.isLoading && <LoadingState rows={5} label="Loading reviews…" />}
          {reviews.isError && <ErrorState error={reviews.error} onRetry={() => void reviews.refetch()} />}
          {data && data.content.length === 0 && (
            <EmptyState title={values.search ? 'No reviews match this search' : 'No reviews here'} description="Closing a major incident opens its review." icon={<ClipboardCheck size={20} />} />
          )}
          {data && data.content.length > 0 && (
            <>
              <DataTable<PirSummary>
                caption="Post-incident reviews"
                rows={data.content}
                rowKey={(review) => review.id}
                rowLabel={(review) => `${review.number} ${review.title}`}
                onRowClick={(review) => navigate(`/reviews/${review.id}`)}
                columns={[
                  { key: 'number', header: 'Review', render: (review) => <span className="id-cell">{review.number}</span> },
                  {
                    key: 'title',
                    header: 'Title',
                    className: 'summary-cell',
                    render: (review) => (
                      <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
                        <span className="break-words font-semibold">{review.title}</span>
                        <DemoBadge demo={review.demo} />
                      </span>
                    ),
                  },
                  { key: 'status', header: 'Status', render: (review) => <PirStatusBadge status={review.status} /> },
                  {
                    key: 'incident',
                    header: 'Incident',
                    render: (review) => (
                      <span className="inline-flex flex-wrap gap-1">
                        <span className="id-cell">{review.incidentNumber}</span>
                        {review.majorIncidentNumber && <span className="id-cell">{review.majorIncidentNumber}</span>}
                      </span>
                    ),
                  },
                  { key: 'owner', header: 'Owner', render: (review) => <UserChip name={review.owner?.name} emptyLabel="Unowned" /> },
                  { key: 'actions', header: 'Actions', render: (review) => `${formatNumber(review.openActions)} open / ${formatNumber(review.actionCount)}` },
                  { key: 'submitted', header: 'Submitted', render: (review) => (review.submittedAt ? formatDate(review.submittedAt) : '—') },
                  { key: 'updated', header: 'Updated', render: (review) => formatRelative(review.updatedAt) },
                ]}
              />
              <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} size={data.size} onPageChange={setPage} label="reviews" />
            </>
          )}
        </Panel>
      </div>
    </>
  );
}
