import Ionicons from '@expo/vector-icons/Ionicons';
import { Image } from 'expo-image';
import { Link } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';

import type { Post } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

/** What a grid tile says about a post that is not (yet) playable; null for a published one. */
export function badgeFor(status: Post['status']): string | null {
  switch (status) {
    case 'PENDING_MEDIA':
      return 'Processing…';
    case 'FAILED':
      return 'Failed';
    case 'HIDDEN':
      return 'Hidden';
    default:
      return null;
  }
}

/**
 * A profile's posts as a three-column grid of thumbnails, one tile per post (its cover, with the number of
 * clips when there are several); a tile opens the post. With an act (an artist's page, a gig's
 * act), a tile shows that act's first clip and opens the post at it.
 */
export function PostGrid({ posts, actId }: { posts: Post[]; actId?: string }) {
  const { colors } = useTheme();
  return (
    <View style={styles.grid} testID="post-grid">
      {posts.map((post) => {
        const badge = badgeFor(post.status);
        const clip = actId ? post.clips.find((c) => c.act?.id === actId) : undefined;
        const playback = clip?.playback ?? post.playback;
        const params: { id: string; clip?: string } = clip
          ? { id: post.id, clip: clip.id }
          : { id: post.id };
        return (
          <Link key={post.id} href={{ pathname: '/post/[id]', params }} asChild>
            <Pressable
              // One flat object: expo-router's <Link asChild> rejects a style array on web.
              style={StyleSheet.flatten([styles.tile, { backgroundColor: colors.surface }])}
              accessibilityRole="link"
              accessibilityLabel={post.caption ? `Post: ${post.caption}` : 'Post'}
              testID="post-tile"
            >
              <Image
                source={playback.thumbUrl ? { uri: playback.thumbUrl } : null}
                placeholder={playback.blurhash ? { blurhash: playback.blurhash } : null}
                contentFit="cover"
                style={StyleSheet.absoluteFill}
              />
              {post.clipCount > 1 ? (
                <View
                  style={styles.count}
                  accessibilityLabel={`${post.clipCount} clips`}
                  testID="post-tile-clips"
                >
                  <Ionicons name="copy-outline" size={12} color="#FFFFFF" />
                  <Text variant="caption" style={styles.badgeText}>
                    {post.clipCount}
                  </Text>
                </View>
              ) : null}
              {badge ? (
                <View style={styles.badge}>
                  <Text variant="caption" style={styles.badgeText}>
                    {badge}
                  </Text>
                </View>
              ) : null}
            </Pressable>
          </Link>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  tile: {
    width: '32%',
    aspectRatio: 9 / 16,
    borderRadius: radius.sm,
    overflow: 'hidden',
    justifyContent: 'flex-end',
  },
  badge: { backgroundColor: 'rgba(0,0,0,0.6)', padding: spacing.xs },
  badgeText: { color: '#FFFFFF' },
  // A post of several clips: a stack and how many, in the top corner.
  count: {
    position: 'absolute',
    top: spacing.xs,
    right: spacing.xs,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 2,
    backgroundColor: 'rgba(0,0,0,0.6)',
    borderRadius: radius.pill,
    paddingHorizontal: spacing.xs,
  },
});
