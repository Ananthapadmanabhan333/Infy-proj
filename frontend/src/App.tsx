import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { ProtectedRoute } from './components/auth/ProtectedRoute';
import { BrandLoader } from './components/BrandLoader';
import { APPROVAL_FLOW_EDITORS, READERS, WORKFLOW_EDITORS } from './lib/constants';
import { rolesFor } from './lib/nav';
import type { Role } from './lib/types';
import { GuestRoute } from './components/auth/GuestRoute';

// Lazy so the public landing page does not download the workspace shell or the forms library.
const AppShell = lazy(() => import('./components/layout/AppShell').then((m) => ({ default: m.AppShell })));
const LoginPage = lazy(() => import('./pages/auth/LoginPage'));

const RegisterPage = lazy(() => import('./pages/auth/RegisterPage'));
const ForgotPasswordPage = lazy(() => import('./pages/auth/ForgotPasswordPage'));
const ResetPasswordPage = lazy(() => import('./pages/auth/ResetPasswordPage'));

// Public pre-login pages (PUBLIC_ACCESS_SPEC §1) — separate chunks, no Recharts, no app shell.
const LandingPage = lazy(() => import('./pages/public/LandingPage'));
const DocsPage = lazy(() => import('./pages/public/DocsPage'));
const SupportPage = lazy(() => import('./pages/public/SupportPage'));
const PrivacyPage = lazy(() => import('./pages/public/PrivacyPage'));
const TermsPage = lazy(() => import('./pages/public/TermsPage'));
const StatusPage = lazy(() => import('./pages/public/StatusPage'));
const StatusSubscriptionPage = lazy(() => import('./pages/public/StatusSubscriptionPage'));

