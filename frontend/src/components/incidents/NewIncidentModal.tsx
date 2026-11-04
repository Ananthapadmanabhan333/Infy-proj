import { useMemo, useState, type ReactNode } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate } from 'react-router-dom';
import { CheckCircle2, Clock3, Gauge, GitMerge, Plus, Server, Sparkles, Tags, UserCheck, Users } from 'lucide-react';
import { useCreateIncident, usePreviewDuplicates } from '../../api/incidents';
import { useServices } from '../../api/services';
import { useDebounce } from '../../hooks/useDebounce';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { LEVELS } from '../../lib/constants';
import { formatPercent, titleCase } from '../../lib/format';
import { incidentCreateSchema, type IncidentCreateValues } from '../../lib/schemas';
import type { CreateIncidentRequest, CreateIncidentResult } from '../../lib/types';
import { Badge, DemoBadge, PriorityBadge, StatusBadge } from '../Badge';
import { idOrNull, SelectField, TextareaField, TextField } from '../FormField';
import { Modal } from '../Modal';
import { SlaCountdown } from '../SlaCountdown';

export interface NewIncidentPrefill {
  title?: string;
  description?: string;
  serviceId?: number | null;
}

const CLASSIFICATION_LABELS: Record<string, string> = {
  MANUAL: 'Manual',
  RULES: 'Rules engine',
  AI: 'SA AI',
  MONITORING: 'Monitoring',
};

function Fact({ icon, label, children }: { icon: ReactNode; label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 items-start gap-2.5 rounded-[10px] border border-line bg-panel-raised p-3">
      <span className="grid size-7 shrink-0 place-items-center rounded-[8px] border border-[rgba(214,183,122,0.26)] bg-[var(--gold-soft)] text-gold" aria-hidden="true">
        {icon}
      </span>
      <div className="min-w-0">
        <small className="block text-[9px] font-semibold tracking-wider text-muted uppercase">{label}</small>
        <div className="mt-1 text-[12px] font-semibold text-ivory">{children}</div>
      </div>
    </div>
  );
}

/** Summary of what the backend decided when the incident was created (classification, priority, routing, SLA, duplicates). */
export function CreateIncidentResultView({ result }: { result: CreateIncidentResult }) {
  const { incident, classification, duplicate, assignment } = result;
  return (
    <div className="grid gap-3">
      <div className="callout green" role="status">
        <CheckCircle2 size={16} />
        <div className="min-w-0 flex-1">
          <b>{incident.number}</b> was logged and the SLA clock has started.
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <PriorityBadge priority={incident.priority} />
            <StatusBadge status={incident.status} />
            <DemoBadge demo={incident.demo} />
            <span className="truncate text-[10px] text-muted">{incident.title}</span>
          </div>
        </div>
      </div>

      {duplicate?.detected && (
        <div className="callout" role="note" data-testid="duplicate-callout">
          <GitMerge size={16} />
          <div>
            <b>
              Linked to parent {duplicate.parentNumber} ({duplicate.parentCreated ? 'new parent created' : 'existing parent'})
            </b>
            <div className="text-[10px] text-muted">
              Matched {duplicate.matchedIncidentNumber} with {formatPercent(duplicate.score * 100, 0)} similarity. Your report follows the parent's resolution.{' '}
              <Link to={`/incidents/${duplicate.parentId}`}>Open parent incident</Link>
            </div>
          </div>
        </div>
      )}

      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        <Fact icon={<Tags size={14} />} label="Classification">
          {titleCase(classification.category)}
          <span className="mt-1.5 flex flex-wrap items-center gap-1.5 text-[10px] font-normal text-muted">
            <Badge value="outline" label={CLASSIFICATION_LABELS[classification.source] ?? titleCase(classification.source)} />
            {classification.confidence !== null && `${formatPercent(classification.confidence * 100, 0)} confidence`}
          </span>
        </Fact>
        <Fact icon={<Server size={14} />} label="Service">
          {classification.serviceName ?? incident.serviceName ?? 'Not identified'}
        </Fact>
        <Fact icon={<Gauge size={14} />} label="Priority">
          <PriorityBadge priority={incident.priority} />
          <span className="mt-1.5 block text-[10px] font-normal text-muted">
            Impact {titleCase(incident.impact)} · Urgency {titleCase(incident.urgency)}
          </span>
        </Fact>
        <Fact icon={<Users size={14} />} label="Assigned team">
          {assignment?.departmentName ?? incident.departmentName ?? 'Awaiting triage'}
        </Fact>
        <Fact icon={<UserCheck size={14} />} label="Assignee">
          {assignment?.assigneeName ?? incident.assigneeName ?? 'Unassigned'}
        </Fact>
        <Fact icon={<Clock3 size={14} />} label="SLA policy">
          {incident.sla?.policyName ?? '—'}
          {incident.sla && (
            <span className="mt-1.5 block text-[10px] font-normal">
              <SlaCountdown sla={incident.sla} /> <span className="text-muted">to resolve</span>
            </span>
          )}
        </Fact>
      </div>
    </div>
  );
}

