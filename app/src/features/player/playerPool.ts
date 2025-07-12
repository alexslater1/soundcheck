/** Stands on top of a clip's holders between a tap that opens its post and the post page taking it. */
const RESERVED = '#handoff';

/** How long a handoff waits for the post page before the feed takes its player back. */
export const HANDOFF_WAIT_MS = 3000;

type Entry<P> = {
  player: P;
  /** Bumped when the player is replaced (a retry), so views attach the new one. */
  generation: number;
  /** Who uses the player, in the order they took it; the last one is on top. */
  holders: string[];
  create: () => P;
  cancelReservation: (() => void) | null;
};

type Options<P> = {
  /** Frees a player nobody holds any more (expo-video: `release()`). */
  destroy: (player: P) => void;
  /** Starts a timer and returns what cancels it. */
  schedule: (fn: () => void, ms: number) => () => void;
};

/**
 * Shares one player per clip between the screens that show it. Opening a post from the
 * feed used to build a second player for the same clip on the post page, so the clip restarted from
 * the beginning and the sound stopped for a moment. Now each screen *holds* the clip's player: the
 * first holder creates it, the last one to let go frees it, and only the holder on top may show it and
 * start or pause it. The feed's holder stays underneath while the post page is open and gets the
 * player back, still playing where it was, when the post page closes.
 *
 * A tap that opens the post {@link reserve}s the player first: the feed stops controlling it at once
 * (so the feed losing focus does not pause it), but keeps showing it until the post page takes over.
 */
export function createPlayerPool<P>({ destroy, schedule }: Options<P>) {
  const entries = new Map<string, Entry<P>>();
  const listeners = new Set<() => void>();
  let holderCount = 0;

  const notify = () => listeners.forEach((listener) => listener());

  const free = (key: string, entry: Entry<P>) => {
    if (entry.holders.length === 0) {
      entries.delete(key);
      destroy(entry.player);
    }
  };

  const dropReservation = (entry: Entry<P>) => {
    entry.cancelReservation?.();
    entry.cancelReservation = null;
    entry.holders = entry.holders.filter((holder) => holder !== RESERVED);
  };

  return {
    /** A name for one component that will hold players. */
    newHolder: () => `holder-${++holderCount}`,

    /**
     * `holder` takes the clip's player, creating it if nobody holds it, and goes on top; a handoff
     * waiting on top is taken over. Holding it already changes nothing.
     */
    acquire(key: string, holder: string, create: () => P): P {
      let entry = entries.get(key);
      if (!entry) {
        entry = { player: create(), generation: 0, holders: [], create, cancelReservation: null };
        entries.set(key, entry);
      }
      if (!entry.holders.includes(holder)) {
        if (entry.holders.at(-1) === RESERVED) {
          dropReservation(entry);
        }
        entry.holders.push(holder);
        notify();
      }
      return entry.player;
    },

    /** `holder` lets go; the one below it is on top again. The last one out frees the player. */
    release(key: string, holder: string) {
      const entry = entries.get(key);
      if (!entry || !entry.holders.includes(holder)) {
        return;
      }
      entry.holders = entry.holders.filter((h) => h !== holder);
      free(key, entry);
      notify();
    },

    /**
     * Keeps the clip's player for a screen about to open (the post page): whoever holds it now stops
     * controlling it. If nobody takes it within {@link HANDOFF_WAIT_MS}, the reservation lapses.
     * False when there is no player to hand over.
     */
    reserve(key: string): boolean {
      const entry = entries.get(key);
      if (!entry) {
        return false;
      }
      dropReservation(entry);
      entry.holders.push(RESERVED);
      entry.cancelReservation = schedule(() => {
        entry.cancelReservation = null;
        entry.holders = entry.holders.filter((holder) => holder !== RESERVED);
        free(key, entry);
        notify();
      }, HANDOFF_WAIT_MS);
      notify();
      return true;
    },

    /** Replaces a clip's player with a new one (a retry after an error) for everyone holding it. */
    renew(key: string) {
      const entry = entries.get(key);
      if (!entry) {
        return;
      }
      destroy(entry.player);
      entry.player = entry.create();
      entry.generation += 1;
      notify();
    },

    player: (key: string): P | undefined => entries.get(key)?.player,
    generation: (key: string): number => entries.get(key)?.generation ?? -1,
    /** Only the holder on top starts and pauses the player. */
    controls: (key: string, holder: string): boolean => entries.get(key)?.holders.at(-1) === holder,
    /** The top holder other than a handoff shows the video; everyone else shows the poster. */
    shows: (key: string, holder: string): boolean =>
      (entries.get(key)?.holders ?? []).filter((h) => h !== RESERVED).at(-1) === holder,
    /** How many clips have a player right now. */
    size: (): number => entries.size,

    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export type PlayerPool<P> = ReturnType<typeof createPlayerPool<P>>;
