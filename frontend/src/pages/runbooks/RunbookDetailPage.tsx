import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckSquare, Clock3, ListOrdered, Pencil, PlayCircle, ShieldAlert, ShieldCheck } from 'lucide-react';
import { useRunbook } from '../../api/runbooks';
import { Badge, BooleanBadge, DemoBadge } from '../../components/Badge';
import { PageHeader } from '../../components/PageHeader';
import { Panel } from '../../components/Panel';
import { ExecutionHistory } from '../../components/runbooks/ExecutionHistory';
import { sensitiveCount } from '../../components/runbooks/RunbookRow';
import { StartRunbookDialog } from '../../components/runbooks/StartRunbookDialog';
import { ErrorState, LoadingState } from '../../components/States';
import { useAuth } from '../../hooks/useAuth';
import { RUNBOOK_EDITORS, STAFF } from '../../lib/constants';
import { formatDateTime, formatNumber, formatRelative, titleCase } from '../../lib/format';
import { RunbookEditor } from './RunbookEditor';
import '../../components/runbooks/runbooks.css';

/** Runbook detail (`/runbooks/:id`): prerequisites, ordered steps with sensitive markers, overview and execution history. */
export default function RunbookDetailPage() {
  const { id } = useParams();
  const runbookId = Number(id);
  const { hasRole } = useAuth();
  const canEdit = hasRole(...RUNBOOK_EDITORS);
  const canRun = hasRole(...STAFF);
  const runbook = useRunbook(Number.isFinite(runbookId) ? runbookId : null);
  const [editing, setEditing] = useState(false);
  const [starting, setStarting] = useState(false);

  if (runbook.isLoading) return <LoadingState variant="panel" label="Loading runbook…" />;
  if (runbook.isError || !runbook.data) return <ErrorState error={runbook.error} onRetry={() => void runbook.refetch()} title="Runbook unavailable" />;
  const data = runbook.data;
  const steps = [...data.steps].sort((a, b) => a.order - b.order);
  const sensitive = sensitiveCount(data);

  return (
    <>
      <PageHeader
        crumbs={[{ label: 'Runbooks', to: '/runbooks' }, { label: data.title }]}
        title={data.title}
        subtitle={data.description ?? undefined}
        actions={
          (canEdit || (canRun && data.active)) && (
            <>
              {canEdit && (
                <button type="button" className={canRun && data.active ? 'subtle-btn' : 'gold-btn'} onClick={() => setEditing(true)}>
                  <Pencil size={14} /> Edit runbook
                </button>
              )}
              {canRun && data.active && (
                <button type="button" className="gold-btn" onClick={() => setStarting(true)}>
                  <PlayCircle size={15} /> Run on incident
                </button>
              )}
            </>
          )
        }
      >
        <div className="tag-row mt-2">
          <BooleanBadge value={data.active} />
          {data.requiresApproval && <Badge value="pending-approval" label={<><ShieldCheck size={10} /> Approval required</>} />}
          {data.serviceName && <Badge value="outline" label={data.serviceName} />}
          {data.estimatedMinutes ? (
            <span className="inline-flex items-center gap-1 text-[10px] text-muted">
              <Clock3 size={11} /> ~{data.estimatedMinutes} min
            </span>
          ) : null}
          <span className="text-[10px] text-muted">
            Updated {formatRelative(data.updatedAt)}
            {data.authorName ? ` by ${data.authorName}` : ''}
          </span>
          <DemoBadge demo={data.demo} />
        </div>
      </PageHeader>

      <div className="grid gap-[14px] xl:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <div className="grid content-start gap-[14px]">
          {data.requiresApproval && (
            <div className="callout">
              <ShieldCheck size={16} />
              <div>
                <b>Requires approval.</b> Starting this runbook on an incident creates a request for Incident Manager approval; the steps unlock once it
                is approved.
              </div>
            </div>
          )}
          <Panel title="Prerequisites" subtitle={data.prerequisites.length ? 'Confirm these before you start' : undefined}>
            {data.prerequisites.length === 0 ? (
              <p className="text-[11px] text-muted">No prerequisites.</p>
            ) : (
              <ul className="rb-prereqs">
                {data.prerequisites.map((item, index) => (
                  <li key={`${index}-${item}`}>
                    <CheckSquare size={13} aria-hidden="true" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
          <Panel title="Procedure" subtitle={`${steps.length} ordered step${steps.length === 1 ? '' : 's'}${sensitive ? ` · ${sensitive} sensitive` : ''}`}>
            <ol className="step-list" aria-label="Runbook steps">
              {steps.map((step) => (
                <li key={step.order} className={`step-item ${step.requiresApproval ? 'sensitive' : ''}`}>
                  <span className="step-num">{step.order}</span>
                  <div className="min-w-0">
                    <div className="rb-step-head">
                      <b>{step.title}</b>
                      {step.requiresApproval && (
                        <span className="rb-sensitive">
                          <ShieldAlert size={10} aria-hidden="true" /> Sensitive — approval required
                        </span>
                      )}
                    </div>
                    <p>{step.instruction}</p>
                  </div>
                </li>
              ))}
            </ol>
          </Panel>
        </div>

        <aside className="grid content-start gap-[14px]">
          <Panel title="Overview">
            <div className="detail-grid tight">
              <div>
                <small>Service</small>
                <b>{data.serviceName ?? 'Any service'}</b>
              </div>
              <div>
                <small>Estimated time</small>
                <b>{data.estimatedMinutes ? `~${data.estimatedMinutes} min` : '—'}</b>
              </div>
              <div>
                <small>Steps</small>
                <b className="inline-flex items-center gap-1">
                  <ListOrdered size={11} /> {steps.length}
                  {sensitive ? ` (${sensitive} sensitive)` : ''}
                </b>
              </div>
              <div>
                <small>Execution approval</small>
                <b>{data.requiresApproval ? 'Required' : 'Not required'}</b>
              </div>
              <div>
                <small>Runs</small>
                <b>{formatNumber(data.executionCount)}</b>
              </div>
              <div>
                <small>Updated</small>
                <b title={formatDateTime(data.updatedAt)}>{formatRelative(data.updatedAt)}</b>
              </div>
            </div>
            <div className="mt-3">
              <small className="text-[10px] text-muted">Linked incident categories</small>
              <div className="tag-row mt-1">
                {data.categories.length ? (
                  data.categories.map((category) => <Badge key={category} value="outline" label={titleCase(category)} />)
                ) : (
                  <span className="text-[11px] text-muted">All categories</span>
                )}
              </div>
            </div>
          </Panel>
          <ExecutionHistory runbookId={data.id} subtitle={`${formatNumber(data.executionCount)} run${data.executionCount === 1 ? '' : 's'} in total`} />
        </aside>
      </div>

      {editing && <RunbookEditor runbook={data} onClose={() => setEditing(false)} />}
      {starting && <StartRunbookDialog runbook={data} onClose={() => setStarting(false)} />}
    </>
  );
}
