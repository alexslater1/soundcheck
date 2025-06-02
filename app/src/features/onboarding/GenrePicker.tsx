import { useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import type { GenreResponse } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import {
  searchGenres,
  setWeight,
  toggleGenre,
  WEIGHTS,
  type GenreSelection,
} from './genreSelection';

type Props = {
  genres: GenreResponse[];
  selection: GenreSelection;
  onChange: (next: GenreSelection) => void;
  /**
   * Shows "love it / like it" beside each picked genre. Sign-up leaves it out and saves every pick as
   * "love"; Settings → Your genres shows it.
   */
  showWeights?: boolean;
};

/**
 * Genres as chips with a search box. Typing narrows the chips; a style that is not one of the
 * 24 genres is found through its other names ("shoegaze" → Indie) and the chip says so. While a search is
 * on, the genres already picked stay in view above the results, so they can still be removed.
 */
export function GenrePicker({ genres, selection, onChange, showWeights = false }: Props) {
  const { colors } = useTheme();
  const [query, setQuery] = useState('');
  const matches = searchGenres(genres, query);
  const searching = query.trim() !== '';
  const picked = genres.filter((g) => selection.has(g.id));

  const chip = (genre: GenreResponse, via: string | null) => {
    const weight = selection.get(genre.id);
    const on = weight !== undefined;
    return (
      <View key={genre.id} style={styles.chipGroup}>
        <Pressable
          accessibilityRole="button"
          accessibilityState={{ selected: on }}
          accessibilityLabel={genre.name}
          onPress={() => onChange(toggleGenre(selection, genre.id))}
          style={[
            styles.chip,
            {
              backgroundColor: on ? colors.accent : colors.surface,
              borderColor: on ? colors.accent : colors.border,
            },
          ]}
        >
          <Text
            style={{ color: on ? colors.onAccent : colors.text, fontWeight: on ? '700' : '400' }}
          >
            {genre.name}
          </Text>
          {via ? (
            <Text variant="caption" style={{ color: on ? colors.onAccent : colors.textMuted }}>
              {via}
            </Text>
          ) : null}
        </Pressable>
        {on && showWeights
          ? WEIGHTS.map((w) => (
              <Pressable
                key={w}
                accessibilityRole="button"
                accessibilityState={{ selected: weight === w }}
                accessibilityLabel={`${genre.name}: ${w === 1 ? 'love it' : 'like it'}`}
                onPress={() => onChange(setWeight(selection, genre.id, w))}
                style={[
                  styles.weight,
                  { backgroundColor: weight === w ? colors.accentSoft : 'transparent' },
                ]}
              >
                <Text variant="caption" muted={weight !== w}>
                  {w === 1 ? 'love' : 'like'}
                </Text>
              </Pressable>
            ))
          : null}
      </View>
    );
  };

  return (
    <View style={styles.root}>
      <TextInput
        accessibilityLabel="Search genres"
        placeholder="Search genres (e.g. shoegaze, dnb)"
        placeholderTextColor={colors.textMuted}
        value={query}
        onChangeText={setQuery}
        autoCapitalize="none"
        autoCorrect={false}
        style={[
          styles.input,
          { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border },
        ]}
      />
      {searching && picked.length > 0 ? (
        <View style={styles.chips} testID="genres-picked">
          {picked.map((g) => chip(g, null))}
        </View>
      ) : null}
      <View style={styles.chips}>
        {matches.map((m) => (searching && selection.has(m.genre.id) ? null : chip(m.genre, m.via)))}
      </View>
      {searching && matches.length === 0 ? (
        <Text muted testID="genres-no-match">
          No genre matches “{query.trim()}”. Try a broader one, like Rock or Electronic.
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: spacing.sm },
  input: {
    minHeight: touchTarget,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    fontSize: 16,
  },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chipGroup: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  chip: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
  },
  weight: {
    minHeight: touchTarget,
    justifyContent: 'center',
    paddingHorizontal: spacing.sm,
    borderRadius: radius.sm,
  },
});
