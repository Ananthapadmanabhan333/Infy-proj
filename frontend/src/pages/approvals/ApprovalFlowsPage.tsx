import { Link, useNavigate } from 'react-router-dom';
import { GitBranch, Lock, Plus } from 'lucide-react';
import { useApprovalFlows } from '../../api/approvalFlows';
import { BooleanBadge, DemoBadge } from '../../components/Badge';
import { DataTable, type Column } from '../../components/DataTable';
import { FilterSelect } from '../../components/FilterSelect';
import { ToneBadge } from '../../components/itsm/ItsmParts';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { FILTER_CLASS, FILTER_ROW_CLASS } from '../../components/servicedesk/requestModel';
import { useAuth } from '../../hooks/useAuth';
import { useUrlFilters } from '../../hooks/useUrlState';
import { APPROVAL_FLOW_EDITORS } from '../../lib/constants';
import { formatRelative } from '../../lib/format';
import type { ApprovalFlowDto, ApprovalSubjectType } from '../../lib/itsmTypes';
import { SUBJECT_TYPE_OPTIONS, subjectTypeLabel } from './flowModel';

const KEYS = ['subjectType', 'active'] as const;
const ACTIVE_OPTIONS = [
  { value: 'true', label: 'Active' },
  { value: 'false', label: 'Inactive' },
];

const COLUMNS: Column<ApprovalFlowDto>[] = [
  {
    key: 'name',
    header: 'Flow',
    render: (flow) => (
      <span className="block min-w-0">
        <b className="block break-words">{flow.name}</b>
        <small className="id-cell">{flow.code}</small>
        {flow.description && <small className="block text-muted">{flow.description}</small>}
      </span>
    ),
  },
  { key: 'subjectType', header: 'Subject', render: (flow) => subjectTypeLabel(flow.subjectType) },
  {
    key: 'stages',
    header: 'Stages',
    render: (flow) => (
      <span className="block min-w-0">
        <b>{flow.stages.length}</b> <small className="text-muted">{flow.stages.map((stage) => stage.name).join(' → ')}</small>
      </span>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    render: (flow) => (
      <span className="flex flex-wrap items-center gap-1">
        <BooleanBadge value={flow.active} />
        {flow.systemFlow && <ToneBadge value="system" label={<><Lock size={10} aria-hidden="true" /> System</>} tone="champagne" />}
        <DemoBadge demo={flow.demo} />
      </span>
    ),
  },
  { key: 'updatedAt', header: 'Updated', render: (flow) => formatRelative(flow.updatedAt) },
];

/** /approvals/flows (readers): approval flows with subject type / active filters; approval-flow editors create new ones. */
export default function ApprovalFlowsPage() {
  const { hasRole } = useAuth();
  const canEdit = hasRole(...APPROVAL_FLOW_EDITORS);
  const navigate = useNavigate();
  const { values, setValues } = useUrlFilters(KEYS);
  const flows = useApprovalFlows({
    subjectType: (values.subjectType || '') as ApprovalSubjectType | '',
    active: values.active === 'true' ? true : values.active === 'false' ? false : undefined,
  });
  const filtering = Boolean(values.subjectType || values.active);

  return (
    <>
      <PageHeader
        title="Approval flows"
        subtitle="Ordered approval stages with approvers, quorum, conditions and deadlines, used by changes and workflow sign-offs"
        crumbs={[{ label: 'Approvals', to: '/approvals' }, { label: 'Approval flows' }]}
        actions={
          canEdit ? (
            <Link className="gold-btn" to="/approvals/flows/new">
              <Plus size={14} aria-hidden="true" /> New flow
            </Link>
          ) : undefined
        }
      />
      <div className="page-stack">
        <Panel aria-label="Approval flows">
          <div className="toolbar">
            <div className={FILTER_ROW_CLASS}>
              <FilterSelect className={FILTER_CLASS} label="Subject type" allLabel="All subject types" value={values.subjectType} onChange={(subjectType) => setValues({ subjectType })} options={SUBJECT_TYPE_OPTIONS} />
              <FilterSelect className={FILTER_CLASS} label="Status" allLabel="Active and inactive" value={values.active} onChange={(active) => setValues({ active })} options={ACTIVE_OPTIONS} />
            </div>
            {values.subjectType && <small className="text-muted">Flows without a subject type apply to every subject and are included.</small>}
          </div>
          {flows.isLoading && <LoadingState variant="table" rows={4} label="Loading approval flows…" />}
          {flows.isError && <ErrorState error={flows.error} onRetry={() => void flows.refetch()} />}
          {flows.data && (
            <DataTable
              caption="Approval flows"
              columns={COLUMNS}
              rows={flows.data}
              rowKey={(flow) => flow.id}
              onRowClick={(flow) => navigate(`/approvals/flows/${flow.id}`)}
              rowLabel={(flow) => `Open approval flow ${flow.name}`}
              empty={
                <EmptyState
                  title={filtering ? 'No approval flows match these filters' : 'No approval flows yet'}
                  description={canEdit && !filtering ? 'Create a flow to route changes and sign-offs through ordered approval stages.' : undefined}
                  icon={<GitBranch size={20} />}
                />
              }
            />
          )}
        </Panel>
      </div>
    </>
  );
}
