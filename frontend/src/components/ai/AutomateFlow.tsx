import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle, ArrowDown, Clock3, Cog, ExternalLink, GitBranch, Zap } from 'lucide-react';
import { useAiWorkflowSuggestion } from '../../api/ai';
import { useWorkflowMeta } from '../../api/workflows';
import { WORKFLOW_ACTION_LABELS, WORKFLOW_TRIGGER_LABELS } from '../../lib/constants';
import { titleCase } from '../../lib/format';
import type { AiResult, ConditionRule, WorkflowAction, WorkflowMeta, WorkflowSuggestion } from '../../lib/types';
import { AiResultCard } from '../AiResultCard';
import { aiErrorMessage, OPERATOR_LABELS } from './aiUtils';

/** Router state handed to `/automation/new`: the builder reads `location.state?.suggestion`. */
export interface WorkflowSuggestionState {
  suggestion: WorkflowSuggestion;
  /** Where the draft came from, so the builder can keep showing the source label. */
  source: Pick<AiResult<WorkflowSuggestion>, 'source' | 'provider' | 'model' | 'notice'>;
}

const MIN_GOAL = 10;

function fieldLabel(meta: WorkflowMeta | undefined, field: string) {
  return meta?.fields.find((entry) => entry.field === field)?.label ?? titleCase(field);
}

function actionLabel(meta: WorkflowMeta | undefined, action: WorkflowAction) {
  return meta?.actions.find((entry) => entry.type === action.type)?.label ?? WORKFLOW_ACTION_LABELS[action.type] ?? titleCase(action.type);
}

function paramLabel(meta: WorkflowMeta | undefined, action: WorkflowAction, key: string) {
  return meta?.actions.find((entry) => entry.type === action.type)?.params.find((param) => param.key === key)?.label ?? titleCase(key);
}

function RuleText({ rule, meta }: { rule: ConditionRule; meta: WorkflowMeta | undefined }) {
  return (
    <span>
      {fieldLabel(meta, rule.field)} <i>{OPERATOR_LABELS[rule.operator] ?? titleCase(rule.operator)}</i> <b>{rule.value}</b>
    </span>
  );
}

function ActionList({ actions, meta, empty }: { actions: WorkflowAction[]; meta: WorkflowMeta | undefined; empty: string }) {
  if (!actions.length) return <p className="muted-text text-[10px]">{empty}</p>;
  return (
    <ol className="nx-wf-actions">
      {actions.map((action, index) => {
        const params = Object.entries(action.params ?? {}).filter(([, value]) => value !== '' && value !== null && value !== undefined);
        return (
          <li key={`${action.type}-${index}`}>
            <b>{actionLabel(meta, action)}</b>
            {params.length > 0 && (
              <span className="flex flex-wrap gap-1">
                {params.map(([key, value]) => (
                  <span key={key} className="chip">
                    {paramLabel(meta, action, key)}: {value}
                  </span>
                ))}
              </span>
            )}
          </li>
        );
      })}
    </ol>
  );
}

