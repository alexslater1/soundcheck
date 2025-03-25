import { create } from 'zustand';

import { playerWindow } from './playerWindow';

/** At most this many live players exist at once (previous, current, next). */
export const MAX_LIVE_PLAYERS = 3;

type PlayerManagerState = {
  /** The clips of the list on screen, in display order. */
  order: string[];
  /** The clip that is playing: the one most visible. */
  currentId: string | null;
  /** Derived from the two above; kept in the store so components subscribe to it cheaply. */
  liveIds: string[];
  setOrder: (order: string[]) => void;
  setCurrent: (id: string | null) => void;
  reset: () => void;
};

/**
 * The single place that decides which clips have a live player and which one plays. A list reports its
 * order and which item is in view; each clip asks {@link usePlayerSlot} what it may do. One clip plays
 * at a time, so two sounds never overlap.
 */
export const usePlayerManager = create<PlayerManagerState>((set) => ({
  order: [],
  currentId: null,
  liveIds: [],
  setOrder: (order) =>
    set((state) => {
      // A clip that left the list (deleted, refreshed away) cannot stay current.
      const currentId =
        state.currentId !== null && order.includes(state.currentId) ? state.currentId : null;
      return { order, currentId, liveIds: playerWindow(order, currentId, MAX_LIVE_PLAYERS) };
    }),
  setCurrent: (currentId) =>
    set((state) => ({
      currentId,
      liveIds: playerWindow(state.order, currentId, MAX_LIVE_PLAYERS),
    })),
  reset: () => set({ order: [], currentId: null, liveIds: [] }),
}));

/** What one clip may do: hold a live player (`live`), and play (`active`). */
export function usePlayerSlot(id: string): { live: boolean; active: boolean } {
  const live = usePlayerManager((state) => state.liveIds.includes(id));
  const active = usePlayerManager((state) => state.currentId === id);
  return { live, active };
}
