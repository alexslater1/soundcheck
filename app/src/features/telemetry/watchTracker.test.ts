import { advanceWatch, INITIAL_WATCH, type WatchEvent, type WatchState } from './watchTracker';

/** Feeds positions (ms) of a clip of the given length through the tracker; returns every event. */
function play(positions: number[], durationMs = 10_000, from: WatchState = INITIAL_WATCH) {
  let state = from;
  const events: WatchEvent[] = [];
  for (const positionMs of positions) {
    const step = advanceWatch(state, { positionMs, durationMs });
    state = step.state;
    events.push(...step.events);
  }
  return { state, events };
}

describe('watch tracker', () => {
  it('reports the start once playback moves', () => {
    expect(play([0]).events).toEqual([]);
    expect(play([0, 500, 1_000]).events).toEqual([{ type: 'VIEW_START' }]);
  });

  it('reports each quarter once, in order, and completion at 95 %', () => {
    const { events } = play([500, 2_500, 3_000, 5_000, 7_400, 7_500, 9_400, 9_500, 9_900]);

    expect(events).toEqual([
      { type: 'VIEW_START' },
      { type: 'WATCH_PROGRESS', value: 0.25 },
      { type: 'WATCH_PROGRESS', value: 0.5 },
      { type: 'WATCH_PROGRESS', value: 0.75 },
      { type: 'COMPLETE' },
    ]);
  });

  it('reports every mark that a single jump passes', () => {
    expect(play([500, 8_000]).events).toEqual([
      { type: 'VIEW_START' },
      { type: 'WATCH_PROGRESS', value: 0.25 },
      { type: 'WATCH_PROGRESS', value: 0.5 },
      { type: 'WATCH_PROGRESS', value: 0.75 },
    ]);
  });

  it('counts a loop as watched to the end, even when no report landed on the last frames', () => {
    // 9.2 s of a 10 s clip, then back at 0.3 s: the clip started over.
    const { events } = play([500, 5_000, 9_200, 300]);

    expect(events).toEqual([
      { type: 'VIEW_START' },
      { type: 'WATCH_PROGRESS', value: 0.25 },
      { type: 'WATCH_PROGRESS', value: 0.5 },
      { type: 'WATCH_PROGRESS', value: 0.75 },
      { type: 'COMPLETE' },
    ]);
  });

  it('reports nothing again on later loops of the same viewing', () => {
    const first = play([500, 5_000, 9_800]);
    const again = play([300, 2_600, 5_100, 7_600, 9_800, 200], 10_000, first.state);

    expect(again.events).toEqual([]);
  });

  it('ignores reports before the duration is known', () => {
    expect(play([500, 1_000], 0).events).toEqual([]);
    expect(play([500], Number.NaN).events).toEqual([]);
  });

  it('does not take a small step back (a seek within the first half) for a loop', () => {
    const { events } = play([500, 3_000, 1_000, 3_500]);

    expect(events).toEqual([{ type: 'VIEW_START' }, { type: 'WATCH_PROGRESS', value: 0.25 }]);
  });
});
