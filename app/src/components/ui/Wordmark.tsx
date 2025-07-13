import { Image, StyleSheet, View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';

import markDark from '../../../assets/mark.png';
import markLight from '../../../assets/mark-light.png';

import { Text } from './Text';

/**
 * The Soundcheck mark and name: the mic-check mark (a tick whose long arm is a microphone) beside
 * the name in the display face. The mark is the same drawing as the app icon, rendered by
 * `ops/scripts/make_icons.py` in each palette's accents, so the light theme gets its darker violet and
 * cyan. Screen readers hear one heading, "Soundcheck".
 */
export function Wordmark({ size = 26 }: { size?: number }) {
  const { scheme } = useTheme();
  const mark = Math.round(size * 1.15);
  return (
    <View style={styles.row} accessible accessibilityRole="header" accessibilityLabel="Soundcheck">
      <Image
        source={scheme === 'dark' ? markDark : markLight}
        style={{ width: mark, height: mark }}
        accessible={false}
        testID="wordmark-mark"
      />
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
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
});
