import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, mockApi, requests } from '../../test/mockApi';
import { makeAuth, makeUser, renderWithProviders } from '../../test/utils';
import type { ReportRunDto, ReportScheduleDto, ReportTypeDto } from '../../lib/types';
import { describeSchedule, ScheduledReportsTab } from './ScheduledReportsTab';

const last = (method: string, url: string) => [...requests].reverse().find((request) => request.method === method && request.url === url);

const TYPES: ReportTypeDto[] = [
  { type: 'SLA', label: 'SLA compliance', description: 'SLA met and missed by priority', available: true, formats: ['PDF', 'CSV'], filterKeys: ['departmentId'] },
  { type: 'INCIDENTS', label: 'Incidents', description: 'Incident register', available: true, formats: ['CSV'], filterKeys: [] },
  { type: 'CHANGES', label: 'Changes', description: 'Change records', available: false, formats: ['PDF'], filterKeys: [] },
];

const RUN: ReportRunDto = {
  id: 90,
  scheduleId: 1,
  trigger: 'SCHEDULE',
  scheduledFor: '2026-11-03T06:00:00Z',
  status: 'SUCCEEDED',
  periodFrom: '2026-10-27',
  periodTo: '2026-11-02',
  fileName: 'sla-2026-11-02.pdf',
  contentType: 'application/pdf',
  sizeBytes: 20480,
  error: null,
  emailedTo: 0,
  startedAt: '2026-11-03T06:00:00Z',
  finishedAt: '2026-11-03T06:00:05Z',
  demo: false,
};

const SCHEDULE: ReportScheduleDto = {
  id: 1,
  name: 'Weekly SLA',
  reportType: 'SLA',
  reportTypeLabel: 'SLA compliance',
  format: 'PDF',
  frequency: 'WEEKLY',
  runHour: 6,
  dayOfWeek: 1,
  dayOfMonth: null,
  filters: null,
  recipients: ['cio@nexus360.test'],
  ownerId: 7,
  ownerName: 'Ada Lovelace',
  nextRunAt: '2026-11-10T06:00:00Z',
  lastRunAt: '2026-11-03T06:00:00Z',
  active: true,
  demo: false,
  lastRun: RUN,
};

function setup(schedules: ReportScheduleDto[] = [SCHEDULE]) {
  mockApi({
    'GET /reports/types': json(TYPES),
    'GET /reports/schedules': json(schedules),
    'GET /departments': json([{ id: 2, code: 'IT', name: 'IT Applications', description: null, managerId: null, managerName: null, email: null, memberCount: 1, demo: false }]),
    'POST /reports/schedules': (config) => json({ ...SCHEDULE, id: 2, ...JSON.parse(String(config.data)) }, 201),
    'POST /reports/schedules/1/run': json({ ...RUN, id: 91, trigger: 'MANUAL', status: 'FAILED', error: 'Generator crashed' }),
    'GET /reports/schedules/1/runs': json([RUN]),
    'GET /reports/runs/90/download': { status: 200, data: new Blob(['%PDF']), headers: { 'content-disposition': 'attachment; filename="sla.pdf"' } },
  });
}

describe('describeSchedule', () => {
  it('summarises frequency, day and hour', () => {
    expect(describeSchedule({ frequency: 'WEEKLY', runHour: 6, dayOfWeek: 3, dayOfMonth: null })).toBe('Weekly · Wednesday 06:00');
    expect(describeSchedule({ frequency: 'MONTHLY', runHour: 18, dayOfWeek: null, dayOfMonth: 28 })).toBe('Monthly · day 28 18:00');
    expect(describeSchedule({ frequency: 'DAILY', runHour: 0, dayOfWeek: null, dayOfMonth: null })).toBe('Daily · 00:00');
  });
});

