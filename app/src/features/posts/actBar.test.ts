import { actAt, actSegments, firstClipOf, showsActBar } from './actBar';

const keo = { id: 'a1', name: 'Keo' };
const cards = { id: 'a2', name: 'Cardinals' };
const clips = [
  { act: cards },
  { act: cards },
  { act: keo },
  { act: keo },
  { act: keo },
  { act: undefined },
];

describe('the act bar of a post', () => {
  it('has one segment per act, in clip order, and none for clips with no act', () => {
    expect(actSegments(clips)).toEqual([
      { actId: 'a2', name: 'Cardinals', first: 0, last: 1 },
      { actId: 'a1', name: 'Keo', first: 2, last: 4 },
    ]);
  });

  it('shows only for two acts or more', () => {
    expect(showsActBar(actSegments(clips))).toBe(true);
    expect(showsActBar(actSegments([{ act: keo }, { act: keo }, { act: undefined }]))).toBe(false);
    expect(showsActBar(actSegments([]))).toBe(false);
  });

  it('highlights the act of the clip in view, moving at the boundary', () => {
    const segments = actSegments(clips);
    expect(actAt(segments, 1)).toBe('a2');
    expect(actAt(segments, 2)).toBe('a1');
    expect(actAt(segments, 5)).toBeNull();
  });

  it("jumps to an act's first clip", () => {
    const segments = actSegments(clips);
    expect(firstClipOf(segments, 'a1')).toBe(2);
    expect(firstClipOf(segments, 'a2')).toBe(0);
    expect(firstClipOf(segments, 'nobody')).toBe(0);
  });
});
