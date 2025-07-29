import { ScrollView, StyleSheet, View } from 'react-native';

import {
  getListVenueGigsQueryKey,
  listVenueGigs,
  useGetVenue,
} from '@/api/generated/catalog/catalog';
import { useListVenuePosts } from '@/api/generated/posts/posts';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { NotifyBell } from '@/features/follows/NotifyBell';
import { PagedGigs } from '@/features/gigs/PagedGigs';
import { useDistanceOrigin } from '@/features/location/deviceLocation';
import { PostGrid } from '@/features/posts/PostGrid';
import { openWebLink } from '@/lib/openLink';
import { spacing } from '@/theme/tokens';

import { CoverageNote } from './CoverageNote';
import { VenueFollowButton } from './VenueFollowButton';

/**
 * Venue page: address, a link out to a maps app (maps are out of scope), upcoming gigs
 * (more on request) and the clips filmed there.
 */
export function VenueScreen({ id }: { id: string }) {
  const venue = useGetVenue(id);
  const origin = useDistanceOrigin();
  const clips = useListVenuePosts(id, { limit: 12 });

  if (venue.isPending) {
    return (
      <Screen>
        <View style={styles.gap} testID="venue-loading">
          <Skeleton height={32} width="60%" />
          <Skeleton height={18} width="80%" />
          <Skeleton height={96} />
        </View>
      </Screen>
    );
  }
  if (!venue.data) {
    return (
      <Screen>
        <StateMessage
          testID="venue-error"
          title="Could not load this venue"
          onRetry={() => void venue.refetch()}
        />
      </Screen>
    );
  }

  const v = venue.data;
  const address = [v.address, v.city, v.postcode].filter(Boolean).join(', ');
  const mapsQuery = v.location
    ? `${v.location.lat},${v.location.lng}`
    : encodeURIComponent(`${v.name} ${address}`);
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.gap}>
        <Text variant="display">{v.name}</Text>
        <Text muted>{address || 'Address not listed'}</Text>
        <CoverageNote note={v.coverageNote} />
        <View style={styles.follow}>
          <View style={styles.followRow}>
            <View style={styles.followButton}>
              <VenueFollowButton venueId={v.id} venueName={v.name} isFollowing={v.following} />
            </View>
            {/* While following, the bell sets what you hear about this venue. */}
            {v.notifyLevel ? (
              <NotifyBell kind="venue" id={v.id} name={v.name} level={v.notifyLevel} />
            ) : null}
          </View>
          <Text variant="caption" muted testID="venue-followers">
            {v.followerCount === 1 ? '1 follower' : `${v.followerCount} followers`} · the bell sets
            what reaches you in Activity
          </Text>
        </View>
        <Button
          title="Open in Maps"
          variant="secondary"
          accessibilityRole="link"
          onPress={() =>
            void openWebLink(`https://www.google.com/maps/search/?api=1&query=${mapsQuery}`)
          }
        />
        <Text variant="label" muted style={styles.heading}>
          UPCOMING GIGS
        </Text>
        <PagedGigs
          queryKey={getListVenueGigsQueryKey(id, origin.params)}
          enabled={origin.ready}
          fetchPage={(cursor) =>
            listVenueGigs(id, { limit: 10, ...origin.params, ...(cursor ? { cursor } : {}) })
          }
          empty="No upcoming gigs listed."
          testID="venue-gigs"
        />
        <Text variant="label" muted style={styles.heading}>
          CLIPS FILMED HERE
        </Text>
        {clips.isPending ? (
          <Skeleton height={120} />
        ) : !clips.data ? (
          <StateMessage title="Could not load clips" onRetry={() => void clips.refetch()} />
        ) : clips.data.items.length === 0 ? (
          <Text muted>No clips from here yet.</Text>
        ) : (
          <PostGrid posts={clips.data.items} />
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  follow: { gap: spacing.xs },
  followRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  followButton: { flex: 1 },
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  heading: { marginTop: spacing.md },
});
