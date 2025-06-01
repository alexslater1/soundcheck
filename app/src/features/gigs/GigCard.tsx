import { Image } from 'expo-image';
import { Link } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';

import type { GigSummary } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { dateParts, formatDistance, formatGigTime, formatPrice, statusLabel } from './format';

/** Wording for the viewer's own mark on a gig. */
const ATTENDANCE: Record<string, string> = {
  GOING: 'Going',
  INTERESTED: 'Interested',
  WENT: 'Went',
};

/**
 * One gig in a list: a date tile, who is playing, where, how far, how much, the event image, and
 * badges for the viewer's mark, an unusual status, or a gig added by fans. Opens the gig page.
 */
export function GigCard({ gig }: { gig: GigSummary }) {
  const { colors } = useTheme();
  const status = statusLabel(gig.status);
  const when = dateParts(gig.startsAt);
  const support = gig.support.map((a) => a.name).join(', ');
  const place = gig.venue.name + (gig.venue.city ? `, ${gig.venue.city}` : '');
  const facts = [
    when.time,
    formatDistance(gig.distanceKm),
    formatPrice(gig.priceMin, gig.priceMax, gig.currency),
  ].filter(Boolean);
  const mine = gig.myAttendance ? ATTENDANCE[gig.myAttendance] : null;

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
        <View style={[styles.tile, { backgroundColor: colors.accentSoft }]}>
          <Text variant="label" style={{ color: colors.accent }}>
            {when.weekday}
          </Text>
          <Text style={styles.tileDay}>{when.day}</Text>
          <Text variant="label" muted>
            {when.month}
          </Text>
        </View>
        <View style={styles.body}>
          <Text style={styles.title} numberOfLines={2}>
            {gig.title}
          </Text>
          {support ? (
            <Text variant="caption" muted numberOfLines={1}>
              with {support}
            </Text>
          ) : null}
          <Text variant="caption" numberOfLines={1}>
            {place}
          </Text>
          <Text variant="caption" muted numberOfLines={1}>
            {facts.join(' · ')}
          </Text>
          {mine || status || gig.userAdded ? (
            <View style={styles.badges}>
              {mine ? (
                <Badge label={mine} color={colors.text} background={colors.accent2Soft} />
              ) : null}
              {status ? (
                <Badge label={status} color={colors.text} background={colors.surfaceRaised} />
              ) : null}
              {gig.userAdded ? (
                <Badge
                  label="Added by fans"
                  color={colors.textMuted}
                  background={colors.surfaceRaised}
                />
              ) : null}
            </View>
          ) : null}
        </View>
        {gig.imageUrl ? (
          <Image
            source={{ uri: gig.imageUrl }}
            style={styles.image}
            contentFit="cover"
            transition={150}
            accessibilityIgnoresInvertColors
          />
        ) : null}
      </Pressable>
    </Link>
  );
}

function Badge({ label, color, background }: { label: string; color: string; background: string }) {
  return (
    <View style={[styles.badge, { backgroundColor: background }]}>
      <Text variant="label" style={{ color }}>
        {label.toUpperCase()}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    gap: spacing.md,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    padding: spacing.sm,
    marginBottom: spacing.sm,
    alignItems: 'center',
  },
  tile: {
    width: 56,
    alignSelf: 'stretch',
    minHeight: 72,
    borderRadius: radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tileDay: { fontSize: 24, lineHeight: 28, fontWeight: '800' },
  body: { flex: 1, gap: 2 },
  title: { fontSize: 17, lineHeight: 22, fontWeight: '700' },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: spacing.xs },
  badge: { borderRadius: radius.pill, paddingHorizontal: spacing.sm, paddingVertical: 2 },
  image: { width: 72, height: 72, borderRadius: radius.sm },
});
