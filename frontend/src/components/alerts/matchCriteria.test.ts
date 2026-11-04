import { describe, expect, it } from 'vitest';
import { describeMatch, describeWindow, draftFromMatch, isEmptyMatch, labelsFromRows, matchFromDraft } from './matchCriteria';

describe('MatchCriteria helpers', () => {
  it('round-trips a matcher through the editor draft, dropping empty criteria', () => {
    const draft = draftFromMatch({ labels: { job: 'backup', env: 'prod|staging' }, severities: ['CRITICAL'], ciIds: [21] });
    expect(draft.cis).toEqual([{ id: 21, name: 'CI #21' }]);
    expect(matchFromDraft(draft)).toEqual({ labels: { job: 'backup', env: 'prod|staging' }, severities: ['CRITICAL'], ciIds: [21] });
    expect(matchFromDraft(draftFromMatch(null))).toBeNull();
    expect(matchFromDraft({ ...draftFromMatch(null), titleContains: '  disk  ' })).toEqual({ titleContains: 'disk' });
  });

  it('validates label rows', () => {
    expect(labelsFromRows([{ name: '', value: '' }])).toEqual({});
    expect(labelsFromRows([{ name: 'job', value: '' }])).toMatch(/Give a value/);
    expect(labelsFromRows([{ name: '9bad', value: 'x' }])).toMatch(/not a valid label name/);
    expect(labelsFromRows([{ name: 'job', value: 'a' }, { name: 'job', value: 'b' }])).toMatch(/twice/);
    expect(labelsFromRows([{ name: 'instance', value: '*' }])).toEqual({ instance: '*' });
  });

  it('describes matchers and windows for the rule lists', () => {
    expect(isEmptyMatch({ labels: {} })).toBe(true);
    expect(describeMatch(null)).toBe('Every alert');
    expect(
      describeMatch({ severities: ['CRITICAL', 'MAJOR'], sources: ['CLOUDWATCH'], serviceIds: [4], labels: { job: 'backup', env: 'a|b', instance: '*' }, titleContains: 'disk' }, { services: new Map([[4, 'Payroll']]) }),
    ).toBe('Severity Critical, Major · From AWS CloudWatch · Service Payroll · Labels job=backup, env ∈ {a, b}, instance present · Title contains “disk”');
    expect(describeWindow({ startsAt: null, endsAt: null, dailyStart: '01:00', dailyEnd: '03:00', daysOfWeek: [6, 1] }, (iso) => iso)).toBe('daily 01:00–03:00 · Mon, Sat');
    expect(describeWindow({ startsAt: null, endsAt: null, dailyStart: null, dailyEnd: null, daysOfWeek: null }, (iso) => iso)).toBe('Always');
  });
});
