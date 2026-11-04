import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { EmailStatusDto, OutboxItem } from '../../lib/types';
import { EmailTab } from './EmailTab';
import { SystemHealthTab } from './SystemHealthTab';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const NOT_CONFIGURED: EmailStatusDto = { configured: false, fromAddress: null, pending: 0, sentLast24h: 0, failedLast24h: 0, skippedLast24h: 7, lastSentAt: null, lastRunAt: '2026-11-04T07:59:00Z' };
const OUTBOX: OutboxItem[] = [
  { id: 1, category: 'INVITATION', to: 'new.user@nexus360.test', subject: 'You are invited to SA', status: 'SKIPPED', attempts: 0, nextAttemptAt: null, lastError: null, sentAt: null, createdAt: '2026-11-04T07:00:00Z', sensitive: true, attachmentName: null },
  { id: 2, category: 'REPORT', to: 'cio@nexus360.test', subject: 'Weekly SLA report', status: 'FAILED', attempts: 5, nextAttemptAt: null, lastError: 'Connection refused', sentAt: null, createdAt: '2026-11-03T07:00:00Z', sensitive: false, attachmentName: 'sla.pdf' },
];

function setup(status: EmailStatusDto = NOT_CONFIGURED) {
  mockApi({
    'GET /admin/email/status': json(status),
    'GET /admin/email/outbox': json({ content: OUTBOX, page: 0, size: 20, totalElements: 2, totalPages: 1 }),
    'POST /admin/email/test': { status: 409, data: apiErrorBody(409, 'E-mail is not configured') },
  });
}

describe('EmailTab', () => {
  it('explains that e-mail is not configured and lists the outbox', async () => {
    setup();
    renderWithProviders(<EmailTab />);
    expect(await screen.findByText('E-mail is not configured.')).toBeInTheDocument();
    expect(screen.getAllByText('Not configured').length).toBeGreaterThan(0);
    expect(screen.getByText('7 skipped (no SMTP)')).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'E-mail outbox' });
    expect(within(table).getByText('You are invited to SA')).toBeInTheDocument();
    expect(within(table).getByText('One-time link')).toBeInTheDocument();
    expect(within(table).getByLabelText('Last error: Connection refused')).toBeInTheDocument();
    // Test e-mail is offered to System Admins but disabled without SMTP.
    expect(screen.getByRole('button', { name: /Send test/ })).toBeDisabled();
  });

  it('filters the outbox by status', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<EmailTab />);
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Status' }), 'FAILED');
    await waitFor(() => expect(last('GET', '/admin/email/outbox')?.params).toMatchObject({ status: 'FAILED', page: 0 }));
  });

  it('explains a 409 from the test endpoint', async () => {
    setup({ ...NOT_CONFIGURED, configured: true, fromAddress: 'sa@nexus360.test' });
    const user = userEvent.setup();
    renderWithProviders(<EmailTab />);
    expect(await screen.findByText(/SMTP is configured/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Send test/ }));
    await waitFor(() => expect(last('POST', '/admin/email/test')?.body).toEqual({ to: 'system_admin@nexus360.test' }));
    expect(await screen.findByText(/E-mail is not configured on the server/)).toBeInTheDocument();
  });

  it('hides the test e-mail for IT admins', async () => {
    setup();
    renderWithProviders(<EmailTab />, { auth: makeAuth(makeUser('IT_ADMIN')) });
    await screen.findByRole('table', { name: 'E-mail outbox' });
    expect(screen.queryByRole('button', { name: /Send test/ })).not.toBeInTheDocument();
  });
});

describe('SystemHealthTab (Release 3)', () => {
  it('shows the e-mail outbox and the report scheduler', async () => {
    mockApi({
      'GET /admin/system-health': json({
        status: 'UP',
        version: '3.0.0',
        uptimeSeconds: 3600,
        database: { status: 'UP', latencyMs: 3, schemaVersion: '6' },
        redis: { enabled: false, status: 'UNKNOWN' },
        slaScheduler: { enabled: true, lastRunAt: '2026-11-04T07:59:00Z' },
        workflowEngine: { pendingRetries: 0, failedLast24h: 0 },
        ai: { provider: 'none', keyConfigured: false },
        counts: { users: 10, incidents: 20, activeIncidents: 4 },
        email: { configured: false, status: 'NOT_CONFIGURED', pending: 2, failedLast24h: 0, lastSentAt: null },
        reportScheduler: { enabled: true, lastRunAt: '2026-11-04T07:00:00Z' },
      }),
    });
    renderWithProviders(<SystemHealthTab />);
    const email = await screen.findByRole('region', { name: 'E-mail' });
    expect(within(email).getByText('Not configured')).toBeInTheDocument();
    expect(within(email).getByText('2')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Report scheduler' })).getByText('Running')).toBeInTheDocument();
  });
});
