import { useState } from 'react';
import { PlayCircle, ShieldCheck } from 'lucide-react';
import { useStartRunbook } from '../../api/runbooks';
import { errorMessage } from '../../lib/api';
import type { IncidentSummary, RunbookDto } from '../../lib/types';
import { IncidentPicker } from '../incidents/IncidentPicker';
import { Modal } from '../Modal';
import { useToast } from '../Toast';

/** "Run on incident": starts the runbook on a chosen incident (approval-gated runbooks go to approval first). */
export function StartRunbookDialog({ runbook, onClose }: { runbook: Pick<RunbookDto, 'id' | 'title' | 'requiresApproval'>; onClose: () => void }) {
  const [incident, setIncident] = useState<IncidentSummary | null>(null);
  const start = useStartRunbook();
  const toast = useToast();

  const run = () => {
    if (!incident) return;
    start.mutate(
      { runbookId: runbook.id, incidentId: incident.id },
      {
        onSuccess: (execution) => {
          toast.success(execution?.status === 'PENDING_APPROVAL' ? `${runbook.title} sent for approval` : `${runbook.title} started on ${incident.number}`, {
            action: { label: 'Open incident', to: `/incidents/${incident.id}` },
          });
          onClose();
        },
      },
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title="Run on incident"
      eyebrow="RUNBOOKS"
      description={runbook.title}
      dismissible={!start.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={start.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={run} disabled={!incident || start.isPending}>
            <PlayCircle size={14} /> {start.isPending ? 'Starting…' : runbook.requiresApproval ? 'Request approval' : 'Start runbook'}
          </button>
        </>
      }
    >
      <IncidentPicker label="Incident" value={incident} onChange={setIncident} />
      {runbook.requiresApproval && (
        <div className="callout mt-3">
          <ShieldCheck size={16} />
          <div>This runbook is approval-gated: an Incident Manager must approve the execution before its steps unlock.</div>
        </div>
      )}
      {start.isError && <p className="field-error mt-2">{errorMessage(start.error)}</p>}
    </Modal>
  );
}
