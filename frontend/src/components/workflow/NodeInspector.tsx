import { useId, type RefObject } from 'react';
import { ArrowDown, ArrowLeftRight, ArrowUp, Plus, Trash2, X } from 'lucide-react';
import { MIN_SCHEDULE_INTERVAL_MINUTES } from '../../lib/constants';
import type { ConditionOperator, ConditionRule, WorkflowActionType, WorkflowMeta, WorkflowTrigger } from '../../lib/types';
import { CheckboxField, SelectField, TextareaField, TextField } from '../FormField';
import { actionIcon, triggerIcon } from './icons';
import {
  ACTION_DESCRIPTIONS,
  actionLabel,
  addRule,
  CONDITION_NODE,
  conditionLines,
  DESCRIPTION_MAX,
  fieldLabel,
  findAction,
  formatInterval,
  isNumericField,
  LIMIT_RANGES,
  MAX_ACTIONS_PER_BRANCH,
  MAX_RULES,
  MAX_SCHEDULE_MINUTES,
  moveAction,
  moveActionToBranch,
  NAME_MAX,
  OPERATOR_LABELS,
  removeAction,
  removeRule,
  setActionParam,
  setMatch,
  setScheduleInterval,
  setTrigger,
  splitValues,
  TRIGGER_DESCRIPTIONS,
  TRIGGER_NODE,
  triggerLabel,
  updateAction,
  updateRule,
  valueLabel,
  type Branch,
  type ValidationIssue,
  type WorkflowDraft,
  type WorkflowLimits,
  type WorkflowModel,
} from './model';
import { AgentPicker, DepartmentPicker, IntegrationPicker, RunbookPicker, UserEmailInput, useValueSuggestions } from './pickers';
import { paletteActions } from './WorkflowPalette';

export interface NodeInspectorProps {
  draft: WorkflowDraft;
  meta: WorkflowMeta;
  selectedId: string | null;
  readOnly: boolean;
  /** Issues to show next to fields (empty until the user validates or saves). */
  issues: ValidationIssue[];
  onDraftChange: (patch: Partial<Omit<WorkflowDraft, 'model'>>) => void;
  onModelChange: (model: WorkflowModel, select?: string | null) => void;
  onSelect: (id: string | null) => void;
  headingRef: RefObject<HTMLHeadingElement>;
}

const SCHEDULE_PRESETS = [15, 30, 60, 240, 1440];
const TEXT_PARAMS = new Set(['message', 'reason', 'note']);
const PARAM_LABELS: Record<string, string> = {
  departmentCode: 'Team',
  runbookId: 'Runbook',
  integrationId: 'Integration',
  role: 'Role',
  priority: 'Priority',
  status: 'New status',
  resolutionCode: 'Resolution code',
};

function joinErrors(issues: ValidationIssue[]): string | undefined {
  return issues.length ? issues.map((issue) => issue.message).join(' · ') : undefined;
}

/** Right-hand side panel: edits the selected node, or the workflow settings when nothing is selected. */
export function NodeInspector(props: NodeInspectorProps) {
  const { draft, selectedId, onSelect, headingRef } = props;
  const found = selectedId && selectedId !== TRIGGER_NODE && selectedId !== CONDITION_NODE ? findAction(draft.model, selectedId) : null;
  const kind = selectedId === TRIGGER_NODE ? 'trigger' : selectedId === CONDITION_NODE ? 'condition' : found ? 'action' : 'workflow';
  const eyebrow =
    kind === 'trigger'
      ? 'Trigger'
      : kind === 'condition'
        ? 'Condition'
        : kind === 'action' && found
          ? `${found.branch === 'yes' ? 'Yes' : 'No'} branch · step ${found.index + 1} of ${draft.model[found.branch].length}`
          : 'Workflow';
  const title =
    kind === 'trigger'
      ? triggerLabel(draft.model.trigger)
      : kind === 'condition'
        ? 'Conditions'
        : kind === 'action' && found
          ? actionLabel(found.action.type, props.meta)
          : 'Workflow settings';

  return (
    <aside className="panel wf-inspector" aria-labelledby="wf-inspector-title">
      <div className="wf-inspector-head">
        <div className="min-w-0">
          <span className="eyebrow">{eyebrow}</span>
          <h3 id="wf-inspector-title" ref={headingRef} tabIndex={-1}>
            {title}
          </h3>
        </div>
        {kind !== 'workflow' && (
          <button type="button" className="icon-btn sm" aria-label="Close node editor" onClick={() => onSelect(null)}>
            <X size={15} />
          </button>
        )}
      </div>
      <fieldset className="wf-inspector-body" disabled={props.readOnly}>
        <legend className="sr-only">{title}</legend>
        {kind === 'workflow' && <WorkflowSettings {...props} />}
        {kind === 'trigger' && <TriggerEditor {...props} />}
        {kind === 'condition' && <ConditionEditor {...props} />}
        {kind === 'action' && found && <ActionEditor {...props} uid={found.action.uid} />}
      </fieldset>
    </aside>
  );
}

