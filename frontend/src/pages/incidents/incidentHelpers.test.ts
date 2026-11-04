import { describe, expect, it } from 'vitest';
import { bulkTransitionLabel, commonTransitions } from './BulkActions';
import { matchTone } from './detail/AiRecommendationCard';
import { readable } from './detail/ActivityPanel';
import { relatedCount } from './detail/RelationsPanel';
import { makeDetail, makeSummary } from './fixtures.test-utils';
import { csvCell, incidentsToCsv } from './incidentCsv';

describe('incident CSV export', () => {
  it('quotes separators and neutralises spreadsheet formulas', () => {
    expect(csvCell('plain')).toBe('plain');
    expect(csvCell('Payroll, HR')).toBe('"Payroll, HR"');
    expect(csvCell('He said "down"')).toBe('"He said ""down"""');
    expect(csvCell('=HYPERLINK("x")')).toBe(`"'=HYPERLINK(""x"")"`);
    expect(csvCell(null)).toBe('');
  });

  it('writes one row per incident with labelled values', () => {
    const csv = incidentsToCsv([makeSummary({ title: 'VPN, slow', sla: null, major: true })]);
    const [header, row] = csv.trim().split('\r\n');
    expect(header.split(',').slice(0, 6)).toEqual(['Number', 'Title', 'Priority', 'Status', 'Category', 'Service']);
    expect(row).toContain('INC-7846,"VPN, slow",Critical,In Progress,Application,Payroll');
    expect(row).toContain(',Yes,');
  });
});

describe('bulk status helpers', () => {
  it('offers only transitions valid for every selected incident', () => {
    expect(commonTransitions([{ status: 'IN_PROGRESS' }, { status: 'PENDING' }])).toEqual(['RESOLVED']);
    expect(commonTransitions([{ status: 'NEW' }, { status: 'ASSIGNED' }])).toEqual(['IN_PROGRESS', 'PENDING', 'RESOLVED', 'CLOSED']);
    expect(commonTransitions([{ status: 'CLOSED' }])).toEqual([]);
    expect(commonTransitions([])).toEqual([]);
  });

  it('labels CLOSED as a cancellation from NEW/ASSIGNED', () => {
    expect(bulkTransitionLabel([{ status: 'NEW' }], 'CLOSED')).toMatch(/^Cancelled/);
    expect(bulkTransitionLabel([{ status: 'RESOLVED' }], 'CLOSED')).toBe('Closed');
    expect(bulkTransitionLabel([{ status: 'NEW' }], 'PENDING')).toBe('Pending');
  });
});

describe('detail helpers', () => {
  it('counts parent, children, links and same-service incidents for the Related tab', () => {
    expect(relatedCount(makeDetail())).toBe(0);
    expect(
      relatedCount(
        makeDetail({
          parentId: 1,
          children: [makeSummary({ id: 2 })],
          links: [{ id: 1, linkType: 'RELATED_TO', direction: 'OUTGOING', incident: { id: 3, number: 'INC-3', title: 'x', status: 'NEW', priority: 'LOW' } }],
          relatedSameService: [makeSummary({ id: 4 })],
        }),
      ),
    ).toBe(4);
  });

  it('maps confidence to the match badge tone and role codes to labels', () => {
    expect(matchTone(87)).toBe('');
    expect(matchTone(60)).toBe('medium');
    expect(matchTone(20)).toBe('low');
    expect(readable('Routed to INCIDENT_MANAGER')).toBe('Routed to Incident Manager');
  });
});
