import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { NotificationPreferencesPanel } from '../admin/NotificationPreferencesPanel';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { NotificationPreference, SessionDto } from '../../lib/types';
import { SessionsPanel, UserSessionsSection } from './SessionsPanel';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

function session(id: number, overrides: Partial<SessionDto> = {}): SessionDto {
  return {
    id,
    device: 'Chrome on Windows',
    browser: 'Chrome',
    os: 'Windows',
    userAgent: 'Mozilla/5.0',
    ipAddress: '10.0.0.1',
    lastIpAddress: '10.0.0.1',
    createdAt: '2026-11-01T08:00:00Z',
    lastUsedAt: '2026-11-04T08:00:00Z',
    expiresAt: '2026-12-01T08:00:00Z',
    rememberMe: true,
    current: false,
    ...overrides,
  };
}

const SESSIONS = [session(1, { current: true }), session(2, { device: 'Safari on iOS', os: 'iOS', rememberMe: false, lastIpAddress: '10.0.0.9' })];

describe('SessionsPanel', () => {
  it('lists own sessions with the current badge and revokes one', async () => {
    mockApi({ 'GET /auth/sessions': json(SESSIONS), 'DELETE /auth/sessions/2': { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<SessionsPanel />);
    const list = await screen.findByRole('list', { name: 'Active sessions' });
    expect(within(list).getByText('This device')).toBeInTheDocument();
    expect(within(list).getByText('Browser session')).toBeInTheDocument();
    expect(within(list).getByText(/10\.0\.0\.1 → 10\.0\.0\.9/)).toBeInTheDocument();
    await user.click(within(list).getByRole('button', { name: 'Revoke Safari on iOS' }));
    await user.click(within(await screen.findByRole('dialog', { name: 'Revoke this session?' })).getByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(last('DELETE', '/auth/sessions/2')).toBeDefined());
  });

  it('signs out every other session', async () => {
    mockApi({ 'GET /auth/sessions': json(SESSIONS), 'POST /auth/sessions/revoke-others': json({ revoked: 1 }) });
    const user = userEvent.setup();
    renderWithProviders(<SessionsPanel />);
    await user.click(await screen.findByRole('button', { name: /Sign out other sessions/ }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Sign out others' }));
    await waitFor(() => expect(last('POST', '/auth/sessions/revoke-others')).toBeDefined());
    expect(await screen.findByText('1 other session signed out')).toBeInTheDocument();
  });

  it('signing out the current device logs out', async () => {
    mockApi({ 'GET /auth/sessions': json([session(1, { current: true })]), 'DELETE /auth/sessions/1': { status: 204 } });
    const auth = makeAuth(makeUser('EMPLOYEE'));
    const user = userEvent.setup();
    renderWithProviders(<SessionsPanel />, { auth });
    expect(screen.queryByRole('button', { name: /Sign out other sessions/ })).not.toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Sign out of this device' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(auth.logout).toHaveBeenCalled());
  });

  it('lets admins revoke every session of a user', async () => {
    mockApi({ 'GET /users/5/sessions': json([session(3)]), 'POST /users/5/sessions/revoke': json({ revoked: 1 }) });
    const user = userEvent.setup();
    renderWithProviders(<UserSessionsSection userId={5} userName="Kiran Rao" canRevoke />);
    expect(await screen.findByRole('list', { name: 'Sessions of Kiran Rao' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Sign out everywhere/ }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Sign out everywhere' }));
    await waitFor(() => expect(last('POST', '/users/5/sessions/revoke')).toBeDefined());
  });
});

const PREFERENCES: NotificationPreference[] = [
  { type: 'INCIDENT_ASSIGNED', label: 'Incident assigned', description: 'Work assigned to you', inApp: true, email: true, emailDefault: true },
  { type: 'TEAM_QUEUE', label: 'Team queue', description: 'Unassigned work in my team', inApp: true, email: false, emailDefault: false },
];

describe('NotificationPreferencesPanel (e-mail channel)', () => {
  it('saves both channels per type', async () => {
    mockApi({
      'GET /notifications/preferences': json(PREFERENCES),
      'PUT /notifications/preferences': json(PREFERENCES),
      'GET /status-page': json({ emailConfigured: true }),
    });
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferencesPanel />, { auth: makeAuth(makeUser('SUPPORT_AGENT')) });
    const emailToggle = await screen.findByRole('switch', { name: 'Team queue e-mail' });
    await waitFor(() => expect(emailToggle).toBeEnabled());
    await user.click(emailToggle);
    await user.click(screen.getByRole('button', { name: /Save preferences/ }));
    await waitFor(() =>
      expect(last('PUT', '/notifications/preferences')?.body).toEqual({
        INCIDENT_ASSIGNED: { inApp: true, email: true },
        TEAM_QUEUE: { inApp: true, email: true },
      }),
    );
  });

  it('disables the e-mail column with an explanation when e-mail is not configured', async () => {
    mockApi({
      'GET /notifications/preferences': json(PREFERENCES),
      'GET /admin/system-health': json({ status: 'UP', email: { configured: false, status: 'NOT_CONFIGURED', pending: 0, failedLast24h: 0, lastSentAt: null } }),
    });
    renderWithProviders(<NotificationPreferencesPanel />, { auth: makeAuth(makeUser('SYSTEM_ADMIN')) });
    expect(await screen.findByText(/E-mail is not configured on this server/)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Team queue e-mail' })).toBeDisabled();
    expect(screen.getByRole('switch', { name: 'Team queue in-app' })).toBeEnabled();
  });
});
