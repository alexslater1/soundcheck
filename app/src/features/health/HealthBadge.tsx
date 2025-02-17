import { ActivityIndicator, StyleSheet, View } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { useHealth } from './useHealth';

/** Shows the API health fetched through the edge — a demo check ("API: UP"). */
export function HealthBadge() {
  const { colors } = useTheme();
  const { data, isPending, isError, error, refetch } = useHealth();

  let content: React.ReactNode;
  if (isPending) {
    content = (
      <View style={styles.row}>
        <ActivityIndicator color={colors.textMuted} />
        <Text muted>Checking API…</Text>
      </View>
    );
  } else if (isError) {
    content = (
      <View>
        <Text style={{ color: colors.danger }}>API: unreachable</Text>
        <Text variant="caption" muted onPress={() => void refetch()} accessibilityRole="button">
          {error.message} — tap to retry
        </Text>
      </View>
    );
  } else {
    content = (
      <View>
        <Text style={{ color: colors.success }} testID="health-status">
          API: {data.status}
        </Text>
        <Text variant="caption" muted>
          v{data.version} · {data.time}
        </Text>
      </View>
    );
  }

  return (
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      {content}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { padding: spacing.md, borderRadius: radius.md, borderWidth: StyleSheet.hairlineWidth },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
});