// ------------------------------------------------------------------ workflow settings

function WorkflowSettings({ draft, issues, onDraftChange }: NodeInspectorProps) {
  const fieldIssues = (field: string) => issues.filter((issue) => issue.target.kind === 'workflow' && issue.target.field === field);
  const limit = (key: keyof WorkflowLimits, hint: string) => (
    <TextField
      label={LIMIT_RANGES[key].label}
      type="number"
      min={LIMIT_RANGES[key].min}
      max={LIMIT_RANGES[key].max}
      step={1}
      value={Number.isFinite(draft[key]) ? String(draft[key]) : ''}
      onChange={(event) => onDraftChange({ [key]: event.target.value === '' ? Number.NaN : Number(event.target.value) } as Partial<WorkflowLimits>)}
      error={joinErrors(fieldIssues(key))}
      hint={hint}
    />
  );
  return (
    <>
      <TextField
        label="Name"
        required
        maxLength={NAME_MAX}
        value={draft.name}
        onChange={(event) => onDraftChange({ name: event.target.value })}
        error={joinErrors(fieldIssues('name'))}
      />
      <TextareaField
        label="Description"
        rows={3}
        maxLength={DESCRIPTION_MAX}
        value={draft.description}
        onChange={(event) => onDraftChange({ description: event.target.value })}
        error={joinErrors(fieldIssues('description'))}
        hint="What the workflow does and why — shown in the workflow list."
      />
      <h4 className="wf-section-title">Execution limits</h4>
      {limit('maxExecutionsPerHour', 'Further matching events in the same hour are rate limited.')}
      <div className="form-row">
        {limit('maxRetries', 'Attempts after a failed step.')}
        {limit('retryBackoffSeconds', 'Wait before each retry.')}
      </div>
      <p className="wf-note">Select the trigger, the condition or an action on the canvas to edit it.</p>
    </>
  );
}

// ------------------------------------------------------------------ trigger

function TriggerEditor({ draft, meta, issues, onModelChange }: NodeInspectorProps) {
  const model = draft.model;
  const Icon = triggerIcon(model.trigger);
  const triggerIssues = issues.filter((issue) => issue.target.kind === 'trigger');
  return (
    <>
      <SelectField
        label="Trigger event"
        value={model.trigger}
        onChange={(event) => onModelChange(setTrigger(model, event.target.value as WorkflowTrigger), TRIGGER_NODE)}
        hint={TRIGGER_DESCRIPTIONS[model.trigger]}
      >
        {meta.triggers.map((trigger) => (
          <option key={trigger} value={trigger}>
            {triggerLabel(trigger)}
          </option>
        ))}
      </SelectField>
      <div className="wf-note flex items-start gap-2">
        <Icon size={14} className="mt-[1px] shrink-0 text-gold" aria-hidden="true" />
        <span>
          {model.trigger === 'SCHEDULED'
            ? 'The scheduler evaluates the conditions against each active incident (up to 200 per run) and runs the Yes actions for matches. Each incident is processed at most once per interval; the hourly execution limit applies.'
            : `${TRIGGER_DESCRIPTIONS[model.trigger]}, the conditions are checked and the Yes or No branch runs.`}
        </span>
      </div>
      {model.trigger === 'SCHEDULED' && (
        <>
          <TextField
            label="Run every (minutes)"
            type="number"
            required
            min={MIN_SCHEDULE_INTERVAL_MINUTES}
            max={MAX_SCHEDULE_MINUTES}
            step={5}
            value={model.intervalMinutes === null ? '' : String(model.intervalMinutes)}
            onChange={(event) => onModelChange(setScheduleInterval(model, event.target.value === '' ? null : Number(event.target.value)))}
            error={joinErrors(triggerIssues)}
            hint={`${formatInterval(model.intervalMinutes)} · minimum ${MIN_SCHEDULE_INTERVAL_MINUTES} minutes`}
          />
          <div className="wf-chip-row" role="group" aria-label="Interval presets">
            {SCHEDULE_PRESETS.map((minutes) => (
              <button
                key={minutes}
                type="button"
                className={`chip ${model.intervalMinutes === minutes ? 'on' : ''}`}
                aria-pressed={model.intervalMinutes === minutes}
                onClick={() => onModelChange(setScheduleInterval(model, minutes))}
              >
                {formatInterval(minutes).replace('Every ', '')}
              </button>
            ))}
          </div>
        </>
      )}
      {model.trigger !== 'SCHEDULED' && triggerIssues.length > 0 && <p className="field-error">{joinErrors(triggerIssues)}</p>}
    </>
  );
}

