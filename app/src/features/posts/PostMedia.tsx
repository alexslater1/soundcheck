import { Image } from 'expo-image';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';

import type { Post, PostClip } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { PROCESSING_HINT } from '@/features/player/processing';
import type { TapPoint } from '@/features/player/tapGesture';
import type { PlaybackProgress } from '@/features/player/types';
import { VideoPlayer } from '@/features/player/VideoPlayer';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { advanceWatch, INITIAL_WATCH } from '@/features/telemetry/watchTracker';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { HeartBurst } from './HeartBurst';
import { useLike } from './useLike';

type Props = {
  post: Post;
  /**
   * Which of the post's clips to show; without one, the post's cover (`post.playback`), as a
   * grid or a post from before clips existed shows it.
   */
  clip?: PostClip;
  /** Plays when true; in a list only the clip in view is active (see the player manager). */
  active: boolean;
  /** False shows the poster only (a clip far from the one in view). */
  live?: boolean;
  /** The sound button at the top of the clip (the feed lays its controls over the foot). */
  soundAtTop?: boolean;
  /**
   * The shape of the box the clip is shown in, when it is not the clip's own: the feed fills a page
   * whose shape is close to the clip's, cropping the edges (see `pageFit`).
   */
  frameAspect?: number;
  /** Rounded corners even when the clip fills a box of another shape (the feed's card styles). */
  rounded?: boolean;
  /** See `VideoPlayerProps.surface`: the post page asks for a TextureView. */
  surface?: 'surfaceView' | 'textureView';
  /** Where the feed's clip was when this post was opened from it (used by the web player). */
  startAtMs?: number;
};

/** Width divided by height of a post's clip; portrait 9:16 until the size is known. */
export function aspectOf(post: Post): number {
  const { width, height } = post.playback;
  return width && height ? width / height : 9 / 16;
}

type Burst = { id: number; x: number; y: number };

/**
 * One clip of a post: the player when it is ready, a note while it is on its way (its preview frame,
 * blurred, behind it) or when it failed. While
 * the clip is the active one, what the viewer watches is reported: the start, each quarter, the end.
 * A double tap on a published clip likes the post and pops a heart where the finger was (H5).
 */
export function PostMedia({
  post,
  clip,
  active,
  live = true,
  soundAtTop,
  frameAspect,
  rounded,
  surface,
  startAtMs,
}: Props) {
  const { colors } = useTheme();
  const track = useTrack();
  const myId = useAuthStore((state) => state.me?.id);
  const playback = clip ? clip.playback : post.playback;
  // One player per clip; the post's id for a post without clips.
  const clipKey = clip ? clip.id : post.id;
  const aspectRatio = frameAspect ?? aspectOf(post);
  const { like } = useLike(post.id, { likedByMe: post.likedByMe, likeCount: post.likeCount });
  const [bursts, setBursts] = useState<Burst[]>([]);
  const nextBurst = useRef(0);

  // One "viewing" lasts from the clip becoming active until it stops being so; each watch event is
  // reported once per viewing, however often the clip loops.
  const watch = useRef(INITIAL_WATCH);
  useEffect(() => {
    watch.current = INITIAL_WATCH;
  }, [active, clipKey]);

  const onProgress = (progress: PlaybackProgress) => {
    if (!active) {
      return;
    }
    const step = advanceWatch(watch.current, progress);
    watch.current = step.state;
    for (const event of step.events) {
      track(event.type, post.id, event.type === 'WATCH_PROGRESS' ? event.value : undefined);
    }
  };

  const onDoubleTap = (point: TapPoint) => {
    nextBurst.current += 1;
    const burst = { id: nextBurst.current, ...point };
    setBursts((now) => [...now, burst]);
    void like();
  };
  const dropBurst = useCallback(
    (id: number) => setBursts((now) => now.filter((burst) => burst.id !== id)),
    [],
  );

  if (playback.status === 'READY' && playback.hlsUrl) {
    return (
      <View>
        <VideoPlayer
          testID="post-player"
          clipKey={clipKey}
          hlsUrl={playback.hlsUrl}
          posterUrl={playback.posterUrl}
          blurhash={playback.blurhash}
          aspectRatio={aspectRatio}
          active={active}
          live={live}
          soundAtTop={soundAtTop}
          rounded={rounded ?? frameAspect === undefined}
          surface={surface}
          startAtMs={startAtMs}
          onDoubleTap={post.status === 'PUBLISHED' ? onDoubleTap : undefined}
          onProgress={onProgress}
        />
        {bursts.map((burst) => (
          <HeartBurst key={burst.id} x={burst.x} y={burst.y} onDone={() => dropBurst(burst.id)} />
        ))}
      </View>
    );
  }
  if (clip?.status === 'FAILED' || (!clip && post.status === 'FAILED')) {
    return (
      <View style={[styles.pending, { backgroundColor: colors.surface, aspectRatio }]}>
        <Text muted>This clip could not be processed.</Text>
      </View>
    );
  }
  if (clip && (post.status === 'PUBLISHED' || clip.previewUrl)) {
    // A clip of a post that is live while this one is still uploading or processing: its
    // preview frame, blurred, says what is coming. The owner and the author also get the usual hint.
    const mine = myId !== undefined && (myId === clip.owner.id || myId === post.author.id);
    return (
      <View
        style={[styles.pending, styles.onItsWay, { backgroundColor: colors.surface, aspectRatio }]}
        testID="clip-on-its-way"
      >
        {clip.previewUrl ? (
          <Image
            source={{ uri: clip.previewUrl }}
            blurRadius={24}
            contentFit="cover"
            style={StyleSheet.absoluteFill}
            accessibilityIgnoresInvertColors
            testID="clip-preview"
          />
        ) : null}
        <ActivityIndicator color={clip.previewUrl ? LIGHT : colors.accent} />
        <Text style={clip.previewUrl ? styles.light : undefined}>On its way</Text>
        {mine ? (
          <Text
            variant="caption"
            muted={!clip.previewUrl}
            style={[styles.hint, clip.previewUrl ? styles.light : null]}
          >
            {PROCESSING_HINT}
          </Text>
        ) : null}
      </View>
    );
  }
  // Not a blank grey box: say what is happening and roughly how long it takes. The
  // screens around it keep polling, so the player replaces this by itself.
  return (
    <View
      style={[styles.pending, { backgroundColor: colors.surface, aspectRatio }]}
      testID="post-processing"
    >
      <ActivityIndicator color={colors.accent} />
      <Text>Processing your clip…</Text>
      <Text variant="caption" muted style={styles.hint}>
        {PROCESSING_HINT}
      </Text>
    </View>
  );
}

const LIGHT = '#FFFFFF';

const styles = StyleSheet.create({
  pending: {
    width: '100%',
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
    padding: spacing.lg,
  },
  hint: { textAlign: 'center' },
  onItsWay: { overflow: 'hidden' },
  light: {
    color: LIGHT,
    textShadowColor: 'rgba(0,0,0,0.6)',
    textShadowRadius: 4,
    textShadowOffset: { width: 0, height: 1 },
  },
});
