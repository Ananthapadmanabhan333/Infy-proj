import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useAutoAssignIncident, useEscalateIncident, useIncident, useSetMajor, useTransitionIncident } from '../../api/incidents';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState, LoadingState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useToast } from '../../components/Toast';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { errorMessage, toApiError } from '../../lib/api';
import { isCancelTransition, READERS, STAFF, STATUS_LABELS, transitionNeedsReason } from '../../lib/constants';
import type { ArticleSummary, IncidentDetail, IncidentStatus } from '../../lib/types';
import { ActivityPanel } from './detail/ActivityPanel';
import { IncidentAiPanel } from './detail/AiPanel';
import { AiRecommendationCard } from './detail/AiRecommendationCard';
import { IncidentHeader, IncidentProgress, type HeaderAction } from './detail/IncidentHeader';
import { AddLinkDialog, AssignDialog, EditIncidentDialog, LinkParentDialog, ResolveDialog } from './detail/IncidentDialogs';
import { OverviewTab } from './detail/OverviewTab';
import { relatedCount, RelationsPanel } from './detail/RelationsPanel';
import { RunbooksPanel } from './detail/RunbooksPanel';
import { MajorCommandCard } from './detail/MajorCommandCard';
import { RunbookRecommendationsPanel, SlaRiskAiPanel } from '../../components/ai/OpsAiPanels';
import { SlaPanel } from './detail/SlaPanel';
import './incidents.css';

type DetailTab = 'overview' | 'activity' | 'related' | 'ai';
const DETAIL_TABS: DetailTab[] = ['overview', 'activity', 'related', 'ai'];
const TAB_LABELS: Record<DetailTab, string> = { overview: 'Overview', activity: 'Activity', related: 'Related', ai: 'AI Recommendation' };

type DialogState =
  | { kind: 'resolve'; article?: { id: number; number: string; title: string } | null }
  | { kind: 'reason'; status: IncidentStatus; cancel: boolean }
  | { kind: HeaderAction }
  | null;

