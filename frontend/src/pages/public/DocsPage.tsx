import { Link } from 'react-router-dom';
import { ArrowRight, Info } from 'lucide-react';
import { ContentPage, type ContentSection } from '../../components/public/ContentPage';
import { useAuth } from '../../hooks/useAuth';
import { usePageMeta } from '../../hooks/usePageMeta';

const SECTIONS: ContentSection[] = [
  { id: 'getting-started', label: 'Getting started' },
  { id: 'roles', label: 'Roles & access' },
  { id: 'modules', label: 'Modules' },
  { id: 'ai-transparency', label: 'AI transparency' },
  { id: 'security', label: 'Sessions & security' },
  { id: 'help', label: 'Need help?' },
];

/** Role summaries (docs/RELEASE2_SPEC.md §1, RELEASE3_SPEC §2). Enforced by the API on every request. */
const ROLES: [role: string, summary: string][] = [
  ['System Admin', 'Everything, including organisation, AI and system settings and the demo tools.'],
  [
    'IT Admin',
    'IT configuration — users and departments (cannot create, edit or grant System Admin), services, catalogue, SLA policies, calendars, incident categories, integrations, workflows, runbooks, knowledge publishing — plus all staff incident powers.',
  ],
  ['IT Manager', 'Team and department management: approves service requests; workflows, analytics, exports and major incidents.'],
  [
    'Incident Manager',
    'Incident command: major incidents, escalation and cross-team assignment; approves runbook executions, sensitive runbook steps and AI actions; analytics and exports.',
  ],
  ['Change Manager', 'Change enablement: changes, the change calendar, CAB approvals, deployments and releases — plus the staff powers to work tickets.'],
  ['Asset Manager', 'Asset and configuration management: assets, the CMDB, software licences, import and export — plus the staff powers to work tickets.'],
  ['Support Agent', 'Works incidents and service requests.'],
  ['Employee', 'Reports incidents, raises service requests and reads published knowledge.'],
  ['Auditor', 'Read-only compliance access: operational data, audit logs and exports.'],
  ['Security Analyst', 'Security operations: reads operational data, observability telemetry and the digital twin (runs read-only impact simulations); not incident staff.'],
  ['FinOps Analyst', 'Cloud cost management: reads operational data, observability telemetry and the digital twin (runs read-only impact simulations); not incident staff.'],
];

