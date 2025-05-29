import { StyleSheet, View } from 'react-native';

import type { Post, Reason } from '@/api/generated/model';
import { spacing } from '@/theme/tokens';

import { PostActions } from './PostActions';
import { aspectOf, PostMedia } from './PostMedia';
import { PostMeta } from './PostMeta';

type Props = {
  post: Post;
  /** Plays when true. */
  active: boolean;
  /** False shows the poster only. */
  live?: boolean;
  /** Called after the post was hidden or deleted. */
  onGone?: (postId: string) => void;
  reasons?: Reason[];
};

/**
 * One post on its own page: the clip, who posted it, the caption, the gig and artists it is tagged
 * with, and the actions under it. (In the feeds the same parts are laid over the clip: FeedPage.)
 */
export function PostCard({ post, active, live = true, onGone, reasons }: Props) {
  return (
    <View style={styles.card} testID={`post-${post.id}`}>
      <View style={aspectOf(post) < 1 ? styles.portrait : undefined}>
        <PostMedia post={post} active={active} live={live} />
      </View>
      <PostMeta post={post} />
      <PostActions post={post} onGone={onGone} reasons={reasons} />
    </View>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.sm },
  // A portrait clip would be taller than the window on a wide screen: cap its width instead.
  portrait: { maxWidth: 360, width: '100%', alignSelf: 'center' },
});
