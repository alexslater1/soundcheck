import { useEffect, useState } from 'react';
import { FlatList, StyleSheet, TextInput, View } from 'react-native';

import { useListArtists } from '@/api/generated/catalog/catalog';
import { useCompleteOnboarding } from '@/api/generated/me/me';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Skeleton } from '@/components/ui/Skeleton';
import { Text } from '@/components/ui/Text';
import { ArtistRow } from '@/features/artists/ArtistRow';
import { useGenreNames } from '@/features/artists/useGenreNames';
import { useAuthStore } from '@/features/auth/store';
import { StepError } from '@/features/onboarding/StepError';
import { useSaveStep } from '@/features/onboarding/useSaveStep';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/**
 * Last onboarding step: popular artists, or search results while typing, each with a
 * follow button. Following is optional; Finish marks onboarding complete.
 */
export default function ArtistsStep() {
  const { colors } = useTheme();
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const { saving, error, run } = useSaveStep();
  const { mutateAsync: completeOnboarding } = useCompleteOnboarding();
  const genreNames = useGenreNames();
  const [text, setText] = useState('');
  const [q, setQ] = useState('');

  useEffect(() => {
    const timer = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(timer);
  }, [text]);

  const artists = useListArtists(q ? { q, limit: 20 } : { popular: true, limit: 20 }, {
    query: { placeholderData: (previous) => previous },
  });

  function finish() {
    void run(async () => {
      await completeOnboarding();
      await reloadMe(); // the root layout redirects to the tabs once `onboarded` is true
    });
  }

  return (
    <Screen>
      <Text variant="title">Follow some artists</Text>
      <Text muted>Their gigs and clips will show up first. You can skip this.</Text>
      <TextInput
        accessibilityLabel="Search artists"
        placeholder="Search artists"
        placeholderTextColor={colors.textMuted}
        value={text}
        onChangeText={setText}
        autoCapitalize="none"
        autoCorrect={false}
        style={[
          styles.input,
          { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border },
        ]}
      />
      {artists.isPending ? (
        <View style={styles.skeletons} testID="artists-loading">
          <Skeleton height={48} />
          <Skeleton height={48} />
          <Skeleton height={48} />
        </View>
      ) : (
        <FlatList
          data={artists.data?.items ?? []}
          keyExtractor={(artist) => artist.id}
          keyboardShouldPersistTaps="handled"
          renderItem={({ item }) => (
            <ArtistRow artist={item} genreNames={genreNames} linked={false} />
          )}
          ListEmptyComponent={
            <Text muted style={styles.empty} testID="artists-empty">
              {artists.isError
                ? 'Could not load artists. You can still finish and follow artists later.'
                : q
                  ? 'No artists match that search.'
                  : 'No artists yet — they arrive with the first gig import.'}
            </Text>
          }
          style={styles.list}
        />
      )}
      <View style={styles.footer}>
        <StepError message={error} />
        <Button title={saving ? 'Saving…' : 'Finish'} disabled={saving} onPress={finish} />
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
  skeletons: { gap: spacing.sm, marginTop: spacing.md, flex: 1 },
  list: { marginTop: spacing.sm },
  empty: { padding: spacing.md },
  footer: { paddingVertical: spacing.md, gap: spacing.sm },
});
