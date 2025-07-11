import Ionicons from '@expo/vector-icons/Ionicons';
import { useRef, useState } from 'react';
import { Platform, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { useSearchCities } from '@/api/generated/reference/reference';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import type { Place } from './gigFilters';

/**
 * Where the Gigs tab looks: near the home city, anywhere in the UK, or near another city found by
 * name. The place only changes here: picking an artist no longer switches to UK-wide by itself.
 * "Another city…" turns into the city search box where it stands, with the matching cities listed
 * under the row; the cross, or leaving the box empty, turns it back into the chip.
 */
export function PlacePicker({
  place,
  homeCity,
  onChange,
}: {
  place: Place;
  homeCity: string | null;
  onChange: (place: Place) => void;
}) {
  const { colors } = useTheme();
  const [choosing, setChoosing] = useState(false);
  const [text, setText] = useState('');
  const row = useRef<ScrollView>(null);
  const q = useDebounced(text.trim(), 250);
  const cities = useSearchCities({ q }, { query: { enabled: choosing && q.length >= 2 } });

  const open = () => {
    setChoosing(true);
    // The box is the row's last item: bring it into view on a narrow screen.
    requestAnimationFrame(() => row.current?.scrollToEnd({ animated: true }));
  };
  const close = () => {
    setChoosing(false);
    setText('');
  };

  return (
    <View>
      <ScrollView
        ref={row}
        horizontal
        showsHorizontalScrollIndicator={false}
        style={styles.row}
        keyboardShouldPersistTaps="handled"
        testID="place-chips"
      >
        <Chip
          label={homeCity ? `Near ${homeCity}` : 'Near me'}
          selected={place.kind === 'home'}
          onPress={() => onChange({ kind: 'home' })}
        />
        <Chip
          label="UK-wide"
          selected={place.kind === 'uk'}
          onPress={() => onChange({ kind: 'uk' })}
        />
        {place.kind === 'city' ? (
          <Chip label={`Near ${place.name}`} selected onPress={open} />
        ) : null}
        {choosing ? (
          <View
            style={[styles.field, { backgroundColor: colors.surface, borderColor: colors.accent }]}
          >
            <Ionicons name="search" size={14} color={colors.textMuted} />
            <TextInput
              accessibilityLabel="Search cities"
              placeholder="Which city?"
              placeholderTextColor={colors.textMuted}
              value={text}
              onChangeText={setText}
              onBlur={() => {
                if (text.trim() === '') close();
              }}
              autoFocus
              autoCorrect={false}
              returnKeyType="search"
              style={[styles.input, { color: colors.text }]}
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Cancel city search"
              hitSlop={8}
              onPress={close}
            >
              <Ionicons name="close-circle" size={18} color={colors.textMuted} />
            </Pressable>
          </View>
        ) : (
          <Chip
            label={place.kind === 'city' ? 'Other city…' : 'Another city…'}
            selected={false}
            onPress={open}
          />
        )}
      </ScrollView>
      {choosing && (cities.data ?? []).length > 0 ? (
        <View style={styles.results} testID="city-results">
          {(cities.data ?? []).map((city) => (
            <Pressable
              key={city.id}
              accessibilityRole="button"
              accessibilityLabel={`City: ${city.name}`}
              onPress={() => {
                onChange({
                  kind: 'city',
                  id: city.id,
                  name: city.name,
                  lat: city.lat,
                  lng: city.lng,
                });
                close();
              }}
              style={[styles.city, { borderColor: colors.border }]}
            >
              <Ionicons name="business-outline" size={16} color={colors.textMuted} />
              <Text>{city.name}</Text>
              {city.region ? (
                <Text variant="caption" muted>
                  {city.region}
                </Text>
              ) : null}
            </Pressable>
          ))}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { marginTop: spacing.sm, flexGrow: 0 },
  // Shaped like the chip it replaces, so the row keeps its height and nothing below it moves.
  field: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    minHeight: touchTarget,
    minWidth: 200,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    borderWidth: 1,
    marginRight: spacing.sm,
  },
  input: {
    flex: 1,
    minWidth: 120,
    fontSize: 14,
    paddingVertical: 0,
    // The pill's accent border shows the focus; the browser's own ring would draw a box inside it.
    ...(Platform.OS === 'web' ? { outlineWidth: 0 } : null),
  },
  results: { marginTop: spacing.xs },
  city: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
});
