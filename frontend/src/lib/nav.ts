import {
  Activity,
  AlertTriangle,
  Bot,
  BrainCircuit,
  BarChart3,
  BellRing,
  BookOpen,
  CalendarClock,
  FileClock,
  Gauge,
  GitPullRequestArrow,
  Headphones,
  LayoutDashboard,
  Layers3,
  LifeBuoy,
  Laptop,
  ListTodo,
  Network,
  Package,
  PiggyBank,
  Plug,
  RadioTower,
  Rocket,
  ScrollText,
  SearchCheck,
  Settings,
  ShieldCheck,
  ShoppingBag,
  Siren,
  Sparkles,
  Stamp,
  Users,
  UsersRound,
  Waypoints,
  Workflow,
  Wrench,
  type LucideIcon,
} from 'lucide-react';
import {
  ADMIN_OR_MANAGER,
  AGENT_USERS,
  ALL_ROLES,
  AUDIT_READERS,
  DEX_READERS,
  FINOPS_READERS,
  OBSERVABILITY_READERS,
  READERS,
  SECOPS_READERS,
  SETTINGS_PAGE,
  STAFF,
  USER_READERS,
} from './constants';
import type { Role } from './types';

export type NavGroupId =
  | 'workspace'
  | 'service-management'
  | 'operations'
  | 'autonomous-operations'
  | 'enterprise'
  | 'intelligence'
  | 'governance'
  | 'administration';

export interface NavLeaf {
  label: string;
  to: string;
  icon: LucideIcon;
  roles: Role[];
  /**
   * Whether the page exists. `false` keeps the item (and only the item) out of the sidebar until the module's
   * page is built — the engineer who ships the page flips it to `true` (see components/README.md, "Navigation").
   */
  available: boolean;
  badge?: 'openIncidents';
  /** Other path prefixes that keep this item highlighted. */
  match?: string[];
}

export interface NavGroup {
  id: NavGroupId;
  /** Shown upper-case by CSS (WORKSPACE, SERVICE MANAGEMENT, …). */
  label: string;
  items: NavLeaf[];
}

/**
 * Sidebar (RELEASE3_SPEC §3): six role-aware groups. Items whose page has not been built yet are declared with
 * `available: false` — they are hidden, so every visible item leads to a real page.
 */
