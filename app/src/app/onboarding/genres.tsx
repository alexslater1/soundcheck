import { useRouter } from 'expo-router';
import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { useSetMyGenres } from '@/api/generated/me/me';
import { useListGenres } from '@/api/generated/reference/reference';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { StateMessage } from '@/components/ui/StateMessage';
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
 * Genre step: 1–8 genres, found by tapping or by searching. Every pick is saved as "love"
 * (weight 1): how much is asked later, in Settings → Your genres, not at sign-up.
 */
export default function GenresStep() {
  const router = useRouter();
  const me = useAuthStore((s) => s.me);
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const { data: genres, isPending, isError, refetch } = useListGenres();
  const [selection, setSelection] = useState<GenreSelection>(() => fromMe(me?.genres ?? []));
  const { saving, error, run } = useSaveStep();
  const { mutateAsync: setMyGenres } = useSetMyGenres();

  function next() {
    void run(async () => {
      await setMyGenres({ data: { genres: toRequest(selection) } });
      await reloadMe();
      router.push('/onboarding/artists');
    });
  }

  return (
    <Screen keyboard>
      <Text variant="title">What do you listen to?</Text>
      <Text muted>Pick up to {MAX_GENRES}. Tap again to remove one.</Text>
      {isPending ? <Skeleton height={240} /> : null}
      {isError ? (
        <StateMessage title="Could not load the genres" onRetry={() => void refetch()} />
      ) : null}
      <ScrollView
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
        testID="genres-scroll"
      >
        <GenrePicker genres={genres ?? []} selection={selection} onChange={setSelection} />
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
  content: { paddingVertical: spacing.md },
  footer: { paddingVertical: spacing.md, gap: spacing.sm },
});
