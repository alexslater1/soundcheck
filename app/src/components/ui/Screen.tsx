import type { PropsWithChildren } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, View, type ViewProps } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useTheme } from '@/theme/ThemeProvider';
import { spacing } from '@/theme/tokens';

/**
 * Full-screen container: safe area, themed background, a centred column on wide web.
 * `wide` lets the content use a wider column: the home feed puts a details panel beside the clip.
 * `keyboard` keeps text boxes above the on-screen keyboard. Android draws edge to edge (SDK 53:
 * `android.edgeToEdgeEnabled` in app.config.ts), so the system no longer shrinks the window when the
 * keyboard opens; the screen must make room itself.
 */
export function Screen({
  children,
  style,
  wide = false,
  keyboard = false,
  ...props
}: PropsWithChildren<ViewProps & { wide?: boolean; keyboard?: boolean }>) {
  const { colors } = useTheme();
  const column = (
    <View {...props} style={[styles.column, wide ? styles.wide : null, style]}>
      {children}
    </View>
  );
  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      {keyboard && Platform.OS !== 'web' ? (
        <KeyboardAvoidingView style={styles.safe} behavior="padding">
          {column}
        </KeyboardAvoidingView>
      ) : (
        column
      )}
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
