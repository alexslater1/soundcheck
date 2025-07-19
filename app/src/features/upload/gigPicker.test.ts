import { addAct, parseTime, postLabel, searchWindow } from './gigPicker';

describe('gig picker helpers', () => {
  it('searches a year back from now, up to the end of the UK day', () => {
    // Every window ends at midnight UK time (23:00 UTC in summer): no gig of a later day.
    expect(searchWindow(new Date('2026-10-02T20:00:00Z'))).toEqual({
      from: '2025-10-02T20:00:00.000Z',
      to: '2026-10-02T23:00:00.000Z',
    });
  });

  it("ends at the UK day's midnight, after midnight UTC too, and in winter", () => {
    // 00:30 BST on 3 Oct: the day is the 3rd.
    expect(searchWindow(new Date('2026-10-02T23:30:00Z')).to).toBe('2026-10-03T23:00:00.000Z');
    // GMT: midnight is midnight UTC.
    expect(searchWindow(new Date('2026-12-01T12:00:00Z')).to).toBe('2026-12-02T00:00:00.000Z');
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
