import { StyleSheet, View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';

import { Text } from './Text';

/** Bar heights of the mark, as a share of its size: a level meter caught mid-song. */
const BARS = [0.45, 0.85, 0.6, 1, 0.7];

/**
 * The Soundcheck mark and name: five meter bars in the two accent colours beside the name
 * in the display face. Drawn with views, so it needs no image and follows the theme.
 */
export function Wordmark({ size = 26 }: { size?: number }) {
  const { colors } = useTheme();
  const bar = Math.max(3, Math.round(size / 7));
  return (
    <View style={styles.row} accessibilityRole="header" accessibilityLabel="Soundcheck">
      <View style={[styles.mark, { height: size, gap: Math.max(2, bar / 2) }]}>
        {BARS.map((height, i) => (
          <View
            key={i}
            style={{
              width: bar,
              height: size * height,
              borderRadius: bar / 2,
              backgroundColor: i % 2 === 0 ? colors.accent : colors.accent2,
            }}
          />
        ))}
      </View>
      <Text
        variant="title"
        style={{ fontSize: size, lineHeight: size * 1.2, fontWeight: '700', letterSpacing: -0.5 }}
      >
        Soundcheck
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  mark: { flexDirection: 'row', alignItems: 'flex-end' },
});