export const NAV_GROUPS: NavGroup[] = [
  {
    id: 'workspace',
    label: 'Workspace',
    items: [
      { label: 'Command Center', to: '/dashboard', icon: LayoutDashboard, roles: ALL_ROLES, available: true },
      { label: 'My Work', to: '/my-work', icon: ListTodo, roles: STAFF, available: true },
      { label: 'Employee Portal', to: '/portal', icon: LifeBuoy, roles: ALL_ROLES, available: true },
    ],
  },
  {
    id: 'service-management',
    label: 'Service Management',
    items: [
      { label: 'Service Desk', to: '/service-desk', icon: Headphones, roles: ALL_ROLES, available: true },
      { label: 'Service Catalogue', to: '/catalog', icon: ShoppingBag, roles: ALL_ROLES, available: true },
      { label: 'Incidents', to: '/incidents', icon: AlertTriangle, roles: ALL_ROLES, available: true, badge: 'openIncidents' },
      { label: 'Major Incidents', to: '/major-incidents', icon: Siren, roles: READERS, available: true, match: ['/reviews'] },
      { label: 'Problems', to: '/problems', icon: SearchCheck, roles: READERS, available: true },
      { label: 'Changes', to: '/changes', icon: GitPullRequestArrow, roles: READERS, available: true },
    ],
  },
  {
    id: 'operations',
    label: 'Operations',
    items: [
      { label: 'Alerts', to: '/alerts', icon: BellRing, roles: READERS, available: true },
      { label: 'On-call', to: '/on-call', icon: CalendarClock, roles: READERS, available: true },
      { label: 'Services', to: '/services', icon: Layers3, roles: READERS, available: true },
      { label: 'CMDB', to: '/cmdb', icon: Network, roles: READERS, available: true },
      { label: 'Assets', to: '/assets', icon: Package, roles: READERS, available: true },
      { label: 'Deployments', to: '/deployments', icon: Rocket, roles: READERS, available: true },
    ],
  },
  {
    // Release 4 (RELEASE4_SPEC §3). Pages ship phase by phase: FE-6 observability + twin, FE-7 predictions + agents, FE-8 remediation.
    id: 'autonomous-operations',
    label: 'Autonomous Operations',
    items: [
      { label: 'Observability', to: '/observability', icon: Activity, roles: OBSERVABILITY_READERS, available: true },
      { label: 'Digital Twin', to: '/digital-twin', icon: Waypoints, roles: OBSERVABILITY_READERS, available: true },
      { label: 'Predictions', to: '/predictions', icon: BrainCircuit, roles: READERS, available: true },
      { label: 'SA Agents', to: '/agents', icon: Bot, roles: AGENT_USERS, available: true },
      { label: 'Remediation', to: '/remediation', icon: Wrench, roles: READERS, available: true },
    ],
  },
  {
    // Release 4 (RELEASE4_SPEC §3): FE-8 security operations, FE-9 FinOps and employee experience.
    id: 'enterprise',
    label: 'Enterprise',
    items: [
      { label: 'Security Operations', to: '/security', icon: ShieldCheck, roles: SECOPS_READERS, available: true },
      { label: 'FinOps', to: '/finops', icon: PiggyBank, roles: FINOPS_READERS, available: true },
      { label: 'Employee Experience', to: '/dex', icon: Laptop, roles: DEX_READERS, available: true },
    ],
  },
  {
    id: 'intelligence',
    label: 'Intelligence',
    items: [
      { label: 'SA AI', to: '/sa-ai', icon: Sparkles, roles: READERS, available: true },
      { label: 'Workflows', to: '/automation', icon: Workflow, roles: READERS, available: true },
      { label: 'Runbooks', to: '/runbooks', icon: ScrollText, roles: READERS, available: true },
      { label: 'Knowledge Base', to: '/knowledge', icon: BookOpen, roles: ALL_ROLES, available: true },
    ],
  },
  {
    id: 'governance',
    label: 'Governance',
    items: [
      { label: 'SLA Monitor', to: '/sla-monitor', icon: Gauge, roles: READERS, available: true },
      { label: 'Approvals', to: '/approvals', icon: Stamp, roles: ALL_ROLES, available: true },
      { label: 'Analytics', to: '/analytics', icon: BarChart3, roles: READERS, available: true },
      { label: 'Audit Logs', to: '/audit-logs', icon: FileClock, roles: AUDIT_READERS, available: true },
      { label: 'Status Page', to: '/status-page', icon: RadioTower, roles: ALL_ROLES, available: true },
    ],
  },
  {
    id: 'administration',
    label: 'Administration',
    items: [
      { label: 'Users', to: '/users', icon: Users, roles: USER_READERS, available: true },
      { label: 'Teams', to: '/teams', icon: UsersRound, roles: READERS, available: true },
      { label: 'Integrations', to: '/integrations', icon: Plug, roles: ADMIN_OR_MANAGER, available: true },
      { label: 'Settings', to: '/settings', icon: Settings, roles: SETTINGS_PAGE, available: true },
    ],
  },
];

/** Groups and items a role may see: available items only; a group without visible items is dropped. */
export function navForRole(role: Role | null | undefined, groups: NavGroup[] = NAV_GROUPS): NavGroup[] {
  if (!role) return [];
  return groups.flatMap((group) => {
    const items = group.items.filter((item) => item.available && item.roles.includes(role));
    return items.length ? [{ ...group, items }] : [];
  });
}

/** Every item (available or not), in sidebar order. */
export function navLeaves(groups: NavGroup[] = NAV_GROUPS): NavLeaf[] {
  return groups.flatMap((group) => group.items);
}

/** Items whose page exists (what users can actually open), in sidebar order. */
export function availableNavLeaves(groups: NavGroup[] = NAV_GROUPS): NavLeaf[] {
  return navLeaves(groups).filter((leaf) => leaf.available);
}

/** Role access per route, derived from the nav so guards and sidebar never drift apart. */
export function rolesFor(path: string): Role[] {
  return navLeaves().find((leaf) => leaf.to === path)?.roles ?? ALL_ROLES;
}

function startsWithSegment(pathname: string, prefix: string): boolean {
  return pathname === prefix || pathname.startsWith(`${prefix}/`);
}

/** True when `pathname` belongs to the nav leaf (exact path, sub-path or an extra `match` prefix). */
export function isLeafActive(leaf: NavLeaf, pathname: string): boolean {
  return [leaf.to, ...(leaf.match ?? [])].some((prefix) => startsWithSegment(pathname, prefix));
}

/** The visible leaf for `pathname` (longest matching prefix wins). */
export function activeLeaf(pathname: string, groups: NavGroup[] = NAV_GROUPS): NavLeaf | undefined {
  return navLeaves(groups)
    .filter((leaf) => isLeafActive(leaf, pathname))
    .sort((a, b) => b.to.length - a.to.length)[0];
}

