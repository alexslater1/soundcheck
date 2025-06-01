import type { PastGig } from '@/api/generated/model';

import { pastGigRequest, yearChoices } from './pastGig';

const gig: PastGig = {
  setlistId: '63d6a2b3',
  url: 'https://www.setlist.fm/setlist/pale-kites/2025/x-63d6a2b3.html',
  date: '2025-10-17',
  artistName: 'Pale Kites',
  artistMbid: '5b11f4ce-a62d-471e-81fc-a69a8278c7da',
  venueName: 'The Snug',
  cityName: 'Manchester',
  countryCode: 'GB',
  lat: 53.48,
  lng: -2.24,
  tour: null,
};

describe('pastGigRequest', () => {
  it('adds the gig with the act, the placed venue, the date and the link back to setlist.fm', () => {
    expect(pastGigRequest(gig)).toEqual({
      artistNames: ['Pale Kites'],
      newVenue: { name: 'The Snug', cityName: 'Manchester', lat: 53.48, lng: -2.24 },
      date: '2025-10-17',
      foundOn: {
        source: 'SETLISTFM',
        externalId: '63d6a2b3',
        url: 'https://www.setlist.fm/setlist/pale-kites/2025/x-63d6a2b3.html',
        artistMbid: '5b11f4ce-a62d-471e-81fc-a69a8278c7da',
      },
    });
  });

  it('cannot place a venue without coordinates', () => {
    expect(pastGigRequest({ ...gig, lat: null, lng: null })).toBeNull();
  });

  it('offers any year, then this year and the three before', () => {
    expect(yearChoices(new Date('2026-10-02T12:00:00Z'))).toEqual([null, 2026, 2025, 2024, 2023]);
  });
});
