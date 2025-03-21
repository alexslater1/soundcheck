import { ScrollView, StyleSheet, View } from 'react-native';

import { useGetVenue, useListVenueGigs } from '@/api/generated/catalog/catalog';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { GigCard } from '@/features/gigs/GigCard';
import { openWebLink } from '@/lib/openLink';
import { spacing } from '@/theme/tokens';

/** Venue page: address, a link out to a maps app (maps are out of scope), upcoming gigs. */
export function VenueScreen({ id }: { id: string }) {
  const venue = useGetVenue(id);
  const gigs = useListVenueGigs(id, { limit: 20 });

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
        {gigs.isPending ? (
          <Skeleton height={96} />
        ) : !gigs.data ? (
          <StateMessage title="Could not load gigs" onRetry={() => void gigs.refetch()} />
        ) : gigs.data.items.length === 0 ? (
          <Text muted testID="venue-no-gigs">
            No upcoming gigs listed.
          </Text>
        ) : (
          gigs.data.items.map((gig) => <GigCard key={gig.id} gig={gig} />)
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  heading: { marginTop: spacing.md },
});
