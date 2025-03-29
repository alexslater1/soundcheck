import type { Playback } from '@/api/generated/model';

/** How long the app keeps asking whether a clip is ready. */
export const POLL_GIVE_UP_MS = 5 * 60 * 1000;
const MAX_DELAY_MS = 10_000;

/** A status the worker will not change any more. */
export function isSettled(status: Playback['status']): boolean {
  return status === 'READY' || status === 'REJECTED' || status === 'FAILED' || status === 'DELETED';
}

/**
 * When to ask again, given how many answers have arrived: 1 s, 2 s, 4 s, 8 s, then every 10 s; `false`
 * (stop) once the clip is settled or the schedule has waited five minutes. Most clips are ready within seconds, so the early polls are quick, and a
 * slow one does not cost a request per second for minutes.
 */
export function nextPollDelay(
  status: Playback['status'] | undefined,
  pollsSoFar: number,
): number | false {
  if (status !== undefined && isSettled(status)) {
    return false;
  }
  if (waitedAfter(pollsSoFar) >= POLL_GIVE_UP_MS) {
    return false;
  }
  return delayAfter(pollsSoFar);
}

/**
 * For a list that shows clips still being processed: how soon to look again, given how long something
 * has been waiting. Every 2 s for the first 20 s (most clips are ready by then), then every 10 s, and
 * not at all after five minutes (the next visit to the screen refetches).
 */
export function pollDelayForAge(waitedMs: number): number | false {
  if (waitedMs >= POLL_GIVE_UP_MS) {
    return false;
  }
  return waitedMs < 20_000 ? 2_000 : MAX_DELAY_MS;
}

/**
 * The interval for a screen that watches one clip (TanStack Query's `refetchInterval`). A failed
 * request stops the polling: the screen shows the error with a "Try again" button, and a clip the
 * viewer may not see (404) must not be asked for once a second for as long as the page is open.
 */
export function clipPollInterval(state: {
  status: 'pending' | 'error' | 'success';
  data: Pick<Playback, 'status'> | undefined;
  dataUpdateCount: number;
}): number | false {
  if (state.status === 'error') {
    return false;
  }
  return nextPollDelay(state.data?.status, state.dataUpdateCount);
}

function delayAfter(polls: number): number {
  return Math.min(1000 * 2 ** Math.max(0, polls - 1), MAX_DELAY_MS);
}

/**
 * How long the schedule has waited by the time `polls` answers are in: the sum of the delays so far.
 * Counting from the number of polls keeps this a pure function (no clock to read while rendering).
 */
function waitedAfter(polls: number): number {
  let waited = 0;
  for (let i = 1; i < polls; i++) {
    waited += delayAfter(i);
  }
  return waited;
}
