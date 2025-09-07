import {
  NONE,
  configLabel,
  dayLabel,
  formatCount,
  formatMs,
  formatPercent,
  signedDelta,
  syntheticBanner,
} from './format';

describe('overview format', () => {
  it('writes percentages with a decimal only under 10 %', () => {
    expect(formatPercent(0.4)).toBe('40 %');
    expect(formatPercent(0.0479)).toBe('4.8 %');
    expect(formatPercent(0.9963)).toBe('100 %');
    expect(formatPercent(0)).toBe('0 %');
    expect(formatPercent(null)).toBe(NONE);
    expect(formatPercent(undefined)).toBe(NONE);
  });

  it('writes durations in ms, seconds or minutes', () => {
    expect(formatMs(850)).toBe('850 ms');
    expect(formatMs(1400)).toBe('1.4 s');
    expect(formatMs(31_102.5)).toBe('31.1 s');
    expect(formatMs(95_655)).toBe('1.6 min');
    expect(formatMs(null)).toBe(NONE);
  });

  it('keeps whole counts whole and rounds averages to one decimal', () => {
    expect(formatCount(74)).toBe('74');
    expect(formatCount(10.857)).toBe('10.9');
    expect(formatCount(1234)).toBe('1,234');
    expect(formatCount(null)).toBe(NONE);
  });

  it('gives the signed change on the previous period, or nothing to compare', () => {
    expect(signedDelta(12, 10)).toBeCloseTo(0.2);
    expect(signedDelta(5, 10)).toBeCloseTo(-0.5);
    expect(signedDelta(10, 10)).toBe(0);
    expect(signedDelta(3, 0)).toBeNull(); // up from nothing: no percentage
    expect(signedDelta(null, 10)).toBeNull();
    expect(signedDelta(3, null)).toBeNull();
  });

  it('labels UK days and config versions', () => {
    expect(dayLabel('2026-10-02')).toBe('2 Oct');
    // 23:30 UTC on 1 October is already 2 October in London (BST).
    expect(configLabel(3, '2026-10-01T23:30:00Z')).toBe('v3 · 2 Oct');
    expect(configLabel(1, null)).toBe('v1');
  });

  it('says how much of the activity is synthetic, even when none of it is', () => {
    expect(syntheticBanner(0.7264)).toBe(
      "73 % of this period's activity is from seeded or simulated accounts",
    );
    expect(syntheticBanner(0)).toBe(
      "0 % of this period's activity is from seeded or simulated accounts",
    );
    expect(syntheticBanner(null)).toMatch(/No activity in this period/);
  });
});