function DuplicatePreview({ title, description, serviceId, onNavigate }: { title: string; description: string; serviceId: number | null; onNavigate: () => void }) {
  const raw = useMemo(() => ({ title: title.trim(), description: description.trim() || undefined, serviceId }), [title, description, serviceId]);
  const input = useDebounce(raw, 600);
  const enabled = input.title.length >= 5;
  const preview = usePreviewDuplicates(input, enabled);
  if (!enabled) return null;
  if (preview.isFetching && !preview.data) return <p className="field-hint">Checking for similar open incidents…</p>;
  if (!preview.data?.length) return null;
  return (
    <div className="callout info mt-1" aria-live="polite">
      <Sparkles size={16} />
      <div className="min-w-0 flex-1">
        <b>Possible duplicates</b>
        <div className="mt-1 grid gap-1">
          {preview.data.slice(0, 5).map((candidate) => (
            <div key={candidate.incidentId} className="flex flex-wrap items-center gap-2 text-[10px]">
              <Link to={`/incidents/${candidate.incidentId}`} onClick={onNavigate} className="id-cell">
                {candidate.number}
              </Link>
              <span className="min-w-0 flex-1 truncate">{candidate.title}</span>
              <StatusBadge status={candidate.status} />
              <span className="gold-text font-bold">{formatPercent(candidate.score * 100, 0)} match</span>
            </div>
          ))}
        </div>
        <p className="mt-1 text-[10px] text-muted">If this is the same issue, SA will link your report to the existing parent automatically.</p>
      </div>
    </div>
  );
}

function SectionLabel({ children }: { children: ReactNode }) {
  return <p className="mt-1 mb-1 text-[9px] font-bold tracking-[1.5px] text-[#a38a64] uppercase">{children}</p>;
}

export function NewIncidentModal({ onClose, prefill }: { onClose: () => void; prefill?: NewIncidentPrefill }) {
  const [result, setResult] = useState<CreateIncidentResult | null>(null);
  const navigate = useNavigate();
  const services = useServices();
  const create = useCreateIncident();
  const {
    register,
    handleSubmit,
    control,
    setError,
    reset,
    formState: { errors },
  } = useForm<IncidentCreateValues>({
    resolver: zodResolver(incidentCreateSchema),
    defaultValues: {
      title: prefill?.title ?? '',
      description: prefill?.description ?? '',
      serviceId: prefill?.serviceId ? String(prefill.serviceId) : '',
      impact: '',
      urgency: '',
      affectedUsers: 1,
    },
  });
  const [title, description, serviceId] = useWatch({ control, name: ['title', 'description', 'serviceId'] });

  const onSubmit = handleSubmit((values) => {
    const body: CreateIncidentRequest = {
      title: values.title.trim(),
      description: values.description.trim() || undefined,
      serviceId: idOrNull(values.serviceId) ?? undefined,
      impact: values.impact || undefined,
      urgency: values.urgency || undefined,
      affectedUsers: values.affectedUsers,
      source: 'PORTAL',
    };
    create.mutate(body, {
      onSuccess: (data) => {
        setResult(data);
        navigate(`/incidents/${data.incident.id}`);
      },
      onError: (error) => applyFieldErrors(error, setError),
    });
  });

  return (
    <Modal
      open
      onClose={onClose}
      eyebrow="INCIDENT MANAGEMENT"
      title={result ? 'Incident created' : 'Create new incident'}
      description={result ? undefined : 'SA classifies, prioritises, checks for duplicates and assigns the incident when you submit.'}
      size="lg"
      dismissible={!create.isPending}
    >
      {result ? (
        <>
          <CreateIncidentResultView result={result} />
          <div className="modal-foot">
            <button
              type="button"
              className="subtle-btn"
              onClick={() => {
                setResult(null);
                create.reset();
                reset();
              }}
            >
              <Plus size={15} /> Create another
            </button>
            <button type="button" className="gold-btn" onClick={onClose} data-autofocus>
              View incident
            </button>
          </div>
        </>
      ) : (
        <form onSubmit={onSubmit} noValidate>
          <SectionLabel>What happened</SectionLabel>
          <TextField label="Title" required placeholder="Describe the issue in a sentence" maxLength={200} error={errors.title?.message} {...register('title')} />
          <TextareaField
            label="Description"
            placeholder="What happened, who is affected, any error messages…"
            maxLength={5000}
            error={errors.description?.message}
            {...register('description')}
          />
          <DuplicatePreview title={title ?? ''} description={description ?? ''} serviceId={idOrNull(serviceId)} onNavigate={onClose} />

          <SectionLabel>Where and how big</SectionLabel>
          <SelectField
            label="Service"
            error={errors.serviceId?.message}
            hint={services.isError ? 'Services could not be loaded — SA will classify the incident.' : 'Leave on “Let SA classify” if you are not sure.'}
            {...register('serviceId')}
          >
            <option value="">Let SA classify</option>
            {services.data?.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </SelectField>
          <div className="form-row three">
            <SelectField label="Impact" error={errors.impact?.message} {...register('impact')}>
              <option value="">Auto</option>
              {LEVELS.map((level) => (
                <option key={level} value={level}>
                  {titleCase(level)}
                </option>
              ))}
            </SelectField>
            <SelectField label="Urgency" error={errors.urgency?.message} {...register('urgency')}>
              <option value="">Auto</option>
              {LEVELS.map((level) => (
                <option key={level} value={level}>
                  {titleCase(level)}
                </option>
              ))}
            </SelectField>
            <TextField
              label="Affected users"
              type="number"
              min={1}
              inputMode="numeric"
              error={errors.affectedUsers?.message}
              {...register('affectedUsers', { valueAsNumber: true })}
            />
          </div>
          <p className="field-hint">Priority is calculated from impact × urgency; “Auto” lets SA derive them from the service tier, affected users and keywords.</p>
          {create.isError && !Object.keys(errors).length && (
            <p className="field-error" role="alert">
              {errorMessage(create.error)}
            </p>
          )}
          <div className="modal-foot">
            <button type="button" className="subtle-btn" onClick={onClose} disabled={create.isPending}>
              Cancel
            </button>
            <button type="submit" className="gold-btn" disabled={create.isPending}>
              <Plus size={15} /> {create.isPending ? 'Creating…' : 'Create incident'}
            </button>
          </div>
        </form>
      )}
    </Modal>
  );
}
