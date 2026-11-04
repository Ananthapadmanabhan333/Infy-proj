/** Fixtures for the Release 3 BE-4 contract (on-call, major incident command, post-incident reviews). */
import { makeSummary } from '../pages/incidents/fixtures.test-utils';
import type {
  ActionItemDto,
  MajorDetail,
  MajorSummary,
  NowEntry,
  OnCallPerson,
  OverrideDto,
  PirDetail,
  PirSummary,
  PolicyDto,
  ScheduleDetail,
  ScheduleSummary,
} from '../lib/responseTypes';

export function makePerson(overrides: Partial<OnCallPerson> = {}): OnCallPerson {
  return {
    userId: 21,
    name: 'Riya Varma',
    email: 'riya@sa.test',
    phone: '+91 90000 00001',
    source: 'ROTATION',
    since: '2026-11-02T03:30:00Z',
    until: '2026-11-09T03:30:00Z',
    scheduledUserId: 21,
    scheduledUserName: 'Riya Varma',
    holiday: false,
    ...overrides,
  };
}

export function makeScheduleSummary(overrides: Partial<ScheduleSummary> = {}): ScheduleSummary {
  return {
    id: 3,
    name: 'Payroll & HR Apps on-call',
    teamId: 4,
    teamCode: 'PAYROLL',
    teamName: 'Payroll & HR Apps',
    timeZone: 'Asia/Kolkata',
    rotationType: 'WEEKLY',
    handoffTime: '09:00',
    handoffDay: 1,
    shiftHours: null,
    rotationStart: '2026-10-05',
    primaryCount: 3,
    secondaryCount: 0,
    escalationPolicyId: 1,
    escalationPolicyName: 'Payroll critical path',
    active: true,
    demo: true,
    primaryNow: makePerson(),
    secondaryNow: makePerson({ userId: 22, name: 'Dev Nair', scheduledUserId: 22, scheduledUserName: 'Dev Nair' }),
    ...overrides,
  };
}

export function makePolicy(overrides: Partial<PolicyDto> = {}): PolicyDto {
  return {
    id: 1,
    name: 'Payroll critical path',
    description: 'Primary at once, then secondary, then Priya',
    teamId: 4,
    teamName: 'Payroll & HR Apps',
    minSeverity: 'MAJOR',
    levels: [
      { level: 1, delayMinutes: 0, minutesFromStart: 0, target: 'PRIMARY', userId: null, userName: null, label: 'Primary on call' },
      { level: 2, delayMinutes: 5, minutesFromStart: 5, target: 'SECONDARY', userId: null, userName: null, label: 'Secondary on call' },
      { level: 3, delayMinutes: 10, minutesFromStart: 15, target: 'USER', userId: 30, userName: 'Priya Raman', label: 'Priya Raman' },
    ],
    active: true,
    scheduleCount: 1,
    demo: true,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    ...overrides,
  };
}

export function makeOverride(overrides: Partial<OverrideDto> = {}): OverrideDto {
  return {
    id: 11,
    scheduleId: 3,
    scheduleName: 'Payroll & HR Apps on-call',
    layer: 'PRIMARY',
    userId: 22,
    userName: 'Dev Nair',
    originalUserId: 21,
    originalUserName: 'Riya Varma',
    startsAt: '2026-11-05T03:30:00Z',
    endsAt: '2026-11-05T15:30:00Z',
    reason: 'Riya at the dentist',
    status: 'ACTIVE',
    createdByName: 'Sunita Joshi',
    takenAt: null,
    decidedNote: null,
    canTake: false,
    demo: true,
    createdAt: '2026-11-03T08:00:00Z',
    ...overrides,
  };
}

export function makeScheduleDetail(overrides: Partial<ScheduleDetail> = {}, schedule: Partial<ScheduleSummary> = {}): ScheduleDetail {
  return {
    schedule: makeScheduleSummary(schedule),
    description: 'Weekly rotation for the payroll applications',
    primary: [
      { position: 1, userId: 21, userName: 'Riya Varma', email: 'riya@sa.test', active: true, availableNow: true },
      { position: 2, userId: 22, userName: 'Dev Nair', email: 'dev@sa.test', active: true, availableNow: true },
      { position: 3, userId: 23, userName: 'Meera Sharma', email: 'meera@sa.test', active: true, availableNow: false },
    ],
    secondary: [],
    holidayCalendarId: 1,
    holidayCalendarName: 'India business hours',
    holidayCoverUserId: 30,
    holidayCoverUserName: 'Priya Raman',
    upcomingHolidays: [{ date: '2026-11-08', name: 'Diwali' }],
    overrides: [makeOverride()],
    handovers: [
      {
        id: 5,
        scheduleId: 3,
        authorId: 21,
        authorName: 'Riya Varma',
        toUserId: 22,
        toUserName: 'Dev Nair',
        shiftStart: null,
        shiftEnd: null,
        body: 'Watch the payroll batch at 02:00',
        demo: true,
        createdAt: '2026-11-03T08:00:00Z',
      },
    ],
    escalationPolicy: makePolicy(),
    canEdit: true,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-11-01T08:00:00Z',
    ...overrides,
  };
}

