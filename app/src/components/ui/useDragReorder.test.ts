import { dropIndex, reorder, shiftFor } from './useDragReorder';

describe('drag to reorder', () => {
  it('moves an item to its new place', () => {
    expect(reorder(['a', 'b', 'c', 'd'], 0, 2)).toEqual(['b', 'c', 'a', 'd']);
    expect(reorder(['a', 'b', 'c', 'd'], 3, 1)).toEqual(['a', 'd', 'b', 'c']);
    expect(reorder(['a', 'b'], 1, 1)).toEqual(['a', 'b']);
    expect(reorder(['a', 'b'], 5, 0)).toEqual(['a', 'b']);
  });

  it('lands where the finger let go, a stride per place, never off the list', () => {
    expect(dropIndex(0, 130, 128, 4)).toBe(1);
    expect(dropIndex(0, 50, 128, 4)).toBe(0);
    expect(dropIndex(1, 700, 128, 4)).toBe(3);
    expect(dropIndex(2, -400, 128, 4)).toBe(0);
    expect(dropIndex(2, 300, 0, 4)).toBe(2);
  });

  it('steps the items between aside', () => {
    const down = { from: 0, to: 2 };
    expect([0, 1, 2, 3].map((i) => shiftFor(i, down, 100))).toEqual([0, -100, -100, 0]);
    const up = { from: 3, to: 1 };
    expect([0, 1, 2, 3].map((i) => shiftFor(i, up, 100))).toEqual([0, 100, 100, 0]);
    expect(shiftFor(1, null, 100)).toBe(0);
  });
});
