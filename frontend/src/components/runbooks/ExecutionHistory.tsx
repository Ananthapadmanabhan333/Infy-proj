import { useState } from 'react';
import { History } from 'lucide-react';
import { useRunbookExecutionHistory } from '../../api/runbooks';
import { useAuth } from '../../hooks/useAuth';
import { STAFF } from '../../lib/constants';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import type { PageResponse, RunbookExecutionDto, RunbookExecutionStatus } from '../../lib/types';
import { StatusBadge } from '../Badge';
import { Drawer } from '../Drawer';
import { FilterSelect } from '../FilterSelect';
import { Pagination } from '../Pagination';
import { Panel } from '../Panel';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { ExecutionCard } from './ExecutionCard';

const EXECUTION_STATUSES: RunbookExecutionStatus[] = ['PENDING_APPROVAL', 'READY', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'ABORTED'];

/** Accepts the paged response, or a plain array from an older API, as one page. */
export function asExecutionPage(data: PageResponse<RunbookExecutionDto> | RunbookExecutionDto[] | undefined, size: number): PageResponse<RunbookExecutionDto> | undefined {
  if (!data) return undefined;
  if (Array.isArray(data)) return { content: data, page: 0, size: Math.max(size, data.length), totalElements: data.length, totalPages: data.length ? 1 : 0 };
  return data;
}

interface ExecutionHistoryProps {
  runbookId?: number;
  title?: string;
  subtitle?: string;
  pageSize?: number;
  /** Show the runbook title on each row (cross-runbook history). */
  showRunbook?: boolean;
}

/** Paged execution history (`GET /runbooks/executions`); a row opens the execution view in a drawer. */
export function ExecutionHistory({ runbookId, title = 'Execution history', subtitle, pageSize = 5, showRunbook = false }: ExecutionHistoryProps) {
  const { hasRole } = useAuth();
  const canRun = hasRole(...STAFF);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const history = useRunbookExecutionHistory({ runbookId, status: status || undefined, page, size: pageSize });
  const data = asExecutionPage(history.data as PageResponse<RunbookExecutionDto> | RunbookExecutionDto[] | undefined, pageSize);
  const [opened, setOpened] = useState<RunbookExecutionDto | null>(null);
  // Keep the drawer in sync with refetched data after step / complete / abort actions.
  const current = opened ? (data?.content.find((execution) => execution.id === opened.id) ?? opened) : null;

  return (
    <Panel
      title={title}
      subtitle={subtitle ?? (data ? `${data.totalElements} execution${data.totalElements === 1 ? '' : 's'}` : undefined)}
      actions={
        <FilterSelect
          label="Execution status"
          allLabel="Any status"
          value={status}
          onChange={(value) => {
            setStatus(value);
            setPage(0);
          }}
          options={EXECUTION_STATUSES}
          formatOption={titleCase}
        />
      }
    >
      {history.isLoading && <LoadingState rows={3} label="Loading executions…" />}
      {history.isError && !data && <ErrorState error={history.error} onRetry={() => void history.refetch()} compact />}
      {data && data.content.length === 0 && (
        <EmptyState
          title={status ? 'No executions with this status' : 'Never executed'}
          icon={<History size={20} />}
          description={status ? undefined : 'Start a runbook from an incident or with “Run on incident”.'}
        />
      )}
      {data && data.content.length > 0 && (
        <div className={history.isPlaceholderData ? 'opacity-60' : ''}>
          <ul className="rb-exec-list" aria-label="Runbook executions">
            {data.content.map((execution) => {
              const done = execution.stepStates.filter((state) => state.done).length;
              const total = execution.stepStates.length;
              return (
                <li key={execution.id}>
                  <button type="button" className="rb-exec-row" onClick={() => setOpened(execution)} aria-label={`Open execution of ${execution.runbookTitle}${execution.incidentNumber ? ` on ${execution.incidentNumber}` : ''}`}>
                    <b>{showRunbook ? execution.runbookTitle : (execution.incidentNumber ?? `Execution #${execution.id}`)}</b>
                    <StatusBadge status={execution.status} />
                    <small>
                      {showRunbook && execution.incidentNumber && <span className="id-cell">{execution.incidentNumber}</span>}
                      <span>{execution.startedByName ?? '—'}</span>
                      <span title={formatDateTime(execution.createdAt)}>{formatRelative(execution.createdAt)}</span>
                      <span className="inline-flex items-center gap-1.5">
                        <span className="rb-progress" aria-hidden="true">
                          <i style={{ width: `${total ? (done / total) * 100 : 0}%` }} />
                        </span>
                        {done}/{total} steps
                      </span>
                    </small>
                  </button>
                </li>
              );
            })}
          </ul>
          {data.totalPages > 1 && (
            <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} size={data.size} onPageChange={setPage} label="executions" window={3} />
          )}
        </div>
      )}
      <Drawer
        open={current !== null}
        onClose={() => setOpened(null)}
        label="RUNBOOK EXECUTION"
        title={current?.runbookTitle ?? ''}
        subtitle={current ? [current.incidentNumber, titleCase(current.status), current.startedAt ? `started ${formatDateTime(current.startedAt)}` : null].filter(Boolean).join(' · ') : undefined}
        wide
      >
        {current && <ExecutionCard execution={current} canRun={canRun} showIncident />}
      </Drawer>
    </Panel>
  );
}
