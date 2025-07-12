import { useEffect, useState } from 'react';
import { Animated, Easing, Platform, StyleSheet, View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';

/** The five bars of the mark (see `Wordmark`), as a share of the size. */
const BARS = [0.45, 0.85, 0.6, 1, 0.7];
/** How far each bar drops while it dances, and how long one beat takes: never in step. */
const LOW = [0.35, 0.5, 0.25, 0.55, 0.3];
const BEAT_MS = [300, 380, 260, 420, 340];

/**
 * The mark's five level-meter bars, dancing while `animating` (the feed's refresh) and still
 * otherwise. Each bar scales on the native driver, so the animation keeps running smoothly while
 * JavaScript is busy loading the new feed.
 */
export function EqualizerBars({ size = 36, animating }: { size?: number; animating: boolean }) {
  const { colors } = useTheme();
  const [levels] = useState(() => BARS.map(() => new Animated.Value(1)));
  const bar = Math.max(4, Math.round(size / 6));

  useEffect(() => {
    if (!animating) {
      levels.forEach((level) => level.setValue(1));
      return;
    }
    const native = Platform.OS !== 'web';
    const loops = levels.map((level, i) =>
      Animated.loop(
        Animated.sequence([
          Animated.timing(level, {
            toValue: LOW[i] ?? 0.4,
            duration: BEAT_MS[i] ?? 320,
            easing: Easing.inOut(Easing.quad),
            useNativeDriver: native,
          }),
          Animated.timing(level, {
            toValue: 1,
            duration: BEAT_MS[i] ?? 320,
            easing: Easing.inOut(Easing.quad),
            useNativeDriver: native,
          }),
        ]),
      ),
    );
    loops.forEach((loop) => loop.start());
    return () => loops.forEach((loop) => loop.stop());
  }, [animating, levels]);

  return (
    <View
      style={[styles.row, { height: size, gap: Math.max(3, bar / 2) }]}
      testID={animating ? 'equalizer-animating' : 'equalizer'}
    >
      {levels.map((level, i) => (
        <Animated.View
          key={i}
          style={{
            width: bar,
            height: size * (BARS[i] ?? 1),
            borderRadius: bar / 2,
            backgroundColor: i % 2 === 0 ? colors.accent : colors.accent2,
            transform: [{ scaleY: level }],
          }}
        />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center' },
});
