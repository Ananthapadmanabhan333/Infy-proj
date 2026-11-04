import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useAddIncidentLink, useAssignIncident, useLinkParent, useTransitionIncident, useUpdateIncident } from '../../../api/incidents';
import { useArticles } from '../../../api/knowledge';
import { useServices } from '../../../api/services';
import { useTeam, useTeams } from '../../../api/teams';
import { useAssignableUsers, useDepartments } from '../../../api/users';
import { idOrNull, SelectField, TextareaField, TextField } from '../../../components/FormField';
import { IncidentPicker } from '../../../components/incidents/IncidentPicker';
import { Modal } from '../../../components/Modal';
import { useToast } from '../../../components/Toast';
import { useDebounce } from '../../../hooks/useDebounce';
import { applyFieldErrors, errorMessage } from '../../../lib/api';
import { CATEGORIES, LEVELS, LINK_TYPES, RESOLUTION_CODES } from '../../../lib/constants';
import { titleCase } from '../../../lib/format';
import {
  incidentUpdateSchema,
  resolveSchema,
  type IncidentUpdateValues,
  type ResolveValues,
} from '../../../lib/schemas';
import type { IncidentDetail, IncidentSummary, LinkType } from '../../../lib/types';

/** The API requires at least 10 characters of resolution notes. */
const resolveDialogSchema = resolveSchema.extend({
  resolutionNotes: z.string().trim().min(10, 'Describe how the incident was resolved (at least 10 characters)').max(5000),
});

interface DialogProps {
  incident: IncidentDetail;
  open: boolean;
  onClose: () => void;
}

function FormError({ error }: { error: unknown }) {
  if (!error) return null;
  return (
    <p className="field-error" role="alert">
      {errorMessage(error)}
    </p>
  );
}

