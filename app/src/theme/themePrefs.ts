import { MMKV } from 'react-native-mmkv';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

import { DEFAULT_THEME, themes, type ThemeId } from './tokens';

/** Auto follows the phone's light/dark setting; the other two fix it. */
export type ThemeMode = 'auto' | 'dark' | 'light';

type ThemePrefs = {
  themeId: ThemeId;
  mode: ThemeMode;
  setTheme: (themeId: ThemeId) => void;
  setMode: (mode: ThemeMode) => void;
};

// The same small key-value file the upload queue uses (MMKV on Android, localStorage on the web).
const mmkv = new MMKV({ id: 'soundcheck.prefs' });

/** The chosen theme and mode, kept on the device (they are not account settings). */
export const useThemePrefs = create<ThemePrefs>()(
  persist(
    (set) => ({
      themeId: DEFAULT_THEME,
      mode: 'auto',
      setTheme: (themeId) => set({ themeId }),
      setMode: (mode) => set({ mode }),
    }),
    {
      name: 'theme',
      version: 1,
      storage: createJSONStorage(() => ({
        getItem: (key) => mmkv.getString(key) ?? null,
        setItem: (key, value) => mmkv.set(key, value),
        removeItem: (key) => {
          mmkv.delete(key);
        },
      })),
      partialize: (state) => ({ themeId: state.themeId, mode: state.mode }),
      // A theme removed in a later version falls back to the default instead of crashing.
      merge: (persisted, current) => {
        const saved = (persisted ?? {}) as Partial<ThemePrefs>;
        return {
          ...current,
          themeId: saved.themeId && saved.themeId in themes ? saved.themeId : current.themeId,
          mode: saved.mode === 'dark' || saved.mode === 'light' ? saved.mode : 'auto',
        };
      },
    },
  ),
);
