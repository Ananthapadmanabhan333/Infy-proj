import { useState } from 'react';
import { FileText, Play, ShieldCheck } from 'lucide-react';
import { useRunbookExecutions, useRunbooks, useStartRunbook } from '../../../api/runbooks';
import { ConfirmDialog } from '../../../components/ConfirmDialog';
import { PanelTitle } from '../../../components/Panel';
import { ExecutionCard } from '../../../components/runbooks/ExecutionCard';
import { EmptyState, ErrorState, LoadingState } from '../../../components/States';
import { useToast } from '../../../components/Toast';
import { errorMessage } from '../../../lib/api';
import type { IncidentDetail, RunbookDto } from '../../../lib/types';

export function RunbooksPanel({ incident, canRun }: { incident: IncidentDetail; canRun: boolean }) {
  const executions = useRunbookExecutions({ incidentId: incident.id });
  const runbooks = useRunbooks({}, canRun);
  const start = useStartRunbook();
  const toast = useToast();
  const [selected, setSelected] = useState('');
  const [confirm, setConfirm] = useState<RunbookDto | null>(null);
  const closed = incident.status === 'RESOLVED' || incident.status === 'CLOSED';

  // Runbooks for this incident's service first, then the rest.
  const options = [...(runbooks.data ?? [])]
    .filter((runbook) => runbook.active)
    .sort((a, b) => Number(b.serviceId === incident.serviceId) - Number(a.serviceId === incident.serviceId) || a.title.localeCompare(b.title));

  const run = (runbook: RunbookDto) =>
    start.mutate(
      { runbookId: runbook.id, incidentId: incident.id },
      {
        onSuccess: (execution) => {
          setConfirm(null);
          setSelected('');
          if (execution.status === 'PENDING_APPROVAL') toast.info(`“${runbook.title}” sent for manager approval`);
          else toast.success(`Runbook “${runbook.title}” started`);
        },
      },
    );

  return (
    <section className="panel" aria-labelledby="runbooks-title">
      <PanelTitle id="runbooks-title" title="Runbooks" subtitle="Standard procedures executed against this incident" />
      {canRun && !closed && (
        <div className="mb-3 flex flex-wrap gap-2">
          <select className="control sm min-w-[200px] flex-1" aria-label="Runbook to start" value={selected} onChange={(event) => setSelected(event.target.value)}>
            <option value="">Choose a runbook…</option>
            {options.map((runbook) => (
              <option key={runbook.id} value={runbook.id}>
                {runbook.title}
                {runbook.serviceId === incident.serviceId && incident.serviceId ? ' (this service)' : ''}
                {runbook.requiresApproval ? ' — requires approval' : ''}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="gold-btn compact"
            disabled={!selected || start.isPending}
            onClick={() => {
              const runbook = options.find((entry) => String(entry.id) === selected);
              if (runbook) setConfirm(runbook);
            }}
          >
            <Play size={13} /> Start runbook
          </button>
        </div>
      )}
      {executions.isLoading && <LoadingState rows={2} />}
      {executions.isError && <ErrorState error={executions.error} onRetry={() => void executions.refetch()} compact />}
      {executions.data && executions.data.length === 0 && <EmptyState title="No runbooks started" icon={<FileText size={20} />} />}
      {executions.data?.map((execution) => <ExecutionCard key={execution.id} execution={execution} canRun={canRun} />)}

      <ConfirmDialog
        open={confirm !== null}
        onClose={() => setConfirm(null)}
        title={`Start “${confirm?.title ?? ''}”?`}
        message={
          confirm?.requiresApproval ? (
            <span className="inline-flex items-start gap-2">
              <ShieldCheck size={16} className="gold-text" /> This runbook requires approval. It will be sent for manager approval and the steps unlock once
              approved.
            </span>
          ) : (
            `${confirm?.steps.length ?? 0} steps${confirm?.estimatedMinutes ? ` · about ${confirm.estimatedMinutes} minutes` : ''}.`
          )
        }
        confirmLabel={confirm?.requiresApproval ? 'Request approval' : 'Start'}
        pending={start.isPending}
        error={start.isError ? errorMessage(start.error) : null}
        onConfirm={() => confirm && run(confirm)}
      />
    </section>
  );
}
