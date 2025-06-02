import { useIsFocused } from '@react-navigation/native';
import { createContext, useContext, useMemo, useState, type PropsWithChildren } from 'react';

import { usePlayerPrefs } from './playerPrefs';

/** What the players of one screen share: whether the screen is on show, and its sound setting. */
export type Playback = {
  /** False while another screen covers this one or another tab is open: nothing here may play. */
  onScreen: boolean;
  muted: boolean;
  setMuted: (muted: boolean) => void;
  toggleMuted: () => void;
};

const PlaybackContext = createContext<Playback | null>(null);

/**
 * Wraps a screen that shows clips (the route files of Home, a post, a clip). Two things are per screen
 *: tabs stay mounted and a pushed screen covers without unmounting, so a clip must stop
 * when its screen is not the one in view; and turning sound on for one screen's clip must not turn it on
 * for another screen's.
 */
export function PlaybackScope({ children }: PropsWithChildren) {
  const onScreen = useIsFocused();
  const [muted, setMuted] = useState(true);
  const value = useMemo<Playback>(
    () => ({ onScreen, muted, setMuted, toggleMuted: () => setMuted((m) => !m) }),
    [onScreen, muted],
  );
  return <PlaybackContext.Provider value={value}>{children}</PlaybackContext.Provider>;
}

/**
 * The playback state of the screen around a player. Outside any scope (a component rendered on its own,
 * as in tests) the clip counts as on screen and the session-wide sound setting applies.
 */
export function usePlayback(): Playback {
  const scoped = useContext(PlaybackContext);
  const muted = usePlayerPrefs((state) => state.muted);
  const setMuted = usePlayerPrefs((state) => state.setMuted);
  const toggleMuted = usePlayerPrefs((state) => state.toggleMuted);
  return scoped ?? { onScreen: true, muted, setMuted, toggleMuted };
}
