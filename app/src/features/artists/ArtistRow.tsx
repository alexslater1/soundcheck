import { Image } from 'expo-image';
import { Link } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';

import type { ArtistSummary } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing, touchTarget } from '@/theme/tokens';

import { FollowButton } from './FollowButton';

/**
 * An artist in a list: photo (or initial), name, genres, and a follow button; the name opens the
 * artist page.
 */
export function ArtistRow({
  artist,
  genreNames,
  linked = true,
}: {
  artist: ArtistSummary;
  /** slug → display name, from `GET /genres`. */
  genreNames: ReadonlyMap<string, string>;
  linked?: boolean;
}) {
  const { colors } = useTheme();
  const genres = artist.genres.map((slug) => genreNames.get(slug) ?? slug).join(' · ');
  const label = (
    <View style={styles.label}>
      <View style={[styles.avatar, { backgroundColor: colors.accentSoft }]}>
        {artist.imageUrl ? (
          <Image
            source={{ uri: artist.imageUrl }}
            style={StyleSheet.absoluteFill}
            contentFit="cover"
            transition={150}
            accessibilityIgnoresInvertColors
          />
        ) : (
          <Text style={{ fontWeight: '800', color: colors.accent }}>
            {artist.name.slice(0, 1).toUpperCase()}
          </Text>
        )}
      </View>
      <View style={styles.text}>
        <Text numberOfLines={1} style={{ fontWeight: '600' }}>
          {artist.name}
        </Text>
        {genres ? (
          <Text variant="caption" muted numberOfLines={1}>
            {genres}
          </Text>
        ) : null}
      </View>
    </View>
  );
  return (
    <View style={styles.row} testID="artist-row">
      {linked ? (
        <Link href={`/artist/${artist.id}`} asChild>
          <Pressable accessibilityRole="link" style={styles.fill}>
            {label}
          </Pressable>
        </Link>
      ) : (
        <View style={styles.fill}>{label}</View>
      )}
      <FollowButton
        artistId={artist.id}
        artistName={artist.name}
        isFollowing={artist.isFollowing}
        compact
      />
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    minHeight: touchTarget + spacing.sm,
    paddingVertical: spacing.xs,
  },
  fill: { flex: 1 },
  label: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  avatar: {
    width: touchTarget,
    height: touchTarget,
    borderRadius: touchTarget / 2,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  text: { flex: 1 },
});
