import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { useAddHandover, useAddUnavailability, useCreateOverride, useSavePolicy } from '../../api/oncall';
import { useTeams } from '../../api/teams';
import { useAssignableUsers } from '../../api/users';
import { CheckboxField, SelectField, TextareaField, TextField } from '../../components/FormField';
import { Modal } from '../../components/Modal';
import '../../components/ops/ops.css';
import { zonedTimeToUtc } from '../../components/oncall/shiftLayout';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { ALERT_SEVERITIES } from '../../lib/ops';
import { ESCALATION_TARGET_LABELS, ESCALATION_TARGETS } from '../../lib/response';
import type { AlertSeverityName, EscalationTarget, OnCallLayer, PolicyDto, ScheduleDetail } from '../../lib/responseTypes';

/** "YYYY-MM-DDTHH:mm" entered as wall-clock time of `timeZone` → ISO instant. */
export function zonedInputToIso(value: string, timeZone: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value);
  if (!match) return null;
  const [, y, m, d, hh, mm] = match.map(Number) as unknown as number[];
  return new Date(zonedTimeToUtc(y, m, d, timeZone, hh, mm)).toISOString();
}

function fieldErrorOf(error: unknown) {
  const errors = error ? toApiError(error).fieldErrors ?? [] : [];
  return { errors, get: (field: string) => errors.find((entry) => entry.field === field || entry.field.startsWith(`${field}.`) || entry.field.startsWith(`${field}[`))?.message };
}

function FormError({ error, shown }: { error: unknown; shown: boolean }) {
  if (!error || shown) return null;
  return (
    <p className="field-error" role="alert">
      {errorMessage(error)}
    </p>
  );
}

/**
 * Override (on-call editors, `offer: false` → ACTIVE) or shift offer (`offer: true` → OFFERED to one person or to every
 * participant). Participants who are not editors can only offer their own shift. Times are entered in the schedule zone.
 */
export function OverrideDialog({ detail, editor, onClose }: { detail: ScheduleDetail; editor: boolean; onClose: () => void }) {
  const { user } = useAuth();
  const timeZone = detail.schedule.timeZone;
  const create = useCreateOverride(detail.schedule.id);
  const staff = useAssignableUsers(null, editor);
  const toast = useToast();
  const [offer, setOffer] = useState(!editor);
  const [layer, setLayer] = useState<OnCallLayer>('PRIMARY');
  const [userId, setUserId] = useState('');
  const [originalUserId, setOriginalUserId] = useState('');
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [reason, setReason] = useState('');
  const participants = [...detail.primary, ...detail.secondary].filter((participant, index, all) => all.findIndex((other) => other.userId === participant.userId) === index);
  const candidates = offer || !editor ? participants.map((participant) => ({ id: participant.userId, fullName: participant.userName })) : (staff.data ?? []).map((person) => ({ id: person.id, fullName: person.fullName }));
  const { errors, get } = fieldErrorOf(create.error);
  const startIso = zonedInputToIso(startsAt, timeZone);
  const endIso = zonedInputToIso(endsAt, timeZone);
  const blocked = create.isPending || !startIso || !endIso || (!offer && !userId);

  const submit = () =>
    create.mutate(
      {
        layer,
        offer,
        userId: userId ? Number(userId) : null,
        originalUserId: editor ? (originalUserId ? Number(originalUserId) : null) : offer ? user?.id ?? null : null,
        startsAt: startIso ?? '',
        endsAt: endIso ?? '',
        reason: reason.trim() || null,
      },
      {
        onSuccess: (created) => {
          toast.success(created.status === 'OFFERED' ? `Shift offered${created.userName ? ` to ${created.userName}` : ' to the rotation'}` : `Override for ${created.userName ?? 'the selected person'} is active`);
          onClose();
        },
      },
    );

  return (
    <Modal
      open
      onClose={onClose}
      title={offer ? 'Offer a shift' : 'Add an override'}
      eyebrow={detail.schedule.name.toUpperCase()}
      description={`Times are in ${timeZone}.`}
      dismissible={!create.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={create.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={blocked}>
            {create.isPending ? 'Saving…' : offer ? 'Offer shift' : 'Add override'}
          </button>
        </>
      }
    >
      {editor && (
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Override kind">
          <button type="button" role="radio" aria-checked={!offer} className="chip-toggle chip" aria-pressed={!offer} onClick={() => setOffer(false)}>
            Direct override
          </button>
          <button type="button" role="radio" aria-checked={offer} className="chip-toggle chip" aria-pressed={offer} onClick={() => setOffer(true)}>
            Offer to someone to take
          </button>
        </div>
      )}
      {!editor && <p className="m-0 text-[11px] text-muted">You can offer your own shift: the person you choose (or anyone on the rotation) can take it from their offers inbox.</p>}
      <div className="form-row">
        <SelectField label="Layer" value={layer} onChange={(event) => setLayer(event.target.value as OnCallLayer)} error={get('layer')}>
          <option value="PRIMARY">Primary</option>
          <option value="SECONDARY">Secondary</option>
        </SelectField>
        <SelectField label={offer ? 'Offer to' : 'Covered by'} required={!offer} value={userId} onChange={(event) => setUserId(event.target.value)} error={get('userId')}>
          <option value="">{offer ? 'Anyone on the rotation' : 'Choose a person…'}</option>
          {candidates
            .filter((person) => offer || person.id !== Number(originalUserId))
            .filter((person) => editor || person.id !== user?.id)
            .map((person) => (
              <option key={person.id} value={person.id}>
                {person.fullName}
              </option>
            ))}
        </SelectField>
      </div>
      {editor && (
        <SelectField label="Whose shift (optional)" value={originalUserId} onChange={(event) => setOriginalUserId(event.target.value)} error={get('originalUserId')}>
          <option value="">Whoever the rotation schedules</option>
          {participants.map((participant) => (
            <option key={participant.userId} value={participant.userId}>
              {participant.userName}
            </option>
          ))}
        </SelectField>
      )}
      <div className="form-row">
        <TextField label={`Starts (${timeZone})`} required type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} error={get('startsAt')} />
        <TextField label={`Ends (${timeZone})`} required type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} error={get('endsAt')} hint="In the future, at most 31 days" />
      </div>
      <TextareaField label="Reason" rows={2} maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} error={get('reason')} />
      <FormError error={create.error} shown={errors.length > 0} />
    </Modal>
  );
}

