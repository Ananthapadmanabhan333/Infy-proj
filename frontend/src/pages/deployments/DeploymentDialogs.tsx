import { useState } from 'react';
import { useChanges } from '../../api/changes';
import { useCreateDeployment, useReleases, useSaveRelease } from '../../api/deployments';
import { useServices } from '../../api/services';
import { SelectField, TextareaField, TextField } from '../../components/FormField';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { errorMessage, toApiError } from '../../lib/api';
import { isoToLocalInput, localInputToIso, titleCase } from '../../lib/format';
import { ENVIRONMENT_LABELS, ENVIRONMENTS, RELEASE_STATUSES } from '../../lib/itsm';
import type { DeploymentDto, DeploymentEnvironment, DeploymentStatus, ReleaseDto, ReleaseStatus } from '../../lib/itsmTypes';

function useFieldErrors(error: unknown, active: boolean) {
  const errors = active ? toApiError(error).fieldErrors ?? [] : [];
  return { errors, of: (field: string) => errors.find((entry) => entry.field === field)?.message };
}

/** Changes a deployment can reference (approved onwards, newest first). */
function useLinkableChanges() {
  return useChanges({ status: 'APPROVED,SCHEDULED,IMPLEMENTING,COMPLETED', size: 50, sort: 'updatedAt,desc' });
}

export function RecordDeploymentModal({ onClose, onCreated }: { onClose: () => void; onCreated?: (deployment: DeploymentDto) => void }) {
  const create = useCreateDeployment();
  const services = useServices();
  const releases = useReleases({ size: 50, sort: 'updatedAt,desc' });
  const changes = useLinkableChanges();
  const toast = useToast();
  const [serviceId, setServiceId] = useState('');
  const [environment, setEnvironment] = useState<DeploymentEnvironment>('PRODUCTION');
  const [version, setVersion] = useState('');
  const [commitSha, setCommitSha] = useState('');
  const [pipelineUrl, setPipelineUrl] = useState('');
  const [status, setStatus] = useState<DeploymentStatus>('IN_PROGRESS');
  const [releaseId, setReleaseId] = useState('');
  const [changeId, setChangeId] = useState('');
  const [notes, setNotes] = useState('');
  const fields = useFieldErrors(create.error, create.isError);

  const submit = () =>
    create.mutate(
      {
        serviceId: Number(serviceId),
        environment,
        version: version.trim(),
        commitSha: commitSha.trim() || null,
        pipelineUrl: pipelineUrl.trim() || null,
        status,
        releaseId: releaseId ? Number(releaseId) : null,
        changeId: changeId ? Number(changeId) : null,
        notes: notes.trim() || null,
      },
      {
        onSuccess: (deployment) => {
          toast.success(`${deployment.number} recorded`);
          onCreated?.(deployment);
          onClose();
        },
      },
    );

  return (
    <Modal
      open
      onClose={onClose}
      title="Record a deployment"
      eyebrow="DEPLOYMENTS"
      size="lg"
      description="Manual record — pipelines connected through a webhook source record deployments automatically."
      dismissible={!create.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={create.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={!serviceId || !version.trim() || create.isPending}>
            {create.isPending ? 'Saving…' : 'Record deployment'}
          </button>
        </>
      }
    >
      <div className="form-row three">
        <SelectField label="Service" required value={serviceId} onChange={(event) => setServiceId(event.target.value)} error={fields.of('serviceId')}>
          <option value="">Choose a service</option>
          {(services.data ?? []).map((service) => (
            <option key={service.id} value={service.id}>
              {service.name}
            </option>
          ))}
        </SelectField>
        <SelectField label="Environment" required value={environment} onChange={(event) => setEnvironment(event.target.value as DeploymentEnvironment)}>
          {ENVIRONMENTS.map((value) => (
            <option key={value} value={value}>
              {ENVIRONMENT_LABELS[value]}
            </option>
          ))}
        </SelectField>
        <TextField label="Version" required maxLength={80} value={version} onChange={(event) => setVersion(event.target.value)} error={fields.of('version')} />
      </div>
      <div className="form-row">
        <TextField label="Commit SHA" placeholder="7–64 hex characters" value={commitSha} onChange={(event) => setCommitSha(event.target.value)} error={fields.of('commitSha')} />
        <TextField label="Pipeline URL" type="url" placeholder="https://…" value={pipelineUrl} onChange={(event) => setPipelineUrl(event.target.value)} error={fields.of('pipelineUrl')} />
      </div>
      <div className="form-row three">
        <SelectField label="Status" value={status} onChange={(event) => setStatus(event.target.value as DeploymentStatus)}>
          <option value="IN_PROGRESS">In progress</option>
          <option value="SUCCEEDED">Succeeded</option>
          <option value="FAILED">Failed</option>
        </SelectField>
        <SelectField label="Release" value={releaseId} onChange={(event) => setReleaseId(event.target.value)} error={fields.of('releaseId')}>
          <option value="">None</option>
          {(releases.data?.content ?? []).map((release) => (
            <option key={release.id} value={release.id}>
              {release.number} {release.name} {release.version}
            </option>
          ))}
        </SelectField>
        <SelectField label="Change" value={changeId} onChange={(event) => setChangeId(event.target.value)} error={fields.of('changeId')} hint="Production deployments without a change raise the risk.">
          <option value="">None</option>
          {(changes.data?.content ?? []).map((change) => (
            <option key={change.id} value={change.id}>
              {change.number} {change.title}
            </option>
          ))}
        </SelectField>
      </div>
      <TextareaField label="Notes" rows={2} maxLength={2000} value={notes} onChange={(event) => setNotes(event.target.value)} />
      {create.isError && !fields.errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(create.error)}
        </p>
      )}
    </Modal>
  );
}

