import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { activeGroupId, NAV_GROUPS, navForRole, navLeaves, pageLabelFor, rolesFor, type NavGroup } from '../../lib/nav';
import { ROLES } from '../../lib/constants';
import { json, mockApi } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import { brandSubtitle, NAV_STATE_KEY_PREFIX, readCollapsedGroups, Sidebar } from './Sidebar';
import type { Role } from '../../lib/types';

function renderSidebar(role: Role, route = '/dashboard', userId = 7) {
  renderWithProviders(<Sidebar open={false} onClose={() => undefined} />, { auth: makeAuth(makeUser(role, { id: userId })), route });
  return within(screen.getByRole('navigation', { name: 'Main navigation' }));
}

/** Visible structure for a role: group label → item labels. */
function structure(role: Role): Record<string, string[]> {
  return Object.fromEntries(navForRole(role).map((group) => [group.label, group.items.map((item) => item.label)]));
}

afterEach(() => {
  window.localStorage.clear();
});

describe('navigation config (RELEASE3_SPEC §3)', () => {
  it('declares the eight groups with every module of the specs (Release 3 + Release 4), in order', () => {
    expect(NAV_GROUPS.map((group) => group.label)).toEqual(['Workspace', 'Service Management', 'Operations', 'Autonomous Operations', 'Enterprise', 'Intelligence', 'Governance', 'Administration']);
    expect(Object.fromEntries(NAV_GROUPS.map((group) => [group.id, group.items.map((item) => `${item.label} ${item.to}`)]))).toEqual({
      workspace: ['Command Center /dashboard', 'My Work /my-work', 'Employee Portal /portal'],
      'service-management': ['Service Desk /service-desk', 'Service Catalogue /catalog', 'Incidents /incidents', 'Major Incidents /major-incidents', 'Problems /problems', 'Changes /changes'],
      operations: ['Alerts /alerts', 'On-call /on-call', 'Services /services', 'CMDB /cmdb', 'Assets /assets', 'Deployments /deployments'],
      'autonomous-operations': ['Observability /observability', 'Digital Twin /digital-twin', 'Predictions /predictions', 'SA Agents /agents', 'Remediation /remediation'],
      enterprise: ['Security Operations /security', 'FinOps /finops', 'Employee Experience /dex'],
      intelligence: ['SA AI /sa-ai', 'Workflows /automation', 'Runbooks /runbooks', 'Knowledge Base /knowledge'],
      governance: ['SLA Monitor /sla-monitor', 'Approvals /approvals', 'Analytics /analytics', 'Audit Logs /audit-logs', 'Status Page /status-page'],
      administration: ['Users /users', 'Teams /teams', 'Integrations /integrations', 'Settings /settings'],
    });
  });

  it('marks every shipped page available across all eight groups (Release 3 + Release 4)', () => {
    const unavailable = navLeaves().filter((leaf) => !leaf.available).map((leaf) => leaf.label);
    expect(unavailable).toEqual([]);
    expect(navForRole('SYSTEM_ADMIN').find((group) => group.id === 'enterprise')).toBeDefined();
    for (const role of ROLES) {
      const visible = navForRole(role).flatMap((group) => group.items.map((item) => item.label));
      navLeaves()
        .filter((leaf) => leaf.available && leaf.roles.includes(role))
        .forEach((leaf) => expect(visible, `${role} sees ${leaf.label}`).toContain(leaf.label));
    }
  });

  it('hides an item again when its availability flag is false', () => {
    const groups: NavGroup[] = NAV_GROUPS.map((group) => ({
      ...group,
      items: group.items.map((item) => (item.to === '/on-call' ? { ...item, available: false } : item)),
    }));
    expect(navForRole('SYSTEM_ADMIN', groups).flatMap((group) => group.items.map((item) => item.label))).not.toContain('On-call');
  });

  it('shows an item as soon as its availability flag is flipped', () => {
    const groups: NavGroup[] = NAV_GROUPS.map((group) => ({
      ...group,
      items: group.items.map((item) => (item.to === '/problems' ? { ...item, available: true } : item)),
    }));
    const serviceManagement = navForRole('SUPPORT_AGENT', groups).find((group) => group.id === 'service-management');
    expect(serviceManagement?.items.map((item) => item.label)).toContain('Problems');
    expect(navForRole('EMPLOYEE', groups).flatMap((group) => group.items.map((item) => item.label))).not.toContain('Problems');
  });

  it('gives a system admin every available page, grouped', () => {
    expect(structure('SYSTEM_ADMIN')).toEqual({
      Workspace: ['Command Center', 'My Work', 'Employee Portal'],
      'Service Management': ['Service Desk', 'Service Catalogue', 'Incidents', 'Major Incidents', 'Problems', 'Changes'],
      Operations: ['Alerts', 'On-call', 'Services', 'CMDB', 'Assets', 'Deployments'],
      'Autonomous Operations': ['Observability', 'Digital Twin', 'Predictions', 'SA Agents', 'Remediation'],
      Enterprise: ['Security Operations', 'FinOps', 'Employee Experience'],
      Intelligence: ['SA AI', 'Workflows', 'Runbooks', 'Knowledge Base'],
      Governance: ['SLA Monitor', 'Approvals', 'Analytics', 'Audit Logs', 'Status Page'],
      Administration: ['Users', 'Teams', 'Integrations', 'Settings'],
    });
  });

  it('gives employees the workspace without My Work, service desk, catalogue, incidents, knowledge, approvals and the status page', () => {
    expect(structure('EMPLOYEE')).toEqual({
      Workspace: ['Command Center', 'Employee Portal'],
      'Service Management': ['Service Desk', 'Service Catalogue', 'Incidents'],
      Intelligence: ['Knowledge Base'],
      Governance: ['Approvals', 'Status Page'],
    });
  });

  it('filters by role for managers, agents, auditors and the Release 3 roles', () => {
    expect(structure('IT_MANAGER').Administration).toEqual(['Users', 'Teams', 'Integrations']);
    expect(structure('INCIDENT_MANAGER').Administration).toEqual(['Users', 'Teams', 'Integrations']);
    expect(structure('IT_ADMIN').Administration).toEqual(['Users', 'Teams', 'Integrations', 'Settings']);
    expect(structure('AUDITOR')).toEqual({
      Workspace: ['Command Center', 'Employee Portal'],
      'Service Management': ['Service Desk', 'Service Catalogue', 'Incidents', 'Major Incidents', 'Problems', 'Changes'],
      Operations: ['Alerts', 'On-call', 'Services', 'CMDB', 'Assets', 'Deployments'],
      'Autonomous Operations': ['Observability', 'Digital Twin', 'Predictions', 'Remediation'],
      Enterprise: ['Security Operations', 'FinOps', 'Employee Experience'],
      Intelligence: ['SA AI', 'Workflows', 'Runbooks', 'Knowledge Base'],
      Governance: ['SLA Monitor', 'Approvals', 'Analytics', 'Audit Logs', 'Status Page'],
      Administration: ['Users', 'Teams'],
    });
    expect(structure('SUPPORT_AGENT').Workspace).toEqual(['Command Center', 'My Work', 'Employee Portal']);
    expect(structure('SUPPORT_AGENT').Administration).toEqual(['Teams']);
    expect(structure('SUPPORT_AGENT').Governance).toEqual(['SLA Monitor', 'Approvals', 'Analytics', 'Status Page']);
    for (const role of ['CHANGE_MANAGER', 'ASSET_MANAGER'] as const) {
      expect(structure(role).Intelligence).toEqual(['SA AI', 'Workflows', 'Runbooks', 'Knowledge Base']);
      expect(structure(role).Administration).toEqual(['Teams']);
      expect(structure(role).Governance).toEqual(['SLA Monitor', 'Approvals', 'Analytics', 'Status Page']);
    }
    expect(structure('EMPLOYEE').Operations).toBeUndefined();
    expect(rolesFor('/alerts')).not.toContain('EMPLOYEE');
    expect(rolesFor('/cmdb')).toContain('AUDITOR');
    expect(rolesFor('/assets')).toContain('ASSET_MANAGER');
    expect(activeGroupId('/cmdb/12')).toBe('operations');
    expect(pageLabelFor('/alerts/5')).toBe('Alerts');
    expect(rolesFor('/on-call')).not.toContain('EMPLOYEE');
    expect(rolesFor('/major-incidents')).toContain('AUDITOR');
    expect(activeGroupId('/reviews/3')).toBe('service-management');
    expect(pageLabelFor('/reviews/3')).toBe('Post-incident reviews');
    expect(pageLabelFor('/on-call/4')).toBe('On-call');
    expect(navForRole(null)).toEqual([]);
  });

  it('gives the Release 4 analysts the reader pages (not My Work) and the new observability modules', () => {
    expect(structure('SECURITY_ANALYST').Workspace).toEqual(['Command Center', 'Employee Portal']);
    expect(structure('SECURITY_ANALYST')['Autonomous Operations']).toEqual(['Observability', 'Digital Twin', 'Predictions', 'SA Agents', 'Remediation']);
    expect(structure('SECURITY_ANALYST').Enterprise).toEqual(['Security Operations', 'Employee Experience']);
    expect(structure('FINOPS_ANALYST').Workspace).toEqual(['Command Center', 'Employee Portal']);
    expect(structure('FINOPS_ANALYST')['Autonomous Operations']).toEqual(['Observability', 'Digital Twin', 'Predictions', 'Remediation']);
    expect(structure('FINOPS_ANALYST').Enterprise).toEqual(['FinOps', 'Employee Experience']);
    for (const role of ['SECURITY_ANALYST', 'FINOPS_ANALYST'] as const) {
      expect(structure(role).Operations).toEqual(['Alerts', 'On-call', 'Services', 'CMDB', 'Assets', 'Deployments']);
      expect(rolesFor('/observability')).toContain(role);
      expect(rolesFor('/my-work')).not.toContain(role);
    }
    expect(rolesFor('/observability')).not.toContain('EMPLOYEE');
    expect(rolesFor('/digital-twin')).not.toContain('EMPLOYEE');
    expect(structure('EMPLOYEE')['Autonomous Operations']).toBeUndefined();
    expect(activeGroupId('/digital-twin')).toBe('autonomous-operations');
    expect(pageLabelFor('/observability')).toBe('Observability');
    expect(rolesFor('/security')).toContain('SECURITY_ANALYST');
    expect(rolesFor('/finops')).toContain('FINOPS_ANALYST');
    expect(rolesFor('/finops')).not.toContain('SECURITY_ANALYST');
  });

  it('derives route guards, the active group and the document title from the config', () => {
    expect(rolesFor('/settings')).toEqual(['SYSTEM_ADMIN', 'IT_ADMIN']);
    expect(rolesFor('/audit-logs')).toEqual(['SYSTEM_ADMIN', 'IT_ADMIN', 'AUDITOR']);
    expect(rolesFor('/analytics')).not.toContain('EMPLOYEE');
    expect(rolesFor('/analytics')).toContain('CHANGE_MANAGER');
    expect(rolesFor('/incidents')).toContain('EMPLOYEE');
    expect(activeGroupId('/runbooks/4')).toBe('intelligence');
    expect(activeGroupId('/incidents/12')).toBe('service-management');
    expect(activeGroupId('/profile')).toBeUndefined();
    expect(pageLabelFor('/automation/new')).toBe('Workflows');
    expect(pageLabelFor('/catalog')).toBe('Service Catalogue');
    expect(pageLabelFor('/notifications')).toBe('Notifications');
    expect(pageLabelFor('/nowhere')).toBeUndefined();
  });
});

