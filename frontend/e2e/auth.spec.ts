import { expect, test } from '@playwright/test';
import { DEMO_PASSWORD, email, login } from './support';

test('login screen lists clearly labelled demo accounts', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Demo accounts' })).toBeVisible();
  await expect(page.getByText(email('admin'))).toBeVisible();
});

test('wrong password shows an error and stays on login', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email('vishal.kumar'));
  await page.getByLabel('Password').fill('definitely-wrong-1');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toContainText('Invalid email or password');
  await expect(page).toHaveURL(/\/login/);
});

test('client-side validation runs before the API is called', async ({ page }) => {
  let loginCalls = 0;
  page.on('request', (request) => {
    if (request.url().includes('/api/auth/login')) loginCalls += 1;
  });
  await page.goto('/login');
  await page.getByLabel('Email').fill('not-an-email');
  await page.getByLabel('Password').fill(DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/login/);
  await expect(page.locator('[data-invalid="true"]').first()).toBeVisible();
  expect(loginCalls).toBe(0);
});

test('protected routes redirect to login and return after sign-in', async ({ page }) => {
  await page.goto('/sla-monitor');
  await expect(page).toHaveURL(/\/login\?next=%2Fsla-monitor/);
  await page.getByLabel('Email').fill(email('sunita.joshi'));
  await page.getByLabel('Password').fill(DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/sla-monitor$/);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('SLA');
});

test('system admin reaches the Command Center with live data and can reload the session', async ({ page }) => {
  await login(page, 'admin');
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('heading', { level: 1, name: /Operations Command Center/ })).toBeVisible();
  await expect(page.getByText('Total Incidents').first()).toBeVisible();
  await expect(page.locator('button.profile')).toContainText('System Admin');
  await page.reload(); // refresh cookie restores the session
  await expect(page.getByRole('heading', { level: 1, name: /Operations Command Center/ })).toBeVisible();
});
