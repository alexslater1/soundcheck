import { preview } from './catalogTestData';
import { countLabel, isGigId, mergeChanges, mergeWarnings } from './mergeModel';

describe('mergeModel', () => {
  it('names each kind of row, singular and plural', () => {
    expect(countLabel({ what: 'posts', count: 1 })).toBe('1 post');
    expect(countLabel({ what: 'gigComments', count: 3 })).toBe('3 gig comments');
    expect(countLabel({ what: 'somethingNew', count: 2 })).toBe('2 somethingNew');
  });

  it('lists only what moves, then the delete and the redirect', () => {
    expect(mergeChanges(preview())).toEqual([
      'Move 3 fan marks (Interested, Going, Went) to Keo · Sat 3 Oct 2026 · Band on the Wall',
      'Move 2 acts on the bill to Keo · Sat 3 Oct 2026 · Band on the Wall',
      'Move 1 post to Keo · Sat 3 Oct 2026 · Band on the Wall',
      'Delete Keo (fan copy) · Sat 3 Oct 2026 · Band on the Wall',
      'Send its old link to the gig that stays',
    ]);
  });

  it('says so when nothing moves', () => {
    expect(mergeChanges(preview({ moving: [{ what: 'posts', count: 0 }] }))[0]).toMatch(
      /^Move nothing/,
    );
  });

  it('warns about different days and venues without refusing', () => {
    expect(mergeWarnings(preview())).toEqual([]);
    expect(mergeWarnings(preview({ sameDay: false, sameVenue: false }))).toEqual([
      'The two gigs are on different days.',
      'The two gigs are at different venues.',
    ]);
  });

  it('recognises a pasted gig id', () => {
    expect(isGigId(' 0199a1b2-3c4d-7e5f-8a9b-0c1d2e3f4a5b ')).toBe(true);
    expect(isGigId('Keo')).toBe(false);
  });
});
