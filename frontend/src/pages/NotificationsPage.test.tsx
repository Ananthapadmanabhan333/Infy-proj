import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { InternalAxiosRequestConfig } from 'axios';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../test/mockApi';
import { renderWithProviders } from '../test/utils';
import type { NotificationDto, NotificationPreference, PageResponse } from '../lib/types';
import NotificationsPage from './NotificationsPage';

const NOTIFICATIONS: NotificationDto[] = [
  { id: 1, type: 'SLA_BREACHED', title: 'SLA breached on INC-7846', message: 'Resolution target missed', link: '/incidents/12', severity: 'CRITICAL', read: false, createdAt: '2026-11-03T09:00:00Z' },
  { id: 2, type: 'INCIDENT_ASSIGNED', title: 'INC-7840 assigned to you', message: 'Payroll latency', link: null, severity: 'INFO', read: true, createdAt: '2026-11-02T09:00:00Z' },
];

const PREFERENCES: NotificationPreference[] = [
  { type: 'SLA_BREACHED', label: 'SLA breached', description: 'An SLA target was missed', inApp: true },
  { type: 'ANNOUNCEMENT', label: 'Announcements', description: 'Service notices', inApp: false },
];

function page(content: NotificationDto[]): PageResponse<NotificationDto> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 };
}

function setup() {
  mockApi({
    'GET /notifications': (config: InternalAxiosRequestConfig) => {
      const params = (config.params ?? {}) as Record<string, unknown>;
      return json(page(params.unreadOnly ? NOTIFICATIONS.filter((n) => !n.read) : NOTIFICATIONS));
    },
    'GET /notifications/unread-count': json({ count: 1 }),
    'GET /notifications/preferences': json(PREFERENCES),
    'PUT /notifications/preferences': (config) => json(PREFERENCES.map((p) => ({ ...p, inApp: JSON.parse(String(config.data))[p.type] }))),
    'POST /notifications/1/read': { status: 204 },
    'POST /notifications/read-all': { status: 204 },
  });
}

function renderPage(route = '/notifications') {
  return renderWithProviders(
    <Routes>
      <Route path="/notifications" element={<NotificationsPage />} />
      <Route path="/incidents/:id" element={<h1>Incident page</h1>} />
    </Routes>,
    { route },
  );
}

describe('NotificationsPage', () => {
  it('lists notifications with severity, type labels and unread state', async () => {
    setup();
    renderPage();
    const list = await screen.findByRole('list', { name: 'Notifications' });
    expect(within(list).getByRole('article', { name: 'Unread: SLA breached on INC-7846' })).toHaveTextContent('Critical');
    expect(within(list).getByRole('article', { name: 'INC-7840 assigned to you' })).toHaveTextContent('Incident Assigned');
    expect(await screen.findByRole('tab', { name: /Unread/ })).toHaveTextContent('(1)');
  });

  it('shows only unread notifications on the Unread tab', async () => {
    setup();
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('list', { name: 'Notifications' });
    await user.click(screen.getByRole('tab', { name: /Unread/ }));
    await waitFor(() => expect(screen.queryByRole('article', { name: 'INC-7840 assigned to you' })).not.toBeInTheDocument());
    expect(requests.some((request) => request.url === '/notifications' && (request.params as Record<string, unknown>).unreadOnly === true)).toBe(true);
  });

  it('marks one or all notifications read and opens links', async () => {
    setup();
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('list', { name: 'Notifications' });
    await user.click(screen.getByRole('button', { name: 'Mark all read' }));
    await waitFor(() => expect(requests.some((request) => request.method === 'POST' && request.url === '/notifications/read-all')).toBe(true));
    await user.click(screen.getByRole('button', { name: /Open/ }));
    expect(await screen.findByRole('heading', { name: 'Incident page' })).toBeInTheDocument();
    expect(requests.some((request) => request.method === 'POST' && request.url === '/notifications/1/read')).toBe(true);
  });

  it('saves notification preferences as a type → boolean map', async () => {
    setup();
    const user = userEvent.setup();
    renderPage();
    const toggle = await screen.findByRole('switch', { name: 'Announcements' });
    expect(screen.getByRole('button', { name: /Save preferences/ })).toBeDisabled();
    await user.click(toggle);
    await user.click(screen.getByRole('button', { name: /Save preferences/ }));
    await waitFor(() => expect(requests.some((request) => request.method === 'PUT')).toBe(true));
    expect(requests.find((request) => request.method === 'PUT')?.body).toEqual({ SLA_BREACHED: true, ANNOUNCEMENT: true });
    expect(await screen.findByText('Notification preferences saved')).toBeInTheDocument();
  });
});
