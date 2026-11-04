import { useEffect } from 'react';
import { useFieldArray, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ArrowDown, ArrowUp, Plus, ShieldAlert, Trash2, X } from 'lucide-react';
import { useSaveRunbook } from '../../api/runbooks';
import { useServices } from '../../api/services';
import { CheckboxField, idOrNull, SelectField, TextareaField, TextField, textOrNull, toNumberOrNull } from '../../components/FormField';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { CATEGORIES } from '../../lib/constants';
import { titleCase } from '../../lib/format';
import { runbookSchema } from '../../lib/schemas';
import type { RunbookDto, RunbookRequest } from '../../lib/types';
import '../../components/runbooks/runbooks.css';

/** Prerequisites are edited as a list (one field per item) instead of the shared schema's text block. */
export const runbookEditorSchema = runbookSchema.omit({ prerequisites: true }).extend({
  prerequisites: z.array(z.object({ value: z.string().trim().max(300, 'Keep each prerequisite under 300 characters') })),
});
export type RunbookEditorValues = z.infer<typeof runbookEditorSchema>;

export function toRunbookRequest(values: RunbookEditorValues): RunbookRequest {
  return {
    title: values.title.trim(),
    description: textOrNull(values.description) ?? undefined,
    serviceId: idOrNull(values.serviceId),
    categories: values.categories,
    prerequisites: values.prerequisites.map((item) => item.value.trim()).filter(Boolean),
    steps: values.steps.map((step, index) => ({
      order: index + 1,
      title: step.title.trim(),
      instruction: step.instruction.trim(),
      requiresApproval: step.requiresApproval,
    })),
    requiresApproval: values.requiresApproval,
    estimatedMinutes: values.estimatedMinutes,
    active: values.active,
  };
}

