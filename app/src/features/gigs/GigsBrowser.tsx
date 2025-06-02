import { useMemo, useState } from 'react';
import { FlatList, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { useListGigDays } from '@/api/generated/catalog/catalog';
import type { ListGigsParams } from '@/api/generated/model';
import { useListGenres } from '@/api/generated/reference/reference';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { MonthCalendar } from '@/features/calendar/MonthCalendar';
import { dayWindow, monthOf, monthWindow, ukDate, type Month } from '@/features/calendar/monthGrid';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { formatGigDate } from './format';
import { GigCard } from './GigCard';
import {
  addPick,
  describePlace,
  EMPTY_SEARCH,
  pickKey,
  removePick,
  toParams,
  type GigSearch,
} from './gigFilters';
import { PlacePicker } from './PlacePicker';
import { SavedFilters } from './SavedFilters';
import { FilterPill, SearchSuggestions } from './SearchSuggestions';
import { useGigList } from './useGigList';

/** How long typing must pause before suggestions are fetched. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * The Gigs tab: a month calendar whose highlighted days have
 * matching gigs, and the gigs of the month (from today on) or of the day tapped. The search box only
 * suggests; the results change when a suggestion is picked (G13): one or more artists and venues (G15),
 * or "Gigs titled …" for the text. The place is near home, UK-wide or another city (G16) and changes
 * only when the user changes it (G14). A search can be saved and brought back with one tap (G17).
 */
export function GigsBrowser() {
  const { colors } = useTheme();
  const homeCity = useAuthStore((s) => s.me?.homeCity?.name ?? null);
  const [search, setSearch] = useState<GigSearch>(EMPTY_SEARCH);
  const [text, setText] = useState('');
  const [month, setMonth] = useState<Month>(() => monthOf(new Date()));
  const [day, setDay] = useState<string | null>(null);
  // Today, fixed for the life of the screen: a new Date() per render would be a new query key each time.
  const [today] = useState(() => ukDate(new Date()));
  const q = useDebounced(text.trim(), SEARCH_DEBOUNCE_MS);
  const { data: genres } = useListGenres();

  const filters = useMemo(() => toParams(search), [search]);

  // The list: the chosen day, or else the shown month from today on (earlier days are in the past).
  const range = useMemo(() => {
    if (day) {
      return dayWindow(day);
    }
    const whole = monthWindow(month);
    const fromToday = dayWindow(today).from;
    return { from: whole.from > fromToday ? whole.from : fromToday, to: whole.to };
  }, [day, month, today]);
  const params: Omit<ListGigsParams, 'cursor'> = { ...range, ...filters, limit: 20 };
  const list = useGigList(params);
  const days = useListGigDays({ ...monthWindow(month), ...filters });
  const counts = useMemo(
    () => Object.fromEntries((days.data ?? []).map((d) => [d.date, d.count])),
    [days.data],
  );

  const gigs = list.data?.pages.flatMap((page) => page.items) ?? [];
  const topGenres = (genres ?? []).filter((g) => g.parentId == null);
  const monthIsPast = range.from >= range.to;

  const header = (
    <View>
      <Text variant="title">Gigs</Text>
      <Text muted testID="gigs-where">
        {describePlace(search.place, homeCity)}
      </Text>
      <SavedFilters
        search={search}
        onApply={(next) => {
          setSearch(next);
          setDay(null);
        }}
      />
      <TextInput
        accessibilityLabel="Search gigs"
        placeholder="Search artists or venues"
        placeholderTextColor={colors.textMuted}
        value={text}
        onChangeText={setText}
        autoCapitalize="none"
        autoCorrect={false}
        style={[
          styles.input,
          { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border },
        ]}
      />
      <SearchSuggestions
        q={q}
        onPick={(picked) => {
          setSearch((s) => addPick(s, picked));
          setText('');
        }}
        onPickText={(typed) => {
          setSearch((s) => addPick(s, { kind: 'text', text: typed }));
          setText('');
        }}
      />
      {search.picks.length > 0 ? (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={styles.chips}
          keyboardShouldPersistTaps="handled"
          testID="active-filters"
        >
          {search.picks.map((pick) => (
            <FilterPill
              key={pickKey(pick)}
              filter={pick}
              onClear={() => setSearch((s) => removePick(s, pick))}
            />
          ))}
        </ScrollView>
      ) : null}
      <PlacePicker
        place={search.place}
        homeCity={homeCity}
        onChange={(place) => setSearch((s) => ({ ...s, place }))}
      />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chips}>
        <Chip
          label="All genres"
          selected={search.genre === null}
          onPress={() => setSearch((s) => ({ ...s, genre: null }))}
        />
        {topGenres.map((g) => (
          <Chip
            key={g.id}
            label={g.name}
            selected={search.genre === g.slug}
            onPress={() => setSearch((s) => ({ ...s, genre: s.genre === g.slug ? null : g.slug }))}
          />
        ))}
      </ScrollView>
      <View style={styles.calendar}>
        <MonthCalendar
          month={month}
          onMonthChange={(next) => {
            setMonth(next);
            setDay(null);
          }}
          counts={counts}
          selected={day}
          onSelect={(picked) => setDay((current) => (current === picked ? null : picked))}
          today={today}
          loading={days.isFetching}
          testID="gigs-calendar"
        />
        <Text variant="label" muted style={styles.dayHeading}>
          {day
            ? `GIGS ON ${formatGigDate(`${day}T12:00:00Z`).toUpperCase()} · TAP AGAIN FOR THE MONTH`
            : 'GIGS THIS MONTH · TAP A DAY TO NARROW'}
        </Text>
      </View>
    </View>
  );

  return (
    <FlatList
      data={monthIsPast || list.isPending || (list.isError && !list.data) ? [] : gigs}
      keyExtractor={(gig) => gig.id}
      renderItem={({ item }) => <GigCard gig={item} />}
      testID="gig-list"
      ListHeaderComponent={header}
      onEndReached={() => {
        if (list.hasNextPage && !list.isFetchingNextPage && !list.isError)
          void list.fetchNextPage();
      }}
      onEndReachedThreshold={0.5}
      keyboardShouldPersistTaps="handled"
      ListEmptyComponent={
        list.isPending && !monthIsPast ? (
          <View style={styles.skeletons} testID="gigs-loading">
            <Skeleton height={96} />
            <Skeleton height={96} />
            <Skeleton height={96} />
          </View>
        ) : list.isError && !list.data ? (
          <StateMessage
            testID="gigs-error"
            title="Could not load gigs"
            detail="Check your connection and try again."
            onRetry={() => void list.refetch()}
          />
        ) : (
          <View style={styles.empty} testID="gigs-empty">
            <StateMessage
              title={monthIsPast ? 'This month is over' : 'No gigs found'}
              detail={
                monthIsPast
                  ? 'Tap a highlighted day to see its gigs.'
                  : search.place.kind === 'uk'
                    ? 'Try another month or another search.'
                    : 'Try another month, another search, or the whole UK.'
              }
            />
            {!monthIsPast && search.place.kind !== 'uk' ? (
              <Button
                title="Search UK-wide"
                variant="secondary"
                onPress={() => setSearch((s) => ({ ...s, place: { kind: 'uk' } }))}
                testID="gigs-uk-wide"
              />
            ) : null}
          </View>
        )
      }
      ListFooterComponent={
        list.isFetchingNextPage ? (
          <Skeleton height={96} />
        ) : list.isError && list.data ? (
          // Loaded pages stay on screen; only the failed fetch is reported, with a retry.
          <StateMessage
            testID="gigs-more-error"
            title="Could not load more gigs"
            onRetry={() => void (list.hasNextPage ? list.fetchNextPage() : list.refetch())}
          />
        ) : gigs.length > 0 ? (
          <Text variant="caption" muted style={styles.credit}>
            Listings from Ticketmaster and from fans
          </Text>
        ) : null
      }
      contentContainerStyle={styles.list}
    />
  );
}

const styles = StyleSheet.create({
  input: {
    minHeight: touchTarget,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    fontSize: 16,
    marginTop: spacing.md,
  },
  chips: { marginTop: spacing.sm, flexGrow: 0 },
  calendar: { marginTop: spacing.md, gap: spacing.sm },
  dayHeading: { marginTop: spacing.xs },
  skeletons: { gap: spacing.sm, marginTop: spacing.md },
  empty: { gap: spacing.sm, alignItems: 'stretch' },
  list: { paddingBottom: spacing.xl, gap: 0 },
  credit: { paddingVertical: spacing.md },
});
