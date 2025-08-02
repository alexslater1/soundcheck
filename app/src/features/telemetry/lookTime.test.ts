import { act, renderHook } from '@testing-library/react-native';

import { LOOK_TICK_MS, lookProgress, PHOTO_LOOK_MS, useLookTime } from './lookTime';

describe('lookProgress', () => {
  it('is the time looked at, as the position of a clip PHOTO_LOOK_MS long', () => {
    expect(lookProgress(1_250)).toEqual({ positionMs: 1_250, durationMs: PHOTO_LOOK_MS });
    expect(lookProgress(60_000).positionMs).toBe(PHOTO_LOOK_MS);
    expect(lookProgress(-5).positionMs).toBe(0);
  });
});

describe('useLookTime', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  const advance = async (ms: number) => {
    await act(async () => {
      jest.advanceTimersByTime(ms);
    });
  };

  it('counts only while active and on screen, carries on after a pause, and stops at the end', async () => {
    const onProgress = jest.fn();
    const { rerender } = await renderHook(
      ({ active, onScreen }: { active: boolean; onScreen: boolean }) =>
        useLookTime(active, onScreen, onProgress),
      { initialProps: { active: true, onScreen: true } },
    );

    await advance(1_000);
    expect(onProgress).toHaveBeenLastCalledWith(lookProgress(1_000));

    // Another screen covers this one: nothing counts.
    await rerender({ active: true, onScreen: false });
    const calls = onProgress.mock.calls.length;
    await advance(3_000);
    expect(onProgress).toHaveBeenCalledTimes(calls);

    // Back on screen: it carries on from where it was.
    await rerender({ active: true, onScreen: true });
    await advance(LOOK_TICK_MS);
    expect(onProgress).toHaveBeenLastCalledWith(lookProgress(1_000 + LOOK_TICK_MS));

    // Once the look is complete, reporting stops.
    await advance(PHOTO_LOOK_MS);
    const atEnd = onProgress.mock.calls.length;
    expect(onProgress).toHaveBeenLastCalledWith(lookProgress(PHOTO_LOOK_MS));
    await advance(5_000);
    expect(onProgress).toHaveBeenCalledTimes(atEnd);
  });

  it('starts a new viewing from zero once the photo becomes active again', async () => {
    const onProgress = jest.fn();
    const { rerender } = await renderHook(
      ({ active }: { active: boolean }) => useLookTime(active, true, onProgress),
      { initialProps: { active: true } },
    );
    await advance(2_000);

    await rerender({ active: false });
    await rerender({ active: true });
    await advance(LOOK_TICK_MS);

    expect(onProgress).toHaveBeenLastCalledWith(lookProgress(LOOK_TICK_MS));
  });
});
