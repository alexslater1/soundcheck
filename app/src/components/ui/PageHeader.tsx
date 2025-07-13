import Ionicons from '@expo/vector-icons/Ionicons';
import type { ReactNode } from 'react';
import { Platform, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useTheme } from '@/theme/ThemeProvider';
import { spacing, touchTarget, type Colors } from '@/theme/tokens';

import { Text } from './Text';

/**
 * Height of the header's bar below the status bar. It matches React Navigation's default for the
 * platform, which the stack reports as the header height until it has measured this one.
 */
export const PAGE_HEADER_HEIGHT = Platform.select({ ios: 44, web: 64, default: 56 });

/** The part of React Navigation's header props this header uses (native stack and web stack alike). */
export type PageHeaderProps = {
  /** Set when there is a screen to go back to. */
  back?: { title?: string | undefined } | undefined;
  options: {
    title?: string | undefined;
    headerRight?: ((props: { tintColor?: string; canGoBack: boolean }) => ReactNode) | undefined;
  };
  navigation: { goBack: () => void };
};

/**
 * The header of every pushed screen: part of the page, not a grey bar. It is
 * transparent, so the page's background and glow run behind it, with a back chevron when there is
 * somewhere to go back to and the title in the display face. `Screen` leaves room for it, and the
 * content scrolls up to its edge. An empty title shows the chevron alone, for pages whose own heading
 * says it (sign-up, the follower lists).
 */
export function PageHeader({ back, options, navigation }: PageHeaderProps) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const right = options.headerRight?.({ tintColor: colors.text, canGoBack: !!back });
  return (
    <View style={{ paddingTop: insets.top }} testID="page-header">
      <View style={[styles.bar, { height: PAGE_HEADER_HEIGHT }, back ? null : styles.noBack]}>
        {back ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Back"
            onPress={() => navigation.goBack()}
            hitSlop={8}
            style={styles.back}
          >
            <Ionicons name="chevron-back" size={26} color={colors.text} />
          </Pressable>
        ) : null}
        <Text variant="title" numberOfLines={1} accessibilityRole="header" style={styles.title}>
          {options.title ?? ''}
        </Text>
        {right ? <View style={styles.right}>{right}</View> : null}
      </View>
    </View>
  );
}

/**
 * Screen options for every `Stack` in the app: the page header above, transparent, over the theme's
 * background. A screen that wants no header at all (sign-in) sets `headerShown: false`.
 */
export function pageStackOptions(colors: Colors) {
  return {
    header: (props: PageHeaderProps) => <PageHeader {...props} />,
    headerTransparent: true,
    contentStyle: { backgroundColor: colors.background },
  };
}

const styles = StyleSheet.create({
  // The same centred column as Screen's, so the chevron and title line up with the content on wide web.
  bar: {
    width: '100%',
    maxWidth: 640,
    alignSelf: 'center',
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.xs,
    gap: spacing.xs,
  },
  // Without a chevron the title starts where the content does.
  noBack: { paddingLeft: spacing.md },
  back: {
    width: touchTarget,
    height: touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: { flex: 1 },
  right: { flexDirection: 'row', alignItems: 'center', paddingRight: spacing.xs },
});
