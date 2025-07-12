import { createContext, useContext } from 'react';
import { createStore, useStore, type StoreApi } from 'zustand';

import { playerWindow } from './playerWindow';

/** At most this many live players exist at once in one list (previous, current, next). */
export const MAX_LIVE_PLAYERS = 3;

export type PlayerManagerState = {
  /** The clips of the list, in display order. */
  order: string[];
  /** The clip in view: the one most visible. Kept while the list is hidden, so it comes back there. */
  currentId: string | null;
  /** False while the list is hidden (the other feed is showing): it holds no players at all. */
  enabled: boolean;
  /** Derived from the three above; kept in the store so components subscribe to it cheaply. */
  liveIds: string[];
  setOrder: (order: string[]) => void;
  setCurrent: (id: string | null) => void;
  setEnabled: (enabled: boolean) => void;
  reset: () => void;
};

const windowOf = (order: string[], currentId: string | null, enabled: boolean) =>
  enabled ? playerWindow(order, currentId, MAX_LIVE_PLAYERS) : [];

/**
 * Decides which clips of one list have a live player and which one plays. A list reports its order
 * and which item is in view; each clip asks {@link usePlayerSlot} what it may do. One clip plays at a
 * time, so two sounds never overlap.
 *
 * Each list has its own manager: For You and Following stay mounted side by side so each
 * keeps its place, and the hidden one is switched off rather than fighting the shown one over a
 * single global "current clip".
 */
export function createPlayerManager(): StoreApi<PlayerManagerState> {
  return createStore<PlayerManagerState>((set) => ({
    order: [],
    currentId: null,
    enabled: true,
    liveIds: [],
    setOrder: (order) =>
      set((state) => {
        // A clip that left the list (deleted, refreshed away) cannot stay current.
        const currentId =
          state.currentId !== null && order.includes(state.currentId) ? state.currentId : null;
        return { order, currentId, liveIds: windowOf(order, currentId, state.enabled) };
      }),
    setCurrent: (currentId) =>
      set((state) => ({ currentId, liveIds: windowOf(state.order, currentId, state.enabled) })),
    setEnabled: (enabled) =>
      set((state) => ({ enabled, liveIds: windowOf(state.order, state.currentId, enabled) })),
    reset: () => set({ order: [], currentId: null, liveIds: [] }),
  }));
}

const ManagerContext = createContext<StoreApi<PlayerManagerState> | null>(null);

/** Gives the clips inside a list that list's manager. */
export const PlayerManagerProvider = ManagerContext.Provider;

/** Clips outside any list (rendered on their own, as in tests) share this one. */
const standalone = createPlayerManager();

/** The manager of the list around the caller. */
export function usePlayerManagerStore(): StoreApi<PlayerManagerState> {
  return useContext(ManagerContext) ?? standalone;
}

/** What one clip may do: hold a live player (`live`), and play (`active`). */
export function usePlayerSlot(id: string): { live: boolean; active: boolean } {
  const store = usePlayerManagerStore();
  const live = useStore(store, (state) => state.liveIds.includes(id));
  const active = useStore(store, (state) => state.enabled && state.currentId === id);
  return { live, active };
}
