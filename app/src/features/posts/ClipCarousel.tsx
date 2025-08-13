import Ionicons from '@expo/vector-icons/Ionicons';
import { Image } from 'expo-image';
import { useEffect, useMemo, useRef, useState, type PropsWithChildren } from 'react';
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
import { ClipShade } from '@/features/player/ClipShade';
import { SoundButton } from '@/features/player/PlayerFrame';
import { SEEK_BAR_HEIGHT } from '@/features/player/SeekBar';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { ActBar } from './ActBar';
import { actAt, actSegments, firstClipOf, showsActBar } from './actBar';
import { clipInBox } from './pageFit';
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
  /** A dark gradient over the foot of each clip, under its controls (the feed's text sits there). */
  shade?: boolean;
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
  shade,
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
      current={actAt(clips, index)}
      // Already on that act's clips: stay on the clip in view.
      onPick={(actId) =>
        actId === actAt(clips, index) ? undefined : goTo(firstClipOf(segments, actId))
      }
      onDark={actBar === 'overlay'}
    />
  ) : null;
  // Clear of the sound button when it sits at the top right.
  const overlayRight = soundAtTop ? SOUND_ROOM : spacing.sm;
  const sound = soundOf(current);
  const filmedBy =
    current && current.owner.id !== post.author.id ? `filmed by @${current.owner.handle}` : null;

  if (clips.length <= 1) {
    const media = (
      <PostMedia
        post={post}
        clip={clips[0]}
        active={active}
        live={live}
        soundAtTop={soundAtTop}
        shade={shade}
        frameAspect={frameAspect}
        rounded={rounded}
        surface={surface}
        startAtMs={startAtMs}
      />
    );
    if (!bar) {
      return media;
    }
    // One clip has no carousel, but still says whose set it is.
    return (
      <View>
        {actBar === 'above' ? <View style={styles.above}>{bar}</View> : null}
        <View>
          {media}
          {actBar === 'overlay' ? (
            <View
              style={[styles.overlay, { top: overlayTop, right: overlayRight }]}
              pointerEvents="box-none"
            >
              {bar}
            </View>
          ) : null}
        </View>
      </View>
    );
  }

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
                <ShapedClip
                  clip={item}
                  boxAspect={boxAspect}
                  rounded={rounded ?? frameAspect === undefined}
                  shade={shade}
                >
                  <PostMedia
                    post={post}
                    clip={item}
                    active={active && i === index}
                    live={live && Math.abs(i - index) <= 1}
                    soundAtTop={soundAtTop}
                    // One sound button for the carousel, drawn below: it stays still while the
                    // clips swipe past under it, as the counter and the dots do.
                    soundButton={false}
                    shade={shade}
                    frameAspect={shapeOf(item) ?? boxAspect}
                    rounded={rounded ?? frameAspect === undefined}
                    surface={surface}
                    startAtMs={i === initial ? startAtMs : undefined}
                  />
                </ShapedClip>
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
          style={[styles.overlay, { top: overlayTop, right: overlayRight }]}
          pointerEvents="box-none"
        >
          {/* The counter, then the act chips on the same line; long chips scroll sideways. */}
          <View style={styles.where} pointerEvents="box-none" testID="clip-where">
            <View style={styles.counter} pointerEvents="none">
              <Text
                variant="caption"
                style={styles.counterText}
                accessibilityLabel={`Clip ${index + 1} of ${clips.length}`}
                testID="clip-counter"
              >
                {index + 1}/{clips.length}
              </Text>
            </View>
            {actBar === 'overlay' && bar ? <View style={styles.barBeside}>{bar}</View> : null}
          </View>
          {filmedBy ? (
            <View style={styles.counter} pointerEvents="none">
              <Text variant="caption" style={styles.counterText} testID="clip-filmed-by">
                {filmedBy}
              </Text>
            </View>
          ) : null}
          <View
            // The overlay is inset more on the right (the sound button): shift the dots back by half
            // the difference so they sit in the middle of the clip, not of the overlay.
            style={[styles.dots, { transform: [{ translateX: (overlayRight - spacing.sm) / 2 }] }]}
            pointerEvents="none"
            testID="clip-dots"
          >
            {clips.map((clip, i) => (
              <View
                key={clip.id}
                style={[styles.dot, i === index ? styles.dotOn : null]}
                testID={i === index ? 'clip-dot-current' : undefined}
              />
            ))}
          </View>
        </View>
        {live && sound ? (
          <SoundButton
            noSound={sound === 'none'}
            style={
              soundAtTop
                ? styles.soundAtTop
                : { bottom: spacing.sm + (sound === 'toggle' ? SEEK_BAR_HEIGHT : 0) }
            }
            testID="clip-carousel"
          />
        ) : null}
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

/**
 * What the carousel's sound button shows for the clip in view: the toggle for a playable video, "No
 * sound" for one without an audio track, nothing for a photo or a clip still on its way.
 */
export function soundOf(clip: PostClip | undefined): 'toggle' | 'none' | null {
  const playback = clip?.playback;
  if (!playback || playback.status !== 'READY' || playback.kind === 'IMAGE' || !playback.hlsUrl) {
    return null;
  }
  return playback.hasAudio === false ? 'none' : 'toggle';
}

/** A clip's own shape (width over height), or null while its size is unknown. */
function shapeOf(clip: PostClip): number | null {
  const { width, height } = clip.playback;
  return width && height ? width / height : null;
}

/**
 * Keeps every clip of a post in the post's one box, so swiping never makes the box jump. A clip of
 * another shape is shown whole, centred, over a blurred, scaled-up copy of its own poster (the usual
 * short-video treatment) instead of being cropped hard or left with black bars.
 */
function ShapedClip({
  clip,
  boxAspect,
  rounded,
  shade,
  children,
}: PropsWithChildren<{ clip: PostClip; boxAspect: number; rounded: boolean; shade?: boolean }>) {
  const fit = clipInBox(shapeOf(clip), boxAspect);
  if (!fit) {
    return <>{children}</>;
  }
  const own = shapeOf(clip) ?? boxAspect;
  return (
    <View
      style={[styles.shaped, { aspectRatio: boxAspect }, rounded ? styles.shapedRounded : null]}
      testID={`clip-${clip.id}-shaped`}
    >
      <Image
        source={clip.playback.posterUrl ? { uri: clip.playback.posterUrl } : null}
        placeholder={clip.playback.blurhash ? { blurhash: clip.playback.blurhash } : null}
        blurRadius={30}
        contentFit="cover"
        style={StyleSheet.absoluteFill}
        accessibilityIgnoresInvertColors
        testID={`clip-${clip.id}-backdrop`}
      />
      {/* The feed's text runs over the backdrop too. */}
      {shade ? <ClipShade /> : null}
      <View style={{ width: `${fit.width * 100}%`, aspectRatio: own }}>{children}</View>
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
      // 40 pt on screen, 44 pt to touch.
      hitSlop={(touchTarget - 40) / 2}
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
  shaped: { width: '100%', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  shapedRounded: { borderRadius: radius.md },
  above: { marginBottom: spacing.sm },
  measuring: { width: '100%', borderRadius: radius.md },
  overlay: {
    position: 'absolute',
    left: spacing.sm,
    paddingTop: spacing.sm,
    gap: spacing.xs,
    alignItems: 'flex-start',
  },
  where: { flexDirection: 'row', alignItems: 'center', alignSelf: 'stretch', gap: spacing.xs },
  barBeside: { flexShrink: 1 },
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
  soundAtTop: { top: spacing.sm },
  arrowLeft: { left: spacing.sm },
  arrowRight: { right: spacing.sm },
});
