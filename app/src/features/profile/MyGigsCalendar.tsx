import { useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { useListMyGigs } from '@/api/generated/catalog/catalog';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { MonthCalendar } from '@/features/calendar/MonthCalendar';
import { monthOf, monthWindow, ukDate, type Month } from '@/features/calendar/monthGrid';
import { formatGigDate } from '@/features/gigs/format';
import { GigCard } from '@/features/gigs/GigCard';
import { useDistanceOrigin } from '@/features/location/deviceLocation';
import { spacing } from '@/theme/tokens';

import { byDay } from './byDay';

/**
 * The Me tab's My gigs: every gig the user marked (Going, Interested or Went), past and
 * upcoming, on a month grid; a day's gigs are listed under it when tapped, otherwise the month's. H44's Pending list
 * (gigs the user went to without posting) lives on Create, not here (J6).
 */
export function MyGigsCalendar() {
  const [month, setMonth] = useState<Month>(() => monthOf(new Date()));
  const [day, setDay] = useState<string | null>(null);
  // A month of one person's gigs fits in one page (50); more than that shows the first 50.
  const origin = useDistanceOrigin(); // distances from the phone when allowed
  const gigs = useListMyGigs(
    { ...monthWindow(month), limit: 50, ...origin.params },
    { query: { enabled: origin.ready } },
  );
  const days = useMemo(() => byDay(gigs.data?.items ?? []), [gigs.data]);
  const counts = useMemo(
    () => Object.fromEntries(Object.entries(days).map(([date, list]) => [date, list.length])),
    [days],
  );
  const shown = day ? (days[day] ?? []) : Object.values(days).flat();

  return (
    <View style={styles.box} testID="my-gigs">
      <MonthCalendar
        month={month}
        onMonthChange={(next) => {
          setMonth(next);
          setDay(null);
        }}
        counts={counts}
        selected={day}
        onSelect={setDay}
        today={ukDate(new Date())}
        loading={gigs.isFetching}
        testID="my-gigs-calendar"
      />
      {gigs.isPending ? (
        <Skeleton height={96} />
      ) : gigs.isError && !gigs.data ? (
        <StateMessage title="Could not load your gigs" onRetry={() => void gigs.refetch()} />
      ) : shown.length === 0 ? (
        <Text muted testID="my-gigs-empty">
          {day
            ? 'No gigs marked on this day.'
            : 'No gigs this month. Mark gigs as Going or Went and they appear here.'}
        </Text>
      ) : (
        <>
          {day ? (
            <Text variant="label" muted>
              {formatGigDate(`${day}T12:00:00Z`).toUpperCase()}
            </Text>
          ) : null}
          {shown.map((gig) => (
            <GigCard key={gig.id} gig={gig} />
          ))}
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
});
