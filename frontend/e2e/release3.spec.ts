import { expect, test } from '@playwright/test';
import { API_URL, apiGet, apiToken, login, trackPageErrors } from './support';

/**
 * Release 3 acceptance flows against the running stack (demo data, docs/RELEASE3_SPEC.md):
 * a signed monitoring webhook becomes an alert, a responder acknowledges it and converts it to an incident,
 * and the operations modules render live data.
 */

interface SourceSaveResult {
  source: { id: number; webhookPath: string };
  secret: string;
}

interface WebhookResult {
  outcome: string;
  alerts: { id: number; number: string; action: string; incidentNumber: string | null }[];
}

test('a Prometheus alert with a valid secret is ingested, acknowledged and converted to an incident', async ({ page, request }) => {
  const errors = trackPageErrors(page);
  const admin = await apiToken(request, 'admin');
  const auth = { Authorization: `Bearer ${admin}`, 'X-Requested-With': 'nexus360' };
  const stamp = Date.now();

  const created = await request.post(`${API_URL}/alerts/sources`, {
    headers: auth,
    data: { name: `E2E Alertmanager ${stamp}`, provider: 'PROMETHEUS', defaultSeverity: 'MINOR' },
  });
  expect(created.status(), await created.text()).toBe(201);
  const { source, secret } = (await created.json()) as SourceSaveResult;
  expect(secret).toMatch(/^alsec_/);

  const alertname = `E2EQueueBacklog${stamp}`;
  const payload = {
    version: '4',
    status: 'firing',
    receiver: 'sa',
    groupKey: `{}:{alertname="${alertname}"}`,
    alerts: [
      {
        status: 'firing',
        labels: { alertname, severity: 'warning', instance: 'e2e-worker-01:9100' },
        annotations: { summary: `E2E queue backlog ${stamp}`, description: 'Synthetic alert sent by the end-to-end suite.' },
        startsAt: new Date().toISOString(),
        endsAt: '0001-01-01T00:00:00Z',
        generatorURL: 'http://prometheus.example.test/graph',
        fingerprint: `e2e${stamp}`,
      },
    ],
  };

  // Wrong credentials are rejected without detail.
  const rejected = await request.post(`${API_URL}/alerts/webhooks/${source.id}`, {
    headers: { Authorization: 'Bearer alsec_wrong' },
    data: payload,
  });
  expect(rejected.status()).toBe(401);

  const delivered = await request.post(`${API_URL}/alerts/webhooks/${source.id}`, {
    headers: { Authorization: `Bearer ${secret}` },
    data: payload,
  });
  expect(delivered.status(), await delivered.text()).toBe(200);
  const result = (await delivered.json()) as WebhookResult;
  expect(result.outcome).toBe('PROCESSED');
  expect(result.alerts).toHaveLength(1);
  const alert = result.alerts[0];
  expect(alert.action).toBe('CREATED');
  expect(alert.incidentNumber).toBeNull();

  // The same delivery again only counts an occurrence.
  const repeat = await request.post(`${API_URL}/alerts/webhooks/${source.id}`, {
    headers: { Authorization: `Bearer ${secret}` },
    data: payload,
  });
  expect(((await repeat.json()) as WebhookResult).alerts[0].id).toBe(alert.id);

  await login(page, 'admin');
  await page.goto(`/alerts/${alert.id}`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(`E2E queue backlog ${stamp}`);
  await page.getByRole('button', { name: 'Acknowledge' }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Acknowledge' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText('Acknowledged').first()).toBeVisible();

  await page.getByRole('button', { name: 'Convert to incident' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Create incident' }).click();
  await expect(page.getByRole('link', { name: /INC-\d+/ }).first()).toBeVisible();

  const detail = await apiGet<{ alert: { status: string }; incident: { number: string } | null }>(request, admin, `/alerts/${alert.id}`);
  expect(detail.alert.status).toBe('ACKNOWLEDGED');
  expect(detail.incident?.number).toMatch(/^INC-/);
  expect(errors).toEqual([]);
});

test('the public status page renders without signing in', async ({ page }) => {
  const errors = trackPageErrors(page);
  await page.goto('/status');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Components' })).toBeVisible();
  await expect(page).not.toHaveURL(/\/login/);
  expect(errors).toEqual([]);
});

test('operations modules show live demo data to an incident manager', async ({ page }) => {
  const errors = trackPageErrors(page);
  await login(page, 'admin');

  await page.goto('/on-call');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('On-call');
  await expect(page.getByText(/Primary/).first()).toBeVisible();

  await page.goto('/major-incidents');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Major Incidents');
  await expect(page.getByText(/^MI-\d+$/).first()).toBeVisible();

  await page.goto('/cmdb?tab=map');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('CMDB');

  await page.goto('/changes');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Changes');
  await expect(page.getByText('CHG-5001').first()).toBeVisible();

  await page.goto('/analytics');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Analytics');
  expect(errors).toEqual([]);
});
