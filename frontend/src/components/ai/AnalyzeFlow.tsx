import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, ExternalLink, Sparkle } from 'lucide-react';
import { useAiRootCause, useAiSummarize } from '../../api/ai';
import type { IncidentSummary, RootCauseAnalysis } from '../../lib/types';
import { AiResultCard } from '../AiResultCard';
import { Badge, PriorityBadge } from '../Badge';
import { IncidentPicker } from '../incidents/IncidentPicker';
import { aiErrorMessage } from './aiUtils';

const LIKELIHOOD_TONE = { HIGH: 'critical', MEDIUM: 'warning', LOW: 'neutral' } as const;

function Hypotheses({ hypotheses }: { hypotheses: RootCauseAnalysis['hypotheses'] }) {
  if (!hypotheses.length) return <p className="muted-text">No root-cause hypothesis could be formed from the available data.</p>;
  return (
    <ol className="nx-hypotheses">
      {hypotheses.map((hypothesis, index) => (
        <li key={`${hypothesis.cause}-${index}`}>
          <div className="flex flex-wrap items-center gap-2">
            <b>{hypothesis.cause}</b>
            <Badge value={hypothesis.likelihood} tone={LIKELIHOOD_TONE[hypothesis.likelihood]} label={`${hypothesis.likelihood.charAt(0)}${hypothesis.likelihood.slice(1).toLowerCase()} likelihood`} />
          </div>
          {hypothesis.evidence.length > 0 && (
            <>
              <small>Evidence</small>
              <ul>
                {hypothesis.evidence.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </>
          )}
          {hypothesis.suggestedChecks.length > 0 && (
            <>
              <small>Suggested checks</small>
              <ul>
                {hypothesis.suggestedChecks.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </>
          )}
        </li>
      ))}
    </ol>
  );
}

/** Analyze tile: pick an incident → AI summary (readers) and root-cause hypotheses (staff). */
export function AnalyzeFlow({ canRootCause }: { canRootCause: boolean }) {
  const [incident, setIncident] = useState<IncidentSummary | null>(null);
  const summarize = useAiSummarize();
  const rootCause = useAiRootCause();
  const queryClient = useQueryClient();
  const pending = summarize.isPending || rootCause.isPending;
  const refreshRecent = () => void queryClient.invalidateQueries({ queryKey: ['ai', 'interactions'] });

  const choose = (next: IncidentSummary | null) => {
    setIncident(next);
    summarize.reset();
    rootCause.reset();
  };

  const run = () => {
    if (!incident) return;
    summarize.mutate(incident.id, { onSuccess: refreshRecent });
    if (canRootCause) rootCause.mutate(incident.id, { onSuccess: refreshRecent });
  };

  return (
    <div className="grid gap-3">
      <div className="nx-flow-form">
        <IncidentPicker label="Incident to analyse" value={incident} onChange={choose} />
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className="gold-btn compact" onClick={run} disabled={!incident || pending}>
            <Sparkle size={14} /> {pending ? 'Analysing…' : summarize.data ? 'Analyse again' : 'Analyse incident'}
          </button>
          {incident && (
            <Link className="subtle-btn compact" to={`/incidents/${incident.id}`}>
              <ExternalLink size={13} /> Open {incident.number}
            </Link>
          )}
          {incident && <PriorityBadge priority={incident.priority} />}
        </div>
      </div>
      {!canRootCause && <p className="text-[10px] text-muted">Root-cause hypotheses are available to support staff; you can generate the summary.</p>}

      {pending && (
        <p className="nx-working" role="status">
          <Sparkle size={13} className="animate-pulse" /> Analysing {incident?.number}…
        </p>
      )}

      <div className="grid gap-3 lg:grid-cols-2">
        {summarize.isError && (
          <div className="callout red" role="alert">
            <AlertTriangle size={16} />
            <div>Summary failed: {aiErrorMessage(summarize.error)}</div>
          </div>
        )}
        {summarize.data && (
          <AiResultCard result={summarize.data} title="Incident summary">
            <p className="whitespace-pre-wrap">{summarize.data.data.summary}</p>
            {summarize.data.data.keyFacts.length > 0 && (
              <>
                <h5 className="nx-sub">Key facts</h5>
                <ul>
                  {summarize.data.data.keyFacts.map((fact) => (
                    <li key={fact}>{fact}</li>
                  ))}
                </ul>
              </>
            )}
            {summarize.data.data.nextSteps.length > 0 && (
              <>
                <h5 className="nx-sub">Next steps</h5>
                <ul>
                  {summarize.data.data.nextSteps.map((step) => (
                    <li key={step}>{step}</li>
                  ))}
                </ul>
              </>
            )}
          </AiResultCard>
        )}
        {rootCause.isError && (
          <div className="callout red" role="alert">
            <AlertTriangle size={16} />
            <div>Root-cause analysis failed: {aiErrorMessage(rootCause.error)}</div>
          </div>
        )}
        {rootCause.data && (
          <AiResultCard result={rootCause.data} title="Root-cause hypotheses">
            <Hypotheses hypotheses={rootCause.data.data.hypotheses} />
          </AiResultCard>
        )}
      </div>
    </div>
  );
}
