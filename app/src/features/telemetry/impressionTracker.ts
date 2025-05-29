/** An item counts as seen once it has been at least half visible for this long. */
export const IMPRESSION_AFTER_MS = 1_000;

export type ImpressionDeps = {
  /** Runs `fn` after `ms`; returns a function that cancels it. */
  schedule: (fn: () => void, ms: number) => () => void;
  onImpression: (id: string) => void;
};

export type ImpressionTracker = {
  /** The ids that are at least half visible right now. */
  update: (visibleIds: string[]) => void;
  /** Forgets what was seen and cancels what is pending (the list was replaced). */
  reset: () => void;
};

/**
 * The viewability → impression rule: an impression is logged for an item once it has
 * stayed at least 50 % visible for one second, and only once however often it comes back into view.
 * The list reports which items are half visible; this decides which of them were really looked at.
 */
export function createImpressionTracker(deps: ImpressionDeps): ImpressionTracker {
  const pending = new Map<string, () => void>();
  const logged = new Set<string>();

  return {
    update(visibleIds) {
      const visible = new Set(visibleIds);
      // Scrolled away before the second was up: no impression.
      for (const [id, cancel] of pending) {
        if (!visible.has(id)) {
          cancel();
          pending.delete(id);
        }
      }
      for (const id of visible) {
        if (logged.has(id) || pending.has(id)) {
          continue;
        }
        pending.set(
          id,
          deps.schedule(() => {
            pending.delete(id);
            logged.add(id);
            deps.onImpression(id);
          }, IMPRESSION_AFTER_MS),
        );
      }
    },
    reset() {
      for (const cancel of pending.values()) {
        cancel();
      }
      pending.clear();
      logged.clear();
    },
  };
}
