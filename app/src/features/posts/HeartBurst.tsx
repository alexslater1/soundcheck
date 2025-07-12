import Ionicons from '@expo/vector-icons/Ionicons';
import { useEffect, useRef, useState } from 'react';
import { Animated, Easing, Platform } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';

const SIZE = 96;
const DURATION_MS = 750;

/**
 * The heart that pops where a clip was double-tapped: it swells past full size, settles,
 * floats up a little and fades. Plain React Native `Animated` on the native driver, so it needs no
 * gesture or animation library (and no new native module).
 */
export function HeartBurst({ x, y, onDone }: { x: number; y: number; onDone: () => void }) {
  const { colors } = useTheme();
  const [progress] = useState(() => new Animated.Value(0));
  // The animation runs once per heart, whatever the parent passes on later renders.
  const done = useRef(onDone);
  useEffect(() => {
    done.current = onDone;
  });

  useEffect(() => {
    const animation = Animated.timing(progress, {
      toValue: 1,
      duration: DURATION_MS,
      easing: Easing.out(Easing.cubic),
      // The web has no native driver; there it runs in JavaScript.
      useNativeDriver: Platform.OS !== 'web',
    });
    animation.start(({ finished }) => {
      if (finished) {
        done.current();
      }
    });
    return () => animation.stop();
  }, [progress]);

  const scale = progress.interpolate({
    inputRange: [0, 0.2, 0.35, 1],
    outputRange: [0.3, 1.2, 1, 1],
  });
  const opacity = progress.interpolate({ inputRange: [0, 0.6, 1], outputRange: [1, 1, 0] });
  const rise = progress.interpolate({ inputRange: [0, 0.6, 1], outputRange: [0, 0, -48] });

  return (
    <Animated.View
      pointerEvents="none"
      testID="heart-burst"
      style={{
        position: 'absolute',
        left: (Number.isFinite(x) ? x : 0) - SIZE / 2,
        top: (Number.isFinite(y) ? y : 0) - SIZE / 2,
        opacity,
        transform: [{ translateY: rise }, { scale }, { rotate: '-8deg' }],
      }}
    >
      <Ionicons name="heart" size={SIZE} color={colors.accent} />
    </Animated.View>
  );
}
