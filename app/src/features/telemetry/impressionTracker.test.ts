import { createImpressionTracker, IMPRESSION_AFTER_MS } from './impressionTracker';

function harness() {
  const impressions: string[] = [];
  const timers: { fn: () => void; ms: number; cancelled: boolean }[] = [];
  const tracker = createImpressionTracker({
    schedule: (fn, ms) => {
      const timer = { fn, ms, cancelled: false };
      timers.push(timer);
      return () => {
        timer.cancelled = true;
      };
    },
    onImpression: (id) => impressions.push(id),
  });
  /** A second passes: every timer still pending fires. */
  const aSecondPasses = () => {
    const due = timers.filter((timer) => !timer.cancelled);
    timers.length = 0;
    due.forEach((timer) => timer.fn());
  };
  return { tracker, impressions, timers, aSecondPasses };
}

describe('impression tracker', () => {
  it('logs an impression once an item has been half visible for a second', () => {
    const { tracker, impressions, timers, aSecondPasses } = harness();

    tracker.update(['a']);
    expect(impressions).toEqual([]);
    expect(timers[0]!.ms).toBe(IMPRESSION_AFTER_MS);

    aSecondPasses();
    expect(impressions).toEqual(['a']);
  });

  it('logs nothing for an item that was scrolled past in under a second', () => {
    const { tracker, impressions, aSecondPasses } = harness();

    tracker.update(['a']);
    tracker.update(['b']); // a left before its second was up
    aSecondPasses();

    expect(impressions).toEqual(['b']);
  });

  it('starts the second again when an item comes back into view', () => {
    const { tracker, impressions, aSecondPasses } = harness();

    tracker.update(['a']);
    tracker.update([]);
    tracker.update(['a']);
    expect(impressions).toEqual([]);

    aSecondPasses();
    expect(impressions).toEqual(['a']);
  });

  it('logs each item once, however often it is seen again', () => {
    const { tracker, impressions, aSecondPasses } = harness();

    tracker.update(['a']);
    aSecondPasses();
    tracker.update(['b']);
    aSecondPasses();
    tracker.update(['a', 'b']);
    aSecondPasses();

    expect(impressions).toEqual(['a', 'b']);
  });

  it('does not restart the second while an item simply stays visible', () => {
    const { tracker, timers } = harness();

    tracker.update(['a']);
    tracker.update(['a', 'b']);
    tracker.update(['a', 'b']);

    expect(timers).toHaveLength(2); // one for a, one for b
  });

  it('starts over after a reset (a new feed session)', () => {
    const { tracker, impressions, aSecondPasses } = harness();
    tracker.update(['a']);
    aSecondPasses();
    tracker.update(['b']);

    tracker.reset();
    aSecondPasses(); // b's pending second was cancelled
    tracker.update(['a']);
    aSecondPasses();

    expect(impressions).toEqual(['a', 'a']);
  });
});
