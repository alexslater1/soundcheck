import { pageStep, targetIndex, type KeyPress } from './pagerKeys';

const press = (key: string, extra: Partial<KeyPress> = {}): KeyPress => ({
  key,
  altKey: false,
  ctrlKey: false,
  metaKey: false,
  inTextField: false,
  ...extra,
});

describe('pageStep (keyboard navigation)', () => {
  it('turns the page with the arrows, Page Up / Down and j / k', () => {
    expect(['ArrowDown', 'PageDown', 'j'].map((key) => pageStep(press(key)))).toEqual([1, 1, 1]);
    expect(['ArrowUp', 'PageUp', 'k'].map((key) => pageStep(press(key)))).toEqual([-1, -1, -1]);
  });

  it('ignores other keys, shortcuts with modifiers, and typing in a text field', () => {
    expect(pageStep(press(' '))).toBeNull();
    expect(pageStep(press('J'))).toBeNull();
    expect(pageStep(press('ArrowDown', { metaKey: true }))).toBeNull();
    expect(pageStep(press('ArrowUp', { ctrlKey: true }))).toBeNull();
    expect(pageStep(press('k', { altKey: true }))).toBeNull();
    expect(pageStep(press('j', { inTextField: true }))).toBeNull();
  });
});

describe('targetIndex', () => {
  it('moves one page and stops at both ends', () => {
    expect(targetIndex(0, 1, 5)).toBe(1);
    expect(targetIndex(3, -1, 5)).toBe(2);
    expect(targetIndex(4, 1, 5)).toBeNull();
    expect(targetIndex(0, -1, 5)).toBeNull();
  });

  it('starts from the first page when none is current yet, and does nothing in an empty feed', () => {
    expect(targetIndex(-1, 1, 5)).toBe(1);
    expect(targetIndex(-1, 1, 0)).toBeNull();
  });
});