export function makeNowEntry(overrides: Partial<NowEntry> = {}): NowEntry {
  return {
    scheduleId: 3,
    scheduleName: 'Payroll & HR Apps on-call',
    teamId: 4,
    teamCode: 'PAYROLL',
    teamName: 'Payroll & HR Apps',
    timeZone: 'Asia/Kolkata',
    at: '2026-11-04T08:00:00Z',
    primary: makePerson({ source: 'OVERRIDE', name: 'Dev Nair', userId: 22, scheduledUserId: 21, scheduledUserName: 'Riya Varma' }),
    secondary: null,
    escalationPolicyId: 1,
    escalationPolicyName: 'Payroll critical path',
    ...overrides,
  };
}

export function makeActionItem(overrides: Partial<ActionItemDto> = {}): ActionItemDto {
  return {
    id: 41,
    source: 'MAJOR_INCIDENT',
    majorIncidentId: 2,
    pirId: null,
    title: 'Add a connection-pool alert',
    description: null,
    actionType: 'FOLLOW_UP',
    ownerId: 21,
    ownerName: 'Riya Varma',
    dueDate: '2026-11-10',
    status: 'OPEN',
    overdue: false,
    completedAt: null,
    demo: true,
    createdAt: '2026-11-03T08:00:00Z',
    updatedAt: '2026-11-03T08:00:00Z',
    ...overrides,
  };
}

export function makeMajorSummary(overrides: Partial<MajorSummary> = {}): MajorSummary {
  return {
    id: 2,
    number: 'MI-002',
    status: 'ACTIVE',
    incidentId: 9,
    incidentNumber: 'INC-7009',
    title: 'Payroll portal returns 503',
    priority: 'CRITICAL',
    incidentStatus: 'IN_PROGRESS',
    commander: { id: 31, name: 'Sunita Joshi', email: 'sunita@sa.test' },
    techLead: { id: 21, name: 'Riya Varma', email: 'riya@sa.test' },
    commsLead: { id: 30, name: 'Priya Raman', email: 'priya@sa.test' },
    declaredAt: '2026-11-04T05:00:00Z',
    resolvedAt: null,
    closedAt: null,
    durationMinutes: 95,
    openActions: 3,
    pir: { id: 6, number: 'PIR-006', status: 'DRAFT' },
    demo: true,
    ...overrides,
  };
}

export function makeMajorDetail(overrides: Partial<MajorDetail> = {}): MajorDetail {
  const summary = makeMajorSummary();
  return {
    id: summary.id,
    number: summary.number,
    status: 'ACTIVE',
    incident: makeSummary({ id: 9, number: 'INC-7009', title: 'Payroll portal returns 503', priority: 'CRITICAL', status: 'IN_PROGRESS', major: true }),
    commander: summary.commander,
    techLead: summary.techLead,
    commsLead: summary.commsLead,
    summary: 'Payroll portal unavailable during the morning batch',
    impactSummary: '2,400 employees cannot see payslips',
    investigationNotes: 'Connection pool exhausted',
    resolutionSummary: null,
    bridgeUrl: 'https://meet.example.test/payroll',
    declaredAt: summary.declaredAt,
    declaredByName: 'Sunita Joshi',
    resolvedAt: null,
    closedAt: null,
    durationMinutes: 95,
    services: [{ id: 4, code: 'PAYROLL', name: 'Payroll' }],
    timeline: [
      { id: 1, entryType: 'INVESTIGATION', body: 'DB pool at 100 %', actorId: 21, actorName: 'Riya Varma', occurredAt: '2026-11-04T05:10:00Z', demo: true },
      { id: 2, entryType: 'DECISION', body: 'Pause the batch', actorId: 31, actorName: 'Sunita Joshi', occurredAt: '2026-11-04T05:20:00Z', demo: true },
    ],
    updates: [
      {
        id: 7,
        status: 'INVESTIGATING',
        title: 'Payroll portal unavailable',
        message: 'We are investigating.',
        published: true,
        statusUpdateId: 3,
        publishedAt: '2026-11-04T05:15:00Z',
        postedByName: 'Priya Raman',
        postedAt: '2026-11-04T05:15:00Z',
        demo: true,
      },
      {
        id: 8,
        status: 'IDENTIFIED',
        title: 'Cause identified',
        message: 'The batch exhausted the pool.',
        published: false,
        statusUpdateId: null,
        publishedAt: null,
        postedByName: 'Priya Raman',
        postedAt: '2026-11-04T05:40:00Z',
        demo: true,
      },
    ],
    actionItems: [makeActionItem()],
    alerts: [{ id: 61, number: 'ALR-00061', title: 'Payroll API 5xx', severity: 'CRITICAL', status: 'ACKNOWLEDGED' }],
    pir: summary.pir,
    allowedTransitions: ['MONITORING', 'RESOLVED'],
    canCommand: true,
    canPublish: true,
    demo: true,
    createdAt: summary.declaredAt,
    updatedAt: summary.declaredAt,
    ...overrides,
  };
}

