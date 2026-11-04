import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { renderWithProviders } from '../../test/utils';
import { NotificationBell } from './NotificationBell';

const notifications = [
  { id: 1, type: 'SLA_BREACHED', title: 'SLA breached', message: 'INC-7846 passed its target', link: '/incidents/46', severity: 'CRITICAL', read: false, createdAt: '2026-11-03T10:00:00Z' },
  { id: 2, type: 'APPROVAL_DECIDED', title: 'Request approved', message: 'REQ-3921 was approved', link: null, severity: 'SUCCESS', read: true, createdAt: '2026-11-03T09:00:00Z' },
];

describe('<NotificationBell />', () => {
  it('shows the unread badge and opens the notifications panel', async () => {
    mockApi({
      'GET /notifications/unread-count': json({ count: 1 }),
      'GET /notifications': json({ content: notifications, page: 0, size: 8, totalElements: 2, totalPages: 1 }),
      'POST /notifications/read-all': json(null),
    });
    const user = userEvent.setup();
    renderWithProviders(<NotificationBell />);
    const bell = await screen.findByRole('button', { name: 'Notifications, 1 unread' });
    await user.click(bell);
    const panel = screen.getByRole('region', { name: 'Recent notifications' });
    expect(await within(panel).findByText('SLA breached')).toBeInTheDocument();
    expect(within(panel).getByText('Request approved')).toBeInTheDocument();
    expect(within(panel).getByRole('tab', { name: 'Unread (1)' })).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: 'View all notifications' })).toHaveAttribute('href', '/notifications');

    await user.click(within(panel).getByRole('tab', { name: 'Unread (1)' }));
    expect(requests.some((request) => request.url === '/notifications' && (request.params as Record<string, unknown>)?.unreadOnly === true)).toBe(true);

    await user.click(within(panel).getByRole('button', { name: /Mark all read/ }));
    expect(requests.some((request) => request.method === 'POST' && request.url === '/notifications/read-all')).toBe(true);
  });
});
