import { LinearGradient } from 'expo-linear-gradient';
import { StyleSheet } from 'react-native';

/**
 * A gradient from clear to dark over the foot of a clip, so the feed's white text stays readable on a
 * bright frame without a box over the clip. The player draws it under its own controls: drawn over
 * the whole card it also covered the seek bar, which was then all but invisible in the feed
 * (2025-08-06).
 */
export function ClipShade({ testID }: { testID?: string }) {
  return (
    <LinearGradient
      pointerEvents="none"
      colors={['transparent', 'rgba(0,0,0,0.55)', 'rgba(0,0,0,0.85)']}
      locations={[0, 0.55, 1]}
      style={styles.shade}
      testID={testID}
    />
  );
}

const styles = StyleSheet.create({
  shade: { position: 'absolute', left: 0, right: 0, bottom: 0, height: '45%', maxHeight: 360 },
});
