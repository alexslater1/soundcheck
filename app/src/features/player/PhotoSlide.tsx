import { Image } from 'expo-image';
import { useEffect, useRef } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import type { Playback } from '@/api/generated/model';
import { useLookTime } from '@/features/telemetry/lookTime';
import { useTheme } from '@/theme/ThemeProvider';
import { radius } from '@/theme/tokens';

import { ClipShade } from './ClipShade';
import { usePlayback } from './PlaybackScope';
import { createTapClassifier, type TapClassifier, type TapPoint } from './tapGesture';
import type { PlaybackProgress } from './types';

type Props = {
  playback: Playback;
  /** Width over height of the box (see `PostMedia`'s `frameAspect`). */
  aspectRatio: number;
  /** The item in view: its time on screen counts (a stand-in for watch time). */
  active: boolean;
  rounded?: boolean;
  /** A dark gradient over the foot of the photo, for text laid over it (the feed, `ClipShade`). */
  shade?: boolean;
  /** A double tap likes the post, as on a clip. */
  onDoubleTap?: (point: TapPoint) => void;
  /** Time on screen, reported as a clip's progress (`useLookTime`). */
  onProgress?: (progress: PlaybackProgress) => void;
  testID?: string;
};

/**
 * One photo of a post: the photo itself fills the box, over its BlurHash until it has
 * loaded, the way a clip's poster does. There is no sound button and nothing to pause, so a single tap
 * does nothing and a double tap likes. In a carousel it is shaped like any clip (shown whole over a
 * blurred copy of itself, `ShapedClip`) and swiped past like one; its time on screen stands in for
 * watch time.
 */
export function PhotoSlide({
  playback,
  aspectRatio,
  active,
  rounded = true,
  shade = false,
  onDoubleTap,
  onProgress,
  testID,
}: Props) {
  const { colors } = useTheme();
  const { onScreen } = usePlayback();
  useLookTime(active, onScreen, onProgress);

  const handler = useRef(onDoubleTap);
  useEffect(() => {
    handler.current = onDoubleTap;
  });
  const taps = useRef<TapClassifier | null>(null);
  useEffect(() => () => taps.current?.cancel(), []);
  const tap = (point: TapPoint) => {
    taps.current ??= createTapClassifier({
      schedule: (fn, ms) => {
        const timer = setTimeout(fn, ms);
        return () => clearTimeout(timer);
      },
      onSingle: () => undefined,
      onDouble: (at) => handler.current?.(at),
    });
    taps.current.tap(point);
  };

  return (
    <View
      style={[
        styles.frame,
        { aspectRatio, backgroundColor: colors.surface },
        rounded ? null : styles.square,
      ]}
      testID={testID}
    >
      <Image
        source={playback.posterUrl ? { uri: playback.posterUrl } : null}
        placeholder={playback.blurhash ? { blurhash: playback.blurhash } : null}
        contentFit="cover"
        transition={150}
        style={StyleSheet.absoluteFill}
        accessibilityIgnoresInvertColors
        accessibilityLabel="Photo"
        testID={testID ? `${testID}-image` : undefined}
      />
      {shade ? <ClipShade /> : null}
      {onDoubleTap ? (
        <Pressable
          style={StyleSheet.absoluteFill}
          onPress={(event) =>
            tap({ x: event.nativeEvent.locationX, y: event.nativeEvent.locationY })
          }
          accessibilityRole="imagebutton"
          accessibilityLabel="Photo"
          accessibilityHint="Double tap to like"
          testID={testID ? `${testID}-tap` : undefined}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  frame: { width: '100%', borderRadius: radius.md, overflow: 'hidden' },
  square: { borderRadius: 0 },
});