// ---------------------------------------------------------------- Resolve
export function ResolveDialog({ incident, open, onClose, initialArticle }: DialogProps & { initialArticle?: { id: number; number: string; title: string } | null }) {
  const transition = useTransitionIncident(incident.id);
  const toast = useToast();
  const [articleSearch, setArticleSearch] = useState('');
  const debounced = useDebounce(articleSearch.trim(), 300);
  const articles = useArticles({ q: debounced || undefined, status: 'PUBLISHED', size: 10 }, open);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<ResolveValues>({
    resolver: zodResolver(resolveDialogSchema),
    defaultValues: { resolutionCode: '', resolutionNotes: '', rootCause: '', resolutionArticleId: '' },
  });

  useEffect(() => {
    if (open) {
      reset({
        resolutionCode: '',
        resolutionNotes: '',
        rootCause: incident.rootCause ?? '',
        resolutionArticleId: initialArticle ? String(initialArticle.id) : incident.resolutionArticle ? String(incident.resolutionArticle.id) : '',
      });
      transition.reset();
    }
  }, [open, initialArticle?.id]);

  const options = useMemo(() => {
    const list = (articles.data?.content ?? []).map((article) => ({ id: article.id, label: `${article.number} · ${article.title}` }));
    const pinned = initialArticle ?? incident.resolutionArticle;
    if (pinned && !list.some((entry) => entry.id === pinned.id)) list.unshift({ id: pinned.id, label: `${pinned.number} · ${pinned.title}` });
    return list;
  }, [articles.data, initialArticle, incident.resolutionArticle]);

  const onSubmit = handleSubmit((values) =>
    transition.mutate(
      {
        status: 'RESOLVED',
        resolutionCode: values.resolutionCode || undefined,
        resolutionNotes: values.resolutionNotes.trim(),
        rootCause: values.rootCause.trim() || undefined,
        resolutionArticleId: idOrNull(values.resolutionArticleId),
      },
      {
        onSuccess: () => {
          toast.success(`${incident.number} resolved`);
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  return (
    <Modal open={open} onClose={onClose} title={`Resolve ${incident.number}`} eyebrow="RESOLUTION" size="lg" dismissible={!transition.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <SelectField label="Resolution code" required error={errors.resolutionCode?.message} {...register('resolutionCode')}>
          <option value="">Choose a resolution code</option>
          {RESOLUTION_CODES.map((code) => (
            <option key={code} value={code}>
              {titleCase(code)}
            </option>
          ))}
        </SelectField>
        <TextareaField
          label="Resolution notes"
          required
          placeholder="What fixed the issue? These notes are visible to the requester."
          error={errors.resolutionNotes?.message}
          {...register('resolutionNotes')}
        />
        <TextareaField label="Root cause" placeholder="Optional — underlying cause, if known" error={errors.rootCause?.message} {...register('rootCause')} />
        <div className="form-row">
          <TextField
            label="Search articles"
            placeholder="Filter published articles"
            value={articleSearch}
            onChange={(event) => setArticleSearch(event.target.value)}
          />
          <SelectField
            label="Knowledge article"
            hint={articles.isFetching ? 'Searching…' : 'Optional — cite the article that documents the fix.'}
            error={errors.resolutionArticleId?.message}
            {...register('resolutionArticleId')}
          >
            <option value="">No article</option>
            {options.map((option) => (
              <option key={option.id} value={option.id}>
                {option.label}
              </option>
            ))}
          </SelectField>
        </div>
        <FormError error={transition.isError && !Object.keys(errors).length ? transition.error : null} />
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose} disabled={transition.isPending}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={transition.isPending}>
            {transition.isPending ? 'Resolving…' : 'Confirm resolution'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- Assign
/** R3: a team may be chosen; the department then follows the team and the assignee must be a member. */
const teamAssignSchema = z
  .object({ teamId: z.string(), departmentId: z.string(), assigneeId: z.string() })
  .refine((v) => v.teamId !== '' || v.departmentId !== '' || v.assigneeId !== '', { path: ['departmentId'], message: 'Choose a team, a department or an assignee' });
type TeamAssignValues = z.infer<typeof teamAssignSchema>;

function assignDefaults(incident: IncidentDetail): TeamAssignValues {
  return {
    teamId: incident.teamId ? String(incident.teamId) : '',
    departmentId: incident.departmentId ? String(incident.departmentId) : '',
    assigneeId: incident.assigneeId ? String(incident.assigneeId) : '',
  };
}

export function AssignDialog({ incident, open, onClose }: DialogProps) {
  const assign = useAssignIncident(incident.id);
  const toast = useToast();
  const departments = useDepartments(open);
  const teams = useTeams({ active: true }, open);
  const {
    register,
    handleSubmit,
    control,
    setValue,
    reset,
    formState: { errors },
  } = useForm<TeamAssignValues>({ resolver: zodResolver(teamAssignSchema), defaultValues: assignDefaults(incident) });
  const departmentId = useWatch({ control, name: 'departmentId' });
  const teamId = useWatch({ control, name: 'teamId' });
  const selectedTeam = teams.data?.find((team) => String(team.id) === teamId);
  const team = useTeam(idOrNull(teamId), open && Boolean(teamId));
  const assignees = useAssignableUsers(idOrNull(departmentId), open && !teamId);
  const members = (team.data?.members ?? []).filter((member) => member.active);

  useEffect(() => {
    if (open) {
      reset(assignDefaults(incident));
      assign.reset();
    }
  }, [open]);

  // Re-apply the stored values once the options have loaded so an edit never silently drops them.
  useEffect(() => {
    if (teams.data && open) setValue('teamId', incident.teamId ? String(incident.teamId) : '');
  }, [teams.data]);

  const onSubmit = handleSubmit((values) => {
    const body = values.teamId
      ? { teamId: idOrNull(values.teamId), assigneeId: idOrNull(values.assigneeId) }
      : { departmentId: idOrNull(values.departmentId), assigneeId: idOrNull(values.assigneeId) };
    assign.mutate(body, {
      onSuccess: (data) => {
        toast.success(data?.assigneeName ? `Assigned to ${data.assigneeName}` : data?.teamName ? `Queued for ${data.teamName}` : 'Assignment updated');
        onClose();
      },
    });
  });

  return (
    <Modal open={open} onClose={onClose} title={`Assign ${incident.number}`} eyebrow="ASSIGNMENT" dismissible={!assign.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <SelectField
          label="Team"
          hint={selectedTeam ? `Department: ${selectedTeam.departmentName ?? '—'}. Without an assignee the incident waits in the team queue.` : 'Optional. The team decides the department.'}
          {...register('teamId', {
            onChange: (event) => {
              setValue('assigneeId', '');
              const next = teams.data?.find((entry) => String(entry.id) === event.target.value);
              if (next) setValue('departmentId', String(next.departmentId));
            },
          })}
        >
          <option value="">{teams.isLoading ? 'Loading teams…' : 'No team (department only)'}</option>
          {teams.data?.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.name}
              {entry.departmentName ? ` · ${entry.departmentName}` : ''}
            </option>
          ))}
        </SelectField>
        <SelectField
          label="Department"
          error={errors.departmentId?.message}
          disabled={Boolean(teamId)}
          {...register('departmentId', { onChange: () => setValue('assigneeId', '') })}
        >
          <option value="">Keep current department</option>
          {departments.data?.map((department) => (
            <option key={department.id} value={department.id}>
              {department.name}
            </option>
          ))}
        </SelectField>
        {teamId ? (
          <SelectField label="Assignee" hint="Active members of the selected team, with their open workload." {...register('assigneeId')}>
            <option value="">{team.isLoading ? 'Loading members…' : 'Team queue (no individual)'}</option>
            {members.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.fullName}
                {member.memberRole === 'LEAD' ? ' (lead)' : ''} — {member.openAssignments} open
              </option>
            ))}
          </SelectField>
        ) : (
          <SelectField label="Assignee" hint="Agents and managers in the selected department, with their open workload." {...register('assigneeId')}>
            <option value="">Department queue (no individual)</option>
            {assignees.data?.map((user) => (
              <option key={user.id} value={user.id}>
                {user.fullName} — {user.openAssignments} open
              </option>
            ))}
          </SelectField>
        )}
        <FormError error={assign.error} />
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose} disabled={assign.isPending}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={assign.isPending}>
            {assign.isPending ? 'Saving…' : 'Save assignment'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- Edit
export function EditIncidentDialog({ incident, open, onClose }: DialogProps) {
  const update = useUpdateIncident(incident.id);
  const services = useServices(open);
  const toast = useToast();
  const defaults = (): IncidentUpdateValues => ({
    title: incident.title,
    description: incident.description ?? '',
    category: incident.category,
    impact: incident.impact,
    urgency: incident.urgency,
    serviceId: incident.serviceId ? String(incident.serviceId) : '',
    affectedUsers: incident.affectedUsers,
  });
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<IncidentUpdateValues>({ resolver: zodResolver(incidentUpdateSchema), defaultValues: defaults() });

  useEffect(() => {
    if (open) {
      reset(defaults());
      update.reset();
    }
  }, [open]);

  const onSubmit = handleSubmit((values) =>
    update.mutate(
      {
        title: values.title.trim(),
        description: values.description.trim() || null,
        category: values.category,
        impact: values.impact,
        urgency: values.urgency,
        serviceId: idOrNull(values.serviceId),
        affectedUsers: values.affectedUsers,
      },
      {
        onSuccess: (data) => {
          const changed = data && data.priority !== incident.priority;
          toast.success(
            changed
              ? `Saved. Priority recalculated: ${titleCase(incident.priority)} → ${titleCase(data.priority)}`
              : `Saved. Priority remains ${titleCase(data?.priority ?? incident.priority)}`,
          );
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  return (
    <Modal open={open} onClose={onClose} title={`Edit ${incident.number}`} eyebrow="INCIDENT DETAILS" size="lg" dismissible={!update.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <TextField label="Title" required error={errors.title?.message} {...register('title')} />
        <TextareaField label="Description" error={errors.description?.message} {...register('description')} />
        <div className="form-row">
          <SelectField label="Category" error={errors.category?.message} {...register('category')}>
            {CATEGORIES.map((category) => (
              <option key={category} value={category}>
                {titleCase(category)}
              </option>
            ))}
          </SelectField>
          <SelectField label="Service" error={errors.serviceId?.message} {...register('serviceId')}>
            <option value="">No service</option>
            {services.data?.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </SelectField>
        </div>
        <div className="form-row three">
          <SelectField label="Impact" error={errors.impact?.message} {...register('impact')}>
            {LEVELS.map((level) => (
              <option key={level} value={level}>
                {titleCase(level)}
              </option>
            ))}
          </SelectField>
          <SelectField label="Urgency" error={errors.urgency?.message} {...register('urgency')}>
            {LEVELS.map((level) => (
              <option key={level} value={level}>
                {titleCase(level)}
              </option>
            ))}
          </SelectField>
          <TextField label="Affected users" type="number" min={1} error={errors.affectedUsers?.message} {...register('affectedUsers', { valueAsNumber: true })} />
        </div>
        <p className="field-hint">Priority is recalculated from impact × urgency when you save; the SLA targets follow the new priority.</p>
        <FormError error={update.isError && !Object.keys(errors).length ? update.error : null} />
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose} disabled={update.isPending}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={update.isPending}>
            {update.isPending ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- Parent link
export function LinkParentDialog({ incident, open, onClose }: DialogProps) {
  const link = useLinkParent(incident.id);
  const toast = useToast();
  const [parent, setParent] = useState<IncidentSummary | null>(null);
  useEffect(() => {
    if (open) {
      setParent(null);
      link.reset();
    }
  }, [open]);

  return (
    <Modal open={open} onClose={onClose} title={`Link ${incident.number} to a parent`} eyebrow="DUPLICATES & PARENTS" dismissible={!link.isPending}>
      <p className="text-[11px] leading-relaxed text-muted">
        The parent incident tracks the shared outage; this incident becomes a child and follows the parent's resolution.
      </p>
      <IncidentPicker label="Parent incident" value={parent} onChange={setParent} excludeIds={[incident.id]} />
      <FormError error={link.error} />
      <div className="modal-foot">
        <button type="button" className="subtle-btn" onClick={onClose} disabled={link.isPending}>
          Cancel
        </button>
        <button
          type="button"
          className="gold-btn"
          disabled={!parent || link.isPending}
          onClick={() =>
            parent &&
            link.mutate(parent.id, {
              onSuccess: () => {
                toast.success(`${incident.number} linked under ${parent.number}`);
                onClose();
              },
            })
          }
        >
          {link.isPending ? 'Linking…' : 'Link as child'}
        </button>
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------- Related link
export function AddLinkDialog({ incident, open, onClose }: DialogProps) {
  const add = useAddIncidentLink(incident.id);
  const toast = useToast();
  const [target, setTarget] = useState<IncidentSummary | null>(null);
  const [linkType, setLinkType] = useState<LinkType>('RELATED_TO');
  useEffect(() => {
    if (open) {
      setTarget(null);
      setLinkType('RELATED_TO');
      add.reset();
    }
  }, [open]);

  return (
    <Modal open={open} onClose={onClose} title="Add related incident" eyebrow="RELATIONSHIPS" dismissible={!add.isPending}>
      <IncidentPicker label="Related incident" value={target} onChange={setTarget} excludeIds={[incident.id]} />
      <SelectField label="Relationship" value={linkType} onChange={(event) => setLinkType(event.target.value as LinkType)}>
        {LINK_TYPES.map((type) => (
          <option key={type} value={type}>
            {titleCase(type)}
          </option>
        ))}
      </SelectField>
      <FormError error={add.error} />
      <div className="modal-foot">
        <button type="button" className="subtle-btn" onClick={onClose} disabled={add.isPending}>
          Cancel
        </button>
        <button
          type="button"
          className="gold-btn"
          disabled={!target || add.isPending}
          onClick={() =>
            target &&
            add.mutate(
              { targetId: target.id, linkType },
              {
                onSuccess: () => {
                  toast.success(`Linked ${target.number}`);
                  onClose();
                },
              },
            )
          }
        >
          {add.isPending ? 'Saving…' : 'Save link'}
        </button>
      </div>
    </Modal>
  );
}
