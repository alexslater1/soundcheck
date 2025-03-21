import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { AppState } from 'react-native';

/** A date window older than this is recomputed when the screen is looked at again. */
export const WINDOW_MAX_AGE_MS = 5 * 60 * 1000;

/** The anchor to keep: the old one while it is fresh, otherwise now. */
export function refreshedAnchor(previous: number, now: number, maxAgeMs = WINDOW_MAX_AGE_MS) {
  return now - previous > maxAgeMs ? now : previous;
}

/**
 * The "now" that the Gigs tab's date window is computed from. It must be stable between renders (a new
 * `from` each render would be a new query each render), but it must not stay frozen either: a tab left
 * open overnight would keep listing yesterday's gigs. So it is refreshed when the tab regains focus or
 * the app returns to the foreground, if it is more than five minutes old; `reset()` restarts it when a
 * chip is chosen.
 */
export function useWindowAnchor(): { anchor: number; reset: () => void } {
  const [anchor, setAnchor] = useState(() => Date.now());
  const refresh = useCallback(
    () => setAnchor((previous) => refreshedAnchor(previous, Date.now())),
    [],
  );

  useFocusEffect(refresh);
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') refresh();
    });
    return () => subscription.remove();
  }, [refresh]);

  return { anchor, reset: useCallback(() => setAnchor(Date.now()), []) };
}
