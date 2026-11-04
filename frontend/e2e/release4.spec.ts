import { expect, test } from '@playwright/test';
import { API_URL, apiGet, apiToken, login, logout, trackPageErrors } from './support';

test('Release 4 end-to-end journeys: simulation -> observability -> twin -> agents -> remediation -> security -> finops -> dex', async ({
  page,
  request,
}) => {
  const errors = trackPageErrors(page);
  const adminToken = await apiToken(request, 'admin');
  const headers = { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' };

  // 1. Capture baseline analytics KPIs before simulation mode generates any simulated records.
  const kpisBefore = await apiGet<{ kpis: { totalIncidents: number } }>(request, adminToken, '/analytics/overview?days=30');

  // Enable simulation mode and run PAYROLL_DB_SATURATION ticks -> anomaly detection -> correlated alert -> incident.
  await request.put(`${API_URL}/simulation/mode`, { headers, data: { enabled: true } });
  await request.post(`${API_URL}/simulation/scenarios/PAYROLL_DB_SATURATION/start`, { headers });
  for (let i = 0; i < 9; i++) {
    await request.post(`${API_URL}/simulation/scenarios/PAYROLL_DB_SATURATION/tick`, { headers });
  }
  await request.post(`${API_URL}/observability/anomaly-detectors/run`, { headers });

  // Verify default analytics KPIs are unchanged by simulated incidents/alerts.
  const kpisAfter = await apiGet<{ kpis: { totalIncidents: number } }>(request, adminToken, '/analytics/overview?days=30');
  expect(kpisAfter.kpis.totalIncidents).toBe(kpisBefore.kpis.totalIncidents);

  // Sign in and verify Observability shows the simulated anomaly & correlation group when Include simulated data is on.
  await login(page, 'admin');
  await page.goto('/observability?tab=anomalies&sim=1');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Observability');
  await expect(page.getByText('db.client.connections.usage').first()).toBeVisible();
  await expect(page.getByText('SIMULATION').first()).toBeVisible();

  // 2. Digital Twin failure simulation.
  const graphRes = await apiGet<{ nodes: { id: number; name: string }[] }>(
    request,
    adminToken,
    '/twin/graph?layer=ALL&depth=5&includeState=true&includeSimulated=true',
  );
  expect(graphRes.nodes.length).toBeGreaterThan(0);
  const twinSimRes = await request.post(`${API_URL}/twin/simulations`, {
    headers,
    data: {
      type: 'FAILURE',
      mode: 'HARD',
      targets: [graphRes.nodes[0].id],
      name: 'E2E Payroll DB Failure',
    },
  });
  expect(twinSimRes.ok()).toBeTruthy();

  await page.goto('/digital-twin?tab=simulate');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Digital Twin');
  await page.getByRole('list', { name: 'Simulation history' }).getByRole('button').first().click();
  await expect(page.getByText(/Simulation — no real systems were changed/i).first()).toBeVisible();

  // 3. SA Agentic AI: run incident investigation -> fallback labelled -> propose action -> approval created.
  const incidentsPage = await apiGet<{ content: { id: number; number: string }[] }>(
    request,
    adminToken,
    '/incidents?size=5',
  );
  const targetIncidentId = incidentsPage.content[0].id;
  const runRes = await request.post(`${API_URL}/agents/runs`, {
    headers,
    data: {
      runType: 'INVESTIGATE_INCIDENT',
      incidentId: targetIncidentId,
      includeSimulated: true,
    },
  });
  expect(runRes.ok()).toBeTruthy();
  const runDetail = (await runRes.json()) as {
    run: { id: number; status: string; source: string };
    actions: { id: number; status: string }[];
  };
  expect(runDetail.run.status).toBe('SUCCEEDED');
  expect(runDetail.run.source).toBe('FALLBACK');
  if (runDetail.actions.length > 0) {
    const propRes = await request.post(
      `${API_URL}/agents/runs/${runDetail.run.id}/actions/${runDetail.actions[0].id}/propose`,
      { headers },
    );
    expect(propRes.ok()).toBeTruthy();
  }

  await page.goto('/agents');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('SA Agents');
  await expect(page.getByText(/Deterministic fallback/i).first()).toBeVisible();

  // 4. Autonomous Remediation: playbook execution with SIMULATED connector, rollback on simulated step failure, and hash-chain audit verify.
  const playbooks = await apiGet<{ id: number; code: string }[]>(request, adminToken, '/remediation/playbooks');
  expect(playbooks.length).toBeGreaterThan(0);
  const rollbackRunRes = await request.post(`${API_URL}/remediation/runs`, {
    headers,
    data: {
      playbookId: playbooks[0].id,
      targetKey: 'payroll-db-primary',
      environment: 'STAGING',
      incidentId: targetIncidentId,
      triggerKind: 'MANUAL',
      parameters: {},
      simulateFailureAtStep: 1,
      overrideReason: 'E2E automated verification of step failure rollback',
    },
  });
  expect(rollbackRunRes.ok()).toBeTruthy();
  const rollbackRun = (await rollbackRunRes.json()) as { run: { status: string } };
  expect(rollbackRun.run.status).toBe('ROLLED_BACK');

  await page.goto('/remediation?tab=audit&sim=1');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Remediation');
  await page.getByRole('button', { name: /Verify hash chain/i }).click();
  await expect(page.getByText(/Hash chain verified|CHAIN VALID/i).first()).toBeVisible();

  // 5. Security Operations: vulnerability ingestion -> risk ranking.
  await page.goto('/security?tab=findings');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Security Operations');
  await page.getByRole('button', { name: /Ingest Findings/i }).click();
  await expect(page.getByText('CVE-2026-4101').first()).toBeVisible();

  // 6. FinOps: FOCUS 1.x CSV dry run -> apply -> budget & cost overview.
  await page.goto('/finops?tab=ingestion');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('FinOps');
  await page.getByRole('button', { name: /Validate \(Dry-Run\)/i }).click();
  await expect(page.getByText(/DRY-RUN RESULT/i)).toBeVisible();
  await page.getByRole('button', { name: /Apply Cost Ingestion/i }).click();
  await expect(page.getByText(/APPLIED/i)).toBeVisible();

  // 7. Digital Employee Experience (DEX): ingest endpoint telemetry -> experience score & employee portal card.
  const dexIngest = await request.post(`${API_URL}/dex/ingest/endpoints`, {
    headers,
    data: {
      deviceId: 'DEV-E2E-01',
      userEmail: 'kavya.reddy@demo.nexus360.io',
      userName: 'Kavya Reddy',
      departmentName: 'Human Resources',
      osName: 'Windows 11',
      osVersion: '23H2',
      deviceModel: 'ThinkPad X1 Carbon',
      cpuUsagePct: 32,
      memoryUsagePct: 54,
      diskUsagePct: 48,
      bootTimeSeconds: 21,
      crashCount7d: 0,
      batteryHealthPct: 96,
    },
  });
  expect(dexIngest.ok()).toBeTruthy();

  await page.goto('/dex');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Employee Experience');
  await expect(page.getByText(/0\.35 [*×] deviceHealth/i).first()).toBeVisible();

  // Clean up simulated data and switch simulation mode off.
  await request.delete(`${API_URL}/simulation/data`, { headers });
  await request.put(`${API_URL}/simulation/mode`, { headers, data: { enabled: false } });
  expect(errors).toEqual([]);
});

test('Release 4 pages render cleanly at mobile 375 px viewport and employee portal shows DEX device card', async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  await page.setViewportSize({ width: 375, height: 812 });
  await login(page, 'admin');

  for (const [path, heading] of [
    ['/observability', 'Observability'],
    ['/digital-twin', 'Digital Twin'],
    ['/predictions', 'Predictions'],
    ['/agents', 'SA Agents'],
    ['/remediation', 'Remediation'],
    ['/security', 'Security Operations'],
    ['/finops', 'FinOps'],
    ['/dex', 'Employee Experience'],
  ] as const) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 }), path).toContainText(heading);
    await expect(page.locator('.loading-state, [aria-busy="true"]').first(), path).toHaveCount(0, { timeout: 15_000 });
  }

  await logout(page);
  await login(page, 'kavya.reddy');
  await page.goto('/portal');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Employee Portal');
  await expect(page.getByText('My Device Health (DEX)')).toBeVisible();
  expect(errors).toEqual([]);
});