export function makePirSummary(overrides: Partial<PirSummary> = {}): PirSummary {
  return {
    id: 6,
    number: 'PIR-006',
    title: 'Payroll 503s during the morning batch',
    status: 'DRAFT',
    incidentId: 9,
    incidentNumber: 'INC-7009',
    incidentTitle: 'Payroll portal returns 503',
    majorIncidentId: 2,
    majorIncidentNumber: 'MI-002',
    owner: { id: 31, name: 'Sunita Joshi', email: 'sunita@sa.test' },
    openActions: 1,
    actionCount: 1,
    submittedAt: null,
    approvedAt: null,
    demo: true,
    createdAt: '2026-11-04T06:00:00Z',
    updatedAt: '2026-11-04T06:00:00Z',
    ...overrides,
  };
}

export function makePirDetail(overrides: Partial<PirDetail> = {}): PirDetail {
  return {
    id: 6,
    number: 'PIR-006',
    title: 'Payroll 503s during the morning batch',
    status: 'DRAFT',
    incident: makeSummary({ id: 9, number: 'INC-7009', title: 'Payroll portal returns 503', status: 'RESOLVED', major: true }),
    majorIncidentId: 2,
    majorIncidentNumber: 'MI-002',
    owner: { id: 31, name: 'Sunita Joshi', email: 'sunita@sa.test' },
    summary: 'The payroll portal returned 503 for 95 minutes.',
    rootCause: 'Connection pool exhausted by the batch',
    contributingFactors: ['Batch started before the cache warmed up'],
    impact: '',
    detectionGaps: null,
    responseAnalysis: null,
    lessonsLearned: null,
    timeline: [
      { at: '2026-11-04T04:55:00Z', kind: 'ALERT', source: 'alert', title: 'Alert fired: Payroll API 5xx', detail: null, actorName: null },
      { at: '2026-11-04T05:00:00Z', kind: 'WAR_ROOM', source: 'major', title: 'Major incident declared', detail: null, actorName: 'Sunita Joshi' },
    ],
    metrics: {
      detectedAt: '2026-11-04T04:55:00Z',
      reportedAt: '2026-11-04T05:00:00Z',
      firstResponseAt: '2026-11-04T05:04:00Z',
      declaredAt: '2026-11-04T05:00:00Z',
      acknowledgedAt: '2026-11-04T04:58:00Z',
      resolvedAt: '2026-11-04T06:35:00Z',
      minutesToDetect: 5,
      minutesToAcknowledge: 4,
      minutesToDeclare: 0,
      minutesToResolve: 95,
      alerts: 2,
      pages: 3,
      linkedReports: 4,
      slaOutcome: 'MISSED',
    },
    timelineBuiltAt: '2026-11-04T06:00:00Z',
    actions: [makeActionItem({ id: 51, source: 'PIR', pirId: 6, majorIncidentId: null, title: 'Raise the pool size', actionType: 'CORRECTIVE' })],
    approvalId: null,
    approvalStatus: null,
    submittedAt: null,
    approvedAt: null,
    approvedByName: null,
    reviewComment: null,
    canEdit: true,
    canSubmit: true,
    demo: true,
    createdAt: '2026-11-04T06:00:00Z',
    updatedAt: '2026-11-04T06:00:00Z',
    ...overrides,
  };
}
