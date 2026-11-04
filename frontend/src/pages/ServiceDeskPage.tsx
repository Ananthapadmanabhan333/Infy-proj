import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Plus } from 'lucide-react';
import { useApprovals, useServiceRequestCounts } from '../api/serviceDesk';
import { PageHeader } from '../components/PageHeader';
import { Panel } from '../components/Panel';
import { Tabs } from '../components/Tabs';
import { ApprovalsPanel } from '../components/servicedesk/ApprovalsPanel';
import { CatalogBrowser } from '../components/servicedesk/CatalogBrowser';
import { NewRequestModal } from '../components/servicedesk/NewRequestModal';
import { RequestDrawer } from '../components/servicedesk/RequestDrawer';
import { RequestList, type RequestFilters } from '../components/servicedesk/RequestList';
import { useAuth } from '../hooks/useAuth';
import { useUrlFilters } from '../hooks/useUrlState';
import { APPROVERS, READERS } from '../lib/constants';
import type { CatalogItemDto } from '../lib/types';

type DeskTab = 'requests' | 'catalog' | 'mine' | 'approvals';
const TABS: DeskTab[] = ['requests', 'catalog', 'mine', 'approvals'];
const KEYS = ['tab', 'search', 'category', 'priority', 'status', 'team', 'request'] as const;
const CLEARED = { search: '', category: '', priority: '', status: '', team: '' };

/**
 * Service Desk (§4, reference screen 4): staff request queue, the service catalogue, the user's own requests and
 * approvals, with a request drawer and the "+ New Request" catalogue picker.
 */
export default function ServiceDeskPage() {
  const { user, hasRole } = useAuth();
  // Staff work the queue; auditors read it. Employees only see their own requests (the API enforces the same).
  const canSeeQueue = hasRole(...READERS);
  // Auditors have read-only access.
  const canRequest = user?.role !== 'AUDITOR';
  const canApprove = hasRole(...APPROVERS);
  const { values, setValues, page, setPage } = useUrlFilters(KEYS);
  const [newRequest, setNewRequest] = useState<{ item: CatalogItemDto | null } | null>(null);

  const defaultTab: DeskTab = canSeeQueue ? 'requests' : 'mine';
  let tab = (TABS.includes(values.tab as DeskTab) ? values.tab : defaultTab) as DeskTab;
  if ((tab === 'requests' && !canSeeQueue) || (tab === 'approvals' && !canApprove)) tab = defaultTab;

  const counts = useServiceRequestCounts(canSeeQueue ? 'all' : 'mine');
  const pendingApprovals = useApprovals({ status: 'PENDING', page: 0, size: 1 }, canApprove);
  const requestId = values.request ? Number(values.request) : null;
  const filters: RequestFilters = { search: values.search, category: values.category, priority: values.priority, status: values.status, team: values.team };

  const openRequest = (id: number) => setValues({ request: String(id), page: page ? String(page) : '' });
  const changeTab = (next: DeskTab) => setValues({ ...CLEARED, tab: next === defaultTab ? '' : next, request: '' });
  const newRequestButton = canRequest ? (
    <button type="button" className="gold-btn" onClick={() => setNewRequest({ item: null })}>
      <Plus size={16} aria-hidden="true" /> New Request
    </button>
  ) : undefined;

  return (
    <>
      <PageHeader title="Service Desk" subtitle="Manage user requests, service catalog and approvals" actions={newRequestButton} />
      <div className="page-stack">
        <Tabs<DeskTab>
          label="Service desk views"
          value={tab}
          onChange={changeTab}
          tabs={[
            { id: 'requests', label: 'Requests', hidden: !canSeeQueue, count: counts.data?.all },
            { id: 'catalog', label: 'Service Catalog' },
            { id: 'mine', label: 'My Requests', count: counts.data?.mine },
            { id: 'approvals', label: 'Approvals', hidden: !canApprove, count: pendingApprovals.data?.totalElements },
          ]}
        />

        {(tab === 'requests' || tab === 'mine') && (
          <Panel aria-label={tab === 'mine' ? 'My requests' : 'Request queue'}>
            <RequestList
              key={tab}
              mode={tab === 'mine' ? 'mine' : 'queue'}
              filters={filters}
              onFiltersChange={(patch) => setValues(patch)}
              page={page}
              onPageChange={setPage}
              onOpen={(request) => openRequest(request.id)}
              emptyAction={
                tab === 'mine' && canRequest ? (
                  <button type="button" className="gold-btn compact" onClick={() => setNewRequest({ item: null })}>
                    <Plus size={14} aria-hidden="true" /> New Request
                  </button>
                ) : undefined
              }
            />
          </Panel>
        )}

        {tab === 'catalog' && (
          <p className="m-0 flex justify-end text-[11px]">
            <Link to={values.category ? `/catalog?category=${encodeURIComponent(values.category)}` : '/catalog'} className="link-btn inline-flex items-center gap-1">
              Open the Service Catalogue page <ArrowRight size={13} aria-hidden="true" />
            </Link>
          </p>
        )}
        {tab === 'catalog' && (
          <CatalogBrowser categoryId={values.category} search={values.search} onChange={(patch) => setValues(patch)} onRequest={canRequest ? (item) => setNewRequest({ item }) : undefined} />
        )}

        {tab === 'approvals' && (
          <Panel aria-label="Approvals">
            <ApprovalsPanel status={values.status} onStatusChange={(status) => setValues({ status })} page={page} onPageChange={setPage} onOpenRequest={openRequest} />
          </Panel>
        )}
      </div>

      {requestId !== null && requestId > 0 && <RequestDrawer id={requestId} onClose={() => setValues({ request: '', page: page ? String(page) : '' })} />}
      {newRequest && <NewRequestModal initialItem={newRequest.item} onClose={() => setNewRequest(null)} />}
    </>
  );
}
