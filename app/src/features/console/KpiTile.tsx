import { StyleSheet, View } from 'react-native';

import { Skeleton } from '@/components/ui/Skeleton';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { Sparkline } from './charts/Chart';

/**
 * One headline number with its change on the previous period and an optional sparkline.
 * `delta` is a fraction (0.12 = +12 %); `goodWhen` says which direction is good, so a rising failure
 * rate reads as bad. The direction is spelled out (▲/▼ and the sign), never colour alone.
 */
export function KpiTile({
  label,
  value,
  delta,
  goodWhen = 'up',
  trend,
  loading = false,
  testID,
}: {
  label: string;
  value: string;
  delta?: number | null;
  goodWhen?: 'up' | 'down';
  trend?: number[];
  loading?: boolean;
  testID?: string;
}) {
  const { colors } = useTheme();
  return (
    <View
      testID={testID}
      style={[styles.tile, { backgroundColor: colors.surface, borderColor: colors.border }]}
    >
      <Text variant="label" muted>
        {label.toUpperCase()}
      </Text>
      {loading ? (
        <Skeleton height={34} width="60%" />
      ) : (
        <>
          <Text variant="display">{value}</Text>
          {delta === undefined || delta === null ? null : (
            <Text
              variant="caption"
              style={{
                color:
                  deltaTone(delta, goodWhen) === 'good'
                    ? colors.success
                    : deltaTone(delta, goodWhen) === 'bad'
                      ? colors.danger
                      : colors.textMuted,
              }}
            >
              {formatDelta(delta)} vs previous period
            </Text>
          )}
          {trend && trend.length > 1 ? <Sparkline values={trend} label={label} /> : null}
        </>
      )}
    </View>
  );
}

/** `▲ 12 %`, `▼ 3.5 %`, `± 0 %`. */
export function formatDelta(delta: number): string {
  const pct = Math.round(Math.abs(delta) * 1000) / 10;
  if (pct === 0) return '± 0 %';
  return `${delta > 0 ? '▲ +' : '▼ −'}${pct} %`;
}

export function deltaTone(delta: number, goodWhen: 'up' | 'down'): 'good' | 'bad' | 'flat' {
  if (Math.round(Math.abs(delta) * 1000) === 0) return 'flat';
  return delta > 0 === (goodWhen === 'up') ? 'good' : 'bad';
}

const styles = StyleSheet.create({
  tile: {
    flexGrow: 1,
    flexBasis: 200,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.xs,
  },
});
