import { useRouter } from 'expo-router';
import { useState } from 'react';
import { FlatList, Pressable, StyleSheet, TextInput, View } from 'react-native';

import { updateMe } from '@/api/generated/me/me';
import { useSearchCities } from '@/api/generated/reference/reference';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/** City autocomplete (only a chosen city centroid is ever stored, never GPS). */
export default function CityStep() {
  const { colors } = useTheme();
  const router = useRouter();
  const me = useAuthStore((s) => s.me);
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<{ id: number; name: string } | null>(
    me?.homeCity ? { id: me.homeCity.id, name: me.homeCity.name } : null,
  );
  const [saving, setSaving] = useState(false);
  const { data, isFetching } = useSearchCities(
    { q: query },
    { query: { placeholderData: (prev) => prev } },
  );

  async function next() {
    if (!selected) return;
    setSaving(true);
    try {
      await updateMe({ homeCityId: selected.id });
      await reloadMe();
      router.push('/onboarding/genres');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Screen>
      <Text variant="title">Where do you go to gigs?</Text>
      <Text muted>We only store the city, never your location.</Text>
      <TextInput
        accessibilityLabel="City"
        placeholder="Start typing a UK city"
        placeholderTextColor={colors.textMuted}
        value={query}
        onChangeText={setQuery}
        autoCapitalize="words"
        style={[
          styles.input,
          { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border },
        ]}
      />
      <FlatList
        data={data ?? []}
        keyExtractor={(c) => String(c.id)}
        keyboardShouldPersistTaps="handled"
        ListEmptyComponent={
          <Text muted style={styles.empty}>
            {isFetching ? 'Searching…' : 'No matches'}
          </Text>
        }
        renderItem={({ item }) => (
          <Pressable
            accessibilityRole="button"
            onPress={() => setSelected({ id: item.id, name: item.name })}
            style={[
              styles.row,
              { backgroundColor: selected?.id === item.id ? colors.surfaceRaised : 'transparent' },
            ]}
          >
            <Text>{item.name}</Text>
            <Text variant="caption" muted>
              {item.region ?? ''}
            </Text>
          </Pressable>
        )}
        style={styles.list}
      />
      <View style={styles.footer}>
        <Button
          title={selected ? `Continue as ${selected.name}` : 'Pick a city'}
          disabled={!selected || saving}
          onPress={next}
        />
      </View>
    </Screen>
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
  list: { marginTop: spacing.sm },
  row: {
    minHeight: touchTarget,
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
    borderRadius: radius.md,
  },
  empty: { padding: spacing.md },
  footer: { paddingVertical: spacing.md },
});
