import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronRight, LayoutTemplate, Plus, Workflow } from 'lucide-react';
import { useActivateWorkflow } from '../../api/workflows';
import { errorMessage } from '../../lib/api';
import { formatNumber, formatRelative } from '../../lib/format';
import type { WorkflowDto, WorkflowMeta, WorkflowTemplate, WorkflowTrigger } from '../../lib/types';
import { Badge, BooleanBadge, DemoBadge } from '../Badge';
import { ConfirmDialog } from '../ConfirmDialog';
import { Toggle } from '../Controls';
import { FilterSelect } from '../FilterSelect';
import { SearchInput } from '../SearchInput';
import { EmptyState } from '../States';
import { useToast } from '../Toast';
import { triggerIcon } from './icons';
import { actionLabel, conditionLines, fromDefinition, triggerLabel } from './model';

function flowSummary(definition: WorkflowDto['definition'], trigger: WorkflowTrigger, meta?: WorkflowMeta | null) {
  const model = fromDefinition(definition, trigger);
  return {
    model,
    condition: conditionLines(model, meta).full,
    yes: model.yes.map((action) => actionLabel(action.type, meta)),
    no: model.no.map((action) => actionLabel(action.type, meta)),
  };
}

// ------------------------------------------------------------------ My Workflows

export function WorkflowCards({ workflows, meta, canEdit }: { workflows: WorkflowDto[]; meta?: WorkflowMeta | null; canEdit: boolean }) {
  const activate = useActivateWorkflow();
  const toast = useToast();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [trigger, setTrigger] = useState('');
  const [status, setStatus] = useState('');
  const [pending, setPending] = useState<WorkflowDto | null>(null);

  const triggers = useMemo(() => [...new Set(workflows.map((workflow) => workflow.triggerEvent))], [workflows]);
  const visible = useMemo(() => {
    const term = search.trim().toLowerCase();
    return workflows
      .filter((workflow) => !term || workflow.name.toLowerCase().includes(term) || (workflow.description ?? '').toLowerCase().includes(term))
      .filter((workflow) => !trigger || workflow.triggerEvent === trigger)
      .filter((workflow) => !status || (status === 'active') === workflow.active)
      .sort((a, b) => Number(b.active) - Number(a.active) || a.name.localeCompare(b.name));
  }, [workflows, search, trigger, status]);

  if (workflows.length === 0) {
    return (
      <EmptyState
        title="No workflows yet"
        icon={<Workflow size={20} />}
        description={canEdit ? 'Start from a blank canvas or one of the templates.' : 'Workflow editors have not created any automations yet.'}
        action={
          canEdit && (
            <div className="flex flex-wrap justify-center gap-2">
              <button type="button" className="gold-btn compact" onClick={() => navigate('/automation/new')}>
                <Plus size={13} /> Create Workflow
              </button>
              <Link className="subtle-btn compact" to="/automation?tab=templates">
                <LayoutTemplate size={13} /> Browse templates
              </Link>
            </div>
          )
        }
      />
    );
  }

  return (
    <>
      <div className="toolbar">
        <SearchInput value={search} onChange={setSearch} placeholder="Search workflows…" label="Search workflows" />
        <FilterSelect label="Trigger" value={trigger} onChange={setTrigger} options={triggers.map((value) => ({ value, label: triggerLabel(value) }))} allLabel="All triggers" />
        <FilterSelect
          label="Status"
          value={status}
          onChange={setStatus}
          options={[
            { value: 'active', label: 'Active' },
            { value: 'inactive', label: 'Inactive' },
          ]}
          allLabel="Any status"
        />
      </div>
      {visible.length === 0 ? (
        <EmptyState title="No workflows match these filters" icon={<Workflow size={20} />} />
      ) : (
        <div className="wf-card-grid">
          {visible.map((workflow) => {
            const Icon = triggerIcon(workflow.triggerEvent);
            const summary = flowSummary(workflow.definition, workflow.triggerEvent, meta);
            return (
              <article key={workflow.id} className="panel wf-card" aria-labelledby={`wf-card-${workflow.id}`}>
                <div className="wf-card-head">
                  <span className="icon-tile" aria-hidden="true">
                    <Icon size={17} />
                  </span>
                  <div className="wf-card-title">
                    <Link id={`wf-card-${workflow.id}`} to={`/automation/${workflow.id}`}>
                      {workflow.name}
                    </Link>
                    <div className="mt-1 flex flex-wrap items-center gap-1.5">
                      <Badge value="outline" label={triggerLabel(workflow.triggerEvent)} />
                      <Badge value="outline" label={`v${workflow.currentVersion}`} />
                      <BooleanBadge value={workflow.active} />
                      <DemoBadge demo={workflow.demo} />
                    </div>
                  </div>
                  <Toggle
                    checked={workflow.active}
                    label={`${workflow.active ? 'Deactivate' : 'Activate'} ${workflow.name}`}
                    disabled={!canEdit || activate.isPending}
                    onChange={() => setPending(workflow)}
                  />
                </div>
                <p className="wf-card-desc">{workflow.description || summary.condition}</p>
                <p className="wf-flow-line">
                  <b>Yes:</b> {summary.yes.join(' → ') || '—'}
                  <br />
                  <b>No:</b> {summary.no.join(' → ') || 'do nothing'}
                </p>
                <div className="wf-card-stats">
                  <div>
                    <small>Runs (24 h)</small>
                    <b>{formatNumber(workflow.executionsLast24h)}</b>
                  </div>
                  <div>
                    <small>Failures (24 h)</small>
                    <b className={workflow.failuresLast24h ? 'red-text' : ''}>{formatNumber(workflow.failuresLast24h)}</b>
                  </div>
                  <div>
                    <small>Last run</small>
                    <b className="text-[11px]">{workflow.lastExecutedAt ? formatRelative(workflow.lastExecutedAt) : 'Never'}</b>
                  </div>
                </div>
                <div className="wf-card-foot">
                  <Link to={`/automation/${workflow.id}`} aria-label={`${canEdit ? 'Edit' : 'View'} ${workflow.name} in the builder`}>
                    {canEdit ? 'Open builder' : 'View workflow'} <ChevronRight size={12} />
                  </Link>
                  <Link to={`/automation?tab=history&workflowId=${workflow.id}`} aria-label={`Run history of ${workflow.name}`}>
                    Run history <ChevronRight size={12} />
                  </Link>
                  {workflow.createdByName && <span className="ml-auto text-[9px] text-muted">by {workflow.createdByName}</span>}
                </div>
              </article>
            );
          })}
        </div>
      )}
      <ConfirmDialog
        open={pending !== null}
        onClose={() => {
          setPending(null);
          activate.reset();
        }}
        title={pending?.active ? `Deactivate ${pending.name}?` : `Activate ${pending?.name ?? ''}?`}
        message={
          pending?.active
            ? 'The workflow stops reacting to events. Its versions and run history are kept.'
            : `Version v${pending?.currentVersion ?? ''} starts running on "${pending ? triggerLabel(pending.triggerEvent) : ''}" events that match its conditions.`
        }
        confirmLabel={pending?.active ? 'Deactivate' : 'Activate'}
        tone={pending?.active ? 'danger' : 'gold'}
        pending={activate.isPending}
        error={activate.isError ? errorMessage(activate.error) : null}
        onConfirm={() => {
          if (!pending) return;
          activate.mutate(
            { id: pending.id, active: !pending.active },
            {
              onSuccess: (saved) => {
                toast.success(`${saved.name} ${saved.active ? 'activated' : 'deactivated'}`);
                setPending(null);
              },
            },
          );
        }}
      />
    </>
  );
}

