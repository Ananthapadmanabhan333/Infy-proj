import { useState } from 'react';
import { Link } from 'react-router-dom';
import { FlaskConical } from 'lucide-react';
import { useRunPayrollScenario } from '../../api/admin';
import { errorMessage } from '../../lib/api';
import { formatTime } from '../../lib/format';
import type { ScenarioResult } from '../../lib/types';
import { ConfirmDialog } from '../ConfirmDialog';
import { Modal } from '../Modal';

export function ScenarioResultView({ result, onNavigate }: { result: ScenarioResult; onNavigate?: () => void }) {
  return (
    <div className="grid gap-3">
      {result.parentIncidentId && result.parentNumber && (
        <div className="callout">
          <FlaskConical size={16} />
          <div>
            Parent incident{' '}
            <Link to={`/incidents/${result.parentIncidentId}`} onClick={onNavigate}>
              <b>{result.parentNumber}</b>
            </Link>{' '}
            {result.childNumbers.length > 0 && <>with {result.childNumbers.length} linked child reports ({result.childNumbers.join(', ')})</>}
          </div>
        </div>
      )}
      <ol className="timeline">
        {result.steps.map((step) => (
          <li key={step.step} className="relative my-3 list-none text-[11px] text-[#d5c8b5]">
            <i className="absolute -left-[21px] top-1 h-2 w-2 rounded-full bg-gold" />
            <b>
              {step.step}. {step.title}
            </b>
            <span className="block text-[10px] leading-relaxed text-muted">{step.description}</span>
            <small className="block text-[9px] text-[#928777]">
              {formatTime(step.at)}
              {step.reference ? ` · ${step.reference}` : ''}
            </small>
          </li>
        ))}
      </ol>
    </div>
  );
}

/** "Run payroll outage demo" — drives the real engines via POST /admin/demo/payroll-outage. */
export function PayrollScenarioButton({ className = 'subtle-btn' }: { className?: string }) {
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<ScenarioResult | null>(null);
  const run = useRunPayrollScenario();
  return (
    <>
      <button type="button" className={className} onClick={() => setConfirming(true)}>
        <FlaskConical size={15} /> Run payroll outage demo
      </button>
      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title="Run payroll outage demo?"
        message={
          <>
            This runs the payroll outage scenario through the real classification, duplicate-detection, assignment, SLA and workflow engines.
            The incidents it creates are flagged <span className="badge demo">DEMO</span>.
          </>
        }
        confirmLabel="Run scenario"
        pending={run.isPending}
        error={run.isError ? errorMessage(run.error) : null}
        onConfirm={() =>
          run.mutate(undefined, {
            onSuccess: (data) => {
              setConfirming(false);
              setResult(data);
            },
          })
        }
      />
      <Modal open={result !== null} onClose={() => setResult(null)} title="Payroll outage scenario" eyebrow="DEMO SCENARIO" size="lg">
        {result && <ScenarioResultView result={result} onNavigate={() => setResult(null)} />}
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={() => setResult(null)}>
            Done
          </button>
          {result?.parentIncidentId && (
            <Link className="gold-btn" to={`/incidents/${result.parentIncidentId}`} onClick={() => setResult(null)}>
              Open {result.parentNumber}
            </Link>
          )}
        </div>
      </Modal>
    </>
  );
}
