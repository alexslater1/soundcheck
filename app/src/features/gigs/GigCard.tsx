import { Link } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { formatDistance, formatGigTime, formatPrice, statusLabel } from './format';

/** One gig in a list: when, who, where, how far, how much; opens the gig page. */
export function GigCard({ gig }: { gig: GigSummary }) {
  const { colors } = useTheme();
  const status = statusLabel(gig.status);
  const support = gig.support.map((a) => a.name).join(', ');
  const facts = [
    gig.venue.name + (gig.venue.city ? `, ${gig.venue.city}` : ''),
    formatDistance(gig.distanceKm),
    formatPrice(gig.priceMin, gig.priceMax, gig.currency),
  ].filter(Boolean);
  return (
    <Link href={`/gig/${gig.id}`} asChild>
      <Pressable
        testID="gig-card"
        accessibilityRole="link"
        accessibilityLabel={`${gig.title}, ${formatGigTime(gig.startsAt)}, ${gig.venue.name}`}
        // expo-router's <Link asChild> rejects a style array on its child: flatten it.
        style={StyleSheet.flatten([
          styles.card,
          { backgroundColor: colors.surface, borderColor: colors.border },
        ])}
      >
        <View style={styles.row}>
          <Text variant="label" style={{ color: colors.accent }}>
            {formatGigTime(gig.startsAt).toUpperCase()}
          </Text>
          {status ? (
            <Text variant="label" style={{ color: colors.warning }}>
              {status.toUpperCase()}
            </Text>
          ) : null}
          {gig.myAttendance ? (
            <Text variant="label" style={{ color: colors.success }}>
              {gig.myAttendance}
            </Text>
          ) : null}
        </View>
        <Text variant="title" numberOfLines={2}>
          {gig.title}
        </Text>
        {support ? (
          <Text muted numberOfLines={1}>
            with {support}
          </Text>
        ) : null}
        <Text variant="caption" muted numberOfLines={1}>
          {facts.join(' · ')}
        </Text>
      </Pressable>
    </Link>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.xs,
    marginBottom: spacing.sm,
  },
  row: { flexDirection: 'row', gap: spacing.sm, flexWrap: 'wrap' },
});
