import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, BookOpen, Hourglass, ListChecks, PlayCircle, RefreshCw, ShieldCheck, Sparkles, Star } from 'lucide-react';
import { useAiResolution } from '../../../api/ai';
import { useRunbookExecutions, useStartRunbook } from '../../../api/runbooks';
import { AiSourceBadge } from '../../../components/AiResultCard';
import { ConfirmDialog } from '../../../components/ConfirmDialog';
import { LoadingState } from '../../../components/States';
import { useToast } from '../../../components/Toast';
import { errorMessage } from '../../../lib/api';
import { RESOLVED_STATUSES } from '../../../lib/constants';
import { formatTime } from '../../../lib/format';
import type { ArticleSummary, IncidentDetail, RunbookExecutionStatus } from '../../../lib/types';

const ACTIVE_EXECUTION: RunbookExecutionStatus[] = ['PENDING_APPROVAL', 'READY', 'IN_PROGRESS'];

/** Match badge tone from the 0–100 confidence. */
export function matchTone(confidence: number): '' | 'medium' | 'low' {
  if (confidence >= 75) return '';
  if (confidence >= 50) return 'medium';
  return 'low';
}

interface AiRecommendationCardProps {
  incident: IncidentDetail;
  /** The resolve dialog can be opened with a cited article. */
  canResolve: boolean;
  onUseArticle: (article: ArticleSummary) => void;
}

/**
 * Reference "SA AI Recommendation — 87% Match" card (spec §7): likely cause, a local checklist of recommended actions
 * and "Apply Resolution Runbook", which starts the suggested runbook (approval-gated runbooks go to an Incident Manager).
 * Generated on demand; the result is cached per incident and always shows its source (LLM vs deterministic fallback).
 */
