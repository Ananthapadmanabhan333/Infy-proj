import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Clock, Sparkles, TrendingDown, TrendingUp } from 'lucide-react';
import {
  useAiCmdbImpact,
  useAiProblemCorrelation,
  useAiRunbookRecommendations,
  useAiSlaRisk,
  useAiTrends,
} from '../../api/ai';
import { toApiError } from '../../lib/api';
import { formatDateTime, formatMinutes, formatNumber, formatPercent, titleCase } from '../../lib/format';
import { RISK_LEVEL_TONES } from '../../lib/response';
import { AiResultCard } from '../AiResultCard';
import { ToneBadge } from '../itsm/ItsmParts';
import '../oncall/oncall.css';
import { Panel } from '../Panel';
import { ErrorState } from '../States';

/** AI errors: a rate limit (429) is explained with its retry time; anything else is a compact error state. */
export function AiErrorNotice({ error }: { error: unknown }) {
  const apiError = toApiError(error);
  if (apiError.status === 429) {
    return (
      <div className="callout" role="status">
        <Clock size={16} aria-hidden="true" />
        <div>
          <b>SA AI rate limit reached.</b> {apiError.retryAfterSeconds ? `Try again in ${apiError.retryAfterSeconds} seconds.` : 'Wait a moment and try again.'} Nothing was changed.
        </div>
      </div>
    );
  }
  return <ErrorState error={error} compact />;
}

function RunButton({ pending, hasResult, label, onClick }: { pending: boolean; hasResult: boolean; label: string; onClick: () => void }) {
  return (
    <button type="button" className="subtle-btn compact" onClick={onClick} disabled={pending}>
      <Sparkles size={13} aria-hidden="true" /> {pending ? 'Analysing…' : hasResult ? 'Analyse again' : label}
    </button>
  );
}

function Basis({ text }: { text: string | null | undefined }) {
  if (!text) return null;
  return <p className="mt-2 mb-0 text-[10px] text-muted">{text}</p>;
}

