import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { useUpdateMe } from '@/api/generated/me/me';
import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

/**
 * Settings → Privacy: whether other people see your gig stats on your profile. Off until
 * the user turns it on: the stats come from the gigs marked Went, so they tell where someone was on
 * which nights. The choice shows at once and is put back if saving fails.
 */
export function PrivacyScreen() {
  const { colors } = useTheme();
  const me = useAuthStore((s) => s.me);
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const { mutateAsync: updateMe } = useUpdateMe();
  const [choice, setChoice] = useState<boolean | null>(null);
  const [failed, setFailed] = useState(false);

  if (!me) {
    return (
      <Screen>
        <Text muted>Loading…</Text>
      </Screen>
    );
  }
  const shared = choice ?? me.showGigStats;
  const choose = async (show: boolean) => {
    if (show === shared) return;
    setChoice(show);
    setFailed(false);
    try {
      await updateMe({ data: { showGigStats: show } });
      await reloadMe();
    } catch {
      setFailed(true);
    }
    setChoice(null);
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="label" muted>
          WHO SEES YOUR GIG STATS
        </Text>
        <View style={styles.row} accessibilityRole="radiogroup">
          <Chip label="Only me" selected={!shared} onPress={() => void choose(false)} />
          <Chip label="Everyone" selected={shared} onPress={() => void choose(true)} />
        </View>
        <Text variant="caption" muted testID="privacy-stats-detail">
          {shared
            ? 'Your profile shows the acts you have seen most and the venues you go back to, with their dates, to anyone signed in. People you block never see them.'
            : 'Your stats are on your Me tab, for you only. They come from the gigs you mark as Went, so they show where you were and when.'}
        </Text>
        {failed ? (
          <Text variant="caption" style={{ color: colors.danger }} testID="privacy-error">
            Could not save that. Check your connection and try again.
          </Text>
        ) : null}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.sm, paddingBottom: spacing.xl },
  row: { flexDirection: 'row' },
});
