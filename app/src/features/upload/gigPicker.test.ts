import type { GigSummary } from '@/api/generated/model';

import { addAct, parseTime, pickerWindows, postLabel, suggestedGigs } from './gigPicker';

const gig = (id: string, startsAt: string) => ({ id, startsAt }) as GigSummary;

describe('gig picker helpers', () => {
  it('looks back from now: two weeks of mine, three days nearby, a year when searching', () => {
    const w = pickerWindows(new Date('2026-10-02T20:00:00Z'));
    expect(w.mine).toEqual({ from: '2026-09-18T20:00:00.000Z', to: '2026-10-03T20:00:00.000Z' });
    expect(w.recent).toEqual({ from: '2026-09-29T20:00:00.000Z', to: '2026-10-03T08:00:00.000Z' });
    expect(w.search.from).toBe('2025-10-02T20:00:00.000Z');
  });

  it("suggests the user's own gigs first, newest first, then recent ones nearby, each once", () => {
    const mine = [gig('m-old', '2026-09-20T19:00:00Z'), gig('m-new', '2026-10-01T19:00:00Z')];
    const recent = [gig('r1', '2026-09-30T19:00:00Z'), gig('m-new', '2026-10-01T19:00:00Z')];
    expect(suggestedGigs(mine, recent).map((g) => g.id)).toEqual(['m-new', 'm-old', 'r1']);
    expect(suggestedGigs(mine, recent, 2)).toHaveLength(2);
  });

  it('reads a start time, or nothing', () => {
    expect(parseTime('19:30')).toBe('19:30');
    expect(parseTime(' 9:05 ')).toBe('09:05');
    expect(parseTime('24:00')).toBeNull();
    expect(parseTime('7pm')).toBeNull();
    expect(parseTime('')).toBeNull();
  });

  it('labels the post button by the number of clips', () => {
    expect(postLabel(1)).toBe('Post');
    expect(postLabel(3)).toBe('Post 3 clips');
  });

  it('lists each act once, known or new', () => {
    let acts = addAct([], { id: 'a1', name: 'Pale Kites' });
    acts = addAct(acts, { id: null, name: '  night   bus ' });
    acts = addAct(acts, { id: null, name: 'pale kites' });
    acts = addAct(acts, { id: 'a1', name: 'Pale Kites' });
    acts = addAct(acts, { id: null, name: '   ' });
    expect(acts).toEqual([
      { id: 'a1', name: 'Pale Kites' },
      { id: null, name: 'night bus' },
    ]);
  });
});
