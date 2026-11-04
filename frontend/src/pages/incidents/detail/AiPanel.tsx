import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, BookOpen, Brain, Copy, FileSearch, GitMerge, Hourglass, ListChecks, Sparkles, Tags } from 'lucide-react';
import { useAiAction, useAiClassify, useAiDuplicates, useAiKnowledge, useAiRootCause, useAiSummarize } from '../../../api/ai';
import { useLinkParent, useUpdateIncident } from '../../../api/incidents';
import { AiResultCard } from '../../../components/AiResultCard';
import { Badge, PriorityBadge, StatusBadge } from '../../../components/Badge';
import { Panel } from '../../../components/Panel';
import { useToast } from '../../../components/Toast';
import { errorMessage } from '../../../lib/api';
import { formatPercent, titleCase } from '../../../lib/format';
import type { ArticleSummary, ClassificationSuggestion, IncidentDetail } from '../../../lib/types';

interface AiPanelProps {
  incident: IncidentDetail;
  staff: boolean;
  canSummarize: boolean;
  canResolve: boolean;
  onUseArticle: (article: ArticleSummary) => void;
}

/**
 * "AI Recommendation" tab: summary, classification with priority recommendation, duplicates, knowledge and root cause.
 * Every result shows its source; sensitive proposals (escalating to Critical) are routed through POST /ai/actions for approval.
 */
