import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

import { ChartCanvas } from './ChartCanvas';
import { chartPalette } from './palette';
import type { ChartSpec } from './chartTypes';
import { summarise, tableOf } from './summary';

/**
 * A titled chart with its text summary (the `aria-label`) and a "Show table" toggle, so no number
 * is ever shown by colour or shape alone. The frame is React Native; the drawing is behind the
 * platform boundary (`ChartCanvas`), which gets the measured width and plain data.
 */
export function Chart({
  title,
  spec,
  height = 220,
  testID,
}: {
  title: string;
  spec: ChartSpec;
  height?: number;
  testID?: string;
}) {
  const { colors, scheme } = useTheme();
  const [width, setWidth] = useState(0);
  const [asTable, setAsTable] = useState(false);
  const label = summarise(spec, title);
  return (
    <View
      testID={testID}
      style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}
    >
      <View style={styles.header}>
        <Text style={styles.title}>{title}</Text>
        <Pressable
          accessibilityRole="button"
          onPress={() => setAsTable((t) => !t)}
          testID={testID ? `${testID}-toggle` : undefined}
        >
          <Text variant="caption" muted>
            {asTable ? 'Show chart' : 'Show table'}
          </Text>
        </Pressable>
      </View>
      {asTable ? (
        <ChartTable spec={spec} />
      ) : (
        <View
          accessible
          accessibilityLabel={label}
          style={{ height }}
          onLayout={(e) => setWidth(Math.floor(e.nativeEvent.layout.width))}
        >
          <ChartCanvas
            spec={spec}
            palette={chartPalette(colors, scheme)}
            width={width}
            height={height}
            label={label}
          />
        </View>
      )}
    </View>
  );
}

function ChartTable({ spec }: { spec: ChartSpec }) {
  const { colors } = useTheme();
  const { headers, rows } = tableOf(spec);
  return (
    <View accessibilityRole="list">
      <View style={[styles.row, { borderBottomColor: colors.border }]}>
        {headers.map((h, i) => (
          <Text key={`${h}-${i}`} variant="label" muted style={styles.cell}>
            {h}
          </Text>
        ))}
      </View>
      {rows.map((row, r) => (
        <View key={r} style={[styles.row, { borderBottomColor: colors.border }]}>
          {row.map((cell, c) => (
            <Text key={c} variant="caption" style={styles.cell}>
              {cell}
            </Text>
          ))}
        </View>
      ))}
    </View>
  );
}

/** A small trend line for a KPI tile: no axes, no table toggle; the tile's text carries the numbers. */
export function Sparkline({ values, label }: { values: number[]; label: string }) {
  const { colors, scheme } = useTheme();
  const [width, setWidth] = useState(0);
  const spec: ChartSpec = { kind: 'sparkline', values };
  const summary = summarise(spec, label);
  return (
    <View
      accessible
      accessibilityLabel={summary}
      style={styles.spark}
      onLayout={(e) => setWidth(Math.floor(e.nativeEvent.layout.width))}
    >
      <ChartCanvas
        spec={spec}
        palette={chartPalette(colors, scheme)}
        width={width}
        height={32}
        label={summary}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  title: { fontWeight: '600' },
  row: { flexDirection: 'row', borderBottomWidth: StyleSheet.hairlineWidth, paddingVertical: 4 },
  cell: { flex: 1 },
  spark: { height: 32 },
});
