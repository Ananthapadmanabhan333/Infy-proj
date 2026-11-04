import { useFieldArray, useWatch, type Control, type FieldErrors, type UseFormRegister, type UseFormSetValue } from 'react-hook-form';
import { ArrowDown, ArrowUp, Plus, Trash2, X } from 'lucide-react';
import { CheckboxField, SelectField, TextField, toNumberOrNull } from '../../components/FormField';
import { UserSearchPicker } from '../../components/itsm/ItsmParts';
import type { Role, TeamDto } from '../../lib/types';
import {
  APPROVER_KIND_LABELS,
  CONDITION_FIELD_SUGGESTIONS,
  CONDITION_OPERATORS,
  emptyApprover,
  emptyRule,
  MAX_APPROVERS,
  MAX_RULES,
  MODE_LABELS,
  QUORUM_LABELS,
  ROLE_OPTIONS,
  type FlowFormValues,
  type StageDraft,
} from './flowModel';

type ArrayError = { message?: string; root?: { message?: string } } | undefined;

/** Array-level error: zod puts it on the array, server errors are mapped to `.root`. */
export function arrayMessage(error: unknown): string | undefined {
  const value = error as ArrayError;
  return value?.root?.message ?? value?.message;
}

interface StageCardProps {
  index: number;
  count: number;
  control: Control<FlowFormValues>;
  register: UseFormRegister<FlowFormValues>;
  setValue: UseFormSetValue<FlowFormValues>;
  errors: FieldErrors<FlowFormValues>;
  teams: TeamDto[];
  onMove: (to: number) => void;
  onRemove: () => void;
}

