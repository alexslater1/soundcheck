import { formatClock, progressShare, seekTarget } from './clock';

describe('formatClock', () => {
  it.each([
    [0, '0:00'],
    [999, '0:00'],
    [7_400, '0:07'],
    [65_000, '1:05'],
    [750_000, '12:30'],
    [-5, '0:00'],
  ])('%i ms reads %s', (ms, text) => {
    expect(formatClock(ms)).toBe(text);
  });
});

describe('seekTarget', () => {
  it('maps a point on the bar to a time in the clip', () => {
    expect(seekTarget(50, 200, 40_000)).toBe(10_000);
    expect(seekTarget(200, 200, 40_000)).toBe(40_000);
  });

  it('stays inside the clip when the finger slides off either end', () => {
    expect(seekTarget(-30, 200, 40_000)).toBe(0);
    expect(seekTarget(260, 200, 40_000)).toBe(40_000);
  });

  it('is 0 before the bar or the clip has a size', () => {
    expect(seekTarget(50, 0, 40_000)).toBe(0);
    expect(seekTarget(50, 200, 0)).toBe(0);
  });
});

describe('progressShare', () => {
  it('is the played share of the clip, clamped', () => {
    expect(progressShare(10_000, 40_000)).toBe(0.25);
    expect(progressShare(50_000, 40_000)).toBe(1);
    expect(progressShare(1_000, 0)).toBe(0);
  });
});
