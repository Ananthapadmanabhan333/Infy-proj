import { AlertTriangle, Cpu, RefreshCw, Sparkles } from 'lucide-react';
import { useAiStatus } from '../../api/ai';
import { errorMessage } from '../../lib/api';
import { Skeleton } from '../States';

/**
 * Which engine produces SA AI output. When no LLM is configured (or it is disabled) the page says so plainly:
 * answers then come from the deterministic rules/statistics engine and are never presented as AI-generated.
 */
export function EngineStatus() {
  const status = useAiStatus();

  if (status.isLoading) {
    return (
      <div className="nx-engine" role="status" aria-busy="true">
        <span className="sr-only">Loading engine status…</span>
        <Skeleton height={12} width="45%" />
      </div>
    );
  }

  if (status.isError || !status.data) {
    return (
      <div className="nx-engine error" role="alert">
        <AlertTriangle size={15} aria-hidden="true" />
        <span>
          <b>Engine status unavailable.</b> {errorMessage(status.error)} Every answer still shows its source label.
        </span>
        <button type="button" className="link-btn ml-auto text-[11px]" onClick={() => void status.refetch()}>
          <RefreshCw size={12} /> Retry
        </button>
      </div>
    );
  }

  const { provider, model, llmConfigured, llmEnabled, lastError } = status.data;
  const llm = llmConfigured && llmEnabled;

  return (
    <div className={`nx-engine ${llm ? 'llm' : 'fallback'}`} role="note" aria-label="SA AI engine status">
      {llm ? <Sparkles size={15} aria-hidden="true" /> : <Cpu size={15} aria-hidden="true" />}
      {llm ? (
        <span>
          <b>LLM engine active</b> · {[provider, model].filter(Boolean).join(' · ')}. If the model cannot answer, the deterministic engine takes
          over and the answer is labelled “Deterministic fallback”.
        </span>
      ) : (
        <span>
          <b>Fallback engine active</b> — {llmConfigured ? 'the LLM provider is configured but disabled' : 'no LLM provider is configured'}. Answers are
          computed by deterministic rules and statistics over SA records — not AI-generated.
        </span>
      )}
      {lastError && (
        <span className="nx-engine-error">
          <AlertTriangle size={12} aria-hidden="true" /> Last provider error: {lastError}
        </span>
      )}
    </div>
  );
}
