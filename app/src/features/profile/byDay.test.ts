import type { GigSummary } from '@/api/generated/model';

import { byDay } from './byDay';

const gig = (id: string, startsAt: string) => ({ id, startsAt }) as GigSummary;

describe('byDay', () => {
  it('groups gigs by UK calendar day, in start order', () => {
    const days = byDay([
      gig('late', '2026-10-17T21:00:00Z'),
      gig('early', '2026-10-17T18:00:00Z'),
      gig('after-midnight', '2026-10-17T23:30:00Z'), // 00:30 BST on the 18th
    ]);
    expect(Object.keys(days).sort()).toEqual(['2026-10-17', '2026-10-18']);
    expect(days['2026-10-17']!.map((g) => g.id)).toEqual(['early', 'late']);
    expect(days['2026-10-18']!.map((g) => g.id)).toEqual(['after-midnight']);
  });
});
