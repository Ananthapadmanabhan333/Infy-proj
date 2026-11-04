import { defineConfig } from '@playwright/test';

/**
 * End-to-end tests against a running stack:
 *   API:      the backend on :8080 with demo data (locally `mvn spring-boot:test-run
 *             -Dspring-boot.run.main-class=com.nexus360.LocalDevApplication`, embedded PostgreSQL)
 *   Frontend: a fresh production build served by `vite preview` on :4173 (proxies /api to :8080),
 *             unless E2E_BASE_URL points at an already running deployment.
 * Locally the installed Google Chrome is used; CI installs Playwright's Chromium.
 */
const PREVIEW_URL = 'http://localhost:4173';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? PREVIEW_URL,
    channel: process.env.CI ? undefined : 'chrome',
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : { command: 'npm run build && npm run preview', url: PREVIEW_URL, reuseExistingServer: !process.env.CI, timeout: 180_000 },
});
