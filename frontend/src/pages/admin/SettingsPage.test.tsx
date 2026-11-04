import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { AiConfigDto, CatalogCategoryDto, CatalogItemDto, IncidentCategoryConfig, SettingDto, SystemHealthDto } from '../../lib/types';
import { parseKeywords, toCategoryUpdates } from '../settings/IncidentCategoriesTab';
import SettingsPage, { settingsTabsFor } from './SettingsPage';

const CATEGORIES: IncidentCategoryConfig[] = [
  { code: 'NETWORK', name: 'Network', description: 'Connectivity', defaultDepartmentId: 3, defaultDepartmentName: 'Network Ops', keywords: ['vpn'], active: true },
  { code: 'EMAIL', name: 'Email', description: null, defaultDepartmentId: null, defaultDepartmentName: null, keywords: [], active: true },
];

const SETTINGS: SettingDto[] = [
  { key: 'org.name', value: 'Acme Corp', type: 'STRING', description: 'Organisation name', updatedAt: '2026-11-01T10:00:00Z' },
  { key: 'org.support_email', value: 'help@acme.test', type: 'STRING', description: null, updatedAt: '2026-11-01T10:00:00Z' },
  { key: 'org.timezone', value: 'Asia/Kolkata', type: 'STRING', description: null, updatedAt: '2026-11-01T10:00:00Z' },
  { key: 'org.business_hours_note', value: '', type: 'STRING', description: null, updatedAt: '2026-11-01T10:00:00Z' },
  { key: 'notifications.sla_at_risk.notify_managers', value: 'true', type: 'BOOLEAN', description: null, updatedAt: '2026-11-01T10:00:00Z' },
  { key: 'incident.assignment.auto', value: 'true', type: 'BOOLEAN', description: 'Auto-assign new incidents', updatedAt: '2026-11-01T10:00:00Z' },
];

const HEALTH: SystemHealthDto = {
  status: 'UP',
  version: '2.0.0',
  uptimeSeconds: 93_784,
  database: { status: 'UP', latencyMs: 4, schemaVersion: '27' },
  redis: { enabled: false, status: 'UNKNOWN' },
  slaScheduler: { enabled: true, lastRunAt: '2026-11-03T10:00:00Z' },
  workflowEngine: { pendingRetries: 1, failedLast24h: 2 },
  ai: { provider: 'ANTHROPIC', keyConfigured: false },
  counts: { users: 42, incidents: 310, activeIncidents: 17 },
};

const AI: AiConfigDto = { provider: 'ANTHROPIC', model: 'claude-test', keyConfigured: false, llmEnabled: false, rateLimitPerMinute: 30, timeoutSeconds: 20 };

const CATALOG_CATEGORIES: CatalogCategoryDto[] = [{ id: 1, code: 'HARDWARE', name: 'Hardware', description: null, icon: 'Laptop', itemCount: 1 }];
const CATALOG_ITEMS: CatalogItemDto[] = [
  {
    id: 9,
    categoryId: 1,
    categoryName: 'Hardware',
    name: 'New laptop',
    description: 'Standard laptop',
    requiresApproval: true,
    approverRole: 'IT_MANAGER',
    fulfillmentHours: 48,
    fulfillmentDepartmentId: 3,
    fulfillmentDepartmentName: 'IT Service Desk',
    formFields: [{ key: 'model', label: 'Model', type: 'select', required: true, options: ['13"', '15"'] }],
    active: true,
  },
];

function setup() {
  mockApi({
    'GET /admin/incident-categories': json(CATEGORIES),
    'PUT /admin/incident-categories': (config) => json(JSON.parse(String(config.data))),
    'GET /admin/settings': json(SETTINGS),
    'PUT /admin/settings/org.name': (config) => json({ ...SETTINGS[0], value: JSON.parse(String(config.data)).value }),
    'GET /admin/system-health': json(HEALTH),
    'GET /admin/ai': json(AI),
    'GET /departments': json([{ id: 3, code: 'NET', name: 'Network Ops', description: null, managerId: null, managerName: null, email: null, memberCount: 4, demo: false }]),
    'GET /catalog/categories': json(CATALOG_CATEGORIES),
    'GET /catalog/items': json(CATALOG_ITEMS),
    'PUT /catalog/items/9': (config) => json({ ...CATALOG_ITEMS[0], ...JSON.parse(String(config.data)) }),
    'GET /sla/policies': json([]),
    'GET /sla/calendars': json([]),
  });
}

function tabNames() {
  return within(screen.getByRole('tablist', { name: 'Settings sections' }))
    .getAllByRole('tab')
    .map((tab) => tab.textContent);
}

describe('settings helpers', () => {
  it('builds the FULL incident category list for PUT, replacing only the edited row', () => {
    const body = toCategoryUpdates(CATEGORIES, { code: 'EMAIL', name: 'E-mail', description: null, defaultDepartmentId: 3, keywords: ['outlook'], active: false });
    expect(body).toEqual([
      { code: 'NETWORK', name: 'Network', description: 'Connectivity', defaultDepartmentId: 3, keywords: ['vpn'], active: true },
      { code: 'EMAIL', name: 'E-mail', description: null, defaultDepartmentId: 3, keywords: ['outlook'], active: false },
    ]);
    expect(parseKeywords('vpn, Wi-Fi ,, VPN\nlatency')).toEqual(['vpn', 'Wi-Fi', 'latency']);
  });

  it('gates tabs by role', () => {
    expect(settingsTabsFor('SYSTEM_ADMIN').map((tab) => tab.id)).toEqual(['organisation', 'sla', 'business-hours', 'categories', 'catalogue', 'notifications', 'ai', 'email', 'health', 'demo', 'simulation']);
    expect(settingsTabsFor('IT_ADMIN').map((tab) => tab.id)).toEqual(['sla', 'business-hours', 'categories', 'catalogue', 'email', 'health']);
    expect(settingsTabsFor('IT_MANAGER')).toEqual([]);
  });
});

