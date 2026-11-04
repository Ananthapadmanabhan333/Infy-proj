import { expect, test, type Page } from '@playwright/test';
import { apiGet, apiToken, findIncident, login, logout, trackPageErrors } from './support';

/**
 * Acceptance scenario through the UI: several employees report the same payroll outage in their own words; SA
 * detects the duplicates, groups them under a parent, raises the priority, routes it to the owning department, starts
 * the SLA, recommends knowledge, escalates, records the resolution (closing every report) and updates analytics.
 */

const REPORTS = [
  { user: 'amit.shah', title: 'Payroll portal not loading', description: 'Since 10 am the payroll portal keeps spinning and then shows error 503.', affected: '1' },
  { user: 'divya.pillai', title: 'Cannot access payroll application - error 503', description: 'HTTP 503 Service Unavailable when opening payroll. My team has the same problem.', affected: '4' },
  { user: 'fatima.khan', title: 'Payroll site down for our whole team', description: 'Nobody can open the payroll application; the page fails with a 503 error. Salary processing is blocked.', affected: '25' },
];

async function report(page: Page, r: (typeof REPORTS)[number]): Promise<{ number: string; parent: string | null }> {
  await login(page, r.user);
  await page.getByRole('button', { name: 'New Incident' }).first().click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Title').fill(r.title);
  await dialog.getByLabel('Description').fill(r.description);
  await dialog.getByLabel('Affected users').fill(r.affected);
  await dialog.getByRole('button', { name: 'Create incident' }).click();
  await expect(dialog.getByText('was logged and the SLA clock has started')).toBeVisible();
  const number = ((await dialog.locator('b', { hasText: /^INC-\d+$/ }).first().textContent()) ?? '').trim();
  const linked = dialog.getByText(/Linked to parent INC-\d+/);
  const parent = (await linked.count()) > 0 ? ((await linked.first().textContent())?.match(/INC-\d+/)?.[0] ?? null) : null;
  await dialog.getByRole('button', { name: 'View incident' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText(number);
  await logout(page);
  return { number, parent };
}

test('payroll outage reported by employees is grouped, prioritised, routed, escalated and resolved', async ({ page, request }) => {
  const errors = trackPageErrors(page);
  const admin = await apiToken(request, 'admin');
  const before = await apiGet<{ kpis: { resolvedInPeriod: number } }>(request, admin, '/analytics/dashboard?days=7');

  // 1. Employees report the same outage in their own words.
  const results: { number: string; parent: string | null }[] = [];
  for (const r of REPORTS) results.push(await report(page, r));

  // 2–3. Duplicate detection links the reports under one parent incident.
  const parentNumber = results.map((r) => r.parent).find((p): p is string => p !== null);
  expect(parentNumber, 'a parent incident groups the reports').toBeTruthy();
  expect(results.filter((r) => r.parent === parentNumber).length).toBeGreaterThanOrEqual(2);
  const parent = await findIncident(request, admin, parentNumber!);

  // 4–6. The manager of the owning team sees a P1 major incident with a running 24x7 SLA.
  await login(page, 'priya.raman');
  await page.goto(`/incidents/${parent.id}`);
  const heading = page.locator('.page-heading');
  await expect(heading.getByRole('heading', { level: 1 })).toContainText(parentNumber!);
  await expect(heading.getByText('Critical', { exact: true })).toBeVisible();
  await expect(heading.getByText('Major incident')).toBeVisible();
  await expect(heading.getByText(/\d+ child reports/)).toBeVisible();
  await expect(page.getByText('Enterprise Applications').first()).toBeVisible();
  await expect(page.getByText(/P1 Critical/).first()).toBeVisible();

  // 7. SA AI recommends the payroll troubleshooting guide and labels the deterministic engine honestly.
  const aiTab = page.getByRole('tab', { name: /AI Recommendation/ });
  await aiTab.click();
  await page.getByRole('button', { name: 'Recommended knowledge' }).click();
  const recommendation = page.getByRole('article', { name: 'Recommended knowledge' });
  await expect(recommendation.getByText('Payroll application troubleshooting guide')).toBeVisible();
  // The E2E stack runs without an LLM key, so the source must be labelled as the deterministic engine.
  await expect(recommendation.getByText('Deterministic fallback — not AI-generated')).toBeVisible();

  // 8. Escalation to the department manager is recorded.
  await page.getByRole('toolbar', { name: 'Incident actions' }).getByRole('button', { name: 'Escalate' }).click();
  let dialog = page.getByRole('dialog');
  await dialog.getByLabel('Reason').fill('Payroll run for the whole company is blocked.');
  await dialog.getByRole('button', { name: 'Escalate' }).click();
  await expect(page.getByText('Escalation level').filter({ visible: true }).first()).toBeVisible();

  // 9. Work starts, then the resolution cites the recommended article and closes every linked report.
  await page.getByRole('toolbar', { name: 'Incident actions' }).getByRole('button', { name: 'Start Progress' }).click();
  await expect(heading.getByText('In Progress')).toBeVisible();
  await aiTab.click();
  await page.getByRole('button', { name: 'Recommended knowledge' }).click();
  await recommendation
    .locator('.list-row', { hasText: 'Payroll application troubleshooting guide' })
    .getByRole('button', { name: 'Use as resolution article' })
    .click();
  dialog = page.getByRole('dialog');
  await dialog.getByLabel('Resolution code').selectOption('FIXED');
  await dialog.getByLabel('Resolution notes').fill('Restarted the payroll application nodes and raised the database connection pool limit.');
  await expect(dialog.getByLabel('Knowledge article')).not.toHaveValue('');
  await dialog.getByRole('button', { name: 'Confirm resolution' }).click();
  await expect(heading.getByText('Resolved')).toBeVisible();
  await logout(page);

  // Each employee's own report is resolved together with the parent.
  for (const [i, r] of REPORTS.entries()) {
    if (results[i].parent !== parentNumber) continue;
    const token = await apiToken(request, r.user);
    expect((await findIncident(request, token, results[i].number)).status).toBe('RESOLVED');
  }

  // Analytics are recalculated from stored records.
  const after = await apiGet<{ kpis: { resolvedInPeriod: number } }>(request, admin, '/analytics/dashboard?days=7');
  expect(after.kpis.resolvedInPeriod).toBeGreaterThan(before.kpis.resolvedInPeriod);
  expect(errors).toEqual([]);
});

test('demo scenario runner drives the real engines and the SLA engine escalates a missed response', async ({ page }) => {
  const errors = trackPageErrors(page);
  await login(page, 'admin');
  await page.getByRole('button', { name: 'Run payroll outage demo' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Run scenario' }).click();
  const result = page.getByRole('dialog', { name: /Payroll outage scenario/ });
  await expect(result.getByText('Duplicate reports detected')).toBeVisible();
  await expect(result.getByText('Department assigned')).toBeVisible();
  const open = result.getByRole('link', { name: /^Open INC-\d+$/ });
  const parentNumber = ((await open.textContent()) ?? '').replace('Open', '').trim();
  await open.click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText(parentNumber);

  // No one responds within the 15 minute P1 target: advance the demo clock and let the SLA engine act.
  await page.goto('/settings?tab=demo');
  await page.getByLabel('Demo incident number').fill(parentNumber);
  await page.getByLabel('Minutes to advance').fill('30');
  await page.getByRole('button', { name: 'Simulate elapsed time' }).click();
  await expect(page.getByText(`Simulated 30 minutes on ${parentNumber}`)).toBeVisible();
  await page.getByRole('button', { name: `Open ${parentNumber}` }).or(page.getByRole('link', { name: `Open ${parentNumber}` })).first().click();
  await expect(page.getByText('Breached — no response')).toBeVisible();
  await expect(page.getByText(/Escalation level/).first()).toBeVisible();
  expect(errors).toEqual([]);
});
