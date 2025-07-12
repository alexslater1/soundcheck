/** Where a tap landed, in the tapped view's own coordinates. */
export type TapPoint = { x: number; y: number };

/** How long a second tap may follow the first and still count as a double tap. */
export const DOUBLE_TAP_MS = 250;

type Options = {
  /** Starts a timer and returns what cancels it (setTimeout in the app, a fake in tests). */
  schedule: (fn: () => void, ms: number) => () => void;
  onSingle: (point: TapPoint) => void;
  onDouble: (point: TapPoint) => void;
  windowMs?: number;
};

/**
 * Tells a single tap from a double tap. A single tap acts only once the double-tap window
 * has passed without a second tap, so a double tap (like) never also pauses the clip. A third tap
 * starts a new pair.
 */
export function createTapClassifier({
  schedule,
  onSingle,
  onDouble,
  windowMs = DOUBLE_TAP_MS,
}: Options) {
  let cancelPending: (() => void) | null = null;
  return {
    tap(point: TapPoint) {
      if (cancelPending) {
        cancelPending();
        cancelPending = null;
        onDouble(point);
        return;
      }
      cancelPending = schedule(() => {
        cancelPending = null;
        onSingle(point);
      }, windowMs);
    },
    /** Drops a tap still waiting for its window (the view is going away). */
    cancel() {
      cancelPending?.();
      cancelPending = null;
    },
  };
}

export type TapClassifier = ReturnType<typeof createTapClassifier>;
