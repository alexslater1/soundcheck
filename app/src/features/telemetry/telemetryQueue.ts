import type { EventIn } from '@/api/generated/model';

/** Flush when this many events are waiting. */
export const FLUSH_AT = 20;
/** Flush at the latest this long after the first waiting event. */
export const FLUSH_AFTER_MS = 10_000;
/** The server accepts at most this many events per request. */
export const MAX_BATCH = 100;
/** A batch that fails this many times is dropped: analytics must never block or pile up. */
export const MAX_ATTEMPTS = 3;

export type TelemetryDeps = {
  /** `POST /events`; rejects when the request fails. */
  send: (events: EventIn[]) => Promise<unknown>;
  /** Runs `fn` after `ms`; returns a function that cancels it. */
  schedule: (fn: () => void, ms: number) => () => void;
  /** Waits between retries. */
  sleep: (ms: number) => Promise<void>;
};

export type TelemetryQueue = {
  /** Adds an event; never throws, never waits. */
  track: (event: EventIn) => void;
  /** Sends what is waiting now (app going to the background, tab hidden). Resolves when it is done. */
  flush: () => Promise<void>;
  /** Forgets everything that is waiting (sign-out). */
  clear: () => void;
  size: () => number;
};

/**
 * The telemetry queue: events wait in memory and go out in batches, when 20 are waiting,
 * 10 seconds after the first one, or when the app is about to go away. One request is in flight at a
 * time; a failed batch is retried twice with a growing pause and then dropped. Everything it needs
 * from the outside is passed in, so the rules are tested with fakes.
 */
export function createTelemetryQueue(deps: TelemetryDeps): TelemetryQueue {
  let waiting: EventIn[] = [];
  let cancelTimer: (() => void) | null = null;
  let sending: Promise<void> | null = null;

  const stopTimer = () => {
    cancelTimer?.();
    cancelTimer = null;
  };

  const sendBatch = async (batch: EventIn[]): Promise<void> => {
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        await deps.send(batch);
        return;
      } catch {
        if (attempt < MAX_ATTEMPTS) {
          await deps.sleep(1_000 * 2 ** (attempt - 1));
        }
      }
    }
    // Dropped: three failures in a row.
  };

  const drain = async (): Promise<void> => {
    while (waiting.length > 0) {
      const batch = waiting.slice(0, MAX_BATCH);
      waiting = waiting.slice(batch.length);
      await sendBatch(batch);
    }
  };

  const flush = (): Promise<void> => {
    stopTimer();
    // Events tracked while a request is in flight are picked up by the same drain loop.
    sending ??= drain().finally(() => {
      sending = null;
    });
    return sending;
  };

  return {
    track(event) {
      waiting.push(event);
      if (waiting.length >= FLUSH_AT) {
        void flush();
      } else if (!cancelTimer && !sending) {
        cancelTimer = deps.schedule(() => {
          cancelTimer = null;
          void flush();
        }, FLUSH_AFTER_MS);
      }
    },
    flush,
    clear() {
      stopTimer();
      waiting = [];
    },
    size: () => waiting.length,
  };
}
