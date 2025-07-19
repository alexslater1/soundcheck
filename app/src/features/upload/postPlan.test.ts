import type { ArtistSummary, GigSummary } from '@/api/generated/model';

import {
  actsOverLimit,
  festivalDays,
  looksMultiDay,
  MAX_CLIPS_PER_ACT,
  postHint,
  runningOrder,
} from './postPlan';

const act = (id: string) => ({ id, name: id, genres: [], isFollowing: false }) as ArtistSummary;

function gig(over: Partial<GigSummary> = {}): GigSummary {
  return {
    id: 'g',
    title: 'Keo',
    startsAt: '2026-08-01T18:30:00Z',
    headliner: act('head'),
    support: [act('main-support'), act('opener')],
    venue: { id: 'v', name: 'Scala' },
    status: 'SCHEDULED',
    userAdded: false,
    ...over,
  } as GigSummary;
}

describe('runningOrder', () => {
  it('puts support acts first (the opener first), then the headliner, others, and no act last', () => {
    const clips = [
      { localId: '1', artistId: 'head' },
      { localId: '2', artistId: null },
      { localId: '3', artistId: 'main-support' },
      { localId: '4', artistId: 'someone-else' },
      { localId: '5', artistId: 'opener' },
      { localId: '6', artistId: 'head' },
    ];
    expect(runningOrder(clips, gig()).map((c) => c.localId)).toEqual([
      '5',
      '3',
      '1',
      '6',
      '4',
      '2',
    ]);
  });

  it('keeps the picked order without a gig', () => {
    const clips = [
      { localId: '1', artistId: null },
      { localId: '2', artistId: 'x' },
    ];
    expect(runningOrder(clips, null).map((c) => c.localId)).toEqual(['2', '1']);
  });
});

describe('actsOverLimit', () => {
  it('names an act with more clips than a post may hold', () => {
    const clips = Array.from({ length: MAX_CLIPS_PER_ACT + 1 }, (_, i) => ({
      localId: String(i),
      artistId: 'head',
    }));
    expect(actsOverLimit(clips)).toEqual(['head']);
    expect(actsOverLimit(clips.slice(1))).toEqual([]);
  });
});

describe('festival days', () => {
  it('asks about days only for a gig that looks multi-day', () => {
    expect(looksMultiDay(gig())).toBe(false);
    expect(looksMultiDay(gig({ title: 'Green Man Festival 2026' }))).toBe(true);
    expect(looksMultiDay(gig({ title: 'Indietracks Fest' }))).toBe(true);
    expect(looksMultiDay(gig({ title: 'Bluedot all-dayer' }))).toBe(true);
    expect(looksMultiDay(gig({ title: 'Fester' }))).toBe(false);
    expect(looksMultiDay(gig({ support: [1, 2, 3, 4, 5].map((n) => act(`s${n}`)) }))).toBe(true);
  });

  it('offers the first day and up to three more, never a day still to come', () => {
    const festival = gig({ title: 'Green Man Festival' });
    expect(festivalDays(festival, new Date('2026-10-03T12:00:00Z'))).toEqual([
      '2026-08-01',
      '2026-08-02',
      '2026-08-03',
      '2026-08-04',
    ]);
    expect(festivalDays(festival, new Date('2026-08-02T20:00:00Z'))).toEqual([
      '2026-08-01',
      '2026-08-02',
    ]);
    expect(festivalDays(gig(), new Date('2026-10-03T12:00:00Z'))).toEqual([]);
  });

  it('counts days on the UK calendar', () => {
    // 23:30 UTC on 31 July is 00:30 on 1 August in London (BST).
    const late = gig({ title: 'Festival', startsAt: '2026-07-31T23:30:00Z' });
    expect(festivalDays(late, new Date('2026-08-01T12:00:00Z'))).toEqual(['2026-08-01']);
  });
});

describe('postHint', () => {
  it('says what is missing, in either order', () => {
    expect(postHint({ gigChosen: false, clips: 0, overLimit: false })).toBe(
      'Choose the gig and add your clips to post.',
    );
    expect(postHint({ gigChosen: false, clips: 2, overLimit: false })).toMatch(/Choose the gig/);
    expect(postHint({ gigChosen: true, clips: 0, overLimit: false })).toBe(
      'Add at least one clip to post.',
    );
    expect(postHint({ gigChosen: true, clips: 1, overLimit: true })).toMatch(/10 of your clips/);
    expect(postHint({ gigChosen: true, clips: 1, overLimit: false })).toBeNull();
    expect(postHint({ gigChosen: false, clips: 1, overLimit: false, joining: true })).toBeNull();
  });
});