const CommandCenter = lazy(() => import('./pages/CommandCenter'));
const IncidentsPage = lazy(() => import('./pages/incidents/IncidentsPage'));
const IncidentDetailPage = lazy(() => import('./pages/incidents/IncidentDetailPage'));
const ServiceDeskPage = lazy(() => import('./pages/ServiceDeskPage'));
const CatalogPage = lazy(() => import('./pages/CatalogPage'));
const ServicesPage = lazy(() => import('./pages/ServicesPage'));
const ServiceDetailPage = lazy(() => import('./pages/services/ServiceDetailPage'));
const SlaMonitorPage = lazy(() => import('./pages/SlaMonitorPage'));
const AiPage = lazy(() => import('./pages/AiPage'));
const AnalyticsPage = lazy(() => import('./pages/AnalyticsPage'));
const WorkflowsPage = lazy(() => import('./pages/workflows/WorkflowsPage'));
const WorkflowEditorPage = lazy(() => import('./pages/workflows/WorkflowEditorPage'));
const RunbooksPage = lazy(() => import('./pages/runbooks/RunbooksPage'));
const RunbookDetailPage = lazy(() => import('./pages/runbooks/RunbookDetailPage'));
const IntegrationsPage = lazy(() => import('./pages/IntegrationsPage'));
const KnowledgePage = lazy(() => import('./pages/knowledge/KnowledgePage'));
const ArticlePage = lazy(() => import('./pages/knowledge/ArticlePage'));
const AnnouncementsPage = lazy(() => import('./pages/AnnouncementsPage'));
const NotificationsPage = lazy(() => import('./pages/NotificationsPage'));
const ProfilePage = lazy(() => import('./pages/ProfilePage'));
const UsersPage = lazy(() => import('./pages/admin/UsersPage'));
const SettingsPage = lazy(() => import('./pages/admin/SettingsPage'));
const AuditLogsPage = lazy(() => import('./pages/admin/AuditLogsPage'));
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'));
// Release 3 foundations
const MyWorkPage = lazy(() => import('./pages/workspace/MyWorkPage'));
const PortalPage = lazy(() => import('./pages/workspace/PortalPage'));
const TeamsPage = lazy(() => import('./pages/teams/TeamsPage'));
const StatusPageAdminPage = lazy(() => import('./pages/status/StatusPageAdminPage'));
// Release 3 BE-2 modules: approvals, problems, changes, deployments
const ApprovalsPage = lazy(() => import('./pages/approvals/ApprovalsPage'));
const ApprovalFlowsPage = lazy(() => import('./pages/approvals/ApprovalFlowsPage'));
const ApprovalFlowEditorPage = lazy(() => import('./pages/approvals/ApprovalFlowEditorPage'));
const ProblemsPage = lazy(() => import('./pages/problems/ProblemsPage'));
const ProblemDetailPage = lazy(() => import('./pages/problems/ProblemDetailPage'));
const ChangesPage = lazy(() => import('./pages/changes/ChangesPage'));
const ChangeDetailPage = lazy(() => import('./pages/changes/ChangeDetailPage'));
const DeploymentsPage = lazy(() => import('./pages/deployments/DeploymentsPage'));
const DeploymentDetailPage = lazy(() => import('./pages/deployments/DeploymentDetailPage'));
// Release 3 BE-3 modules: alerts, CMDB, assets
const AlertsPage = lazy(() => import('./pages/alerts/AlertsPage'));
const AlertDetailPage = lazy(() => import('./pages/alerts/AlertDetailPage'));
const CmdbPage = lazy(() => import('./pages/cmdb/CmdbPage'));
const CiDetailPage = lazy(() => import('./pages/cmdb/CiDetailPage'));
const AssetsPage = lazy(() => import('./pages/assets/AssetsPage'));
const AssetDetailPage = lazy(() => import('./pages/assets/AssetDetailPage'));
// Release 3 BE-4 modules: on-call, major incident command, post-incident reviews
const OnCallPage = lazy(() => import('./pages/oncall/OnCallPage'));
const ScheduleDetailPage = lazy(() => import('./pages/oncall/ScheduleDetailPage'));
const MajorIncidentsPage = lazy(() => import('./pages/major/MajorIncidentsPage'));
const MajorIncidentDetailPage = lazy(() => import('./pages/major/MajorIncidentDetailPage'));
const ReviewsPage = lazy(() => import('./pages/reviews/ReviewsPage'));
const ReviewDetailPage = lazy(() => import('./pages/reviews/ReviewDetailPage'));
// Release 4 FE-6: observability and digital twin
const ObservabilityPage = lazy(() => import('./pages/observability/ObservabilityPage'));
const DigitalTwinPage = lazy(() => import('./pages/twin/DigitalTwinPage'));
// Release 4 FE-7: predictive intelligence and agentic AI
const PredictionsPage = lazy(() => import('./pages/predictions/PredictionsPage'));
const AgentsPage = lazy(() => import('./pages/agents/AgentsPage'));
// Release 4 FE-8 & FE-9: autonomous remediation, security operations, FinOps and digital employee experience
const RemediationPage = lazy(() => import('./pages/remediation/RemediationPage'));
const SecurityPage = lazy(() => import('./pages/security/SecurityPage'));
const FinOpsPage = lazy(() => import('./pages/finops/FinOpsPage'));
const DexPage = lazy(() => import('./pages/dex/DexPage'));

/**
 * Redirect that keeps the current query string and merges the target's own parameters
 * (e.g. `/forecast?period=30` → `/analytics?tab=operations&period=30`). `:param` tokens in `to`
 * are filled from the matched route (e.g. `/workflows/:id` → `/automation/:id`).
 */
export function LegacyRedirect({ to }: { to: string }) {
  const location = useLocation();
  const params = useParams();
  const [pathTemplate, targetQuery = ''] = to.split('?');
  const pathname = pathTemplate.replace(/:(\w+)/g, (_, key: string) => encodeURIComponent(params[key] ?? ''));
  const search = new URLSearchParams(location.search);
  new URLSearchParams(targetQuery).forEach((value, key) => search.set(key, value));
  const query = search.toString();
  return <Navigate to={{ pathname, search: query ? `?${query}` : '', hash: location.hash }} replace />;
}

/** Old paths (Release 1) → current routes (spec §14). `/catalog` is a real page again since Release 3. */
export const LEGACY_REDIRECTS: [from: string, to: string][] = [
  ['/sla', '/sla-monitor'],
  ['/ai', '/sa-ai'],
  ['/nexus-ai', '/sa-ai'], // renamed product (NEXUS → SA); keeps old links working
  ['/workflows', '/automation'],
  ['/workflows/:id', '/automation/:id'],
  ['/admin/users', '/users'],
  ['/admin/audit-logs', '/audit-logs'],
  ['/admin/configuration', '/settings'],
  ['/forecast', '/analytics?tab=operations'],
  ['/solutions', '/knowledge?type=SOLUTION'],
  ['/oncall', '/on-call'], // the API path spelling
  ['/oncall/:scheduleId', '/on-call/:scheduleId'],
];

