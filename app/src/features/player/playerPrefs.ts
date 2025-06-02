import { create } from 'zustand';

type PlayerPrefs = {
  /** Clips start muted (browsers and good manners both require it); one tap turns sound on for the session. */
  muted: boolean;
  setMuted: (muted: boolean) => void;
  toggleMuted: () => void;
};

/**
 * The sound setting of players outside any {@link PlaybackScope} (components rendered on their own, as
 * in tests). Screens that show clips wrap them in a scope with its own setting.
 */
export const usePlayerPrefs = create<PlayerPrefs>((set) => ({
  muted: true,
  setMuted: (muted) => set({ muted }),
  toggleMuted: () => set((state) => ({ muted: !state.muted })),
}));
