import { Image } from 'expo-image';
import { StyleSheet, View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius } from '@/theme/tokens';

/**
 * A picked photo shown straight from the device, in the same frame as a picked clip's preview
 * (`ClipPreview`) so the row of previews lines up. Nothing goes over the network.
 */
export function PhotoPreview({ uri, testID }: { uri: string; testID?: string }) {
  const { colors } = useTheme();
  return (
    <View
      style={[styles.frame, { backgroundColor: colors.surfaceRaised }]}
      accessibilityLabel="Photo"
      testID={testID}
    >
      <Image
        source={{ uri }}
        contentFit="cover"
        style={StyleSheet.absoluteFill}
        accessibilityIgnoresInvertColors
      />
    </View>
  );
}

const styles = StyleSheet.create({
  frame: { width: '100%', aspectRatio: 9 / 16, borderRadius: radius.md, overflow: 'hidden' },
});
