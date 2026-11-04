import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { X } from 'lucide-react';
import { AnalyzeFlow } from '../components/ai/AnalyzeFlow';
import { AskNexus } from '../components/ai/AskNexus';
import { AutomateFlow } from '../components/ai/AutomateFlow';
import { AI_TOOLS, CapabilityTiles, type AiTool } from '../components/ai/CapabilityTiles';
import { EngineStatus } from '../components/ai/EngineStatus';
import { InsightOfTheDay } from '../components/ai/InsightOfTheDay';
import { PredictFlow } from '../components/ai/PredictFlow';
import { RecentAnalyses } from '../components/ai/RecentAnalyses';
import { RecommendFlow } from '../components/ai/RecommendFlow';
import { TrendsPanel } from '../components/ai/OpsAiPanels';
import { PageHeader } from '../components/PageHeader';
import { Panel } from '../components/Panel';
import { useAuth } from '../hooks/useAuth';
import { STAFF, WORKFLOW_EDITORS } from '../lib/constants';
import '../components/ai/ai.css';

const FLOW_PANEL_ID = 'nx-flow-panel';

/** SA AI — operations intelligence workspace (reference screen 5). Route `/sa-ai`, READERS. */
export default function AiPage() {
  const { hasRole } = useAuth();
  const staff = hasRole(...STAFF);
  const workflowEditor = hasRole(...WORKFLOW_EDITORS);
  // Tiles a role cannot use are not shown (the API enforces the same rules).
  const tools = AI_TOOLS.filter((tool) => (tool.id === 'recommend' ? staff : tool.id === 'automate' ? workflowEditor : true));

  const [params, setParams] = useSearchParams();
  const requested = params.get('tool') as AiTool | null;
  const active = tools.find((tool) => tool.id === requested) ?? null;

  const setTool = useCallback(
    (tool: AiTool | null) =>
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          if (tool) next.set('tool', tool);
          else next.delete('tool');
          return next;
        },
        { replace: true },
      ),
    [setParams],
  );

  return (
    <>
      <PageHeader
        title={
          <span className="nx-title">
            SA AI <span className="badge beta">Beta</span>
          </span>
        }
        subtitle="Your IT operations co-pilot"
      />
      <div className="page-stack">
        <EngineStatus />
        <CapabilityTiles tools={tools} value={active?.id ?? null} onSelect={(tool) => setTool(active?.id === tool ? null : tool)} controls={FLOW_PANEL_ID} />

        {active && (
          <Panel
            id={FLOW_PANEL_ID}
            title={active.title}
            subtitle={active.description}
            actions={
              <button type="button" className="icon-btn sm" onClick={() => setTool(null)} aria-label={`Close ${active.label}`}>
                <X size={15} />
              </button>
            }
          >
            {active.id === 'analyze' && <AnalyzeFlow canRootCause={staff} />}
            {active.id === 'predict' && <PredictFlow />}
            {active.id === 'recommend' && <RecommendFlow canStartRunbook={staff} />}
            {active.id === 'automate' && <AutomateFlow />}
          </Panel>
        )}

        <AskNexus />

        <TrendsPanel />

        <div className="grid gap-[14px] lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
          <InsightOfTheDay />
          <RecentAnalyses />
        </div>
      </div>
    </>
  );
}
