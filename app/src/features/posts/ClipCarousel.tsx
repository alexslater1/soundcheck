import Ionicons from '@expo/vector-icons/Ionicons';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  FlatList,
  Platform,
  Pressable,
  StyleSheet,
  View,
  type LayoutChangeEvent,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from 'react-native';

import type { Post, PostClip } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ActBar } from './ActBar';
import { actAt, actSegments, firstClipOf, showsActBar } from './actBar';
import { aspectOf, PostMedia } from './PostMedia';

type Props = {
  post: Post;
  /** The post is the one in view: its clip in view plays. */
  active: boolean;
  /** False shows posters only. */
  live?: boolean;
  soundAtTop?: boolean;
  /** The shape of the box every clip is shown in (see `PostMedia`); default: the cover's own. */
  frameAspect?: number;
  rounded?: boolean;
  surface?: 'surfaceView' | 'textureView';
  /** Where the clip opened from the feed was (the web player starts there). */
  startAtMs?: number;
  /** The clip to start on (the one in view when the post was opened from the feed). */
  initialClipId?: string;
  /** Told whenever another clip comes into view (the feed hands that one to the post page). */
  onClipChange?: (clip: PostClip | undefined) => void;
  /** The act bar above the clips (the post page) or over their top (a feed card). */
  actBar?: 'above' | 'overlay';
  /** Room to leave at the top of the overlay (a gig ticket drawn over the clip). */
  overlayTop?: number;
};

/**
 * A post's clips, swiped through sideways: the post page and every feed card show the same
 * carousel, so vertical swipes move between posts and horizontal ones between a post's clips. Only the
 * clip in view plays; its neighbours show their posters. A "2/9" marker and dots say where you are, an
 * act bar (two acts or more) jumps to an act's first clip, and a collaborator's clip says who filmed it.
 * A post with one clip is just that clip.
 */
