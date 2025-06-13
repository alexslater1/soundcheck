import { useNetworkState, type NetworkState } from 'expo-network';
import { useEffect, useRef } from 'react';
import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { queryClient } from '@/api/queryClient';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing } from '@/theme/tokens';

/**
 * Offline only when the phone says so: no network at all, or a network without internet. A state not
 * known yet (undefined at start-up) is not offline, so the banner never flashes on launch.
 */
export function isOffline(state: NetworkState): boolean {
  return state.isConnected === false || state.isInternetReachable === false;
}

/**
 * The offline banner: a note at the top of every screen while there is no connection, over the
 * content and never in the way of a tap. Screens keep what they had loaded. When the connection
 * comes back, the queries on screen are fetched again.
 *
 * Only a note: TanStack Query's own online switch is left alone, because a phone on `adb reverse` with
 * no Wi-Fi reaches the local API while reporting no internet, and pausing every request there would
 * stop the app in development.
 */
export function OfflineBanner() {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const offline = isOffline(useNetworkState());
  const wasOffline = useRef(false);

  useEffect(() => {
    if (offline) {
      wasOffline.current = true;
    } else if (wasOffline.current) {
      wasOffline.current = false;
      void queryClient.invalidateQueries({ refetchType: 'active' });
    }
  }, [offline]);

  if (!offline) {
    return null;
  }
  return (
    <View
      pointerEvents="none"
      style={[styles.wrap, { top: insets.top + spacing.xs }]}
      testID="offline-banner"
    >
      <View
        accessibilityRole="alert"
        accessibilityLiveRegion="polite"
        style={[
          styles.banner,
          { backgroundColor: colors.surfaceRaised, borderColor: colors.warning },
        ]}
      >
        <Text variant="caption" style={{ color: colors.text }}>
          No connection. You are seeing what was already loaded.
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: 0, right: 0, alignItems: 'center' },
  banner: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
  },
});
