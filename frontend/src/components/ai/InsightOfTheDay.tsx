import { useEffect } from 'react';
import { AlertTriangle, RefreshCw, Sparkles } from 'lucide-react';
import { useAiInsights, useRegenerateAiInsights } from '../../api/ai';
import { AiSourceBadge } from '../AiResultCard';
import { Badge } from '../Badge';
import { Sparkline } from '../Sparkline';
import { Skeleton } from '../States';
import { aiErrorMessage, CONFIDENCE_LABELS, CONFIDENCE_TONES, hourlySeries, peakLabel, topInsight } from './aiUtils';

/**
 * "Insight of the Day": the most significant operational insight with its confidence, plus a sparkline of
 * incidents created by hour of day (last 30 days). Generated once when the page opens (shared cache with the
 * Command Center), refreshable on demand.
 */
export function InsightOfTheDay() {
  const insights = useAiInsights();
  const regenerate = useRegenerateAiInsights();
  const busy = insights.isFetching || regenerate.isPending;

  useEffect(() => {
    if (!insights.data && !insights.isFetching && !insights.isError) void insights.refetch();
    // Generate once on mount; later refreshes are explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const result = insights.data;
  const insight = topInsight(result?.data.insights);
  const series = hourlySeries(result?.data.hourly);
  const peak = peakLabel(series);
  const others = (result?.data.insights.length ?? 0) - (insight ? 1 : 0);

  return (
    <section className="panel nx-insight" aria-labelledby="nx-insight-title">
      <div className="nx-insight-head">
        <h3 id="nx-insight-title">
          <Sparkles size={15} aria-hidden="true" /> Insight of the Day
        </h3>
        {insight && <Badge value={insight.confidence} tone={CONFIDENCE_TONES[insight.confidence]} label={CONFIDENCE_LABELS[insight.confidence]} />}
        <button
          type="button"
          className="icon-btn sm ml-auto"
          onClick={() => (result ? regenerate.mutate() : void insights.refetch())}
          disabled={busy}
          aria-label={result ? 'Refresh insight' : 'Generate insight'}
          title={result ? 'Refresh insight' : 'Generate insight'}
        >
          <RefreshCw size={14} className={busy ? 'animate-spin' : ''} />
        </button>
      </div>

      {insights.isFetching && !result && (
        <div className="grid gap-2" role="status" aria-busy="true">
          <span className="sr-only">Generating insight…</span>
          <Skeleton height={14} width="80%" />
          <Skeleton height={14} width="60%" />
          <Skeleton height={48} />
        </div>
      )}
      {insights.isError && !result && (
        <div className="callout red" role="alert">
          <AlertTriangle size={16} />
          <div>
            Insight unavailable: {aiErrorMessage(insights.error)}{' '}
            <button type="button" className="link-btn text-[11px]" onClick={() => void insights.refetch()}>
              Retry
            </button>
          </div>
        </div>
      )}
      {!insights.isFetching && !insights.isError && !result && <p className="muted-text text-[11px]">No insight generated yet.</p>}

      {result && (
        <div className="nx-insight-body">
          <div className="min-w-0">
            {insight ? (
              <>
                <p className="nx-insight-text">
                  {insight.metric && <b>{insight.metric} · </b>}
                  {insight.title}
                </p>
                <p className="nx-insight-detail">{insight.detail}</p>
              </>
            ) : (
              <p className="nx-insight-text">No notable signals in the current operational data.</p>
            )}
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <AiSourceBadge result={result} />
              {others > 0 && (
                <span className="text-[10px] text-muted">
                  +{others} more insight{others === 1 ? '' : 's'}
                </span>
              )}
            </div>
          </div>
          <div className="nx-insight-spark">
            <Sparkline
              data={series}
              width={190}
              height={64}
              label={`Incidents created by hour of day over the last 30 days${peak ? `, ${peak}` : ''}`}
            />
            <small>{peak ?? 'No incidents in the last 30 days'}</small>
            <small className="muted-text">By hour of day · last 30 days</small>
          </div>
        </div>
      )}
      {result && <p className="ai-notice">{result.notice}</p>}
    </section>
  );
}
