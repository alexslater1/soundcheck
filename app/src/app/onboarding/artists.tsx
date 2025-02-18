import { useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { completeOnboarding } from '@/api/generated/me/me';
import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { spacing } from '@/theme/tokens';

/** Artists step placeholder (filled in once the catalog has artists); finishing marks onboarding complete. */
export default function ArtistsStep() {
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const [saving, setSaving] = useState(false);

  async function finish() {
    setSaving(true);
    try {
      await completeOnboarding();
      await reloadMe(); // the root layout redirects to the tabs once `onboarded` is true
    } finally {
      setSaving(false);
    }
  }

  return (
    <Screen>
      <Text variant="title">Follow some artists</Text>
      <Text muted>
        Artist suggestions arrive with the gig catalog. You can skip this for now.
      </Text>
      <View style={styles.footer}>
        <Button title="Finish" disabled={saving} onPress={finish} />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({ footer: { paddingVertical: spacing.lg } });
