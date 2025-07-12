import { ScrollView, StyleSheet, View } from 'react-native';

import { Chip } from '@/components/ui/Chip';
import { Screen } from '@/components/ui/Screen';
import { Text } from '@/components/ui/Text';
import { Wordmark } from '@/components/ui/Wordmark';
import { useThemePrefs, type FeedStyle, type ThemeMode } from '@/theme/themePrefs';
import { spacing } from '@/theme/tokens';
import type { TypeStyle } from '@/theme/typography';

const MODES: { mode: ThemeMode; label: string; detail: string }[] = [
  { mode: 'auto', label: 'Auto', detail: "Follows the phone's dark or light setting." },
  { mode: 'dark', label: 'Dark', detail: 'Always dark.' },
  { mode: 'light', label: 'Light', detail: 'Always light.' },
];

const TYPES: { style: TypeStyle; label: string; detail: string }[] = [
  {
    style: 'grotesk',
    label: 'Grotesk',
    detail: 'Headings in Space Grotesk, text in Inter.',
  },
  { style: 'system', label: 'System', detail: "The phone's own font everywhere." },
];

const FEED_STYLES: { style: FeedStyle; label: string; detail: string }[] = [
  {
    style: 'cards',
    label: 'Cards',
    detail: 'Each post a rounded card with a gap before the next, the gig on a ticket at the top.',
  },
  {
    style: 'immersive',
    label: 'Full screen',
    detail: 'The clip edge to edge, with the gig in the top corner.',
  },
  {
    style: 'ticket',
    label: 'Ticket',
    detail: 'The gig on a ticket stub above a rounded clip.',
  },
];

/**
 * Settings → Appearance: dark, light or the phone's setting; the typeface; the accent glow at the top
 * of each screen (the look is chosen by trying it); and how the home feed draws a post. The colours
 * are Electric: chosen from five themes, and the others were removed.
 */
export function AppearanceScreen() {
  const mode = useThemePrefs((state) => state.mode);
  const setMode = useThemePrefs((state) => state.setMode);
  const typeStyle = useThemePrefs((state) => state.typeStyle);
  const setTypeStyle = useThemePrefs((state) => state.setTypeStyle);
  const glow = useThemePrefs((state) => state.glow);
  const setGlow = useThemePrefs((state) => state.setGlow);
  const feedStyle = useThemePrefs((state) => state.feedStyle);
  const setFeedStyle = useThemePrefs((state) => state.setFeedStyle);

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.preview}>
          <Wordmark />
          <Text variant="title">Gigs this weekend</Text>
          <Text muted>
            Pale Kites at The Snug, Friday 20:00. A preview of the type and colours you choose.
          </Text>
        </View>

        <Text variant="label" muted>
          MODE
        </Text>
        <View style={styles.row} accessibilityRole="radiogroup">
          {MODES.map((m) => (
            <Chip
              key={m.mode}
              label={m.label}
              selected={mode === m.mode}
              onPress={() => setMode(m.mode)}
            />
          ))}
        </View>
        <Text variant="caption" muted>
          {MODES.find((m) => m.mode === mode)?.detail}
        </Text>

        <Text variant="label" muted style={styles.heading}>
          TYPE
        </Text>
        <View style={styles.row} accessibilityRole="radiogroup">
          {TYPES.map((t) => (
            <Chip
              key={t.style}
              label={t.label}
              selected={typeStyle === t.style}
              onPress={() => setTypeStyle(t.style)}
            />
          ))}
        </View>
        <Text variant="caption" muted>
          {TYPES.find((t) => t.style === typeStyle)?.detail}
        </Text>

        <Text variant="label" muted style={styles.heading}>
          GLOW
        </Text>
        <View style={styles.row} accessibilityRole="radiogroup">
          <Chip label="On" selected={glow} onPress={() => setGlow(true)} />
          <Chip label="Off" selected={!glow} onPress={() => setGlow(false)} />
        </View>
        <Text variant="caption" muted>
          {glow
            ? 'A soft purple light fades down from the top of each screen.'
            : 'Plain backgrounds.'}
        </Text>

        <Text variant="label" muted style={styles.heading}>
          FEED
        </Text>
        <View style={styles.row} accessibilityRole="radiogroup">
          {FEED_STYLES.map((f) => (
            <Chip
              key={f.style}
              label={f.label}
              selected={feedStyle === f.style}
              onPress={() => setFeedStyle(f.style)}
            />
          ))}
        </View>
        <Text variant="caption" muted>
          {FEED_STYLES.find((f) => f.style === feedStyle)?.detail}
        </Text>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: spacing.sm, paddingBottom: spacing.xl },
  preview: { gap: spacing.sm, paddingVertical: spacing.md },
  row: { flexDirection: 'row' },
  heading: { marginTop: spacing.md },
});
