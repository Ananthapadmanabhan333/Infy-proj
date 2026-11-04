import {
  BarChart3,
  BookOpen,
  Brain,
  CircleAlert,
  CircleCheck,
  Cloud,
  GitPullRequestArrow,
  Globe,
  Headphones,
  KeyRound,
  Layers,
  LayoutGrid,
  Network,
  Package,
  Radar,
  Rocket,
  ScrollText,
  SearchCheck,
  ShieldCheck,
  Sparkle,
  Users,
  Workflow,
  Zap,
  type LucideIcon,
} from 'lucide-react';
import { ROLES } from '../../lib/constants';
import { availableNavLeaves } from '../../lib/nav';

/**
 * Landing-page copy (PUBLIC_ACCESS_SPEC §6, RELEASE3_SPEC §3). No customer counts, uptime percentages, resolution
 * times or certifications. Source of truth: README "Modules", docs/ARCHITECTURE.md and, for the Release 3 modules
 * (problems, changes, assets, CMDB), docs/RELEASE3_SPEC.md §5.
 */

export interface Capability {
  /** Anchor of the explainer block on the landing page. */
  id: string;
  title: string;
  /** One line for the capability card. */
  short: string;
  icon: LucideIcon;
  /** Route inside the workspace (shown as text, the route itself needs a session). */
  route: string;
  summary: string;
  points: string[];
}

