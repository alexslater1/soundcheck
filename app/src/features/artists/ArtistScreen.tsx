import { Image, ScrollView, StyleSheet, View } from 'react-native';

import {
  getListArtistGigsQueryKey,
  listArtistGigs,
  useGetArtist,
} from '@/api/generated/catalog/catalog';
import { useListArtistPosts } from '@/api/generated/posts/posts';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { NotifyBell } from '@/features/follows/NotifyBell';
import { PagedGigs } from '@/features/gigs/PagedGigs';
import { useDistanceOrigin } from '@/features/location/deviceLocation';
import { PostGrid } from '@/features/posts/PostGrid';
import { openWebLink } from '@/lib/openLink';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { FollowButton } from './FollowButton';
import { useGenreNames } from './useGenreNames';

/** Artist page: genres, follower count, follow, upcoming gigs (more on request), clips. */
export function ArtistScreen({ id }: { id: string }) {
  const { colors } = useTheme();
  const genreNames = useGenreNames();
  const origin = useDistanceOrigin();
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
        <View style={styles.follow}>
          <View style={styles.followButton}>
            <FollowButton
              artistId={artist.id}
              artistName={artist.name}
              isFollowing={artist.isFollowing}
            />
          </View>
          {/* While following, the bell sets what you hear about this act. */}
          {data.notifyLevel ? (
            <NotifyBell kind="artist" id={artist.id} name={artist.name} level={data.notifyLevel} />
          ) : null}
        </View>
        <Text variant="label" muted style={styles.heading}>
          UPCOMING GIGS
        </Text>
        <PagedGigs
          queryKey={getListArtistGigsQueryKey(id, origin.params)}
          enabled={origin.ready}
          fetchPage={(cursor) =>
            listArtistGigs(id, { limit: 10, ...origin.params, ...(cursor ? { cursor } : {}) })
          }
          empty="No upcoming gigs listed."
          testID="artist-gigs"
        />
        <Text variant="label" muted style={styles.heading}>
          CLIPS
        </Text>
        <ArtistClips artistId={id} name={artist.name} />
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
  follow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  followButton: { flex: 1 },
});

/**
 * Clips of this artist on stage (each clip's act), newest first; a tile opens at the act's clip.
 */
function ArtistClips({ artistId, name }: { artistId: string; name: string }) {
  const posts = useListArtistPosts(artistId, { limit: 12 });
  if (posts.isPending) {
    return <Skeleton height={120} />;
  }
  if (!posts.data) {
    return <StateMessage title="Could not load clips" onRetry={() => void posts.refetch()} />;
  }
  if (posts.data.items.length === 0) {
    return <Text muted>No clips of {name} yet.</Text>;
  }
  return <PostGrid posts={posts.data.items} actId={artistId} />;
}