export function ReleaseModal({ release, onClose }: { release: ReleaseDto | null; onClose: () => void }) {
  const save = useSaveRelease();
  const services = useServices();
  const changes = useLinkableChanges();
  const toast = useToast();
  const [name, setName] = useState(release?.name ?? '');
  const [version, setVersion] = useState(release?.version ?? '');
  const [description, setDescription] = useState(release?.description ?? '');
  const [status, setStatus] = useState<ReleaseStatus>(release?.status ?? 'PLANNED');
  const [serviceId, setServiceId] = useState(release?.serviceId ? String(release.serviceId) : '');
  const [changeId, setChangeId] = useState(release?.changeId ? String(release.changeId) : '');
  const [targetAt, setTargetAt] = useState(isoToLocalInput(release?.targetAt));
  const fields = useFieldErrors(save.error, save.isError);
  const submit = () =>
    save.mutate(
      {
        id: release?.id,
        body: {
          name: name.trim(),
          version: version.trim(),
          description: description.trim() || null,
          status,
          serviceId: serviceId ? Number(serviceId) : null,
          changeId: changeId ? Number(changeId) : null,
          targetAt: localInputToIso(targetAt),
        },
      },
      {
        onSuccess: (saved) => {
          toast.success(`${saved.number} saved`);
          onClose();
        },
      },
    );
  return (
    <Modal
      open
      onClose={onClose}
      title={release ? `Edit ${release.number}` : 'New release'}
      eyebrow="RELEASES"
      size="lg"
      dismissible={!save.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button type="button" className="gold-btn" onClick={submit} disabled={!name.trim() || !version.trim() || save.isPending}>
            {save.isPending ? 'Saving…' : 'Save release'}
          </button>
        </>
      }
    >
      <div className="form-row">
        <TextField label="Name" required maxLength={150} value={name} onChange={(event) => setName(event.target.value)} error={fields.of('name')} />
        <TextField label="Version" required maxLength={80} value={version} onChange={(event) => setVersion(event.target.value)} error={fields.of('version')} />
      </div>
      <TextareaField label="Description" rows={2} maxLength={2000} value={description} onChange={(event) => setDescription(event.target.value)} />
      <div className="form-row">
        <SelectField label="Status" value={status} onChange={(event) => setStatus(event.target.value as ReleaseStatus)} hint="Follows its deployments automatically once they run.">
          {RELEASE_STATUSES.map((value) => (
            <option key={value} value={value}>
              {titleCase(value)}
            </option>
          ))}
        </SelectField>
        <TextField label="Target date" type="datetime-local" value={targetAt} onChange={(event) => setTargetAt(event.target.value)} />
      </div>
      <div className="form-row">
        <SelectField label="Service" value={serviceId} onChange={(event) => setServiceId(event.target.value)}>
          <option value="">None</option>
          {(services.data ?? []).map((service) => (
            <option key={service.id} value={service.id}>
              {service.name}
            </option>
          ))}
        </SelectField>
        <SelectField label="Change" value={changeId} onChange={(event) => setChangeId(event.target.value)}>
          <option value="">None</option>
          {(changes.data?.content ?? []).map((change) => (
            <option key={change.id} value={change.id}>
              {change.number} {change.title}
            </option>
          ))}
        </SelectField>
      </div>
      {save.isError && !fields.errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(save.error)}
        </p>
      )}
    </Modal>
  );
}
