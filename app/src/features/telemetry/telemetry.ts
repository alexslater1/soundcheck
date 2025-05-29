import { recordEvents } from '@/api/generated/signals/signals';

import { createTelemetryQueue } from './telemetryQueue';

/**
 * The app's one telemetry queue, wired to `POST /events` and real timers. Components do not use it
 * directly: they call `useTrack()`, which adds where the event happened (surface, feed session,
 * position).
 */
export const telemetry = createTelemetryQueue({
  send: (events) => recordEvents({ events }),
  schedule: (fn, ms) => {
    const timer = setTimeout(fn, ms);
    return () => clearTimeout(timer);
  },
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
});
