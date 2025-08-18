import { useEffect, useRef, useState } from 'react';
import { Animated, type GestureResponderEvent, type LayoutChangeEvent } from 'react-native';

/** How long a finger rests on an item before it lifts. */
export const LIFT_DELAY_MS = 300;

/** The list with the item at `from` moved to `to` (both clamped to the list). */
export function reorder<T>(list: readonly T[], from: number, to: number): T[] {
  const next = [...list];
  if (from < 0 || from >= next.length) {
    return next;
  }
  const [moved] = next.splice(from, 1);
  next.splice(Math.max(0, Math.min(next.length, to)), 0, moved!);
  return next;
}

/** Where an item lifted at `from` lands after moving `delta` points, items being `stride` apart. */
export function dropIndex(from: number, delta: number, stride: number, count: number): number {
  if (stride <= 0) {
    return from;
  }
  return Math.max(0, Math.min(count - 1, from + Math.round(delta / stride)));
}

/** How far an item that is not lifted moves aside while another is held over its place. */
export function shiftFor(
  index: number,
  drag: { from: number; to: number } | null,
  stride: number,
): number {
  if (!drag || index === drag.from) {
    return 0;
  }
  if (drag.from < drag.to && index > drag.from && index <= drag.to) {
    return -stride;
  }
  if (drag.to < drag.from && index >= drag.to && index < drag.from) {
    return stride;
  }
  return 0;
}

/**
 * Hold and drag to reorder a short list (≤ 10 items) with React Native's own responder system (the
 * props `PanResponder` wraps, as plain handlers) and `Animated`: no gesture library is installed. A long press on an item lifts it; once lifted, the
 * container takes the finger's moves, the item follows it and the others step aside; letting go commits
 * the new order through `onMove(from, to)`. Screen readers keep their Move up / Move down actions.
 *
 * Spread `containerProps` on the view around the items, `itemProps(i)` on each item's `Animated.View`
 * and `liftProps(i)` on the pressable that takes the long press. While `active`, a scroll view around
 * the list should not scroll.
 */
export function useDragReorder({
  count,
  horizontal = false,
  onMove,
}: {
  count: number;
  horizontal?: boolean;
  onMove: (from: number, to: number) => void;
}) {
  const [drag, setDrag] = useState<{ from: number; to: number } | null>(null);
  const [delta] = useState(() => new Animated.Value(0));
  const [stride, setStride] = useState(0);
  // What the handlers read between renders: the drag as it is now, where the finger started, whether
  // the list has the finger, and where each item starts along the list.
  const live = useRef({
    drag: null as { from: number; to: number } | null,
    start: 0,
    granted: false,
    stride: 0,
    starts: [] as number[],
    count,
    onMove,
  });
  useEffect(() => {
    live.current.count = count;
    live.current.onMove = onMove;
  });

  const set = (next: { from: number; to: number } | null) => {
    live.current.drag = next;
    setDrag(next);
  };
  const along = (event: GestureResponderEvent) =>
    horizontal ? event.nativeEvent.pageX : event.nativeEvent.pageY;
  const end = (commit: boolean) => {
    const current = live.current.drag;
    live.current.granted = false;
    delta.setValue(0);
    set(null);
    if (commit && current && current.to !== current.from) {
      live.current.onMove(current.from, current.to);
    }
  };

  const containerProps = {
    // Once an item is lifted, the list takes the finger's moves from the pressable under it.
    onMoveShouldSetResponderCapture: () => live.current.drag !== null,
    onResponderGrant: (event: GestureResponderEvent) => {
      live.current.granted = true;
      live.current.start = along(event);
    },
    onResponderTerminationRequest: () => false,
    onResponderMove: (event: GestureResponderEvent) => {
      const d = along(event) - live.current.start;
      delta.setValue(d);
      const current = live.current.drag;
      if (!current) {
        return;
      }
      const to = dropIndex(current.from, d, live.current.stride, live.current.count);
      if (to !== current.to) {
        set({ from: current.from, to });
      }
    },
    onResponderRelease: () => end(true),
    onResponderTerminate: () => end(false),
  };

  const measure = (index: number, event: LayoutChangeEvent) => {
    const { x, y } = event.nativeEvent.layout;
    const starts = live.current.starts;
    starts[index] = horizontal ? x : y;
    const first = starts[0];
    const last = starts[count - 1];
    if (count > 1 && first !== undefined && last !== undefined) {
      const next = Math.abs(last - first) / (count - 1);
      if (Math.abs(next - live.current.stride) > 0.5) {
        live.current.stride = next;
        setStride(next);
      }
    }
  };

  return {
    active: drag !== null,
    containerProps,
    itemProps: (index: number) => {
      const lifted = drag?.from === index;
      const offset = lifted ? delta : shiftFor(index, drag, stride);
      return {
        onLayout: (event: LayoutChangeEvent) => measure(index, event),
        style: [
          { transform: horizontal ? [{ translateX: offset }] : [{ translateY: offset }] },
          lifted ? styles.lifted : null,
        ],
      };
    },
    liftProps: (index: number) => ({
      delayLongPress: LIFT_DELAY_MS,
      onLongPress: () => {
        if (count > 1) {
          live.current.granted = false;
          set({ from: index, to: index });
        }
      },
      // A lift the finger let go of without moving: nothing moved, so nothing changes. When the list
      // takes the finger over, the pressable is let go too; that is not an end.
      onPressOut: () => {
        setTimeout(() => {
          if (!live.current.granted && live.current.drag?.from === index) {
            set(null);
          }
        }, 0);
      },
    }),
  };
}

const styles = { lifted: { zIndex: 1, elevation: 6, opacity: 0.9 } } as const;
