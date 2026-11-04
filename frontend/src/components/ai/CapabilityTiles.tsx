import { Cog, Crosshair, Sparkle, TrendingUp, type LucideIcon } from 'lucide-react';

export type AiTool = 'analyze' | 'predict' | 'recommend' | 'automate';

export interface AiToolDef {
  id: AiTool;
  label: string;
  caption: string;
  icon: LucideIcon;
  /** Heading of the in-page flow the tile opens. */
  title: string;
  description: string;
}

/** The four capability tiles of the reference (screen 5). */
export const AI_TOOLS: AiToolDef[] = [
  {
    id: 'analyze',
    label: 'Analyze',
    caption: 'Investigate incidents',
    icon: Sparkle,
    title: 'Analyze an incident',
    description: 'Summary, key facts and root-cause hypotheses for one incident.',
  },
  {
    id: 'predict',
    label: 'Predict',
    caption: 'Forecast issues',
    icon: Crosshair,
    title: 'Predict incident volume',
    description: 'Statistical forecast from incident history — a regression model, not AI.',
  },
  {
    id: 'recommend',
    label: 'Recommend',
    caption: 'Resolution steps',
    icon: TrendingUp,
    title: 'Recommend a resolution',
    description: 'Likely cause, confidence, steps, runbook and knowledge for one incident.',
  },
  {
    id: 'automate',
    label: 'Automate',
    caption: 'Create workflows',
    icon: Cog,
    title: 'Automate with a workflow',
    description: 'Describe a goal; SA AI drafts a workflow for you to review in the builder.',
  },
];

interface CapabilityTilesProps {
  tools: AiToolDef[];
  value: AiTool | null;
  onSelect: (tool: AiTool) => void;
  /** id of the flow panel the tiles control. */
  controls?: string;
}

export function CapabilityTiles({ tools, value, onSelect, controls }: CapabilityTilesProps) {
  return (
    <div className="nx-tiles" role="group" aria-label="SA AI capabilities">
      {tools.map(({ id, label, caption, icon: Icon }) => {
        const selected = value === id;
        return (
          <button
            key={id}
            type="button"
            className={`nx-tile ${selected ? 'selected' : ''}`}
            aria-pressed={selected}
            aria-label={`${label} — ${caption}`}
            aria-controls={selected ? controls : undefined}
            onClick={() => onSelect(id)}
          >
            <Icon size={22} aria-hidden="true" />
            <b>{label}</b>
            <small>{caption}</small>
          </button>
        );
      })}
    </div>
  );
}
