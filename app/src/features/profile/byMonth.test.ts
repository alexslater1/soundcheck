import type { GigSummary } from '@/api/generated/model';

import { byMonth } from './byMonth';

const gig = (id: string, startsAt: string) => ({ id, startsAt }) as GigSummary;

describe('byMonth', () => {
  it('groups a newest-first list under UK month headers, keeping its order', () => {
    const groups = byMonth([
      gig('a', '2026-10-02T19:00:00Z'),
      gig('b', '2026-10-01T19:00:00Z'),
      gig('c', '2026-08-30T19:00:00Z'),
      gig('d', '2025-08-30T19:00:00Z'),
    ]);
    expect(groups.map((g) => [g.title, g.gigs.map((x) => x.id)])).toEqual([
      ['October 2026', ['a', 'b']],
      ['August 2026', ['c']],
      ['August 2025', ['d']],
    ]);
  });

  it('puts a gig just after midnight UK time in the new month', () => {
    // 00:30 BST on 1 October is 23:30 UTC on 30 September.
    expect(byMonth([gig('late', '2026-09-30T23:30:00Z')])[0]?.title).toBe('October 2026');
  });

  it('is empty for no gigs', () => {
    expect(byMonth([])).toEqual([]);
  });
});
