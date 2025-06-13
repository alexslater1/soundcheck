import { compareYears, count, formatStatDate, seenLine, yearDelta } from './statsText';

describe('formatStatDate', () => {
  it('shows the UK date, even for a gig after midnight UTC in summer', () => {
    expect(formatStatDate('2025-03-01T20:00:00Z')).toBe('1 Mar 2025');
    // 23:30 UTC on 30 June is 00:30 on 1 July in London (BST).
    expect(formatStatDate('2025-06-30T23:30:00Z')).toBe('1 Jul 2025');
  });
});

describe('seenLine', () => {
  it('says once for a single gig', () => {
    expect(seenLine(1, '2025-03-01T20:00:00Z', '2025-03-01T20:00:00Z')).toBe('Once, 1 Mar 2025');
  });

  it('names the day when every time was on one day (two bills in one night)', () => {
    expect(seenLine(2, '2025-03-01T19:00:00Z', '2025-03-01T22:00:00Z')).toBe(
      '2 times on 1 Mar 2025',
    );
  });

  it('gives the first and the last time otherwise', () => {
    expect(seenLine(3, '2025-03-01T19:00:00Z', '2026-02-10T20:00:00Z')).toBe(
      'First 1 Mar 2025 · last 10 Feb 2026',
    );
  });
});

describe('count', () => {
  it('uses the singular for one', () => {
    expect(count(1, 'gig')).toBe('1 gig');
    expect(count(0, 'gig')).toBe('0 gigs');
    expect(count(92, 'gig')).toBe('92 gigs');
  });
});

describe('compareYears', () => {
  it('reads a year missing from the list as zero', () => {
    expect(compareYears(2026, [{ year: 2025, gigs: 32 }])).toEqual({
      thisYear: 2026,
      lastYear: 2025,
      thisGigs: 0,
      lastGigs: 32,
    });
  });

  it('is null when neither year has a gig', () => {
    expect(compareYears(2026, [{ year: 2019, gigs: 4 }])).toBeNull();
    expect(compareYears(2026, [])).toBeNull();
  });

  it('describes the difference', () => {
    const years = [
      { year: 2026, gigs: 40 },
      { year: 2025, gigs: 32 },
    ];
    const comparison = compareYears(2026, years);
    expect(comparison && yearDelta(comparison)).toBe('8 more than 2025');
    expect(yearDelta({ thisYear: 2026, lastYear: 2025, thisGigs: 5, lastGigs: 8 })).toBe(
      '3 fewer than 2025',
    );
    expect(yearDelta({ thisYear: 2026, lastYear: 2025, thisGigs: 8, lastGigs: 8 })).toBe(
      'Same as 2025',
    );
  });
});