export function ClipCarousel({
  post,
  active,
  live = true,
  soundAtTop,
  frameAspect,
  rounded,
  surface,
  startAtMs,
  initialClipId,
  onClipChange,
  actBar = 'overlay',
  overlayTop = 0,
}: Props) {
  const { colors } = useTheme();
  const clips = post.clips;
  const initial = Math.max(
    0,
    clips.findIndex((clip) => clip.id === initialClipId),
  );
  const [index, setIndex] = useState(initial);
  const [width, setWidth] = useState(0);
  const list = useRef<FlatList<PostClip>>(null);
  const segments = useMemo(() => actSegments(clips), [clips]);
  const boxAspect = frameAspect ?? aspectOf(post);
  const current = clips[Math.min(index, clips.length - 1)];

  useEffect(() => {
    onClipChange?.(current);
  }, [current, onClipChange]);

  if (clips.length <= 1) {
    return (
      <PostMedia
        post={post}
        clip={clips[0]}
        active={active}
        live={live}
        soundAtTop={soundAtTop}
        frameAspect={frameAspect}
        rounded={rounded}
        surface={surface}
        startAtMs={startAtMs}
      />
    );
  }

  const goTo = (target: number) => {
    const bounded = Math.max(0, Math.min(clips.length - 1, target));
    setIndex(bounded);
    list.current?.scrollToOffset({ offset: bounded * width, animated: true });
  };
  const onScroll = (event: NativeSyntheticEvent<NativeScrollEvent>) => {
    if (width <= 0) {
      return;
    }
    const seen = Math.round(event.nativeEvent.contentOffset.x / width);
    if (seen !== index && seen >= 0 && seen < clips.length) {
      setIndex(seen);
    }
  };
  const onLayout = (event: LayoutChangeEvent) => {
    const measured = Math.round(event.nativeEvent.layout.width);
    if (measured !== width) {
      setWidth(measured);
    }
  };

  const bar = showsActBar(segments) ? (
    <ActBar
      segments={segments}
      current={actAt(segments, index)}
      onPick={(actId) => goTo(firstClipOf(segments, actId))}
      onDark={actBar === 'overlay'}
    />
  ) : null;
  const filmedBy =
    current && current.owner.id !== post.author.id ? `filmed by @${current.owner.handle}` : null;

  return (
    <View testID="clip-carousel">
      {actBar === 'above' && bar ? <View style={styles.above}>{bar}</View> : null}
      <View onLayout={onLayout} testID="clip-frame">
        {width > 0 ? (
          <FlatList
            ref={list}
            horizontal
            pagingEnabled
            data={clips}
            keyExtractor={(clip) => clip.id}
            getItemLayout={(_, i) => ({ length: width, offset: width * i, index: i })}
            initialScrollIndex={initial}
            initialNumToRender={Math.min(clips.length, initial + 2)}
            windowSize={3}
            onScroll={onScroll}
            scrollEventThrottle={32}
            showsHorizontalScrollIndicator={false}
            nestedScrollEnabled
            testID="clip-list"
            renderItem={({ item, index: i }) => (
              <View style={{ width }} testID={`clip-${item.id}`}>
                <PostMedia
                  post={post}
                  clip={item}
                  active={active && i === index}
                  live={live && Math.abs(i - index) <= 1}
                  soundAtTop={soundAtTop}
                  frameAspect={boxAspect}
                  rounded={rounded ?? frameAspect === undefined}
                  surface={surface}
                  startAtMs={i === initial ? startAtMs : undefined}
                />
              </View>
            )}
          />
        ) : (
          // Until the width is known: the box the clips will fill, so nothing jumps.
          <View
            style={[styles.measuring, { aspectRatio: boxAspect, backgroundColor: colors.surface }]}
          />
        )}
        <View
          // Clear of the sound button when it sits at the top right.
          style={[styles.overlay, { top: overlayTop, right: soundAtTop ? SOUND_ROOM : spacing.sm }]}
          pointerEvents="box-none"
        >
          {actBar === 'overlay' ? bar : null}
          <View style={styles.where} pointerEvents="none">
            <View style={styles.counter}>
              <Text
                variant="caption"
                style={styles.counterText}
                accessibilityLabel={`Clip ${index + 1} of ${clips.length}`}
                testID="clip-counter"
              >
                {index + 1}/{clips.length}
              </Text>
            </View>
            {filmedBy ? (
              <View style={styles.counter}>
                <Text variant="caption" style={styles.counterText} testID="clip-filmed-by">
                  {filmedBy}
                </Text>
              </View>
            ) : null}
          </View>
          <View style={styles.dots} pointerEvents="none">
            {clips.map((clip, i) => (
              <View
                key={clip.id}
                style={[styles.dot, i === index ? styles.dotOn : null]}
                testID={i === index ? 'clip-dot-current' : undefined}
              />
            ))}
          </View>
        </View>
        {Platform.OS === 'web' && width > 0 ? (
          <>
            {index > 0 ? <Arrow side="left" onPress={() => goTo(index - 1)} /> : null}
            {index < clips.length - 1 ? (
              <Arrow side="right" onPress={() => goTo(index + 1)} />
            ) : null}
          </>
        ) : null}
      </View>
    </View>
  );
}

/** A mouse has no swipe: the web gets buttons for the previous and next clip. */
function Arrow({ side, onPress }: { side: 'left' | 'right'; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={side === 'left' ? 'Previous clip' : 'Next clip'}
      onPress={onPress}
      style={[styles.arrow, side === 'left' ? styles.arrowLeft : styles.arrowRight]}
      testID={`clip-${side === 'left' ? 'previous' : 'next'}`}
    >
      <Ionicons
        name={side === 'left' ? 'chevron-back' : 'chevron-forward'}
        size={22}
        color="#FFFFFF"
      />
    </Pressable>
  );
}

/** Room on the right of the overlay for the sound button. */
const SOUND_ROOM = 64;

const styles = StyleSheet.create({
  above: { marginBottom: spacing.sm },
  measuring: { width: '100%', borderRadius: radius.md },
  overlay: {
    position: 'absolute',
    left: spacing.sm,
    paddingTop: spacing.sm,
    gap: spacing.xs,
    alignItems: 'flex-start',
  },
  where: { flexDirection: 'row', gap: spacing.xs },
  counter: {
    backgroundColor: 'rgba(0,0,0,0.45)',
    borderRadius: radius.pill,
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
  },
  counterText: { color: '#FFFFFF', fontWeight: '700' },
  dots: { flexDirection: 'row', gap: 4, alignSelf: 'center' },
  dot: { width: 6, height: 6, borderRadius: 3, backgroundColor: 'rgba(255,255,255,0.45)' },
  dotOn: { backgroundColor: '#FFFFFF' },
  arrow: {
    position: 'absolute',
    top: '50%',
    marginTop: -20,
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.45)',
  },
  arrowLeft: { left: spacing.sm },
  arrowRight: { right: spacing.sm },
});
