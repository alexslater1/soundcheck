import { useRef, useState } from 'react';
import { StyleSheet, View, type GestureResponderEvent } from 'react-native';

import { Text } from '@/components/ui/Text';
import { radius, spacing } from '@/theme/tokens';

import { formatClock, progressShare, seekTarget } from './clock';

type Props = {
  positionMs: number;
  durationMs: number;
  /** Called once, when the finger lets go (or an accessibility action steps the clip). */
  onSeek: (positionMs: number) => void;
  testID?: string;
};

/** The strip at the foot of a clip that takes the finger: taller than the bar it draws. */
export const SEEK_BAR_HEIGHT = 24;
/**
 * How far text laid over a clip's foot stays above the clip's bottom edge: clear of the bar it draws
 * (6 pt while dragging), though inside the taller strip that takes the finger.
 */
export const SEEK_TRACK_ROOM = 12;
/** One accessibility "increment" or "decrement". */
const STEP_MS = 5000;

/**
 * Where the clip is, and a way to move it: a thin bar at rest; while a finger drags it,
 * a thicker bar, a thumb and the time ("0:12 / 0:45"). Touch, mouse and pen all go through React
 * Native's responder system, so the same code works on the phone and on the web. Granting the
 * responder returns true, which stops the feed's list from taking the drag over as a scroll.
 */
export function SeekBar({ positionMs, durationMs, onSeek, testID }: Props) {
  const [width, setWidth] = useState(0);
  // The time under the finger while it drags; null at rest.
  const [drag, setDrag] = useState<number | null>(null);
  // The bar's left edge on the page, measured when a drag starts: `locationX` is relative to
  // whatever is under the finger, which stops being the bar once the finger slides off it.
  const left = useRef(0);
  const usable = durationMs > 0;
  const shown = drag ?? positionMs;
  const share = progressShare(shown, durationMs);
  const dragging = drag !== null;

  const at = (event: GestureResponderEvent) =>
    seekTarget(event.nativeEvent.pageX - left.current, width, durationMs);

  return (
    <View
      style={styles.hit}
      onLayout={(event) => setWidth(event.nativeEvent.layout.width)}
      onStartShouldSetResponder={() => usable}
      onMoveShouldSetResponder={() => usable}
      onResponderTerminationRequest={() => false}
      onResponderGrant={(event) => {
        left.current = event.nativeEvent.pageX - event.nativeEvent.locationX;
        setDrag(at(event));
        // True blocks the native scroll view from intercepting the drag (Android).
        return true;
      }}
      onResponderMove={(event) => setDrag(at(event))}
      onResponderRelease={(event) => {
        const target = at(event);
        setDrag(null);
        onSeek(target);
      }}
      onResponderTerminate={() => setDrag(null)}
      accessible
      accessibilityRole="adjustable"
      accessibilityLabel="Position in the clip"
      accessibilityValue={{
        min: 0,
        max: Math.round(durationMs / 1000),
        now: Math.round(shown / 1000),
        text: `${formatClock(shown)} of ${formatClock(durationMs)}`,
      }}
      accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
      onAccessibilityAction={(event) => {
        const by = event.nativeEvent.actionName === 'increment' ? STEP_MS : -STEP_MS;
        onSeek(Math.min(durationMs, Math.max(0, positionMs + by)));
      }}
      testID={testID}
    >
      {dragging ? (
        <View pointerEvents="none" style={styles.timeRow}>
          <Text style={styles.time} testID={testID ? `${testID}-time` : undefined}>
            {formatClock(shown)} / {formatClock(durationMs)}
          </Text>
        </View>
      ) : null}
      <View pointerEvents="none" style={[styles.track, dragging ? styles.thick : null]}>
        <View style={[styles.fill, { width: `${share * 100}%` }]} />
        {dragging ? <View style={[styles.thumb, { left: share * width - THUMB / 2 }]} /> : null}
      </View>
    </View>
  );
}

const THUMB = 14;

const styles = StyleSheet.create({
  hit: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    height: SEEK_BAR_HEIGHT,
    justifyContent: 'flex-end',
  },
  track: {
    height: 3,
    backgroundColor: 'rgba(255,255,255,0.3)',
    justifyContent: 'center',
  },
  thick: { height: 6 },
  fill: { height: '100%', backgroundColor: 'rgba(255,255,255,0.92)' },
  thumb: {
    position: 'absolute',
    width: THUMB,
    height: THUMB,
    borderRadius: THUMB / 2,
    backgroundColor: '#FFFFFF',
  },
  timeRow: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: SEEK_BAR_HEIGHT + spacing.sm,
    alignItems: 'center',
  },
  time: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 18,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: 'rgba(0,0,0,0.55)',
    overflow: 'hidden',
  },
});