/** One row per sidebar module (`lib/nav.ts`); `DocsPage` tests keep the two in step. */
export const MODULES: [module: string, route: string, summary: string][] = [
  ['Command Center', '/dashboard', 'Live KPIs with deltas, trend with statistical forecast, service health, alerts, on-call now, change and workflow activity; a self-service home for employees.'],
  ['My Work', '/my-work', 'Everything assigned to you: incidents, problems, changes, approvals, alerts, on-call shifts and action items.'],
  ['Employee Portal', '/portal', 'Report an issue, request a service, follow your requests and assets, and read published knowledge.'],
  ['Service Desk', '/service-desk', 'Requests, my requests and approvals with segregation of duties and fulfilment.'],
  ['Service Catalogue', '/catalog', 'Request items with dynamic forms and approval flows.'],
  ['Incidents', '/incidents', 'Status tabs with live counts, filters, SLA countdowns and bulk actions; intake from classification to SLA start.'],
  ['Major Incidents', '/major-incidents', 'Command view with roles, war-room timeline, stakeholder updates, status-page publication, action items and post-incident reviews.'],
  ['Problems', '/problems', 'Root-cause investigation, known errors, linked incidents and configuration items.'],
  ['Changes', '/changes', 'Change requests with risk assessment, CAB approval, schedule conflicts and freeze windows.'],
  ['Alerts', '/alerts', 'Monitoring alerts from Prometheus Alertmanager, Grafana, AWS CloudWatch and signed JSON; de-duplication, suppression, routing and conversion to incidents.'],
  ['On-call', '/on-call', "Rotations in each team's time zone, overrides, holidays, availability, handovers, escalation policies and the page log."],
  ['Services', '/services', 'Service catalogue with owners, tiers, cycle-safe dependencies, availability, linked incidents and SLA performance.'],
  ['CMDB', '/cmdb', 'Configuration items, relationships, a visual dependency map and impact analysis.'],
  ['Assets', '/assets', 'Asset lifecycle, assignment, warranties, maintenance and CSV import with a dry run.'],
  ['Deployments', '/deployments', 'CI/CD deployments from signed GitHub and GitLab webhooks, linked to changes and services.'],
  ['Observability', '/observability', 'OTLP/HTTP JSON and Prometheus telemetry: metrics explorer, logs, traces, service RED summaries, statistical anomaly detection with measured precision/recall, and alert correlation groups.'],
  ['Digital Twin', '/digital-twin', 'Layered model of business services, applications and infrastructure built from the CMDB, with live state, failure and change impact simulation and a unified change timeline.'],
  ['Predictions', '/predictions', 'Trained and calibrated SLA breach, incident recurrence, capacity saturation and service health forecasting models with offline evaluation, live accuracy and drift warnings.'],
  ['SA Agents', '/agents', 'Specialist operations agents with read-only user-scoped tools, schema-validated outputs, deterministic fallbacks, approval-gated action proposals and evaluation suites.'],
  ['Remediation', '/remediation', 'Policy-gated autonomous remediation playbooks with allow-listed action templates, automatic rollback on failure and a SHA-256 tamper-evident audit chain.'],
  ['Security Operations', '/security', 'Security incidents with response checklists, multi-format vulnerability ingestion (GENERIC_JSON, SARIF, TRIVY_JSON, CSV), composite risk prioritisation and security alert correlation.'],
  ['FinOps', '/finops', 'FOCUS 1.x cloud cost ingestion with dry-run validation, tag and pattern allocation rules, monthly budgets with linear forecasts, EWMA/MAD cost anomalies and right-sizing savings recommendations.'],
  ['Employee Experience', '/dex', 'Digital Employee Experience (DEX) 0–100 composite scores across endpoint device health, application performance, sign-in reliability and service experience analytics.'],
  ['SA AI', '/sa-ai', 'Analyze, predict, recommend and trend tools and grounded chat — LLM or a labelled deterministic fallback.'],
  ['Workflows', '/automation', 'Visual builder (trigger → condition → Yes / No), templates, run history, versions, dry-run tests and retries.'],
  ['Runbooks', '/runbooks', 'Prerequisites, ordered steps with sensitive-step approval and execution history.'],
  ['Knowledge Base', '/knowledge', 'Full-text search, draft → review → published workflow, versions and ratings.'],
  ['SLA Monitor', '/sla-monitor', 'Compliance, breaches, at-risk queue and trends; business-time clocks with pause / resume and escalation.'],
  ['Approvals', '/approvals', 'One inbox for service-request, change, runbook, AI-action and post-incident-review approvals.'],
  ['Analytics', '/analytics', 'Operations metrics (MTTA, MTTD, change success, alert volume, on-call performance…), reports, scheduled reports, CSV and PDF export.'],
  ['Audit Logs', '/audit-logs', 'Append-only record of sign-ins, denials, changes, AI actions and exports; filters and CSV export.'],
  ['Status Page', '/status-page', 'Service status, incidents and maintenance published to the public status page.'],
  ['Users', '/users', 'Users, departments and roles; access requests and invitations.'],
  ['Teams', '/teams', 'Teams, members, routing and team leads.'],
  ['Integrations', '/integrations', 'Signed outbound webhooks, Slack and Teams channels, alert sources and a delivery log.'],
  ['Settings', '/settings', 'Organisation, SLA policies, business hours, categories, notification defaults, e-mail, AI configuration and system health.'],
];

