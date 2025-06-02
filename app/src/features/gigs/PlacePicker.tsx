import Ionicons from '@expo/vector-icons/Ionicons';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { useSearchCities } from '@/api/generated/reference/reference';
import { Chip } from '@/components/ui/Chip';
import { Text } from '@/components/ui/Text';
import { useDebounced } from '@/lib/useDebounced';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

import type { Place } from './gigFilters';

/**
 * Where the Gigs tab looks: near the home city, anywhere in the UK, or near another city found by
 * found by name ("Other city…" opens a search over the UK cities list). The place only changes here:
 * picking an artist no longer switches to UK-wide by itself (G14).
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
  const q = useDebounced(text.trim(), 250);
  const cities = useSearchCities({ q }, { query: { enabled: choosing && q.length >= 2 } });

  return (
    <View>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        style={styles.row}
        keyboardShouldPersistTaps="handled"
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
          <Chip label={`Near ${place.name}`} selected onPress={() => setChoosing(true)} />
        ) : null}
        <Chip
          label={place.kind === 'city' ? 'Other city…' : 'Another city…'}
          selected={false}
          onPress={() => setChoosing((open) => !open)}
        />
      </ScrollView>
      {choosing ? (
        <View style={styles.search}>
          <TextInput
            accessibilityLabel="Search cities"
            placeholder="Which city? e.g. Bristol"
            placeholderTextColor={colors.textMuted}
            value={text}
            onChangeText={setText}
            autoFocus
            autoCorrect={false}
            style={[
              styles.input,
              { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border },
            ]}
          />
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
                setChoosing(false);
                setText('');
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
  search: { marginTop: spacing.sm, gap: spacing.xs },
  input: {
    minHeight: touchTarget,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    fontSize: 16,
  },
  city: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
});