function IncidentView({ incident }: { incident: IncidentDetail }) {
  const { user, hasRole } = useAuth();
  const toast = useToast();
  const [tab, setTab] = useTabParam<DetailTab>(DETAIL_TABS, 'overview');
  // Tab panels mount on first visit and then stay mounted, so AI results and filters survive tab switches.
  const [visited, setVisited] = useState<Set<DetailTab>>(() => new Set([tab]));
  useEffect(() => setVisited((current) => (current.has(tab) ? current : new Set(current).add(tab))), [tab]);

  const [dialog, setDialog] = useState<DialogState>(null);
  const transition = useTransitionIncident(incident.id);
  const autoAssign = useAutoAssignIncident(incident.id);
  const escalate = useEscalateIncident(incident.id);
  const setMajor = useSetMajor(incident.id);
  const queryClient = useQueryClient();

  const staff = hasRole(...STAFF);
  const reader = hasRole(...READERS);
  const readOnly = user?.role === 'AUDITOR';
  const canResolve = incident.allowedTransitions.includes('RESOLVED');
  const close = () => setDialog(null);

  const runTransition = (status: IncidentStatus) => {
    if (status === 'RESOLVED') {
      setDialog({ kind: 'resolve' });
      return;
    }
    if (transitionNeedsReason(incident.status, status)) {
      transition.reset();
      setDialog({ kind: 'reason', status, cancel: isCancelTransition(incident.status, status) });
      return;
    }
    transition.mutate(
      { status },
      {
        onSuccess: () => toast.success(`${incident.number} → ${STATUS_LABELS[status]}`),
        onError: (error) => toast.error(error),
      },
    );
  };

  const autoRoute = () =>
    autoAssign.mutate(undefined, {
      onSuccess: (data) => toast.success(data?.assigneeName ? `Auto-assigned to ${data.assigneeName}` : 'Auto-assignment complete'),
      onError: (error) => toast.error(error),
    });

  const openAction = (action: HeaderAction) => {
    if (action === 'escalate') escalate.reset();
    if (action === 'major') setMajor.reset();
    setDialog({ kind: action });
  };

  const chooseArticle = (article: ArticleSummary) => setDialog({ kind: 'resolve', article: { id: article.id, number: article.number, title: article.title } });
  const related = relatedCount(incident);

  return (
    <>
      <IncidentHeader
        incident={incident}
        onTransition={runTransition}
        onAction={openAction}
        onAutoRoute={autoRoute}
        busy={transition.isPending || autoAssign.isPending}
      />
      <IncidentProgress incident={incident} />

      <div className="page-stack">
        <Tabs<DetailTab>
          label="Incident sections"
          value={tab}
          onChange={setTab}
          tabs={DETAIL_TABS.map((id) => ({ id, label: TAB_LABELS[id], count: id === 'related' ? related : undefined }))}
        />

        <div className="incident-layout">
          <div className="incident-main">
            {DETAIL_TABS.filter((id) => visited.has(id) || id === tab).map((id) => (
              <div key={id} role="tabpanel" aria-label={TAB_LABELS[id]} hidden={id !== tab} className="incident-tabpanel">
                {id === 'overview' && <OverviewTab incident={incident} readOnly={readOnly} />}
                {id === 'activity' && <ActivityPanel incident={incident} />}
                {id === 'related' && (
                  <RelationsPanel incident={incident} staff={staff} onLinkParent={() => openAction('parent')} onAddLink={() => openAction('link')} />
                )}
                {id === 'ai' && (
                  <IncidentAiPanel incident={incident} staff={staff} canSummarize={reader} canResolve={canResolve} onUseArticle={chooseArticle} />
                )}
              </div>
            ))}
          </div>
          <aside className="incident-side" aria-label="Recommendation and service level">
            {reader && <MajorCommandCard incident={incident} />}
            {staff && <AiRecommendationCard incident={incident} canResolve={canResolve} onUseArticle={chooseArticle} />}
            <SlaPanel incident={incident} />
            {reader && incident.sla && <SlaRiskAiPanel incidentId={incident.id} />}
            {reader && <RunbooksPanel incident={incident} canRun={staff} />}
            {staff && <RunbookRecommendationsPanel incidentId={incident.id} />}
          </aside>
        </div>
      </div>

      <ResolveDialog incident={incident} open={dialog?.kind === 'resolve'} onClose={close} initialArticle={dialog?.kind === 'resolve' ? dialog.article : null} />
      <AssignDialog incident={incident} open={dialog?.kind === 'assign'} onClose={close} />
      <EditIncidentDialog incident={incident} open={dialog?.kind === 'edit'} onClose={close} />
      <LinkParentDialog incident={incident} open={dialog?.kind === 'parent'} onClose={close} />
      <AddLinkDialog incident={incident} open={dialog?.kind === 'link'} onClose={close} />
      <ConfirmDialog
        open={dialog?.kind === 'reason'}
        onClose={close}
        title={dialog?.kind === 'reason' && dialog.cancel ? `Cancel ${incident.number}?` : `Set ${incident.number} to pending?`}
        message={
          dialog?.kind === 'reason' && dialog.cancel
            ? 'The incident is closed with resolution code Cancelled; its SLA clock stops and it leaves the queue.'
            : 'The SLA clock pauses while the incident is pending. The reason is added to the conversation.'
        }
        reasonLabel="Reason"
        reasonRequired
        confirmLabel={dialog?.kind === 'reason' && dialog.cancel ? 'Confirm cancellation' : 'Set pending'}
        tone={dialog?.kind === 'reason' && dialog.cancel ? 'danger' : 'gold'}
        pending={transition.isPending}
        error={transition.isError ? errorMessage(transition.error) : null}
        onConfirm={(reason) =>
          dialog?.kind === 'reason' &&
          transition.mutate(
            dialog.cancel
              ? { status: dialog.status, resolutionCode: 'CANCELLED', resolutionNotes: reason, comment: reason }
              : { status: dialog.status, comment: reason },
            {
              onSuccess: () => {
                toast.success(dialog.cancel ? `${incident.number} cancelled` : `${incident.number} → ${STATUS_LABELS[dialog.status]}`);
                close();
              },
            },
          )
        }
      />
      <ConfirmDialog
        open={dialog?.kind === 'escalate'}
        onClose={close}
        title={`Escalate ${incident.number}`}
        message="Escalation raises the escalation level, is recorded in the escalation history and notifies the owning team's manager."
        reasonLabel="Reason"
        reasonRequired
        confirmLabel="Escalate"
        tone="danger"
        pending={escalate.isPending}
        error={escalate.isError ? errorMessage(escalate.error) : null}
        onConfirm={(reason) =>
          escalate.mutate(reason, {
            onSuccess: () => {
              toast.success(`${incident.number} escalated`);
              close();
            },
          })
        }
      />
      <ConfirmDialog
        open={dialog?.kind === 'major'}
        onClose={close}
        title={incident.major ? 'Remove major incident flag?' : `Declare ${incident.number} a major incident?`}
        message={incident.major ? 'The incident returns to normal handling.' : 'Major incidents are broadcast to managers and trigger the major-incident workflows.'}
        confirmLabel={incident.major ? 'Remove flag' : 'Declare major incident'}
        tone={incident.major ? 'gold' : 'danger'}
        pending={setMajor.isPending}
        error={setMajor.isError ? errorMessage(setMajor.error) : null}
        onConfirm={() =>
          setMajor.mutate(!incident.major, {
            onSuccess: () => {
              toast.success(incident.major ? 'Major flag removed' : 'Declared major incident');
              void queryClient.invalidateQueries({ queryKey: ['major-incidents'] });
              close();
            },
          })
        }
      />
    </>
  );
}

export default function IncidentDetailPage() {
  const { id } = useParams();
  const incidentId = Number(id);
  const incident = useIncident(incidentId);
  const crumbs = [{ label: 'Incidents', to: '/incidents' }, { label: id ? `#${id}` : 'Incident' }];

  if (!Number.isFinite(incidentId) || incidentId <= 0) {
    return (
      <>
        <PageHeader title="Incident not found" crumbs={crumbs} />
        <ErrorState error={new Error('The incident link is not valid.')} title="Incident not found" />
      </>
    );
  }
  if (incident.isLoading) return <LoadingState variant="panel" label="Loading incident…" />;
  if (incident.isError && !incident.data) {
    const notFound = toApiError(incident.error).status === 404;
    return (
      <>
        <PageHeader title={notFound ? 'Incident not found' : 'Incident unavailable'} crumbs={crumbs} />
        <ErrorState error={incident.error} onRetry={() => void incident.refetch()} title={notFound ? 'This incident does not exist or you cannot access it' : undefined} />
      </>
    );
  }
  if (!incident.data) return null;
  return <IncidentView incident={incident.data} />;
}