// ------------------------------------------------------------------ condition

function ConditionEditor({ draft, meta, issues, onModelChange, onSelect }: NodeInspectorProps) {
  const model = draft.model;
  const summary = conditionLines(model, meta);
  const general = issues.filter((issue) => issue.target.kind === 'condition' && issue.target.ruleIndex === undefined);
  const firstField = meta.fields[0]?.field ?? 'priority';
  const firstAction = (branch: Branch) => model[branch][0]?.uid;
  return (
    <>
      <div className="field">
        <span className="field-label">
          <span id="wf-match-label">Match</span>
        </span>
        <div className="wf-segmented" role="radiogroup" aria-labelledby="wf-match-label">
          {(['ALL', 'ANY'] as const).map((match) => (
            <button
              key={match}
              type="button"
              role="radio"
              aria-checked={model.match === match}
              className={model.match === match ? 'on' : ''}
              onClick={() => onModelChange(setMatch(model, match))}
            >
              {match === 'ALL' ? 'All conditions (AND)' : 'Any condition (OR)'}
            </button>
          ))}
        </div>
      </div>

      {model.rules.length === 0 && <p className="wf-note">No conditions: every event takes the Yes branch.</p>}
      <ol className="wf-rules">
        {model.rules.map((rule, index) => (
          <RuleEditor
            key={index}
            index={index}
            rule={rule}
            meta={meta}
            error={joinErrors(issues.filter((issue) => issue.target.kind === 'condition' && issue.target.ruleIndex === index))}
            onChange={(patch) => onModelChange(updateRule(model, index, patch))}
            onRemove={() => onModelChange(removeRule(model, index))}
          />
        ))}
      </ol>
      <button
        type="button"
        className="subtle-btn compact"
        disabled={model.rules.length >= MAX_RULES}
        onClick={() => onModelChange(addRule(model, { field: firstField, operator: 'EQUALS', value: '' }))}
      >
        <Plus size={13} /> Add condition
      </button>
      {general.length > 0 && <p className="field-error mt-2">{joinErrors(general)}</p>}

      <div className="wf-summary" aria-live="polite">
        <b>Reads as</b>
        <span>{summary.full}</span>
        <span className="wf-branch-counts">
          <button type="button" className="link-btn" disabled={!firstAction('yes')} onClick={() => onSelect(firstAction('yes') ?? null)}>
            Yes: {model.yes.length} action{model.yes.length === 1 ? '' : 's'}
          </button>
          <button type="button" className="link-btn" disabled={!firstAction('no')} onClick={() => onSelect(firstAction('no') ?? null)}>
            No: {model.no.length ? `${model.no.length} action${model.no.length === 1 ? '' : 's'}` : 'do nothing'}
          </button>
        </span>
      </div>
    </>
  );
}

function RuleEditor({
  index,
  rule,
  meta,
  error,
  onChange,
  onRemove,
}: {
  index: number;
  rule: ConditionRule;
  meta: WorkflowMeta;
  error?: string;
  onChange: (patch: Partial<ConditionRule>) => void;
  onRemove: () => void;
}) {
  const numeric = isNumericField(rule.field);
  const operators = meta.operators.filter((op) => numeric || (op !== 'GREATER_THAN' && op !== 'LESS_THAN') || op === rule.operator);
  return (
    <li className="wf-rule" aria-label={`Condition ${index + 1}`}>
      <div className="wf-rule-head">
        <span className="step-num !h-5 !w-5 !text-[9px]">{index + 1}</span>
        <button type="button" className="icon-btn sm ml-auto" aria-label={`Remove condition ${index + 1}`} onClick={onRemove}>
          <Trash2 size={13} />
        </button>
      </div>
      <SelectField label="Field" value={rule.field} onChange={(event) => onChange({ field: event.target.value as ConditionRule['field'], operator: 'EQUALS', value: '' })}>
        {!meta.fields.some((entry) => entry.field === rule.field) && <option value={rule.field}>{rule.field || 'Choose…'}</option>}
        {meta.fields.map((entry) => (
          <option key={entry.field} value={entry.field}>
            {entry.label}
          </option>
        ))}
      </SelectField>
      <SelectField
        label="Operator"
        value={rule.operator}
        onChange={(event) => {
          const operator = event.target.value as ConditionOperator;
          onChange({ operator, value: operator === 'IN' || rule.operator === 'IN' ? (splitValues(rule.value)[0] ?? '') : rule.value });
        }}
      >
        {operators.map((op) => (
          <option key={op} value={op}>
            {OPERATOR_LABELS[op] ?? op}
          </option>
        ))}
      </SelectField>
      <RuleValue rule={rule} meta={meta} error={error} onChange={(value) => onChange({ value })} />
    </li>
  );
}

