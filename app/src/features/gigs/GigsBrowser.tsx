import { useEffect, useMemo, useState } from 'react';
import { FlatList, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { useListGenres } from '@/api/generated/reference/reference';
import { Chip } from '@/components/ui/Chip';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { chipRange, DATE_CHIPS, type DateChip } from './dateChips';
import { GigCard } from './GigCard';
import { useGigList } from './useGigList';
import { useWindowAnchor } from './useWindowAnchor';

/** How long typing must pause before the search is sent. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * The Gigs tab: gigs near the viewer's home city, narrowed by a date chip, a genre and a
 * search box, loading further pages as the list is scrolled.
 */
export function GigsBrowser() {
  const { colors } = useTheme();
  const homeCity = useAuthStore((s) => s.me?.homeCity ?? null);
  const [chip, setChip] = useState<DateChip>('week');
  const [genre, setGenre] = useState<string | null>(null);
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const { data: genres } = useListGenres();

  useEffect(() => {
    const timer = setTimeout(() => setQ(text.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [text]);

  // The window is computed from a stable anchor, not from `new Date()` on every render (that would be a
  // new query key each render); the anchor moves when a chip is chosen or the tab is revisited later.
  const { anchor, reset } = useWindowAnchor();
  const range = useMemo(() => {
    const { from, to } = chipRange(chip, new Date(anchor));
    return { from: from.toISOString(), to: to.toISOString() };
  }, [chip, anchor]);

  const list = useGigList({
    ...range,
    ...(genre ? { genre } : {}),
    ...(q ? { q } : {}),
    limit: 20,
  });
  const gigs = list.data?.pages.flatMap((page) => page.items) ?? [];
  const topGenres = (genres ?? []).filter((g) => g.parentId == null);

  return (
    <View style={styles.fill}>
      <Text variant="title">Gigs</Text>
      <Text muted>
        {homeCity
          ? `Within 25 km of ${homeCity.name}`
          : 'Across the UK — pick a home city in Settings'}
      </Text>
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
      <View>
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
      </View>
      {list.isPending ? (
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
        <FlatList
          data={gigs}
          keyExtractor={(gig) => gig.id}
          renderItem={({ item }) => <GigCard gig={item} />}
          testID="gig-list"
          onEndReached={() => {
            if (list.hasNextPage && !list.isFetchingNextPage && !list.isError)
              void list.fetchNextPage();
          }}
          onEndReachedThreshold={0.5}
          keyboardShouldPersistTaps="handled"
          ListEmptyComponent={
            <StateMessage
              testID="gigs-empty"
              title="No gigs found"
              detail="Try a wider date range, another genre or a different search."
            />
          }
          ListFooterComponent={
            list.isFetchingNextPage ? (
              <Skeleton height={96} />
            ) : list.isError ? (
              // Loaded pages stay on screen; only the failed fetch is reported, with a retry.
              <StateMessage
                testID="gigs-more-error"
                title="Could not load more gigs"
                onRetry={() => void (list.hasNextPage ? list.fetchNextPage() : list.refetch())}
              />
            ) : (
              <Text variant="caption" muted style={styles.credit}>
                Listings from Ticketmaster
              </Text>
            )
          }
          contentContainerStyle={styles.list}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  input: {
    minHeight: touchTarget,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    fontSize: 16,
    marginTop: spacing.md,
  },
  chips: { marginTop: spacing.sm, flexGrow: 0 },
  skeletons: { gap: spacing.sm, marginTop: spacing.md },
  list: { paddingTop: spacing.md, paddingBottom: spacing.xl },
  credit: { paddingVertical: spacing.md },
});
