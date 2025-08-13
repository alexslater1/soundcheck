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
      { actId: 'a2', name: 'Cardinals', first: 0, count: 2 },
      { actId: 'a1', name: 'Keo', first: 2, count: 3 },
    ]);
  });

  it('shows whenever a clip has an act, one act included', () => {
    expect(showsActBar(actSegments(clips))).toBe(true);
    expect(showsActBar(actSegments([{ act: keo }, { act: keo }, { act: undefined }]))).toBe(true);
    expect(showsActBar(actSegments([{ act: undefined }, { act: null }]))).toBe(false);
    expect(showsActBar(actSegments([]))).toBe(false);
  });

  it('highlights the act of the clip in view, moving at the boundary', () => {
    expect(actAt(clips, 1)).toBe('a2');
    expect(actAt(clips, 2)).toBe('a1');
    expect(actAt(clips, 5)).toBeNull();
    expect(actAt(clips, 9)).toBeNull();
  });

  it('keeps interleaved acts in the poster’s order: one chip per act, the clip’s own act lit', () => {
    const interleaved = [{ act: keo }, { act: cards }, { act: keo }];
    expect(actSegments(interleaved)).toEqual([
      { actId: 'a1', name: 'Keo', first: 0, count: 2 },
      { actId: 'a2', name: 'Cardinals', first: 1, count: 1 },
    ]);
    expect(actAt(interleaved, 1)).toBe('a2');
    expect(actAt(interleaved, 2)).toBe('a1');
    expect(firstClipOf(actSegments(interleaved), 'a1')).toBe(0);
  });

  it("jumps to an act's first clip", () => {
    const segments = actSegments(clips);
    expect(firstClipOf(segments, 'a1')).toBe(2);
    expect(firstClipOf(segments, 'a2')).toBe(0);
    expect(firstClipOf(segments, 'nobody')).toBe(0);
  });
});
