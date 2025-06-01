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
import { PagedGigs } from '@/features/gigs/PagedGigs';
import { PostGrid } from '@/features/posts/PostGrid';
import { openWebLink } from '@/lib/openLink';
import { spacing } from '@/theme/tokens';

/**
 * Venue page: address, a link out to a maps app (maps are out of scope), upcoming gigs
 * (more on request) and the clips filmed there.
 */
export function VenueScreen({ id }: { id: string }) {
  const venue = useGetVenue(id);
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
          queryKey={getListVenueGigsQueryKey(id)}
          fetchPage={(cursor) => listVenueGigs(id, { limit: 10, ...(cursor ? { cursor } : {}) })}
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
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  heading: { marginTop: spacing.md },
});
