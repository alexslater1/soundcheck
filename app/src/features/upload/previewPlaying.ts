import { create } from 'zustand';

type PreviewPlaying = {
  /** The preview playing now (its clip's local URI), or null when none is. */
  playing: string | null;
  /** Plays this preview, pausing whichever played before, or pauses it if it was the one playing. */
  toggle: (uri: string) => void;
  /** Forgets this preview if it was the one playing (it left the screen). */
  release: (uri: string) => void;
};

/**
 * Which of the compose form's previews is playing: one at a time, so tapping a second clip
 * pauses the first instead of playing both over each other.
 */
export const usePreviewPlaying = create<PreviewPlaying>((set) => ({
  playing: null,
  toggle: (uri) => set((state) => ({ playing: state.playing === uri ? null : uri })),
  release: (uri) => set((state) => (state.playing === uri ? { playing: null } : state)),
}));
