import { Image } from 'expo-image';
import { LinearGradient } from 'expo-linear-gradient';
import { StyleSheet, View } from 'react-native';

import type { Post, Reason } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { usePlayerSlot } from '@/features/player/playerManager';
import { SEEK_BAR_HEIGHT } from '@/features/player/SeekBar';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

import { PostActions } from './PostActions';
import { aspectOf, PostMedia } from './PostMedia';
import { PostMeta } from './PostMeta';
import { fitClip, pageFit } from './pageFit';
import { useOpenPost } from './useOpenPost';

/** From this width on, the clip gets a details panel beside it instead of an overlay. */
export const WIDE_FROM = 900;

type Props = {
  post: Post;
  reasons?: Reason[];
  /** The size of one page: the pager's own width and height. */
  width: number;
  height: number;
  onGone?: (postId: string) => void;
};

/**
 * One page of a feed: the clip fills the screen, with the author, caption and tags laid
 * over its foot and the actions stacked on its right. On a wide window the clip is a centred column
 * and the same details sit in a panel beside it.
 */
export function FeedPage({ post, reasons, width, height, onGone }: Props) {
  const { colors } = useTheme();
  const { live, active } = usePlayerSlot(post.id);
  const aspect = aspectOf(post);
  // The caption and the comment count open the post, with the clip playing on.
  const open = useOpenPost(post);

  if (width >= WIDE_FROM) {
    const clip = fitClip(aspect, width - PANEL_WIDTH - spacing.xl, height - spacing.lg);
    return (
      <View style={[styles.wide, { height }]} testID={`post-${post.id}`}>
        <View style={{ width: clip.width }}>
          <PostMedia post={post} active={active} live={live} />
        </View>
        <View style={[styles.panel, { backgroundColor: colors.surface }]}>
          <PostMeta post={post} onCaptionPress={open} />
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

  const clip = pageFit(aspect, width, height);
  return (
    <View style={[styles.page, { height }]} testID={`post-${post.id}`}>
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
      <View style={{ width: clip.width }}>
        <PostMedia
          post={post}
          active={active}
          live={live}
          soundAtTop
          frameAspect={clip.fill ? width / height : undefined}
        />
      </View>
      {/* A gradient from clear to dark under the overlay: text stays readable on a bright frame
          without a box over the clip. */}
      <LinearGradient
        pointerEvents="none"
        colors={['transparent', 'rgba(0,0,0,0.55)', 'rgba(0,0,0,0.85)']}
        locations={[0, 0.55, 1]}
        style={[styles.shade, { height: Math.min(height * 0.45, 360) }]}
      />
      {/* box-none: taps between the controls reach the clip (a tap pauses it). Over a clip that fills
          the page it stops above the seek bar. */}
      <View
        style={[styles.overlay, clip.fill ? { bottom: SEEK_BAR_HEIGHT } : null]}
        pointerEvents="box-none"
      >
        <View style={styles.scrim}>
          <PostMeta post={post} onDark onCaptionPress={open} />
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
  );
}

const PANEL_WIDTH = 360;

const styles = StyleSheet.create({
  page: { alignItems: 'center', justifyContent: 'center', backgroundColor: '#000000' },
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
