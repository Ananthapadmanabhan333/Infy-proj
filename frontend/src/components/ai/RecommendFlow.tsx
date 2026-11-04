import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, BookOpen, CheckCircle2, ExternalLink, FileText, PlayCircle, ShieldCheck, TrendingUp } from 'lucide-react';
import { useAiResolution } from '../../api/ai';
import { useStartRunbook } from '../../api/runbooks';
import { errorMessage } from '../../lib/api';
import type { IncidentSummary, ResolutionSuggestion } from '../../lib/types';
import { AiResultCard } from '../AiResultCard';
import { Badge } from '../Badge';
import { ConfirmDialog } from '../ConfirmDialog';
import { IncidentPicker } from '../incidents/IncidentPicker';
import { RatingSummary } from '../StarRating';
import { useToast } from '../Toast';
import { aiErrorMessage } from './aiUtils';

function confidenceTone(confidence: number) {
  if (confidence >= 75) return 'success' as const;
  if (confidence >= 50) return 'warning' as const;
  return 'neutral' as const;
}

function RunbookBlock({ runbook, incident, canStart }: { runbook: NonNullable<ResolutionSuggestion['runbook']>; incident: IncidentSummary; canStart: boolean }) {
  const start = useStartRunbook();
  const toast = useToast();
  const [confirming, setConfirming] = useState(false);
  return (
    <div className="nx-runbook">
      <FileText size={16} aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <small>Suggested runbook</small>
        <Link to={`/runbooks/${runbook.id}`}>{runbook.title}</Link>
        {runbook.requiresApproval && (
          <span className="mt-1 flex items-center gap-1 text-[10px] text-warning">
            <ShieldCheck size={11} /> Requires Incident Manager approval before it runs
          </span>
        )}
      </div>
      {canStart && (
        <button type="button" className="gold-btn compact" onClick={() => setConfirming(true)}>
          <PlayCircle size={14} /> Apply runbook
        </button>
      )}
      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title="Apply resolution runbook"
        message={
          runbook.requiresApproval
            ? `“${runbook.title}” is approval-gated: starting it on ${incident.number} sends it to an Incident Manager for approval first.`
            : `Start “${runbook.title}” on ${incident.number}? Its steps are then tracked on the incident.`
        }
        confirmLabel={runbook.requiresApproval ? 'Request approval' : 'Start runbook'}
        pending={start.isPending}
        error={start.isError ? errorMessage(start.error) : null}
        onConfirm={() =>
          start.mutate(
            { runbookId: runbook.id, incidentId: incident.id },
            {
              onSuccess: (execution) => {
                setConfirming(false);
                const message =
                  execution?.status === 'PENDING_APPROVAL' ? `${runbook.title} sent for approval` : `${runbook.title} started on ${incident.number}`;
                toast.success(message, { action: { label: 'Open incident', to: `/incidents/${incident.id}` } });
              },
            },
          )
        }
      />
    </div>
  );
}

function SuggestionView({ data, incident, canStartRunbook }: { data: ResolutionSuggestion; incident: IncidentSummary; canStartRunbook: boolean }) {
  const confidence = Math.max(0, Math.min(100, Math.round(data.confidence)));
  return (
    <div className="grid gap-3">
      <div>
        <h5 className="nx-sub">Likely cause</h5>
        <p>{data.likelyCause}</p>
        <div className="nx-meter mt-2" aria-hidden="true">
          <i style={{ width: `${confidence}%` }} />
        </div>
      </div>
      {data.steps.length > 0 && (
        <div>
          <h5 className="nx-sub">Recommended actions</h5>
          <ol className="nx-checklist">
            {data.steps.map((step, index) => (
              <li key={`${index}-${step}`}>
                <CheckCircle2 size={13} aria-hidden="true" />
                <span>{step}</span>
              </li>
            ))}
          </ol>
        </div>
      )}
      {data.runbook && <RunbookBlock runbook={data.runbook} incident={incident} canStart={canStartRunbook} />}
      {data.articles.length > 0 && (
        <div>
          <h5 className="nx-sub">Related knowledge</h5>
          <ul className="nx-links">
            {data.articles.map((article) => (
              <li key={article.id}>
                <BookOpen size={13} aria-hidden="true" />
                <Link to={`/knowledge/${article.id}`}>{article.title}</Link>
                <RatingSummary value={article.averageRating} count={article.ratingCount} />
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

/** Recommend tile: pick an incident → resolution suggestion (likely cause, confidence, steps, runbook, articles). */
export function RecommendFlow({ canStartRunbook }: { canStartRunbook: boolean }) {
  const [incident, setIncident] = useState<IncidentSummary | null>(null);
  const resolution = useAiResolution(incident?.id ?? 0, false);
  const queryClient = useQueryClient();

  // A new recommendation appears in "Recent Analyses".
  useEffect(() => {
    if (resolution.dataUpdatedAt) void queryClient.invalidateQueries({ queryKey: ['ai', 'interactions'] });
  }, [resolution.dataUpdatedAt, queryClient]);

  const result = incident ? resolution.data : undefined;
  const confidence = result ? Math.max(0, Math.min(100, Math.round(result.data.confidence))) : 0;

  return (
    <div className="grid gap-3">
      <div className="nx-flow-form">
        <IncidentPicker label="Incident to resolve" value={incident} onChange={setIncident} />
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className="gold-btn compact" onClick={() => void resolution.refetch()} disabled={!incident || resolution.isFetching}>
            <TrendingUp size={14} /> {resolution.isFetching ? 'Working…' : result ? 'Recommend again' : 'Recommend resolution'}
          </button>
          {incident && (
            <Link className="subtle-btn compact" to={`/incidents/${incident.id}`}>
              <ExternalLink size={13} /> Open {incident.number}
            </Link>
          )}
        </div>
      </div>

      {incident && resolution.isFetching && !result && (
        <p className="nx-working" role="status">
          <TrendingUp size={13} className="animate-pulse" /> Matching {incident.number} against resolved incidents, runbooks and knowledge…
        </p>
      )}
      {incident && resolution.isError && (
        <div className="callout red" role="alert">
          <AlertTriangle size={16} />
          <div>Recommendation failed: {aiErrorMessage(resolution.error)}</div>
        </div>
      )}
      {incident && result && (
        <AiResultCard
          result={result}
          title="SA AI Recommendation"
          badge={<Badge value="match" tone={confidenceTone(confidence)} label={`${confidence}% Match`} />}
        >
          <SuggestionView data={result.data} incident={incident} canStartRunbook={canStartRunbook} />
        </AiResultCard>
      )}
    </div>
  );
}
