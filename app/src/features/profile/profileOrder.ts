import { MMKV } from 'react-native-mmkv';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

import type { ListUserPostsSort } from '@/api/generated/model';

type ProfileOrderPrefs = {
  order: ListUserPostsSort;
  setOrder: (order: ListUserPostsSort) => void;
};

// The same small key-value file as the theme and the sound setting.
const mmkv = new MMKV({ id: 'soundcheck.prefs' });

/**
 * How profiles list their posts, one choice for every profile and kept across launches: by gig date,
 * newest gig first (the default), or by when they were posted. The grid and the pager a tile opens
 * both read it, so swiping goes through the posts in the order the grid shows them.
 */
export const useProfileOrder = create<ProfileOrderPrefs>()(
  persist(
    (set) => ({
      order: 'gig',
      setOrder: (order) => set({ order }),
    }),
    {
      name: 'profileOrder',
      version: 1,
      storage: createJSONStorage(() => ({
        getItem: (key) => mmkv.getString(key) ?? null,
        setItem: (key, value) => mmkv.set(key, value),
        removeItem: (key) => {
          mmkv.delete(key);
        },
      })),
      partialize: (state) => ({ order: state.order }),
      merge: (persisted, current) => ({
        ...current,
        order:
          (persisted as Partial<ProfileOrderPrefs> | undefined)?.order === 'posted'
            ? 'posted'
            : 'gig',
      }),
    },
  ),
);
