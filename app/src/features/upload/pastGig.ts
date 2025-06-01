import type { AddGigRequest, PastGig } from '@/api/generated/model';

/**
 * The request that adds the gig a fan picked from setlist.fm. Only what identifies the gig is
 * sent: the act, the venue with its city and coordinates, the date, and the setlist's id and link
 * (kept as the gig's listing so its page links back to setlist.fm). Null when setlist.fm has no
 * coordinates for the city: the venue could not be placed, so the gig must be added by hand.
 */
export function pastGigRequest(gig: PastGig): AddGigRequest | null {
  if (gig.lat == null || gig.lng == null) {
    return null;
  }
  return {
    artistNames: [gig.artistName],
    newVenue: {
      name: gig.venueName,
      cityName: gig.cityName ?? gig.venueName,
      lat: gig.lat,
      lng: gig.lng,
    },
    date: gig.date,
    foundOn: {
      source: 'SETLISTFM',
      externalId: gig.setlistId,
      ...(gig.url ? { url: gig.url } : {}),
      ...(gig.artistMbid ? { artistMbid: gig.artistMbid } : {}),
    },
  };
}

/** Year choices for the search: any year, then this year and the three before. */
export function yearChoices(now: Date): (number | null)[] {
  const year = now.getFullYear();
  return [null, year, year - 1, year - 2, year - 3];
}
