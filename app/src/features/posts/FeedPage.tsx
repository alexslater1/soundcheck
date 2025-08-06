import { Image } from 'expo-image';
import { LinearGradient } from 'expo-linear-gradient';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import type { Post, PostClip, Reason } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { usePlayerSlot } from '@/features/player/playerManager';
import { SEEK_BAR_HEIGHT } from '@/features/player/SeekBar';
import { useTheme } from '@/theme/ThemeProvider';
import { useThemePrefs, type FeedStyle } from '@/theme/themePrefs';
import { radius, spacing } from '@/theme/tokens';

import { GigTicket, TICKET_HEIGHT } from './GigTicket';
import { PostActions } from './PostActions';
import { ClipCarousel } from './ClipCarousel';
import { aspectOf } from './PostMedia';
import { PostMeta } from './PostMeta';
import { fitClip, pageFit } from './pageFit';
import { useOpenPost } from './useOpenPost';

/** From this width on, the clip gets a details panel beside it instead of an overlay. */
export const WIDE_FROM = 900;

/** The gap around a card (the Cards and Ticket styles); two cards are twice this apart. */
export const CARD_INSET = spacing.sm;

type Props = {
  post: Post;
  reasons?: Reason[];
  /** The size of one page: the pager's own width and height. */
  width: number;
  height: number;
  onGone?: (postId: string) => void;
};

/**
 * Where the clip's card sits on a page of the given size in each feed style: Full
 * screen fills the page; Cards insets a rounded card so the page's background shows between posts;
 * Ticket also leaves room above the card for the gig's ticket.
 */
export function cardBox(
  style: FeedStyle,
  width: number,
  height: number,
  hasGig: boolean,
): { inset: number; ticket: number; width: number; height: number } {
  if (style === 'immersive') {
    return { inset: 0, ticket: 0, width, height };
  }
  const ticket = style === 'ticket' && hasGig ? TICKET_HEIGHT + CARD_INSET : 0;
  return {
    inset: CARD_INSET,
    ticket,
    width: width - CARD_INSET * 2,
    height: height - CARD_INSET * 2 - ticket,
  };
}

/**
 * One page of a feed: the clip fills its card, with the author, caption and artists laid
 * over its foot and the actions stacked on its right, and the gig's ticket (date, gig, venue) at the
 * top, so every post says which night it is from. Settings → Appearance picks the style.
 * On a wide window the clip is a centred column and the same details sit in a panel beside it.
 */
