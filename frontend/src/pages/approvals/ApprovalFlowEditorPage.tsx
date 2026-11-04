import { useEffect, useMemo, useRef, useState } from 'react';
import { useFieldArray, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { GitBranch, Lock, Plus, Save, Trash2 } from 'lucide-react';
import { useApprovalFlow, useDeleteApprovalFlow, useSaveApprovalFlow } from '../../api/approvalFlows';
import { useTeams } from '../../api/teams';
import { BooleanBadge, DemoBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { CheckboxField, SelectField, TextareaField, TextField } from '../../components/FormField';
import { Fact, ToneBadge } from '../../components/itsm/ItsmParts';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { APPROVAL_FLOW_EDITORS } from '../../lib/constants';
import { formatDateTime } from '../../lib/format';
import type { ApprovalFlowDto } from '../../lib/itsmTypes';
import type { TeamDto } from '../../lib/types';
import {
  describeApprover,
  describeCondition,
  describeEscalation,
  describeQuorum,
  emptyFlowValues,
  emptyStage,
  flowFormSchema,
  flowToValues,
  mapFlowFieldErrors,
  MAX_STAGES,
  MODE_LABELS,
  SUBJECT_TYPE_OPTIONS,
  subjectTypeLabel,
  toFlowRequest,
  type FlowFormValues,
} from './flowModel';
import { arrayMessage, StageCard } from './StageEditor';

const CRUMBS = [
  { label: 'Approvals', to: '/approvals' },
  { label: 'Approval flows', to: '/approvals/flows' },
];

/** Read-only view of a flow (readers without approval-flow editor rights). */
function FlowSummary({ flow, teams }: { flow: ApprovalFlowDto; teams: TeamDto[] }) {
  const teamName = (id: number) => teams.find((team) => team.id === id)?.name;
  return (
    <div className="grid items-start gap-[14px] xl:grid-cols-[1fr_2fr]">
      <Panel title="Details">
        <div className="detail-grid">
          <Fact label="Code">
            <span className="id-cell">{flow.code}</span>
          </Fact>
          <Fact label="Subject type">{subjectTypeLabel(flow.subjectType)}</Fact>
          <Fact label="Status">
            <BooleanBadge value={flow.active} />
          </Fact>
          <Fact label="Updated">{formatDateTime(flow.updatedAt)}</Fact>
        </div>
        {flow.description && <p className="mt-3 text-[11px]">{flow.description}</p>}
      </Panel>
      <Panel title={`Stages (${flow.stages.length})`} subtitle="Stages run one after another; a stage with a condition is skipped when it does not match.">
        <ol className="m-0 grid list-none gap-2 p-0" aria-label="Stages">
          {flow.stages.map((stage, index) => {
            const condition = describeCondition(stage);
            const escalation = describeEscalation(stage);
            return (
              <li key={`${index}-${stage.name}`} className="action-card" aria-label={`Stage ${index + 1}: ${stage.name}`}>
                <b className="flex items-center gap-2">
                  <span className="step-num">{index + 1}</span> {stage.name}
                </b>
                <small className="mt-1 block">
                  {MODE_LABELS[stage.mode] ?? stage.mode} · {describeQuorum(stage)}
                </small>
                <ul className="m-0 mt-1 pl-4 text-[11px]">
                  {stage.approvers.map((approver, approverIndex) => (
                    <li key={approverIndex}>{describeApprover(approver, teamName, flow.userNames)}</li>
                  ))}
                </ul>
                {condition && <small className="mt-1 block text-muted">{condition}</small>}
                {escalation && <small className="mt-1 block text-muted">{escalation}</small>}
              </li>
            );
          })}
        </ol>
      </Panel>
    </div>
  );
}

function FlowForm({ flow, teams, onDelete }: { flow: ApprovalFlowDto | null; teams: TeamDto[]; onDelete: () => void }) {
  const save = useSaveApprovalFlow();
  const toast = useToast();
  const navigate = useNavigate();
  const [general, setGeneral] = useState<string[]>([]);
  // Flow DTOs carry only user ids: remember the names picked in this session so they survive the reset after saving.
  const knownUsers = useRef(new Map<number, { name: string | null; role: string | null }>());
  const defaults = useMemo(() => {
    const values = flow ? flowToValues(flow) : emptyFlowValues();
    values.stages.forEach((stage) =>
      stage.approvers.forEach((approver) => {
        const known = approver.userId ? knownUsers.current.get(approver.userId) : undefined;
        if (known) Object.assign(approver, { userName: known.name, userRole: known.role });
      }),
    );
    return values;
  }, [flow]);
  const {
    register,
    control,
    handleSubmit,
    setError,
    setValue,
    reset,
    formState: { errors, isDirty },
  } = useForm<FlowFormValues>({ resolver: zodResolver(flowFormSchema), defaultValues: defaults });
  const stages = useFieldArray({ control, name: 'stages' });

  // Re-seed the form when the saved flow changes (after a save or a refetch), not on mount: a reset re-keys the stages.
  const seeded = useRef(defaults);
  useEffect(() => {
    if (seeded.current === defaults) return;
    seeded.current = defaults;
    reset(defaults);
  }, [defaults, reset]);

  const onSubmit = handleSubmit((values) => {
    setGeneral([]);
    values.stages.forEach((stage) =>
      stage.approvers.forEach((approver) => {
        if (approver.userId && approver.userName) knownUsers.current.set(approver.userId, { name: approver.userName, role: approver.userRole });
      }),
    );
    save.mutate(
      { id: flow?.id, body: toFlowRequest(values) },
      {
        onSuccess: (saved) => {
          toast.success(flow ? `${saved.name} saved` : `${saved.name} created`);
          if (!flow) navigate(`/approvals/flows/${saved.id}`, { replace: true });
        },
        onError: (error) => {
          const apiError = toApiError(error);
          if (apiError.status === 409 && !flow) {
            setError('code', { type: 'server', message: apiError.message }, { shouldFocus: true });
            return;
          }
          const mapped = mapFlowFieldErrors(apiError.fieldErrors);
          mapped.fields.forEach(({ name, message }, index) => setError(name as FieldPath<FlowFormValues>, { type: 'server', message }, { shouldFocus: index === 0 }));
          const rest = mapped.fields.length || mapped.general.length ? mapped.general : [apiError.message];
          setGeneral(rest);
        },
      },
    );
  });

  const stagesError = arrayMessage(errors.stages);

  return (
    <form onSubmit={onSubmit} noValidate aria-label={flow ? `Edit approval flow ${flow.name}` : 'New approval flow'}>
      <div className="grid items-start gap-[14px] xl:grid-cols-[1fr_2fr]">
        <Panel title="Details">
          <TextField
            label="Code"
            required
            maxLength={40}
            readOnly={Boolean(flow)}
            aria-readonly={Boolean(flow) || undefined}
            hint={flow ? 'The code cannot change after the flow is created.' : 'Capital letters, digits, _ or - (used by workflows: REQUEST_APPROVAL flowCode).'}
            error={errors.code?.message}
            {...register('code', { setValueAs: (value: string) => (typeof value === 'string' ? value.toUpperCase() : value) })}
          />
          <TextField label="Name" required maxLength={120} error={errors.name?.message} {...register('name')} />
          <TextareaField label="Description" rows={3} maxLength={500} error={errors.description?.message} {...register('description')} />
          <SelectField label="Subject type" hint="Empty = usable for any subject." error={errors.subjectType?.message} {...register('subjectType')}>
            <option value="">Any subject</option>
            {SUBJECT_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
          {flow?.systemFlow && flow.active ? (
            <CheckboxField label="Active" checked disabled readOnly />
          ) : (
            <CheckboxField label="Active" error={errors.active?.message} {...register('active')} />
          )}
          {flow?.systemFlow && <p className="field-hint m-0">System flow: changes depend on it, so it cannot be deactivated or deleted.</p>}
        </Panel>

        <Panel
          title={`Stages (${stages.fields.length})`}
          subtitle="Stages run one after another. Within a stage every approver becomes one task; the quorum decides when the stage is approved."
        >
          {stagesError && (
            <p className="field-error" role="alert">
              {stagesError}
            </p>
          )}
          <div className="grid gap-2">
            {stages.fields.map((field, index) => (
              <StageCard
                key={field.id}
                index={index}
                count={stages.fields.length}
                control={control}
                register={register}
                setValue={setValue}
                errors={errors}
                teams={teams}
                onMove={(to) => stages.move(index, to)}
                onRemove={() => stages.remove(index)}
              />
            ))}
          </div>
          <button type="button" className="subtle-btn compact mt-2" disabled={stages.fields.length >= MAX_STAGES} onClick={() => stages.append(emptyStage(stages.fields.length))}>
            <Plus size={13} aria-hidden="true" /> Add stage
          </button>
        </Panel>
      </div>

      {general.length > 0 && (
        <div className="callout red mt-[14px]" role="alert">
          <ul className="m-0 pl-4">
            {general.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        </div>
      )}
      <div className="mt-[14px] flex flex-wrap justify-end gap-2">
        {flow && !flow.systemFlow && (
          <button type="button" className="danger-btn" onClick={onDelete}>
            <Trash2 size={14} aria-hidden="true" /> Delete flow
          </button>
        )}
        <Link className="subtle-btn" to="/approvals/flows">
          Cancel
        </Link>
        <button type="submit" className="gold-btn" disabled={save.isPending || (Boolean(flow) && !isDirty)}>
          <Save size={14} aria-hidden="true" /> {save.isPending ? 'Saving…' : flow ? 'Save flow' : 'Create flow'}
        </button>
      </div>
    </form>
  );
}

/** /approvals/flows/new (approval-flow editors) and /approvals/flows/:id (readers view, editors edit). */
export default function ApprovalFlowEditorPage() {
  const params = useParams();
  const isNew = !params.id || params.id === 'new';
  const id = isNew ? null : Number(params.id);
  const { hasRole } = useAuth();
  const canEdit = hasRole(...APPROVAL_FLOW_EDITORS);
  const flow = useApprovalFlow(id && Number.isFinite(id) ? id : null);
  const teams = useTeams({ active: true });
  const remove = useDeleteApprovalFlow();
  const toast = useToast();
  const navigate = useNavigate();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const teamList = teams.data ?? [];

  if (!isNew && (!id || !Number.isFinite(id))) {
    return <EmptyState title="Approval flow not found" icon={<GitBranch size={20} />} />;
  }

  const data = flow.data ?? null;
  const title = isNew ? 'New approval flow' : data?.name ?? 'Approval flow';

  return (
    <>
      <PageHeader title={title} subtitle={isNew ? 'Define the ordered approval stages of a new flow' : data?.description ?? undefined} crumbs={[...CRUMBS, { label: title }]}>
        {data && (
          <span className="flex flex-wrap items-center gap-1">
            <span className="id-cell">{data.code}</span>
            <BooleanBadge value={data.active} />
            {data.systemFlow && <ToneBadge value="system" label={<><Lock size={10} aria-hidden="true" /> System flow</>} tone="champagne" />}
            <DemoBadge demo={data.demo} />
            {!canEdit && <ToneBadge value="read-only" label="Read-only" tone="muted" />}
          </span>
        )}
      </PageHeader>
      <div className="page-stack">
        {!isNew && flow.isLoading && <LoadingState variant="panel" rows={4} label="Loading approval flow…" />}
        {!isNew && flow.isError && <ErrorState error={flow.error} onRetry={() => void flow.refetch()} />}
        {isNew && !canEdit && <EmptyState title="Read-only access" description="Only approval-flow editors can create flows." icon={<Lock size={20} />} />}
        {isNew && canEdit && <FlowForm flow={null} teams={teamList} onDelete={() => undefined} />}
        {data && canEdit && <FlowForm flow={data} teams={teamList} onDelete={() => setConfirmDelete(true)} />}
        {data && !canEdit && <FlowSummary flow={data} teams={teamList} />}
      </div>
      <ConfirmDialog
        open={confirmDelete}
        onClose={() => {
          setConfirmDelete(false);
          remove.reset();
        }}
        title={data ? `Delete ${data.name}?` : 'Delete flow?'}
        message={data ? `The flow ${data.code} is removed. Approvals already requested through it keep their stages; workflows that request it by code will fail until they are updated.` : ''}
        confirmLabel="Delete flow"
        tone="danger"
        pending={remove.isPending}
        error={remove.isError ? errorMessage(remove.error) : null}
        onConfirm={() => {
          if (!data) return;
          remove.mutate(data.id, {
            onSuccess: () => {
              toast.success(`${data.name} deleted`);
              setConfirmDelete(false);
              navigate('/approvals/flows', { replace: true });
            },
          });
        }}
      />
    </>
  );
}
