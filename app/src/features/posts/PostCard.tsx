import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import type { Post, PostClip, Reason } from '@/api/generated/model';
import { spacing } from '@/theme/tokens';

import { PostActions } from './PostActions';
import { ClipCarousel } from './ClipCarousel';
import { aspectOf } from './PostMedia';
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
  /** The comment button: on the post page it goes to the comment box. */
  onComment?: () => void;
  /** Where the feed's clip was, when the post was opened from it (the web player starts there). */
  startAtMs?: number;
  /** The clip to start on. */
  clipId?: string;
};

/**
 * One post on its own page: its clips (a carousel with an act bar above it), who posted it, the
 * caption, the gig and artists it is tagged with, and the actions under it. (In the feeds the same
 * parts are laid over the clip: FeedPage.)
 */
export function PostCard({
  post,
  active,
  live = true,
  onGone,
  reasons,
  onComment,
  startAtMs,
  clipId,
}: Props) {
  // The clip in view: its song shows under the caption.
  const [inView, setInView] = useState<PostClip | undefined>(undefined);
  return (
    <View style={styles.card} testID={`post-${post.id}`}>
      <View style={aspectOf(post) < 1 ? styles.portrait : undefined}>
        {/* A TextureView: the page scrolls, and a SurfaceView would lose the rounded corners. */}
        <ClipCarousel
          post={post}
          active={active}
          live={live}
          surface="textureView"
          startAtMs={startAtMs}
          initialClipId={clipId}
          actBar="above"
          onClipChange={setInView}
        />
      </View>
      <PostMeta post={post} song={inView?.song} />
      <PostActions post={post} onGone={onGone} reasons={reasons} onComment={onComment} />
    </View>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.sm },
  // A portrait clip would be taller than the window on a wide screen: cap its width instead.
  portrait: { maxWidth: 360, width: '100%', alignSelf: 'center' },
});
