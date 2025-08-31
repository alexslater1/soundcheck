import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { RankingPreviewItem } from '@/api/generated/model';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { Chart } from '../charts/Chart';
import type { WaterfallSpec } from '../charts/chartTypes';

/**
 * How a score is made: the bias, plus each feature's weight × value, gives the logit; the logistic
 * of the logit is the probability the feed ranks by. Features that add nothing are left out of the
 * chart (they are in the table).
 */
export function waterfallOf(item: RankingPreviewItem): WaterfallSpec {
  return {
    kind: 'waterfall',
    start: { label: 'bias', value: item.bias },
    steps: item.contributions
      .filter((c) => c.contribution !== 0)
      .map((c) => ({ label: c.feature, value: c.contribution })),
    totalLabel: 'logit',
  };
}

export function WaterfallDrawer({
  item,
  configLabel,
  onClose,
}: {
  item: RankingPreviewItem;
  configLabel: string;
  onClose: () => void;
}) {
  const { colors } = useTheme();
  return (
    <View
      style={[styles.drawer, { backgroundColor: colors.surface, borderColor: colors.border }]}
      testID="waterfall-drawer"
    >
      <View style={styles.header}>
        <View style={styles.title}>
          <Text variant="title">Why rank {item.rank}?</Text>
          <Text variant="caption" muted>
            @{item.post.author.handle} · {configLabel}
          </Text>
        </View>
        <Pressable accessibilityRole="button" onPress={onClose} testID="waterfall-close">
          <Text style={{ color: colors.accent }}>Close</Text>
        </Pressable>
      </View>
      <ScrollView contentContainerStyle={styles.body}>
        <Chart title="Bias + each weight × feature = logit" spec={waterfallOf(item)} height={260} />
        <Text testID="waterfall-total">
          logit {item.logit.toFixed(3)} → probability σ(logit) = {item.score.toFixed(4)}
        </Text>
        {item.exploration ? (
          <Text variant="caption" style={{ color: colors.accent2 }}>
            Placed here by exploration, not by its score.
          </Text>
        ) : null}
        <Text variant="caption" muted>
          Sources: {item.sources.join(', ') || '—'}
        </Text>
        <View>
          <View style={[styles.row, { borderBottomColor: colors.border }]}>
            {['Feature', 'Value', 'Weight', 'w × f'].map((h) => (
              <Text key={h} variant="label" muted style={styles.cell}>
                {h.toUpperCase()}
              </Text>
            ))}
          </View>
          {item.contributions.map((c) => (
            <View key={c.feature} style={[styles.row, { borderBottomColor: colors.border }]}>
              <Text variant="caption" style={styles.cell}>
                {c.feature}
              </Text>
              <Text variant="caption" style={styles.cell}>
                {c.value.toFixed(3)}
              </Text>
              <Text variant="caption" style={styles.cell}>
                {c.weight.toFixed(2)}
              </Text>
              <Text
                variant="caption"
                style={[
                  styles.cell,
                  c.contribution > 0
                    ? { color: colors.success }
                    : c.contribution < 0
                      ? { color: colors.danger }
                      : null,
                ]}
              >
                {c.contribution.toFixed(3)}
              </Text>
            </View>
          ))}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  drawer: {
    width: 420,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.sm,
    alignSelf: 'flex-start',
  },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' },
  title: { flex: 1 },
  body: { gap: spacing.sm },
  row: { flexDirection: 'row', borderBottomWidth: StyleSheet.hairlineWidth, paddingVertical: 3 },
  cell: { flex: 1 },
});
