import type { ListGigsParams, SaveGigFilterRequest, SavedGigFilter } from '@/api/generated/model';

/**
 * The Gigs tab's search as data: what was picked from the suggestions, the genre and
 * the place. Pure functions turn it into `GET /gigs` parameters, into a saved filter and back, so the
 * screen only holds state and draws it.
 */

/** A chosen suggestion: an artist, a venue, or the typed text itself ("Search gigs for …"). */
export type Pick =
  | { kind: 'artist'; id: string; name: string }
  | { kind: 'venue'; id: string; name: string; city?: string | null }
  | { kind: 'text'; text: string };

/** Where to look: near the home city, anywhere in the UK, or near another city's centre. */
export type Place =
  | { kind: 'home' }
  | { kind: 'uk' }
  | { kind: 'city'; id: number; name: string; lat: number; lng: number };

export type GigSearch = { picks: Pick[]; genre: string | null; place: Place };

export const EMPTY_SEARCH: GigSearch = { picks: [], genre: null, place: { kind: 'home' } };

/** "Near" a city means within this distance of its centre, as for the home city. */
export const CITY_RADIUS_KM = 25;

/** At most this many artists and as many venues (the API's limit). */
export const MAX_PICKS_PER_KIND = 20;

export function pickKey(pick: Pick): string {
  return pick.kind === 'text' ? `text:${pick.text.toLowerCase()}` : `${pick.kind}:${pick.id}`;
}

/**
 * Adds a pick (several artists and venues at once). Picking the same one again changes nothing; a
 * new text replaces the old one (the API takes one `q`). The place is left as it is.
 */
export function addPick(search: GigSearch, pick: Pick): GigSearch {
  if (search.picks.some((p) => pickKey(p) === pickKey(pick))) {
    return search;
  }
  if (
    pick.kind !== 'text' &&
    search.picks.filter((p) => p.kind === pick.kind).length >= MAX_PICKS_PER_KIND
  ) {
    return search;
  }
  const others =
    pick.kind === 'text' ? search.picks.filter((p) => p.kind !== 'text') : search.picks;
  return { ...search, picks: [...others, pick] };
}

export function removePick(search: GigSearch, pick: Pick): GigSearch {
  return { ...search, picks: search.picks.filter((p) => pickKey(p) !== pickKey(pick)) };
}

/** Anything beyond "near home, every genre"? (Only then is there something worth saving.) */
export function isNarrowed(search: GigSearch): boolean {
  return search.picks.length > 0 || search.genre !== null || search.place.kind !== 'home';
}

/** The `GET /gigs` / `GET /gigs/days` parameters of a search (everything but the date window). */
export function toParams(
  search: GigSearch,
): Omit<ListGigsParams, 'cursor' | 'from' | 'to' | 'limit'> {
  const artistIds = search.picks.flatMap((p) => (p.kind === 'artist' ? [p.id] : []));
  const venueIds = search.picks.flatMap((p) => (p.kind === 'venue' ? [p.id] : []));
  const text = search.picks.find((p) => p.kind === 'text');
  const { place } = search;
  return {
    ...(search.genre ? { genre: search.genre } : {}),
    ...(text ? { q: text.text } : {}),
    ...(artistIds.length > 0 ? { artistIds } : {}),
    ...(venueIds.length > 0 ? { venueIds } : {}),
    ...(place.kind === 'uk' ? { nationwide: true } : {}),
    ...(place.kind === 'city' ? { lat: place.lat, lng: place.lng, radiusKm: CITY_RADIUS_KM } : {}),
  };
}

/** How the place reads under the title. */
export function describePlace(place: Place, homeCity: string | null): string {
  switch (place.kind) {
    case 'uk':
      return 'Anywhere in the UK';
    case 'city':
      return `Within ${CITY_RADIUS_KM} km of ${place.name}`;
    case 'home':
      return homeCity
        ? `Within ${CITY_RADIUS_KM} km of ${homeCity}`
        : 'Across the UK — pick a home city in Settings';
  }
}

/** A name to offer when saving: the picks and the place, e.g. "Keo, Bleech 9:3 +1 · London". */
export function suggestName(search: GigSearch): string {
  const names = search.picks.map((p) => (p.kind === 'text' ? `“${p.text}”` : p.name));
  const head = names.slice(0, 2).join(', ') + (names.length > 2 ? ` +${names.length - 2}` : '');
  const where =
    search.place.kind === 'city' ? search.place.name : search.place.kind === 'uk' ? 'UK' : '';
  const genre = search.genre ? search.genre : '';
  return [head, genre, where].filter(Boolean).join(' · ').slice(0, 60) || 'Near me';
}

export function toSaveRequest(name: string, search: GigSearch): SaveGigFilterRequest {
  const text = search.picks.find((p) => p.kind === 'text');
  return {
    name,
    artistIds: search.picks.flatMap((p) => (p.kind === 'artist' ? [p.id] : [])),
    venueIds: search.picks.flatMap((p) => (p.kind === 'venue' ? [p.id] : [])),
    genre: search.genre,
    q: text ? text.text : null,
    place: search.place.kind === 'city' ? 'CITY' : search.place.kind === 'uk' ? 'UK' : 'HOME',
    cityId: search.place.kind === 'city' ? search.place.id : null,
  };
}

/** A saved filter back into a search. */
export function fromSaved(saved: SavedGigFilter): GigSearch {
  const picks: Pick[] = [
    ...saved.artists.map((a) => ({ kind: 'artist' as const, id: a.id, name: a.name })),
    ...saved.venues.map((v) => ({ kind: 'venue' as const, id: v.id, name: v.name, city: v.city })),
    ...(saved.q ? [{ kind: 'text' as const, text: saved.q }] : []),
  ];
  const { place } = saved;
  return {
    picks,
    genre: saved.genre ?? null,
    place:
      place.kind === 'CITY' && place.cityId != null && place.lat != null && place.lng != null
        ? {
            kind: 'city',
            id: place.cityId,
            name: place.cityName ?? 'City',
            lat: place.lat,
            lng: place.lng,
          }
        : place.kind === 'UK'
          ? { kind: 'uk' }
          : { kind: 'home' },
  };
}
