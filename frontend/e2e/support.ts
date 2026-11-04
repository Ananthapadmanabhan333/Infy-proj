import { expect, type APIRequestContext, type Page } from '@playwright/test';

/** Demo password of the local/CI stack (APP_DEMO_PASSWORD); never a production credential. */
export const DEMO_PASSWORD = process.env.E2E_DEMO_PASSWORD ?? 'Nexus360-Demo!';
export const API_URL = process.env.E2E_API_URL ?? 'http://localhost:8080/api';

export const email = (user: string) => `${user}@demo.nexus360.io`;

/** Sidebar link by label; links can carry a live count badge, so match on the leading text. */
export const navLink = (page: Page, label: string) =>
  page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`) });

export async function login(page: Page, user: string) {
  await page.goto('/login');
  await page.getByLabel('Email', { exact: true }).fill(email(user));
  await page.getByLabel('Password', { exact: true }).fill(DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).not.toHaveURL(/\/login/);
  await expect(page.locator('button.profile')).toBeVisible();
}

export async function logout(page: Page) {
  await page.locator('button.profile').click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
}

/** API helpers for lookups and assertions that the UI does not display directly. */
export async function apiToken(request: APIRequestContext, user: string): Promise<string> {
  const response = await request.post(`${API_URL}/auth/login`, {
    data: { email: email(user), password: DEMO_PASSWORD },
    headers: { 'X-Requested-With': 'nexus360' },
  });
  expect(response.ok(), `login ${user} -> ${response.status()}`).toBeTruthy();
  return (await response.json()).accessToken as string;
}

export async function apiGet<T>(request: APIRequestContext, token: string, path: string): Promise<T> {
  const response = await request.get(`${API_URL}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), `${path} -> ${response.status()}`).toBeTruthy();
  return (await response.json()) as T;
}

export interface IncidentRow {
  id: number;
  number: string;
  status: string;
  priority: string;
  parentNumber: string | null;
}

export async function findIncident(request: APIRequestContext, token: string, number: string): Promise<IncidentRow> {
  const page = await apiGet<{ content: IncidentRow[] }>(request, token, `/incidents?search=${encodeURIComponent(number)}&size=5`);
  const match = page.content.find((row) => row.number === number);
  expect(match, `incident ${number} is listed`).toBeTruthy();
  return match!;
}

/** Collects uncaught page errors and Content-Security-Policy violations so a test can assert none happened. */
export function trackPageErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy/i.test(message.text())) errors.push(message.text());
  });
  return errors;
}
