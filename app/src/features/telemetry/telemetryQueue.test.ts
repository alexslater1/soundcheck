import type { EventIn } from '@/api/generated/model';

import {
  createTelemetryQueue,
  FLUSH_AFTER_MS,
  FLUSH_AT,
  MAX_BATCH,
  type TelemetryDeps,
} from './telemetryQueue';

const event = (n: number): EventIn => ({
  type: 'IMPRESSION',
  postId: `post-${n}`,
  surface: 'FOR_YOU',
  position: n,
});

/** Fake timers and a recordable `send`; `sleep` resolves at once and records the pause. */
function harness(send?: TelemetryDeps['send']) {
  const sent: EventIn[][] = [];
  const timers: { fn: () => void; ms: number; cancelled: boolean }[] = [];
  const pauses: number[] = [];
  const queue = createTelemetryQueue({
    send:
      send ??
      (async (events) => {
        sent.push(events);
      }),
    schedule: (fn, ms) => {
      const timer = { fn, ms, cancelled: false };
      timers.push(timer);
      return () => {
        timer.cancelled = true;
      };
    },
    sleep: async (ms) => {
      pauses.push(ms);
    },
  });
  const fireTimers = () =>
    timers.filter((timer) => !timer.cancelled).forEach((timer) => timer.fn());
  return { queue, sent, timers, pauses, fireTimers };
}

describe('telemetry queue', () => {
  it('waits, and flushes ten seconds after the first event', async () => {
    const { queue, sent, timers, fireTimers } = harness();

    queue.track(event(1));
    queue.track(event(2));

    expect(sent).toHaveLength(0);
    expect(queue.size()).toBe(2);
    // One timer for the batch, not one per event.
    expect(timers).toHaveLength(1);
    expect(timers[0]!.ms).toBe(FLUSH_AFTER_MS);

    fireTimers();
    await queue.flush();

    expect(sent).toEqual([[event(1), event(2)]]);
    expect(queue.size()).toBe(0);
  });

  it('flushes at once when twenty events are waiting, and cancels the timer', async () => {
    const { queue, sent, timers } = harness();

    for (let n = 1; n <= FLUSH_AT; n++) {
      queue.track(event(n));
    }
    await queue.flush();

    expect(sent).toHaveLength(1);
    expect(sent[0]).toHaveLength(FLUSH_AT);
    expect(timers[0]!.cancelled).toBe(true);
  });

  it('flushes on demand (app to the background, tab hidden) whatever is waiting', async () => {
    const { queue, sent } = harness();
    queue.track(event(1));

    await queue.flush();

    expect(sent).toEqual([[event(1)]]);
    // Nothing waiting: nothing is sent.
    await queue.flush();
    expect(sent).toHaveLength(1);
  });

  it('never sends more than a hundred events in one request', async () => {
    // Hold the first request so that events pile up behind it.
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    const batches: EventIn[][] = [];
    const slow = createTelemetryQueue({
      send: async (events) => {
        batches.push(events);
        if (batches.length === 1) {
          await held;
        }
      },
      schedule: () => () => undefined,
      sleep: async () => undefined,
    });
    for (let n = 1; n <= 20; n++) {
      slow.track(event(n));
    }
    for (let n = 21; n <= 250; n++) {
      slow.track(event(n));
    }
    // Only one request is in flight, however many events arrived meanwhile.
    expect(batches).toHaveLength(1);

    release();
    await slow.flush();

    expect(batches.map((batch) => batch.length)).toEqual([20, MAX_BATCH, MAX_BATCH, 30]);
    expect(batches.flat().map((e) => e.position)).toEqual(
      Array.from({ length: 250 }, (_, i) => i + 1),
    );
    expect(slow.size()).toBe(0);
  });

  it('retries a failed batch twice with a growing pause, then sends it', async () => {
    let calls = 0;
    const delivered: EventIn[][] = [];
    const { queue, pauses } = harness(async (events) => {
      calls++;
      if (calls < 3) {
        throw new Error('offline');
      }
      delivered.push(events);
    });
    queue.track(event(1));

    await queue.flush();

    expect(calls).toBe(3);
    expect(pauses).toEqual([1_000, 2_000]);
    expect(delivered).toEqual([[event(1)]]);
  });

  it('drops a batch after three failures and carries on with the next events', async () => {
    let calls = 0;
    const delivered: EventIn[][] = [];
    const { queue } = harness(async (events) => {
      calls++;
      if (calls <= 3) {
        throw new Error('server down');
      }
      delivered.push(events);
    });
    queue.track(event(1));
    await queue.flush();
    expect(calls).toBe(3);
    expect(queue.size()).toBe(0);

    queue.track(event(2));
    await queue.flush();

    // The first batch is gone for good; the second went through.
    expect(delivered).toEqual([[event(2)]]);
  });

  it('never lets a failure escape to the caller', async () => {
    const { queue } = harness(async () => {
      throw new Error('boom');
    });
    expect(() => queue.track(event(1))).not.toThrow();
    await expect(queue.flush()).resolves.toBeUndefined();
  });

  it('forgets what is waiting when cleared (sign-out)', async () => {
    const { queue, sent, timers } = harness();
    queue.track(event(1));

    queue.clear();
    await queue.flush();

    expect(sent).toHaveLength(0);
    expect(timers[0]!.cancelled).toBe(true);
  });
});
