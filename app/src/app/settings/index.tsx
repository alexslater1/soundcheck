import { Link } from 'expo-router';
import { Platform, StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useIsAdmin } from '@/features/console/useIsAdmin';
import { HealthBadge } from '@/features/health/HealthBadge';
import { spacing, touchTarget } from '@/theme/tokens';

/**
 * Settings hub: appearance, home city, genres, privacy (who sees the gig stats), profile, account
 * (delete), sign out, and the server's status.
 */
export default function SettingsScreen() {
  const signOut = useAuthStore((s) => s.signOut);
  const homeCity = useAuthStore((s) => s.me?.homeCity?.name);
  // The operators' console is a web page; the Android app doesn't offer it.
  const showConsole = useIsAdmin() && Platform.OS === 'web';
  return (
    <Screen>
      <View style={styles.list}>
        <Link href="/settings/appearance" style={styles.item}>
          <Text>Appearance: dark or light →</Text>
        </Link>
        <Link href="/settings/location" style={styles.item} testID="settings-location">
          <Text>{`Your city: ${homeCity ?? 'not set'} →`}</Text>
        </Link>
        <Link href="/settings/genres" style={styles.item}>
          <Text>Your genres: love or like →</Text>
        </Link>
        <Link href="/settings/privacy" style={styles.item}>
          <Text>Privacy: who sees your gig stats →</Text>
        </Link>
        <Link href="/settings/profile" style={styles.item}>
          <Text>Edit profile →</Text>
        </Link>
        <Link href="/settings/account" style={styles.item}>
          <Text>Account and deletion →</Text>
        </Link>
        {showConsole ? (
          <Link href="/console" style={styles.item} testID="settings-console">
            <Text>Console: operators only →</Text>
          </Link>
        ) : null}
      </View>
      <Button title="Sign out" variant="secondary" onPress={() => void signOut()} />
      <View style={styles.about}>
        <Text variant="label" muted>
          ABOUT
        </Text>
        <HealthBadge />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  list: { gap: spacing.sm, marginBottom: spacing.xl },
  item: { minHeight: touchTarget, paddingVertical: spacing.sm },
  about: { gap: spacing.sm, marginTop: spacing.xl },
});
