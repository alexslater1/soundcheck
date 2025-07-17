import Ionicons from '@expo/vector-icons/Ionicons';
import { useRouter } from 'expo-router';
import { useState, type ReactNode } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import type { Suggestion } from '@/api/generated/model';
import { Chip } from '@/components/ui/Chip';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
import { Text } from '@/components/ui/Text';
import { GigCard } from '@/features/gigs/GigCard';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import { PersonRow, PlaceOrActRow } from './SearchResultRows';
import {
  FILTER_LABELS,
  FILTERS,
  GROUP_TITLES,
  groupsToShow,
  parseQuery,
  type Filter,
  type Group,
} from './searchQuery';
import {
  allFailed,
  nothingFound,
  topNames,
  useSearchResults,
  type GroupState,
} from './useSearchResults';

/** Under "All", each group shows this many; its "More" switches to the group's own chip. */
const PREVIEW = 3;

/** Which chip shows a group in full. */
const FILTER_OF: Record<Group, Filter> = {
  people: 'people',
  artists: 'artists',
  venues: 'venues',
  upcoming: 'gigs',
  past: 'gigs',
};

/**
 * The Search tab on Home: one box for people, artists, venues and gigs. The
 * results come in groups, each from the module that owns them, and the group that matches exactly
 * what was typed comes first. Picking an artist or venue opens a feed of the clips tagged with it.
 */
export function SearchScreen() {
  const { colors } = useTheme();
  const router = useRouter();
  const [text, setText] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const query = parseQuery(useDebounced(text, 300));
  const results = useSearchResults(query);
  const groups = groupsToShow(query, filter, topNames(results));

  const openClips = (s: Suggestion) =>
    router.push({
      pathname: '/search/[kind]/[id]',
      params: { kind: s.kind === 'ARTIST' ? 'artist' : 'venue', id: s.id, name: s.name },
    });

  return (
    <View style={styles.fill} testID="search-screen">
      <View style={[styles.box, { backgroundColor: colors.surface, borderColor: colors.border }]}>
        <Ionicons name="search" size={18} color={colors.textMuted} />
        <TextInput
          accessibilityLabel="Search"
          placeholder="People, artists, venues, gigs"
          placeholderTextColor={colors.textMuted}
          value={text}
          onChangeText={setText}
          autoCapitalize="none"
          autoCorrect={false}
          returnKeyType="search"
          style={[styles.input, { color: colors.text }]}
          testID="search-input"
        />
        {text.length > 0 ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Clear search"
            hitSlop={spacing.sm}
            onPress={() => setText('')}
          >
            <Ionicons name="close-circle" size={18} color={colors.textMuted} />
          </Pressable>
        ) : null}
      </View>
      {query.peopleOnly ? null : (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.chips}
          keyboardShouldPersistTaps="handled"
          style={styles.chipRow}
        >
          {FILTERS.map((f) => (
            <Chip
              key={f}
              label={FILTER_LABELS[f]}
              selected={filter === f}
              onPress={() => setFilter(f)}
            />
          ))}
        </ScrollView>
      )}
      {!query.ready ? (
        <StateMessage
          testID="search-hint"
          title="Search Soundcheck"
          detail="Find people, artists, venues and gigs. Pick an artist or a venue to watch its clips. Start with @ to look for someone by their handle."
        />
      ) : allFailed(results, groups) ? (
        <StateMessage
          testID="search-error"
          title="Could not search"
          detail="Check your connection and try again."
          onRetry={() => groups.forEach((g) => results[g].retry())}
        />
      ) : nothingFound(results, groups) ? (
        <StateMessage
          testID="search-empty"
          title={`Nothing matches “${query.text}”`}
          detail="Check the spelling, or try an artist, a venue or someone's @handle."
        />
      ) : (
        <ScrollView
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          contentContainerStyle={styles.results}
        >
          {groups.map((group) => {
            const state: GroupState<unknown> = results[group];
            const preview = filter === 'all' && groups.length > 1;
            const more = preview && state.items.length > PREVIEW;
            // Under "All" an empty group, or one not asked yet, says nothing; under its own chip it says so.
            if (
              preview &&
              state.status !== 'loading' &&
              state.status !== 'error' &&
              state.items.length === 0
            )
              return null;
            return (
              <Section
                key={group}
                title={GROUP_TITLES[group]}
                state={state}
                onMore={more ? () => setFilter(FILTER_OF[group]) : undefined}
                testID={`search-group-${group}`}
              >
                {renderItems(group, results, preview ? PREVIEW : undefined, openClips)}
              </Section>
            );
          })}
        </ScrollView>
      )}
    </View>
  );
}

function renderItems(
  group: Group,
  results: ReturnType<typeof useSearchResults>,
  limit: number | undefined,
  openClips: (s: Suggestion) => void,
): ReactNode {
  switch (group) {
    case 'people':
      return results.people.items
        .slice(0, limit)
        .map((p) => <PersonRow key={p.user.id} person={p} />);
    case 'artists':
    case 'venues':
      return results[group].items
        .slice(0, limit)
        .map((s) => <PlaceOrActRow key={s.id} suggestion={s} onOpen={openClips} />);
    case 'upcoming':
    case 'past':
      return results[group].items.slice(0, limit).map((g) => <GigCard key={g.id} gig={g} />);
  }
}

function Section({
  title,
  state,
  onMore,
  children,
  testID,
}: {
  title: string;
  state: GroupState<unknown>;
  onMore?: () => void;
  children: ReactNode;
  testID: string;
}) {
  return (
    <View style={styles.section} testID={testID}>
      <View style={styles.sectionHead}>
        <Text variant="label" muted>
          {title.toUpperCase()}
        </Text>
        {onMore ? (
          <Pressable accessibilityRole="button" onPress={onMore} hitSlop={spacing.sm}>
            <Text variant="caption" style={styles.more}>
              More
            </Text>
          </Pressable>
        ) : null}
      </View>
      {state.status === 'idle' ? (
        <Text variant="caption" muted>
          Type three letters or more.
        </Text>
      ) : state.status === 'loading' ? (
        <Skeleton height={touchTarget} />
      ) : state.status === 'error' ? (
        <Pressable accessibilityRole="button" onPress={state.retry}>
          <Text variant="caption" muted>
            Could not load these. Tap to try again.
          </Text>
        </Pressable>
      ) : state.items.length === 0 ? (
        <Text variant="caption" muted>
          No matches.
        </Text>
      ) : (
        children
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  box: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
  },
  input: { flex: 1, fontSize: 16, minHeight: touchTarget },
  chipRow: { flexGrow: 0, marginVertical: spacing.sm },
  chips: { gap: spacing.sm },
  results: { gap: spacing.lg, paddingBottom: spacing.xxl },
  section: { gap: spacing.sm },
  sectionHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  more: { fontWeight: '700' },
});
