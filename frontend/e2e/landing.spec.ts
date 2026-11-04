import { expect, test } from '@playwright/test';
import { login, trackPageErrors } from './support';

test('landing page renders the reference composition with live status and honest content', async ({ page }) => {
  const errors = trackPageErrors(page);
  const apiCalls: string[] = [];
  page.on('request', (r) => {
    if (r.url().includes('/api/')) apiCalls.push(new URL(r.url()).pathname);
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Complex Operations.');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Under Control.');
  await expect(page.getByText('AI-POWERED IT OPERATIONS PLATFORM')).toBeVisible();
  await expect(page.getByText('System Operational').first()).toBeVisible(); // from /api/health
  await expect(page.getByText('Demo preview · illustrative data')).toBeVisible();
  for (const name of ['Incident Management', 'Service Desk', 'SA AI', 'SLA Monitoring', 'Workflow Automation', 'Analytics', 'Knowledge Base']) {
    await expect(page.locator('#capabilities').getByText(name, { exact: true })).toBeVisible();
  }
  await expect(page.getByRole('heading', { name: 'How It Works' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Built for Enterprise Reliability' })).toBeVisible();
  // No unverified claims.
  const body = (await page.locator('body').innerText()).toUpperCase();
  for (const claim of ['SOC-2', 'SOC 2', 'ISO 27001', '99.9%', '500+']) expect(body, claim).not.toContain(claim);
  // The public page never calls protected APIs.
  expect(apiCalls.filter((p) => !['/api/health', '/api/auth/refresh', '/api/public/info'].includes(p))).toEqual([]);
  expect(errors).toEqual([]);
});

test('capability cards scroll to their public explainers and footer links open real pages', async ({ page }) => {
  await page.goto('/');
  await page.locator('#capabilities').getByText('Workflow Automation', { exact: true }).click();
  await expect(page.locator('#workflow-automation')).toBeInViewport();
  for (const [link, path, heading] of [
    ['Privacy Policy', '/privacy', /Privacy/],
    ['Terms and Conditions', '/terms', /Terms/],
    ['Documentation', '/docs', /product overview|Documentation/],
    ['Support', '/support', /How can we help|Support/],
  ] as const) {
    await page.goto('/');
    await page.getByRole('contentinfo').getByRole('link', { name: link, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${path}$`));
    await expect(page.getByRole('heading', { level: 1 })).toContainText(heading);
  }
  await expect(page.getByText('Enterprise Operations Platform').first()).toBeVisible();
});

test('Enter Workspace and Login lead to sign-in, and signed-in visitors get Open Workspace', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: /Enter Workspace/ }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto('/');
  await page.getByRole('banner').getByRole('link', { name: 'Login', exact: true }).click();
  await expect(page).toHaveURL(/\/login/);

  await login(page, 'priya.raman');
  await page.goto('/');
  await expect(page.getByRole('banner').getByRole('link', { name: 'Login', exact: true })).toHaveCount(0);
  await page.getByRole('banner').getByRole('link', { name: /Open Workspace/ }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Operations Command Center');
});

test('landing page is usable on a phone without horizontal scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await page.getByRole('button', { name: 'Open menu' }).click();
  const drawer = page.getByRole('dialog', { name: 'Site menu' });
  await expect(drawer).toBeVisible();
  await drawer.getByRole('link', { name: /Login|Access Workspace/ }).first().click();
  await expect(page).toHaveURL(/\/login/);
});
