import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { InternalAxiosRequestConfig } from 'axios';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { AuditLogDto, PageResponse } from '../../lib/types';
import AuditLogsPage, { dayBoundary, localDate } from './AuditLogsPage';

const EVENTS: AuditLogDto[] = [
  {
    id: 41,
    actorEmail: 'rajesh.iyer@nexus360.test',
    action: 'USER_ROLE_CHANGED',
    entityType: 'USER',
    entityId: '12',
    details: { from: 'SUPPORT_AGENT', to: 'IT_MANAGER' },
    ipAddress: '10.0.0.4',
    outcome: 'SUCCESS',
    createdAt: '2026-11-03T09:30:00Z',
  },
  {
    id: 42,
    actorEmail: 'kavya.reddy@nexus360.test',
    action: 'ACCESS_DENIED',
    entityType: 'SETTING',
    entityId: null,
    details: null,
    ipAddress: null,
    outcome: 'DENIED',
    createdAt: '2026-11-03T09:40:00Z',
  },
];

function page(content: AuditLogDto[], totalElements = content.length): PageResponse<AuditLogDto> {
  return { content, page: 0, size: 20, totalElements, totalPages: 1 };
}

function setup() {
  mockApi({
    'GET /audit-logs': (config: InternalAxiosRequestConfig) => {
      const params = (config.params ?? {}) as Record<string, unknown>;
      if (params.size === 1) {
        if (params.outcome === 'FAILURE') return json(page([], 3));
        if (params.outcome === 'DENIED') return json(page([], 5));
        return json(page([], 57));
      }
      const rows = params.outcome ? EVENTS.filter((event) => event.outcome === params.outcome) : EVENTS;
      return json(page(rows));
    },
    'GET /audit-logs/actions': json(['ACCESS_DENIED', 'USER_ROLE_CHANGED']),
  });
}

describe('audit date helpers', () => {
  it('turns local calendar dates into inclusive ISO boundaries', () => {
    const start = dayBoundary('2026-11-03', false);
    const end = dayBoundary('2026-11-03', true);
    expect(start && new Date(start).getDate()).toBe(3);
    expect(end && new Date(end).getDate()).toBe(3);
    expect(end && start && new Date(end).getTime() - new Date(start).getTime()).toBe(86_400_000 - 1);
    expect(dayBoundary('', false)).toBeUndefined();
    expect(localDate(new Date(2026, 0, 5))).toBe('2026-01-05');
  });
});

describe('AuditLogsPage', () => {
  it('shows KPI counts derived from the list endpoint and the event table', async () => {
    setup();
    renderWithProviders(<AuditLogsPage />, { route: '/audit-logs', auth: makeAuth(makeUser('AUDITOR')) });
    const table = await screen.findByRole('table', { name: 'Audit log' });
    expect(within(table).getByText('User Role Changed')).toBeInTheDocument();
    expect(within(table).getByText('Denied')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('link', { name: /^Events today:/ })).toHaveTextContent('57'));
    expect(screen.getByRole('link', { name: /^Failures today:/ })).toHaveTextContent('3');
    expect(screen.getByRole('link', { name: /^Access denied today:/ })).toHaveTextContent('5');
    const todayRequest = requests.find((request) => request.url === '/audit-logs' && (request.params as Record<string, unknown>).size === 1);
    expect((todayRequest?.params as Record<string, unknown>).from).toBe(dayBoundary(localDate(), false));
  });

  it('sends the outcome filter to the API', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<AuditLogsPage />, { route: '/audit-logs' });
    await screen.findByRole('table', { name: 'Audit log' });
    await user.selectOptions(screen.getByLabelText('Outcome'), 'DENIED');
    await waitFor(() =>
      expect(requests.some((request) => request.url === '/audit-logs' && (request.params as Record<string, unknown>).outcome === 'DENIED' && (request.params as Record<string, unknown>).size === 20)).toBe(true),
    );
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Audit log' })).queryByText('User Role Changed')).not.toBeInTheDocument());
  });

  it('opens a detail drawer with the JSON details', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<AuditLogsPage />, { route: '/audit-logs' });
    await user.click(await screen.findByRole('row', { name: /User Role Changed by rajesh/ }));
    const drawer = await screen.findByRole('dialog', { name: 'User Role Changed' });
    const details = within(drawer).getByLabelText('Audit event details');
    expect(details).toHaveTextContent('"from": "SUPPORT_AGENT"');
    expect(details).toHaveTextContent('"to": "IT_MANAGER"');
    expect(within(drawer).getByText('10.0.0.4')).toBeInTheDocument();
    await user.click(within(drawer).getByRole('button', { name: /Events for this user/ }));
    await waitFor(() =>
      expect(requests.some((request) => request.url === '/audit-logs' && (request.params as Record<string, unknown>).entityId === '12' && (request.params as Record<string, unknown>).entityType === 'USER')).toBe(true),
    );
  });
});