function Guard({ roles, children }: { roles: Role[]; children: JSX.Element }) {
  return <ProtectedRoute roles={roles}>{children}</ProtectedRoute>;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/docs" element={<DocsPage />} />
      <Route path="/support" element={<SupportPage />} />
      <Route path="/privacy" element={<PrivacyPage />} />
      <Route path="/terms" element={<TermsPage />} />
      <Route path="/status" element={<StatusPage />} />
      <Route path="/status/confirm" element={<StatusSubscriptionPage action="confirm" />} />
      <Route path="/status/unsubscribe" element={<StatusSubscriptionPage action="unsubscribe" />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<GuestRoute><RegisterPage /></GuestRoute>} />
      <Route path="/forgot-password" element={<GuestRoute><ForgotPasswordPage /></GuestRoute>} />
      <Route path="/reset-password" element={<GuestRoute><ResetPasswordPage /></GuestRoute>} />
      {LEGACY_REDIRECTS.map(([from, to]) => (
        <Route key={from} path={from} element={<LegacyRedirect to={to} />} />
      ))}
      <Route
        element={
          <ProtectedRoute>
            <AppShell />
          </ProtectedRoute>
        }
      >
        <Route path="dashboard" element={<CommandCenter />} />
        <Route path="my-work" element={<Guard roles={rolesFor('/my-work')}><MyWorkPage /></Guard>} />
        <Route path="portal" element={<Guard roles={rolesFor('/portal')}><PortalPage /></Guard>} />
        <Route path="incidents" element={<IncidentsPage />} />
        <Route path="incidents/:id" element={<IncidentDetailPage />} />
        <Route path="service-desk" element={<ServiceDeskPage />} />
        <Route path="catalog" element={<CatalogPage />} />
        <Route path="services" element={<ServicesPage />} />
        <Route path="services/:id" element={<ServiceDetailPage />} />
        <Route path="sla-monitor" element={<Guard roles={rolesFor('/sla-monitor')}><SlaMonitorPage /></Guard>} />
        <Route path="sa-ai" element={<Guard roles={rolesFor('/sa-ai')}><AiPage /></Guard>} />
        <Route path="automation" element={<Guard roles={rolesFor('/automation')}><WorkflowsPage /></Guard>} />
        <Route path="automation/new" element={<Guard roles={WORKFLOW_EDITORS}><WorkflowEditorPage /></Guard>} />
        <Route path="automation/:id" element={<Guard roles={READERS}><WorkflowEditorPage /></Guard>} />
        <Route path="analytics" element={<Guard roles={rolesFor('/analytics')}><AnalyticsPage /></Guard>} />
        <Route path="knowledge" element={<KnowledgePage mode="all" />} />
        <Route path="knowledge/:id" element={<ArticlePage />} />
        <Route path="runbooks" element={<Guard roles={READERS}><RunbooksPage /></Guard>} />
        <Route path="runbooks/:id" element={<Guard roles={READERS}><RunbookDetailPage /></Guard>} />
        <Route path="notifications" element={<NotificationsPage />} />
        <Route path="users" element={<Guard roles={rolesFor('/users')}><UsersPage /></Guard>} />
        <Route path="audit-logs" element={<Guard roles={rolesFor('/audit-logs')}><AuditLogsPage /></Guard>} />
        <Route path="status-page" element={<Guard roles={rolesFor('/status-page')}><StatusPageAdminPage /></Guard>} />
        <Route path="teams" element={<Guard roles={rolesFor('/teams')}><TeamsPage /></Guard>} />
        <Route path="approvals" element={<Guard roles={rolesFor('/approvals')}><ApprovalsPage /></Guard>} />
        {/* Approval flows: readers view (the API's readers), approval-flow editors create and edit. */}
        <Route path="approvals/flows" element={<Guard roles={READERS}><ApprovalFlowsPage /></Guard>} />
        <Route path="approvals/flows/new" element={<Guard roles={APPROVAL_FLOW_EDITORS}><ApprovalFlowEditorPage /></Guard>} />
        <Route path="approvals/flows/:id" element={<Guard roles={READERS}><ApprovalFlowEditorPage /></Guard>} />
        <Route path="problems" element={<Guard roles={rolesFor('/problems')}><ProblemsPage /></Guard>} />
        <Route path="problems/:id" element={<Guard roles={rolesFor('/problems')}><ProblemDetailPage /></Guard>} />
        <Route path="changes" element={<Guard roles={rolesFor('/changes')}><ChangesPage /></Guard>} />
        <Route path="changes/calendar" element={<Guard roles={rolesFor('/changes')}><ChangesPage view="calendar" /></Guard>} />
        <Route path="changes/freeze-windows" element={<Guard roles={rolesFor('/changes')}><ChangesPage view="freezes" /></Guard>} />
        <Route path="changes/:id" element={<Guard roles={rolesFor('/changes')}><ChangeDetailPage /></Guard>} />
        <Route path="deployments" element={<Guard roles={rolesFor('/deployments')}><DeploymentsPage /></Guard>} />
        <Route path="deployments/:id" element={<Guard roles={rolesFor('/deployments')}><DeploymentDetailPage /></Guard>} />
        <Route path="alerts" element={<Guard roles={rolesFor('/alerts')}><AlertsPage /></Guard>} />
        <Route path="alerts/:id" element={<Guard roles={rolesFor('/alerts')}><AlertDetailPage /></Guard>} />
        <Route path="cmdb" element={<Guard roles={rolesFor('/cmdb')}><CmdbPage /></Guard>} />
        <Route path="cmdb/:id" element={<Guard roles={rolesFor('/cmdb')}><CiDetailPage /></Guard>} />
        <Route path="assets" element={<Guard roles={rolesFor('/assets')}><AssetsPage /></Guard>} />
        <Route path="assets/:id" element={<Guard roles={rolesFor('/assets')}><AssetDetailPage /></Guard>} />
        <Route path="on-call" element={<Guard roles={rolesFor('/on-call')}><OnCallPage /></Guard>} />
        <Route path="on-call/:scheduleId" element={<Guard roles={rolesFor('/on-call')}><ScheduleDetailPage /></Guard>} />
        <Route path="major-incidents" element={<Guard roles={rolesFor('/major-incidents')}><MajorIncidentsPage /></Guard>} />
        <Route path="major-incidents/:id" element={<Guard roles={rolesFor('/major-incidents')}><MajorIncidentDetailPage /></Guard>} />
        <Route path="reviews" element={<Guard roles={rolesFor('/major-incidents')}><ReviewsPage /></Guard>} />
        <Route path="reviews/:id" element={<Guard roles={rolesFor('/major-incidents')}><ReviewDetailPage /></Guard>} />
        <Route path="observability" element={<Guard roles={rolesFor('/observability')}><ObservabilityPage /></Guard>} />
        <Route path="digital-twin" element={<Guard roles={rolesFor('/digital-twin')}><DigitalTwinPage /></Guard>} />
        <Route path="predictions" element={<Guard roles={rolesFor('/predictions')}><PredictionsPage /></Guard>} />
        <Route path="agents" element={<Guard roles={rolesFor('/agents')}><AgentsPage /></Guard>} />
        <Route path="remediation" element={<Guard roles={rolesFor('/remediation')}><RemediationPage /></Guard>} />
        <Route path="security" element={<Guard roles={rolesFor('/security')}><SecurityPage /></Guard>} />
        <Route path="finops" element={<Guard roles={rolesFor('/finops')}><FinOpsPage /></Guard>} />
        <Route path="dex" element={<Guard roles={rolesFor('/dex')}><DexPage /></Guard>} />
        <Route path="integrations" element={<Guard roles={rolesFor('/integrations')}><IntegrationsPage /></Guard>} />
        <Route path="settings" element={<Guard roles={rolesFor('/settings')}><SettingsPage /></Guard>} />
        <Route path="profile" element={<ProfilePage />} />
        <Route path="announcements" element={<AnnouncementsPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <Suspense fallback={<BrandLoader message="Loading SA…" />}>
      <AppRoutes />
    </Suspense>
  );
}
