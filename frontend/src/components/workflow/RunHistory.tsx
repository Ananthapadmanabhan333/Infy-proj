import { useState } from 'react';
import { Link } from 'react-router-dom';
import { History, RotateCcw } from 'lucide-react';
import { useRetryExecution, useWorkflowExecutions } from '../../api/workflows';
import { useUrlFilters } from '../../hooks/useUrlState';
import { DEFAULT_PAGE_SIZE, EXECUTION_STATUSES } from '../../lib/constants';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import type { WorkflowDto, WorkflowExecutionDto, WorkflowMeta } from '../../lib/types';
import { Badge, StatusBadge } from '../Badge';
import { DataTable } from '../DataTable';
import { Drawer } from '../Drawer';
import { FilterSelect } from '../FilterSelect';
import { Pagination } from '../Pagination';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';
import { actionLabel, triggerLabel } from './model';

export function executionDuration(execution: Pick<WorkflowExecutionDto, 'startedAt' | 'finishedAt'>): string {
  if (!execution.finishedAt) return '—';
  const ms = new Date(execution.finishedAt).getTime() - new Date(execution.startedAt).getTime();
  if (!Number.isFinite(ms) || ms < 0) return '—';
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.round(ms / 60_000)} min`;
}

function BranchBadge({ branch }: { branch: WorkflowExecutionDto['branch'] }) {
  if (!branch) return <span className="muted-text">—</span>;
  return <Badge value="outline" tone={branch === 'YES' ? 'gold' : 'neutral'} label={branch === 'YES' ? 'Yes' : 'No'} />;
}

/** Run History tab: every execution, paged, with step log drawer and retry for failed runs. */
export function RunHistory({ workflows, meta, canRetry }: { workflows: WorkflowDto[]; meta?: WorkflowMeta | null; canRetry: boolean }) {
  const { values, setValues, page, setPage } = useUrlFilters(['status', 'workflowId'] as const);
  const executions = useWorkflowExecutions({ status: values.status || undefined, workflowId: values.workflowId || undefined, page, size: DEFAULT_PAGE_SIZE });
  const retry = useRetryExecution();
  const toast = useToast();
  const [openId, setOpenId] = useState<number | null>(null);
  const [snapshot, setSnapshot] = useState<WorkflowExecutionDto | null>(null);
  const open = executions.data?.content.find((execution) => execution.id === openId) ?? (snapshot?.id === openId ? snapshot : null);
  const filtered = Boolean(values.status || values.workflowId);

  const doRetry = (execution: WorkflowExecutionDto) =>
    retry.mutate(execution.id, {
      onSuccess: () => toast.success(`Retry scheduled for run #${execution.id}`),
      onError: (error) => toast.error(error),
    });

  const canRetryRun = (execution: WorkflowExecutionDto) => canRetry && execution.status === 'FAILED';

  return (
    <>
      <div className="toolbar">
        <FilterSelect
          label="Workflow"
          value={values.workflowId}
          onChange={(workflowId) => setValues({ workflowId })}
          options={workflows.map((workflow) => ({ value: String(workflow.id), label: workflow.name }))}
          allLabel="All workflows"
        />
        <FilterSelect
          label="Status"
          value={values.status}
          onChange={(status) => setValues({ status })}
          options={EXECUTION_STATUSES}
          formatOption={titleCase}
          allLabel="Any status"
        />
        {filtered && (
          <button type="button" className="link-btn" onClick={() => setValues({ status: '', workflowId: '' })}>
            Clear filters
          </button>
        )}
      </div>
      {executions.isLoading && <LoadingState rows={6} label="Loading run history…" />}
      {executions.isError && !executions.data && <ErrorState error={executions.error} onRetry={() => void executions.refetch()} />}
      {executions.data && (
        <>
          <DataTable<WorkflowExecutionDto>
            caption="Workflow run history"
            rows={executions.data.content}
            rowKey={(row) => row.id}
            rowLabel={(row) => `Run ${row.id} of ${row.workflowName}, ${titleCase(row.status)}`}
            onRowClick={(row) => {
              setOpenId(row.id);
              setSnapshot(row);
            }}
            empty={
              <EmptyState
                title={filtered ? 'No runs match these filters' : 'No workflow runs yet'}
                icon={<History size={20} />}
                description={filtered ? undefined : 'Runs appear here when an active workflow reacts to an event.'}
              />
            }
            columns={[
              { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
              {
                key: 'workflow',
                header: 'Workflow',
                className: 'wrap',
                render: (row) => (
                  <span>
                    <b className="text-ivory">{row.workflowName}</b> <span className="muted-text">v{row.workflowVersion}</span>
                  </span>
                ),
              },
              { key: 'trigger', header: 'Trigger', render: (row) => triggerLabel(row.triggerEvent) },
              {
                key: 'incident',
                header: 'Incident',
                render: (row) =>
                  row.incidentId ? (
                    <Link className="id-cell" to={`/incidents/${row.incidentId}`} onClick={(event) => event.stopPropagation()}>
                      {row.incidentNumber ?? `#${row.incidentId}`}
                    </Link>
                  ) : row.alertId ? (
                    <Link className="id-cell" to={`/alerts/${row.alertId}`} onClick={(event) => event.stopPropagation()}>
                      Alert #{row.alertId}
                    </Link>
                  ) : (
                    <span className="muted-text">—</span>
                  ),
              },
              { key: 'branch', header: 'Branch', render: (row) => <BranchBadge branch={row.branch} /> },
              { key: 'attempt', header: 'Attempt', render: (row) => row.attempt },
              { key: 'started', header: 'Started', render: (row) => <span title={formatDateTime(row.startedAt)}>{formatRelative(row.startedAt)}</span> },
              { key: 'duration', header: 'Duration', render: (row) => executionDuration(row) },
              {
                key: 'error',
                header: 'Error',
                className: 'wrap',
                render: (row) => (row.errorMessage ? <span className="red-text line-clamp-2 max-w-[260px]">{row.errorMessage}</span> : <span className="muted-text">—</span>),
              },
              {
                key: 'actions',
                header: <span className="sr-only">Actions</span>,
                render: (row) =>
                  canRetryRun(row) ? (
                    <button
                      type="button"
                      className="subtle-btn compact"
                      disabled={retry.isPending}
                      aria-label={`Retry run ${row.id}`}
                      onClick={(event) => {
                        event.stopPropagation();
                        doRetry(row);
                      }}
                    >
                      <RotateCcw size={12} /> Retry
                    </button>
                  ) : null,
              },
            ]}
          />
          <Pagination
            page={executions.data.page}
            totalPages={executions.data.totalPages}
            totalElements={executions.data.totalElements}
            size={executions.data.size}
            onPageChange={setPage}
            label="runs"
          />
        </>
      )}

      <Drawer
        open={open !== null}
        onClose={() => setOpenId(null)}
        label="Workflow run"
        title={open ? `${open.workflowName} · v${open.workflowVersion}` : ''}
        subtitle={open ? `Run #${open.id} · ${triggerLabel(open.triggerEvent)} · started ${formatDateTime(open.startedAt)}` : undefined}
        footer={
          open && (
            <>
              <Link className="subtle-btn" to={`/automation/${open.workflowId}`}>
                Open workflow
              </Link>
              {canRetryRun(open) && (
                <button type="button" className="gold-btn" disabled={retry.isPending} onClick={() => doRetry(open)}>
                  <RotateCcw size={14} /> Retry run
                </button>
              )}
            </>
          )
        }
      >
        {open && (
          <>
            <div className="detail-grid">
              <div>
                <span className="eyebrow">Status</span>
                <div className="mt-1">
                  <StatusBadge status={open.status} />
                </div>
              </div>
              <div>
                <span className="eyebrow">Incident</span>
                <div className="mt-1 text-[11px]">
                  {open.incidentId ? (
                    <Link className="id-cell" to={`/incidents/${open.incidentId}`}>
                      {open.incidentNumber ?? `#${open.incidentId}`}
                    </Link>
                  ) : (
                    '—'
                  )}
                </div>
              </div>
              <div>
                <span className="eyebrow">Branch</span>
                <div className="mt-1">
                  <BranchBadge branch={open.branch} />
                </div>
              </div>
              <div>
                <span className="eyebrow">Attempt</span>
                <div className="mt-1 text-[11px]">{open.attempt}</div>
              </div>
              <div>
                <span className="eyebrow">Finished</span>
                <div className="mt-1 text-[11px]">{open.finishedAt ? formatDateTime(open.finishedAt) : 'Not finished'}</div>
              </div>
              <div>
                <span className="eyebrow">Duration</span>
                <div className="mt-1 text-[11px]">{executionDuration(open)}</div>
              </div>
            </div>
            {open.errorMessage && (
              <div className="callout red mb-3" role="alert">
                <div>{open.errorMessage}</div>
              </div>
            )}
            {open.nextAttemptAt && <p className="mb-3 text-[10px] text-muted">Next attempt {formatDateTime(open.nextAttemptAt)}</p>}
            <div className="drawer-section">
              <h3>Step log</h3>
              {open.steps.length === 0 ? (
                <p>No steps ran (the conditions did not match the configured branch, or the run was rate limited).</p>
              ) : (
                <ol className="wf-steps">
                  {open.steps.map((step, index) => (
                    <li key={`${step.action}-${index}`}>
                      <span className="step-num !h-5 !w-5 !text-[9px]">{index + 1}</span>
                      <div>
                        <span className="flex flex-wrap items-center gap-2">
                          <b>{actionLabel(step.action, meta)}</b>
                          <StatusBadge status={step.status} />
                        </span>
                        {step.message && <span>{step.message}</span>}
                        <small>{formatDateTime(step.at)}</small>
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </div>
          </>
        )}
      </Drawer>
    </>
  );
}
