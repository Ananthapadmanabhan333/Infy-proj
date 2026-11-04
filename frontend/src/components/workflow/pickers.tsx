import { useId } from 'react';
import { useIntegrations } from '../../api/integrations';
import { useRunbooks } from '../../api/runbooks';
import { useServices } from '../../api/services';
import { useAssignableUsers, useDepartments, useUsers } from '../../api/users';
import { titleCase } from '../../lib/format';
import { SelectField, TextField } from '../FormField';

/**
 * Pickers backed by live reference data (teams, agents, runbooks, integrations, services).
 * If the list cannot be loaded (e.g. no permission) they fall back to a plain text input.
 */

interface PickerProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  error?: string;
  hint?: string;
  disabled?: boolean;
  /** Label of the empty option (defaults to "Choose…"). */
  emptyLabel?: string;
}

function withCurrent(options: { value: string; label: string }[], value: string, missingLabel: (value: string) => string) {
  if (!value || options.some((option) => option.value === value)) return options;
  return [{ value, label: missingLabel(value) }, ...options];
}

function OptionSelect({ label, value, onChange, required, error, hint, disabled, emptyLabel, options }: PickerProps & { options: { value: string; label: string }[] }) {
  return (
    <SelectField label={label} value={value} onChange={(event) => onChange(event.target.value)} required={required} error={error} hint={hint} disabled={disabled}>
      <option value="">{emptyLabel ?? 'Choose…'}</option>
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </SelectField>
  );
}

export function DepartmentPicker(props: PickerProps) {
  const departments = useDepartments();
  if (departments.isError || (!departments.isLoading && !departments.data?.length))
    return (
      <TextField
        label={props.label}
        value={props.value}
        onChange={(event) => props.onChange(event.target.value)}
        required={props.required}
        error={props.error}
        hint={props.hint ?? 'Department code, e.g. IT-SD'}
        disabled={props.disabled}
      />
    );
  const options = withCurrent(
    (departments.data ?? []).map((department) => ({ value: department.code, label: `${department.name} (${department.code})` })),
    props.value,
    (code) => `${code} (unknown team)`,
  );
  return <OptionSelect {...props} disabled={props.disabled || departments.isLoading} emptyLabel={departments.isLoading ? 'Loading teams…' : props.emptyLabel} options={options} />;
}

export function AgentPicker(props: PickerProps) {
  const agents = useAssignableUsers(null);
  if (agents.isError || (!agents.isLoading && !agents.data?.length)) {
    return (
      <TextField
        label={props.label}
        type="email"
        value={props.value}
        onChange={(event) => props.onChange(event.target.value)}
        required={props.required}
        error={props.error}
        hint={props.hint}
        disabled={props.disabled}
      />
    );
  }
  const options = withCurrent(
    (agents.data ?? []).map((user) => ({ value: user.email, label: `${user.fullName} — ${user.email}${user.departmentName ? ` (${user.departmentName})` : ''}` })),
    props.value,
    (email) => `${email} (not an assignable agent)`,
  );
  return <OptionSelect {...props} disabled={props.disabled || agents.isLoading} emptyLabel={agents.isLoading ? 'Loading agents…' : props.emptyLabel} options={options} />;
}

/** Any active user (free text with suggestions). */
export function UserEmailInput(props: PickerProps) {
  const listId = useId();
  const users = useUsers({ active: 'true', size: 100 });
  return (
    <>
      <TextField
        label={props.label}
        type="email"
        value={props.value}
        onChange={(event) => props.onChange(event.target.value)}
        required={props.required}
        error={props.error}
        hint={props.hint}
        disabled={props.disabled}
        list={users.data?.content.length ? listId : undefined}
        autoComplete="off"
      />
      {users.data && (
        <datalist id={listId}>
          {users.data.content.map((user) => (
            <option key={user.id} value={user.email}>
              {user.fullName}
            </option>
          ))}
        </datalist>
      )}
    </>
  );
}

export function RunbookPicker(props: PickerProps) {
  const runbooks = useRunbooks();
  if (runbooks.isError)
    return (
      <TextField
        label={props.label}
        inputMode="numeric"
        value={props.value}
        onChange={(event) => props.onChange(event.target.value)}
        required={props.required}
        error={props.error}
        hint="Runbook id"
        disabled={props.disabled}
      />
    );
  const options = withCurrent(
    (runbooks.data ?? [])
      .filter((runbook) => runbook.active || String(runbook.id) === props.value)
      .map((runbook) => ({ value: String(runbook.id), label: `${runbook.title}${runbook.requiresApproval ? ' · approval required' : ''}${runbook.active ? '' : ' (inactive)'}` })),
    props.value,
    (id) => `Runbook #${id} (not found)`,
  );
  return (
    <OptionSelect
      {...props}
      disabled={props.disabled || runbooks.isLoading}
      emptyLabel={runbooks.isLoading ? 'Loading runbooks…' : options.length ? 'Choose a runbook…' : 'No active runbooks'}
      hint={props.hint ?? 'Approval-gated runbooks are sent for approval before they run.'}
      options={options}
    />
  );
}

export function IntegrationPicker(props: PickerProps) {
  const integrations = useIntegrations();
  if (integrations.isError)
    return (
      <TextField
        label={props.label}
        inputMode="numeric"
        value={props.value}
        onChange={(event) => props.onChange(event.target.value)}
        required={props.required}
        error={props.error}
        hint="Integration id"
        disabled={props.disabled}
      />
    );
  const options = withCurrent(
    (integrations.data ?? [])
      .filter((integration) => integration.type !== 'MONITORING_INBOUND')
      .map((integration) => ({ value: String(integration.id), label: `${integration.name} (${titleCase(integration.type)})${integration.enabled ? '' : ' · disabled'}` })),
    props.value,
    (id) => `Integration #${id} (not found)`,
  );
  return (
    <OptionSelect
      {...props}
      disabled={props.disabled || integrations.isLoading}
      emptyLabel={integrations.isLoading ? 'Loading integrations…' : options.length ? 'Choose an integration…' : 'No outbound integrations'}
      options={options}
    />
  );
}

/** Lookup of names for canvas card details (only fetches what the canvas needs). */
export function useNameResolver(needs: { runbooks: boolean; integrations: boolean; departments: boolean }) {
  const runbooks = useRunbooks({}, needs.runbooks);
  const departments = useDepartments(needs.departments);
  const integrations = useIntegrations(needs.integrations).data;
  return (kind: 'runbook' | 'integration' | 'department', key: string): string | undefined => {
    if (kind === 'runbook') return runbooks.data?.find((runbook) => String(runbook.id) === key)?.title;
    if (kind === 'department') return departments.data?.find((entry) => entry.code.toLowerCase() === key.toLowerCase())?.name;
    return integrations?.find((integration) => String(integration.id) === key)?.name;
  };
}

/** Datalist suggestions for condition values that reference records (service, team, assignee). */
export function useValueSuggestions(field: string): { value: string; label: string }[] {
  const services = useServices(field === 'serviceCode');
  const departments = useDepartments(field === 'departmentCode' || field === 'assignedTeam');
  const agents = useAssignableUsers(null, field === 'assigneeEmail');
  if (field === 'serviceCode') return (services.data ?? []).map((service) => ({ value: service.code, label: service.name }));
  if (field === 'departmentCode' || field === 'assignedTeam') return (departments.data ?? []).map((department) => ({ value: department.code, label: department.name }));
  if (field === 'assigneeEmail') return (agents.data ?? []).map((user) => ({ value: user.email, label: user.fullName }));
  return [];
}
