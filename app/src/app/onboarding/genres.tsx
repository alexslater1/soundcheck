import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { setMyGenres } from '@/api/generated/me/me';
import { useListGenres } from '@/api/generated/reference/reference';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { StepError } from '@/features/onboarding/StepError';
import {
  canContinue,
  fromMe,
  MAX_GENRES,
  setWeight,
  toggleGenre,
  toRequest,
  WEIGHTS,
  type GenreSelection,
} from '@/features/onboarding/genreSelection';
import { useSaveStep } from '@/features/onboarding/useSaveStep';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/** Genre picker: 1–8 genres as chips, each with a "love it / like it" weight. */
export default function GenresStep() {
  const { colors } = useTheme();
  const router = useRouter();
  const me = useAuthStore((s) => s.me);
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const { data: genres, isPending, isError } = useListGenres();
  const [selection, setSelection] = useState<GenreSelection>(() => fromMe(me?.genres ?? []));
  const { saving, error, run } = useSaveStep();

  function next() {
    void run(async () => {
      await setMyGenres({ genres: toRequest(selection) });
      await reloadMe();
      router.push('/onboarding/artists');
    });
  }

  return (
    <Screen>
      <Text variant="title">What do you listen to?</Text>
      <Text muted>
        Pick up to {MAX_GENRES}. Tap again to remove; use the weight to say how much.
      </Text>
      {isPending ? <Text muted>Loading genres…</Text> : null}
      {isError ? <Text style={{ color: colors.danger }}>Could not load genres</Text> : null}
      <ScrollView contentContainerStyle={styles.chips}>
        {(genres ?? []).map((g) => {
          const weight = selection.get(g.id);
          const on = weight !== undefined;
          return (
            <View key={g.id} style={styles.chipGroup}>
              <Pressable
                accessibilityRole="button"
                accessibilityState={{ selected: on }}
                onPress={() => setSelection((s) => toggleGenre(s, g.id))}
                style={[
                  styles.chip,
                  {
                    backgroundColor: on ? colors.accent : colors.surface,
                    borderColor: colors.border,
                  },
                ]}
              >
                <Text style={{ color: on ? '#FFFFFF' : colors.text }}>{g.name}</Text>
              </Pressable>
              {on
                ? WEIGHTS.map((w) => (
                    <Pressable
                      key={w}
                      accessibilityRole="button"
                      accessibilityLabel={`${g.name} weight ${w}`}
                      onPress={() => setSelection((s) => setWeight(s, g.id, w))}
                      style={[
                        styles.weight,
                        { backgroundColor: weight === w ? colors.surfaceRaised : 'transparent' },
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
        })}
      </ScrollView>
      <View style={styles.footer}>
        <StepError message={error} />
        <Button
          title={`Continue (${selection.size}/${MAX_GENRES})`}
          disabled={!canContinue(selection) || saving}
          onPress={next}
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, paddingVertical: spacing.md },
  chipGroup: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  chip: {
    minHeight: touchTarget,
    justifyContent: 'center',
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
  footer: { paddingVertical: spacing.md, gap: spacing.sm },
});
