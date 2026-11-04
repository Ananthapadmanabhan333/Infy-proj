import { useState } from 'react';
import { AlertCircle, CheckCircle2, FlaskConical, History, Sparkles, TriangleAlert, XCircle } from 'lucide-react';
import { useAiWorkflowSuggestion } from '../../api/ai';
import { useRollbackWorkflow, useTestWorkflow, useWorkflowVersions } from '../../api/workflows';
import { errorMessage } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import type { AiResult, IncidentSummary, WorkflowDto, WorkflowMeta, WorkflowSuggestion } from '../../lib/types';
import { AiResultCard } from '../AiResultCard';
import { Badge } from '../Badge';
import { ConfirmDialog } from '../ConfirmDialog';
import { Drawer } from '../Drawer';
import { TextareaField } from '../FormField';
import { IncidentPicker } from '../incidents/IncidentPicker';
import { Modal } from '../Modal';
import { EmptyState, ErrorState, LoadingState } from '../States';
import { useToast } from '../Toast';
import { actionLabel, conditionLines, fromDefinition, issueNodeId, ruleSummary, triggerLabel, type ValidationIssue } from './model';

// ------------------------------------------------------------------ test against an incident

export function TestWorkflowModal({ open, onClose, workflow, meta, dirty }: { open: boolean; onClose: () => void; workflow: WorkflowDto; meta: WorkflowMeta; dirty: boolean }) {
  const [incident, setIncident] = useState<IncidentSummary | null>(null);
  const test = useTestWorkflow(workflow.id);
  const result = test.data;
  const branch = result ? (result.branch ?? (result.matched ? 'YES' : 'NO')) : null;
  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      eyebrow="Dry run · no side effects"
      title={`Test ${workflow.name}`}
      description={`Evaluates saved version v${workflow.currentVersion} against a real incident and lists what would run.`}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose}>
            Close
          </button>
          <button type="button" className="gold-btn" disabled={!incident || test.isPending} onClick={() => incident && test.mutate(incident.id)}>
            <FlaskConical size={14} /> {test.isPending ? 'Testing…' : 'Run test'}
          </button>
        </>
      }
    >
      {dirty && (
        <div className="callout mb-3">
          <TriangleAlert size={15} />
          <div>You have unsaved changes. The test uses the saved version — save first to test your edits.</div>
        </div>
      )}
      <IncidentPicker
        label="Incident"
        value={incident}
        onChange={(next) => {
          setIncident(next);
          test.reset();
        }}
      />
      {test.isError && <p className="field-error mt-2">{errorMessage(test.error)}</p>}
      {result && (
        <div className="mt-3 grid gap-3" aria-live="polite">
          <div className={`callout ${result.matched ? 'green' : 'red'}`}>
            {result.matched ? <CheckCircle2 size={16} /> : <XCircle size={16} />}
            <div>
              {result.matched ? 'Conditions matched — the Yes branch would run.' : 'Conditions did not match — the No branch would run.'}
              {branch && <span className="muted-text"> ({branch === 'YES' ? 'Yes' : 'No'} branch)</span>}
            </div>
          </div>
          <section aria-label="Rule results">
            <h4 className="wf-section-title !mt-0 mb-1">Rule by rule</h4>
            {result.ruleResults.length === 0 && <p className="wf-note">No conditions — every event matches.</p>}
            {result.ruleResults.map((entry, index) => (
              <div key={index} className="list-row">
                {entry.matched ? <CheckCircle2 size={14} className="green-text" aria-label="Matched" /> : <XCircle size={14} className="red-text" aria-label="Not matched" />}
                <div>
                  <b>{ruleSummary(entry.rule, meta)}</b>
                  <small>Actual value: {entry.actual === null || entry.actual === '' ? '—' : entry.actual}</small>
                </div>
              </div>
            ))}
          </section>
          <section aria-label="Planned actions">
            <h4 className="wf-section-title !mt-0 mb-1">Planned actions</h4>
            {result.plannedActions.length === 0 ? (
              <p className="wf-note">Nothing would run (the {branch === 'NO' ? 'No' : 'Yes'} branch is empty).</p>
            ) : (
              <ol className="ml-4 list-decimal text-[11px] leading-relaxed text-[#d6cab8]">
                {result.plannedActions.map((action, index) => (
                  <li key={index}>{action}</li>
                ))}
              </ol>
            )}
          </section>
        </div>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ versions

export function VersionsDrawer({
  open,
  onClose,
  workflow,
  meta,
  canEdit,
  onRolledBack,
}: {
  open: boolean;
  onClose: () => void;
  workflow: WorkflowDto;
  meta: WorkflowMeta;
  canEdit: boolean;
  onRolledBack: (workflow: WorkflowDto) => void;
}) {
  const versions = useWorkflowVersions(open ? workflow.id : null);
  const rollback = useRollbackWorkflow(workflow.id);
  const toast = useToast();
  const [target, setTarget] = useState<number | null>(null);
  return (
    <Drawer open={open} onClose={onClose} label="Version history" title={workflow.name} subtitle={`Current version v${workflow.currentVersion} · every save creates a new version`}>
      {versions.isLoading && <LoadingState rows={4} label="Loading versions…" />}
      {versions.isError && <ErrorState error={versions.error} onRetry={() => void versions.refetch()} compact />}
      {versions.data && versions.data.length === 0 && <EmptyState title="No versions recorded" icon={<History size={20} />} />}
      {versions.data && (
        <ol className="grid gap-0" aria-label="Versions">
          {[...versions.data]
            .sort((a, b) => b.versionNumber - a.versionNumber)
            .map((version) => {
              const model = fromDefinition(version.definition, workflow.triggerEvent);
              const current = version.versionNumber === workflow.currentVersion;
              return (
                <li key={version.versionNumber} className="list-row items-start">
                  <History size={14} className="mt-[2px] gold-text" aria-hidden="true" />
                  <div>
                    <b>
                      v{version.versionNumber} {current && <Badge value="active" label="Current" />}
                    </b>
                    <small>{version.changeNote || 'No change note'}</small>
                    <small>
                      {version.createdByName ?? 'Unknown author'} · {formatDateTime(version.createdAt)}
                    </small>
                    <small>
                      {conditionLines(model, meta).full} · Yes: {model.yes.map((action) => actionLabel(action.type, meta)).join(', ') || '—'} · No:{' '}
                      {model.no.map((action) => actionLabel(action.type, meta)).join(', ') || 'nothing'}
                    </small>
                  </div>
                  {canEdit && !current && (
                    <button type="button" className="subtle-btn compact shrink-0" onClick={() => setTarget(version.versionNumber)}>
                      Roll back
                    </button>
                  )}
                </li>
              );
            })}
        </ol>
      )}
      <ConfirmDialog
        open={target !== null}
        onClose={() => setTarget(null)}
        title={`Roll back to v${target ?? ''}?`}
        message="The selected definition is copied into a new current version. Execution history and older versions are kept. Unsaved changes in the builder are discarded."
        confirmLabel="Roll back"
        pending={rollback.isPending}
        error={rollback.isError ? errorMessage(rollback.error) : null}
        onConfirm={() =>
          target !== null &&
          rollback.mutate(target, {
            onSuccess: (saved) => {
              toast.success(`Rolled back to v${target} — now v${saved.currentVersion}`);
              setTarget(null);
              onRolledBack(saved);
            },
          })
        }
      />
    </Drawer>
  );
}

// ------------------------------------------------------------------ SA AI draft (§7)

export function AiDraftModal({
  open,
  onClose,
  meta,
  onApply,
}: {
  open: boolean;
  onClose: () => void;
  meta: WorkflowMeta;
  onApply: (result: AiResult<WorkflowSuggestion>) => void;
}) {
  const [goal, setGoal] = useState('');
  const suggest = useAiWorkflowSuggestion();
  const result = suggest.data;
  const model = result ? fromDefinition(result.data.definition, result.data.triggerEvent) : null;
  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      eyebrow="SA AI"
      title="Draft a workflow from a goal"
      description="SA AI proposes a trigger, conditions and actions. Nothing is saved: review the result on the canvas and save it yourself."
      dismissible={!suggest.isPending}
      footer={
        <>
          <button type="button" className="subtle-btn" onClick={onClose} disabled={suggest.isPending}>
            Cancel
          </button>
          {result ? (
            <button type="button" className="gold-btn" onClick={() => onApply(result)}>
              Apply to canvas
            </button>
          ) : (
            <button type="button" className="gold-btn" disabled={goal.trim().length < 10 || suggest.isPending} onClick={() => suggest.mutate(goal.trim())}>
              <Sparkles size={14} /> {suggest.isPending ? 'Drafting…' : 'Draft workflow'}
            </button>
          )}
        </>
      }
    >
      <TextareaField
        label="What should the workflow do?"
        rows={3}
        maxLength={1000}
        value={goal}
        onChange={(event) => {
          setGoal(event.target.value);
          if (suggest.data || suggest.error) suggest.reset();
        }}
        placeholder="e.g. When a critical payroll incident is created, run the payroll runbook and notify the payroll team; otherwise route it to the service desk."
        hint="At least 10 characters."
      />
      {suggest.isError && <p className="field-error">{errorMessage(suggest.error)}</p>}
      {result && model && (
        <AiResultCard result={result} title={result.data.name}>
          <p className="text-[11px] leading-relaxed text-[#d6cab8]">{result.data.explanation}</p>
          <p className="wf-flow-line mt-2">
            <b>{triggerLabel(result.data.triggerEvent)}</b> → {conditionLines(model, meta).full} → Yes:{' '}
            {model.yes.map((action) => actionLabel(action.type, meta)).join(', ') || '—'} · No: {model.no.map((action) => actionLabel(action.type, meta)).join(', ') || 'nothing'}
          </p>
        </AiResultCard>
      )}
    </Modal>
  );
}

// ------------------------------------------------------------------ validation panel

export function ValidationPanel({ issues, onFocusIssue }: { issues: ValidationIssue[]; onFocusIssue: (issue: ValidationIssue) => void }) {
  const errors = issues.filter((issue) => issue.severity === 'error');
  const warnings = issues.filter((issue) => issue.severity === 'warning');
  return (
    <section className="panel wf-issues" aria-labelledby="wf-issues-title" aria-live="polite">
      <div className="panel-title">
        <div>
          <h3 id="wf-issues-title">Validation</h3>
          <p>
            {errors.length ? `${errors.length} problem${errors.length === 1 ? '' : 's'} to fix before saving` : 'No problems found'}
            {warnings.length ? ` · ${warnings.length} warning${warnings.length === 1 ? '' : 's'}` : ''}
          </p>
        </div>
      </div>
      {issues.length === 0 ? (
        <div className="callout green">
          <CheckCircle2 size={15} />
          <div>The workflow is valid. The server re-checks referenced teams, users, runbooks and integrations when you save.</div>
        </div>
      ) : (
        <ul className="wf-issue-list">
          {[...errors, ...warnings].map((issue) => {
            const Icon = issue.severity === 'error' ? AlertCircle : TriangleAlert;
            const focusable = issueNodeId(issue) !== null || issue.target.kind === 'workflow';
            const content = (
              <>
                <Icon size={13} aria-hidden="true" />
                <span>
                  <span className="sr-only">{issue.severity === 'error' ? 'Error: ' : 'Warning: '}</span>
                  {issue.target.kind === 'server' && <b>Server: </b>}
                  {issue.message}
                </span>
              </>
            );
            return (
              <li key={issue.id}>
                {focusable ? (
                  <button type="button" className={`wf-issue ${issue.severity}`} onClick={() => onFocusIssue(issue)}>
                    {content}
                  </button>
                ) : (
                  <div className={`wf-issue ${issue.severity}`}>{content}</div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