function List({ items, label }: { items: string[]; label: string }) {
  if (!items.length) return null;
  return (
    <div className="mt-2">
      <b className="text-[11px]">{label}</b>
      <ul className="m-0 mt-1 grid gap-1 pl-4 text-[11px]">
        {items.map((item, index) => (
          <li key={index} className="break-words">
            {item}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Problem detail: unlinked incidents, changes and alert groups correlated with the problem. Suggestions only — linking stays manual. */
export function ProblemCorrelationPanel({ problemId }: { problemId: number }) {
  const run = useAiProblemCorrelation();
  const result = run.data;
  const data = result?.data;
  return (
    <Panel
      title="SA AI correlation"
      subtitle="Incidents, changes and alert groups that may belong to this problem — link them yourself"
      actions={<RunButton pending={run.isPending} hasResult={Boolean(result)} label="Find correlations" onClick={() => run.mutate(problemId)} />}
    >
      {run.isError && <AiErrorNotice error={run.error} />}
      {!result && !run.isError && <p className="m-0 text-[11px] text-muted">Scores unlinked incidents of the last 90 days by text similarity, service and category.</p>}
      {result && data && (
        <AiResultCard result={result} title="Correlated records">
          {data.summary && <p className="m-0 mb-2 text-[12px]">{data.summary}</p>}
          {data.candidates.length === 0 ? (
            <p className="m-0 text-[11px] text-muted">No unlinked incident scored above the threshold.</p>
          ) : (
            <ul className="m-0 grid list-none gap-1 p-0" aria-label="Correlated incidents">
              {data.candidates.map((candidate) => (
                <li key={candidate.incidentId} className="list-row !items-start">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <Link to={`/incidents/${candidate.incidentId}`} className="id-cell">
                        {candidate.number}
                      </Link>
                      <span className="break-words">{candidate.title}</span>
                    </b>
                    <small className="block">
                      {titleCase(candidate.status)} · {formatDateTime(candidate.createdAt)}
                      {candidate.reasons.length ? ` · ${candidate.reasons.join('; ')}` : ''}
                    </small>
                  </div>
                  <span className="id-cell shrink-0">{Math.round(candidate.score * 100)}%</span>
                </li>
              ))}
            </ul>
          )}
          {data.changes.length > 0 && (
            <div className="mt-2">
              <b className="text-[11px]">Changes before the incidents</b>
              <ul className="m-0 mt-1 grid list-none gap-1 p-0">
                {data.changes.map((change) => (
                  <li key={change.changeId} className="text-[11px]">
                    <Link to={`/changes/${change.changeId}`} className="id-cell">
                      {change.number}
                    </Link>{' '}
                    {change.title} · {titleCase(change.status)} · {formatNumber(change.incidentsWithin48h)} incidents within 48 h
                  </li>
                ))}
              </ul>
            </div>
          )}
          {data.alertGroups.length > 0 && (
            <List label="Alert groups on the same services (30 days)" items={data.alertGroups.map((group) => `${group.title} — ${formatNumber(group.alerts)} alerts${group.maxSeverity ? `, up to ${group.maxSeverity.toLowerCase()}` : ''}`)} />
          )}
          <Basis text={data.basis} />
        </AiResultCard>
      )}
    </Panel>
  );
}

/** CI detail: what fails if this CI fails (impacted services, upstream/downstream counts, risk). */
export function CmdbImpactPanel({ ciId }: { ciId: number }) {
  const run = useAiCmdbImpact();
  const result = run.data;
  const data = result?.data;
  return (
    <Panel
      title="SA AI impact analysis"
      subtitle="CMDB traversal in both directions (depth 4), explained"
      actions={<RunButton pending={run.isPending} hasResult={Boolean(result)} label="Analyse impact" onClick={() => run.mutate(ciId)} />}
    >
      {run.isError && <AiErrorNotice error={run.error} />}
      {!result && !run.isError && <p className="m-0 text-[11px] text-muted">Rates the risk of a failure of this configuration item for change planning.</p>}
      {result && data && (
        <AiResultCard result={result} title="Impact" badge={<ToneBadge value={data.riskLevel} label={`${titleCase(data.riskLevel)} risk`} tone={RISK_LEVEL_TONES[data.riskLevel] ?? 'neutral'} />}>
          {data.summary && <p className="m-0 mb-2 text-[12px]">{data.summary}</p>}
          <p className="m-0 text-[11px] text-muted">
            {formatNumber(data.upstreamCount)} dependents · {formatNumber(data.downstreamCount)} dependencies · {formatNumber(data.openIncidents)} open incidents · {formatNumber(data.openAlerts)} open alerts
            {data.truncated ? ' · traversal truncated' : ''}
          </p>
          {data.impactedServices.length > 0 && (
            <List label="Impacted business services" items={data.impactedServices.map((service) => `${service.name} (${service.code}) · tier ${service.tier} · ${titleCase(service.status)}`)} />
          )}
          <List label="Critical dependents" items={data.criticalUpstream} />
          <List label="Recommendations" items={data.recommendations} />
          <Basis text={data.basis} />
        </AiResultCard>
      )}
    </Panel>
  );
}

/** Incident detail: runbooks worth running, scored. Never starts a runbook — the Runbooks panel does that, with confirmation. */
export function RunbookRecommendationsPanel({ incidentId }: { incidentId: number }) {
  const run = useAiRunbookRecommendations();
  const result = run.data;
  const data = result?.data;
  return (
    <Panel title="Runbook recommendations" subtitle="SA AI · suggestions only" actions={<RunButton pending={run.isPending} hasResult={Boolean(result)} label="Recommend" onClick={() => run.mutate(incidentId)} />}>
      {run.isError && <AiErrorNotice error={run.error} />}
      {!result && !run.isError && <p className="m-0 text-[11px] text-muted">Scores runbooks by service, category, text and earlier outcomes. Start one from the Runbooks panel.</p>}
      {result && data && (
        <AiResultCard result={result} title="Recommended runbooks" compact>
          {data.rationale && <p className="m-0 mb-2 text-[11px]">{data.rationale}</p>}
          {data.recommendations.length === 0 ? (
            <p className="m-0 text-[11px] text-muted">No runbook scored high enough.</p>
          ) : (
            <ol className="m-0 grid list-none gap-1 p-0" aria-label="Recommended runbooks">
              {data.recommendations.map((recommendation) => (
                <li key={recommendation.runbookId} className="list-row !items-start">
                  <div className="min-w-0">
                    <b className="flex flex-wrap items-center gap-1.5">
                      <Link to={`/runbooks/${recommendation.runbookId}`}>{recommendation.title}</Link>
                      {recommendation.requiresApproval && <ToneBadge value="approval" label="Requires approval" tone="warning" />}
                    </b>
                    <small className="block">
                      {recommendation.estimatedMinutes ? `about ${formatMinutes(recommendation.estimatedMinutes)} · ` : ''}
                      {recommendation.reasons.join('; ')}
                    </small>
                  </div>
                  <span className="id-cell shrink-0">{recommendation.score}</span>
                </li>
              ))}
            </ol>
          )}
          <Basis text={data.basis} />
        </AiResultCard>
      )}
    </Panel>
  );
}

/** Incident detail: predictive SLA risk. The probability is computed from history (labelled), never generated. */
export function SlaRiskAiPanel({ incidentId }: { incidentId: number }) {
  const run = useAiSlaRisk();
  const result = run.data;
  const data = result?.data;
  return (
    <Panel title="Predictive SLA risk" subtitle="A statistic from historical clocks, not an AI estimate" actions={<RunButton pending={run.isPending} hasResult={Boolean(result)} label="Assess risk" onClick={() => run.mutate(incidentId)} />}>
      {run.isError && <AiErrorNotice error={run.error} />}
      {!result && !run.isError && <p className="m-0 text-[11px] text-muted">Breach probability from comparable resolution clocks of the same priority.</p>}
      {result && data && (
        <AiResultCard result={result} title="SLA risk" compact badge={<ToneBadge value={data.riskLevel} label={data.riskLevel === 'N/A' ? 'Nothing to predict' : `${titleCase(data.riskLevel)} risk`} tone={RISK_LEVEL_TONES[data.riskLevel] ?? 'neutral'} />}>
          <div className="flex items-baseline gap-2">
            <b className="text-[20px] gold-text">{data.probability === null ? '—' : formatPercent(data.probability * 100, 0)}</b>
            <span className="text-[11px] text-muted">breach probability</span>
          </div>
          <div className="risk-meter mt-1" data-level={data.riskLevel} aria-hidden="true">
            <span style={{ width: `${Math.round((data.probability ?? 0) * 100)}%` }} />
          </div>
          {data.probabilityLabel && <p className="m-0 mt-2 text-[11px]">{data.probabilityLabel}</p>}
          <p className="m-0 mt-1 text-[10px] text-muted">
            {formatPercent(data.elapsedPercent, 0)} of the target elapsed · {data.remainingMinutes >= 0 ? `${formatMinutes(data.remainingMinutes)} left` : `${formatMinutes(-data.remainingMinutes)} over`}
            {data.policyName ? ` · ${data.policyName}` : ''}
          </p>
          <List label="Factors" items={data.factors} />
          <List label="Recommendations" items={data.recommendations} />
          {data.method && <Basis text={data.method} />}
        </AiResultCard>
      )}
    </Panel>
  );
}

const TREND_WINDOWS = [7, 14, 28, 60, 90];

/** SA AI page: statistically significant changes against the previous window, with the narrative labelled by source. */
export function TrendsPanel() {
  const run = useAiTrends();
  const [days, setDays] = useState(28);
  const result = run.data;
  const data = result?.data;
  const trends = data ? [...data.trends].sort((a, b) => Number(b.significant) - Number(a.significant) || Math.abs(b.zScore ?? 0) - Math.abs(a.zScore ?? 0)) : [];
  return (
    <Panel
      title="Trend analysis"
      subtitle="Incidents, alerts, failed changes and MTTR against the previous window (z-test, |z| ≥ 2)"
      actions={
        <span className="flex flex-wrap items-center gap-2">
          <select className="control sm w-auto" aria-label="Trend window" value={days} onChange={(event) => setDays(Number(event.target.value))}>
            {TREND_WINDOWS.map((window) => (
              <option key={window} value={window}>
                Last {window} days
              </option>
            ))}
          </select>
          <RunButton pending={run.isPending} hasResult={Boolean(result)} label="Analyse trends" onClick={() => run.mutate(days)} />
        </span>
      }
    >
      {run.isError && <AiErrorNotice error={run.error} />}
      {!result && !run.isError && <p className="m-0 text-[11px] text-muted">Compares the last {days} days with the {days} days before.</p>}
      {result && data && (
        <AiResultCard result={result} title={`Trends · ${data.from} – ${data.to}`}>
          {data.narrative && <p className="m-0 mb-2 text-[12px]">{data.narrative}</p>}
          <List label="Highlights" items={data.highlights} />
          {trends.length > 0 && (
            <ul className="m-0 mt-2 grid list-none gap-1 p-0" aria-label="Trends">
              {trends.slice(0, 12).map((trend, index) => (
                <li key={`${trend.metric}-${trend.dimension}-${index}`} className="list-row !py-1.5">
                  <span className={trend.direction === 'UP' ? 'red-text' : trend.direction === 'DOWN' ? 'green-text' : 'text-muted'} aria-hidden="true">
                    {trend.direction === 'UP' ? <TrendingUp size={14} /> : trend.direction === 'DOWN' ? <TrendingDown size={14} /> : '–'}
                  </span>
                  <span className="min-w-0 flex-1 break-words text-[11px]">
                    <b>{titleCase(trend.metric)}</b>
                    {trend.dimension ? ` · ${trend.dimension}` : ''}: {formatNumber(trend.current)} vs {formatNumber(trend.previous)}
                    {trend.changePercent !== null ? ` (${trend.changePercent > 0 ? '+' : ''}${formatPercent(trend.changePercent, 0)})` : ''}
                  </span>
                  {trend.significant && <ToneBadge value="significant" label="Significant" tone="warning" />}
                </li>
              ))}
            </ul>
          )}
          <Basis text={data.basis} />
        </AiResultCard>
      )}
    </Panel>
  );
}
