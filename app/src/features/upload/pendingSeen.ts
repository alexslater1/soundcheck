import { MMKV } from 'react-native-mmkv';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

type PendingSeen = {
  /** Per user: the pending gigs they had when they last opened the Create tab. */
  seen: Record<string, string[]>;
  markSeen: (userId: string, gigIds: string[]) => void;
};

// The same small key-value file as the theme and the sound setting (MMKV on Android, localStorage on
// the web).
const mmkv = new MMKV({ id: 'soundcheck.prefs' });

/**
 * Which pending gigs the user has already been shown (the Create tab's badge goes once the tab is
 * opened). Opening Create replaces the user's list with the gigs pending then, so it never grows
 * past the Pending list itself; a gig that becomes pending later is not in it and counts on the
 * badge again. Kept on the device: a second phone shows the badge once more.
 */
export const usePendingSeen = create<PendingSeen>()(
  persist(
    (set) => ({
      seen: {},
      markSeen: (userId, gigIds) => set((state) => ({ seen: { ...state.seen, [userId]: gigIds } })),
    }),
    {
      name: 'pending-seen',
      version: 1,
      storage: createJSONStorage(() => ({
        getItem: (key) => mmkv.getString(key) ?? null,
        setItem: (key, value) => mmkv.set(key, value),
        removeItem: (key) => {
          mmkv.delete(key);
        },
      })),
      partialize: (state) => ({ seen: state.seen }),
    },
  ),
);

/** How many of `gigIds` the user has not been shown on the Create tab yet. */
export function unseenCount(gigIds: string[], seen: string[] | undefined): number {
  const shown = new Set(seen ?? []);
  return gigIds.filter((id) => !shown.has(id)).length;
}
