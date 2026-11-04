import { expect, test, type Page } from '@playwright/test';
import { API_URL, login, logout, trackPageErrors } from './support';

/**
 * Account access without self-registration: a visitor requests access, an administrator approves it and hands over a
 * single-use invitation link, the invitee sets a password and signs in. Forgotten passwords follow the same
 * administrator-mediated path with a reset link.
 */

const NEW_PASSWORD = 'Ledger-Review-2026';

async function signIn(page: Page, email: string, password: string) {
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
}

test('access request → administrator approval → invitation → registration → sign in', async ({ page, request }) => {
  const errors = trackPageErrors(page);
  const stamp = Date.now();
  const fullName = `Nisha Rao ${stamp}`;
  const email = `nisha.rao.${stamp}@example.com`;

  await page.goto('/register');
  await page.getByLabel('Full name').fill(fullName);
  await page.getByLabel('Work e-mail').fill(email);
  await page.getByLabel('Department').fill('Finance');
  await page.getByLabel('Job title').fill('Payroll Analyst');
  await page.getByLabel('Why do you need access?').fill('I raise and track payroll incidents for the finance team.');
  await page.getByRole('button', { name: 'Send request' }).click();
  await expect(page.getByText('Request received')).toBeVisible();

  await login(page, 'admin');
  await page.goto('/users?tab=access-requests');
  await page.getByRole('button', { name: `Approve request from ${fullName}` }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Role').selectOption('EMPLOYEE');
  await dialog.getByLabel('Department').selectOption({ label: 'Finance' });
  await dialog.getByRole('button', { name: 'Approve and create link' }).click();
  const link = await page.getByRole('textbox', { name: 'Invitation link' }).inputValue();
  expect(link).toMatch(/\/register\?invite=[A-Za-z0-9_-]{20,}$/);
  const token = new URL(link).searchParams.get('invite')!;
  await page.getByRole('button', { name: /Done — I have handed it over/ }).click();
  await logout(page);

  await page.goto(link);
  await expect(page.getByText(email)).toBeVisible();
  await expect(page).not.toHaveURL(/invite=/); // the token is removed from the address bar
  await page.getByLabel('Full name').fill(fullName);
  await page.getByLabel('Password', { exact: true }).fill(NEW_PASSWORD);
  await page.getByLabel('Confirm password').fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByText('Account created')).toBeVisible();
  await page.getByRole('link', { name: /Sign in/ }).click();
  await expect(page.getByLabel('Email')).toHaveValue(email);
  await signIn(page, email, NEW_PASSWORD);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Your IT Service Hub');

  // The invitation is single use.
  expect((await request.get(`${API_URL}/auth/invitations/${token}`)).status()).toBe(404);
  expect(errors).toEqual([]);
});

test('forgotten password → administrator reset link → new password → sign in', async ({ page }) => {
  const email = 'manoj.verma@demo.nexus360.io';
  const password = `Fresh-Start-${Date.now()}`;

  await page.goto('/login');
  await page.getByRole('link', { name: 'Forgot password?' }).click();
  await expect(page).toHaveURL(/\/forgot-password$/);
  await page.getByLabel('Email').fill(email);
  await page.getByRole('button', { name: 'Request a reset link' }).click();
  await expect(page.getByText('Request received')).toBeVisible();

  await login(page, 'admin');
  await page.goto('/users?tab=access-requests');
  await page.getByRole('button', { name: 'Approve request from Manoj Verma' }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Create reset link' }).click();
  const link = await page.getByRole('textbox', { name: 'Password reset link' }).inputValue();
  expect(link).toMatch(/\/reset-password\?token=/);
  await page.getByRole('button', { name: /Done — I have handed it over/ }).click();
  await logout(page);

  await page.goto(link);
  await expect(page.getByText(email)).toBeVisible();
  await page.getByLabel('New password', { exact: true }).fill(password);
  await page.getByLabel('Confirm new password').fill(password);
  await page.getByRole('button', { name: 'Set new password' }).click();
  await page.getByRole('link', { name: /Sign in/ }).click();
  await expect(page.getByText('Your password has been changed')).toBeVisible();
  await signIn(page, email, password);

  // The reset link cannot be used twice.
  await logout(page);
  await page.goto(link);
  await expect(page.getByRole('heading', { name: 'This reset link is invalid or has expired' })).toBeVisible();
});

test('public account pages answer generically and sign-out is confirmed', async ({ page }) => {
  await page.goto('/forgot-password');
  await page.getByLabel('Email').fill(`nobody.${Date.now()}@example.com`);
  await page.getByRole('button', { name: 'Request a reset link' }).click();
  await expect(page.getByText('Request received')).toBeVisible(); // same answer as for a real account

  await page.goto('/register?invite=not-a-real-token-000000000000');
  await expect(page.getByRole('heading', { name: 'This invitation link is invalid or has expired' })).toBeVisible();

  await login(page, 'sneha.patil');
  await page.goto('/login');
  await expect(page).toHaveURL(/\/dashboard$/); // signed-in users skip the sign-in page
  await logout(page);
  await expect(page.getByText('You have signed out.')).toBeVisible();
});
