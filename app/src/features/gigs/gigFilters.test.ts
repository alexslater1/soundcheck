import type { SavedGigFilter } from '@/api/generated/model';

import {
  addPick,
  describePlace,
  EMPTY_SEARCH,
  fromSaved,
  isNarrowed,
  removePick,
  suggestName,
  toParams,
  toSaveRequest,
  type GigSearch,
} from './gigFilters';

const keo = { kind: 'artist' as const, id: 'a-keo', name: 'Keo' };
const bleech = { kind: 'artist' as const, id: 'a-bleech', name: 'Bleech 9:3' };
const north = { kind: 'artist' as const, id: 'a-north', name: 'the north' };
const london = { kind: 'city' as const, id: 7, name: 'London', lat: 51.5, lng: -0.12 };

describe('gig search', () => {
  it('keeps several artists, once each, and the place as it was', () => {
    let s = addPick(EMPTY_SEARCH, keo);
    s = addPick(s, bleech);
    s = addPick(s, keo);
    expect(s.picks).toEqual([keo, bleech]);
    expect(s.place).toEqual({ kind: 'home' });
    expect(removePick(s, keo).picks).toEqual([bleech]);
  });

  it('keeps one typed text: a new one replaces the old', () => {
    let s = addPick(EMPTY_SEARCH, { kind: 'text', text: 'jazz night' });
    s = addPick(s, keo);
    s = addPick(s, { kind: 'text', text: 'late show' });
    expect(s.picks).toEqual([keo, { kind: 'text', text: 'late show' }]);
  });

  it('turns a search into GET /gigs parameters for each place', () => {
    const base: GigSearch = { ...EMPTY_SEARCH, picks: [keo, bleech], genre: 'rock' };
    expect(toParams(base)).toEqual({ genre: 'rock', artistIds: ['a-keo', 'a-bleech'] });
    expect(toParams({ ...base, place: { kind: 'uk' } })).toMatchObject({ nationwide: true });
    expect(toParams({ ...base, place: london })).toMatchObject({
      lat: 51.5,
      lng: -0.12,
      radiusKm: 25,
    });
    expect(toParams(EMPTY_SEARCH)).toEqual({});
  });

  it('describes the place', () => {
    expect(describePlace({ kind: 'home' }, 'Manchester')).toBe('Within 25 km of Manchester');
    expect(describePlace(london, 'Manchester')).toBe('Within 25 km of London');
    expect(describePlace({ kind: 'uk' }, null)).toBe('Anywhere in the UK');
  });

  it('round-trips through a saved filter', () => {
    const s: GigSearch = {
      picks: [keo, bleech, north, { kind: 'venue', id: 'v1', name: 'Roundhouse', city: 'London' }],
      genre: null,
      place: london,
    };
    expect(isNarrowed(s)).toBe(true);
    expect(isNarrowed(EMPTY_SEARCH)).toBe(false);
    expect(suggestName(s)).toBe('Keo, Bleech 9:3 +2 · London');
    const request = toSaveRequest('My bands in London', s);
    expect(request).toEqual({
      name: 'My bands in London',
      artistIds: ['a-keo', 'a-bleech', 'a-north'],
      venueIds: ['v1'],
      genre: null,
      q: null,
      place: 'CITY',
      cityId: 7,
    });
    const saved: SavedGigFilter = {
      id: 'f1',
      name: request.name,
      alerts: true,
      artists: [
        { id: 'a-keo', name: 'Keo' },
        { id: 'a-bleech', name: 'Bleech 9:3' },
        { id: 'a-north', name: 'the north' },
      ],
      venues: [{ id: 'v1', name: 'Roundhouse', city: 'London' }],
      genre: null,
      q: null,
      place: { kind: 'CITY', cityId: 7, cityName: 'London', lat: 51.5, lng: -0.12 },
    };
    expect(fromSaved(saved)).toEqual(s);
  });
});
