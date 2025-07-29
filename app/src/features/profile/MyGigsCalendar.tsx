import { useInfiniteQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { getListMyGigsQueryKey, listMyGigs, useListMyGigs } from '@/api/generated/catalog/catalog';
import type { ListMyGigsParams } from '@/api/generated/model';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { IconButton } from '@/components/ui/IconButton';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { MonthCalendar } from '@/features/calendar/MonthCalendar';
import {
  monthOf,
  monthTitle,
  monthWindow,
  ukDate,
  type Month,
} from '@/features/calendar/monthGrid';
import { formatGigDate } from '@/features/gigs/format';
import { GigCard } from '@/features/gigs/GigCard';
import { useDistanceOrigin } from '@/features/location/deviceLocation';
import { spacing } from '@/theme/tokens';

import { byDay } from './byDay';
import { byMonth } from './byMonth';

/** Which of the user's gigs: every one (the default), the ones to come, or the ones that have been. */
export type When = 'all' | 'upcoming' | 'past';

const WHEN: readonly { value: When; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'upcoming', label: 'Upcoming' },
  { value: 'past', label: 'Past' },
];

const EMPTY: Record<When, string> = {
  all: 'No gigs yet. Mark gigs as Going or Went and they appear here.',
  upcoming: 'No gigs to come. Mark gigs as Going or Interested and they appear here.',
  past: 'No past gigs yet. Mark the gigs you went to and they appear here.',
};

/**
 * The Me tab's My gigs: every gig the user marked (Going, Interested or Went), newest first, under
 * month headers, a page at a time. All / Upcoming / Past narrow it. The calendar button opens a month
 * grid: the list then shows that month, a tapped day narrows it to the day, and the month's chip
 * clears back to all time. The Pending list lives on Create, not here.
 */
export function MyGigsCalendar() {
  const [when, setWhen] = useState<When>('all');
  const [month, setMonth] = useState<Month | null>(null);
  const [day, setDay] = useState<string | null>(null);
  const clearMonth = () => {
    setMonth(null);
    setDay(null);
  };

  return (
    <View style={styles.box} testID="my-gigs">
      <View style={styles.bar}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chips}>
          {WHEN.map((option) => (
            <Chip
              key={option.value}
              label={option.label}
              selected={when === option.value}
              onPress={() => {
                setWhen(option.value);
                setDay(null);
              }}
            />
          ))}
        </ScrollView>
        <IconButton
          icon={month ? 'calendar' : 'calendar-outline'}
          accessibilityLabel={month ? 'Show all months' : 'Pick a month'}
          accessibilityState={{ expanded: month !== null }}
          onPress={() => (month ? clearMonth() : setMonth(monthOf(new Date())))}
          testID="my-gigs-calendar-button"
        />
      </View>
      {month ? (
        <MonthGigs
          when={when}
          month={month}
          day={day}
          onMonthChange={(next) => {
            setMonth(next);
            setDay(null);
          }}
          onDayChange={setDay}
          onClear={clearMonth}
        />
      ) : (
        <AllGigs when={when} />
      )}
    </View>
  );
}

/** Every gig of the chosen kind, newest first under month headers; "Show more gigs" loads the next page. */
function AllGigs({ when }: { when: When }) {
  const origin = useDistanceOrigin(); // distances from the phone when allowed
  const params: ListMyGigsParams = { when, limit: 20, ...origin.params };
  const list = useInfiniteQuery({
    // The generated key starts with '/api/v1/me/gigs', so refreshOwnGigs refreshes this list too.
    queryKey: [...getListMyGigsQueryKey(params), 'infinite'],
    queryFn: ({ pageParam }) => listMyGigs({ ...params, cursor: pageParam }),
    enabled: origin.ready,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const groups = useMemo(
    () => byMonth(list.data?.pages.flatMap((page) => page.items) ?? []),
    [list.data],
  );
  if (list.isPending) {
    return <Skeleton height={96} />;
  }
  if (!list.data) {
    return <StateMessage title="Could not load your gigs" onRetry={() => void list.refetch()} />;
  }
  if (groups.length === 0) {
    return (
      <Text muted testID="my-gigs-empty">
        {EMPTY[when]}
      </Text>
    );
  }
  return (
    <View style={styles.box} testID="my-gigs-all">
      {groups.map((group) => (
        <View key={group.key} style={styles.box}>
          <Text variant="label" muted testID={`my-gigs-month-${group.key}`}>
            {group.title.toUpperCase()}
          </Text>
          {group.gigs.map((gig) => (
            <GigCard key={gig.id} gig={gig} />
          ))}
        </View>
      ))}
      {list.hasNextPage ? (
        <Button
          title={list.isFetchingNextPage ? 'Loading…' : 'Show more gigs'}
          variant="secondary"
          disabled={list.isFetchingNextPage}
          onPress={() => void list.fetchNextPage()}
          testID="my-gigs-more"
        />
      ) : null}
      {list.isError ? (
        <StateMessage title="Could not load more gigs" onRetry={() => void list.fetchNextPage()} />
      ) : null}
    </View>
  );
}

/** One month on the grid and its gigs (newest first); a tapped day narrows the list to that day. */
function MonthGigs({
  when,
  month,
  day,
  onMonthChange,
  onDayChange,
  onClear,
}: {
  when: When;
  month: Month;
  day: string | null;
  onMonthChange: (month: Month) => void;
  onDayChange: (day: string | null) => void;
  onClear: () => void;
}) {
  const origin = useDistanceOrigin();
  // A month of one person's gigs fits in one page (50); more than that shows the first 50.
  const gigs = useListMyGigs(
    { ...monthWindow(month), when, limit: 50, ...origin.params },
    { query: { enabled: origin.ready } },
  );
  const items = useMemo(() => gigs.data?.items ?? [], [gigs.data]);
  const counts = useMemo(
    () =>
      Object.fromEntries(Object.entries(byDay(items)).map(([date, list]) => [date, list.length])),
    [items],
  );
  const shown = day ? items.filter((gig) => ukDate(gig.startsAt) === day) : items;

  return (
    <View style={styles.box}>
      <View style={styles.filters}>
        <Chip
          icon="close"
          label={day ? formatGigDate(`${day}T12:00:00Z`) : monthTitle(month)}
          selected
          onPress={day ? () => onDayChange(null) : onClear}
        />
        <Text variant="caption" muted>
          {day ? 'Tap to see the whole month' : 'Tap to see all time'}
        </Text>
      </View>
      <MonthCalendar
        month={month}
        onMonthChange={onMonthChange}
        counts={counts}
        selected={day}
        onSelect={onDayChange}
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
          {day ? 'No gigs marked on this day.' : 'No gigs marked this month.'}
        </Text>
      ) : (
        shown.map((gig) => <GigCard key={gig.id} gig={gig} />)
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: spacing.sm },
  bar: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  chips: { flexGrow: 1, flexShrink: 1 },
  filters: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
});
