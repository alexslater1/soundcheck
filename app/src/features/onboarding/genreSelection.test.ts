import { canContinue, setWeight, toggleGenre, toRequest } from './genreSelection';

describe('genre selection', () => {
  it('toggles, caps at 8 and needs at least one to continue', () => {
    let s = toggleGenre(new Map(), 2);
    expect(s.get(2)).toBe(1);
    expect(canContinue(s)).toBe(true);
    for (const id of [3, 4, 5, 6, 7, 8, 9]) s = toggleGenre(s, id);
    expect(s.size).toBe(8);
    s = toggleGenre(s, 10); // ninth is ignored
    expect(s.size).toBe(8);
    s = toggleGenre(s, 2);
    expect(s.has(2)).toBe(false);
    expect(canContinue(new Map())).toBe(false);
  });

  it('adjusts weights only for selected genres within [0, 1]', () => {
    let s = toggleGenre(new Map(), 14);
    s = setWeight(s, 14, 0.5);
    expect(s.get(14)).toBe(0.5);
    expect(setWeight(s, 99, 0.5)).toBe(s);
    expect(setWeight(s, 14, 2)).toBe(s);
    expect(toRequest(s)).toEqual([{ genreId: 14, weight: 0.5 }]);
  });
});
