import { expect, test } from '@playwright/test';
import { login, trackPageErrors } from './support';

test('new incident form validates input, shows the intake result and accepts comments', async ({ page }) => {
  const errors = trackPageErrors(page);
  await login(page, 'sneha.patil');
  await page.getByRole('button', { name: 'New Incident' }).first().click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Title').fill('abc');
  await dialog.getByRole('button', { name: 'Create incident' }).click();
  await expect(dialog.getByText('Title must be at least 5 characters')).toBeVisible();

  const title = `Meeting room display shows no signal ${Date.now()}`;
  await dialog.getByLabel('Title').fill(title);
  await dialog.getByLabel('Description').fill('The screen in the board room shows no signal from the HDMI input.');
  await dialog.getByRole('button', { name: 'Create incident' }).click();
  await expect(dialog.getByText('was logged and the SLA clock has started')).toBeVisible();
  await dialog.getByRole('button', { name: 'View incident' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText(title);

  // Employees can comment publicly on their own incident.
  await page.getByLabel('Add a comment').fill('It is the large screen on the left of the room.');
  await page.getByRole('button', { name: 'Post comment' }).click();
  await expect(page.getByText('It is the large screen on the left of the room.')).toBeVisible();
  expect(errors).toEqual([]);
});
