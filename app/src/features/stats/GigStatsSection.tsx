import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import type { GigStats } from '@/api/generated/model';
import { useGetUserStats } from '@/api/generated/stats/stats';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { compareYears, count, seenLine, yearDelta } from './statsText';

/** How many acts and venues are listed at first, and after "Show all". */
export const TOP = 10;
export const ALL = 100;

/**
 * The stats section of a profile: totals, this year against last, the most-seen acts and
 * the most-visited venues, each line opening the gigs behind it. Counted by the server from the gigs
 * marked Went, every act on the bill included (support acts too).
 */
export function GigStatsSection({ userId, own }: { userId: string; own: boolean }) {
  const [showAll, setShowAll] = useState(false);
  const stats = useGetUserStats(userId, { top: showAll ? ALL : TOP });
  const heading = (
    <Text variant="label" muted>
      {own ? 'YOUR STATS' : 'STATS'}
    </Text>
  );

  if (stats.isPending) {
    return (
      <View style={styles.box} testID="stats-loading">
        {heading}
        <Skeleton height={72} />
        <Skeleton height={160} />
      </View>
    );
  }
  if (!stats.data) {
    return (
      <View style={styles.box}>
        {heading}
        <StateMessage title="Could not load the stats" onRetry={() => void stats.refetch()} />
      </View>
    );
  }
  const data = stats.data;
  if (data.totals.gigs === 0) {
    return (
      <View style={styles.box} testID="stats-empty">
        {heading}
        <Text muted>
          {own
            ? 'Mark the gigs you went to as Went and your stats appear here: the acts you have seen most and the venues you go back to.'
            : 'No gigs marked as Went yet.'}
        </Text>
      </View>
    );
  }
  const more = data.totals.acts > data.acts.length || data.totals.venues > data.venues.length;
  return (
    <View style={styles.box} testID="stats">
      {heading}
      <Totals stats={data} />
      <YearLine stats={data} />
      <YearBars stats={data} />
      <Text variant="label" muted style={styles.heading}>
        MOST SEEN
      </Text>
      {data.acts.map((act, index) => (
        <StatRow
          key={act.id}
          rank={index + 1}
          name={act.name}
          detail={seenLine(act.times, act.first, act.last)}
          times={act.times}
          imageUrl={act.imageUrl ?? null}
          href={statsGigsHref(userId, { artistId: act.id, title: act.name })}
          testID="stats-act"
        />
      ))}
      <Text variant="label" muted style={styles.heading}>
        MOST VISITED
      </Text>
      {data.venues.map((venue, index) => (
        <StatRow
          key={venue.id}
          rank={index + 1}
          name={venue.city ? `${venue.name}, ${venue.city.trim()}` : venue.name}
          detail={seenLine(venue.times, venue.first, venue.last)}
          times={venue.times}
          imageUrl={null}
          href={statsGigsHref(userId, { venueId: venue.id, title: venue.name })}
          testID="stats-venue"
        />
      ))}
      {more && !showAll ? (
        <Button
          title="Show all acts and venues"
          variant="secondary"
          onPress={() => setShowAll(true)}
        />
      ) : null}
    </View>
  );
}

export type StatsGigsHref = {
  pathname: '/stats/[userId]';
  params: { userId: string; title: string; artistId?: string; venueId?: string };
};

/** Where a line of the stats leads: the gigs with that act, or at that venue. */
export function statsGigsHref(
  userId: string,
  line: { artistId?: string; venueId?: string; title: string },
): StatsGigsHref {
  return { pathname: '/stats/[userId]', params: { userId, ...line } };
}

function Totals({ stats }: { stats: GigStats }) {
  const tiles: [number, string][] = [
    [stats.totals.gigs, stats.totals.gigs === 1 ? 'gig' : 'gigs'],
    [stats.totals.acts, stats.totals.acts === 1 ? 'act' : 'acts'],
    [stats.totals.venues, stats.totals.venues === 1 ? 'venue' : 'venues'],
    [stats.totals.cities, stats.totals.cities === 1 ? 'city' : 'cities'],
  ];
  return (
    <View style={styles.tiles}>
      {tiles.map(([value, label]) => (
        <Tile key={label} value={value} label={label} />
      ))}
    </View>
  );
}

