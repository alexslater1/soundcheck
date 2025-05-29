import Ionicons from '@expo/vector-icons/Ionicons';
import { Link } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import type { Post } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

type Props = {
  post: Post;
  /** True when drawn over the clip: light text and translucent chips, whatever the theme. */
  onDark?: boolean;
};

const LIGHT = '#FFFFFF';
const LIGHT_MUTED = 'rgba(255,255,255,0.8)';

/**
 * Who posted the clip, the caption (two lines until tapped), the gig and the artists. The author and
 * the gig are links, and tapping them is reported as a signal of interest.
 */
export function PostMeta({ post, onDark = false }: Props) {
  const { colors } = useTheme();
  const track = useTrack();
  const [expanded, setExpanded] = useState(false);
  const ink = onDark ? { color: LIGHT } : undefined;
  const mutedInk = onDark ? { color: LIGHT_MUTED } : undefined;
  // One flat object: expo-router's <Link asChild> rejects a style array on web.
  const chipStyle = StyleSheet.flatten([
    styles.chip,
    { backgroundColor: onDark ? 'rgba(255,255,255,0.18)' : colors.surfaceRaised },
  ]);

  return (
    <View style={styles.meta}>
      <Link
        href={{ pathname: '/u/[handle]', params: { handle: post.author.handle } }}
        onPress={() => track('PROFILE_TAP', post.id)}
        asChild
      >
        <Pressable accessibilityRole="link" style={styles.author}>
          <Text style={[styles.bold, ink]}>{post.author.displayName}</Text>
          <Text muted style={mutedInk}>
            @{post.author.handle}
          </Text>
        </Pressable>
      </Link>
      {post.caption ? (
        <Text
          testID="post-caption"
          style={ink}
          numberOfLines={expanded ? undefined : 2}
          onPress={() => setExpanded((open) => !open)}
        >
          {post.caption}
        </Text>
      ) : null}
      <View style={styles.chips}>
        {post.gig ? (
          <Link
            href={{ pathname: '/gig/[id]', params: { id: post.gig.id } }}
            onPress={() => track('GIG_TAP', post.id)}
            asChild
          >
            <Pressable accessibilityRole="link" style={chipStyle}>
              <Ionicons
                name="calendar-outline"
                size={14}
                color={onDark ? LIGHT_MUTED : colors.textMuted}
              />
              <Text variant="caption" style={ink}>
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
              <Text variant="caption" style={ink}>
                {artist.name}
              </Text>
            </Pressable>
          </Link>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  meta: { gap: spacing.sm },
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
});
