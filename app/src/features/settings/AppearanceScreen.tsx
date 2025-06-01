import Ionicons from '@expo/vector-icons/Ionicons';
import { Pressable, ScrollView, StyleSheet, Text as RNText, View } from 'react-native';

import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { resolveScheme, useTheme } from '@/theme/ThemeProvider';
import { useThemePrefs, type ThemeMode } from '@/theme/themePrefs';
import {
  radius,
  spacing,
  THEME_ORDER,
  themes,
  typeScale,
  type Colors,
  type Theme,
} from '@/theme/tokens';

const MODES: { mode: ThemeMode; label: string }[] = [
  { mode: 'auto', label: 'Auto' },
  { mode: 'dark', label: 'Dark' },
  { mode: 'light', label: 'Light' },
];

/**
 * Settings → Appearance: every theme drawn as a small gig card in its own colours, so the
 * choice is made by looking, not by reading hex codes; tapping one applies it at once. Auto follows the
 * phone's dark/light setting.
 */
export function AppearanceScreen() {
  const { scheme } = useTheme();
  const themeId = useThemePrefs((state) => state.themeId);
  const mode = useThemePrefs((state) => state.mode);
  const setTheme = useThemePrefs((state) => state.setTheme);
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
        <Text variant="label" muted style={styles.heading}>
          THEME
        </Text>
        {THEME_ORDER.map((id) => {
          const theme = themes[id];
          // Each preview shows the variant that would actually be used with the current mode.
          const previewScheme =
            mode === 'auto' ? resolveScheme('auto', scheme, theme.prefers) : mode;
          return (
            <ThemePreview
              key={id}
              theme={theme}
              colors={theme.palettes[previewScheme]}
              selected={themeId === id}
              onPress={() => setTheme(id)}
            />
          );
        })}
      </ScrollView>
    </Screen>
  );
}

/** One theme as a miniature screen: header, a gig card with its date tile, a button, chips, a like. */
function ThemePreview({
  theme,
  colors: c,
  selected,
  onPress,
}: {
  theme: Theme;
  colors: Colors;
  selected: boolean;
  onPress: () => void;
}) {
  // Plain React Native text: the app's <Text> would use the *current* theme's colours.
  const ink = (color: string, variant: keyof typeof typeScale = 'body') => [
    typeScale[variant],
    { color },
  ];
  return (
    <Pressable
      role="radio"
      aria-checked={selected}
      accessibilityLabel={`${theme.name} theme`}
      onPress={onPress}
      testID={`theme-${theme.id}`}
      style={[
        styles.preview,
        { backgroundColor: c.background, borderColor: selected ? c.accent : c.border },
        selected ? styles.previewSelected : null,
      ]}
    >
      <View style={styles.previewHeader}>
        <RNText style={ink(c.text, 'title')}>{theme.name}</RNText>
        {selected ? <Ionicons name="checkmark-circle" size={24} color={c.accent} /> : null}
      </View>
      <RNText style={ink(c.textMuted, 'caption')}>{theme.description}</RNText>
      <View style={[styles.card, { backgroundColor: c.surface, borderColor: c.border }]}>
        <View style={[styles.dateTile, { backgroundColor: c.accentSoft }]}>
          <RNText style={[ink(c.accent, 'label'), styles.center]}>FRI</RNText>
          <RNText style={[ink(c.text, 'title'), styles.center]}>17</RNText>
        </View>
        <View style={styles.cardText}>
          <RNText style={ink(c.text)} numberOfLines={1}>
            Pale Kites
          </RNText>
          <RNText style={ink(c.textMuted, 'caption')} numberOfLines={1}>
            The Snug, Manchester · 1.2 km
          </RNText>
          <View style={styles.row}>
            <View style={[styles.pill, { backgroundColor: c.accent2Soft }]}>
              <RNText style={ink(c.text, 'label')}>GOING</RNText>
            </View>
            <Ionicons name="heart" size={16} color={c.accent2} />
            <RNText style={ink(c.textMuted, 'caption')}>128</RNText>
          </View>
        </View>
      </View>
      <View style={styles.row}>
        <View style={[styles.button, { backgroundColor: c.accent }]}>
          <RNText style={[ink(c.onAccent, 'caption'), styles.bold]}>Post</RNText>
        </View>
        <View style={[styles.button, { backgroundColor: c.surfaceRaised }]}>
          <RNText style={[ink(c.text, 'caption'), styles.bold]}>Cancel</RNText>
        </View>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.sm, paddingBottom: spacing.xl },
  modes: { flexDirection: 'row' },
  heading: { marginTop: spacing.md },
  preview: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md, gap: spacing.sm },
  previewSelected: { borderWidth: 3 },
  previewHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  card: {
    flexDirection: 'row',
    gap: spacing.sm,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    padding: spacing.sm,
  },
  dateTile: {
    width: 52,
    borderRadius: radius.sm,
    paddingVertical: spacing.xs,
    justifyContent: 'center',
  },
  center: { textAlign: 'center' },
  cardText: { flex: 1, gap: 2 },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  pill: { borderRadius: radius.pill, paddingHorizontal: spacing.sm, paddingVertical: 2 },
  button: { borderRadius: radius.pill, paddingHorizontal: spacing.md, paddingVertical: spacing.sm },
  bold: { fontWeight: '700' },
});
