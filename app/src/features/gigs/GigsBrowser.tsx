import { useMemo, useState } from 'react';
import { FlatList, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { useListGigDays } from '@/api/generated/catalog/catalog';
import type { ListGigsParams } from '@/api/generated/model';
import { useListGenres } from '@/api/generated/reference/reference';
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

import { chipRange, DATE_CHIPS, type DateChip } from './dateChips';
import { formatGigDate } from './format';
import { GigCard } from './GigCard';
import { FilterPill, SearchSuggestions, type PickedFilter } from './SearchSuggestions';
import { useGigList } from './useGigList';
import { useWindowAnchor } from './useWindowAnchor';

/** How long typing must pause before the search is sent. */
const SEARCH_DEBOUNCE_MS = 300;

type View_ = 'list' | 'calendar';

/**
 * The Gigs tab: gigs near the viewer's home city (or anywhere in the UK),
 * narrowed by a search box, an artist or venue picked from its suggestions, and a genre. Two views of
 * the same search: a list by date chip, or a month calendar whose highlighted days are the days with
 * matching gigs; tapping a day lists that day's gigs.
 */
export function GigsBrowser() {
  const { colors } = useTheme();
  const homeCity = useAuthStore((s) => s.me?.homeCity ?? null);
  const [view, setView] = useState<View_>('list');
  const [chip, setChip] = useState<DateChip>('week');
  const [genre, setGenre] = useState<string | null>(null);
  const [text, setText] = useState('');
  const [filter, setFilter] = useState<PickedFilter | null>(null);
  const [nationwide, setNationwide] = useState(false);
  const [month, setMonth] = useState<Month>(() => monthOf(new Date()));
  const [day, setDay] = useState<string | null>(null);
  const q = useDebounced(text.trim(), SEARCH_DEBOUNCE_MS);
  const { data: genres } = useListGenres();

  // What every request shares: the search and the filters, whatever the view.
  const filters = useMemo(
    () => ({
      ...(genre ? { genre } : {}),
      ...(q ? { q } : {}),
      ...(filter?.kind === 'artist' ? { artistId: filter.id } : {}),
      ...(filter?.kind === 'venue' ? { venueId: filter.id } : {}),
      ...(nationwide ? { nationwide: true } : {}),
    }),
    [genre, q, filter, nationwide],
  );

  // The list's window is computed from a stable anchor, not from `new Date()` on every render (that
  // would be a new query key each render); the anchor moves when a chip is chosen or the tab is
  // revisited later. In the calendar view the window is the chosen day, or else the shown month.
  const { anchor, reset } = useWindowAnchor();
  const range = useMemo(() => {
    if (view === 'calendar') {
      return day ? dayWindow(day) : monthWindow(month);
    }
    const { from, to } = chipRange(chip, new Date(anchor));
    return { from: from.toISOString(), to: to.toISOString() };
  }, [view, day, month, chip, anchor]);

  const params: Omit<ListGigsParams, 'cursor'> = { ...range, ...filters, limit: 20 };
  const list = useGigList(params);
  const days = useListGigDays(
    { ...monthWindow(month), ...filters },
    { query: { enabled: view === 'calendar' } },
  );
  const counts = useMemo(
    () => Object.fromEntries((days.data ?? []).map((d) => [d.date, d.count])),
    [days.data],
  );

  const gigs = list.data?.pages.flatMap((page) => page.items) ?? [];
  const topGenres = (genres ?? []).filter((g) => g.parentId == null);
  const where = nationwide
    ? 'Anywhere in the UK'
    : homeCity
      ? `Within 25 km of ${homeCity.name}`
      : 'Across the UK — pick a home city in Settings';

  const header = (
    <View>
      <View style={styles.titleRow}>
        <Text variant="title">Gigs</Text>
        <View
          style={[styles.toggle, { backgroundColor: colors.surface, borderColor: colors.border }]}
        >
          {(['list', 'calendar'] as const).map((v) => (
            <Text
              key={v}
              role="radio"
              aria-checked={view === v}
              accessibilityLabel={v === 'list' ? 'List view' : 'Calendar view'}
              onPress={() => {
                setView(v);
                setDay(null);
              }}
              style={[
                styles.toggleItem,
                view === v
                  ? { backgroundColor: colors.accent, color: colors.onAccent, fontWeight: '700' }
                  : null,
              ]}
            >
              {v === 'list' ? 'List' : 'Calendar'}
            </Text>
          ))}
        </View>
      </View>
      <Text muted>{where}</Text>
      <TextInput
        accessibilityLabel="Search gigs"
        placeholder="Search artists, gigs or venues"
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
      {filter ? null : (
        <SearchSuggestions
          q={q}
          onPick={(picked) => {
            setFilter(picked);
            setText('');
            // An artist's or a venue's gigs are worth seeing wherever they are.
            setNationwide(true);
          }}
        />
      )}
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        style={styles.chips}
        keyboardShouldPersistTaps="handled"
      >
        {filter ? (
          <FilterPill
            filter={filter}
            onClear={() => {
              setFilter(null);
              setNationwide(false);
            }}
          />
        ) : null}
        <Chip
          label={homeCity ? `Near ${homeCity.name}` : 'Near me'}
          selected={!nationwide}
          onPress={() => setNationwide(false)}
        />
        <Chip label="UK-wide" selected={nationwide} onPress={() => setNationwide(true)} />
      </ScrollView>
      {view === 'list' ? (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chips}>
          {DATE_CHIPS.map((c) => (
            <Chip
              key={c.key}
              label={c.label}
              selected={chip === c.key}
              onPress={() => {
                setChip(c.key);
                reset();
              }}
            />
          ))}
        </ScrollView>
      ) : null}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chips}>
        <Chip label="All genres" selected={genre === null} onPress={() => setGenre(null)} />
        {topGenres.map((g) => (
          <Chip
            key={g.id}
            label={g.name}
            selected={genre === g.slug}
            onPress={() => setGenre(genre === g.slug ? null : g.slug)}
          />
        ))}
      </ScrollView>
      {view === 'calendar' ? (
        <View style={styles.calendar}>
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
            loading={days.isFetching}
            testID="gigs-calendar"
          />
          <Text variant="label" muted style={styles.dayHeading}>
            {day
              ? `GIGS ON ${formatGigDate(`${day}T12:00:00Z`).toUpperCase()}`
              : 'ALL GIGS THIS MONTH · TAP A DAY TO NARROW'}
          </Text>
        </View>
      ) : null}
    </View>
  );

  return (
    <FlatList
      data={list.isPending || (list.isError && !list.data) ? [] : gigs}
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
        list.isPending ? (
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
          <StateMessage
            testID="gigs-empty"
            title="No gigs found"
            detail={
              nationwide
                ? 'Try other dates or another search.'
                : 'Try other dates, UK-wide, or another search.'
            }
          />
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
  titleRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  toggle: {
    flexDirection: 'row',
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.pill,
    overflow: 'hidden',
  },
  toggleItem: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    minHeight: 36,
    fontSize: 13,
  },
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
  list: { paddingBottom: spacing.xl, gap: 0 },
  credit: { paddingVertical: spacing.md },
});
