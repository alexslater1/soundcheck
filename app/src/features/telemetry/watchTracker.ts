import type { PlaybackProgress } from '@/features/player/types';

export type WatchEvent =
  | { type: 'VIEW_START' }
  | { type: 'WATCH_PROGRESS'; value: 0.25 | 0.5 | 0.75 }
  | { type: 'COMPLETE' };

/** What has already been reported for one viewing of one clip. */
export type WatchState = {
  started: boolean;
  /** The highest progress mark reported so far (0 before the first). */
  mark: 0 | 0.25 | 0.5 | 0.75;
  completed: boolean;
  /** The last position seen, to notice the clip starting over. */
  lastPositionMs: number;
};

export const INITIAL_WATCH: WatchState = {
  started: false,
  mark: 0,
  completed: false,
  lastPositionMs: 0,
};

const MARKS = [0.25, 0.5, 0.75] as const;
/** A clip counts as watched to the end at 95 %, or when it loops. */
const COMPLETE_AT = 0.95;

/**
 * Turns the player's progress reports into watch events: VIEW_START when playback first
 * moves, WATCH_PROGRESS once each at 25, 50 and 75 %, COMPLETE at 95 % or when the clip starts over.
 * Each is reported once per viewing, however many times the clip loops. A pure function: the caller
 * keeps the state.
 */
export function advanceWatch(
  state: WatchState,
  progress: PlaybackProgress,
): { state: WatchState; events: WatchEvent[] } {
  const events: WatchEvent[] = [];
  const { positionMs, durationMs } = progress;
  if (!(durationMs > 0) || positionMs < 0) {
    return { state, events };
  }
  let { started, mark, completed } = state;
  const fraction = positionMs / durationMs;
  // Back near the start after having been past the middle: the clip looped.
  const looped = state.lastPositionMs > durationMs / 2 && positionMs < state.lastPositionMs / 2;

  if (!started && positionMs > 0) {
    started = true;
    events.push({ type: 'VIEW_START' });
  }
  if (started && !completed) {
    // A loop means everything up to the end was played, even if no report landed on the last frames.
    const reached = looped ? 1 : fraction;
    for (const next of MARKS) {
      if (reached >= next && mark < next) {
        mark = next;
        events.push({ type: 'WATCH_PROGRESS', value: next });
      }
    }
    if (reached >= COMPLETE_AT) {
      completed = true;
      events.push({ type: 'COMPLETE' });
    }
  }
  return { state: { started, mark, completed, lastPositionMs: positionMs }, events };
}
