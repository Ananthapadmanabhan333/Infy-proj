import { useState } from 'react';
import { X } from 'lucide-react';
import { useSaveProblem } from '../../api/problems';
import { useTeams } from '../../api/teams';
import { useAssignableUsers } from '../../api/users';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { unlinkedCiNames } from '../../components/cmdb/AffectedCis';
import { CiMultiPicker } from '../../components/cmdb/CiPicker';
import { IncidentPicker } from '../../components/incidents/IncidentPicker';
import { parseList, ServiceMultiPicker } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { CATEGORIES, PRIORITIES, PRIORITY_LABELS } from '../../lib/constants';
import { titleCase } from '../../lib/format';
import type { ProblemDetail } from '../../lib/itsmTypes';
import type { CiRef } from '../../lib/opsTypes';
import type { Category, IncidentSummary, Priority } from '../../lib/types';

/** Create or edit a problem (problem editors). Incidents can be linked when creating; later via the detail page. */
export function ProblemEditor({ problem, onClose, onSaved }: { problem: ProblemDetail | null; onClose: () => void; onSaved?: (problem: ProblemDetail) => void }) {
  const save = useSaveProblem();
  const toast = useToast();
  const staff = useAssignableUsers(null);
  const teams = useTeams({ active: true });
  const [title, setTitle] = useState(problem?.title ?? '');
  const [description, setDescription] = useState(problem?.description ?? '');
  const [priority, setPriority] = useState<Priority>(problem?.priority ?? 'MEDIUM');
  const [category, setCategory] = useState<string>(problem?.category ?? '');
  const [ownerId, setOwnerId] = useState(problem?.ownerId ? String(problem.ownerId) : '');
  const [teamId, setTeamId] = useState(problem?.teamId ? String(problem.teamId) : '');
  const [serviceIds, setServiceIds] = useState<number[]>(problem?.services.map((service) => service.id) ?? []);
  const [linkedCis, setLinkedCis] = useState<CiRef[]>(problem?.cis ?? []);
  // Legacy free-text names that are not CMDB records stay editable as text.
  const [cis, setCis] = useState(unlinkedCiNames(problem?.affectedCis, problem?.cis).join(', '));
  const [incidents, setIncidents] = useState<IncidentSummary[]>([]);
  const [titleError, setTitleError] = useState<string | undefined>();

  const errors = save.isError ? toApiError(save.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field || error.field.startsWith(`${field}[`))?.message;

  const submit = () => {
    if (title.trim().length < 5) {
      setTitleError('Give a title of at least 5 characters');
      return;
    }
    setTitleError(undefined);
    save.mutate(
      {
        id: problem?.id,
        body: {
          title: title.trim(),
          description: description.trim() || null,
          priority,
          category: (category || null) as Category | null,
          ownerId: ownerId ? Number(ownerId) : null,
          teamId: teamId ? Number(teamId) : null,
          serviceIds,
          affectedCis: parseList(cis),
          ciIds: linkedCis.map((ci) => ci.id),
          ...(problem ? {} : { incidentIds: incidents.map((incident) => incident.id) }),
        },
      },
      {
        onSuccess: (saved) => {
          toast.success(problem ? `${saved.number} updated` : `${saved.number} created`);
          onSaved?.(saved);
          onClose();
        },
      },
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={problem ? `Edit ${problem.number}` : 'New problem'}
      eyebrow="PROBLEM MANAGEMENT"
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={save.isPending}>
            {save.isPending ? 'Saving…' : problem ? 'Save problem' : 'Create problem'}
          </button>
        </>
      }
    >
      <TextField label="Title" required maxLength={200} value={title} onChange={(event) => setTitle(event.target.value)} error={titleError ?? fieldError('title')} />
      <TextareaField label="Description" rows={4} maxLength={10000} value={description} onChange={(event) => setDescription(event.target.value)} error={fieldError('description')} />
      <div className="form-row three">
        <SelectField label="Priority" value={priority} onChange={(event) => setPriority(event.target.value as Priority)}>
          {PRIORITIES.map((value) => (
            <option key={value} value={value}>
              {PRIORITY_LABELS[value]}
            </option>
          ))}
        </SelectField>
        <SelectField label="Category" value={category} onChange={(event) => setCategory(event.target.value)}>
          <option value="">Not set</option>
          {CATEGORIES.map((value) => (
            <option key={value} value={value}>
              {titleCase(value)}
            </option>
          ))}
        </SelectField>
        <SelectField label="Owner" value={ownerId} onChange={(event) => setOwnerId(event.target.value)} error={fieldError('ownerId')} disabled={staff.isError}>
          <option value="">Unowned</option>
          {(staff.data ?? []).map((person) => (
            <option key={person.id} value={person.id}>
              {person.fullName}
            </option>
          ))}
        </SelectField>
      </div>
      <div className="form-row">
        <SelectField label="Team" value={teamId} onChange={(event) => setTeamId(event.target.value)} error={fieldError('teamId')} disabled={teams.isError}>
          <option value="">No team</option>
          {(teams.data ?? []).map((team) => (
            <option key={team.id} value={team.id}>
              {team.name}
            </option>
          ))}
        </SelectField>
        <TextField
          label="Other affected items"
          placeholder="mx-01, vendor gateway"
          hint="Comma-separated names that are not in the CMDB (kept as text)."
          value={cis}
          onChange={(event) => setCis(event.target.value)}
          error={fieldError('affectedCis')}
        />
      </div>
      <CiMultiPicker label="Configuration items" value={linkedCis} onChange={setLinkedCis} error={fieldError('ciIds')} hint="Linked CMDB records (up to 30); their names are added to the problem." />
      <ServiceMultiPicker value={serviceIds} onChange={setServiceIds} error={fieldError('serviceIds')} />
      {!problem && (
        <>
          <IncidentPicker label="Link incidents (optional)" value={null} excludeIds={incidents.map((incident) => incident.id)} onChange={(incident) => incident && setIncidents((current) => [...current, incident])} />
          {incidents.length > 0 && (
            <div className="tag-row mb-3" aria-label="Incidents to link">
              {incidents.map((incident) => (
                <span key={incident.id} className="chip">
                  {incident.number}
                  <button type="button" className="icon-btn sm !h-4 !w-4" aria-label={`Remove ${incident.number}`} onClick={() => setIncidents((current) => current.filter((other) => other.id !== incident.id))}>
                    <X size={10} />
                  </button>
                </span>
              ))}
            </div>
          )}
        </>
      )}
      {save.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}
