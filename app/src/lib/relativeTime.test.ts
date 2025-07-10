import { act, renderHook } from '@testing-library/react-native';
import { AppState, type AppStateStatus } from 'react-native';

import { relativeTime, relativeTimeLabel, useNow } from './relativeTime';

const NOW = Date.parse('2026-10-03T14:00:00Z');
const ago = (ms: number) => new Date(NOW - ms).toISOString();
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const UK = 'Europe/London';

describe('relativeTime', () => {
  it.each([
    [0, 'now'],
    [59 * SECOND, 'now'],
    [MINUTE, '1m'],
    [9 * MINUTE + 59 * SECOND, '9m'],
    [59 * MINUTE, '59m'],
    [HOUR, '1h'],
    [23 * HOUR + 59 * MINUTE, '23h'],
    [DAY, '1d'],
    [6 * DAY + 23 * HOUR, '6d'],
  ])('%d ms ago reads %s', (elapsed, expected) => {
    expect(relativeTime(ago(elapsed), NOW, UK)).toBe(expected);
  });

  it('shows the date from a week on, with the year only when it is not this one', () => {
    expect(relativeTime('2026-09-26T14:00:00Z', NOW, UK)).toMatch(/^26 Sept?$/);
    expect(relativeTime('2026-01-05T09:00:00Z', NOW, UK)).toBe('5 Jan');
    expect(relativeTime('2025-12-25T20:00:00Z', NOW, UK)).toBe('25 Dec 2025');
  });

  it('reads the date in the given time zone', () => {
    // 23:30 UTC on 1 Jan is already 2 Jan in Tokyo.
    expect(relativeTime('2026-01-01T23:30:00Z', NOW, 'Asia/Tokyo')).toBe('2 Jan');
    expect(relativeTime('2026-01-01T23:30:00Z', NOW, UK)).toBe('1 Jan');
  });

  it('treats a time a little ahead of the phone (server clock) as now', () => {
    expect(relativeTime(new Date(NOW + 30 * SECOND).toISOString(), NOW, UK)).toBe('now');
  });
});

describe('relativeTimeLabel', () => {
  it('spells the age out for screen readers', () => {
    expect(relativeTimeLabel(ago(5 * SECOND), NOW, UK)).toBe('just now');
    expect(relativeTimeLabel(ago(MINUTE), NOW, UK)).toBe('1 minute ago');
    expect(relativeTimeLabel(ago(9 * MINUTE), NOW, UK)).toBe('9 minutes ago');
    expect(relativeTimeLabel(ago(HOUR), NOW, UK)).toBe('1 hour ago');
    expect(relativeTimeLabel(ago(3 * HOUR), NOW, UK)).toBe('3 hours ago');
    expect(relativeTimeLabel(ago(2 * DAY), NOW, UK)).toBe('2 days ago');
    expect(relativeTimeLabel('2025-12-25T20:00:00Z', NOW, UK)).toBe('on 25 Dec 2025');
  });
});

describe('useNow', () => {
  beforeEach(() => jest.useFakeTimers({ now: NOW }));
  afterEach(() => jest.useRealTimers());

  it('moves on while the screen stays open, and when the app comes back to the front', async () => {
    let onChange: ((state: AppStateStatus) => void) | undefined;
    const remove = jest.fn();
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_type, handler) => {
      onChange = handler as (state: AppStateStatus) => void;
      return { remove };
    });
    const { result, unmount } = await renderHook(() => useNow(30_000));
    expect(result.current).toBe(NOW);

    await act(() => jest.advanceTimersByTime(30_000));
    expect(result.current).toBe(NOW + 30_000);

    // Timers stop in the background; coming back reads the clock at once.
    jest.setSystemTime(NOW + 10 * MINUTE);
    await act(() => onChange?.('active'));
    expect(result.current).toBe(NOW + 10 * MINUTE);

    await unmount();
    expect(remove).toHaveBeenCalled();
  });
});
