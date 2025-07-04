import Ionicons from '@expo/vector-icons/Ionicons';
import { StyleSheet, View } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

/**
 * Why a venue's list here may be incomplete: Soundcheck does not read its own listings (a
 * ticketing widget, a list copied from elsewhere, no readable page), so its gigs come from Ticketmaster
 * and fans. The text comes from the API (`coverageNote`); nothing renders for every other venue. On the
 * venue page it is a card; under a gig's venue line, a quiet caption.
 */
export function CoverageNote({
  note,
  compact = false,
}: {
  note?: string | null;
  compact?: boolean;
}) {
  const { colors } = useTheme();
  if (!note) {
    return null;
  }
  if (compact) {
    return (
      <Text variant="caption" muted testID="coverage-note">
        {note}
      </Text>
    );
  }
  return (
    <View
      testID="coverage-note"
      accessibilityRole="text"
      style={[styles.card, { borderColor: colors.border, backgroundColor: colors.surface }]}
    >
      <Ionicons name="information-circle-outline" size={18} color={colors.textMuted} />
      <Text variant="caption" muted style={styles.text}>
        {note}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    gap: spacing.sm,
    alignItems: 'flex-start',
    padding: spacing.md,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
  },
  text: { flex: 1 },
});
