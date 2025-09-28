import type { FanAddedGig, GigMergePreview, GigMergeSide, GigSummary } from '@/api/generated/model';

import type { CatalogTabProps } from './tabTypes';

/** A gig as the API returns it, for the Catalog page's tests. */
export function gig(id: string, title: string, startsAt = '2026-10-03T19:00:00Z'): GigSummary {
  return {
    id,
    title,
    startsAt,
    status: 'SCHEDULED',
    venue: { id: 'venue-1', name: 'Band on the Wall' },
    headliner: { id: 'act-1', name: 'Keo', genres: [], isFollowing: false },
    support: [{ id: 'act-2', name: 'Opener', genres: [], isFollowing: false }],
    myActsMissed: [],
    userAdded: false,
    wentUnconfirmed: false,
  };
}

export function side(g: GigSummary, overrides: Partial<GigMergeSide> = {}): GigMergeSide {
  return {
    gig: g,
    listings: [{ source: 'TICKETMASTER', externalId: 'tm-1', url: null, primary: true }],
    addedBy: null,
    counts: [{ what: 'posts', count: 2 }],
    ...overrides,
  };
}

export function preview(overrides: Partial<GigMergePreview> = {}): GigMergePreview {
  const from = gig('gig-from', 'Keo (fan copy)');
  const into = gig('gig-into', 'Keo');
  return {
    from: side(from, { addedBy: 'user-1', listings: [] }),
    into: side(into),
    sameDay: true,
    sameVenue: true,
    moving: [
      { what: 'attendance', count: 3 },
      { what: 'acts', count: 2 },
      { what: 'posts', count: 1 },
      { what: 'gigComments', count: 0 },
    ],
    conflicts: [],
    ...overrides,
  };
}

export function fanGig(id: string, overrides: Partial<FanAddedGig> = {}): FanAddedGig {
  return {
    gig: { ...gig(id, `Fan gig ${id}`), userAdded: true },
    addedBy: 'user-1',
    addedAt: '2026-10-04T10:00:00Z',
    reviewedAt: null,
    reviewedBy: null,
    attendance: 2,
    posts: 1,
    likelyDuplicates: [gig('gig-real', 'Keo')],
    ...overrides,
  };
}

/** What the Catalog page gives a tab, with nothing handed over and no venue merge yet. */
export function tabProps(overrides: Partial<CatalogTabProps> = {}): CatalogTabProps {
  return {
    openMerge: () => undefined,
    pair: null,
    openTab: () => undefined,
    venueMerge: null,
    setVenueMerge: () => undefined,
    mergedGigIds: new Set(),
    onGigMerged: () => undefined,
    ...overrides,
  };
}
