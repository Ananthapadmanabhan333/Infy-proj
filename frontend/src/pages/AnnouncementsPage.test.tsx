import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { InternalAxiosRequestConfig } from 'axios';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../test/utils';
import type { AnnouncementDto } from '../lib/types';
import AnnouncementsPage, { announcementState } from './AnnouncementsPage';
import ProfilePage from './ProfilePage';

const NOW = Date.parse('2026-11-03T12:00:00Z');

const ANNOUNCEMENTS: AnnouncementDto[] = [
  {
    id: 1,
    title: 'Payroll maintenance tonight',
    body: 'Payroll is read-only from 22:00.',
    severity: 'MAINTENANCE',
    serviceId: 4,
    serviceName: 'Payroll',
    startsAt: null,
    endsAt: null,
    published: true,
    authorName: 'Priya Raman',
    createdAt: '2026-11-03T08:00:00Z',
    demo: false,
  },
  {
    id: 2,
    title: 'Old VPN outage',
    body: 'Resolved.',
    severity: 'CRITICAL',
    serviceId: null,
    serviceName: null,
    startsAt: '2026-10-01T08:00:00Z',
    endsAt: '2026-10-01T12:00:00Z',
    published: true,
    authorName: null,
    createdAt: '2026-10-01T08:00:00Z',
    demo: true,
  },
];

function setup() {
  mockApi({
    'GET /announcements': (config: InternalAxiosRequestConfig) =>
      json((config.params as Record<string, unknown> | undefined)?.activeOnly ? ANNOUNCEMENTS.slice(0, 1) : ANNOUNCEMENTS),
    'DELETE /announcements/1': { status: 204 },
    'GET /notifications/unread-count': json({ count: 4 }),
  });
}

describe('announcementState', () => {
  it('classifies drafts, scheduled, ended and live notices', () => {
    expect(announcementState({ published: false, startsAt: null, endsAt: null }, NOW)).toBe('draft');
    expect(announcementState({ published: true, startsAt: '2026-11-04T00:00:00Z', endsAt: null }, NOW)).toBe('scheduled');
    expect(announcementState({ published: true, startsAt: null, endsAt: '2026-11-02T00:00:00Z' }, NOW)).toBe('ended');
    expect(announcementState({ published: true, startsAt: null, endsAt: null }, NOW)).toBe('live');
  });
});

describe('AnnouncementsPage', () => {
  it('shows active announcements with tab counts and lets managers delete', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<AnnouncementsPage />, { route: '/announcements', auth: makeAuth(makeUser('IT_MANAGER')) });
    const card = await screen.findByRole('article', { name: 'Payroll maintenance tonight' });
    expect(card).toHaveTextContent('Payroll');
    expect(card).toHaveTextContent('Live');
    await waitFor(() => expect(screen.getByRole('tab', { name: /All \(including past\)/ })).toHaveTextContent('(2)'));
    await user.click(within(card).getByRole('button', { name: 'Delete Payroll maintenance tonight' }));
    await user.click(within(await screen.findByRole('dialog', { name: 'Delete announcement?' })).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(requests.some((request) => request.method === 'DELETE' && request.url === '/announcements/1')).toBe(true));
  });

  it('is read-only for employees and shows past notices on the All tab', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<AnnouncementsPage />, { route: '/announcements', auth: makeAuth(makeUser('EMPLOYEE')) });
    await screen.findByRole('article', { name: 'Payroll maintenance tonight' });
    expect(screen.queryByRole('button', { name: /New Announcement/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Edit / })).not.toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: /All \(including past\)/ }));
    expect(await screen.findByRole('article', { name: 'Old VPN outage' })).toHaveTextContent('Ended');
  });
});

describe('ProfilePage', () => {
  it('shows the account with role label, description and the password form', async () => {
    setup();
    renderWithProviders(<ProfilePage />, { route: '/profile', auth: makeAuth(makeUser('INCIDENT_MANAGER', { organisation: { name: 'Acme Corp' } })) });
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Your profile');
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getAllByText(/Incident Manager/).length).toBeGreaterThan(0);
    expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /Change password/ })).toBeInTheDocument();
    expect(await screen.findByText('4 unread')).toBeInTheDocument();
  });
});
