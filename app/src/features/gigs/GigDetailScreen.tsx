import { Link } from 'expo-router';
import { Image, ScrollView, StyleSheet, View } from 'react-native';

import { useGetGig } from '@/api/generated/catalog/catalog';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { ArtistRow } from '@/features/artists/ArtistRow';
import { useGenreNames } from '@/features/artists/useGenreNames';
import { isWebUrl, openWebLink } from '@/lib/openLink';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { AttendanceButtons } from './AttendanceButtons';
import { formatDistance, formatGigTime, formatPrice, statusLabel, ticketLabel } from './format';

/** Gig page: line-up, venue, local date and time, price, attendance, ticket link out. */
export function GigDetailScreen({ id }: { id: string }) {
  const { colors } = useTheme();
  const genreNames = useGenreNames();
  const { data, isPending, refetch } = useGetGig(id);

  if (isPending) {
    return (
      <Screen>
        <View style={styles.gap} testID="gig-loading">
          <Skeleton height={28} width="70%" />
          <Skeleton height={18} width="50%" />
          <Skeleton height={120} />
        </View>
      </Screen>
    );
  }
  // A failed background refetch keeps the data it already had; the error screen is for having nothing.
  if (!data) {
    return (
      <Screen>
        <StateMessage
          testID="gig-error"
          title="Could not load this gig"
          detail="It may have been removed, or you may be offline."
          onRetry={() => void refetch()}
        />
      </Screen>
    );
  }

  const { gig } = data;
  const status = statusLabel(gig.status);
  const price = formatPrice(gig.priceMin, gig.priceMax, gig.currency);
  const distance = formatDistance(gig.distanceKm);
  const ticketUrl = isWebUrl(gig.ticketUrl) ? gig.ticketUrl : null;
  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.gap}>
        {gig.imageUrl ? (
          <Image
            source={{ uri: gig.imageUrl }}
            accessibilityIgnoresInvertColors
            style={[styles.image, { backgroundColor: colors.surfaceRaised }]}
          />
        ) : null}
        <Text variant="display">{gig.title}</Text>
        {status ? (
          <Text variant="label" style={{ color: colors.warning }}>
            {status.toUpperCase()}
          </Text>
        ) : null}
        <Text testID="gig-time">{formatGigTime(gig.startsAt)}</Text>
        <Link href={`/venue/${gig.venue.id}`}>
          <Text style={{ color: colors.accent }}>
            {gig.venue.name}
            {gig.venue.city ? `, ${gig.venue.city}` : ''}
          </Text>
        </Link>
        <Text variant="caption" muted>
          {[distance ? `${distance} from your city` : null, price].filter(Boolean).join(' · ') ||
            'Price not listed'}
        </Text>

        <AttendanceButtons detail={data} />

        {ticketUrl ? (
          <Button
            title={ticketLabel(data.sources)}
            accessibilityRole="link"
            onPress={() => void openWebLink(ticketUrl)}
          />
        ) : null}

        <Text variant="label" muted style={styles.heading}>
          LINE-UP
        </Text>
        {data.lineup.map((artist) => (
          <ArtistRow key={artist.id} artist={artist} genreNames={genreNames} />
        ))}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  gap: { gap: spacing.sm, paddingBottom: spacing.xl },
  image: { width: '100%', aspectRatio: 16 / 9, borderRadius: radius.md },
  heading: { marginTop: spacing.md },
});
