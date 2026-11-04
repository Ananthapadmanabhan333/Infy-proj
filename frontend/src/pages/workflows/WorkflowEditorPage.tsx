import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { FlaskConical, History, LayoutGrid, LayoutTemplate, Lock, Save, Settings2, ShieldCheck, Sparkles, Undo2, X } from 'lucide-react';
import { useActivateWorkflow, useSaveWorkflow, useWorkflow, useWorkflowMeta, useWorkflowTemplates } from '../../api/workflows';
import { AiResultCard } from '../../components/AiResultCard';
import { Badge, BooleanBadge, DemoBadge } from '../../components/Badge';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Toggle } from '../../components/Controls';
import { PageHeader } from '../../components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../../components/States';
import { useToast } from '../../components/Toast';
import { AiDraftModal, TestWorkflowModal, ValidationPanel, VersionsDrawer } from '../../components/workflow/BuilderDialogs';
import {
  actionLabel,
  addAction,
  draftFingerprint,
  draftFromBlueprint,
  draftFromWorkflow,
  emptyDraft,
  findAction,
  fromDefinition,
  issueNodeId,
  MAX_ACTIONS_PER_BRANCH,
  readSuggestionState,
  removeAction,
  resetLayout,
  setPosition,
  setTrigger,
  toRequest,
  TRIGGER_NODE,
  validateDraft,
  type AiSourceMeta,
  type Branch,
  type ValidationIssue,
  type WorkflowDraft,
  type WorkflowModel,
} from '../../components/workflow/model';
import { NodeInspector } from '../../components/workflow/NodeInspector';
import { useNameResolver } from '../../components/workflow/pickers';
import { WorkflowCanvas } from '../../components/workflow/WorkflowCanvas';
import { WorkflowPalette } from '../../components/workflow/WorkflowPalette';
import '../../components/workflow/workflow.css';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { STAFF, WORKFLOW_EDITORS } from '../../lib/constants';
import { formatNumber, formatRelative } from '../../lib/format';
import type { AiResult, WorkflowActionType, WorkflowDto, WorkflowMeta, WorkflowSuggestion, WorkflowTrigger } from '../../lib/types';

type Dialog = 'save' | 'test' | 'versions' | 'activate' | 'ai';

/** Where an unsaved draft came from (shown as a banner until saved or dismissed). */
type Origin = { kind: 'template'; name: string } | { kind: 'ai'; suggestion: WorkflowSuggestion; ai: AiSourceMeta | null };

interface EditorState {
  draft: WorkflowDraft;
  /** Previous canvas models for undo (newest last). */
  history: WorkflowModel[];
}

const HISTORY_LIMIT = 50;

function serverIssuesFrom(error: unknown): ValidationIssue[] {
  const apiError = toApiError(error);
  const issues: ValidationIssue[] = (apiError.fieldErrors ?? []).map((fieldError, index) => ({
    id: `server-${index}`,
    severity: 'error',
    message: fieldError.field === 'name' ? fieldError.message : `${fieldError.field}: ${fieldError.message}`,
    target: fieldError.field === 'name' ? { kind: 'workflow', field: 'name' } : { kind: 'server' },
  }));
  if (!issues.length) issues.push({ id: 'server', severity: 'error', message: apiError.message, target: { kind: 'server' } });
  return issues;
}

function isEditableTarget(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  return Boolean(element && (element.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(element.tagName)));
}

function usedTypes(model: WorkflowModel): Set<WorkflowActionType> {
  return new Set([...model.yes, ...model.no].map((action) => action.type));
}

