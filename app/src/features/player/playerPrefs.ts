import { MMKV } from 'react-native-mmkv';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

type PlayerPrefs = {
  /** One app-wide sound setting: sound is on until the viewer turns it off. */
  muted: boolean;
  setMuted: (muted: boolean) => void;
  toggleMuted: () => void;
};

// The same small key-value file as the theme (MMKV on Android, localStorage on the web).
const mmkv = new MMKV({ id: 'soundcheck.prefs' });

/**
 * Whether clips are muted, shared by every screen and kept across launches. Before this each
 * screen started muted and forgot its choice, so the sound seemed to be missing. It starts
 * unmuted on first launch; the speaker button is the only toggle. A browser that refuses
 * sound before the first tap mutes the clip through `setMuted` (see `VideoPlayer.web`).
 */
export const usePlayerPrefs = create<PlayerPrefs>()(
  persist(
    (set) => ({
      muted: false,
      setMuted: (muted) => set({ muted }),
      toggleMuted: () => set((state) => ({ muted: !state.muted })),
    }),
    {
      name: 'player',
      version: 1,
      storage: createJSONStorage(() => ({
        getItem: (key) => mmkv.getString(key) ?? null,
        setItem: (key, value) => mmkv.set(key, value),
        removeItem: (key) => {
          mmkv.delete(key);
        },
      })),
      partialize: (state) => ({ muted: state.muted }),
      merge: (persisted, current) => ({
        ...current,
        muted: (persisted as Partial<PlayerPrefs> | undefined)?.muted === true,
      }),
    },
  ),
);
