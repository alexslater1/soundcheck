import type { VenueMergePreview } from '@/api/generated/model';

import {
  inUk,
  osmUrl,
  parseLatLng,
  reasonLabel,
  venueCountLabel,
  venueMergeChanges,
  venueMergeWarnings,
} from './venueModel';

function preview(overrides: Partial<VenueMergePreview> = {}): VenueMergePreview {
  const side = (id: string, name: string) => ({ id, name, externalIds: [], counts: [] });
  return {
    from: side('v-from', 'The O2 Arena'),
    into: side('v-into', 'The O2'),
    distanceMetres: 40,
    moving: [
      { what: 'gigs', count: 12 },
      { what: 'followers', count: 1 },
      { what: 'siblings', count: 0 },
    ],
    sameNight: [],
    conflicts: [],
    ...overrides,
  };
}

describe('venueModel', () => {
  it('builds the same OpenStreetMap link as the server', () => {
    expect(osmUrl(53.4631, -2.2913)).toBe(
      'https://www.openstreetmap.org/?mlat=53.463100&mlon=-2.291300#map=18/53.463100/-2.291300',
    );
  });

  it('reads a pasted pair, latitude first, and nothing else', () => {
    expect(parseLatLng('53.4631, -2.2913')).toEqual({ lat: 53.4631, lng: -2.2913 });
    expect(parseLatLng(' 51.5  -0.12 ')).toEqual({ lat: 51.5, lng: -0.12 });
    expect(parseLatLng('53.4631')).toBeNull();
    expect(parseLatLng('north, west')).toBeNull();
    expect(parseLatLng('1, 2, 3')).toBeNull();
  });

  it('keeps points inside the UK box, so a swapped pair is caught', () => {
    expect(inUk({ lat: 53.4631, lng: -2.2913 })).toBe(true);
    expect(inUk({ lat: -2.2913, lng: 53.4631 })).toBe(false);
    expect(inUk({ lat: 48.8566, lng: 2.3522 })).toBe(false);
  });

  it('names the reasons and the counts', () => {
    expect(reasonLabel('CITY_CENTRE')).toBe("At a town's centre point");
    expect(reasonLabel('SOMETHING')).toBe('SOMETHING');
    expect(venueCountLabel({ what: 'gigs', count: 1 })).toBe('1 gig');
    expect(venueCountLabel({ what: 'followers', count: 3 })).toBe('3 followers');
    expect(venueCountLabel({ what: 'other', count: 2 })).toBe('2 other');
  });

  it('warns about distance and shared nights without refusing', () => {
    expect(venueMergeWarnings(preview())).toEqual([]);
    expect(
      venueMergeWarnings(
        preview({
          distanceMetres: 5200,
          sameNight: [
            { movedGigId: 'a', movedTitle: 'Keo', gigId: 'b', title: 'Keo', day: '2026-10-03' },
          ],
        }),
      ),
    ).toEqual([
      'The two venues are 5.2 km apart.',
      '1 gig will share a night with a gig at the venue that stays: check them on the Merge tab afterwards.',
    ]);
  });

  it('says in the confirm dialog what moves and what happens to the duplicate', () => {
    expect(venueMergeChanges(preview())).toEqual([
      'Move 12 gigs to The O2',
      'Move 1 follower to The O2',
      'Delete The O2 Arena',
      'Send its old link, and its name in new listings nearby, to The O2',
    ]);
  });
});