export function FeedPage({ post, reasons, width, height, onGone }: Props) {
  const { colors } = useTheme();
  const feedStyle = useThemePrefs((state) => state.feedStyle);
  const { live, active } = usePlayerSlot(post.id);
  const aspect = aspectOf(post);
  // The clip in view: the one that plays on when the post is opened.
  const [inView, setInView] = useState<PostClip | undefined>(undefined);
  // The caption and the comment count open the post, with the clip playing on.
  const open = useOpenPost(post, inView?.id);

  if (width >= WIDE_FROM) {
    const clip = fitClip(aspect, width - PANEL_WIDTH - spacing.xl, height - spacing.lg);
    return (
      <View style={[styles.wide, { height }]} testID={`post-${post.id}`}>
        <View style={{ width: clip.width }}>
          <ClipCarousel post={post} active={active} live={live} onClipChange={setInView} />
        </View>
        <View style={[styles.panel, { backgroundColor: colors.surface }]}>
          {post.gig ? <GigTicket gig={post.gig} postId={post.id} /> : null}
          <PostMeta post={post} onCaptionPress={open} showGig={false} song={inView?.song} />
          <PostActions post={post} onGone={onGone} reasons={reasons} onComment={open} />
          {reasons && reasons.length > 0 ? (
            <View style={styles.reasons} testID="post-reasons">
              <Text variant="label" muted>
                WHY THIS POST
              </Text>
              {reasons.map((reason) => (
                <Text key={reason.kind} muted>
                  {reason.text}
                </Text>
              ))}
            </View>
          ) : null}
        </View>
      </View>
    );
  }

  const immersive = feedStyle === 'immersive';
  const box = cardBox(feedStyle, width, height, post.gig != null);
  const clip = pageFit(aspect, box.width, box.height);
  const ticketOnTop = feedStyle === 'ticket';
  return (
    <View
      style={[
        styles.page,
        {
          height,
          padding: box.inset,
          backgroundColor: immersive ? '#000000' : colors.background,
        },
      ]}
      testID={`post-${post.id}`}
    >
      {ticketOnTop && post.gig ? (
        <View style={[styles.ticketAbove, { height: TICKET_HEIGHT }]}>
          <GigTicket gig={post.gig} postId={post.id} />
        </View>
      ) : null}
      <View
        style={[
          styles.card,
          { width: box.width, height: box.height },
          immersive ? null : styles.rounded,
        ]}
        testID="feed-card"
      >
        {clip.fill ? null : (
          <Image
            source={post.playback.posterUrl ? { uri: post.playback.posterUrl } : null}
            placeholder={post.playback.blurhash ? { blurhash: post.playback.blurhash } : null}
            blurRadius={30}
            contentFit="cover"
            style={StyleSheet.absoluteFill}
            accessibilityIgnoresInvertColors
            testID="page-backdrop"
          />
        )}
        {clip.fill ? null : (
          // A fitted clip leaves the blurred backdrop showing round it: it gets the same gradient,
          // drawn behind the clip.
          <LinearGradient
            pointerEvents="none"
            colors={['transparent', 'rgba(0,0,0,0.55)', 'rgba(0,0,0,0.85)']}
            locations={[0, 0.55, 1]}
            style={[styles.shade, { height: Math.min(box.height * 0.45, 360) }]}
          />
        )}
        <View style={{ width: clip.width }}>
          <ClipCarousel
            post={post}
            active={active}
            live={live}
            onClipChange={setInView}
            // A gradient from clear to dark under the overlay keeps the text readable on a bright
            // frame. The clip draws it under its own controls, so the seek bar shows
            // above it (2025-08-06: over the card it all but hid the bar).
            shade
            overlayTop={!ticketOnTop && post.gig ? TICKET_HEIGHT + spacing.sm : 0}
            soundAtTop
            frameAspect={clip.fill ? box.width / box.height : undefined}
            // The card rounds a clip that fills it; a fitted one keeps its own corners.
            rounded={!clip.fill}
            // A SurfaceView ignores rounded corners: a rounded card needs a TextureView (as on the
            // post page).
            surface={immersive ? 'surfaceView' : 'textureView'}
          />
        </View>
        {!ticketOnTop && post.gig ? (
          <View style={styles.ticketOver} pointerEvents="box-none">
            <GigTicket gig={post.gig} postId={post.id} onDark />
          </View>
        ) : null}
        {/* box-none: taps between the controls reach the clip (a tap pauses it). Over a clip that
            fills the card it stops above the seek bar. */}
        <View
          style={[styles.overlay, clip.fill ? { bottom: SEEK_BAR_HEIGHT } : null]}
          pointerEvents="box-none"
        >
          <View style={styles.scrim}>
            <PostMeta
              post={post}
              onDark
              onCaptionPress={open}
              showGig={false}
              song={inView?.song}
            />
          </View>
          <PostActions
            post={post}
            onGone={onGone}
            reasons={reasons}
            layout="column"
            onDark
            onComment={open}
          />
        </View>
      </View>
    </View>
  );
}

const PANEL_WIDTH = 360;
/** Room on the right of a ticket over the clip for the sound button. */
const SOUND_ROOM = 64;

const styles = StyleSheet.create({
  page: { alignItems: 'center', justifyContent: 'center' },
  card: { alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  rounded: { borderRadius: radius.lg, backgroundColor: '#000000' },
  ticketAbove: { alignSelf: 'stretch', marginBottom: CARD_INSET, justifyContent: 'center' },
  ticketOver: {
    position: 'absolute',
    top: spacing.sm,
    left: spacing.sm,
    right: SOUND_ROOM,
  },
  overlay: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
    gap: spacing.sm,
    padding: spacing.md,
  },
  shade: { position: 'absolute', left: 0, right: 0, bottom: 0 },
  scrim: { flex: 1, paddingVertical: spacing.sm },
  wide: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xl,
  },
  panel: { width: PANEL_WIDTH, gap: spacing.md, padding: spacing.lg, borderRadius: 12 },
  reasons: { gap: spacing.xs },
});
