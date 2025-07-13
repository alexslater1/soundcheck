import { HeaderHeightContext } from '@react-navigation/elements';
import { LinearGradient } from 'expo-linear-gradient';
import { use, type PropsWithChildren } from 'react';
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
 * With the glow on (Settings → Appearance) a soft wash of the accent colour fades down from
 * the top, so screens have depth instead of a flat black.
 *
 * Under a page header (`PageHeader`) the screen starts at the top of the window,
 * behind the transparent header, so the glow runs behind it too. The column then starts at the header's
 * measured bottom edge instead of 16 pt below it, so content scrolls right up to the header.
 */
export function Screen({
  children,
  style,
  wide = false,
  keyboard = false,
  ...props
}: PropsWithChildren<ViewProps & { wide?: boolean; keyboard?: boolean }>) {
  const { colors, glow } = useTheme();
  // The stack's header height: 0 on the tabs and on screens without a header. Every header in the app
  // is a transparent PageHeader (pageStackOptions), so a height here is room to leave at the top.
  const headerHeight = use(HeaderHeightContext) ?? 0;
  const column = (
    <View
      {...props}
      style={[
        styles.column,
        wide ? styles.wide : null,
        style,
        // Last, so a screen's own top padding can never slide its content under the header.
        headerHeight > 0 ? { paddingTop: headerHeight } : null,
      ]}
    >
      {children}
    </View>
  );
  return (
    <SafeAreaView
      // The header already keeps clear of the status bar.
      edges={headerHeight > 0 ? ['right', 'bottom', 'left'] : undefined}
      style={[styles.safe, { backgroundColor: colors.background }]}
    >
      {glow ? (
        <LinearGradient
          pointerEvents="none"
          colors={[`${colors.accent}40`, `${colors.accent}12`, 'transparent']}
          locations={[0, 0.45, 1]}
          style={styles.glow}
          testID="screen-glow"
        />
      ) : null}
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
  glow: { position: 'absolute', top: 0, left: 0, right: 0, height: 320 },
  column: {
    flex: 1,
    width: '100%',
    maxWidth: 640,
    alignSelf: 'center',
    padding: spacing.md,
  },
  wide: { maxWidth: 1100 },
});
