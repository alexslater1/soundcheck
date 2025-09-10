import type { IngestionRun } from '@/api/generated/model';

import {
  explainIssue,
  groupIssues,
  isActive,
  isBreakage,
  ISSUE_KINDS,
  issueCountList,
} from './issueKinds';

const run = (source: string, issueCounts: Record<string, number>): IngestionRun => ({
  id: 'r1',
  source,
  trigger: 'SCHEDULE',
  status: 'SUCCEEDED',
  startedAt: '2026-10-05T05:10:00Z',
  finishedAt: '2026-10-05T05:12:00Z',
  fetched: 10,
  created: 1,
  updated: 2,
  unchanged: 7,
  merged: 0,
  skipped: 0,
  errors: 0,
  apiCalls: 4,
  issueCounts,
});

describe('issueKinds', () => {
  it('explains every kind the backend records, and none it does not', () => {
    for (const kind of ['VENUE_EMPTY', 'VENUE_UNREADABLE', 'NOT_SEEN', 'BUDGET_EXHAUSTED']) {
      expect(explainIssue(kind)).toEqual(expect.any(String));
    }
    expect(explainIssue('SOMETHING_NEW')).toBeUndefined();
    // Not fooled by names on Object.prototype.
    expect(explainIssue('toString')).toBeUndefined();
    expect(Object.keys(ISSUE_KINDS)).toHaveLength(26);
  });

  it('groups issues by kind, biggest group first, keeping unknown kinds', () => {
    const groups = groupIssues([
      { kind: 'NO_DATE', externalId: 'a', detail: 'x' },
      { kind: 'SOMETHING_NEW', externalId: null, detail: 'y' },
      { kind: 'NO_ARTISTS', externalId: 'b', detail: 'z' },
      { kind: 'NO_DATE', externalId: 'c', detail: 'w' },
    ]);
    expect(groups.map((g) => [g.kind, g.issues.length])).toEqual([
      ['NO_DATE', 2],
      ['NO_ARTISTS', 1],
      ['SOMETHING_NEW', 1],
    ]);
    expect(groups[2]?.explanation).toBeUndefined();
    expect(groups[0]?.issues.map((i) => i.externalId)).toEqual(['a', 'c']);
  });

  it('orders the issue counts and recognises active runs', () => {
    expect(issueCountList({ B: 1, A: 1, C: 5 })).toEqual([
      ['C', 5],
      ['A', 1],
      ['B', 1],
    ]);
    expect(isActive('QUEUED')).toBe(true);
    expect(isActive('RUNNING')).toBe(true);
    expect(isActive('PARTIAL')).toBe(false);
    expect(isActive(undefined)).toBe(false);
  });

  it('flags a VENUE run with an empty or unreadable site as breakage, and nothing else', () => {
    expect(isBreakage(run('VENUE', { VENUE_EMPTY: 1 }))).toBe(true);
    expect(isBreakage(run('VENUE', { VENUE_UNREADABLE: 2, NO_DATE: 1 }))).toBe(true);
    expect(isBreakage(run('VENUE', { NO_DATE: 3 }))).toBe(false);
    expect(isBreakage(run('TICKETMASTER', { VENUE_EMPTY: 1 }))).toBe(false);
    expect(isBreakage(undefined)).toBe(false);
  });
});
