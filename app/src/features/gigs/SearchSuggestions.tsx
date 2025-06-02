import Ionicons from '@expo/vector-icons/Ionicons';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { useSuggest } from '@/api/generated/catalog/catalog';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import type { Pick } from './gigFilters';

/** What a suggestion turns into: a filter on one artist or one venue. */
export type PickedFilter = {
  kind: 'artist' | 'venue';
  id: string;
  name: string;
  city?: string | null;
};

/**
 * Artists and venues matching what is being typed, as tappable chips, from `GET /search/suggest`: the
 * best name match first ("The North" before "North Base"), then the ones that matter to the viewer
 * (followed, near home, their genres), then the busiest. Picking one turns the search into
 * an exact filter. With `onPickText`, a last chip offers to search gig titles for the text itself.
 */
export function SearchSuggestions({
  q,
  onPick,
  onPickText,
}: {
  q: string;
  onPick: (filter: PickedFilter) => void;
  onPickText?: (text: string) => void;
}) {
  const { colors } = useTheme();
  const enabled = q.length >= 2;
  const suggestions = useSuggest({ q }, { query: { enabled } });
  if (!enabled) {
    return null;
  }
  const items = suggestions.data ?? [];
  if (items.length === 0 && !onPickText) {
    return null;
  }
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      style={styles.row}
      keyboardShouldPersistTaps="handled"
      testID="search-suggestions"
    >
      {items.map((s) => {
        const kind = s.kind === 'ARTIST' ? ('artist' as const) : ('venue' as const);
        const detail =
          kind === 'venue' ? s.city : s.upcomingGigs > 0 ? `${s.upcomingGigs} upcoming` : null;
        return (
          <Pressable
            key={`${kind}-${s.id}`}
            accessibilityRole="button"
            accessibilityLabel={`${kind === 'artist' ? 'Artist' : 'Venue'}: ${s.name}`}
            onPress={() => onPick({ kind, id: s.id, name: s.name, city: s.city })}
            style={[styles.chip, { backgroundColor: colors.accent2Soft }]}
          >
            <Ionicons
              name={kind === 'artist' ? 'mic-outline' : 'location-outline'}
              size={14}
              color={colors.text}
            />
            <View>
              <Text variant="caption" style={styles.bold} numberOfLines={1}>
                {s.name}
              </Text>
              {detail ? (
                <Text variant="label" muted numberOfLines={1}>
                  {detail}
                </Text>
              ) : null}
            </View>
          </Pressable>
        );
      })}
      {onPickText ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Search gigs for ${q}`}
          onPress={() => onPickText(q)}
          style={[styles.chip, { backgroundColor: colors.surfaceRaised }]}
          testID="search-text"
        >
          <Ionicons name="search" size={14} color={colors.text} />
          <Text variant="caption" numberOfLines={1}>
            Gigs titled “{q}”
          </Text>
        </Pressable>
      ) : null}
    </ScrollView>
  );
}

/** One active filter (an artist, a venue or a text), with a cross to remove it. */
export function FilterPill({ filter, onClear }: { filter: Pick; onClear: () => void }) {
  const { colors } = useTheme();
  const label = filter.kind === 'text' ? `“${filter.text}”` : filter.name;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`Remove filter ${label}`}
      onPress={onClear}
      style={[styles.chip, { backgroundColor: colors.accent }]}
      testID="active-filter"
    >
      <Ionicons
        name={filter.kind === 'artist' ? 'mic' : filter.kind === 'venue' ? 'location' : 'search'}
        size={14}
        color={colors.onAccent}
      />
      <Text variant="caption" style={[styles.bold, { color: colors.onAccent }]} numberOfLines={1}>
        {label}
      </Text>
      <Ionicons name="close" size={16} color={colors.onAccent} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: { marginTop: spacing.sm, flexGrow: 0 },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    minHeight: touchTarget,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    marginRight: spacing.sm,
    maxWidth: 260,
  },
  bold: { fontWeight: '700' },
});