/** The twelve capabilities of the brief, in card order (two rows of six). */
export const CAPABILITIES: Capability[] = [
  {
    id: 'incident-management',
    title: 'Incident Management',
    short: 'End-to-end incident lifecycle',
    icon: CircleAlert,
    route: '/incidents',
    summary: 'Every incident follows one lifecycle — from intake to closure — with its SLA clock, history and audit trail attached.',
    points: [
      'Intake pipeline: classification → ITIL impact × urgency priority → duplicate detection → assignment → SLA start.',
      'Similar reports are linked under a parent incident; at three reports the parent becomes a P1 major incident.',
      'Status tabs with live counts, priority / service / status / assignee filters and live SLA countdowns.',
      'Enforced state machine (New → … → Closed, Pending, Reopened), escalation history, comments, attachments and bulk actions.',
    ],
  },
  {
    id: 'service-desk',
    title: 'Service Desk',
    short: 'Unified request management',
    icon: Headphones,
    route: '/service-desk',
    summary: 'Employees request access, hardware and software from an approved catalogue; staff approve and fulfil in one place.',
    points: [
      'Requests, Service Catalogue, My Requests and Approvals — plus a dedicated catalogue page.',
      'Seven request categories with priority, comments and a full history.',
      'Approvals routed by role, with segregation of duties — nobody approves their own request.',
      'Fulfilment tracked through to completion.',
    ],
  },
  {
    id: 'problem-management',
    title: 'Problem Management',
    short: 'Root causes & known errors',
    icon: SearchCheck,
    route: '/problems',
    summary: 'Problems connect recurring incidents to their root cause, workaround and permanent fix — and keep the known error on record.',
    points: [
      'Problem records move from New → Investigating → Root cause identified → Known error → Resolved → Closed.',
      'Linked incidents, affected services, owner and team, with root cause, workaround and permanent fix.',
      'Known-error records that can be published to the knowledge base, and corrective actions with owners and due dates.',
      'Recurring-incident detection suggests problems for clusters of similar incidents; SA AI suggestions are labelled by source.',
    ],
  },
  {
    id: 'change-management',
    title: 'Change Management',
    short: 'Assess, approve and schedule',
    icon: GitPullRequestArrow,
    route: '/changes',
    summary: 'Standard, normal and emergency changes with a risk assessment, CAB approval and a change calendar.',
    points: [
      'Change records from draft through assessment, approval and scheduling to completed, failed or rolled back.',
      'Risk questionnaire with a computed score, impact on services and users, and implementation, rollback and test plans.',
      'Change calendar with conflict and freeze-window checks; CAB approval through staged approval flows.',
      'Incident correlation: incidents on affected services after implementation are linked back to the change.',
    ],
  },
  {
    id: 'asset-management',
    title: 'Asset Management',
    short: 'Hardware & software lifecycle',
    icon: Package,
    route: '/assets',
    summary: 'An inventory of hardware, software and licences, with ownership and the full lifecycle of each asset.',
    points: [
      'Laptops, desktops, servers, network and mobile devices, and software licences with seat counts.',
      'Assignment, location, purchase, warranty and maintenance history.',
      'Lifecycle from in stock → in use → in repair → retired → disposed.',
      'Validated CSV import with a dry run and row-level errors, plus CSV export.',
    ],
  },
  {
    id: 'cmdb',
    title: 'CMDB',
    short: 'Configuration items & impact',
    icon: Network,
    route: '/cmdb',
    summary: 'Configuration items and their relationships, so the impact of a failure or a change is visible before it spreads.',
    points: [
      'Applications, servers, databases, network devices, cloud resources and business services as configuration items.',
      'Depends-on, runs-on, hosts, connects-to and part-of relationships with cycle-safe traversal.',
      'Impact analysis: upstream and downstream items, impacted business services and their open incidents.',
      'A visual dependency map; assets link to the configuration items they implement.',
    ],
  },
  {
    id: 'sa-ai',
    title: 'SA AI',
    short: 'Intelligent operations co-pilot',
    icon: Sparkle,
    route: '/sa-ai',
    summary: 'An operations co-pilot grounded in your own records — and always labelled with the source of its answer.',
    points: [
      'Analyze, Predict, Recommend and Automate tools, grounded chat and an insight of the day.',
      'Uses the configured LLM provider, or a clearly labelled deterministic engine when none is configured.',
      'Proposed changes (priority escalation, major incident, runbook run) become approvals — a person decides.',
      'Per-user rate limit; every AI interaction is logged.',
    ],
  },
  {
    id: 'workflow-automation',
    title: 'Workflow Automation',
    short: 'Visual operational workflows',
    icon: Workflow,
    route: '/automation',
    summary: 'Event-driven workflows drawn visually as trigger → condition → Yes / No branches.',
    points: [
      'Triggers for incident, priority, assignment, SLA and service-request events, plus scheduled runs.',
      '13 action types — assign, change priority or status, notify, escalate, declare major, send a webhook, run a runbook…',
      'Templates, published versions with rollback and dry-run tests against a real incident.',
      'Retries with backoff, per-workflow execution limits and failure notifications.',
    ],
  },
  {
    id: 'sla-monitoring',
    title: 'SLA Monitoring',
    short: 'Track, prevent and escalate',
    icon: ShieldCheck,
    route: '/sla-monitor',
    summary: 'Response and resolution clocks that run in business time, pause and resume exactly, and escalate on their own.',
    points: [
      'Business-hours calendars with time zones and holidays, or 24×7 policies.',
      'Evaluated every minute: at-risk and breached events raise escalation levels 1–3 and notify the manager.',
      'Compliance, breach, at-risk and average-resolution KPIs, compliance trend, breaches by service and department.',
      'Live queue of the incidents closest to breach.',
    ],
  },
  {
    id: 'analytics',
    title: 'Analytics',
    short: 'Actionable operational insights',
    icon: BarChart3,
    route: '/analytics',
    summary: 'Operational reporting computed from live records, with filters, a statistical forecast and exports.',
    points: [
      'Operations, Incidents, Services, Users and Custom tabs with date and department filters.',
      'Resolution rate, first-call resolution, MTTR and incident volume by service.',
      'Statistical incident forecast and natural-language questions.',
      'CSV and PDF export (exports are audited).',
    ],
  },
  {
    id: 'knowledge-base',
    title: 'Knowledge Base',
    short: 'Verified solutions & articles',
    icon: BookOpen,
    route: '/knowledge',
    summary: 'Reusable fixes and how-to articles that agents and employees can find in seconds.',
    points: [
      'Category tiles and popular articles with ratings and read time.',
      'Full-text, typo-tolerant search.',
      'Draft → review → published workflow with version history and restore.',
      'Guides, solutions, known errors and FAQs — published articles are visible to employees too.',
    ],
  },
  {
    id: 'runbooks',
    title: 'Runbooks',
    short: 'Standard operating procedures',
    icon: ScrollText,
    route: '/runbooks',
    summary: 'Step-by-step procedures for recurring operational work, with an approval gate before anything sensitive runs.',
    points: [
      'Prerequisites, linked incident categories and ordered steps.',
      'Sensitive steps need an incident manager’s approval before they can be completed.',
      'Execution history for every run.',
      'Workflows can start an approved runbook automatically.',
    ],
  },
];

export interface Fact {
  value: string;
  label: string;
  icon: LucideIcon;
}

/** Hero fact row — implemented capabilities only (spec §6). */
export const HERO_FACTS: Fact[] = [
  { value: `${availableNavLeaves().length} modules`, label: 'One platform', icon: LayoutGrid },
  { value: `${ROLES.length} roles`, label: 'Server-enforced', icon: KeyRound },
  { value: '24×7', label: 'SLA engine', icon: Zap },
  { value: 'Append-only', label: 'Audit trail', icon: ScrollText },
];

