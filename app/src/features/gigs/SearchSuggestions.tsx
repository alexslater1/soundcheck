import Ionicons from '@expo/vector-icons/Ionicons';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { useListArtists, useSearchVenues } from '@/api/generated/catalog/catalog';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/** What a suggestion turns into: a filter on one artist or one venue. */
export type PickedFilter = { kind: 'artist' | 'venue'; id: string; name: string };

/**
 * Artists and venues whose names match what is being typed, as tappable chips. Picking one turns the
 * free-text search into an exact filter ("gigs by this artist", "gigs at this venue").
 */
export function SearchSuggestions({
  q,
  onPick,
}: {
  q: string;
  onPick: (filter: PickedFilter) => void;
}) {
  const { colors } = useTheme();
  const enabled = q.length >= 2;
  const artists = useListArtists({ q, limit: 5 }, { query: { enabled } });
  const venues = useSearchVenues({ q }, { query: { enabled } });
  const picks: (PickedFilter & { detail?: string | null })[] = [
    ...(artists.data?.items ?? []).map((a) => ({
      kind: 'artist' as const,
      id: a.id,
      name: a.name,
    })),
    ...(venues.data ?? [])
      .slice(0, 5)
      .map((v) => ({ kind: 'venue' as const, id: v.id, name: v.name, detail: v.city })),
  ];
  if (!enabled || picks.length === 0) {
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
      {picks.map((pick) => (
        <Pressable
          key={`${pick.kind}-${pick.id}`}
          accessibilityRole="button"
          accessibilityLabel={`${pick.kind === 'artist' ? 'Artist' : 'Venue'}: ${pick.name}`}
          onPress={() => onPick({ kind: pick.kind, id: pick.id, name: pick.name })}
          style={[styles.chip, { backgroundColor: colors.accent2Soft }]}
        >
          <Ionicons
            name={pick.kind === 'artist' ? 'mic-outline' : 'location-outline'}
            size={14}
            color={colors.text}
          />
          <View>
            <Text variant="caption" style={styles.bold} numberOfLines={1}>
              {pick.name}
            </Text>
            {pick.detail ? (
              <Text variant="label" muted numberOfLines={1}>
                {pick.detail}
              </Text>
            ) : null}
          </View>
        </Pressable>
      ))}
    </ScrollView>
  );
}

/** The active artist/venue filter, with a cross to remove it. */
export function FilterPill({ filter, onClear }: { filter: PickedFilter; onClear: () => void }) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`Remove filter ${filter.name}`}
      onPress={onClear}
      style={[styles.chip, { backgroundColor: colors.accent }]}
      testID="active-filter"
    >
      <Ionicons
        name={filter.kind === 'artist' ? 'mic' : 'location'}
        size={14}
        color={colors.onAccent}
      />
      <Text variant="caption" style={[styles.bold, { color: colors.onAccent }]} numberOfLines={1}>
        {filter.name}
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
