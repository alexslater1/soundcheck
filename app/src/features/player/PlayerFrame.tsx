import Ionicons from '@expo/vector-icons/Ionicons';
import { Image } from 'expo-image';
import { useEffect, useRef, type PropsWithChildren } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { usePlayback } from './PlaybackScope';
import { SEEK_BAR_HEIGHT, SeekBar } from './SeekBar';
import { createTapClassifier, type TapClassifier, type TapPoint } from './tapGesture';

/** What the seek bar needs: where the clip is, how long it is, and how to move it. */
export type SeekControl = {
  positionMs: number;
  durationMs: number;
  onSeek: (positionMs: number) => void;
};

type Props = PropsWithChildren<{
  posterUrl?: string | null;
  blurhash?: string | null;
  aspectRatio: number;
  /** The poster covers the video until its first frame is on screen. */
  showPoster: boolean;
  /** Hides the sound button (a poster-only clip has no sound to toggle). */
  showSound: boolean;
  /** The clip has no audio track: a crossed-out speaker and "No sound" replace the toggle. */
  noSound?: boolean;
  /** The sound button in the top corner instead of the bottom one. */
  soundAtTop?: boolean;
  /** False: square corners (a clip filling a feed page). */
  rounded?: boolean;
  failed?: boolean;
  onRetry?: () => void;
  /** Paused by the viewer: a play sign shows in the middle. */
  paused?: boolean;
  /** A tap on the clip (a live clip pauses or plays). */
  onTogglePause?: () => void;
  /** A double tap on the clip; with it set, a single tap waits out the double-tap window. */
  onDoubleTap?: (point: TapPoint) => void;
  /** The seek bar along the foot of the clip, once its length is known. */
  seek?: SeekControl;
  testID?: string;
}>;

/** A clip's poster over its BlurHash placeholder, filling the box it is drawn in. */
export function ClipPoster({
  posterUrl,
  blurhash,
  testID,
}: {
  posterUrl?: string | null;
  blurhash?: string | null;
  testID?: string;
}) {
  return (
    <Image
      testID={testID}
      source={posterUrl ? { uri: posterUrl } : null}
      placeholder={blurhash ? { blurhash } : null}
      contentFit="cover"
      transition={150}
      style={StyleSheet.absoluteFill}
      accessibilityIgnoresInvertColors
    />
  );
}

/**
 * What every player shares, native or web: the box with the clip's shape, the poster over a BlurHash
 * placeholder (so there is colour before the first byte of the poster arrives), the error state, and
 * the controls: a tap on the clip pauses or plays it, a double tap is passed on (the post
 * likes it), the speaker button is the only way to turn sound on or off, and the seek bar sits along
 * the foot.
 */
export function PlayerFrame({
  children,
  posterUrl,
  blurhash,
  aspectRatio,
  showPoster,
  showSound,
  noSound = false,
  soundAtTop = false,
  rounded = true,
  failed = false,
  onRetry,
  paused = false,
  onTogglePause,
  onDoubleTap,
  seek,
  testID,
}: Props) {
  const { colors } = useTheme();
  const { muted, toggleMuted } = usePlayback();

  // The classifier lives as long as the frame, made on the first tap; it reads the latest handlers
  // through a ref when its timer fires.
  const handlers = useRef({ onTogglePause, onDoubleTap });
  useEffect(() => {
    handlers.current = { onTogglePause, onDoubleTap };
  });
  const taps = useRef<TapClassifier | null>(null);
  useEffect(() => () => taps.current?.cancel(), []);
  const tap = (point: TapPoint) => {
    taps.current ??= createTapClassifier({
      schedule: (fn, ms) => {
        const timer = setTimeout(fn, ms);
        return () => clearTimeout(timer);
      },
      onSingle: () => handlers.current.onTogglePause?.(),
      onDouble: (at) => handlers.current.onDoubleTap?.(at),
    });
    taps.current.tap(point);
  };

  const controls = showSound && !failed;
  return (
    <View
      style={[
        styles.frame,
        { aspectRatio, backgroundColor: colors.surface },
        rounded ? null : styles.square,
      ]}
      testID={testID}
    >
      {children}
      {showPoster ? (
        <ClipPoster
          posterUrl={posterUrl}
          blurhash={blurhash}
          testID={testID ? `${testID}-poster` : undefined}
        />
      ) : null}
      {failed ? (
        <View
          style={[StyleSheet.absoluteFill, styles.failed]}
          testID={testID ? `${testID}-error` : undefined}
        >
          <Text style={styles.onVideo}>This clip could not be played.</Text>
          {onRetry ? <Button title="Try again" variant="secondary" onPress={onRetry} /> : null}
        </View>
      ) : null}
      {controls ? (
        <Pressable
          style={[StyleSheet.absoluteFill, styles.center]}
          onPress={(event) => {
            const point = { x: event.nativeEvent.locationX, y: event.nativeEvent.locationY };
            // Without a double-tap action there is nothing to wait for.
            if (onDoubleTap) {
              tap(point);
            } else {
              onTogglePause?.();
            }
          }}
          accessibilityRole="button"
          accessibilityLabel={paused ? 'Play' : 'Pause'}
          testID={testID ? `${testID}-tap` : undefined}
        >
          {paused ? (
            <View pointerEvents="none" testID={testID ? `${testID}-paused` : undefined}>
              <Ionicons name="play" size={64} color="rgba(255,255,255,0.9)" />
            </View>
          ) : null}
        </Pressable>
      ) : null}
      {controls && noSound ? (
        <View
          style={[
            styles.soundBadge,
            styles.noSound,
            soundAtTop ? styles.soundAtTop : { bottom: spacing.sm + (seek ? SEEK_BAR_HEIGHT : 0) },
          ]}
          accessible
          accessibilityLabel="No sound"
          testID={testID ? `${testID}-no-sound` : undefined}
        >
          <Ionicons name="volume-mute" size={16} color="#FFFFFF" />
          <Text variant="caption" style={styles.onVideo}>
            No sound
          </Text>
        </View>
      ) : null}
      {controls && !noSound ? (
        <Pressable
          style={[
            styles.soundBadge,
            soundAtTop ? styles.soundAtTop : { bottom: spacing.sm + (seek ? SEEK_BAR_HEIGHT : 0) },
          ]}
          onPress={toggleMuted}
          hitSlop={8}
          accessibilityRole="button"
          accessibilityLabel={muted ? 'Turn sound on' : 'Turn sound off'}
          testID={testID ? `${testID}-sound` : undefined}
        >
          <Ionicons name={muted ? 'volume-mute' : 'volume-high'} size={20} color="#FFFFFF" />
        </Pressable>
      ) : null}
      {controls && seek ? (
        <SeekBar
          positionMs={seek.positionMs}
          durationMs={seek.durationMs}
          onSeek={seek.onSeek}
          testID={testID ? `${testID}-seek` : undefined}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  frame: { width: '100%', borderRadius: radius.md, overflow: 'hidden' },
  square: { borderRadius: 0 },
  center: { alignItems: 'center', justifyContent: 'center' },
  noSound: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    width: 'auto',
    paddingHorizontal: spacing.sm,
  },
  failed: {
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
    padding: spacing.md,
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  onVideo: { color: '#FFFFFF' },
  soundBadge: {
    position: 'absolute',
    right: spacing.sm,
    width: touchTarget,
    height: touchTarget,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  soundAtTop: { top: spacing.sm },
});
