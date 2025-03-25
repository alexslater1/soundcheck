import { playerWindow } from './playerWindow';

const ids = ['a', 'b', 'c', 'd', 'e', 'f'];

describe('playerWindow', () => {
  it('is the previous, current and next clip', () => {
    expect(playerWindow(ids, 'c')).toEqual(['b', 'c', 'd']);
  });

  it('slides inside the list at both ends instead of shrinking', () => {
    expect(playerWindow(ids, 'a')).toEqual(['a', 'b', 'c']);
    expect(playerWindow(ids, 'f')).toEqual(['d', 'e', 'f']);
  });

  it('never exceeds the size, wherever the current clip is', () => {
    for (const id of ids) {
      const window = playerWindow(ids, id);
      expect(window.length).toBeLessThanOrEqual(3);
      expect(window).toContain(id);
    }
  });

  it('is the whole list when the list is shorter than the window', () => {
    expect(playerWindow(['a', 'b'], 'b')).toEqual(['a', 'b']);
  });

  it('is empty when nothing is current or the current clip is not in the list', () => {
    expect(playerWindow(ids, null)).toEqual([]);
    expect(playerWindow(ids, 'zz')).toEqual([]);
    expect(playerWindow([], 'a')).toEqual([]);
  });

  it('honours another size', () => {
    expect(playerWindow(ids, 'c', 1)).toEqual(['c']);
    expect(playerWindow(ids, 'c', 5)).toEqual(['a', 'b', 'c', 'd', 'e']);
  });
});