/** Runbook editor (RUNBOOK_EDITORS): details, categories, prerequisites, ordered steps with sensitive flags, approval and active flags. */
export function RunbookEditor({ runbook, onClose, onSaved }: { runbook: RunbookDto | null; onClose: () => void; onSaved?: (saved: RunbookDto) => void }) {
  const save = useSaveRunbook();
  const services = useServices();
  const toast = useToast();
  const {
    register,
    handleSubmit,
    control,
    setError,
    setValue,
    getValues,
    formState: { errors },
  } = useForm<RunbookEditorValues>({
    resolver: zodResolver(runbookEditorSchema),
    defaultValues: {
      title: runbook?.title ?? '',
      description: runbook?.description ?? '',
      serviceId: runbook?.serviceId ? String(runbook.serviceId) : '',
      categories: runbook?.categories ?? [],
      prerequisites: (runbook?.prerequisites ?? []).map((value) => ({ value })),
      steps: runbook?.steps.length
        ? [...runbook.steps]
            .sort((a, b) => a.order - b.order)
            .map((step) => ({ title: step.title, instruction: step.instruction, requiresApproval: Boolean(step.requiresApproval) }))
        : [{ title: '', instruction: '', requiresApproval: false }],
      requiresApproval: runbook?.requiresApproval ?? false,
      estimatedMinutes: runbook?.estimatedMinutes ?? null,
      active: runbook?.active ?? true,
    },
  });
  // The service options can arrive after the form mounted: re-apply the saved service once they are there.
  useEffect(() => {
    if (services.data) setValue('serviceId', getValues('serviceId'));
  }, [services.data, getValues, setValue]);
  const steps = useFieldArray({ control, name: 'steps' });
  const prerequisites = useFieldArray({ control, name: 'prerequisites' });
  const watchedSteps = useWatch({ control, name: 'steps' });
  const sensitive = (watchedSteps ?? []).filter((step) => step?.requiresApproval).length;

  const onSubmit = handleSubmit((values) =>
    save.mutate(
      { id: runbook?.id, body: toRunbookRequest(values) },
      {
        onSuccess: (saved) => {
          toast.success(runbook ? 'Runbook updated' : 'Runbook created');
          onSaved?.(saved);
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  return (
    <Modal open onClose={onClose} title={runbook ? `Edit ${runbook.title}` : 'New runbook'} eyebrow="RUNBOOKS" size="xl" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <TextField label="Title" required error={errors.title?.message} {...register('title')} />
        <TextareaField label="Description" rows={2} error={errors.description?.message} {...register('description')} />
        <div className="form-row">
          <SelectField label="Service" error={errors.serviceId?.message} {...register('serviceId')}>
            <option value="">Any service</option>
            {services.data?.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Estimated minutes"
            type="number"
            min={1}
            error={errors.estimatedMinutes?.message}
            {...register('estimatedMinutes', { setValueAs: toNumberOrNull })}
          />
        </div>

        <fieldset className="field">
          <legend className="mb-1">Linked incident categories</legend>
          <div className="rb-category-grid">
            {CATEGORIES.map((category) => (
              <CheckboxField key={category} label={titleCase(category)} value={category} {...register('categories')} />
            ))}
          </div>
          <span className="field-hint">None selected = suggested for every category.</span>
        </fieldset>

        <fieldset className="field">
          <legend className="mb-1">Prerequisites</legend>
          {prerequisites.fields.length === 0 && <p className="mb-2 text-[10px] text-muted">No prerequisites.</p>}
          {prerequisites.fields.map((field, index) => (
            <div key={field.id} className="rb-prereq-row">
              <TextField
                label={`Prerequisite ${index + 1}`}
                className="field inline"
                placeholder="e.g. VPN access to the payroll subnet"
                error={errors.prerequisites?.[index]?.value?.message}
                {...register(`prerequisites.${index}.value`)}
              />
              <button type="button" className="icon-btn sm mt-[22px]" aria-label={`Remove prerequisite ${index + 1}`} onClick={() => prerequisites.remove(index)}>
                <X size={14} />
              </button>
            </div>
          ))}
          <button type="button" className="subtle-btn compact" onClick={() => prerequisites.append({ value: '' })}>
            <Plus size={13} /> Add prerequisite
          </button>
        </fieldset>

        <div className="flex flex-wrap gap-6">
          <CheckboxField label="Requires Incident Manager approval before execution" {...register('requiresApproval')} />
          <CheckboxField label="Active" {...register('active')} />
        </div>

        <div className="form-section-title">
          Steps{sensitive > 0 ? ` · ${sensitive} sensitive` : ''}
        </div>
        {errors.steps?.message && <p className="field-error">{errors.steps.message}</p>}
        {steps.fields.map((field, index) => (
          <div key={field.id} className="action-card">
            <div className="flex items-end gap-2">
              <span className="step-num mb-[15px]">{index + 1}</span>
              <TextField label="Step title" className="field inline flex-1" required error={errors.steps?.[index]?.title?.message} {...register(`steps.${index}.title`)} />
              <div className="mb-1 flex gap-1">
                <button type="button" className="icon-btn sm" aria-label={`Move step ${index + 1} up`} disabled={index === 0} onClick={() => steps.move(index, index - 1)}>
                  <ArrowUp size={14} />
                </button>
                <button
                  type="button"
                  className="icon-btn sm"
                  aria-label={`Move step ${index + 1} down`}
                  disabled={index === steps.fields.length - 1}
                  onClick={() => steps.move(index, index + 1)}
                >
                  <ArrowDown size={14} />
                </button>
                <button type="button" className="icon-btn sm" aria-label={`Remove step ${index + 1}`} disabled={steps.fields.length === 1} onClick={() => steps.remove(index)}>
                  <Trash2 size={14} />
                </button>
              </div>
            </div>
            <TextareaField label="Instruction" required error={errors.steps?.[index]?.instruction?.message} {...register(`steps.${index}.instruction`)} />
            <CheckboxField
              label={
                <span className="inline-flex items-center gap-1">
                  <ShieldAlert size={11} className="text-warning" /> Sensitive step — completion needs Incident Manager approval
                </span>
              }
              {...register(`steps.${index}.requiresApproval`)}
            />
          </div>
        ))}
        <button type="button" className="subtle-btn compact" onClick={() => steps.append({ title: '', instruction: '', requiresApproval: false })}>
          <Plus size={13} /> Add step
        </button>
        {save.isError && !Object.keys(errors).length && <p className="field-error mt-2">{errorMessage(save.error)}</p>}
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save runbook'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