export default function DocsPage() {
  usePageMeta(
    'SA | Documentation',
    'SA product documentation: getting an account, the eleven roles, every module, how SA AI answers and how sessions are secured.',
  );
  const { status, user } = useAuth();
  const signedIn = status === 'authenticated' && Boolean(user);

  return (
    <ContentPage
      kicker="Documentation"
      title="SA product overview"
      lead="How to get access, what each role can do, what each module contains and how SA AI decides what to say."
      sections={SECTIONS}
      heroExtra={
        <div className="pub-btn-row">
          <Link to={signedIn ? '/dashboard' : '/login'} className="pub-btn pub-btn-gold">
            {signedIn ? 'Open Workspace' : 'Sign in'} <ArrowRight size={16} aria-hidden="true" />
          </Link>
          {!signedIn && (
            <Link to="/register" className="pub-btn pub-btn-outline">
              Request access
            </Link>
          )}
        </div>
      }
    >
      <section id="getting-started" aria-labelledby="getting-started-title">
        <h2 id="getting-started-title">Getting started</h2>
        <div className="pub-callout">
          <Info size={18} aria-hidden="true" />
          <p>
            <strong>Accounts are administrator-controlled.</strong> There is no open self-registration. Invitation and reset
            links are shown once to an administrator, who hands them to you through a trusted channel; when the deployment has
            e-mail configured, they are also e-mailed to you.
          </p>
        </div>
        <ol>
          <li>
            <strong>Ask for access.</strong> Submit a request at <Link to="/register">Request access</Link>. A user
            administrator (System Admin or IT Admin) reviews it under <em>Users → Access requests</em>.
          </li>
          <li>
            <strong>Accept your invitation.</strong> An approved request produces a single-use invitation link (valid for
            7 days). Open it, confirm your name and choose a password.
          </li>
          <li>
            <strong>Sign in</strong> at <Link to="/login">/login</Link> with your e-mail address and password. After five
            failed attempts the account is locked for 15 minutes.
          </li>
          <li>
            <strong>Forgot your password?</strong> Use <Link to="/forgot-password">Forgot password</Link>. An administrator
            creates a single-use reset link (valid for 2 hours) for you.
          </li>
          <li>
            <strong>First steps.</strong> Employees land on a self-service home: report an issue, follow their incidents and
            requests, search the knowledge base and check service status. Staff land on the Operations Command Center with
            live KPIs, trends and their queue.
          </li>
        </ol>
      </section>

      <section id="roles" aria-labelledby="roles-title">
        <h2 id="roles-title">Roles &amp; access</h2>
        <p>
          Every account has exactly one of eleven roles. Access is enforced by the API on every request (URL rules plus
          per-method checks); the interface only hides what the server would refuse. Role changes and access denials are
          written to the audit log.
        </p>
        <div className="pub-table-wrap">
          <table className="pub-table">
            <caption className="pub-sr-only">The eleven SA roles</caption>
            <thead>
              <tr>
                <th scope="col">Role</th>
                <th scope="col">What it can do</th>
              </tr>
            </thead>
            <tbody>
              {ROLES.map(([role, summary]) => (
                <tr key={role}>
                  <td>{role}</td>
                  <td>{summary}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p>
          Approvals enforce segregation of duties: nobody approves their own request, and IT Admins cannot grant the System
          Admin role.
        </p>
      </section>

      <section id="modules" aria-labelledby="modules-title">
        <h2 id="modules-title">Modules</h2>
        <p>
          {MODULES.length} modules share one data model, one audit trail and one notification stream. Which ones appear in your
          sidebar depends on your role. Every number on screen is read from the database; seeded demonstration records are
          flagged <strong>DEMO</strong>, and sample data generated by simulation mode is flagged <strong>SIMULATION</strong> and
          excluded from figures unless you include it explicitly.
        </p>
        <div className="pub-table-wrap">
          <table className="pub-table">
            <caption className="pub-sr-only">SA modules</caption>
            <thead>
              <tr>
                <th scope="col">Module</th>
                <th scope="col">Route</th>
                <th scope="col">What it does</th>
              </tr>
            </thead>
            <tbody>
              {MODULES.map(([module, route, summary]) => (
                <tr key={module}>
                  <td>{module}</td>
                  <td>
                    <code>{route}</code>
                  </td>
                  <td>{summary}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section id="ai-transparency" aria-labelledby="ai-title">
        <h2 id="ai-title">AI transparency</h2>
        <h3>Where answers come from</h3>
        <p>
          SA AI builds its prompts from records stored in SA — the incident, similar incidents, knowledge
          articles, SLA state and analytics — and asks for structured, schema-validated output.
        </p>
        <h3>Two engines, always labelled</h3>
        <p>
          When the operating organisation configures an LLM provider, that model answers. Without one, or when a call fails,
          a deterministic engine based on rules and statistics answers instead. Every result carries its source (LLM or
          fallback) and the interface shows it; fallback output is never presented as AI-generated.
        </p>
        <h3>People decide</h3>
        <p>
          Changes SA AI proposes — escalating a priority, declaring a major incident, running a runbook — become approvals
          for an Incident Manager. Nothing is applied without a human decision. Workflow suggestions only ever open as an
          unsaved draft in the builder.
        </p>
        <h3>Logged and limited</h3>
        <ul>
          <li>Each interaction records the feature, user, source, provider, model, latency and outcome.</li>
          <li>AI endpoints are rate-limited per user (30 requests per minute by default).</li>
          <li>"Match" percentages on resolution suggestions are computed from similar incidents, runbooks and knowledge — never a constant.</li>
          <li>Provider API keys come from the server environment and are never shown in the interface.</li>
        </ul>
      </section>

      <section id="security" aria-labelledby="security-title">
        <h2 id="security-title">Sessions &amp; security</h2>
        <ul>
          <li>Short-lived access tokens (15 minutes) are kept in memory only — never in browser storage.</li>
          <li>
            One HttpOnly refresh cookie, limited to <code>/api/auth</code>, keeps you signed in. It is rotated on every use;
            reusing an old one revokes the whole session family.
          </li>
          <li>Passwords are hashed with BCrypt; repeated failures lock the account; per-IP rate limits apply.</li>
          <li>When a session expires you are asked to sign in again and return to the page you were on.</li>
          <li>
            The audit log is append-only. See the <Link to="/privacy">Privacy Policy</Link> for everything the platform stores.
          </li>
        </ul>
      </section>

      <section id="help" aria-labelledby="help-title">
        <h2 id="help-title">Need help?</h2>
        <p>
          Your SA administrators are your first point of contact. The <Link to="/support">Support page</Link> shows
          the contact details your organisation has published.
        </p>
      </section>
    </ContentPage>
  );
}
