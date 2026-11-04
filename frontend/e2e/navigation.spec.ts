import { expect, test } from '@playwright/test';
import { login, logout, navLink, trackPageErrors } from './support';

const ADMIN_ROUTES: [string, string][] = [
  ['/dashboard', 'Operations Command Center'],
  ['/incidents', 'Incidents'],
  ['/service-desk', 'Service Desk'],
  ['/services', 'Services'],
  ['/sla-monitor', 'SLA Monitoring'],
  ['/sa-ai', 'SA AI'],
  ['/automation', 'Workflow Automation'],
  ['/analytics', 'Analytics & Reports'],
  ['/knowledge', 'Knowledge Base'],
  ['/runbooks', 'Runbooks'],
  ['/notifications', 'Notifications'],
  ['/users', 'Users'],
  ['/audit-logs', 'Audit Logs'],
  ['/integrations', 'Integrations'],
  ['/settings', 'Settings'],
  ['/announcements', 'Announcements'],
  ['/profile', 'Your profile'],
  // Release 3 modules
  ['/my-work', 'My Work'],
  ['/portal', 'Employee Portal'],
  ['/catalog', 'Service Catalogue'],
  ['/major-incidents', 'Major Incidents'],
  ['/reviews', 'Post-incident reviews'],
  ['/problems', 'Problems'],
  ['/changes', 'Changes'],
  ['/alerts', 'Alerts'],
  ['/on-call', 'On-call'],
  ['/cmdb', 'CMDB'],
  ['/assets', 'Assets'],
  ['/deployments', 'Deployments'],
  ['/approvals', 'Approvals'],
  ['/status-page', 'Status Page'],
  ['/teams', 'Teams'],
  // Release 4 modules
  ['/observability', 'Observability'],
  ['/digital-twin', 'Digital Twin'],
  ['/predictions', 'Predictions'],
  ['/agents', 'SA Agents'],
  ['/remediation', 'Remediation'],
  ['/security', 'Security Operations'],
  ['/finops', 'FinOps'],
  ['/dex', 'Employee Experience'],
];

const REDIRECTS: [string, RegExp][] = [
  ['/sla', /\/sla-monitor$/],
  ['/ai', /\/sa-ai$/],
  ['/nexus-ai', /\/sa-ai$/],
  ['/workflows', /\/automation$/],
  ['/admin/users', /\/users$/],
  ['/admin/audit-logs', /\/audit-logs$/],
  ['/admin/configuration', /\/settings$/],
  ['/oncall', /\/on-call$/],
  ['/forecast', /\/analytics/],
];

test('every primary route renders with live data for a system administrator', async ({ page }) => {
  const errors = trackPageErrors(page);
  await login(page, 'admin');
  for (const [path, heading] of ADMIN_ROUTES) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 }), path).toContainText(heading);
    await expect(page.locator('.loading-state, [aria-busy="true"]').first(), path).toHaveCount(0, { timeout: 15_000 });
    await expect(page.getByText('Could not load this data'), path).toHaveCount(0);
    await expect(page.getByText('API unreachable'), path).toHaveCount(0);
  }
  expect(errors).toEqual([]);
});

test('Release 1 paths redirect to their Release 2 routes', async ({ page }) => {
  await login(page, 'admin');
  for (const [from, to] of REDIRECTS) {
    await page.goto(from);
    await expect(page, from).toHaveURL(to);
  }
});

test('sidebar navigation links open their pages', async ({ page }) => {
  await login(page, 'priya.raman');
  for (const [label, heading] of [
    ['Incidents', 'Incidents'],
    ['SLA Monitor', 'SLA Monitoring'],
    ['Knowledge Base', 'Knowledge Base'],
    ['Workflows', 'Workflow Automation'],
    ['Alerts', 'Alerts'],
    ['On-call', 'On-call'],
    ['Observability', 'Observability'],
    ['Digital Twin', 'Digital Twin'],
    ['Predictions', 'Predictions'],
    ['SA Agents', 'SA Agents'],
    ['Remediation', 'Remediation'],
    ['Security Operations', 'Security Operations'],
    ['FinOps', 'FinOps'],
    ['Employee Experience', 'Employee Experience'],
    ['Command Center', 'Operations Command Center'],
  ]) {
    await navLink(page, label).click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText(heading);
  }
});

test('employees see a reduced navigation and cannot open restricted pages', async ({ page }) => {
  await login(page, 'kavya.reddy');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Your IT Service Hub');
  await expect(navLink(page, 'Incidents')).toBeVisible();
  for (const hidden of [
    'SLA Monitor',
    'SA AI',
    'Workflows',
    'Analytics',
    'Alerts',
    'On-call',
    'CMDB',
    'Observability',
    'Digital Twin',
    'Predictions',
    'SA Agents',
    'Remediation',
    'Security Operations',
    'FinOps',
    'Employee Experience',
    'Users',
    'Settings',
  ]) {
    await expect(navLink(page, hidden), hidden).toHaveCount(0);
  }
  for (const path of [
    '/audit-logs',
    '/analytics',
    '/settings',
    '/users',
    '/observability',
    '/digital-twin',
    '/predictions',
    '/agents',
    '/remediation',
    '/security',
    '/finops',
    '/dex',
  ]) {
    await page.goto(path);
    await expect(page.getByRole('heading', { name: 'Access restricted' }), path).toBeVisible();
  }
});

test('security and FinOps analysts have scoped enterprise access', async ({ page }) => {
  await login(page, 'vikram.sec');
  await page.goto('/security');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Security Operations');
  await page.goto('/finops');
  await expect(page.getByRole('heading', { name: 'Access restricted' })).toBeVisible();

  await logout(page);
  await login(page, 'neha.finops');
  await page.goto('/finops');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('FinOps');
  await page.goto('/security');
  await expect(page.getByRole('heading', { name: 'Access restricted' })).toBeVisible();
});

test('auditors can read audit logs but not settings', async ({ page }) => {
  await login(page, 'ananya.bose');
  await page.goto('/audit-logs');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Audit Logs');
  await expect(page.getByRole('table').first()).toBeVisible();
  await page.goto('/settings');
  await expect(page.getByRole('heading', { name: 'Access restricted' })).toBeVisible();
});

test('IT administrators manage configuration but not system settings', async ({ page }) => {
  await login(page, 'rajesh.iyer');
  await page.goto('/settings');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Settings');
  await expect(page.getByRole('tab', { name: /Incident Categories/ })).toBeVisible();
  await expect(page.getByRole('tab', { name: /^AI/ })).toHaveCount(0);
  await expect(page.getByRole('tab', { name: /Demo Tools/ })).toHaveCount(0);
});
