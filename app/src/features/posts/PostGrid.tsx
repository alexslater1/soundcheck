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

/** A profile's clips as a three-column grid of thumbnails; a tile opens the post. */
export function PostGrid({ posts }: { posts: Post[] }) {
  const { colors } = useTheme();
  return (
    <View style={styles.grid} testID="post-grid">
      {posts.map((post) => {
        const badge = badgeFor(post.status);
        return (
          <Link key={post.id} href={{ pathname: '/post/[id]', params: { id: post.id } }} asChild>
            <Pressable
              // One flat object: expo-router's <Link asChild> rejects a style array on web.
              style={StyleSheet.flatten([styles.tile, { backgroundColor: colors.surface }])}
              accessibilityRole="link"
              accessibilityLabel={post.caption ? `Post: ${post.caption}` : 'Post'}
              testID="post-tile"
            >
              <Image
                source={post.playback.thumbUrl ? { uri: post.playback.thumbUrl } : null}
                placeholder={post.playback.blurhash ? { blurhash: post.playback.blurhash } : null}
                contentFit="cover"
                style={StyleSheet.absoluteFill}
              />
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
});
