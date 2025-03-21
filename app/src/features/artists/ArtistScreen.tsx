import { Image, ScrollView, StyleSheet, View } from 'react-native';

import { useGetArtist } from '@/api/generated/catalog/catalog';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { GigCard } from '@/features/gigs/GigCard';
import { openWebLink } from '@/lib/openLink';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { FollowButton } from './FollowButton';
import { useGenreNames } from './useGenreNames';

/** Artist page: genres, follower count, follow, upcoming gigs. */
export function ArtistScreen({ id }: { id: string }) {
  const { colors } = useTheme();
  const genreNames = useGenreNames();
  const { data, isPending, refetch } = useGetArtist(id);

  if (isPending) {
    return (
      <Screen>
        <View style={styles.gap} testID="artist-loading">
          <Skeleton height={32} width="60%" />
          <Skeleton height={18} width="40%" />
          <Skeleton height={96} />
        </View>
      </Screen>
    );
  }
  if (!data) {
    return (
      <Screen>
        <StateMessage
          testID="artist-error"
          title="Could not load this artist"
          onRetry={() => void refetch()}
        />
      </Screen>
    );
  }

  const { artist } = data;
  const genres = artist.genres.map((slug) => genreNames.get(slug) ?? slug);
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.gap}>
        {artist.imageUrl ? (
          <Image
            source={{ uri: artist.imageUrl }}
            accessibilityIgnoresInvertColors
            style={[styles.image, { backgroundColor: colors.surfaceRaised }]}
          />
        ) : null}
        <Text variant="display">{artist.name}</Text>
        <Text muted>
          {genres.length > 0 ? genres.join(' · ') : 'No genres yet'} · {data.followerCount}{' '}
          {data.followerCount === 1 ? 'follower' : 'followers'}
        </Text>
        <FollowButton
          artistId={artist.id}
          artistName={artist.name}
          isFollowing={artist.isFollowing}
        />
        <Text variant="label" muted style={styles.heading}>
          UPCOMING GIGS
        </Text>
        {data.upcomingGigs.length === 0 ? (
          <Text muted testID="artist-no-gigs">
            No upcoming gigs listed.
          </Text>
        ) : (
          data.upcomingGigs.map((gig) => <GigCard key={gig.id} gig={gig} />)
        )}
        {/* Last.fm asks for attribution wherever its data is shown (API terms §2.7). */}
        <Text
          variant="caption"
          muted
          accessibilityRole="link"
          onPress={() => void openWebLink('https://www.last.fm')}
          style={styles.credit}
        >
          Genre tags from Last.fm · artist ids from MusicBrainz
        </Text>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  image: { width: '100%', aspectRatio: 16 / 9, borderRadius: radius.md },
  heading: { marginTop: spacing.md },
  credit: { marginTop: spacing.md },
});