/** Id of the group that contains the current page, if any. */
export function activeGroupId(pathname: string, groups: NavGroup[] = NAV_GROUPS): NavGroupId | undefined {
  const leaf = activeLeaf(pathname, groups);
  return leaf ? groups.find((group) => group.items.includes(leaf))?.id : undefined;
}

/** Pages outside the sidebar that still get a document title. */
const EXTRA_TITLES: [prefix: string, label: string][] = [
  ['/notifications', 'Notifications'],
  ['/profile', 'Profile'],
  ['/announcements', 'Announcements'],
  ['/reviews', 'Post-incident reviews'],
];

/** Label for the document title of an in-app route (`SA | <label>`), or undefined for unknown paths. */
export function pageLabelFor(pathname: string): string | undefined {
  const extra = EXTRA_TITLES.find(([prefix]) => startsWithSegment(pathname, prefix));
  if (extra) return extra[1];
  return activeLeaf(pathname)?.label;
}

/** Default subtitles for <PageHeading page="…" />. */
export const pageDescriptions: Record<string, string> = {
  'Command Center': 'Real-time IT operations, incident intelligence and service health',
  'My Work': 'Everything assigned to you and your teams',
  'Employee Portal': 'Raise requests, follow your tickets and check service status',
  Incidents: 'Manage, track and resolve IT incidents',
  'Major Incidents': 'Command, coordinate and communicate during major incidents',
  Problems: 'Find and remove the root causes behind recurring incidents',
  Changes: 'Plan, assess, approve and schedule changes',
  'Service Desk': 'Manage user requests, service catalog and approvals',
  'Service Catalogue': 'Request IT services, access and equipment from approved catalogue items',
  Alerts: 'Monitoring alerts, deduplicated and routed to the right team',
  'On-call': 'Schedules, rotations and escalation policies',
  Services: 'Monitor business-critical services and dependencies',
  CMDB: 'Configuration items, relationships and impact',
  Assets: 'Hardware and software inventory through its lifecycle',
  Deployments: 'Releases and deployments across environments',
  Observability: 'Metrics, logs, traces, anomalies and alert correlation from your telemetry',
  'Digital Twin': 'Business services, applications and infrastructure as one live model — with impact simulation',
  Predictions: 'Trained SLA breach, incident recurrence, capacity risk and service health forecasting models',
  'SA Agents': 'Multi-agent incident investigation, change risk assessment and approval-gated action proposals',
  Remediation: 'Policy-gated autonomous remediation playbooks, automatic rollback and tamper-evident hash chain',
  'Security Operations': 'Security incidents, vulnerability findings, risk prioritisation and security alert correlation',
  FinOps: 'FOCUS 1.x cloud cost allocation, budgets, cost anomalies and right-sizing savings recommendations',
  'Employee Experience': 'Digital Employee Experience (DEX) scores across endpoints, applications, logins and services',
  'SLA Monitor': 'Track SLA compliance, breaches and escalations',
  Approvals: 'Approval tasks waiting for your decision',
  'Status Page': 'Service status and maintenance communicated to users',
  'SA AI': 'Your IT operations co-pilot',
  Analytics: 'Operational insights, trends and performance',
  'Analytics & Reports': 'Operational insights, trends and performance',
  'Forecast & Trends': 'Anticipate incident volume and operational risk',
  Automation: 'Design, deploy and manage IT workflows',
  Workflows: 'Design, deploy and manage IT workflows',
  'Workflow Automation': 'Design, deploy and manage IT workflows',
  'Workflow Builder': 'Design and automate repeatable IT operations',
  Runbooks: 'Standardized procedures for incident resolution',
  Integrations: 'Connect enterprise tools and data sources',
  'Knowledge Base': 'Centralized knowledge for faster resolution',
  Solutions: 'Reusable fixes and verified resolutions',
  Announcements: 'Operational notices and planned maintenance',
  Users: 'Manage users, departments and access',
  'Users & Roles': 'Manage users, teams and access policies',
  Teams: 'Teams, members and their queues',
  Settings: 'Organisation, SLA, categories, notifications, AI, e-mail and system settings',
  Configuration: 'Configure services, priorities and SLA policies',
  'Audit Logs': 'Review security and operational activity',
  Notifications: 'Alerts, assignments and approvals addressed to you',
  Profile: 'Your account details and password',
};
