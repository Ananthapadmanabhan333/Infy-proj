import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { useAlertCommand, useCreateAlert, useConvertAlert, useLinkAlertIncident } from '../../api/alerts';
import { useServices } from '../../api/services';
import { useTeams } from '../../api/teams';
import { CiSinglePicker } from '../../components/cmdb/CiPicker';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { IncidentPicker } from '../../components/incidents/IncidentPicker';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { LEVELS } from '../../lib/constants';
import { titleCase } from '../../lib/format';
import { ALERT_SEVERITIES } from '../../lib/ops';
import type { AlertDetail, AlertSeverity, AlertSummary, CiRef } from '../../lib/opsTypes';
import type { IncidentSummary, Level } from '../../lib/types';

/** Suppression durations offered (minutes; null = until resolved or unsuppressed). API range 5–10080. */
export const SUPPRESS_DURATIONS: { value: string; label: string; minutes: number | null }[] = [
  { value: '30', label: '30 minutes', minutes: 30 },
  { value: '60', label: '1 hour', minutes: 60 },
  { value: '240', label: '4 hours', minutes: 240 },
  { value: '1440', label: '1 day', minutes: 1440 },
  { value: '10080', label: '7 days', minutes: 10080 },
  { value: 'until', label: 'Until resolved or unsuppressed', minutes: null },
];

function FormError({ error }: { error: unknown }) {
  return (
    <p className="field-error" role="alert">
      {errorMessage(error)}
    </p>
  );
}