// ------------------------------------------------------------------ Templates

export function TemplateGallery({ templates, meta, canEdit }: { templates: WorkflowTemplate[]; meta?: WorkflowMeta | null; canEdit: boolean }) {
  const navigate = useNavigate();
  if (templates.length === 0) return <EmptyState title="No templates available" icon={<LayoutTemplate size={20} />} />;
  return (
    <>
      {!canEdit && <p className="mb-3 text-[10px] text-muted">Templates are starting points for workflow editors (System Admin, IT Admin, IT Manager).</p>}
      <div className="wf-card-grid">
        {templates.map((template) => {
          const Icon = triggerIcon(template.triggerEvent);
          const summary = flowSummary(template.definition, template.triggerEvent, meta);
          return (
            <article key={template.key} className="panel wf-card" aria-labelledby={`wf-template-${template.key}`}>
              <div className="wf-card-head">
                <span className="icon-tile" aria-hidden="true">
                  <Icon size={17} />
                </span>
                <div className="wf-card-title">
                  <h3 id={`wf-template-${template.key}`}>{template.name}</h3>
                  <div className="mt-1 flex flex-wrap items-center gap-1.5">
                    <Badge value="outline" label={triggerLabel(template.triggerEvent)} />
                    {template.triggerEvent === 'SCHEDULED' && summary.model.intervalMinutes && <Badge value="outline" label={`every ${summary.model.intervalMinutes} min`} />}
                  </div>
                </div>
              </div>
              <p className="wf-card-desc">{template.description}</p>
              <p className="wf-flow-line">
                <b>If</b> {summary.condition}
                <br />
                <b>Yes:</b> {summary.yes.join(' → ') || '—'}
                <br />
                <b>No:</b> {summary.no.join(' → ') || 'do nothing'}
              </p>
              {canEdit && (
                <div className="wf-card-foot">
                  <button
                    type="button"
                    className="gold-btn compact ml-auto"
                    aria-label={`Use template ${template.name}`}
                    onClick={() => navigate(`/automation/new?template=${encodeURIComponent(template.key)}`)}
                  >
                    Use template
                  </button>
                </div>
              )}
            </article>
          );
        })}
      </div>
    </>
  );
}