function Builder({ meta, workflow, initialDraft, initialOrigin }: { meta: WorkflowMeta; workflow?: WorkflowDto; initialDraft: WorkflowDraft; initialOrigin: Origin | null }) {
  const { hasRole } = useAuth();
  const canEdit = hasRole(...WORKFLOW_EDITORS);
  const canTest = hasRole(...STAFF);
  const readOnly = !canEdit;
  const toast = useToast();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const save = useSaveWorkflow();
  const activate = useActivateWorkflow();

  const [state, setState] = useState<EditorState>({ draft: initialDraft, history: [] });
  const [baseline, setBaseline] = useState(() => draftFingerprint(workflow ? initialDraft : emptyDraft()));
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [targetBranch, setTargetBranch] = useState<Branch>('yes');
  const [showIssues, setShowIssues] = useState(false);
  const [serverIssues, setServerIssues] = useState<ValidationIssue[]>([]);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [origin, setOrigin] = useState<Origin | null>(initialOrigin);
  const inspectorHeading = useRef<HTMLHeadingElement>(null);
  const focusInspector = useRef(false);

  const { draft, history } = state;
  const model = draft.model;
  const isNew = !workflow;
  const dirty = draftFingerprint(draft) !== baseline;
  const issues = useMemo(() => validateDraft(draft, meta), [draft, meta]);
  const shownIssues = useMemo(() => (showIssues ? [...issues, ...serverIssues] : serverIssues), [showIssues, issues, serverIssues]);
  const invalidIds = useMemo(
    () =>
      new Set(
        shownIssues
          .filter((issue) => issue.severity === 'error')
          .map(issueNodeId)
          .filter((id): id is string => Boolean(id)),
      ),
    [shownIssues],
  );
  const types = usedTypes(model);
  const resolve = useNameResolver({
    runbooks: types.has('START_RUNBOOK'),
    integrations: canEdit && types.has('SEND_WEBHOOK'),
    departments: types.has('ASSIGN_DEPARTMENT') || types.has('NOTIFY_DEPARTMENT'),
  });

  // Warn before leaving with unsaved work.
  useEffect(() => {
    if (!canEdit || !dirty) return undefined;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [canEdit, dirty]);

  useEffect(() => {
    if (!focusInspector.current) return;
    focusInspector.current = false;
    inspectorHeading.current?.focus();
  });

  // ---------------------------------------------------------------- edits
  const changeModel = (next: WorkflowModel, select?: string | null) => {
    if (readOnly) return;
    setState((current) =>
      current.draft.model === next ? current : { draft: { ...current.draft, model: next }, history: [...current.history.slice(-(HISTORY_LIMIT - 1)), current.draft.model] },
    );
    setServerIssues([]);
    if (select === null) setSelectedId(null);
    else if (select !== undefined) selectNode(select, next);
  };

  const restoreModel = (previous: WorkflowModel) =>
    setState((current) => ({ draft: { ...current.draft, model: previous }, history: [...current.history.slice(-(HISTORY_LIMIT - 1)), current.draft.model] }));

  const undo = () =>
    setState((current) => {
      const previous = current.history[current.history.length - 1];
      return previous ? { draft: { ...current.draft, model: previous }, history: current.history.slice(0, -1) } : current;
    });

  const changeDraft = (patch: Partial<Omit<WorkflowDraft, 'model'>>) => {
    if (readOnly) return;
    setState((current) => ({ ...current, draft: { ...current.draft, ...patch } }));
    setServerIssues([]);
  };

  const selectNode = (id: string | null, within: WorkflowModel = model) => {
    setSelectedId(id);
    const found = id ? findAction(within, id) : null;
    if (found) setTargetBranch(found.branch);
  };

  const openNode = (id: string | null) => {
    selectNode(id);
    focusInspector.current = true;
  };

  const addActionTo = (branch: Branch, type: WorkflowActionType) => {
    if (model[branch].length >= MAX_ACTIONS_PER_BRANCH) {
      toast.warning(`A branch can hold at most ${MAX_ACTIONS_PER_BRANCH} actions`);
      return;
    }
    const added = addAction(model, branch, type);
    changeModel(added.model, added.uid);
    setTargetBranch(branch);
  };

  const removeNode = (uid: string) => {
    const found = findAction(model, uid);
    if (!found) return;
    const before = model;
    changeModel(removeAction(model, uid), selectedId === uid ? null : undefined);
    toast.info(`Removed ${actionLabel(found.action.type, meta)} from the ${found.branch === 'yes' ? 'Yes' : 'No'} branch`, {
      action: { label: 'Undo', onClick: () => restoreModel(before) },
    });
  };

  const setTriggerTo = (trigger: WorkflowTrigger) => changeModel(setTrigger(model, trigger), TRIGGER_NODE);

  // ---------------------------------------------------------------- validate / save
  const focusIssue = (issue: ValidationIssue) => {
    const nodeId = issueNodeId(issue);
    if (nodeId) openNode(nodeId);
    else if (issue.target.kind === 'workflow') openNode(null);
  };

  const runValidation = () => {
    setShowIssues(true);
    const errors = issues.filter((issue) => issue.severity === 'error');
    const warnings = issues.length - errors.length;
    if (errors.length) {
      toast.warning(`${errors.length} problem${errors.length === 1 ? '' : 's'} to fix`);
      focusIssue(errors[0]);
    } else {
      toast.success(warnings ? `No problems found · ${warnings} warning${warnings === 1 ? '' : 's'}` : 'No problems found');
    }
  };

  const startSave = () => {
    setShowIssues(true);
    const errors = issues.filter((issue) => issue.severity === 'error');
    if (errors.length) {
      toast.error(`Fix ${errors.length} problem${errors.length === 1 ? '' : 's'} before saving`);
      focusIssue(errors[0]);
      return;
    }
    setDialog('save');
  };

  const resetFrom = (saved: WorkflowDto) => {
    const next = draftFromWorkflow(saved);
    setState({ draft: next, history: [] });
    setBaseline(draftFingerprint(next));
    setSelectedId(null);
    setServerIssues([]);
    setShowIssues(false);
    setOrigin(null);
  };

  const confirmSave = (note: string) => {
    const body = { ...toRequest(draft, note || (isNew ? 'Initial version' : undefined)), active: workflow ? workflow.active : draft.active };
    save.mutate(
      { id: workflow?.id, body },
      {
        onSuccess: (saved) => {
          setDialog(null);
          if (!workflow) {
            queryClient.setQueryData(['workflows', 'detail', saved.id], saved);
            setBaseline(draftFingerprint(draft));
            toast.success(`${saved.name} created as v${saved.currentVersion}${saved.active ? ' and activated' : ''}`);
            navigate(`/automation/${saved.id}`, { replace: true, state: null });
            return;
          }
          resetFrom(saved);
          toast.success(
            saved.currentVersion === workflow.currentVersion
              ? `Saved ${saved.name} — still v${saved.currentVersion} (only settings or node positions changed)`
              : `Saved ${saved.name} as v${saved.currentVersion}`,
          );
        },
        onError: (error) => {
          setDialog(null);
          setServerIssues(serverIssuesFrom(error));
          setShowIssues(true);
          toast.error(error);
        },
      },
    );
  };

  const confirmActivate = () => {
    if (!workflow) return;
    const next = !workflow.active;
    activate.mutate(
      { id: workflow.id, active: next },
      {
        onSuccess: (saved) => {
          setDialog(null);
          setState((current) => ({ ...current, draft: { ...current.draft, active: saved.active } }));
          toast.success(`${saved.name} ${saved.active ? 'activated' : 'deactivated'}`);
        },
      },
    );
  };

  const applyAi = (result: AiResult<WorkflowSuggestion>) => {
    const suggestion = result.data;
    const next = draftFromBlueprint(suggestion);
    setState((current) => ({
      draft: isNew
        ? { ...current.draft, name: suggestion.name || current.draft.name, description: suggestion.description || current.draft.description, model: next.model }
        : { ...current.draft, model: next.model },
      history: [...current.history.slice(-(HISTORY_LIMIT - 1)), current.draft.model],
    }));
    setOrigin({ kind: 'ai', suggestion, ai: result });
    setSelectedId(null);
    setDialog(null);
    setShowIssues(true);
    toast.info('SA AI draft applied — review it and save explicitly');
  };

  const onBuilderKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z' && !event.shiftKey && !isEditableTarget(event.target) && !readOnly) {
      event.preventDefault();
      undo();
    }
  };

  const activeNow = workflow ? workflow.active : draft.active;
  const errorsCount = issues.filter((issue) => issue.severity === 'error').length;
  const nameError = shownIssues.find((issue) => issue.target.kind === 'workflow' && issue.target.field === 'name');
  const title = draft.name.trim() || workflow?.name || 'New workflow';

  return (
    <>
      <PageHeader
        crumbs={[{ label: 'Automation', to: '/automation' }, { label: workflow ? workflow.name : 'New workflow' }]}
        title={title}
        subtitle={draft.description.trim() || 'Trigger → condition → Yes / No actions'}
        actions={
          <>
            {canEdit && (
              <span className="wf-active-control inline-flex items-center gap-2 text-[11px] text-[#d9cbb5]">
                <Toggle
                  checked={activeNow}
                  disabled={activate.isPending}
                  label={workflow ? `${activeNow ? 'Deactivate' : 'Activate'} ${workflow.name}` : 'Activate when saved'}
                  onChange={(next) => (workflow ? setDialog('activate') : changeDraft({ active: next }))}
                />
                {workflow ? (activeNow ? 'Active' : 'Inactive') : activeNow ? 'Activate on save' : 'Save inactive'}
              </span>
            )}
            {workflow && (
              <button type="button" className="subtle-btn" onClick={() => setDialog('versions')}>
                <History size={14} /> Versions
              </button>
            )}
            {workflow && canTest && (
              <button type="button" className="subtle-btn" onClick={() => setDialog('test')}>
                <FlaskConical size={14} /> Test
              </button>
            )}
            {canEdit && (
              <button type="button" className="subtle-btn" onClick={runValidation} aria-describedby={showIssues && errorsCount ? 'wf-issues-title' : undefined}>
                <ShieldCheck size={14} /> Validate{showIssues && errorsCount ? ` (${errorsCount})` : ''}
              </button>
            )}
            {canEdit ? (
              <button type="button" className="gold-btn" onClick={startSave} disabled={save.isPending || (!isNew && !dirty)}>
                <Save size={15} /> {save.isPending ? 'Saving…' : isNew ? 'Save workflow' : 'Save new version'}
              </button>
            ) : (
              <span className="chip">
                <Lock size={11} /> Read-only — editing needs System Admin, IT Admin or IT Manager
              </span>
            )}
          </>
        }
      >
        <div className="tag-row mt-2 flex flex-wrap items-center gap-2 text-[10px] text-muted">
          {workflow ? (
            <>
              <BooleanBadge value={workflow.active} />
              <Badge value="outline" label={`v${workflow.currentVersion}`} />
              <DemoBadge demo={workflow.demo} />
              <span>
                {formatNumber(workflow.executionsLast24h)} runs ·{' '}
                <span className={workflow.failuresLast24h ? 'red-text' : ''}>{formatNumber(workflow.failuresLast24h)} failed</span> in 24 h
              </span>
              <span>Last run {workflow.lastExecutedAt ? formatRelative(workflow.lastExecutedAt) : 'never'}</span>
              <Link className="gold-text" to={`/automation?tab=history&workflowId=${workflow.id}`}>
                Run history
              </Link>
            </>
          ) : (
            <Badge value="outline" label="Not saved yet" />
          )}
          {canEdit && dirty && !isNew && <Badge value="warning" tone="warning" label="Unsaved changes" />}
        </div>
      </PageHeader>

      <div className="page-stack">
        {origin?.kind === 'ai' &&
          (origin.ai ? (
            <AiResultCard
              result={{ ...origin.ai, data: origin.suggestion }}
              title="Drafted by SA AI — review before saving"
              actions={
                <button type="button" className="subtle-btn compact" onClick={() => setOrigin(null)}>
                  <X size={12} /> Dismiss
                </button>
              }
            >
              <p className="text-[11px] leading-relaxed text-[#d6cab8]">{origin.suggestion.explanation}</p>
              <p className="mt-1 text-[10px] text-muted">Nothing has been saved. Check each node, then use Save to create the workflow.</p>
            </AiResultCard>
          ) : (
            <div className="callout" role="note">
              <Sparkles size={15} />
              <div>
                <b>SA AI suggestion</b> <span className="chip">Source label not provided</span>
                <p className="mt-1">{origin.suggestion.explanation}</p>
                <p className="mt-1 text-[10px] text-muted">Nothing has been saved. Review the canvas and save explicitly.</p>
              </div>
              <button type="button" className="icon-btn sm ml-auto" aria-label="Dismiss suggestion note" onClick={() => setOrigin(null)}>
                <X size={13} />
              </button>
            </div>
          ))}
        {origin?.kind === 'template' && (
          <div className="callout" role="note">
            <LayoutTemplate size={15} />
            <div>
              Started from the <b>{origin.name}</b> template. It is not saved yet — adjust it, then save.
            </div>
            <button type="button" className="icon-btn sm ml-auto" aria-label="Dismiss template note" onClick={() => setOrigin(null)}>
              <X size={13} />
            </button>
          </div>
        )}

        <div className={`wf-builder ${readOnly ? 'no-palette' : ''}`} onKeyDown={onBuilderKeyDown}>
          {!readOnly && (
            <WorkflowPalette
              meta={meta}
              trigger={model.trigger}
              targetBranch={targetBranch}
              onTargetBranch={setTargetBranch}
              onSetTrigger={setTriggerTo}
              onAddAction={addActionTo}
            />
          )}

          <div className="grid min-w-0 content-start gap-[14px]">
            <section className="panel wf-canvas-panel" aria-label="Workflow canvas">
              <div className="wf-canvas-head">
                {readOnly ? (
                  <h2 className="wf-name-input !border-0">{title}</h2>
                ) : (
                  <input
                    className="wf-name-input"
                    aria-label="Workflow name"
                    placeholder="Name this workflow"
                    maxLength={150}
                    value={draft.name}
                    aria-invalid={nameError ? true : undefined}
                    onChange={(event) => changeDraft({ name: event.target.value })}
                  />
                )}
                <div className="wf-head-tools">
                  {!readOnly && (
                    <>
                      <button type="button" className="icon-btn sm" aria-label="Undo" title="Undo (Ctrl+Z)" disabled={!history.length} onClick={undo}>
                        <Undo2 size={14} />
                      </button>
                      <button
                        type="button"
                        className="icon-btn sm"
                        aria-label="Auto-arrange nodes"
                        title="Auto-arrange nodes"
                        disabled={!Object.keys(model.positions).length}
                        onClick={() => changeModel(resetLayout(model))}
                      >
                        <LayoutGrid size={14} />
                      </button>
                      <button type="button" className="icon-btn sm" aria-label="Draft with SA AI" title="Draft with SA AI" onClick={() => setDialog('ai')}>
                        <Sparkles size={14} />
                      </button>
                    </>
                  )}
                  <button
                    type="button"
                    className="icon-btn sm"
                    aria-label="Workflow settings"
                    title="Workflow settings"
                    aria-pressed={selectedId === null}
                    onClick={() => openNode(null)}
                  >
                    <Settings2 size={14} />
                  </button>
                </div>
              </div>
              <WorkflowCanvas
                model={model}
                meta={meta}
                selectedId={selectedId}
                invalidIds={invalidIds}
                readOnly={readOnly}
                resolve={resolve}
                onSelect={(id) => selectNode(id)}
                onOpen={openNode}
                onMove={(id, point) => changeModel(setPosition(model, id, point))}
                onRemove={removeNode}
                onAddAction={addActionTo}
                onSetTrigger={setTriggerTo}
              />
              <div className="wf-canvas-foot" aria-label="Canvas shortcuts">
                <span>
                  {readOnly ? 'Click a node to inspect it' : 'Drag nodes to arrange'} · <kbd>Enter</kbd> opens the editor
                </span>
                {!readOnly && (
                  <>
                    <span>
                      <kbd>Arrows</kbd> nudge (<kbd>Shift</kbd> = faster)
                    </span>
                    <span>
                      <kbd>Delete</kbd> removes an action
                    </span>
                    <span>
                      <kbd>Ctrl</kbd>+<kbd>Z</kbd> undo
                    </span>
                  </>
                )}
              </div>
            </section>
            {(showIssues || serverIssues.length > 0) && <ValidationPanel issues={shownIssues} onFocusIssue={focusIssue} />}
          </div>

          <NodeInspector
            draft={draft}
            meta={meta}
            selectedId={selectedId}
            readOnly={readOnly}
            issues={shownIssues}
            onDraftChange={changeDraft}
            onModelChange={changeModel}
            onSelect={(id) => selectNode(id)}
            headingRef={inspectorHeading}
          />
        </div>
      </div>

      <ConfirmDialog
        open={dialog === 'save'}
        onClose={() => setDialog(null)}
        title={isNew ? 'Save workflow' : `Save version v${(workflow?.currentVersion ?? 0) + 1}?`}
        message={
          isNew ? (
            <>
              Creates <b>{draft.name.trim()}</b> as version 1{draft.active ? ' and activates it immediately' : ' (inactive until you activate it)'}.
            </>
          ) : (
            <>
              Definition changes are saved as a new version; the previous versions stay available for rollback. Changes to the name, limits or node positions alone keep the current
              version.
            </>
          )
        }
        confirmLabel={isNew ? 'Save workflow' : 'Save version'}
        reasonLabel="Change note"
        pending={save.isPending}
        onConfirm={confirmSave}
      />
      {workflow && (
        <ConfirmDialog
          open={dialog === 'activate'}
          onClose={() => setDialog(null)}
          title={workflow.active ? `Deactivate ${workflow.name}?` : `Activate ${workflow.name}?`}
          message={
            workflow.active
              ? 'The workflow stops reacting to events. Its versions and run history are kept.'
              : `Saved version v${workflow.currentVersion} starts running on matching events${dirty ? '. Your unsaved changes are not included until you save' : ''}.`
          }
          confirmLabel={workflow.active ? 'Deactivate' : 'Activate'}
          tone={workflow.active ? 'danger' : 'gold'}
          pending={activate.isPending}
          error={activate.isError ? errorMessage(activate.error) : null}
          onConfirm={confirmActivate}
        />
      )}
      {workflow && dialog === 'test' && <TestWorkflowModal open onClose={() => setDialog(null)} workflow={workflow} meta={meta} dirty={dirty} />}
      {workflow && (
        <VersionsDrawer
          open={dialog === 'versions'}
          onClose={() => setDialog(null)}
          workflow={workflow}
          meta={meta}
          canEdit={canEdit}
          onRolledBack={(saved) => {
            setDialog(null);
            resetFrom(saved);
          }}
        />
      )}
      {canEdit && dialog === 'ai' && <AiDraftModal open onClose={() => setDialog(null)} meta={meta} onApply={applyAi} />}
    </>
  );
}