describe('SettingsPage', () => {
  it('shows IT admins only their configuration tabs', async () => {
    setup();
    renderWithProviders(<SettingsPage />, { route: '/settings', auth: makeAuth(makeUser('IT_ADMIN')) });
    expect(tabNames()).toEqual(['SLA Policies', 'Business Hours', 'Incident Categories', 'Service Catalogue', 'E-mail', 'System Health']);
  });

  it('saves a category edit by sending the full list', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<SettingsPage />, { route: '/settings?tab=categories' });
    await user.click(await screen.findByRole('button', { name: 'Edit category Email' }));
    const drawer = await screen.findByRole('dialog', { name: 'Email' });
    await user.type(within(drawer).getByLabelText(/Extra classifier keywords/), 'outlook, smtp');
    await user.click(within(drawer).getByRole('button', { name: 'Save category' }));
    await waitFor(() => expect(requests.some((request) => request.method === 'PUT')).toBe(true));
    const put = requests.find((request) => request.method === 'PUT' && request.url === '/admin/incident-categories');
    expect(put?.body).toEqual([
      { code: 'NETWORK', name: 'Network', description: 'Connectivity', defaultDepartmentId: 3, keywords: ['vpn'], active: true },
      { code: 'EMAIL', name: 'Email', description: null, defaultDepartmentId: null, keywords: ['outlook', 'smtp'], active: true },
    ]);
  });

  it('toggles a category inline with the full list', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<SettingsPage />, { route: '/settings?tab=categories' });
    await user.click(await screen.findByRole('switch', { name: 'Network active' }));
    await waitFor(() => expect(requests.some((request) => request.method === 'PUT')).toBe(true));
    const put = requests.find((request) => request.method === 'PUT');
    expect((put?.body as { code: string; active: boolean }[]).map((row) => [row.code, row.active])).toEqual([
      ['NETWORK', false],
      ['EMAIL', true],
    ]);
  });

  it('shows AI configuration without any API key input and explains the fallback', async () => {
    setup();
    renderWithProviders(<SettingsPage />, { route: '/settings?tab=ai' });
    expect(await screen.findByText('claude-test')).toBeInTheDocument();
    expect(screen.getByText('Not configured')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Use the language model' })).toBeDisabled();
    expect(screen.getByText(/deterministic fallback engine/, { selector: 'b' })).toBeInTheDocument();
    expect(document.querySelector('input[type="password"], input[name*="key" i]')).toBeNull();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('renders system health tiles and refreshes every 30 seconds', async () => {
    setup();
    renderWithProviders(<SettingsPage />, { route: '/settings?tab=health' });
    expect(await screen.findByRole('region', { name: 'Database' })).toHaveTextContent('4 ms');
    expect(screen.getByRole('region', { name: 'Database' })).toHaveTextContent('27');
    expect(screen.getByRole('region', { name: 'Workflow engine' })).toHaveTextContent('2 failed');
    expect(screen.getByRole('region', { name: 'Redis' })).toHaveTextContent('Disabled');
    expect(screen.getByText('1 d 2 h', { selector: 'b' })).toBeInTheDocument();
    expect(screen.getByText(/refreshes every 30 s/)).toBeInTheDocument();
  });

  it('saves changed organisation settings key by key', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<SettingsPage />, { route: '/settings' });
    const name = await screen.findByLabelText(/Organisation name/);
    expect(name).toHaveValue('Acme Corp');
    await user.clear(name);
    await user.type(name, 'Acme Group');
    await user.click(screen.getByRole('button', { name: /Save organisation/ }));
    await waitFor(() => expect(requests.some((request) => request.method === 'PUT' && request.url === '/admin/settings/org.name')).toBe(true));
    const puts = requests.filter((request) => request.method === 'PUT');
    expect(puts).toHaveLength(1);
    expect(puts[0].body).toEqual({ value: 'Acme Group' });
  });

  it('manages catalogue items, including the inline active toggle', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<SettingsPage />, { route: '/settings?tab=catalogue' });
    const table = await screen.findByRole('table', { name: 'Catalogue items' });
    expect(within(table).getByText('IT Manager')).toBeInTheDocument();
    await user.click(within(table).getByRole('switch', { name: 'New laptop active' }));
    await waitFor(() => expect(requests.some((request) => request.method === 'PUT' && request.url === '/catalog/items/9')).toBe(true));
    const put = requests.find((request) => request.method === 'PUT');
    expect(put?.body).toMatchObject({ categoryId: 1, approverRole: 'IT_MANAGER', fulfillmentDepartmentId: 3, fulfillmentHours: 48, active: false });
    await user.click(within(table).getByRole('button', { name: 'Edit item New laptop' }));
    const modal = await screen.findByRole('dialog', { name: 'Edit New laptop' });
    const approver = within(modal).getByLabelText('Approver');
    expect(within(approver).getAllByRole('option').map((option) => option.textContent)).toEqual(['No approval', 'IT Manager', 'System Admin']);
  });

  it('keeps the demo tool labels used by the end-to-end tests', async () => {
    setup();
    renderWithProviders(<SettingsPage />, { route: '/settings?tab=demo' });
    expect(await screen.findByLabelText('Demo incident number')).toBeInTheDocument();
    expect(screen.getByLabelText('Minutes to advance')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Simulate elapsed time' })).toBeInTheDocument();
  });
});
