import { useIsFocused } from '@react-navigation/native';
import { createContext, useContext, useMemo, type PropsWithChildren } from 'react';

import { usePlayerPrefs } from './playerPrefs';

/** What the players of one screen share: whether the screen is on show, and the app-wide sound setting. */
export type Playback = {
  /** False while another screen covers this one or another tab is open: nothing here may play. */
  onScreen: boolean;
  muted: boolean;
  setMuted: (muted: boolean) => void;
  toggleMuted: () => void;
};

const PlaybackContext = createContext<Playback | null>(null);

/**
 * Wraps a screen that shows clips (the route files of Home, a post, a clip). What is per screen is
 * whether it is in view: tabs stay mounted and a pushed screen covers without unmounting, so
 * a clip must stop when its screen is not the one in view. Sound is not per screen: it is one setting
 * for the whole app, remembered across launches.
 */
export function PlaybackScope({ children }: PropsWithChildren) {
  const onScreen = useIsFocused();
  const muted = usePlayerPrefs((state) => state.muted);
  const setMuted = usePlayerPrefs((state) => state.setMuted);
  const toggleMuted = usePlayerPrefs((state) => state.toggleMuted);
  const value = useMemo<Playback>(
    () => ({ onScreen, muted, setMuted, toggleMuted }),
    [onScreen, muted, setMuted, toggleMuted],
  );
  return <PlaybackContext.Provider value={value}>{children}</PlaybackContext.Provider>;
}

/**
 * The playback state of the screen around a player. Outside any scope (a component rendered on its own,
 * as in tests) the clip counts as on screen; the sound setting is the same one either way.
 */
export function usePlayback(): Playback {
  const scoped = useContext(PlaybackContext);
  const muted = usePlayerPrefs((state) => state.muted);
  const setMuted = usePlayerPrefs((state) => state.setMuted);
  const toggleMuted = usePlayerPrefs((state) => state.toggleMuted);
  return scoped ?? { onScreen: true, muted, setMuted, toggleMuted };
}