export default function WorkflowEditorPage() {
  const { id } = useParams();
  const location = useLocation();
  const [params] = useSearchParams();
  const isNew = id === undefined || id === 'new';
  const workflowId = isNew ? null : Number(id);
  const validId = isNew || (Number.isInteger(workflowId) && (workflowId ?? 0) > 0);
  const handoff = useMemo(() => (isNew ? readSuggestionState(location.state) : null), [isNew, location.state]);
  const templateKey = isNew && !handoff ? params.get('template') : null;

  const meta = useWorkflowMeta();
  const workflow = useWorkflow(validId ? workflowId : null);
  const templates = useWorkflowTemplates(Boolean(templateKey));

  if (!validId) {
    return (
      <EmptyState
        title="Workflow not found"
        description="The address does not point to a workflow."
        action={
          <Link className="subtle-btn compact" to="/automation">
            Back to Automation
          </Link>
        }
      />
    );
  }
  if (meta.isLoading || (!isNew && workflow.isLoading) || (templateKey && templates.isLoading)) {
    return <LoadingState variant="panel" label="Loading workflow builder…" />;
  }
  if (!meta.data) return <ErrorState error={meta.error} title="Could not load the workflow builder" onRetry={() => void meta.refetch()} />;
  if (!isNew && !workflow.data) return <ErrorState error={workflow.error} onRetry={() => void workflow.refetch()} />;

  if (workflow.data) {
    return <Builder key={`wf:${workflow.data.id}`} meta={meta.data} workflow={workflow.data} initialDraft={draftFromWorkflow(workflow.data)} initialOrigin={null} />;
  }
  if (handoff) {
    const draft = draftFromBlueprint(handoff.suggestion);
    return <Builder key="new:ai" meta={meta.data} initialDraft={draft} initialOrigin={{ kind: 'ai', suggestion: handoff.suggestion, ai: handoff.ai }} />;
  }
  if (templateKey) {
    const template = templates.data?.find((entry) => entry.key === templateKey);
    if (!template) {
      return templates.isError ? (
        <ErrorState error={templates.error} title="Could not load the template" onRetry={() => void templates.refetch()} />
      ) : (
        <EmptyState
          title="Template not found"
          description={`There is no workflow template called "${templateKey}".`}
          icon={<LayoutTemplate size={20} />}
          action={
            <Link className="subtle-btn compact" to="/automation?tab=templates">
              Browse templates
            </Link>
          }
        />
      );
    }
    return <Builder key={`new:template:${template.key}`} meta={meta.data} initialDraft={draftFromBlueprint(template)} initialOrigin={{ kind: 'template', name: template.name }} />;
  }
  return <Builder key="new" meta={meta.data} initialDraft={{ ...emptyDraft(), model: fromDefinition(null) }} initialOrigin={null} />;
}