/** Readable trigger → conditions → yes/no actions view of a drafted workflow definition. */
export function WorkflowSuggestionView({ suggestion, meta }: { suggestion: WorkflowSuggestion; meta?: WorkflowMeta }) {
  const { definition } = suggestion;
  const rules = definition.conditions?.rules ?? [];
  const elseActions = definition.elseActions ?? [];
  return (
    <div className="nx-wf">
      <div className="nx-wf-node">
        <span className="nx-wf-kind">
          <Zap size={12} /> Trigger
        </span>
        <b>{WORKFLOW_TRIGGER_LABELS[suggestion.triggerEvent] ?? titleCase(suggestion.triggerEvent)}</b>
        {definition.schedule?.intervalMinutes ? (
          <span className="text-[10px] text-muted">
            <Clock3 size={10} /> every {definition.schedule.intervalMinutes} minutes
          </span>
        ) : null}
      </div>
      <ArrowDown size={14} className="nx-wf-arrow" aria-hidden="true" />
      <div className="nx-wf-node">
        <span className="nx-wf-kind">
          <GitBranch size={12} /> Conditions
        </span>
        {rules.length === 0 ? (
          <span className="text-[10px] text-muted">None — every trigger event matches</span>
        ) : (
          <>
            <span className="text-[10px] text-muted">{definition.conditions.match === 'ANY' ? 'Any of these' : 'All of these'}</span>
            <ul className="nx-wf-rules">
              {rules.map((rule, index) => (
                <li key={`${rule.field}-${index}`}>
                  <RuleText rule={rule} meta={meta} />
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
      <ArrowDown size={14} className="nx-wf-arrow" aria-hidden="true" />
      <div className="grid gap-2 sm:grid-cols-2">
        <div className="nx-wf-node yes">
          <span className="nx-wf-kind">Yes — run</span>
          <ActionList actions={definition.actions ?? []} meta={meta} empty="No actions" />
        </div>
        <div className="nx-wf-node no">
          <span className="nx-wf-kind">No — run</span>
          <ActionList actions={elseActions} meta={meta} empty="Nothing (no else branch)" />
        </div>
      </div>
    </div>
  );
}

/** Automate tile: goal text → AI workflow draft, reviewed here and opened in the builder (never saved automatically). */
export function AutomateFlow() {
  const [goal, setGoal] = useState('');
  const suggest = useAiWorkflowSuggestion();
  const meta = useWorkflowMeta();
  const navigate = useNavigate();
  const trimmed = goal.trim();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (trimmed.length < MIN_GOAL || suggest.isPending) return;
    suggest.mutate(trimmed);
  };

  const result = suggest.data;
  const openInBuilder = () => {
    if (!result) return;
    const state: WorkflowSuggestionState = {
      suggestion: result.data,
      source: { source: result.source, provider: result.provider, model: result.model, notice: result.notice },
    };
    navigate('/automation/new', { state });
  };

  return (
    <div className="grid gap-3">
      <form className="grid gap-2" onSubmit={submit}>
        <label className="text-[11px] font-bold text-ivory" htmlFor="nx-automate-goal">
          What should be automated?
        </label>
        <textarea
          id="nx-automate-goal"
          className="control"
          rows={3}
          maxLength={1000}
          value={goal}
          onChange={(event) => setGoal(event.target.value)}
          placeholder="e.g. When a critical payroll incident is created, start the payroll runbook and notify the payroll team; otherwise route it to the service desk."
        />
        <div className="flex flex-wrap items-center gap-2">
          <button type="submit" className="gold-btn compact" disabled={trimmed.length < MIN_GOAL || suggest.isPending}>
            <Cog size={14} className={suggest.isPending ? 'animate-spin' : ''} /> {suggest.isPending ? 'Drafting…' : 'Draft workflow'}
          </button>
          <span className="text-[10px] text-muted">Nothing is saved — you review the draft in the builder first.</span>
        </div>
      </form>

      {suggest.isError && (
        <div className="callout red" role="alert">
          <AlertTriangle size={16} />
          <div>Workflow draft failed: {aiErrorMessage(suggest.error)}</div>
        </div>
      )}

      {result && (
        <AiResultCard
          result={result}
          title={result.data.name || 'Workflow draft'}
          actions={
            <>
              <button type="button" className="gold-btn compact" onClick={openInBuilder}>
                <ExternalLink size={13} /> Open in builder
              </button>
              <button type="button" className="subtle-btn compact" onClick={() => suggest.reset()}>
                Discard
              </button>
            </>
          }
        >
          {result.data.description && <p>{result.data.description}</p>}
          <WorkflowSuggestionView suggestion={result.data} meta={meta.data} />
          {result.data.explanation && (
            <p className="mt-2 text-[10px] text-muted">
              <b className="text-champagne">Why: </b>
              {result.data.explanation}
            </p>
          )}
        </AiResultCard>
      )}
    </div>
  );
}
