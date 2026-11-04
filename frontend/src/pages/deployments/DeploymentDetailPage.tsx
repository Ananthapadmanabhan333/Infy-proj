import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Cpu, ExternalLink, GitCommitHorizontal, RotateCcw } from 'lucide-react';
import { useDeployment, useDeploymentStatus, useRollbackDeployment } from '../../api/deployments';
import { DemoBadge, PriorityBadge, StatusBadge, SimulatedBadge } from '../../components/Badge';
import { TextareaField, TextField } from '../../components/FormField';
import { DeploymentStatusBadge, Fact, TextBlock, ToneBadge } from '../../components/itsm/ItsmParts';
import { Modal } from '../../components/Modal';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { DEPLOYMENT_EDITORS } from '../../lib/constants';
import { formatDateTime, formatRelative, titleCase } from '../../lib/format';
import { ENVIRONMENT_LABELS, ENVIRONMENT_TONES, formatDurationSeconds, RISK_TONES } from '../../lib/itsm';
import type { DeploymentDto } from '../../lib/itsmTypes';

function safeUrl(url: string | null): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.toString() : null;
  } catch {
    return null;
  }
}

function RollbackModal({ deployment, onClose }: { deployment: DeploymentDto; onClose: () => void }) {
  const rollback = useRollbackDeployment(deployment.id);
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [targetVersion, setTargetVersion] = useState('');
  const errors = rollback.isError ? toApiError(rollback.error).fieldErrors ?? [] : [];
  return (
    <Modal
      open
      onClose={onClose}
      title={`Roll back ${deployment.number}`}
      eyebrow="DEPLOYMENTS"
      description={`Records a rollback deployment of ${deployment.serviceName} in ${ENVIRONMENT_LABELS[deployment.environment]}; ${deployment.number} becomes rolled back.`}
      dismissible={!rollback.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={rollback.isPending}>
            Cancel
          </button>
          <button
            type="button"
            className="danger-btn"
            disabled={reason.trim().length < 5 || rollback.isPending}
            onClick={() =>
              rollback.mutate(
                { reason: reason.trim(), targetVersion: targetVersion.trim() || undefined },
                {
                  onSuccess: (created) => {
                    toast.success(`${created.number} rolls back to ${created.version}`, { action: { label: `Open ${created.number}`, to: `/deployments/${created.id}` } });
                    onClose();
                  },
                },
              )
            }
          >
            {rollback.isPending ? 'Rolling back…' : 'Roll back'}
          </button>
        </>
      }
    >
      <TextareaField label="Reason" required rows={3} minLength={5} maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)} error={errors.find((error) => error.field === 'reason')?.message} />
      <TextField
        label="Target version (optional)"
        maxLength={80}
        hint="Defaults to the previous successful version on this service and environment."
        value={targetVersion}
        onChange={(event) => setTargetVersion(event.target.value)}
        error={errors.find((error) => error.field === 'targetVersion')?.message}
      />
      {rollback.isError && !errors.length && (
        <p className="field-error" role="alert">
          {errorMessage(rollback.error)}
        </p>
      )}
    </Modal>
  );
}

