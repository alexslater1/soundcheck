import { useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';

import type { PlaybackProgress } from '@/features/player/types';

/**
 * A photo has no watch time, so its time on screen stands in for it. Looking at a photo
 * for {@link PHOTO_LOOK_MS} counts as watching a clip to the end; a quarter of that is a quarter
 * watched. The ranking signals (VIEW_START, WATCH_PROGRESS, COMPLETE) are then the
 * same events a clip reports, made by the same `advanceWatch`, so nothing on the server changes.
 */
export const PHOTO_LOOK_MS = 5_000;

/** As often as a playing clip reports its position. */
export const LOOK_TICK_MS = 250;

/** Time looked at a photo as the position of a clip {@link PHOTO_LOOK_MS} long. */
export function lookProgress(elapsedMs: number): PlaybackProgress {
  return { positionMs: Math.min(Math.max(0, elapsedMs), PHOTO_LOOK_MS), durationMs: PHOTO_LOOK_MS };
}

/**
 * Counts how long a photo is looked at and reports it as progress, the way a clip's player does.
 * Time counts only while the photo is the active item, its screen is in view and the app is in the
 * foreground; leaving and coming back carries on, as a paused clip would. A new viewing (the photo
 * becoming active again) starts from zero. Reporting stops once the look counts as complete.
 */
export function useLookTime(
  active: boolean,
  onScreen: boolean,
  onProgress?: (progress: PlaybackProgress) => void,
): void {
  const elapsed = useRef(0);
  const report = useRef(onProgress);
  useEffect(() => {
    report.current = onProgress;
  });
  const [foreground, setForeground] = useState(AppState.currentState !== 'background');
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) =>
      setForeground(state !== 'background'),
    );
    return () => subscription.remove();
  }, []);
  useEffect(() => {
    if (!active) {
      elapsed.current = 0;
    }
  }, [active]);

  const counting = active && onScreen && foreground;
  useEffect(() => {
    if (!counting || elapsed.current >= PHOTO_LOOK_MS) {
      return;
    }
    let last = Date.now();
    const timer = setInterval(() => {
      const now = Date.now();
      elapsed.current += now - last;
      last = now;
      report.current?.(lookProgress(elapsed.current));
      if (elapsed.current >= PHOTO_LOOK_MS) {
        clearInterval(timer);
      }
    }, LOOK_TICK_MS);
    return () => clearInterval(timer);
  }, [counting]);
}
