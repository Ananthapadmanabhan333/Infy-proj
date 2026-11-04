import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import {
  BookOpen,
  ChevronRight,
  FileText,
  Gauge,
  History,
  Lightbulb,
  MessageSquare,
  Search,
  Sparkles,
  Tags,
  TrendingUp,
  Workflow,
  type LucideIcon,
} from 'lucide-react';
import { useAiInteractions } from '../../api/ai';
import { incidentKeys } from '../../api/incidents';
import { getJson } from '../../lib/api';
import { formatRelative } from '../../lib/format';
import type { AiInteractionDto, IncidentSummary, PageResponse } from '../../lib/types';
import { AiSourceTag } from '../AiResultCard';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';
import { featureLabel, isIncidentNumber } from './aiUtils';

const FEATURE_ICONS: Record<string, LucideIcon> = {
  ROOT_CAUSE: Search,
  RESOLUTION: TrendingUp,
  ASK: MessageSquare,
  INSIGHTS: Lightbulb,
  WORKFLOW_SUGGESTION: Workflow,
  SUMMARIZE: FileText,
  SUMMARY: FileText,
  CLASSIFY: Tags,
  CLASSIFICATION: Tags,
  DUPLICATES: History,
  KNOWLEDGE: BookOpen,
  FORECAST: Gauge,
};

/** Looks an incident up by its number ("INC-7846") so the row can open `/incidents/:id`. */
function useOpenIncidentByNumber() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const [opening, setOpening] = useState<string | null>(null);
  const open = async (number: string) => {
    const params = { search: number.trim(), size: 5 };
    setOpening(number);
    try {
      const page = await queryClient.fetchQuery({
        queryKey: incidentKeys.list(params),
        queryFn: () => getJson<PageResponse<IncidentSummary>>('/incidents', params),
        staleTime: 30_000,
      });
      const match = page.content.find((incident) => incident.number.toUpperCase() === number.trim().toUpperCase());
      if (match) navigate(`/incidents/${match.id}`);
      else toast.warning(`${number} could not be found — it may have been removed or you may not have access.`);
    } catch (error) {
      toast.error(error);
    } finally {
      setOpening(null);
    }
  };
  return { open, opening };
}

function RowContent({ item }: { item: AiInteractionDto }) {
  const Icon = FEATURE_ICONS[item.feature] ?? Sparkles;
  return (
    <>
      <span className="nx-recent-icon" aria-hidden="true">
        <Icon size={14} />
      </span>
      <span className="min-w-0 flex-1">
        <b>
          {featureLabel(item.feature)}
          {item.subject ? ` – ${item.subject}` : ''}
        </b>
        <small>
          <AiSourceTag source={item.source} /> <span>{formatRelative(item.createdAt)}</span>
        </small>
      </span>
    </>
  );
}

/** "Recent Analyses": the current user's last AI interactions; incident subjects open the incident. */
export function RecentAnalyses() {
  const interactions = useAiInteractions(5);
  const { open, opening } = useOpenIncidentByNumber();

  return (
    <section className="panel nx-recent" aria-labelledby="nx-recent-title">
      <div className="panel-title">
        <div>
          <h3 id="nx-recent-title">Recent Analyses</h3>
          <p>Your latest SA AI requests</p>
        </div>
      </div>
      {interactions.isLoading && <LoadingState rows={3} label="Loading recent analyses…" />}
      {interactions.isError && <ErrorState error={interactions.error} onRetry={() => void interactions.refetch()} compact />}
      {interactions.data && interactions.data.length === 0 && (
        <EmptyState title="No analyses yet" description="Analyses, questions and drafts you run here appear in this list." icon={<History size={20} />} />
      )}
      {interactions.data && interactions.data.length > 0 && (
        <ul className="nx-recent-list">
          {interactions.data.map((item) => (
            <li key={item.id}>
              {isIncidentNumber(item.subject) ? (
                <button
                  type="button"
                  className="nx-recent-row"
                  onClick={() => void open(item.subject as string)}
                  disabled={opening !== null}
                  aria-label={`${featureLabel(item.feature)} – ${item.subject}: open incident`}
                >
                  <RowContent item={item} />
                  <ChevronRight size={14} aria-hidden="true" className={opening === item.subject ? 'animate-pulse' : ''} />
                </button>
              ) : (
                <div className="nx-recent-row static">
                  <RowContent item={item} />
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
