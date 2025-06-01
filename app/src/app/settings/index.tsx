import { Link } from 'expo-router';
import { StyleSheet, View } from 'react-native';

import { Button } from '@/components/ui/Button';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { spacing, touchTarget } from '@/theme/tokens';

/** Settings hub: profile, account (delete), sign out. */
export default function SettingsScreen() {
  const signOut = useAuthStore((s) => s.signOut);
  return (
    <Screen>
      <View style={styles.list}>
        <Link href="/settings/appearance" style={styles.item}>
          <Text>Appearance: theme, dark or light →</Text>
        </Link>
        <Link href="/settings/profile" style={styles.item}>
          <Text>Edit profile →</Text>
        </Link>
        <Link href="/settings/account" style={styles.item}>
          <Text>Account and deletion →</Text>
        </Link>
      </View>
      <Button title="Sign out" variant="secondary" onPress={() => void signOut()} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  list: { gap: spacing.sm, marginBottom: spacing.xl },
  item: { minHeight: touchTarget, paddingVertical: spacing.sm },
});