export function AiRecommendationCard({ incident, canResolve, onUseArticle }: AiRecommendationCardProps) {
  const resolution = useAiResolution(incident.id);
  const executions = useRunbookExecutions({ incidentId: incident.id });
  const start = useStartRunbook();
  const toast = useToast();
  const [done, setDone] = useState<Set<number>>(() => new Set());
  const [confirming, setConfirming] = useState(false);
  const [outcome, setOutcome] = useState<'approval' | 'started' | null>(null);

  const result = resolution.data;
  const suggestion = result?.data;
  useEffect(() => {
    setDone(new Set());
    setOutcome(null);
  }, [result?.generatedAt]);

  const runbook = suggestion?.runbook ?? null;
  const activeExecution = useMemo(
    () => (runbook ? executions.data?.find((execution) => execution.runbookId === runbook.id && ACTIVE_EXECUTION.includes(execution.status)) : undefined),
    [executions.data, runbook],
  );
  const closed = RESOLVED_STATUSES.includes(incident.status);
  const confidence = Math.max(0, Math.min(100, Math.round(suggestion?.confidence ?? 0)));
  const generate = () => void resolution.refetch();

  let applyBlocker: string | null = null;
  if (!runbook) applyBlocker = 'No runbook matches this incident — follow the recommended actions manually.';
  else if (closed) applyBlocker = 'The incident is already resolved.';
  else if (activeExecution?.status === 'PENDING_APPROVAL') applyBlocker = `“${runbook.title}” is waiting for Incident Manager approval.`;
  else if (activeExecution) applyBlocker = `“${runbook.title}” is already running — track it in the Runbooks panel.`;

  const apply = () =>
    runbook &&
    start.mutate(
      { runbookId: runbook.id, incidentId: incident.id },
      {
        onSuccess: (execution) => {
          setConfirming(false);
          if (execution.status === 'PENDING_APPROVAL') {
            setOutcome('approval');
            toast.info(`“${runbook.title}” sent for Incident Manager approval`);
          } else {
            setOutcome('started');
            toast.success(`Runbook “${runbook.title}” started`);
          }
        },
      },
    );

  const toggle = (index: number) =>
    setDone((current) => {
      const next = new Set(current);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });

  return (
    <section className="panel ai-reco-card" aria-labelledby="ai-reco-title" aria-busy={resolution.isFetching || undefined}>
      <div className="ai-reco-head">
        <span className="ai-reco-icon" aria-hidden="true">
          <Sparkles size={16} />
        </span>
        <h3 id="ai-reco-title">SA AI Recommendation</h3>
        {suggestion && <span className={`ai-match ${matchTone(confidence)}`.trim()}>{confidence}% Match</span>}
      </div>

      {!result && !resolution.isFetching && !resolution.isError && (
        <>
          <p className="ai-reco-line text-muted">
            Analyses this incident, similar resolved incidents, runbooks and published knowledge to suggest a likely cause and the next steps.
          </p>
          <button type="button" className="date-btn compact w-full justify-center" onClick={generate}>
            <Sparkles size={14} aria-hidden="true" /> Generate recommendation
          </button>
        </>
      )}

      {resolution.isFetching && !result && <LoadingState variant="panel" label="SA AI is analysing the incident…" />}

      {resolution.isError && (
        <div className="callout red" role="alert">
          <RefreshCw size={16} />
          <div className="min-w-0 flex-1">
            <b>The recommendation could not be generated.</b> {errorMessage(resolution.error)}
            <div className="mt-2">
              <button type="button" className="subtle-btn compact" onClick={generate} disabled={resolution.isFetching}>
                <RefreshCw size={13} /> Try again
              </button>
            </div>
          </div>
        </div>
      )}

      {result && suggestion && (
        <>
          <p className="ai-reco-line">
            <Star size={14} aria-hidden="true" />
            <span>
              <b>Likely cause:</b> {suggestion.likelyCause || 'Not enough evidence to name a cause yet.'}
            </span>
          </p>

          <p className="ai-reco-line mb-2">
            <ListChecks size={14} aria-hidden="true" />
            <b>
              Recommended actions{suggestion.steps.length > 0 ? ` (${done.size}/${suggestion.steps.length} done)` : ''}:
            </b>
          </p>
          {suggestion.steps.length === 0 ? (
            <p className="mb-3 ml-[22px] text-[11px] text-muted">No specific steps were suggested.</p>
          ) : (
            <ul className="ai-checklist" aria-label="Recommended actions checklist">
              {suggestion.steps.map((stepText, index) => (
                <li key={`${index}-${stepText}`} className={done.has(index) ? 'done' : undefined}>
                  <label>
                    <input type="checkbox" checked={done.has(index)} onChange={() => toggle(index)} />
                    <span>{stepText}</span>
                  </label>
                </li>
              ))}
            </ul>
          )}

          {suggestion.articles.length > 0 && (
            <div className="mb-3">
              <p className="ai-reco-line mb-1">
                <BookOpen size={14} aria-hidden="true" />
                <b>Related knowledge:</b>
              </p>
              {suggestion.articles.slice(0, 3).map((article) => (
                <div key={article.id} className="list-row !py-1.5">
                  <div>
                    <Link to={`/knowledge/${article.id}`} className="text-[11px]">
                      {article.number} · {article.title}
                    </Link>
                  </div>
                  {canResolve && (
                    <button type="button" className="link-btn text-[10px]" onClick={() => onUseArticle(article)}>
                      Cite in resolution
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}

          {outcome === 'approval' && (
            <div className="callout mb-3" role="status">
              <Hourglass size={16} />
              <div>
                <b>Sent for approval.</b> An Incident Manager must approve “{runbook?.title}” before its steps unlock.
              </div>
            </div>
          )}
          {outcome === 'started' && (
            <div className="callout green mb-3" role="status">
              <PlayCircle size={16} />
              <div>
                <b>Runbook started.</b> Work through the steps in the Runbooks panel.
              </div>
            </div>
          )}

          <button
            type="button"
            className="gold-btn apply-btn"
            disabled={Boolean(applyBlocker) || start.isPending}
            onClick={() => {
              start.reset();
              setConfirming(true);
            }}
            aria-describedby={applyBlocker ? 'ai-reco-apply-hint' : undefined}
          >
            <span>{activeExecution?.status === 'PENDING_APPROVAL' ? 'Runbook awaiting approval' : activeExecution ? 'Runbook in progress' : 'Apply Resolution Runbook'}</span>
            <ArrowRight size={15} aria-hidden="true" />
          </button>
          {applyBlocker ? (
            <p id="ai-reco-apply-hint" className="mt-2 text-[10px] text-muted">
              {applyBlocker}
            </p>
          ) : (
            runbook && (
              <p className="mt-2 flex items-center gap-1.5 text-[10px] text-muted">
                {runbook.requiresApproval && <ShieldCheck size={12} className="gold-text" aria-hidden="true" />}
                {runbook.title}
                {runbook.requiresApproval ? ' · requires Incident Manager approval' : ''}
              </p>
            )
          )}

          <div className="ai-reco-foot">
            <AiSourceBadge result={result} />
            <button type="button" className="link-btn text-[10px]" onClick={generate} disabled={resolution.isFetching}>
              <RefreshCw size={11} aria-hidden="true" /> {resolution.isFetching ? 'Regenerating…' : 'Regenerate'}
            </button>
          </div>
          <p className="ai-notice">
            {result.notice}
            {result.source !== 'LLM' && result.fallbackReason ? ` (${result.fallbackReason})` : ''}
            <span className="muted-text"> · {formatTime(result.generatedAt)}</span>
          </p>
        </>
      )}

      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title={`Apply “${runbook?.title ?? ''}”?`}
        message={
          runbook?.requiresApproval ? (
            <span className="inline-flex items-start gap-2">
              <ShieldCheck size={16} className="gold-text" aria-hidden="true" /> This runbook requires approval. The execution is sent to an Incident Manager and
              its steps unlock once approved.
            </span>
          ) : (
            `The runbook is started against ${incident.number}; its steps are tracked in the Runbooks panel and recorded in the activity log.`
          )
        }
        confirmLabel={runbook?.requiresApproval ? 'Request approval' : 'Start runbook'}
        pending={start.isPending}
        error={start.isError ? errorMessage(start.error) : null}
        onConfirm={apply}
      />
    </section>
  );
}