function ApproverRow({
  stageIndex,
  index,
  count,
  control,
  register,
  setValue,
  errors,
  teams,
  onRemove,
}: Omit<StageCardProps, 'onMove' | 'onRemove' | 'index'> & { stageIndex: number; index: number; onRemove: () => void }) {
  const base = `stages.${stageIndex}.approvers.${index}` as const;
  const approver = useWatch({ control, name: base });
  const error = errors.stages?.[stageIndex]?.approvers?.[index];
  const label = `Stage ${stageIndex + 1} approver ${index + 1}`;
  const inactiveTeam = approver?.kind === 'TEAM' && approver.teamId && !teams.some((team) => String(team.id) === approver.teamId);
  return (
    <div className="flex flex-wrap items-start gap-2 sm:flex-nowrap" role="group" aria-label={label}>
      <SelectField label="Approver type" className="field w-full sm:w-[190px] sm:shrink-0" error={error?.kind?.message} {...register(`${base}.kind`)}>
        {Object.entries(APPROVER_KIND_LABELS).map(([value, text]) => (
          <option key={value} value={value}>
            {text}
          </option>
        ))}
      </SelectField>
      <div className="min-w-0 flex-1">
        {approver?.kind === 'USER' && (
          <UserSearchPicker
            label="Person"
            roles={null}
            value={approver.userId ? { id: approver.userId, fullName: approver.userName ?? `User #${approver.userId}`, role: (approver.userRole ?? '') as Role, departmentName: null } : null}
            onChange={(user) => {
              setValue(`${base}.userId`, user?.id ?? null, { shouldDirty: true, shouldValidate: Boolean(user) });
              setValue(`${base}.userName`, user?.fullName ?? null, { shouldDirty: true });
              setValue(`${base}.userRole`, user?.role ?? null, { shouldDirty: true });
            }}
            error={error?.userId?.message}
          />
        )}
        {approver?.kind === 'ROLE' && (
          <SelectField label="Role" hint="Anyone with the role can decide (list the role twice to need two people)." error={error?.role?.message} {...register(`${base}.role`)}>
            <option value="">Choose a role</option>
            {ROLE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
        )}
        {approver?.kind === 'TEAM' && (
          <SelectField label="Team" hint="Any active member of the team can decide." error={error?.teamId?.message} {...register(`${base}.teamId`)}>
            <option value="">Choose a team</option>
            {inactiveTeam && <option value={approver.teamId}>Team #{approver.teamId} (inactive or unknown)</option>}
            {teams.map((team) => (
              <option key={team.id} value={team.id}>
                {team.name}
              </option>
            ))}
          </SelectField>
        )}
        {approver?.kind === 'MANAGER' && <p className="m-0 mt-[24px] text-[11px] text-muted">The requester&apos;s department manager (IT managers when there is none), resolved when the approval is requested.</p>}
      </div>
      <button type="button" className="icon-btn sm mt-[24px]" aria-label={`Remove ${label.toLowerCase()}`} disabled={count === 1} onClick={onRemove}>
        <X size={14} />
      </button>
    </div>
  );
}

/** One stage of the flow: name, mode, quorum, approvers, optional condition, deadline and escalation. */
export function StageCard({ index, count, control, register, setValue, errors, teams, onMove, onRemove }: StageCardProps) {
  const stage = useWatch({ control, name: `stages.${index}` }) as StageDraft | undefined;
  const approvers = useFieldArray({ control, name: `stages.${index}.approvers` });
  const rules = useFieldArray({ control, name: `stages.${index}.conditionRules` });
  const stageErrors = errors.stages?.[index];
  const title = `Stage ${index + 1}`;
  const listId = `stage-${index}-facts`;

  return (
    <div className="action-card" role="group" aria-label={`${title}: ${stage?.name || 'unnamed'}`}>
      <div className="flex items-end gap-2">
        <span className="step-num mb-[15px]">{index + 1}</span>
        <TextField label={`${title} name`} className="field inline flex-1" required maxLength={120} error={stageErrors?.name?.message} {...register(`stages.${index}.name`)} />
        <div className="mb-1 flex gap-1">
          <button type="button" className="icon-btn sm" aria-label={`Move stage ${index + 1} up`} disabled={index === 0} onClick={() => onMove(index - 1)}>
            <ArrowUp size={14} />
          </button>
          <button type="button" className="icon-btn sm" aria-label={`Move stage ${index + 1} down`} disabled={index === count - 1} onClick={() => onMove(index + 1)}>
            <ArrowDown size={14} />
          </button>
          <button type="button" className="icon-btn sm" aria-label={`Remove stage ${index + 1}`} disabled={count === 1} onClick={onRemove}>
            <Trash2 size={14} />
          </button>
        </div>
      </div>

      <div className="form-row three">
        <SelectField label="Mode" error={stageErrors?.mode?.message} {...register(`stages.${index}.mode`)}>
          {Object.entries(MODE_LABELS).map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </SelectField>
        <SelectField label="Quorum" error={stageErrors?.quorum?.message} {...register(`stages.${index}.quorum`)}>
          {Object.entries(QUORUM_LABELS).map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </SelectField>
        {stage?.quorum === 'N' ? (
          <TextField
            label="Required approvals"
            type="number"
            min={1}
            max={10}
            required
            hint={`Out of ${approvers.fields.length} approver${approvers.fields.length === 1 ? '' : 's'}`}
            error={stageErrors?.requiredCount?.message}
            {...register(`stages.${index}.requiredCount`, { setValueAs: toNumberOrNull })}
          />
        ) : (
          <p className="m-0 self-center text-[11px] text-muted">
            {stage?.quorum === 'ALL' ? 'Every approver must approve; one rejection rejects the stage.' : 'The first decision (approve or reject) decides the stage.'}
          </p>
        )}
      </div>

      <fieldset className="field" aria-label={`${title} approvers`}>
        <legend className="mb-1">Approvers</legend>
        {approvers.fields.map((field, approverIndex) => (
          <ApproverRow
            key={field.id}
            stageIndex={index}
            index={approverIndex}
            count={approvers.fields.length}
            control={control}
            register={register}
            setValue={setValue}
            errors={errors}
            teams={teams}
            onRemove={() => approvers.remove(approverIndex)}
          />
        ))}
        {arrayMessage(stageErrors?.approvers) && (
          <span className="field-error" role="alert">
            {arrayMessage(stageErrors?.approvers)}
          </span>
        )}
        <div>
          <button type="button" className="subtle-btn compact" disabled={approvers.fields.length >= MAX_APPROVERS} onClick={() => approvers.append(emptyApprover())}>
            <Plus size={13} aria-hidden="true" /> Add approver to stage {index + 1}
          </button>
        </div>
      </fieldset>

      <div className="form-row">
        <TextField
          label="Deadline (hours)"
          type="number"
          min={1}
          max={720}
          hint="Optional, 1–720. Overdue stages are escalated once."
          error={stageErrors?.deadlineHours?.message}
          {...register(`stages.${index}.deadlineHours`, { setValueAs: toNumberOrNull })}
        />
        <div className="field">
          <CheckboxField label="Custom escalation when overdue" error={stageErrors?.escalate?.message} {...register(`stages.${index}.escalate`)} />
          {!stage?.escalate && <span className="field-hint">Default: IT managers and system administrators are notified.</span>}
        </div>
      </div>
      {stage?.escalate && (
        <div className="form-row three">
          <CheckboxField label="Notify the approvers' managers" {...register(`stages.${index}.notifyManager`)} />
          <SelectField label="Also notify role" error={stageErrors?.notifyRole?.message} {...register(`stages.${index}.notifyRole`)}>
            <option value="">No role</option>
            {ROLE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
          <CheckboxField label="Reject automatically when overdue" {...register(`stages.${index}.autoReject`)} />
        </div>
      )}

      <div className="field">
        <CheckboxField
          label="Run this stage only when a condition matches (otherwise it is skipped)"
          {...register(`stages.${index}.hasCondition`, {
            onChange: (event: { target: { checked: boolean } }) => {
              if (event.target.checked && rules.fields.length === 0) rules.append(emptyRule());
            },
          })}
        />
      </div>
      {stage?.hasCondition && (
        <fieldset className="field" aria-label={`${title} condition`}>
          <datalist id={listId}>
            {CONDITION_FIELD_SUGGESTIONS.map((fact) => (
              <option key={fact} value={fact} />
            ))}
          </datalist>
          <SelectField label="Match" error={stageErrors?.conditionMatch?.message} {...register(`stages.${index}.conditionMatch`)}>
            <option value="ALL">All rules (and)</option>
            <option value="ANY">Any rule (or)</option>
          </SelectField>
          {rules.fields.map((field, ruleIndex) => {
            const ruleError = stageErrors?.conditionRules?.[ruleIndex];
            return (
              <div key={field.id} className="flex flex-wrap items-start gap-2 sm:flex-nowrap" role="group" aria-label={`Stage ${index + 1} rule ${ruleIndex + 1}`}>
                <TextField label="Fact" className="field flex-1" list={listId} placeholder="changeRisk" error={ruleError?.field?.message} {...register(`stages.${index}.conditionRules.${ruleIndex}.field`)} />
                <SelectField label="Operator" className="field flex-1" error={ruleError?.operator?.message} {...register(`stages.${index}.conditionRules.${ruleIndex}.operator`)}>
                  {CONDITION_OPERATORS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </SelectField>
                <TextField label="Value" className="field flex-1" maxLength={500} placeholder="HIGH" error={ruleError?.value?.message} {...register(`stages.${index}.conditionRules.${ruleIndex}.value`)} />
                <button type="button" className="icon-btn sm mt-[24px]" aria-label={`Remove stage ${index + 1} rule ${ruleIndex + 1}`} onClick={() => rules.remove(ruleIndex)}>
                  <X size={14} />
                </button>
              </div>
            );
          })}
          {arrayMessage(stageErrors?.conditionRules) && (
            <span className="field-error" role="alert">
              {arrayMessage(stageErrors?.conditionRules)}
            </span>
          )}
          <div>
            <button type="button" className="subtle-btn compact" disabled={rules.fields.length >= MAX_RULES} onClick={() => rules.append(emptyRule())}>
              <Plus size={13} aria-hidden="true" /> Add rule
            </button>
          </div>
        </fieldset>
      )}
    </div>
  );
}

