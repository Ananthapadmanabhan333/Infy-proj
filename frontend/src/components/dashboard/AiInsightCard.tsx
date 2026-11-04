import { useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { ChevronRight, RefreshCw, Sparkles } from 'lucide-react';
import { useAiInsights, useRegenerateAiInsights } from '../../api/ai';
import type { Confidence, OperationalInsight, Severity } from '../../lib/types';
import { AiResultCard } from '../AiResultCard';
import { Badge } from '../Badge';
import { Panel } from '../Panel';
import { ErrorState, Skeleton } from '../States';
import type { Tone } from '../../lib/constants';
import './dashboard.css';

const SEVERITY_RANK: Record<Severity, number> = { CRITICAL: 0, WARNING: 1, INFO: 2, SUCCESS: 3 };
const CONFIDENCE_RANK: Record<Confidence, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };
const CONFIDENCE_TONE: Record<Confidence, Tone> = { HIGH: 'success', MEDIUM: 'gold', LOW: 'neutral' };
const CONFIDENCE_LABEL: Record<Confidence, string> = { HIGH: 'High Confidence', MEDIUM: 'Medium Confidence', LOW: 'Low Confidence' };

/** The insight to feature: most severe first, then the most confident; API order breaks ties. */
export function topInsight(insights: OperationalInsight[]): OperationalInsight | null {
  if (!insights.length) return null;
  return insights
    .map((insight, index) => ({ insight, index }))
    .sort(
      (a, b) =>
        (SEVERITY_RANK[a.insight.severity] ?? 9) - (SEVERITY_RANK[b.insight.severity] ?? 9) ||
        (CONFIDENCE_RANK[a.insight.confidence] ?? 9) - (CONFIDENCE_RANK[b.insight.confidence] ?? 9) ||
        a.index - b.index,
    )[0].insight;
}

export function ConfidenceBadge({ confidence }: { confidence: Confidence | null | undefined }) {
  if (!confidence) return null;
  return <Badge value={confidence} label={CONFIDENCE_LABEL[confidence] ?? confidence} tone={CONFIDENCE_TONE[confidence] ?? 'neutral'} className="pill" />;
}

/**
 * Command Center "AI Insight" card. Insights are generated once per session cache (POST /ai/insights may
 * call an LLM and is rate limited), always shown with their LLM / deterministic-fallback source label.
 */
export function AiInsightCard() {
  const insights = useAiInsights();
  const regenerate = useRegenerateAiInsights();
  const busy = insights.isFetching || regenerate.isPending;
  const requested = useRef(false);

  useEffect(() => {
    if (requested.current) return;
    requested.current = true;
    if (!insights.data && !insights.isFetching) void insights.refetch();
    // Generate once on mount when nothing is cached; later refreshes are explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const result = insights.data;
  const top = result ? topInsight(result.data.insights) : null;
  const others = result ? Math.max(0, result.data.insights.length - 1) : 0;

  return (
    <Panel
      className="ai-panel cc-ai-card"
      title={
        <span className="inline-flex items-center gap-2">
          <Sparkles size={15} className="gold-text" aria-hidden="true" /> AI Insight
        </span>
      }
      subtitle="SA AI, grounded in live incident data"
      actions={
        <>
          {top && <ConfidenceBadge confidence={top.confidence} />}
          <button
            type="button"
            className="icon-btn sm"
            onClick={() => (result ? regenerate.mutate() : void insights.refetch())}
            disabled={busy}
            aria-label={result ? 'Refresh AI insight' : 'Generate AI insight'}
            title={result ? 'Refresh AI insight' : 'Generate AI insight'}
          >
            <RefreshCw size={14} className={busy ? 'animate-spin' : ''} aria-hidden="true" />
          </button>
        </>
      }
    >
      {insights.isFetching && !result && (
        <div className="grid gap-2" role="status" aria-live="polite">
          <span className="sr-only">Analysing live data…</span>
          <Skeleton height={12} width="55%" />
          <Skeleton height={34} />
          <Skeleton height={10} width="40%" />
        </div>
      )}
      {insights.isError && !insights.isFetching && (
        <ErrorState error={insights.error} onRetry={() => void insights.refetch()} title="AI insight unavailable" compact />
      )}
      {!insights.isFetching && !insights.isError && !result && (
        <p className="muted-text text-[11px]">Generate an insight from current incidents, SLAs and services.</p>
      )}
      {result && (
        <AiResultCard result={result} title={top?.title ?? 'No notable signals'}>
          {top ? (
            <p className="cc-insight-text">
              {top.detail}
              {top.metric && <span className="cc-insight-metric"> · {top.metric}</span>}
            </p>
          ) : (
            <p className="cc-insight-text">Nothing unusual in the current incident, SLA and service data.</p>
          )}
        </AiResultCard>
      )}
      <Link to="/sa-ai" className="panel-action cc-ai-link">
        {others > 0 ? `${others} more insight${others === 1 ? '' : 's'} in SA AI` : 'Open SA AI'} <ChevronRight size={14} aria-hidden="true" />
      </Link>
    </Panel>
  );
}
