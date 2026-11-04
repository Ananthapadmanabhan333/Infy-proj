import { cloneElement, type ReactElement } from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { NewIncidentProvider } from '../../components/incidents/NewIncidentContext';
import { makeDetail } from '../../components/services/fixtures.test-utils';
import type { IncidentSummary } from '../../lib/types';
import { apiErrorBody, json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import ServiceDetailPage from './ServiceDetailPage';

// jsdom has no layout: give the chart a fixed size instead of measuring its container.
vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('recharts')>();
  return { ...actual, ResponsiveContainer: ({ children }: { children: ReactElement }) => cloneElement(children, { width: 800, height: 240 } as never) };
});

const incident: IncidentSummary = {
  id: 7846,
  number: 'INC-7846',
  title: 'Payroll application not accessible for multiple users',
  priority: 'CRITICAL',
  impact: 'HIGH',
  urgency: 'HIGH',
  status: 'IN_PROGRESS',
  category: 'APPLICATION',
  serviceId: 1,
  serviceName: 'Payroll',
  departmentId: 2,
  departmentName: 'Application Support',
  assigneeId: 9,
  assigneeName: 'Riya Varma',
  requesterId: 21,
  requesterName: 'Priya Raman',
  parentId: null,
  parentNumber: null,
  major: false,
  childCount: 0,
  createdAt: '2026-11-03T10:24:00Z',
  updatedAt: '2026-11-03T10:30:00Z',
  resolvedAt: null,
  sla: null,
  demo: false,
};

function renderDetail(route: string, role: Parameters<typeof makeUser>[0] = 'SUPPORT_AGENT') {
  return renderWithProviders(
    <NewIncidentProvider>
      <Routes>
        <Route path="/services/:id" element={<ServiceDetailPage />} />
      </Routes>
    </NewIncidentProvider>,
    { route, auth: makeAuth(makeUser(role)) },
  );
}

const lastRequest = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

describe('ServiceDetailPage', () => {
  it('shows the header, KPIs, availability, dependencies, dependents and linked incidents', async () => {
    mockApi({
      'GET /services/1': json(makeDetail()),
      'GET /incidents': json({ content: [incident], page: 0, size: 10, totalElements: 14, totalPages: 2 }),
    });
    renderDetail('/services/1');
    const user = userEvent.setup();

    expect(await screen.findByRole('heading', { level: 1, name: 'Payroll' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Services/ })).toHaveAttribute('href', '/services');
    expect(screen.getByText('Tier 1 — business critical')).toBeInTheDocument();
    expect(screen.getAllByText('Degraded').length).toBeGreaterThan(0);
    expect(screen.getByText('Degraded by a dependency.').closest('.callout')).toHaveTextContent('Dependency Payroll DB is in outage');

    expect(screen.getByText('Availability · 30 days').closest('.kpi')).toHaveTextContent('99.9%');
    expect(screen.getByText('SLA compliance · 30 days').closest('.kpi')).toHaveTextContent('96.4%');
    expect(screen.getByText('MTTR · 30 days').closest('.kpi')).toHaveTextContent('28 min');
    expect(screen.getByText('Open incidents').closest('.kpi')).toHaveTextContent('1 critical');
    expect(screen.getByRole('img', { name: /Daily availability over 3 days/ })).toBeInTheDocument();

    expect(within(screen.getByRole('list', { name: 'Dependencies' })).getByRole('link', { name: /Payroll DB/ })).toHaveAttribute('href', '/services/2');
    expect(within(screen.getByRole('list', { name: 'Dependents' })).getByRole('link', { name: /HR Portal/ })).toHaveAttribute('href', '/services/3');

    expect(await screen.findByText('Payroll application not accessible for multiple users')).toBeInTheDocument();
    expect(lastRequest('GET', '/incidents')?.params).toMatchObject({ serviceId: 1, page: 0, size: 10, status: 'NEW,ASSIGNED,IN_PROGRESS,PENDING,REOPENED' });
    expect(screen.getByText(/Showing 1–10 of 14/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Page 2' }));
    await waitFor(() => expect(lastRequest('GET', '/incidents')?.params).toMatchObject({ page: 1 }));
    await user.click(screen.getByRole('tab', { name: 'All' }));
    await waitFor(() => expect(lastRequest('GET', '/incidents')?.params).not.toHaveProperty('status'));

    expect(screen.getByText('payroll')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument();
  });

  it('lets config admins edit the service', async () => {
    mockApi({
      'GET /services/1': json(makeDetail()),
      'GET /services': json([]),
      'GET /departments': json([]),
      'GET /users/assignable': json([]),
      'GET /incidents': json({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }),
    });
    renderDetail('/services/1', 'IT_ADMIN');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /Edit/ }));
    expect(await screen.findByRole('dialog', { name: 'Edit Payroll' })).toBeInTheDocument();
  });

  it('shows a not-found state for an unknown service', async () => {
    mockApi({ 'GET /services/99': { status: 404, data: apiErrorBody(404, 'Service 99 not found') } });
    renderDetail('/services/99');
    expect(await screen.findByRole('heading', { level: 1, name: 'Service not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to services' })).toHaveAttribute('href', '/services');
  });
});