/** Record unavailability (mine, or anyone's for on-call editors). Times in the browser's zone. */
export function UnavailabilityDialog({ editor, onClose }: { editor: boolean; onClose: () => void }) {
  const add = useAddUnavailability();
  const staff = useAssignableUsers(null, editor);
  const toast = useToast();
  const [userId, setUserId] = useState('');
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [reason, setReason] = useState('');
  const { errors, get } = fieldErrorOf(add.error);
  const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  const startIso = zonedInputToIso(startsAt, localZone);
  const endIso = zonedInputToIso(endsAt, localZone);
  return (
    <Modal
      open
      onClose={onClose}
      title="Add unavailability"
      eyebrow="AVAILABILITY"
      description={`While unavailable, the next available participant covers your on-call slots. Times are in ${localZone}.`}
      dismissible={!add.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={add.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={add.isPending || !startIso || !endIso}
            onClick={() =>
              add.mutate(
                { userId: userId ? Number(userId) : null, startsAt: startIso ?? '', endsAt: endIso ?? '', reason: reason.trim() || null },
                {
                  onSuccess: () => {
                    toast.success('Unavailability recorded');
                    onClose();
                  },
                },
              )
            }
          >
            {add.isPending ? 'Saving…' : 'Add'}
          </button>
        </>
      }
    >
      {editor && (
        <SelectField label="Person" value={userId} onChange={(event) => setUserId(event.target.value)} error={get('userId')}>
          <option value="">Me</option>
          {(staff.data ?? []).map((person) => (
            <option key={person.id} value={person.id}>
              {person.fullName}
            </option>
          ))}
        </SelectField>
      )}
      <div className="form-row">
        <TextField label="From" required type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} error={get('startsAt')} />
        <TextField label="Until" required type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} error={get('endsAt')} hint="At most 90 days" />
      </div>
      <TextField label="Reason" maxLength={300} value={reason} onChange={(event) => setReason(event.target.value)} error={get('reason')} />
      <FormError error={add.error} shown={errors.length > 0} />
    </Modal>
  );
}

