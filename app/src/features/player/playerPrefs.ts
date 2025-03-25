import { create } from 'zustand';

type PlayerPrefs = {
  /** Clips start muted (browsers and good manners both require it); one tap turns sound on for the session. */
  muted: boolean;
  setMuted: (muted: boolean) => void;
  toggleMuted: () => void;
};

/** Sound preference shared by every player, kept for the session only. */
export const usePlayerPrefs = create<PlayerPrefs>((set) => ({
  muted: true,
  setMuted: (muted) => set({ muted }),
  toggleMuted: () => set((state) => ({ muted: !state.muted })),
}));