export function IncidentAiPanel({ incident, staff, canSummarize, canResolve, onUseArticle }: AiPanelProps) {
  const summarize = useAiSummarize();
  const classify = useAiClassify();
  const rootCause = useAiRootCause();
  const knowledge = useAiKnowledge();
  const duplicates = useAiDuplicates();
  const update = useUpdateIncident(incident.id);
  const linkParent = useLinkParent(incident.id);
  const aiAction = useAiAction();
  const toast = useToast();
  const [approvalNotice, setApprovalNotice] = useState<string | null>(null);

  const applySuggestion = (suggestion: ClassificationSuggestion) => {
    const escalatesToCritical = suggestion.priority === 'CRITICAL' && incident.priority !== 'CRITICAL';
    if (escalatesToCritical) {
      const params: Record<string, string> = {
        priority: 'CRITICAL',
        category: suggestion.category,
        impact: suggestion.impact,
        urgency: suggestion.urgency,
      };
      if (suggestion.serviceId) params.serviceId = String(suggestion.serviceId);
      aiAction.mutate(
        { incidentId: incident.id, actionType: 'SET_PRIORITY', params, rationale: suggestion.rationale },
        {
          onSuccess: (approval) => {
            setApprovalNotice(`Priority escalation to Critical sent for Incident Manager approval${approval.referenceNumber ? ` (${approval.referenceNumber})` : ''}.`);
            toast.info('Sent for Incident Manager approval');
          },
          onError: (error) => toast.error(error),
        },
      );
      return;
    }
    update.mutate(
      {
        title: incident.title,
        description: incident.description,
        category: suggestion.category,
        impact: suggestion.impact,
        urgency: suggestion.urgency,
        serviceId: suggestion.serviceId ?? incident.serviceId,
        affectedUsers: incident.affectedUsers,
      },
      {
        onSuccess: (data) => toast.success(`Suggestion applied — priority is now ${titleCase(data?.priority ?? suggestion.priority)}`),
        onError: (error) => toast.error(error),
      },
    );
  };

  const linkAsChild = (parentId: number, number: string) =>
    linkParent.mutate(parentId, {
      onSuccess: () => toast.success(`${incident.number} linked as a child of ${number}`),
      onError: (error) => toast.error(error),
    });

  const busy = summarize.isPending || classify.isPending || rootCause.isPending || knowledge.isPending || duplicates.isPending;
  const nothingYet = !summarize.data && !classify.data && !rootCause.data && !knowledge.data && !duplicates.data;

  return (
    <Panel
      title={
        <span className="inline-flex items-center gap-2">
          <Sparkles size={15} className="gold-text" aria-hidden="true" /> SA AI analysis
        </span>
      }
      subtitle="Each result is labelled with its source (language model or deterministic engine) and is never applied without your action."
      aria-label="SA AI analysis"
    >
      <div className="incident-ai-tools" role="group" aria-label="SA AI tools">
        {canSummarize && (
          <button type="button" onClick={() => summarize.mutate(incident.id)} disabled={busy}>
            <FileSearch size={14} /> Summarize
          </button>
        )}
        {staff && (
          <button type="button" onClick={() => classify.mutate(incident.id)} disabled={busy}>
            <Tags size={14} /> Classify &amp; prioritise
          </button>
        )}
        {staff && (
          <button type="button" onClick={() => rootCause.mutate(incident.id)} disabled={busy}>
            <Brain size={14} /> Root cause
          </button>
        )}
        <button type="button" onClick={() => knowledge.mutate(incident.id)} disabled={busy}>
          <BookOpen size={14} /> Recommended knowledge
        </button>
        {staff && (
          <button type="button" onClick={() => duplicates.mutate(incident.id)} disabled={busy}>
            <Copy size={14} /> Duplicate suggestions
          </button>
        )}
      </div>
      {busy && (
        <p className="mt-2 text-[11px] text-muted" role="status">
          SA AI is working…
        </p>
      )}
      {nothingYet && !busy && <p className="mt-2 text-[11px] text-muted">Choose an analysis above. Results appear here and stay until you leave the incident.</p>}

      <div className="mt-3 grid gap-3">
        {approvalNotice && (
          <div className="callout" role="status">
            <Hourglass size={16} />
            <div>
              <b>Sent for approval.</b> {approvalNotice}
            </div>
          </div>
        )}
        {[summarize, classify, rootCause, knowledge, duplicates].map((mutation, index) =>
          mutation.isError ? (
            <p key={index} className="field-error" role="alert">
              {errorMessage(mutation.error)}
            </p>
          ) : null,
        )}

        {summarize.data && (
          <AiResultCard result={summarize.data} title="Summary">
            <p>{summarize.data.data.summary}</p>
            {summarize.data.data.keyFacts.length > 0 && (
              <>
                <b className="mt-2 block text-[10px] text-gold-light">Key facts</b>
                <ul>
                  {summarize.data.data.keyFacts.map((fact) => (
                    <li key={fact}>{fact}</li>
                  ))}
                </ul>
              </>
            )}
            {summarize.data.data.nextSteps.length > 0 && (
              <>
                <b className="mt-2 block text-[10px] text-gold-light">Next steps</b>
                <ul>
                  {summarize.data.data.nextSteps.map((stepText) => (
                    <li key={stepText}>{stepText}</li>
                  ))}
                </ul>
              </>
            )}
          </AiResultCard>
        )}

        {classify.data && (
          <AiResultCard
            result={classify.data}
            title="Classification & priority recommendation"
            actions={
              staff && (
                <button type="button" className="gold-btn compact" onClick={() => applySuggestion(classify.data.data)} disabled={update.isPending || aiAction.isPending}>
                  <ListChecks size={13} /> Apply suggestion
                </button>
              )
            }
          >
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-[11px] text-muted">Recommended priority</span>
              <PriorityBadge priority={classify.data.data.priority} />
              {classify.data.data.priority !== incident.priority && (
                <span className="inline-flex items-center gap-1 text-[10px] text-muted">
                  current <PriorityBadge priority={incident.priority} />
                </span>
              )}
              {classify.data.data.priority === incident.priority && <span className="text-[10px] text-muted">(matches the current priority)</span>}
            </div>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <Badge value="outline" label={titleCase(classify.data.data.category)} />
              <span className="text-[10px]">
                Impact {titleCase(classify.data.data.impact)} · Urgency {titleCase(classify.data.data.urgency)}
                {classify.data.data.serviceName ? ` · ${classify.data.data.serviceName}` : ''}
              </span>
              <span className="text-[10px] text-muted">{formatPercent(classify.data.data.confidence * 100, 0)} confidence</span>
            </div>
            <p className="mt-2">{classify.data.data.rationale}</p>
            {classify.data.data.priority === 'CRITICAL' && incident.priority !== 'CRITICAL' && (
              <p className="mt-1 text-[10px] text-gold">Raising the priority to Critical needs Incident Manager approval — applying submits it for approval.</p>
            )}
          </AiResultCard>
        )}

        {rootCause.data && (
          <AiResultCard result={rootCause.data} title="Root-cause hypotheses">
            {rootCause.data.data.hypotheses.length === 0 && <p>No hypotheses could be formed from the available evidence.</p>}
            {rootCause.data.data.hypotheses.map((hypothesis) => (
              <div key={hypothesis.cause} className="mt-2 border-t border-line pt-2 first:mt-0 first:border-0 first:pt-0">
                <div className="flex flex-wrap items-center gap-2">
                  <b>{hypothesis.cause}</b>
                  <Badge value={hypothesis.likelihood} label={`${titleCase(hypothesis.likelihood)} likelihood`} />
                </div>
                {hypothesis.evidence.length > 0 && (
                  <ul>
                    {hypothesis.evidence.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                )}
                {hypothesis.suggestedChecks.length > 0 && (
                  <p className="text-[10px] text-muted">
                    <ArrowRight size={10} className="inline" aria-hidden="true" /> Checks: {hypothesis.suggestedChecks.join(' · ')}
                  </p>
                )}
              </div>
            ))}
          </AiResultCard>
        )}

        {knowledge.data && (
          <AiResultCard result={knowledge.data} title="Recommended knowledge">
            <p>{knowledge.data.data.explanation}</p>
            {knowledge.data.data.articles.length === 0 && <p className="text-muted">No matching articles.</p>}
            {knowledge.data.data.articles.map((article) => (
              <div key={article.id} className="list-row">
                <BookOpen size={14} className="gold-text" />
                <div>
                  <Link to={`/knowledge/${article.id}`}>
                    <b>
                      {article.number} · {article.title}
                    </b>
                  </Link>
                  <small>{article.summary}</small>
                </div>
                {staff && (
                  <button
                    type="button"
                    className="subtle-btn compact"
                    disabled={!canResolve}
                    title={canResolve ? undefined : 'Available when the incident can be resolved'}
                    onClick={() => onUseArticle(article)}
                  >
                    Use as resolution article
                  </button>
                )}
              </div>
            ))}
          </AiResultCard>
        )}

        {duplicates.data && (
          <AiResultCard result={duplicates.data} title="Duplicate suggestions">
            <p>{duplicates.data.data.explanation}</p>
            {duplicates.data.data.candidates.length === 0 && <p className="text-muted">No likely duplicates found.</p>}
            {duplicates.data.data.candidates.map((candidate) => {
              const parentId = candidate.parentId ?? candidate.incidentId;
              const alreadyLinked = incident.parentId === parentId;
              return (
                <div key={candidate.incidentId} className="list-row">
                  <GitMerge size={14} className="gold-text" />
                  <div>
                    <Link to={`/incidents/${candidate.incidentId}`}>
                      <b>
                        {candidate.number} · {candidate.title}
                      </b>
                    </Link>
                    <small>
                      {formatPercent(candidate.score * 100, 0)} similar · {candidate.serviceName ?? 'No service'}
                    </small>
                  </div>
                  <StatusBadge status={candidate.status} />
                  {staff && parentId !== incident.id && (
                    <button
                      type="button"
                      className="subtle-btn compact"
                      disabled={alreadyLinked || linkParent.isPending}
                      onClick={() => linkAsChild(parentId, candidate.number)}
                    >
                      {alreadyLinked ? 'Linked' : 'Link as child'}
                    </button>
                  )}
                </div>
              );
            })}
          </AiResultCard>
        )}
      </div>
    </Panel>
  );
}