/** /deployments/:id — commit and pipeline, linked change and release, rules-based risk, correlated incidents, rollbacks. */
export default function DeploymentDetailPage() {
  const params = useParams();
  const id = Number(params.id);
  const valid = Number.isInteger(id) && id > 0;
  const { hasRole } = useAuth();
  const canEdit = hasRole(...DEPLOYMENT_EDITORS);
  const detail = useDeployment(valid ? id : null);
  const status = useDeploymentStatus(id);
  const toast = useToast();
  const [rollingBack, setRollingBack] = useState(false);
  const crumbs = [{ label: 'Deployments', to: '/deployments' }];

  if (!valid || (detail.isError && toApiError(detail.error).status === 404)) {
    return (
      <>
        <PageHeader crumbs={[...crumbs, { label: 'Not found' }]} title="Deployment not found" />
        <EmptyState title="This deployment does not exist" action={<Link to="/deployments" className="subtle-btn compact">Back to deployments</Link>} />
      </>
    );
  }
  if (detail.isLoading) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Loading deployment…" />
        <LoadingState variant="panel" />
      </>
    );
  }
  if (!detail.data) {
    return (
      <>
        <PageHeader crumbs={crumbs} title="Deployment" />
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
      </>
    );
  }

  const { deployment, risk, correlatedIncidents, correlationHours, rollbacks } = detail.data;
  const pipeline = safeUrl(deployment.pipelineUrl);
  const finish = (next: 'SUCCEEDED' | 'FAILED') =>
    status.mutate({ status: next }, { onSuccess: () => toast.success(`${deployment.number} marked ${next.toLowerCase()}`), onError: (error) => toast.error(error) });

  return (
    <>
      <PageHeader
        crumbs={[...crumbs, { label: deployment.number }]}
        title={`${deployment.serviceName} ${deployment.version}`}
        actions={
          canEdit ? (
            <>
              {deployment.status === 'IN_PROGRESS' && (
                <>
                  <button type="button" className="danger-btn" disabled={status.isPending} onClick={() => finish('FAILED')}>
                    Mark failed
                  </button>
                  <button type="button" className="gold-btn" disabled={status.isPending} onClick={() => finish('SUCCEEDED')}>
                    Mark succeeded
                  </button>
                </>
              )}
              {(deployment.status === 'SUCCEEDED' || deployment.status === 'FAILED') && (
                <button type="button" className="subtle-btn" onClick={() => setRollingBack(true)}>
                  <RotateCcw size={14} aria-hidden="true" /> Roll back
                </button>
              )}
            </>
          ) : undefined
        }
      >
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
          <span className="id-cell">{deployment.number}</span>
          <DeploymentStatusBadge status={deployment.status} />
          <ToneBadge value={deployment.environment} label={ENVIRONMENT_LABELS[deployment.environment]} tone={ENVIRONMENT_TONES[deployment.environment]} />
          <DemoBadge demo={deployment.demo} /> <SimulatedBadge simulated={deployment.simulated} />
          <span>
            {deployment.sourceName ?? titleCase(deployment.source)}
            {deployment.actorName ? ` · by ${deployment.actorName}` : ''}
          </span>
        </div>
      </PageHeader>
      <div className="page-stack">
        {deployment.status === 'ROLLED_BACK' && (
          <div className="callout red" role="status">
            <RotateCcw size={16} aria-hidden="true" />
            <div>
              <b>Rolled back</b> {deployment.rolledBackAt ? formatRelative(deployment.rolledBackAt) : ''}
              {deployment.rollbackReason ? ` — ${deployment.rollbackReason}` : ''}
            </div>
          </div>
        )}
        <div className="grid items-start gap-[14px] xl:grid-cols-[1.55fr_1fr]">
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Deployment">
              <div className="detail-grid">
                <Fact label="Service">{deployment.serviceName}</Fact>
                <Fact label="Version">{deployment.version}</Fact>
                <Fact label="Commit">
                  {deployment.commitSha ? (
                    <span className="inline-flex items-center gap-1 font-mono" title={deployment.commitSha}>
                      <GitCommitHorizontal size={12} aria-hidden="true" /> {deployment.commitSha.slice(0, 12)}
                    </span>
                  ) : (
                    '—'
                  )}
                </Fact>
                <Fact label="Pipeline">
                  {pipeline ? (
                    <a href={pipeline} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1">
                      Open pipeline <ExternalLink size={11} aria-hidden="true" />
                    </a>
                  ) : (
                    '—'
                  )}
                </Fact>
                <Fact label="Change">{deployment.changeId ? <Link to={`/changes/${deployment.changeId}`}>{deployment.changeNumber}</Link> : 'Not linked'}</Fact>
                <Fact label="Release">{deployment.releaseNumber ? `${deployment.releaseNumber} ${deployment.releaseName ?? ''}` : '—'}</Fact>
                <Fact label="Started">{formatDateTime(deployment.startedAt)}</Fact>
                <Fact label="Finished">{deployment.finishedAt ? formatDateTime(deployment.finishedAt) : '—'}</Fact>
                <Fact label="Duration">{formatDurationSeconds(deployment.durationSeconds)}</Fact>
                <Fact label="External id">{deployment.externalId ?? '—'}</Fact>
                <Fact label="Rollback of">{deployment.rollbackOfId ? <Link to={`/deployments/${deployment.rollbackOfId}`}>{deployment.rollbackOfNumber}</Link> : '—'}</Fact>
              </div>
              {deployment.notes && (
                <div className="mt-3">
                  <TextBlock text={deployment.notes} />
                </div>
              )}
            </Panel>
            <Panel title={`Correlated incidents (within ${correlationHours} h)`}>
              {correlatedIncidents.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No incidents on {deployment.serviceName} after this deployment.</p>
              ) : (
                <ul className="m-0 grid list-none gap-1 p-0" aria-label="Correlated incidents">
                  {correlatedIncidents.map(({ incident, minutesAfterStart }) => (
                    <li key={incident.id} className="list-row flex-wrap sm:flex-nowrap">
                      <div className="min-w-0">
                        <b className="flex flex-wrap items-center gap-1.5">
                          <Link to={`/incidents/${incident.id}`} className="id-cell">
                            {incident.number}
                          </Link>
                          <span className="break-words">{incident.title}</span>
                        </b>
                        <small>{minutesAfterStart} min after the start</small>
                      </div>
                      <span className="flex shrink-0 items-center gap-1">
                        <PriorityBadge priority={incident.priority} />
                        <StatusBadge status={incident.status} />
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>
          <div className="grid min-w-0 gap-[14px]">
            <Panel title="Deployment risk">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <ToneBadge value={risk.level} label={`${titleCase(risk.level)} · score ${risk.score}`} tone={RISK_TONES[risk.level] ?? 'neutral'} />
                <small className="inline-flex items-center gap-1 text-muted">
                  <Cpu size={11} aria-hidden="true" /> Rules-based score — not AI-generated
                </small>
              </div>
              {risk.factors.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">No risk factors.</p>
              ) : (
                <ul className="m-0 grid gap-1 pl-4 text-[11px]">
                  {risk.factors.map((factor) => (
                    <li key={factor.key}>
                      {factor.label} <span className="text-muted">(+{factor.points})</span>
                    </li>
                  ))}
                </ul>
              )}
              <p className="mt-2 mb-0 text-[10px] text-muted">{risk.basis}</p>
            </Panel>
            <Panel title="Rollbacks">
              {rollbacks.length === 0 ? (
                <p className="m-0 text-[11px] text-muted">This deployment has not been rolled back.</p>
              ) : (
                <ul className="m-0 grid list-none gap-1 p-0">
                  {rollbacks.map((rollback) => (
                    <li key={rollback.id} className="list-row">
                      <div className="min-w-0">
                        <b className="flex flex-wrap items-center gap-1.5">
                          <Link to={`/deployments/${rollback.id}`} className="id-cell">
                            {rollback.number}
                          </Link>
                          to {rollback.version}
                        </b>
                        <small>
                          {formatRelative(rollback.startedAt)}
                          {rollback.rollbackReason ? ` · ${rollback.rollbackReason}` : ''}
                        </small>
                      </div>
                      <DeploymentStatusBadge status={rollback.status} />
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>
        </div>
      </div>
      {rollingBack && <RollbackModal deployment={deployment} onClose={() => setRollingBack(false)} />}
    </>
  );
}