describe('Sidebar', () => {
  it('renders the role-filtered groups as collapsible sections with Lucide icons', () => {
    const nav = renderSidebar('SYSTEM_ADMIN');
    const toggles = nav.getAllByRole('button').map((button) => button.textContent);
    expect(toggles).toEqual(['Workspace', 'Service Management', 'Operations', 'Autonomous Operations', 'Enterprise', 'Intelligence', 'Governance', 'Administration']);
    const intelligence = within(nav.getByRole('group', { name: 'Intelligence' }));
    expect(intelligence.getAllByRole('link').map((link) => link.getAttribute('aria-label'))).toEqual(['SA AI', 'Workflows', 'Runbooks', 'Knowledge Base']);
    expect(intelligence.getByRole('link', { name: 'Workflows' })).toHaveAttribute('href', '/automation');
    expect(intelligence.getByRole('link', { name: 'Workflows' }).querySelector('svg.lucide')).not.toBeNull();
    expect(nav.getByRole('link', { name: 'Service Catalogue' })).toHaveAttribute('href', '/catalog');
  });

  it('shows employees only their groups and never an empty group', () => {
    const nav = renderSidebar('EMPLOYEE');
    expect(nav.getAllByRole('button').map((button) => button.textContent)).toEqual(['Workspace', 'Service Management', 'Intelligence', 'Governance']);
    expect(nav.queryByRole('link', { name: 'My Work' })).not.toBeInTheDocument();
    expect(nav.getByRole('link', { name: 'Status Page' })).toHaveAttribute('href', '/status-page');
    ['SLA Monitor', 'SA AI', 'Workflows', 'Analytics', 'Services', 'Users'].forEach((label) => expect(nav.queryByRole('link', { name: label })).not.toBeInTheDocument());
    ['Command Center', 'Incidents', 'Service Desk', 'Service Catalogue', 'Knowledge Base'].forEach((label) => expect(nav.getByRole('link', { name: label })).toBeInTheDocument());
  });

  it('marks the active item and highlights its group', () => {
    const nav = renderSidebar('SUPPORT_AGENT', '/runbooks/4');
    const runbooks = nav.getByRole('link', { name: 'Runbooks' });
    expect(runbooks).toHaveClass('active');
    expect(runbooks).toHaveAttribute('aria-current', 'page');
    expect(nav.getByRole('link', { name: 'Workflows' })).not.toHaveClass('active');
    expect(nav.getByRole('button', { name: 'Intelligence' }).closest('.nav-group')).toHaveClass('has-active');
    expect(nav.getByRole('button', { name: 'Workspace' }).closest('.nav-group')).not.toHaveClass('has-active');
  });

  it('collapses a group and remembers it per user in localStorage', async () => {
    const user = userEvent.setup();
    const nav = renderSidebar('SYSTEM_ADMIN', '/dashboard', 41);
    const governance = nav.getByRole('button', { name: 'Governance' });
    expect(governance).toHaveAttribute('aria-expanded', 'true');
    await user.click(governance);
    expect(governance).toHaveAttribute('aria-expanded', 'false');
    expect(nav.queryByRole('link', { name: 'Analytics' })).not.toBeInTheDocument();
    expect(JSON.parse(window.localStorage.getItem(`${NAV_STATE_KEY_PREFIX}.41`) ?? '[]')).toEqual(['governance']);
    expect(readCollapsedGroups(41)).toEqual(new Set(['governance']));
    // Another user on the same browser keeps their own state.
    expect(readCollapsedGroups(42).size).toBe(0);
  });

  it('restores the remembered state but always opens the group of the current page', () => {
    window.localStorage.setItem(`${NAV_STATE_KEY_PREFIX}.7`, JSON.stringify(['governance', 'administration']));
    const nav = renderSidebar('SYSTEM_ADMIN', '/audit-logs');
    expect(nav.getByRole('button', { name: 'Administration' })).toHaveAttribute('aria-expanded', 'false');
    expect(nav.getByRole('button', { name: 'Governance' })).toHaveAttribute('aria-expanded', 'true');
    expect(nav.getByRole('link', { name: 'Audit Logs' })).toHaveAttribute('aria-current', 'page');
  });

  it('works when localStorage is unavailable or holds garbage', async () => {
    window.localStorage.setItem(`${NAV_STATE_KEY_PREFIX}.7`, '{not json');
    expect(readCollapsedGroups(7).size).toBe(0);
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const nav = renderSidebar('SUPPORT_AGENT');
    await userEvent.setup().click(nav.getByRole('button', { name: 'Operations' }));
    expect(nav.getByRole('button', { name: 'Operations' })).toHaveAttribute('aria-expanded', 'false');
    expect(getItem).toHaveBeenCalled();
    expect(setItem).toHaveBeenCalled();
  });

  it('shows the product wordmark and the organisation or platform subtitle', () => {
    renderWithProviders(<Sidebar open={false} onClose={() => undefined} />, { auth: makeAuth(makeUser('SUPPORT_AGENT', { organisation: { name: 'Acme Corp' } })) });
    const sidebar = within(screen.getByRole('complementary', { name: 'Sidebar' }));
    expect(sidebar.getByRole('link', { name: 'SA — Command Center' })).toHaveAttribute('href', '/dashboard');
    expect(sidebar.getByText('Acme Corp')).toBeInTheDocument();
    expect(brandSubtitle(null)).toBe('Enterprise Operations Platform');
    expect(brandSubtitle('SA 360')).toBe('Enterprise Operations Platform');
    expect(brandSubtitle(' sa ')).toBe('Enterprise Operations Platform');
    expect(sidebar.getByText(/^SA/, { selector: '.version' })).toBeInTheDocument();
  });

  it('reports live API health and the open incident count badge', async () => {
    mockApi({
      'GET /health': json({ status: 'UP' }),
      'GET /incidents': json({ content: [], page: 0, size: 1, totalElements: 12, totalPages: 12 }),
    });
    const nav = renderSidebar('SUPPORT_AGENT');
    expect(await screen.findByText('API Connected')).toBeInTheDocument();
    expect(await nav.findByText('12')).toHaveClass('nav-badge');
  });

  it('shows the API as unreachable when the health check fails', async () => {
    renderSidebar('SUPPORT_AGENT');
    expect(await screen.findByText('API Unreachable')).toBeInTheDocument();
  });

  it('closes the mobile drawer with Escape', () => {
    const onClose = vi.fn();
    renderWithProviders(<Sidebar open onClose={onClose} />, { auth: makeAuth(makeUser('EMPLOYEE')) });
    expect(screen.getByRole('button', { name: 'Close navigation' })).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalled();
  });
});
