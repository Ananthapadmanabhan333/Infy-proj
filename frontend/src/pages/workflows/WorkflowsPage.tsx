import { useNavigate } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { useWorkflowMeta, useWorkflows, useWorkflowTemplates } from '../../api/workflows';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { RunHistory } from '../../components/workflow/RunHistory';
import '../../components/workflow/workflow.css';
import { TemplateGallery, WorkflowCards } from '../../components/workflow/WorkflowList';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { WORKFLOW_EDITORS } from '../../lib/constants';

type AutomationTab = 'workflows' | 'templates' | 'history';
const TABS: readonly AutomationTab[] = ['workflows', 'templates', 'history'];
const TAB_FILTERS = ['status', 'workflowId'] as const;

/** /automation — Workflow Automation (spec §6, §15.7): My Workflows, Templates, Run History. */
export default function WorkflowsPage() {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const canEdit = hasRole(...WORKFLOW_EDITORS);
  const [tab, setTab] = useTabParam(TABS, 'workflows', TAB_FILTERS);
  const workflows = useWorkflows();
  const templates = useWorkflowTemplates();
  const meta = useWorkflowMeta();

  return (
    <>
      <PageHeader
        title="Workflow Automation"
        subtitle="Design, deploy and manage IT workflows"
        actions={
          canEdit && (
            <button type="button" className="gold-btn" onClick={() => navigate('/automation/new')}>
              <Plus size={16} /> Create Workflow
            </button>
          )
        }
      />
      <div className="page-stack">
        <Tabs<AutomationTab>
          label="Automation views"
          value={tab}
          onChange={setTab}
          tabs={[
            { id: 'workflows', label: 'My Workflows', count: workflows.data?.length },
            { id: 'templates', label: 'Templates', count: templates.data?.length },
            { id: 'history', label: 'Run History' },
          ]}
        />
        <div role="tabpanel" aria-label={tab === 'workflows' ? 'My Workflows' : tab === 'templates' ? 'Templates' : 'Run History'}>
          {tab === 'workflows' &&
            (workflows.isLoading ? (
              <LoadingState variant="cards" rows={6} label="Loading workflows…" />
            ) : workflows.isError || !workflows.data ? (
              <ErrorState error={workflows.error} onRetry={() => void workflows.refetch()} />
            ) : (
              <WorkflowCards workflows={workflows.data} meta={meta.data} canEdit={canEdit} />
            ))}
          {tab === 'templates' &&
            (templates.isLoading ? (
              <LoadingState variant="cards" rows={4} label="Loading templates…" />
            ) : templates.isError || !templates.data ? (
              <ErrorState error={templates.error} onRetry={() => void templates.refetch()} />
            ) : (
              <TemplateGallery templates={templates.data} meta={meta.data} canEdit={canEdit} />
            ))}
          {tab === 'history' && (
            <section className="panel" aria-label="Run history">
              <RunHistory workflows={workflows.data ?? []} meta={meta.data} canRetry={canEdit} />
            </section>
          )}
        </div>
      </div>
    </>
  );
}