export interface Step {
  title: string;
  text: string;
  icon: LucideIcon;
}

export const HOW_IT_WORKS: Step[] = [
  { title: 'Detect', text: 'Capture incidents & service requests from different channels.', icon: Radar },
  { title: 'Analyse', text: 'Use AI to classify, de-duplicate and assess impact.', icon: Brain },
  { title: 'Assign', text: 'Route to the right team with SLA and workflow automation.', icon: Users },
  { title: 'Resolve', text: 'Collaborate, execute runbooks and close with insights.', icon: CircleCheck },
];

export const RELIABILITY: Fact[] = [
  { value: 'Enterprise Security', label: 'RBAC · JWT · Audit logs', icon: ShieldCheck },
  { value: 'High Availability', label: 'Health probes · Graceful shutdown · Stateless API', icon: Layers },
  { value: 'Scalable', label: 'Stateless tokens · Shared rate limits (Redis) · Pooled DB', icon: Cloud },
  { value: 'Compliance Ready', label: 'Append-only audit · Exports · Role separation', icon: Globe },
  { value: 'Rapid Deployment', label: 'Docker · Render · Vercel · Neon', icon: Rocket },
];

/** "About SA" section of the landing page (footer → /#about). Describes the product, not any deployment. */
export const ABOUT = {
  title: 'About SA',
  lead:
    'SA is an enterprise service management and operations platform that an organisation runs for its own people — one workspace for the service desk, IT operations and the teams that keep services running.',
  principles: [
    { title: 'Real records only', text: 'Every figure in the workspace is computed from persisted data; seeded demonstration records are flagged as demo.' },
    { title: 'AI with its source', text: 'SA AI answers from your own records and labels each answer — language model or deterministic fallback.' },
    { title: 'People decide', text: 'Sensitive actions proposed by automation or AI become approvals that a person accepts or rejects.' },
    { title: 'Enforced on the server', text: 'Roles are checked by the API on every request, and the audit trail is append-only.' },
  ],
} as const;

export interface MenuItem {
  label: string;
  /** A route, optionally with a hash: `/#how-it-works`, `/docs#roles`, `/privacy`. */
  to: string;
  description?: string;
  icon?: LucideIcon;
}

export interface MenuGroup {
  label: string;
  items: MenuItem[];
  /** Two-column panel (Platform). */
  wide?: boolean;
}

export const NAV_GROUPS: MenuGroup[] = [
  {
    label: 'Platform',
    wide: true,
    items: CAPABILITIES.map((capability) => ({
      label: capability.title,
      to: `/#${capability.id}`,
      description: capability.short,
      icon: capability.icon,
    })),
  },
  {
    label: 'Solutions',
    items: [
      { label: 'How it works', to: '/#how-it-works', description: 'From detection to resolution' },
      { label: 'Incident response', to: '/#incident-management', description: 'Intake, duplicates and major incidents' },
      { label: 'Employee self-service', to: '/#service-desk', description: 'Catalogue requests and approvals' },
      { label: 'Change enablement', to: '/#change-management', description: 'Risk, CAB approval and the change calendar' },
      { label: 'SLA governance', to: '/#sla-monitoring', description: 'Business-time clocks and escalation' },
      { label: 'Enterprise reliability', to: '/#reliability', description: 'Security, availability and deployment' },
    ],
  },
  {
    label: 'Resources',
    items: [
      { label: 'Documentation', to: '/docs', description: 'Product overview and guides' },
      { label: 'Getting started', to: '/docs#getting-started', description: 'Accounts, sign-in and first steps' },
      { label: 'Roles & access', to: '/docs#roles', description: 'The roles and what they can do' },
      { label: 'AI transparency', to: '/docs#ai-transparency', description: 'How SA AI answers and what it logs' },
      { label: 'Service status', to: '/status', description: 'Current service health and maintenance' },
      { label: 'Support', to: '/support', description: 'Get help with your workspace' },
    ],
  },
  {
    label: 'Company',
    items: [
      { label: 'About SA', to: '/#about', description: 'What the platform is and the rules it follows' },
      { label: 'Contact', to: '/support#contact', description: 'Reach your SA administrators' },
      { label: 'Privacy Policy', to: '/privacy', description: 'What the platform stores' },
      { label: 'Terms and Conditions', to: '/terms', description: 'Terms of use (template)' },
    ],
  },
];