function Tile({ value, label }: { value: number; label: string }) {
  const { colors } = useTheme();
  return (
    <View
      style={[styles.tile, { backgroundColor: colors.accentSoft }]}
      accessible
      accessibilityLabel={`${value} ${label}`}
      testID={`stats-total-${label}`}
    >
      <Text variant="title">{value}</Text>
      <Text variant="caption" muted>
        {label}
      </Text>
    </View>
  );
}

function YearLine({ stats }: { stats: GigStats }) {
  const comparison = compareYears(stats.currentYear, stats.years);
  if (!comparison) return null;
  return (
    <Text muted testID="stats-year">
      {`${count(comparison.thisGigs, 'gig')} in ${comparison.thisYear} so far · ${yearDelta(comparison)}`}
    </Text>
  );
}

/**
 * Gigs per year as thin horizontal bars, newest first, each as long as its share of the busiest year,
 * with the count written beside it (the text is the value; the bar only shows the shape). Left out with
 * a single year: there is nothing to compare.
 */
function YearBars({ stats }: { stats: GigStats }) {
  const { colors } = useTheme();
  if (stats.years.length < 2) return null;
  const busiest = Math.max(...stats.years.map((y) => y.gigs));
  return (
    <View style={styles.years}>
      <Text variant="label" muted style={styles.heading}>
        BY YEAR
      </Text>
      {stats.years.map((y) => (
        <View
          key={y.year}
          style={styles.yearRow}
          accessible
          accessibilityLabel={`${y.year}${y.year === stats.currentYear ? ' so far' : ''}: ${count(y.gigs, 'gig')}`}
          testID="stats-year-row"
        >
          <Text variant="caption" muted style={styles.yearLabel}>
            {y.year}
          </Text>
          <View style={styles.track}>
            <View
              testID="stats-year-bar"
              style={[
                styles.bar,
                { width: `${(y.gigs / busiest) * 100}%`, backgroundColor: colors.accent },
              ]}
            />
          </View>
          <Text variant="caption" style={styles.yearCount}>
            {y.gigs}
          </Text>
        </View>
      ))}
    </View>
  );
}

function StatRow({
  rank,
  name,
  detail,
  times,
  imageUrl,
  href,
  testID,
}: {
  rank: number;
  name: string;
  detail: string;
  times: number;
  imageUrl: string | null;
  href: StatsGigsHref;
  testID: string;
}) {
  const { colors } = useTheme();
  const router = useRouter();
  return (
    <Pressable
      onPress={() => router.push(href)}
      accessibilityRole="link"
      accessibilityLabel={`${name}: ${count(times, 'time')}. ${detail}`}
      style={styles.row}
      testID={testID}
    >
      <Text variant="caption" muted style={styles.rank}>
        {rank}
      </Text>
      <View style={[styles.avatar, { backgroundColor: colors.accentSoft }]}>
        {imageUrl ? (
          <Image
            source={{ uri: imageUrl }}
            style={StyleSheet.absoluteFill}
            contentFit="cover"
            transition={150}
            accessibilityIgnoresInvertColors
          />
        ) : (
          <Text style={{ fontWeight: '800', color: colors.accent }}>
            {name.slice(0, 1).toUpperCase()}
          </Text>
        )}
      </View>
      <View style={styles.text}>
        <Text numberOfLines={1} style={{ fontWeight: '600' }}>
          {name}
        </Text>
        <Text variant="caption" muted numberOfLines={1}>
          {detail}
        </Text>
      </View>
      <View style={[styles.times, { borderColor: colors.border }]}>
        <Text style={{ fontWeight: '700', color: colors.accent2 }}>{`${times}×`}</Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  heading: { marginTop: spacing.sm },
  tiles: { flexDirection: 'row', gap: spacing.sm },
  tile: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    minHeight: touchTarget + spacing.sm,
    paddingVertical: spacing.xs,
  },
  rank: { width: 20, textAlign: 'right' },
  years: { gap: spacing.xs },
  yearRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 24 },
  yearLabel: { width: 40 },
  track: { flex: 1 },
  // A thin bar anchored at the left, its far end rounded (4 px).
  bar: { height: 10, minWidth: 4, borderTopRightRadius: 4, borderBottomRightRadius: 4 },
  yearCount: { width: 32, textAlign: 'right' },
  avatar: {
    width: touchTarget,
    height: touchTarget,
    borderRadius: touchTarget / 2,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  text: { flex: 1 },
  times: {
    minWidth: touchTarget,
    alignItems: 'center',
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
  },
});
