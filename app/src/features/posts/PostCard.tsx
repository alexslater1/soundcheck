import Ionicons from '@expo/vector-icons/Ionicons';
import { Link, useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { deletePost } from '@/api/generated/posts/posts';
import { hidePost, reportPost } from '@/api/generated/engagement/engagement';
import type { Post } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { VideoPlayer } from '@/features/player/VideoPlayer';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { LikeButton } from './LikeButton';
import { sharePost } from './sharePost';

type Props = {
  post: Post;
  /** Plays when true; in a list only the clip in view is active (see the player manager). */
  active: boolean;
  /** False shows the poster only (a clip far from the one in view). */
  live?: boolean;
  /** Called after the post was hidden or deleted, so a list can drop it. */
  onGone?: (postId: string) => void;
};

/**
 * One post: the clip, who posted it, the caption, the gig and artists it is tagged with, and the
 * actions. Used alone on the post page and stacked in the feed pager.
 */
export function PostCard({ post, active, live = true, onGone }: Props) {
  const { colors } = useTheme();
  const router = useRouter();
  const myId = useAuthStore((state) => state.me?.id);
  const [menuOpen, setMenuOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const mine = post.author.id === myId;
  const { playback } = post;
  const aspectRatio = playback.width && playback.height ? playback.width / playback.height : 9 / 16;
  // One flat object: expo-router's <Link asChild> rejects a style array on web.
  const chipStyle = StyleSheet.flatten([styles.chip, { backgroundColor: colors.surfaceRaised }]);

  const act = async (work: () => Promise<unknown>, done: string, gone: boolean) => {
    setMenuOpen(false);
    try {
      await work();
      setNotice(done);
      if (gone) {
        onGone?.(post.id);
      }
    } catch {
      setNotice('That did not work. Try again.');
    }
  };

  return (
    <View style={styles.card} testID={`post-${post.id}`}>
      <View style={aspectRatio < 1 ? styles.portrait : undefined}>
        {playback.status === 'READY' && playback.hlsUrl ? (
          <VideoPlayer
            testID="post-player"
            hlsUrl={playback.hlsUrl}
            posterUrl={playback.posterUrl}
            blurhash={playback.blurhash}
            aspectRatio={aspectRatio}
            active={active}
            live={live}
          />
        ) : (
          <View style={[styles.pending, { backgroundColor: colors.surface, aspectRatio }]}>
            <Text muted>
              {post.status === 'FAILED' ? 'This clip could not be processed.' : 'Processing…'}
            </Text>
          </View>
        )}
      </View>

      <View style={styles.row}>
        <Link href={{ pathname: '/u/[handle]', params: { handle: post.author.handle } }} asChild>
          <Pressable accessibilityRole="link" style={styles.author}>
            <Text style={styles.bold}>{post.author.displayName}</Text>
            <Text muted>@{post.author.handle}</Text>
          </Pressable>
        </Link>
      </View>
      {post.caption ? <Text testID="post-caption">{post.caption}</Text> : null}

      <View style={styles.chips}>
        {post.gig ? (
          <Link href={{ pathname: '/gig/[id]', params: { id: post.gig.id } }} asChild>
            <Pressable accessibilityRole="link" style={chipStyle}>
              <Ionicons name="calendar-outline" size={14} color={colors.textMuted} />
              <Text variant="caption">
                {post.gig.title} · {post.gig.venue.name}
              </Text>
            </Pressable>
          </Link>
        ) : null}
        {post.artists.map((artist) => (
          <Link
            key={artist.id}
            href={{ pathname: '/artist/[id]', params: { id: artist.id } }}
            asChild
          >
            <Pressable accessibilityRole="link" style={chipStyle}>
              <Text variant="caption">{artist.name}</Text>
            </Pressable>
          </Link>
        ))}
      </View>

      {post.status === 'PUBLISHED' ? (
        <View style={styles.actions}>
          <LikeButton
            key={`${post.id}-${post.likedByMe}-${post.likeCount}`}
            postId={post.id}
            initial={{ likedByMe: post.likedByMe, likeCount: post.likeCount }}
          />
          <Pressable
            style={styles.action}
            accessibilityRole="link"
            accessibilityLabel={`${post.commentCount} comments`}
            onPress={() => router.push({ pathname: '/post/[id]', params: { id: post.id } })}
          >
            <Ionicons name="chatbubble-outline" size={22} color={colors.text} />
            <Text testID="comment-count">{post.commentCount}</Text>
          </Pressable>
          <Pressable
            style={styles.action}
            accessibilityRole="button"
            accessibilityLabel="Share"
            onPress={() =>
              void sharePost(post.id).then((result) =>
                setNotice(
                  result === 'copied'
                    ? 'Link copied'
                    : result === 'failed'
                      ? 'Could not share'
                      : null,
                ),
              )
            }
          >
            <Ionicons name="share-outline" size={22} color={colors.text} />
          </Pressable>
          <Pressable
            style={styles.action}
            accessibilityRole="button"
            accessibilityLabel="More"
            onPress={() => setMenuOpen((open) => !open)}
          >
            <Ionicons name="ellipsis-horizontal" size={22} color={colors.text} />
          </Pressable>
        </View>
      ) : null}
      {menuOpen ? (
        <View style={styles.menu}>
          {mine ? (
            <Button
              title="Delete post"
              variant="danger"
              onPress={() => void act(() => deletePost(post.id), 'Post deleted', true)}
            />
          ) : (
            <>
              <Button
                title="Show me less like this"
                variant="secondary"
                onPress={() =>
                  void act(() => hidePost(post.id), 'You will see less like this', true)
                }
              />
              <Button
                title="Report"
                variant="secondary"
                onPress={() =>
                  void act(
                    () => reportPost(post.id, { reason: 'OTHER' }),
                    'Reported. Thank you.',
                    false,
                  )
                }
              />
            </>
          )}
        </View>
      ) : null}
      {notice ? (
        <Text variant="caption" muted accessibilityRole="alert" testID="post-notice">
          {notice}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.sm },
  // A portrait clip would be taller than the window on a wide screen: cap its width instead.
  portrait: { maxWidth: 360, width: '100%', alignSelf: 'center' },
  pending: {
    width: '100%',
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  author: { minHeight: touchTarget, justifyContent: 'center' },
  bold: { fontWeight: '700' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
  },
  actions: { flexDirection: 'row', alignItems: 'center', gap: spacing.lg },
  action: {
    minHeight: touchTarget,
    minWidth: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  menu: { gap: spacing.sm },
});