describe('ScheduledReportsTab', () => {
  it('lists schedules with their last run and type availability', async () => {
    setup();
    renderWithProviders(<ScheduledReportsTab />, { auth: makeAuth(makeUser('IT_MANAGER', { id: 7 })) });
    const table = await screen.findByRole('table', { name: 'Scheduled reports' });
    expect(within(table).getByText('Weekly SLA')).toBeInTheDocument();
    expect(within(table).getByText('Weekly · Monday 06:00')).toBeInTheDocument();
    expect(within(table).getByRole('button', { name: 'Download sla-2026-11-02.pdf' })).toBeInTheDocument();
    expect(screen.getByText('Not available yet')).toBeInTheDocument();
  });

  it('creates a schedule; unavailable types cannot be chosen', async () => {
    setup([]);
    const user = userEvent.setup();
    renderWithProviders(<ScheduledReportsTab />, { auth: makeAuth(makeUser('IT_MANAGER', { id: 7 })) });
    await user.click(await screen.findByRole('button', { name: /New schedule/ }));
    const form = await screen.findByRole('form', { name: 'New scheduled report' });
    const type = within(form).getByLabelText(/Report type/);
    expect(within(type).getByRole('option', { name: /Changes/ })).toBeDisabled();
    await user.type(within(form).getByLabelText(/^Name/), 'Monthly SLA');
    await user.selectOptions(within(form).getByLabelText(/^Frequency/), 'MONTHLY');
    await user.selectOptions(within(form).getByLabelText(/Day of month/), '5');
    await user.selectOptions(within(form).getByLabelText(/^Department/), '2');
    await user.type(within(form).getByLabelText(/Recipients/), 'cio@nexus360.test, ops@nexus360.test');
    await user.click(within(form).getByRole('button', { name: 'Create schedule' }));
    await waitFor(() =>
      expect(last('POST', '/reports/schedules')?.body).toEqual({
        name: 'Monthly SLA',
        reportType: 'SLA',
        format: 'PDF',
        frequency: 'MONTHLY',
        runHour: 6,
        dayOfWeek: null,
        dayOfMonth: 5,
        filters: { departmentId: 2 },
        recipients: ['cio@nexus360.test', 'ops@nexus360.test'],
        active: true,
      }),
    );
  });

  it('validates recipients client-side', async () => {
    setup([]);
    const user = userEvent.setup();
    renderWithProviders(<ScheduledReportsTab />, { auth: makeAuth(makeUser('SYSTEM_ADMIN')) });
    await user.click(await screen.findByRole('button', { name: /New schedule/ }));
    const form = await screen.findByRole('form', { name: 'New scheduled report' });
    await user.type(within(form).getByLabelText(/^Name/), 'Bad');
    await user.type(within(form).getByLabelText(/Recipients/), 'not-an-address');
    await user.click(within(form).getByRole('button', { name: 'Create schedule' }));
    expect(await within(form).findByText(/is not an e-mail address/)).toBeInTheDocument();
    expect(last('POST', '/reports/schedules')).toBeUndefined();
  });

  it('runs now, reports a generator failure and shows the run history', async () => {
    setup();
    const user = userEvent.setup();
    renderWithProviders(<ScheduledReportsTab />, { auth: makeAuth(makeUser('AUDITOR', { id: 99 })) });
    await user.click(await screen.findByRole('button', { name: 'Run Weekly SLA now' }));
    expect(await screen.findByText(/Weekly SLA failed: Generator crashed/)).toBeInTheDocument();
    // Auditors are report exporters (run, history) but not the owner or an admin (no edit/delete).
    expect(screen.queryByRole('button', { name: 'Edit Weekly SLA' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Run history of Weekly SLA' }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByRole('list', { name: 'Report runs' })).toBeInTheDocument();
    expect(within(drawer).getByText(/sla-2026-11-02\.pdf/)).toBeInTheDocument();
  });

  it('hides scheduling for employees', async () => {
    setup([]);
    renderWithProviders(<ScheduledReportsTab />, { auth: makeAuth(makeUser('EMPLOYEE')) });
    expect(await screen.findByText('No scheduled reports')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /New schedule/ })).not.toBeInTheDocument();
  });
});
