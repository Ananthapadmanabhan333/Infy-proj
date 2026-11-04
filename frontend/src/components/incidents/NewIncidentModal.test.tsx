import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { CreateIncidentResult, IncidentDetail } from '../../lib/types';
import { json, mockApi, requests } from '../../test/mockApi';
import { renderWithProviders } from '../../test/utils';
import { CreateIncidentResultView, NewIncidentModal } from './NewIncidentModal';

const incident: IncidentDetail = {
  id: 42,
  number: 'INC-7042',
  title: 'Payroll portal returns 502 for finance',
  priority: 'HIGH',
  impact: 'HIGH',
  urgency: 'MEDIUM',
  status: 'ASSIGNED',
  category: 'APPLICATION',
  serviceId: 3,
  serviceName: 'Payroll',
  departmentId: 2,
  departmentName: 'Application Support',
  assigneeId: 9,
  assigneeName: 'Riya Varma',
  requesterId: 7,
  requesterName: 'Ada Lovelace',
  parentId: 41,
  parentNumber: 'INC-7001',
  major: false,
  childCount: 0,
  createdAt: '2026-11-03T10:24:00Z',
  updatedAt: '2026-11-03T10:24:00Z',
  resolvedAt: null,
  sla: null,
  demo: false,
  description: null,
  classificationSource: 'RULES',
  classificationConfidence: 0.82,
  affectedUsers: 1,
  source: 'PORTAL',
  escalationLevel: 0,
  resolutionCode: null,
  resolutionNotes: null,
  rootCause: null,
  resolutionArticle: null,
  firstResponseAt: null,
  closedAt: null,
  resolvedByName: null,
  createdByName: 'Ada Lovelace',
  duplicateScore: 0.91,
  children: [],
  links: [],
  allowedTransitions: ['IN_PROGRESS'],
  escalations: [],
  relatedSameService: [],
};

const result: CreateIncidentResult = {
  incident,
  classification: { category: 'APPLICATION', serviceId: 3, serviceName: 'Payroll', source: 'RULES', confidence: 0.82 },
  duplicate: { detected: true, matchedIncidentNumber: 'INC-6999', parentId: 41, parentNumber: 'INC-7001', parentCreated: true, score: 0.91 },
  assignment: { departmentName: 'Application Support', assigneeName: 'Riya Varma' },
};

describe('NewIncidentModal', () => {
  it('submits the incident and shows classification, assignment and the duplicate callout', async () => {
    mockApi({
      'GET /services': json([]),
      'POST /incidents/preview-duplicates': json([]),
      'POST /incidents': json(result, 201),
    });
    renderWithProviders(<NewIncidentModal onClose={vi.fn()} />, { route: '/incidents' });
    const user = userEvent.setup();

    expect(screen.getByRole('dialog', { name: 'Create new incident' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Title'), '  Payroll portal returns 502 for finance  ');
    await user.type(screen.getByLabelText('Description'), 'Since 10:00, all finance users.');
    await user.click(screen.getByRole('button', { name: 'Create incident' }));

    expect(await screen.findByText(/Linked to parent INC-7001 \(new parent created\)/)).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Incident created' })).toBeInTheDocument();
    expect(screen.getByText('Rules engine')).toBeInTheDocument();
    expect(screen.getByText('Riya Varma')).toBeInTheDocument();
    expect(screen.getByText('Application Support')).toBeInTheDocument();

    const create = requests.find((request) => request.method === 'POST' && request.url === '/incidents');
    expect(create?.body).toEqual({
      title: 'Payroll portal returns 502 for finance',
      description: 'Since 10:00, all finance users.',
      affectedUsers: 1,
      source: 'PORTAL',
    });
  });

  it('labels a link to an existing parent', () => {
    renderWithProviders(<CreateIncidentResultView result={{ ...result, duplicate: { ...result.duplicate!, parentCreated: false } }} />);
    expect(screen.getByText(/Linked to parent INC-7001 \(existing parent\)/)).toBeInTheDocument();
  });

  it('omits the callout when no duplicate was detected', () => {
    renderWithProviders(<CreateIncidentResultView result={{ ...result, duplicate: null }} />);
    expect(screen.queryByText(/Linked to parent/)).not.toBeInTheDocument();
  });

  it('shows possible duplicates while typing', async () => {
    mockApi({
      'GET /services': json([]),
      'POST /incidents/preview-duplicates': json([
        { incidentId: 41, number: 'INC-7001', title: 'Payroll down', status: 'IN_PROGRESS', priority: 'CRITICAL', serviceName: 'Payroll', createdAt: '2026-11-03T09:00:00Z', score: 0.87, parentId: null },
      ]),
    });
    renderWithProviders(<NewIncidentModal onClose={vi.fn()} />);
    await userEvent.setup().type(screen.getByLabelText('Title'), 'Payroll is down');
    expect(await screen.findByText('Possible duplicates', {}, { timeout: 3000 })).toBeInTheDocument();
    expect(screen.getByText('87% match')).toBeInTheDocument();
  });

  it('shows validation errors without calling the API', async () => {
    mockApi({ 'GET /services': json([]) });
    renderWithProviders(<NewIncidentModal onClose={vi.fn()} />);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Create incident' }));
    expect(await screen.findByText('Title must be at least 5 characters')).toBeInTheDocument();
    await waitFor(() => expect(requests.some((request) => request.method === 'POST' && request.url === '/incidents')).toBe(false));
  });
});
