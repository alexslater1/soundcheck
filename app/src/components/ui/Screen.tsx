import type { PropsWithChildren } from 'react';
import { StyleSheet, View, type ViewProps } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

/** Full-screen container: safe area, themed background, a centred column on wide web. */
export function Screen({ children, style, ...props }: PropsWithChildren<ViewProps>) {
  const { colors } = useTheme();
  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      <View {...props} style={[styles.column, style]}>
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
});
