import { useEffect, useRef } from 'react';
import { StyleSheet, View } from 'react-native';

import type { Post } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import type { PlaybackProgress } from '@/features/player/types';
import { VideoPlayer } from '@/features/player/VideoPlayer';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { advanceWatch, INITIAL_WATCH } from '@/features/telemetry/watchTracker';
import { useTheme } from '@/theme/ThemeProvider';
import { radius } from '@/theme/tokens';

type Props = {
  post: Post;
  /** Plays when true; in a list only the clip in view is active (see the player manager). */
  active: boolean;
  /** False shows the poster only (a clip far from the one in view). */
  live?: boolean;
  /** The sound button at the top of the clip (the feed lays its controls over the foot). */
  soundAtTop?: boolean;
};

/** Width divided by height of a post's clip; portrait 9:16 until the size is known. */
export function aspectOf(post: Post): number {
  const { width, height } = post.playback;
  return width && height ? width / height : 9 / 16;
}

/**
 * A post's clip: the player when it is ready, a note while it is processing or when it failed. While
 * the clip is the active one, what the viewer watches is reported: the start, each quarter, the end.
 */
export function PostMedia({ post, active, live = true, soundAtTop }: Props) {
  const { colors } = useTheme();
  const track = useTrack();
  const { playback } = post;
  const aspectRatio = aspectOf(post);

  // One "viewing" lasts from the clip becoming active until it stops being so; each watch event is
  // reported once per viewing, however often the clip loops.
  const watch = useRef(INITIAL_WATCH);
  useEffect(() => {
    watch.current = INITIAL_WATCH;
  }, [active, post.id]);

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

  if (playback.status === 'READY' && playback.hlsUrl) {
    return (
      <VideoPlayer
        testID="post-player"
        hlsUrl={playback.hlsUrl}
        posterUrl={playback.posterUrl}
        blurhash={playback.blurhash}
        aspectRatio={aspectRatio}
        active={active}
        live={live}
        soundAtTop={soundAtTop}
        onProgress={onProgress}
      />
    );
  }
  return (
    <View style={[styles.pending, { backgroundColor: colors.surface, aspectRatio }]}>
      <Text muted>
        {post.status === 'FAILED' ? 'This clip could not be processed.' : 'Processing…'}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  pending: {
    width: '100%',
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
