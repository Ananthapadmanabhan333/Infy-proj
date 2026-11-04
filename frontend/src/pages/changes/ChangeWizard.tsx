import { useMemo, useState } from 'react';
import { AlertTriangle, ArrowLeft, ArrowRight, CalendarX2, Cpu, ShieldAlert, Snowflake } from 'lucide-react';
import { useChangeCalendar, useChangeTemplates, useAnyChangeTransition, useFreezeWindows, useRiskQuestions, useSaveChange } from '../../api/changes';
import { useServices } from '../../api/services';
import { useTeams } from '../../api/teams';
import { useAssignableUsers } from '../../api/users';
import { unlinkedCiNames } from '../../components/cmdb/AffectedCis';
import { CiMultiPicker } from '../../components/cmdb/CiPicker';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { parseList, ServiceMultiPicker, ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { ErrorState, LoadingState } from '../../components/States';
import { Stepper } from '../../components/Stepper';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { CATEGORIES, CHANGE_EDITORS, LEVELS, PRIORITIES, PRIORITY_LABELS } from '../../lib/constants';
import { formatDateTime, isoToLocalInput, localInputToIso, titleCase } from '../../lib/format';
import { CHANGE_TYPE_LABELS, RISK_TONES } from '../../lib/itsm';
import type { ChangeDetail, ChangeRequestBody, ChangeStatus, ChangeType } from '../../lib/itsmTypes';
import type { CiRef } from '../../lib/opsTypes';
import type { Category, Level, Priority } from '../../lib/types';
import { liveRisk, overlaps } from './changeRisk';

const PLANNED: ChangeStatus[] = ['AWAITING_APPROVAL', 'APPROVED', 'SCHEDULED', 'IMPLEMENTING'];
type Step = 'type' | 'details' | 'risk' | 'plans' | 'window';
const STEP_LABELS: Record<Step, string> = { type: 'Type', details: 'Details & impact', risk: 'Risk', plans: 'Plans', window: 'Window' };

const TYPE_HELP: Record<ChangeType, string> = {
  STANDARD: 'Pre-approved, low-risk change from a template. Submitting approves it — no CAB.',
  NORMAL: 'Risk assessment, then CAB approval (a second CAB stage when the risk is high).',
  EMERGENCY: 'Expedited approval by the emergency CAB; a post-implementation review is required.',
};

function addMinutes(iso: string, minutes: number): string {
  return new Date(new Date(iso).getTime() + minutes * 60_000).toISOString();
}

/**
 * Create (wizard) or edit a change: type (STANDARD from a template / NORMAL / EMERGENCY), details and impact, risk
 * questionnaire with a live computed score, plans, planned window with conflict and freeze warnings.
 */
export function ChangeWizard({ change, onClose, onSaved }: { change?: ChangeDetail | null; onClose: () => void; onSaved?: (change: ChangeDetail) => void }) {
  const editing = Boolean(change);
  const { hasRole } = useAuth();
  const editor = hasRole(...CHANGE_EDITORS);
  const toast = useToast();
  const save = useSaveChange();
  const templates = useChangeTemplates();
  const questions = useRiskQuestions();
  const services = useServices();
  const staff = useAssignableUsers(null);
  const teams = useTeams({ active: true });
  const freezes = useFreezeWindows();

  const steps: Step[] = editing ? ['details', 'risk', 'plans', 'window'] : ['type', 'details', 'risk', 'plans', 'window'];
  const [stepIndex, setStepIndex] = useState(0);
  const step = steps[stepIndex];

  const [type, setType] = useState<ChangeType>(change?.type ?? (editor ? 'NORMAL' : 'EMERGENCY'));
  const [templateId, setTemplateId] = useState(change?.templateId ? String(change.templateId) : '');
  const [title, setTitle] = useState(change?.title ?? '');
  const [description, setDescription] = useState(change?.description ?? '');
  const [justification, setJustification] = useState(change?.justification ?? '');
  const [category, setCategory] = useState<string>(change?.category ?? '');
  const [priority, setPriority] = useState<Priority>(change?.priority ?? 'MEDIUM');
  const [impact, setImpact] = useState<string>(change?.impact ?? '');
  const [affectedUsers, setAffectedUsers] = useState(change ? String(change.affectedUsers ?? 0) : '');
  const [serviceIds, setServiceIds] = useState<number[]>(change?.services.map((service) => service.id) ?? []);
  const [linkedCis, setLinkedCis] = useState<CiRef[]>(change?.cis ?? []);
  // Legacy free-text names that are not CMDB records stay editable as text.
  const [cis, setCis] = useState(unlinkedCiNames(change?.affectedCis, change?.cis).join(', '));
  const [assigneeId, setAssigneeId] = useState(change?.assigneeId ? String(change.assigneeId) : '');
  const [teamId, setTeamId] = useState(change?.teamId ? String(change.teamId) : '');
  const [answers, setAnswers] = useState<Record<string, string>>(change?.riskAnswers ?? {});
  const [implementationPlan, setImplementationPlan] = useState(change?.implementationPlan ?? '');
  const [rollbackPlan, setRollbackPlan] = useState(change?.rollbackPlan ?? '');
  const [testPlan, setTestPlan] = useState(change?.testPlan ?? '');
  const [start, setStart] = useState(isoToLocalInput(change?.plannedStart));
  const [end, setEnd] = useState(isoToLocalInput(change?.plannedEnd));
  const [stepError, setStepError] = useState<string | null>(null);
  const [submitAfter, setSubmitAfter] = useState(false);
  const submitTransition = useAnyChangeTransition();

  const template = templates.data?.find((candidate) => String(candidate.id) === templateId) ?? null;
  const startIso = localInputToIso(start);
  const endIso = localInputToIso(end);
  const tiers = serviceIds.map((id) => services.data?.find((service) => service.id === id)?.tier ?? 3);
  const live = liveRisk({ type, answers, questions: questions.data ?? [], serviceTiers: tiers, plannedStart: startIso ? new Date(startIso) : null });

  // Conflict and freeze warnings for the planned window (server re-checks when scheduling).
  const windowValid = Boolean(startIso && endIso && new Date(endIso) > new Date(startIso));
  const calendarFrom = startIso ? new Date(new Date(startIso).getTime() - 86_400_000).toISOString() : '';
  const calendarTo = endIso ? new Date(new Date(endIso).getTime() + 86_400_000).toISOString() : '';
  const rangeOk = windowValid && new Date(calendarTo).getTime() - new Date(calendarFrom).getTime() <= 90 * 86_400_000;
  const calendar = useChangeCalendar(calendarFrom, calendarTo, undefined, step === 'window' && rangeOk);
  const conflicts = useMemo(() => {
    if (!windowValid || !calendar.data || !startIso || !endIso) return [];
    return calendar.data.entries.filter(
      (entry) =>
        entry.id !== change?.id &&
        PLANNED.includes(entry.status) &&
        entry.start &&
        entry.end &&
        overlaps(new Date(startIso), new Date(endIso), new Date(entry.start), new Date(entry.end)) &&
        entry.services.some((service) => serviceIds.includes(service.id)),
    );
  }, [calendar.data, windowValid, startIso, endIso, serviceIds, change?.id]);
  const frozen = useMemo(() => {
    if (!windowValid || !freezes.data || !startIso || !endIso) return [];
    return freezes.data.filter(
      (freeze) => freeze.active && overlaps(new Date(startIso), new Date(endIso), new Date(freeze.startsAt), new Date(freeze.endsAt)) && (freeze.serviceIds.length === 0 || freeze.serviceIds.some((id) => serviceIds.includes(id))),
    );
  }, [freezes.data, windowValid, startIso, endIso, serviceIds]);

  const chooseTemplate = (id: string) => {
    setTemplateId(id);
    const chosen = templates.data?.find((candidate) => String(candidate.id) === id);
    if (!chosen) return;
    if (!title.trim()) setTitle(chosen.name);
    setImplementationPlan(chosen.implementationPlan);
    setRollbackPlan(chosen.rollbackPlan);
    setTestPlan(chosen.testPlan ?? '');
    if (chosen.category) setCategory(chosen.category);
    if (chosen.serviceIds.length && !serviceIds.length) setServiceIds(chosen.serviceIds);
  };

  const validateStep = (current: Step): string | null => {
    if (current === 'type' && type === 'STANDARD' && !templateId) return 'Choose the standard-change template.';
    if (current === 'details' && type !== 'STANDARD' && title.trim().length < 5) return 'Give a title of at least 5 characters.';
    if (current === 'details' && title.trim() && title.trim().length < 5) return 'The title needs at least 5 characters.';
    if (current === 'window' && (start || end) && !windowValid) return 'The window must end after it starts.';
    return null;
  };

  const next = () => {
    const problem = validateStep(step);
    setStepError(problem);
    if (!problem) setStepIndex((index) => Math.min(index + 1, steps.length - 1));
  };

  const body = (): ChangeRequestBody => {
    let plannedEnd = endIso;
    if (startIso && !plannedEnd && template) plannedEnd = addMinutes(startIso, template.defaultDurationMinutes);
    return {
      title: title.trim() || null,
      description: description.trim() || null,
      ...(editing ? {} : { type, templateId: type === 'STANDARD' && templateId ? Number(templateId) : null }),
      category: (category || null) as Category | null,
      priority,
      impact: (impact || null) as Level | null,
      affectedUsers: affectedUsers.trim() ? Number(affectedUsers) : null,
      affectedCis: parseList(cis),
      ciIds: linkedCis.map((ci) => ci.id),
      justification: justification.trim() || null,
      implementationPlan: implementationPlan.trim() || null,
      rollbackPlan: rollbackPlan.trim() || null,
      testPlan: testPlan.trim() || null,
      plannedStart: startIso,
      plannedEnd,
      serviceIds,
      assigneeId: assigneeId ? Number(assigneeId) : null,
      teamId: teamId ? Number(teamId) : null,
      riskAnswers: type === 'STANDARD' ? null : answers,
    };
  };

  const finish = (andSubmit: boolean) => {
    const problem = validateStep(step) ?? validateStep('details');
    setStepError(problem);
    if (problem) return;
    setSubmitAfter(andSubmit);
    save.mutate(
      { id: change?.id, body: body() },
      {
        onSuccess: (saved) => {
          if (!andSubmit) {
            toast.success(editing ? `${saved.number} saved` : `${saved.number} created as a draft`);
            onSaved?.(saved);
            onClose();
            return;
          }
          submitTransition.mutate(
            { id: saved.id, body: { status: 'SUBMITTED' } },
            {
              onSuccess: (submitted) => {
                toast.success(`${submitted.number} submitted${submitted.status === 'APPROVED' ? ' and pre-approved' : submitted.status === 'AWAITING_APPROVAL' ? ' — waiting for the emergency CAB' : ''}`);
                onSaved?.(submitted);
                onClose();
              },
              onError: (error) => {
                toast.warning(`${saved.number} was saved as a draft but could not be submitted: ${errorMessage(error)}`);
                onSaved?.(saved);
                onClose();
              },
            },
          );
        },
      },
    );
  };

  const serverErrors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => serverErrors.find((error) => error.field === field || error.field.startsWith(`${field}.`))?.message;
  const pending = save.isPending || submitTransition.isPending;
  const isLast = stepIndex === steps.length - 1;
  const canSubmitNow = !editing || change?.status === 'DRAFT';

  return (
    <Modal
      open
      onClose={onClose}
      title={editing ? `Edit ${change?.number}` : 'New change request'}
      eyebrow="CHANGE ENABLEMENT"
      size="xl"
      dismissible={!pending}
      footer={
        <>
          {stepIndex > 0 && (
            <button type="button" className="link-btn mr-auto text-[11px]" onClick={() => setStepIndex((index) => index - 1)} disabled={pending}>
              <ArrowLeft size={13} aria-hidden="true" /> Back
            </button>
          )}
          <button type="button" className="subtle-btn" onClick={onClose} disabled={pending}>
            Cancel
          </button>
          {(isLast || editing) && (
            <button type="button" className={isLast && !canSubmitNow ? 'gold-btn' : 'subtle-btn'} onClick={() => finish(false)} disabled={pending}>
              {save.isPending && !submitAfter ? 'Saving…' : editing ? 'Save changes' : 'Save as draft'}
            </button>
          )}
          {isLast && canSubmitNow ? (
            <button type="button" className="gold-btn" onClick={() => finish(true)} disabled={pending}>
              {pending && submitAfter ? 'Submitting…' : editing ? 'Save and submit' : 'Create and submit'}
            </button>
          ) : (
            !isLast && (
              <button type="button" className="gold-btn" onClick={next} disabled={pending}>
                Next <ArrowRight size={13} aria-hidden="true" />
              </button>
            )
          )}
        </>
      }
    >
      <div className="mb-4 overflow-x-auto">
        <Stepper steps={steps.map((id) => STEP_LABELS[id])} current={stepIndex} label="Change wizard progress" />
      </div>

      {step === 'type' && (
        <div className="grid gap-3">
          <div className="grid gap-2 md:grid-cols-3" role="radiogroup" aria-label="Change type">
            {(['STANDARD', 'NORMAL', 'EMERGENCY'] as ChangeType[]).map((value) => {
              const allowed = editor || value === 'EMERGENCY';
              return (
                <button
                  key={value}
                  type="button"
                  role="radio"
                  aria-checked={type === value}
                  disabled={!allowed}
                  className={`panel raised text-left ${type === value ? 'selected !border-[#b8955e]' : ''} ${allowed ? '' : 'opacity-50'}`}
                  onClick={() => setType(value)}
                >
                  <b className="flex items-center gap-1.5 text-[13px]">
                    {value === 'EMERGENCY' && <ShieldAlert size={14} aria-hidden="true" />} {CHANGE_TYPE_LABELS[value]}
                  </b>
                  <small className="mt-1 block text-[10px] leading-relaxed text-muted">{TYPE_HELP[value]}</small>
                  {!allowed && <small className="mt-1 block text-[10px]">Incident managers raise emergency changes only.</small>}
                </button>
              );
            })}
          </div>
          {type === 'STANDARD' && (
            <>
              {templates.isLoading && <LoadingState variant="inline" label="Loading templates…" />}
              {templates.isError && <ErrorState error={templates.error} compact />}
              {templates.data && (
                <SelectField label="Standard-change template" required value={templateId} onChange={(event) => chooseTemplate(event.target.value)} hint={template?.description ?? undefined}>
                  <option value="">Choose a template</option>
                  {templates.data.map((candidate) => (
                    <option key={candidate.id} value={candidate.id}>
                      {candidate.name} ({candidate.code}) · {candidate.defaultDurationMinutes} min
                    </option>
                  ))}
                </SelectField>
              )}
            </>
          )}
        </div>
      )}

      {step === 'details' && (
        <>
          <TextField
            label="Title"
            required={type !== 'STANDARD'}
            maxLength={200}
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            error={fieldError('title')}
            hint={type === 'STANDARD' ? 'Defaults to the template name.' : undefined}
          />
          <TextareaField label="Description" rows={3} maxLength={10000} value={description} onChange={(event) => setDescription(event.target.value)} />
          <TextareaField label="Justification" rows={2} maxLength={5000} value={justification} onChange={(event) => setJustification(event.target.value)} />
          <div className="form-row three">
            <SelectField label="Category" value={category} onChange={(event) => setCategory(event.target.value)}>
              <option value="">Not set</option>
              {CATEGORIES.map((value) => (
                <option key={value} value={value}>
                  {titleCase(value)}
                </option>
              ))}
            </SelectField>
            <SelectField label="Priority" value={priority} onChange={(event) => setPriority(event.target.value as Priority)}>
              {PRIORITIES.map((value) => (
                <option key={value} value={value}>
                  {PRIORITY_LABELS[value]}
                </option>
              ))}
            </SelectField>
            <SelectField label="Impact" value={impact} onChange={(event) => setImpact(event.target.value)}>
              <option value="">Not set</option>
              {LEVELS.map((value) => (
                <option key={value} value={value}>
                  {titleCase(value)}
                </option>
              ))}
            </SelectField>
          </div>
          <ServiceMultiPicker value={serviceIds} onChange={setServiceIds} error={fieldError('serviceIds')} hint="At least one service is needed to submit; the first service's team is the default." />
          <CiMultiPicker label="Configuration items" value={linkedCis} onChange={setLinkedCis} error={fieldError('ciIds')} hint="Linked CMDB records (up to 30); their names are added to the change and its conflict check." />
          <div className="form-row">
            <TextField label="Other affected items" placeholder="vendor gateway, lb-edge-2" hint="Comma-separated names that are not in the CMDB (kept as text)." value={cis} onChange={(event) => setCis(event.target.value)} error={fieldError('affectedCis')} />
            <TextField label="Affected users" type="number" min={0} value={affectedUsers} onChange={(event) => setAffectedUsers(event.target.value)} error={fieldError('affectedUsers')} />
          </div>
          <div className="form-row">
            <SelectField label="Implementer" value={assigneeId} onChange={(event) => setAssigneeId(event.target.value)} error={fieldError('assigneeId')} disabled={staff.isError}>
              <option value="">Not assigned</option>
              {(staff.data ?? []).map((person) => (
                <option key={person.id} value={person.id}>
                  {person.fullName}
                </option>
              ))}
            </SelectField>
            <SelectField label="Team" value={teamId} onChange={(event) => setTeamId(event.target.value)} disabled={teams.isError}>
              <option value="">Default (first service's team)</option>
              {(teams.data ?? []).map((team) => (
                <option key={team.id} value={team.id}>
                  {team.name}
                </option>
              ))}
            </SelectField>
          </div>
        </>
      )}

      {step === 'risk' && (
        <div className="grid gap-3 lg:grid-cols-[1.3fr_1fr]">
          <div>
            {type === 'STANDARD' ? (
              <p className="text-[11px] text-muted">Standard changes are pre-approved and low risk; no questionnaire is needed.</p>
            ) : (
              <>
                {questions.isLoading && <LoadingState variant="inline" label="Loading questions…" />}
                {questions.isError && <ErrorState error={questions.error} compact />}
                {(questions.data ?? []).map((question) => (
                  <SelectField
                    key={question.key}
                    label={question.text}
                    value={answers[question.key] ?? ''}
                    onChange={(event) => setAnswers((current) => ({ ...current, [question.key]: event.target.value }))}
                    error={fieldError(`riskAnswers.${question.key}`)}
                  >
                    <option value="">Not answered yet</option>
                    {question.options.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </SelectField>
                ))}
              </>
            )}
          </div>
          <aside className="panel raised" aria-label="Computed risk">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <b className="text-[12px]">Computed risk</b>
              <ToneBadge value={live.risk} label={`${titleCase(live.risk)} · score ${live.score}`} tone={RISK_TONES[live.risk]} />
            </div>
            <p className="mt-1 mb-2 inline-flex items-center gap-1 text-[10px] text-muted">
              <Cpu size={11} aria-hidden="true" /> Risk model (rules, not AI). The server adds failed changes on these services and is authoritative; SA AI analysis is available on the change once saved.
            </p>
            {live.factors.length === 0 ? (
              <p className="m-0 text-[11px] text-muted">No risk factors.</p>
            ) : (
              <ul className="m-0 grid gap-1 pl-4 text-[11px]">
                {live.factors.map((factor) => (
                  <li key={factor.key}>
                    {factor.label} <span className="text-muted">(+{factor.points})</span>
                  </li>
                ))}
              </ul>
            )}
            {live.risk === 'HIGH' && type === 'NORMAL' && <p className="mt-2 mb-0 text-[11px] warning-text">High risk adds the Change Advisory Board stage to the approval.</p>}
          </aside>
        </div>
      )}

      {step === 'plans' && (
        <>
          {type === 'STANDARD' && template && <p className="field-hint mb-2">Prefilled from template {template.code}; adjust if needed.</p>}
          <TextareaField
            label="Implementation plan"
            required={type !== 'STANDARD'}
            rows={5}
            maxLength={20000}
            value={implementationPlan}
            onChange={(event) => setImplementationPlan(event.target.value)}
            error={fieldError('implementationPlan')}
          />
          <TextareaField label="Rollback plan" required={type !== 'STANDARD'} rows={4} maxLength={20000} value={rollbackPlan} onChange={(event) => setRollbackPlan(event.target.value)} error={fieldError('rollbackPlan')} />
          <TextareaField label="Test plan" rows={3} maxLength={20000} value={testPlan} onChange={(event) => setTestPlan(event.target.value)} error={fieldError('testPlan')} />
        </>
      )}

      {step === 'window' && (
        <>
          <div className="form-row">
            <TextField label="Planned start" type="datetime-local" value={start} onChange={(event) => setStart(event.target.value)} error={fieldError('plannedStart')} />
            <TextField
              label="Planned end"
              type="datetime-local"
              value={end}
              onChange={(event) => setEnd(event.target.value)}
              error={fieldError('plannedEnd')}
              hint={template && !end ? `Defaults to start + ${template.defaultDurationMinutes} min (template).` : 'At most 14 days after the start.'}
            />
          </div>
          {!start && <p className="field-hint">The window can be planned later; CAB approval needs it.</p>}
          {windowValid && calendar.isLoading && <LoadingState variant="inline" label="Checking the change calendar…" />}
          {conflicts.length > 0 && (
            <div className="callout red" role="alert">
              <AlertTriangle size={16} aria-hidden="true" />
              <div>
                <b>Possible conflicts on the same services:</b>
                <ul className="m-0 mt-1 pl-4">
                  {conflicts.map((entry) => (
                    <li key={entry.id}>
                      {entry.number} {entry.title} · {formatDateTime(entry.start)} – {formatDateTime(entry.end)}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          )}
          {frozen.length > 0 && (
            <div className="callout" role="alert">
              <Snowflake size={16} aria-hidden="true" />
              <div>
                <b>Inside a change freeze:</b> {frozen.map((freeze) => `${freeze.name} (${formatDateTime(freeze.startsAt)} – ${formatDateTime(freeze.endsAt)})`).join('; ')}.
                {type === 'EMERGENCY' ? ' Emergency changes may still be scheduled.' : ' Only emergency changes can be scheduled then.'}
              </div>
            </div>
          )}
          {windowValid && calendar.data && conflicts.length === 0 && frozen.length === 0 && (
            <p className="inline-flex items-center gap-1 text-[11px] green-text">
              <CalendarX2 size={12} aria-hidden="true" /> No conflicting change or freeze window found for these services.
            </p>
          )}
        </>
      )}

      {stepError && (
        <p className="field-error mt-2" role="alert">
          {stepError}
        </p>
      )}
      {save.isError && (
        <p className="field-error mt-2" role="alert">
          {serverErrors.length ? serverErrors.map((error) => error.message).join(' · ') : errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}
