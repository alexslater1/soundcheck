import Ionicons from '@expo/vector-icons/Ionicons';
import { Image } from 'expo-image';
import { Link } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import type { Post } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { formatGigDate } from '@/features/gigs/format';
import { useTrack } from '@/features/telemetry/TelemetryScope';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

type Props = {
  post: Post;
  /** True when drawn over the clip: light text and translucent chips, whatever the theme. */
  onDark?: boolean;
  /** In the feed a tap on the caption opens the post; elsewhere it shows the whole caption. */
  onCaptionPress?: () => void;
  /** False where a ticket already shows the gig and its venue (the feed). */
  showGig?: boolean;
  /** The song in the clip in view, as its owner typed it. */
  song?: string | null;
};

const AVATAR = 32;

const LIGHT = '#FFFFFF';
const LIGHT_MUTED = 'rgba(255,255,255,0.8)';

/**
 * Who posted the clip, the song in the clip in view (typed by whoever filmed it), the people they
 * went with ("with @a, @b"; each opens their profile), the caption (two lines until tapped; in the
 * feed a tap opens the post), the gig (its date and venue), the venue and the artists. The author,
 * the gig, the venue and each artist are links; tapping the author or the gig is reported as a
 * signal of interest.
 */
export function PostMeta({ post, onDark = false, onCaptionPress, showGig = true, song }: Props) {
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
          {post.author.avatarUrl ? (
            <Image
              source={{ uri: post.author.avatarUrl }}
              style={styles.avatar}
              contentFit="cover"
              accessibilityIgnoresInvertColors
              testID="post-author-avatar"
            />
          ) : (
            <View style={[styles.avatar, styles.initial, { backgroundColor: colors.accent }]}>
              <Text style={[styles.bold, { color: colors.onAccent }]}>
                {post.author.displayName.slice(0, 1).toUpperCase()}
              </Text>
            </View>
          )}
          <View style={styles.names}>
            <Text style={[styles.bold, ink]} numberOfLines={1}>
              {post.author.displayName}
            </Text>
            <Text variant="caption" muted style={mutedInk} numberOfLines={1}>
              @{post.author.handle}
            </Text>
          </View>
        </Pressable>
      </Link>
      {post.caption ? (
        <Text
          testID="post-caption"
          style={ink}
          numberOfLines={expanded ? undefined : 2}
          onPress={onCaptionPress ?? (() => setExpanded((open) => !open))}
          accessibilityRole={onCaptionPress ? 'link' : undefined}
        >
          {post.caption}
        </Text>
      ) : null}
      {song ? (
        <View style={styles.with} testID="post-song">
          <Ionicons
            name="musical-notes-outline"
            size={16}
            color={onDark ? LIGHT_MUTED : colors.textMuted}
          />
          <Text
            variant="caption"
            style={[styles.bold, ink]}
            numberOfLines={1}
            accessibilityLabel={`Song: ${song}`}
          >
            {song}
          </Text>
        </View>
      ) : null}
      {post.withUsers.length > 0 ? (
        <View style={styles.with} testID="post-with">
          <Ionicons
            name="people-outline"
            size={16}
            color={onDark ? LIGHT_MUTED : colors.textMuted}
          />
          <Text variant="caption" style={mutedInk} muted={!onDark}>
            with
          </Text>
          {post.withUsers.map((user, i) => (
            <Link
              key={user.id}
              href={{ pathname: '/u/[handle]', params: { handle: user.handle } }}
              asChild
            >
              <Pressable accessibilityRole="link" testID={`post-with-${user.handle}`}>
                <Text variant="caption" style={[styles.bold, ink]}>
                  @{user.handle}
                  {i < post.withUsers.length - 1 ? ',' : ''}
                </Text>
              </Pressable>
            </Link>
          ))}
        </View>
      ) : null}
      {post.gig && showGig ? (
        <Link
          href={{ pathname: '/gig/[id]', params: { id: post.gig.id } }}
          onPress={() => track('GIG_TAP', post.id)}
          asChild
        >
          <Pressable accessibilityRole="link" style={styles.gig} testID="post-gig">
            <Ionicons
              name="calendar-outline"
              size={16}
              color={onDark ? LIGHT_MUTED : colors.accent}
            />
            <Text variant="caption" style={[styles.bold, ink]} numberOfLines={1}>
              {formatGigDate(post.gig.startsAt)} · {post.gig.title}
            </Text>
          </Pressable>
        </Link>
      ) : null}
      <View style={styles.chips}>
        {post.gig && showGig ? (
          <Link href={{ pathname: '/venue/[id]', params: { id: post.gig.venue.id } }} asChild>
            <Pressable accessibilityRole="link" style={chipStyle} testID="post-venue">
              <Ionicons
                name="location-outline"
                size={14}
                color={onDark ? LIGHT_MUTED : colors.textMuted}
              />
              <Text variant="caption" style={ink}>
                {post.gig.venue.name}
                {post.gig.venue.city ? `, ${post.gig.venue.city}` : ''}
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
              <Ionicons
                name="mic-outline"
                size={14}
                color={onDark ? LIGHT_MUTED : colors.textMuted}
              />
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
  author: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    alignSelf: 'flex-start',
  },
  avatar: { width: AVATAR, height: AVATAR, borderRadius: AVATAR / 2 },
  initial: { alignItems: 'center', justifyContent: 'center' },
  names: { flexShrink: 1 },
  bold: { fontWeight: '700' },
  gig: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs, minHeight: 28 },
  with: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: spacing.xs },
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
