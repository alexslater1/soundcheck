import type { GigSummary } from '@/api/generated/model';

import { billOf, gigChoices } from './composeDraft';

const gig = (id: string, headliner = 'Headliner', support: string[] = []): GigSummary =>
  ({
    id,
    title: `Gig ${id}`,
    startsAt: '2026-10-01T19:30:00Z',
    status: 'SCHEDULED',
    venue: { id: 'v1', name: 'Venue' },
    headliner: { id: `${id}-h`, name: headliner, genres: [], isFollowing: false },
    support: support.map((name, i) => ({
      id: `${id}-s${i}`,
      name,
      genres: [],
      isFollowing: false,
    })),
  }) as GigSummary;

describe('compose form helpers', () => {
  it('offers attended gigs first, then nearby ones, each once', () => {
    const choices = gigChoices([gig('a'), gig('b')], [gig('b'), gig('c'), gig('d')], 3);
    expect(choices.map((g) => g.id)).toEqual(['a', 'b', 'c']);
  });

  it('lists a gig’s bill headliner first', () => {
    expect(billOf(gig('a', 'Main Act', ['Opener', 'Second'])).map((artist) => artist.name)).toEqual(
      ['Main Act', 'Opener', 'Second'],
    );
  });
});