function RuleValue({ rule, meta, error, onChange }: { rule: ConditionRule; meta: WorkflowMeta; error?: string; onChange: (value: string) => void }) {
  const values = meta.fields.find((entry) => entry.field === rule.field)?.values ?? null;
  const label = `Value (${fieldLabel(rule.field, meta)})`;
  if (values?.length && (rule.operator === 'EQUALS' || rule.operator === 'NOT_EQUALS')) {
    return (
      <SelectField label={label} value={rule.value} onChange={(event) => onChange(event.target.value)} error={error}>
        <option value="">Choose…</option>
        {values.map((value) => (
          <option key={value} value={value}>
            {valueLabel(value, rule.field)}
          </option>
        ))}
      </SelectField>
    );
  }
  if (values?.length && rule.operator === 'IN') {
    const selected = splitValues(rule.value);
    const toggle = (value: string) => onChange((selected.includes(value) ? selected.filter((entry) => entry !== value) : [...selected, value]).join(','));
    return (
      <fieldset className="field wf-multi" data-invalid={error ? 'true' : undefined}>
        <legend className="field-label">{label}</legend>
        <div className="wf-chip-row">
          {values.map((value) => (
            <label key={value} className={`chip ${selected.includes(value) ? 'on' : ''}`}>
              <input type="checkbox" className="sr-only" checked={selected.includes(value)} onChange={() => toggle(value)} />
              {valueLabel(value, rule.field)}
            </label>
          ))}
        </div>
        {error && (
          <span className="field-error" role="alert">
            {error}
          </span>
        )}
      </fieldset>
    );
  }
  if (isNumericField(rule.field)) {
    return (
      <TextField
        label={label}
        type="number"
        value={rule.value}
        onChange={(event) => onChange(event.target.value)}
        error={error}
        hint={rule.field === 'slaRemainingMinutes' ? 'Minutes left on the resolution SLA (no SLA = no match)' : undefined}
      />
    );
  }
  return (
    <SuggestedText label={label} field={rule.field} value={rule.value} onChange={onChange} error={error} hint={rule.operator === 'IN' ? 'Comma-separated values' : undefined} />
  );
}

