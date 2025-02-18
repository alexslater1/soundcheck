import { Pressable, StyleSheet } from 'react-native';

import { completeOnboarding } from '@/api/generated/me/me';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useAuthStore } from '@/features/auth/store';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

/**
 * Onboarding placeholder (city search, genre picker and the artists step come later). For now it lets
 * a new account mark onboarding complete so the tab shell is reachable.
 */
export default function OnboardingScreen() {
  const { colors } = useTheme();
  const reloadMe = useAuthStore((s) => s.reloadMe);
  const signOut = useAuthStore((s) => s.signOut);
  return (
    <Screen>
      <Text variant="display">Welcome</Text>
      <Text muted>City and genre choices come later. Continue to the app for now.</Text>
      <Pressable
        accessibilityRole="button"
        style={[styles.button, { backgroundColor: colors.accent }]}
        onPress={async () => {
          await completeOnboarding();
          await reloadMe();
        }}
      >
        <Text style={styles.buttonText}>Continue</Text>
      </Pressable>
      <Pressable accessibilityRole="button" style={styles.link} onPress={() => void signOut()}>
        <Text muted>Sign out</Text>
      </Pressable>
    </Screen>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: touchTarget,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: spacing.lg,
  },
  buttonText: { color: '#FFFFFF', fontWeight: '700' },
  link: { marginTop: spacing.lg, minHeight: touchTarget, justifyContent: 'center' },
});