/** Reason + duration for suppressing one or more alerts. */
export function SuppressFields({ reason, onReason, duration, onDuration }: { reason: string; onReason: (value: string) => void; duration: string; onDuration: (value: string) => void }) {
  return (
    <>
      <SelectField label="Suppress for" value={duration} onChange={(event) => onDuration(event.target.value)}>
        {SUPPRESS_DURATIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </SelectField>
      <TextareaField label="Reason" required rows={3} maxLength={1000} value={reason} onChange={(event) => onReason(event.target.value)} hint="Recorded in the alert history and the audit log." />
    </>
  );
}

export function durationMinutes(value: string): number | null {
  return SUPPRESS_DURATIONS.find((option) => option.value === value)?.minutes ?? null;
}

export function SuppressDialog({ alert, onClose }: { alert: AlertSummary; onClose: () => void }) {
  const command = useAlertCommand(alert.id);
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [duration, setDuration] = useState('240');
  return (
    <Modal
      open
      onClose={onClose}
      title={`Suppress ${alert.number}`}
      eyebrow="ALERT"
      description="A suppressed alert notifies nobody and does not escalate. Repeats still count."
      dismissible={!command.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={command.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={reason.trim().length < 3 || command.isPending}
            onClick={() =>
              command.mutate(
                { action: 'suppress', reason: reason.trim(), minutes: durationMinutes(duration) },
                {
                  onSuccess: () => {
                    toast.success(`${alert.number} suppressed`);
                    onClose();
                  },
                },
              )
            }
          >
            {command.isPending ? 'Suppressing…' : 'Suppress'}
          </button>
        </>
      }
    >
      <SuppressFields reason={reason} onReason={setReason} duration={duration} onDuration={setDuration} />
      {command.isError && <FormError error={command.error} />}
    </Modal>
  );
}

export function AssignTeamDialog({ alert, onClose }: { alert: AlertSummary; onClose: () => void }) {
  const command = useAlertCommand(alert.id);
  const teams = useTeams({ active: true });
  const toast = useToast();
  const [teamId, setTeamId] = useState(alert.teamId ? String(alert.teamId) : '');
  const [note, setNote] = useState('');
  return (
    <Modal
      open
      onClose={onClose}
      title={`Assign ${alert.number} to a team`}
      eyebrow="ALERT"
      description="The team's members are notified."
      dismissible={!command.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={command.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={!teamId || command.isPending}
            onClick={() =>
              command.mutate(
                { action: 'assign', teamId: Number(teamId), note: note.trim() || null },
                {
                  onSuccess: (detail) => {
                    toast.success(`${alert.number} assigned to ${detail.alert.teamName ?? 'the team'}`);
                    onClose();
                  },
                },
              )
            }
          >
            {command.isPending ? 'Assigning…' : 'Assign'}
          </button>
        </>
      }
    >
      <SelectField label="Team" required value={teamId} onChange={(event) => setTeamId(event.target.value)} disabled={teams.isLoading || teams.isError}>
        <option value="">{teams.isLoading ? 'Loading teams…' : 'Choose a team'}</option>
        {(teams.data ?? []).map((team) => (
          <option key={team.id} value={team.id}>
            {team.name}
          </option>
        ))}
      </SelectField>
      <TextField label="Note (optional)" maxLength={1000} value={note} onChange={(event) => setNote(event.target.value)} />
      {teams.isError && <FormError error={teams.error} />}
      {command.isError && <FormError error={command.error} />}
    </Modal>
  );
}

export function ConvertDialog({ detail, onClose, onConverted }: { detail: AlertDetail; onClose: () => void; onConverted: (incidentId: number) => void }) {
  const convert = useConvertAlert(detail.alert.id);
  const toast = useToast();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [impact, setImpact] = useState('');
  const [urgency, setUrgency] = useState('');
  const errors = convert.isError ? toApiError(convert.error).fieldErrors ?? [] : [];
  const titleInvalid = title.trim().length > 0 && title.trim().length < 5;
  return (
    <Modal
      open
      onClose={onClose}
      title={`Convert ${detail.alert.number} to an incident`}
      eyebrow="ALERT → INCIDENT"
      description="The incident goes through the normal intake (classification, priority, duplicate detection, assignment, SLA). Everything is optional — empty fields use the alert's own values."
      size="lg"
      dismissible={!convert.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={convert.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={titleInvalid || convert.isPending}
            onClick={() =>
              convert.mutate(
                { title: title.trim() || null, description: description.trim() || null, impact: (impact || null) as Level | null, urgency: (urgency || null) as Level | null },
                {
                  onSuccess: (result) => {
                    const extra = result.duplicate ? ` (possible duplicate${result.parentNumber ? ` of ${result.parentNumber}` : ''})` : '';
                    toast.success(`${result.incidentNumber} created${extra}${result.linkedAlerts > 1 ? ` · ${result.linkedAlerts} alerts linked` : ''}`, {
                      action: { label: `Open ${result.incidentNumber}`, to: `/incidents/${result.incidentId}` },
                    });
                    onConverted(result.incidentId);
                    onClose();
                  },
                },
              )
            }
          >
            {convert.isPending ? 'Creating incident…' : 'Create incident'}
          </button>
        </>
      }
    >
      <TextField
        label="Incident title (optional)"
        maxLength={200}
        placeholder={`[${detail.alert.severity}] ${detail.alert.title}`}
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        error={titleInvalid ? 'At least 5 characters' : errors.find((error) => error.field === 'title')?.message}
      />
      <TextareaField label="Description (optional)" rows={4} maxLength={5000} value={description} onChange={(event) => setDescription(event.target.value)} hint="Defaults to the alert number, CI, labels and source link." />
      <div className="form-row">
        <SelectField label="Impact" value={impact} onChange={(event) => setImpact(event.target.value)} hint="Default from the severity">
          <option value="">From severity</option>
          {LEVELS.map((level) => (
            <option key={level} value={level}>
              {titleCase(level)}
            </option>
          ))}
        </SelectField>
        <SelectField label="Urgency" value={urgency} onChange={(event) => setUrgency(event.target.value)} hint="Default from the severity">
          <option value="">From severity</option>
          {LEVELS.map((level) => (
            <option key={level} value={level}>
              {titleCase(level)}
            </option>
          ))}
        </SelectField>
      </div>
      {convert.isError && !errors.length && <FormError error={convert.error} />}
    </Modal>
  );
}

export function LinkIncidentDialog({ alert, onClose }: { alert: AlertSummary; onClose: () => void }) {
  const link = useLinkAlertIncident(alert.id);
  const toast = useToast();
  const [incident, setIncident] = useState<IncidentSummary | null>(null);
  return (
    <Modal
      open
      onClose={onClose}
      title={`Link ${alert.number} to an incident`}
      eyebrow="ALERT"
      description="An internal note is added to the incident."
      dismissible={!link.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={link.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={!incident || link.isPending}
            onClick={() =>
              incident &&
              link.mutate(incident.id, {
                onSuccess: (result) => {
                  toast.success(`${alert.number} linked to ${result.incidentNumber}`);
                  onClose();
                },
              })
            }
          >
            {link.isPending ? 'Linking…' : 'Link incident'}
          </button>
        </>
      }
    >
      <IncidentPicker label="Incident" value={incident} onChange={setIncident} />
      {link.isError && <FormError error={link.error} />}
    </Modal>
  );
}

interface PairRow {
  key: string;
  value: string;
}

function pairsToMap(rows: PairRow[]): Record<string, string> | null {
  const map: Record<string, string> = {};
  rows.forEach((row) => {
    if (row.key.trim()) map[row.key.trim()] = row.value.trim();
  });
  return Object.keys(map).length ? map : null;
}

/** Manual alert (`POST /alerts`, source MANUAL): goes through suppression, routing and conversion like any other. */
export function CreateAlertModal({ onClose, onCreated }: { onClose: () => void; onCreated: (detail: AlertDetail) => void }) {
  const create = useCreateAlert();
  const services = useServices();
  const toast = useToast();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [severity, setSeverity] = useState<AlertSeverity>('MAJOR');
  const [serviceId, setServiceId] = useState('');
  const [ci, setCi] = useState<CiRef | null>(null);
  const [externalUrl, setExternalUrl] = useState('');
  const [labels, setLabels] = useState<PairRow[]>([]);
  const errors = create.isError ? toApiError(create.error).fieldErrors ?? [] : [];
  const fieldError = (field: string) => errors.find((error) => error.field === field || error.field.startsWith(`${field}.`))?.message;
  const urlInvalid = externalUrl.trim() !== '' && !/^https?:\/\/.+/i.test(externalUrl.trim());

  const submit = () =>
    create.mutate(
      {
        title: title.trim(),
        description: description.trim() || null,
        severity,
        serviceId: serviceId ? Number(serviceId) : null,
        ciId: ci?.id ?? null,
        externalUrl: externalUrl.trim() || null,
        labels: pairsToMap(labels),
      },
      {
        onSuccess: (detail) => {
          toast.success(`${detail.alert.number} ${detail.alert.occurrenceCount > 1 ? 'fired again' : 'raised'}${detail.alert.status === 'SUPPRESSED' ? ' (suppressed by a rule)' : ''}`);
          onCreated(detail);
          onClose();
        },
      },
    );

  return (
    <Modal
      open
      onClose={onClose}
      title="Raise an alert"
      eyebrow="MANUAL ALERT"
      description="The same title, service and CI again counts as a repeat of the open alert."
      size="lg"
      dismissible={!create.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={create.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={title.trim().length < 3 || urlInvalid || create.isPending}>
            {create.isPending ? 'Raising…' : 'Raise alert'}
          </button>
        </>
      }
    >
      <TextField label="Title" required maxLength={300} value={title} onChange={(event) => setTitle(event.target.value)} error={fieldError('title')} />
      <TextareaField label="Description" rows={3} maxLength={10000} value={description} onChange={(event) => setDescription(event.target.value)} error={fieldError('description')} />
      <div className="form-row">
        <SelectField label="Severity" value={severity} onChange={(event) => setSeverity(event.target.value as AlertSeverity)}>
          {ALERT_SEVERITIES.map((value) => (
            <option key={value} value={value}>
              {titleCase(value)}
            </option>
          ))}
        </SelectField>
        <SelectField label="Business service" value={serviceId} onChange={(event) => setServiceId(event.target.value)} error={fieldError('serviceId')} disabled={services.isError}>
          <option value="">Derive from the CI</option>
          {(services.data ?? []).map((service) => (
            <option key={service.id} value={service.id}>
              {service.name} ({service.code})
            </option>
          ))}
        </SelectField>
      </div>
      <CiSinglePicker label="Configuration item (optional)" value={ci} onChange={setCi} error={fieldError('ciId')} />
      <TextField label="Link (optional)" placeholder="https://…" maxLength={500} value={externalUrl} onChange={(event) => setExternalUrl(event.target.value)} error={urlInvalid ? 'Use an http(s) link' : fieldError('externalUrl')} />
      <fieldset className="m-0 mb-3 min-w-0 border-0 p-0">
        <legend className="mb-1 text-[11px] font-semibold">Labels (optional)</legend>
        <div className="grid gap-2">
          {labels.map((row, index) => (
            <div key={index} className="grid grid-cols-[1fr_1fr_auto] gap-2">
              <input className="control sm" aria-label={`Label ${index + 1} name`} placeholder="name" value={row.key} onChange={(event) => setLabels((current) => current.map((other, i) => (i === index ? { ...other, key: event.target.value } : other)))} />
              <input className="control sm" aria-label={`Label ${index + 1} value`} placeholder="value" value={row.value} onChange={(event) => setLabels((current) => current.map((other, i) => (i === index ? { ...other, value: event.target.value } : other)))} />
              <button type="button" className="icon-btn sm self-center" aria-label={`Remove label ${index + 1}`} onClick={() => setLabels((current) => current.filter((_, i) => i !== index))}>
                <Trash2 size={13} />
              </button>
            </div>
          ))}
        </div>
        {labels.length < 50 && (
          <button type="button" className="link-btn mt-2 text-[11px]" onClick={() => setLabels((current) => [...current, { key: '', value: '' }])}>
            <Plus size={12} aria-hidden="true" /> Add label
          </button>
        )}
      </fieldset>
      {create.isError && !errors.length && <FormError error={create.error} />}
    </Modal>
  );
}
