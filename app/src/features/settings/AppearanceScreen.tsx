import { ScrollView, StyleSheet, View } from 'react-native';

import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { useThemePrefs, type ThemeMode } from '@/theme/themePrefs';
import { spacing } from '@/theme/tokens';

const MODES: { mode: ThemeMode; label: string; detail: string }[] = [
  { mode: 'auto', label: 'Auto', detail: "Follows the phone's dark or light setting." },
  { mode: 'dark', label: 'Dark', detail: 'Always dark.' },
  { mode: 'light', label: 'Light', detail: 'Always light.' },
];

/**
 * Settings → Appearance: dark, light, or the phone's setting. The theme itself is Electric, chosen
 * from the five offered and the others were removed.
 */
export function AppearanceScreen() {
  const mode = useThemePrefs((state) => state.mode);
  const setMode = useThemePrefs((state) => state.setMode);

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="label" muted>
          MODE
        </Text>
        <View style={styles.modes} accessibilityRole="radiogroup">
          {MODES.map((m) => (
            <Chip
              key={m.mode}
              label={m.label}
              selected={mode === m.mode}
              onPress={() => setMode(m.mode)}
            />
          ))}
        </View>
        <Text muted>{MODES.find((m) => m.mode === mode)?.detail}</Text>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.sm, paddingBottom: spacing.xl },
  modes: { flexDirection: 'row', gap: spacing.sm },
});
