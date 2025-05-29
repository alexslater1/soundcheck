import type { PropsWithChildren } from 'react';
import { StyleSheet, View, type ViewProps } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

/**
 * Full-screen container: safe area, themed background, a centred column on wide web.
 * `wide` lets the content use a wider column: the home feed puts a details panel beside the clip.
 */
export function Screen({
  children,
  style,
  wide = false,
  ...props
}: PropsWithChildren<ViewProps & { wide?: boolean }>) {
  const { colors } = useTheme();
  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      <View {...props} style={[styles.column, wide ? styles.wide : null, style]}>
        {children}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  column: {
    flex: 1,
    width: '100%',
    maxWidth: 640,
    alignSelf: 'center',
    padding: spacing.md,
  },
  wide: { maxWidth: 1100 },
});
