import { expect, test, type APIRequestContext } from '@playwright/test';
import { API_URL, apiGet, apiToken, login, logout, trackPageErrors } from './support';

/**
 * Release 3 approval and on-call journeys: records are prepared through the API, the human decision is taken in the UI
 * by a different user than the requester, and the outcome is checked through the API.
 */

type Json = Record<string, unknown>;

async function call<T>(request: APIRequestContext, token: string, method: 'POST' | 'PUT', path: string, data?: Json): Promise<T> {
  const response = await request.fetch(`${API_URL}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'X-Requested-With': 'nexus360' },
    data: data ?? {},
  });
  expect(response.ok(), `${method} ${path} -> ${response.status()} ${await response.text()}`).toBeTruthy();
  return (await response.json()) as T;
}

/** Title of the approval about `number`, as the approvals inbox shows it. */
async function approvalTitle(request: APIRequestContext, adminToken: string, number: string): Promise<string> {
  const page = await apiGet<{ content: { approval?: { title: string }; title?: string }[] }>(
    request,
    adminToken,
    `/approvals?scope=all&status=PENDING&search=${encodeURIComponent(number)}&size=5`,
  );
  const row = page.content[0];
  const title = row?.approval?.title ?? row?.title;
  expect(title, `pending approval for ${number}`).toBeTruthy();
  return title!;
}

async function approveInInbox(page: import('@playwright/test').Page, title: string) {
  await page.goto('/approvals');
  const list = page.getByRole('list', { name: 'Tasks waiting for me' });
  await list.getByRole('button', { name: `Approve ${title}` }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Approve' }).click();
  await expect(list.getByRole('button', { name: `Approve ${title}` })).toHaveCount(0);
}

test('a normal change goes through assessment and is approved by the change authority in the inbox', async ({ page, request }) => {
  const errors = trackPageErrors(page);
  const admin = await apiToken(request, 'admin');
  const changeManager = await apiToken(request, 'anil.kumar');
  const stamp = Date.now();

  const services = await apiGet<{ id: number; tier?: string }[] | { content: { id: number }[] }>(request, admin, '/services');
  const serviceId = (Array.isArray(services) ? services : services.content)[0].id;
  const questions = await apiGet<{ key: string; options: { value: string; points: number }[] }[]>(request, admin, '/changes/risk-questions');
  const riskAnswers = Object.fromEntries(
    questions.map((q) => [q.key, [...q.options].sort((a, b) => a.points - b.points)[0].value]),
  );
  // A window two weeks out at 02:00 UTC, two hours long — outside business hours and clear of the demo freeze.
  const start = new Date(Date.now() + 14 * 24 * 3600_000);
  start.setUTCHours(2, 0, 0, 0);
  const end = new Date(start.getTime() + 2 * 3600_000);

  const change = await call<{ id: number; number: string; status: string }>(request, changeManager, 'POST', '/changes', {
    title: `E2E certificate rotation ${stamp}`,
    description: 'Rotate the TLS certificate on the internal load balancer (end-to-end test).',
    type: 'NORMAL',
    impact: 'LOW',
    implementationPlan: 'Upload the new certificate, switch the listener, verify the handshake.',
    rollbackPlan: 'Switch the listener back to the previous certificate.',
    testPlan: 'TLS handshake check from two networks.',
    plannedStart: start.toISOString(),
    plannedEnd: end.toISOString(),
    serviceIds: [serviceId],
    riskAnswers,
  });
  expect(change.status).toBe('DRAFT');
  for (const status of ['SUBMITTED', 'ASSESSMENT', 'AWAITING_APPROVAL']) {
    const moved = await call<{ status: string }>(request, changeManager, 'POST', `/changes/${change.id}/transition`, { status, riskAnswers });
    expect(moved.status).toBe(status);
  }

  const title = await approvalTitle(request, admin, change.number);
  // The requester cannot decide their own change (segregation of duties) — the inbox does not offer it.
  await login(page, 'anil.kumar');
  await page.goto('/approvals');
  await expect(page.getByRole('list', { name: 'Tasks waiting for me' }).or(page.getByText(/nothing|no tasks/i)).first()).toBeVisible();
  await expect(page.getByRole('button', { name: `Approve ${title}` })).toHaveCount(0);
  await logout(page);

  await login(page, 'priya.raman');
  await approveInInbox(page, title);

  const decided = await apiGet<{ status: string }>(request, admin, `/changes/${change.id}`);
  expect(decided.status).toBe('APPROVED');
  await page.goto(`/changes/${change.id}`);
  await expect(page.getByText(change.number).first()).toBeVisible();
  expect(errors).toEqual([]);
});

test('a post-incident review is completed, submitted and approved by an incident manager', async ({ page, request }) => {
  const errors = trackPageErrors(page);
  const admin = await apiToken(request, 'admin');

  // A resolved demo incident without a review.
  const resolved = await apiGet<{ content: { id: number; number: string; parentNumber: string | null }[] }>(
    request,
    admin,
    '/incidents?status=RESOLVED,CLOSED&parentOnly=true&size=50',
  );
  let review: { id: number; number: string } | null = null;
  for (const incident of resolved.content) {
    const response = await request.post(`${API_URL}/reviews`, {
      headers: { Authorization: `Bearer ${admin}`, 'X-Requested-With': 'nexus360' },
      data: { incidentId: incident.id, title: `E2E review of ${incident.number}` },
    });
    if (response.status() === 201) {
      review = (await response.json()) as { id: number; number: string };
      break;
    }
  }
  expect(review, 'a resolved incident without a review').toBeTruthy();

  // Submitting without the required analysis is refused with field errors.
  const early = await request.post(`${API_URL}/reviews/${review!.id}/submit`, {
    headers: { Authorization: `Bearer ${admin}`, 'X-Requested-With': 'nexus360' },
  });
  expect(early.status()).toBe(400);

  await call(request, admin, 'PUT', `/reviews/${review!.id}`, {
    summary: 'Users could not open the portal for 40 minutes after a configuration push.',
    rootCause: 'A connection-pool limit was lowered by an unreviewed configuration change.',
    impact: 'About 300 employees could not submit requests; no data was lost.',
    contributingFactors: ['No pre-deployment check of pool limits'],
    lessonsLearned: 'Configuration pushes need the same review as code changes.',
  });
  await call(request, admin, 'POST', `/reviews/${review!.id}/actions`, {
    title: 'Add a pool-limit check to the deployment pipeline',
    actionType: 'PREVENTIVE',
  });
  const submitted = await call<{ status: string }>(request, admin, 'POST', `/reviews/${review!.id}/submit`);
  expect(submitted.status).toBe('IN_REVIEW');

  const title = await approvalTitle(request, admin, review!.number);
  await login(page, 'sunita.joshi');
  await approveInInbox(page, title);

  const approved = await apiGet<{ status: string; approvedByName: string | null }>(request, admin, `/reviews/${review!.id}`);
  expect(approved.status).toBe('APPROVED');
  await page.goto(`/reviews/${review!.id}`);
  await expect(page.getByText(review!.number).first()).toBeVisible();
  await expect(page.getByText(/Approved/).first()).toBeVisible();
  expect(errors).toEqual([]);
});

test('an on-call participant offers a shift and a colleague takes it from My on-call', async ({ page, request }) => {
  const errors = trackPageErrors(page);
  const admin = await apiToken(request, 'admin');
  const schedules = await apiGet<{ id: number; name: string; primaryCount: number; active: boolean }[]>(request, admin, '/oncall/schedules?active=true');
  let chosen: { id: number; name: string; offerer: string; taker: string; takerName: string } | null = null;
  for (const schedule of schedules.filter((s) => s.primaryCount >= 2)) {
    const detail = await apiGet<{ primary: { userId: number; userName: string; email: string; active: boolean }[] }>(
      request,
      admin,
      `/oncall/schedules/${schedule.id}`,
    );
    const people = detail.primary.filter((p) => p.active && p.email.endsWith('@demo.nexus360.io'));
    if (people.length >= 2) {
      chosen = {
        id: schedule.id,
        name: schedule.name,
        offerer: people[0].email.split('@')[0],
        taker: people[1].email.split('@')[0],
        takerName: people[1].userName,
      };
      break;
    }
  }
  expect(chosen, 'a schedule with two primary participants').toBeTruthy();

  const offererToken = await apiToken(request, chosen!.offerer);
  const me = await apiGet<{ id: number }>(request, offererToken, '/auth/me');
  const startsAt = new Date(Date.now() + 20 * 24 * 3600_000);
  startsAt.setUTCMinutes(0, 0, 0);
  const endsAt = new Date(startsAt.getTime() + 8 * 3600_000);
  const offer = await call<{ id: number; status: string }>(request, offererToken, 'POST', `/oncall/schedules/${chosen!.id}/overrides`, {
    offer: true,
    originalUserId: me.id,
    startsAt: startsAt.toISOString(),
    endsAt: endsAt.toISOString(),
    reason: 'E2E: family event',
  });
  expect(offer.status).toBe('OFFERED');

  await login(page, chosen!.taker);
  await page.goto('/on-call?tab=mine');
  const take = page.getByRole('button', { name: `Take offer on ${chosen!.name}` }).first();
  await expect(take).toBeVisible();
  await take.click();
  await expect.poll(async () => {
    const overrides = await apiGet<{ id: number; status: string; userName: string }[]>(request, admin, `/oncall/schedules/${chosen!.id}/overrides`);
    return overrides.find((o) => o.id === offer.id)?.status;
  }).toBe('ACTIVE');
  const overrides = await apiGet<{ id: number; userName: string }[]>(request, admin, `/oncall/schedules/${chosen!.id}/overrides`);
  expect(overrides.find((o) => o.id === offer.id)?.userName).toBe(chosen!.takerName);
  expect(errors).toEqual([]);
});
