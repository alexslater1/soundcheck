import { ActivityIndicator, StyleSheet, View } from 'react-native';

import { useHealth } from '@/api/generated/health/health';
import type { HealthResponse } from '@/api/generated/model';
import type { ApiError } from '@/api/http';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

/** Shows the API health fetched through the edge with the orval-generated hook — a demo check. */
export function HealthBadge() {
  const { colors } = useTheme();
  const { data, isPending, isError, error, refetch } = useHealth<HealthResponse, ApiError>({
    query: { refetchInterval: 15_000 },
  });

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