/** Handover note for a schedule; the recipient defaults to the next shift's primary (server side). */
export function HandoverDialog({ detail, onClose }: { detail: ScheduleDetail; onClose: () => void }) {
  const add = useAddHandover(detail.schedule.id);
  const toast = useToast();
  const [body, setBody] = useState('');
  const [toUserId, setToUserId] = useState('');
  const { errors, get } = fieldErrorOf(add.error);
  const participants = [...detail.primary, ...detail.secondary].filter((participant, index, all) => all.findIndex((other) => other.userId === participant.userId) === index);
  return (
    <Modal
      open
      onClose={onClose}
      title="Leave a handover note"
      eyebrow={detail.schedule.name.toUpperCase()}
      dismissible={!add.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={add.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="gold-btn"
            disabled={add.isPending || !body.trim()}
            onClick={() =>
              add.mutate(
                { body: body.trim(), toUserId: toUserId ? Number(toUserId) : null },
                {
                  onSuccess: (note) => {
                    toast.success(`Handover note saved${note.toUserName ? ` for ${note.toUserName}` : ''}`);
                    onClose();
                  },
                },
              )
            }
          >
            {add.isPending ? 'Saving…' : 'Save note'}
          </button>
        </>
      }
    >
      <SelectField label="For" value={toUserId} onChange={(event) => setToUserId(event.target.value)} error={get('toUserId')}>
        <option value="">The primary of the next shift</option>
        {participants.map((participant) => (
          <option key={participant.userId} value={participant.userId}>
            {participant.userName}
          </option>
        ))}
      </SelectField>
      <TextareaField label="Note" required rows={6} maxLength={5000} value={body} onChange={(event) => setBody(event.target.value)} error={get('body')} hint="Open issues, ongoing alerts, things to watch" />
      <FormError error={add.error} shown={errors.length > 0} />
    </Modal>
  );
}

interface LevelDraft {
  delayMinutes: string;
  target: EscalationTarget;
  userId: string;
}

/** Escalation policy editor (on-call editors): ordered levels with the delay after the previous level. */
export function PolicyEditor({ policy, onClose }: { policy: PolicyDto | null; onClose: () => void }) {
  const save = useSavePolicy();
  const teams = useTeams({ active: true });
  const staff = useAssignableUsers(null);
  const toast = useToast();
  const [name, setName] = useState(policy?.name ?? '');
  const [description, setDescription] = useState(policy?.description ?? '');
  const [teamId, setTeamId] = useState(policy?.teamId ? String(policy.teamId) : '');
  const [minSeverity, setMinSeverity] = useState<AlertSeverityName>(policy?.minSeverity ?? 'MAJOR');
  const [active, setActive] = useState(policy?.active ?? true);
  const [levels, setLevels] = useState<LevelDraft[]>(
    policy?.levels.map((level) => ({ delayMinutes: String(level.delayMinutes), target: level.target, userId: level.userId ? String(level.userId) : '' })) ?? [
      { delayMinutes: '5', target: 'PRIMARY', userId: '' },
      { delayMinutes: '10', target: 'SECONDARY', userId: '' },
      { delayMinutes: '15', target: 'MANAGER', userId: '' },
    ],
  );
  const { errors, get } = fieldErrorOf(save.error);
  const update = (index: number, patch: Partial<LevelDraft>) => setLevels((current) => current.map((level, i) => (i === index ? { ...level, ...patch } : level)));
  const invalidLevel = levels.some((level) => level.delayMinutes === '' || Number(level.delayMinutes) < 0 || Number(level.delayMinutes) > 1440 || (level.target === 'USER' && !level.userId));
  const blocked = save.isPending || name.trim().length < 2 || levels.length === 0 || invalidLevel;
  let cumulative = 0;

  const submit = () =>
    save.mutate(
      {
        id: policy?.id,
        body: {
          name: name.trim(),
          description: description.trim() || null,
          teamId: teamId ? Number(teamId) : null,
          minSeverity,
          active,
          levels: levels.map((level) => ({ delayMinutes: Number(level.delayMinutes), target: level.target, userId: level.target === 'USER' && level.userId ? Number(level.userId) : null })),
        },
      },
      {
        onSuccess: (saved) => {
          toast.success(`${saved.name} saved`);
          onClose();
        },
      },
    );

  return (
    <Modal
      open
      onClose={onClose}
      title={policy ? `Edit ${policy.name}` : 'New escalation policy'}
      eyebrow="ESCALATION POLICY"
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={blocked}>
            {save.isPending ? 'Saving…' : 'Save policy'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <TextField label="Name" required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} error={get('name')} />
        <SelectField label="Team (optional)" value={teamId} onChange={(event) => setTeamId(event.target.value)} error={get('teamId')}>
          <option value="">Any team</option>
          {(teams.data ?? []).map((team) => (
            <option key={team.id} value={team.id}>
              {team.name}
            </option>
          ))}
        </SelectField>
      </div>
      <TextareaField label="Description" rows={2} maxLength={500} value={description} onChange={(event) => setDescription(event.target.value)} />
      <SelectField label="Applies from severity" value={minSeverity} onChange={(event) => setMinSeverity(event.target.value as AlertSeverityName)} hint="Alerts below this severity keep the built-in team-lead escalation">
        {ALERT_SEVERITIES.map((severity) => (
          <option key={severity} value={severity}>
            {severity.charAt(0) + severity.slice(1).toLowerCase()} and above
          </option>
        ))}
      </SelectField>
      <fieldset className="m-0 grid gap-2 border-0 p-0">
        <legend className="mb-1 text-[11px] font-semibold">Levels</legend>
        {levels.map((level, index) => {
          cumulative += Number(level.delayMinutes) || 0;
          return (
            <div key={index} className="action-card !p-2.5" role="group" aria-label={`Level ${index + 1}`}>
              <div className="form-row three">
                <TextField
                  label={`Level ${index + 1} — delay (min)`}
                  type="number"
                  min={0}
                  max={1440}
                  value={level.delayMinutes}
                  onChange={(event) => update(index, { delayMinutes: event.target.value })}
                  error={get(`levels[${index}].delayMinutes`)}
                  hint={`${index === 0 ? 'after the alert fires' : 'after the previous level'} · ${cumulative} min from start`}
                />
                <SelectField label="Pages" value={level.target} onChange={(event) => update(index, { target: event.target.value as EscalationTarget })}>
                  {ESCALATION_TARGETS.map((target) => (
                    <option key={target} value={target}>
                      {ESCALATION_TARGET_LABELS[target]}
                    </option>
                  ))}
                </SelectField>
                {level.target === 'USER' ? (
                  <SelectField label="Person" required value={level.userId} onChange={(event) => update(index, { userId: event.target.value })} error={get(`levels[${index}].userId`)}>
                    <option value="">Choose a person…</option>
                    {(staff.data ?? []).map((person) => (
                      <option key={person.id} value={person.id}>
                        {person.fullName}
                      </option>
                    ))}
                  </SelectField>
                ) : (
                  <div className="flex items-end justify-end pb-3">
                    <button type="button" className="icon-btn sm" aria-label={`Remove level ${index + 1}`} disabled={levels.length === 1} onClick={() => setLevels((current) => current.filter((_, i) => i !== index))}>
                      <Trash2 size={13} />
                    </button>
                  </div>
                )}
              </div>
              {level.target === 'USER' && (
                <div className="flex justify-end">
                  <button type="button" className="icon-btn sm" aria-label={`Remove level ${index + 1}`} disabled={levels.length === 1} onClick={() => setLevels((current) => current.filter((_, i) => i !== index))}>
                    <Trash2 size={13} />
                  </button>
                </div>
              )}
            </div>
          );
        })}
        {get('levels') && (
          <span className="field-error" role="alert">
            {get('levels')}
          </span>
        )}
        <div>
          <button type="button" className="subtle-btn compact" disabled={levels.length >= 10} onClick={() => setLevels((current) => [...current, { delayMinutes: '15', target: 'MANAGER', userId: '' }])}>
            <Plus size={12} aria-hidden="true" /> Add level
          </button>
        </div>
      </fieldset>
      <CheckboxField label="Active" checked={active} onChange={(event) => setActive(event.target.checked)} />
      <FormError error={save.error} shown={errors.length > 0} />
    </Modal>
  );
}
