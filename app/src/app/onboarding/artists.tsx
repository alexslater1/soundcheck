import { StyleSheet, View } from 'react-native';

import { completeOnboarding } from '@/api/generated/me/me';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { StepError } from '@/features/onboarding/StepError';
import { useSaveStep } from '@/features/onboarding/useSaveStep';
import { spacing } from '@/theme/tokens';

/** Artists step placeholder (filled in once the catalog has artists); finishing marks onboarding complete. */
export default function ArtistsStep() {
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const { saving, error, run } = useSaveStep();

  function finish() {
    void run(async () => {
      await completeOnboarding();
      await reloadMe(); // the root layout redirects to the tabs once `onboarded` is true
    });
  }

  return (
    <Screen>
      <Text variant="title">Follow some artists</Text>
      <Text muted>
        Artist suggestions arrive with the gig catalog. You can skip this for now.
      </Text>
      <View style={styles.footer}>
        <StepError message={error} />
        <Button title={saving ? 'Saving…' : 'Finish'} disabled={saving} onPress={finish} />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({ footer: { paddingVertical: spacing.lg, gap: spacing.sm } });
