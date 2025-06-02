import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { useSetMyGenres } from '@/api/generated/me/me';
import { useListGenres } from '@/api/generated/reference/reference';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { GenrePicker } from '@/features/onboarding/GenrePicker';
import {
  canContinue,
  fromMe,
  MAX_GENRES,
  toRequest,
  type GenreSelection,
} from '@/features/onboarding/genreSelection';
import { StepError } from '@/features/onboarding/StepError';
import { useSaveStep } from '@/features/onboarding/useSaveStep';
import { spacing } from '@/theme/tokens';

/**
 * Settings → Your genres: the sign-up picks, changeable, each with "love it / like it". The
 * weight scales the genre in the For You ranking: chosen genres are 60 % of the taste vector the
 * ranking compares posts with (`GenreProfile.blend`), so "like" counts half as much as "love".
 */
export function GenresScreen({ onSaved }: { onSaved?: () => void }) {
  const me = useAuthStore((s) => s.me);
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const genres = useListGenres();
  const [selection, setSelection] = useState<GenreSelection>(() => fromMe(me?.genres ?? []));
  const [saved, setSaved] = useState(false);
  const { saving, error, run } = useSaveStep();
  const { mutateAsync: setMyGenres } = useSetMyGenres();

  const save = () =>
    void run(async () => {
      await setMyGenres({ data: { genres: toRequest(selection) } });
      await reloadMe();
      setSaved(true);
      onSaved?.();
    });

  return (
    <Screen keyboard>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text muted>
          Up to {MAX_GENRES}. “Love” counts twice as much as “like” when your For You feed is
          ranked.
        </Text>
        {genres.isPending ? <Text muted>Loading genres…</Text> : null}
        <GenrePicker
          genres={genres.data ?? []}
          selection={selection}
          onChange={(next) => {
            setSaved(false);
            setSelection(next);
          }}
          showWeights
        />
      </ScrollView>
      <View style={styles.footer}>
        <StepError message={error} />
        {saved ? (
          <Text muted testID="genres-saved">
            Saved. The next For You feed uses them.
          </Text>
        ) : null}
        <Button
          title={saving ? 'Saving…' : `Save (${selection.size}/${MAX_GENRES})`}
          disabled={!canContinue(selection) || saving}
          onPress={save}
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.md, paddingBottom: spacing.lg },
  footer: { paddingVertical: spacing.md, gap: spacing.sm },
});