function SuggestedText({
  label,
  field,
  value,
  onChange,
  error,
  hint,
}: {
  label: string;
  field: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  hint?: string;
}) {
  const listId = useId();
  const suggestions = useValueSuggestions(field);
  return (
    <>
      <TextField
        label={label}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        error={error}
        hint={hint}
        list={suggestions.length ? listId : undefined}
        autoComplete="off"
      />
      {suggestions.length > 0 && (
        <datalist id={listId}>
          {suggestions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </datalist>
      )}
    </>
  );
}

// ------------------------------------------------------------------ action

function ActionEditor({ draft, meta, issues, onModelChange, uid }: NodeInspectorProps & { uid: string }) {
  const model = draft.model;
  const found = findAction(model, uid);
  if (!found) return null;
  const { action, branch, index } = found;
  const actionMeta = meta.actions.find((entry) => entry.type === action.type);
  const Icon = actionIcon(action.type);
  const otherBranch: Branch = branch === 'yes' ? 'no' : 'yes';
  const actionIssues = issues.filter((issue) => issue.target.kind === 'action' && issue.target.uid === uid);
  const paramError = (key: string) => joinErrors(actionIssues.filter((issue) => issue.target.kind === 'action' && issue.target.param === key));
  const general = actionIssues.filter((issue) => issue.target.kind === 'action' && !issue.target.param);
  return (
    <>
      <SelectField
        label="Action"
        value={action.type}
        onChange={(event) => onModelChange(updateAction(model, uid, { type: event.target.value as WorkflowActionType }), uid)}
        hint="Changing the action clears its settings."
      >
        {!actionMeta && <option value={action.type}>{actionLabel(action.type, meta)}</option>}
        {paletteActions(meta).map((type) => (
          <option key={type} value={type}>
            {actionLabel(type, meta)}
          </option>
        ))}
      </SelectField>
      <p className="wf-note flex items-start gap-2">
        <Icon size={14} className="mt-[1px] shrink-0 text-gold" aria-hidden="true" />
        <span>{ACTION_DESCRIPTIONS[action.type] ?? actionMeta?.label}</span>
      </p>
      {actionMeta?.params.length === 0 && <p className="wf-note">This action has no settings.</p>}
      {actionMeta?.params.map((param) => (
        <ParamField
          key={param.key}
          actionType={action.type}
          param={param}
          value={action.params[param.key] ?? ''}
          error={paramError(param.key)}
          onChange={(value) => onModelChange(setActionParam(model, uid, param.key, value), uid)}
        />
      ))}
      {general.length > 0 && <p className="field-error">{joinErrors(general)}</p>}
      <div className="wf-action-tools">
        <button type="button" className="subtle-btn compact" disabled={index === 0} onClick={() => onModelChange(moveAction(model, uid, -1), uid)} aria-label="Move action up">
          <ArrowUp size={13} /> Up
        </button>
        <button
          type="button"
          className="subtle-btn compact"
          disabled={index === model[branch].length - 1}
          onClick={() => onModelChange(moveAction(model, uid, 1), uid)}
          aria-label="Move action down"
        >
          <ArrowDown size={13} /> Down
        </button>
        <button
          type="button"
          className="subtle-btn compact"
          disabled={model[otherBranch].length >= MAX_ACTIONS_PER_BRANCH}
          onClick={() => onModelChange(moveActionToBranch(model, uid, otherBranch), uid)}
        >
          <ArrowLeftRight size={13} /> Move to {otherBranch === 'yes' ? 'Yes' : 'No'}
        </button>
        <button type="button" className="danger-btn compact" onClick={() => onModelChange(removeAction(model, uid), null)}>
          <Trash2 size={13} /> Remove
        </button>
      </div>
    </>
  );
}

function ParamField({
  actionType,
  param,
  value,
  error,
  onChange,
}: {
  actionType: WorkflowActionType;
  param: WorkflowMeta['actions'][number]['params'][number];
  value: string;
  error?: string;
  onChange: (value: string) => void;
}) {
  const label = PARAM_LABELS[param.key] ?? param.label;
  const common = { label, value, onChange, required: param.required, error };
  if (param.key === 'departmentCode') return <DepartmentPicker {...common} emptyLabel={param.required ? 'Choose a team…' : "Incident's team (default)"} />;
  if (param.key === 'userEmail')
    return actionType === 'ASSIGN_USER' ? <AgentPicker {...common} label="Agent" emptyLabel="Choose an agent…" /> : <UserEmailInput {...common} label="Recipient e-mail" />;
  if (param.key === 'runbookId') return <RunbookPicker {...common} />;
  if (param.key === 'integrationId') return <IntegrationPicker {...common} />;
  if (param.key === 'internal') {
    return (
      <CheckboxField
        label="Internal work note (visible to staff only)"
        checked={value === 'true'}
        onChange={(event) => onChange(event.target.checked ? 'true' : 'false')}
        error={error}
      />
    );
  }
  if (param.options) {
    return (
      <SelectField
        label={label}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        required={param.required}
        error={error}
        hint={param.key === 'status' ? 'Closed from New or Assigned cancels the incident.' : undefined}
      >
        <option value="">{param.required ? 'Choose…' : 'Not set'}</option>
        {param.options.map((option) => (
          <option key={option} value={option}>
            {valueLabel(option, param.key)}
          </option>
        ))}
      </SelectField>
    );
  }
  if (TEXT_PARAMS.has(param.key)) {
    return (
      <TextareaField
        label={param.label}
        rows={3}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        required={param.required}
        error={error}
        maxLength={1000}
        hint="Placeholders: {{number}}, {{title}}, {{priority}}"
      />
    );
  }
  return <TextField label={label} value={value} onChange={(event) => onChange(event.target.value)} required={param.required} error={error} />;
}
